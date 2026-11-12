//! Step 3.4 (#54): the class stream on the real Hub. Teachers post, edit and delete announcements;
//! enrolled learners get `EVENT_ANNOUNCEMENT_PUSH`; members comment unless comments are off.

mod common;

use common::uploads::{material_form, Class, PDF};
use common::ws::{connect, next, nothing_within};
use reqwest::Method;
use serde_json::{json, Value};

fn code(body: &Value) -> &str {
    body["error"]["code"].as_str().unwrap_or("")
}

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

async fn post(class: &Class, token: &str, body: Value) -> (u16, Value) {
    class.hub.post_json("/api/announcements", &body, Some(token)).await
}

/// A teacher's announcement in the class, as the API returned it.
async fn announce(class: &Class, token: &str) -> Value {
    let (status, body) = post(class, token, json!({"classroom_id": class.id, "title": "Field Trip", "content": "Bring water."})).await;
    assert_eq!(status, 200, "{body}");
    body
}

async fn patch(class: &Class, id: &str, body: Value, token: &str) -> (u16, Value) {
    class.hub.send_json(Method::PATCH, &format!("/api/announcements/{id}"), &body, Some(token)).await
}

async fn delete(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.request(Method::DELETE, &format!("/api/announcements/{id}"), Some(token)).await
}

async fn pull_from(class: &Class, token: &str, cursor: i64) -> Value {
    let (status, body) = class.hub.post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(token)).await;
    assert_eq!(status, 200, "{body}");
    body
}

fn deleted(pull: &Value) -> Vec<(String, String)> {
    pull["deleted"]
        .as_array()
        .unwrap()
        .iter()
        .map(|d| (d["entity_table"].as_str().unwrap().to_owned(), d["entity_id"].as_str().unwrap().to_owned()))
        .collect()
}

#[tokio::test]
async fn teacher_posts_and_the_learner_pulls_it() {
    let class = Class::start().await;
    let ann = announce(&class, class.teacher()).await;
    assert_eq!(ann["allow_comments"], true, "comments are on unless the teacher turns them off");
    assert_eq!((ann["title"].as_str(), ann["content"].as_str()), (Some("Field Trip"), Some("Bring water.")));
    assert_eq!(ann["classroom_id"], class.id.as_str());
    let id = ann["id"].as_str().unwrap();
    assert!(uuid::Uuid::parse_str(id).is_ok(), "{id}");
    assert!(ann["created_at"].as_i64().unwrap() > 0 && ann["updated_at"] == ann["created_at"]);

    let pulled = class.pull(&class.learner_token).await;
    let ids: Vec<&str> = pulled["announcements"].as_array().unwrap().iter().map(|a| a["id"].as_str().unwrap()).collect();
    assert_eq!(ids, [id]);
    assert_eq!(pulled["announcements"][0], ann, "the pull carries the same announcement");
}

#[tokio::test]
async fn post_pushes_to_active_learners_only() {
    let class = Class::start().await;
    let (_, pending_token) = class.add_learner("123456789013", false).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    let mut pending = connect(&class.hub, &pending_token).await;
    let mut teacher = connect(&class.hub, class.teacher()).await;

    let ann = announce(&class, class.teacher()).await;
    let event = next(&mut learner).await;
    let timestamp = event["timestamp"].as_i64().unwrap();
    assert!(timestamp > 0);
    assert_eq!(
        event,
        json!({"event": "EVENT_ANNOUNCEMENT_PUSH", "classroom_id": class.id, "announcement_id": ann["id"],
               "title": "Field Trip", "timestamp": timestamp})
    );
    nothing_within(&mut pending, 300).await;
    nothing_within(&mut teacher, 300).await;
}

#[tokio::test]
async fn edit_changes_only_the_given_fields() {
    let class = Class::start().await;
    let ann = announce(&class, class.teacher()).await;
    let id = ann["id"].as_str().unwrap();
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, edited) = patch(&class, id, json!({"allow_comments": false}), class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    assert_eq!((edited["title"].as_str(), edited["allow_comments"].as_bool()), (Some("Field Trip"), Some(false)));
    assert_eq!(edited["created_at"], ann["created_at"]);
    let (status, edited) = patch(&class, id, json!({"title": "Field Trip (moved)", "content": "Friday."}), class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    assert_eq!((edited["title"].as_str(), edited["content"].as_str()), (Some("Field Trip (moved)"), Some("Friday.")));
    assert_eq!(edited["allow_comments"], false, "an earlier edit stays");
    assert_eq!(pull_from(&class, &class.learner_token, cursor).await["announcements"][0], edited);

    let (status, body) = patch(&class, id, json!({"title": 5}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "title must be text"));
    let (status, body) = patch(&class, id, json!({"allow_comments": "no"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "allow_comments must be true or false"));
    let (status, body) = patch(&class, id, json!({"title": " "}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "title cannot be empty"));
}

#[tokio::test]
async fn deleting_twice_is_not_found_and_writes_one_tombstone() {
    let class = Class::start().await;
    let ann = announce(&class, class.teacher()).await;
    let id = ann["id"].as_str().unwrap();
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, body), (200, json!({"success": true})));
    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (404, "NOT_FOUND", "Announcement not found"));
    assert_eq!(
        class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'announcements' AND action = 'DELETE'").await,
        1
    );
    let after = pull_from(&class, &class.learner_token, cursor).await;
    assert_eq!(deleted(&after), [("announcements".to_owned(), id.to_owned())]);
    assert_eq!(after["announcements"], json!([]));
}

