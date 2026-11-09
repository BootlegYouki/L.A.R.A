//! Step 3.1: `GET /api/materials/{id}/chunks` on the real Hub, and lesson text extracted on upload.

mod common;

use common::uploads::{material_form, Class, PDF};
use lara_hub::db::{begin_immediate, material_chunks};
use lara_hub::services::document_parser::ChunkDraft;
use serde_json::Value;

async fn upload(class: &Class, file_type: &str, bytes: &[u8]) -> Value {
    let (status, material) = class.upload("/api/materials", Some(class.teacher()), material_form(&class.id, file_type, bytes)).await;
    assert_eq!(status, 200, "{material}");
    material
}

fn draft(order_index: i64, text: &str) -> ChunkDraft {
    ChunkDraft { order_index, heading: Some("Plants".to_owned()), text: text.to_owned(), token_estimate: 3 }
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
