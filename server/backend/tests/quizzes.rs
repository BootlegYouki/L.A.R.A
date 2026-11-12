//! Step 4.5 (#61): quiz authoring, the learner's redacted view and `/begin` on the real Hub.

mod common;

use common::uploads::Class;
use reqwest::Method;
use serde_json::{json, Value};

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

fn code(body: &Value) -> &str {
    body["error"]["code"].as_str().unwrap_or("")
}

/// The mock's two-question quiz (`QUIZ` in `tests/test_mock_hub.py`).
fn quiz_body(classroom_id: &str) -> Value {
    json!({
        "classroom_id": classroom_id, "title": "Ecosystem", "time_limit_minutes": 15,
        "questions": [
            {"question_text": "Gumagawa ng pagkain ang halaman.", "question_type": "MULTIPLE_CHOICE",
             "options": ["Photosynthesis", "Evaporation"], "points": 1, "correct_answer": "Photosynthesis"},
            {"question_text": "Ano ang kinakain ng herbivore?", "question_type": "IDENTIFICATION",
             "options": [], "points": 1, "correct_answer": "Halaman", "synonyms": ["plants"]},
        ],
    })
}

async fn create(class: &Class, token: &str, body: &Value) -> (u16, Value) {
    class.hub.post_json("/api/quizzes", body, Some(token)).await
}

/// A draft quiz in the class, as the teacher got it back.
async fn draft(class: &Class) -> Value {
    let (status, body) = create(class, class.teacher(), &quiz_body(&class.id)).await;
    assert_eq!(status, 200, "{body}");
    body
}

#[tokio::test]
async fn teacher_creates_a_draft_quiz() {
    let class = Class::start().await;
    let quiz = draft(&class).await;
    assert!(uuid::Uuid::parse_str(quiz["id"].as_str().unwrap()).is_ok(), "{quiz}");
    assert_eq!((quiz["status"].as_str(), quiz["classroom_id"].as_str()), (Some("DRAFT"), Some(class.id.as_str())));
    assert!(quiz["started_at"].is_null() && quiz["topic_id"].is_null() && quiz["instructions"].is_null());
    assert_eq!((quiz["shuffle_questions"].as_bool(), quiz["release_scores_immediately"].as_bool()), (Some(false), Some(true)));
    assert_eq!(quiz["time_limit_minutes"], 15);
    let questions = quiz["questions"].as_array().unwrap();
    assert_eq!(questions.len(), 2);
    assert_eq!(questions.iter().map(|q| q["order_index"].as_i64().unwrap()).collect::<Vec<_>>(), [1, 2]);
    assert!(questions.iter().all(|q| uuid::Uuid::parse_str(q["id"].as_str().unwrap()).is_ok()));
    assert_eq!((questions[0]["correct_answer"].as_str(), questions[0]["question_type"].as_str()), (Some("Photosynthesis"), Some("MULTIPLE_CHOICE")));
    assert_eq!(questions[0]["options"], json!(["Photosynthesis", "Evaporation"]));
    assert_eq!((questions[1]["correct_answer"].clone(), questions[1]["synonyms"].clone()), (json!("Halaman"), json!(["plants"])));
    assert_eq!(questions[1]["points"], 1);

    let teacher_pull = class.pull(class.teacher()).await;
    assert_eq!(teacher_pull["quizzes"][0]["id"], quiz["id"]);
    assert_eq!(teacher_pull["quizzes"][0]["status"], "DRAFT");
    assert_eq!(class.pull(&class.learner_token).await["quizzes"], json!([]), "learners never see a draft");
}

#[tokio::test]
async fn quiz_fields_are_checked() {
    let class = Class::start().await;
    let base = quiz_body(&class.id);
    let edit = |f: &dyn Fn(&mut Value)| {
        let mut body = base.clone();
        f(&mut body);
        body
    };
    let cases: Vec<(Value, &str)> = vec![
        (edit(&|b| { b.as_object_mut().unwrap().remove("questions"); }), "Missing: questions"),
        (edit(&|b| b["questions"] = json!([])), "questions cannot be empty"),
        (edit(&|b| b["time_limit_minutes"] = json!(0)), "time_limit_minutes must be from 1 to 9007199254740991"),
        (edit(&|b| b["questions"][0]["points"] = json!(0)), "points must be from 1 to 9007199254740991"),
        (edit(&|b| b["questions"][0]["question_type"] = json!("ESSAY")), "question_type must be MULTIPLE_CHOICE, TRUE_FALSE or IDENTIFICATION"),
        (edit(&|b| b["questions"][0]["correct_answer"] = json!("Condensation")), "correct_answer must be one of the options"),
        (edit(&|b| { b["questions"][1].as_object_mut().unwrap().remove("correct_answer"); }), "Missing: correct_answer"),
        (edit(&|b| { b["questions"][0]["order_index"] = json!(1); b["questions"][1]["order_index"] = json!(1); }),
         "order_index must be different for each question"),
        (edit(&|b| b["title"] = json!(" ")), "title cannot be empty"),
        (edit(&|b| b["questions"][0]["options"] = json!("A, B")), "options must be a list of text"),
    ];
    for (body, text) in cases {
        let (status, answer) = create(&class, class.teacher(), &body).await;
        assert_eq!((status, message(&answer)), (400, text), "{body}");
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes").await, 0);
}

#[tokio::test]
async fn quiz_writes_need_a_writable_class_you_teach() {
    let class = Class::start().await;
    let (other, other_teacher) = class.other_class().await;
    assert_eq!(create(&class, &class.learner_token, &quiz_body(&class.id)).await.0, 403);
    let (status, body) = create(&class, &other_teacher, &quiz_body(&class.id)).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let co = class.co_teacher("T-0002").await;
    assert_eq!(create(&class, &co, &quiz_body(&class.id)).await.0, 200);

    class.exec("INSERT INTO topics (id, classroom_id, name, created_at, updated_at) VALUES ('t-other', ?, 'X', 1, 1)", &[other.as_str()]).await;
    let mut body = quiz_body(&class.id);
    body["topic_id"] = json!("t-other");
    let (status, answer) = create(&class, class.teacher(), &body).await;
    assert_eq!((status, message(&answer)), (404, "Topic not found"));
    class.topic("t-1").await;
    body["topic_id"] = json!("t-1");
    let (status, answer) = create(&class, class.teacher(), &body).await;
    assert_eq!((status, answer["topic_id"].as_str()), (200, Some("t-1")));

    class.archive().await;
    let (status, body) = create(&class, class.teacher(), &quiz_body(&class.id)).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
}

// Used by later tasks' tests.
#[allow(dead_code)]
async fn send(class: &Class, method: Method, path: &str, body: &Value, token: &str) -> (u16, Value) {
    class.hub.send_json(method, path, body, Some(token)).await
}
#[allow(dead_code)]
fn unused() -> fn(&Value) -> &str {
    code
}
