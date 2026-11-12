//! `assignment_submissions` rows: one per (assignment, learner). `file_path` stays in the
//! database: `Submission` cannot carry it.

use sqlx::{FromRow, SqliteConnection};

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::Submission;

/// The stored columns, for `concat!` (query text stays `'static`).
macro_rules! columns {
    () => {
        "id, assignment_id, student_id, file_path, file_type, submitted_at, created_at, received_at, score, teacher_feedback, updated_at"
    };
}

#[derive(Clone, FromRow)]
pub struct StoredSubmission {
    pub id: String,
    pub assignment_id: String,
    pub student_id: String,
    /// Relative to `storage/` (`storage::StoredFile::relative`).
    pub file_path: String,
    pub file_type: String,
    pub submitted_at: i64,
    /// When the learner first handed in work for this assignment; a resubmission under a new id
    /// keeps it, as the mock does (contract #273). Stays on the Hub.
    pub created_at: i64,
    pub received_at: i64,
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
            received_at: self.received_at,
            score: self.score,
            teacher_feedback: self.teacher_feedback.clone(),
            updated_at: self.updated_at,
        }
    }

    /// Graded work is final: only an identical retry is answered (contract #257).
    pub fn is_graded(&self) -> bool {
        self.score.is_some()
    }
}

/// What the file route needs; never serialized.
#[derive(FromRow)]
pub struct SubmissionFile {
    pub student_id: String,
    pub classroom_id: String,
    pub file_path: String,
}

/// The assignment as homework submit needs it.
#[derive(FromRow)]
pub struct OpenAssignment {
    pub classroom_id: String,
    pub due_date: i64,
    pub allow_late: bool,
}

impl OpenAssignment {
    /// Work turned in after the due date, when the assignment does not take late work.
    pub fn refuses(&self, submitted_at: i64) -> bool {
        !self.allow_late && submitted_at > self.due_date
    }
}

/// Like `assignments::open_class`, with the deadline.
pub async fn open_assignment(conn: &mut SqliteConnection, assignment_id: &str) -> Result<Option<OpenAssignment>, sqlx::Error> {
    sqlx::query_as("SELECT classroom_id, due_date, allow_late FROM assignments WHERE id = ? AND archived_at IS NULL")
        .bind(assignment_id)
        .fetch_optional(conn)
        .await
}

pub async fn for_learner(
    conn: &mut SqliteConnection,
    assignment_id: &str,
    student_id: &str,
) -> Result<Option<StoredSubmission>, sqlx::Error> {
    sqlx::query_as(concat!("SELECT ", columns!(), " FROM assignment_submissions WHERE assignment_id = ? AND student_id = ?"))
    .bind(assignment_id)
    .bind(student_id)
    .fetch_optional(conn)
    .await
}

pub async fn by_id(conn: &mut SqliteConnection, id: &str) -> Result<Option<StoredSubmission>, sqlx::Error> {
    sqlx::query_as(concat!("SELECT ", columns!(), " FROM assignment_submissions WHERE id = ?"))
        .bind(id)
        .fetch_optional(conn)
        .await
}

/// The homework handed in for an assignment, oldest hand-in first (step 3.4).
pub async fn for_assignment(conn: &mut SqliteConnection, assignment_id: &str) -> Result<Vec<Submission>, sqlx::Error> {
    let rows: Vec<StoredSubmission> =
        sqlx::query_as(concat!("SELECT ", columns!(), " FROM assignment_submissions WHERE assignment_id = ? ORDER BY submitted_at, id"))
            .bind(assignment_id)
            .fetch_all(conn)
            .await?;
    Ok(rows.iter().map(StoredSubmission::public).collect())
}

/// A submission a teacher may grade, with its class. `None` when there is no such id (a resubmission
/// under a new id replaced it) or its assignment is archived.
pub async fn grading_target(conn: &mut SqliteConnection, id: &str) -> Result<Option<(StoredSubmission, String)>, sqlx::Error> {
    let Some(row) = by_id(&mut *conn, id).await? else { return Ok(None) };
    let classroom_id = super::assignments::open_class(conn, &row.assignment_id).await?;
    Ok(classroom_id.map(|classroom_id| (row, classroom_id)))
}

/// Sets what the teacher sent and keeps the rest; synced to that learner and the class's teachers.
pub async fn grade(
    tx: &mut SqliteConnection,
    row: &StoredSubmission,
    classroom_id: &str,
    score: Option<i64>,
    teacher_feedback: Option<String>,
    now_ms: i64,
) -> Result<Submission, sqlx::Error> {
    let graded = StoredSubmission {
        score: score.or(row.score),
        teacher_feedback: teacher_feedback.or_else(|| row.teacher_feedback.clone()),
        updated_at: now_ms,
        ..row.clone()
    };
    sqlx::query("UPDATE assignment_submissions SET score = ?, teacher_feedback = ?, updated_at = ? WHERE id = ?")
        .bind(graded.score)
        .bind(&graded.teacher_feedback)
        .bind(now_ms)
        .bind(&graded.id)
        .execute(&mut *tx)
        .await?;
    record_revision(
        tx,
        Scope::student(classroom_id, &graded.student_id),
        "assignment_submissions",
        &graded.id,
        Action::Upsert,
        now_ms,
    )
    .await?;
    Ok(graded.public())
}

/// Inserts the row, or replaces the file of the existing one (same id), keeping its score and
/// feedback; records a revision only that learner and the class's teachers pull.
pub async fn save(tx: &mut SqliteConnection, row: &StoredSubmission, classroom_id: &str) -> Result<Submission, sqlx::Error> {
    sqlx::query(concat!(
        "INSERT INTO assignment_submissions (",
        columns!(),
        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET file_path = excluded.file_path, file_type = excluded.file_type,
             submitted_at = excluded.submitted_at, received_at = excluded.received_at, updated_at = excluded.updated_at"
    ))
    .bind(&row.id)
    .bind(&row.assignment_id)
    .bind(&row.student_id)
    .bind(&row.file_path)
    .bind(&row.file_type)
    .bind(row.submitted_at)
    .bind(row.created_at)
    .bind(row.received_at)
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

/// Removes a row whose work was replaced under a new id, with a tombstone for the same learner
/// and teachers, so their devices drop the old id (contract #257). Its file is the caller's.
pub async fn delete_replaced(
    tx: &mut SqliteConnection,
    row: &StoredSubmission,
    classroom_id: &str,
    now: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM assignment_submissions WHERE id = ?").bind(&row.id).execute(&mut *tx).await?;
    record_revision(tx, Scope::student(classroom_id, &row.student_id), "assignment_submissions", &row.id, Action::Delete, now)
        .await?;
    Ok(())
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
