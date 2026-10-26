use axum::routing::{get, post};
use axum::Router;

use super::not_implemented_signed_in as stub;
use crate::AppState;

/// Steps 4.1 and 4.2 move start/close/results to `quiz_control.rs` and submit to `quiz_submit.rs`.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/quizzes", post(stub))
        .route("/api/quizzes/active", get(stub))
        .route("/api/quizzes/{id}", get(stub).patch(stub))
        .route("/api/quizzes/{id}/start", post(stub))
        .route("/api/quizzes/{id}/begin", post(stub))
        .route("/api/quizzes/{id}/close", post(stub))
        .route("/api/quizzes/{id}/submit", post(stub))
        .route("/api/quizzes/{id}/results", get(stub))
}
