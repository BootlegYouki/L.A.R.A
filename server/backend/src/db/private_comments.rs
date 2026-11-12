//! `private_comments` rows written through REST (step 3.5). Queued comments from devices arrive through
//! `POST /api/sync/push` (`sync_revisions::push_private_comment`, step 2.2).

use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::PrivateComment;

/// The learner's enrollment status in the class (`ACTIVE`, `PENDING`, `REJECTED`, `REMOVED`), if any.
pub async fn enrollment_status(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    student_id: &str,
) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT status FROM enrollments WHERE classroom_id = ? AND student_id = ?")
        .bind(classroom_id)
        .bind(student_id)
        .fetch_optional(conn)
        .await
}

/// One learner's thread on an assignment, oldest first.
pub async fn thread(conn: &mut SqliteConnection, assignment_id: &str, student_id: &str) -> Result<Vec<PrivateComment>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, assignment_id, student_id, author_id, content, created_at, updated_at FROM private_comments
         WHERE assignment_id = ? AND student_id = ? ORDER BY created_at, id",
    )
    .bind(assignment_id)
    .bind(student_id)
    .fetch_all(conn)
    .await
}

/// The revision names the learner, so only they and the class teachers pull the comment.
#[allow(clippy::too_many_arguments)]
pub async fn insert(
    tx: &mut SqliteConnection,
    id: &str,
    assignment_id: &str,
    classroom_id: &str,
    student_id: &str,
    author_id: &str,
    content: &str,
    now_ms: i64,
) -> Result<PrivateComment, sqlx::Error> {
    sqlx::query(
        "INSERT INTO private_comments (id, assignment_id, student_id, author_id, content, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(assignment_id)
    .bind(student_id)
    .bind(author_id)
    .bind(content)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::student(classroom_id, student_id), "private_comments", id, Action::Upsert, now_ms).await?;
    Ok(PrivateComment {
        id: id.to_owned(),
        assignment_id: assignment_id.to_owned(),
        student_id: student_id.to_owned(),
        author_id: author_id.to_owned(),
        content: content.to_owned(),
        created_at: now_ms,
        updated_at: now_ms,
    })
}
