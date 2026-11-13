//! Step 3.5 (#122): a private thread between one learner and the class teachers on an assignment
//! (PRD FR-3.8). Classmates never see it; comments written offline arrive through sync push (2.2).

mod common;

use common::uploads::Class;
use lara_hub::auth::Role;
use reqwest::Method;
use serde_json::{json, Value};

const PATH: &str = "/api/assignments/asg-1/private-comments";

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

/// The class with assignment `asg-1` and a second active learner, Ana: (class, ana_id, ana_token).
async fn class_with_assignment() -> (Class, String, String) {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    let (ana, ana_token) = class.add_learner("123456789013", true).await;
    (class, ana, ana_token)
}

async fn post(class: &Class, token: &str, body: Value) -> (u16, Value) {
    class.hub.post_json(PATH, &body, Some(token)).await
}

async fn thread(class: &Class, token: &str, student: Option<&str>) -> (u16, Value) {
    let path = student.map_or(PATH.to_owned(), |s| format!("{PATH}?student_id={s}"));
    class.hub.get(&path, Some(token)).await
}

#[tokio::test]
async fn private_comments_reach_only_that_learner_and_the_teacher() {
    let (class, _, ana_token) = class_with_assignment().await;
    let juan = class.learner.as_str();
    let (status, asked) = post(&class, &class.learner_token, json!({"content": "Hindi ko po maintindihan"})).await;
    assert_eq!(status, 200, "{asked}");
    assert_eq!((asked["student_id"].as_str(), asked["author_id"].as_str()), (Some(juan), Some(juan)));
    assert_eq!(asked["assignment_id"], "asg-1");
    assert!(uuid::Uuid::parse_str(asked["id"].as_str().unwrap()).is_ok());

    let (status, body) = thread(&class, class.teacher(), None).await;
    assert_eq!((status, message(&body)), (400, "Missing: student_id"), "a teacher must say whose thread");
    let (status, seen) = thread(&class, class.teacher(), Some(juan)).await;
    assert_eq!((status, seen), (200, json!([asked])));
    let (status, reply) = post(&class, class.teacher(), json!({"content": "Tingnan ang pahina 2", "student_id": juan})).await;
    assert_eq!(status, 200, "{reply}");
    assert_eq!(reply["student_id"], juan);
    assert_ne!(reply["author_id"], juan, "the teacher wrote it");

    assert_eq!(thread(&class, &class.learner_token, None).await, (200, json!([asked, reply])), "oldest first");
    assert_eq!(class.pull(&class.learner_token).await["private_comments"].as_array().unwrap().len(), 2);
    assert_eq!(class.pull(&ana_token).await["private_comments"], json!([]), "a classmate never receives the thread");
    assert_eq!(thread(&class, &ana_token, None).await, (200, json!([])), "Ana gets her own, empty thread");
    assert_eq!(thread(&class, &ana_token, Some(juan)).await, (200, json!([])), "and cannot ask for Juan's");
}

#[tokio::test]
async fn a_learner_cannot_write_into_a_classmates_thread() {
    let (class, ana, ana_token) = class_with_assignment().await;
    let (status, body) = post(&class, &ana_token, json!({"content": "Ako po ito", "student_id": class.learner})).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(body["student_id"], ana.as_str(), "a learner's student_id is ignored");
    assert_eq!(thread(&class, class.teacher(), Some(&class.learner)).await, (200, json!([])));
}

