//! One module per area. Each registers the contract paths it owns; until the area's step
//! lands they answer 501 `NOT_IMPLEMENTED`, after the same token and role checks the real route
//! will do.

mod activity;
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
use tower_http::cors::CorsLayer;
use tower_http::trace::TraceLayer;

use crate::auth::{require_role, AuthUser, Role};
use crate::error::ApiError;
use crate::AppState;

pub fn router(app: AppState) -> Router {
    Router::new()
        .merge(admin::router())
        .merge(activity::router())
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
        // A wrong method uses the same ROUTE_NOT_FOUND envelope as an unknown path.
        .fallback(route_not_found)
        .method_not_allowed_fallback(route_not_found)
        .layer(axum::middleware::from_fn_with_state(app.clone(), record_failed_operation))
        // Path only: media routes will carry `?token=`, which must never reach a log.
        .layer(TraceLayer::new_for_http().make_span_with(
            |req: &Request| tracing::info_span!("http", method = crate::services::request_activity::method(req.method()), endpoint = crate::services::request_activity::endpoint(req)),
        ))
        // The bundled Hub window calls REST on loopback. Other web origins get no CORS access.
        .layer(
            CorsLayer::new()
                .allow_origin(allowed_origins())
                .allow_methods([Method::GET, Method::POST])
                .allow_headers([header::CONTENT_TYPE, header::AUTHORIZATION]),
        )
        // Outside CORS so preflight and all rejected requests also receive one safe entry.
        .layer(axum::middleware::from_fn_with_state(app.clone(), crate::services::request_activity::record))
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

async fn record_failed_operation(
    axum::extract::State(app): axum::extract::State<AppState>,
    mut request: Request,
    next: axum::middleware::Next,
) -> axum::response::Response {
    use crate::services::activity::{Code, Event, Reason, Service};
    let operation = if request.method() == Method::POST {
        let parts: Vec<_> = request.uri().path().trim_matches('/').split('/').collect();
        match parts.as_slice() {
            ["api", "admin", "users"] => Some((Code::AccountCreateFailed, Service::Accounts)),
            ["api", "admin", "users", _, "reset-pin"] => Some((Code::PinResetFailed, Service::Accounts)),
            ["api", "quizzes", _, "start"] => Some((Code::QuizStartFailed, Service::Quizzes)),
            ["api", "quizzes", _, "close"] => Some((Code::QuizCloseFailed, Service::Quizzes)),
            ["api", "materials"] => Some((Code::DocumentUploadFailed, Service::Documents)),
            _ => None,
        }
    } else {
        None
    };
    let may_record = if let Some((_, service)) = operation {
        use axum::extract::FromRequestParts;
        let (mut parts, body) = request.into_parts();
        let authorized = AuthUser::from_request_parts(&mut parts, &app)
            .await
            .is_ok_and(|user| user.role == if service == Service::Accounts { Role::Admin } else { Role::Teacher });
        request = Request::from_parts(parts, body);
        authorized
    } else {
        false
    };
    let response = next.run(request).await;
    let status = response.status();
    if let Some((code, service)) = operation {
        if may_record
            && (status.is_client_error() || status.is_server_error())
            && status.as_u16() != 401
            && status.as_u16() != 403
        {
            let reason = match status.as_u16() {
                409 => Reason::Conflict,
                413 => Reason::SizeLimit,
                500..=599 => Reason::InternalFailure,
                _ => Reason::InvalidInput,
            };
            app.activity.record(Event::new(code, service).reason(reason));
        }
    }
    response
}

/// Role-restricted stubs: require a valid session, then the contract role.
async fn not_implemented_teacher(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Teacher, &method, &uri)
}

async fn not_implemented_admin(user: AuthUser, method: Method, uri: Uri) -> ApiError {
    stub_for(&user, Role::Admin, &method, &uri)
}

fn stub_for(user: &AuthUser, role: Role, method: &Method, uri: &Uri) -> ApiError {
    require_role(user, role).err().unwrap_or_else(|| ApiError::not_implemented(method, uri.path()))
}
