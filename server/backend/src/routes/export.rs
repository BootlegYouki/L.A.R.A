use axum::routing::post;
use axum::Router;

use super::not_implemented_teacher as teacher;
use crate::AppState;

/// Teacher only, as the mock. TECH_SPEC section 14, Q6 also lets the admin export; that waits
/// for its `contract-change`.
pub fn router() -> Router<AppState> {
    Router::new().route("/api/export/class-record", post(teacher))
}
