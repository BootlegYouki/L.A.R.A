//! Step 3.3: `POST /api/materials` and `GET /api/materials/{id}/download` on the real Hub.

mod common;

use common::uploads::{file_part, material_form, raw_upload, Class, RawBody, HTML, MP4, PDF};
use reqwest::multipart::Form;
use serde_json::Value;

async fn upload_pdf(class: &Class, token: &str) -> Value {
    let (status, material) = class.upload("/api/materials", Some(token), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!(status, 200, "{material}");
    material
}

fn error_code(body: &Value) -> &str {
    body["error"]["code"].as_str().unwrap_or("")
}

#[tokio::test]
async fn teacher_uploads_a_pdf_and_gets_the_contract_material() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    let mut keys: Vec<&str> = material.as_object().unwrap().keys().map(String::as_str).collect();
    keys.sort_unstable();
    assert_eq!(
        keys,
        ["announcement_id", "assignment_id", "classroom_id", "download_url", "file_size_bytes", "file_type", "id", "title", "topic_id", "updated_at"]
    );
    let id = material["id"].as_str().unwrap();
    assert_eq!(material["classroom_id"], class.id.as_str());
    assert_eq!((material["title"].as_str(), material["file_type"].as_str()), (Some("Lesson 1"), Some("DOCUMENT")));
    assert_eq!(material["file_size_bytes"], PDF.len());
    assert_eq!(material["download_url"], format!("/api/materials/{id}/download"));
    assert!(material["topic_id"].is_null() && material["assignment_id"].is_null() && material["announcement_id"].is_null());

    let stored = class.files_in("materials");
    assert_eq!(stored.len(), 1);
    assert!(!stored[0].contains("lesson"), "the client's file name is never used: {}", stored[0]);
    assert!(class.files_in("tmp").is_empty());
    let scope: (Option<String>, Option<String>) =
        sqlx::query_as("SELECT classroom_id, student_id FROM sync_revisions WHERE entity_table = 'materials' AND entity_id = ?")
            .bind(id)
            .fetch_one(&class.hub.state().db)
            .await
            .unwrap();
    assert_eq!(scope, (Some(class.id.clone()), None), "every member of the class pulls it");

    let pulled = class.pull(&class.learner_token).await;
    assert_eq!(pulled["materials"].as_array().unwrap().iter().filter(|m| m["id"] == id).count(), 1);
    let text = pulled.to_string();
    assert!(!text.contains("file_path") && !text.contains("mime_type") && !text.contains(&stored[0]));
    class.hub.stop().await;
}

#[tokio::test]
async fn a_video_is_stored_as_a_video() {
    let class = Class::start().await;
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "VIDEO", MP4)).await;
    assert_eq!((status, material["file_type"].as_str()), (200, Some("VIDEO")), "{material}");
    assert!(class.files_in("materials")[0].ends_with(".mp4"));
    class.hub.stop().await;
}

