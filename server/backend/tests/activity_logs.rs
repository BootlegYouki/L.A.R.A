mod common;

use common::TestHub;
use lara_hub::services::activity::{Code, Event, Reason, Service};
use serde_json::{json, Value};
use std::time::Duration;

async fn admin(hub: &TestHub) -> String {
    hub.login("ADMIN-0001", "123456").await
}

async fn flushed(hub: &TestHub, code: &str) {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE event_code=?")
                .bind(code)
                .fetch_one(&hub.state().db)
                .await
                .unwrap();
            if count > 0 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("activity writer did not flush");
}

#[tokio::test]
async fn history_requires_local_admin_and_validates_filters() {
    let hub = TestHub::start_seeded().await;
    let token = admin(&hub).await;
    let path = "/api/admin/activity-logs";
    assert_eq!(hub.get(path, None).await.0, 401);
    assert_eq!(hub.get(path, Some(hub.teacher_token())).await.0, 403);
    let response = reqwest::Client::new()
        .get(format!("http://127.0.0.1:{}{path}", hub.http_port()))
        .bearer_auth(&token)
        .header("Cf-Connecting-IP", "203.0.113.7")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 403);
    for query in [
        "limit=0",
        "limit=101",
        "after_seq=0",
        "severity=DEBUG",
        "unknown=yes",
        "from_time=2&to_time=1",
        "limit=1&limit=2",
    ] {
        assert_eq!(hub.get(&format!("{path}?{query}"), Some(&token)).await.0, 400, "{query}");
    }
    let response = reqwest::Client::new()
        .get(format!("http://127.0.0.1:{}{path}", hub.http_port()))
        .bearer_auth(&token)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);
    assert_eq!(response.headers()["cache-control"], "no-store");
    hub.stop().await;
}

#[tokio::test]
async fn catchup_drains_oldest_unseen_without_skipping_a_burst() {
    let hub = TestHub::start_seeded().await;
    let token = admin(&hub).await;
    flushed(&hub, "HUB_STARTED").await;
    let (_, initial) = hub.get("/api/admin/activity-logs", Some(&token)).await;
    let history = initial["history_id"].as_str().unwrap();
    let start = initial["high_water_seq"].as_i64().unwrap();
    let now = hub.state().clock.now_ms();
    sqlx::query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<350) INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service) SELECT ?,'ACCOUNT_CREATED','INFO','SUCCEEDED','ACCOUNTS' FROM n")
        .bind(now).execute(&hub.state().db).await.unwrap();
    let mut after = start;
    let mut snapshot = None;
    let mut seen = std::collections::BTreeSet::new();
    loop {
        let mut path = format!("/api/admin/activity-logs?q=account%20created&history_id={history}&after_seq={after}");
        if let Some(high) = snapshot {
            path.push_str(&format!("&snapshot_seq={high}"));
        }
        let (status, page) = hub.get(&path, Some(&token)).await;
        assert_eq!(status, 200, "{page}");
        let high = page["high_water_seq"].as_i64().unwrap();
        assert_eq!(*snapshot.get_or_insert(high), high);
        let entries = page["entries"].as_array().unwrap();
        assert!(entries.windows(2).all(|pair| pair[0]["seq"].as_i64() > pair[1]["seq"].as_i64()));
        for entry in entries {
            assert!(seen.insert(entry["seq"].as_i64().unwrap()));
        }
        match page["next_after_seq"].as_i64() {
            Some(next) => after = next,
            None => break,
        }
    }
    assert_eq!(seen.len(), 350);
    let expected: Vec<i64> = sqlx::query_scalar("SELECT seq FROM hub_activity_events WHERE event_code='ACCOUNT_CREATED' ORDER BY seq")
        .fetch_all(&hub.state().db).await.unwrap();
    assert_eq!(seen.into_iter().collect::<Vec<_>>(), expected);
    let (_, literal) = hub.get("/api/admin/activity-logs?q=%25", Some(&token)).await;
    assert!(literal["entries"].as_array().unwrap().is_empty());
    let (_, filtered) =
        hub.get("/api/admin/activity-logs?q=account%20created&severity=INFO&limit=3", Some(&token)).await;
    assert_eq!(filtered["entries"].as_array().unwrap().len(), 3);
    let (status, reset) = hub
        .get("/api/admin/activity-logs?history_id=00000000-0000-4000-8000-000000000001&after_seq=0", Some(&token))
        .await;
    assert_eq!(status, 409);
    assert_eq!(reset["error"]["code"], "ACTIVITY_LOG_RESET");
    hub.stop().await;
}

