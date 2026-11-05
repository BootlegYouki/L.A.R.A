mod common;

use common::TestHub;
use futures_util::{SinkExt, StreamExt};
use lara_hub::auth::Role;
use lara_hub::db::{self, classrooms};
use lara_hub::models::user::User;
use lara_hub::websocket::broker;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_tungstenite::{tungstenite::Message, MaybeTlsStream, WebSocketStream};

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

async fn next(ws: &mut Socket) -> Value {
    loop {
        let frame = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .unwrap()
            .unwrap()
            .unwrap();
        if let Message::Text(text) = frame {
            return serde_json::from_str(text.as_str()).unwrap();
        }
    }
}

async fn connect(hub: &TestHub, token: &str) -> Socket {
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(Message::text(
        json!({"event": "EVENT_HELLO", "token": token}).to_string(),
    ))
    .await
    .unwrap();
    assert_eq!(next(&mut ws).await["event"], "EVENT_HELLO_ACK");
    ws
}

async fn create(hub: &TestHub) -> Value {
    let (status, room) = hub
        .post_json(
            "/api/classrooms",
            &json!({"name": "Science 4", "section": "Aguinaldo"}),
            Some(hub.teacher_token()),
        )
        .await;
    assert_eq!(status, 200, "{room}");
    room
}

async fn learner(hub: &TestHub, lrn: &str) -> (User, String) {
    let user = hub.seed_user(lrn, "Ana Reyes", Role::Student, "1234").await;
    let token = hub.open_session(user.clone()).await;
    (user, token)
}

async fn join(hub: &TestHub, room: &Value, token: &str) -> (u16, Value) {
    hub.post_json(
        "/api/classrooms/join",
        &json!({"class_code": room["class_code"]}),
        Some(token),
    )
    .await
}

async fn decide(hub: &TestHub, room: &Value, student_id: &str, action: &str, token: &str) -> (u16, Value) {
    hub.post_json(
        &format!("/api/classrooms/{}/{action}", room["id"].as_str().unwrap()),
        &json!({"student_id": student_id}),
        Some(token),
    )
    .await
}

async fn revisions(hub: &TestHub) -> i64 {
    sqlx::query_scalar("SELECT count(*) FROM sync_revisions")
        .fetch_one(&hub.state().db)
        .await
        .unwrap()
}

async fn enrollment(hub: &TestHub, student_id: &str) -> (String, String, i64, i64) {
    sqlx::query_as("SELECT id, status, joined_at, updated_at FROM enrollments WHERE student_id = ?")
        .bind(student_id)
        .fetch_one(&hub.state().db)
        .await
        .unwrap()
}

fn assert_private(payload: &Value, other_lrn: &str) {
    let json = payload.to_string();
    for secret in ["pin_hash", "correct_answer", "synonyms", "file_path", other_lrn] {
        assert!(!json.contains(secret), "learner payload contains {secret}");
    }
}

#[tokio::test]
async fn create_generates_valid_unique_codes_and_scoped_revisions() {
    let hub = TestHub::start().await;
    let owner: String = sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = 'T-0001'")
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    let mut codes = HashSet::new();
    for _ in 0..32 {
        let room = create(&hub).await;
        let code = room["class_code"].as_str().unwrap();
        assert_eq!(code.len(), 6);
        assert!(code.bytes().all(|b| b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains(&b)));
        assert!(codes.insert(code.to_owned()));
        assert_eq!(room["teacher_id"], owner);
        assert!(uuid::Uuid::parse_str(room["id"].as_str().unwrap()).is_ok());
        assert!(room.get("enrollment_status").is_none());
        let (scope, student, action): (String, Option<String>, String) = sqlx::query_as(
            "SELECT classroom_id, student_id, action FROM sync_revisions WHERE entity_table = 'classrooms' AND entity_id = ?",
        ).bind(room["id"].as_str().unwrap()).fetch_one(&hub.state().db).await.unwrap();
        assert_eq!(scope, room["id"]);
        assert_eq!((student, action.as_str()), (None, "UPSERT"));
    }
    assert_eq!(
        hub.get("/api/classrooms", Some(hub.teacher_token()))
            .await
            .1
            .as_array()
            .unwrap()
            .len(),
        32
    );
    hub.stop().await;
}

#[tokio::test]
async fn code_collision_can_retry_without_replacing_a_class_or_writing_a_revision() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let before = revisions(&hub).await;
    let mut tx = db::begin_immediate(&hub.state().db).await.unwrap();
    let owner = room["teacher_id"].as_str().unwrap();
    assert!(
        classrooms::insert(&mut tx, owner, "Other", "B", room["class_code"].as_str().unwrap(), 10)
            .await
            .unwrap()
            .is_none()
    );
    let replacement = loop {
        let code = classrooms::new_class_code().unwrap();
        if let Some(room) = classrooms::insert(&mut tx, owner, "Other", "B", &code, 10)
            .await
            .unwrap()
        {
            break room;
        }
    };
    tx.commit().await.unwrap();
    assert_ne!(replacement.id, room["id"]);
    assert_eq!(revisions(&hub).await, before + 1);
    let name: String = sqlx::query_scalar("SELECT name FROM classrooms WHERE id = ?")
        .bind(room["id"].as_str().unwrap())
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(name, "Science 4");
    hub.stop().await;
}

