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

/// The class as the teacher routes answer it. The caller has already checked it exists.
pub async fn get(conn: &mut SqliteConnection, classroom_id: &str) -> Result<Classroom, sqlx::Error> {
    let mut room = sqlx::query_as::<_, Classroom>(
        "SELECT id, name, section, class_code, teacher_id, archived_at, NULL AS enrollment_status
         FROM classrooms WHERE id = ?",
    )
    .bind(classroom_id)
    .fetch_one(&mut *conn)
    .await?;
    room.co_teacher_ids =
        sqlx::query_scalar("SELECT teacher_id FROM classroom_teachers WHERE classroom_id = ? ORDER BY teacher_id")
            .bind(classroom_id)
            .fetch_all(conn)
            .await?;
    Ok(room)
}

pub struct ClassroomChange {
    pub name: Option<String>,
    pub section: Option<String>,
    pub archived: Option<bool>,
}

/// A class that stays archived can't be renamed. Clearing the archive is the one way back, and a
/// change to nothing writes no revision, so a retry keeps the first `archived_at`.
pub async fn update(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    change: ClassroomChange,
    now: i64,
) -> Result<Classroom, ApiError> {
    let mut room = get(conn, classroom_id).await?;
    let name = change.name.unwrap_or_else(|| room.name.clone());
    let section = change.section.unwrap_or_else(|| room.section.clone());
    let archived_at = match change.archived {
        Some(true) => room.archived_at.or(Some(now)),
        Some(false) => None,
        None => room.archived_at,
    };
    let renamed = name != room.name || section != room.section;
    if renamed && room.archived_at.is_some() && archived_at.is_some() {
        return Err(ApiError::forbidden("This class is archived"));
    }
    if !renamed && archived_at == room.archived_at {
        return Ok(room);
    }
    sqlx::query("UPDATE classrooms SET name = ?, section = ?, archived_at = ?, updated_at = ? WHERE id = ?")
        .bind(&name)
        .bind(&section)
        .bind(archived_at)
        .bind(now)
        .bind(classroom_id)
        .execute(&mut *conn)
        .await?;
    record_revision(conn, Scope::class(classroom_id), "classrooms", classroom_id, Action::Upsert, now).await?;
    room.name = name;
    room.section = section;
    room.archived_at = archived_at;
    Ok(room)
}

/// Two revisions, class first: learners and teachers need the new `co_teacher_ids`, and the copy
/// addressed to the teacher tells sync the class just appeared for them or just left them.
async fn record_teacher_change(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    teacher_id: &str,
    now: i64,
) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE classrooms SET updated_at = ? WHERE id = ?")
        .bind(now)
        .bind(classroom_id)
        .execute(&mut *conn)
        .await?;
    record_revision(conn, Scope::class(classroom_id), "classrooms", classroom_id, Action::Upsert, now).await?;
    record_revision(
        conn,
        Scope::student(classroom_id, teacher_id),
        "classrooms",
        classroom_id,
        Action::Upsert,
        now,
    )
    .await?;
    Ok(())
}

pub async fn add_co_teacher(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    lrn_or_id: &str,
    now: i64,
) -> Result<Classroom, ApiError> {
    let teacher_id: Option<String> =
        sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = ? AND role = 'TEACHER' AND is_active = 1")
            .bind(lrn_or_id)
            .fetch_optional(&mut *conn)
            .await?;
    let teacher_id = teacher_id.ok_or_else(|| ApiError::not_found("No teacher with that ID"))?;
    let added = sqlx::query(
        "INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at, created_at, updated_at)
         SELECT id, ?, ?, ?, ? FROM classrooms WHERE id = ? AND teacher_id <> ?
         ON CONFLICT(classroom_id, teacher_id) DO NOTHING",
    )
    .bind(&teacher_id)
    .bind(now)
    .bind(now)
    .bind(now)
    .bind(classroom_id)
    .bind(&teacher_id)
    .execute(&mut *conn)
    .await?;
    if added.rows_affected() > 0 {
        record_teacher_change(conn, classroom_id, &teacher_id, now).await?;
    }
    Ok(get(conn, classroom_id).await?)
}

pub async fn remove_co_teacher(
    conn: &mut SqliteConnection,
    classroom_id: &str,
    teacher_id: &str,
    now: i64,
) -> Result<(), sqlx::Error> {
    let removed = sqlx::query("DELETE FROM classroom_teachers WHERE classroom_id = ? AND teacher_id = ?")
        .bind(classroom_id)
        .bind(teacher_id)
        .execute(&mut *conn)
        .await?;
    if removed.rows_affected() > 0 {
        record_teacher_change(conn, classroom_id, teacher_id, now).await?;
    }
    Ok(())
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
                "INSERT INTO enrollments (id, classroom_id, student_id, status, joined_at, created_at, updated_at)
                 VALUES (?, ?, ?, 'PENDING', ?, ?, ?)",
            )
            .bind(&id)
            .bind(&classroom_id)
            .bind(student_id)
            .bind(now)
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

/// Approve and reject act on `PENDING`, remove on `ACTIVE`. A retry at the target changes nothing.
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
    let (from, wrong_state) = if target == "REMOVED" {
        ("ACTIVE", "Learner is not active")
    } else {
        ("PENDING", "No pending request")
    };
    let (id, current) = existing.ok_or_else(|| ApiError::not_found(wrong_state))?;
    if current == target {
        return Ok(false);
    }
    if current != from {
        return Err(ApiError::not_found(wrong_state));
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
