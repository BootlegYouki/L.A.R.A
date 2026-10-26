use axum::routing::{get, post};
use axum::Router;

use super::{not_implemented, not_implemented_signed_in};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/admin/setup", post(not_implemented))
        .route("/api/admin/users", get(not_implemented_signed_in).post(not_implemented_signed_in))
        .route("/api/admin/users/{id}/reset-pin", post(not_implemented_signed_in))
}
