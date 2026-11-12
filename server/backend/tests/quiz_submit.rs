//! Step 4.2 (#12): quiz submits are graded on the Hub, through REST, the offline push and the WebSocket.

mod common;

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
async fn draft_quiz(class: &Class, release: bool) -> (String, [String; 2]) {
    let body = json!({
        "classroom_id": class.id, "title": "Ecosystem", "time_limit_minutes": 15, "release_scores_immediately": release,
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

/// The same quiz, `ACTIVE` and started now (starting is step 4.1).
async fn active_quiz(class: &Class, release: bool) -> (String, [String; 2]) {
    let (id, questions) = draft_quiz(class, release).await;
    let started = now_ms().to_string();
    class.exec("UPDATE quizzes SET status = 'ACTIVE', started_at = ? WHERE id = ?", &[started.as_str(), id.as_str()]).await;
    (id, questions)
}

async fn begin(class: &Class, quiz: &str, token: &str) {
    let (status, body) = class.hub.post_json(&format!("/api/quizzes/{quiz}/begin"), &json!({}), Some(token)).await;
    assert_eq!(status, 200, "{body}");
}

/// One right (MC) and one wrong (ID) answer: score 1 of 2.
fn one_right(questions: &[String; 2]) -> Value {
    json!([{"question_id": questions[0], "selected_option": "Photosynthesis"}, {"question_id": questions[1], "selected_option": "Karne"}])
}

async fn submit(class: &Class, quiz: &str, token: &str, started_at: i64, submitted_at: i64, answers: Value) -> (u16, Value) {
    let body = json!({"started_at": started_at, "submitted_at": submitted_at, "answers": answers});
    class.hub.post_json(&format!("/api/quizzes/{quiz}/submit"), &body, Some(token)).await
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
async fn a_learner_submits_and_gets_the_grade() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let mut teacher = connect(&class.hub, class.teacher()).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    begin(&class, &quiz, &class.learner_token).await;
    assert_eq!(next_event(&mut teacher).await["state"], "ANSWERING_QUIZ");

    let now = now_ms();
    let (status, receipt) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert_eq!(status, 200, "{receipt}");
    assert_eq!((receipt["score"].as_i64(), receipt["total_points"].as_i64()), (Some(1), Some(2)));
    assert_eq!((receipt["quiz_id"].as_str(), receipt["student_id"].as_str()), (Some(quiz.as_str()), Some(class.learner.as_str())));
    assert!(uuid::Uuid::parse_str(receipt["attempt_id"].as_str().unwrap()).is_ok());
    assert!(receipt["submitted_at"].as_i64().unwrap() <= now_ms());

    let graded = next_event(&mut learner).await;
    assert_eq!(
        graded,
        json!({"event": "EVENT_GRADE_CONFIRMED", "quiz_id": quiz, "attempt_id": receipt["attempt_id"], "score": 1,
               "total_points": 2, "timestamp": graded["timestamp"]})
    );
    let presence = next_event(&mut teacher).await;
    assert_eq!((presence["state"].as_str(), presence["student_id"].as_str()), (Some("SUBMITTED"), Some(class.learner.as_str())));
    assert_eq!(class.pull(&class.learner_token).await["quiz_attempts"][0]["score"], 1);
    assert_eq!(
        class.count("SELECT COUNT(*) FROM quiz_attempts WHERE status = 'SUBMITTED' AND score = 1 AND total_points = 2 AND answers_json LIKE '%Photosynthesis%'").await,
        1
    );
}

#[tokio::test]
async fn a_submit_arriving_twice_is_stored_once() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    begin(&class, &quiz, &class.learner_token).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    let now = now_ms();
    let (_, first) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert_eq!(next_event(&mut learner).await["event"], "EVENT_GRADE_CONFIRMED");
    let all_right = json!([{"question_id": questions[0], "selected_option": "Photosynthesis"}, {"question_id": questions[1], "selected_option": "Halaman"}]);
    let (status, again) = submit(&class, &quiz, &class.learner_token, now, now, all_right).await;
    assert_eq!((status, again), (200, first.clone()), "the stored receipt comes back, never a regrade");
    nothing_within(&mut learner, 300).await;
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 1);

    let (ana_id, ana) = class.add_learner("123456789013", true).await;
    let _ = ana_id;
    let (a, b) = tokio::join!(
        submit(&class, &quiz, &ana, now, now, one_right(&questions)),
        submit(&class, &quiz, &ana, now, now, one_right(&questions))
    );
    assert_eq!((a.0, b.0), (200, 200), "{a:?} {b:?}");
    assert_eq!(a.1["attempt_id"], b.1["attempt_id"]);
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 2);
}

