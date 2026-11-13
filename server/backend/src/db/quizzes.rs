//! `quizzes` and `quiz_questions` rows (step 4.5). `correct_answer` and `synonyms_json` are read only by
//! the teacher view; the learner queries never select them.

use std::collections::HashSet;

use sqlx::{FromRow, SqliteConnection};

use super::sync_revisions::{record_revision, Action, Scope};
use crate::auth::AuthUser;
use crate::services::grading_engine::{grade, GradingQuestion};
use crate::models::quiz::{
    AnswerInput, QuestionInput, QuestionType, QuizInput, ResultRow, StudentQuestion, StudentQuiz, TeacherQuestion, TeacherQuiz,
};

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
    insert_questions(tx, id, &input.questions, HashSet::new(), now_ms).await?;
    record_revision(tx, Scope::class(classroom_id), "quizzes", id, Action::Upsert, now_ms).await?;
    teacher_view(tx, id).await?.ok_or(sqlx::Error::RowNotFound)
}

/// A question keeps its `id` only when it is one of `keep` (this quiz's own questions), and only the first
/// time: any other id the client sends, or the same id sent again (a duplicated question), would collide on
/// the primary key, so it gets a new one.
async fn insert_questions(
    tx: &mut SqliteConnection,
    quiz_id: &str,
    questions: &[QuestionInput],
    mut keep: HashSet<String>,
    now_ms: i64,
) -> Result<(), sqlx::Error> {
    for q in questions {
        let id = q.id.clone().filter(|id| keep.remove(id)).unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
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

/// The quiz's class and status, for the route's checks.
pub async fn meta(conn: &mut SqliteConnection, id: &str) -> Result<Option<(String, String)>, sqlx::Error> {
    sqlx::query_as("SELECT classroom_id, status FROM quizzes WHERE id = ?").bind(id).fetch_optional(conn).await
}

/// Replaces a draft's settings and questions (contract `PATCH /api/quizzes/{id}`). The caller has
/// checked it is still `DRAFT`, so no attempt can point at a question that goes away.
pub async fn replace(
    tx: &mut SqliteConnection,
    id: &str,
    classroom_id: &str,
    input: &QuizInput,
    now_ms: i64,
) -> Result<TeacherQuiz, sqlx::Error> {
    sqlx::query(
        "UPDATE quizzes SET title = ?, instructions = ?, time_limit_minutes = ?, shuffle_questions = ?,
             release_scores_immediately = ?, topic_id = CASE WHEN ? THEN ? ELSE topic_id END, updated_at = ?
         WHERE id = ?",
    )
    .bind(&input.title)
    .bind(&input.instructions)
    .bind(input.time_limit_minutes)
    .bind(input.shuffle_questions)
    .bind(input.release_scores_immediately)
    .bind(input.topic_id.is_some())
    .bind(input.topic_id.clone().flatten())
    .bind(now_ms)
    .bind(id)
    .execute(&mut *tx)
    .await?;
    let own: HashSet<String> =
        sqlx::query_scalar("SELECT id FROM quiz_questions WHERE quiz_id = ?").bind(id).fetch_all(&mut *tx).await?.into_iter().collect();
    sqlx::query("DELETE FROM quiz_questions WHERE quiz_id = ?").bind(id).execute(&mut *tx).await?;
    insert_questions(tx, id, &input.questions, own, now_ms).await?;
    record_revision(tx, Scope::class(classroom_id), "quizzes", id, Action::Upsert, now_ms).await?;
    teacher_view(tx, id).await?.ok_or(sqlx::Error::RowNotFound)
}

#[derive(FromRow)]
struct StudentQuizRow {
    id: String,
    classroom_id: String,
    title: String,
    time_limit_minutes: i64,
    started_at: Option<i64>,
    shuffle_questions: bool,
}

#[derive(FromRow)]
struct StudentQuestionRow {
    id: String,
    order_index: i64,
    question_text: String,
    question_type: String,
    options_json: Option<String>,
    points: i64,
}

/// The quiz as a learner may see it. The queries never select `correct_answer` or `synonyms_json`.
/// With `shuffle_questions` on, `shuffle_for` gives each learner their own order, the same on every
/// request (PRD FR-4.1); grading goes by question id, so the order never changes a score. `None` keeps
/// the set order (a teacher's `/active`).
pub async fn student_view(
    conn: &mut SqliteConnection,
    id: &str,
    learner: Option<&str>,
) -> Result<Option<StudentQuiz>, sqlx::Error> {
    let Some(row) = sqlx::query_as::<_, StudentQuizRow>(
        "SELECT id, classroom_id, title, time_limit_minutes, started_at, shuffle_questions FROM quizzes WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(&mut *conn)
    .await?
    else {
        return Ok(None);
    };
    let rows: Vec<StudentQuestionRow> = sqlx::query_as(
        "SELECT id, order_index, question_text, question_type, options_json, points
         FROM quiz_questions WHERE quiz_id = ? ORDER BY order_index",
    )
    .bind(id)
    .fetch_all(&mut *conn)
    .await?;
    let mut questions: Vec<StudentQuestion> = rows
        .into_iter()
        .map(|q| StudentQuestion {
            options: json_list(q.options_json.as_deref()),
            question_type: QuestionType::parse(&q.question_type).unwrap_or(QuestionType::Identification),
            id: q.id,
            order_index: q.order_index,
            question_text: q.question_text,
            points: q.points,
        })
        .collect();
    if let (true, Some(learner)) = (row.shuffle_questions, learner) {
        shuffle_for(&mut questions, &row.id, learner);
        // Numbered in the learner's own order: clients sort by `order_index` (mobile's quiz cache), which
        // would otherwise undo the shuffle. Grading goes by question id (review of #61).
        for (question, number) in questions.iter_mut().zip(1..) {
            question.order_index = number;
        }
    }
    Ok(Some(StudentQuiz {
        id: row.id,
        classroom_id: row.classroom_id,
        title: row.title,
        time_limit_minutes: row.time_limit_minutes,
        started_at: row.started_at,
        questions,
    }))
}

/// Fisher-Yates driven by a xorshift64 seeded with the FNV-1a hash of "quiz:learner". Deterministic, so a
/// learner who reloads sees the same order, and different for each learner. Not for secrets.
fn shuffle_for<T>(items: &mut [T], quiz_id: &str, learner: &str) {
    let mut seed = 0xcbf2_9ce4_8422_2325_u64;
    for byte in quiz_id.bytes().chain(std::iter::once(b':')).chain(learner.bytes()) {
        seed = (seed ^ u64::from(byte)).wrapping_mul(0x0100_0000_01b3);
    }
    let mut state = seed | 1;
    for i in (1..items.len()).rev() {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        let j = (state % (i as u64 + 1)) as usize;
        items.swap(i, j);
    }
}

/// The caller's active quiz: the most recently started `ACTIVE` quiz in a class they are an `ACTIVE`
/// learner of, or teach. A learner in two classes could have two at once (one per class).
pub async fn active_for(conn: &mut SqliteConnection, user: &AuthUser) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar(
        "SELECT q.id FROM quizzes q WHERE q.status = 'ACTIVE' AND (
             (? = 'STUDENT' AND EXISTS (SELECT 1 FROM enrollments e
                 WHERE e.classroom_id = q.classroom_id AND e.student_id = ? AND e.status = 'ACTIVE'))
          OR (? = 'TEACHER' AND (EXISTS (SELECT 1 FROM classrooms c WHERE c.id = q.classroom_id AND c.teacher_id = ?)
                 OR EXISTS (SELECT 1 FROM classroom_teachers t WHERE t.classroom_id = q.classroom_id AND t.teacher_id = ?))))
         ORDER BY q.started_at DESC, q.id LIMIT 1",
    )
    .bind(user.role.as_str())
    .bind(&user.id)
    .bind(user.role.as_str())
    .bind(&user.id)
    .bind(&user.id)
    .fetch_optional(conn)
    .await
}

/// The learner's attempt, opened on their first `/begin`: `(attempt_id, started_at, newly_opened)`. One
/// attempt per learner per quiz (`UNIQUE(quiz_id, student_id)`, PRD FR-4.1); a repeat, even after
/// submitting, gets the stored one back. The `IN_PROGRESS` row is what the AI lockout checks
/// (rules/quiz-and-anti-cheat.md section 1); pull sends only submitted attempts.
pub async fn begin(
    tx: &mut SqliteConnection,
    quiz_id: &str,
    classroom_id: &str,
    student_id: &str,
    now_ms: i64,
) -> Result<(String, i64, bool), sqlx::Error> {
    let existing: Option<(String, i64)> =
        sqlx::query_as("SELECT id, started_at FROM quiz_attempts WHERE quiz_id = ? AND student_id = ?")
            .bind(quiz_id)
            .bind(student_id)
            .fetch_optional(&mut *tx)
            .await?;
    if let Some((id, started_at)) = existing {
        return Ok((id, started_at, false));
    }
    let id = uuid::Uuid::new_v4().to_string();
    sqlx::query(
        "INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, created_at, answers_json, updated_at)
         VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, '[]', ?)",
    )
    .bind(&id)
    .bind(quiz_id)
    .bind(student_id)
    .bind(now_ms)
    .bind(now_ms)
    .bind(now_ms)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::student(classroom_id, student_id), "quiz_attempts", &id, Action::Upsert, now_ms).await?;
    Ok((id, now_ms, true))
}

