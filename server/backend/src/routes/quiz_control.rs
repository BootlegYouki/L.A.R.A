//! Starting and closing a quiz (contract `/api/quizzes/{id}/start` and `/close`, step 4.1, #11, PRD FR-4.2
//! to FR-4.6). One quiz per class can be `ACTIVE`; the start time is the Hub's clock.

use axum::extract::{Path, State};
use axum::routing::post;
use axum::{Json, Router};
use serde_json::{json, Value};

use super::not_implemented_teacher as teacher;
use crate::auth::{require_role, AuthUser, Role};
use crate::db::{begin_immediate, quizzes};
use crate::error::ApiError;
use crate::services::class_access;
use crate::websocket::events::ServerEvent;
use crate::websocket::quiz_hub;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/quizzes/{id}/start", post(start))
        .route("/api/quizzes/{id}/close", post(teacher))
}

fn quiz_not_found() -> ApiError {
    ApiError::not_found("Quiz not found")
}

/// Starts a `DRAFT` quiz for the whole class at the Hub's now (contract #294): 409 `QUIZ_NOT_DRAFT` once
/// it has started, 409 `QUIZ_ALREADY_ACTIVE` while another quiz in the class is `ACTIVE`.
async fn start(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    let quiz = quizzes::quiz_state(&mut tx, &id).await?.ok_or_else(quiz_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &quiz.classroom_id).await?;
    if quiz.status != "DRAFT" {
        return Err(ApiError::quiz_already_started());
    }
    if let Some(active) = quizzes::active_in_class(&mut tx, &quiz.classroom_id).await? {
        return Err(ApiError::quiz_already_active(&active));
    }
    quizzes::start(&mut tx, &id, &quiz.classroom_id, now).await?;
    tx.commit().await?;

    let duration_seconds = quiz.time_limit_minutes * 60;
    let event = ServerEvent::QuizStart { quiz_id: id, title: quiz.title, start_epoch_ms: now, duration_seconds };
    quiz_hub::announce_start(&app, &quiz.classroom_id, &event).await;
    Ok(Json(json!({"started_at": now, "duration_seconds": duration_seconds})))
}
