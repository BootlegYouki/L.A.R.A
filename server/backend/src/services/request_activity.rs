use std::time::Instant;

use axum::extract::{MatchedPath, Request, State};
use axum::http::Method;
use axum::middleware::Next;
use axum::response::Response;

use crate::services::activity::Event;
use crate::AppState;

const ENDPOINTS: &[&str] = &[
    "/",
    "/api/admin/activity-logs",
    "/api/admin/setup",
    "/api/admin/users",
    "/api/admin/users/{id}/reset-pin",
    "/api/announcements",
    "/api/announcements/{id}",
    "/api/announcements/{id}/comments",
    "/api/assignments",
    "/api/assignments/{id}",
    "/api/assignments/{id}/private-comments",
    "/api/assignments/{id}/submissions",
    "/api/assignments/{id}/submit",
    "/api/auth/login",
    "/api/auth/logout",
    "/api/auth/register",
    "/api/backup",
    "/api/classrooms",
    "/api/classrooms/join",
    "/api/classrooms/{id}",
    "/api/classrooms/{id}/approve",
    "/api/classrooms/{id}/reject",
    "/api/classrooms/{id}/remove",
    "/api/classrooms/{id}/roster",
    "/api/classrooms/{id}/teachers",
    "/api/classrooms/{id}/teachers/{teacher_id}",
    "/api/export/class-record",
    "/api/materials",
    "/api/materials/{id}",
    "/api/materials/{id}/chunks",
    "/api/materials/{id}/download",
    "/api/materials/{id}/stream",
    "/api/quizzes",
    "/api/quizzes/active",
    "/api/quizzes/{id}",
    "/api/quizzes/{id}/begin",
    "/api/quizzes/{id}/close",
    "/api/quizzes/{id}/results",
    "/api/quizzes/{id}/start",
    "/api/quizzes/{id}/submit",
    "/api/restore",
    "/api/submissions/{id}",
    "/api/submissions/{id}/file",
    "/api/sync/pull",
    "/api/sync/push",
    "/api/topics",
    "/api/topics/{id}",
    "/download",
    "/download/assets/{*path}",
    "/download/{file}",
    "UNMATCHED",
];

pub(crate) fn endpoint(request: &Request) -> &'static str {
    request
        .extensions()
        .get::<MatchedPath>()
        .and_then(|matched| {
            ENDPOINTS
                .iter()
                .copied()
                .find(|path| *path == matched.as_str())
        })
        .unwrap_or("UNMATCHED")
}

pub(crate) fn method(method: &Method) -> &'static str {
    match method.as_str() {
        "GET" => "GET",
        "HEAD" => "HEAD",
        "POST" => "POST",
        "PUT" => "PUT",
        "PATCH" => "PATCH",
        "DELETE" => "DELETE",
        "OPTIONS" => "OPTIONS",
        "CONNECT" => "CONNECT",
        "TRACE" => "TRACE",
        _ => "OTHER",
    }
}

pub(crate) async fn record(State(app): State<AppState>, request: Request, next: Next) -> Response {
    let method = method(request.method());
    let endpoint = endpoint(&request);
    let started = Instant::now();
    let response = next.run(request).await;
    app.activity.record(Event::http_request(
        method,
        endpoint,
        response.status().as_u16(),
        started.elapsed(),
    ));
    response
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::StatusCode;
    use axum::routing::get;
    use axum::Router;
    use tower::ServiceExt;

    #[tokio::test]
    async fn server_errors_redirects_and_streaming_headers_record_without_reading_the_body() {
        let directory = tempfile::tempdir().unwrap();
        let config = crate::HubConfig {
            http_port: 0,
            ws_port: 0,
            ..crate::HubConfig::new(directory.path())
        };
        let hub = crate::serve(config).await.unwrap();
        let router = Router::new()
            .route(
                "/api/admin/users",
                get(|| async { StatusCode::INTERNAL_SERVER_ERROR }),
            )
            .route("/download", get(|| async { StatusCode::FOUND }))
            .route(
                "/api/materials/{id}/stream",
                get(|| async {
                    Body::from_stream(futures_util::stream::pending::<
                        Result<Vec<u8>, std::io::Error>,
                    >())
                }),
            )
            .layer(axum::middleware::from_fn_with_state(hub.state().clone(), record));
        for path in [
            "/api/admin/users",
            "/api/admin/users",
            "/download",
            "/api/materials/PRIVATE_ID/stream",
        ] {
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                router
                    .clone()
                    .oneshot(Request::builder().uri(path).body(Body::empty()).unwrap()),
            )
            .await
            .expect("request recording must not consume the response stream")
            .unwrap();
            drop(response);
        }
        tokio::time::timeout(std::time::Duration::from_secs(5), async {
            loop {
                let count: i64 = sqlx::query_scalar(
                    "SELECT COUNT(*) FROM hub_activity_events WHERE event_code='HTTP_REQUEST'",
                )
                .fetch_one(&hub.state().db)
                .await
                .unwrap();
                if count == 4 {
                    break;
                }
                tokio::time::sleep(std::time::Duration::from_millis(25)).await;
            }
        })
        .await
        .unwrap();
        let errors: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE status_code=500 AND severity='ERROR' AND result='FAILED'")
            .fetch_one(&hub.state().db).await.unwrap();
        assert_eq!(errors, 2, "request errors must not be deduplicated");
        let redirects: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM hub_activity_events WHERE status_code=302 AND severity='INFO' AND result='SUCCEEDED'")
            .fetch_one(&hub.state().db).await.unwrap();
        assert_eq!(redirects, 1);
        let stream: String =
            sqlx::query_scalar("SELECT endpoint FROM hub_activity_events WHERE status_code=200")
                .fetch_one(&hub.state().db)
                .await
                .unwrap();
        assert_eq!(stream, "/api/materials/{id}/stream");
        hub.shutdown().await;
    }
}
