//! Step 3.3: `POST /api/assignments/{id}/submit` and `GET /api/submissions/{id}/file` on the real Hub.

mod common;

use common::uploads::{assert_file_too_large, file_part, jpeg, raw_upload, submission_form, Class, RawBody, HTML, PDF};
use reqwest::multipart::Form;
use serde_json::Value;

const SUBMIT: &str = "/api/assignments/asg-1/submit";

async fn class_with_assignment() -> Class {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    class
}

async fn submit(class: &Class, token: &str, id: &str, bytes: &[u8]) -> (u16, Value) {
    class.upload(SUBMIT, Some(token), submission_form(id, bytes)).await
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
    let (status, sub) = submit(&class, &class.learner_token, "sub-1", &photo).await;
    assert_eq!(status, 200, "{sub}");
    let mut keys: Vec<&str> = sub.as_object().unwrap().keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(keys, ["assignment_id", "file_type", "id", "score", "student_id", "submitted_at", "teacher_feedback", "updated_at"]);
    assert_eq!((sub["id"].as_str(), sub["assignment_id"].as_str()), (Some("sub-1"), Some("asg-1")));
    assert_eq!((sub["student_id"].as_str(), sub["file_type"].as_str()), (Some(class.learner.as_str()), Some("IMAGE")));
    assert!(sub["score"].is_null() && sub["teacher_feedback"].is_null());
    let scope: (Option<String>, Option<String>) = sqlx::query_as(
        "SELECT classroom_id, student_id FROM sync_revisions WHERE entity_table = 'assignment_submissions' AND entity_id = 'sub-1'",
    )
    .fetch_one(&class.hub.state().db)
    .await
    .unwrap();
    assert_eq!(scope, (Some(class.id.clone()), Some(class.learner.clone())));

    assert_eq!(class.pull(class.teacher()).await["submissions"][0]["id"], "sub-1");
    assert_eq!(class.pull(&class.learner_token).await["submissions"].as_array().unwrap().len(), 1);
    assert_eq!(class.pull(&classmate_token).await["submissions"], serde_json::json!([]), "classmates never see each other's homework");
    assert!(!class.pull(class.teacher()).await.to_string().contains("file_path"));

    assert_eq!(file(&class, "sub-1", class.teacher()).await, (200, "image/jpeg".to_owned(), photo.clone()));
    let with_query = format!("/api/submissions/sub-1/file?token={}", class.learner_token);
    let (status, _, bytes) = class.hub.get_file(&with_query, None, None).await;
    assert_eq!((status, bytes), (200, photo), "the owner opens it from an image view with ?token=");
    let (status, body) = class.hub.get("/api/submissions/sub-1/file", Some(&classmate_token)).await;
    assert_eq!((status, message(&body)), (403, "Not your submission"));
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    assert_eq!(class.hub.get("/api/submissions/sub-1/file", Some(&other_teacher)).await.0, 403);
    assert_eq!(class.hub.get("/api/submissions/missing/file", Some(class.teacher())).await.0, 404);
    assert_eq!(class.hub.get("/api/submissions/sub-1/file", None).await.0, 401);
    class.hub.stop().await;
}

#[tokio::test]
async fn a_pdf_is_a_document() {
    let class = class_with_assignment().await;
    let (status, sub) = submit(&class, &class.learner_token, "sub-1", PDF).await;
    assert_eq!((status, sub["file_type"].as_str()), (200, Some("DOCUMENT")), "{sub}");
    assert_eq!(file(&class, "sub-1", class.teacher()).await, (200, "application/pdf".to_owned(), PDF.to_vec()));
    class.hub.stop().await;
}

