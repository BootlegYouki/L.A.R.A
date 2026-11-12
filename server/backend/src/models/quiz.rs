//! Quiz wire types (contract `TeacherQuiz`, `TeacherQuestion`, `StudentQuizResponse`, `StudentQuestion`).
//! The learner types are separate structs with no answer field, so a learner route cannot serialize
//! `correct_answer` or `synonyms`, even by mistake (`server/AGENTS.md`, "Redaction by type").

use serde::Serialize;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum QuestionType {
    MultipleChoice,
    TrueFalse,
    Identification,
}

impl QuestionType {
    pub fn parse(text: &str) -> Option<Self> {
        match text {
            "MULTIPLE_CHOICE" => Some(Self::MultipleChoice),
            "TRUE_FALSE" => Some(Self::TrueFalse),
            "IDENTIFICATION" => Some(Self::Identification),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::MultipleChoice => "MULTIPLE_CHOICE",
            Self::TrueFalse => "TRUE_FALSE",
            Self::Identification => "IDENTIFICATION",
        }
    }

    /// Multiple choice and true/false are graded by an exact match against one of the options.
    pub fn answers_from_options(self) -> bool {
        self != Self::Identification
    }
}

/// One answer in a submission (contract `Answer`).
#[derive(Clone, Debug, serde::Deserialize, Serialize)]
pub struct AnswerInput {
    pub question_id: String,
    pub selected_option: String,
}

/// A question as a teacher sent it, already checked (`routes::quizzes`).
pub struct QuestionInput {
    pub id: Option<String>,
    pub order_index: i64,
    pub question_text: String,
    pub question_type: QuestionType,
    pub options: Vec<String>,
    pub points: i64,
    pub correct_answer: String,
    pub synonyms: Vec<String>,
}

/// A quiz's settings and questions as a teacher sent them, already checked. `topic_id: Some(None)`
/// clears the topic.
pub struct QuizInput {
    pub title: String,
    pub instructions: Option<String>,
    pub time_limit_minutes: i64,
    pub shuffle_questions: bool,
    pub release_scores_immediately: bool,
    pub topic_id: Option<Option<String>>,
    pub questions: Vec<QuestionInput>,
}

/// Teacher only: carries the answer key.
#[derive(Serialize)]
pub struct TeacherQuestion {
    pub id: String,
    pub order_index: i64,
    pub question_text: String,
    pub question_type: QuestionType,
    pub options: Vec<String>,
    pub points: i64,
    pub correct_answer: String,
    pub synonyms: Vec<String>,
}

/// Teacher only (contract `TeacherQuiz`).
#[derive(Serialize)]
pub struct TeacherQuiz {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub instructions: Option<String>,
    pub time_limit_minutes: i64,
    pub shuffle_questions: bool,
    pub release_scores_immediately: bool,
    pub topic_id: Option<String>,
    pub questions: Vec<TeacherQuestion>,
    pub status: String,
    pub started_at: Option<i64>,
}

/// Learner view of a question (contract `StudentQuestion`). Has no answer field by design.
#[derive(Serialize)]
pub struct StudentQuestion {
    pub id: String,
    pub order_index: i64,
    pub question_text: String,
    pub question_type: QuestionType,
    pub options: Vec<String>,
    pub points: i64,
}

/// Learner view of a quiz (contract `StudentQuizResponse`).
#[derive(Serialize)]
pub struct StudentQuiz {
    pub id: String,
    pub classroom_id: String,
    pub title: String,
    pub time_limit_minutes: i64,
    pub started_at: Option<i64>,
    pub questions: Vec<StudentQuestion>,
}

/// One learner's line in a teacher's results (contract `/api/quizzes/{id}/results`). Scores appear only once
/// the attempt is `SUBMITTED`, and they are always the real ones: the teacher sees held scores.
#[derive(Serialize, sqlx::FromRow)]
pub struct ResultRow {
    pub student_id: String,
    pub full_name: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub score: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub total_points: Option<i64>,
}
