use axum::routing::{delete, get, patch, post};
use axum::Router;

use super::{not_implemented_signed_in as stub, not_implemented_student as student, not_implemented_teacher as teacher};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/classrooms", get(stub).post(teacher))
        .route("/api/classrooms/join", post(student))
        .route("/api/classrooms/{id}", patch(teacher))
        .route("/api/classrooms/{id}/remove", post(teacher))
        .route("/api/classrooms/{id}/teachers", post(teacher))
        .route("/api/classrooms/{id}/teachers/{teacher_id}", delete(teacher))
        .route("/api/classrooms/{id}/roster", get(teacher))
        .route("/api/classrooms/{id}/approve", post(teacher))
        .route("/api/classrooms/{id}/reject", post(teacher))
}
