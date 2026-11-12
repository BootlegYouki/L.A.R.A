//! Step 3.4 (#54): teachers create, edit and archive assignments, list the homework handed in and
//! grade it, on the real Hub. Submitting homework is step 3.3 (`tests/submissions.rs`).

mod common;

use common::uploads::{jpeg, submission_form, Class};
use reqwest::Method;
use serde_json::{json, Value};

const DUE_2100: i64 = 4_102_444_800_000;

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

async fn create(class: &Class, token: &str, body: Value) -> (u16, Value) {
    class.hub.post_json("/api/assignments", &body, Some(token)).await
}

/// "Worksheet 1", 20 points, due in 2100, as the given teacher.
async fn worksheet(class: &Class, token: &str) -> Value {
    let (status, body) =
        create(class, token, json!({"classroom_id": class.id, "title": "Worksheet 1", "max_points": 20, "due_date": DUE_2100})).await;
    assert_eq!(status, 200, "{body}");
    body
}

async fn patch(class: &Class, id: &str, body: Value, token: &str) -> (u16, Value) {
    class.hub.send_json(Method::PATCH, &format!("/api/assignments/{id}"), &body, Some(token)).await
}

async fn delete(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.request(Method::DELETE, &format!("/api/assignments/{id}"), Some(token)).await
}

async fn submit(class: &Class, assignment: &str, token: &str, submission_id: &str, tag: &str) -> Value {
    let path = format!("/api/assignments/{assignment}/submit");
    let (status, body) = class.upload(&path, Some(token), submission_form(submission_id, &jpeg(tag))).await;
    assert_eq!(status, 200, "{body}");
    body
}

async fn pull_from(class: &Class, token: &str, cursor: i64) -> Value {
    let (status, body) = class.hub.post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(token)).await;
    assert_eq!(status, 200, "{body}");
    body
}

#[tokio::test]
async fn teacher_creates_an_assignment_with_the_mock_defaults() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    assert!(uuid::Uuid::parse_str(asg["id"].as_str().unwrap()).is_ok(), "{asg}");
    assert_eq!(
        (asg["title"].as_str(), asg["max_points"].as_i64(), asg["due_date"].as_i64()),
        (Some("Worksheet 1"), Some(20), Some(DUE_2100))
    );
    assert_eq!((asg["instructions"].as_str(), asg["allow_late"].as_bool()), (Some(""), Some(false)));
    assert!(asg["topic_id"].is_null());
    assert_eq!(class.pull(&class.learner_token).await["assignments"], json!([asg]));
}

