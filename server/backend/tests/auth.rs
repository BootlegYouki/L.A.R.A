//! Register, login and logout against the mock's flows (`scripts/mock_hub.py`, ported from
//! `tests/test_mock_hub.py`), plus the failed-login limit (TECH_SPEC section 14, Q7).

mod common;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use common::TestHub;
use futures_util::{SinkExt, StreamExt};
use lara_hub::auth::Role;
use reqwest::Method;
use serde_json::{json, Value};
use tokio_tungstenite::tungstenite::Message;

fn code(body: &Value) -> Option<&str> {
    body["error"]["code"].as_str()
}

fn message(body: &Value) -> Option<&str> {
    body["error"]["message"].as_str()
}

fn learner(lrn: &str, pin: Value) -> Value {
    json!({"lrn_or_id": lrn, "full_name": "Ana Reyes", "pin": pin})
}

async fn register(hub: &TestHub, lrn: &str, pin: &str) -> (u16, Value) {
    hub.post_json("/api/auth/register", &learner(lrn, json!(pin)), None).await
}

async fn login(hub: &TestHub, lrn: &str, pin: &str) -> (u16, Value) {
    hub.post_json("/api/auth/login", &json!({"lrn_or_id": lrn, "pin": pin}), None).await
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

#[tokio::test]
async fn register_opens_a_learner_session() {
    let hub = TestHub::start().await;
    let (status, session) = register(&hub, "123456789012", "4821").await;
    assert_eq!(status, 200, "{session}");
    let token = session["token"].as_str().unwrap();
    assert_eq!(token.len(), 64);
    assert!((session["expires_at"].as_i64().unwrap() - (now_ms() + 43_200_000)).abs() < 5_000);
    assert_eq!(session["user"]["role"], "STUDENT");
    assert_eq!(session["user"]["lrn_or_id"], "123456789012");
    assert_eq!(session["user"]["full_name"], "Ana Reyes");
    assert!(!session.to_string().contains("pin_hash"));
    let (status, _) = hub.post_json("/api/sync/pull", &json!({"cursor":0}), Some(token)).await;
    assert_eq!(status, 200, "the new token is signed in");
    hub.stop().await;
}

#[tokio::test]
async fn register_records_a_users_revision() {
    let hub = TestHub::start().await;
    let (_, session) = register(&hub, "123456789012", "4821").await;
    let rows: Vec<(String, Option<String>, Option<String>)> =
        sqlx::query_as("SELECT entity_table, classroom_id, student_id FROM sync_revisions WHERE entity_id = ?")
            .bind(session["user"]["id"].as_str().unwrap())
            .fetch_all(&hub.state().db)
            .await
            .unwrap();
    assert_eq!(rows, [("users".to_string(), None, None)]);
    hub.stop().await;
}

#[tokio::test]
async fn register_twice_is_409_already_exists() {
    let hub = TestHub::start().await;
    assert_eq!(register(&hub, "123456789012", "4821").await.0, 200);
    let (status, body) = register(&hub, "123456789012", "1111").await;
    assert_eq!((status, code(&body), message(&body)), (409, Some("ALREADY_EXISTS"), Some("Account already exists")));
    let (status, body) = register(&hub, "T-0001", "1111").await;
    assert_eq!((status, code(&body)), (409, Some("ALREADY_EXISTS")), "a teacher's ID is taken too");
    hub.stop().await;
}

#[tokio::test]
async fn concurrent_duplicate_register_is_one_200_one_409() {
    let hub = TestHub::start().await;
    let (a, b) = tokio::join!(register(&hub, "123456789012", "4821"), register(&hub, "123456789012", "4821"));
    let mut statuses = [a.0, b.0];
    statuses.sort();
    assert_eq!(statuses, [200, 409], "{a:?} {b:?}");
    hub.stop().await;
}

#[tokio::test]
async fn register_rejects_bad_pins() {
    let hub = TestHub::start().await;
    for pin in ["123", "12a4", "١٢٣٤", "12345", ""] {
        let (status, body) = register(&hub, "123456789012", pin).await;
        assert_eq!((status, code(&body), message(&body)), (400, Some("BAD_REQUEST"), Some("PIN must be 4 digits")), "{pin:?}");
    }
    hub.stop().await;
}

/// Contract PR #197: both are trimmed, then 1-32 and 1-100 characters, as in the mock.
#[tokio::test]
async fn register_trims_and_limits_the_lrn_and_name() {
    let hub = TestHub::start().await;
    let bad = [
        json!({"lrn_or_id": " ", "full_name": "A", "pin": "1234"}),
        json!({"lrn_or_id": "999", "full_name": "", "pin": "1234"}),
        json!({"lrn_or_id": "9".repeat(33), "full_name": "A", "pin": "1234"}),
        json!({"lrn_or_id": "999", "full_name": "A".repeat(101), "pin": "1234"}),
    ];
    for body in bad {
        let (status, err) = hub.post_json("/api/auth/register", &body, None).await;
        let expected = (400, Some("BAD_REQUEST"), Some("LRN and name must be 1-32 and 1-100 characters"));
        assert_eq!((status, code(&err), message(&err)), expected, "{body}");
    }
    // Characters, not bytes: a 100-letter name with ñ is 200 bytes.
    let longest = json!({"lrn_or_id": format!(" {} ", "9".repeat(32)), "full_name": format!("  {}  ", "ñ".repeat(100)), "pin": "1234"});
    let (status, session) = hub.post_json("/api/auth/register", &longest, None).await;
    assert_eq!(status, 200, "{session}");
    assert_eq!((session["user"]["lrn_or_id"].as_str(), session["user"]["full_name"].as_str()), (Some(&*"9".repeat(32)), Some(&*"ñ".repeat(100))));
    assert_eq!(login(&hub, &"9".repeat(32), "1234").await.0, 200, "stored trimmed");
    let (status, err) = hub.post_json("/api/auth/register", &learner(" ADMIN-0001 ", json!("1234")), None).await;
    assert_eq!((status, code(&err)), (409, Some("ALREADY_EXISTS")), "the admin ID stays reserved");
    hub.stop().await;
}

#[tokio::test]
async fn register_accepts_a_numeric_pin() {
    let hub = TestHub::start().await;
    let (status, body) = hub.post_json("/api/auth/register", &learner("123456789012", json!(4821)), None).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(login(&hub, "123456789012", "4821").await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn missing_fields_is_400() {
    let hub = TestHub::start().await;
    let (status, body) = hub.post_json("/api/auth/login", &json!({}), None).await;
    assert_eq!((status, code(&body), message(&body)), (400, Some("BAD_REQUEST"), Some("Missing: lrn_or_id, pin")));
    let (status, body) = hub.post_json("/api/auth/register", &json!({"pin": "1234"}), None).await;
    assert_eq!((status, message(&body)), (400, Some("Missing: lrn_or_id, full_name")));
    hub.stop().await;
}

#[tokio::test]
async fn invalid_json_is_400() {
    let hub = TestHub::start().await;
    for raw in ["{not json", "[1, 2]"] {
        let (status, body) = hub.post_raw("/api/auth/login", raw).await;
        assert_eq!((status, code(&body), message(&body)), (400, Some("BAD_REQUEST"), Some("Invalid JSON")), "{raw}");
    }
    hub.stop().await;
}

#[tokio::test]
async fn login_and_wrong_pin() {
    let hub = TestHub::start().await;
    let (status, session) = login(&hub, "T-0001", "1234").await;
    assert_eq!(status, 200, "{session}");
    assert_eq!(session["user"]["role"], "TEACHER");
    for (lrn, pin) in [("T-0001", "9999"), ("NOBODY", "1234"), ("T-0001", "12")] {
        let (status, body) = login(&hub, lrn, pin).await;
        assert_eq!((status, code(&body), message(&body)), (401, Some("UNAUTHORIZED"), Some("Wrong LRN or PIN")), "{lrn} {pin}");
    }
    hub.stop().await;
}

#[tokio::test]
async fn deactivated_account_cannot_log_in() {
    let hub = TestHub::start().await;
    sqlx::query("UPDATE users SET is_active = 0 WHERE lrn_or_id = 'T-0001'").execute(&hub.state().db).await.unwrap();
    let (status, body) = login(&hub, "T-0001", "1234").await;
    assert_eq!((status, message(&body)), (401, Some("Wrong LRN or PIN")));
    hub.stop().await;
}

#[tokio::test]
async fn logout_kills_the_token() {
    let hub = TestHub::start().await;
    let token = hub.login("T-0001", "1234").await;
    let (status, body) = hub.post_json("/api/auth/logout", &json!({}), Some(&token)).await;
    assert_eq!((status, body), (200, json!({"success": true})));

    let (status, body) = hub.request(Method::POST, "/api/sync/pull", Some(&token)).await;
    assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")));
    let (status, _) = hub.post_json("/api/auth/logout", &json!({}), Some(&token)).await;
    assert_eq!(status, 401, "logging out twice needs a live token");

    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(Message::text(json!({"event": "EVENT_HELLO", "token": token}).to_string())).await.unwrap();
    match tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap() {
        Some(Ok(Message::Close(Some(frame)))) => assert_eq!(u16::from(frame.code), 4401),
        other => panic!("expected a 4401 close, got {other:?}"),
    }
    let (status, _) = hub.post_json("/api/sync/pull", &json!({"cursor":0}), Some(hub.teacher_token())).await;
    assert_eq!(status, 200, "other sessions of the same user stay signed in");
    hub.stop().await;
}

#[tokio::test]
async fn sixth_failed_login_in_a_minute_is_429() {
    let hub = TestHub::start().await;
    register(&hub, "123456789012", "4821").await;
    register(&hub, "123456789013", "4821").await;
    for i in 0..5 {
        assert_eq!(login(&hub, "123456789012", "0000").await.0, 401, "attempt {i}");
    }
    let (status, headers, body) = hub
        .post_json_with_headers("/api/auth/login", &json!({"lrn_or_id": "123456789012", "pin": "4821"}), None)
        .await;
    assert_eq!((status, code(&body)), (429, Some("TOO_MANY_ATTEMPTS")), "even the right PIN waits");
    let wait: u64 = headers["retry-after"].to_str().unwrap().parse().unwrap();
    assert!((1..=60).contains(&wait), "{wait}");
    assert_eq!(login(&hub, "123456789013", "4821").await.0, 200, "another learner is not locked out");
    hub.stop().await;
}

#[tokio::test]
async fn success_resets_the_count() {
    let hub = TestHub::start().await;
    for _ in 0..4 {
        assert_eq!(login(&hub, "T-0001", "0000").await.0, 401);
    }
    assert_eq!(login(&hub, "T-0001", "1234").await.0, 200);
    for i in 0..5 {
        assert_eq!(login(&hub, "T-0001", "0000").await.0, 401, "attempt {i} after a success");
    }
    hub.stop().await;
}

#[tokio::test]
async fn pins_never_reach_the_database() {
    let hub = TestHub::start().await;
    assert_eq!(register(&hub, "123456789012", "4821").await.0, 200);
    let rows: Vec<(String, String, String, String, String)> =
        sqlx::query_as("SELECT id, lrn_or_id, full_name, role, pin_hash FROM users").fetch_all(&hub.state().db).await.unwrap();
    assert_eq!(rows.len(), 2, "the seeded teacher and the new learner");
    for (id, lrn, name, role, pin_hash) in rows {
        assert!(pin_hash.starts_with("$argon2id$"), "{lrn}");
        assert!(![id, lrn, name, role, pin_hash].iter().any(|v| v == "4821" || v == "1234"));
    }
    let sessions: Vec<(String, String)> =
        sqlx::query_as("SELECT token_hash, user_id FROM sessions").fetch_all(&hub.state().db).await.unwrap();
    assert!(sessions.iter().all(|(t, u)| t != "4821" && u != "4821"));
    hub.stop().await;
}

#[tokio::test]
async fn teacher_on_admin_routes_is_403() {
    let hub = TestHub::start().await;
    let (status, body) = hub.get("/api/admin/users", Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body), message(&body)), (403, Some("FORBIDDEN"), Some("Hub admin only")));
    hub.stop().await;
}

#[tokio::test]
async fn admin_is_not_a_teacher() {
    let hub = TestHub::start().await;
    let admin = hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "1234").await;
    let token = hub.open_session(admin).await;
    let (status, body) = hub.post_json("/api/classrooms", &json!({"name": "x", "section": "y"}), Some(&token)).await;
    assert_eq!((status, code(&body), message(&body)), (403, Some("FORBIDDEN"), Some("Teacher only")));
    hub.stop().await;
}

#[tokio::test]
async fn backup_and_restore_are_admin_only() {
    let hub = TestHub::start().await;
    let admin = hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "1234").await;
    let admin = hub.open_session(admin).await;
    for path in ["/api/backup", "/api/restore"] {
        let (status, body) = hub.request(Method::POST, path, Some(hub.teacher_token())).await;
        assert_eq!((status, message(&body)), (403, Some("Hub admin only")), "{path}");
        let (status, body) = hub.request(Method::POST, path, Some(&admin)).await;
        assert_eq!((status, code(&body)), (501, Some("NOT_IMPLEMENTED")), "{path}");
    }
    hub.stop().await;
}
