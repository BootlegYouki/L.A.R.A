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


#[tokio::test]
async fn unknown_route_is_route_not_found() {
    let hub = TestHub::start().await;
    let (status, body) = hub.get("/api/nope", None).await;
    assert_eq!((status, code(&body)), (404, Some("ROUTE_NOT_FOUND")));
    assert_eq!(body["error"]["message"], "GET /api/nope");
    hub.stop().await;
}

#[tokio::test]
async fn wrong_method_is_route_not_found() {
    let hub = TestHub::start().await;
    let (status, body) = hub.get("/api/sync/pull", Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body)), (404, Some("ROUTE_NOT_FOUND")));
    hub.stop().await;
}

#[tokio::test]
async fn sync_checks_the_token_first() {
    let hub = TestHub::start().await;
    for token in [None, Some("not-a-session")] {
        let (status, body) = hub.request(Method::POST, "/api/sync/pull", token).await;
        assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")), "token {token:?}");
    }
    let (status, body) = hub.post_json("/api/sync/pull", &json!({"cursor":0}), Some(hub.teacher_token())).await;
    assert_eq!((status, code(&body)), (200, None));
    hub.stop().await;
}

#[tokio::test]
async fn open_routes_need_no_token() {
    let hub = TestHub::start().await;
    // An empty body reaches the handler, which asks for its fields: no 401 first.
    for path in ["/api/auth/register", "/api/auth/login", "/api/admin/setup"] {
        let (status, body) = hub.request(Method::POST, path, None).await;
        assert_eq!((status, code(&body)), (400, Some("BAD_REQUEST")), "{path}");
    }
    hub.stop().await;
}

const ROLE_ROUTES: &[(&str, &str, Role)] = &[
    ("GET", "/api/admin/users", Role::Admin),
    ("POST", "/api/admin/users", Role::Admin),
    ("POST", "/api/admin/users/{id}/reset-pin", Role::Admin),
    ("POST", "/api/classrooms", Role::Teacher),
    ("POST", "/api/classrooms/join", Role::Student),
    ("GET", "/api/classrooms/{id}/roster", Role::Teacher),
    ("POST", "/api/classrooms/{id}/approve", Role::Teacher),
    ("POST", "/api/classrooms/{id}/reject", Role::Teacher),
    ("POST", "/api/classrooms/{id}/remove", Role::Teacher),
    ("PATCH", "/api/classrooms/{id}", Role::Teacher),
    ("POST", "/api/classrooms/{id}/teachers", Role::Teacher),
    ("DELETE", "/api/classrooms/{id}/teachers/{teacher_id}", Role::Teacher),
    ("POST", "/api/sync/push", Role::Student),
    ("POST", "/api/announcements", Role::Teacher),
    ("PATCH", "/api/announcements/{id}", Role::Teacher),
    ("DELETE", "/api/announcements/{id}", Role::Teacher),
    ("POST", "/api/topics", Role::Teacher),
    ("PATCH", "/api/topics/{id}", Role::Teacher),
    ("DELETE", "/api/topics/{id}", Role::Teacher),
    ("PATCH", "/api/materials/{id}", Role::Teacher),
    ("DELETE", "/api/materials/{id}", Role::Teacher),
    ("POST", "/api/materials", Role::Teacher),
    ("POST", "/api/assignments", Role::Teacher),
    ("PATCH", "/api/assignments/{id}", Role::Teacher),
    ("DELETE", "/api/assignments/{id}", Role::Teacher),
    ("POST", "/api/assignments/{id}/submit", Role::Student),
    ("GET", "/api/assignments/{id}/submissions", Role::Teacher),
    ("PATCH", "/api/submissions/{id}", Role::Teacher),
    ("POST", "/api/quizzes", Role::Teacher),
    ("PATCH", "/api/quizzes/{id}", Role::Teacher),
    ("POST", "/api/quizzes/{id}/start", Role::Teacher),
    ("POST", "/api/quizzes/{id}/begin", Role::Student),
    ("POST", "/api/quizzes/{id}/submit", Role::Student),
    ("POST", "/api/quizzes/{id}/close", Role::Teacher),
    ("GET", "/api/quizzes/{id}/results", Role::Teacher),
    ("POST", "/api/export/class-record", Role::Teacher),
    ("POST", "/api/backup", Role::Admin),
    ("POST", "/api/restore", Role::Admin),
];

