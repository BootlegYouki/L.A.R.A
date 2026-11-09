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
                                topic_id, assignment_id, announcement_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
    )
    .bind(new.id)
    .bind(new.classroom_id)
    .bind(new.title)
    .bind(new.file_type)
    .bind(new.mime_type)
    .bind(new.file_path)
    .bind(new.file_size_bytes)
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
    sqlx::query_as("SELECT classroom_id, file_path, file_type FROM materials WHERE id = ?").bind(id).fetch_optional(conn).await
}
