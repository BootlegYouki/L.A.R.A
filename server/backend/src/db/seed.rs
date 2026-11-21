//! Test-only accounts for the real Hub (`LARA_TEST_SEED=1`, `HubConfig::test_seed`).
//! Classes, enrollments and posts are created through the contract routes in each test.

use sqlx::SqlitePool;

use super::begin_immediate;
use super::users::{self, InsertError};
use crate::auth::pin::PinHasher;
use crate::auth::{Role, ADMIN_ID};
use crate::StartError;

/// Synthetic development accounts. Every account's PIN is `TEST_PIN`.
pub const TEST_ACCOUNTS: [(&str, &str, Role); 4] = [
    (ADMIN_ID, "Hub Admin", Role::Admin),
    ("T-0001", "Maria Santos", Role::Teacher),
    ("123456789012", "Juan dela Cruz", Role::Student),
    ("123456789013", "Ana Reyes", Role::Student),
];
pub const TEST_PIN: &str = "123456";

/// Creates missing test accounts without overwriting existing accounts on restart.
pub async fn test_accounts(db: &SqlitePool, pins: &PinHasher, now_ms: i64) -> Result<(), StartError> {
    let mut hashes = Vec::with_capacity(TEST_ACCOUNTS.len());
    for _ in TEST_ACCOUNTS {
        hashes.push(pins.hash(TEST_PIN).await.map_err(|e| StartError::TestSeed(e.message))?);
    }
    let mut tx = begin_immediate(db).await?;
    for ((lrn_or_id, full_name, role), pin_hash) in TEST_ACCOUNTS.into_iter().zip(&hashes) {
        match users::insert(&mut tx, lrn_or_id, full_name, role, pin_hash, now_ms).await {
            Ok(_) | Err(InsertError::Exists) => {}
            Err(InsertError::Db(e)) => return Err(e.into()),
        }
    }
    tx.commit().await?;
    // No PIN here: the rule is never to log one, even a test one.
    tracing::warn!("LARA_TEST_SEED=1: development test accounts exist. Never set this on a school Hub.");
    Ok(())
}
