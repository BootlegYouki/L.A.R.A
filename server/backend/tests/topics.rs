//! Step 3.5 (#122): topics group classwork (PRD FR-3.7) on the real Hub.

mod common;

use common::uploads::{material_form, Class, PDF};
use reqwest::Method;
use serde_json::{json, Value};

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

async fn create(class: &Class, token: &str, name: &str) -> (u16, Value) {
    class.hub.post_json("/api/topics", &json!({"classroom_id": class.id, "name": name}), Some(token)).await
}

/// A topic named `name` in the class, as the API returned it.
async fn topic(class: &Class, name: &str) -> Value {
    let (status, body) = create(class, class.teacher(), name).await;
    assert_eq!(status, 200, "{body}");
    body
}

async fn patch(class: &Class, id: &str, body: Value, token: &str) -> (u16, Value) {
    class.hub.send_json(Method::PATCH, &format!("/api/topics/{id}"), &body, Some(token)).await
}

async fn pull_from(class: &Class, token: &str, cursor: i64) -> Value {
    let (status, body) = class.hub.post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(token)).await;
    assert_eq!(status, 200, "{body}");
    body
}

#[tokio::test]
async fn teacher_creates_topics_that_go_last() {
    let class = Class::start().await;
    let (other, _) = class.other_class().await;
    class
        .exec("INSERT INTO topics (id, classroom_id, name, order_index, created_at, updated_at) VALUES ('t-x', ?, 'Fractions', 9, 1, 1)", &[other.as_str()])
        .await;
    let first = topic(&class, "Unit 1").await;
    let second = topic(&class, "Unit 2").await;
    assert_eq!((first["order_index"].as_i64(), second["order_index"].as_i64()), (Some(1), Some(2)), "another class's topics don't count");
    assert_eq!((first["name"].as_str(), first["classroom_id"].as_str()), (Some("Unit 1"), Some(class.id.as_str())));
    assert!(uuid::Uuid::parse_str(first["id"].as_str().unwrap()).is_ok(), "{first}");
    let mut keys: Vec<&str> = first.as_object().unwrap().keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(keys, ["classroom_id", "id", "name", "order_index", "updated_at"]);
    let mut pulled = class.pull(&class.learner_token).await["topics"].as_array().unwrap().clone();
    pulled.sort_by_key(|t| t["order_index"].as_i64());
    assert_eq!(pulled, [first, second], "the pull has no set order");
}

#[tokio::test]
async fn rename_and_reorder() {
    let class = Class::start().await;
    let unit1 = topic(&class, "Unit 1").await;
    let unit2 = topic(&class, "Unit 2").await;
    let id = unit1["id"].as_str().unwrap();
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, renamed) = patch(&class, id, json!({"name": "Unit 1 - Plants"}), class.teacher()).await;
    assert_eq!(status, 200, "{renamed}");
    assert_eq!((renamed["name"].as_str(), renamed["order_index"].as_i64()), (Some("Unit 1 - Plants"), Some(1)));
    let (status, moved) = patch(&class, id, json!({"order_index": 5}), class.teacher()).await;
    assert_eq!((status, moved["order_index"].as_i64(), moved["name"].as_str()), (200, Some(5), Some("Unit 1 - Plants")));
    assert_eq!(pull_from(&class, &class.learner_token, cursor).await["topics"], json!([moved]));
    let pulled = class.pull(&class.learner_token).await;
    assert!(pulled["topics"].as_array().unwrap().contains(&unit2), "the other topic is unchanged");
}

