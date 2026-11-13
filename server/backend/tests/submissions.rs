//! Step 3.3: `POST /api/assignments/{id}/submit` and `GET /api/submissions/{id}/file` on the real Hub.

mod common;

use common::uploads::{
    assert_file_too_large, file_part, jpeg, raw_upload, submission_form, submission_form_at, Class, RawBody, HTML, PDF,
};
use reqwest::multipart::Form;
use serde_json::{json, Value};

const SUBMIT: &str = "/api/assignments/asg-1/submit";

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

async fn class_with_assignment() -> Class {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    class
}

async fn submit(class: &Class, token: &str, id: &str, bytes: &[u8]) -> (u16, Value) {
    class.upload(SUBMIT, Some(token), submission_form(id, bytes)).await
}

/// The learner hands in with the device's Turn in time.
async fn submit_at(class: &Class, id: &str, bytes: &[u8], turned_in_at: &str) -> (u16, Value) {
    class.upload(SUBMIT, Some(&class.learner_token), submission_form_at(id, bytes, turned_in_at)).await
}

fn code(body: &Value) -> &str {
    body["error"]["code"].as_str().unwrap_or("")
}

async fn file(class: &Class, submission: &str, token: &str) -> (u16, String, Vec<u8>) {
    let (status, headers, bytes) = class.hub.get_file(&format!("/api/submissions/{submission}/file"), Some(token), None).await;
    let content_type = headers.get("content-type").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
    (status, content_type, bytes)
}

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

#[tokio::test]
async fn a_learner_submits_a_photo_and_only_they_and_the_teachers_see_it() {
    let class = class_with_assignment().await;
    let (_, classmate_token) = class.add_learner("123456789013", true).await;
    let photo = jpeg("page 1");
    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &photo).await;
    assert_eq!(status, 200, "{sub}");
    let mut keys: Vec<&str> = sub.as_object().unwrap().keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        ["assignment_id", "file_type", "id", "received_at", "score", "student_id", "submitted_at", "teacher_feedback", "updated_at"]
    );
    assert!(class.pull(&class.learner_token).await["submissions"][0]["received_at"].is_i64(), "sync carries it too");
    assert_eq!((sub["id"].as_str(), sub["assignment_id"].as_str()), (Some("c1000000-0000-4000-8000-000000000001"), Some("asg-1")));
    assert_eq!((sub["student_id"].as_str(), sub["file_type"].as_str()), (Some(class.learner.as_str()), Some("IMAGE")));
    assert!(sub["score"].is_null() && sub["teacher_feedback"].is_null());
    let scope: (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT classroom_id, student_id FROM sync_revisions WHERE entity_table = 'assignment_submissions' AND entity_id = 'c1000000-0000-4000-8000-000000000001'",
    )
    .fetch_one(&class.hub.state().db)
    .await
    .unwrap();
    assert_eq!(scope, (Some(class.id.clone()), Some(class.learner.clone())));

    assert_eq!(class.pull(class.teacher()).await["submissions"][0]["id"], "c1000000-0000-4000-8000-000000000001");
    assert_eq!(class.pull(&class.learner_token).await["submissions"].as_array().unwrap().len(), 1);
    assert_eq!(class.pull(&classmate_token).await["submissions"], serde_json::json!([]), "classmates never see each other's homework");
    assert!(!class.pull(class.teacher()).await.to_string().contains("file_path"));

    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await, (200, "image/jpeg".to_owned(), photo.clone()));
    let with_query = format!("/api/submissions/c1000000-0000-4000-8000-000000000001/file?token={}", class.learner_token);
    let (status, _, bytes) = class.hub.get_file(&with_query, None, None).await;
    assert_eq!((status, bytes), (200, photo), "the owner opens it from an image view with ?token=");
    let (status, body) = class.hub.get("/api/submissions/c1000000-0000-4000-8000-000000000001/file", Some(&classmate_token)).await;
    assert_eq!((status, message(&body)), (403, "Not your submission"));
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    assert_eq!(class.hub.get("/api/submissions/c1000000-0000-4000-8000-000000000001/file", Some(&other_teacher)).await.0, 403);
    assert_eq!(class.hub.get("/api/submissions/missing/file", Some(class.teacher())).await.0, 404);
    assert_eq!(class.hub.get("/api/submissions/c1000000-0000-4000-8000-000000000001/file", None).await.0, 401);
    class.hub.stop().await;
}