#[tokio::test]
async fn join_approve_flow_pushes_websocket_events() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    let (_, other) = learner(&hub, "123456789012").await;
    let mut teacher_ws = connect(&hub, hub.teacher_token()).await;
    let mut learner_ws = connect(&hub, &token).await;
    let code = room["class_code"].as_str().unwrap();
    let grouped = format!("{}-{}", &code[..3], &code[3..]).to_lowercase();
    let (status, receipt) = hub
        .post_json("/api/classrooms/join", &json!({"class_code": grouped}), Some(&token))
        .await;
    assert_eq!((status, receipt["status"].as_str()), (200, Some("PENDING")));
    assert_eq!(receipt["classroom_id"], room["id"]);
    let request = next(&mut teacher_ws).await;
    assert_eq!(request["event"], "EVENT_JOIN_REQUEST");
    assert_eq!(request["student_id"], ana.id);
    assert_eq!(request["lrn"], "123456789013");
    assert_eq!(request["full_name"], "Ana Reyes");
    assert_eq!(request["class_code"], room["class_code"]);
    let roster_path = format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap());
    let (status, roster) = hub.get(&roster_path, Some(hub.teacher_token())).await;
    assert_eq!(status, 200);
    let row = &roster[0];
    assert_eq!(row["enrollment_id"], enrollment(&hub, &ana.id).await.0);
    assert_eq!(row["lrn"], "123456789013");
    assert_eq!(row["status"], "PENDING");
    assert_eq!(hub.get(&roster_path, Some(&other)).await.0, 403);
    assert_eq!(
        decide(&hub, &room, &ana.id, "approve", hub.teacher_token()).await,
        (200, json!({"success": true}))
    );
    let approval = next(&mut learner_ws).await;
    assert_eq!(approval["event"], "EVENT_JOIN_APPROVAL");
    assert_eq!(approval["status"], "ACTIVE");
    assert_eq!(approval["classroom_id"], room["id"]);
    assert_eq!(approval["student_id"], ana.id);
    let (_, mine) = hub.get("/api/classrooms", Some(&token)).await;
    assert_eq!(mine[0]["enrollment_status"], "ACTIVE");
    assert_eq!(hub.get("/api/classrooms", Some(&other)).await.1, json!([]));
    for payload in [&receipt, &approval, &mine] {
        assert_private(payload, "123456789012");
    }
    let scopes: Vec<(String, String)> = sqlx::query_as(
        "SELECT classroom_id, student_id FROM sync_revisions WHERE entity_table = 'enrollments' ORDER BY seq",
    )
    .fetch_all(&hub.state().db)
    .await
    .unwrap();
    assert_eq!(scopes, vec![(room["id"].as_str().unwrap().to_owned(), ana.id); 2]);
    teacher_ws.close(None).await.unwrap();
    learner_ws.close(None).await.unwrap();
    hub.stop().await;
}

