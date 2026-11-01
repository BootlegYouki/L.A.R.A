use std::net::SocketAddr;

use axum::extract::{ConnectInfo, State};
use axum::http::StatusCode;
use axum::routing::{get, post};
use axum::{Json, Router};

use super::body::JsonBody;
use super::not_implemented_admin as admin;
use crate::auth::pin::is_valid_pin;
use crate::auth::sessions;
use crate::auth::{Role, ADMIN_ID};
use crate::db::begin_immediate;
use crate::db::users::{self, admin_exists};
use crate::error::{ApiError, ErrorCode};
use crate::models::user::AuthSession;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/admin/setup", post(setup))
        .route("/api/admin/users", get(admin).post(admin))
        .route("/api/admin/users/{id}/reset-pin", post(admin))
}

/// First run only (PRD FR-1.0): the person at the Hub PC sets the admin PIN. Loopback only, so
/// no device on the classroom network can claim the admin account before the school does.
///
/// Remote/Suspension Mode: `cloudflared` reaches the Hub from 127.0.0.1, so a tunnelled request
/// also looks local. That is safe only because the tunnel is switched on by an existing admin,
/// after which this answers 409. Do not let anything start the tunnel before setup.
async fn setup(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    body: JsonBody,
) -> Result<Json<AuthSession>, ApiError> {
    if !peer.ip().is_loopback() {
        return Err(ApiError::forbidden("Only on the Hub PC"));
    }
    body.need(&["full_name", "pin"])?;
    // Before the PIN check, so a second setup answers 409 whatever it sends, as in the mock.
    if admin_exists(&mut *app.db.acquire().await?).await? {
        return Err(admin_already_set_up());
    }
    let pin = body.text("pin").filter(|p| is_valid_pin(p)).ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    let full_name = body.text("full_name").ok_or_else(|| ApiError::bad_request("full_name must be text"))?;

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
