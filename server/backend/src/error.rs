use axum::http::header::RETRY_AFTER;
use axum::http::{Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

/// The contract's error code strings, in one place. REST and `EVENT_ERROR` share them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ErrorCode {
    BadRequest,
    Unauthorized,
    Forbidden,
    NotFound,
    ClassCodeInvalid,
    /// Mock code, not yet in the contract (TECH_SPEC section 14, Q8).
    AlreadyExists,
    AdminExists,
    /// 429 with `Retry-After` (contract PR #197, TECH_SPEC section 14, Q7).
    TooManyAttempts,
    /// 403: the admin signs in only on the Hub PC (contract PR #197).
    AdminLocalOnly,
    /// Mock code, not yet in the contract (TECH_SPEC section 14, Q8).
    RouteNotFound,
    /// 413: an upload over 250 MB (contract `POST /api/materials`).
    FileTooLarge,
    /// 409: homework after the due date when the assignment does not allow late work (contract #257).
    LateSubmission,
    /// 409: a different file for homework the teacher already graded (contract #257).
    AlreadyGraded,
    /// 403: a comment on a post whose `allow_comments` is false (contract `/api/announcements/{id}/comments`).
    CommentsDisabled,
    /// Area stubs until their step lands; no client depends on it.
    NotImplemented,
    /// The database failed. The contract has no code for it yet (TECH_SPEC section 14, Q10).
    Internal,
}

impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::BadRequest => "BAD_REQUEST",
            Self::Unauthorized => "UNAUTHORIZED",
            Self::Forbidden => "FORBIDDEN",
            Self::NotFound => "NOT_FOUND",
            Self::ClassCodeInvalid => "CLASS_CODE_INVALID",
            Self::AlreadyExists => "ALREADY_EXISTS",
            Self::AdminExists => "ADMIN_EXISTS",
            Self::TooManyAttempts => "TOO_MANY_ATTEMPTS",
            Self::AdminLocalOnly => "ADMIN_LOCAL_ONLY",
            Self::RouteNotFound => "ROUTE_NOT_FOUND",
            Self::FileTooLarge => "FILE_TOO_LARGE",
            Self::LateSubmission => "LATE_SUBMISSION",
            Self::AlreadyGraded => "ALREADY_GRADED",
            Self::CommentsDisabled => "COMMENTS_DISABLED",
            Self::NotImplemented => "NOT_IMPLEMENTED",
            Self::Internal => "INTERNAL_ERROR",
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
    /// Sent as `Retry-After` (seconds) when set.
    pub retry_after_secs: Option<u64>,
}

