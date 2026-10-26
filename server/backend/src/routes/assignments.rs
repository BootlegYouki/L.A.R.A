use axum::routing::{get, patch, post};
use axum::Router;

use super::not_implemented_signed_in as stub;
use crate::AppState;

/// `/private-comments` is not here: it gets its own `routes/private_comments.rs` in step 3.5.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/assignments", post(stub))
        .route("/api/assignments/{id}", patch(stub).delete(stub))
        .route("/api/assignments/{id}/submit", post(stub))
        .route("/api/assignments/{id}/submissions", get(stub))
        .route("/api/submissions/{id}", patch(stub))
        .route("/api/submissions/{id}/file", get(stub))
}
