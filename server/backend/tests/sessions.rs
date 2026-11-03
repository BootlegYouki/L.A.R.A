//! Sessions live in the `sessions` table: only the token's SHA-256 is stored, expired and
//! deactivated sessions are refused, and a Hub restart keeps everyone signed in.

mod common;

use std::time::Duration;

use common::TestHub;
use futures_util::{SinkExt, StreamExt};
use lara_hub::auth::token::token_hash;
use lara_hub::HubConfig;
use reqwest::Method;
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::Message;

fn code(body: &Value) -> Option<&str> {
    body["error"]["code"].as_str()
}

#[tokio::test]
async fn seeded_teacher_token_reaches_sync() {
    let hub = TestHub::start().await;
    let (status, body) = hub.post_json("/api/sync/pull", &json!({"cursor":0}), Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body)), (200, None));
    hub.stop().await;
}

#[tokio::test]
async fn token_is_stored_only_as_its_sha256() {
    let hub = TestHub::start().await;
    let token = hub.teacher_token().to_owned();
    let rows: Vec<(String, String, i64, i64)> =
        sqlx::query_as("SELECT token_hash, user_id, created_at, expires_at FROM sessions")
            .fetch_all(&hub.state().db)
            .await
            .unwrap();
    assert_eq!(rows.len(), 1);
    let (stored, user_id, created_at, expires_at) = &rows[0];
    assert_eq!(stored, &token_hash(&token));
    assert_ne!(stored, &token);
    assert_ne!(user_id, &token);
    assert_eq!(expires_at - created_at, 12 * 60 * 60 * 1000);
    hub.stop().await;
}

#[tokio::test]
async fn expired_token_is_401() {
    let hub = TestHub::start().await;
    sqlx::query("UPDATE sessions SET expires_at = 0").execute(&hub.state().db).await.unwrap();
    let (status, body) = hub.request(Method::POST, "/api/sync/pull", Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")));
    assert_eq!(body["error"]["message"], "Missing or expired token");
    hub.stop().await;
}

#[tokio::test]
async fn deactivated_users_token_is_401() {
    let hub = TestHub::start().await;
    sqlx::query("UPDATE users SET is_active = 0").execute(&hub.state().db).await.unwrap();
    let (status, body) = hub.request(Method::POST, "/api/sync/pull", Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")));
    hub.stop().await;
}

#[tokio::test]
async fn deactivated_users_websocket_hello_closes_4401() {
    let hub = TestHub::start().await;
    sqlx::query("UPDATE users SET is_active = 0").execute(&hub.state().db).await.unwrap();
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(Message::text(json!({"event": "EVENT_HELLO", "token": hub.teacher_token()}).to_string())).await.unwrap();
    let closed = tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap();
    match closed {
        Some(Ok(Message::Close(Some(frame)))) => assert_eq!(u16::from(frame.code), 4401),
        other => panic!("expected a 4401 close, got {other:?}"),
    }
    hub.stop().await;
}

#[tokio::test]
async fn sessions_survive_a_hub_restart() {
    let data_dir = tempfile::tempdir().unwrap();
    let config = || HubConfig { http_port: 0, ws_port: 0, ..HubConfig::new(data_dir.path()) };

    let hub = lara_hub::serve(config()).await.unwrap();
    let state = hub.state();
    let pin_hash = state.pins.hash("1234").await.unwrap();
    let mut tx = lara_hub::db::begin_immediate(&state.db).await.unwrap();
    let teacher =
        lara_hub::db::users::insert(&mut tx, "T-0001", "Teacher", lara_hub::auth::Role::Teacher, &pin_hash, state.clock.now_ms())
            .await
            .unwrap();
    let token = lara_hub::auth::sessions::open(&mut tx, teacher, state.clock.now_ms()).await.unwrap().token;
    tx.commit().await.unwrap();
    hub.shutdown().await;

    let hub = lara_hub::serve(config()).await.unwrap();
    let url = format!("http://127.0.0.1:{}/api/sync/pull", hub.http_addr().port());
    let res = reqwest::Client::new().post(url).bearer_auth(&token).json(&json!({"cursor":0})).send().await.unwrap();
    assert_eq!(res.status().as_u16(), 200, "the token still works after a restart");
    hub.shutdown().await;
}
