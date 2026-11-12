//! `materials` rows. `file_path` and `mime_type` stay in the database: `Material` cannot carry them.

use sqlx::{FromRow, SqliteConnection};

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::Material;

pub struct NewMaterial<'a> {
    pub id: &'a str,
    pub classroom_id: &'a str,
    pub title: &'a str,
    pub file_type: &'a str,
    pub mime_type: &'a str,
    /// Relative to `storage/` (`storage::StoredFile::relative`).
    pub file_path: &'a str,
    pub file_size_bytes: i64,
    /// The signed-in teacher (owner or co-teacher); never sent to clients (contract #273).
    pub uploaded_by: &'a str,
    pub topic_id: Option<&'a str>,
    pub assignment_id: Option<&'a str>,
    pub announcement_id: Option<&'a str>,
}

/// What a download needs; never serialized.
#[derive(FromRow)]
pub struct MaterialFile {
    pub classroom_id: String,
    pub file_path: String,
    /// `VIDEO` downloads resume with `Range` (contract #259).
    pub file_type: String,
    /// Sniffed at upload (`storage::FileFormat::mime`); the stream sends it so `<video>` can play.
    pub mime_type: String,
}

/// A post a material can be attached to (contract `POST /api/materials`).
#[derive(Clone, Copy)]
pub enum Attachment {
    Topic,
    Assignment,
    Announcement,
}

impl Attachment {
    pub const ALL: [Self; 3] = [Self::Topic, Self::Assignment, Self::Announcement];

    /// The multipart field that names it.
    pub fn field(self) -> &'static str {
        match self {
            Self::Topic => "topic_id",
            Self::Assignment => "assignment_id",
            Self::Announcement => "announcement_id",
        }
    }

    pub fn not_found(self) -> &'static str {
        match self {
            Self::Topic => "Topic not found",
            Self::Assignment => "Assignment not found",
            Self::Announcement => "Announcement not found",
        }
    }

    fn exists_sql(self) -> &'static str {
        match self {
            Self::Topic => "SELECT EXISTS (SELECT 1 FROM topics WHERE id = ? AND classroom_id = ?)",
            // An archived assignment is gone for everyone (PRD FR-3.6).
            Self::Assignment => {
                "SELECT EXISTS (SELECT 1 FROM assignments WHERE id = ? AND classroom_id = ? AND archived_at IS NULL)"
            }
            Self::Announcement => "SELECT EXISTS (SELECT 1 FROM announcements WHERE id = ? AND classroom_id = ?)",
        }
    }
}

/// Whether the topic or post is in this class, so a material never attaches across classes.
pub async fn attachment_in_class(
    conn: &mut SqliteConnection,
    attachment: Attachment,
    id: &str,
    classroom_id: &str,
) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar(attachment.exists_sql()).bind(id).bind(classroom_id).fetch_one(conn).await
}

/// Inserts the row and its class-wide revision; call inside the write transaction.
pub async fn insert(tx: &mut SqliteConnection, new: &NewMaterial<'_>, now_ms: i64) -> Result<Material, sqlx::Error> {
    sqlx::query(
        "INSERT INTO materials (id, classroom_id, title, file_type, mime_type, file_path, file_size_bytes,
                                uploaded_by, topic_id, assignment_id, announcement_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(new.id)
    .bind(new.classroom_id)
    .bind(new.title)
    .bind(new.file_type)
    .bind(new.mime_type)
    .bind(new.file_path)
    .bind(new.file_size_bytes)
    .bind(new.uploaded_by)
    .bind(new.topic_id)
    .bind(new.assignment_id)
    .bind(new.announcement_id)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(new.classroom_id), "materials", new.id, Action::Upsert, now_ms).await?;
    Ok(Material {
        id: new.id.to_owned(),
        classroom_id: new.classroom_id.to_owned(),
        title: new.title.to_owned(),
        file_type: new.file_type.to_owned(),
        file_size_bytes: new.file_size_bytes,
        download_url: format!("/api/materials/{}/download", new.id),
        topic_id: new.topic_id.map(str::to_owned),
        assignment_id: new.assignment_id.map(str::to_owned),
        announcement_id: new.announcement_id.map(str::to_owned),
        updated_at: now_ms,
    })
}

pub async fn file_of(conn: &mut SqliteConnection, id: &str) -> Result<Option<MaterialFile>, sqlx::Error> {
    sqlx::query_as("SELECT classroom_id, file_path, file_type, mime_type FROM materials WHERE id = ?").bind(id).fetch_optional(conn).await
}

pub async fn class_of(conn: &mut SqliteConnection, id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT classroom_id FROM materials WHERE id = ?").bind(id).fetch_optional(conn).await
}

/// Rename or move to a topic (`topic_id: Some(None)` takes it out of its topic). Step 3.4.
pub async fn update(
    tx: &mut SqliteConnection,
    id: &str,
    title: Option<String>,
    topic_id: Option<Option<String>>,
    now_ms: i64,
) -> Result<Material, sqlx::Error> {
    let material: Material = sqlx::query_as(
        "UPDATE materials SET title = COALESCE(?, title), topic_id = CASE WHEN ? THEN ? ELSE topic_id END, updated_at = ?
         WHERE id = ?
         RETURNING id, classroom_id, title, file_type, file_size_bytes, '/api/materials/' || id || '/download' AS download_url,
                   topic_id, assignment_id, announcement_id, updated_at",
    )
    .bind(title)
    .bind(topic_id.is_some())
    .bind(topic_id.flatten())
    .bind(now_ms)
    .bind(id)
    .fetch_one(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(&material.classroom_id), "materials", id, Action::Upsert, now_ms).await?;
    Ok(material)
}

/// Deletes the material; `ON DELETE CASCADE` takes its lesson chunks, so each chunk gets a `DELETE`
/// revision too. Returns the stored file for the caller to remove after commit. Step 3.4.
pub async fn delete(tx: &mut SqliteConnection, id: &str, classroom_id: &str, now_ms: i64) -> Result<String, sqlx::Error> {
    let file_path: String = sqlx::query_scalar("SELECT file_path FROM materials WHERE id = ?").bind(id).fetch_one(&mut *tx).await?;
    let chunks: Vec<String> =
        sqlx::query_scalar("SELECT id FROM material_chunks WHERE material_id = ?").bind(id).fetch_all(&mut *tx).await?;
    for chunk in &chunks {
        record_revision(tx, Scope::class(classroom_id), "material_chunks", chunk, Action::Delete, now_ms).await?;
    }
    record_revision(tx, Scope::class(classroom_id), "materials", id, Action::Delete, now_ms).await?;
    sqlx::query("DELETE FROM materials WHERE id = ?").bind(id).execute(&mut *tx).await?;
    Ok(file_path)
}