#[tokio::test]
async fn delete_takes_comments_and_attachments_with_it() {
    let class = Class::start().await;
    let ann = announce(&class, class.teacher()).await;
    let id = ann["id"].as_str().unwrap().to_owned();
    // Task 3 replaces this with the comment route.
    class
        .exec(
            "INSERT INTO announcement_comments (id, announcement_id, author_id, content, created_at, updated_at) VALUES ('c-1', ?, ?, 'Opo!', 1, 1)",
            &[id.as_str(), class.learner.as_str()],
        )
        .await;
    let comment = json!({"id": "c-1"});
    let form = material_form(&class.id, "DOCUMENT", PDF).text("announcement_id", id.clone());
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), form).await;
    assert_eq!(status, 200, "{material}");
    assert_eq!(class.files_in("materials").len(), 1);
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, body) = delete(&class, &id, class.teacher()).await;
    assert_eq!(status, 200, "{body}");
    let mut gone = deleted(&pull_from(&class, &class.learner_token, cursor).await);
    gone.sort();
    let mut expected = vec![
        ("announcement_comments".to_owned(), comment["id"].as_str().unwrap().to_owned()),
        ("announcements".to_owned(), id),
        ("materials".to_owned(), material["id"].as_str().unwrap().to_owned()),
    ];
    expected.sort();
    assert_eq!(gone, expected);
    assert!(class.files_in("materials").is_empty(), "the attachment's file is removed from disk");
    assert_eq!(class.count("SELECT COUNT(*) FROM materials").await, 0);
}

#[tokio::test]
async fn co_teacher_can_post_and_archived_class_refuses() {
    let class = Class::start().await;
    let co = class.co_teacher("T-0002").await;
    let ann = announce(&class, &co).await;
    let id = ann["id"].as_str().unwrap();

    class.archive().await;
    let (status, body) = post(&class, &co, json!({"classroom_id": class.id, "title": "x", "content": "y"})).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = patch(&class, id, json!({"title": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = delete(&class, id, class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    assert_eq!(class.count("SELECT COUNT(*) FROM announcements").await, 1, "nothing changed");
}

#[tokio::test]
async fn outsiders_and_learners_are_refused() {
    let class = Class::start().await;
    let ann = announce(&class, class.teacher()).await;
    let id = ann["id"].as_str().unwrap();
    let (_, other_teacher) = class.other_class().await;

    let (status, body) = post(&class, &other_teacher, json!({"classroom_id": class.id, "title": "x", "content": "y"})).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let (status, _) = patch(&class, id, json!({"title": "x"}), &other_teacher).await;
    assert_eq!(status, 403);
    let (status, _) = delete(&class, id, &other_teacher).await;
    assert_eq!(status, 403);
    let (status, body) = post(&class, &class.learner_token, json!({"classroom_id": class.id, "title": "x", "content": "y"})).await;
    assert_eq!((status, code(&body)), (403, "FORBIDDEN"));
    let (status, _) = delete(&class, id, &class.learner_token).await;
    assert_eq!(status, 403);

    let (status, body) = patch(&class, "no-such-id", json!({"title": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Announcement not found"));
    let (status, body) = delete(&class, "no-such-id", class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Announcement not found"));
    let (status, body) = post(&class, class.teacher(), json!({"classroom_id": class.id, "title": "x"})).await;
    assert_eq!((status, message(&body)), (400, "Missing: content"));
    let (status, body) = post(&class, class.teacher(), json!({"classroom_id": class.id, "title": "   ", "content": "y"})).await;
    assert_eq!((status, message(&body)), (400, "title cannot be empty"));
    let (status, body) = post(&class, class.teacher(), json!({"classroom_id": "no-such-class", "title": "x", "content": "y"})).await;
    assert_eq!((status, message(&body)), (404, "Classroom not found"));
    assert_eq!(class.count("SELECT COUNT(*) FROM announcements").await, 1);
}
