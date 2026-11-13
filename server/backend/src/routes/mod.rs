//! One module per area. Each registers the contract paths it owns; until the area's step
//! lands they answer 501 `NOT_IMPLEMENTED`, after the same token and role checks the real route
//! will do.

mod admin;
mod announcements;
mod assignments;
mod auth;
mod backup;
mod body;
mod classrooms;
mod export;
mod files;
mod materials;
mod portal;
mod private_comments;
mod quiz_control;
mod quiz_submit;
mod quizzes;
mod sync;
mod topics;
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
        .merge(announcements::router())
        .merge(assignments::router())
        .merge(auth::router())
        .merge(backup::router())
        .merge(classrooms::router())
        .merge(export::router())
        .merge(materials::router())
        .merge(portal::router())
        .merge(private_comments::router())
        .merge(quiz_control::router())
        .merge(quiz_submit::router())
        .merge(quizzes::router())
        .merge(sync::router())
        .merge(topics::router())
        // The mock matches method and path together, so a wrong method is also ROUTE_NOT_FOUND.
        .fallback(route_not_found)
        .method_not_allowed_fallback(route_not_found)
        // Path only: media routes will carry `?token=`, which must never reach a log.
        .layer(TraceLayer::new_for_http().make_span_with(|req: &Request| {
            tracing::info_span!("http", method = %req.method(), path = %req.uri().path())
        }))
        // The bundled Hub window calls REST on loopback. Other web origins get no CORS access.
        .layer(CorsLayer::new()
            .allow_origin(allowed_origins())
            .allow_methods([Method::GET, Method::POST])
            .allow_headers([header::CONTENT_TYPE, header::AUTHORIZATION]))
        .with_state(app)
}

/// The Tauri window's origins, plus the Vite dev server (`npm run dev` in `ui/`) in debug builds
/// only, so a release Hub never answers CORS for `:5173`.
fn allowed_origins() -> Vec<HeaderValue> {
    let mut origins = vec![
        HeaderValue::from_static("tauri://localhost"),
        HeaderValue::from_static("http://tauri.localhost"),
        HeaderValue::from_static("https://tauri.localhost"),
    ];
    if cfg!(debug_assertions) {
        origins.push(HeaderValue::from_static("http://localhost:5173"));
        origins.push(HeaderValue::from_static("http://127.0.0.1:5173"));
    }
    origins
}

async fn route_not_found(method: Method, uri: Uri) -> ApiError {
    ApiError::route_not_found(&method, uri.path())
}

/// Stubs for routes with a role in the mock (`@route(..., role=...)`): 401, then the mock's 403.
async fn not_implemented_teacher(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Teacher, &method, &uri)
}

async fn not_implemented_admin(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Admin, &method, &uri)
}

fn stub_for(user: &AuthUser, role: Role, method: &Method, uri: &Uri) -> ApiError {
    require_role(user, role).err().unwrap_or_else(|| ApiError::not_implemented(method, uri.path()))
}
