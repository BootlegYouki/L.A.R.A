mod common;

use common::TestHub;
use lara_hub::db;
use serde_json::json;
use std::time::Duration;

#[tokio::test]
async fn requests_capture_outcomes_templates_preflight_and_repeated_rejections_without_secrets() {
    let hub = TestHub::start_seeded().await;
    let admin = hub.login("ADMIN-0001", "123456").await;
    let client = reqwest::Client::new();
    let base = format!("http://127.0.0.1:{}", hub.http_port());
    let cases = [
        ("GET", "/download?token=QUERY_SECRET", "/download", 200),
        (
            "GET",
            "/api/materials/123456789012/chunks?token=QUERY_SECRET",
            "/api/materials/{id}/chunks",
            401,
        ),
        ("GET", "/api/classrooms", "/api/classrooms", 401),
        ("GET", "/api/classrooms", "/api/classrooms", 401),
        (
            "GET",
            "/PRIVATE_PATH_123456789012?token=QUERY_SECRET",
            "UNMATCHED",
            404,
        ),
        ("DELETE", "/api/admin/users", "/api/admin/users", 404),
        ("OPTIONS", "/api/classrooms", "/api/classrooms", 200),
        (
            "PRIVATE_METHOD",
            "/PRIVATE_PATH_123456789012",
            "UNMATCHED",
            404,
        ),
    ];
    for (method, path, _, status) in cases {
        let response = client
            .request(
                reqwest::Method::from_bytes(method.as_bytes()).unwrap(),
                format!("{base}{path}"),
            )
            .header("origin", "http://tauri.localhost")
            .header("access-control-request-method", "GET")
            .header("x-private-header", "HEADER_SECRET")
            .send()
            .await
            .unwrap();
        assert_eq!(response.status().as_u16(), status, "{method} {path}");
    }
    let failed_login = client
        .post(format!("{base}/api/auth/login"))
        .json(&json!({"lrn_or_id":"123456789012", "pin":"918273", "extra":"BODY_SECRET"}))
        .send()
        .await
        .unwrap();
    assert_eq!(failed_login.status(), 401);
    // A partial upload is rejected without the recorder buffering its contents.
    let malformed = client
        .post(format!("{base}/api/sync/pull"))
        .bearer_auth(hub.teacher_token())
        .header("content-type", "application/json")
        .body("BODY_SECRET")
        .send()
        .await
        .unwrap();
    assert_eq!(malformed.status(), 400);

    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let recorded: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE event_code='HTTP_REQUEST' AND endpoint='UNMATCHED'")
                .fetch_one(&hub.state().db).await.unwrap();
            let invalid: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE endpoint='/api/sync/pull' AND status_code=400")
                .fetch_one(&hub.state().db).await.unwrap();
            if recorded == 2 && invalid == 1 { break; }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    }).await.expect("HTTP request entries did not flush");

    let (status, page) = hub
        .get("/api/admin/activity-logs?q=http%20request", Some(&admin))
        .await;
    assert_eq!(status, 200);
    let entries = page["entries"].as_array().unwrap();
    for (method, _, endpoint, status) in cases {
        let safe_method = if method == "PRIVATE_METHOD" {
            "OTHER"
        } else {
            method
        };
        let entry = entries
            .iter()
            .find(|entry| {
                entry["details"]["http_method"] == safe_method
                    && entry["details"]["endpoint"] == endpoint
                    && entry["details"]["status_code"] == status
            })
            .unwrap();
        assert!(entry["details"]["duration_ms"].as_i64().unwrap() >= 0);
        assert_eq!(
            entry["severity"],
            if status >= 400 { "WARNING" } else { "INFO" }
        );
        assert_eq!(
            entry["result"],
            if status >= 400 { "FAILED" } else { "SUCCEEDED" }
        );
        assert!(entry.to_string().len() <= 1024);
    }
    assert_eq!(
        entries
            .iter()
            .filter(|entry| entry["details"]["http_method"] == "GET"
                && entry["details"]["endpoint"] == "/api/classrooms"
                && entry["details"]["status_code"] == 401)
            .count(),
        2
    );
    let serialized = page.to_string();
    for secret in [
        "QUERY_SECRET",
        "BODY_SECRET",
        "HEADER_SECRET",
        "PRIVATE_PATH",
        "PRIVATE_METHOD",
        "123456789012",
        "918273",
        &admin,
        hub.teacher_token(),
    ] {
        assert!(
            !serialized.contains(secret),
            "request history leaked a private input"
        );
    }
    tokio::time::sleep(Duration::from_millis(600)).await;
    let (_, search) = hub
        .get(
            "/api/admin/activity-logs?q=%2Fapi%2Fadmin%2Factivity-logs",
            Some(&admin),
        )
        .await;
    assert!(search["entries"].as_array().unwrap().is_empty(), "activity reads must not record themselves");
    hub.stop().await;
}

