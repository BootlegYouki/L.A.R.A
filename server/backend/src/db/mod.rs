pub mod sync_revisions;

use std::path::Path;
use std::time::Duration;

use sqlx::migrate::MigrateError;
use sqlx::sqlite::{Sqlite, SqliteConnectOptions, SqliteJournalMode, SqlitePool, SqlitePoolOptions, SqliteSynchronous};
use sqlx::Transaction;

/// PRAGMAs live on the connect options, never in a migration: SQLx runs migrations in a
/// transaction, where `journal_mode = WAL` fails, and `foreign_keys` is per connection.
pub async fn connect(data_dir: &Path) -> Result<SqlitePool, sqlx::Error> {
    let options = SqliteConnectOptions::new()
        .filename(data_dir.join("lara.db"))
        .create_if_missing(true)
        .journal_mode(SqliteJournalMode::Wal)
        .synchronous(SqliteSynchronous::Normal)
        .foreign_keys(true)
        .busy_timeout(Duration::from_secs(5));
    SqlitePoolOptions::new().max_connections(8).connect_with(options).await
}

/// Applies every file in `src/db/migrations` not yet recorded in `_sqlx_migrations`.
pub async fn migrate(pool: &SqlitePool) -> Result<(), MigrateError> {
    sqlx::migrate!("src/db/migrations").run(pool).await
}

/// Every write goes through this. A deferred `BEGIN` that reads first and writes later can hit
/// `SQLITE_BUSY` without waiting for `busy_timeout`; `IMMEDIATE` takes the write lock up front.
/// Dropping the transaction without `commit` rolls it back.
pub async fn begin_immediate(pool: &SqlitePool) -> Result<Transaction<'static, Sqlite>, sqlx::Error> {
    pool.begin_with("BEGIN IMMEDIATE").await
}

pub struct HubMeta {
    pub hub_id: String,
    pub sync_epoch: i64,
}

/// Creates `hub_id` and `sync_epoch = 1` on first run, then returns the stored values. `hub_id`
/// never changes after that: clients compare it to detect a different Hub (`reset`).
pub async fn load_hub_meta(pool: &SqlitePool) -> Result<HubMeta, sqlx::Error> {
    let mut tx = begin_immediate(pool).await?;
    sqlx::query("INSERT OR IGNORE INTO hub_meta (key, value) VALUES ('hub_id', ?), ('sync_epoch', '1')")
        .bind(uuid::Uuid::new_v4().to_string())
        .execute(&mut *tx)
        .await?;
    let hub_id: String =
        sqlx::query_scalar("SELECT value FROM hub_meta WHERE key = 'hub_id'").fetch_one(&mut *tx).await?;
    let epoch: String =
        sqlx::query_scalar("SELECT value FROM hub_meta WHERE key = 'sync_epoch'").fetch_one(&mut *tx).await?;
    tx.commit().await?;
    let sync_epoch = epoch.parse().map_err(|_| sqlx::Error::Decode(format!("hub_meta.sync_epoch is {epoch:?}").into()))?;
    Ok(HubMeta { hub_id, sync_epoch })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pragmas_hold_on_every_pooled_connection() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();

        let mut a = pool.acquire().await.unwrap();
        let mut b = pool.acquire().await.unwrap();
        for conn in [&mut a, &mut b] {
            let mode: String = sqlx::query_scalar("PRAGMA journal_mode").fetch_one(&mut **conn).await.unwrap();
            let fk: i64 = sqlx::query_scalar("PRAGMA foreign_keys").fetch_one(&mut **conn).await.unwrap();
            let busy: i64 = sqlx::query_scalar("PRAGMA busy_timeout").fetch_one(&mut **conn).await.unwrap();
            assert_eq!((mode.as_str(), fk, busy), ("wal", 1, 5000));
        }
    }

    async fn schema_of(pool: &SqlitePool) -> Vec<(String, String, String, Option<String>)> {
        sqlx::query_as(
            "SELECT type, name, tbl_name, sql FROM sqlite_master
             WHERE tbl_name <> '_sqlx_migrations' ORDER BY type, name",
        )
        .fetch_all(pool)
        .await
        .unwrap()
    }

    #[tokio::test]
    async fn migrations_reproduce_the_server_master_contract() {
        let contract_path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../contracts/schema/server_master.sql");
        // A Windows checkout gives the contract CRLF; the migration is pinned to LF (.gitattributes),
        // and sqlite_master keeps statement text byte for byte.
        let contract = std::fs::read_to_string(contract_path).unwrap().replace("\r\n", "\n");

        let migrated_dir = tempfile::tempdir().unwrap();
        let migrated = connect(migrated_dir.path()).await.unwrap();
        migrate(&migrated).await.unwrap();

        let contract_dir = tempfile::tempdir().unwrap();
        let reference = connect(contract_dir.path()).await.unwrap();
        sqlx::raw_sql(sqlx::AssertSqlSafe(contract.clone())).execute(&reference).await.unwrap();

        let expected = schema_of(&reference).await;
        let tables = expected.iter().filter(|(kind, name, ..)| kind == "table" && name != "sqlite_sequence").count();
        assert_eq!(tables, contract.matches("CREATE TABLE").count());
        assert_eq!(schema_of(&migrated).await, expected);
    }

    #[tokio::test]
    async fn first_run_creates_hub_meta_and_restarts_keep_it() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();
        let first = load_hub_meta(&pool).await.unwrap();
        assert!(uuid::Uuid::parse_str(&first.hub_id).is_ok(), "{}", first.hub_id);
        assert_eq!(first.sync_epoch, 1);
        pool.close().await;

        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();
        let again = load_hub_meta(&pool).await.unwrap();
        assert_eq!((again.hub_id, again.sync_epoch), (first.hub_id, 1));
        let rows: i64 = sqlx::query_scalar("SELECT count(*) FROM hub_meta").fetch_one(&pool).await.unwrap();
        assert_eq!(rows, 2);
    }

    #[tokio::test]
    async fn write_transaction_rolls_back_unless_committed() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();
        let insert = "INSERT INTO hub_meta (key, value) VALUES ('probe', 'x')";

        let mut tx = begin_immediate(&pool).await.unwrap();
        sqlx::query(insert).execute(&mut *tx).await.unwrap();
        drop(tx);
        let count = || sqlx::query_scalar::<_, i64>("SELECT count(*) FROM hub_meta WHERE key = 'probe'").fetch_one(&pool);
        assert_eq!(count().await.unwrap(), 0);

        let mut tx = begin_immediate(&pool).await.unwrap();
        sqlx::query(insert).execute(&mut *tx).await.unwrap();
        tx.commit().await.unwrap();
        assert_eq!(count().await.unwrap(), 1);
    }

    #[tokio::test]
    async fn migrate_runs_again_on_restart() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();
        pool.close().await;

        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();
        let ledger: i64 =
            sqlx::query_scalar("SELECT count(*) FROM sqlite_master WHERE name = '_sqlx_migrations'")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(ledger, 1);
    }
}
