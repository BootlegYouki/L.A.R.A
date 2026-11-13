use std::net::SocketAddr;

use axum::extract::{ConnectInfo, Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::{json, Value};

use super::body::{within_text_cap, JsonBody};
use crate::auth::pin::is_valid_pin;
use crate::auth::sessions;
use crate::auth::{is_hub_pc, require_role, AuthUser, Role, ADMIN_ID};
use crate::db::begin_immediate;
use crate::db::users::{self, admin_exists};
use crate::error::{ApiError, ErrorCode};
use crate::models::user::{AuthSession, User};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/admin/setup", post(setup))
        .route("/api/admin/users", get(list).post(create))
        .route("/api/admin/users/{id}/reset-pin", post(reset_pin))
}

async fn list(State(app): State<AppState>, user: AuthUser) -> Result<Json<Vec<User>>, ApiError> {
    require_role(&user, Role::Admin)?;
    Ok(Json(users::list(&app.db).await?))
}

async fn create(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<User>, ApiError> {
    require_role(&user, Role::Admin)?;
    body.need(&["lrn_or_id", "full_name", "role", "pin"])?;
    let role = body.text("role").and_then(|r| Role::parse(&r))
        .filter(|r| *r != Role::Admin).ok_or_else(|| ApiError::bad_request("Invalid role"))?;
    let lrn_or_id = body.text("lrn_or_id").filter(|s| !s.trim().is_empty())
        .ok_or_else(|| ApiError::bad_request("lrn_or_id must be text"))?;
    let full_name = body.text("full_name").filter(|s| !s.trim().is_empty())
        .ok_or_else(|| ApiError::bad_request("full_name must be text"))?;
    // Every client pulls `users` (bug #290).
    within_text_cap("full_name", &full_name)?;
    let pin = body.text("pin").filter(|p| is_valid_pin(p))
        .ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    let pin_hash = app.pins.hash(&pin).await?;
    let mut tx = begin_immediate(&app.db).await?;
    let created = users::insert(&mut tx, &lrn_or_id, &full_name, role, &pin_hash, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(created))
}

async fn reset_pin(
    State(app): State<AppState>, user: AuthUser, Path(id): Path<String>, body: JsonBody,
) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Admin)?;
    body.need(&["pin"])?;
    let pin = body.text("pin").filter(|p| is_valid_pin(p))
        .ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    let pin_hash = app.pins.hash(&pin).await?;
    let mut tx = begin_immediate(&app.db).await?;
    if !users::reset_pin(&mut tx, &id, &pin_hash, app.clock.now_ms()).await? {
        return Err(ApiError::not_found("User not found"));
    }
    // A reset is how the admin answers a leaked PIN, so the old sessions end with it.
    sessions::close_all(&mut tx, &id).await?;
    tx.commit().await?;
    Ok(Json(json!({"success": true})))
}

/// First run only (PRD FR-1.0): the person at the Hub PC sets the admin PIN. Only from the Hub
/// PC itself (`is_hub_pc`: loopback, not relayed by the tunnel), so no device on the classroom
/// network or the internet can claim the admin account before the school does.
async fn setup(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: JsonBody,
) -> Result<Json<AuthSession>, ApiError> {
    if !is_hub_pc(peer, &headers) {
        return Err(ApiError::forbidden("Only on the Hub PC"));
    }
    body.need(&["full_name", "pin"])?;
    // Before the PIN check, so a second setup answers 409 whatever it sends, as in the mock.
    if admin_exists(&mut *app.db.acquire().await?).await? {
        return Err(admin_already_set_up());
    }
    let pin = body.text("pin").filter(|p| is_valid_pin(p)).ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    let full_name = body.text("full_name").ok_or_else(|| ApiError::bad_request("full_name must be text"))?;
    within_text_cap("full_name", &full_name)?;

    let pin_hash = app.pins.hash(&pin).await?;
    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    // Again under the write lock: two setups at once must not both pass the first check.
    if admin_exists(&mut tx).await? {
        return Err(admin_already_set_up());
    }
    let new_admin = users::insert(&mut tx, ADMIN_ID, &full_name, Role::Admin, &pin_hash, now).await?;
    let session = sessions::open(&mut tx, new_admin, now).await?;
    tx.commit().await?;
    Ok(Json(session))
}

fn admin_already_set_up() -> ApiError {
    ApiError::new(StatusCode::CONFLICT, ErrorCode::AdminExists, "The Hub admin is already set up")
}
