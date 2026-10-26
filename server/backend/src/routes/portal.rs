//! The captive download portal (`GET /download`, contract `openapi.yaml`). No token: a learner who
//! just joined the Wi-Fi opens it to get the app. It serves a bilingual 3-step sideloading guide
//! and the client installers.
//!
//! Everything the page needs (HTML, CSS, Nunito and Phosphor) is compiled into the binary from
//! `static/portal/`, so the portal renders with no internet and no files on disk (invariant 3.1).
//! The installers themselves are too large and are not committed (`.gitignore`); the Hub serves
//! them from `<data_dir>/downloads/` once the build or the installer step (1.4) puts them there.

use axum::body::Body;
use axum::extract::{Path, State};
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;

use crate::AppState;

/// One bundled asset: its bytes and the `Content-Type` to send.
struct Asset {
    bytes: &'static [u8],
    content_type: &'static str,
}

const INDEX_HTML: Asset = Asset {
    bytes: include_bytes!("../../static/portal/index.html"),
    content_type: "text/html; charset=utf-8",
};

/// The page's own CSS and web fonts. The map is tiny and fixed, so a match is clearer than a loader.
fn bundled_asset(path: &str) -> Option<Asset> {
    let asset = match path {
        "fonts.css" => Asset {
            bytes: include_bytes!("../../static/portal/assets/fonts.css"),
            content_type: "text/css; charset=utf-8",
        },
        "tokens.css" => Asset {
            bytes: include_bytes!("../../static/portal/assets/tokens.css"),
            content_type: "text/css; charset=utf-8",
        },
        "web/nunito-latin-400-normal.woff2" => woff2(include_bytes!(
            "../../static/portal/assets/web/nunito-latin-400-normal.woff2"
        )),
        "web/nunito-latin-700-normal.woff2" => woff2(include_bytes!(
            "../../static/portal/assets/web/nunito-latin-700-normal.woff2"
        )),
        "web/nunito-latin-800-normal.woff2" => woff2(include_bytes!(
            "../../static/portal/assets/web/nunito-latin-800-normal.woff2"
        )),
        "web/nunito-latin-900-normal.woff2" => woff2(include_bytes!(
            "../../static/portal/assets/web/nunito-latin-900-normal.woff2"
        )),
        "web/Phosphor.woff2" => {
            woff2(include_bytes!("../../static/portal/assets/web/Phosphor.woff2"))
        }
        _ => return None,
    };
    Some(asset)
}

fn woff2(bytes: &'static [u8]) -> Asset {
    Asset { bytes, content_type: "font/woff2" }
}

pub fn router() -> Router<AppState> {
    Router::new()
        // The portal page. `/` is the captive-portal landing a redirect lands on; both show it.
        .route("/", get(page))
        .route("/download", get(page))
        // Bundled page assets (CSS, fonts) and then the client installers from disk.
        .route("/download/assets/{*path}", get(asset))
        .route("/download/{file}", get(installer))
}

async fn page() -> Response {
    serve_bytes(INDEX_HTML, false)
}

async fn asset(Path(path): Path<String>) -> Response {
    match bundled_asset(&path) {
        Some(asset) => serve_bytes(asset, true),
        None => not_found(),
    }
}

/// A client installer (APK, `.exe`, `.deb`, ...) from `<data_dir>/releases/<file>`. Missing file:
/// 404, so a learner sees the browser's "not found" rather than a broken download, and the page
/// still loads (the contract notes "404 missing installer").
async fn installer(State(app): State<AppState>, Path(file): Path<String>) -> Response {
    let Some(name) = safe_file_name(&file) else { return not_found() };
    let full = app.releases_dir.join(name);
    // Byte-range resume and the 2.0 MB/s per-client cap come with the shared download work in step
    // 3.2, which wraps every download route and the portal. Until then this is a plain read.
    let Ok(bytes) = tokio::fs::read(&full).await else { return not_found() };

    let mut res = Response::new(Body::from(bytes));
    res.headers_mut()
        .insert(header::CONTENT_TYPE, HeaderValue::from_static("application/octet-stream"));
    res
}

/// Accepts only a plain file name: no path separators, no `.`/`..` segments, no empty name. This is
/// the only guard between a URL and the filesystem, so it rejects anything that could escape
/// `releases_dir`.
fn safe_file_name(file: &str) -> Option<&str> {
    if file.is_empty() || file == "." || file == ".." {
        return None;
    }
    if file.contains('/') || file.contains('\\') || file.contains('\0') {
        return None;
    }
    Some(file)
}

fn serve_bytes(asset: Asset, cacheable: bool) -> Response {
    let mut res = Response::new(Body::from(asset.bytes));
    let headers = res.headers_mut();
    headers.insert(header::CONTENT_TYPE, HeaderValue::from_static(asset.content_type));
    if cacheable {
        // Fonts and CSS are versioned with the Hub build; a day of caching spares budget phones.
        headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=86400"));
    }
    res
}

/// The portal is unauthenticated, so a miss is a plain 404, not the API's JSON error envelope.
fn not_found() -> Response {
    (StatusCode::NOT_FOUND, "Not found").into_response()
}
