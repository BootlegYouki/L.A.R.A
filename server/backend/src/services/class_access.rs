use sqlx::SqliteConnection;

use crate::auth::{require_role, AuthUser, Role};
use crate::error::ApiError;

/// Owner and co-teachers have the same enrollment permissions (contract PR #216).
pub async fn require_teacher(conn: &mut SqliteConnection, user: &AuthUser, classroom_id: &str) -> Result<(), ApiError> {
    require_role(user, Role::Teacher)?;
    let owner: Option<String> = sqlx::query_scalar("SELECT teacher_id FROM classrooms WHERE id = ?")
        .bind(classroom_id)
        .fetch_optional(&mut *conn)
        .await?;
    let owner = owner.ok_or_else(|| ApiError::not_found("Classroom not found"))?;
    if owner != user.id {
        let co_teacher: bool = sqlx::query_scalar(
            "SELECT EXISTS (SELECT 1 FROM classroom_teachers WHERE classroom_id = ? AND teacher_id = ?)",
        )
        .bind(classroom_id)
        .bind(&user.id)
        .fetch_one(&mut *conn)
        .await?;
        if !co_teacher {
            return Err(ApiError::forbidden("Not your classroom"));
        }
    }
    Ok(())
}

pub async fn require_writable(conn: &mut SqliteConnection, classroom_id: &str) -> Result<(), ApiError> {
    if is_archived(conn, classroom_id).await? {
        return Err(ApiError::forbidden("This class is archived"));
    }
    Ok(())
}

pub async fn is_archived(conn: &mut SqliteConnection, classroom_id: &str) -> Result<bool, sqlx::Error> {
    let archived: Option<i64> = sqlx::query_scalar("SELECT archived_at FROM classrooms WHERE id = ?")
        .bind(classroom_id)
        .fetch_one(conn)
        .await?;
    Ok(archived.is_some())
}

/// The mock's `member_of`, for class content any member may read or a learner may submit to:
/// the owner and co-teachers, and learners whose enrollment is `ACTIVE`. The admin is not a member.
pub async fn require_member(conn: &mut SqliteConnection, user: &AuthUser, classroom_id: &str) -> Result<(), ApiError> {
    let exists: bool = sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM classrooms WHERE id = ?)")
        .bind(classroom_id)
        .fetch_one(&mut *conn)
        .await?;
    if !exists {
        return Err(ApiError::not_found("Classroom not found"));
    }
    let member: bool = match user.role {
        Role::Teacher => {
            sqlx::query_scalar(
                "SELECT EXISTS (SELECT 1 FROM classrooms WHERE id = ? AND teacher_id = ?)
                     OR EXISTS (SELECT 1 FROM classroom_teachers WHERE classroom_id = ? AND teacher_id = ?)",
            )
            .bind(classroom_id)
            .bind(&user.id)
            .bind(classroom_id)
            .bind(&user.id)
            .fetch_one(&mut *conn)
            .await?
        }
        Role::Student => {
            sqlx::query_scalar(
                "SELECT EXISTS (SELECT 1 FROM enrollments WHERE classroom_id = ? AND student_id = ? AND status = 'ACTIVE')",
            )
            .bind(classroom_id)
            .bind(&user.id)
            .fetch_one(&mut *conn)
            .await?
        }
        Role::Admin => false,
    };
    if !member {
        return Err(ApiError::forbidden("Not your class"));
    }
    Ok(())
}
