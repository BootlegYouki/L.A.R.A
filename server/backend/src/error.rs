use axum::http::{Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

/// The contract's error code strings, in one place. REST and `EVENT_ERROR` share them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ErrorCode {
    BadRequest,
    Unauthorized,
    /// Mock code, not yet in the contract (TECH_SPEC section 14, Q8).
    RouteNotFound,
    /// Area stubs until their step lands; no client depends on it.
    NotImplemented,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::BadRequest => "BAD_REQUEST",
            Self::Unauthorized => "UNAUTHORIZED",
            Self::RouteNotFound => "ROUTE_NOT_FOUND",
            Self::NotImplemented => "NOT_IMPLEMENTED",
        }
    }
}

impl Serialize for ErrorCode {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(self.as_str())
    }
}

#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: ErrorCode,
    pub message: String,
}

impl ApiError {
    pub fn new(status: StatusCode, code: ErrorCode, message: impl Into<String>) -> Self {
        Self { status, code, message: message.into() }
    }

    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, ErrorCode::Unauthorized, "Missing or expired token")
    }

    pub fn route_not_found(method: &Method, path: &str) -> Self {
        Self::new(StatusCode::NOT_FOUND, ErrorCode::RouteNotFound, format!("{method} {path}"))
    }

    pub fn not_implemented(method: &Method, path: &str) -> Self {
        Self::new(StatusCode::NOT_IMPLEMENTED, ErrorCode::NotImplemented, format!("{method} {path} is not built yet"))
    }
}

#[derive(Serialize)]
struct Envelope<'a> {
    error: Body<'a>,
}

#[derive(Serialize)]
struct Body<'a> {
    code: ErrorCode,
    message: &'a str,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = Envelope { error: Body { code: self.code, message: &self.message } };
        (self.status, Json(body)).into_response()
    }
}
