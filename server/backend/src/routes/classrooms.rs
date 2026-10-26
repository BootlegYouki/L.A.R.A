use axum::routing::{delete, get, patch, post};
use axum::Router;

use super::not_implemented_signed_in as stub;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/classrooms", get(stub).post(stub))
        .route("/api/classrooms/join", post(stub))
        .route("/api/classrooms/{id}", patch(stub))
        .route("/api/classrooms/{id}/remove", post(stub))
        .route("/api/classrooms/{id}/teachers", post(stub))
        .route("/api/classrooms/{id}/teachers/{teacher_id}", delete(stub))
        .route("/api/classrooms/{id}/roster", get(stub))
        .route("/api/classrooms/{id}/approve", post(stub))
        .route("/api/classrooms/{id}/reject", post(stub))
}
