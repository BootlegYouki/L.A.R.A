//! `topics` rows (step 3.5, PRD FR-3.7). Call the writers inside the write transaction.

use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::Topic;

/// New topics go last: one past the class's highest `order_index`, read under the same write lock, so
/// two topics created at once still get different places.
pub async fn insert(tx: &mut SqliteConnection, id: &str, classroom_id: &str, name: &str, now_ms: i64) -> Result<Topic, sqlx::Error> {
    let topic: Topic = sqlx::query_as(
        "INSERT INTO topics (id, classroom_id, name, order_index, created_at, updated_at)
         VALUES (?, ?, ?, (SELECT 1 + COALESCE(MAX(order_index), 0) FROM topics WHERE classroom_id = ?), ?, ?)
         RETURNING id, classroom_id, name, order_index, updated_at",
    )
    .bind(id)
    .bind(classroom_id)
    .bind(name)
    .bind(classroom_id)
    .bind(now_ms)
    .bind(now_ms)
    .fetch_one(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(classroom_id), "topics", id, Action::Upsert, now_ms).await?;
    Ok(topic)
}

pub async fn class_of(conn: &mut SqliteConnection, id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT classroom_id FROM topics WHERE id = ?").bind(id).fetch_optional(conn).await
}

/// Rename or move. Like the mock, the other topics keep their `order_index`.
pub async fn update(
    tx: &mut SqliteConnection,
    id: &str,
    name: Option<String>,
    order_index: Option<i64>,
    now_ms: i64,
) -> Result<Topic, sqlx::Error> {
    let topic: Topic = sqlx::query_as(
        "UPDATE topics SET name = COALESCE(?, name), order_index = COALESCE(?, order_index), updated_at = ?
         WHERE id = ? RETURNING id, classroom_id, name, order_index, updated_at",
    )
    .bind(name)
    .bind(order_index)
    .bind(now_ms)
    .bind(id)
    .fetch_one(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(&topic.classroom_id), "topics", id, Action::Upsert, now_ms).await?;
    Ok(topic)
}

/// Deletes the topic and keeps its work (PRD FR-3.7). Materials, open assignments and quizzes lose the
/// topic with an `UPSERT` revision each, so devices unfile them; quizzes need the explicit UPDATE because
/// `quizzes.topic_id` has no foreign key (#177). Archived assignments get no revision: their latest one
/// must stay the `DELETE` tombstone, or a device that missed the archive would never drop them
/// (`load_entity` skips archived assignments). Their `topic_id` is cleared by `ON DELETE SET NULL`.
pub async fn delete(tx: &mut SqliteConnection, id: &str, classroom_id: &str, now_ms: i64) -> Result<(), sqlx::Error> {
    const UNFILE: [(&str, &str); 3] = [
        ("materials", "UPDATE materials SET topic_id = NULL, updated_at = ? WHERE topic_id = ? RETURNING id"),
        (
            "assignments",
            "UPDATE assignments SET topic_id = NULL, updated_at = ? WHERE topic_id = ? AND archived_at IS NULL RETURNING id",
        ),
        ("quizzes", "UPDATE quizzes SET topic_id = NULL, updated_at = ? WHERE topic_id = ? RETURNING id"),
    ];
    for (table, sql) in UNFILE {
        let ids: Vec<String> = sqlx::query_scalar(sqlx::AssertSqlSafe(sql)).bind(now_ms).bind(id).fetch_all(&mut *tx).await?;
        for row in &ids {
            record_revision(tx, Scope::class(classroom_id), table, row, Action::Upsert, now_ms).await?;
        }
    }
    sqlx::query("DELETE FROM topics WHERE id = ?").bind(id).execute(&mut *tx).await?;
    record_revision(tx, Scope::class(classroom_id), "topics", id, Action::Delete, now_ms).await?;
    Ok(())
}
