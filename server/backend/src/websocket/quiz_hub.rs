//! Quiz start and close fan-out (step 4.1, #11). Events go out after the transaction commits; a failed
//! lookup of recipients is logged and never fails the teacher's request, which has already been stored.

use crate::db::quizzes::ReleasedScore;
use crate::websocket::events::ServerEvent;
use crate::AppState;

/// `EVENT_QUIZ_START` to every `ACTIVE` learner of the class (`rules/quiz-and-anti-cheat.md` section 2).
pub async fn announce_start(app: &AppState, classroom_id: &str, event: &ServerEvent) {
    if let Err(error) = app.broker.send_to_classroom_students(&app.db, classroom_id, event).await {
        tracing::warn!(%error, "quiz start push failed");
    }
}

/// After a close: `EVENT_GRADE_CONFIRMED` to each learner whose held score was released, then
/// `EVENT_QUIZ_CLOSED` to the class's learners, in the mock's order (`quiz_close`). Learners the close
/// auto-submitted get no grade event; their devices submit their own answers on `EVENT_QUIZ_CLOSED`.
pub async fn announce_close(app: &AppState, classroom_id: &str, quiz_id: &str, released: &[ReleasedScore], now_ms: i64) {
    for r in released {
        let graded = ServerEvent::GradeConfirmed {
            quiz_id: quiz_id.to_owned(),
            attempt_id: r.attempt_id.clone(),
            score: r.score,
            total_points: r.total_points,
            timestamp: now_ms,
        };
        app.broker.send_to_user(&r.student_id, &graded);
    }
    let closed = ServerEvent::QuizClosed { quiz_id: quiz_id.to_owned(), timestamp: now_ms };
    if let Err(error) = app.broker.send_to_classroom_students(&app.db, classroom_id, &closed).await {
        tracing::warn!(%error, "quiz close push failed");
    }
}
