//! One module per area. Each registers the contract paths it owns; until the area's step
//! lands they answer 501 `NOT_IMPLEMENTED`, after the same token check the real route will do.

mod admin;
mod assignments;
mod auth;
mod classrooms;
mod export;
mod materials;
mod quizzes;
mod sync;

use axum::extract::Request;
use axum::http::{Method, Uri};
use axum::Router;
use tower_http::trace::TraceLayer;

use crate::auth::AuthUser;
use crate::error::ApiError;
use crate::AppState;

pub fn router(app: AppState) -> Router {
    Router::new()
        .merge(admin::router())
        .merge(assignments::router())
        .merge(auth::router())
        .merge(classrooms::router())
        .merge(export::router())
        .merge(materials::router())
        .merge(quizzes::router())
        .merge(sync::router())
        // The mock matches method and path together, so a wrong method is also ROUTE_NOT_FOUND.
        .fallback(route_not_found)
        .method_not_allowed_fallback(route_not_found)
        // Path only: media routes will carry `?token=`, which must never reach a log.
        .layer(TraceLayer::new_for_http().make_span_with(|req: &Request| {
            tracing::info_span!("http", method = %req.method(), path = %req.uri().path())
        }))
        .with_state(app)
}

async fn route_not_found(method: Method, uri: Uri) -> ApiError {
    ApiError::route_not_found(&method, uri.path())
}

/// Stub for the routes the contract leaves open (register, login, admin setup).
async fn not_implemented(method: Method, uri: Uri) -> ApiError {
    ApiError::not_implemented(&method, uri.path())
}

/// Stub for every other route: 401 without a valid token, as the real route will answer.
async fn not_implemented_signed_in(_user: AuthUser, method: Method, uri: Uri) -> ApiError {
    ApiError::not_implemented(&method, uri.path())
}
