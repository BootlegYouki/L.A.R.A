mod common;

use common::TestHub;
use lara_hub::auth::Role;
use serde_json::{json, Value};

#[tokio::test]
async fn all_pin_write_routes_require_exactly_six_ascii_digits() {
    let hub = TestHub::start().await;
    let admin = hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "654321").await;
    let admin_token = hub.open_session(admin).await;
    let teacher_id: String = sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = 'T-0001'")
        .fetch_one(&hub.state().db).await.unwrap();
    let reset_path = format!("/api/admin/users/{teacher_id}/reset-pin");
    let fresh = TestHub::start().await;

    for pin in ["1234", "12345", "1234567", "12a456", "１２３４５６", "١٢٣٤٥٦", " 123456", "123456 ", ""] {
        for (target, path, body, token) in [
            (&fresh, "/api/admin/setup", json!({"full_name": "Admin", "pin": pin}), None),
            (&hub, "/api/auth/register", json!({"lrn_or_id": "123456789012", "full_name": "Learner", "pin": pin}), None),
            (&hub, "/api/admin/users", json!({"lrn_or_id": "T-NEW", "full_name": "Teacher", "role": "TEACHER", "pin": pin}), Some(admin_token.as_str())),
            (&hub, reset_path.as_str(), json!({"pin": pin}), Some(admin_token.as_str())),
        ] {
            let (status, response) = target.post_json(path, &body, token).await;
            assert_eq!(status, 400, "{path}: {response}");
            assert_eq!(response["error"]["code"], "BAD_REQUEST");
            assert_eq!(response["error"]["message"], "PIN must be 6 digits");
        }
    }

    for (target, path, body, token) in [
        (&fresh, "/api/admin/setup", json!({"full_name": "Admin", "pin": "000000"}), None),
        (&hub, "/api/auth/register", json!({"lrn_or_id": "123456789012", "full_name": "Learner", "pin": "000000"}), None),
        (&hub, "/api/admin/users", json!({"lrn_or_id": "T-NEW", "full_name": "Teacher", "role": "TEACHER", "pin": "000000"}), Some(admin_token.as_str())),
        (&hub, reset_path.as_str(), json!({"pin": "000000"}), Some(admin_token.as_str())),
    ] {
        let (status, response) = target.post_json(path, &body, token).await;
        assert_eq!(status, 200, "{path}: {response}");
    }
    hub.login("T-0001", "000000").await;
    hub.login("T-NEW", "000000").await;
    hub.login("123456789012", "000000").await;
    fresh.login("ADMIN-0001", "000000").await;
    fresh.stop().await;
    hub.stop().await;
}

#[tokio::test]
async fn legacy_pin_requires_admin_reset_and_existing_sessions_end_on_reset() {
    let hub = TestHub::start().await;
    let legacy = hub.seed_user("T-LEGACY", "Legacy Teacher", Role::Teacher, "1234").await;
    let user_id = legacy.id.clone();
    let old_token = hub.open_session(legacy).await;
    let old_hash: String = sqlx::query_scalar("SELECT pin_hash FROM users WHERE id = ?")
        .bind(&user_id).fetch_one(&hub.state().db).await.unwrap();

    for pin in ["1234", "001234", "123456"] {
        let (status, response) = hub.post_json("/api/auth/login", &json!({"lrn_or_id": "T-LEGACY", "pin": pin}), None).await;
        assert_eq!(status, 401, "{response}");
        assert_eq!(response["error"]["code"], "UNAUTHORIZED");
    }
    let unchanged: String = sqlx::query_scalar("SELECT pin_hash FROM users WHERE id = ?")
        .bind(&user_id).fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(old_hash, unchanged);
    assert_eq!(hub.post_json("/api/sync/pull", &json!({"cursor": 0}), Some(&old_token)).await.0, 200);

    let admin = hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "654321").await;
    let admin_token = hub.open_session(admin).await;
    let (status, response) = hub.post_json(&format!("/api/admin/users/{user_id}/reset-pin"), &json!({"pin": "001234"}), Some(&admin_token)).await;
    assert_eq!((status, response), (200, json!({"success": true})));
    assert_eq!(hub.post_json("/api/sync/pull", &json!({"cursor": 0}), Some(&old_token)).await.0, 401);
    let token = hub.login("T-LEGACY", "001234").await;
    let (status, response): (u16, Value) = hub.post_json("/api/sync/pull", &json!({"cursor": 0}), Some(&token)).await;
    assert_eq!(status, 200, "{response}");
    hub.stop().await;
}