#[tokio::test]
async fn held_scores_stay_hidden_from_the_learner() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, false).await;
    begin(&class, &quiz, &class.learner_token).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    let now = now_ms();
    let (status, receipt) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert_eq!(status, 200, "{receipt}");
    assert!(receipt["score"].is_null(), "{receipt}");
    assert_eq!(receipt["total_points"], 2);
    nothing_within(&mut learner, 300).await;
    assert!(class.pull(&class.learner_token).await["quiz_attempts"][0]["score"].is_null());
    assert_eq!(class.pull(class.teacher()).await["quiz_attempts"][0]["score"], 1, "the teacher always sees it");
    class.exec("UPDATE quizzes SET status = 'CLOSED' WHERE id = ?", &[quiz.as_str()]).await;
    assert_eq!(class.pull(&class.learner_token).await["quiz_attempts"][0]["score"], 1, "released on close");
}

#[tokio::test]
async fn the_hub_clock_decides_the_start() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    begin(&class, &quiz, &class.learner_token).await;
    let now = now_ms();
    let twenty_ago = (now - 20 * 60_000).to_string();
    class.exec("UPDATE quiz_attempts SET started_at = ? WHERE student_id = ?", &[twenty_ago.as_str(), class.learner.as_str()]).await;
    let (status, body) = submit(&class, &quiz, &class.learner_token, now - 60_000, now, one_right(&questions)).await;
    assert_eq!((status, code(&body), message(&body)), (422, "TIME_LIMIT_EXCEEDED", "The time limit has passed"), "the device's start is ignored");

    let (_, ana) = class.add_learner("123456789013", true).await;
    begin(&class, &quiz, &ana).await;
    let just_inside = (now - 15 * 60_000 - 30_000).to_string();
    class.exec("UPDATE quiz_attempts SET started_at = ? WHERE status = 'IN_PROGRESS' AND student_id <> ?", &[just_inside.as_str(), class.learner.as_str()]).await;
    let (status, receipt) = submit(&class, &quiz, &ana, now, now + 3_600_000, one_right(&questions)).await;
    assert_eq!(status, 200, "inside the 60 s grace, and a future submitted_at is clamped: {receipt}");
    assert!(receipt["submitted_at"].as_i64().unwrap() <= now_ms(), "{receipt}");
}

#[tokio::test]
async fn no_begin_uses_the_clients_start_but_not_before_the_quiz() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let now = now_ms();
    let (status, receipt) = submit(&class, &quiz, &class.learner_token, now - 3_600_000, now, one_right(&questions)).await;
    assert_eq!(status, 200, "the quiz started just now, so an hour-old start counts from the quiz: {receipt}");
    let (_, ana) = class.add_learner("123456789013", true).await;
    let ten_min_ago = (now - 10 * 60_000).to_string();
    class.exec("UPDATE quizzes SET started_at = ? WHERE id = ?", &[ten_min_ago.as_str(), quiz.as_str()]).await;
    let (status, body) = submit(&class, &quiz, &ana, now - 10 * 60_000, now + 7 * 60_000, one_right(&questions)).await;
    assert_eq!(status, 200, "a future submitted_at is clamped to now: {body}");
    let (_, juan2) = class.add_learner("123456789014", true).await;
    let (status, body) = class.hub.post_json(&format!("/api/quizzes/{quiz}/submit"), &json!({"submitted_at": now, "answers": []}), Some(&juan2)).await;
    assert_eq!((status, message(&body)), (400, "Missing: started_at"));
}

#[tokio::test]
async fn who_may_submit() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let now = now_ms();
    let (draft, _) = draft_quiz(&class, true).await;
    let (status, body) = submit(&class, &draft, &class.learner_token, now, now, json!([])).await;
    assert_eq!((status, message(&body)), (404, "Quiz not found"));
    let outsider = class.hub.seed_user("123456789098", "Outsider", lara_hub::auth::Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    let (_, pending) = class.add_learner("123456789013", false).await;
    for token in [outsider.as_str(), pending.as_str(), class.teacher()] {
        assert_eq!(submit(&class, &quiz, token, now, now, json!([])).await.0, 403);
    }
    let (status, body) = submit(&class, &quiz, &class.learner_token, now, now, json!("A, B")).await;
    assert_eq!((status, message(&body)), (400, "answers must be a list of {question_id, selected_option}"));

    let (status, done) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert_eq!(status, 200, "{done}");
    class.exec("UPDATE quizzes SET status = 'CLOSED' WHERE id = ?", &[quiz.as_str()]).await;
    let (_, ana) = class.add_learner("123456789014", true).await;
    assert_eq!(submit(&class, &quiz, &ana, now, now, one_right(&questions)).await.0, 200, "an on-time queued submit after close (FR-4.5)");
    let (_, ben) = class.add_learner("123456789015", true).await;
    class.archive().await;
    assert_eq!(submit(&class, &quiz, &class.learner_token, now, now, json!([])).await, (200, done), "a retry is answered");
    let (status, body) = submit(&class, &quiz, &ben, now, now, one_right(&questions)).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"), "new work in an archived class");
}

