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
    #[serde(rename = "EVENT_PRESENCE")]
    Presence { classroom_id: String, student_id: String, state: PresenceState, timestamp: i64 },
    #[serde(rename = "EVENT_JOIN_REQUEST")]
    JoinRequest { class_code: String, student_id: String, full_name: String, lrn: String, timestamp: i64 },
    #[serde(rename = "EVENT_JOIN_APPROVAL")]
    JoinApproval { classroom_id: String, student_id: String, status: EnrollmentStatus, timestamp: i64 },
    #[serde(rename = "EVENT_ANNOUNCEMENT_PUSH")]
    AnnouncementPush { classroom_id: String, announcement_id: String, title: String, timestamp: i64 },
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PresenceState {
    Online,
    Offline,
    AnsweringQuiz,
    Submitted,
}

#[derive(Clone, Copy, Debug, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum EnrollmentStatus {
    Active,
    Rejected,
    Removed,
}

impl EnrollmentStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Active => "ACTIVE",
            Self::Rejected => "REJECTED",
            Self::Removed => "REMOVED",
        }
    }
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

    // Covers the keywords used by the emitted event schemas, without fetching remote schemas.
    fn validate(frame: &Value, schema: &Value) {
        if let Some(kind) = schema["type"].as_str() {
            assert!(
                match kind {
                    "object" => frame.is_object(),
                    "string" => frame.is_string(),
                    "integer" => frame.is_i64() || frame.is_u64(),
                    "boolean" => frame.is_boolean(),
                    _ => panic!("unhandled schema type {kind}"),
                },
                "{frame} does not match {schema}"
            );
        }
        if let Some(required) = schema["required"].as_array() {
            for key in required {
                assert!(frame.get(key.as_str().unwrap()).is_some(), "missing {key}");
            }
        }
        if let Some(properties) = schema["properties"].as_object() {
            for (key, property) in properties {
                if let Some(value) = frame.get(key) {
                    validate(value, property);
                }
            }
        }
        if let Some(value) = schema.get("const") {
            assert_eq!(frame, value);
        }
        if let Some(values) = schema["enum"].as_array() {
            assert!(values.contains(frame));
        }
    }

    #[test]
    fn emitted_push_frames_match_the_contract_schemas() {
        let mut events = vec![
            (
                "join_request.json",
                ServerEvent::JoinRequest {
                    class_code: "K7M4QX".into(),
                    student_id: "s".into(),
                    full_name: "Learner".into(),
                    lrn: "123456789013".into(),
                    timestamp: 1,
                },
            ),
            (
                "announcement_push.json",
                ServerEvent::AnnouncementPush {
                    classroom_id: "c".into(),
                    announcement_id: "a".into(),
                    title: "Lesson".into(),
                    timestamp: 1,
                },
            ),
        ];
        for state in
            [PresenceState::Online, PresenceState::Offline, PresenceState::AnsweringQuiz, PresenceState::Submitted]
        {
            events.push((
                "presence.json",
                ServerEvent::Presence { classroom_id: "c".into(), student_id: "s".into(), state, timestamp: 1 },
            ));
        }
        for status in [EnrollmentStatus::Active, EnrollmentStatus::Rejected, EnrollmentStatus::Removed] {
            events.push((
                "join_approval.json",
                ServerEvent::JoinApproval { classroom_id: "c".into(), student_id: "s".into(), status, timestamp: 1 },
            ));
        }
        for code in [ErrorCode::Unauthorized, ErrorCode::Forbidden, ErrorCode::BadRequest] {
            events.push(("error.json", ServerEvent::error(code, "m")));
        }
        for (name, event) in events {
            let frame: Value = serde_json::from_str(&event.to_frame()).unwrap();
            validate(&frame, &contract(name));
        }
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