#[tokio::test]
async fn decisions_retries_and_rejoins_preserve_the_enrollment_identity() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    let (tx, mut teacher_rx) = broker::channel();
    hub.state().broker.register(room["teacher_id"].as_str().unwrap(), tx);
    let (tx, mut learner_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    assert_eq!(join(&hub, &room, &token).await.0, 200);
    assert!(teacher_rx.try_recv().is_ok());
    let original = enrollment(&hub, &ana.id).await;
    let before = revisions(&hub).await;
    assert_eq!(join(&hub, &room, &token).await.1["status"], "PENDING");
    assert_eq!(enrollment(&hub, &ana.id).await, original);
    assert_eq!(revisions(&hub).await, before);
    assert!(teacher_rx.try_recv().is_err());

    for action in ["reject", "approve"] {
        assert_eq!(decide(&hub, &room, &ana.id, action, hub.teacher_token()).await.0, 200);
        let event: Value = serde_json::from_str(&learner_rx.try_recv().unwrap()).unwrap();
        assert_eq!(event["status"], if action == "approve" { "ACTIVE" } else { "REJECTED" });
        let finished = enrollment(&hub, &ana.id).await;
        let before = revisions(&hub).await;
        assert_eq!(decide(&hub, &room, &ana.id, action, hub.teacher_token()).await.0, 200);
        assert_eq!(enrollment(&hub, &ana.id).await, finished);
        assert_eq!(revisions(&hub).await, before);
        assert!(learner_rx.try_recv().is_err());
        let other = if action == "approve" { "reject" } else { "approve" };
        let (status, error) = decide(&hub, &room, &ana.id, other, hub.teacher_token()).await;
        assert_eq!((status, error["error"]["code"].as_str()), (404, Some("NOT_FOUND")));
        if action == "reject" {
            assert_eq!(hub.get("/api/classrooms", Some(&token)).await.1, json!([]));
            assert_eq!(join(&hub, &room, &token).await.1["status"], "PENDING");
            assert!(teacher_rx.try_recv().is_ok());
            let rejoined = enrollment(&hub, &ana.id).await;
            assert_eq!((&rejoined.0, rejoined.2), (&original.0, original.2));
        }
    }
    let before = revisions(&hub).await;
    assert_eq!(join(&hub, &room, &token).await.1["status"], "ACTIVE");
    assert_eq!(revisions(&hub).await, before);
    assert!(teacher_rx.try_recv().is_err());
    // Removal itself belongs to #121; seed its contracted state to verify this issue's rejoin.
    sqlx::query("UPDATE enrollments SET status = 'REMOVED' WHERE student_id = ?")
        .bind(&ana.id)
        .execute(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(hub.get("/api/classrooms", Some(&token)).await.1, json!([]));
    for action in ["approve", "reject"] {
        assert_eq!(decide(&hub, &room, &ana.id, action, hub.teacher_token()).await.0, 404);
    }
    assert_eq!(join(&hub, &room, &token).await.1["status"], "PENDING");
    assert!(teacher_rx.try_recv().is_ok());
    let rejoined = enrollment(&hub, &ana.id).await;
    assert_eq!((&rejoined.0, rejoined.2), (&original.0, original.2));
    hub.stop().await;
}

#[tokio::test]
async fn co_teachers_can_list_read_roster_and_decide_but_foreign_teachers_cannot() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let co = hub.seed_user("T-co", "Co Teacher", Role::Teacher, "1234").await;
    let foreign = hub.seed_user("T-other", "Other Teacher", Role::Teacher, "1234").await;
    let co_id = co.id.clone();
    let co_token = hub.open_session(co).await;
    let foreign_token = hub.open_session(foreign).await;
    sqlx::query("INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at) VALUES (?, ?, 1)")
        .bind(room["id"].as_str().unwrap())
        .bind(&co_id)
        .execute(&hub.state().db)
        .await
        .unwrap();
    let (tx, mut co_rx) = broker::channel();
    hub.state().broker.register(&co_id, tx);
    let (tx, mut foreign_rx) = broker::channel();
    let foreign_id: String = sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = 'T-other'")
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    hub.state().broker.register(&foreign_id, tx);
    let (ana, token) = learner(&hub, "123456789013").await;
    let (tx, mut ana_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    assert_eq!(join(&hub, &room, &token).await.0, 200);
    assert!(co_rx.try_recv().is_ok());
    assert!(foreign_rx.try_recv().is_err());
    assert!(
        ana_rx.try_recv().is_err(),
        "teacher-only LRN event must not reach learners"
    );
    let (_, rooms) = hub.get("/api/classrooms", Some(&co_token)).await;
    assert_eq!(rooms[0]["id"], room["id"]);
    assert_eq!(rooms[0]["co_teacher_ids"], json!([co_id]));
    assert!(rooms[0].get("enrollment_status").is_none());
    assert_eq!(hub.get("/api/classrooms", Some(&foreign_token)).await.1, json!([]));
    let roster = format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap());
    assert_eq!(hub.get(&roster, Some(&co_token)).await.0, 200);
    assert_eq!(hub.get(&roster, Some(&foreign_token)).await.0, 403);
    for action in ["approve", "reject"] {
        assert_eq!(decide(&hub, &room, &ana.id, action, &foreign_token).await.0, 403);
    }
    assert_eq!(decide(&hub, &room, &ana.id, "reject", &co_token).await.0, 200);
    assert_eq!(join(&hub, &room, &token).await.0, 200);
    assert_eq!(decide(&hub, &room, &ana.id, "approve", &co_token).await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn archived_class_refuses_joins_and_decisions_without_changes_or_events() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    join(&hub, &room, &token).await;
    let (tx, mut teacher_rx) = broker::channel();
    hub.state().broker.register(room["teacher_id"].as_str().unwrap(), tx);
    let (tx, mut learner_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    sqlx::query("UPDATE classrooms SET archived_at = 1 WHERE id = ?")
        .bind(room["id"].as_str().unwrap())
        .execute(&hub.state().db)
        .await
        .unwrap();
    let before = revisions(&hub).await;
    let original = enrollment(&hub, &ana.id).await;
    assert_eq!(join(&hub, &room, &token).await.0, 403);
    for action in ["approve", "reject"] {
        let (status, body) = decide(&hub, &room, &ana.id, action, hub.teacher_token()).await;
        assert_eq!((status, body["error"]["code"].as_str()), (403, Some("FORBIDDEN")));
    }
    assert_eq!(revisions(&hub).await, before);
    assert_eq!(enrollment(&hub, &ana.id).await, original);
    assert!(teacher_rx.try_recv().is_err());
    assert!(learner_rx.try_recv().is_err());
    assert_eq!(
        hub.get(
            &format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap()),
            Some(hub.teacher_token())
        )
        .await
        .0,
        200
    );
    hub.stop().await;
}

#[tokio::test]
async fn missing_codes_classes_and_enrollments_use_contract_errors() {
    let hub = TestHub::start().await;
    let (_, token) = learner(&hub, "123456789013").await;
    for body in [
        json!({"class_code": "ZZZZZZ"}),
        json!({}),
        json!({"class_code": "O0I1AA"}),
    ] {
        let (status, error) = hub.post_json("/api/classrooms/join", &body, Some(&token)).await;
        assert_eq!(
            (status, error["error"]["code"].as_str()),
            (404, Some("CLASS_CODE_INVALID"))
        );
    }
    let room = create(&hub).await;
    for action in ["approve", "reject"] {
        assert_eq!(decide(&hub, &room, "missing", action, hub.teacher_token()).await.0, 404);
    }
    assert_eq!(
        hub.get("/api/classrooms/missing/roster", Some(hub.teacher_token()))
            .await
            .0,
        404
    );
    for body in [
        json!({}),
        json!({"name": null, "section": "A"}),
        json!({"name": "Science", "section": []}),
    ] {
        assert_eq!(
            hub.post_json("/api/classrooms", &body, Some(hub.teacher_token()))
                .await
                .0,
            400
        );
    }
    hub.stop().await;
}

#[tokio::test]
async fn concurrent_join_and_approval_retries_write_and_notify_once() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    let (tx, mut teacher_rx) = broker::channel();
    hub.state().broker.register(room["teacher_id"].as_str().unwrap(), tx);
    let (tx, mut learner_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    let before = revisions(&hub).await;
    let (a, b) = tokio::join!(join(&hub, &room, &token), join(&hub, &room, &token));
    assert_eq!((a.0, b.0), (200, 200));
    assert_eq!(a.1, b.1);
    assert_eq!(revisions(&hub).await, before + 1);
    assert!(teacher_rx.try_recv().is_ok());
    assert!(teacher_rx.try_recv().is_err());
    let (a, b) = tokio::join!(
        decide(&hub, &room, &ana.id, "approve", hub.teacher_token()),
        decide(&hub, &room, &ana.id, "approve", hub.teacher_token()),
    );
    assert_eq!((a.0, b.0), (200, 200));
    assert_eq!(revisions(&hub).await, before + 2);
    assert!(learner_rx.try_recv().is_ok());
    assert!(learner_rx.try_recv().is_err());
    hub.stop().await;
}

#[tokio::test]
async fn revision_failure_rolls_back_create_join_and_approval_without_events() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    let (tx, mut teacher_rx) = broker::channel();
    hub.state().broker.register(room["teacher_id"].as_str().unwrap(), tx);
    let (tx, mut learner_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    sqlx::query("CREATE TRIGGER fail_revision BEFORE INSERT ON sync_revisions BEGIN SELECT RAISE(ABORT, 'test'); END")
        .execute(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(
        hub.post_json(
            "/api/classrooms",
            &json!({"name": "Other", "section": "B"}),
            Some(hub.teacher_token())
        )
        .await
        .0,
        500
    );
    assert_eq!(
        hub.get("/api/classrooms", Some(hub.teacher_token()))
            .await
            .1
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(join(&hub, &room, &token).await.0, 500);
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM enrollments")
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(count, 0);
    assert!(teacher_rx.try_recv().is_err());
    sqlx::query("DROP TRIGGER fail_revision")
        .execute(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(join(&hub, &room, &token).await.0, 200);
    assert!(teacher_rx.try_recv().is_ok());
    sqlx::query("CREATE TRIGGER fail_revision BEFORE INSERT ON sync_revisions BEGIN SELECT RAISE(ABORT, 'test'); END")
        .execute(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(
        decide(&hub, &room, &ana.id, "approve", hub.teacher_token()).await.0,
        500
    );
    assert_eq!(enrollment(&hub, &ana.id).await.1, "PENDING");
    assert!(learner_rx.try_recv().is_err());
    hub.stop().await;
}

#[tokio::test]
async fn disconnected_devices_recover_enrollment_after_hub_restart() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = learner(&hub, "123456789013").await;
    assert_eq!(join(&hub, &room, &token).await.1["status"], "PENDING");
    let original = enrollment(&hub, &ana.id).await;
    let hub = hub.restart().await;
    assert_eq!(enrollment(&hub, &ana.id).await, original);
    let roster_path = format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap());
    let (status, roster) = hub.get(&roster_path, Some(hub.teacher_token())).await;
    assert_eq!(status, 200);
    assert_eq!(roster[0]["enrollment_id"], original.0);
    assert_eq!(roster[0]["status"], "PENDING");
    assert_eq!(
        decide(&hub, &room, &ana.id, "approve", hub.teacher_token()).await.0,
        200
    );
    let hub = hub.restart().await;
    let (status, rooms) = hub.get("/api/classrooms", Some(&token)).await;
    assert_eq!(status, 200);
    assert_eq!(rooms[0]["enrollment_status"], "ACTIVE");
    let url = format!("http://127.0.0.1:{}/api/classrooms", hub.http_port());
    hub.stop().await;
    let response = reqwest::Client::new().get(url).bearer_auth(token).send().await;
    assert!(response.is_err(), "the stopped Hub must actually be unreachable");
}

// ---- #121: rename, archive, remove a learner, co-teachers ----

async fn patch(hub: &TestHub, room: &Value, body: Value, token: &str) -> (u16, Value) {
    hub.send_json(
        reqwest::Method::PATCH,
        &format!("/api/classrooms/{}", room["id"].as_str().unwrap()),
        &body,
        Some(token),
    )
    .await
}

async fn add_teacher(hub: &TestHub, room: &Value, lrn: &str, token: &str) -> (u16, Value) {
    hub.post_json(
        &format!("/api/classrooms/{}/teachers", room["id"].as_str().unwrap()),
        &json!({"lrn_or_id": lrn}),
        Some(token),
    )
    .await
}

async fn drop_teacher(hub: &TestHub, room: &Value, teacher_id: &str, token: &str) -> (u16, Value) {
    hub.request(
        reqwest::Method::DELETE,
        &format!("/api/classrooms/{}/teachers/{teacher_id}", room["id"].as_str().unwrap()),
        Some(token),
    )
    .await
}

async fn teacher(hub: &TestHub, id: &str) -> (User, String) {
    let user = hub.seed_user(id, "Jose Rizal", Role::Teacher, "1234").await;
    let token = hub.open_session(user.clone()).await;
    (user, token)
}

async fn active_learner(hub: &TestHub, room: &Value, lrn: &str) -> (User, String) {
    let (user, token) = learner(hub, lrn).await;
    assert_eq!(join(hub, room, &token).await.0, 200);
    assert_eq!(decide(hub, room, &user.id, "approve", hub.teacher_token()).await.0, 200);
    (user, token)
}

async fn classroom_revisions(hub: &TestHub) -> Vec<(Option<String>, String)> {
    sqlx::query_as("SELECT student_id, action FROM sync_revisions WHERE entity_table = 'classrooms' ORDER BY seq")
        .fetch_all(&hub.state().db)
        .await
        .unwrap()
}

#[tokio::test]
async fn owner_renames_a_class_and_each_change_writes_one_class_scoped_revision() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (status, updated) = patch(&hub, &room, json!({"name": "Science 4A"}), hub.teacher_token()).await;
    assert_eq!(status, 200, "{updated}");
    assert_eq!(updated["id"], room["id"]);
    assert_eq!(updated["name"], "Science 4A");
    assert_eq!(updated["section"], "Aguinaldo", "a field not sent stays as it was");
    assert_eq!(updated["class_code"], room["class_code"]);
    assert_eq!(updated["archived_at"], Value::Null);
    assert_eq!(updated["co_teacher_ids"], json!([]));
    let (_, updated) = patch(&hub, &room, json!({"section": "Bonifacio"}), hub.teacher_token()).await;
    assert_eq!((updated["name"].as_str(), updated["section"].as_str()), (Some("Science 4A"), Some("Bonifacio")));
    assert_eq!(classroom_revisions(&hub).await, vec![(None, "UPSERT".to_owned()); 3], "create and two renames");

    let before = revisions(&hub).await;
    for body in [json!({}), json!({"name": "Science 4A"}), json!({"archived": false})] {
        assert_eq!(patch(&hub, &room, body, hub.teacher_token()).await.0, 200);
    }
    assert_eq!(revisions(&hub).await, before, "nothing changed, so nothing to sync");

    let (_, listed) = hub.get("/api/classrooms", Some(hub.teacher_token())).await;
    assert_eq!(listed[0]["name"], "Science 4A");
    hub.stop().await;
}

#[tokio::test]
async fn patch_refuses_wrong_types_without_changing_the_class() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let before = revisions(&hub).await;
    for body in [json!({"name": null}), json!({"section": 4}), json!({"archived": "yes"}), json!({"name": "New", "archived": 1})] {
        assert_eq!(patch(&hub, &room, body, hub.teacher_token()).await.0, 400);
    }
    assert_eq!(revisions(&hub).await, before);
    let (_, listed) = hub.get("/api/classrooms", Some(hub.teacher_token())).await;
    assert_eq!(listed[0]["name"], "Science 4");
    hub.stop().await;
}

#[tokio::test]
async fn an_archived_class_is_read_only_until_the_owner_clears_it() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (_, ana_token) = active_learner(&hub, &room, "123456789013").await;
    let (ben, ben_token) = learner(&hub, "123456789014").await;
    assert_eq!(join(&hub, &room, &ben_token).await.0, 200);

    let (status, archived) = patch(&hub, &room, json!({"name": "Science 4A", "archived": true}), hub.teacher_token()).await;
    assert_eq!(status, 200, "{archived}");
    assert_eq!(archived["name"], "Science 4A", "rename and archive can go together");
    let archived_at = archived["archived_at"].as_i64().expect("archived_at is set");

    let before = revisions(&hub).await;
    let (status, body) = patch(&hub, &room, json!({"name": "Other"}), hub.teacher_token()).await;
    assert_eq!((status, body["error"]["code"].as_str()), (403, Some("FORBIDDEN")));
    let (status, _) = patch(&hub, &room, json!({"name": "Other", "archived": true}), hub.teacher_token()).await;
    assert_eq!(status, 403, "staying archived does not make a rename allowed");
    assert_eq!(join(&hub, &room, &ben_token).await.0, 403);
    assert_eq!(decide(&hub, &room, &ben.id, "approve", hub.teacher_token()).await.0, 403);
    let (status, retry) = patch(&hub, &room, json!({"archived": true}), hub.teacher_token()).await;
    assert_eq!((status, retry["archived_at"].as_i64()), (200, Some(archived_at)), "a retry keeps the first time");
    assert_eq!(revisions(&hub).await, before);

    let (_, listed) = hub.get("/api/classrooms", Some(&ana_token)).await;
    assert_eq!(listed[0]["archived_at"].as_i64(), Some(archived_at), "reads still work, for learners too");
    let roster = format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap());
    assert_eq!(hub.get(&roster, Some(hub.teacher_token())).await.0, 200);

    let (status, cleared) = patch(&hub, &room, json!({"name": "Science 5", "archived": false}), hub.teacher_token()).await;
    assert_eq!(status, 200, "{cleared}");
    assert_eq!(cleared["archived_at"], Value::Null);
    assert_eq!(cleared["name"], "Science 5");
    assert_eq!(decide(&hub, &room, &ben.id, "approve", hub.teacher_token()).await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn rename_archive_and_teacher_management_are_owner_only() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (co, co_token) = teacher(&hub, "T-0002").await;
    let (other, other_token) = teacher(&hub, "T-0003").await;
    let (_, ana_token) = active_learner(&hub, &room, "123456789013").await;
    assert_eq!(add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await.0, 200);
    let before = revisions(&hub).await;
    for token in [co_token.as_str(), other_token.as_str(), ana_token.as_str()] {
        for body in [json!({"name": "Mine"}), json!({"archived": true})] {
            let (status, error) = patch(&hub, &room, body, token).await;
            assert_eq!((status, error["error"]["code"].as_str()), (403, Some("FORBIDDEN")));
        }
        assert_eq!(add_teacher(&hub, &room, "T-0003", token).await.0, 403);
        assert_eq!(drop_teacher(&hub, &room, &co.id, token).await.0, 403);
    }
    assert_eq!(revisions(&hub).await, before);
    assert_eq!(other.role, Role::Teacher);
    let missing = json!({"id": "missing"});
    assert_eq!(patch(&hub, &missing, json!({"name": "x"}), hub.teacher_token()).await.0, 404);
    assert_eq!(add_teacher(&hub, &missing, "T-0002", hub.teacher_token()).await.0, 404);
    assert_eq!(drop_teacher(&hub, &missing, &co.id, hub.teacher_token()).await.0, 404);
    hub.stop().await;
}

