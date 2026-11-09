//! One module per area. Each registers the contract paths it owns; until the area's step
//! lands they answer 501 `NOT_IMPLEMENTED`, after the same token and role checks the real route
//! will do.

mod admin;
mod assignments;
mod auth;
mod backup;
mod body;
mod classrooms;
mod export;
mod files;
mod materials;
mod portal;
mod quizzes;
mod sync;
mod upload;

use axum::extract::Request;
use axum::http::{header, HeaderValue, Method, Uri};
use axum::Router;
use tower_http::trace::TraceLayer;
use tower_http::cors::CorsLayer;

use crate::auth::{require_role, AuthUser, Role};
use crate::error::ApiError;
use crate::AppState;

pub fn router(app: AppState) -> Router {
    Router::new()
        .merge(admin::router())
        .merge(assignments::router())
        .merge(auth::router())
        .merge(backup::router())
        .merge(classrooms::router())
        .merge(export::router())
        .merge(materials::router())
        .merge(portal::router())
        .merge(quizzes::router())
        .merge(sync::router())
        // The mock matches method and path together, so a wrong method is also ROUTE_NOT_FOUND.
        .fallback(route_not_found)
        .method_not_allowed_fallback(route_not_found)
        // Path only: media routes will carry `?token=`, which must never reach a log.
        .layer(TraceLayer::new_for_http().make_span_with(|req: &Request| {
            tracing::info_span!("http", method = %req.method(), path = %req.uri().path())
        }))
        // The bundled Hub window calls REST on loopback. Other web origins get no CORS access.
        .layer(CorsLayer::new()
            .allow_origin([
                HeaderValue::from_static("tauri://localhost"),
                HeaderValue::from_static("http://tauri.localhost"),
                HeaderValue::from_static("https://tauri.localhost"),
                HeaderValue::from_static("http://localhost:5173"),
                HeaderValue::from_static("http://127.0.0.1:5173"),
            ])
            .allow_methods([Method::GET, Method::POST])
            .allow_headers([header::CONTENT_TYPE, header::AUTHORIZATION]))
        .with_state(app)
}

async fn route_not_found(method: Method, uri: Uri) -> ApiError {
    ApiError::route_not_found(&method, uri.path())
}

/// Stub for a route any signed-in user may call: 401 without a valid token, as the real route
/// will answer.
async fn not_implemented_signed_in(_user: AuthUser, method: Method, uri: Uri) -> ApiError {
    ApiError::not_implemented(&method, uri.path())
}

/// Stubs for routes with a role in the mock (`@route(..., role=...)`): 401, then the mock's 403.
async fn not_implemented_teacher(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Teacher, &method, &uri)
}

async fn not_implemented_student(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Student, &method, &uri)
}

async fn not_implemented_admin(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Admin, &method, &uri)
}

fn stub_for(user: &AuthUser, role: Role, method: &Method, uri: &Uri) -> ApiError {
    require_role(user, role).err().unwrap_or_else(|| ApiError::not_implemented(method, uri.path()))
}
