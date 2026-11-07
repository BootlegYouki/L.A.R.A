use sqlx::SqliteConnection;
use std::collections::{BTreeMap, HashSet};

use crate::auth::{AuthUser, Role};
use crate::models::classroom::Classroom;
use crate::models::sync::*;

pub const PULL_PAGE_SIZE: usize = 500;

#[derive(sqlx::FromRow)]
struct Revision {
    seq: i64,
    classroom_id: Option<String>,
    student_id: Option<String>,
    entity_table: String,
    entity_id: String,
    action: String,
}

impl Revision {
    fn visible(&self, user: &AuthUser, classes: &HashSet<String>) -> bool {
        let own = matches!(self.entity_table.as_str(), "enrollments" | "classrooms")
            && self.student_id.as_deref() == Some(user.id.as_str());
        let class_visible = self
            .classroom_id
            .as_ref()
            .is_some_and(|id| classes.contains(id));
        (class_visible || own)
            && (self.student_id.is_none()
                || self.student_id.as_deref() == Some(&user.id)
                || user.role == Role::Teacher)
    }
}

/// Call inside the restore/pruning transaction; neither operation may leave old cursors valid.
pub async fn bump_sync_epoch(conn: &mut SqliteConnection) -> Result<i64, sqlx::Error> {
    let value: String = sqlx::query_scalar(
        "UPDATE hub_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'sync_epoch' RETURNING value",
    ).fetch_one(conn).await?;
    value.parse().map_err(|e| sqlx::Error::Decode(Box::new(e)))
}

/// The caller keeps one read transaction open across identity, ledger and entity reads.
pub async fn pull_page(
    conn: &mut SqliteConnection,
    user: &AuthUser,
    input: &SyncPullInput,
    now_ms: i64,
) -> Result<SyncPullResponse, sqlx::Error> {
    let hub_id: String = sqlx::query_scalar("SELECT value FROM hub_meta WHERE key = 'hub_id'")
        .fetch_one(&mut *conn)
        .await?;
    let epoch: String = sqlx::query_scalar("SELECT value FROM hub_meta WHERE key = 'sync_epoch'")
        .fetch_one(&mut *conn)
        .await?;
    let sync_epoch = epoch
        .parse::<i64>()
        .map_err(|e| sqlx::Error::Decode(Box::new(e)))?;
    let reset = input.hub_id.as_ref().is_some_and(|id| id != &hub_id)
        || input.sync_epoch.is_some_and(|epoch| epoch != sync_epoch);
    let cursor = if reset { 0 } else { input.cursor };
    let classes: HashSet<String> = sqlx::query_scalar::<_, String>(
        "SELECT c.id FROM classrooms c WHERE
         (? = 'TEACHER' AND (c.teacher_id = ? OR EXISTS (
            SELECT 1 FROM classroom_teachers ct WHERE ct.classroom_id = c.id AND ct.teacher_id = ?)))
         OR (? = 'STUDENT' AND EXISTS (SELECT 1 FROM enrollments e
            WHERE e.classroom_id = c.id AND e.student_id = ? AND e.status = 'ACTIVE'))",
    ).bind(user.role.as_str()).bind(&user.id).bind(&user.id).bind(user.role.as_str()).bind(&user.id)
        .fetch_all(&mut *conn).await?.into_iter().collect();
    // Scan even invisible revisions, so an empty visible page still makes forward progress.
    let mut page = sqlx::query_as::<_, Revision>(
        "SELECT seq, classroom_id, student_id, entity_table, entity_id, action
         FROM sync_revisions WHERE seq > ? ORDER BY seq LIMIT ?",
    )
    .bind(cursor)
    .bind((PULL_PAGE_SIZE + 1) as i64)
    .fetch_all(&mut *conn)
    .await?;
    let has_more = page.len() > PULL_PAGE_SIZE;
    page.truncate(PULL_PAGE_SIZE);
    let next_cursor = page.last().map_or(cursor, |r| r.seq);
    let fresh: HashSet<String> = page
        .iter()
        .filter(|r| {
            cursor > 0
                && r.student_id.as_deref() == Some(&user.id)
                && matches!(r.entity_table.as_str(), "classrooms" | "enrollments")
        })
        .filter_map(|r| r.classroom_id.as_ref())
        .filter(|id| classes.contains(*id))
        .cloned()
        .collect();
    // Older rows must accompany the visibility revision: a scalar cursor cannot page backwards.
    // Normal ledger pages stay bounded; a newly visible class snapshot is included atomically.
    for class_id in fresh {
        page.extend(
            sqlx::query_as::<_, Revision>(
                "SELECT seq, classroom_id, student_id, entity_table, entity_id, action
             FROM sync_revisions WHERE classroom_id = ? AND seq <= ? ORDER BY seq",
            )
            .bind(class_id)
            .bind(cursor)
            .fetch_all(&mut *conn)
            .await?,
        );
    }
    let mut selected = BTreeMap::new();
    for revision in page {
        if revision.visible(user, &classes) {
            selected.insert(
                (revision.entity_table.clone(), revision.entity_id.clone()),
                revision,
            );
        }
    }
    let mut out = SyncPullResponse {
        server_time: now_ms,
        hub_id,
        sync_epoch,
        next_cursor,
        has_more,
        reset,
        ..Default::default()
    };
    for ((table, id), revision) in selected {
        // A page may contain an older delete followed by a later recreation. Always send current state.
        let latest = sqlx::query_as::<_, Revision>(
            "SELECT seq, classroom_id, student_id, entity_table, entity_id, action FROM sync_revisions
             WHERE entity_table = ? AND entity_id = ?
             AND (student_id IS NULL OR student_id = ? OR ? = 'TEACHER') ORDER BY seq DESC LIMIT 1",
        ).bind(&table).bind(&id).bind(&user.id).bind(user.role.as_str()).fetch_one(&mut *conn).await?;
        // A copy addressed to this user still counts when a later class-wide revision of the same
        // row is one they can't see: a removed co-teacher must learn they left the class.
        if !latest.visible(user, &classes) && revision.student_id.as_deref() != Some(user.id.as_str()) {
            continue;
        }
        if !matches!(
            table.as_str(),
            "classrooms"
                | "enrollments"
                | "topics"
                | "announcements"
                | "announcement_comments"
                | "private_comments"
                | "materials"
                | "material_chunks"
                | "assignments"
                | "assignment_submissions"
                | "quizzes"
                | "quiz_attempts"
        ) {
            continue;
        }
        if latest.action == "DELETE" {
            out.deleted.push(Tombstone {
                entity_table: table,
                entity_id: id,
            });
        } else {
            load_entity(conn, user, &table, &id, &mut out).await?;
        }
    }
    out.users = public_users(conn, user).await?;
    Ok(out)
}