#[tokio::test]
async fn a_teacher_writes_only_to_a_learner_of_the_class() {
    let (class, _, _) = class_with_assignment().await;
    let stranger = class.hub.seed_user("123456789099", "Stranger", Role::Student, "1234").await;
    let (pending, _) = class.add_learner("123456789014", false).await;
    for student in [stranger.id.as_str(), "00000000-0000-4000-8000-000000000000", pending.as_str()] {
        let (status, body) = post(&class, class.teacher(), json!({"content": "x", "student_id": student})).await;
        assert_eq!((status, message(&body)), (404, "Learner not found in this class"), "{student}");
    }
    assert_eq!(thread(&class, class.teacher(), Some(&stranger.id)).await.0, 404);
    assert_eq!(thread(&class, class.teacher(), Some(&pending)).await, (200, json!([])), "a pending learner's thread is readable");

    post(&class, &class.learner_token, json!({"content": "Tanong po"})).await;
    let path = format!("/api/classrooms/{}/remove", class.id);
    let (status, body) = class.hub.post_json(&path, &json!({"student_id": class.learner}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    let (status, kept) = thread(&class, class.teacher(), Some(&class.learner)).await;
    assert_eq!((status, kept.as_array().map(Vec::len)), (200, Some(1)), "a removed learner's thread stays readable");
    let (status, _) = post(&class, class.teacher(), json!({"content": "x", "student_id": class.learner})).await;
    assert_eq!(status, 404, "but nobody writes into it");
    assert_eq!(class.count("SELECT COUNT(*) FROM private_comments").await, 1);
}

#[tokio::test]
async fn removed_co_teacher_cannot_read_or_write_threads() {
    let (class, _, _) = class_with_assignment().await;
    let co = class.co_teacher("T-0002").await;
    assert_eq!(thread(&class, &co, Some(&class.learner)).await.0, 200);
    assert_eq!(post(&class, &co, json!({"content": "Hello", "student_id": class.learner})).await.0, 200);
    class.exec("DELETE FROM classroom_teachers", &[]).await;
    assert_eq!(thread(&class, &co, Some(&class.learner)).await.0, 403);
    assert_eq!(post(&class, &co, json!({"content": "Again", "student_id": class.learner})).await.0, 403);
}

#[tokio::test]
async fn outsiders_and_bad_requests() {
    let (class, _, _) = class_with_assignment().await;
    let outsider = class.hub.seed_user("123456789098", "Outsider", Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    let (status, body) = thread(&class, &outsider, None).await;
    assert_eq!((status, message(&body)), (403, "Not your class"));
    assert_eq!(post(&class, &outsider, json!({"content": "x"})).await.0, 403);
    let admin = class.hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "1234").await;
    let admin = class.hub.open_session(admin).await;
    assert_eq!(thread(&class, &admin, Some(&class.learner)).await.0, 403);

    let (status, body) = class.hub.get("/api/assignments/no-such/private-comments", Some(&class.learner_token)).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    let (status, body) = post(&class, &class.learner_token, json!({"content": "  "})).await;
    assert_eq!((status, message(&body)), (400, "content cannot be empty"));
    let (status, body) = post(&class, &class.learner_token, json!({})).await;
    assert_eq!((status, message(&body)), (400, "Missing: content"));

    post(&class, &class.learner_token, json!({"content": "Tanong po"})).await;
    class.archive().await;
    let (status, body) = post(&class, &class.learner_token, json!({"content": "Isa pa"})).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    assert_eq!(thread(&class, &class.learner_token, None).await.0, 200, "reading stays open");
}

#[tokio::test]
async fn an_archived_assignment_has_no_thread() {
    let (class, _, _) = class_with_assignment().await;
    post(&class, &class.learner_token, json!({"content": "Tanong po"})).await;
    let (status, _) = class.hub.request(Method::DELETE, "/api/assignments/asg-1", Some(class.teacher())).await;
    assert_eq!(status, 200);
    let (status, body) = thread(&class, &class.learner_token, None).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    assert_eq!(post(&class, class.teacher(), json!({"content": "x", "student_id": class.learner})).await.0, 404);
}

/// #122's acceptance; pins the queued-comment push that step 2.2 built.
#[tokio::test]
async fn pushing_the_same_queued_comment_twice_stores_it_once() {
    let (class, _, _) = class_with_assignment().await;
    let queued = json!({"private_comments": [{"id": "e1000000-0000-4000-8000-000000000001", "assignment_id": "asg-1", "content": "Offline tanong", "created_at": 5}]});
    for _ in 0..2 {
        let (status, body) = class.hub.post_json("/api/sync/push", &queued, Some(&class.learner_token)).await;
        assert_eq!(status, 200, "{body}");
        assert_eq!(body["receipts"][0]["status"], "SYNCED", "{body}");
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM private_comments WHERE id = 'e1000000-0000-4000-8000-000000000001'").await, 1);
    let (status, listed) = thread(&class, &class.learner_token, None).await;
    assert_eq!((status, listed.as_array().map(Vec::len)), (200, Some(1)));
    assert_eq!(listed[0]["content"], "Offline tanong");
}