#[tokio::test]
async fn attachments_link_to_their_post() {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    class.announcement("ann-1").await;
    class.topic("topic-1").await;
    for (field, id) in [("assignment_id", "asg-1"), ("announcement_id", "ann-1"), ("topic_id", "topic-1")] {
        let form = material_form(&class.id, "WORKSHEET", PDF).text(field, id);
        let (status, material) = class.upload("/api/materials", Some(class.teacher()), form).await;
        assert_eq!((status, material[field].as_str()), (200, Some(id)), "{material}");
    }
    let (other_class, _) = class.other_class().await;
    let mut tx = lara_hub::db::begin_immediate(&class.hub.state().db).await.unwrap();
    sqlx::query("INSERT INTO assignments (id, classroom_id, title, due_date, created_at, updated_at) VALUES ('other-asg', ?, 'x', 1, 1, 1)")
        .bind(&other_class)
        .execute(&mut *tx)
        .await
        .unwrap();
    tx.commit().await.unwrap();
    class.assignment("archived-asg").await;
    class.exec("UPDATE assignments SET archived_at = 1 WHERE id = 'archived-asg'", &[]).await;
    for (field, id) in [("assignment_id", "other-asg"), ("assignment_id", "missing"), ("assignment_id", "archived-asg"), ("topic_id", "missing"), ("announcement_id", "missing")] {
        let form = material_form(&class.id, "WORKSHEET", PDF).text(field, id);
        let (status, body) = class.upload("/api/materials", Some(class.teacher()), form).await;
        assert_eq!((status, error_code(&body)), (404, "NOT_FOUND"), "{field}={id}: {body}");
    }
    assert_eq!(class.files_in("materials").len(), 3, "refused attachments keep no file");
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn who_may_upload() {
    let class = Class::start().await;
    let (status, body) = class.upload("/api/materials", Some(&class.learner_token), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!((status, body["error"]["message"].as_str()), (403, Some("Teacher only")));
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    assert_eq!(class.upload("/api/materials", Some(&other_teacher), material_form(&class.id, "DOCUMENT", PDF)).await.0, 403);
    assert_eq!(class.upload("/api/materials", Some(class.teacher()), material_form("no-such-class", "DOCUMENT", PDF)).await.0, 404);
    assert_eq!(class.upload("/api/materials", None, material_form(&class.id, "DOCUMENT", PDF)).await.0, 401);
    assert!(class.files_in("materials").is_empty());

    let co_teacher = class.co_teacher("T-0003").await;
    upload_pdf(&class, &co_teacher).await;
    class.archive().await;
    let (status, body) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!((status, body["error"]["message"].as_str()), (403, Some("This class is archived")));
    assert_eq!(class.files_in("materials").len(), 1, "only the co-teacher's upload is kept");
    assert!(class.files_in("tmp").is_empty());
    assert_eq!(class.count("SELECT COUNT(*) FROM materials").await, 1);
    class.hub.stop().await;
}

#[tokio::test]
async fn bad_forms_are_400_and_store_nothing() {
    let class = Class::start().await;
    let no_title = Form::new()
        .text("classroom_id", class.id.clone())
        .text("file_type", "DOCUMENT")
        .part("file", file_part("a.pdf", PDF));
    let no_file = Form::new().text("classroom_id", class.id.clone()).text("title", "x").text("file_type", "DOCUMENT");
    let cases = [
        (no_title, "Missing: title"),
        (no_file, "Missing file"),
        (material_form(&class.id, "AUDIO", PDF), "Invalid file_type"),
        (material_form(&class.id, "VIDEO", PDF), "File must be a video (MP4 or WebM)"),
        (material_form(&class.id, "DOCUMENT", HTML), "File must be a PDF, Word document or image"),
        (material_form(&class.id, "DOCUMENT", MP4), "File must be a PDF, Word document or image"),
        (material_form(&class.id, "DOCUMENT", b"PDF"), "File must be a PDF, Word document or image"),
    ];
    for (form, message) in cases {
        let (status, body) = class.upload("/api/materials", Some(class.teacher()), form).await;
        assert_eq!((status, body["error"]["message"].as_str()), (400, Some(message)), "{body}");
    }
    let (status, body) = class.hub.post_json("/api/materials", &serde_json::json!({"title": "x"}), Some(class.teacher())).await;
    assert_eq!((status, error_code(&body)), (400, "BAD_REQUEST"), "JSON instead of multipart");
    assert!(class.files_in("materials").is_empty());
    assert!(class.files_in("tmp").is_empty());
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'materials'").await, 0);
    class.hub.stop().await;
}

#[tokio::test]
async fn checks_after_the_file_part_leave_nothing_stored() {
    let class = Class::start().await;
    let (other_class, _) = class.other_class().await;
    // The file arrives first, so the Hub has already written it before it reads the class.
    let form = Form::new()
        .part("file", file_part("lesson.pdf", PDF))
        .text("title", "Lesson")
        .text("file_type", "DOCUMENT")
        .text("classroom_id", other_class);
    let (status, body) = class.upload("/api/materials", Some(class.teacher()), form).await;
    assert_eq!(status, 403, "{body}");
    assert!(class.files_in("materials").is_empty());
    assert!(class.files_in("tmp").is_empty());
    class.hub.stop().await;
}

#[tokio::test]
async fn a_300_mb_upload_is_refused_and_leaves_no_file() {
    let class = Class::start().await;
    let fields = [("classroom_id", class.id.as_str()), ("title", "Big video"), ("file_type", "VIDEO")];
    for body in [RawBody::Declared(300 * 1024 * 1024), RawBody::Chunked(300 * 1024 * 1024)] {
        let (status, answer) = raw_upload(&class.hub, "/api/materials", class.teacher(), &fields, body).await;
        assert_eq!((status, error_code(&answer)), (413, "FILE_TOO_LARGE"), "{answer}");
        assert!(class.files_in("tmp").is_empty(), "the partial upload is deleted");
        assert!(class.files_in("materials").is_empty());
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM materials").await, 0);
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'materials'").await, 0);
    class.hub.stop().await;
}

#[tokio::test]
async fn members_download_the_bytes_with_range_and_token() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    let url = material["download_url"].as_str().unwrap();
    let (status, headers, bytes) = class.hub.get_file(url, Some(&class.learner_token), None).await;
    assert_eq!((status, bytes.as_slice()), (200, PDF));
    assert_eq!(headers["content-type"], "application/octet-stream");
    assert_eq!(headers["x-content-type-options"], "nosniff");
    let with_query = format!("{url}?token={}", class.learner_token);
    let (status, _, bytes) = class.hub.get_file(&with_query, None, None).await;
    assert_eq!((status, bytes.as_slice()), (200, PDF), "players that cannot send headers use ?token=");
    let (status, headers, bytes) = class.hub.get_file(url, Some(&class.learner_token), Some("bytes=0-3")).await;
    assert_eq!((status, bytes.as_slice()), (206, &b"%PDF"[..]));
    assert_eq!(headers["content-range"], format!("bytes 0-3/{}", PDF.len()));
    let co_teacher = class.co_teacher("T-0003").await;
    assert_eq!(class.hub.get_file(url, Some(&co_teacher), None).await.0, 200);
    class.hub.stop().await;
}

#[tokio::test]
async fn outsiders_cannot_download() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    let url = material["download_url"].as_str().unwrap();
    assert_eq!(class.hub.get_file(url, None, None).await.0, 401);
    assert_eq!(class.hub.get_file(&format!("{url}?token=not-a-session"), None, None).await.0, 401);
    let (status, body) = class.hub.get("/api/materials/no-such-material/download", Some(class.teacher())).await;
    assert_eq!((status, body["error"]["message"].as_str()), (404, Some("Material not found")));
    let (_, pending) = class.add_learner("123456789013", false).await;
    let outsider = class.hub.seed_user("123456789014", "Outsider", lara_hub::auth::Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    let admin = class.hub.seed_user("ADMIN-0001", "Hub Admin", lara_hub::auth::Role::Admin, "1234").await;
    let admin = class.hub.open_session(admin).await;
    for token in [&pending, &outsider, &other_teacher, &admin] {
        let (status, body) = class.hub.get(url, Some(token)).await;
        assert_eq!((status, body["error"]["message"].as_str()), (403, Some("Not your class")));
    }
    class.hub.stop().await;
}

#[tokio::test]
async fn a_missing_file_is_404_not_500() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    let stored = class.files_in("materials").remove(0);
    std::fs::remove_file(class.hub.state().storage.root().join("materials").join(stored)).unwrap();
    let (status, body) = class.hub.get(material["download_url"].as_str().unwrap(), Some(&class.learner_token)).await;
    assert_eq!((status, body["error"]["code"].as_str()), (404, Some("NOT_FOUND")));
    class.hub.stop().await;
}

#[tokio::test]
async fn downloads_still_work_in_an_archived_class() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    class.archive().await;
    let (status, _, bytes) = class.hub.get_file(material["download_url"].as_str().unwrap(), Some(&class.learner_token), None).await;
    assert_eq!((status, bytes.as_slice()), (200, PDF));
    class.hub.stop().await;
}
