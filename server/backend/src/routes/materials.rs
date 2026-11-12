//! Lesson files (contract `/api/materials`). Upload and download are step 3.3, with contract #259's
//! resumable VIDEO downloads; lesson text chunks are 3.1 and the video stream 3.2. Rename, move to a
//! topic and delete are 3.4 (PRD FR-3.6): teachers of the class, not while it is archived.

use std::sync::Arc;

use axum::extract::multipart::MultipartRejection;
use axum::extract::{DefaultBodyLimit, Multipart, Path, Request, State};
use axum::http::HeaderMap;
use axum::response::Response;
use axum::routing::{get, patch, post};
use axum::{Json, Router};
use serde_json::{json, Value};
use tokio::sync::Semaphore;

use super::files::{serve_stored, stored_etag, Ranges};
use super::upload::{read_form, storage_error, UPLOAD_BODY_LIMIT};
use super::body::JsonBody;
use crate::auth::{require_role, AuthUser, MediaUser, Role};
use crate::db::begin_immediate;
use crate::db::material_chunks;
use crate::db::materials::{self, Attachment, NewMaterial};
use crate::error::ApiError;
use crate::models::sync::{Material, MaterialChunk};
use crate::services::class_access;
use crate::services::document_parser::{self, ChunkDraft};
use crate::storage::{FileFormat, StorageKind};
use crate::AppState;

const FILE_TYPES: [&str; 3] = ["VIDEO", "DOCUMENT", "WORKSHEET"];
/// Lesson text extraction (3.1) stops here, so a huge or hostile handout still uploads, with no chunks.
/// It includes the wait for a free parse slot (`PARSE_SLOTS`).
const EXTRACT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(20);

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/materials", post(upload).layer(DefaultBodyLimit::max(UPLOAD_BODY_LIMIT)))
        .route("/api/materials/{id}", patch(rename).delete(remove))
        .route("/api/materials/{id}/download", get(download))
        .route("/api/materials/{id}/chunks", get(chunks))
        .route("/api/materials/{id}/stream", get(stream))
}

/// The file is on disk before the transaction opens (no file I/O under the write lock), and is
/// deleted again by `StoredFile`'s drop if any check in the transaction refuses it.
async fn upload(
    State(app): State<AppState>,
    user: AuthUser,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Result<Json<Material>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let fields = ["classroom_id", "title", "file_type", "topic_id", "assignment_id", "announcement_id"];
    let mut form = read_form(&app.storage, &headers, multipart, &fields).await?;
    form.need(&["classroom_id", "title", "file_type"])?;
    let file = form.take_file()?;
    let file_type = form.fields["file_type"].as_str();
    if !FILE_TYPES.contains(&file_type) {
        return Err(ApiError::bad_request("Invalid file_type"));
    }
    let video = file_type == "VIDEO";
    let Some(format) = file.format.filter(|f| f.is_video() == video) else {
        return Err(ApiError::bad_request(if video {
            "File must be a video (MP4 or WebM)"
        } else {
            "File must be a PDF, Word document or image"
        }));
    };
    let classroom_id = form.fields["classroom_id"].as_str();
    // Read-only, before the file is kept or read (review of #267), so a teacher of another class
    // cannot make the Hub parse a handout. The transaction checks again: the class can change.
    class_access::require_teacher(&mut *app.db.acquire().await?, &user, classroom_id).await?;
    let size = i64::try_from(file.size).map_err(|_| ApiError::file_too_large())?;
    let stored = app.storage.commit(file, StorageKind::Material).await.map_err(storage_error)?;
    let id = uuid::Uuid::new_v4().to_string();
    // Lesson text for the AI tutor, before the write lock: parsing a long PDF takes CPU time.
    let chunks = if video { Vec::new() } else { extract(&app, &stored.relative, format, &form.fields["title"], &id).await };

    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    class_access::require_teacher(&mut tx, &user, classroom_id).await?;
    class_access::require_writable(&mut tx, classroom_id).await?;
    for attachment in Attachment::ALL {
        if let Some(target) = form.field(attachment.field()) {
            if !materials::attachment_in_class(&mut tx, attachment, target, classroom_id).await? {
                return Err(ApiError::not_found(attachment.not_found()));
            }
        }
    }
    let new = NewMaterial {
        id: &id,
        classroom_id,
        title: &form.fields["title"],
        file_type,
        mime_type: format.mime(),
        file_path: &stored.relative,
        file_size_bytes: size,
        uploaded_by: &user.id,
        topic_id: form.field("topic_id"),
        assignment_id: form.field("assignment_id"),
        announcement_id: form.field("announcement_id"),
    };
    let material = materials::insert(&mut tx, &new, now).await?;
    material_chunks::insert_all(&mut tx, &id, classroom_id, &chunks, now).await?;
    tx.commit().await?;
    stored.keep();
    Ok(Json(material))
}

