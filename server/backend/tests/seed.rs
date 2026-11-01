//! `LARA_TEST_SEED=1` (`HubConfig::test_seed`): the mock Hub's accounts, so CI can run
//! `tests/test_mock_hub.py` against the real Hub. Off unless set, so a school Hub never has them.

use std::path::Path;

use lara_hub::{HubConfig, HubHandle};
use serde_json::{json, Value};

async fn start(data_dir: &Path, test_seed: bool) -> HubHandle {
    lara_hub::serve(HubConfig { http_port: 0, ws_port: 0, test_seed, ..HubConfig::new(data_dir) }).await.unwrap()
}

async fn post(hub: &HubHandle, path: &str, body: Value) -> (u16, Value) {
    let res = reqwest::Client::new().post(format!("http://127.0.0.1:{}{path}", hub.http_addr().port())).json(&body).send().await.unwrap();
    let status = res.status().as_u16();
    (status, res.json().await.unwrap_or(Value::Null))
}

async fn user_count(hub: &HubHandle) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM users").fetch_one(&hub.state().db).await.unwrap()
}

/// The accounts of `scripts/mock_hub.py` `HubState._seed`, all with PIN 1234.
#[tokio::test]
async fn test_seed_creates_the_mock_accounts() {
    let dir = tempfile::tempdir().unwrap();
    let hub = start(dir.path(), true).await;
    let accounts = [
        ("ADMIN-0001", "Hub Admin", "ADMIN"),
        ("T-0001", "Maria Santos", "TEACHER"),
        ("123456789012", "Juan dela Cruz", "STUDENT"),
        ("123456789013", "Ana Reyes", "STUDENT"),
    ];
    for (lrn, name, role) in accounts {
        let (status, session) = post(&hub, "/api/auth/login", json!({"lrn_or_id": lrn, "pin": "1234"})).await;
        assert_eq!(status, 200, "{lrn}: {session}");
        assert_eq!((session["user"]["full_name"].as_str(), session["user"]["role"].as_str()), (Some(name), Some(role)), "{lrn}");
    }
    let (status, body) = post(&hub, "/api/admin/setup", json!({"full_name": "X", "pin": "4321"})).await;
    assert_eq!((status, body["error"]["code"].as_str()), (409, Some("ADMIN_EXISTS")), "as on the mock, which is seeded too");
    hub.shutdown().await;
}

#[tokio::test]
async fn a_restart_with_the_seed_keeps_one_of_each() {
    let dir = tempfile::tempdir().unwrap();
    start(dir.path(), true).await.shutdown().await;
    let hub = start(dir.path(), true).await;
    assert_eq!(user_count(&hub).await, 4);
    hub.shutdown().await;
}

#[tokio::test]
async fn without_the_setting_nothing_is_seeded() {
    let dir = tempfile::tempdir().unwrap();
    let hub = lara_hub::serve(HubConfig { http_port: 0, ws_port: 0, ..HubConfig::new(dir.path()) }).await.unwrap();
    assert_eq!(user_count(&hub).await, 0);
    let (status, _) = post(&hub, "/api/auth/login", json!({"lrn_or_id": "T-0001", "pin": "1234"})).await;
    assert_eq!(status, 401);
    hub.shutdown().await;
}
