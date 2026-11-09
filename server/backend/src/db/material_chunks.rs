//! `material_chunks` rows: a material's lesson text for the AI tutor (step 3.1).

use sqlx::SqliteConnection;

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::sync::MaterialChunk;
use crate::services::document_parser::ChunkDraft;

/// Inserts the chunks under new ids, each with a class-wide revision, so they sync with their
/// material. Called in the material's own transaction.
pub async fn insert_all(
    tx: &mut SqliteConnection,
    material_id: &str,
    classroom_id: &str,
    chunks: &[ChunkDraft],
    now_ms: i64,
) -> Result<(), sqlx::Error> {
    for chunk in chunks {
        let id = uuid::Uuid::new_v4().to_string();
        sqlx::query(
            "INSERT INTO material_chunks (id, material_id, order_index, heading, text, token_estimate, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(material_id)
        .bind(chunk.order_index)
        .bind(&chunk.heading)
        .bind(&chunk.text)
        .bind(chunk.token_estimate)
        .bind(now_ms)
        .execute(&mut *tx)
        .await?;
        record_revision(tx, Scope::class(classroom_id), "material_chunks", &id, Action::Upsert, now_ms).await?;
    }
    Ok(())
}

/// In `order_index` order. `token_estimate` stays on the Hub (contract `MaterialChunk`).
pub async fn for_material(conn: &mut SqliteConnection, material_id: &str) -> Result<Vec<MaterialChunk>, sqlx::Error> {
    sqlx::query_as("SELECT id, material_id, order_index, heading, text FROM material_chunks WHERE material_id = ? ORDER BY order_index")
        .bind(material_id)
        .fetch_all(conn)
        .await
}
