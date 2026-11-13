use serde::{Deserialize, Serialize};
use sqlx::FromRow;

use super::classroom::Classroom;

/// 2^53 - 1, the largest integer JavaScript and the desktop app store exactly. The desktop refuses a
/// pulled integer outside `0..=MAX_SAFE_INT` and would stop syncing, so the Hub never stores one.
pub const MAX_SAFE_INT: i64 = 9_007_199_254_740_991;

/// The longest text a client applies from a pull: the desktop refuses a longer string and, with it, the
/// whole page (`desktop/src-tauri/src/sync.rs` `valid`, "string"). Bug #290.
pub const MAX_TEXT_BYTES: usize = 1_000_000;

/// An id a client made (a comment, a private comment, a homework submission) must be one the desktop
/// accepts in a pull: a hyphenated UUID in any letter case, never the nil UUID
/// (`desktop/src-tauri/src/account.rs` `uuid`). Bug #290. Kept as sent, so receipts match the client's row.
pub fn is_client_uuid(id: &str) -> bool {
    uuid::Uuid::parse_str(id).is_ok_and(|v| v.hyphenated().to_string().eq_ignore_ascii_case(id) && !v.is_nil())
}

#[derive(Deserialize)]
pub struct SyncPullInput {
    pub cursor: i64,
    pub hub_id: Option<String>,
    pub sync_epoch: Option<i64>,
}

#[derive(Deserialize, Default)]
pub struct SyncPushInput {
    #[serde(default)]
    pub comments: Vec<CommentPush>,
    #[serde(default)]
    pub private_comments: Vec<PrivateCommentPush>,
    /// Contract `QuizAttemptPush`, read item by item so one bad item is refused alone (step 4.2).
    #[serde(default)]
    pub quiz_attempts: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
pub struct CommentPush {
    pub id: String,
    pub announcement_id: String,
    pub content: String,
    pub created_at: i64,
}

#[derive(Deserialize)]
pub struct PrivateCommentPush {
    pub id: String,
    pub assignment_id: String,
    pub content: String,
    pub created_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct PublicUser {
    pub id: String,
    pub full_name: String,
    pub role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lrn_or_id: Option<String>,
}

#[derive(Serialize, FromRow)]
pub struct Enrollment {
    pub id: String,
    pub classroom_id: String,
    pub student_id: String,
    pub status: String,
    pub joined_at: i64,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct Topic {
    pub id: String,
    pub classroom_id: String,
    pub name: String,
    pub order_index: i64,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct Announcement {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub content: String,
    pub allow_comments: bool,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct Comment {
    pub id: String,
    pub announcement_id: String,
    pub author_id: String,
    pub content: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct PrivateComment {
    pub id: String,
    pub assignment_id: String,
    pub student_id: String,
    pub author_id: String,
    pub content: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct Material {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub file_type: String,
    pub file_size_bytes: i64,
    pub download_url: String,
    pub topic_id: Option<String>,
    pub assignment_id: Option<String>,
    pub announcement_id: Option<String>,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct MaterialChunk {
    pub id: String,
    pub material_id: String,
    pub order_index: i64,
    pub heading: Option<String>,
    pub text: String,
}

#[derive(Serialize, FromRow)]
pub struct Assignment {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub instructions: String,
    pub max_points: i64,
    pub due_date: i64,
    pub allow_late: bool,
    pub topic_id: Option<String>,
    pub updated_at: i64,
}

#[derive(Serialize, FromRow)]
pub struct Submission {
    pub id: String,
    pub assignment_id: String,
    pub student_id: String,
    pub file_type: String,
    /// When the learner pressed Turn in, never later than `received_at` (contract #257).
    pub submitted_at: i64,
    /// When the Hub stored the file.
    pub received_at: i64,
    pub score: Option<i64>,
    pub teacher_feedback: Option<String>,
    pub updated_at: i64,
}

// Sync carries metadata only; question and answer-key fields cannot be serialized here.
#[derive(Serialize, FromRow)]
pub struct QuizMetadata {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub status: String,
    pub time_limit_minutes: i64,
    pub started_at: Option<i64>,
}

#[derive(Serialize, FromRow)]
pub struct GradeReceipt {
    pub attempt_id: String,
    pub quiz_id: String,
    pub student_id: String,
    pub score: Option<i64>,
    pub total_points: i64,
    pub submitted_at: i64,
}

#[derive(Serialize)]
pub struct Tombstone {
    pub entity_table: String,
    pub entity_id: String,
}

#[derive(Serialize, Default)]
pub struct SyncPullResponse {
    pub server_time: i64,
    pub hub_id: String,
    pub sync_epoch: i64,
    pub next_cursor: i64,
    pub has_more: bool,
    pub reset: bool,
    pub users: Vec<PublicUser>,
    pub classrooms: Vec<Classroom>,
    pub enrollments: Vec<Enrollment>,
    pub topics: Vec<Topic>,
    pub announcements: Vec<Announcement>,
    pub comments: Vec<Comment>,
    pub private_comments: Vec<PrivateComment>,
    pub materials: Vec<Material>,
    pub material_chunks: Vec<MaterialChunk>,
    pub assignments: Vec<Assignment>,
    pub submissions: Vec<Submission>,
    pub quizzes: Vec<QuizMetadata>,
    pub quiz_attempts: Vec<GradeReceipt>,
    pub deleted: Vec<Tombstone>,
}

#[derive(Serialize)]
pub struct SyncPushResponse {
    pub acknowledged: bool,
    pub receipts: Vec<SyncReceipt>,
}

#[derive(Serialize)]
pub struct SyncReceipt {
    pub id: String,
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn is_client_uuid_matches_the_desktop() {
        assert!(is_client_uuid("a1000000-0000-4000-8000-000000000001"));
        assert!(is_client_uuid("A1000000-0000-4000-8000-0000000000AB"));
        for refused in [
            "00000000-0000-0000-0000-000000000000",
            "a1000000000040008000000000000001",
            "{a1000000-0000-4000-8000-000000000001}",
            "urn:uuid:a1000000-0000-4000-8000-000000000001",
            "sub-1",
            "",
        ] {
            assert!(!is_client_uuid(refused), "{refused}");
        }
    }
}
