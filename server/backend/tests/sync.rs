mod common;

use common::TestHub;
use lara_hub::auth::{AuthUser, Role};
use lara_hub::db::{
    self,
    sync_revisions::{self, record_revision, Action, Scope},
};
use lara_hub::models::sync::SyncPullInput;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::time::{Duration, Instant};

struct Class {
    hub: TestHub,
    id: String,
    code: String,
    student: String,
    token: String,
}

impl Class {
    async fn start() -> Self {
        let hub = TestHub::start().await;
        let (status, room) = hub
            .post_json(
                "/api/classrooms",
                &json!({"name":"Science 4", "section":"A"}),
                Some(hub.teacher_token()),
            )
            .await;
        assert_eq!(status, 200, "{room}");
        let learner = hub
            .seed_user("123456789012", "Juan", Role::Student, "1234")
            .await;
        let student = learner.id.clone();
        let token = hub.open_session(learner).await;
        let class = Self {
            hub,
            id: room["id"].as_str().unwrap().into(),
            code: room["class_code"].as_str().unwrap().into(),
            student,
            token,
        };
        class.enroll(&class.token, &class.student).await;
        class
    }

    async fn enroll(&self, token: &str, student: &str) {
        assert_eq!(
            self.hub
                .post_json(
                    "/api/classrooms/join",
                    &json!({"class_code": self.code}),
                    Some(token)
                )
                .await
                .0,
            200
        );
        assert_eq!(
            self.hub
                .post_json(
                    &format!("/api/classrooms/{}/approve", self.id),
                    &json!({"student_id":student}),
                    Some(self.hub.teacher_token())
                )
                .await
                .0,
            200
        );
    }

    async fn announcements(&self, count: usize, timestamp: i64) {
        self.announcements_from(0, count, timestamp).await;
    }

    async fn announcements_from(&self, first: usize, count: usize, timestamp: i64) {
        let mut tx = db::begin_immediate(&self.hub.state().db).await.unwrap();
        for n in first..first + count {
            let id = format!("post-{n}");
            sqlx::query("INSERT INTO announcements (id, classroom_id, title, content, created_at, updated_at) VALUES (?, ?, 'Lesson', 'Plants', ?, ?)")
                .bind(&id).bind(&self.id).bind(timestamp).bind(timestamp).execute(&mut *tx).await.unwrap();
            record_revision(
                &mut tx,
                Scope::class(&self.id),
                "announcements",
                &id,
                Action::Upsert,
                timestamp,
            )
            .await
            .unwrap();
        }
        tx.commit().await.unwrap();
    }

    async fn assignment(&self) {
        let mut tx = db::begin_immediate(&self.hub.state().db).await.unwrap();
        sqlx::query("INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES ('assignment', ?, 'Homework', 100, 1, 1)")
            .bind(&self.id).execute(&mut *tx).await.unwrap();
        record_revision(
            &mut tx,
            Scope::class(&self.id),
            "assignments",
            "assignment",
            Action::Upsert,
            1,
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }
}

async fn pull(hub: &TestHub, token: &str, cursor: i64) -> Value {
    let (status, body) = hub
        .post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(token))
        .await;
    assert_eq!(status, 200, "{body}");
    body
}

fn cursor(body: &Value) -> i64 {
    body["next_cursor"].as_i64().unwrap()
}

fn comment(id: &str) -> Value {
    json!({"id":id, "announcement_id":"post-0", "content":"My draft", "created_at":10})
}

fn private(id: &str) -> Value {
    json!({"id":id, "assignment_id":"assignment", "content":"Help with step one", "created_at":10})
}

