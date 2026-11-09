//! The one multipart reader behind `POST /api/materials` and `POST /api/assignments/{id}/submit`.
//!
//! The `file` part streams into `storage/tmp` as it arrives, never into memory (a video is up to
//! 250 MB). Parts may come in any order, so the caller checks the text fields only after the
//! whole body is read; if a check fails, dropping the form deletes the temp file.

use std::collections::HashMap;

use axum::extract::multipart::{Field, MultipartError, MultipartRejection};
use axum::extract::Multipart;
use axum::http::header::CONTENT_LENGTH;
use axum::http::{HeaderMap, StatusCode};

use crate::error::ApiError;
use crate::storage::{Received, Storage, UploadError, MAX_UPLOAD_BYTES};

/// The route body limit: the file cap plus room for the boundaries and text fields. Axum's 2 MB
/// default would refuse every real upload.
pub const UPLOAD_BODY_LIMIT: usize = MAX_UPLOAD_BYTES as usize + 1024 * 1024;

/// Ids and titles only; a longer text part is a malformed form, not something to buffer.
const TEXT_FIELD_LIMIT: usize = 4 * 1024;

pub struct UploadForm {
    pub fields: HashMap<String, String>,
    pub file: Option<Received>,
}

impl UploadForm {
    /// The mock's `need`: one 400 naming every missing text field.
    pub fn need(&self, keys: &[&str]) -> Result<(), ApiError> {
        let missing: Vec<&str> = keys.iter().copied().filter(|k| !self.fields.contains_key(*k)).collect();
        if missing.is_empty() {
            Ok(())
        } else {
            Err(ApiError::bad_request(format!("Missing: {}", missing.join(", "))))
        }
    }

    /// A field as sent. An empty optional id counts as not sent: form builders send blanks.
    pub fn field(&self, key: &str) -> Option<&str> {
        self.fields.get(key).map(String::as_str).filter(|v| !v.is_empty())
    }

    /// The mock's second check, after the text fields.
    pub fn take_file(&mut self) -> Result<Received, ApiError> {
        self.file.take().ok_or_else(|| ApiError::bad_request("Missing file"))
    }
}

/// Reads the listed text fields and the `file` part; other parts are skipped unread.
pub async fn read_form(
    storage: &Storage,
    headers: &HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
    text_fields: &[&str],
) -> Result<UploadForm, ApiError> {
    // A declared length over the limit is refused before a byte is read or written to disk.
    let declared = headers.get(CONTENT_LENGTH).and_then(|v| v.to_str().ok()).and_then(|v| v.parse::<u64>().ok());
    if declared.is_some_and(|n| n > UPLOAD_BODY_LIMIT as u64) {
        return Err(ApiError::file_too_large());
    }
    let mut multipart = multipart.map_err(|_| ApiError::bad_request("Expected multipart/form-data"))?;
    let mut form = UploadForm { fields: HashMap::new(), file: None };
    while let Some(mut field) = multipart.next_field().await.map_err(multipart_error)? {
        let Some(name) = field.name().map(str::to_owned) else { continue };
        if name == "file" {
            if form.file.is_some() {
                return Err(ApiError::bad_request("Send one file"));
            }
            form.file = Some(receive(storage, &mut field).await?);
        } else if text_fields.contains(&name.as_str()) {
            let text = read_text(&mut field, &name).await?;
            form.fields.insert(name, text);
        }
    }
    Ok(form)
}

/// For an upload that cannot be written or moved (disk full, permissions).
pub fn storage_error(error: std::io::Error) -> ApiError {
    tracing::error!(%error, "could not store an upload");
    ApiError::internal("Could not save the file on the Hub")
}

async fn receive(storage: &Storage, field: &mut Field<'_>) -> Result<Received, ApiError> {
    let mut upload = storage.temp().await.map_err(storage_error)?;
    while let Some(chunk) = field.chunk().await.map_err(multipart_error)? {
        upload.write(&chunk).await.map_err(|error| match error {
            UploadError::TooLarge => ApiError::file_too_large(),
            UploadError::Io(error) => storage_error(error),
        })?;
    }
    upload.finish().await.map_err(storage_error)
}

async fn read_text(field: &mut Field<'_>, name: &str) -> Result<String, ApiError> {
    let mut bytes = Vec::new();
    while let Some(chunk) = field.chunk().await.map_err(multipart_error)? {
        if bytes.len() + chunk.len() > TEXT_FIELD_LIMIT {
            return Err(ApiError::bad_request(format!("{name} is too long")));
        }
        bytes.extend_from_slice(&chunk);
    }
    String::from_utf8(bytes).map_err(|_| ApiError::bad_request(format!("{name} must be text")))
}

/// The body limit surfaces here as a 413 multipart error; anything else is a malformed body.
fn multipart_error(error: MultipartError) -> ApiError {
    if error.status() == StatusCode::PAYLOAD_TOO_LARGE {
        ApiError::file_too_large()
    } else {
        ApiError::bad_request("Invalid multipart body")
    }
}
