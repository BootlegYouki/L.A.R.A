//! Step 3.3: `POST /api/materials` and `GET /api/materials/{id}/download` on the real Hub.

mod common;

use common::uploads::{assert_file_too_large, file_part, material_form, raw_upload, Class, RawBody, HTML, MP4, PDF};
use reqwest::multipart::Form;
use serde_json::Value;

async fn upload_pdf(class: &Class, token: &str) -> Value {
    let (status, material) = class.upload("/api/materials", Some(token), material_form(&class.id, "DOCUMENT", PDF)).await;
    assert_eq!(status, 200, "{material}");
    material
}

async fn upload_video(class: &Class) -> Value {
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "VIDEO", MP4)).await;
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
        assert_file_too_large(raw_upload(&class.hub, "/api/materials", class.teacher(), &fields, body).await);
        assert!(class.files_in("tmp").is_empty(), "the partial upload is deleted");
        assert!(class.files_in("materials").is_empty());
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM materials").await, 0);
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'materials'").await, 0);
    class.hub.stop().await;
}

#[tokio::test]
async fn members_download_the_bytes_with_a_token() {
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
    let co_teacher = class.co_teacher("T-0003").await;
    assert_eq!(class.hub.get_file(url, Some(&co_teacher), None).await.0, 200);
    class.hub.stop().await;
}

/// Contract #259: `Range` is for VIDEO downloads only. Any other file comes whole.
#[tokio::test]
async fn a_document_download_sends_the_whole_file_whatever_the_range() {
    let class = Class::start().await;
    let url = upload_pdf(&class, class.teacher()).await["download_url"].as_str().unwrap().to_owned();
    let (status, headers, bytes) = class.hub.get_file(&url, Some(&class.learner_token), Some("bytes=0-3")).await;
    assert_eq!((status, bytes.as_slice()), (200, PDF));
    assert!(headers.get("content-range").is_none());
    assert_eq!(headers["accept-ranges"], "none");
    class.hub.stop().await;
}

/// Contract #259 and the mock's `test_video_download_resumes_with_range_and_if_range`: Save for Home
/// resumes after a Wi-Fi drop, and starts over when the file changed.
#[tokio::test]
async fn a_video_download_resumes_with_range_and_if_range() {
    let class = Class::start().await;
    let url = upload_video(&class).await["download_url"].as_str().unwrap().to_owned();
    let token = class.learner_token.clone();
    let (status, headers, whole) = class.hub.get_file(&url, Some(&token), None).await;
    assert_eq!((status, whole.as_slice()), (200, MP4));
    assert_eq!(headers["accept-ranges"], "bytes");
    let etag = headers["etag"].to_str().unwrap().to_owned();

    let (status, headers, tail) = class.hub.get_file_with(&url, Some(&token), &[("Range", "bytes=10-"), ("If-Range", &etag)]).await;
    assert_eq!((status, tail.as_slice()), (206, &MP4[10..]));
    assert_eq!(headers["content-range"], format!("bytes 10-{}/{}", MP4.len() - 1, MP4.len()));
    assert_eq!(headers["etag"], etag.as_str());

    let (status, _, start) = class.hub.get_file(&url, Some(&token), Some("bytes=0-3")).await;
    assert_eq!((status, start.as_slice()), (206, &MP4[..4]), "a Range alone works too");

    let (status, _, again) = class.hub.get_file_with(&url, Some(&token), &[("Range", "bytes=10-"), ("If-Range", "\"stale\"")]).await;
    assert_eq!((status, again.as_slice()), (200, MP4), "a changed file restarts from byte 0");

    let past = format!("bytes={}-", MP4.len());
    let (status, headers, _) = class.hub.get_file(&url, Some(&token), Some(&past)).await;
    assert_eq!(status, 416);
    assert_eq!(headers["content-range"], format!("bytes */{}", MP4.len()));
    class.hub.stop().await;
}