fn material_not_found() -> ApiError {
    ApiError::not_found("Material not found")
}

async fn rename(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<Material>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let title = body.non_empty_text("title")?;
    let topic_id = body.nullable_text("topic_id")?;
    let mut tx = begin_immediate(&app.db).await?;
    let classroom_id = materials::class_of(&mut tx, &id).await?.ok_or_else(material_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    if let Some(Some(topic)) = &topic_id {
        if !materials::attachment_in_class(&mut tx, Attachment::Topic, topic, &classroom_id).await? {
            return Err(ApiError::not_found(Attachment::Topic.not_found()));
        }
    }
    let material = materials::update(&mut tx, &id, title, topic_id, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(material))
}

/// Materials hold no grades and are really deleted (rules section 4), with their lesson chunks. The
/// file goes after the commit; one already missing from disk does not stop the delete.
async fn remove(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Value>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let mut tx = begin_immediate(&app.db).await?;
    let classroom_id = materials::class_of(&mut tx, &id).await?.ok_or_else(material_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    let file = materials::delete(&mut tx, &id, &classroom_id, app.clock.now_ms()).await?;
    tx.commit().await?;
    app.storage.remove(&file).await;
    Ok(Json(json!({"success": true})))
}

/// Never fails the upload (#10): no text, an unreadable file, a parser panic or the timeout all give
/// zero chunks. Logs the material id, chunk count and time only, never the text.
async fn extract(app: &AppState, stored: &str, format: FileFormat, title: &str, material_id: &str) -> Vec<ChunkDraft> {
    if !matches!(format, FileFormat::Pdf | FileFormat::Docx) {
        return Vec::new();
    }
    let Some(path) = app.storage.path(stored) else { return Vec::new() };
    let title = title.to_owned();
    let started = std::time::Instant::now();
    let parse = run_in_slot(Arc::clone(&app.parse_slots), move || document_parser::extract_chunks(&path, format, &title));
    let chunks = match tokio::time::timeout(EXTRACT_TIMEOUT, parse).await {
        Ok(Some(chunks)) => chunks,
        Ok(None) => {
            tracing::warn!(material_id, "lesson text extraction stopped unexpectedly");
            Vec::new()
        }
        Err(_) => {
            tracing::warn!(material_id, seconds = EXTRACT_TIMEOUT.as_secs(), "lesson text extraction timed out");
            Vec::new()
        }
    };
    tracing::info!(material_id, chunks = chunks.len(), ms = started.elapsed().as_millis() as u64, "lesson text extracted");
    chunks
}

/// Runs `parse` on a blocking thread once one of the Hub's parse slots is free (review of #267). The
/// slot is held until `parse` ends, even after the upload stopped waiting at `EXTRACT_TIMEOUT`, so
/// parses that ran too long cannot pile up and take every core. `None` if the thread ended without
/// a result (the parser already runs under `catch_unwind`).
async fn run_in_slot<T: Send + 'static>(slots: Arc<Semaphore>, parse: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    let slot = slots.acquire_owned().await.ok()?;
    tokio::task::spawn_blocking(move || {
        let _slot = slot;
        parse()
    })
    .await
    .ok()
}

/// Any member of the class, also while it is archived (read-only, not hidden). The contract and the
/// mock send `application/octet-stream`; the client knows the kind from `Material.file_type`.
/// A VIDEO resumes with `Range` and `If-Range`; any other file comes whole (contract #259).
async fn download(
    State(app): State<AppState>,
    MediaUser(user): MediaUser,
    Path(id): Path<String>,
    request: Request,
) -> Result<Response, ApiError> {
    let mut conn = app.db.acquire().await?;
    let file = materials::file_of(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Material not found"))?;
    class_access::require_member(&mut conn, &user, &file.classroom_id).await?;
    drop(conn);
    let path = app.storage.path(&file.file_path).ok_or_else(|| ApiError::not_found("File not found"))?;
    let ranges = if file.file_type == "VIDEO" { Ranges::Resumable(stored_etag(&file.file_path)) } else { Ranges::WholeFile };
    serve_stored(path, "application/octet-stream", request, &id, ranges, app.bandwidth.user(&user.id)).await
}

/// Contract `GET /api/materials/{id}/chunks`: the lesson text for the AI tutor (3.1), for members
/// of the class, also while it is archived. Bearer token only: `?token=` is for the media routes.
async fn chunks(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Vec<MaterialChunk>>, ApiError> {
    let mut conn = app.db.acquire().await?;
    let file = materials::file_of(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Material not found"))?;
    class_access::require_member(&mut conn, &user, &file.classroom_id).await?;
    Ok(Json(material_chunks::for_material(&mut conn, &id).await?))
}

/// Contract `GET /api/materials/{id}/stream` (step 3.2, #8): a lesson video in byte ranges, so the
/// player seeks anywhere without fetching the whole file. Any member of the class, also while it is
/// archived. `Range` gets `206`, none gets the whole video as `200`, and `If-Range` works as on
/// the VIDEO download (contract #259). The video's own type (`video/mp4` or `video/webm`), so a
/// desktop `<video>` tag plays it. Any other kind of material is 404, as in the mock.
async fn stream(
    State(app): State<AppState>,
    MediaUser(user): MediaUser,
    Path(id): Path<String>,
    request: Request,
) -> Result<Response, ApiError> {
    let mut conn = app.db.acquire().await?;
    let file = materials::file_of(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Material not found"))?;
    class_access::require_member(&mut conn, &user, &file.classroom_id).await?;
    if file.file_type != "VIDEO" {
        return Err(ApiError::not_found("Not a video"));
    }
    drop(conn);
    let path = app.storage.path(&file.file_path).ok_or_else(|| ApiError::not_found("File not found"))?;
    let ranges = Ranges::Resumable(stored_etag(&file.file_path));
    serve_stored(path, &file.mime_type, request, &id, ranges, app.bandwidth.user(&user.id)).await
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;
    use std::time::Duration;

    use tokio::sync::Semaphore;

    use super::*;
    use crate::services::document_parser::PARSE_SLOTS;

    #[tokio::test]
    async fn at_most_two_handouts_are_read_at_once() {
        let slots = Arc::new(Semaphore::new(PARSE_SLOTS));
        let (running, most) = (Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)));
        let parses = (0..5).map(|_| {
            let (running, most) = (Arc::clone(&running), Arc::clone(&most));
            run_in_slot(Arc::clone(&slots), move || {
                most.fetch_max(running.fetch_add(1, Ordering::SeqCst) + 1, Ordering::SeqCst);
                std::thread::sleep(Duration::from_millis(50));
                running.fetch_sub(1, Ordering::SeqCst);
            })
        });
        let finished = futures_util::future::join_all(parses).await;
        assert!(finished.iter().all(Option::is_some), "every parse ran");
        assert_eq!(most.load(Ordering::SeqCst), 2);
    }

    /// The upload stops waiting at the time limit, but the parse runs on until it ends. It keeps its
    /// slot for that long, so parses that ran too long cannot pile up and use every core.
    #[tokio::test]
    async fn a_parse_past_the_time_limit_keeps_its_slot_until_it_ends() {
        let slots = Arc::new(Semaphore::new(PARSE_SLOTS));
        let slow = run_in_slot(Arc::clone(&slots), || std::thread::sleep(Duration::from_millis(300)));
        assert!(tokio::time::timeout(Duration::from_millis(20), slow).await.is_err(), "the upload stops waiting");
        assert_eq!(slots.available_permits(), PARSE_SLOTS - 1, "the parse still runs, so its slot stays taken");
        tokio::time::sleep(Duration::from_millis(600)).await;
        assert_eq!(slots.available_permits(), PARSE_SLOTS, "its slot is free once it ends");
    }
}
