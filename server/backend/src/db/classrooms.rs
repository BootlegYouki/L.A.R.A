use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::auth::AuthUser;
use crate::error::ApiError;
use crate::models::classroom::{Classroom, RosterEntry};

const CLASS_CODE_ALPHABET: &[u8; 32] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

pub fn new_class_code() -> Result<String, ApiError> {
    let mut bytes = [0u8; 6];
    getrandom::fill(&mut bytes).map_err(|_| ApiError::internal("Class Code generation failed"))?;
    Ok(bytes
        .iter()
        .map(|b| CLASS_CODE_ALPHABET[usize::from(b % 32)] as char)
        .collect())
}

/// A collision returns None so creation can retry without overwriting the existing class.
pub async fn insert(
    conn: &mut SqliteConnection,
    teacher_id: &str,
    name: &str,
    section: &str,
    code: &str,
    now: i64,
) -> Result<Option<Classroom>, sqlx::Error> {
    let id = uuid::Uuid::new_v4().to_string();
    let inserted = sqlx::query(
        "INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(class_code) DO NOTHING",
    )
    .bind(&id)
    .bind(name)
    .bind(section)
    .bind(code)
    .bind(teacher_id)
    .bind(now)
    .bind(now)
    .execute(&mut *conn)
    .await?;
    if inserted.rows_affected() == 0 {
        return Ok(None);
    }
    record_revision(conn, Scope::class(&id), "classrooms", &id, Action::Upsert, now).await?;
    Ok(Some(Classroom {
        id,
        name: name.to_owned(),
        section: section.to_owned(),
        class_code: code.to_owned(),
        teacher_id: teacher_id.to_owned(),
        archived_at: None,
        co_teacher_ids: vec![],
        enrollment_status: None,
    }))
}

pub async fn list(conn: &mut SqliteConnection, user: &AuthUser) -> Result<Vec<Classroom>, sqlx::Error> {
    let mut rooms = sqlx::query_as::<_, Classroom>(
        "SELECT c.id, c.name, c.section, c.class_code, c.teacher_id, c.archived_at, e.status AS enrollment_status
         FROM classrooms c LEFT JOIN enrollments e ON e.classroom_id = c.id AND e.student_id = ?
         WHERE (? = 'TEACHER' AND (c.teacher_id = ? OR EXISTS (
             SELECT 1 FROM classroom_teachers ct WHERE ct.classroom_id = c.id AND ct.teacher_id = ?)))
         OR (? = 'STUDENT' AND e.status IN ('PENDING', 'ACTIVE')) ORDER BY c.created_at, c.id",
    )
    .bind(&user.id)
    .bind(user.role.as_str())
    .bind(&user.id)
    .bind(&user.id)
    .bind(user.role.as_str())
    .fetch_all(&mut *conn)
    .await?;
    for room in &mut rooms {
        room.co_teacher_ids =
            sqlx::query_scalar("SELECT teacher_id FROM classroom_teachers WHERE classroom_id = ? ORDER BY teacher_id")
                .bind(&room.id)
                .fetch_all(&mut *conn)
                .await?;
    }
    Ok(rooms)
}

pub async fn roster(conn: &mut SqliteConnection, classroom_id: &str) -> Result<Vec<RosterEntry>, sqlx::Error> {
    sqlx::query_as(
        "SELECT e.id AS enrollment_id, e.student_id, u.full_name, u.lrn_or_id AS lrn, e.status, e.joined_at
         FROM enrollments e JOIN users u ON u.id = e.student_id
         WHERE e.classroom_id = ? ORDER BY e.joined_at, e.id",
    )
    .bind(classroom_id)
    .fetch_all(conn)
    .await
}

pub struct JoinOutcome {
    pub classroom_id: String,
    pub status: String,
    pub learner: Option<(String, String)>,
}

/// Learner details are needed only for a new teacher notification, never for the join receipt.
pub async fn join(
    conn: &mut SqliteConnection,
    student_id: &str,
    code: &str,
    now: i64,
) -> Result<JoinOutcome, ApiError> {
    let classroom: Option<(String, Option<i64>)> =
        sqlx::query_as("SELECT id, archived_at FROM classrooms WHERE class_code = ?")
            .bind(code)
            .fetch_optional(&mut *conn)
            .await?;
    let (classroom_id, archived_at) = classroom.ok_or_else(ApiError::class_code_invalid)?;
    if archived_at.is_some() {
        return Err(ApiError::forbidden("This class is archived"));
    }
    let existing: Option<(String, String)> =
        sqlx::query_as("SELECT id, status FROM enrollments WHERE classroom_id = ? AND student_id = ?")
            .bind(&classroom_id)
            .bind(student_id)
            .fetch_optional(&mut *conn)
            .await?;
    let id = match existing {
        Some((_, status)) if status == "PENDING" || status == "ACTIVE" => {
            return Ok(JoinOutcome {
                classroom_id,
                status,
                learner: None,
            })
        }
        Some((id, _)) => {
            sqlx::query("UPDATE enrollments SET status = 'PENDING', updated_at = ? WHERE id = ?")
                .bind(now)
                .bind(&id)
                .execute(&mut *conn)
                .await?;
            id
        }
        None => {
            let id = uuid::Uuid::new_v4().to_string();
            sqlx::query(
                "INSERT INTO enrollments (id, classroom_id, student_id, status, joined_at, updated_at)
                 VALUES (?, ?, ?, 'PENDING', ?, ?)",
            )
            .bind(&id)
            .bind(&classroom_id)
            .bind(student_id)
            .bind(now)
            .bind(now)
            .execute(&mut *conn)
            .await?;
            id
        }
    };
    record_revision(
        conn,
        Scope::student(&classroom_id, student_id),
        "enrollments",
        &id,
        Action::Upsert,
        now,
    )
    .await?;
    let (full_name, lrn): (String, String) = sqlx::query_as("SELECT full_name, lrn_or_id FROM users WHERE id = ?")
        .bind(student_id)
        .fetch_one(&mut *conn)
        .await?;
    Ok(JoinOutcome {
        classroom_id,
        status: "PENDING".into(),
        learner: Some((full_name, lrn)),
    })
}

pub async fn decide(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    student_id: &str,
    target: &str,
    now: i64,
) -> Result<bool, ApiError> {
    let existing: Option<(String, String)> =
        sqlx::query_as("SELECT id, status FROM enrollments WHERE classroom_id = ? AND student_id = ?")
            .bind(classroom_id)
            .bind(student_id)
            .fetch_optional(&mut *conn)
            .await?;
    let (id, current) = existing.ok_or_else(|| ApiError::not_found("No pending request"))?;
    if current == target {
        return Ok(false);
    }
    if current != "PENDING" {
        return Err(ApiError::not_found("No pending request"));
    }
    sqlx::query("UPDATE enrollments SET status = ?, updated_at = ? WHERE id = ?")
        .bind(target)
        .bind(now)
        .bind(&id)
        .execute(&mut *conn)
        .await?;
    record_revision(
        conn,
        Scope::student(classroom_id, student_id),
        "enrollments",
        &id,
        Action::Upsert,
        now,
    )
    .await?;
    Ok(true)
}
