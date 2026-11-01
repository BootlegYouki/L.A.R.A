pub mod limiter;
pub mod pin;
pub mod sessions;
pub mod token;

use axum::extract::FromRequestParts;
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use axum::http::HeaderMap;
use serde::Serialize;

use crate::error::ApiError;
use crate::AppState;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Teacher,
    Student,
    Admin,
}

impl Role {
    /// The `users.role` and `User.role` value.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Teacher => "TEACHER",
            Self::Student => "STUDENT",
            Self::Admin => "ADMIN",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        [Self::Teacher, Self::Student, Self::Admin].into_iter().find(|role| role.as_str() == value)
    }
}

impl Serialize for Role {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(self.as_str())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AuthUser {
    pub id: String,
    pub role: Role,
}

/// 403 `FORBIDDEN` with the mock's message when the caller has another role.
pub fn require_role(user: &AuthUser, role: Role) -> Result<(), ApiError> {
    if user.role == role {
        return Ok(());
    }
    Err(ApiError::forbidden(match role {
        Role::Teacher => "Teacher only",
        Role::Student => "Learners only",
        Role::Admin => "Hub admin only",
    }))
}

/// The token after `Bearer `, as the mock reads it.
pub(crate) fn bearer_token(headers: &HeaderMap) -> Option<&str> {
    let token = headers.get(AUTHORIZATION)?.to_str().ok()?.strip_prefix("Bearer ")?;
    (!token.is_empty()).then_some(token)
}

impl FromRequestParts<AppState> for AuthUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, app: &AppState) -> Result<Self, Self::Rejection> {
        let token = bearer_token(&parts.headers).ok_or_else(ApiError::unauthorized)?;
        sessions::user_for_token(&app.db, token, app.clock.now_ms()).await?.ok_or_else(ApiError::unauthorized)
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
    fn require_role_answers_the_mocks_403s() {
        let roles = [(Role::Teacher, "Teacher only"), (Role::Student, "Learners only"), (Role::Admin, "Hub admin only")];
        for (needed, text) in roles {
            for (has, _) in roles {
                let user = AuthUser { id: "u".into(), role: has };
                match require_role(&user, needed) {
                    Ok(()) => assert_eq!(has, needed),
                    Err(e) => {
                        assert_ne!(has, needed);
                        assert_eq!((e.status.as_u16(), e.code.as_str(), e.message.as_str()), (403, "FORBIDDEN", text));
                    }
                }
            }
        }
    }

    #[test]
    fn roles_round_trip_through_the_contract_strings() {
        for role in [Role::Teacher, Role::Student, Role::Admin] {
            assert_eq!(Role::parse(role.as_str()), Some(role));
            assert_eq!(serde_json::to_value(role).unwrap(), role.as_str());
        }
        assert_eq!(Role::parse("teacher"), None);
    }
}
