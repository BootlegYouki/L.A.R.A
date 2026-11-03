use axum::extract::{Path, State};
use axum::routing::{delete, get, patch, post};
use axum::Json;
use axum::Router;
use serde_json::{json, Value};

use super::{body::JsonBody, not_implemented_teacher as teacher};
use crate::auth::{require_role, AuthUser, Role};
use crate::db::{begin_immediate, classrooms};
use crate::error::ApiError;
use crate::models::classroom::{Classroom, RosterEntry};
use crate::services::class_access;
use crate::websocket::events::{EnrollmentStatus, ServerEvent};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/classrooms", get(list).post(create))
        .route("/api/classrooms/join", post(join))
        .route("/api/classrooms/{id}", patch(teacher))
        .route("/api/classrooms/{id}/remove", post(teacher))
        .route("/api/classrooms/{id}/teachers", post(teacher))
        .route("/api/classrooms/{id}/teachers/{teacher_id}", delete(teacher))
        .route("/api/classrooms/{id}/roster", get(roster))
        .route("/api/classrooms/{id}/approve", post(approve))
        .route("/api/classrooms/{id}/reject", post(reject))
}

async fn create(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<Classroom>, ApiError> {
    require_role(&user, Role::Teacher)?;
    body.need(&["name", "section"])?;
    let text = |key: &str| {
        body.0
            .get(key)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| ApiError::bad_request(format!("{key} must be text")))
    };
    let name = text("name")?;
    let section = text("section")?;
    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    loop {
        let code = classrooms::new_class_code()?;
        if let Some(room) = classrooms::insert(&mut tx, &user.id, &name, &section, &code, now).await? {
            tx.commit().await?;
            return Ok(Json(room));
        }
    }
}

async fn list(State(app): State<AppState>, user: AuthUser) -> Result<Json<Vec<Classroom>>, ApiError> {
    let mut tx = app.db.begin().await?;
    let rooms = classrooms::list(&mut tx, &user).await?;
    tx.commit().await?;
    Ok(Json(rooms))
}

async fn join(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Student)?;
    let code = body
        .text("class_code")
        .unwrap_or_default()
        .replace('-', "")
        .to_uppercase();
    let mut tx = begin_immediate(&app.db).await?;
    let now = app.clock.now_ms();
    let outcome = classrooms::join(&mut tx, &user.id, &code, now).await?;
    tx.commit().await?;
    if let Some((full_name, lrn)) = outcome.learner {
        let event = ServerEvent::JoinRequest {
            class_code: code,
            student_id: user.id,
            full_name,
            lrn,
            timestamp: now,
        };
        if let Err(error) = app.broker.send_to_teacher(&app.db, &outcome.classroom_id, &event).await {
            tracing::warn!(%error, "join notification failed after commit");
        }
    }
    Ok(Json(
        json!({"status": outcome.status, "classroom_id": outcome.classroom_id}),
    ))
}

async fn roster(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
) -> Result<Json<Vec<RosterEntry>>, ApiError> {
    let mut tx = app.db.begin().await?;
    class_access::require_teacher(&mut tx, &user, &id).await?;
    let roster = classrooms::roster(&mut tx, &id).await?;
    tx.commit().await?;
    Ok(Json(roster))
}

async fn approve(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<Value>, ApiError> {
    decide(app, user, id, body, true).await
}

async fn reject(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<Value>, ApiError> {
    decide(app, user, id, body, false).await
}

async fn decide(
    app: AppState,
    user: AuthUser,
    id: String,
    body: JsonBody,
    approved: bool,
) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let student_id = body.text("student_id").unwrap_or_default();
    let mut tx = begin_immediate(&app.db).await?;
    class_access::require_teacher(&mut tx, &user, &id).await?;
    class_access::require_writable(&mut tx, &id).await?;
    let now = app.clock.now_ms();
    let changed = classrooms::decide(
        &mut tx,
        &id,
        &student_id,
        if approved { "ACTIVE" } else { "REJECTED" },
        now,
    )
    .await?;
    tx.commit().await?;
    if changed {
        let event = ServerEvent::JoinApproval {
            classroom_id: id,
            student_id: student_id.clone(),
            status: if approved {
                EnrollmentStatus::Active
            } else {
                EnrollmentStatus::Rejected
            },
            timestamp: now,
        };
        app.broker.send_to_user(&student_id, &event);
    }
    Ok(Json(json!({"success": true})))
}