#[tokio::test]
async fn first_pull_reset_and_epoch_use_the_committed_database_identity() {
    let class = Class::start().await;
    class.announcements(1, 100).await;
    let first = pull(&class.hub, &class.token, 0).await;
    assert_eq!(first["reset"], false);
    assert_eq!(first["has_more"], false);
    assert_eq!(first["announcements"].as_array().unwrap().len(), 1);
    assert_eq!(first["classrooms"][0]["enrollment_status"], "ACTIVE");
    assert!(first["server_time"].is_i64());
    let request = json!({"cursor":cursor(&first), "hub_id":first["hub_id"], "sync_epoch":first["sync_epoch"]});
    let (status, empty) = class
        .hub
        .post_json("/api/sync/pull", &request, Some(&class.token))
        .await;
    assert_eq!(status, 200);
    assert_eq!(empty["reset"], false);
    assert_eq!(empty["announcements"], json!([]));
    for wrong in [json!({"hub_id":"other-school"}), json!({"sync_epoch":-1})] {
        let mut request = request.clone();
        request
            .as_object_mut()
            .unwrap()
            .extend(wrong.as_object().unwrap().clone());
        let (status, reset) = class
            .hub
            .post_json("/api/sync/pull", &request, Some(&class.token))
            .await;
        assert_eq!(status, 200);
        assert_eq!(reset["reset"], true);
        assert_eq!(reset["announcements"], first["announcements"]);
    }
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sync_revisions::bump_sync_epoch(&mut tx).await.unwrap();
    tx.rollback().await.unwrap();
    assert_eq!(
        pull(&class.hub, &class.token, 0).await["sync_epoch"],
        first["sync_epoch"]
    );
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sync_revisions::bump_sync_epoch(&mut tx).await.unwrap();
    tx.commit().await.unwrap();
    let (status, reset) = class
        .hub
        .post_json("/api/sync/pull", &request, Some(&class.token))
        .await;
    assert_eq!(status, 200);
    assert_eq!(reset["reset"], true);
    assert_eq!(
        reset["sync_epoch"],
        first["sync_epoch"].as_i64().unwrap() + 1
    );
    class.hub.stop().await;
}

#[tokio::test]
async fn cursor_never_misses_same_millisecond_backwards_clock_or_late_commit() {
    let class = Class::start().await;
    let first = pull(&class.hub, &class.token, 0).await;
    class.announcements(1, 100).await;
    let user = AuthUser {
        id: class.student.clone(),
        role: Role::Student,
    };
    let input = |cursor| SyncPullInput {
        cursor,
        hub_id: None,
        sync_epoch: None,
    };
    let mut reader = class.hub.state().db.begin().await.unwrap();
    let same = sync_revisions::pull_page(&mut reader, &user, &input(cursor(&first)), 100)
        .await
        .unwrap();
    assert_eq!(same.announcements.len(), 1);
    let mut writer = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query("UPDATE announcements SET content = 'Clock moved backwards', updated_at = 10 WHERE id = 'post-0'")
        .execute(&mut *writer).await.unwrap();
    record_revision(
        &mut writer,
        Scope::class(&class.id),
        "announcements",
        "post-0",
        Action::Upsert,
        10,
    )
    .await
    .unwrap();
    // A pull during the uncommitted write cannot advance past it.
    let during = pull(&class.hub, &class.token, same.next_cursor).await;
    assert_eq!(during["announcements"], json!([]));
    writer.commit().await.unwrap();
    let unchanged = sync_revisions::pull_page(&mut reader, &user, &input(same.next_cursor), 10)
        .await
        .unwrap();
    assert!(
        unchanged.announcements.is_empty(),
        "the read transaction retains its snapshot"
    );
    reader.commit().await.unwrap();
    let mut reader = class.hub.state().db.begin().await.unwrap();
    let after = sync_revisions::pull_page(&mut reader, &user, &input(cursor(&during)), 10)
        .await
        .unwrap();
    assert_eq!(after.announcements[0].content, "Clock moved backwards");
    assert!(after.next_cursor > same.next_cursor);
    reader.commit().await.unwrap();
    class.hub.stop().await;
}

#[tokio::test]
async fn reconnect_pulls_1200_revisions_in_three_pages_without_gaps() {
    let class = Class::start().await;
    let mut position = cursor(&pull(&class.hub, &class.token, 0).await);
    class.announcements(1200, 100).await;
    let mut ids = HashSet::new();
    for (expected, more) in [(500, true), (500, true), (200, false)] {
        let started = Instant::now();
        let page = pull(&class.hub, &class.token, position).await;
        let elapsed = started.elapsed();
        eprintln!("reconnect pull: {expected} records in {elapsed:?}");
        assert!(
            elapsed < Duration::from_secs(1),
            "reconnect pull took {elapsed:?}"
        );
        let rows = page["announcements"].as_array().unwrap();
        assert_eq!(rows.len(), expected);
        for row in rows {
            assert!(ids.insert(row["id"].as_str().unwrap().to_string()));
        }
        assert_eq!(page["has_more"], more);
        assert!(cursor(&page) > position);
        position = cursor(&page);
    }
    assert_eq!(ids.len(), 1200);
    assert_eq!(
        pull(&class.hub, &class.token, position).await["announcements"],
        json!([])
    );
    class.hub.stop().await;
}

