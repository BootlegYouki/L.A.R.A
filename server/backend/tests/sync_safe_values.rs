//! Bug #290: the Hub never stores a value the desktop app refuses in a sync pull
//! (`desktop/src-tauri/src/sync.rs` `valid`): ids are hyphenated non-nil UUIDs, whole numbers are 0 to
//! 2^53−1, and text is at most 1,000,000 bytes. One refused row would stop a whole class's sync.

mod common;

use common::uploads::{jpeg, material_form, submission_form, submission_form_at, Class, PDF};
use lara_hub::auth::Role;
use reqwest::Method;
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

/// Six bad items, then a good one at every limit. An uppercase id is refused (review of #290): the desktop
/// lowercases ids, so an uppercase copy of another row's id would collide with it there.
fn items(parent_key: &str, parent: &str) -> (Vec<Value>, String) {
    let good = uuid::Uuid::new_v4().hyphenated().to_string();
    let upper = uuid::Uuid::new_v4().hyphenated().to_string().to_uppercase();
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
            item(&upper, json!(1), "x".into()),
            item(&good, json!(MAX_SAFE_INT), "é".repeat(MAX_TEXT_BYTES / 2)),
        ],
        good,
    )
}

fn expected() -> Vec<(String, String)> {
    let rejected = ("REJECTED".to_owned(), "BAD_REQUEST".to_owned());
    let mut all = vec![rejected; 6];
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

/// 1,000,001 bytes: 500,000 two-byte letters and one more. The cap counts bytes, not characters.
fn too_long() -> String {
    "é".repeat(MAX_TEXT_BYTES / 2) + "x"
}

/// Every REST route that stores text clients pull refuses text over the desktop's limit.
#[tokio::test]
async fn rest_text_over_the_cap_is_refused_on_every_route() {
    let class = Class::start().await;
    class.announcement(ANNOUNCEMENT).await;
    class.assignment(ASSIGNMENT).await;
    let submission = uuid::Uuid::new_v4().to_string();
    let path = format!("/api/assignments/{ASSIGNMENT}/submit");
    let (status, body) = class.upload(&path, Some(&class.learner_token), submission_form(&submission, &jpeg("a"))).await;
    assert_eq!(status, 200, "{body}");
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!(status, 200, "{material}");
    let material = material["id"].as_str().unwrap().to_owned();
    let admin = class.hub.seed_user("ADMIN-0001", "Hub Admin", Role::Admin, "1234").await;
    let admin = class.hub.open_session(admin).await;
    let long = too_long();
    let question =
        |text: &str| json!({"question_text": text, "question_type": "TRUE_FALSE", "options": ["True", "False"], "correct_answer": "True"});
    let quiz = |title: &str, instructions: &str, text: &str| {
        json!({"classroom_id": class.id, "title": title, "instructions": instructions, "time_limit_minutes": 5, "questions": [question(text)]})
    };
    let teacher = class.teacher().to_owned();
    let learner = class.learner_token.clone();
    let cases: Vec<(Method, String, Value, &str, &str)> = vec![
        (Method::POST, "/api/classrooms".into(), json!({"name": long, "section": "A"}), "name", &teacher),
        (Method::POST, "/api/classrooms".into(), json!({"name": "Science", "section": long}), "section", &teacher),
        (Method::PATCH, format!("/api/classrooms/{}", class.id), json!({"name": long}), "name", &teacher),
        (Method::POST, "/api/announcements".into(), json!({"classroom_id": class.id, "title": long, "content": "x"}), "title", &teacher),
        (Method::POST, "/api/announcements".into(), json!({"classroom_id": class.id, "title": "x", "content": long}), "content", &teacher),
        (Method::PATCH, format!("/api/announcements/{ANNOUNCEMENT}"), json!({"content": long}), "content", &teacher),
        (Method::POST, format!("/api/announcements/{ANNOUNCEMENT}/comments"), json!({"content": long}), "content", &learner),
        (Method::POST, format!("/api/assignments/{ASSIGNMENT}/private-comments"), json!({"content": long}), "content", &learner),
        (Method::POST, "/api/topics".into(), json!({"classroom_id": class.id, "name": long}), "name", &teacher),
        (
            Method::POST,
            "/api/assignments".into(),
            json!({"classroom_id": class.id, "title": long, "max_points": 10, "due_date": 4_102_444_800_000_i64}),
            "title",
            &teacher,
        ),
        (Method::PATCH, format!("/api/assignments/{ASSIGNMENT}"), json!({"instructions": long}), "instructions", &teacher),
        (Method::PATCH, format!("/api/submissions/{submission}"), json!({"score": 5, "teacher_feedback": long}), "teacher_feedback", &teacher),
        (Method::PATCH, format!("/api/materials/{material}"), json!({"title": long}), "title", &teacher),
        (Method::POST, "/api/quizzes".into(), quiz(&long, "x", "x"), "title", &teacher),
        (Method::POST, "/api/quizzes".into(), quiz("x", &long, "x"), "instructions", &teacher),
        (Method::POST, "/api/quizzes".into(), quiz("x", "x", &long), "question_text", &teacher),
        (
            Method::POST,
            "/api/admin/users".into(),
            json!({"lrn_or_id": "T-0100", "full_name": long, "role": "TEACHER", "pin": "1234"}),
            "full_name",
            &admin,
        ),
    ];
    for (method, path, body, field, token) in cases {
        let (status, reply) = class.hub.send_json(method.clone(), &path, &body, Some(token)).await;
        let message = reply["error"]["message"].as_str().unwrap_or("").to_owned();
        assert_eq!((status, message), (400, format!("{field} is too long")), "{method} {path}");
    }
    let marker = "é".repeat(1000);
    for token in [&teacher, &learner] {
        assert!(!class.pull(token).await.to_string().contains(&marker), "no over-long text reaches a pull");
    }
}

/// Review of #290: a learner who pushes an uppercase copy of a classmate's comment id must not get a second
/// row. The desktop lowercases ids, so the two would collide in its pull, or the copy would overwrite the
/// classmate's comment there.
#[tokio::test]
async fn an_uppercase_copy_of_a_comment_id_is_refused() {
    let class = Class::start().await;
    class.announcement(ANNOUNCEMENT).await;
    let id = uuid::Uuid::new_v4().to_string();
    let comment = |id: &str| json!({"comments": [{"id": id, "announcement_id": ANNOUNCEMENT, "content": "Opo", "created_at": 1}]});
    assert_eq!(statuses(&push(&class, comment(&id)).await), [("SYNCED".to_owned(), String::new())]);
    let receipts = push(&class, comment(&id.to_uppercase())).await;
    assert_eq!(statuses(&receipts), [("REJECTED".to_owned(), "BAD_REQUEST".to_owned())]);
    assert_eq!(class.count("SELECT COUNT(*) FROM announcement_comments").await, 1);
}

/// Review of #290: the learner's Turn in time is stored as `submitted_at`, which every teacher pulls.
#[tokio::test]
async fn a_homework_turned_in_at_must_be_a_safe_whole_number() {
    let class = Class::start().await;
    class.assignment(ASSIGNMENT).await;
    let path = format!("/api/assignments/{ASSIGNMENT}/submit");
    for bad in ["-1", "9007199254740992"] {
        let id = uuid::Uuid::new_v4().to_string();
        let (status, body) =
            class.upload(&path, Some(&class.learner_token), submission_form_at(&id, &jpeg("a"), bad)).await;
        assert_eq!(
            (status, body["error"]["message"].as_str()),
            (400, Some("turned_in_at must be from 0 to 9007199254740991")),
            "{bad}"
        );
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    let id = uuid::Uuid::new_v4().to_string();
    let (status, body) = class.upload(&path, Some(&class.learner_token), submission_form_at(&id, &jpeg("a"), "0")).await;
    assert_eq!((status, body["submitted_at"].as_i64()), (200, Some(0)), "{body}");
}
