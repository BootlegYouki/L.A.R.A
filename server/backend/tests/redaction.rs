//! Step 4.5 (#61), TECH_SPEC section 10: the answer key never reaches a learner. Every learner route that
//! carries a quiz is scanned as raw JSON, not just checked field by field.

mod common;

use common::uploads::Class;
use serde_json::json;

#[tokio::test]
async fn answer_keys_never_reach_a_learner() {
    let class = Class::start().await;
    let body = json!({
        "classroom_id": class.id, "title": "Ecosystem", "time_limit_minutes": 15,
        "questions": [
            {"question_text": "Gumagawa ng pagkain ang halaman.", "question_type": "MULTIPLE_CHOICE",
             "options": ["Photosynthesis", "Evaporation"], "correct_answer": "Photosynthesis"},
            {"question_text": "Ano ang kinakain ng herbivore?", "question_type": "IDENTIFICATION",
             "correct_answer": "Halaman", "synonyms": ["plants"]},
        ],
    });
    let (status, quiz) = class.hub.post_json("/api/quizzes", &body, Some(class.teacher())).await;
    assert_eq!(status, 200, "{quiz}");
    let id = quiz["id"].as_str().unwrap();
    class.exec("UPDATE quizzes SET status = 'ACTIVE', started_at = 1000 WHERE id = ?", &[id]).await;

    let token = Some(class.learner_token.as_str());
    let payloads = [
        ("GET /api/quizzes/{id}", class.hub.get(&format!("/api/quizzes/{id}"), token).await),
        ("GET /api/quizzes/active", class.hub.get("/api/quizzes/active", token).await),
        ("POST /api/quizzes/{id}/begin", class.hub.post_json(&format!("/api/quizzes/{id}/begin"), &json!({}), token).await),
        ("POST /api/sync/pull", class.hub.post_json("/api/sync/pull", &json!({"cursor": 0}), token).await),
    ];
    for (route, (status, payload)) in payloads {
        let text = payload.to_string();
        assert!(status == 200 || status == 501, "{route}: {status} {text}");
        for leak in ["correct_answer", "synonyms", "Halaman"] {
            assert!(!text.contains(leak), "CRITICAL: {route} sent {leak} to a learner: {text}");
        }
    }
}
