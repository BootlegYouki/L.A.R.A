//! Step 3.1: `GET /api/materials/{id}/chunks` on the real Hub, and lesson text extracted on upload.

mod common;

use common::uploads::{jpeg, material_form, Class, MP4, PDF};
use lara_hub::db::{begin_immediate, material_chunks};
use lara_hub::services::document_parser::ChunkDraft;
use serde_json::{json, Value};

async fn upload(class: &Class, file_type: &str, bytes: &[u8]) -> Value {
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, file_type, bytes)).await;
    assert_eq!(status, 200, "{material}");
    material
}

fn draft(order_index: i64, text: &str) -> ChunkDraft {
    ChunkDraft { order_index, heading: Some("Plants".to_owned()), text: text.to_owned(), token_estimate: 3 }
}

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)).unwrap()
}

/// The material's chunks as a member gets them.
async fn chunks_of(class: &Class, material: &Value) -> Vec<Value> {
    let (status, body) = class.hub.get(&format!("/api/materials/{}/chunks", material["id"].as_str().unwrap()), Some(&class.learner_token)).await;
    assert_eq!(status, 200, "{body}");
    body.as_array().unwrap().clone()
}

fn headings(chunks: &[Value]) -> Vec<&str> {
    let mut out: Vec<&str> = chunks.iter().filter_map(|c| c["heading"].as_str()).collect();
    out.dedup();
    out
}

async fn pull(class: &Class, cursor: i64) -> (u16, Value) {
    class.hub.post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(&class.learner_token)).await
}

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

#[tokio::test]
async fn chunks_route_answers_members_only() {
    let class = Class::start().await;
    let id = upload(&class, "DOCUMENT", PDF).await["id"].as_str().unwrap().to_owned();
    let mut tx = begin_immediate(&class.hub.state().db).await.unwrap();
    material_chunks::insert_all(&mut tx, &id, &class.id, &[draft(2, "Roots drink water."), draft(1, "Leaves make food.")], 1)
        .await
        .unwrap();
    tx.commit().await.unwrap();
    let url = format!("/api/materials/{id}/chunks");

    for token in [class.learner_token.as_str(), class.teacher()] {
        let (status, body) = class.hub.get(&url, Some(token)).await;
        assert_eq!(status, 200, "{body}");
        let chunks = body.as_array().unwrap();
        assert_eq!(chunks.iter().map(|c| c["order_index"].as_i64().unwrap()).collect::<Vec<_>>(), [1, 2]);
        assert_eq!(chunks[0]["text"], "Leaves make food.");
        let mut keys: Vec<&str> = chunks[0].as_object().unwrap().keys().map(String::as_str).collect();
        keys.sort_unstable();
        assert_eq!(keys, ["heading", "id", "material_id", "order_index", "text"], "no token_estimate");
    }

    let outsider = class.hub.seed_user("123456789014", "Outsider", lara_hub::auth::Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    let (_, other_teacher) = class.teacher_token("T-0002").await;
    let admin = class.hub.seed_user("ADMIN-0001", "Hub Admin", lara_hub::auth::Role::Admin, "1234").await;
    let admin = class.hub.open_session(admin).await;
    for token in [&outsider, &other_teacher, &admin] {
        let (status, body) = class.hub.get(&url, Some(token)).await;
        assert_eq!((status, message(&body)), (403, "Not your class"));
    }
    assert_eq!(class.hub.get(&url, None).await.0, 401);
    let (status, _) = class.hub.get(&format!("{url}?token={}", class.learner_token), None).await;
    assert_eq!(status, 401, "?token= is for the media routes only");
    let (status, body) = class.hub.get("/api/materials/no-such-material/chunks", Some(class.teacher())).await;
    assert_eq!((status, message(&body)), (404, "Material not found"));

    class.archive().await;
    assert_eq!(class.hub.get(&url, Some(&class.learner_token)).await.0, 200, "an archived class stays readable");
    class.hub.stop().await;
}

#[tokio::test]
async fn uploading_a_pdf_stores_ordered_chunks() {
    let class = Class::start().await;
    let material = upload(&class, "DOCUMENT", &fixture("lesson.pdf")).await;
    let chunks = chunks_of(&class, &material).await;
    let n = chunks.len() as i64;
    assert!(n >= 3, "{chunks:?}");
    assert_eq!(chunks.iter().map(|c| c["order_index"].as_i64().unwrap()).collect::<Vec<_>>(), (1..=n).collect::<Vec<_>>(), "no gaps");
    assert_eq!(headings(&chunks)[..4], ["The Water Cycle", "Evaporation", "Condensation", "Precipitation"]);
    assert!(chunks[1]["text"].as_str().unwrap().contains("Paraño"), "{}", chunks[1]);
    let estimates: Vec<i64> = sqlx::query_scalar("SELECT token_estimate FROM material_chunks WHERE material_id = ?")
        .bind(material["id"].as_str().unwrap())
        .fetch_all(&class.hub.state().db)
        .await
        .unwrap();
    assert!(estimates.iter().all(|t| (1..=500).contains(t)), "{estimates:?}");
    class.hub.stop().await;
}

#[tokio::test]
async fn uploading_a_docx_stores_chunks_with_its_headings() {
    let class = Class::start().await;
    let material = upload(&class, "WORKSHEET", &fixture("lesson.docx.zip")).await;
    let chunks = chunks_of(&class, &material).await;
    let headings = headings(&chunks);
    let at = |h: &str| headings.iter().position(|x| *x == h).unwrap_or_else(|| panic!("{h} missing from {headings:?}"));
    assert!(at("Evaporation") < at("Condensation") && at("Condensation") < at("Precipitation"), "{headings:?}");
    assert!(!format!("{chunks:?}").contains("Quarter 1"), "the page header is not lesson text");
    class.hub.stop().await;
}

#[tokio::test]
async fn files_without_text_get_no_chunks() {
    let class = Class::start().await;
    for (file_type, bytes) in [("DOCUMENT", fixture("scanned.pdf")), ("DOCUMENT", jpeg("photo")), ("VIDEO", MP4.to_vec())] {
        let material = upload(&class, file_type, &bytes).await;
        assert_eq!(chunks_of(&class, &material).await, Vec::<Value>::new(), "{file_type} {}", material["id"]);
    }
    class.hub.stop().await;
}

#[tokio::test]
async fn a_broken_pdf_still_uploads_with_no_chunks() {
    let class = Class::start().await;
    let material = upload(&class, "DOCUMENT", PDF).await;
    assert_eq!(chunks_of(&class, &material).await, Vec::<Value>::new());
    class.hub.stop().await;
}

#[tokio::test]
async fn chunks_reach_learners_through_sync() {
    let class = Class::start().await;
    let (status, before) = pull(&class, 0).await;
    assert_eq!(status, 200, "{before}");
    let material = upload(&class, "DOCUMENT", &fixture("lesson.pdf")).await;
    let (status, after) = pull(&class, before["next_cursor"].as_i64().unwrap()).await;
    assert_eq!(status, 200, "{after}");
    let ids = |chunks: &[Value]| {
        let mut ids: Vec<String> = chunks.iter().map(|c| c["id"].as_str().unwrap().to_owned()).collect();
        ids.sort_unstable();
        ids
    };
    let synced = ids(after["material_chunks"].as_array().unwrap());
    assert!(!synced.is_empty());
    assert_eq!(synced, ids(&chunks_of(&class, &material).await));
    class.hub.stop().await;
}