async fn push(class: &Class, token: &str, payload: Value) -> Value {
    let (status, body) = class.hub.post_json("/api/sync/push", &payload, Some(token)).await;
    assert_eq!(status, 200, "{body}");
    body
}

fn queued(id: &str, quiz: &str, started_at: i64, submitted_at: i64, answers: Value) -> Value {
    json!({"id": id, "quiz_id": quiz, "started_at": started_at, "submitted_at": submitted_at, "answers": answers})
}

#[tokio::test]
async fn a_queued_attempt_is_graded_on_push() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    begin(&class, &quiz, &class.learner_token).await;
    let now = now_ms();
    let item = queued("6b2b8d0e-0000-4000-8000-000000000001", &quiz, now, now, one_right(&questions));
    for _ in 0..2 {
        let body = push(&class, &class.learner_token, json!({"quiz_attempts": [item.clone()]})).await;
        assert_eq!(body["receipts"], json!([{"id": "6b2b8d0e-0000-4000-8000-000000000001", "status": "SYNCED"}]));
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts WHERE status = 'SUBMITTED' AND score = 1").await, 1);
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 1, "the retry is stored once");
}

#[tokio::test]
async fn a_bad_queued_attempt_is_rejected_alone() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let (draft, _) = draft_quiz(&class, true).await;
    class.announcement("ann-1").await;
    begin(&class, &quiz, &class.learner_token).await;
    let now = now_ms();
    let payload = json!({
        "quiz_attempts": [
            {"id": "a-missing", "quiz_id": quiz, "started_at": now, "submitted_at": now},
            queued("a-draft", &draft, now, now, json!([])),
            queued("a-good", &quiz, now, now, one_right(&questions)),
        ],
        "comments": [{"id": "c-1", "announcement_id": "ann-1", "content": "Opo", "created_at": 1}],
    });
    let body = push(&class, &class.learner_token, payload).await;
    assert_eq!(
        body["receipts"],
        json!([
            {"id": "a-missing", "status": "REJECTED", "reason": "BAD_REQUEST"},
            {"id": "a-draft", "status": "REJECTED", "reason": "NOT_FOUND"},
            {"id": "a-good", "status": "SYNCED"},
            {"id": "c-1", "status": "SYNCED"},
        ])
    );

    let (_, ana) = class.add_learner("123456789013", true).await;
    begin(&class, &quiz, &ana).await;
    let late_start = (now - 20 * 60_000).to_string();
    class.exec("UPDATE quiz_attempts SET started_at = ? WHERE status = 'IN_PROGRESS'", &[late_start.as_str()]).await;
    let body = push(&class, &ana, json!({"quiz_attempts": [queued("a-late", &quiz, now, now, json!([]))]})).await;
    assert_eq!(body["receipts"], json!([{"id": "a-late", "status": "REJECTED", "reason": "TIME_LIMIT_EXCEEDED"}]));
}

#[tokio::test]
async fn the_websocket_submit_uses_the_same_path() {
    use futures_util::SinkExt;
    use tokio_tungstenite::tungstenite::Message;

    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    begin(&class, &quiz, &class.learner_token).await;
    let mut learner = connect(&class.hub, &class.learner_token).await;
    let frame = json!({"event": "EVENT_QUIZ_SUBMIT", "quiz_id": quiz, "student_id": "ignored", "submitted_at": now_ms(),
                       "answers": one_right(&questions)});
    learner.send(Message::text(frame.to_string())).await.unwrap();
    let graded = next_event(&mut learner).await;
    assert_eq!((graded["event"].as_str(), graded["score"].as_i64()), (Some("EVENT_GRADE_CONFIRMED"), Some(1)), "{graded}");
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts WHERE status = 'SUBMITTED' AND score = 1").await, 1);

    let (_, ana) = class.add_learner("123456789013", true).await;
    begin(&class, &quiz, &ana).await;
    let late_start = (now_ms() - 20 * 60_000).to_string();
    class.exec("UPDATE quiz_attempts SET started_at = ? WHERE status = 'IN_PROGRESS'", &[late_start.as_str()]).await;
    let mut ana_ws = connect(&class.hub, &ana).await;
    let late = json!({"event": "EVENT_QUIZ_SUBMIT", "quiz_id": quiz, "student_id": "x", "submitted_at": now_ms(), "answers": []});
    ana_ws.send(Message::text(late.to_string())).await.unwrap();
    let error = next_event(&mut ana_ws).await;
    assert_eq!(error, json!({"event": "EVENT_ERROR", "code": "BAD_REQUEST", "message": "TIME_LIMIT_EXCEEDED"}));
    ana_ws.send(Message::text(json!({"event": "EVENT_QUIZ_SUBMIT", "quiz_id": quiz}).to_string())).await.unwrap();
    let error = next_event(&mut ana_ws).await;
    assert_eq!((error["event"].as_str(), error["code"].as_str()), (Some("EVENT_ERROR"), Some("BAD_REQUEST")), "a malformed submit is answered");
}

