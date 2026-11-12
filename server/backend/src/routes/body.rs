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

    /// Absent is `None`; present must be a string. The mock stores whatever was sent, but a
    /// wrong type here would fail a CHECK or end up in a client's typed model.
    pub fn optional_text(&self, key: &str) -> Result<Option<String>, ApiError> {
        match self.0.get(key) {
            None => Ok(None),
            Some(Value::String(text)) => Ok(Some(text.clone())),
            Some(_) => Err(ApiError::bad_request(format!("{key} must be text"))),
        }
    }

    /// Like `optional_text`, but blank text (after trimming) is refused. The text is kept as sent.
    pub fn non_empty_text(&self, key: &str) -> Result<Option<String>, ApiError> {
        let text = self.optional_text(key)?;
        if text.as_deref().is_some_and(|t| t.trim().is_empty()) {
            return Err(ApiError::bad_request(format!("{key} cannot be empty")));
        }
        Ok(text)
    }

    /// For a key a PATCH can clear: absent is `None`, `null` is `Some(None)`.
    pub fn nullable_text(&self, key: &str) -> Result<Option<Option<String>>, ApiError> {
        match self.0.get(key) {
            None => Ok(None),
            Some(Value::Null) => Ok(Some(None)),
            Some(Value::String(text)) => Ok(Some(Some(text.clone()))),
            Some(_) => Err(ApiError::bad_request(format!("{key} must be text"))),
        }
    }

    pub fn optional_bool(&self, key: &str) -> Result<Option<bool>, ApiError> {
        match self.0.get(key) {
            None => Ok(None),
            Some(Value::Bool(flag)) => Ok(Some(*flag)),
            Some(_) => Err(ApiError::bad_request(format!("{key} must be true or false"))),
        }
    }

    /// JSON integers only: `1.5`, `"5"` and `null` are refused.
    pub fn optional_int(&self, key: &str) -> Result<Option<i64>, ApiError> {
        match self.0.get(key) {
            None => Ok(None),
            Some(value) => {
                value.as_i64().map(Some).ok_or_else(|| ApiError::bad_request(format!("{key} must be a whole number")))
            }
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
    fn typed_readers_refuse_the_wrong_json_type() {
        let b = body(json!({"t": "x", "flag": true, "n": 5, "half": 1.5, "s5": "5", "gone": null, "blank": "  "}));
        assert_eq!(b.optional_text("t").unwrap().as_deref(), Some("x"));
        assert_eq!(b.optional_text("absent").unwrap(), None);
        assert_eq!(b.optional_text("n").unwrap_err().message, "n must be text");
        assert_eq!(b.optional_bool("flag").unwrap(), Some(true));
        assert_eq!(b.optional_bool("absent").unwrap(), None);
        assert_eq!(b.optional_bool("t").unwrap_err().message, "t must be true or false");
        assert_eq!(b.optional_int("n").unwrap(), Some(5));
        assert_eq!(b.optional_int("absent").unwrap(), None);
        for key in ["half", "s5", "gone"] {
            assert_eq!(b.optional_int(key).unwrap_err().message, format!("{key} must be a whole number"));
        }
        assert_eq!(b.nullable_text("gone").unwrap(), Some(None));
        assert_eq!(b.nullable_text("t").unwrap(), Some(Some("x".into())));
        assert_eq!(b.nullable_text("absent").unwrap(), None);
        assert_eq!(b.nullable_text("n").unwrap_err().message, "n must be text");
        assert_eq!(b.non_empty_text("blank").unwrap_err().message, "blank cannot be empty");
        assert_eq!(b.non_empty_text("t").unwrap().as_deref(), Some("x"));
        let err = b.optional_text("n").unwrap_err();
        assert_eq!((err.status.as_u16(), err.code.as_str()), (400, "BAD_REQUEST"));
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
