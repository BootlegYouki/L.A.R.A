use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, PoisonError, RwLock};

use tokio::sync::mpsc;

pub type Frame = Arc<str>;

/// Bounded so one slow phone fills only its own queue and is dropped, never the others.
const CONNECTION_QUEUE: usize = 64;

pub fn channel() -> (mpsc::Sender<Frame>, mpsc::Receiver<Frame>) {
    mpsc::channel(CONNECTION_QUEUE)
}

struct Connection {
    id: u64,
    // Read by the send methods that step 2.4 adds.
    #[allow(dead_code)]
    tx: mpsc::Sender<Frame>,
}

/// Open WebSocket connections keyed by user id (one user may have a phone and a laptop).
#[derive(Default)]
pub struct Broker {
    registry: RwLock<HashMap<String, Vec<Connection>>>,
    next_id: AtomicU64,
}

impl Broker {
    pub fn register(&self, user_id: &str, tx: mpsc::Sender<Frame>) -> u64 {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let mut registry = self.registry.write().unwrap_or_else(PoisonError::into_inner);
        registry.entry(user_id.to_owned()).or_default().push(Connection { id, tx });
        id
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
}
