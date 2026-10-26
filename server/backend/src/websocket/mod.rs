//! `ws://<hub-ip>:8081`: handshake per `contracts/events/README.md`, then one reader and one
//! writer per connection. The writer drains the connection's bounded queue, so no send ever
//! waits on another device.

pub mod broker;
pub mod events;

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::WebSocketStream;

use crate::auth::AuthUser;
use crate::error::ErrorCode;
use crate::AppState;
use broker::Frame;
use events::{ClientEvent, ServerEvent};

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const CLOSE_INVALID_TOKEN: u16 = 4401;

pub(crate) async fn run(listener: TcpListener, app: AppState, mut shutdown: watch::Receiver<bool>) {
    loop {
        tokio::select! {
            _ = shutdown.changed() => break,
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => {
                    tokio::spawn(serve_connection(stream, app.clone(), shutdown.clone()));
                }
                Err(e) => {
                    // Usually out of file handles; pause instead of spinning.
                    tracing::warn!(error = %e, "websocket accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            },
        }
    }
}

async fn serve_connection(stream: TcpStream, app: AppState, mut shutdown: watch::Receiver<bool>) {
    let Ok(Ok(mut ws)) = timeout(HELLO_TIMEOUT, tokio_tungstenite::accept_async(stream)).await else {
        return;
    };
    let user = match timeout(HELLO_TIMEOUT, wait_for_hello(&mut ws, &app)).await {
        Ok(Some(user)) => user,
        Ok(None) => return,
        Err(_) => {
            let _ = ws.close(Some(close_frame(CloseCode::Policy, "No EVENT_HELLO"))).await;
            return;
        }
    };

    let (tx, mut rx) = broker::channel();
    let ack = ServerEvent::HelloAck {
        server_time: app.clock.now_ms(),
        hub_id: app.hub_id.to_string(),
        sync_epoch: app.sync_epoch,
    };
    // Queued before the connection is registered, so the ack is always the first frame.
    if !queue(&tx, &ack) {
        return;
    }
    let connection_id = app.broker.register(&user.id, tx.clone());

    let (mut sink, mut source) = ws.split();
    let writer = async {
        while let Some(frame) = rx.recv().await {
            if sink.send(Message::text(frame.as_ref())).await.is_err() {
                break;
            }
        }
    };
    let reader = async {
        loop {
            tokio::select! {
                _ = shutdown.changed() => break,
                message = source.next() => match message {
                    Some(Ok(Message::Text(text))) => {
                        if !handle_frame(text.as_str(), &tx) {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break,
                    Some(Ok(_)) => {}
                },
            }
        }
    };
    tokio::select! {
        _ = writer => {}
        _ = reader => {}
    }
    app.broker.unregister(&user.id, connection_id);
    let _ = timeout(Duration::from_secs(1), sink.close()).await;
}

/// Answers frames sent before `EVENT_HELLO` like the mock does, and closes with 4401 when the
/// token is missing or unknown. `None` once the socket is closed.
async fn wait_for_hello(ws: &mut WebSocketStream<TcpStream>, app: &AppState) -> Option<AuthUser> {
    while let Some(Ok(message)) = ws.next().await {
        let text = match message {
            Message::Text(text) => text,
            Message::Close(_) => return None,
            _ => continue,
        };
        let reply = match ClientEvent::parse(text.as_str()) {
            None => ServerEvent::error(ErrorCode::BadRequest, "Invalid JSON"),
            Some(ClientEvent::Other) => ServerEvent::error(ErrorCode::Unauthorized, "Send EVENT_HELLO first"),
            Some(ClientEvent::Hello { token }) => {
                if let Some(user) = token.as_deref().and_then(|t| app.sessions.user_for_token(t)) {
                    return Some(user);
                }
                let _ = ws.close(Some(close_frame(CloseCode::from(CLOSE_INVALID_TOKEN), "Invalid token"))).await;
                return None;
            }
        };
        ws.send(Message::text(reply.to_frame())).await.ok()?;
    }
    None
}

/// `false` drops the connection: its queue is full or its writer is gone.
fn handle_frame(text: &str, tx: &mpsc::Sender<Frame>) -> bool {
    match ClientEvent::parse(text) {
        None => queue(tx, &ServerEvent::error(ErrorCode::BadRequest, "Invalid JSON")),
        // Later steps handle AI requests and quiz submits here.
        Some(_) => true,
    }
}

fn queue(tx: &mpsc::Sender<Frame>, event: &ServerEvent) -> bool {
    tx.try_send(Frame::from(event.to_frame())).is_ok()
}

fn close_frame(code: CloseCode, reason: &'static str) -> CloseFrame {
    CloseFrame { code, reason: reason.into() }
}
