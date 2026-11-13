//! Quiz start and close fan-out (step 4.1, #11). Events go out after the transaction commits; a failed
//! lookup of recipients is logged and never fails the teacher's request, which has already been stored.

use crate::websocket::events::ServerEvent;
use crate::AppState;

/// `EVENT_QUIZ_START` to every `ACTIVE` learner of the class (`rules/quiz-and-anti-cheat.md` section 2).
pub async fn announce_start(app: &AppState, classroom_id: &str, event: &ServerEvent) {
    if let Err(error) = app.broker.send_to_classroom_students(&app.db, classroom_id, event).await {
        tracing::warn!(%error, "quiz start push failed");
    }
}