#[tokio::test]
async fn removing_a_learner_keeps_their_work_and_sends_the_event_once() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let room_id = room["id"].as_str().unwrap();
    let (ana, token) = active_learner(&hub, &room, "123456789013").await;
    let mut ana_ws = connect(&hub, &token).await;
    sqlx::query("INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES ('hw', ?, 'Homework', 100, 1, 1)")
        .bind(room_id)
        .execute(&hub.state().db)
        .await
        .unwrap();
    sqlx::query("INSERT INTO assignment_submissions (id, assignment_id, student_id, file_path, submitted_at, score, updated_at) VALUES ('sub', 'hw', ?, 'x.jpg', 2, 9, 2)")
        .bind(&ana.id)
        .execute(&hub.state().db)
        .await
        .unwrap();
    let original = enrollment(&hub, &ana.id).await;

    assert_eq!(
        decide(&hub, &room, &ana.id, "remove", hub.teacher_token()).await,
        (200, json!({"success": true}))
    );
    let event = next(&mut ana_ws).await;
    assert_eq!(event["event"], "EVENT_JOIN_APPROVAL");
    assert_eq!(event["status"], "REMOVED");
    assert_eq!(event["classroom_id"], room["id"]);
    assert_eq!(event["student_id"], ana.id);
    ana_ws.close(None).await.unwrap();

    let removed = enrollment(&hub, &ana.id).await;
    assert_eq!((&removed.0, removed.1.as_str(), removed.2), (&original.0, "REMOVED", original.2));
    assert_eq!(hub.get("/api/classrooms", Some(&token)).await.1, json!([]));
    let score: i64 = sqlx::query_scalar("SELECT score FROM assignment_submissions WHERE id = 'sub'")
        .fetch_one(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(score, 9, "no row is deleted");
    let (_, roster) = hub.get(&format!("/api/classrooms/{room_id}/roster"), Some(hub.teacher_token())).await;
    assert_eq!(roster[0]["status"], "REMOVED");

    let scope: (String, String) = sqlx::query_as(
        "SELECT classroom_id, student_id FROM sync_revisions WHERE entity_table = 'enrollments' ORDER BY seq DESC LIMIT 1",
    )
    .fetch_one(&hub.state().db)
    .await
    .unwrap();
    assert_eq!(scope, (room_id.to_owned(), ana.id.clone()));
    hub.stop().await;
}

