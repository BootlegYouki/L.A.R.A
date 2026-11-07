//! Serving a stored upload. `ServeFile` streams it off disk (never into memory) and answers
//! `Range` with `206`, so an interrupted download resumes (PRD section 8). The 2.0 MB/s per-client
//! cap is added across the download routes in step 3.2.

use std::path::PathBuf;

use axum::extract::Request;
use axum::http::{header, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use mime::Mime;
use tower::ServiceExt;
use tower_http::services::ServeFile;

use crate::error::ApiError;

/// `nosniff` keeps a browser from reading a file as anything but its declared type.
pub async fn serve_stored(path: PathBuf, content_type: &str, request: Request, id: &str) -> Result<Response, ApiError> {
    let mime: Mime = content_type.parse().map_err(|_| ApiError::internal("Unknown file type"))?;
    let response = match ServeFile::new_with_mime(&path, &mime).oneshot(request).await {
        Ok(response) => response,
        Err(_) => return Err(missing(id)),
    };
    // The row is there but its file is not (deleted by hand, or a restore without storage/).
    if response.status() == StatusCode::NOT_FOUND {
        return Err(missing(id));
    }
    let mut response = response.into_response();
    response.headers_mut().insert(header::X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    Ok(response)
}

fn missing(id: &str) -> ApiError {
    tracing::warn!(id, "a stored file is missing from storage/");
    ApiError::not_found("File not found")
}
