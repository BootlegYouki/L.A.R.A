//! Lesson files (contract `/api/materials`). Upload and download are step 3.3, with contract #259's
//! resumable VIDEO downloads; rename, delete, chunks (3.1) and the video stream itself (3.2) are
//! still stubs. `/stream` already refuses a material that is not a video (#259).

use axum::extract::multipart::MultipartRejection;
use axum::extract::{DefaultBodyLimit, Multipart, Path, Request, State};
use axum::http::HeaderMap;
use axum::response::Response;
use axum::routing::{get, patch, post};
use axum::{Json, Router};

use super::files::{serve_stored, stored_etag, Ranges};
use super::upload::{read_form, storage_error, UPLOAD_BODY_LIMIT};
use super::not_implemented_teacher as teacher;
use crate::auth::{require_role, AuthUser, MediaUser, Role};
use crate::db::begin_immediate;
use crate::db::material_chunks;
use crate::db::materials::{self, Attachment, NewMaterial};
use crate::error::ApiError;
use crate::models::sync::{Material, MaterialChunk};
use crate::services::class_access;
use crate::storage::StorageKind;
use crate::AppState;

const FILE_TYPES: [&str; 3] = ["VIDEO", "DOCUMENT", "WORKSHEET"];

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/materials", post(upload).layer(DefaultBodyLimit::max(UPLOAD_BODY_LIMIT)))
        .route("/api/materials/{id}", patch(teacher).delete(teacher))
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
    let size = i64::try_from(file.size).map_err(|_| ApiError::file_too_large())?;
    let stored = app.storage.commit(file, StorageKind::Material).await.map_err(storage_error)?;

    let classroom_id = form.fields["classroom_id"].as_str();
    let id = uuid::Uuid::new_v4().to_string();
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
        topic_id: form.field("topic_id"),
        assignment_id: form.field("assignment_id"),
        announcement_id: form.field("announcement_id"),
    };
    let material = materials::insert(&mut tx, &new, now).await?;
    tx.commit().await?;
    stored.keep();
    Ok(Json(material))
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
    serve_stored(path, "application/octet-stream", request, &id, ranges).await
}

/// Contract `GET /api/materials/{id}/chunks`: the lesson text for the AI tutor (3.1), for members
/// of the class, also while it is archived. Bearer token only: `?token=` is for the media routes.
async fn chunks(State(app): State<AppState>, user: AuthUser, Path(id): Path<String>) -> Result<Json<Vec<MaterialChunk>>, ApiError> {
    let mut conn = app.db.acquire().await?;
    let file = materials::file_of(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Material not found"))?;
    class_access::require_member(&mut conn, &user, &file.classroom_id).await?;
    Ok(Json(material_chunks::for_material(&mut conn, &id).await?))
}

/// Contract #259: `/stream` is for VIDEO materials, so a member asking for any other kind gets 404,
/// as in the mock. Streaming the video, with the 2.0 MB/s cap, is step 3.2 (#8).
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
    Err(ApiError::not_implemented(request.method(), request.uri().path()))
}