async fn results(class: &Class, quiz: &str, token: &str) -> (u16, Value) {
    class.hub.get(&format!("/api/quizzes/{quiz}/results"), Some(token)).await
}

/// The row for one learner.
fn row_for<'a>(rows: &'a Value, student: &str) -> &'a Value {
    rows.as_array().unwrap().iter().find(|r| r["student_id"] == student).expect("a row for every active learner")
}

#[tokio::test]
async fn results_show_every_learner() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let (ana_id, ana) = class.add_learner("123456789013", true).await;
    let (ben_id, _) = class.add_learner("123456789014", true).await;
    let (pending_id, _) = class.add_learner("123456789015", false).await;
    begin(&class, &quiz, &class.learner_token).await;
    let now = now_ms();
    submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    begin(&class, &quiz, &ana).await;

    let (status, rows) = results(&class, &quiz, class.teacher()).await;
    assert_eq!(status, 200, "{rows}");
    assert_eq!(rows.as_array().unwrap().len(), 3, "active learners only: {rows}");
    assert!(rows.as_array().unwrap().iter().all(|r| r["student_id"] != pending_id.as_str()));
    let juan = row_for(&rows, &class.learner);
    assert_eq!((juan["status"].as_str(), juan["score"].as_i64(), juan["total_points"].as_i64()), (Some("SUBMITTED"), Some(1), Some(2)));
    assert_eq!(juan["full_name"], "Learner");
    let ana_row = row_for(&rows, &ana_id);
    assert_eq!(ana_row["status"], "IN_PROGRESS");
    assert!(ana_row.get("score").is_none() && ana_row.get("total_points").is_none(), "{ana_row}");
    assert_eq!(row_for(&rows, &ben_id)["status"], "NOT_STARTED");
}

#[tokio::test]
async fn results_show_held_scores_to_the_teacher() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, false).await;
    begin(&class, &quiz, &class.learner_token).await;
    let now = now_ms();
    let (_, receipt) = submit(&class, &quiz, &class.learner_token, now, now, one_right(&questions)).await;
    assert!(receipt["score"].is_null());
    let (status, rows) = results(&class, &quiz, class.teacher()).await;
    assert_eq!((status, row_for(&rows, &class.learner)["score"].as_i64()), (200, Some(1)), "the teacher always sees the real score");
}

#[tokio::test]
async fn only_the_class_teachers_see_results() {
    let class = Class::start().await;
    let (quiz, _) = active_quiz(&class, true).await;
    let (_, other_teacher) = class.other_class().await;
    assert_eq!(results(&class, &quiz, &class.learner_token).await.0, 403);
    let (status, body) = results(&class, &quiz, &other_teacher).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let (status, body) = results(&class, "no-such-quiz", class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Quiz not found"));
    let co = class.co_teacher("T-0002").await;
    assert_eq!(results(&class, &quiz, &co).await.0, 200);
}

/// #12's acceptance: 40 submitted quizzes are graded and stored in under 2 seconds.
#[tokio::test]
async fn forty_learners_are_graded_in_under_two_seconds() {
    let class = Class::start().await;
    let (quiz, questions) = active_quiz(&class, true).await;
    let mut tokens = vec![class.learner_token.clone()];
    for n in 1..40 {
        tokens.push(class.add_learner(&format!("1234567891{n:02}"), true).await.1);
    }
    for token in &tokens {
        begin(&class, &quiz, token).await;
    }
    let now = now_ms();
    let started = std::time::Instant::now();
    let replies = futures_util::future::join_all(tokens.iter().map(|t| submit(&class, &quiz, t, now, now, one_right(&questions)))).await;
    let took = started.elapsed();
    assert!(replies.iter().all(|(status, _)| *status == 200), "{replies:?}");
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts WHERE status = 'SUBMITTED' AND score = 1").await, 40);
    assert!(took < std::time::Duration::from_secs(2), "40 submits took {took:?}");
    println!("40 submits graded and stored in {took:?}");
}
