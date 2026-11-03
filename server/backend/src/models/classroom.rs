use serde::Serialize;
use sqlx::FromRow;

#[derive(Serialize, FromRow)]
pub struct Classroom {
    pub id: String,
    pub name: String,
    pub section: String,
    pub class_code: String,
    pub teacher_id: String,
    pub archived_at: Option<i64>,
    #[sqlx(skip)]
    pub co_teacher_ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enrollment_status: Option<String>,
}

#[derive(Serialize, FromRow)]
pub struct RosterEntry {
    pub enrollment_id: String,
    pub student_id: String,
    pub full_name: String,
    pub lrn: String,
    pub status: String,
    pub joined_at: i64,
}
