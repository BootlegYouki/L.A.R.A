use sha2::{Digest, Sha384};
use sqlx::{sqlite::SqliteConnectOptions, Connection, Row, SqliteConnection};
use std::{borrow::Cow, path::Path};
use tauri::Manager;

pub(crate) const SQL: &[(i64, &str, &str)] = &[
    (
        1,
        "client_offline_schema",
        include_str!("../migrations/001_client_offline.sql"),
    ),
    (
        2,
        "sync_rejections",
        include_str!("../migrations/002_sync_rejections.sql"),
    ),
    (
        3,
        "submission_received_at",
        include_str!("../migrations/003_submission_received_at.sql"),
    ),
    (
        4,
        "audit_created_at",
        include_str!("../migrations/004_audit_created_at.sql"),
    ),
    (5, "announcement_author", include_str!("../migrations/005_announcement_author.sql")),
];

pub(crate) fn migrator() -> sqlx::migrate::Migrator {
    sqlx::migrate::Migrator {
        migrations: Cow::Owned(
            SQL.iter()
                .map(|&(version, name, sql)| {
                    sqlx::migrate::Migration::new(
                        version,
                        name.into(),
                        sqlx::migrate::MigrationType::ReversibleUp,
                        sql.into(),
                        false,
                    )
                })
                .collect(),
        ),
        ..sqlx::migrate::Migrator::DEFAULT
    }
}

pub(crate) async fn normalize_checksums(db: &mut SqliteConnection) -> Result<(), sqlx::Error> {
    let exists: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='_sqlx_migrations'",
    )
    .fetch_one(&mut *db)
    .await?;
    if exists == 0 {
        return Ok(());
    }
    let mut tx = db.begin().await?;
    let rows = sqlx::query("SELECT version,checksum FROM _sqlx_migrations WHERE success=1")
        .fetch_all(&mut *tx)
        .await?;
    for row in rows {
        let version: i64 = row.get("version");
        let stored: Vec<u8> = row.get("checksum");
        if let Some(&(_, _, sql)) = SQL.iter().find(|&&(v, _, _)| v == version) {
            let expected = Sha384::digest(sql.as_bytes()).to_vec();
            let lf = sql.replace("\r\n", "\n");
            let crlf = lf.replace("\n", "\r\n");
            // Only byte-identical SQL apart from newline encoding may change its ledger checksum.
            if stored != expected
                && [lf, crlf]
                    .iter()
                    .any(|sql| Sha384::digest(sql.as_bytes()).as_slice() == stored.as_slice())
            {
                sqlx::query("UPDATE _sqlx_migrations SET checksum=? WHERE version=? AND checksum=? AND success=1")
                    .bind(expected).bind(version).bind(stored).execute(&mut *tx).await?;
            }
        }
    }
    tx.commit().await
}

async fn prepare(path: &Path) -> Result<(), sqlx::Error> {
    if path.exists() {
        let mut db =
            SqliteConnection::connect_with(&SqliteConnectOptions::new().filename(path)).await?;
        normalize_checksums(&mut db).await?;
        db.close().await?;
    }
    Ok(())
}

#[tauri::command]
pub async fn prepare_client_database(app: tauri::AppHandle) -> Result<(), &'static str> {
    let path = app
        .path()
        .app_config_dir()
        .map_err(|_| "database_unavailable")?
        .join("lara_client.db");
    prepare(&path).await.map_err(|_| "database_unavailable")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn plugin_prepare_accepts_newlines_but_rejects_changed_sql() {
        for crlf in [false, true] {
            let root =
                std::env::temp_dir().join(format!("lara-migration-{}", uuid::Uuid::new_v4()));
            std::fs::create_dir_all(&root).unwrap();
            let path = root.join("lara_client.db");
            let mut db = SqliteConnection::connect_with(
                &SqliteConnectOptions::new()
                    .filename(&path)
                    .create_if_missing(true),
            )
            .await
            .unwrap();
            let old = sqlx::migrate::Migrator {
                migrations: Cow::Owned(
                    SQL.iter()
                        .take(3)
                        .map(|&(version, name, sql)| {
                            let lf = sql.replace("\r\n", "\n");
                            let sql = if crlf { lf.replace("\n", "\r\n") } else { lf };
                            sqlx::migrate::Migration::new(
                                version,
                                name.into(),
                                sqlx::migrate::MigrationType::ReversibleUp,
                                sql.into(),
                                false,
                            )
                        })
                        .collect(),
                ),
                ..sqlx::migrate::Migrator::DEFAULT
            };
            old.run_direct(&mut db).await.unwrap();
            sqlx::query("INSERT INTO sync_state(key,value,updated_at) VALUES('cursor','7',1)")
                .execute(&mut db)
                .await
                .unwrap();
            db.close().await.unwrap();
            prepare(&path).await.unwrap();
            let mut db =
                SqliteConnection::connect_with(&SqliteConnectOptions::new().filename(&path))
                    .await
                    .unwrap();
            migrator().run_direct(&mut db).await.unwrap();
            assert_eq!(
                sqlx::query_scalar::<_, String>("SELECT value FROM sync_state WHERE key='cursor'")
                    .fetch_one(&mut db)
                    .await
                    .unwrap(),
                "7"
            );
            let unknown = vec![7_u8; 48];
            sqlx::query("UPDATE _sqlx_migrations SET checksum=? WHERE version=2")
                .bind(&unknown)
                .execute(&mut db)
                .await
                .unwrap();
            normalize_checksums(&mut db).await.unwrap();
            assert!(migrator().run_direct(&mut db).await.is_err());
            assert_eq!(
                sqlx::query_scalar::<_, Vec<u8>>(
                    "SELECT checksum FROM _sqlx_migrations WHERE version=2"
                )
                .fetch_one(&mut db)
                .await
                .unwrap(),
                unknown
            );
            db.close().await.unwrap();
            std::fs::remove_dir_all(root).unwrap();
        }
    }
}