fn contract_operations() -> Vec<(Method, String)> {
    let openapi = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../contracts/openapi.yaml")).unwrap();
    let mut operations = Vec::new();
    let mut path: Option<&str> = None;
    for line in openapi.lines() {
        if let Some(template) = line.strip_prefix("  /").and_then(|value| value.strip_suffix(':')) {
            path = Some(template);
        } else if let (Some(template), Some(method)) = (path, line.strip_prefix("    ").and_then(|value| value.strip_suffix(':'))) {
            if matches!(method, "get" | "post" | "patch" | "delete" | "put" | "options" | "head") {
                operations.push((method.to_uppercase().parse().unwrap(), format!("/{template}")));
            }
        } else if !line.starts_with(' ') {
            path = None;
        }
    }
    operations
}

fn request_path(template: &str) -> String {
    template.replace("{id}", "x").replace("{teacher_id}", "x")
}

async fn role_tokens(hub: &TestHub) -> [(Role, String); 3] {
    let pin = lara_hub::db::seed::TEST_PIN;
    [
        (Role::Teacher, hub.teacher_token().to_owned()),
        (Role::Student, hub.login("123456789012", pin).await),
        (Role::Admin, hub.login("ADMIN-0001", pin).await),
    ]
}

#[tokio::test]
async fn routes_enforce_the_contract_roles() {
    let operations = contract_operations();
    let hub = TestHub::start_seeded().await;
    let tokens = role_tokens(&hub).await;
    for &(method, template, role) in ROLE_ROUTES {
        let method: Method = method.parse().unwrap();
        assert!(operations.contains(&(method.clone(), template.to_owned())), "{method} {template}");
        let path = request_path(template);
        let right = &tokens.iter().find(|(candidate, _)| *candidate == role).unwrap().1;
        let (status, body) = hub.request(method.clone(), &path, Some(right)).await;
        assert!(matches!(status, 200 | 400 | 404 | 501), "{method} {template} as {role:?}: {status} {body}");
        assert_ne!(code(&body), Some("ROUTE_NOT_FOUND"), "{method} {template}");
        for (other, token) in tokens.iter().filter(|(candidate, _)| *candidate != role) {
            let (status, body) = hub.request(method.clone(), &path, Some(token)).await;
            assert_eq!((status, code(&body)), (403, Some("FORBIDDEN")), "{method} {template} as {other:?}");
        }
    }
    hub.stop().await;
}

#[tokio::test]
async fn every_scaffolded_contract_route_is_registered() {
    let operations: Vec<_> = contract_operations().into_iter()
        .filter(|(_, path)| !path.starts_with("/api/auth/") && path != "/api/admin/setup" && path != "/download")
        .collect();
    assert!(operations.len() >= 40, "parsed only {} operations", operations.len());

    let hub = TestHub::start_seeded().await;
    let tokens = role_tokens(&hub).await;
    for (method, template) in operations {
        let role = ROLE_ROUTES.iter().find(|(candidate, path, _)| *candidate == method.as_str() && *path == template)
            .map_or(Role::Teacher, |(_, _, role)| *role);
        let token = &tokens.iter().find(|(candidate, _)| *candidate == role).unwrap().1;
        let path = request_path(&template);
        let (status, body) = hub.request(method.clone(), &path, Some(token)).await;
        assert!(matches!(status, 200 | 400 | 403 | 404 | 501), "{method} {template}: {status} {body}");
        assert_ne!(code(&body), Some("ROUTE_NOT_FOUND"), "{method} {template}");
        let (status, body) = hub.request(method.clone(), &path, None).await;
        assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")), "{method} {template}");
    }
    hub.stop().await;
}

