//! Assignments (contract `/api/assignments`, `/api/submissions`). Homework submit and the
//! submission file are step 3.3; create, edit, archive, the submission list and grading are 3.4.

use axum::extract::multipart::MultipartRejection;
use axum::extract::{DefaultBodyLimit, Multipart, Path, Request, State};
use axum::http::HeaderMap;
use axum::response::Response;
use axum::routing::{get, patch, post};
use axum::{Json, Router};

use super::files::serve_stored;
use super::not_implemented_teacher as teacher;
use super::upload::{read_form, storage_error, UPLOAD_BODY_LIMIT};
use crate::auth::{require_role, AuthUser, MediaUser, Role};
use crate::db::begin_immediate;
use crate::db::submissions::{self, StoredSubmission};
use crate::error::ApiError;
use crate::models::sync::Submission;
use crate::services::class_access;
use crate::storage::{format_of, StorageKind};
use crate::AppState;

/// `/private-comments` is not here: it gets its own `routes/private_comments.rs` in step 3.5.
pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/assignments", post(teacher))
        .route("/api/assignments/{id}", patch(teacher).delete(teacher))
        .route("/api/assignments/{id}/submit", post(submit).layer(DefaultBodyLimit::max(UPLOAD_BODY_LIMIT)))
        .route("/api/assignments/{id}/submissions", get(teacher))
        .route("/api/submissions/{id}", patch(teacher))
        .route("/api/submissions/{id}/file", get(submission_file))
}

fn assignment_not_found() -> ApiError {
    ApiError::not_found("Assignment not found")
}

/// One submission per (assignment, learner). The client-generated `submission_id` makes an offline
/// retry safe; a resubmission keeps the row's id, score and feedback and replaces only the file.
/// The new file is in place before the transaction and the old one is deleted only after it
/// commits, so a failed resubmission never loses the work already handed in.
async fn submit(
    State(app): State<AppState>,
    user: AuthUser,
    Path(assignment_id): Path<String>,
    headers: HeaderMap,
    multipart: Result<Multipart, MultipartRejection>,
) -> Result<Json<Submission>, ApiError> {
    require_role(&user, Role::Student)?;
    // Before the body: someone who may not submit is refused without uploading the photo.
    {
        let mut conn = app.db.acquire().await?;
        let classroom_id =
            submissions::assignment_class(&mut conn, &assignment_id).await?.ok_or_else(assignment_not_found)?;
        class_access::require_member(&mut conn, &user, &classroom_id).await?;
    }
    let mut form = read_form(&app.storage, &headers, multipart, &["submission_id"]).await?;
    let Some(submission_id) = form.field("submission_id").map(str::to_owned) else {
        return Err(ApiError::bad_request("Missing: submission_id"));
    };
    let file = form.take_file()?;
    let Some(file_type) = file.format.and_then(|f| f.submission_type()) else {
        return Err(ApiError::bad_request("File must be a photo (JPEG or PNG), PDF or Word document"));
    };
    let stored = app.storage.commit(file, StorageKind::Submission).await.map_err(storage_error)?;

    let now = app.clock.now_ms();
    let mut tx = begin_immediate(&app.db).await?;
    // Again under the write lock: the class may have changed while the file was arriving.
    let classroom_id = submissions::assignment_class(&mut tx, &assignment_id).await?.ok_or_else(assignment_not_found)?;
    class_access::require_member(&mut tx, &user, &classroom_id).await?;
    let current = submissions::for_learner(&mut tx, &assignment_id, &user.id).await?;
    if class_access::is_archived(&mut tx, &classroom_id).await? {
        // Read-only, but a device that lost the answer to work saved before the archive is told it
        // is safe (the rule #235 set for sync push). The new copy is dropped.
        return match current {
            Some(row) if row.id == submission_id => Ok(Json(row.public())),
            _ => Err(ApiError::forbidden("This class is archived")),
        };
    }
    let (row, replaced) = match current {
        Some(row) => {
            let replaced = row.file_path.clone();
            let row = StoredSubmission {
                file_path: stored.relative.clone(),
                file_type: file_type.to_owned(),
                submitted_at: now,
                updated_at: now,
                ..row
            };
            (row, Some(replaced))
        }
        None => {
            // An id already used for other work is never overwritten (the mock would replace it).
            if submissions::by_id(&mut tx, &submission_id).await?.is_some() {
                return Err(ApiError::forbidden("This submission id belongs to other work"));
            }
            let row = StoredSubmission {
                id: submission_id,
                assignment_id,
                student_id: user.id.clone(),
                file_path: stored.relative.clone(),
                file_type: file_type.to_owned(),
                submitted_at: now,
                score: None,
                teacher_feedback: None,
                updated_at: now,
            };
            (row, None)
        }
    };
    let submission = submissions::save(&mut tx, &row, &classroom_id).await?;
    tx.commit().await?;
    stored.keep();
    if let Some(old) = replaced {
        app.storage.remove(&old).await;
    }
    Ok(Json(submission))
}

/// The owning learner and the class's teachers (PRD FR-3.5). Sent with its real type (the mock
/// sends `image/jpeg`) so a teacher's image view can show the photo.
async fn submission_file(
    State(app): State<AppState>,
    MediaUser(user): MediaUser,
    Path(id): Path<String>,
    request: Request,
) -> Result<Response, ApiError> {
    let mut conn = app.db.acquire().await?;
    let file = submissions::file_of(&mut conn, &id).await?.ok_or_else(|| ApiError::not_found("Submission not found"))?;
    if user.role == Role::Student && file.student_id != user.id {
        return Err(ApiError::forbidden("Not your submission"));
    }
    class_access::require_member(&mut conn, &user, &file.classroom_id).await?;
    drop(conn);
    let missing = || ApiError::not_found("File not found");
    let format = format_of(&file.file_path).ok_or_else(missing)?;
    let path = app.storage.path(&file.file_path).ok_or_else(missing)?;
    serve_stored(path, format.mime(), request, &id).await
}