/// What submit, start and close need to know about the quiz.
#[derive(FromRow)]
pub struct QuizState {
    pub classroom_id: String,
    pub title: String,
    pub status: String,
    pub started_at: Option<i64>,
    pub closed_at: Option<i64>,
    pub time_limit_minutes: i64,
    pub release_scores_immediately: bool,
}

pub async fn quiz_state(conn: &mut SqliteConnection, id: &str) -> Result<Option<QuizState>, sqlx::Error> {
    sqlx::query_as(
        "SELECT classroom_id, title, status, started_at, closed_at, time_limit_minutes, release_scores_immediately
         FROM quizzes WHERE id = ?",
    )
    .bind(id)
    .fetch_optional(conn)
    .await
}

/// The title of the class's `ACTIVE` quiz, if one is running (one per class, `uq_one_active_quiz_per_classroom`).
pub async fn active_in_class(conn: &mut SqliteConnection, classroom_id: &str) -> Result<Option<String>, sqlx::Error> {
    sqlx::query_scalar("SELECT title FROM quizzes WHERE classroom_id = ? AND status = 'ACTIVE'")
        .bind(classroom_id)
        .fetch_optional(conn)
        .await
}

/// Starts a quiz at the Hub's now, with a class-wide revision so the pull sends it `ACTIVE`.
pub async fn start(tx: &mut SqliteConnection, quiz_id: &str, classroom_id: &str, now_ms: i64) -> Result<(), sqlx::Error> {
    sqlx::query("UPDATE quizzes SET status = 'ACTIVE', started_at = ?, updated_at = ? WHERE id = ?")
        .bind(now_ms)
        .bind(now_ms)
        .bind(quiz_id)
        .execute(&mut *tx)
        .await?;
    record_revision(tx, Scope::class(classroom_id), "quizzes", quiz_id, Action::Upsert, now_ms).await?;
    Ok(())
}

