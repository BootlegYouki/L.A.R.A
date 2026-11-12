//! `announcements` rows and their `sync_revisions` entries (step 3.4). Call these inside the write
//! transaction; the route checks who may write first.

use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::{Announcement, Comment};

pub struct AnnouncementChange {
    pub title: Option<String>,
    pub content: Option<String>,
    pub allow_comments: Option<bool>,
}

pub async fn insert(
    tx: &mut SqliteConnection,
    id: &str,
    classroom_id: &str,
    title: &str,
    content: &str,
    allow_comments: bool,
    now_ms: i64,
) -> Result<Announcement, sqlx::Error> {
    sqlx::query(
        "INSERT INTO announcements (id, classroom_id, title, content, allow_comments, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(classroom_id)
    .bind(title)
    .bind(content)
    .bind(allow_comments)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(classroom_id), "announcements", id, Action::Upsert, now_ms).await?;
    Ok(Announcement {
        id: id.to_owned(),
        classroom_id: classroom_id.to_owned(),
        title: title.to_owned(),
        content: content.to_owned(),
        allow_comments,
        created_at: now_ms,
        updated_at: now_ms,
    })
}

pub async fn class_of(conn: &mut SqliteConnection, id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT classroom_id FROM announcements WHERE id = ?").bind(id).fetch_optional(conn).await
}

pub async fn get(conn: &mut SqliteConnection, id: &str) -> Result<Option<Announcement>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, classroom_id, title, content, allow_comments, created_at, updated_at FROM announcements WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(conn)
    .await
}

/// Oldest first, as the mock lists them.
pub async fn comments(conn: &mut SqliteConnection, announcement_id: &str) -> Result<Vec<Comment>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, announcement_id, author_id, content, created_at, updated_at FROM announcement_comments
         WHERE announcement_id = ? ORDER BY created_at, id",
    )
    .bind(announcement_id)
    .fetch_all(conn)
    .await
}

pub async fn insert_comment(
    tx: &mut SqliteConnection,
    id: &str,
    announcement_id: &str,
    classroom_id: &str,
    author_id: &str,
    content: &str,
    now_ms: i64,
) -> Result<Comment, sqlx::Error> {
    sqlx::query(
        "INSERT INTO announcement_comments (id, announcement_id, author_id, content, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)",
    )
    .bind(id)
    .bind(announcement_id)
    .bind(author_id)
    .bind(content)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(classroom_id), "announcement_comments", id, Action::Upsert, now_ms).await?;
    Ok(Comment {
        id: id.to_owned(),
        announcement_id: announcement_id.to_owned(),
        author_id: author_id.to_owned(),
        content: content.to_owned(),
        created_at: now_ms,
        updated_at: now_ms,
    })
}

/// Like the mock, every edit bumps `updated_at` and syncs, even one that changes nothing.
pub async fn update(
    tx: &mut SqliteConnection,
    id: &str,
    change: AnnouncementChange,
    now_ms: i64,
) -> Result<Announcement, sqlx::Error> {
    let announcement: Announcement = sqlx::query_as(
        "UPDATE announcements SET title = COALESCE(?, title), content = COALESCE(?, content),
             allow_comments = COALESCE(?, allow_comments), updated_at = ?
         WHERE id = ?
         RETURNING id, classroom_id, title, content, allow_comments, created_at, updated_at",
    )
    .bind(change.title)
    .bind(change.content)
    .bind(change.allow_comments)
    .bind(now_ms)
    .bind(id)
    .fetch_one(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(&announcement.classroom_id), "announcements", id, Action::Upsert, now_ms).await?;
    Ok(announcement)
}

/// Deletes the announcement. SQLite's `ON DELETE CASCADE` also removes its comments, its attachment
/// materials and their lesson chunks, so each of those gets its own `DELETE` revision here; without
/// them devices would keep the orphans. Returns the attachments' stored files, which the caller
/// removes after commit.
pub async fn delete(
    tx: &mut SqliteConnection,
    id: &str,
    classroom_id: &str,
    now_ms: i64,
) -> Result<Vec<String>, sqlx::Error> {
    let comments: Vec<String> = sqlx::query_scalar("SELECT id FROM announcement_comments WHERE announcement_id = ?")
        .bind(id)
        .fetch_all(&mut *tx)
        .await?;
    let chunks: Vec<String> = sqlx::query_scalar(
        "SELECT id FROM material_chunks WHERE material_id IN (SELECT id FROM materials WHERE announcement_id = ?)",
    )
    .bind(id)
    .fetch_all(&mut *tx)
    .await?;
    let attachments: Vec<(String, String)> = sqlx::query_as("SELECT id, file_path FROM materials WHERE announcement_id = ?")
        .bind(id)
        .fetch_all(&mut *tx)
        .await?;
    let scope = || Scope::class(classroom_id);
    for chunk in &chunks {
        record_revision(tx, scope(), "material_chunks", chunk, Action::Delete, now_ms).await?;
    }
    for (material, _) in &attachments {
        record_revision(tx, scope(), "materials", material, Action::Delete, now_ms).await?;
    }
    for comment in &comments {
        record_revision(tx, scope(), "announcement_comments", comment, Action::Delete, now_ms).await?;
    }
    record_revision(tx, scope(), "announcements", id, Action::Delete, now_ms).await?;
    sqlx::query("DELETE FROM announcements WHERE id = ?").bind(id).execute(&mut *tx).await?;
    Ok(attachments.into_iter().map(|(_, file)| file).collect())
}