async fn public_users(
    conn: &mut SqliteConnection,
    user: &AuthUser,
) -> Result<Vec<PublicUser>, sqlx::Error> {
    sqlx::query_as(
        "WITH visible AS (SELECT c.id FROM classrooms c WHERE
          (? = 'TEACHER' AND (c.teacher_id = ? OR EXISTS (SELECT 1 FROM classroom_teachers ct
             WHERE ct.classroom_id = c.id AND ct.teacher_id = ?))) OR
          (? = 'STUDENT' AND EXISTS (SELECT 1 FROM enrollments e WHERE e.classroom_id = c.id
             AND e.student_id = ? AND e.status = 'ACTIVE'))),
         people AS (SELECT ? AS id UNION SELECT teacher_id FROM classrooms WHERE id IN (SELECT id FROM visible)
          UNION SELECT teacher_id FROM classroom_teachers WHERE classroom_id IN (SELECT id FROM visible)
          UNION SELECT student_id FROM enrollments WHERE classroom_id IN (SELECT id FROM visible) AND status = 'ACTIVE')
         SELECT u.id, u.full_name, u.role, CASE WHEN u.id = ? OR (? = 'TEACHER' AND EXISTS (
          SELECT 1 FROM enrollments e WHERE e.student_id = u.id AND e.classroom_id IN (SELECT id FROM visible)
          AND e.status = 'ACTIVE')) THEN u.lrn_or_id END AS lrn_or_id
         FROM users u WHERE u.id IN (SELECT id FROM people) AND u.role <> 'ADMIN' ORDER BY u.id",
    ).bind(user.role.as_str()).bind(&user.id).bind(&user.id).bind(user.role.as_str()).bind(&user.id)
        .bind(&user.id).bind(&user.id).bind(user.role.as_str()).fetch_all(conn).await
}