#[tokio::test]
async fn newly_visible_class_arrives_with_older_rows_for_learner_and_co_teacher_once() {
    let class = Class::start().await;
    class.announcements(620, 100).await;
    let ana = class
        .hub
        .seed_user("123456789013", "Ana", Role::Student, "1234")
        .await;
    let ana_id = ana.id.clone();
    let ana_token = class.hub.open_session(ana).await;
    let teacher = class
        .hub
        .seed_user("T-0002", "Jose", Role::Teacher, "1234")
        .await;
    let teacher_id = teacher.id.clone();
    let teacher_token = class.hub.open_session(teacher).await;
    let mut position = 0;
    loop {
        let before = pull(&class.hub, &ana_token, position).await;
        assert_eq!(before["announcements"], json!([]));
        position = cursor(&before);
        if before["has_more"] == false {
            break;
        }
    }
    class.enroll(&ana_token, &ana_id).await;
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query(
        "INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at) VALUES (?, ?, 1)",
    )
    .bind(&class.id)
    .bind(&teacher_id)
    .execute(&mut *tx)
    .await
    .unwrap();
    record_revision(
        &mut tx,
        Scope::student(&class.id, &teacher_id),
        "classrooms",
        &class.id,
        Action::Upsert,
        1,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    for token in [&ana_token, &teacher_token] {
        let after = pull(&class.hub, token, position).await;
        assert_eq!(after["announcements"].as_array().unwrap().len(), 620);
        assert_eq!(after["classrooms"][0]["id"], class.id);
        assert_eq!(
            pull(&class.hub, token, cursor(&after)).await["announcements"],
            json!([])
        );
    }
    // Removal is still delivered even after class access is gone.
    let before = pull(&class.hub, &ana_token, 0).await;
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    let enrollment_id: String = sqlx::query_scalar(
        "UPDATE enrollments SET status = 'REMOVED' WHERE student_id = ? RETURNING id",
    )
    .bind(&ana_id)
    .fetch_one(&mut *tx)
    .await
    .unwrap();
    record_revision(
        &mut tx,
        Scope::student(&class.id, &ana_id),
        "enrollments",
        &enrollment_id,
        Action::Upsert,
        2,
    )
    .await
    .unwrap();
    sqlx::query("DELETE FROM classroom_teachers WHERE teacher_id = ?")
        .bind(&teacher_id)
        .execute(&mut *tx)
        .await
        .unwrap();
    record_revision(
        &mut tx,
        Scope::student(&class.id, &teacher_id),
        "classrooms",
        &class.id,
        Action::Upsert,
        2,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    let removed = pull(&class.hub, &ana_token, cursor(&before)).await;
    assert_eq!(removed["enrollments"][0]["status"], "REMOVED");
    assert_eq!(removed["announcements"], json!([]));
    let removed_teacher = pull(&class.hub, &teacher_token, cursor(&before)).await;
    assert_eq!(
        removed_teacher["classrooms"][0]["co_teacher_ids"],
        json!([])
    );
    assert_eq!(removed_teacher["announcements"], json!([]));
    class.hub.stop().await;
}

#[tokio::test]
async fn push_failure_keeps_earlier_items_and_retry_does_not_duplicate_them() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    class.assignment().await;
    sqlx::query("CREATE TRIGGER fail_third BEFORE INSERT ON sync_revisions WHEN NEW.entity_id = 'third' BEGIN SELECT RAISE(ABORT, 'injected revision failure'); END")
        .execute(&class.hub.state().db).await.unwrap();
    let payload = json!({"comments":[comment("first"),comment("second")], "private_comments":[private("third")]});
    let (status, error) = class
        .hub
        .post_json("/api/sync/push", &payload, Some(&class.token))
        .await;
    assert_eq!(status, 500, "{error}");
    assert_eq!(error["error"]["code"], "INTERNAL_ERROR");
    let counts: (i64, i64, i64) = sqlx::query_as("SELECT (SELECT count(*) FROM announcement_comments), (SELECT count(*) FROM private_comments), (SELECT count(*) FROM sync_revisions WHERE entity_id IN ('first','second','third'))")
        .fetch_one(&class.hub.state().db).await.unwrap();
    assert_eq!(counts, (2, 0, 2));
    sqlx::query("DROP TRIGGER fail_third")
        .execute(&class.hub.state().db)
        .await
        .unwrap();
    let (status, response) = class
        .hub
        .post_json("/api/sync/push", &payload, Some(&class.token))
        .await;
    assert_eq!(status, 200, "{response}");
    assert_eq!(
        response["receipts"],
        json!([{"id":"first","status":"SYNCED"},{"id":"second","status":"SYNCED"},{"id":"third","status":"SYNCED"}])
    );
    let ledger: i64 = sqlx::query_scalar(
        "SELECT count(*) FROM sync_revisions WHERE entity_id IN ('first','second','third')",
    )
    .fetch_one(&class.hub.state().db)
    .await
    .unwrap();
    assert_eq!(ledger, 3);
    let private_scope: (String, String) = sqlx::query_as(
        "SELECT classroom_id, student_id FROM sync_revisions WHERE entity_id = 'third'",
    )
    .fetch_one(&class.hub.state().db)
    .await
    .unwrap();
    assert_eq!(private_scope, (class.id.clone(), class.student.clone()));
    class.hub.stop().await;
}

#[tokio::test]
async fn push_validation_ownership_and_quiz_attempts_stay_separate() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    class.assignment().await;
    let payload = json!({"comments":[comment("mine")],"private_comments":[private("private-mine")],"quiz_attempts":[{"id":"unprocessed"}]});
    let (status, accepted) = class
        .hub
        .post_json("/api/sync/push", &payload, Some(&class.token))
        .await;
    assert_eq!(status, 200);
    assert_eq!(
        accepted["receipts"].as_array().unwrap().len(),
        2,
        "no quiz receipt until SERVER 4.2"
    );
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM quiz_attempts")
        .fetch_one(&class.hub.state().db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    let ana = class
        .hub
        .seed_user("123456789013", "Ana", Role::Student, "1234")
        .await;
    let ana_id = ana.id.clone();
    let ana_token = class.hub.open_session(ana).await;
    for token in [&ana_token, &class.token] {
        if token == &class.token {
            sqlx::query("UPDATE announcements SET allow_comments = 0")
                .execute(&class.hub.state().db)
                .await
                .unwrap();
        }
        let (status, rejected) = class
            .hub
            .post_json(
                "/api/sync/push",
                &json!({"comments":[comment("new")]}),
                Some(token),
            )
            .await;
        assert_eq!(status, 200);
        assert_eq!(
            rejected["receipts"][0]["reason"],
            if token == &class.token {
                "COMMENTS_DISABLED"
            } else {
                "FORBIDDEN"
            }
        );
    }
    class.enroll(&ana_token, &ana_id).await;
    let (_, rejected) = class
        .hub
        .post_json("/api/sync/push", &payload, Some(&ana_token))
        .await;
    assert!(rejected["receipts"]
        .as_array()
        .unwrap()
        .iter()
        .all(|r| r["reason"] == "FORBIDDEN"));
    assert_eq!(
        pull(&class.hub, &ana_token, 0).await["private_comments"],
        json!([])
    );
    assert_eq!(
        pull(&class.hub, &class.token, 0).await["private_comments"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        pull(&class.hub, class.hub.teacher_token(), 0).await["private_comments"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    sqlx::query("UPDATE assignments SET archived_at = 100")
        .execute(&class.hub.state().db)
        .await
        .unwrap();
    let (_, rejected) = class
        .hub
        .post_json(
            "/api/sync/push",
            &json!({"private_comments":[private("archived")]}),
            Some(&class.token),
        )
        .await;
    assert_eq!(rejected["receipts"][0]["reason"], "FORBIDDEN");
    assert_eq!(
        class
            .hub
            .post_json("/api/sync/push", &payload, Some(class.hub.teacher_token()))
            .await
            .0,
        403
    );
    class.hub.stop().await;
}

#[tokio::test]
async fn tombstones_latest_state_and_invisible_pages_advance_the_sequence() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    let before = pull(&class.hub, &class.token, 0).await;
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query("DELETE FROM announcements WHERE id = 'post-0'")
        .execute(&mut *tx)
        .await
        .unwrap();
    record_revision(
        &mut tx,
        Scope::class(&class.id),
        "announcements",
        "post-0",
        Action::Delete,
        1,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    let deleted = pull(&class.hub, &class.token, cursor(&before)).await;
    assert_eq!(
        deleted["deleted"],
        json!([{"entity_table":"announcements","entity_id":"post-0"}])
    );
    class.announcements(1, 0).await;
    let latest = pull(&class.hub, &class.token, cursor(&before)).await;
    assert_eq!(latest["deleted"], json!([]));
    assert_eq!(latest["announcements"].as_array().unwrap().len(), 1);
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    for n in 0..501 {
        record_revision(
            &mut tx,
            Scope::class("foreign-class"),
            "announcements",
            &format!("foreign-{n}"),
            Action::Delete,
            0,
        )
        .await
        .unwrap();
    }
    tx.commit().await.unwrap();
    let invisible = pull(&class.hub, &class.token, cursor(&latest)).await;
    assert_eq!(invisible["announcements"], json!([]));
    assert_eq!(invisible["deleted"], json!([]));
    assert_eq!(invisible["has_more"], true);
    assert_eq!(cursor(&invisible), cursor(&latest) + 500);
    class.hub.stop().await;
}

#[tokio::test]
async fn request_validation_and_auth_follow_the_contract() {
    let class = Class::start().await;
    for body in [
        json!({}),
        json!({"cursor":-1}),
        json!({"cursor":"100"}),
        json!({"cursor":1.5}),
    ] {
        let (status, error) = class
            .hub
            .post_json("/api/sync/pull", &body, Some(&class.token))
            .await;
        assert_eq!(status, 400, "{error}");
        assert_eq!(error["error"]["code"], "BAD_REQUEST");
    }
    for path in ["/api/sync/pull", "/api/sync/push"] {
        assert_eq!(
            class
                .hub
                .post_json(path, &json!({"cursor":0}), None)
                .await
                .0,
            401
        );
    }
    let (status, error) = class
        .hub
        .post_json(
            "/api/sync/push",
            &json!({"comments":[{"id":"incomplete"}]}),
            Some(&class.token),
        )
        .await;
    assert_eq!(status, 400, "{error}");
    class.hub.stop().await;
}

#[tokio::test]
async fn pull_redacts_secrets_and_scopes_every_supported_entity() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    class.assignment().await;
    let ana = class
        .hub
        .seed_user("123456789013", "Ana", Role::Student, "1234")
        .await;
    let ana_id = ana.id.clone();
    let ana_token = class.hub.open_session(ana).await;
    class.enroll(&ana_token, &ana_id).await;
    let admin = class
        .hub
        .seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "1234")
        .await;
    let admin_token = class.hub.open_session(admin).await;
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query("INSERT INTO topics (id, classroom_id, name, created_at, updated_at) VALUES ('topic', ?, 'Plants', 1, 1)")
        .bind(&class.id).execute(&mut *tx).await.unwrap();
    record_revision(
        &mut tx,
        Scope::class(&class.id),
        "topics",
        "topic",
        Action::Upsert,
        1,
    )
    .await
    .unwrap();
    sqlx::query("INSERT INTO materials (id, classroom_id, title, file_type, file_path, file_size_bytes, created_at, updated_at) VALUES ('material', ?, 'Lesson', 'DOCUMENT', 'secret-disk-path', 12, 1, 1)")
        .bind(&class.id).execute(&mut *tx).await.unwrap();
    record_revision(
        &mut tx,
        Scope::class(&class.id),
        "materials",
        "material",
        Action::Upsert,
        1,
    )
    .await
    .unwrap();
    sqlx::query("INSERT INTO material_chunks (id, material_id, order_index, text, updated_at) VALUES ('chunk', 'material', 0, 'Plants need light', 1)")
        .execute(&mut *tx).await.unwrap();
    record_revision(
        &mut tx,
        Scope::class(&class.id),
        "material_chunks",
        "chunk",
        Action::Upsert,
        1,
    )
    .await
    .unwrap();
    for (id, status) in [("quiz", "ACTIVE"), ("draft", "DRAFT")] {
        sqlx::query("INSERT INTO quizzes (id, classroom_id, title, time_limit_minutes, release_scores_immediately, status, created_at, updated_at) VALUES (?, ?, 'Quiz', 10, 0, ?, 1, 1)")
            .bind(id).bind(&class.id).bind(status).execute(&mut *tx).await.unwrap();
        record_revision(
            &mut tx,
            Scope::class(&class.id),
            "quizzes",
            id,
            Action::Upsert,
            1,
        )
        .await
        .unwrap();
    }
    sqlx::query("INSERT INTO quiz_questions (id, quiz_id, order_index, question_text, question_type, correct_answer, synonyms_json, created_at, updated_at) VALUES ('question', 'quiz', 0, 'What process?', 'IDENTIFICATION', 'SECRET-ANSWER', '[\"SECRET-SYNONYM\"]', 1, 1)")
        .execute(&mut *tx).await.unwrap();
    for (sid, suffix) in [(&class.student, "mine"), (&ana_id, "ana")] {
        let submission = format!("submission-{suffix}");
        sqlx::query("INSERT INTO assignment_submissions (id, assignment_id, student_id, file_path, submitted_at, score, updated_at) VALUES (?, 'assignment', ?, 'secret-disk-path', 1, 8, 1)")
            .bind(&submission).bind(sid).execute(&mut *tx).await.unwrap();
        record_revision(
            &mut tx,
            Scope::student(&class.id, sid),
            "assignment_submissions",
            &submission,
            Action::Upsert,
            1,
        )
        .await
        .unwrap();
        let attempt = format!("attempt-{suffix}");
        sqlx::query("INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, submitted_at, score, total_points, updated_at) VALUES (?, 'quiz', ?, 'SUBMITTED', 1, 2, 1, 1, 2)")
            .bind(&attempt).bind(sid).execute(&mut *tx).await.unwrap();
        record_revision(
            &mut tx,
            Scope::student(&class.id, sid),
            "quiz_attempts",
            &attempt,
            Action::Upsert,
            2,
        )
        .await
        .unwrap();
    }
    tx.commit().await.unwrap();
    class
        .hub
        .post_json(
            "/api/sync/push",
            &json!({"comments":[comment("stream")], "private_comments":[private("thread")]}),
            Some(&class.token),
        )
        .await;
    let student = pull(&class.hub, &class.token, 0).await;
    let teacher = pull(&class.hub, class.hub.teacher_token(), 0).await;
    let classmate = pull(&class.hub, &ana_token, 0).await;
    for payload in [&student, &teacher, &classmate] {
        let text = payload.to_string();
        for forbidden in [
            "pin_hash",
            "correct_answer",
            "synonyms",
            "secret-disk-path",
            "SECRET-ANSWER",
            "SECRET-SYNONYM",
            "file_path",
            "Hub Admin",
        ] {
            assert!(!text.contains(forbidden), "leaked {forbidden}: {text}");
        }
    }
    assert!(!student.to_string().contains("123456789013"));
    assert!(!classmate.to_string().contains("123456789012"));
    assert!(teacher.to_string().contains("123456789012"));
    assert_eq!(
        student["materials"][0]["download_url"],
        "/api/materials/material/download"
    );
    for key in [
        "topics",
        "material_chunks",
        "assignments",
        "comments",
        "private_comments",
        "submissions",
        "quizzes",
        "quiz_attempts",
    ] {
        assert_eq!(student[key].as_array().unwrap().len(), 1, "{key}");
    }
    assert_eq!(student["quiz_attempts"][0]["score"], Value::Null);
    assert_eq!(student["quiz_attempts"][0]["quiz_id"], "quiz");
    assert_eq!(student["quiz_attempts"][0]["student_id"], class.student);
    assert_eq!(teacher["quiz_attempts"].as_array().unwrap().len(), 2);
    assert_eq!(teacher["quizzes"].as_array().unwrap().len(), 2);
    assert_eq!(teacher["quiz_attempts"][0]["score"], 1);
    assert_eq!(classmate["private_comments"], json!([]));
    assert_eq!(classmate["submissions"][0]["id"], "submission-ana");
    assert_eq!(pull(&class.hub, &admin_token, 0).await["users"], json!([]));
    let mut tx = db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query("UPDATE quizzes SET status = 'CLOSED' WHERE id = 'quiz'")
        .execute(&mut *tx)
        .await
        .unwrap();
    record_revision(
        &mut tx,
        Scope::student(&class.id, &class.student),
        "quiz_attempts",
        "attempt-mine",
        Action::Upsert,
        2,
    )
    .await
    .unwrap();
    tx.commit().await.unwrap();
    assert_eq!(
        pull(&class.hub, &class.token, cursor(&student)).await["quiz_attempts"][0]["score"],
        1
    );
    class.hub.stop().await;
}

// ---- #121: class management seen through sync, using the real routes ----

impl Class {
    async fn patch(&self, body: Value) -> u16 {
        self.hub
            .send_json(
                reqwest::Method::PATCH,
                &format!("/api/classrooms/{}", self.id),
                &body,
                Some(self.hub.teacher_token()),
            )
            .await
            .0
    }

    async fn add_co_teacher(&self, lrn: &str) -> (String, String) {
        let teacher = self.hub.seed_user(lrn, "Jose", Role::Teacher, "1234").await;
        let id = teacher.id.clone();
        let token = self.hub.open_session(teacher).await;
        let (status, body) = self
            .hub
            .post_json(
                &format!("/api/classrooms/{}/teachers", self.id),
                &json!({"lrn_or_id": lrn}),
                Some(self.hub.teacher_token()),
            )
            .await;
        assert_eq!(status, 200, "{body}");
        (id, token)
    }

    async fn drop_co_teacher(&self, id: &str) {
        let (status, body) = self
            .hub
            .request(
                reqwest::Method::DELETE,
                &format!("/api/classrooms/{}/teachers/{id}", self.id),
                Some(self.hub.teacher_token()),
            )
            .await;
        assert_eq!(status, 200, "{body}");
    }
}

async fn drain(hub: &TestHub, token: &str) -> i64 {
    let mut position = 0;
    loop {
        let page = pull(hub, token, position).await;
        position = cursor(&page);
        if page["has_more"] == false {
            return position;
        }
    }
}

#[tokio::test]
async fn learners_pull_a_rename_and_an_archive_and_pulling_still_works_after() {
    let class = Class::start().await;
    let position = drain(&class.hub, &class.token).await;
    assert_eq!(class.patch(json!({"name": "Science 4A"})).await, 200);
    let renamed = pull(&class.hub, &class.token, position).await;
    assert_eq!(renamed["classrooms"][0]["name"], "Science 4A");
    assert_eq!(class.patch(json!({"archived": true})).await, 200);
    let archived = pull(&class.hub, &class.token, cursor(&renamed)).await;
    assert!(archived["classrooms"][0]["archived_at"].is_i64());
    class.announcements(1, 5).await;
    let later = pull(&class.hub, &class.token, cursor(&archived)).await;
    assert_eq!(later["announcements"].as_array().unwrap().len(), 1, "a pull from an archived class still works");
    class.hub.stop().await;
}

#[tokio::test]
async fn a_learner_removed_through_the_route_gets_the_enrollment_and_nothing_else() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    let position = drain(&class.hub, &class.token).await;
    let (status, body) = class
        .hub
        .post_json(
            &format!("/api/classrooms/{}/remove", class.id),
            &json!({"student_id": class.student}),
            Some(class.hub.teacher_token()),
        )
        .await;
    assert_eq!(status, 200, "{body}");
    class.announcements_from(1, 1, 2).await;
    let removed = pull(&class.hub, &class.token, position).await;
    assert_eq!(removed["enrollments"][0]["status"], "REMOVED");
    assert_eq!(removed["announcements"], json!([]));
    let fresh = pull(&class.hub, &class.token, 0).await;
    assert_eq!(fresh["enrollments"][0]["status"], "REMOVED");
    assert_eq!(fresh["announcements"], json!([]), "a device starting over gets none of the class");
    class.hub.stop().await;
}

#[tokio::test]
async fn an_added_co_teacher_gets_the_older_rows_and_the_learners_see_them_listed() {
    let class = Class::start().await;
    class.announcements(3, 1).await;
    let teacher = class.hub.seed_user("T-0002", "Jose", Role::Teacher, "1234").await;
    let teacher_token = class.hub.open_session(teacher.clone()).await;
    let position = drain(&class.hub, &teacher_token).await;
    let learner_position = drain(&class.hub, &class.token).await;
    let (status, _) = class
        .hub
        .post_json(
            &format!("/api/classrooms/{}/teachers", class.id),
            &json!({"lrn_or_id": "T-0002"}),
            Some(class.hub.teacher_token()),
        )
        .await;
    assert_eq!(status, 200);
    let pulled = pull(&class.hub, &teacher_token, position).await;
    assert_eq!(pulled["announcements"].as_array().unwrap().len(), 3);
    assert_eq!(pulled["classrooms"][0]["co_teacher_ids"], json!([teacher.id]));
    let learner = pull(&class.hub, &class.token, learner_position).await;
    assert_eq!(learner["classrooms"][0]["co_teacher_ids"], json!([teacher.id]));
    class.hub.stop().await;
}

/// The removed teacher was offline while the class kept changing: their copy of the removal must
/// survive later class-wide revisions of the same row.
#[tokio::test]
async fn a_removed_co_teacher_learns_it_even_after_the_class_changes_again() {
    let class = Class::start().await;
    let (teacher_id, teacher_token) = class.add_co_teacher("T-0002").await;
    let position = drain(&class.hub, &teacher_token).await;
    class.drop_co_teacher(&teacher_id).await;
    assert_eq!(class.patch(json!({"name": "Science 4A"})).await, 200);
    class.announcements(1, 9).await;

    let pulled = pull(&class.hub, &teacher_token, position).await;
    assert_eq!(pulled["classrooms"].as_array().unwrap().len(), 1);
    assert_eq!(pulled["classrooms"][0]["co_teacher_ids"], json!([]));
    assert_eq!(pulled["announcements"], json!([]));

    let later = pull(&class.hub, &teacher_token, cursor(&pulled)).await;
    assert_eq!(later["classrooms"], json!([]), "only the copy addressed to them, once");

    let fresh = pull(&class.hub, &teacher_token, 0).await;
    assert_eq!(fresh["classrooms"][0]["co_teacher_ids"], json!([]));
    assert_eq!(fresh["announcements"], json!([]));

    let learner = pull(&class.hub, &class.token, position).await;
    assert_eq!(learner["classrooms"][0]["co_teacher_ids"], json!([]), "learners see the list change too");
    class.hub.stop().await;
}

#[tokio::test]
async fn a_co_teacher_added_back_gets_the_class_again() {
    let class = Class::start().await;
    class.announcements(2, 1).await;
    let (teacher_id, teacher_token) = class.add_co_teacher("T-0002").await;
    let position = drain(&class.hub, &teacher_token).await;
    class.drop_co_teacher(&teacher_id).await;
    let (status, _) = class
        .hub
        .post_json(
            &format!("/api/classrooms/{}/teachers", class.id),
            &json!({"lrn_or_id": "T-0002"}),
            Some(class.hub.teacher_token()),
        )
        .await;
    assert_eq!(status, 200);
    let pulled = pull(&class.hub, &teacher_token, position).await;
    assert_eq!(pulled["classrooms"][0]["co_teacher_ids"], json!([teacher_id]));
    assert_eq!(pulled["announcements"].as_array().unwrap().len(), 2);
    class.hub.stop().await;
}

#[tokio::test]
async fn pushes_to_an_archived_class_are_refused_but_a_retry_of_saved_work_is_not() {
    let class = Class::start().await;
    class.announcements(1, 1).await;
    class.assignment().await;
    let saved = json!({"comments":[comment("saved")],"private_comments":[private("saved-private")]});
    let (status, first) = class.hub.post_json("/api/sync/push", &saved, Some(&class.token)).await;
    assert_eq!(status, 200);
    assert_eq!(first["receipts"][0]["status"], "SYNCED");
    assert_eq!(first["receipts"][1]["status"], "SYNCED");
    assert_eq!(class.patch(json!({"archived": true})).await, 200);

    let (_, retry) = class.hub.post_json("/api/sync/push", &saved, Some(&class.token)).await;
    assert_eq!(retry["receipts"][0]["status"], "SYNCED", "the response was lost, the work was not");
    assert_eq!(retry["receipts"][1]["status"], "SYNCED");

    let new = json!({"comments":[comment("new")],"private_comments":[private("new-private")]});
    let (status, refused) = class.hub.post_json("/api/sync/push", &new, Some(&class.token)).await;
    assert_eq!(status, 200);
    for receipt in refused["receipts"].as_array().unwrap() {
        assert_eq!(receipt["status"], "REJECTED");
        assert_eq!(receipt["reason"], "FORBIDDEN");
    }
    let edited = json!({"comments":[{"id":"saved","announcement_id":"post-0","content":"Changed","created_at":10}]});
    let (_, refused) = class.hub.post_json("/api/sync/push", &edited, Some(&class.token)).await;
    assert_eq!(refused["receipts"][0]["reason"], "FORBIDDEN", "an edit is a write");
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM announcement_comments WHERE content = 'My draft'")
        .fetch_one(&class.hub.state().db)
        .await
        .unwrap();
    assert_eq!(count, 1);
    class.hub.stop().await;
}
