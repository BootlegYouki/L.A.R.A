//! JSON request bodies read the way the mock reads them: whatever the `Content-Type`, an empty
//! body is `{}`, anything that is not a JSON object is 400 `Invalid JSON`, and missing keys are
//! named in one 400 (`Missing: a, b`). Axum's `Json` extractor would answer 415 or 422 instead.

use axum::body::Bytes;
use axum::extract::{FromRequest, Request};
use serde_json::{Map, Value};

use crate::error::ApiError;
use crate::AppState;

pub struct JsonBody(pub Map<String, Value>);

impl JsonBody {
    pub fn need(&self, keys: &[&str]) -> Result<(), ApiError> {
        let missing: Vec<&str> = keys.iter().copied().filter(|k| !self.0.contains_key(*k)).collect();
        if missing.is_empty() {
            Ok(())
        } else {
            Err(ApiError::bad_request(format!("Missing: {}", missing.join(", "))))
        }
    }

    /// A string as sent, or a number as text (the mock does `str(b["pin"])`); anything else is `None`.
    pub fn text(&self, key: &str) -> Option<String> {
        match self.0.get(key)? {
            Value::String(s) => Some(s.clone()),
            Value::Number(n) => Some(n.to_string()),
            _ => None,
        }
    }
}

fn parse(raw: &[u8]) -> Result<Map<String, Value>, ApiError> {
    if raw.is_empty() {
        return Ok(Map::new());
    }
    match serde_json::from_slice(raw) {
        Ok(Value::Object(map)) => Ok(map),
        _ => Err(ApiError::bad_request("Invalid JSON")),
    }
}

impl FromRequest<AppState> for JsonBody {
    type Rejection = ApiError;

    async fn from_request(req: Request, state: &AppState) -> Result<Self, Self::Rejection> {
        let raw = Bytes::from_request(req, state).await.map_err(|_| ApiError::bad_request("Invalid JSON"))?;
        parse(&raw).map(Self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn body(value: serde_json::Value) -> JsonBody {
        JsonBody(value.as_object().unwrap().clone())
    }

    #[test]
    fn empty_body_is_an_empty_object() {
        assert!(parse(b"").unwrap().is_empty());
    }

    #[test]
    fn broken_or_non_object_json_is_invalid_json() {
        for raw in [&b"{"[..], b"[1]", b"\"x\""] {
            let err = parse(raw).unwrap_err();
            assert_eq!((err.status.as_u16(), err.code.as_str(), err.message.as_str()), (400, "BAD_REQUEST", "Invalid JSON"));
        }
    }

    #[test]
    fn need_names_the_missing_keys_in_order() {
        let err = body(json!({"pin": "1"})).need(&["lrn_or_id", "full_name", "pin"]).unwrap_err();
        assert_eq!((err.status.as_u16(), err.message.as_str()), (400, "Missing: lrn_or_id, full_name"));
        assert!(body(json!({"pin": "1"})).need(&["pin"]).is_ok());
    }

    #[test]
    fn text_reads_strings_and_numbers_like_the_mock() {
        let b = body(json!({"pin": 1234, "name": "Ana", "gone": null, "list": [1]}));
        assert_eq!(b.text("pin").as_deref(), Some("1234"));
        assert_eq!(b.text("name").as_deref(), Some("Ana"));
        assert_eq!(b.text("gone"), None);
        assert_eq!(b.text("list"), None);
        assert_eq!(b.text("absent"), None);
    }
}
