//! JSON request bodies read the way the mock reads them: whatever the `Content-Type`, an empty
//! body is `{}`, anything that is not a JSON object is 400 `Invalid JSON`, and missing keys are
//! named in one 400 (`Missing: a, b`). Axum's `Json` extractor would answer 415 or 422 instead.

use axum::body::Bytes;
use axum::extract::{FromRequest, Request};
use serde_json::{Map, Value};

use crate::error::ApiError;
use crate::AppState;

pub struct JsonBody(pub Map<String, Value>);

/// 2^53 - 1, the largest integer JavaScript and the desktop app store exactly. The desktop refuses a
/// pulled integer outside `0..=MAX_SAFE_INT` and would stop syncing, so the Hub never stores one.
pub const MAX_SAFE_INT: i64 = 9_007_199_254_740_991;

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

    /// `optional_int` limited to `min..=MAX_SAFE_INT`.
    pub fn optional_int_from(&self, key: &str, min: i64) -> Result<Option<i64>, ApiError> {
        match self.optional_int(key)? {
            Some(n) if !(min..=MAX_SAFE_INT).contains(&n) => {
                Err(ApiError::bad_request(format!("{key} must be from {min} to {MAX_SAFE_INT}")))
            }
            n => Ok(n),
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
    fn bounded_ints_stay_inside_what_clients_can_store() {
        let b = body(json!({"zero": 0, "neg": -1, "top": 9_007_199_254_740_991_i64, "over": 9_007_199_254_740_992_i64, "s": "1"}));
        assert_eq!(b.optional_int_from("zero", 0).unwrap(), Some(0));
        assert_eq!(b.optional_int_from("top", 1).unwrap(), Some(9_007_199_254_740_991));
        assert_eq!(b.optional_int_from("absent", 0).unwrap(), None);
        assert_eq!(b.optional_int_from("neg", 0).unwrap_err().message, "neg must be from 0 to 9007199254740991");
        assert_eq!(b.optional_int_from("over", 0).unwrap_err().message, "over must be from 0 to 9007199254740991");
        assert_eq!(b.optional_int_from("zero", 1).unwrap_err().message, "zero must be from 1 to 9007199254740991");
        assert_eq!(b.optional_int_from("s", 0).unwrap_err().message, "s must be a whole number");
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
