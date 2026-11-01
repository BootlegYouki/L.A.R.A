//! `POST /api/admin/setup` (PRD FR-1.0): the single Hub admin, created once, only from the Hub
//! PC itself. Ported from the mock's `test_admin_is_its_own_account_and_setup_runs_once`.

mod common;

use common::TestHub;
use serde_json::{json, Value};

fn code(body: &Value) -> Option<&str> {
    body["error"]["code"].as_str()
}

async fn setup(hub: &TestHub, pin: &str) -> (u16, Value) {
    hub.post_json("/api/admin/setup", &json!({"full_name": "ICT Coordinator", "pin": pin}), None).await
}

#[tokio::test]
async fn setup_creates_the_admin_once() {
    let hub = TestHub::start().await;
    let (status, session) = setup(&hub, "4321").await;
    assert_eq!(status, 200, "{session}");
    assert_eq!(session["user"]["role"], "ADMIN");
    assert_eq!(session["user"]["lrn_or_id"], "ADMIN-0001");
    assert_eq!(session["user"]["full_name"], "ICT Coordinator");
    assert_eq!(session["token"].as_str().unwrap().len(), 64);

    let (status, body) = setup(&hub, "9999").await;
    assert_eq!((status, code(&body)), (409, Some("ADMIN_EXISTS")));
    assert_eq!(body["error"]["message"], "The Hub admin is already set up");
    hub.stop().await;
}

/// Before the school runs setup, a learner on the network must not be able to take the admin's
/// fixed ID: setup could then never succeed.
#[tokio::test]
async fn a_learner_cannot_take_the_admin_id_before_setup() {
    let hub = TestHub::start().await;
    let (status, body) = hub
        .post_json("/api/auth/register", &json!({"lrn_or_id": "ADMIN-0001", "full_name": "Not Admin", "pin": "1234"}), None)
        .await;
    assert_eq!((status, code(&body)), (409, Some("ALREADY_EXISTS")));
    assert_eq!(setup(&hub, "4321").await.0, 200, "setup still works");
    hub.stop().await;
}

#[tokio::test]
async fn admin_exists_wins_over_a_bad_pin() {
    let hub = TestHub::start().await;
    assert_eq!(setup(&hub, "4321").await.0, 200);
    let (status, body) = setup(&hub, "1").await;
    assert_eq!((status, code(&body)), (409, Some("ADMIN_EXISTS")));
    hub.stop().await;
}

#[tokio::test]
async fn setup_checks_its_fields() {
    let hub = TestHub::start().await;
    let (status, body) = hub.post_json("/api/admin/setup", &json!({"pin": "4321"}), None).await;
    assert_eq!((status, body["error"]["message"].as_str()), (400, Some("Missing: full_name")));
    let (status, body) = setup(&hub, "12a4").await;
    assert_eq!((status, body["error"]["message"].as_str()), (400, Some("PIN must be 4 digits")));
    hub.stop().await;
}

#[tokio::test]
async fn admin_reaches_admin_routes_and_is_never_synced() {
    let hub = TestHub::start().await;
    let (_, session) = setup(&hub, "4321").await;
    let token = session["token"].as_str().unwrap();
    let (status, body) = hub.get("/api/admin/users", Some(token)).await;
    assert_eq!((status, code(&body)), (501, Some("NOT_IMPLEMENTED")));
    let revisions: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM sync_revisions WHERE entity_id = ?")
        .bind(session["user"]["id"].as_str().unwrap())
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(revisions, 0, "the admin is never synced to a client");
    hub.stop().await;
}

#[tokio::test]
async fn admin_can_log_in_after_setup() {
    let hub = TestHub::start().await;
    setup(&hub, "4321").await;
    let (status, session) = hub.post_json("/api/auth/login", &json!({"lrn_or_id": "ADMIN-0001", "pin": "4321"}), None).await;
    assert_eq!((status, session["user"]["role"].as_str()), (200, Some("ADMIN")));
    hub.stop().await;
}

#[tokio::test]
async fn setup_from_the_network_is_403() {
    let hub = TestHub::start().await;
    let Some(ip) = lara_hub::discovery::lan_ip() else {
        eprintln!("skipped: this machine has no LAN address to call the Hub from");
        return hub.stop().await;
    };
    let url = format!("http://{ip}:{}/api/admin/setup", hub.http_port());
    let res = reqwest::Client::new().post(url).json(&json!({"full_name": "X", "pin": "4321"})).send().await.unwrap();
    assert_eq!(res.status().as_u16(), 403);
    let body: Value = res.json().await.unwrap();
    assert_eq!(code(&body), Some("FORBIDDEN"));
    let admins: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'").fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(admins, 0);
    hub.stop().await;
}

#[tokio::test]
async fn concurrent_setups_make_one_admin() {
    let hub = TestHub::start().await;
    let (a, b) = tokio::join!(setup(&hub, "4321"), setup(&hub, "8765"));
    let mut statuses = [a.0, b.0];
    statuses.sort();
    assert_eq!(statuses, [200, 409], "{a:?} {b:?}");
    let admins: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'").fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(admins, 1);
    hub.stop().await;
}