async fn next_json(ws: &mut (impl StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin)) -> Value {
    loop {
        match tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap() {
            Some(Ok(Message::Text(text))) => return serde_json::from_str(text.as_str()).unwrap(),
            Some(Ok(Message::Ping(_) | Message::Pong(_))) => continue,
            other => panic!("expected a text frame, got {other:?}"),
        }
    }
}

async fn close_code(ws: &mut (impl StreamExt<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin)) -> Option<u16> {
    match tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap() {
        Some(Ok(Message::Close(Some(frame)))) => Some(u16::from(frame.code)),
        _ => None,
    }
}

fn hello(token: &str) -> Message {
    Message::text(json!({"event": "EVENT_HELLO", "token": token}).to_string())
}

#[tokio::test]
async fn hello_gets_hello_ack_and_registers_the_connection() {
    let hub = TestHub::start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(hello(hub.teacher_token())).await.unwrap();

    let ack = next_json(&mut ws).await;
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64;
    assert_eq!(ack["event"], "EVENT_HELLO_ACK");
    assert_eq!(ack["hub_id"], hub.state().hub_id.as_ref());
    assert_eq!(ack["sync_epoch"], 1);
    assert!((now - ack["server_time"].as_i64().unwrap()).abs() < 5_000);
    assert_eq!(hub.state().broker.connection_count(), 1);

    ws.close(None).await.unwrap();
    for _ in 0..50 {
        if hub.state().broker.connection_count() == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    assert_eq!(hub.state().broker.connection_count(), 0);
    hub.stop().await;
}

#[tokio::test]
async fn bad_or_missing_token_closes_with_4401() {
    let hub = TestHub::start().await;
    for frame in [hello("not-a-session"), hello(""), Message::text(r#"{"event":"EVENT_HELLO"}"#)] {
        let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
        ws.send(frame).await.unwrap();
        assert_eq!(close_code(&mut ws).await, Some(4401));
    }
    assert_eq!(hub.state().broker.connection_count(), 0);
    hub.stop().await;
}

#[tokio::test]
async fn frames_before_hello_get_event_error() {
    let hub = TestHub::start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();

    ws.send(Message::text(r#"{"event":"EVENT_AI_CHAT_REQUEST"}"#)).await.unwrap();
    let err = next_json(&mut ws).await;
    assert_eq!((err["event"].as_str(), err["code"].as_str()), (Some("EVENT_ERROR"), Some("UNAUTHORIZED")));

    ws.send(Message::text("not json")).await.unwrap();
    let err = next_json(&mut ws).await;
    assert_eq!(err["code"], "BAD_REQUEST");

    ws.send(hello(hub.teacher_token())).await.unwrap();
    assert_eq!(next_json(&mut ws).await["event"], "EVENT_HELLO_ACK");

    ws.send(Message::text("still not json")).await.unwrap();
    assert_eq!(next_json(&mut ws).await["code"], "BAD_REQUEST");
    hub.stop().await;
}

#[tokio::test]
async fn shutdown_closes_open_sockets() {
    let hub = TestHub::start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(hello(hub.teacher_token())).await.unwrap();
    next_json(&mut ws).await;

    tokio::time::timeout(Duration::from_secs(5), hub.stop()).await.unwrap();
    let ended = tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap();
    assert!(matches!(ended, None | Some(Ok(Message::Close(_))) | Some(Err(_))));
}

/// Discovery (the UDP beacon task and the mDNS responder) is started inside `serve` and must not
/// break startup or clean shutdown. A router that drops broadcast packets never stops the Hub, so
/// starting on a machine with or without a reachable LAN both have to succeed here.
#[tokio::test]
async fn hub_with_discovery_starts_and_stops_cleanly() {
    let hub = TestHub::start().await;
    // The HTTP listener still answers, proving the added discovery tasks did not stall serve().
    let (status, _) = hub.get("/api/nope", None).await;
    assert_eq!(status, 404);
    tokio::time::timeout(Duration::from_secs(5), hub.stop()).await.unwrap();
}
