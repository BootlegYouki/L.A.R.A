//! Learner presence is transient and reaches only the teachers of active enrollments.

use super::events::{PresenceState, ServerEvent};
use crate::auth::{AuthUser, Role};
use crate::AppState;

pub struct Presence;

impl Presence {
    pub async fn emit(app: &AppState, user: &AuthUser, state: PresenceState) -> Result<(), sqlx::Error> {
        if user.role != Role::Student {
            return Ok(());
        }
        let classrooms: Vec<String> =
            sqlx::query_scalar("SELECT classroom_id FROM enrollments WHERE student_id = ? AND status = 'ACTIVE'")
                .bind(&user.id)
                .fetch_all(&app.db)
                .await?;
        for classroom_id in classrooms {
            let event = ServerEvent::Presence {
                classroom_id: classroom_id.clone(),
                student_id: user.id.clone(),
                state,
                timestamp: app.clock.now_ms(),
            };
            app.broker.send_to_teacher(&app.db, &classroom_id, &event).await?;
        }
        Ok(())
    }
}
