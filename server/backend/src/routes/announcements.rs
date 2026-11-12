//! The class stream (contract `/api/announcements*`, PRD FR-2.1 and FR-2.2, step 3.4). Teachers of
//! the class post, edit and delete; posting pushes `EVENT_ANNOUNCEMENT_PUSH` to its active learners.
//! An archived class is read-only.

use axum::extract::{Path, State};
use axum::routing::{patch, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, Role};
use crate::db::announcements::{self, AnnouncementChange};
use crate::db::begin_immediate;
use crate::error::ApiError;
use crate::models::sync::Announcement;
use crate::services::class_access;
use crate::websocket::events::ServerEvent;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/announcements", post(create))
        .route("/api/announcements/{id}", patch(update).delete(remove))
}

fn announcement_not_found() -> ApiError {
    ApiError::not_found("Announcement not found")
}

async fn create(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<Announcement>, ApiError> {
    require_role(&user, Role::Teacher)?;
    body.need(&["classroom_id", "title", "content"])?;
    let classroom_id = body.optional_text("classroom_id")?.unwrap_or_default();
    let title = body.non_empty_text("title")?.unwrap_or_default();
    let content = body.optional_text("content")?.unwrap_or_default();
    let allow_comments = body.optional_bool("allow_comments")?.unwrap_or(true);

    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let id = uuid::Uuid::new_v4().to_string();
    let announcement = announcements::insert(&mut tx, &id, &classroom_id, &title, &content, allow_comments, now).await?;
    tx.commit().await?;
    // A hint only: a learner who misses it gets the post on their next pull.
    let event = ServerEvent::announcement_push(&announcement, now);
    if let Err(error) = app.broker.send_to_classroom_students(&app.db, &classroom_id, &event).await {
        tracing::warn!(%error, "announcement push failed");
    }
    Ok(Json(announcement))
}

async fn update(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<Announcement>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let change = AnnouncementChange {
        title: body.non_empty_text("title")?,
        content: body.optional_text("content")?,
        allow_comments: body.optional_bool("allow_comments")?,
    };
    let mut tx = begin_immediate(&app.db).await?;
    let classroom_id = announcements::class_of(&mut tx, &id).await?.ok_or_else(announcement_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let announcement = announcements::update(&mut tx, &id, change, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(announcement))
}

/// Deletes the post with its comments and attachments (they hold no grades, rules section 4). The
/// attachment files are removed only after the commit, so a failed delete loses nothing.
async fn remove(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let mut tx = begin_immediate(&app.db).await?;
    let classroom_id = announcements::class_of(&mut tx, &id).await?.ok_or_else(announcement_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let files = announcements::delete(&mut tx, &id, &classroom_id, app.clock.now_ms()).await?;
    tx.commit().await?;
    for file in files {
        app.storage.remove(&file).await;
    }
    Ok(Json(json!({"success": true})))
}
