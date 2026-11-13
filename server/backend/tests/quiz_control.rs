//! Step 4.1 (#11): teachers start a quiz for the whole class at one Hub instant and close it.

mod common;

use std::time::{Duration, Instant};

use common::uploads::Class;
use common::ws::{connect, next, nothing_within, Socket};
use serde_json::{json, Value};

fn message(body: &Value) -> &str {
    body["error"]["message"].as_str().unwrap_or("")
}

fn code(body: &Value) -> &str {
    body["error"]["code"].as_str().unwrap_or("")
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64
}

/// A `DRAFT` 15-minute quiz: (quiz id, [MC question id, ID question id]).
async fn draft_quiz(class: &Class, title: &str, release: bool) -> (String, [String; 2]) {
    let body = json!({
        "classroom_id": class.id, "title": title, "time_limit_minutes": 15, "release_scores_immediately": release,
        "questions": [
            {"question_text": "Gumagawa ng pagkain ang halaman.", "question_type": "MULTIPLE_CHOICE",
             "options": ["Photosynthesis", "Evaporation"], "correct_answer": "Photosynthesis"},
            {"question_text": "Ano ang kinakain ng herbivore?", "question_type": "IDENTIFICATION",
             "correct_answer": "Halaman", "synonyms": ["plants"]},
        ],
    });
    let (status, quiz) = class.hub.post_json("/api/quizzes", &body, Some(class.teacher())).await;
    assert_eq!(status, 200, "{quiz}");
    let id = quiz["id"].as_str().unwrap().to_owned();
    let q = |i: usize| quiz["questions"][i]["id"].as_str().unwrap().to_owned();
    (id, [q(0), q(1)])
}

async fn start(class: &Class, quiz: &str, token: &str) -> (u16, Value) {
    class.hub.post_json(&format!("/api/quizzes/{quiz}/start"), &json!({}), Some(token)).await
}

async fn begin(class: &Class, quiz: &str, token: &str) -> (u16, Value) {
    class.hub.post_json(&format!("/api/quizzes/{quiz}/begin"), &json!({}), Some(token)).await
}

/// The next event that is not presence ONLINE/OFFLINE.
async fn next_event(ws: &mut Socket) -> Value {
    loop {
        let event = next(ws).await;
        if !(event["event"] == "EVENT_PRESENCE" && (event["state"] == "ONLINE" || event["state"] == "OFFLINE")) {
            return event;
        }
    }
}

#[tokio::test]
async fn a_teacher_starts_a_quiz_and_learners_get_the_start() {
    let class = Class::start().await;
    let (quiz, _) = draft_quiz(&class, "Ecosystem", true).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    let (_, pending) = class.add_learner("123456789013", false).await;
    let mut waiting = connect(&class.hub, &pending).await;

    let before = now_ms();
    let (status, body) = start(&class, &quiz, class.teacher()).await;
    let after = now_ms();
    assert_eq!(status, 200, "{body}");
    let t = body["started_at"].as_i64().unwrap();
    assert!((before..=after).contains(&t), "{t} not in {before}..={after}");
    assert_eq!(body, json!({"started_at": t, "duration_seconds": 900}));

    assert_eq!(
        next_event(&mut learner).await,
        json!({"event": "EVENT_QUIZ_START", "quiz_id": quiz, "title": "Ecosystem", "start_epoch_ms": t, "duration_seconds": 900})
    );
    nothing_within(&mut waiting, 300).await;

    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE status = 'ACTIVE' AND started_at IS NOT NULL").await, 1);
    let pulled = class.pull(&class.learner_token).await;
    let listed = pulled["quizzes"].as_array().unwrap().iter().find(|q| q["id"] == quiz.as_str()).cloned().unwrap();
    assert_eq!((listed["status"].as_str(), listed["started_at"].as_i64()), (Some("ACTIVE"), Some(t)));
    assert_eq!(begin(&class, &quiz, &class.learner_token).await.0, 200);
}

#[tokio::test]
async fn a_quiz_starts_only_from_draft_and_one_at_a_time() {
    let class = Class::start().await;
    let (first, _) = draft_quiz(&class, "Ecosystem", true).await;
    let (second, _) = draft_quiz(&class, "Fractions", true).await;
    assert_eq!(start(&class, &first, class.teacher()).await.0, 200);

    let (status, body) = start(&class, &first, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (409, "QUIZ_NOT_DRAFT", "This quiz has already been started"));
    let (status, body) = start(&class, &second, class.teacher()).await;
    assert_eq!(
        (status, code(&body), message(&body)),
        (409, "QUIZ_ALREADY_ACTIVE", "Close \"Ecosystem\" before starting another quiz")
    );
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE title = 'Fractions' AND status = 'DRAFT'").await, 1);

    class.exec("UPDATE quizzes SET status = 'CLOSED' WHERE id = ?", &[first.as_str()]).await;
    let (status, body) = start(&class, &second, class.teacher()).await;
    assert_eq!(status, 200, "{body}");
}

#[tokio::test]
async fn who_may_start() {
    let class = Class::start().await;
    let (quiz, _) = draft_quiz(&class, "Ecosystem", true).await;
    let (status, body) = start(&class, &quiz, &class.learner_token).await;
    assert_eq!((status, code(&body)), (403, "FORBIDDEN"), "a learner");
    let (_, stranger) = class.other_class().await;
    let (status, body) = start(&class, &quiz, &stranger).await;
    assert_eq!((status, code(&body)), (403, "FORBIDDEN"), "a teacher of another class");
    let unknown = uuid::Uuid::new_v4().to_string();
    let (status, body) = start(&class, &unknown, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (404, "NOT_FOUND", "Quiz not found"));

    let co = class.co_teacher("T-0002").await;
    let (status, body) = start(&class, &quiz, &co).await;
    assert_eq!(status, 200, "a co-teacher: {body}");

    let (other, _) = draft_quiz(&class, "Fractions", true).await;
    class.exec("UPDATE quizzes SET status = 'CLOSED' WHERE id = ?", &[quiz.as_str()]).await;
    class.archive().await;
    let (status, body) = start(&class, &other, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (403, "FORBIDDEN", "This class is archived"));
}

/// #11's acceptance: 40 devices receive the start within 300 ms of each other.
#[tokio::test]
async fn forty_learners_get_the_start_within_300_ms() {
    let class = Class::start().await;
    let (quiz, _) = draft_quiz(&class, "Ecosystem", true).await;
    let mut tokens = vec![class.learner_token.clone()];
    for n in 1..40 {
        tokens.push(class.add_learner(&format!("1234567891{n:02}"), true).await.1);
    }
    let mut sockets = Vec::new();
    for token in &tokens {
        sockets.push(connect(&class.hub, token).await);
    }
    let waits = futures_util::future::join_all(sockets.iter_mut().map(|ws| async move {
        let event = next_event(ws).await;
        (event, Instant::now())
    }));
    let (started, arrivals) = tokio::join!(start(&class, &quiz, class.teacher()), waits);
    assert_eq!(started.0, 200, "{}", started.1);
    assert_eq!(arrivals.len(), 40);
    assert!(arrivals.iter().all(|(event, _)| event["event"] == "EVENT_QUIZ_START" && event["quiz_id"] == quiz.as_str()));
    let first = arrivals.iter().map(|(_, at)| *at).min().unwrap();
    let last = arrivals.iter().map(|(_, at)| *at).max().unwrap();
    let spread = last - first;
    assert!(spread <= Duration::from_millis(300), "40 starts arrived {spread:?} apart");
    println!("40 learners got EVENT_QUIZ_START within {spread:?}");
}