#[tokio::test]
async fn a_pdf_is_a_document() {
    let class = class_with_assignment().await;
    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", PDF).await;
    assert_eq!((status, sub["file_type"].as_str()), (200, Some("DOCUMENT")), "{sub}");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await, (200, "application/pdf".to_owned(), PDF.to_vec()));
    class.hub.stop().await;
}

#[tokio::test]
async fn a_submission_records_when_it_was_turned_in_and_when_it_arrived() {
    let class = class_with_assignment().await;
    let before = now_ms();
    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("A")).await;
    assert_eq!(status, 200, "{sub}");
    let received = sub["received_at"].as_i64().expect("received_at");
    assert!((before..=now_ms()).contains(&received), "{received}");
    assert_eq!((sub["submitted_at"].as_i64(), sub["updated_at"].as_i64()), (Some(received), Some(received)), "no turned_in_at: arrival time");

    let pressed = before - 60_000;
    let (status, sub) = submit_at(&class, "c1000000-0000-4000-8000-000000000002", &jpeg("B"), &pressed.to_string()).await;
    assert_eq!(status, 200, "{sub}");
    assert_eq!(sub["submitted_at"].as_i64(), Some(pressed), "the learner's Turn in time");
    let received = sub["received_at"].as_i64().unwrap();
    assert!(received >= before);
    let stored: (i64, i64) = sqlx::query_as("SELECT submitted_at, received_at FROM assignment_submissions WHERE id = 'c1000000-0000-4000-8000-000000000002'")
        .fetch_one(&class.hub.state().db)
        .await
        .unwrap();
    assert_eq!(stored, (pressed, received));

    let (status, sub) = submit_at(&class, "c1000000-0000-4000-8000-000000000003", &jpeg("C"), &(now_ms() + 3_600_000).to_string()).await;
    assert_eq!(status, 200, "{sub}");
    assert_eq!(sub["submitted_at"], sub["received_at"], "a device clock ahead of the Hub is clamped to the arrival");
    class.hub.stop().await;
}

#[tokio::test]
async fn late_work_is_refused_unless_the_assignment_allows_it() {
    let class = class_with_assignment().await;
    let deadline = now_ms() - 10_000;
    class.set_due("asg-1", deadline, false).await;
    let (status, body) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-0000000000a1", &jpeg("A")).await;
    assert_eq!((status, code(&body)), (409, "LATE_SUBMISSION"), "{body}");
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    assert!(class.files_in("submissions").is_empty() && class.files_in("tmp").is_empty());

    let (status, sub) = submit_at(&class, "c1000000-0000-4000-8000-0000000000a2", &jpeg("A"), &(deadline - 1).to_string()).await;
    assert_eq!(status, 200, "pressed before the deadline, arrived after: {sub}");
    assert_eq!(sub["submitted_at"].as_i64(), Some(deadline - 1));
    assert!(sub["received_at"].as_i64().unwrap() > deadline);

    class.set_due("asg-1", deadline, true).await;
    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-0000000000a3", &jpeg("B")).await;
    assert_eq!(status, 200, "{sub}");
    assert_eq!(sub["submitted_at"], sub["received_at"]);
    class.hub.stop().await;
}