#[tokio::test]
async fn account_reads_are_excluded_but_creation_and_pin_resets_are_recorded() {
    let hub = TestHub::start_seeded().await;
    let admin = hub.login("ADMIN-0001", "123456").await;
    sqlx::query("INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service,http_method,endpoint,status_code,duration_ms) VALUES (?,'HTTP_REQUEST','INFO','SUCCEEDED','HTTP','GET','/api/admin/users',200,1)")
        .bind(hub.state().clock.now_ms()).execute(&hub.state().db).await.unwrap();
    sqlx::query("INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service,http_method,endpoint,status_code,duration_ms) VALUES (?,'HTTP_REQUEST','INFO','SUCCEEDED','HTTP','OPTIONS','/api/admin/users',200,1)")
        .bind(hub.state().clock.now_ms()).execute(&hub.state().db).await.unwrap();
    for token in [Some(admin.as_str()), None, Some(admin.as_str())] {
        let (status, _) = hub.get("/api/admin/users", token).await;
        assert_eq!(status, if token.is_some() { 200 } else { 401 });
    }
    let client = reqwest::Client::new();
    let url = format!("http://127.0.0.1:{}/api/admin/users", hub.http_port());
    for intended in ["GET", "POST"] {
        assert_eq!(client.request(reqwest::Method::OPTIONS, &url).header("origin", "http://tauri.localhost")
            .header("access-control-request-method", intended).send().await.unwrap().status(), 200);
    }
    let (status, created) = hub.post_json("/api/admin/users", &json!({"lrn_or_id":"T-8901","full_name":"Logging fixture","role":"TEACHER","pin":"123456"}), Some(&admin)).await;
    assert_eq!(status, 200);
    let (status, _) = hub.post_json(&format!("/api/admin/users/{}/reset-pin", created["id"].as_str().unwrap()), &json!({"pin":"654321"}), Some(&admin)).await;
    assert_eq!(status, 200);
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE http_method='POST' AND endpoint IN ('/api/admin/users','/api/admin/users/{id}/reset-pin')")
                .fetch_one(&hub.state().db).await.unwrap();
            if count == 2 { break; }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    }).await.unwrap();
    let (_, page) = hub.get("/api/admin/activity-logs?q=%2Fapi%2Fadmin%2Fusers", Some(&admin)).await;
    let entries = page["entries"].as_array().unwrap();
    assert_eq!(entries.len(), 2);
    assert!(entries.iter().all(|entry| entry["details"]["http_method"] == "POST" && entry["details"]["status_code"] == 200));
    let old_reads: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE endpoint='/api/admin/users' AND http_method='GET'")
        .fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(old_reads, 1, "the legacy read is preserved, but no new read is recorded");
    let preflights: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE endpoint='/api/admin/users' AND http_method='OPTIONS'")
        .fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(preflights, 1, "only the preserved legacy preflight may exist; new preflights must not create activity noise");
    hub.stop().await;
}

