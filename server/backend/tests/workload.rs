mod common;

use common::TestHub;

#[tokio::test]
async fn usage_counts_real_requests_including_polls_without_logging_them() {
    let hub = TestHub::start().await;
    let before = hub.state().workload.snapshot();
    assert_eq!(hub.get("/api/admin/users", None).await.0, 401);
    assert_eq!(hub.get("/api/admin/activity-logs", None).await.0, 401);
    assert_eq!(hub.get("/api/classrooms", Some(hub.teacher_token())).await.0, 200);
    let after = hub.state().workload.snapshot();
    assert_eq!(after.requests_last_minute - before.requests_last_minute, 3);
    assert_eq!(after.failed_last_minute - before.failed_last_minute, 2);
    assert!(after.average_response_ms.is_some_and(|ms| ms.is_finite() && ms >= 0.0));
    assert!(after.slowest_response_ms >= after.average_response_ms);
    let logged: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE endpoint IN ('/api/admin/users','/api/admin/activity-logs')")
        .fetch_one(&hub.state().db).await.unwrap();
    assert_eq!(logged, 0);
}