async fn load_entity(
    conn: &mut SqliteConnection,
    user: &AuthUser,
    table: &str,
    id: &str,
    out: &mut SyncPullResponse,
) -> Result<(), sqlx::Error> {
    macro_rules! load {
        ($field:ident, $ty:ty, $sql:literal) => {
            if let Some(row) = sqlx::query_as::<_, $ty>($sql)
                .bind(id)
                .fetch_optional(&mut *conn)
                .await?
            {
                out.$field.push(row);
            }
        };
    }
    match table {
        "classrooms" => {
            if let Some(mut room) = sqlx::query_as::<_, Classroom>(
                "SELECT c.id, c.name, c.section, c.class_code, c.teacher_id, c.archived_at,
                 CASE WHEN ? = 'STUDENT' THEN e.status END AS enrollment_status
                 FROM classrooms c LEFT JOIN enrollments e ON e.classroom_id = c.id AND e.student_id = ? WHERE c.id = ?",
            ).bind(user.role.as_str()).bind(&user.id).bind(id).fetch_optional(&mut *conn).await? {
                room.co_teacher_ids = sqlx::query_scalar("SELECT teacher_id FROM classroom_teachers WHERE classroom_id = ? ORDER BY teacher_id")
                    .bind(id).fetch_all(&mut *conn).await?;
                out.classrooms.push(room);
            }
        }
        "enrollments" => {
            if let Some(row) = sqlx::query_as::<_, Enrollment>(
                "SELECT id, classroom_id, student_id, status, joined_at, updated_at FROM enrollments
                 WHERE id = ? AND (? = 'TEACHER' OR student_id = ?)",
            ).bind(id).bind(user.role.as_str()).bind(&user.id).fetch_optional(&mut *conn).await? { out.enrollments.push(row); }
        }
        "topics" => load!(topics, Topic, "SELECT id, classroom_id, name, order_index, updated_at FROM topics WHERE id = ?"),
        "announcements" => load!(announcements, Announcement, "SELECT id, classroom_id, title, content, allow_comments, created_at, updated_at FROM announcements WHERE id = ?"),
        "announcement_comments" => load!(comments, Comment, "SELECT id, announcement_id, author_id, content, created_at, updated_at FROM announcement_comments WHERE id = ?"),
        "materials" => load!(materials, Material, "SELECT id, classroom_id, title, file_type, file_size_bytes, '/api/materials/' || id || '/download' AS download_url, topic_id, assignment_id, announcement_id, updated_at FROM materials WHERE id = ?"),
        "material_chunks" => load!(material_chunks, MaterialChunk, "SELECT id, material_id, order_index, heading, text FROM material_chunks WHERE id = ?"),
        "assignments" => load!(assignments, Assignment, "SELECT id, classroom_id, title, instructions, max_points, due_date, allow_late, topic_id, updated_at FROM assignments WHERE id = ? AND archived_at IS NULL"),
        "private_comments" => {
            if let Some(row) = sqlx::query_as::<_, PrivateComment>(
                "SELECT id, assignment_id, student_id, author_id, content, created_at, updated_at FROM private_comments
                 WHERE id = ? AND (? = 'TEACHER' OR student_id = ?)",
            ).bind(id).bind(user.role.as_str()).bind(&user.id).fetch_optional(&mut *conn).await? { out.private_comments.push(row); }
        }
        "assignment_submissions" => {
            if let Some(row) = sqlx::query_as::<_, Submission>(
                "SELECT id, assignment_id, student_id, file_type, submitted_at, score, teacher_feedback, updated_at
                 FROM assignment_submissions WHERE id = ? AND (? = 'TEACHER' OR student_id = ?)",
            ).bind(id).bind(user.role.as_str()).bind(&user.id).fetch_optional(&mut *conn).await? { out.submissions.push(row); }
        }
        "quizzes" => {
            if let Some(row) = sqlx::query_as::<_, QuizMetadata>(
                "SELECT id, classroom_id, title, status, time_limit_minutes, started_at FROM quizzes
                 WHERE id = ? AND (status <> 'DRAFT' OR ? = 'TEACHER')",
            ).bind(id).bind(user.role.as_str()).fetch_optional(&mut *conn).await? { out.quizzes.push(row); }
        }
        "quiz_attempts" => {
            if let Some(row) = sqlx::query_as::<_, GradeReceipt>(
                "SELECT a.id AS attempt_id, a.quiz_id, a.student_id, a.total_points, a.submitted_at,
                 CASE WHEN ? = 'TEACHER' OR q.release_scores_immediately = 1 OR q.status = 'CLOSED'
                 THEN a.score END AS score FROM quiz_attempts a JOIN quizzes q ON q.id = a.quiz_id
                 WHERE a.id = ? AND a.status = 'SUBMITTED' AND (? = 'TEACHER' OR a.student_id = ?)",
            ).bind(user.role.as_str()).bind(id).bind(user.role.as_str()).bind(&user.id)
                .fetch_optional(&mut *conn).await? { out.quiz_attempts.push(row); }
        }
        _ => {}
    }
    Ok(())
}

