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
        ("GET", "/api/admin/users", "/api/admin/users", 401),
        ("GET", "/api/admin/users", "/api/admin/users", 401),
        (
            "GET",
            "/PRIVATE_PATH_123456789012?token=QUERY_SECRET",
            "UNMATCHED",
            404,
        ),
        ("DELETE", "/api/admin/users", "/api/admin/users", 404),
        ("OPTIONS", "/api/admin/users", "/api/admin/users", 200),
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
                && entry["details"]["endpoint"] == "/api/admin/users"
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
    assert!(
        !search["entries"].as_array().unwrap().is_empty(),
        "activity reads are also recorded and searchable"
    );
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