#[tokio::test]
async fn remove_follows_the_contract_for_retries_other_states_and_archived_classes() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, _) = active_learner(&hub, &room, "123456789013").await;
    let (ben, ben_token) = learner(&hub, "123456789014").await;
    assert_eq!(join(&hub, &room, &ben_token).await.0, 200);
    let (tx, mut ana_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    let (co, co_token) = teacher(&hub, "T-0002").await;
    let (_, other_token) = teacher(&hub, "T-0003").await;
    assert_eq!(co.role, Role::Teacher);
    assert_eq!(add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await.0, 200);

    assert_eq!(decide(&hub, &room, &ana.id, "remove", &other_token).await.0, 403);
    assert_eq!(decide(&hub, &room, &ana.id, "remove", &ben_token).await.0, 403);
    let (status, error) = decide(&hub, &room, &ben.id, "remove", hub.teacher_token()).await;
    assert_eq!((status, error["error"]["code"].as_str()), (404, Some("NOT_FOUND")), "a PENDING learner is not removed");
    assert_eq!(decide(&hub, &room, "missing", "remove", hub.teacher_token()).await.0, 404);
    assert!(ana_rx.try_recv().is_err());

    assert_eq!(decide(&hub, &room, &ana.id, "remove", &co_token).await.0, 200, "a co-teacher can remove");
    assert!(ana_rx.try_recv().is_ok());
    let finished = enrollment(&hub, &ana.id).await;
    let before = revisions(&hub).await;
    assert_eq!(decide(&hub, &room, &ana.id, "remove", hub.teacher_token()).await.0, 200);
    assert_eq!(enrollment(&hub, &ana.id).await, finished);
    assert_eq!(revisions(&hub).await, before);
    assert!(ana_rx.try_recv().is_err(), "a retry sends nothing");
    assert_eq!(decide(&hub, &room, &ana.id, "approve", hub.teacher_token()).await.0, 404, "approve acts on PENDING only");

    assert_eq!(decide(&hub, &room, &ben.id, "approve", hub.teacher_token()).await.0, 200);
    assert_eq!(patch(&hub, &room, json!({"archived": true}), hub.teacher_token()).await.0, 200);
    let (status, error) = decide(&hub, &room, &ben.id, "remove", hub.teacher_token()).await;
    assert_eq!((status, error["error"]["code"].as_str()), (403, Some("FORBIDDEN")));
    assert_eq!(enrollment(&hub, &ben.id).await.1, "ACTIVE");
    hub.stop().await;
}

