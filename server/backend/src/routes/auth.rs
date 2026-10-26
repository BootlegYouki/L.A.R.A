use axum::routing::post;
use axum::Router;

use super::{not_implemented, not_implemented_signed_in};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/auth/register", post(not_implemented))
        .route("/api/auth/login", post(not_implemented))
        .route("/api/auth/logout", post(not_implemented_signed_in))
}
