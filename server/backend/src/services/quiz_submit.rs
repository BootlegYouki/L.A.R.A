//! The one quiz submit path (step 4.2). REST (`POST /api/quizzes/{id}/submit`), the offline push
//! (`POST /api/sync/push` `quiz_attempts`) and the WebSocket (`EVENT_QUIZ_SUBMIT`) all call `submit`, so
//! validation, timing, grading and storage cannot drift apart.

use crate::auth::{require_role, AuthUser, Role};
use crate::db::quizzes::{self, AttemptRow, GradedAttempt, QuizState};
use crate::db::begin_immediate;
use crate::error::ApiError;
use crate::models::quiz::AnswerInput;
use crate::models::sync::GradeReceipt;
use crate::services::{class_access, grading_engine};
use crate::websocket::events::{PresenceState, ServerEvent};
use crate::AppState;

/// Network grace on top of the time limit (rules/quiz-and-anti-cheat.md section 3, rule 5).
const GRACE_MS: i64 = 60_000;

pub struct Submission {
    /// The device's start; used only when the learner has no attempt row (never reached `/begin`).
    pub started_at: Option<i64>,
    /// The device's finish, which may be an offline finish (PRD FR-4.5). Never later than the Hub's now.
    pub submitted_at: i64,
    pub answers: Vec<AnswerInput>,
}

/// The learner's receipt hides a held score until the quiz is `CLOSED` (PRD FR-4.7). The pull does the same.
fn receipt(quiz: &QuizState, quiz_id: &str, student_id: &str, attempt: &AttemptRow) -> GradeReceipt {
    let held = !quiz.release_scores_immediately && quiz.status != "CLOSED";
    GradeReceipt {
        attempt_id: attempt.id.clone(),
        quiz_id: quiz_id.to_owned(),
        student_id: student_id.to_owned(),
        score: if held { None } else { attempt.score },
        total_points: attempt.total_points.unwrap_or_default(),
        submitted_at: attempt.submitted_at.unwrap_or_default(),
    }
}

/// Grades and stores a learner's finished quiz, once: the quiz (404, and a `DRAFT` is 404 too), membership
/// (403), a stored submission comes back as it is, then the archived class (403) and the time limit (422).
/// Unlike the mock's `submit_attempt`, a stored submission returns before the time check, so a retry always
/// gets its receipt.
///
/// Timing trusts the Hub's clock (`server/AGENTS.md`): the start is the attempt's `started_at` from `/begin`.
/// Only without an attempt row is the device's start used, and then not before the quiz's own start or
/// after the Hub's now. The finish is the device's (an offline finish counts, FR-4.5) but never after the
/// Hub's now. A device finish before the Hub's start means the device clock is behind: then the elapsed time
/// is the device's own duration (its start to its finish, so the offset cancels), or for a WebSocket submit,
/// which is live and carries no start, the time since the Hub's start (review of #12). Clients are expected
/// to send Hub-synchronized times (mobile and desktop TECH_SPECs); a device clock *ahead* of the Hub on an
/// offline finish pushed later can still be refused wrongly.
/// A repeat of work already submitted returns its receipt and sends no event again.
pub async fn submit(app: &AppState, user: &AuthUser, quiz_id: &str, s: Submission) -> Result<GradeReceipt, ApiError> {
    require_role(user, Role::Student)?;
    let now = app.clock.now_ms();
    let not_found = || ApiError::not_found("Quiz not found");
    let mut tx = begin_immediate(&app.db).await?;
    let quiz = quizzes::quiz_state(&mut tx, quiz_id).await?.filter(|q| q.status != "DRAFT").ok_or_else(not_found)?;
    class_access::require_member(&mut tx, user, &quiz.classroom_id).await?;
    let attempt = quizzes::attempt_for(&mut tx, quiz_id, &user.id).await?;
    if let Some(done) = attempt.as_ref().filter(|a| a.status == "SUBMITTED") {
        return Ok(receipt(&quiz, quiz_id, &user.id, done));
    }
    class_access::require_writable(&mut tx, &quiz.classroom_id).await?;
    let start = match &attempt {
        Some(a) => a.started_at,
        None => {
            let claimed = s.started_at.ok_or_else(|| ApiError::bad_request("Missing: started_at"))?;
            claimed.max(quiz.started_at.unwrap_or(claimed)).min(now)
        }
    };
    let (elapsed, finish) = if s.submitted_at >= start {
        let finish = s.submitted_at.min(now);
        (finish - start, finish)
    } else {
        let took = s.started_at.map_or(now.saturating_sub(start), |device_start| s.submitted_at.saturating_sub(device_start));
        let took = took.max(0);
        (took, start.saturating_add(took).min(now))
    };
    if elapsed > quiz.time_limit_minutes * 60_000 + GRACE_MS {
        return Err(ApiError::time_limit_exceeded());
    }
    let key = quizzes::grading_key(&mut tx, quiz_id).await?;
    let (score, total_points) = grading_engine::grade(&key, &s.answers);
    let answers_json = serde_json::to_string(&s.answers).unwrap_or_else(|_| "[]".into());
    let attempt_id = quizzes::store_submission(
        &mut tx,
        &GradedAttempt {
            existing: attempt.as_ref().map(|a| a.id.as_str()),
            quiz_id,
            classroom_id: &quiz.classroom_id,
            student_id: &user.id,
            started_at: start,
            submitted_at: finish,
            score,
            total_points,
            answers_json: &answers_json,
        },
        now,
    )
    .await?;
    tx.commit().await?;

    let stored = AttemptRow {
        id: attempt_id,
        status: "SUBMITTED".into(),
        started_at: start,
        submitted_at: Some(finish),
        score: Some(score),
        total_points: Some(total_points),
    };
    if quiz.release_scores_immediately {
        let graded = ServerEvent::GradeConfirmed {
            quiz_id: quiz_id.to_owned(),
            attempt_id: stored.id.clone(),
            score,
            total_points,
            timestamp: now,
        };
        app.broker.send_to_user(&user.id, &graded);
    }
    let presence = ServerEvent::Presence {
        classroom_id: quiz.classroom_id.clone(),
        student_id: user.id.clone(),
        state: PresenceState::Submitted,
        timestamp: now,
    };
    if let Err(error) = app.broker.send_to_teacher(&app.db, &quiz.classroom_id, &presence).await {
        tracing::warn!(%error, "quiz presence push failed");
    }
    Ok(receipt(&quiz, quiz_id, &user.id, &stored))
}