/// Contract #259: `/stream` is for VIDEO materials. Streaming a video is tested in `streaming.rs` (3.2).
#[tokio::test]
async fn the_stream_route_refuses_a_material_that_is_not_a_video() {
    let class = Class::start().await;
    let pdf = upload_pdf(&class, class.teacher()).await["id"].as_str().unwrap().to_owned();
    let (status, body) = class.hub.get(&format!("/api/materials/{pdf}/stream"), Some(&class.learner_token)).await;
    assert_eq!((status, error_code(&body), body["error"]["message"].as_str()), (404, "NOT_FOUND", Some("Not a video")));
    let (status, body) = class.hub.get("/api/materials/no-such-material/stream", Some(&class.learner_token)).await;
    assert_eq!((status, body["error"]["message"].as_str()), (404, Some("Material not found")));
    let (_, outsider) = class.teacher_token("T-0002").await;
    assert_eq!(class.hub.get(&format!("/api/materials/{pdf}/stream"), Some(&outsider)).await.0, 403);
    let video = upload_video(&class).await["id"].as_str().unwrap().to_owned();
    assert_eq!(class.hub.get(&format!("/api/materials/{video}/stream"), Some(&outsider)).await.0, 403, "not their class");
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

// ---- step 3.4 (#54): rename and delete

fn lesson_pdf() -> Vec<u8> {
    std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/lesson.pdf")).unwrap()
}

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

async fn rename(class: &Class, id: &str, body: serde_json::Value, token: &str) -> (u16, Value) {
    class.hub.send_json(reqwest::Method::PATCH, &format!("/api/materials/{id}"), &body, Some(token)).await
}

async fn remove(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.request(reqwest::Method::DELETE, &format!("/api/materials/{id}"), Some(token)).await
}

async fn pull_from(class: &Class, cursor: i64) -> Value {
    let body = serde_json::json!({"cursor": cursor});
    let (status, pulled) = class.hub.post_json("/api/sync/pull", &body, Some(&class.learner_token)).await;
    assert_eq!(status, 200, "{pulled}");
    pulled
}

#[tokio::test]
async fn material_can_be_attached_renamed_and_deleted() {
    let class = Class::start().await;
    class.assignment("asg-1").await;
    let form = material_form(&class.id, "WORKSHEET", PDF).text("assignment_id", "asg-1");
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), form).await;
    assert_eq!(status, 200, "{material}");
    let id = material["id"].as_str().unwrap();

    let (status, renamed) = rename(&class, id, serde_json::json!({"title": "Worksheet 1"}), class.teacher()).await;
    assert_eq!(status, 200, "{renamed}");
    assert_eq!((renamed["title"].as_str(), renamed["assignment_id"].as_str()), (Some("Worksheet 1"), Some("asg-1")));
    assert_eq!(renamed["download_url"], material["download_url"]);
    assert!(renamed.get("uploaded_by").is_none() && renamed.get("file_path").is_none(), "{renamed}");
    let cursor = class.pull(&class.learner_token).await["next_cursor"].as_i64().unwrap();

    let (status, body) = remove(&class, id, class.teacher()).await;
    assert_eq!((status, body), (200, serde_json::json!({"success": true})));
    let deleted = pull_from(&class, cursor).await["deleted"].clone();
    assert!(deleted.as_array().unwrap().contains(&serde_json::json!({"entity_table": "materials", "entity_id": id})), "{deleted}");
    assert!(class.files_in("materials").is_empty(), "the file is removed from disk");
    let (status, _, _) = class.hub.get_file(&format!("/api/materials/{id}/download"), Some(&class.learner_token), None).await;
    assert_eq!(status, 404);
}

#[tokio::test]
async fn renaming_files_a_material_under_a_topic_and_back() {
    let class = Class::start().await;
    class.topic("t-1").await;
    let material = upload_pdf(&class, class.teacher()).await;
    let id = material["id"].as_str().unwrap();
    let (status, moved) = rename(&class, id, serde_json::json!({"topic_id": "t-1"}), class.teacher()).await;
    assert_eq!((status, moved["topic_id"].as_str(), moved["title"].as_str()), (200, Some("t-1"), Some("Lesson 1")));
    let (status, back) = rename(&class, id, serde_json::json!({"topic_id": null}), class.teacher()).await;
    assert_eq!((status, back["topic_id"].is_null()), (200, true));
    let (status, body) = rename(&class, id, serde_json::json!({"topic_id": "no-such-topic"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Topic not found"));
    let (status, body) = rename(&class, id, serde_json::json!({"title": ""}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "title cannot be empty"));
}

#[tokio::test]
async fn deleting_a_pdf_removes_its_chunks() {
    let class = Class::start().await;
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, "DOCUMENT", &lesson_pdf())).await;
    assert_eq!(status, 200, "{material}");
    let first = class.pull(&class.learner_token).await;
    let chunk_ids: Vec<Value> = first["material_chunks"].as_array().unwrap().iter().map(|c| c["id"].clone()).collect();
    assert!(chunk_ids.len() >= 3, "{first}");

    let (status, _) = remove(&class, material["id"].as_str().unwrap(), class.teacher()).await;
    assert_eq!(status, 200);
    assert_eq!(class.count("SELECT COUNT(*) FROM material_chunks").await, 0);
    let deleted = pull_from(&class, first["next_cursor"].as_i64().unwrap()).await["deleted"].clone();
    for chunk in chunk_ids {
        let entry = serde_json::json!({"entity_table": "material_chunks", "entity_id": chunk});
        assert!(deleted.as_array().unwrap().contains(&entry), "{entry} not in {deleted}");
    }
}

#[tokio::test]
async fn deleting_a_material_with_a_missing_file_still_succeeds() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    for name in class.files_in("materials") {
        std::fs::remove_file(class.hub.state().storage.root().join("materials").join(name)).unwrap();
    }
    let (status, body) = remove(&class, material["id"].as_str().unwrap(), class.teacher()).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(class.count("SELECT COUNT(*) FROM sync_revisions WHERE entity_table = 'materials' AND action = 'DELETE'").await, 1);
}

#[tokio::test]
async fn material_edits_need_a_writable_class_you_teach() {
    let class = Class::start().await;
    let material = upload_pdf(&class, class.teacher()).await;
    let id = material["id"].as_str().unwrap();
    let (_, other_teacher) = class.other_class().await;
    let rename_body = serde_json::json!({"title": "x"});
    for token in [class.learner_token.as_str(), other_teacher.as_str()] {
        assert_eq!(rename(&class, id, rename_body.clone(), token).await.0, 403);
        assert_eq!(remove(&class, id, token).await.0, 403);
    }
    let (status, body) = rename(&class, "no-such-id", rename_body.clone(), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Material not found"));
    let (status, body) = remove(&class, "no-such-id", class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Material not found"));
    let co = class.co_teacher("T-0002").await;
    assert_eq!(rename(&class, id, serde_json::json!({"title": "By the co-teacher"}), &co).await.0, 200);

    class.archive().await;
    let (status, body) = rename(&class, id, rename_body, class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    let (status, body) = remove(&class, id, class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    assert_eq!(class.files_in("materials").len(), 1, "nothing removed");
}
