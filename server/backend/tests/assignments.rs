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

async fn submissions_of(class: &Class, assignment: &str, token: &str) -> (u16, Value) {
    class.hub.get(&format!("/api/assignments/{assignment}/submissions"), Some(token)).await
}

async fn grade(class: &Class, submission: &str, body: Value, token: &str) -> (u16, Value) {
    class.hub.send_json(Method::PATCH, &format!("/api/submissions/{submission}"), &body, Some(token)).await
}

#[tokio::test]
async fn teacher_lists_and_grades_a_submission() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    let handed_in = submit(&class, id, &class.learner_token, "sub-1", "a").await;
    let (_, ana_token) = class.add_learner("123456789013", true).await;

    let (status, listed) = submissions_of(&class, id, class.teacher()).await;
    assert_eq!((status, listed.clone()), (200, json!([handed_in])));
    assert!(listed[0].get("file_path").is_none() && listed[0].get("created_at").is_none(), "{listed}");

    let (status, graded) = grade(&class, "sub-1", json!({"score": 18, "teacher_feedback": "Magaling!"}), class.teacher()).await;
    assert_eq!(status, 200, "{graded}");
    assert_eq!((graded["score"].as_i64(), graded["teacher_feedback"].as_str()), (Some(18), Some("Magaling!")));
    assert_eq!(graded["submitted_at"], handed_in["submitted_at"], "grading never moves the hand-in time");
    assert!(graded["updated_at"].as_i64() >= handed_in["updated_at"].as_i64());
    assert_eq!(class.pull(&class.learner_token).await["submissions"], json!([graded]), "the learner sees the grade");
    assert_eq!(class.pull(&ana_token).await["submissions"], json!([]), "classmates never see it");

    let path = format!("/api/assignments/{id}/submit");
    let (status, body) = class.upload(&path, Some(&class.learner_token), submission_form("sub-2", &jpeg("b"))).await;
    assert_eq!((status, body["error"]["code"].as_str()), (409, Some("ALREADY_GRADED")), "graded work is final (3.3)");
    let (status, regraded) = grade(&class, "sub-1", json!({"score": 19}), class.teacher()).await;
    assert_eq!((status, regraded["score"].as_i64(), regraded["teacher_feedback"].as_str()), (200, Some(19), Some("Magaling!")));
}

#[tokio::test]
async fn score_rules_follow_google_classroom() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    submit(&class, asg["id"].as_str().unwrap(), &class.learner_token, "sub-1", "a").await;
    let cases = [
        (json!({"score": -1}), "score must be 0 or more"),
        (json!({"score": null}), "score must be a whole number"),
        (json!({"score": 17.5}), "score must be a whole number"),
        (json!({"teacher_feedback": 5}), "teacher_feedback must be text"),
        (json!({}), "Missing: score or teacher_feedback"),
    ];
    for (body, text) in cases {
        let (status, answer) = grade(&class, "sub-1", body, class.teacher()).await;
        assert_eq!((status, message(&answer)), (400, text));
    }
    let (status, feedback_only) = grade(&class, "sub-1", json!({"teacher_feedback": "Please redo item 3."}), class.teacher()).await;
    assert_eq!(status, 200, "{feedback_only}");
    assert!(feedback_only["score"].is_null(), "feedback alone does not grade");
    let (status, bonus) = grade(&class, "sub-1", json!({"score": 25}), class.teacher()).await;
    assert_eq!((status, bonus["score"].as_i64()), (200, Some(25)), "above max_points is allowed, as in Google Classroom");
}

#[tokio::test]
async fn grading_a_replaced_submission_id_is_not_found() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    submit(&class, id, &class.learner_token, "sub-2", "b").await;
    let (status, body) = grade(&class, "sub-1", json!({"score": 10}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Submission not found"));
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions WHERE id = 'sub-2' AND score IS NULL").await, 1);
}

#[tokio::test]
async fn removed_co_teacher_can_no_longer_post_or_grade() {
    let class = Class::start().await;
    let co = class.co_teacher("T-0002").await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    assert_eq!(grade(&class, "sub-1", json!({"score": 10}), &co).await.0, 200, "a co-teacher grades");
    assert_eq!(submissions_of(&class, id, &co).await.0, 200);

    class.exec("DELETE FROM classroom_teachers", &[]).await;
    assert_eq!(grade(&class, "sub-1", json!({"score": 11}), &co).await.0, 403);
    assert_eq!(submissions_of(&class, id, &co).await.0, 403);
    let (status, _) =
        class.hub.post_json("/api/announcements", &json!({"classroom_id": class.id, "title": "x", "content": "y"}), Some(&co)).await;
    assert_eq!(status, 403);
}

#[tokio::test]
async fn archived_class_can_list_but_not_grade() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    class.archive().await;
    let (status, listed) = submissions_of(&class, id, class.teacher()).await;
    assert_eq!((status, listed.as_array().map(Vec::len)), (200, Some(1)));
    let (status, body) = grade(&class, "sub-1", json!({"score": 10}), class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
}

#[tokio::test]
async fn archived_assignment_is_hidden_from_grading() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    delete(&class, id, class.teacher()).await;
    let (status, body) = submissions_of(&class, id, class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    let (status, body) = grade(&class, "sub-1", json!({"score": 10}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Submission not found"));
}

#[tokio::test]
async fn learners_and_other_teachers_cannot_list_or_grade() {
    let class = Class::start().await;
    let asg = worksheet(&class, class.teacher()).await;
    let id = asg["id"].as_str().unwrap();
    submit(&class, id, &class.learner_token, "sub-1", "a").await;
    let (_, other_teacher) = class.other_class().await;
    for token in [class.learner_token.as_str(), other_teacher.as_str()] {
        assert_eq!(submissions_of(&class, id, token).await.0, 403);
        assert_eq!(grade(&class, "sub-1", json!({"score": 20}), token).await.0, 403);
    }
    assert_eq!(submissions_of(&class, "no-such-id", class.teacher()).await.0, 404);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions WHERE score IS NULL").await, 1);
}
