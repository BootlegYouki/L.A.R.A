//! `assignment_submissions` rows: one per (assignment, learner). `file_path` stays in the
//! database: `Submission` cannot carry it.

use sqlx::{FromRow, SqliteConnection};

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::Submission;

#[derive(Clone, FromRow)]
pub struct StoredSubmission {
    pub id: String,
    pub assignment_id: String,
    pub student_id: String,
    /// Relative to `storage/` (`storage::StoredFile::relative`).
    pub file_path: String,
    pub file_type: String,
    pub submitted_at: i64,
    pub score: Option<i64>,
    pub teacher_feedback: Option<String>,
    pub updated_at: i64,
}

impl StoredSubmission {
    pub fn public(&self) -> Submission {
        Submission {
            id: self.id.clone(),
            assignment_id: self.assignment_id.clone(),
            student_id: self.student_id.clone(),
            file_type: self.file_type.clone(),
            submitted_at: self.submitted_at,
            score: self.score,
            teacher_feedback: self.teacher_feedback.clone(),
            updated_at: self.updated_at,
        }
    }
}

/// What the file route needs; never serialized.
#[derive(FromRow)]
pub struct SubmissionFile {
    pub student_id: String,
    pub classroom_id: String,
    pub file_path: String,
}

/// The class of an assignment learners can still hand work in to (archived counts as missing).
pub async fn assignment_class(conn: &mut SqliteConnection, assignment_id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT classroom_id FROM assignments WHERE id = ? AND archived_at IS NULL")
        .bind(assignment_id)
        .fetch_optional(conn)
        .await
}

pub async fn for_learner(
    conn: &mut SqliteConnection,
    assignment_id: &str,
    student_id: &str,
) -> Result<Option<StoredSubmission>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at
         FROM assignment_submissions WHERE assignment_id = ? AND student_id = ?",
    )
    .bind(assignment_id)
    .bind(student_id)
    .fetch_optional(conn)
    .await
}

pub async fn by_id(conn: &mut SqliteConnection, id: &str) -> Result<Option<StoredSubmission>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at
         FROM assignment_submissions WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(conn)
    .await
}

/// Inserts the row, or replaces the file of the existing one (same id), keeping its score and
/// feedback; records a revision only that learner and the class's teachers pull.
pub async fn save(tx: &mut SqliteConnection, row: &StoredSubmission, classroom_id: &str) -> Result<Submission, sqlx::Error> {
    sqlx::query(
        "INSERT INTO assignment_submissions
             (id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET file_path = excluded.file_path, file_type = excluded.file_type,
             submitted_at = excluded.submitted_at, updated_at = excluded.updated_at",
    )
    .bind(&row.id)
    .bind(&row.assignment_id)
    .bind(&row.student_id)
    .bind(&row.file_path)
    .bind(&row.file_type)
    .bind(row.submitted_at)
    .bind(row.score)
    .bind(&row.teacher_feedback)
    .bind(row.updated_at)
    .execute(&mut *tx)
    .await?;
    record_revision(
        tx,
        Scope::student(classroom_id, &row.student_id),
        "assignment_submissions",
        &row.id,
        Action::Upsert,
        row.updated_at,
    )
    .await?;
    Ok(row.public())
}

/// Archived assignments included: their submissions and grades are kept and stay viewable.
pub async fn file_of(conn: &mut SqliteConnection, id: &str) -> Result<Option<SubmissionFile>, sqlx::Error> {
    sqlx::query_as(
        "SELECT s.student_id, a.classroom_id, s.file_path
         FROM assignment_submissions s JOIN assignments a ON a.id = s.assignment_id WHERE s.id = ?",
    )
    .bind(id)
    .fetch_optional(conn)
    .await
}
