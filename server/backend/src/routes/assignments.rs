use axum::routing::{get, patch, post};
use axum::Router;

use super::{not_implemented_signed_in as stub, not_implemented_student as student, not_implemented_teacher as teacher};
use crate::AppState;

/// `/private-comments` is not here: it gets its own `routes/private_comments.rs` in step 3.5.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/assignments", post(teacher))
        .route("/api/assignments/{id}", patch(teacher).delete(teacher))
        .route("/api/assignments/{id}/submit", post(student))
        .route("/api/assignments/{id}/submissions", get(teacher))
        .route("/api/submissions/{id}", patch(teacher))
        .route("/api/submissions/{id}/file", get(stub))
}
