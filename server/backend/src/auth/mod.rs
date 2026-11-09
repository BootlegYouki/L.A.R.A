pub mod limiter;
pub mod pin;
pub mod sessions;
pub mod token;

use std::net::SocketAddr;

use axum::extract::{FromRequestParts, Query};
use axum::http::header::AUTHORIZATION;
use axum::http::request::Parts;
use axum::http::{HeaderMap, Uri};
use serde::{Deserialize, Serialize};

use crate::error::ApiError;
use crate::AppState;

/// The single Hub admin's fixed `lrn_or_id` (contract `/api/admin/setup`). Reserved: no one
/// can register it, so setup always stays possible.
pub const ADMIN_ID: &str = "ADMIN-0001";

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

/// A request from the Hub PC itself: a loopback peer that did not come through the
/// Remote/Suspension Mode tunnel. `cloudflared` connects from loopback too, but adds
/// `Cf-Connecting-IP` (contract PR #197). Admin setup and admin login accept only these.
pub fn is_hub_pc(peer: SocketAddr, headers: &HeaderMap) -> bool {
    peer.ip().to_canonical().is_loopback() && !headers.contains_key(CF_CONNECTING_IP)
}

const CF_CONNECTING_IP: &str = "cf-connecting-ip";

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

/// [`AuthUser`] for media routes only (`/api/materials/{id}/download`, `/api/submissions/{id}/file`;
/// step 3.2 adds `/stream`): HTML5 video and image views cannot send headers, so the contract
/// (`TokenQuery`) also accepts `?token=` there. Every other route takes `AuthUser`, header only.
pub struct MediaUser(pub AuthUser);

impl FromRequestParts<AppState> for MediaUser {
    type Rejection = ApiError;

    async fn from_request_parts(parts: &mut Parts, app: &AppState) -> Result<Self, Self::Rejection> {
        let token = media_token(&parts.headers, &parts.uri).ok_or_else(ApiError::unauthorized)?;
        let user = sessions::user_for_token(&app.db, &token, app.clock.now_ms()).await?;
        user.map(Self).ok_or_else(ApiError::unauthorized)
    }
}

#[derive(Deserialize)]
struct TokenQuery {
    token: Option<String>,
}

/// The bearer header when present, else `?token=` (as the mock's `_token` reads it).
fn media_token(headers: &HeaderMap, uri: &Uri) -> Option<String> {
    if let Some(token) = bearer_token(headers) {
        return Some(token.to_owned());
    }
    let Query(query) = Query::<TokenQuery>::try_from_uri(uri).ok()?;
    query.token.filter(|token| !token.is_empty())
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
    fn media_routes_take_the_header_then_the_query() {
        let uri = |s: &str| s.parse::<Uri>().unwrap();
        assert_eq!(media_token(&HeaderMap::new(), &uri("/api/materials/m/download?token=abc")).as_deref(), Some("abc"));
        assert_eq!(media_token(&headers("Bearer hdr"), &uri("/x?token=abc")).as_deref(), Some("hdr"));
        assert_eq!(media_token(&HeaderMap::new(), &uri("/x?other=1&token=abc")).as_deref(), Some("abc"));
        for bare in ["/x", "/x?token=", "/x?tokens=abc"] {
            assert_eq!(media_token(&HeaderMap::new(), &uri(bare)), None, "{bare}");
        }
    }

    #[test]
    fn hub_pc_is_loopback_without_the_tunnel_header() {
        let local: SocketAddr = "127.0.0.1:50000".parse().unwrap();
        let mut tunnel = HeaderMap::new();
        tunnel.insert("cf-connecting-ip", HeaderValue::from_static("203.0.113.7"));
        assert!(is_hub_pc(local, &HeaderMap::new()));
        assert!(is_hub_pc("[::1]:50000".parse().unwrap(), &HeaderMap::new()));
        assert!(is_hub_pc("[::ffff:127.0.0.1]:50000".parse().unwrap(), &HeaderMap::new()), "IPv4 loopback seen on an IPv6 socket");
        assert!(!is_hub_pc(local, &tunnel), "cloudflared relays from loopback");
        assert!(!is_hub_pc("192.168.1.20:50000".parse().unwrap(), &HeaderMap::new()));
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
