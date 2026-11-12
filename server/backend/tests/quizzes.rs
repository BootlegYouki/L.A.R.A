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

async fn edit(class: &Class, id: &str, body: &Value, token: &str) -> (u16, Value) {
    class.hub.send_json(Method::PATCH, &format!("/api/quizzes/{id}"), body, Some(token)).await
}

/// Starting is step 4.1; until then tests set the status straight in the row.
async fn set_status(class: &Class, id: &str, status: &str) {
    class.exec("UPDATE quizzes SET status = ?, started_at = 1000 WHERE id = ?", &[status, id]).await;
}

#[tokio::test]
async fn quiz_can_be_edited_only_while_it_is_a_draft() {
    let class = Class::start().await;
    let quiz = draft(&class).await;
    let id = quiz["id"].as_str().unwrap();
    let mut body = quiz_body(&class.id);
    body["title"] = json!("Ecosystem 2");
    let (status, edited) = edit(&class, id, &body, class.teacher()).await;
    assert_eq!((status, edited["title"].as_str()), (200, Some("Ecosystem 2")), "{edited}");
    assert_eq!(edited["status"], "DRAFT");
    set_status(&class, id, "ACTIVE").await;
    let (status, answer) = edit(&class, id, &quiz_body(&class.id), class.teacher()).await;
    assert_eq!((status, code(&answer), message(&answer)), (409, "QUIZ_NOT_DRAFT", "A quiz can be edited only before it starts"));
}

