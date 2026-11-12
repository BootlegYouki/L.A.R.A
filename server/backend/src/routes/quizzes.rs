//! Quizzes (contract `/api/quizzes*`, PRD FR-4.1, FR-4.3, FR-4.8). Step 4.5 builds authoring, the
//! learner's view and `/begin`; steps 4.1 and 4.2 move start/close/results to `quiz_control.rs` and
//! submit to `quiz_submit.rs`.

use std::collections::HashSet;

use axum::extract::{Path, State};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde_json::Value;

use super::body::JsonBody;
use super::{not_implemented_signed_in as stub, not_implemented_student as student, not_implemented_teacher as teacher};
use crate::auth::{require_role, AuthUser, Role};
use crate::db::materials::{attachment_in_class, Attachment};
use crate::db::{begin_immediate, quizzes};
use crate::error::ApiError;
use crate::models::quiz::{QuestionInput, QuestionType, QuizInput, TeacherQuiz};
use crate::services::class_access;
use crate::AppState;

pub fn router() -> Router<AppState> {
    Router::new()
        .route("/api/quizzes", post(create))
        .route("/api/quizzes/active", get(stub))
        .route("/api/quizzes/{id}", get(stub).patch(update))
        .route("/api/quizzes/{id}/start", post(teacher))
        .route("/api/quizzes/{id}/begin", post(student))
        .route("/api/quizzes/{id}/close", post(teacher))
        .route("/api/quizzes/{id}/submit", post(student))
        .route("/api/quizzes/{id}/results", get(teacher))
}

fn text_list(value: Option<&Value>, key: &str) -> Result<Vec<String>, ApiError> {
    match value {
        None | Some(Value::Null) => Ok(Vec::new()),
        Some(Value::Array(items)) => items
            .iter()
            .map(|v| v.as_str().map(str::to_owned))
            .collect::<Option<Vec<_>>>()
            .ok_or_else(|| ApiError::bad_request(format!("{key} must be a list of text"))),
        Some(_) => Err(ApiError::bad_request(format!("{key} must be a list of text"))),
    }
}

fn parse_question(item: &Value, position: i64) -> Result<QuestionInput, ApiError> {
    let Value::Object(map) = item else {
        return Err(ApiError::bad_request("Each question must be an object"));
    };
    let q = JsonBody(map.clone());
    q.need(&["question_text", "question_type", "correct_answer"])?;
    let question_type = q
        .optional_text("question_type")?
        .as_deref()
        .and_then(QuestionType::parse)
        .ok_or_else(|| ApiError::bad_request("question_type must be MULTIPLE_CHOICE, TRUE_FALSE or IDENTIFICATION"))?;
    let options = text_list(map.get("options"), "options")?;
    let correct_answer = q.non_empty_text("correct_answer")?.unwrap_or_default();
    // Graded by an exact match (rules/quiz-and-anti-cheat.md section 4), so an answer that is not one
    // of the options could never be marked right.
    if question_type.answers_from_options() && !options.contains(&correct_answer) {
        return Err(ApiError::bad_request("correct_answer must be one of the options"));
    }
    Ok(QuestionInput {
        id: q.optional_text("id")?,
        order_index: q.optional_int_from("order_index", 0)?.unwrap_or(position),
        question_text: q.non_empty_text("question_text")?.unwrap_or_default(),
        question_type,
        options,
        points: q.optional_int_from("points", 1)?.unwrap_or(1),
        correct_answer,
        synonyms: text_list(map.get("synonyms"), "synonyms")?,
    })
}

/// Every check the mock's `quiz_fields` does, plus the ones the database and grading need.
fn parse_quiz(body: &JsonBody) -> Result<QuizInput, ApiError> {
    body.need(&["title", "time_limit_minutes", "questions"])?;
    let Some(Value::Array(items)) = body.0.get("questions") else {
        return Err(ApiError::bad_request("questions must be a list"));
    };
    if items.is_empty() {
        return Err(ApiError::bad_request("questions cannot be empty"));
    }
    let questions = items
        .iter()
        .zip(1..)
        .map(|(item, position)| parse_question(item, position))
        .collect::<Result<Vec<_>, _>>()?;
    let mut places = HashSet::new();
    if !questions.iter().all(|q| places.insert(q.order_index)) {
        return Err(ApiError::bad_request("order_index must be different for each question"));
    }
    Ok(QuizInput {
        title: body.non_empty_text("title")?.unwrap_or_default(),
        instructions: body.nullable_text("instructions")?.flatten(),
        time_limit_minutes: body.optional_int_from("time_limit_minutes", 1)?.unwrap_or_default(),
        shuffle_questions: body.optional_bool("shuffle_questions")?.unwrap_or(false),
        release_scores_immediately: body.optional_bool("release_scores_immediately")?.unwrap_or(true),
        topic_id: body.nullable_text("topic_id")?,
        questions,
    })
}

/// A topic of this class, so a quiz never files under another class's topic.
async fn require_topic(conn: &mut sqlx::SqliteConnection, input: &QuizInput, classroom_id: &str) -> Result<(), ApiError> {
    match input.topic_id.as_ref().and_then(Option::as_deref) {
        Some(topic) if !attachment_in_class(conn, Attachment::Topic, topic, classroom_id).await? => {
            Err(ApiError::not_found(Attachment::Topic.not_found()))
        }
        _ => Ok(()),
    }
}

async fn create(State(app): State<AppState>, user: AuthUser, body: JsonBody) -> Result<Json<TeacherQuiz>, ApiError> {
    require_role(&user, Role::Teacher)?;
    body.need(&["classroom_id", "title", "time_limit_minutes", "questions"])?;
    let classroom_id = body.optional_text("classroom_id")?.unwrap_or_default();
    let input = parse_quiz(&body)?;
    let mut tx = begin_immediate(&app.db).await?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    require_topic(&mut tx, &input, &classroom_id).await?;
    let id = uuid::Uuid::new_v4().to_string();
    let quiz = quizzes::insert(&mut tx, &id, &classroom_id, &input, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(quiz))
}

fn quiz_not_found() -> ApiError {
    ApiError::not_found("Quiz not found")
}

/// Replaces a draft (PRD FR-4.8). The mock's order: 404, 403, then 409 once it has started, then the body.
async fn update(
    State(app): State<AppState>,
    user: AuthUser,
    Path(id): Path<String>,
    body: JsonBody,
) -> Result<Json<TeacherQuiz>, ApiError> {
    require_role(&user, Role::Teacher)?;
    let mut tx = begin_immediate(&app.db).await?;
    let (classroom_id, status) = quizzes::meta(&mut tx, &id).await?.ok_or_else(quiz_not_found)?;
    class_access::require_teacher_writable(&mut tx, &user, &classroom_id).await?;
    if status != "DRAFT" {
        return Err(ApiError::quiz_not_draft());
    }
    let input = parse_quiz(&body)?;
    require_topic(&mut tx, &input, &classroom_id).await?;
    let quiz = quizzes::replace(&mut tx, &id, &classroom_id, &input, app.clock.now_ms()).await?;
    tx.commit().await?;
    Ok(Json(quiz))
}
