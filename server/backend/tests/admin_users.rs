mod common;

use common::TestHub;
use serde_json::{json, Value};

async fn admin(hub: &TestHub) -> String {
    let (status, session) = hub.post_json("/api/admin/setup", &json!({"full_name": "ICT Coordinator", "pin": "4321"}), None).await;
    assert_eq!(status, 200);
    session["token"].as_str().unwrap().to_owned()
}

fn teacher() -> Value {
    json!({"lrn_or_id": "T-0002", "full_name": "New Teacher", "role": "TEACHER", "pin": "0284"})
}

#[tokio::test]
async fn created_accounts_sign_in_and_reset_pin_survives_restart() {
    let hub = TestHub::start().await;
    let token = admin(&hub).await;
    let mut created_ids = Vec::new();
    for body in [teacher(), json!({"lrn_or_id": "123456789014", "full_name": "New Learner", "role": "STUDENT", "pin": "0284"})] {
        let (status, created) = hub.post_json("/api/admin/users", &body, Some(&token)).await;
        assert_eq!(status, 200, "{created}");
        assert_eq!(created.as_object().unwrap().len(), 4, "only User fields leave the Hub");
        assert_eq!(created["role"], body["role"]);
        hub.login(body["lrn_or_id"].as_str().unwrap(), "0284").await;
        let id = created["id"].as_str().unwrap().to_owned();
        let hash: String = sqlx::query_scalar("SELECT pin_hash FROM users WHERE id = ?")
            .bind(&id).fetch_one(&hub.state().db).await.unwrap();
        assert!(hash.starts_with("$argon2id$"));
        let revisions: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM sync_revisions WHERE entity_id = ?")
            .bind(&id).fetch_one(&hub.state().db).await.unwrap();
        assert_eq!(revisions, 1);
        created_ids.push(id);
    }
    let (status, users) = hub.get("/api/admin/users", Some(&token)).await;
    assert_eq!(status, 200);
    assert_eq!(users.as_array().unwrap().len(), 4);
    for user in users.as_array().unwrap() {
        assert_eq!(user.as_object().unwrap().len(), 4);
        assert!(user.get("pin").is_none() && user.get("pin_hash").is_none());
    }
    let path = format!("/api/admin/users/{}/reset-pin", created_ids[0]);
    assert_eq!(hub.post_json(&path, &json!({"pin": "0907"}), Some(&token)).await, (200, json!({"success": true})));
    let hub = hub.restart().await;
    assert_eq!(hub.post_json("/api/auth/login", &json!({"lrn_or_id": "T-0002", "pin": "0284"}), None).await.0, 401);
    hub.login("T-0002", "0907").await;
    hub.login("123456789014", "0284").await;
    assert_eq!(hub.get("/api/admin/users", Some(&token)).await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn account_routes_require_admin_and_do_not_change_data_for_other_roles() {
    let hub = TestHub::start().await;
    let token = admin(&hub).await;
    let learner = hub.seed_user("123456789014", "Learner", lara_hub::auth::Role::Student, "1234").await;
    let learner_token = hub.open_session(learner).await;
    let (status, users) = hub.get("/api/admin/users", Some(&token)).await;
    assert_eq!(status, 200);
    let id = users.as_array().unwrap().iter().find(|u| u["lrn_or_id"] == "T-0001").unwrap()["id"].as_str().unwrap();
    for (token, expected) in [(None, 401), (Some(hub.teacher_token()), 403), (Some(learner_token.as_str()), 403)] {
        assert_eq!(hub.get("/api/admin/users", token).await.0, expected);
        assert_eq!(hub.post_json("/api/admin/users", &teacher(), token).await.0, expected);
        assert_eq!(hub.post_json(&format!("/api/admin/users/{id}/reset-pin"), &json!({"pin": "9999"}), token).await.0, expected);
    }
    hub.login("T-0001", "1234").await;
    assert_eq!(hub.get("/api/admin/users", Some(&token)).await.1, users);
    hub.stop().await;
}

#[tokio::test]
async fn invalid_input_duplicates_and_missing_users_are_safe() {
    let hub = TestHub::start().await;
    let token = admin(&hub).await;
    for (field, value) in [("role", json!("ADMIN")), ("role", json!("teacher")), ("pin", json!("123")), ("pin", json!("12a4")), ("full_name", json!(null)), ("lrn_or_id", json!(""))] {
        let mut input = teacher();
        input[field] = value;
        assert_eq!(hub.post_json("/api/admin/users", &input, Some(&token)).await.0, 400, "{field}");
    }
    assert_eq!(hub.post_json("/api/admin/users", &json!({}), Some(&token)).await.0, 400);
    let input = teacher();
    let (a, b) = tokio::join!(hub.post_json("/api/admin/users", &input, Some(&token)), hub.post_json("/api/admin/users", &input, Some(&token)));
    let mut statuses = [a.0, b.0];
    statuses.sort();
    assert_eq!(statuses, [200, 409]);
    let users = hub.get("/api/admin/users", Some(&token)).await.1;
    let id = users.as_array().unwrap().iter().find(|u| u["lrn_or_id"] == "T-0002").unwrap()["id"].as_str().unwrap();
    let path = format!("/api/admin/users/{id}/reset-pin");
    for pin in [json!(""), json!("123"), json!("１２３４"), json!(null)] {
        assert_eq!(hub.post_json(&path, &json!({"pin": pin}), Some(&token)).await.0, 400);
    }
    assert_eq!(hub.post_json("/api/admin/users/missing/reset-pin", &json!({"pin": "1234"}), Some(&token)).await.0, 404);
    hub.login("T-0002", "0284").await;
    hub.stop().await;
}

#[tokio::test]
async fn cors_allows_the_local_window_but_not_other_web_origins() {
    let hub = TestHub::start().await;
    let url = format!("http://127.0.0.1:{}/api/admin/users", hub.http_port());
    for origin in ["tauri://localhost", "http://tauri.localhost", "https://tauri.localhost", "http://localhost:5173", "http://127.0.0.1:5173", "https://untrusted.invalid"] {
        let response = reqwest::Client::new().request(reqwest::Method::OPTIONS, &url)
            .header("Origin", origin).header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "authorization,content-type").send().await.unwrap();
        assert!(response.status().is_success());
        let allowed = response.headers().get("Access-Control-Allow-Origin").and_then(|v| v.to_str().ok());
        assert_eq!(allowed, if origin == "https://untrusted.invalid" { None } else { Some(origin) });
    }
    // CORS grants no authentication: a permitted origin still needs an admin bearer session.
    let response = reqwest::Client::new().get(url).header("Origin", "http://tauri.localhost").send().await.unwrap();
    assert_eq!(response.status(), 401);
    hub.stop().await;
}
