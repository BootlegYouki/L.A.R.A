//! `/api/auth/*`, answering as `scripts/mock_hub.py` does. PINs, tokens and LRNs never reach a
//! log: the request span carries only the method and path (`routes/mod.rs`).

use std::net::SocketAddr;

use axum::extract::{ConnectInfo, State};
use axum::http::HeaderMap;
use axum::routing::post;
use axum::{Json, Router};
use serde_json::{json, Value};

use super::body::JsonBody;
use crate::auth::pin::is_valid_pin;
use crate::auth::sessions;
use crate::auth::{bearer_token, is_hub_pc, AuthUser, Role, ADMIN_ID};
use crate::db::{begin_immediate, users};
use crate::error::ApiError;
use crate::models::user::AuthSession;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/auth/register", post(register))
        .route("/api/auth/login", post(login))
        .route("/api/auth/logout", post(logout))
}

/// Contract limits on `lrn_or_id` and `full_name` after trimming, in characters (PR #197).
const MAX_LRN_CHARS: usize = 32;
const MAX_NAME_CHARS: usize = 100;

/// Learners only: teachers are created by the Hub admin (PRD FR-1.0, FR-1.1).
async fn register(State(app): State<AppState>, body: JsonBody) -> Result<Json<AuthSession>, ApiError> {
    body.need(&["lrn_or_id", "full_name", "pin"])?;
    let lrn_or_id = text_field(&body, "lrn_or_id")?.trim().to_owned();
    let full_name = text_field(&body, "full_name")?.trim().to_owned();
    if !(1..=MAX_LRN_CHARS).contains(&lrn_or_id.chars().count()) || !(1..=MAX_NAME_CHARS).contains(&full_name.chars().count()) {
        return Err(ApiError::bad_request("LRN and name must be 1-32 and 1-100 characters"));
    }
    let pin = body.text("pin").filter(|p| is_valid_pin(p)).ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    // Taken before setup too, as in the mock (which is seeded with the admin), so setup always works.
    if lrn_or_id == ADMIN_ID {
        return Err(users::InsertError::Exists.into());
    }

    // Hashed before the write lock is taken, so 40 registrations never queue behind argon2.
    let pin_hash = app.pins.hash(&pin).await?;
    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    let new_user = users::insert(&mut tx, &lrn_or_id, &full_name, Role::Student, &pin_hash, now).await?;
    let session = sessions::open(&mut tx, new_user, now).await?;
    tx.commit().await?;
    Ok(Json(session))
}

/// Unknown LRN, deactivated account and wrong PIN all answer 401 "Wrong LRN or PIN". Each
/// attempt counts toward the limit until one succeeds (`auth::limiter`).
async fn login(
    State(app): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: JsonBody,
) -> Result<Json<AuthSession>, ApiError> {
    body.need(&["lrn_or_id", "pin"])?;
    // A value that is not text cannot match an account, like the mock's string compare.
    let lrn_or_id = body.text("lrn_or_id").unwrap_or_default();
    let pin = body.text("pin").unwrap_or_default();
    let now = app.clock.now_ms();
    // Contract PR #197: the admin signs in on the Hub PC only, checked before the PIN. Matched on
    // the ID, the only ADMIN account (setup creates it, register refuses it), so no lookup runs
    // first. Not counted: no one on the network can guess the PIN or lock the admin out.
    if lrn_or_id == ADMIN_ID && !is_hub_pc(peer, &headers) {
        app.login_limiter.check(&lrn_or_id, now).map_err(ApiError::too_many_attempts)?;
        return Err(ApiError::admin_local_only());
    }
    app.login_limiter.try_attempt(&lrn_or_id, now).map_err(ApiError::too_many_attempts)?;

    let account = users::find_by_lrn(&app.db, &lrn_or_id).await?.filter(|a| a.is_active);
    let Some(account) = account else {
        return Err(ApiError::wrong_credentials());
    };
    if !is_valid_pin(&pin) || !app.pins.verify(&pin, &account.pin_hash).await? {
        return Err(ApiError::wrong_credentials());
    }
    app.login_limiter.reset(&lrn_or_id);

    let mut tx = begin_immediate(&app.db).await?;
    let session = sessions::open(&mut tx, account.user, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(session))
}

/// Ends this session only; the user's other devices stay signed in.
async fn logout(State(app): State<AppState>, _user: AuthUser, headers: HeaderMap) -> Result<Json<Value>, ApiError> {
    // The extractor already found this token, so it is present.
    if let Some(token) = bearer_token(&headers) {
        sessions::close(&app.db, token).await?;
    }
    Ok(Json(json!({"success": true})))
}

fn text_field(body: &JsonBody, key: &str) -> Result<String, ApiError> {
    body.text(key).ok_or_else(|| ApiError::bad_request(format!("{key} must be text")))
}