#[tokio::test]
async fn edit_updates_fields_and_clears_the_topic() {
    let class = Class::start().await;
    class.topic("t-1").await;
    let (status, asg) = create(
        &class,
        class.teacher(),
        json!({"classroom_id": class.id, "title": "Worksheet 1", "instructions": "Show your work.", "max_points": 20,
               "due_date": DUE_2100, "allow_late": true, "topic_id": "t-1"}),
    )
    .await;
    assert_eq!(status, 200, "{asg}");
    assert_eq!((asg["topic_id"].as_str(), asg["allow_late"].as_bool()), (Some("t-1"), Some(true)));
    let id = asg["id"].as_str().unwrap();
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, edited) = patch(&class, id, json!({"title": "Bagong pamagat", "topic_id": null}), class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    assert_eq!(edited["title"], "Bagong pamagat");
    assert!(edited["topic_id"].is_null());
    assert_eq!((edited["instructions"].as_str(), edited["max_points"].as_i64()), (Some("Show your work."), Some(20)), "untouched");
    let (status, edited) = patch(&class, id, json!({"max_points": 30, "due_date": 5, "allow_late": false, "topic_id": "t-1"}), class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    assert_eq!((edited["max_points"].as_i64(), edited["due_date"].as_i64(), edited["topic_id"].as_str()), (Some(30), Some(5), Some("t-1")));
    assert_eq!(pull_from(&class, &class.learner_token, cursor).await["assignments"], json!([edited]));
}

#[tokio::test]
async fn invalid_fields_are_refused() {
    let class = Class::start().await;
    let base = json!({"classroom_id": class.id, "title": "Worksheet 1", "max_points": 20, "due_date": DUE_2100});
    let with = |key: &str, value: Value| {
        let mut body = base.clone();
        body[key] = value;
        body
    };
    let cases = [
        (with("max_points", json!(0)), 400, "max_points must be more than 0"),
        (with("max_points", json!("20")), 400, "max_points must be a whole number"),
        (with("due_date", json!(1.5)), 400, "due_date must be a whole number"),
        (with("allow_late", json!("yes")), 400, "allow_late must be true or false"),
        (with("title", json!(" ")), 400, "title cannot be empty"),
        (with("topic_id", json!("no-such-topic")), 404, "Topic not found"),
    ];
    for (body, status, text) in cases {
        let (got, answer) = create(&class, class.teacher(), body).await;
        assert_eq!((got, message(&answer)), (status, text));
    }
    let (status, answer) = create(&class, class.teacher(), json!({"classroom_id": class.id, "title": "x", "max_points": 20})).await;
    assert_eq!((status, message(&answer)), (400, "Missing: due_date"));

    let (other, _) = class.other_class().await;
    class
        .exec("INSERT INTO topics (id, classroom_id, name, created_at, updated_at) VALUES ('t-other', ?, 'Fractions', 1, 1)", &[other.as_str()])
        .await;
    let (status, answer) = create(&class, class.teacher(), with("topic_id", json!("t-other"))).await;
    assert_eq!((status, message(&answer)), (404, "Topic not found"), "a topic of another class");
    let asg = worksheet(&class, class.teacher()).await;
    let (status, answer) = patch(&class, asg["id"].as_str().unwrap(), json!({"max_points": -3}), class.teacher()).await;
    assert_eq!((status, message(&answer)), (400, "max_points must be more than 0"));
    assert_eq!(class.count("SELECT COUNT(*) FROM assignments").await, 1);
}

#[tokio::test]
async fn delete_archives_and_keeps_grades() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    class.exec("UPDATE assignment_submissions SET score = 18 WHERE id = 'sub-1'", &[]).await;
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, body), (200, json!({"success": true})));
    let after = pull_from(&class, &class.learner_token, cursor).await;
    assert!(after["deleted"].as_array().unwrap().contains(&json!({"entity_table": "assignments", "entity_id": id})), "{after}");
    assert_eq!(class.count("SELECT COUNT(*) FROM assignments WHERE archived_at IS NOT NULL AND archived_at = updated_at").await, 1);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions WHERE id = 'sub-1' AND score = 18").await, 1, "grades are kept");
    let (status, body) = patch(&class, id, json!({"title": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
}

#[tokio::test]
async fn archiving_twice_is_not_found() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    assert_eq!(delete(&class, id, class.teacher()).await.0, 200);
    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    assert_eq!(
        class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'assignments' AND action = 'DELETE'").await,
        1
    );
}

#[tokio::test]
async fn assignment_writes_need_a_writable_class_you_teach() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    let (_, other_teacher) = class.other_class().await;

    assert_eq!(patch(&class, id, json!({"title": "x"}), &class.learner_token).await.0, 403, "the mock's last check");
    assert_eq!(delete(&class, id, &class.learner_token).await.0, 403);
    let (status, body) = patch(&class, id, json!({"title": "x"}), &other_teacher).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let (status, body) =
        create(&class, &other_teacher, json!({"classroom_id": class.id, "title": "x", "max_points": 1, "due_date": 1})).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));

    let co = class.co_teacher("T-0002").await;
    let co_asg = worksheet(&class, &co).await;
    assert_eq!(patch(&class, co_asg["id"].as_str().unwrap(), json!({"title": "By the co-teacher"}), &co).await.0, 200);

    class.archive().await;
    let (status, body) =
        create(&class, class.teacher(), json!({"classroom_id": class.id, "title": "x", "max_points": 1, "due_date": 1})).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = patch(&class, id, json!({"title": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
}
