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

async fn close(class: &Class, quiz: &str, token: &str) -> (u16, Value) {
    class.hub.post_json(&format!("/api/quizzes/{quiz}/close"), &json!({}), Some(token)).await
}

async fn submit(class: &Class, quiz: &str, token: &str, started_at: i64, submitted_at: i64, answers: Value) -> (u16, Value) {
    let body = json!({"started_at": started_at, "submitted_at": submitted_at, "answers": answers});
    class.hub.post_json(&format!("/api/quizzes/{quiz}/submit"), &body, Some(token)).await
}

async fn pull_from(class: &Class, token: &str, cursor: i64) -> Value {
    let (status, body) = class.hub.post_json("/api/sync/pull", &json!({"cursor": cursor}), Some(token)).await;
    assert_eq!(status, 200, "{body}");
    body
}

/// One right (MC) and one wrong (ID) answer: score 1 of 2.
fn one_right(questions: &[String; 2]) -> Value {
    json!([{"question_id": questions[0], "selected_option": "Photosynthesis"}, {"question_id": questions[1], "selected_option": "Karne"}])
}

/// A started quiz: (quiz id, question ids).
async fn started_quiz(class: &Class, release: bool) -> (String, [String; 2]) {
    let (quiz, questions) = draft_quiz(class, "Ecosystem", release).await;
    let (status, body) = start(class, &quiz, class.teacher()).await;
    assert_eq!(status, 200, "{body}");
    (quiz, questions)
}

#[tokio::test]
async fn closing_auto_submits_unfinished_attempts() {
    let class = Class::start().await;
    let (_, ana) = class.add_learner("123456789013", true).await;
    let (quiz, questions) = started_quiz(&class, true).await;
    assert_eq!(begin(&class, &quiz, &class.learner_token).await.0, 200);
    assert_eq!(begin(&class, &quiz, &ana).await.0, 200);
    let now = now_ms();
    let (status, receipt) = submit(&class, &quiz, &ana, now, now, one_right(&questions)).await;
    assert_eq!((status, receipt["score"].as_i64()), (200, Some(1)), "{receipt}");
    let mut juan_ws = connect(&class.hub, &class.learner_token).await;
    let mut ana_ws = connect(&class.hub, &ana).await;

    let (status, body) = close(&class, &quiz, class.teacher()).await;
    assert_eq!((status, body), (200, json!({"success": true})));
    let closed_at = class.count("SELECT closed_at FROM quizzes WHERE status = 'CLOSED' AND scores_released_at IS NULL").await;
    assert_eq!(
        class
            .count(
                "SELECT COUNT(*) FROM quiz_attempts WHERE student_id = (SELECT id FROM users WHERE lrn_or_id = '123456789012')
                 AND status = 'SUBMITTED' AND score = 0 AND total_points = 2 AND auto_submitted = 1
                 AND submitted_at = (SELECT closed_at FROM quizzes)"
            )
            .await,
        1,
        "Juan's unfinished attempt is auto-submitted at the close"
    );
    assert_eq!(
        class
            .count(
                "SELECT COUNT(*) FROM quiz_attempts WHERE student_id <> (SELECT id FROM users WHERE lrn_or_id = '123456789012')
                 AND score = 1 AND auto_submitted = 0"
            )
            .await,
        1,
        "Ana's submission is left as it was"
    );
    let closed = json!({"event": "EVENT_QUIZ_CLOSED", "quiz_id": quiz, "timestamp": closed_at});
    assert_eq!(next_event(&mut juan_ws).await, closed);
    assert_eq!(next_event(&mut ana_ws).await, closed);
    nothing_within(&mut juan_ws, 300).await;
    nothing_within(&mut ana_ws, 300).await;

    assert_eq!(class.pull(&class.learner_token).await["quiz_attempts"][0]["score"], 0);
    let (status, rows) = class.hub.get(&format!("/api/quizzes/{quiz}/results"), Some(class.teacher())).await;
    assert_eq!(status, 200, "{rows}");
    let states: Vec<&str> = rows.as_array().unwrap().iter().map(|r| r["status"].as_str().unwrap()).collect();
    assert_eq!(states, ["SUBMITTED", "SUBMITTED"]);
}

