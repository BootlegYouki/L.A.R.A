pub mod announcements;
pub mod assignments;
pub mod material_chunks;
pub mod materials;
pub mod private_comments;
pub mod quizzes;
pub mod seed;
pub mod submissions;
pub mod sync_revisions;
pub mod topics;
pub mod users;
pub mod classrooms;

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

    /// A Hub that already holds homework upgrades without losing it: the arrival time it recorded
    /// as `submitted_at` becomes `received_at` too.
    #[tokio::test]
    async fn upgrading_keeps_handed_in_work_and_fills_received_at() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        let mut first = sqlx::migrate!("src/db/migrations");
        first.migrations = first.migrations.iter().filter(|m| m.version == 1).cloned().collect::<Vec<_>>().into();
        first.run(&pool).await.unwrap();
        sqlx::raw_sql(
            "INSERT INTO users (id, lrn_or_id, full_name, role, pin_hash, created_at, updated_at)
                 VALUES ('t', 'T-0001', 'Teacher', 'TEACHER', 'x', 1, 1), ('s', '123456789012', 'Ana', 'STUDENT', 'x', 1, 1);
             INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
                 VALUES ('c', 'Science 4', 'A', 'K7M4QX', 't', 1, 1);
             INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES ('hw', 'c', 'Homework', 9, 1, 1);
             INSERT INTO assignment_submissions (id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at)
                 VALUES ('sub', 'hw', 's', 'submissions/a.jpg', 'IMAGE', 5, 8, 'Good', 6);",
        )
        .execute(&pool)
        .await
        .unwrap();

        migrate(&pool).await.unwrap();
        let row: (String, String, i64, i64, Option<i64>, Option<String>, i64) = sqlx::query_as(
            "SELECT id, file_path, submitted_at, received_at, score, teacher_feedback, updated_at FROM assignment_submissions",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(row, ("sub".into(), "submissions/a.jpg".into(), 5, 5, Some(8), Some("Good".into()), 6));
        assert_eq!(sqlx::query("PRAGMA foreign_key_check").fetch_all(&pool).await.unwrap().len(), 0, "no broken references");
    }

    /// Contract #273 (issue #274): a Hub that already holds classes upgrades with every row kept and
    /// the new audit columns filled from the times it already recorded. Rebuilding `materials` and
    /// `material_chunks` must not cascade into their chunks or null the tutor's chunk references.
    #[tokio::test]
    async fn upgrading_backfills_the_audit_fields_and_keeps_every_row() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        let mut before = sqlx::migrate!("src/db/migrations");
        before.migrations = before.migrations.iter().filter(|m| m.version <= 2).cloned().collect::<Vec<_>>().into();
        before.run(&pool).await.unwrap();
        sqlx::raw_sql(
            "INSERT INTO users (id, lrn_or_id, full_name, role, pin_hash, created_at, updated_at)
                 VALUES ('t', 'T-0001', 'Owner', 'TEACHER', 'x', 1, 1), ('t2', 'T-0002', 'Co', 'TEACHER', 'x', 1, 1),
                        ('s', '123456789012', 'Ana', 'STUDENT', 'x', 1, 1);
             INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
                 VALUES ('c', 'Science 4', 'A', 'K7M4QX', 't', 1, 1);
             INSERT INTO enrollments (id, classroom_id, student_id, status, joined_at, updated_at) VALUES ('e', 'c', 's', 'ACTIVE', 3, 4);
             INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at) VALUES ('c', 't2', 5);
             INSERT INTO materials (id, classroom_id, title, file_type, file_path, file_size_bytes, created_at, updated_at)
                 VALUES ('m', 'c', 'Lesson', 'DOCUMENT', 'materials/a.pdf', 10, 6, 6);
             INSERT INTO material_chunks (id, material_id, order_index, text, updated_at) VALUES ('ch', 'm', 0, 'Plants', 7);
             INSERT INTO ai_chat_messages (id, classroom_id, student_id, material_id, chunk_id, role, content, created_at)
                 VALUES ('ai', 'c', 's', 'm', 'ch', 'TUTOR', 'What do plants need?', 7);
             INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES ('hw', 'c', 'Homework', 9, 1, 1);
             INSERT INTO assignment_submissions (id, assignment_id, student_id, file_path, submitted_at, received_at, updated_at)
                 VALUES ('sub', 'hw', 's', 'submissions/a.jpg', 8, 9, 9);
             INSERT INTO quizzes (id, classroom_id, title, time_limit_minutes, created_at, updated_at) VALUES ('q', 'c', 'Quiz', 15, 1, 1);
             INSERT INTO quiz_attempts (id, quiz_id, student_id, started_at, updated_at) VALUES ('at', 'q', 's', 10, 10);",
        )
        .execute(&pool)
        .await
        .unwrap();

        migrate(&pool).await.unwrap();
        let one = |sql: &'static str| sqlx::query_scalar::<_, i64>(sql).fetch_one(&pool);
        assert_eq!(one("SELECT created_at FROM enrollments WHERE id = 'e'").await.unwrap(), 3, "from joined_at");
        assert_eq!(one("SELECT created_at FROM material_chunks WHERE id = 'ch'").await.unwrap(), 7, "from updated_at");
        assert_eq!(one("SELECT created_at FROM assignment_submissions WHERE id = 'sub'").await.unwrap(), 8, "from submitted_at");
        assert_eq!(one("SELECT created_at FROM quiz_attempts WHERE id = 'at'").await.unwrap(), 10, "from started_at");
        let teachers: (i64, i64, i64) = sqlx::query_as("SELECT added_at, created_at, updated_at FROM classroom_teachers")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(teachers, (5, 5, 5));
        let uploader: String = sqlx::query_scalar("SELECT uploaded_by FROM materials WHERE id = 'm'").fetch_one(&pool).await.unwrap();
        assert_eq!(uploader, "t", "the class owner");
        let chat: (Option<String>, Option<String>) =
            sqlx::query_as("SELECT material_id, chunk_id FROM ai_chat_messages WHERE id = 'ai'").fetch_one(&pool).await.unwrap();
        assert_eq!(chat, (Some("m".into()), Some("ch".into())), "the rebuild must not fire ON DELETE SET NULL");
        for table in ["enrollments", "classroom_teachers", "materials", "material_chunks", "assignment_submissions", "quiz_attempts"] {
            let rows: i64 = sqlx::query_scalar(sqlx::AssertSqlSafe(format!("SELECT count(*) FROM {table}")))
                .fetch_one(&pool)
                .await
                .unwrap();
            assert_eq!(rows, 1, "{table} kept its row");
        }
        assert_eq!(sqlx::query("PRAGMA foreign_key_check").fetch_all(&pool).await.unwrap().len(), 0, "no broken references");
    }

    /// Contract #294 (for #11): a Hub that already holds quizzes upgrades with every quiz, question and
    /// attempt kept. Rebuilding `quizzes` must not cascade into its questions and attempts, a quiz already
    /// `CLOSED` gets `closed_at` from `updated_at`, and nothing counts as auto-submitted.
    #[tokio::test]
    async fn upgrading_adds_the_close_columns_and_keeps_every_quiz_row() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        let mut before = sqlx::migrate!("src/db/migrations");
        before.migrations = before.migrations.iter().filter(|m| m.version <= 3).cloned().collect::<Vec<_>>().into();
        before.run(&pool).await.unwrap();
        sqlx::raw_sql(
            "INSERT INTO users (id, lrn_or_id, full_name, role, pin_hash, created_at, updated_at)
                 VALUES ('t', 'T-0001', 'Owner', 'TEACHER', 'x', 1, 1), ('s', '123456789012', 'Ana', 'STUDENT', 'x', 1, 1);
             INSERT INTO classrooms (id, name, section, class_code, teacher_id, created_at, updated_at)
                 VALUES ('c', 'Science 4', 'A', 'K7M4QX', 't', 1, 1);
             INSERT INTO quizzes (id, classroom_id, title, time_limit_minutes, status, started_at, created_at, updated_at)
                 VALUES ('done', 'c', 'Closed quiz', 15, 'CLOSED', 2, 1, 20), ('live', 'c', 'Live quiz', 10, 'ACTIVE', 30, 25, 30);
             INSERT INTO quiz_questions (id, quiz_id, order_index, question_text, question_type, options_json, points,
                                         correct_answer, synonyms_json, created_at, updated_at)
                 VALUES ('q1', 'done', 0, 'Plants need?', 'IDENTIFICATION', NULL, 2, 'sunlight', '[\"light\"]', 1, 1),
                        ('q2', 'live', 0, 'Is water wet?', 'TRUE_FALSE', '[\"True\",\"False\"]', 1, 'True', NULL, 25, 25);
             INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, created_at, submitted_at, score,
                                        total_points, answers_json, updated_at)
                 VALUES ('a1', 'done', 's', 'SUBMITTED', 3, 3, 9, 2, 2, '[{\"question_id\":\"q1\",\"selected_option\":\"light\"}]', 9),
                        ('a2', 'live', 's', 'IN_PROGRESS', 31, 31, NULL, NULL, NULL, '[]', 31);",
        )
        .execute(&pool)
        .await
        .unwrap();

        migrate(&pool).await.unwrap();
        let quizzes: Vec<(String, String, Option<i64>, i64)> =
            sqlx::query_as("SELECT id, status, closed_at, updated_at FROM quizzes ORDER BY id").fetch_all(&pool).await.unwrap();
        assert_eq!(
            quizzes,
            vec![("done".into(), "CLOSED".into(), Some(20), 20), ("live".into(), "ACTIVE".into(), None, 30)],
            "closed_at only for a quiz already closed"
        );
        let questions: Vec<(String, String, Option<String>)> =
            sqlx::query_as("SELECT id, correct_answer, synonyms_json FROM quiz_questions ORDER BY id").fetch_all(&pool).await.unwrap();
        assert_eq!(
            questions,
            vec![("q1".into(), "sunlight".into(), Some("[\"light\"]".into())), ("q2".into(), "True".into(), None)],
            "the rebuild must not cascade into the questions"
        );
        let attempts: Vec<(String, String, Option<i64>, String, i64)> =
            sqlx::query_as("SELECT id, status, score, answers_json, auto_submitted FROM quiz_attempts ORDER BY id")
                .fetch_all(&pool)
                .await
                .unwrap();
        assert_eq!(
            attempts,
            vec![
                ("a1".into(), "SUBMITTED".into(), Some(2), "[{\"question_id\":\"q1\",\"selected_option\":\"light\"}]".into(), 0),
                ("a2".into(), "IN_PROGRESS".into(), None, "[]".into(), 0),
            ],
            "every attempt kept, none auto-submitted"
        );
        assert_eq!(sqlx::query("PRAGMA foreign_key_check").fetch_all(&pool).await.unwrap().len(), 0, "no broken references");
        let second_active = sqlx::query(
            "INSERT INTO quizzes (id, classroom_id, title, time_limit_minutes, status, created_at, updated_at)
             VALUES ('other', 'c', 'Another', 5, 'ACTIVE', 40, 40)",
        )
        .execute(&pool)
        .await;
        assert!(second_active.is_err(), "uq_one_active_quiz_per_classroom must be back after the rebuild");
        sqlx::query("DELETE FROM quizzes WHERE id = 'done'").execute(&pool).await.unwrap();
        let left: i64 = sqlx::query_scalar("SELECT count(*) FROM quiz_questions WHERE quiz_id = 'done'").fetch_one(&pool).await.unwrap();
        assert_eq!(left, 0, "the new children cascade from the new quizzes table");
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
