use std::collections::HashMap;
use std::sync::{PoisonError, RwLock};

use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use axum::http::HeaderMap;

use crate::error::ApiError;
use crate::AppState;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Teacher,
    Student,
    Admin,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AuthUser {
    pub id: String,
    pub role: Role,
}

/// Token to user lookup shared by the REST extractor and the WebSocket handshake.
///
/// Nothing issues tokens until SERVER 2.0 (login), so outside tests this is empty and
/// every token is rejected. 2.0 replaces the map with the `sessions` table (SHA-256 only).
#[derive(Default)]
pub struct Sessions {
    users: RwLock<HashMap<String, AuthUser>>,
}

impl Sessions {
    pub fn insert(&self, token: &str, user: AuthUser) {
        self.users.write().unwrap_or_else(PoisonError::into_inner).insert(token.to_owned(), user);
    }

    pub fn user_for_token(&self, token: &str) -> Option<AuthUser> {
        self.users.read().unwrap_or_else(PoisonError::into_inner).get(token).cloned()
    }
}

fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let token = headers.get(AUTHORIZATION)?.to_str().ok()?.strip_prefix("Bearer ")?;
    (!token.is_empty()).then_some(token)
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, app: &AppState) -> Result<Self, Self::Rejection> {
        let token = bearer_token(&parts.headers).ok_or_else(ApiError::unauthorized)?;
        app.sessions.user_for_token(token).ok_or_else(ApiError::unauthorized)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::HeaderValue;

    fn headers(value: &str) -> HeaderMap {
        let mut h = HeaderMap::new();
        h.insert(AUTHORIZATION, HeaderValue::from_str(value).unwrap());
        h
    }

    #[test]
    fn bearer_token_needs_the_scheme_and_a_value() {
        assert_eq!(bearer_token(&headers("Bearer abc")), Some("abc"));
        assert_eq!(bearer_token(&headers("Bearer ")), None);
        assert_eq!(bearer_token(&headers("Basic abc")), None);
        assert_eq!(bearer_token(&HeaderMap::new()), None);
    }

    #[test]
    fn sessions_start_empty() {
        let sessions = Sessions::default();
        assert_eq!(sessions.user_for_token("anything"), None);
        let user = AuthUser { id: "T-0001".into(), role: Role::Teacher };
        sessions.insert("t", user.clone());
        assert_eq!(sessions.user_for_token("t"), Some(user));
    }
}
