//! A WebSocket client for push tests: the handshake, the next event, and "nothing arrives".

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use tokio::net::TcpStream;
use tokio_tungstenite::{tungstenite::Message, MaybeTlsStream, WebSocketStream};

use super::TestHub;

pub type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

async fn next_text(ws: &mut Socket) -> Value {
    loop {
        if let Message::Text(text) = ws.next().await.unwrap().unwrap() {
            return serde_json::from_str(text.as_str()).unwrap();
        }
    }
}

/// The next event, within 5 s.
pub async fn next(ws: &mut Socket) -> Value {
    tokio::time::timeout(Duration::from_secs(5), next_text(ws)).await.unwrap()
}

/// Asserts no event arrives within `ms`.
pub async fn nothing_within(ws: &mut Socket, ms: u64) {
    if let Ok(event) = tokio::time::timeout(Duration::from_millis(ms), next_text(ws)).await {
        panic!("unexpected event {event}");
    }
}

/// Connects and completes the handshake (`EVENT_HELLO` → `EVENT_HELLO_ACK`).
pub async fn connect(hub: &TestHub, token: &str) -> Socket {
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(Message::text(json!({"event":"EVENT_HELLO", "token":token}).to_string())).await.unwrap();
    assert_eq!(next(&mut ws).await["event"], "EVENT_HELLO_ACK");
    ws
}