#[tokio::test]
async fn topic_fields_are_checked() {
    let class = Class::start().await;
    let (status, body) = class.hub.post_json("/api/topics", &json!({"classroom_id": class.id}), Some(class.teacher())).await;
    assert_eq!((status, message(&body)), (400, "Missing: name"));
    let (status, body) = create(&class, class.teacher(), "  ").await;
    assert_eq!((status, message(&body)), (400, "name cannot be empty"));
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    let (status, body) = patch(&class, &id, json!({"order_index": -1}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "order_index must be from 0 to 9007199254740991"));
    let (status, body) = patch(&class, &id, json!({"order_index": "2"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "order_index must be a whole number"));
    let (status, body) = patch(&class, &id, json!({"name": 7}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "name must be text"));
}

#[tokio::test]
async fn topic_writes_need_a_writable_class_you_teach() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    let (_, other_teacher) = class.other_class().await;

    assert_eq!(create(&class, &class.learner_token, "x").await.0, 403);
    assert_eq!(patch(&class, &id, json!({"name": "x"}), &class.learner_token).await.0, 403);
    let (status, body) = create(&class, &other_teacher, "x").await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let (status, body) = patch(&class, &id, json!({"name": "x"}), &other_teacher).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let co = class.co_teacher("T-0002").await;
    assert_eq!(create(&class, &co, "By the co-teacher").await.0, 200);
    let (status, body) = patch(&class, "no-such-topic", json!({"name": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Topic not found"));

    class.archive().await;
    let (status, body) = create(&class, class.teacher(), "x").await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = patch(&class, &id, json!({"name": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
}

#[tokio::test]
async fn topics_created_together_get_distinct_places() {
    let class = Class::start().await;
    let (a, b) = tokio::join!(create(&class, class.teacher(), "A"), create(&class, class.teacher(), "B"));
    assert_eq!((a.0, b.0), (200, 200), "{a:?} {b:?}");
    let mut places = [a.1["order_index"].as_i64().unwrap(), b.1["order_index"].as_i64().unwrap()];
    places.sort_unstable();
    assert_eq!(places, [1, 2]);
}

async fn delete(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.request(Method::DELETE, &format!("/api/topics/{id}"), Some(token)).await
}

/// Files an assignment, a lesson file and a quiz under the topic; returns (assignment, material) ids.
async fn file_work_under(class: &Class, topic: &str) -> (String, String) {
    class.assignment("asg-1").await;
    let (status, body) = class.hub.send_json(Method::PATCH, "/api/assignments/asg-1", &json!({"topic_id": topic}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!(status, 200, "{material}");
    let material = material["id"].as_str().unwrap().to_owned();
    let (status, body) =
        class.hub.send_json(Method::PATCH, &format!("/api/materials/{material}"), &json!({"topic_id": topic}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    class
        .exec(
            "INSERT INTO quizzes (id, classroom_id, title, time_limit_minutes, topic_id, created_at, updated_at) VALUES ('quiz-1', ?, 'Quiz', 15, ?, 1, 1)",
            &[class.id.as_str(), topic],
        )
        .await;
    ("asg-1".to_owned(), material)
}

#[tokio::test]
async fn deleting_a_topic_keeps_its_work() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    let (assignment, material) = file_work_under(&class, &id).await;
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, body) = delete(&class, &id, class.teacher()).await;
    assert_eq!((status, body), (200, json!({"success": true})));
    let after = pull_from(&class, &class.learner_token, cursor).await;
    assert!(after["deleted"].as_array().unwrap().contains(&json!({"entity_table": "topics", "entity_id": id})), "{after}");
    let asg = after["assignments"].as_array().unwrap().iter().find(|a| a["id"] == assignment.as_str()).expect("the assignment is re-sent");
    assert!(asg["topic_id"].is_null(), "{asg}");
    let mat = after["materials"].as_array().unwrap().iter().find(|m| m["id"] == material.as_str()).expect("the material is re-sent");
    assert!(mat["topic_id"].is_null(), "{mat}");
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE id = 'quiz-1' AND topic_id IS NULL").await, 1, "quizzes have no foreign key (#177)");
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'quizzes' AND entity_id = 'quiz-1'").await, 1);
    assert_eq!(class.count("SELECT (SELECT COUNT(*) FROM assignments) + (SELECT COUNT(*) FROM materials) + (SELECT COUNT(*) FROM quizzes)").await, 3, "the work stays");
}

#[tokio::test]
async fn deleting_a_topic_never_brings_back_an_archived_assignment() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    class.assignment("asg-a").await;
    let (status, _) = class.hub.send_json(Method::PATCH, "/api/assignments/asg-a", &json!({"topic_id": id}), Some(class.teacher())).await;
    assert_eq!(status, 200);
    let before_archive = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();
    let (status, _) = class.hub.request(Method::DELETE, "/api/assignments/asg-a", Some(class.teacher())).await;
    assert_eq!(status, 200);

    assert_eq!(delete(&class, &id, class.teacher()).await.0, 200);
    let after = pull_from(&class, &class.learner_token, before_archive).await;
    assert!(
        after["deleted"].as_array().unwrap().contains(&json!({"entity_table": "assignments", "entity_id": "asg-a"})),
        "a device that missed the archive still gets the tombstone: {after}"
    );
    assert_eq!(after["assignments"], json!([]));
    assert_eq!(class.count("SELECT COUNT(*) FROM assignments WHERE id = 'asg-a' AND topic_id IS NULL AND archived_at IS NOT NULL").await, 1);
}

#[tokio::test]
async fn deleting_twice_is_not_found() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    assert_eq!(delete(&class, &id, class.teacher()).await.0, 200);
    let (status, body) = delete(&class, &id, class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Topic not found"));
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'topics' AND action = 'DELETE'").await, 1);
}

#[tokio::test]
async fn topic_delete_needs_a_writable_class_you_teach() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    let (_, other_teacher) = class.other_class().await;
    assert_eq!(delete(&class, &id, &class.learner_token).await.0, 403);
    assert_eq!(delete(&class, &id, &other_teacher).await.0, 403);
    class.archive().await;
    let (status, body) = delete(&class, &id, class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    assert_eq!(class.count("SELECT COUNT(*) FROM topics").await, 1);
}

/// Review of #122: a new topic after one moved to the top place must stay inside 0..=2^53-1, or every
/// desktop of the class would refuse the pull and stop syncing.
#[tokio::test]
async fn a_new_topic_never_goes_past_the_largest_place() {
    let class = Class::start().await;
    let id = topic(&class, "Unit 1").await["id"].as_str().unwrap().to_owned();
    let (status, moved) = patch(&class, &id, json!({"order_index": 9_007_199_254_740_991_i64}), class.teacher()).await;
    assert_eq!((status, moved["order_index"].as_i64()), (200, Some(9_007_199_254_740_991)));
    let next = topic(&class, "Unit 2").await;
    assert_eq!(next["order_index"].as_i64(), Some(9_007_199_254_740_991), "capped at the largest place");
}
