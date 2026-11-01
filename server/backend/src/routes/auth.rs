//! `/api/auth/*`, answering as `scripts/mock_hub.py` does. PINs, tokens and LRNs never reach a
//! log: the request span carries only the method and path (`routes/mod.rs`).

use axum::extract::State;
use axum::http::HeaderMap;
use axum::routing::post;
use axum::{Json, Router};
use serde_json::{json, Value};

use super::body::JsonBody;
use crate::auth::pin::is_valid_pin;
use crate::auth::sessions;
use crate::auth::{bearer_token, AuthUser, Role, ADMIN_ID};
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

/// Learners only: teachers are created by the Hub admin (PRD FR-1.0, FR-1.1).
async fn register(State(app): State<AppState>, body: JsonBody) -> Result<Json<AuthSession>, ApiError> {
    body.need(&["lrn_or_id", "full_name", "pin"])?;
    let pin = body.text("pin").filter(|p| is_valid_pin(p)).ok_or_else(|| ApiError::bad_request("PIN must be 4 digits"))?;
    let lrn_or_id = text_field(&body, "lrn_or_id")?;
    let full_name = text_field(&body, "full_name")?;
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
async fn login(State(app): State<AppState>, body: JsonBody) -> Result<Json<AuthSession>, ApiError> {
    body.need(&["lrn_or_id", "pin"])?;
    // A value that is not text cannot match an account, like the mock's string compare.
    let lrn_or_id = body.text("lrn_or_id").unwrap_or_default();
    let pin = body.text("pin").unwrap_or_default();
    app.login_limiter.try_attempt(&lrn_or_id, app.clock.now_ms()).map_err(ApiError::too_many_attempts)?;

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
