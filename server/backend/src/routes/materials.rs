use axum::routing::{get, patch, post};
use axum::Router;

use super::{not_implemented_signed_in as stub, not_implemented_teacher as teacher};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/materials", post(teacher))
        .route("/api/materials/{id}", patch(teacher).delete(teacher))
        .route("/api/materials/{id}/download", get(stub))
        .route("/api/materials/{id}/chunks", get(stub))
        .route("/api/materials/{id}/stream", get(stub))
}
