//! Bearer sessions in the `sessions` table, found by the SHA-256 of the token. The REST
//! extractor and the WebSocket `EVENT_HELLO` both use [`user_for_token`], so a token that stops
//! working stops on both at once.

use sqlx::{SqliteConnection, SqlitePool};

use super::token::{new_token, token_hash, SESSION_TTL_MS};
use super::{AuthUser, Role};
use crate::db::begin_immediate;
use crate::models::user::{AuthSession, User};

/// Opens a session inside the caller's write transaction, and clears expired ones while it holds
/// the lock (nothing ever reads them again).
pub async fn open(tx: &mut SqliteConnection, user: User, now_ms: i64) -> Result<AuthSession, sqlx::Error> {
    sqlx::query("DELETE FROM sessions WHERE expires_at < ?").bind(now_ms).execute(&mut *tx).await?;
    let token = new_token();
    let expires_at = now_ms + SESSION_TTL_MS;
    sqlx::query("INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)")
        .bind(token_hash(&token))
        .bind(&user.id)
        .bind(now_ms)
        .bind(expires_at)
        .execute(&mut *tx)
        .await?;
    Ok(AuthSession { token, expires_at, user })
}

/// `None` for an unknown or expired token and for a deactivated account. A session is valid
/// until the millisecond it expires, as in the mock (`expires_at < now` is expired).
pub async fn user_for_token(db: &SqlitePool, token: &str, now_ms: i64) -> Result<Option<AuthUser>, sqlx::Error> {
    let row: Option<(String, String)> = sqlx::query_as(
        "SELECT u.id, u.role FROM sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = ? AND s.expires_at >= ? AND u.is_active = 1",
    )
    .bind(token_hash(token))
    .bind(now_ms)
    .fetch_optional(db)
    .await?;
    Ok(row.and_then(|(id, role)| Some(AuthUser { id, role: Role::parse(&role)? })))
}

pub async fn close(db: &SqlitePool, token: &str) -> Result<(), sqlx::Error> {
    let mut tx = begin_immediate(db).await?;
    sqlx::query("DELETE FROM sessions WHERE token_hash = ?").bind(token_hash(token)).execute(&mut *tx).await?;
    tx.commit().await
}

/// Signs the user out everywhere, inside the caller's write transaction. A PIN reset calls it so
/// whoever already holds a session from the old PIN is refused from the same commit.
pub async fn close_all(tx: &mut SqliteConnection, user_id: &str) -> Result<(), sqlx::Error> {
    sqlx::query("DELETE FROM sessions WHERE user_id = ?").bind(user_id).execute(&mut *tx).await?;
    Ok(())
}