#[tokio::test]
async fn closing_releases_held_scores() {
    let class = Class::start().await;
    let (quiz, questions) = started_quiz(&class, false).await;
    assert_eq!(begin(&class, &quiz, &class.learner_token).await.0, 200);
    let now = now_ms();
    let (status, receipt) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert_eq!(status, 200, "{receipt}");
    assert!(receipt["score"].is_null(), "{receipt}");
    let before = pull_from(&class, &class.learner_token, 0).await;
    assert!(before["quiz_attempts"][0]["score"].is_null());
    let cursor = before["next_cursor"].as_i64().unwrap();
    let mut learner = connect(&class.hub, &class.learner_token).await;

    assert_eq!(close(&class, &quiz, class.teacher()).await.0, 200);
    let closed_at = class.count("SELECT closed_at FROM quizzes").await;
    assert_eq!(
        next_event(&mut learner).await,
        json!({"event": "EVENT_GRADE_CONFIRMED", "quiz_id": quiz, "attempt_id": receipt["attempt_id"], "score": 1,
               "total_points": 2, "timestamp": closed_at})
    );
    assert_eq!(next_event(&mut learner).await["event"], "EVENT_QUIZ_CLOSED");

    let after = pull_from(&class, &class.learner_token, cursor).await;
    let released: Vec<&Value> =
        after["quiz_attempts"].as_array().unwrap().iter().filter(|a| a["attempt_id"] == receipt["attempt_id"]).collect();
    assert_eq!(released.len(), 1, "the close writes a new revision, so a learner who pulled null gets the score: {after}");
    assert_eq!(released[0]["score"], 1);
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE scores_released_at = closed_at").await, 1);
    let teacher = pull_from(&class, class.teacher(), 0).await;
    let seen = teacher["quiz_attempts"].as_array().unwrap().iter().find(|a| a["attempt_id"] == receipt["attempt_id"]).cloned().unwrap();
    assert_eq!((seen["quiz_id"].as_str(), seen["student_id"].as_str()), (Some(quiz.as_str()), Some(class.learner.as_str())));
}

#[tokio::test]
async fn closing_again_changes_nothing() {
    let class = Class::start().await;
    let (quiz, _) = started_quiz(&class, false).await;
    assert_eq!(begin(&class, &quiz, &class.learner_token).await.0, 200);
    assert_eq!(close(&class, &quiz, class.teacher()).await.0, 200);
    let closed_at = class.count("SELECT closed_at FROM quizzes").await;
    let seq = class.count("SELECT MAX(seq) FROM sync_revisions").await;
    let mut learner = connect(&class.hub, &class.learner_token).await;

    let (status, body) = close(&class, &quiz, class.teacher()).await;
    assert_eq!((status, body), (200, json!({"success": true})));
    nothing_within(&mut learner, 300).await;
    assert_eq!(class.count("SELECT closed_at FROM quizzes").await, closed_at);
    assert_eq!(class.count("SELECT MAX(seq) FROM sync_revisions").await, seq, "a retry writes nothing");
}

#[tokio::test]
async fn a_draft_cannot_be_closed() {
    let class = Class::start().await;
    let (quiz, _) = draft_quiz(&class, "Ecosystem", true).await;
    let (status, body) = close(&class, &quiz, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (409, "QUIZ_NOT_STARTED", "Start the quiz before closing it"));
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE status = 'DRAFT' AND closed_at IS NULL").await, 1);
}

#[tokio::test]
async fn who_may_close() {
    let class = Class::start().await;
    let (quiz, _) = started_quiz(&class, true).await;
    let (status, body) = close(&class, &quiz, &class.learner_token).await;
    assert_eq!((status, code(&body)), (403, "FORBIDDEN"), "a learner");
    let (_, stranger) = class.other_class().await;
    let (status, body) = close(&class, &quiz, &stranger).await;
    assert_eq!((status, code(&body)), (403, "FORBIDDEN"), "a teacher of another class");
    let unknown = uuid::Uuid::new_v4().to_string();
    let (status, body) = close(&class, &unknown, class.teacher()).await;
    assert_eq!((status, code(&body), message(&body)), (404, "NOT_FOUND", "Quiz not found"));

    // Closing ends work, so an archived class may still close: refusing would leave learners locked out of the tutor.
    class.archive().await;
    let co = class.co_teacher("T-0002").await;
    let (status, body) = close(&class, &quiz, &co).await;
    assert_eq!((status, body), (200, json!({"success": true})), "a co-teacher, in an archived class");
    assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE status = 'CLOSED'").await, 1);
}