fn receipt(id: &str, reason: Option<&'static str>) -> SyncReceipt {
    SyncReceipt {
        id: id.into(),
        status: if reason.is_some() {
            "REJECTED"
        } else {
            "SYNCED"
        },
        reason,
    }
}

/// Checked after the retry check in each push, so work saved before the archive still gets its receipt.
async fn class_archived(conn: &mut SqliteConnection, classroom_id: &str) -> Result<bool, sqlx::Error> {
    let archived_at: Option<i64> = sqlx::query_scalar("SELECT archived_at FROM classrooms WHERE id = ?")
        .bind(classroom_id)
        .fetch_one(conn)
        .await?;
    Ok(archived_at.is_some())
}

pub async fn push_comment(
    conn: &mut SqliteConnection,
    student_id: &str,
    comment: &CommentPush,
    now: i64,
) -> Result<SyncReceipt, sqlx::Error> {
    let announcement: Option<(String, bool)> = sqlx::query_as(
        "SELECT a.classroom_id, a.allow_comments FROM announcements a JOIN enrollments e
         ON e.classroom_id = a.classroom_id WHERE a.id = ? AND e.student_id = ? AND e.status = 'ACTIVE'",
    ).bind(&comment.announcement_id).bind(student_id).fetch_optional(&mut *conn).await?;
    let existing: Option<(String, String, String, i64)> = sqlx::query_as(
        "SELECT author_id, announcement_id, content, created_at FROM announcement_comments WHERE id = ?",
    ).bind(&comment.id).fetch_optional(&mut *conn).await?;
    if existing
        .as_ref()
        .is_some_and(|e| e.0 != student_id || e.1 != comment.announcement_id)
        || announcement.is_none()
    {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    }
    if existing
        .as_ref()
        .is_some_and(|e| e.2 == comment.content && e.3 == comment.created_at)
    {
        return Ok(receipt(&comment.id, None));
    }
    let Some((classroom_id, allow_comments)) = announcement else {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    };
    if class_archived(conn, &classroom_id).await? {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    }
    if !allow_comments {
        return Ok(receipt(&comment.id, Some("COMMENTS_DISABLED")));
    }
    sqlx::query(
        "INSERT INTO announcement_comments (id, announcement_id, author_id, content, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at",
    ).bind(&comment.id).bind(&comment.announcement_id).bind(student_id).bind(&comment.content).bind(comment.created_at).bind(now)
        .execute(&mut *conn).await?;
    record_revision(
        conn,
        Scope::class(&classroom_id),
        "announcement_comments",
        &comment.id,
        Action::Upsert,
        now,
    )
    .await?;
    Ok(receipt(&comment.id, None))
}

pub async fn push_private_comment(
    conn: &mut SqliteConnection,
    student_id: &str,
    comment: &PrivateCommentPush,
    now: i64,
) -> Result<SyncReceipt, sqlx::Error> {
    let classroom_id: Option<String> = sqlx::query_scalar(
        "SELECT a.classroom_id FROM assignments a JOIN enrollments e ON e.classroom_id = a.classroom_id
         WHERE a.id = ? AND a.archived_at IS NULL AND e.student_id = ? AND e.status = 'ACTIVE'",
    ).bind(&comment.assignment_id).bind(student_id).fetch_optional(&mut *conn).await?;
    let existing: Option<(String, String, String, String, i64)> = sqlx::query_as(
        "SELECT author_id, student_id, assignment_id, content, created_at FROM private_comments WHERE id = ?",
    ).bind(&comment.id).fetch_optional(&mut *conn).await?;
    if existing
        .as_ref()
        .is_some_and(|e| e.0 != student_id || e.1 != student_id || e.2 != comment.assignment_id)
    {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    }
    let Some(classroom_id) = classroom_id else {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    };
    if existing
        .as_ref()
        .is_some_and(|e| e.3 == comment.content && e.4 == comment.created_at)
    {
        return Ok(receipt(&comment.id, None));
    }
    if class_archived(conn, &classroom_id).await? {
        return Ok(receipt(&comment.id, Some("FORBIDDEN")));
    }
    sqlx::query(
        "INSERT INTO private_comments (id, assignment_id, student_id, author_id, content, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET content = excluded.content, updated_at = excluded.updated_at",
    ).bind(&comment.id).bind(&comment.assignment_id).bind(student_id).bind(student_id).bind(&comment.content)
        .bind(comment.created_at).bind(now).execute(&mut *conn).await?;
    record_revision(
        conn,
        Scope::student(&classroom_id, student_id),
        "private_comments",
        &comment.id,
        Action::Upsert,
        now,
    )
    .await?;
    Ok(receipt(&comment.id, None))
}

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