#[tokio::test]
async fn resubmitting_replaces_the_file_and_keeps_one_row_and_the_grade() {
    let class = class_with_assignment().await;
    assert_eq!(submit(&class, &class.learner_token, "sub-1", &jpeg("A")).await.0, 200);
    class.exec("UPDATE assignment_submissions SET score = 8, teacher_feedback = 'Good' WHERE id = 'sub-1'", &[]).await;
    assert_eq!(submit(&class, &class.learner_token, "sub-1", &jpeg("B")).await.0, 200, "an offline retry");
    let (status, sub) = submit(&class, &class.learner_token, "sub-2", &jpeg("C")).await;
    assert_eq!(status, 200, "{sub}");
    assert_eq!((sub["id"].as_str(), sub["score"].as_i64()), (Some("sub-1"), Some(8)), "one row per learner, grade kept");
    assert_eq!(sub["teacher_feedback"], "Good");
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 1);
    assert_eq!(file(&class, "sub-1", class.teacher()).await.2, jpeg("C"));
    assert_eq!(class.files_in("submissions").len(), 1, "replaced files are deleted");
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn two_resubmits_at_once_leave_one_row_and_one_file() {
    let class = class_with_assignment().await;
    let (photo_a, photo_b) = (jpeg("A"), jpeg("B"));
    let (a, b) = tokio::join!(
        submit(&class, &class.learner_token, "sub-1", &photo_a),
        submit(&class, &class.learner_token, "sub-1", &photo_b),
    );
    assert_eq!((a.0, b.0), (200, 200), "{} {}", a.1, b.1);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 1);
    assert_eq!(class.files_in("submissions").len(), 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn a_refused_resubmission_keeps_the_old_file() {
    let class = class_with_assignment().await;
    assert_eq!(submit(&class, &class.learner_token, "sub-1", &jpeg("A")).await.0, 200);
    let (status, body) = submit(&class, &class.learner_token, "sub-1", HTML).await;
    assert_eq!((status, message(&body)), (400, "File must be a photo (JPEG or PNG), PDF or Word document"));
    assert_eq!(file(&class, "sub-1", &class.learner_token).await.2, jpeg("A"));
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
    let (status, body) = class.upload("/api/assignments/missing/submit", Some(&class.learner_token), submission_form("m-1", &photo)).await;
    assert_eq!((status, message(&body)), (404, "Assignment not found"));
    class.assignment("asg-archived").await;
    class.exec("UPDATE assignments SET archived_at = 1 WHERE id = 'asg-archived'", &[]).await;
    let (status, _) = class.upload("/api/assignments/asg-archived/submit", Some(&class.learner_token), submission_form("a-1", &photo)).await;
    assert_eq!(status, 404, "a deleted assignment is gone for learners");

    let (_, classmate) = class.add_learner("123456789015", true).await;
    assert_eq!(submit(&class, &classmate, "taken", &jpeg("theirs")).await.0, 200);
    let (status, body) = submit(&class, &class.learner_token, "taken", &jpeg("mine")).await;
    assert_eq!((status, message(&body)), (403, "This submission id belongs to other work"));
    assert_eq!(file(&class, "taken", class.teacher()).await.2, jpeg("theirs"), "the classmate's work is untouched");
    assert_eq!(class.files_in("submissions").len(), 1);
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn bad_forms_are_400() {
    let class = class_with_assignment().await;
    let no_id = Form::new().part("file", file_part("a.jpg", &jpeg("x")));
    let no_file = Form::new().text("submission_id", "sub-1");
    for (form, expected) in [(no_id, "Missing: submission_id"), (no_file, "Missing file")] {
        let (status, body) = class.upload(SUBMIT, Some(&class.learner_token), form).await;
        assert_eq!((status, message(&body)), (400, expected));
    }
    assert_eq!(submit(&class, &class.learner_token, "sub-1", b"JPEGDATA").await.0, 400, "placeholder bytes are not a photo");
    assert!(class.files_in("submissions").is_empty());
    assert!(class.files_in("tmp").is_empty());
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    class.hub.stop().await;
}

#[tokio::test]
async fn an_archived_class_refuses_new_work_but_answers_a_retry() {
    let class = class_with_assignment().await;
    let (_, classmate) = class.add_learner("123456789013", true).await;
    let (_, saved) = submit(&class, &class.learner_token, "sub-1", &jpeg("A")).await;
    class.archive().await;
    let (status, body) = submit(&class, &classmate, "sub-2", &jpeg("new")).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, retry) = submit(&class, &class.learner_token, "sub-1", &jpeg("B")).await;
    assert_eq!(status, 200, "a device that lost the answer is told its work is safe");
    assert_eq!(retry["submitted_at"], saved["submitted_at"], "nothing changed");
    assert_eq!(file(&class, "sub-1", class.teacher()).await.2, jpeg("A"));
    assert_eq!(class.files_in("submissions").len(), 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn an_oversized_homework_upload_is_413() {
    let class = class_with_assignment().await;
    let fields = [("submission_id", "sub-1")];
    assert_file_too_large(raw_upload(&class.hub, SUBMIT, &class.learner_token, &fields, RawBody::Declared(300 * 1024 * 1024)).await);
    assert_eq!(class.count("SELECT COUNT(*) FROM assignment_submissions").await, 0);
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}
