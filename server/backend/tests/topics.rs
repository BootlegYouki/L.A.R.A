//! Step 3.5 (#122): topics group classwork (PRD FR-3.7) on the real Hub.

mod common;

use common::uploads::Class;
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