#[tokio::test]
async fn editing_replaces_the_questions() {
    let class = Class::start().await;
    let quiz = draft(&class).await;
    let id = quiz["id"].as_str().unwrap();
    let kept = quiz["questions"][0]["id"].as_str().unwrap().to_owned();
    let other = draft(&class).await;
    let borrowed = other["questions"][0]["id"].as_str().unwrap().to_owned();
    let body = json!({
        "title": "Ecosystem", "time_limit_minutes": 20,
        "questions": [
            {"id": kept, "question_text": "Gumagawa ng pagkain ang halaman.", "question_type": "MULTIPLE_CHOICE",
             "options": ["Photosynthesis", "Evaporation"], "correct_answer": "Photosynthesis"},
            {"question_text": "Totoo ba?", "question_type": "TRUE_FALSE", "options": ["True", "False"], "correct_answer": "True"},
            {"id": borrowed, "question_text": "Ano ang araw?", "question_type": "IDENTIFICATION", "correct_answer": "Bituin"},
        ],
    });
    let (status, edited) = edit(&class, id, &body, class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    let ids: Vec<&str> = edited["questions"].as_array().unwrap().iter().map(|q| q["id"].as_str().unwrap()).collect();
    assert_eq!(ids.len(), 3);
    assert_eq!(ids[0], kept, "a question of this quiz keeps its id");
    assert!(ids[2] != borrowed && uuid::Uuid::parse_str(ids[2]).is_ok(), "another quiz's id is not reused");
    assert_eq!(edited["time_limit_minutes"], 20);
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_questions").await, 5, "3 here and the other quiz's 2");
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_questions WHERE correct_answer = 'Halaman' AND quiz_id IN (SELECT id FROM quizzes WHERE title = 'Ecosystem' AND time_limit_minutes = 20)").await, 0, "the dropped question is gone");
}

#[tokio::test]
async fn a_started_quiz_cannot_be_edited() {
    let class = Class::start().await;
    for status in ["ACTIVE", "CLOSED"] {
        let quiz = draft(&class).await;
        let id = quiz["id"].as_str().unwrap();
        set_status(&class, id, status).await;
        let mut body = quiz_body(&class.id);
        body["title"] = json!("Changed");
        body["questions"] = json!([{"question_text": "x", "question_type": "IDENTIFICATION", "correct_answer": "y"}]);
        let (got, answer) = edit(&class, id, &body, class.teacher()).await;
        assert_eq!((got, code(&answer)), (409, "QUIZ_NOT_DRAFT"), "{status}");
        assert_eq!(class.count("SELECT COUNT(*) FROM quizzes WHERE title = 'Changed'").await, 0, "{status}");
    }
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_questions").await, 4, "no question changed");
}

#[tokio::test]
async fn edit_needs_a_quiz_you_teach() {
    let class = Class::start().await;
    let id = draft(&class).await["id"].as_str().unwrap().to_owned();
    let (_, other_teacher) = class.other_class().await;
    let (status, body) = edit(&class, "no-such-quiz", &quiz_body(&class.id), class.teacher()).await;
    assert_eq!((status, message(&body)), (404, "Quiz not found"));
    assert_eq!(edit(&class, &id, &quiz_body(&class.id), &class.learner_token).await.0, 403);
    let (status, body) = edit(&class, &id, &quiz_body(&class.id), &other_teacher).await;
    assert_eq!((status, message(&body)), (403, "Not your classroom"));
    let (status, body) = edit(&class, &id, &json!({"title": "x"}), class.teacher()).await;
    assert_eq!((status, message(&body)), (400, "Missing: time_limit_minutes, questions"));
    class.archive().await;
    let (status, body) = edit(&class, &id, &quiz_body(&class.id), class.teacher()).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
}

async fn get_quiz(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.get(&format!("/api/quizzes/{id}"), Some(token)).await
}

fn keys(value: &Value) -> Vec<String> {
    let mut keys: Vec<String> = value.as_object().unwrap().keys().cloned().collect();
    keys.sort_unstable();
    keys
}

#[tokio::test]
async fn drafts_are_invisible_to_learners() {
    let class = Class::start().await;
    let id = draft(&class).await["id"].as_str().unwrap().to_owned();
    let (status, body) = get_quiz(&class, &id, &class.learner_token).await;
    assert_eq!((status, message(&body)), (404, "Quiz not found"));
    let (status, body) = class.hub.get("/api/quizzes/active", Some(&class.learner_token)).await;
    assert_eq!((status, message(&body)), (404, "No active quiz"));
}

#[tokio::test]
async fn learners_get_the_quiz_without_answers() {
    let class = Class::start().await;
    let id = draft(&class).await["id"].as_str().unwrap().to_owned();
    set_status(&class, &id, "ACTIVE").await;
    let (status, quiz) = get_quiz(&class, &id, &class.learner_token).await;
    assert_eq!(status, 200, "{quiz}");
    assert_eq!(keys(&quiz), ["classroom_id", "id", "questions", "started_at", "time_limit_minutes", "title"]);
    assert_eq!(quiz["started_at"], 1000);
    for question in quiz["questions"].as_array().unwrap() {
        assert_eq!(keys(question), ["id", "options", "order_index", "points", "question_text", "question_type"]);
    }
    let (status, active) = class.hub.get("/api/quizzes/active", Some(&class.learner_token)).await;
    assert_eq!((status, active.clone()), (200, quiz), "/active sends the same payload");
    let (status, teacher_view) = get_quiz(&class, &id, class.teacher()).await;
    assert_eq!(status, 200);
    assert!(teacher_view.to_string().contains("correct_answer"), "the teacher sees the key");
    assert_eq!(teacher_view["status"], "ACTIVE");
}

#[tokio::test]
async fn shuffle_gives_each_learner_a_stable_order() {
    let class = Class::start().await;
    let mut body = quiz_body(&class.id);
    body["shuffle_questions"] = json!(true);
    body["questions"] = json!((1..=6)
        .map(|n| json!({"question_text": format!("Q{n}"), "question_type": "IDENTIFICATION", "correct_answer": format!("A{n}")}))
        .collect::<Vec<_>>());
    let (status, quiz) = create(&class, class.teacher(), &body).await;
    assert_eq!(status, 200, "{quiz}");
    let id = quiz["id"].as_str().unwrap();
    set_status(&class, id, "ACTIVE").await;
    let order = |q: &Value| q["questions"].as_array().unwrap().iter().map(|x| x["question_text"].as_str().unwrap().to_owned()).collect::<Vec<_>>();
    let mut tokens = vec![class.learner_token.clone()];
    for lrn in ["123456789013", "123456789014", "123456789015"] {
        tokens.push(class.add_learner(lrn, true).await.1);
    }
    let mut orders = Vec::new();
    for token in &tokens {
        let first = order(&get_quiz(&class, id, token).await.1);
        assert_eq!(order(&get_quiz(&class, id, token).await.1), first, "stable for one learner");
        let mut sorted = first.clone();
        sorted.sort();
        assert_eq!(sorted, ["Q1", "Q2", "Q3", "Q4", "Q5", "Q6"], "the same questions");
        orders.push(first);
    }
    orders.dedup();
    assert!(orders.len() > 1, "learners get different orders: {orders:?}");
    // Review of #61: mobile reads questions ORDER BY order_index, so the learner view is numbered in its
    // own order, or the shuffle would be undone on the phone.
    let (_, shuffled) = get_quiz(&class, id, &tokens[1]).await;
    let numbers: Vec<i64> = shuffled["questions"].as_array().unwrap().iter().map(|q| q["order_index"].as_i64().unwrap()).collect();
    assert_eq!(numbers, [1, 2, 3, 4, 5, 6], "numbered in the learner's order");
    assert_eq!(order(&get_quiz(&class, id, class.teacher()).await.1), ["Q1", "Q2", "Q3", "Q4", "Q5", "Q6"], "the teacher sees the set order");
}

#[tokio::test]
async fn active_picks_the_newest_quiz_of_the_learners_classes() {
    let class = Class::start().await;
    let (other, other_teacher) = class.other_class().await;
    class
        .exec(
            "INSERT INTO enrollments (id, classroom_id, student_id, status, joined_at, created_at, updated_at) VALUES ('e-2', ?, ?, 'ACTIVE', 1, 1, 1)",
            &[other.as_str(), class.learner.as_str()],
        )
        .await;
    let older = draft(&class).await["id"].as_str().unwrap().to_owned();
    let (status, newer) = create(&class, &other_teacher, &quiz_body(&other)).await;
    assert_eq!(status, 200, "{newer}");
    let newer = newer["id"].as_str().unwrap().to_owned();
    class.exec("UPDATE quizzes SET status = 'ACTIVE', started_at = 1 WHERE id = ?", &[older.as_str()]).await;
    class.exec("UPDATE quizzes SET status = 'ACTIVE', started_at = 2 WHERE id = ?", &[newer.as_str()]).await;
    let (status, active) = class.hub.get("/api/quizzes/active", Some(&class.learner_token)).await;
    assert_eq!((status, active["id"].as_str()), (200, Some(newer.as_str())));
}

#[tokio::test]
async fn outsiders_cannot_read_a_quiz() {
    let class = Class::start().await;
    let id = draft(&class).await["id"].as_str().unwrap().to_owned();
    set_status(&class, &id, "ACTIVE").await;
    let outsider = class.hub.seed_user("123456789098", "Outsider", lara_hub::auth::Role::Student, "1234").await;
    let outsider = class.hub.open_session(outsider).await;
    let (_, other_teacher) = class.other_class().await;
    let admin = class.hub.seed_user("ADMIN-0001", "Hub Admin", lara_hub::auth::Role::Admin, "1234").await;
    let admin = class.hub.open_session(admin).await;
    for token in [&outsider, &other_teacher, &admin] {
        assert_eq!(get_quiz(&class, &id, token).await.0, 403);
    }
    assert_eq!(class.hub.get("/api/quizzes/active", Some(&outsider)).await.0, 404, "no active quiz in their classes");
    assert_eq!(get_quiz(&class, "no-such-quiz", class.teacher()).await.0, 404);
}

async fn begin(class: &Class, id: &str, token: &str) -> (u16, Value) {
    class.hub.post_json(&format!("/api/quizzes/{id}/begin"), &json!({}), Some(token)).await
}

/// An `ACTIVE` quiz in the class (starting it is step 4.1).
async fn active_quiz(class: &Class) -> String {
    let id = draft(class).await["id"].as_str().unwrap().to_owned();
    set_status(class, &id, "ACTIVE").await;
    id
}

#[tokio::test]
async fn begin_opens_one_in_progress_attempt() {
    let class = Class::start().await;
    let id = active_quiz(&class).await;
    let mut teacher = common::ws::connect(&class.hub, class.teacher()).await;
    let (status, first) = begin(&class, &id, &class.learner_token).await;
    assert_eq!(status, 200, "{first}");
    assert!(uuid::Uuid::parse_str(first["attempt_id"].as_str().unwrap()).is_ok(), "{first}");
    assert!(first["started_at"].as_i64().unwrap() > 0);
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts WHERE status = 'IN_PROGRESS'").await, 1);

    let mut presence = common::ws::next(&mut teacher).await;
    while presence["state"] == "ONLINE" || presence["state"] == "OFFLINE" {
        presence = common::ws::next(&mut teacher).await;
    }
    assert_eq!(
        (presence["event"].as_str(), presence["state"].as_str(), presence["student_id"].as_str(), presence["classroom_id"].as_str()),
        (Some("EVENT_PRESENCE"), Some("ANSWERING_QUIZ"), Some(class.learner.as_str()), Some(class.id.as_str()))
    );
    let (status, again) = begin(&class, &id, &class.learner_token).await;
    assert_eq!((status, again), (200, first), "the same attempt comes back");
    common::ws::nothing_within(&mut teacher, 300).await;
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 1);
}

