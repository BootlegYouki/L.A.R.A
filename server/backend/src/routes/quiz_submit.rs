//! Quiz submit and results (contract `/api/quizzes/{id}/submit`, `/api/quizzes/{id}/results`, PRD FR-4.6,
//! FR-4.7, step 4.2). Grading itself is `services::quiz_submit`, shared with the push and the WebSocket.

use axum::extract::{Path, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::Value;

use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, Role};
use crate::error::ApiError;
use crate::db::quizzes;
use crate::models::quiz::{AnswerInput, ResultRow};
use crate::services::class_access;
use crate::models::sync::GradeReceipt;
use crate::services::quiz_submit::{self, Submission};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/quizzes/{id}/submit", post(submit))
        .route("/api/quizzes/{id}/results", get(results))
}

/// The contract's `Answer` list; anything else is one clear 400.
pub fn answers(value: Option<&Value>) -> Result<Vec<AnswerInput>, ApiError> {
    value
        .cloned()
        .and_then(|v| serde_json::from_value(v).ok())
        .ok_or_else(|| ApiError::bad_request("answers must be a list of {question_id, selected_option}"))
}

async fn submit(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<GradeReceipt>, ApiError> {
    require_role(&user, Role::Student)?;
    body.need(&["started_at", "submitted_at", "answers"])?;
    let submission = Submission {
        started_at: body.optional_int("started_at")?,
        submitted_at: body.optional_int("submitted_at")?.unwrap_or_default(),
        answers: answers(body.0.get("answers"))?,
    };
    Ok(Json(quiz_submit::submit(&app, &user, &id, submission).await?))
}

/// The class's teachers: one row per active learner, with real scores even while they are held.
async fn results(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Vec<ResultRow>>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let mut conn = app.db.acquire().await?;
    let (classroom_id, _) = quizzes::meta(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Quiz not found"))?;
    class_access::require_teacher(&mut conn, &user, &classroom_id).await?;
    Ok(Json(quizzes::results(&mut conn, &id, &classroom_id).await?))
}
