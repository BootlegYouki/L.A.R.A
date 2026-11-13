//! Bug #290: the Hub never stores a value the desktop app refuses in a sync pull
//! (`desktop/src-tauri/src/sync.rs` `valid`): ids are hyphenated non-nil UUIDs, whole numbers are 0 to
//! 2^53−1, and text is at most 1,000,000 bytes. One refused row would stop a whole class's sync.

mod common;

use common::uploads::{jpeg, submission_form, Class};
use serde_json::{json, Value};

const ANNOUNCEMENT: &str = "a1000000-0000-4000-8000-000000000001";
const ASSIGNMENT: &str = "b1000000-0000-4000-8000-000000000001";
const NIL: &str = "00000000-0000-0000-0000-000000000000";
const MAX_SAFE_INT: i64 = 9_007_199_254_740_991;
const MAX_TEXT_BYTES: usize = 1_000_000;

async fn push(class: &Class, body: Value) -> Vec<Value> {
    let (status, reply) = class.hub.post_json("/api/sync/push", &body, Some(&class.learner_token)).await;
    assert_eq!(status, 200, "{reply}");
    reply["receipts"].as_array().unwrap().clone()
}

fn statuses(receipts: &[Value]) -> Vec<(String, String)> {
    receipts
        .iter()
        .map(|r| (r["status"].as_str().unwrap().to_owned(), r["reason"].as_str().unwrap_or("").to_owned()))
        .collect()
}

/// Four bad items, then a good one at every limit: the uppercase id is still a UUID the desktop accepts.
fn items(parent_key: &str, parent: &str) -> (Vec<Value>, String) {
    let good = uuid::Uuid::new_v4().hyphenated().to_string().to_uppercase();
    let item = |id: &str, created_at: Value, content: String| {
        json!({"id": id, parent_key: parent, "content": content, "created_at": created_at})
    };
    let fresh = || uuid::Uuid::new_v4().to_string();
    (
        vec![
            item("not-a-uuid", json!(1), "x".into()),
            item(NIL, json!(1), "x".into()),
            item(&fresh(), json!(-1), "x".into()),
            item(&fresh(), json!(MAX_SAFE_INT + 1), "x".into()),
            item(&fresh(), json!(1), "é".repeat(MAX_TEXT_BYTES / 2) + "x"),
            item(&good, json!(MAX_SAFE_INT), "é".repeat(MAX_TEXT_BYTES / 2)),
        ],
        good,
    )
}

fn expected() -> Vec<(String, String)> {
    let rejected = ("REJECTED".to_owned(), "BAD_REQUEST".to_owned());
    let mut all = vec![rejected; 5];
    all.push(("SYNCED".to_owned(), String::new()));
    all
}

#[tokio::test]
async fn a_comment_push_with_an_unsafe_value_is_rejected_alone() {
    let class = Class::start().await;
    class.announcement(ANNOUNCEMENT).await;
    let (comments, good) = items("announcement_id", ANNOUNCEMENT);
    let receipts = push(&class, json!({"comments": comments})).await;
    assert_eq!(statuses(&receipts), expected());

    let pulled = class.pull(&class.learner_token).await;
    let ids: Vec<&str> = pulled["comments"].as_array().unwrap().iter().map(|c| c["id"].as_str().unwrap()).collect();
    assert_eq!(ids, [good.as_str()], "only the good comment reaches a pull");
}

#[tokio::test]
async fn a_private_comment_push_with_an_unsafe_value_is_rejected_alone() {
    let class = Class::start().await;
    class.assignment(ASSIGNMENT).await;
    let (comments, good) = items("assignment_id", ASSIGNMENT);
    let receipts = push(&class, json!({"private_comments": comments})).await;
    assert_eq!(statuses(&receipts), expected());

    let pulled = class.pull(&class.learner_token).await;
    let ids: Vec<&str> =
        pulled["private_comments"].as_array().unwrap().iter().map(|c| c["id"].as_str().unwrap()).collect();
    assert_eq!(ids, [good.as_str()], "only the good private comment reaches a pull");
}

#[tokio::test]
async fn a_homework_submission_id_must_be_a_uuid() {
    let class = Class::start().await;
    class.assignment(ASSIGNMENT).await;
    let path = format!("/api/assignments/{ASSIGNMENT}/submit");
    for bad in ["sub-1", NIL] {
        let (status, body) = class.upload(&path, Some(&class.learner_token), submission_form(bad, &jpeg("a"))).await;
        assert_eq!((status, body["error"]["message"].as_str()), (400, Some("submission_id must be a UUID")), "{bad}");
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    let good = uuid::Uuid::new_v4().to_string();
    let (status, body) = class.upload(&path, Some(&class.learner_token), submission_form(&good, &jpeg("a"))).await;
    assert_eq!(status, 200, "{body}");
}