#[tokio::test]
async fn begin_twice_at_once_opens_one_attempt() {
    let class = Class::start().await;
    let id = active_quiz(&class).await;
    let (a, b) = tokio::join!(begin(&class, &id, &class.learner_token), begin(&class, &id, &class.learner_token));
    assert_eq!((a.0, b.0), (200, 200), "{a:?} {b:?}");
    assert_eq!(a.1["attempt_id"], b.1["attempt_id"]);
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 1);
}

#[tokio::test]
async fn only_active_learners_begin() {
    let class = Class::start().await;
    let draft_id = draft(&class).await["id"].as_str().unwrap().to_owned();
    let (status, body) = begin(&class, &draft_id, &class.learner_token).await;
    assert_eq!((status, message(&body)), (404, "Quiz not found"));
    set_status(&class, &draft_id, "CLOSED").await;
    let (status, body) = begin(&class, &draft_id, &class.learner_token).await;
    assert_eq!((status, code(&body), message(&body)), (409, "QUIZ_CLOSED", "This quiz is closed"));

    let id = active_quiz(&class).await;
    let (_, pending) = class.add_learner("123456789013", false).await;
    assert_eq!(begin(&class, &id, &pending).await.0, 403);
    assert_eq!(begin(&class, &id, class.teacher()).await.0, 403);
    assert_eq!(begin(&class, "no-such-quiz", &class.learner_token).await.0, 404);

    let path = format!("/api/classrooms/{}/remove", class.id);
    let (status, body) = class.hub.post_json(&path, &json!({"student_id": class.learner}), Some(class.teacher())).await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(begin(&class, &id, &class.learner_token).await.0, 403, "a removed learner cannot begin");

    let (ana, ana_token) = class.add_learner("123456789014", true).await;
    let _ = ana;
    class.archive().await;
    let (status, body) = begin(&class, &id, &ana_token).await;
    assert_eq!((status, message(&body)), (403, "This class is archived"));
    assert_eq!(class.count("SELECT COUNT(*) FROM quiz_attempts").await, 0);
}

