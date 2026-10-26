use sqlx::SqliteConnection;

/// Who may pull a revision (`rules/database-and-sync.md` §3.1).
#[derive(Clone, Copy)]
pub struct Scope<'a> {
    pub classroom_id: Option<&'a str>,
    pub student_id: Option<&'a str>,
}

impl<'a> Scope<'a> {
    /// Records outside any class, such as `users`.
    pub fn global() -> Self {
        Self { classroom_id: None, student_id: None }
    }

    /// Everyone in the class.
    pub fn class(classroom_id: &'a str) -> Self {
        Self { classroom_id: Some(classroom_id), student_id: None }
    }

    /// Only that learner and the class's teachers: enrollments, submissions, attempts, private comments.
    pub fn student(classroom_id: &'a str, student_id: &'a str) -> Self {
        Self { classroom_id: Some(classroom_id), student_id: Some(student_id) }
    }
}

#[derive(Clone, Copy)]
pub enum Action {
    Upsert,
    Delete,
}

impl Action {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Upsert => "UPSERT",
            Self::Delete => "DELETE",
        }
    }
}

/// The only writer of `sync_revisions`. Call it inside the same transaction as the change, so a
/// client can never pull a revision whose row did not commit, or miss a row that did.
pub async fn record_revision(
    tx: &mut SqliteConnection,
    scope: Scope<'_>,
    table: &'static str,
    id: &str,
    action: Action,
    now_ms: i64,
) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar(
        "INSERT INTO sync_revisions (classroom_id, student_id, entity_table, entity_id, action, created_at)
         VALUES (?, ?, ?, ?, ?, ?) RETURNING seq",
    )
    .bind(scope.classroom_id)
    .bind(scope.student_id)
    .bind(table)
    .bind(id)
    .bind(action.as_str())
    .bind(now_ms) // created_at is informational, never the cursor
    .fetch_one(&mut *tx)
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::{begin_immediate, connect, migrate};

    type Row = (i64, Option<String>, Option<String>, String, String, String, i64);

    #[tokio::test]
    async fn record_revision_stores_scope_and_returns_increasing_seq() {
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();

        let mut tx = begin_immediate(&pool).await.unwrap();
        let a = record_revision(&mut tx, Scope::global(), "users", "u1", Action::Upsert, 10).await.unwrap();
        let b = record_revision(&mut tx, Scope::class("c1"), "announcements", "a1", Action::Delete, 11).await.unwrap();
        let c = record_revision(&mut tx, Scope::student("c1", "s1"), "quiz_attempts", "q1", Action::Upsert, 12)
            .await
            .unwrap();
        tx.commit().await.unwrap();
        assert!(a < b && b < c, "{a} {b} {c}");

        let rows: Vec<Row> = sqlx::query_as(
            "SELECT seq, classroom_id, student_id, entity_table, entity_id, action, created_at
             FROM sync_revisions ORDER BY seq",
        )
        .fetch_all(&pool)
        .await
        .unwrap();
        let s = |v: &str| Some(v.to_string());
        assert_eq!(
            rows,
            [
                (a, None, None, "users".into(), "u1".into(), "UPSERT".into(), 10),
                (b, s("c1"), None, "announcements".into(), "a1".into(), "DELETE".into(), 11),
                (c, s("c1"), s("s1"), "quiz_attempts".into(), "q1".into(), "UPSERT".into(), 12),
            ]
        );
    }

    /// The acceptance check for WAL: a reader holding a transaction open neither blocks nor sees
    /// concurrent writers, and every write commits with a unique seq.
    #[tokio::test]
    async fn concurrent_writers_and_an_open_reader_all_succeed() {
        const WRITERS: i64 = 8;
        const WRITES: i64 = 50;
        let dir = tempfile::tempdir().unwrap();
        let pool = connect(dir.path()).await.unwrap();
        migrate(&pool).await.unwrap();

        let count = "SELECT count(*) FROM sync_revisions";
        let mut reader = pool.begin().await.unwrap();
        let before: i64 = sqlx::query_scalar(count).fetch_one(&mut *reader).await.unwrap();

        let mut tasks = Vec::new();
        for w in 0..WRITERS {
            let pool = pool.clone();
            tasks.push(tokio::spawn(async move {
                for i in 0..WRITES {
                    let mut tx = begin_immediate(&pool).await?;
                    let id = format!("{w}-{i}");
                    record_revision(&mut tx, Scope::class("c1"), "announcements", &id, Action::Upsert, i).await?;
                    tx.commit().await?;
                    sqlx::query_scalar::<_, i64>(count).fetch_one(&pool).await?;
                }
                Ok::<_, sqlx::Error>(())
            }));
        }
        for task in tasks {
            task.await.unwrap().unwrap();
        }

        let during: i64 = sqlx::query_scalar(count).fetch_one(&mut *reader).await.unwrap();
        assert_eq!(during, before, "the open read transaction keeps its snapshot");
        reader.rollback().await.unwrap();

        let (total, distinct, max): (i64, i64, i64) =
            sqlx::query_as("SELECT count(*), count(DISTINCT seq), max(seq) FROM sync_revisions")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!((total, distinct, max), (WRITERS * WRITES, WRITERS * WRITES, WRITERS * WRITES));
    }
}
