use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, PoisonError, RwLock};

use sqlx::SqlitePool;
use tokio::sync::{mpsc, watch};

use super::events::ServerEvent;

pub type Frame = Arc<str>;

/// Bounded so one slow phone fills only its own queue and is dropped, never the others.
const CONNECTION_QUEUE: usize = 64;

pub fn channel() -> (mpsc::Sender<Frame>, mpsc::Receiver<Frame>) {
    mpsc::channel(CONNECTION_QUEUE)
}

struct Connection {
    id: u64,
    tx: mpsc::Sender<Frame>,
    stop: watch::Sender<bool>,
}

/// Open WebSocket connections keyed by user id (one user may have a phone and a laptop).
#[derive(Default)]
pub struct Broker {
    registry: RwLock<HashMap<String, Vec<Connection>>>,
    next_id: AtomicU64,
}

impl Broker {
    pub fn register(&self, user_id: &str, tx: mpsc::Sender<Frame>) -> u64 {
        self.register_with_stop(user_id, tx).0
    }

    pub(super) fn register_with_stop(&self, user_id: &str, tx: mpsc::Sender<Frame>) -> (u64, watch::Receiver<bool>) {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (stop, stopped) = watch::channel(false);
        let mut registry = self.registry.write().unwrap_or_else(PoisonError::into_inner);
        registry.entry(user_id.to_owned()).or_default().push(Connection { id, tx, stop });
        (id, stopped)
    }

    pub fn unregister(&self, user_id: &str, connection_id: u64) {
        let mut registry = self.registry.write().unwrap_or_else(PoisonError::into_inner);
        if let Some(connections) = registry.get_mut(user_id) {
            connections.retain(|c| c.id != connection_id);
            if connections.is_empty() {
                registry.remove(user_id);
            }
        }
    }

    pub fn connection_count(&self) -> usize {
        self.registry.read().unwrap_or_else(PoisonError::into_inner).values().map(Vec::len).sum()
    }

    pub fn is_online(&self, user_id: &str) -> bool {
        self.registry.read().unwrap_or_else(PoisonError::into_inner).contains_key(user_id)
    }

    /// Never waits on a socket. Full queues terminate their connection task, including its reader.
    pub fn send_to_user(&self, user_id: &str, event: &ServerEvent) {
        let frame = Frame::from(event.to_frame());
        let mut registry = self.registry.write().unwrap_or_else(PoisonError::into_inner);
        if let Some(connections) = registry.get_mut(user_id) {
            connections.retain(|connection| {
                if connection.tx.try_send(frame.clone()).is_ok() {
                    true
                } else {
                    let _ = connection.stop.send(true);
                    false
                }
            });
            if connections.is_empty() {
                registry.remove(user_id);
            }
        }
    }

    /// Resolves recipients from committed enrollments, so pending and removed learners are excluded.
    pub async fn send_to_classroom_students(
        &self,
        db: &SqlitePool,
        classroom_id: &str,
        event: &ServerEvent,
    ) -> Result<(), sqlx::Error> {
        let students: Vec<String> = sqlx::query_scalar(
            "SELECT e.student_id FROM enrollments e JOIN users u ON u.id = e.student_id
             WHERE e.classroom_id = ? AND e.status = 'ACTIVE' AND u.is_active = 1 AND u.role = 'STUDENT'",
        )
        .bind(classroom_id)
        .fetch_all(db)
        .await?;
        for student in students {
            self.send_to_user(&student, event);
        }
        Ok(())
    }

    /// Teacher means the class owner and its co-teachers; UNION avoids duplicate delivery.
    pub async fn send_to_teacher(
        &self,
        db: &SqlitePool,
        classroom_id: &str,
        event: &ServerEvent,
    ) -> Result<(), sqlx::Error> {
        let teachers: Vec<String> = sqlx::query_scalar(
            "SELECT id FROM users WHERE is_active = 1 AND role = 'TEACHER' AND id IN (
                 SELECT teacher_id FROM classrooms WHERE id = ?
                 UNION SELECT teacher_id FROM classroom_teachers WHERE classroom_id = ?)",
        )
        .bind(classroom_id)
        .bind(classroom_id)
        .fetch_all(db)
        .await?;
        for teacher in teachers {
            self.send_to_user(&teacher, event);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registry_tracks_each_device_of_a_user() {
        let broker = Broker::default();
        let phone = broker.register("S-1", channel().0);
        let laptop = broker.register("S-1", channel().0);
        broker.register("T-1", channel().0);
        assert_eq!(broker.connection_count(), 3);

        broker.unregister("S-1", phone);
        assert_eq!(broker.connection_count(), 2);
        broker.unregister("S-1", laptop);
        broker.unregister("S-1", laptop);
        assert_eq!(broker.connection_count(), 1);
    }

    #[test]
    fn full_and_closed_queues_do_not_delay_a_healthy_device() {
        let broker = Broker::default();
        let (slow, _slow_rx) = channel();
        let (_, mut stopped) = broker.register_with_stop("S-1", slow);
        let (healthy, mut healthy_rx) = channel();
        broker.register("S-1", healthy);
        broker.register("S-1", channel().0); // receiver already dropped
        let event = ServerEvent::error(crate::error::ErrorCode::BadRequest, "m");
        for _ in 0..=CONNECTION_QUEUE {
            broker.send_to_user("S-1", &event);
            assert!(healthy_rx.try_recv().is_ok());
        }
        assert!(*stopped.borrow_and_update());
        assert_eq!(broker.connection_count(), 1);
        broker.send_to_user("offline-user", &event);
    }
}