/// Review of #61: a teacher app's "duplicate question" sends one id twice; only the first keeps it.
#[tokio::test]
async fn a_question_id_sent_twice_is_kept_once() {
    let class = Class::start().await;
    let quiz = draft(&class).await;
    let id = quiz["id"].as_str().unwrap();
    let mut copy = quiz["questions"][0].clone();
    copy.as_object_mut().unwrap().remove("order_index");
    let body = json!({"title": "Ecosystem", "time_limit_minutes": 15, "questions": [copy.clone(), copy]});
    let (status, edited) = edit(&class, id, &body, class.teacher()).await;
    assert_eq!(status, 200, "{edited}");
    let ids: Vec<&str> = edited["questions"].as_array().unwrap().iter().map(|q| q["id"].as_str().unwrap()).collect();
    assert_eq!(ids[0], quiz["questions"][0]["id"].as_str().unwrap());
    assert_ne!(ids[0], ids[1]);
}

/// Review of #61: step 4.2 adds up the points and checks submit time against limit x 60 000 + 60 000; both
/// must stay inside 2^53 - 1 so the attempt row reaches the desktop and nothing overflows.
#[tokio::test]
async fn totals_stay_inside_what_the_apps_store() {
    let class = Class::start().await;
    let mut body = quiz_body(&class.id);
    body["questions"][0]["points"] = json!(9_007_199_254_740_991_i64);
    let (status, answer) = create(&class, class.teacher(), &body).await;
    assert_eq!((status, message(&answer)), (400, "points add up to more than 9007199254740991"));
    let mut body = quiz_body(&class.id);
    body["time_limit_minutes"] = json!(150_119_987_579_i64);
    let (status, answer) = create(&class, class.teacher(), &body).await;
    assert_eq!((status, message(&answer)), (400, "time_limit_minutes must be from 1 to 150119987578"));
    body["time_limit_minutes"] = json!(150_119_987_578_i64);
    assert_eq!(create(&class, class.teacher(), &body).await.0, 200);
}
