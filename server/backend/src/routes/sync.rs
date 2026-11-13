use axum::{extract::State, routing::post, Json, Router};

use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, Role};
use crate::db::{self, sync_revisions};
use crate::error::{ApiError, ErrorCode};
use crate::models::quiz::AnswerInput;
use crate::models::sync::{
    is_client_uuid, SyncPullInput, SyncPullResponse, SyncPushInput, SyncPushResponse, SyncReceipt, MAX_SAFE_INT,
    MAX_TEXT_BYTES,
};
use crate::services::quiz_submit::{self, Submission};
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/sync/pull", post(pull))
        .route("/api/sync/push", post(push))
}

async fn pull(
    State(app): State<AppState>,
    user: AuthUser,
    body: JsonBody,
) -> Result<Json<SyncPullResponse>, ApiError> {
    let input: SyncPullInput = serde_json::from_value(body.0.into())
        .map_err(|_| ApiError::bad_request("Invalid sync pull"))?;
    if input.cursor < 0 {
        return Err(ApiError::bad_request("Invalid sync cursor"));
    }
    let mut tx = app.db.begin().await?;
    let response = sync_revisions::pull_page(&mut tx, &user, &input, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(response))
}

async fn push(
    State(app): State<AppState>,
    user: AuthUser,
    body: JsonBody,
) -> Result<Json<SyncPushResponse>, ApiError> {
    require_role(&user, Role::Student)?;
    let input: SyncPushInput = serde_json::from_value(body.0.into())
        .map_err(|_| ApiError::bad_request("Invalid sync push"))?;
    let mut receipts = Vec::new();
    // First, in order, as the mock does: a finished quiz is the most time-sensitive item a device queues.
    for item in &input.quiz_attempts {
        receipts.push(quiz_attempt(&app, &user, item).await?);
    }
    for comment in input.comments {
        if unsafe_value(&comment.id, comment.created_at, &comment.content) {
            receipts.push(bad_request(comment.id));
            continue;
        }
        let mut tx = db::begin_immediate(&app.db).await?;
        let receipt =
            sync_revisions::push_comment(&mut tx, &user.id, &comment, app.clock.now_ms()).await?;
        tx.commit().await?;
        receipts.push(receipt);
    }
    for comment in input.private_comments {
        if unsafe_value(&comment.id, comment.created_at, &comment.content) {
            receipts.push(bad_request(comment.id));
            continue;
        }
        let mut tx = db::begin_immediate(&app.db).await?;
        let receipt =
            sync_revisions::push_private_comment(&mut tx, &user.id, &comment, app.clock.now_ms())
                .await?;
        tx.commit().await?;
        receipts.push(receipt);
    }
    Ok(Json(SyncPushResponse {
        acknowledged: true,
        receipts,
    }))
}

/// Bug #290: a pushed value the desktop refuses in a pull (a non-UUID or nil id, a `created_at` outside
/// 0..=2^53−1, text over 1,000,000 bytes) would stop sync for everyone who pulls it, so the item is refused
/// alone and the rest of the push goes on.
fn unsafe_value(id: &str, created_at: i64, content: &str) -> bool {
    !is_client_uuid(id) || !(0..=MAX_SAFE_INT).contains(&created_at) || content.len() > MAX_TEXT_BYTES
}

fn bad_request(id: String) -> SyncReceipt {
    SyncReceipt { id, status: "REJECTED", reason: Some(ErrorCode::BadRequest.as_str()) }
}

/// A queued quiz attempt (contract `QuizAttemptPush`), graded through the same path as the REST submit
/// (`services::quiz_submit`). A bad item gets its own `REJECTED` receipt with the error code; only a Hub
/// failure fails the whole push.
async fn quiz_attempt(app: &AppState, user: &AuthUser, item: &serde_json::Value) -> Result<SyncReceipt, ApiError> {
    let id = item.get("id").and_then(serde_json::Value::as_str).unwrap_or_default().to_owned();
    let parsed = (|| {
        let quiz_id = item.get("quiz_id")?.as_str()?.to_owned();
        let submission = Submission {
            started_at: Some(item.get("started_at")?.as_i64()?),
            submitted_at: item.get("submitted_at")?.as_i64()?,
            answers: serde_json::from_value::<Vec<AnswerInput>>(item.get("answers")?.clone()).ok()?,
        };
        Some((quiz_id, submission))
    })();
    let outcome = match parsed {
        None => Err(ApiError::bad_request("Invalid queued quiz attempt")),
        Some((quiz_id, submission)) => quiz_submit::submit(app, user, &quiz_id, submission).await.map(|_| ()),
    };
    match outcome {
        Ok(()) => Ok(SyncReceipt { id, status: "SYNCED", reason: None }),
        Err(error) if error.code == ErrorCode::Internal => Err(error),
        Err(error) => Ok(SyncReceipt { id, status: "REJECTED", reason: Some(error.code.as_str()) }),
    }
}