#[tokio::test]
async fn a_removed_learner_can_ask_to_join_again_on_the_same_enrollment() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (ana, token) = active_learner(&hub, &room, "123456789013").await;
    let original = enrollment(&hub, &ana.id).await;
    assert_eq!(decide(&hub, &room, &ana.id, "remove", hub.teacher_token()).await.0, 200);
    assert_eq!(join(&hub, &room, &token).await.1["status"], "PENDING");
    let rejoined = enrollment(&hub, &ana.id).await;
    assert_eq!((&rejoined.0, rejoined.2), (&original.0, original.2));
    hub.stop().await;
}

#[tokio::test]
async fn owner_adds_and_removes_co_teachers_by_id() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (co, co_token) = teacher(&hub, "T-0002").await;
    let (ana, ana_token) = learner(&hub, "123456789013").await;
    assert_eq!(hub.get("/api/classrooms", Some(&co_token)).await.1, json!([]));

    let (status, updated) = add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await;
    assert_eq!(status, 200, "{updated}");
    assert_eq!(updated["id"], room["id"]);
    assert_eq!(updated["co_teacher_ids"], json!([co.id]));
    let (_, listed) = hub.get("/api/classrooms", Some(&co_token)).await;
    assert_eq!(listed[0]["id"], room["id"]);

    assert_eq!(join(&hub, &room, &ana_token).await.0, 200);
    assert_eq!(decide(&hub, &room, &ana.id, "approve", &co_token).await.0, 200, "a co-teacher decides requests");

    let scopes = classroom_revisions(&hub).await;
    assert_eq!(
        scopes[1..],
        [(None, "UPSERT".to_owned()), (Some(co.id.clone()), "UPSERT".to_owned())],
        "the class first, then the copy addressed to the added teacher"
    );

    let before = revisions(&hub).await;
    let (status, again) = add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await;
    assert_eq!((status, again["co_teacher_ids"].clone()), (200, json!([co.id])));
    let (status, own) = add_teacher(&hub, &room, "T-0001", hub.teacher_token()).await;
    assert_eq!((status, own["co_teacher_ids"].clone()), (200, json!([co.id])), "the owner is not their own co-teacher");
    assert_eq!(revisions(&hub).await, before);

    let (status, error) = add_teacher(&hub, &room, "T-9999", hub.teacher_token()).await;
    assert_eq!((status, error["error"]["code"].as_str()), (404, Some("NOT_FOUND")));
    assert_eq!(add_teacher(&hub, &room, "123456789013", hub.teacher_token()).await.0, 404, "a learner is not a teacher");
    let path = format!("/api/classrooms/{}/teachers", room["id"].as_str().unwrap());
    assert_eq!(hub.post_json(&path, &json!({}), Some(hub.teacher_token())).await.0, 400);

    assert_eq!(
        drop_teacher(&hub, &room, &co.id, hub.teacher_token()).await,
        (200, json!({"success": true}))
    );
    assert_eq!(hub.get("/api/classrooms", Some(&co_token)).await.1, json!([]));
    let roster = format!("/api/classrooms/{}/roster", room["id"].as_str().unwrap());
    assert_eq!(hub.get(&roster, Some(&co_token)).await.0, 403);
    let scopes = classroom_revisions(&hub).await;
    assert_eq!(
        scopes[3..],
        [(None, "UPSERT".to_owned()), (Some(co.id.clone()), "UPSERT".to_owned())],
        "the class first, then the copy for the teacher who left"
    );

    let before = revisions(&hub).await;
    assert_eq!(drop_teacher(&hub, &room, &co.id, hub.teacher_token()).await.0, 200);
    assert_eq!(revisions(&hub).await, before, "a retry changes nothing");
    hub.stop().await;
}

