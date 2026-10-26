use serde::{Deserialize, Serialize};

use crate::error::ErrorCode;

/// Every frame the Hub sends, tagged by `event` as in `contracts/events/*`.
#[derive(Debug, Serialize)]
#[serde(tag = "event")]
pub enum ServerEvent {
    #[serde(rename = "EVENT_HELLO_ACK")]
    HelloAck { server_time: i64, hub_id: String, sync_epoch: i64 },
    /// Only the codes listed in `contracts/events/error.json`.
    #[serde(rename = "EVENT_ERROR")]
    Error { code: ErrorCode, message: String },
}

impl ServerEvent {
    pub fn error(code: ErrorCode, message: &str) -> Self {
        Self::Error { code, message: message.to_owned() }
    }

    pub fn to_frame(&self) -> String {
        // Plain structs of strings and integers cannot fail to serialize.
        serde_json::to_string(self).unwrap_or_default()
    }
}

/// Frames a client sends. Later steps add `EVENT_AI_CHAT_REQUEST` and `EVENT_QUIZ_SUBMIT`.
#[derive(Debug, Deserialize)]
#[serde(tag = "event")]
pub enum ClientEvent {
    #[serde(rename = "EVENT_HELLO")]
    Hello {
        #[serde(default)]
        token: Option<String>,
    },
    #[serde(other)]
    Other,
}

impl ClientEvent {
    /// `None` means the frame was not JSON. A JSON frame that is not a known event is `Other`.
    pub fn parse(text: &str) -> Option<Self> {
        let value: serde_json::Value = serde_json::from_str(text).ok()?;
        Some(serde_json::from_value(value).unwrap_or(Self::Other))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn contract(name: &str) -> Value {
        let path = format!("{}/../../contracts/events/{name}", env!("CARGO_MANIFEST_DIR"));
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
    }

    #[test]
    fn hello_ack_has_the_handshake_keys() {
        let ack = ServerEvent::HelloAck { server_time: 1, hub_id: "h".into(), sync_epoch: 1 };
        let frame: Value = serde_json::from_str(&ack.to_frame()).unwrap();
        let mut keys: Vec<_> = frame.as_object().unwrap().keys().cloned().collect();
        keys.sort();
        assert_eq!(keys, ["event", "hub_id", "server_time", "sync_epoch"]);
        assert_eq!(frame["event"], "EVENT_HELLO_ACK");
    }

    #[test]
    fn error_frames_match_error_json() {
        let schema = contract("error.json");
        let allowed: Vec<&str> =
            schema["properties"]["code"]["enum"].as_array().unwrap().iter().filter_map(Value::as_str).collect();
        for code in [ErrorCode::Unauthorized, ErrorCode::BadRequest] {
            let frame: Value = serde_json::from_str(&ServerEvent::error(code, "m").to_frame()).unwrap();
            for key in schema["required"].as_array().unwrap() {
                assert!(frame.get(key.as_str().unwrap()).is_some(), "missing {key}");
            }
            assert_eq!(frame["event"], schema["properties"]["event"]["const"]);
            assert!(allowed.contains(&frame["code"].as_str().unwrap()), "{code:?} not in error.json");
        }
    }

    #[test]
    fn client_frames_parse() {
        assert!(matches!(
            ClientEvent::parse(r#"{"event":"EVENT_HELLO","token":"t"}"#),
            Some(ClientEvent::Hello { token: Some(t) }) if t == "t"
        ));
        assert!(matches!(ClientEvent::parse(r#"{"event":"EVENT_HELLO"}"#), Some(ClientEvent::Hello { token: None })));
        assert!(matches!(ClientEvent::parse(r#"{"event":"EVENT_PRESENCE"}"#), Some(ClientEvent::Other)));
        assert!(matches!(ClientEvent::parse(r#"{"no_event":1}"#), Some(ClientEvent::Other)));
        assert!(ClientEvent::parse("not json").is_none());
    }
}
