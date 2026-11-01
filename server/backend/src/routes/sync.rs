use axum::routing::post;
use axum::Router;

use super::{not_implemented_signed_in as stub, not_implemented_student as student};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new().route("/api/sync/pull", post(stub)).route("/api/sync/push", post(student))
}
