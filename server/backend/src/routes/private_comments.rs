//! Private comments (contract `/api/assignments/{id}/private-comments`, PRD FR-3.8, step 3.5): one thread
//! per (assignment, learner), seen only by that learner and the class teachers.

use std::collections::HashMap;

use axum::extract::{Path, Query, State};
use axum::routing::get;
use axum::{Json, Router};
use sqlx::SqliteConnection;

use super::body::JsonBody;
use crate::auth::{AuthUser, Role};
use crate::db::{assignments, begin_immediate, private_comments};
use crate::error::ApiError;
use crate::models::sync::PrivateComment;
use crate::services::class_access;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new().route("/api/assignments/{id}/private-comments", get(list).post(create))
}

/// Whose thread the caller may touch, as `(classroom_id, student_id)`, like the mock's `private_thread`:
/// a learner only their own (an `ACTIVE` member; a `student_id` they send is ignored), a teacher of the
/// class any learner they name. Beyond the mock, the named learner must have an enrollment in the class,
/// and an `ACTIVE` one to post: an unknown id would otherwise reach the `users` foreign key.
async fn whose_thread(
    conn: &mut SqliteConnection,
    user: &AuthUser,
    assignment_id: &str,
    requested: Option<String>,
    posting: bool,
) -> Result<(String, String), ApiError> {
    let classroom_id =
        assignments::open_class(conn, assignment_id).await?.ok_or_else(|| ApiError::not_found("Assignment not found"))?;
    if user.role == Role::Student {
        if private_comments::enrollment_status(conn, &classroom_id, &user.id).await?.as_deref() != Some("ACTIVE") {
            return Err(ApiError::forbidden("Not your class"));
        }
        return Ok((classroom_id, user.id.clone()));
    }
    class_access::require_teacher(conn, user, &classroom_id).await?;
    let student = requested.ok_or_else(|| ApiError::bad_request("Missing: student_id"))?;
    let allowed = match private_comments::enrollment_status(conn, &classroom_id, &student).await?.as_deref() {
        Some("ACTIVE") => true,
        Some(_) => !posting,
        None => false,
    };
    if !allowed {
        return Err(ApiError::not_found("Learner not found in this class"));
    }
    Ok((classroom_id, student))
}

async fn list(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    Query(query): Query<HashMap<String, String>>,
) -> Result<Json<Vec<PrivateComment>>, ApiError> {
    let mut conn = app.db.acquire().await?;
    let (_, student) = whose_thread(&mut conn, &user, &id, query.get("student_id").cloned(), false).await?;
    Ok(Json(private_comments::thread(&mut conn, &id, &student).await?))
}

async fn create(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<PrivateComment>, ApiError> {
    body.need(&["content"])?;
    let content = body.non_empty_text("content")?.unwrap_or_default();
    let requested = body.optional_text("student_id")?;
    let mut tx = begin_immediate(&app.db).await?;
    let (classroom_id, student) = whose_thread(&mut tx, &user, &id, requested, true).await?;
    class_access::require_writable(&mut tx, &classroom_id).await?;
    let comment_id = uuid::Uuid::new_v4().to_string();
    let comment =
        private_comments::insert(&mut tx, &comment_id, &id, &classroom_id, &student, &user.id, &content, app.clock.now_ms())
            .await?;
    tx.commit().await?;
    Ok(Json(comment))
}
