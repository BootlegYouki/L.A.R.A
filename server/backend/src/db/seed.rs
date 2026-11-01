//! Test only (`LARA_TEST_SEED=1`, `HubConfig::test_seed`): the mock Hub's data, so CI can run
//! `tests/test_mock_hub.py` against the real Hub. Today the accounts; later steps add the mock's
//! class, enrollment and the rest as their routes land.

use sqlx::SqlitePool;

use super::begin_immediate;
use super::users::{self, InsertError};
use crate::auth::pin::PinHasher;
use crate::auth::{Role, ADMIN_ID};
use crate::StartError;

/// `scripts/mock_hub.py` `HubState._seed`. Every account's PIN is `MOCK_PIN`.
pub const MOCK_ACCOUNTS: [(&str, &str, Role); 4] = [
    (ADMIN_ID, "Hub Admin", Role::Admin),
    ("T-0001", "Maria Santos", Role::Teacher),
    ("123456789012", "Juan dela Cruz", Role::Student),
    ("123456789013", "Ana Reyes", Role::Student),
];
pub const MOCK_PIN: &str = "1234";

/// Creates the mock accounts that do not exist yet, so restarting with the setting keeps working.
pub async fn mock_accounts(db: &SqlitePool, pins: &PinHasher, now_ms: i64) -> Result<(), StartError> {
    let mut hashes = Vec::with_capacity(MOCK_ACCOUNTS.len());
    for _ in MOCK_ACCOUNTS {
        hashes.push(pins.hash(MOCK_PIN).await.map_err(|e| StartError::TestSeed(e.message))?);
    }
    let mut tx = begin_immediate(db).await?;
    for ((lrn_or_id, full_name, role), pin_hash) in MOCK_ACCOUNTS.into_iter().zip(&hashes) {
        match users::insert(&mut tx, lrn_or_id, full_name, role, pin_hash, now_ms).await {
            Ok(_) | Err(InsertError::Exists) => {}
            Err(InsertError::Db(e)) => return Err(e.into()),
        }
    }
    tx.commit().await?;
    // No PIN here: the rule is never to log one, even a test one.
    tracing::warn!("LARA_TEST_SEED=1: the mock Hub's test accounts exist. Never set this on a school Hub.");
    Ok(())
}
