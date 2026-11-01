//! The `users` table. Accounts are never deleted: `is_active = 0` deactivates one.

use axum::http::StatusCode;
use serde::Serialize;
use sqlx::{SqliteConnection, SqlitePool};

use crate::auth::Role;
use crate::db::sync_revisions::{record_revision, Action, Scope};
use crate::error::{ApiError, ErrorCode};

/// The contract's `User`. It has no `pin_hash` field, so no response can carry one.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct User {
    pub id: String,
    pub lrn_or_id: String,
    pub full_name: String,
    pub role: Role,
}

/// A user with the columns only the Hub reads.
pub struct Account {
    pub user: User,
    pub pin_hash: String,
    pub is_active: bool,
}

#[derive(Debug)]
pub enum InsertError {
    /// `lrn_or_id` is taken.
    Exists,
    Db(sqlx::Error),
}

impl From<InsertError> for ApiError {
    fn from(error: InsertError) -> Self {
        match error {
            InsertError::Exists => ApiError::new(StatusCode::CONFLICT, ErrorCode::AlreadyExists, "Account already exists"),
            InsertError::Db(e) => e.into(),
        }
    }
}

pub async fn find_by_lrn(db: &SqlitePool, lrn_or_id: &str) -> Result<Option<Account>, sqlx::Error> {
    let row: Option<(String, String, String, String, String, bool)> =
        sqlx::query_as("SELECT id, lrn_or_id, full_name, role, pin_hash, is_active FROM users WHERE lrn_or_id = ?")
            .bind(lrn_or_id)
            .fetch_optional(db)
            .await?;
    row.map(|(id, lrn_or_id, full_name, role, pin_hash, is_active)| {
        let role = Role::parse(&role).ok_or_else(|| sqlx::Error::Decode(format!("users.role is {role:?}").into()))?;
        Ok(Account { user: User { id, lrn_or_id, full_name, role }, pin_hash, is_active })
    })
    .transpose()
}

/// Inserts an active account inside the caller's write transaction and records its sync revision
/// there too. The admin gets no revision: it is never synced to a client.
pub async fn insert(
    tx: &mut SqliteConnection,
    lrn_or_id: &str,
    full_name: &str,
    role: Role,
    pin_hash: &str,
    now_ms: i64,
) -> Result<User, InsertError> {
    let id = uuid::Uuid::new_v4().to_string();
    let inserted = sqlx::query(
        "INSERT INTO users (id, lrn_or_id, full_name, role, pin_hash, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
    )
    .bind(&id)
    .bind(lrn_or_id)
    .bind(full_name)
    .bind(role.as_str())
    .bind(pin_hash)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await;
    match inserted {
        Err(sqlx::Error::Database(e)) if e.is_unique_violation() => return Err(InsertError::Exists),
        Err(e) => return Err(InsertError::Db(e)),
        Ok(_) => {}
    }
    if role != Role::Admin {
        record_revision(tx, Scope::global(), "users", &id, Action::Upsert, now_ms).await.map_err(InsertError::Db)?;
    }
    Ok(User { id, lrn_or_id: lrn_or_id.to_owned(), full_name: full_name.to_owned(), role })
}

pub async fn admin_exists(conn: &mut SqliteConnection) -> Result<bool, sqlx::Error> {
    sqlx::query_scalar("SELECT EXISTS (SELECT 1 FROM users WHERE role = 'ADMIN')").fetch_one(conn).await
}
