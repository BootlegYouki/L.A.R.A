use axum::{extract::State, routing::post, Json, Router};

use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, Role};
use crate::db::{self, sync_revisions};
use crate::error::ApiError;
use crate::models::sync::{SyncPullInput, SyncPullResponse, SyncPushInput, SyncPushResponse};
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
    // Quiz attempts remain queued until SERVER 4.2 supplies validation and grading.
    let input: SyncPushInput = serde_json::from_value(body.0.into())
        .map_err(|_| ApiError::bad_request("Invalid sync push"))?;
    let mut receipts = Vec::new();
    for comment in input.comments {
        let mut tx = db::begin_immediate(&app.db).await?;
        let receipt =
            sync_revisions::push_comment(&mut tx, &user.id, &comment, app.clock.now_ms()).await?;
        tx.commit().await?;
        receipts.push(receipt);
    }
    for comment in input.private_comments {
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
