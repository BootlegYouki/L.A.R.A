mod common;

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use common::{TestHub, TEACHER_TOKEN};
use futures_util::{SinkExt, StreamExt};
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
async fn wrong_method_is_route_not_found_like_the_mock() {
    let hub = TestHub::start().await;
    let (status, body) = hub.get("/api/sync/pull", Some(TEACHER_TOKEN)).await;
    assert_eq!((status, code(&body)), (404, Some("ROUTE_NOT_FOUND")));
    hub.stop().await;
}

#[tokio::test]
async fn stubs_check_the_token_first() {
    let hub = TestHub::start().await;
    for token in [None, Some("not-a-session")] {
        let (status, body) = hub.request(Method::POST, "/api/sync/pull", token).await;
        assert_eq!((status, code(&body)), (401, Some("UNAUTHORIZED")), "token {token:?}");
    }
    let (status, body) = hub.request(Method::POST, "/api/sync/pull", Some(TEACHER_TOKEN)).await;
    assert_eq!((status, code(&body)), (501, Some("NOT_IMPLEMENTED")));
    hub.stop().await;
}

#[tokio::test]
async fn open_routes_need_no_token() {
    let hub = TestHub::start().await;
    for path in ["/api/auth/register", "/api/auth/login", "/api/admin/setup"] {
        let (status, body) = hub.request(Method::POST, path, None).await;
        assert_eq!((status, code(&body)), (501, Some("NOT_IMPLEMENTED")), "{path}");
    }
    hub.stop().await;
}

/// Every contract operation in the eight scaffolded areas is registered (501, not 404).
#[tokio::test]
async fn every_scaffolded_contract_route_is_registered() {
    const AREAS: [&str; 9] = [
        "/api/auth/", "/api/admin/", "/api/classrooms", "/api/sync/", "/api/materials",
        "/api/assignments", "/api/submissions", "/api/quizzes", "/api/export/",
    ];
    let openapi = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../contracts/openapi.yaml")).unwrap();
    let mut operations = Vec::new();
    let mut path: Option<&str> = None;
    for line in openapi.lines() {
        if let Some(p) = line.strip_prefix("  /").and_then(|p| p.strip_suffix(':')) {
            path = Some(p);
        } else if let (Some(p), Some(m)) = (path, line.strip_prefix("    ").and_then(|l| l.strip_suffix(':'))) {
            if let Ok(method) = m.to_uppercase().parse::<Method>() {
                operations.push((method, format!("/{p}")));
            }
        } else if !line.starts_with(' ') {
            path = None;
        }
    }
    let scaffolded: Vec<_> = operations
        .into_iter()
        .filter(|(_, p)| AREAS.iter().any(|a| p.starts_with(a)) && !p.ends_with("/private-comments"))
        .collect();
    assert!(scaffolded.len() >= 40, "parsed only {} operations", scaffolded.len());

    let hub = TestHub::start().await;
    for (method, template) in scaffolded {
        let path = template.replace("{id}", "x").replace("{teacher_id}", "y");
        let (status, body) = hub.request(method.clone(), &path, Some(TEACHER_TOKEN)).await;
        assert_eq!((status, code(&body)), (501, Some("NOT_IMPLEMENTED")), "{method} {template}");
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
    ws.send(hello(TEACHER_TOKEN)).await.unwrap();

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

    ws.send(hello(TEACHER_TOKEN)).await.unwrap();
    assert_eq!(next_json(&mut ws).await["event"], "EVENT_HELLO_ACK");

    ws.send(Message::text("still not json")).await.unwrap();
    assert_eq!(next_json(&mut ws).await["code"], "BAD_REQUEST");
    hub.stop().await;
}

#[tokio::test]
async fn shutdown_closes_open_sockets() {
    let hub = TestHub::start().await;
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(hello(TEACHER_TOKEN)).await.unwrap();
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
