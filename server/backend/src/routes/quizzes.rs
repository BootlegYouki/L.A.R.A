use axum::routing::{get, post};
use axum::Router;

use super::{not_implemented_signed_in as stub, not_implemented_student as student, not_implemented_teacher as teacher};
use crate::AppState;

/// Steps 4.1 and 4.2 move start/close/results to `quiz_control.rs` and submit to `quiz_submit.rs`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/quizzes", post(teacher))
        .route("/api/quizzes/active", get(stub))
        .route("/api/quizzes/{id}", get(stub).patch(teacher))
        .route("/api/quizzes/{id}/start", post(teacher))
        .route("/api/quizzes/{id}/begin", post(student))
        .route("/api/quizzes/{id}/close", post(teacher))
        .route("/api/quizzes/{id}/submit", post(student))
        .route("/api/quizzes/{id}/results", get(teacher))
}
