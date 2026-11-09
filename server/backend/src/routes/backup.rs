use axum::routing::post;
use axum::Router;

use super::not_implemented_admin;
use crate::AppState;

/// Hub admin only (#42); the backup step (4.4, #21) fills these in.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/backup", post(not_implemented_admin))
        .route("/api/restore", post(not_implemented_admin))
}