#[tokio::test]
async fn an_identical_retry_gets_the_stored_row_even_after_grading_and_the_deadline() {
    let class = class_with_assignment().await;
    let photo = jpeg("A");
    let (_, saved) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &photo).await;
    class
        .exec("UPDATE assignment_submissions SET score = 8, teacher_feedback = 'Good', updated_at = updated_at + 1 WHERE id = 'c1000000-0000-4000-8000-000000000001'", &[])
        .await;
    class.set_due("asg-1", 1, false).await;
    let (status, retry) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &photo).await;
    assert_eq!(status, 200, "{retry}");
    assert_eq!((retry["score"].as_i64(), retry["teacher_feedback"].as_str()), (Some(8), Some("Good")));
    for key in ["submitted_at", "received_at"] {
        assert_eq!(retry[key], saved[key], "{key} unchanged");
    }
    assert_eq!(retry["updated_at"].as_i64(), Some(saved["updated_at"].as_i64().unwrap() + 1), "the stored row");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await.2, photo);
    assert_eq!(class.files_in("submissions").len(), 1, "the retry's copy is not kept");
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn graded_work_cannot_be_replaced() {
    let class = class_with_assignment().await;
    assert_eq!(submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("A")).await.0, 200);
    class.exec("UPDATE assignment_submissions SET score = 8 WHERE id = 'c1000000-0000-4000-8000-000000000001'", &[]).await;
    for id in ["c1000000-0000-4000-8000-000000000001", "c1000000-0000-4000-8000-000000000002"] {
        let (status, body) = submit(&class, &class.learner_token, id, &jpeg("B")).await;
        assert_eq!((status, code(&body)), (409, "ALREADY_GRADED"), "{id}: {body}");
    }
    class.set_due("asg-1", 1, false).await;
    let (status, body) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000003", &jpeg("C")).await;
    assert_eq!((status, code(&body)), (409, "ALREADY_GRADED"), "graded is checked before late: {body}");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await.2, jpeg("A"));
    assert_eq!(class.files_in("submissions").len(), 1);
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn before_grading_new_work_replaces_the_old_row_and_its_id() {
    let class = class_with_assignment().await;
    assert_eq!(submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("A")).await.0, 200);
    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("B")).await;
    assert_eq!((status, sub["id"].as_str()), (200, Some("c1000000-0000-4000-8000-000000000001")), "same id, another file: {sub}");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await.2, jpeg("B"));

    let (status, sub) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000002", &jpeg("C")).await;
    assert_eq!((status, sub["id"].as_str()), (200, Some("c1000000-0000-4000-8000-000000000002")), "{sub}");
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 1, "one row per learner");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000002", class.teacher()).await.2, jpeg("C"));
    assert_eq!(class.hub.get("/api/submissions/c1000000-0000-4000-8000-000000000001/file", Some(class.teacher())).await.0, 404);
    let tombstone = json!({"entity_table": "assignment_submissions", "entity_id": "c1000000-0000-4000-8000-000000000001"});
    for token in [class.teacher(), class.learner_token.as_str()] {
        let pulled = class.pull(token).await;
        assert!(pulled["deleted"].as_array().unwrap().contains(&tombstone), "{pulled}");
        assert_eq!(pulled["submissions"].as_array().unwrap().len(), 1);
    }
    assert_eq!(class.files_in("submissions").len(), 1, "replaced files are deleted");
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn two_resubmits_at_once_leave_one_row_and_one_file() {
    let class = class_with_assignment().await;
    let (photo_a, photo_b) = (jpeg("A"), jpeg("B"));
    let (a, b) = tokio::join!(
        submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &photo_a),
        submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &photo_b),
    );
    assert_eq!((a.0, b.0), (200, 200), "{} {}", a.1, b.1);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 1);
    assert_eq!(class.files_in("submissions").len(), 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn a_refused_resubmission_keeps_the_old_file() {
    let class = class_with_assignment().await;
    assert_eq!(submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("A")).await.0, 200);
    let (status, body) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", HTML).await;
    assert_eq!((status, message(&body)), (400, "File must be a photo (JPEG or PNG), PDF or Word document"));
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", &class.learner_token).await.2, jpeg("A"));
    assert_eq!(class.files_in("submissions").len(), 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn who_may_submit() {
    let class = class_with_assignment().await;
    let photo = jpeg("x");
    let (status, body) = submit(&class, class.teacher(), "t-1", &photo).await;
    assert_eq!((status, message(&body)), (403, "Learners only"));
    let (_, pending) = class.add_learner("123456789013", false).await;
    let outsider = class.hub.seed_user("123456789014", "Outsider", lara_hub::auth::Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    for token in [&pending, &outsider] {
        let (status, body) = submit(&class, token, "o-1", &photo).await;
        assert_eq!((status, message(&body)), (403, "Not your class"));
    }
    let (status, body) = class.upload("/api/assignments/missing/submit", Some(&class.learner_token), submission_form("d1000000-0000-4000-8000-000000000001", &photo)).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    class.assignment("asg-archived").await;
    class.exec("UPDATE assignments SET archived_at = 1 WHERE id = 'asg-archived'", &[]).await;
    let (status, _) = class.upload("/api/assignments/asg-archived/submit", Some(&class.learner_token), submission_form("d2000000-0000-4000-8000-000000000002", &photo)).await;
    assert_eq!(status, 404, "a deleted assignment is gone for learners");

    let (_, classmate) = class.add_learner("123456789015", true).await;
    assert_eq!(submit(&class, &classmate, "c1000000-0000-4000-8000-0000000000b1", &jpeg("theirs")).await.0, 200);
    let (status, body) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-0000000000b1", &jpeg("mine")).await;
    assert_eq!((status, message(&body)), (403, "This submission id belongs to other work"));
    assert_eq!(file(&class, "c1000000-0000-4000-8000-0000000000b1", class.teacher()).await.2, jpeg("theirs"), "the classmate's work is untouched");
    assert_eq!(class.files_in("submissions").len(), 1);
    // Replacing own work under someone else's id is refused too, and both rows stay.
    assert_eq!(submit(&class, &class.learner_token, "c1000000-0000-4000-8000-0000000000b2", &jpeg("mine")).await.0, 200);
    let (status, body) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-0000000000b1", &jpeg("again")).await;
    assert_eq!((status, message(&body)), (403, "This submission id belongs to other work"));
    assert_eq!(file(&class, "c1000000-0000-4000-8000-0000000000b1", class.teacher()).await.2, jpeg("theirs"));
    assert_eq!(file(&class, "c1000000-0000-4000-8000-0000000000b2", class.teacher()).await.2, jpeg("mine"));
    assert_eq!(class.files_in("submissions").len(), 2);
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn bad_forms_are_400() {
    let class = class_with_assignment().await;
    let no_id = Form::new().part("file", file_part("a.jpg", &jpeg("x")));
    let no_file = Form::new().text("submission_id", "c1000000-0000-4000-8000-000000000001");
    for (form, expected) in [(no_id, "Missing: submission_id"), (no_file, "Missing file")] {
        let (status, body) = class.upload(SUBMIT, Some(&class.learner_token), form).await;
        assert_eq!((status, message(&body)), (400, expected));
    }
    assert_eq!(submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", b"JPEGDATA").await.0, 400, "placeholder bytes are not a photo");
    let (status, body) = submit_at(&class, "c1000000-0000-4000-8000-000000000001", &jpeg("x"), "yesterday").await;
    assert_eq!((status, message(&body)), (400, "turned_in_at must be an integer epoch-millisecond timestamp"));
    assert!(class.files_in("submissions").is_empty());
    assert!(class.files_in("tmp").is_empty());
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    class.hub.stop().await;
}

#[tokio::test]
async fn an_archived_class_refuses_new_work_but_answers_a_retry() {
    let class = class_with_assignment().await;
    let (_, classmate) = class.add_learner("123456789013", true).await;
    let (_, saved) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("A")).await;
    class.archive().await;
    let (status, body) = submit(&class, &classmate, "c1000000-0000-4000-8000-000000000002", &jpeg("new")).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, retry) = submit(&class, &class.learner_token, "c1000000-0000-4000-8000-000000000001", &jpeg("B")).await;
    assert_eq!(status, 200, "a device that lost the answer is told its work is safe");
    assert_eq!(retry["submitted_at"], saved["submitted_at"], "nothing changed");
    assert_eq!(file(&class, "c1000000-0000-4000-8000-000000000001", class.teacher()).await.2, jpeg("A"));
    assert_eq!(class.files_in("submissions").len(), 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn an_oversized_homework_upload_is_413() {
    let class = class_with_assignment().await;
    let fields = [("submission_id", "c1000000-0000-4000-8000-000000000001")];
    assert_file_too_large(raw_upload(&class.hub, SUBMIT, &class.learner_token, &fields, RawBody::Declared(300 * 1024 * 1024)).await);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}
