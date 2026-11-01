use axum::routing::{get, post};
use axum::Router;

use super::{not_implemented, not_implemented_admin as admin};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/admin/setup", post(not_implemented))
        .route("/api/admin/users", get(admin).post(admin))
        .route("/api/admin/users/{id}/reset-pin", post(admin))
}