/// The learner's attempt on a quiz, if any.
#[derive(FromRow)]
pub struct AttemptRow {
    pub id: String,
    pub status: String,
    pub started_at: i64,
    pub submitted_at: Option<i64>,
    pub score: Option<i64>,
    pub total_points: Option<i64>,
    /// Submitted by the close, not the learner: on-time work may replace it once (contract #293).
    pub auto_submitted: bool,
}

pub async fn attempt_for(conn: &mut SqliteConnection, quiz_id: &str, student_id: &str) -> Result<Option<AttemptRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT id, status, started_at, submitted_at, score, total_points, auto_submitted
         FROM quiz_attempts WHERE quiz_id = ? AND student_id = ?",
    )
    .bind(quiz_id)
    .bind(student_id)
    .fetch_optional(conn)
    .await
}

/// The answer key, for grading on the Hub only.
pub async fn grading_key(conn: &mut SqliteConnection, quiz_id: &str) -> Result<Vec<GradingQuestion>, sqlx::Error> {
    let rows: Vec<(String, String, i64, String, Option<String>)> = sqlx::query_as(
        "SELECT id, question_type, points, correct_answer, synonyms_json FROM quiz_questions WHERE quiz_id = ? ORDER BY order_index",
    )
    .bind(quiz_id)
    .fetch_all(conn)
    .await?;
    Ok(rows
        .into_iter()
        .map(|(id, kind, points, correct_answer, synonyms)| GradingQuestion {
            id,
            question_type: QuestionType::parse(&kind).unwrap_or(QuestionType::Identification),
            points,
            correct_answer,
            synonyms: json_list(synonyms.as_deref()),
        })
        .collect())
}

