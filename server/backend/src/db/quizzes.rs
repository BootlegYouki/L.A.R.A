//! `quizzes` and `quiz_questions` rows (step 4.5). `correct_answer` and `synonyms_json` are read only by
//! the teacher view; the learner queries never select them.

use std::collections::HashSet;

use sqlx::{FromRow, SqliteConnection};

use super::sync_revisions::{record_revision, Action, Scope};
use crate::models::quiz::{QuestionInput, QuestionType, QuizInput, TeacherQuestion, TeacherQuiz};

#[derive(FromRow)]
struct QuizRow {
    id: String,
    classroom_id: String,
    title: String,
    instructions: Option<String>,
    time_limit_minutes: i64,
    shuffle_questions: bool,
    release_scores_immediately: bool,
    topic_id: Option<String>,
    status: String,
    started_at: Option<i64>,
}

#[derive(FromRow)]
struct TeacherQuestionRow {
    id: String,
    order_index: i64,
    question_text: String,
    question_type: String,
    options_json: Option<String>,
    points: i64,
    correct_answer: String,
    synonyms_json: Option<String>,
}

fn json_list(text: Option<&str>) -> Vec<String> {
    text.and_then(|t| serde_json::from_str(t).ok()).unwrap_or_default()
}

/// A new `DRAFT` quiz with its questions, and its class-wide revision.
pub async fn insert(
    tx: &mut SqliteConnection,
    id: &str,
    classroom_id: &str,
    input: &QuizInput,
    now_ms: i64,
) -> Result<TeacherQuiz, sqlx::Error> {
    sqlx::query(
        "INSERT INTO quizzes (id, classroom_id, title, instructions, topic_id, time_limit_minutes, shuffle_questions,
                              release_scores_immediately, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)",
    )
    .bind(id)
    .bind(classroom_id)
    .bind(&input.title)
    .bind(&input.instructions)
    .bind(input.topic_id.clone().flatten())
    .bind(input.time_limit_minutes)
    .bind(input.shuffle_questions)
    .bind(input.release_scores_immediately)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    insert_questions(tx, id, &input.questions, &HashSet::new(), now_ms).await?;
    record_revision(tx, Scope::class(classroom_id), "quizzes", id, Action::Upsert, now_ms).await?;
    teacher_view(tx, id).await?.ok_or(sqlx::Error::RowNotFound)
}

/// A question keeps its `id` only when it is one of `keep` (this quiz's own questions); any other id the
/// client sends would collide with another quiz's question (primary key), so it gets a new one.
async fn insert_questions(
    tx: &mut SqliteConnection,
    quiz_id: &str,
    questions: &[QuestionInput],
    keep: &HashSet<String>,
    now_ms: i64,
) -> Result<(), sqlx::Error> {
    for q in questions {
        let id = q.id.clone().filter(|id| keep.contains(id)).unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        sqlx::query(
            "INSERT INTO quiz_questions (id, quiz_id, order_index, question_text, question_type, options_json, points,
                                         correct_answer, synonyms_json, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        .bind(&id)
        .bind(quiz_id)
        .bind(q.order_index)
        .bind(&q.question_text)
        .bind(q.question_type.as_str())
        .bind(serde_json::to_string(&q.options).unwrap_or_else(|_| "[]".into()))
        .bind(q.points)
        .bind(&q.correct_answer)
        .bind(serde_json::to_string(&q.synonyms).unwrap_or_else(|_| "[]".into()))
        .bind(now_ms)
        .bind(now_ms)
        .execute(&mut *tx)
        .await?;
    }
    Ok(())
}

/// The whole quiz with its answer key: teacher routes only.
pub async fn teacher_view(conn: &mut SqliteConnection, id: &str) -> Result<Option<TeacherQuiz>, sqlx::Error> {
    let Some(row) = sqlx::query_as::<_, QuizRow>(
        "SELECT id, classroom_id, title, instructions, time_limit_minutes, shuffle_questions, release_scores_immediately,
                topic_id, status, started_at
         FROM quizzes WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&mut *conn)
    .await?
    else {
        return Ok(None);
    };
    let questions: Vec<TeacherQuestionRow> = sqlx::query_as(
        "SELECT id, order_index, question_text, question_type, options_json, points, correct_answer, synonyms_json
         FROM quiz_questions WHERE quiz_id = ? ORDER BY order_index",
    )
    .bind(id)
    .fetch_all(&mut *conn)
    .await?;
    Ok(Some(TeacherQuiz {
        id: row.id,
        classroom_id: row.classroom_id,
        title: row.title,
        instructions: row.instructions,
        time_limit_minutes: row.time_limit_minutes,
        shuffle_questions: row.shuffle_questions,
        release_scores_immediately: row.release_scores_immediately,
        topic_id: row.topic_id,
        status: row.status,
        started_at: row.started_at,
        questions: questions
            .into_iter()
            .map(|q| TeacherQuestion {
                options: json_list(q.options_json.as_deref()),
                synonyms: json_list(q.synonyms_json.as_deref()),
                question_type: QuestionType::parse(&q.question_type).unwrap_or(QuestionType::Identification),
                id: q.id,
                order_index: q.order_index,
                question_text: q.question_text,
                points: q.points,
                correct_answer: q.correct_answer,
            })
            .collect(),
    }))
}