impl ApiError {
    pub fn new(status: StatusCode, code: ErrorCode, message: impl Into<String>) -> Self {
        Self { status, code, message: message.into(), retry_after_secs: None }
    }

    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, ErrorCode::BadRequest, message)
    }

    pub fn unauthorized() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, ErrorCode::Unauthorized, "Missing or expired token")
    }

    /// Unknown LRN, wrong PIN and deactivated account all answer the same, so the message never
    /// says which part was wrong. (It does not hide which LRNs exist: register's 409 tells, and
    /// a real account takes one PIN hash longer to answer.)
    pub fn wrong_credentials() -> Self {
        Self::new(StatusCode::UNAUTHORIZED, ErrorCode::Unauthorized, "Wrong LRN or PIN")
    }

    pub fn forbidden(message: impl Into<String>) -> Self {
        Self::new(StatusCode::FORBIDDEN, ErrorCode::Forbidden, message)
    }

    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, ErrorCode::NotFound, message)
    }

    pub fn class_code_invalid() -> Self {
        Self::new(StatusCode::NOT_FOUND, ErrorCode::ClassCodeInvalid, "Class code not found")
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, ErrorCode::Internal, message)
    }

    pub fn too_many_attempts(retry_after_secs: u64) -> Self {
        let mut error = Self::new(
            StatusCode::TOO_MANY_REQUESTS,
            ErrorCode::TooManyAttempts,
            "Too many wrong PINs. Wait a minute and try again",
        );
        error.retry_after_secs = Some(retry_after_secs);
        error
    }

    pub fn admin_local_only() -> Self {
        Self::new(StatusCode::FORBIDDEN, ErrorCode::AdminLocalOnly, "The admin signs in on the Hub PC only")
    }

    /// The mock's message. Uploads are refused before the file is kept (`storage`).
    pub fn file_too_large() -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, ErrorCode::FileTooLarge, "Max 250MB")
    }

    /// The mock's message (`submission_create` in `scripts/mock_hub.py`).
    pub fn late_submission() -> Self {
        Self::new(StatusCode::CONFLICT, ErrorCode::LateSubmission, "This assignment does not accept late submissions")
    }

    /// The mock's message (`submission_create` in `scripts/mock_hub.py`).
    pub fn already_graded() -> Self {
        Self::new(StatusCode::CONFLICT, ErrorCode::AlreadyGraded, "This submission has already been graded")
    }

    /// English, unlike the mock's "Naka-off ang komento": the apps are English only (AGENTS.md 1.4).
    pub fn comments_disabled() -> Self {
        Self::new(StatusCode::FORBIDDEN, ErrorCode::CommentsDisabled, "Comments are turned off for this post")
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

/// SQLx errors name the statement and constraint, never a bound value, so logging them cannot
/// leak a PIN, token or LRN.
impl From<sqlx::Error> for ApiError {
    fn from(error: sqlx::Error) -> Self {
        tracing::error!(%error, "database error");
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, ErrorCode::Internal, "Hub database error")
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = Envelope { error: Body { code: self.code, message: &self.message } };
        let mut response = (self.status, Json(body)).into_response();
        if let Some(secs) = self.retry_after_secs {
            response.headers_mut().insert(RETRY_AFTER, secs.into());
        }
        response
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::header::RETRY_AFTER;

    async fn body_json(response: Response) -> serde_json::Value {
        let bytes = axum::body::to_bytes(response.into_body(), 1 << 16).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }

    #[tokio::test]
    async fn too_many_attempts_sets_retry_after() {
        let response = ApiError::too_many_attempts(42).into_response();
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers().get(RETRY_AFTER).unwrap(), "42");
        assert_eq!(body_json(response).await["error"]["code"], "TOO_MANY_ATTEMPTS");
    }

    #[tokio::test]
    async fn plain_errors_have_no_retry_after() {
        for error in [ApiError::wrong_credentials(), ApiError::forbidden("Teacher only"), ApiError::bad_request("x")] {
            let response = error.into_response();
            assert!(response.headers().get(RETRY_AFTER).is_none());
        }
    }

    #[test]
    fn auth_codes_use_the_mock_strings() {
        let codes = [
            (ErrorCode::Forbidden, "FORBIDDEN"),
            (ErrorCode::AlreadyExists, "ALREADY_EXISTS"),
            (ErrorCode::AdminExists, "ADMIN_EXISTS"),
            (ErrorCode::TooManyAttempts, "TOO_MANY_ATTEMPTS"),
            (ErrorCode::AdminLocalOnly, "ADMIN_LOCAL_ONLY"),
            (ErrorCode::Internal, "INTERNAL_ERROR"),
            (ErrorCode::FileTooLarge, "FILE_TOO_LARGE"),
        ];
        for (code, text) in codes {
            assert_eq!(code.as_str(), text);
        }
    }

    #[tokio::test]
    async fn file_too_large_is_the_contracts_413() {
        let response = ApiError::file_too_large().into_response();
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(body_json(response).await["error"]["code"], "FILE_TOO_LARGE");
    }

    #[tokio::test]
    async fn wrong_credentials_is_the_mocks_401() {
        let response = ApiError::wrong_credentials().into_response();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        let body = body_json(response).await;
        assert_eq!((body["error"]["code"].as_str(), body["error"]["message"].as_str()), (Some("UNAUTHORIZED"), Some("Wrong LRN or PIN")));
    }
}
