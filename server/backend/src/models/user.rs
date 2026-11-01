//! Wire types for accounts, named as in `contracts/openapi.yaml`. Queries live in `db::users`.

use serde::Serialize;

use crate::auth::Role;

/// The contract's `User`. It has no `pin_hash` field, so no response can carry one.
#[derive(Serialize, Clone, Debug, PartialEq, Eq)]
pub struct User {
    pub id: String,
    pub lrn_or_id: String,
    pub full_name: String,
    pub role: Role,
}

/// The contract's `AuthSession`: what register, login and admin setup answer.
#[derive(Serialize, Debug)]
pub struct AuthSession {
    pub token: String,
    pub expires_at: i64,
    pub user: User,
}