#[tokio::test]
async fn account_events_are_private_and_history_survives_clean_restart() {
    let hub = TestHub::start_seeded().await;
    let token = admin(&hub).await;
    let body =
        json!({"lrn_or_id":"T-PRIVATE-9382","full_name":"Private Sentinel Name","role":"TEACHER","pin":"984321"});
    assert_eq!(hub.post_json("/api/admin/users", &body, Some(&token)).await.0, 200);
    assert_eq!(hub.post_json("/api/admin/users", &body, Some(&token)).await.0, 409);
    flushed(&hub, "ACCOUNT_CREATE_FAILED").await;
    let (_, before) = hub.get("/api/admin/activity-logs", Some(&token)).await;
    let serialized = before.to_string();
    for secret in ["T-PRIVATE-9382", "Private Sentinel Name", "984321", &token] {
        assert!(!serialized.contains(secret));
    }
    let hub = hub.restart().await;
    let token = admin(&hub).await;
    flushed(&hub, "HUB_STARTED").await;
    let (_, after) = hub.get("/api/admin/activity-logs", Some(&token)).await;
    assert_eq!(before["history_id"], after["history_id"]);
    assert!(!after["entries"].as_array().unwrap().iter().any(|entry| entry["event_code"] == "UNCLEAN_RESTART"));
    assert!(after["entries"].as_array().unwrap().iter().any(|entry| entry["event_code"] == "HUB_STOPPING"));
    hub.stop().await;
}

#[tokio::test]
async fn retention_and_suppression_report_gaps_without_blocking_producers() {
    let hub = TestHub::start_seeded().await;
    flushed(&hub, "HUB_STARTED").await;
    let now = hub.state().clock.now_ms();
    sqlx::query("INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service) VALUES (?,'HUB_STARTED','INFO','SUCCEEDED','HUB')")
        .bind(now - 8 * 86_400_000).execute(&hub.state().db).await.unwrap();
    sqlx::query("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<20001) INSERT INTO hub_activity_events(occurred_at,event_code,severity,result,service) SELECT ?,'ACCOUNT_CREATED','INFO','SUCCEEDED','ACCOUNTS' FROM n")
        .bind(now).execute(&hub.state().db).await.unwrap();
    for _ in 0..20 {
        hub.state().activity.record(Event::new(Code::ConnectionProblem, Service::Http).reason(Reason::Timeout));
    }
    flushed(&hub, "LOG_GAP").await;
    let count: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events").fetch_one(&hub.state().db).await.unwrap();
    assert!(count <= 20000);
    let token = admin(&hub).await;
    let (_, page) = hub.get("/api/admin/activity-logs", Some(&token)).await;
    assert!(page["pruned_through_seq"].as_i64().unwrap() > 0);
    assert!(page["dropped_total"].as_i64().unwrap() >= 19);
    assert!(page["entries"]
        .as_array()
        .unwrap()
        .iter()
        .all(|e| e["occurred_at"].as_i64().unwrap() >= now - 7 * 86_400_000));
    hub.stop().await;
}

#[tokio::test]
async fn unavailable_history_does_not_stop_account_service() {
    let hub = TestHub::start_seeded().await;
    let token = admin(&hub).await;
    sqlx::query("DROP TABLE hub_activity_events").execute(&hub.state().db).await.unwrap();
    hub.state().activity.record(Event::new(Code::AccountCreated, Service::Accounts));
    let (status, body): (_, Value) = hub.get("/api/admin/activity-logs", Some(&token)).await;
    assert_eq!(status, 503);
    assert_eq!(body["error"]["code"], "ACTIVITY_LOG_UNAVAILABLE");
    assert_eq!(hub.get("/api/admin/users", Some(&token)).await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn dirty_shutdown_is_reported_on_restart() {
    let hub = TestHub::start_seeded().await;
    hub.state().activity.shutdown().await;
    sqlx::query("UPDATE hub_activity_state SET clean_shutdown=0 WHERE singleton=1")
        .execute(&hub.state().db)
        .await
        .unwrap();
    let hub = hub.restart().await;
    flushed(&hub, "UNCLEAN_RESTART").await;
    let reason: String =
        sqlx::query_scalar("SELECT reason FROM hub_activity_events WHERE event_code='UNCLEAN_RESTART'")
            .fetch_one(&hub.state().db)
            .await
            .unwrap();
    assert_eq!(reason, "UNCLEAN_EXIT");
    hub.stop().await;
}

#[tokio::test]
async fn a_full_queue_reports_loss_and_shutdown_drains_without_waiting_on_producers() {
    let hub = TestHub::start_seeded().await;
    let started = std::time::Instant::now();
    // This current-thread test does not yield while filling the bounded producer queue.
    for _ in 0..10000 {
        hub.state().activity.record(Event::new(Code::AccountCreated, Service::Accounts));
    }
    assert!(started.elapsed() < Duration::from_secs(1));
    assert!(hub.state().activity.pending_drops() > 0);
    hub.state().activity.shutdown().await;
    let (dropped, clean): (i64, i64) =
        sqlx::query_as("SELECT dropped_total,clean_shutdown FROM hub_activity_state WHERE singleton=1")
            .fetch_one(&hub.state().db)
            .await
            .unwrap();
    assert!(dropped > 0);
    assert_eq!(clean, 1);
    assert_eq!(hub.state().activity.pending_drops(), 0);
    let gap: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM hub_activity_events WHERE event_code='LOG_GAP' AND reason='QUEUE_FULL'",
    )
    .fetch_one(&hub.state().db)
    .await
    .unwrap();
    assert!(gap > 0);
    hub.stop().await;
}