#[tokio::test]
async fn viewer_requests_are_excluded_and_legacy_rows_hidden_without_deleting_history() {
    let hub = TestHub::start_seeded().await;
    let admin = hub.login("ADMIN-0001", "123456").await;
    let now = hub.state().clock.now_ms();
    let legacy = sqlx::query("INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service,http_method,endpoint,status_code,duration_ms) VALUES (?,'HTTP_REQUEST','INFO','SUCCEEDED','HTTP','GET','/api/admin/activity-logs',200,1)")
        .bind(now).execute(&hub.state().db).await.unwrap().last_insert_rowid();
    let client = reqwest::Client::new();
    let url = format!("http://127.0.0.1:{}/api/admin/activity-logs", hub.http_port());
    assert_eq!(client.get(&url).send().await.unwrap().status(), 401);
    assert_eq!(client.post(&url).send().await.unwrap().status(), 404);
    assert_eq!(client.request(reqwest::Method::OPTIONS, &url).header("origin", "http://tauri.localhost")
        .header("access-control-request-method", "GET").send().await.unwrap().status(), 200);
    for _ in 0..3 {
        let (status, page) = hub.get("/api/admin/activity-logs", Some(&admin)).await;
        assert_eq!(status, 200);
        assert!(page["high_water_seq"].as_i64().unwrap() >= legacy);
        assert!(page["entries"].as_array().unwrap().iter().all(|entry| entry["details"]["endpoint"] != "/api/admin/activity-logs"));
    }
    tokio::time::sleep(Duration::from_millis(700)).await;
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE endpoint='/api/admin/activity-logs'")
        .fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(count, 1, "only the preserved legacy row may exist; no viewer request may be recorded");
    let (_, search) = hub.get("/api/admin/activity-logs?q=%2Fapi%2Fadmin%2Factivity-logs", Some(&admin)).await;
    assert!(search["entries"].as_array().unwrap().is_empty());
    hub.stop().await;
}

#[tokio::test]
async fn request_migration_preserves_existing_history_identity_and_pruned_sequence_high_water() {
    let dir = tempfile::tempdir().unwrap();
    let pool = db::connect(dir.path()).await.unwrap();
    let mut old = sqlx::migrate!("src/db/migrations");
    old.migrations = old
        .migrations
        .iter()
        .filter(|migration| migration.version <= 6)
        .cloned()
        .collect::<Vec<_>>()
        .into();
    old.run(&pool).await.unwrap();
    sqlx::raw_sql("INSERT INTO hub_activity_state(singleton,history_id) VALUES (1,'00000000-0000-4000-8000-000000000001');
        INSERT INTO hub_activity_events(seq,occurred_at,event_code,severity,result,service) VALUES (7,1000,'HUB_STARTED','INFO','SUCCEEDED','HUB');
        UPDATE sqlite_sequence SET seq=9000 WHERE name='hub_activity_events';")
        .execute(&pool).await.unwrap();
    db::migrate(&pool).await.unwrap();
    let identity: String = sqlx::query_scalar("SELECT history_id FROM hub_activity_state")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(identity, "00000000-0000-4000-8000-000000000001");
    let old_event: String =
        sqlx::query_scalar("SELECT event_code FROM hub_activity_events WHERE seq=7")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(old_event, "HUB_STARTED");
    let seq = sqlx::query("INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service,http_method,endpoint,status_code,duration_ms)
        VALUES (2000,'HTTP_REQUEST','INFO','SUCCEEDED','HTTP','GET','/api/admin/users',200,2)")
        .execute(&pool).await.unwrap().last_insert_rowid();
    assert_eq!(seq, 9001);
    for raw_path in ["/api/admin/users/123456789012", "/download?token=secret"] {
        assert!(
            sqlx::query("UPDATE hub_activity_events SET endpoint=? WHERE seq=?")
                .bind(raw_path)
                .bind(seq)
                .execute(&pool)
                .await
                .is_err()
        );
    }
    pool.close().await;
}
