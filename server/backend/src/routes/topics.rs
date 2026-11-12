//! Topics (contract `/api/topics*`, PRD FR-3.7, step 3.5). Teachers of the class group materials,
//! assignments and quizzes under topics. An archived class is read-only.

use axum::extract::{Path, State};
use axum::routing::{patch, post};
use axum::{Json, Router};

use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, Role};
use crate::db::begin_immediate;
use crate::db::topics;
use crate::error::ApiError;
use crate::models::sync::Topic;
use crate::services::class_access;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/topics", post(create))
        .route("/api/topics/{id}", patch(update))
}

fn topic_not_found() -> ApiError {
    ApiError::not_found("Topic not found")
}

async fn create(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<Topic>, ApiError> {
    require_role(&user, Role::Teacher)?;
    body.need(&["classroom_id", "name"])?;
    let classroom_id = body.optional_text("classroom_id")?.unwrap_or_default();
    let name = body.non_empty_text("name")?.unwrap_or_default();
    let mut tx = begin_immediate(&app.db).await?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let id = uuid::Uuid::new_v4().to_string();
    let topic = topics::insert(&mut tx, &id, &classroom_id, &name, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(topic))
}

async fn update(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<Topic>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let name = body.non_empty_text("name")?;
    let order_index = body.optional_int_from("order_index", 0)?;
    let mut tx = begin_immediate(&app.db).await?;
    let classroom_id = topics::class_of(&mut tx, &id).await?.ok_or_else(topic_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let topic = topics::update(&mut tx, &id, name, order_index, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(topic))
}
