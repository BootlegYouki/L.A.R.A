//! `assignments` rows (step 3.4). Deleting one archives it: `archived_at` is set, clients get a `DELETE`
//! tombstone, and the row with its submissions and grades stays on the Hub (`rules/database-and-sync.md`
//! section 4). Call the writers inside the write transaction.

use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::Assignment;

pub struct NewAssignment<'a> {
    pub id: &'a str,
    pub classroom_id: &'a str,
    pub title: &'a str,
    pub instructions: &'a str,
    pub max_points: i64,
    pub due_date: i64,
    pub allow_late: bool,
    pub topic_id: Option<&'a str>,
}

/// A PATCH: `None` leaves a field as it is; `topic_id: Some(None)` clears the topic.
pub struct AssignmentChange {
    pub title: Option<String>,
    pub instructions: Option<String>,
    pub max_points: Option<i64>,
    pub due_date: Option<i64>,
    pub allow_late: Option<bool>,
    pub topic_id: Option<Option<String>>,
}

pub async fn insert(tx: &mut SqliteConnection, new: &NewAssignment<'_>, now_ms: i64) -> Result<Assignment, sqlx::Error> {
    sqlx::query(
        "INSERT INTO assignments (id, classroom_id, title, instructions, due_date, allow_late, max_points, topic_id,
                                  created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(new.id)
    .bind(new.classroom_id)
    .bind(new.title)
    .bind(new.instructions)
    .bind(new.due_date)
    .bind(new.allow_late)
    .bind(new.max_points)
    .bind(new.topic_id)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(new.classroom_id), "assignments", new.id, Action::Upsert, now_ms).await?;
    Ok(Assignment {
        id: new.id.to_owned(),
        classroom_id: new.classroom_id.to_owned(),
        title: new.title.to_owned(),
        instructions: new.instructions.to_owned(),
        max_points: new.max_points,
        due_date: new.due_date,
        allow_late: new.allow_late,
        topic_id: new.topic_id.map(str::to_owned),
        updated_at: now_ms,
    })
}

/// The class of an assignment that is not archived. An archived one is gone for everyone (PRD
/// FR-3.6), so every route treats it as missing.
pub async fn open_class(conn: &mut SqliteConnection, id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT classroom_id FROM assignments WHERE id = ? AND archived_at IS NULL")
        .bind(id)
        .fetch_optional(conn)
        .await
}

/// Like the mock, every edit bumps `updated_at` and syncs, even one that changes nothing.
pub async fn update(
    tx: &mut SqliteConnection,
    id: &str,
    change: AssignmentChange,
    now_ms: i64,
) -> Result<Assignment, sqlx::Error> {
    let assignment: Assignment = sqlx::query_as(
        "UPDATE assignments SET title = COALESCE(?, title), instructions = COALESCE(?, instructions),
             max_points = COALESCE(?, max_points), due_date = COALESCE(?, due_date),
             allow_late = COALESCE(?, allow_late), topic_id = CASE WHEN ? THEN ? ELSE topic_id END, updated_at = ?
         WHERE id = ?
         RETURNING id, classroom_id, title, instructions, max_points, due_date, allow_late, topic_id, updated_at",
    )
    .bind(change.title)
    .bind(change.instructions)
    .bind(change.max_points)
    .bind(change.due_date)
    .bind(change.allow_late)
    .bind(change.topic_id.is_some())
    .bind(change.topic_id.flatten())
    .bind(now_ms)
    .bind(id)
    .fetch_one(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(&assignment.classroom_id), "assignments", id, Action::Upsert, now_ms).await?;
    Ok(assignment)
}

pub async fn archive(tx: &mut SqliteConnection, id: &str, classroom_id: &str, now_ms: i64) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE assignments SET archived_at = ?, updated_at = ? WHERE id = ?")
        .bind(now_ms)
        .bind(now_ms)
        .bind(id)
        .execute(&mut *tx)
        .await?;
    record_revision(tx, Scope::class(classroom_id), "assignments", id, Action::Delete, now_ms).await?;
    Ok(())
}
