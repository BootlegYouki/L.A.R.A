mod common;

use common::TestHub;
use futures_util::{SinkExt, StreamExt};
use lara_hub::auth::Role;
use lara_hub::websocket::{
    broker,
    events::{EnrollmentStatus, ServerEvent},
};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_tungstenite::{tungstenite::Message, MaybeTlsStream, WebSocketStream};

type Socket = WebSocketStream<MaybeTlsStream<TcpStream>>;

async fn next(ws: &mut Socket) -> Value {
    loop {
        let frame = tokio::time::timeout(Duration::from_secs(5), ws.next()).await.unwrap().unwrap().unwrap();
        if let Message::Text(text) = frame {
            return serde_json::from_str(text.as_str()).unwrap();
        }
    }
}

async fn connect(hub: &TestHub, token: &str) -> Socket {
    let (mut ws, _) = tokio_tungstenite::connect_async(hub.ws_url()).await.unwrap();
    ws.send(Message::text(json!({"event":"EVENT_HELLO", "token":token}).to_string())).await.unwrap();
    assert_eq!(next(&mut ws).await["event"], "EVENT_HELLO_ACK");
    ws
}

async fn classroom(hub: &TestHub, teacher: &str) {
    sqlx::query("INSERT INTO classrooms (id,name,section,class_code,teacher_id,created_at,updated_at) VALUES ('c','Science','A','K7M4QX',?,1,1)")
        .bind(teacher).execute(&hub.state().db).await.unwrap();
}

async fn enroll(hub: &TestHub, id: &str, student: &str, status: &str) {
    sqlx::query(
        "INSERT INTO enrollments (id,classroom_id,student_id,status,joined_at,created_at,updated_at) VALUES (?,'c',?,?,1,1,1)",
    )
    .bind(id)
    .bind(student)
    .bind(status)
    .execute(&hub.state().db)
    .await
    .unwrap();
}

#[tokio::test]
async fn targeted_push_uses_active_membership_and_all_class_teachers() {
    let hub = TestHub::start().await;
    let owner = hub.seed_user("T-owner", "Owner", Role::Teacher, "1234").await;
    let co = hub.seed_user("T-co", "Co", Role::Teacher, "1234").await;
    let foreign = hub.seed_user("T-other", "Other", Role::Teacher, "1234").await;
    classroom(&hub, &owner.id).await;
    for teacher in [&owner.id, &co.id] {
        sqlx::query("INSERT INTO classroom_teachers VALUES ('c',?,1,1,1)")
            .bind(teacher)
            .execute(&hub.state().db)
            .await
            .unwrap();
    }
    let mut receivers = Vec::new();
    for (lrn, status) in [
        ("123456789012", "ACTIVE"),
        ("123456789013", "PENDING"),
        ("123456789014", "REMOVED"),
        ("123456789015", "REJECTED"),
    ] {
        let student = hub.seed_user(lrn, "Learner", Role::Student, "1234").await;
        enroll(&hub, lrn, &student.id, status).await;
        let (tx, rx) = broker::channel();
        hub.state().broker.register(&student.id, tx);
        receivers.push(rx);
    }
    let mut teachers = Vec::new();
    for user in [&owner, &co, &foreign] {
        let (tx, rx) = broker::channel();
        hub.state().broker.register(&user.id, tx);
        teachers.push(rx);
    }
    let event = ServerEvent::AnnouncementPush {
        classroom_id: "c".into(),
        announcement_id: "a".into(),
        title: "Lesson".into(),
        timestamp: 1,
    };
    hub.state().broker.send_to_classroom_students(&hub.state().db, "c", &event).await.unwrap();
    assert!(receivers[0].try_recv().is_ok());
    for receiver in &mut receivers[1..] {
        assert!(receiver.try_recv().is_err());
    }
    let request = ServerEvent::JoinRequest {
        class_code: "K7M4QX".into(),
        student_id: "s".into(),
        full_name: "Learner".into(),
        lrn: "123456789013".into(),
        timestamp: 1,
    };
    hub.state().broker.send_to_teacher(&hub.state().db, "c", &request).await.unwrap();
    for receiver in &mut teachers[..2] {
        assert!(receiver.try_recv().is_ok());
        assert!(receiver.try_recv().is_err(), "owner must not receive twice");
    }
    assert!(teachers[2].try_recv().is_err());
    for receiver in &mut receivers {
        assert!(receiver.try_recv().is_err(), "teacher event leaked to learner");
    }
    hub.stop().await;
}

#[tokio::test]
async fn presence_tracks_multiple_devices_and_abrupt_disconnect() {
    let hub = TestHub::start().await;
    let teacher = hub.login("T-0001", "1234").await;
    // The seeded teacher's database id differs from its login id.
    let teacher_id: String =
        sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = 'T-0001'").fetch_one(&hub.state().db).await.unwrap();
    classroom(&hub, &teacher_id).await;
    let student = hub.seed_user("123456789012", "Learner", Role::Student, "1234").await;
    enroll(&hub, "e", &student.id, "ACTIVE").await;
    let student_id = student.id.clone();
    let token = hub.open_session(student).await;
    let mut teacher_ws = connect(&hub, &teacher).await;
    let mut phone = connect(&hub, &token).await;
    let online = next(&mut teacher_ws).await;
    assert_eq!(online["event"], "EVENT_PRESENCE");
    assert_eq!(online["state"], "ONLINE");
    assert_eq!(online["student_id"], student_id);
    assert!(online.get("lrn").is_none());
    let laptop = connect(&hub, &token).await;
    assert_eq!(next(&mut teacher_ws).await["state"], "ONLINE");
    phone.close(None).await.unwrap();
    assert!(tokio::time::timeout(Duration::from_millis(150), teacher_ws.next()).await.is_err());
    drop(laptop);
    assert_eq!(next(&mut teacher_ws).await["state"], "OFFLINE");
    let mut reconnected = connect(&hub, &token).await;
    assert_eq!(next(&mut teacher_ws).await["state"], "ONLINE");
    let event = ServerEvent::JoinApproval {
        classroom_id: "c".into(),
        student_id: student_id.clone(),
        status: EnrollmentStatus::Active,
        timestamp: 1,
    };
    hub.state().broker.send_to_user(&student_id, &event);
    assert_eq!(next(&mut reconnected).await["status"], "ACTIVE");
    assert!(tokio::time::timeout(Duration::from_millis(150), teacher_ws.next()).await.is_err());
    hub.stop().await;
}

#[tokio::test]
async fn reconnect_does_not_replay_missed_events() {
    let hub = TestHub::start().await;
    let user: String =
        sqlx::query_scalar("SELECT id FROM users WHERE lrn_or_id = 'T-0001'").fetch_one(&hub.state().db).await.unwrap();
    hub.state().broker.send_to_user(&user, &ServerEvent::error(lara_hub::error::ErrorCode::BadRequest, "missed"));
    let mut ws = connect(&hub, hub.teacher_token()).await;
    assert!(tokio::time::timeout(Duration::from_millis(150), ws.next()).await.is_err());
    hub.stop().await;
}