#[tokio::test]
async fn a_deactivated_teacher_cannot_be_added() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (co, _) = teacher(&hub, "T-0002").await;
    sqlx::query("UPDATE users SET is_active = 0 WHERE id = ?")
        .bind(&co.id)
        .execute(&hub.state().db)
        .await
        .unwrap();
    assert_eq!(add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await.0, 404);
    hub.stop().await;
}

/// `tests/test_mock_hub.py` removes a co-teacher from a class it has just archived.
#[tokio::test]
async fn the_owner_can_still_manage_teachers_on_an_archived_class() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (co, _) = teacher(&hub, "T-0002").await;
    teacher(&hub, "T-0003").await;
    assert_eq!(add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await.0, 200);
    assert_eq!(patch(&hub, &room, json!({"archived": true}), hub.teacher_token()).await.0, 200);
    assert_eq!(drop_teacher(&hub, &room, &co.id, hub.teacher_token()).await.0, 200);
    assert_eq!(add_teacher(&hub, &room, "T-0003", hub.teacher_token()).await.0, 200);
    hub.stop().await;
}

#[tokio::test]
async fn a_failed_revision_rolls_back_every_management_change_without_events() {
    let hub = TestHub::start().await;
    let room = create(&hub).await;
    let (co, _) = teacher(&hub, "T-0002").await;
    teacher(&hub, "T-0003").await;
    let (ana, _) = active_learner(&hub, &room, "123456789013").await;
    assert_eq!(add_teacher(&hub, &room, "T-0002", hub.teacher_token()).await.0, 200);
    let (tx, mut ana_rx) = broker::channel();
    hub.state().broker.register(&ana.id, tx);
    sqlx::query("CREATE TRIGGER fail_revision BEFORE INSERT ON sync_revisions BEGIN SELECT RAISE(ABORT, 'test'); END")
        .execute(&hub.state().db)
        .await
        .unwrap();

    assert_eq!(patch(&hub, &room, json!({"name": "New", "archived": true}), hub.teacher_token()).await.0, 500);
    assert_eq!(decide(&hub, &room, &ana.id, "remove", hub.teacher_token()).await.0, 500);
    assert_eq!(add_teacher(&hub, &room, "T-0003", hub.teacher_token()).await.0, 500);
    assert_eq!(drop_teacher(&hub, &room, &co.id, hub.teacher_token()).await.0, 500);

    let (_, listed) = hub.get("/api/classrooms", Some(hub.teacher_token())).await;
    assert_eq!(listed[0]["name"], "Science 4");
    assert_eq!(listed[0]["archived_at"], Value::Null);
    assert_eq!(listed[0]["co_teacher_ids"], json!([co.id]));
    assert_eq!(enrollment(&hub, &ana.id).await.1, "ACTIVE");
    assert!(ana_rx.try_recv().is_err());
    hub.stop().await;
}
