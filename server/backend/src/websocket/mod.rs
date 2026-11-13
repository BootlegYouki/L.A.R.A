//! `ws://<hub-ip>:8081`: handshake per `contracts/events/README.md`, then one reader and one
//! writer per connection. The writer drains the connection's bounded queue, so no send ever
//! waits on another device.

pub mod broker;
pub mod events;
pub mod presence;
pub mod quiz_hub;

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, watch};
use tokio::time::timeout;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::WebSocketStream;

use crate::auth::{sessions, AuthUser};
use crate::error::ErrorCode;
use crate::services::quiz_submit::{self, Submission};
use crate::AppState;
use broker::Frame;
use events::{ClientEvent, PresenceState, ServerEvent};
use presence::Presence;

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
    let (connection_id, mut stopped) = app.broker.register_with_stop(&user.id, tx.clone());
    if let Err(e) = Presence::emit(&app, &user, PresenceState::Online).await {
        tracing::warn!(error = %e, "presence update failed");
    }

    let (mut sink, mut source) = ws.split();
    let writer = async {
        while let Some(frame) = rx.recv().await {
            if !matches!(timeout(Duration::from_secs(10), sink.send(Message::text(frame.as_ref()))).await, Ok(Ok(()))) {
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
                        if !handle_frame(&app, &user, text.as_str(), &tx).await {
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
        _ = stopped.changed() => {}
    }
    app.broker.unregister(&user.id, connection_id);
    // Closing a phone must not mark the learner offline while their laptop is still connected.
    if !app.broker.is_online(&user.id) {
        if let Err(e) = Presence::emit(&app, &user, PresenceState::Offline).await {
            tracing::warn!(error = %e, "presence update failed");
        }
    }
    let _ = timeout(Duration::from_secs(1), sink.close()).await;
}

/// Answers frames sent before `EVENT_HELLO` like the mock does, and closes with 4401 when the
/// token is missing, unknown, expired or belongs to a deactivated account. `None` once the
/// socket is closed.
async fn wait_for_hello(ws: &mut WebSocketStream<TcpStream>, app: &AppState) -> Option<AuthUser> {
    while let Some(Ok(message)) = ws.next().await {
        let text = match message {
            Message::Text(text) => text,
            Message::Close(_) => return None,
            _ => continue,
        };
        let reply = match ClientEvent::parse(text.as_str()) {
            None => ServerEvent::error(ErrorCode::BadRequest, "Invalid JSON"),
            Some(ClientEvent::Other | ClientEvent::QuizSubmit { .. } | ClientEvent::InvalidQuizSubmit) => {
                ServerEvent::error(ErrorCode::Unauthorized, "Send EVENT_HELLO first")
            }
            Some(ClientEvent::Hello { token }) => {
                let lookup = match token.as_deref() {
                    Some(token) => sessions::user_for_token(&app.db, token, app.clock.now_ms()).await,
                    None => Ok(None),
                };
                let frame = match lookup {
                    Ok(Some(user)) => return Some(user),
                    Ok(None) => close_frame(CloseCode::from(CLOSE_INVALID_TOKEN), "Invalid token"),
                    // Not 4401: that tells the client to drop its token. A failed lookup is the
                    // Hub's fault, so the client should just reconnect.
                    Err(e) => {
                        tracing::error!(error = %e, "session lookup failed");
                        close_frame(CloseCode::Error, "Hub database error")
                    }
                };
                let _ = ws.close(Some(frame)).await;
                return None;
            }
        };
        ws.send(Message::text(reply.to_frame())).await.ok()?;
    }
    None
}

/// `false` drops the connection: its queue is full or its writer is gone. A quiz submit goes through the
/// same path as REST (`services::quiz_submit`); its grade arrives as `EVENT_GRADE_CONFIRMED`, and a refusal
/// as `EVENT_ERROR` `BAD_REQUEST` with the error code as the message (the mock's; `error.json` has no quiz codes).
async fn handle_frame(app: &AppState, user: &AuthUser, text: &str, tx: &mpsc::Sender<Frame>) -> bool {
    match ClientEvent::parse(text) {
        None => queue(tx, &ServerEvent::error(ErrorCode::BadRequest, "Invalid JSON")),
        Some(ClientEvent::QuizSubmit { quiz_id, submitted_at, answers }) => {
            let submission = Submission { started_at: None, submitted_at, answers };
            match quiz_submit::submit(app, user, &quiz_id, submission).await {
                Ok(_) => true,
                Err(error) => queue(tx, &ServerEvent::error(ErrorCode::BadRequest, error.code.as_str())),
            }
        }
        Some(ClientEvent::InvalidQuizSubmit) => {
            queue(tx, &ServerEvent::error(ErrorCode::BadRequest, "Invalid EVENT_QUIZ_SUBMIT"))
        }
        // A later step handles AI requests here.
        Some(_) => true,
    }
}

fn queue(tx: &mpsc::Sender<Frame>, event: &ServerEvent) -> bool {
    tx.try_send(Frame::from(event.to_frame())).is_ok()
}

fn close_frame(code: CloseCode, reason: &'static str) -> CloseFrame {
    CloseFrame { code, reason: reason.into() }
}
