//! Serving a stored upload. `ServeFile` streams it off disk (never into memory) and answers
//! `Range` with `206` and an unsatisfiable range with `416`. The 2.0 MB/s per-client cap is added
//! across the download routes in step 3.2.
//!
//! `ServeFile` ignores `If-Range`, so for a resumable download the Hub checks it here (contract
//! #259): a client resuming a file that changed since its first request gets the whole new file.

use std::path::PathBuf;

use axum::extract::Request;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use mime::Mime;
use sha2::{Digest, Sha256};
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::error::ApiError;

/// How a download treats `Range` (contract #259).
pub enum Ranges {
    /// `ServeFile`'s own handling (submission files; the contract says nothing more).
    AsServed,
    /// VIDEO downloads: `Range` and `If-Range` against this strong `ETag`, so Save for Home resumes.
    Resumable(HeaderValue),
    /// Every other material download: the whole file as `200`, whatever `Range` asks for.
    WholeFile,
}

/// A strong `ETag` for a stored file. Stored files are never changed in place (each upload gets a
/// new name), so the name identifies the bytes. Hashed, so the storage name stays private.
pub fn stored_etag(stored: &str) -> HeaderValue {
    let digest = Sha256::digest(stored.as_bytes());
    let hex: String = digest[..16].iter().map(|b| format!("{b:02x}")).collect();
    HeaderValue::from_str(&format!("\"{hex}\"")).expect("hex in quotes is a valid header value")
}

/// `nosniff` keeps a browser from reading a file as anything but its declared type.
pub async fn serve_stored(
    path: PathBuf,
    content_type: &str,
    mut request: Request,
    id: &str,
    ranges: Ranges,
) -> Result<Response, ApiError> {
    let mime: Mime = content_type.parse().map_err(|_| ApiError::internal("Unknown file type"))?;
    let headers = request.headers_mut();
    match &ranges {
        Ranges::AsServed => {}
        Ranges::Resumable(etag) => {
            // RFC 9110 13.1.5: a validator that no longer matches means "send the whole file".
            if headers.get(header::IF_RANGE).is_some_and(|sent| sent != etag) {
                headers.remove(header::RANGE);
            }
            headers.remove(header::IF_RANGE);
        }
        Ranges::WholeFile => {
            headers.remove(header::RANGE);
            headers.remove(header::IF_RANGE);
        }
    }
    let response = match ServeFile::new_with_mime(&path, &mime).oneshot(request).await {
        Ok(response) => response,
        Err(_) => return Err(missing(id)),
    };
    // The row is there but its file is not (deleted by hand, or a restore without storage/).
    if response.status() == StatusCode::NOT_FOUND {
        return Err(missing(id));
    }
    let mut response = response.into_response();
    let headers = response.headers_mut();
    headers.insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    match ranges {
        Ranges::AsServed => {}
        // Replaces `ServeFile`'s own metadata tag, so `If-Range` is compared with what clients got.
        Ranges::Resumable(etag) => {
            headers.insert(header::ETAG, etag);
        }
        Ranges::WholeFile => {
            headers.insert(header::ACCEPT_RANGES, HeaderValue::from_static("none"));
            headers.remove(header::ETAG);
        }
    }
    Ok(response)
}

fn missing(id: &str) -> ApiError {
    tracing::warn!(id, "a stored file is missing from storage/");
    ApiError::not_found("File not found")
}