/// A graded submission, as the attempt row. Updates the `IN_PROGRESS` row `/begin` made, or inserts one
/// (a learner who never reached `/begin`); either way one row per learner and quiz. Returns the attempt id.
pub struct GradedAttempt<'a> {
    pub existing: Option<&'a str>,
    pub quiz_id: &'a str,
    pub classroom_id: &'a str,
    pub student_id: &'a str,
    pub started_at: i64,
    pub submitted_at: i64,
    pub score: i64,
    pub total_points: i64,
    pub answers_json: &'a str,
    /// True only for the close's auto-submit, which on-time work may replace once (contract #293).
    pub auto_submitted: bool,
}

pub async fn store_submission(tx: &mut SqliteConnection, a: &GradedAttempt<'_>, now_ms: i64) -> Result<String, sqlx::Error> {
    let id = match a.existing {
        Some(id) => {
            sqlx::query(
                "UPDATE quiz_attempts SET status = 'SUBMITTED', submitted_at = ?, score = ?, total_points = ?, answers_json = ?,
                     auto_submitted = ?, updated_at = ?
                 WHERE id = ?",
            )
            .bind(a.submitted_at)
            .bind(a.score)
            .bind(a.total_points)
            .bind(a.answers_json)
            .bind(a.auto_submitted)
            .bind(now_ms)
            .bind(id)
            .execute(&mut *tx)
            .await?;
            id.to_owned()
        }
        None => {
            let id = uuid::Uuid::new_v4().to_string();
            sqlx::query(
                "INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, created_at, submitted_at, score,
                                            total_points, answers_json, auto_submitted, updated_at)
                 VALUES (?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            .bind(&id)
            .bind(a.quiz_id)
            .bind(a.student_id)
            .bind(a.started_at)
            .bind(a.started_at)
            .bind(a.submitted_at)
            .bind(a.score)
            .bind(a.total_points)
            .bind(a.answers_json)
            .bind(a.auto_submitted)
            .bind(now_ms)
            .execute(&mut *tx)
            .await?;
            id
        }
    };
    record_revision(tx, Scope::student(a.classroom_id, a.student_id), "quiz_attempts", &id, Action::Upsert, now_ms).await?;
    Ok(id)
}

/// A held score the close released, for its `EVENT_GRADE_CONFIRMED`.
pub struct ReleasedScore {
    pub student_id: String,
    pub attempt_id: String,
    pub score: i64,
    pub total_points: i64,
}

/// Closes the quiz at the Hub's now (contract #293, #294; `rules/quiz-and-anti-cheat.md` sections 2 and 4):
/// marks it `CLOSED` with `closed_at` (and `scores_released_at` when scores were held, TECH_SPEC Q16),
/// auto-submits each `IN_PROGRESS` attempt graded from its stored answers, and gives each attempt submitted
/// before the close a new revision when scores were held, so a learner who pulled `score: null` gets the
/// score. Returns those released scores; auto-submitted attempts are never among them.
pub async fn close(
    tx: &mut SqliteConnection,
    quiz_id: &str,
    quiz: &QuizState,
    key: &[GradingQuestion],
    now_ms: i64,
) -> Result<Vec<ReleasedScore>, sqlx::Error> {
    let held = !quiz.release_scores_immediately;
    sqlx::query(
        "UPDATE quizzes SET status = 'CLOSED', closed_at = ?, scores_released_at = CASE WHEN ? THEN ? ELSE scores_released_at END,
             updated_at = ?
         WHERE id = ?",
    )
    .bind(now_ms)
    .bind(held)
    .bind(now_ms)
    .bind(now_ms)
    .bind(quiz_id)
    .execute(&mut *tx)
    .await?;
    record_revision(tx, Scope::class(&quiz.classroom_id), "quizzes", quiz_id, Action::Upsert, now_ms).await?;

    let unfinished: Vec<(String, String, i64, String)> = sqlx::query_as(
        "SELECT id, student_id, started_at, answers_json FROM quiz_attempts WHERE quiz_id = ? AND status = 'IN_PROGRESS'",
    )
    .bind(quiz_id)
    .fetch_all(&mut *tx)
    .await?;
    for (id, student_id, started_at, answers_json) in unfinished {
        let answers: Vec<AnswerInput> = serde_json::from_str(&answers_json).unwrap_or_default();
        let (score, total_points) = grade(key, &answers);
        let attempt = GradedAttempt {
            existing: Some(&id),
            quiz_id,
            classroom_id: &quiz.classroom_id,
            student_id: &student_id,
            started_at,
            submitted_at: now_ms,
            score,
            total_points,
            answers_json: &answers_json,
            auto_submitted: true,
        };
        store_submission(tx, &attempt, now_ms).await?;
    }

    if !held {
        return Ok(Vec::new());
    }
    let submitted: Vec<(String, String, i64, i64)> = sqlx::query_as(
        "SELECT student_id, id, score, total_points FROM quiz_attempts
         WHERE quiz_id = ? AND status = 'SUBMITTED' AND auto_submitted = 0",
    )
    .bind(quiz_id)
    .fetch_all(&mut *tx)
    .await?;
    let mut released = Vec::with_capacity(submitted.len());
    for (student_id, attempt_id, score, total_points) in submitted {
        record_revision(tx, Scope::student(&quiz.classroom_id, &student_id), "quiz_attempts", &attempt_id, Action::Upsert, now_ms)
            .await?;
        released.push(ReleasedScore { student_id, attempt_id, score, total_points });
    }
    Ok(released)
}

/// One row per `ACTIVE` learner of the class, by name: `NOT_STARTED` without an attempt, the attempt's
/// status otherwise, and the score only once submitted.
pub async fn results(conn: &mut SqliteConnection, quiz_id: &str, classroom_id: &str) -> Result<Vec<ResultRow>, sqlx::Error> {
    sqlx::query_as(
        "SELECT u.id AS student_id, u.full_name, COALESCE(a.status, 'NOT_STARTED') AS status,
                CASE WHEN a.status = 'SUBMITTED' THEN a.score END AS score,
                CASE WHEN a.status = 'SUBMITTED' THEN a.total_points END AS total_points
         FROM enrollments e JOIN users u ON u.id = e.student_id
         LEFT JOIN quiz_attempts a ON a.quiz_id = ? AND a.student_id = e.student_id
         WHERE e.classroom_id = ? AND e.status = 'ACTIVE'
         ORDER BY u.full_name, u.id",
    )
    .bind(quiz_id)
    .bind(classroom_id)
    .fetch_all(conn)
    .await
}
