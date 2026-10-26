use axum::routing::post;
use axum::Router;

use super::not_implemented_signed_in as stub;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new().route("/api/sync/pull", post(stub)).route("/api/sync/push", post(stub))
}
