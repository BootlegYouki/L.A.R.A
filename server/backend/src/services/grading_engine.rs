//! Quiz grading (step 4.2, rules/quiz-and-anti-cheat.md section 4). Pure: no I/O, so it is fast (well
//! under the 50 ms rule) and tested on its own.

use std::collections::HashMap;

use crate::models::quiz::{AnswerInput, QuestionType};

/// A question with its answer key, as grading needs it. Built only from the teacher's key, on the Hub.
pub struct GradingQuestion {
    pub id: String,
    pub question_type: QuestionType,
    pub points: i64,
    pub correct_answer: String,
    pub synonyms: Vec<String>,
}

fn loose(text: &str) -> String {
    text.trim().to_lowercase()
}

impl GradingQuestion {
    /// Multiple choice and true/false: the exact text of the answer (no index matching: the contract's
    /// `selected_option` is text, and an index is ambiguous when the options are numbers). Identification:
    /// trimmed and lower-cased, against the answer and each synonym.
    fn accepts(&self, given: &str) -> bool {
        match self.question_type {
            QuestionType::MultipleChoice | QuestionType::TrueFalse => given == self.correct_answer,
            QuestionType::Identification => {
                let given = loose(given);
                // A blank answer never scores, even against a blank synonym ("plants, " split by a client), since
                // auto-submit sends "" for an unanswered item (review of #12).
                !given.is_empty()
                    && (given == loose(&self.correct_answer) || self.synonyms.iter().any(|s| loose(s) == given))
            }
        }
    }
}

/// `(score, total_points)`. The total counts every question; an unanswered one scores 0. Answers to
/// unknown questions are ignored, and when a question is answered twice the last answer counts.
pub fn grade(questions: &[GradingQuestion], answers: &[AnswerInput]) -> (i64, i64) {
    let given: HashMap<&str, &str> =
        answers.iter().map(|a| (a.question_id.as_str(), a.selected_option.as_str())).collect();
    questions.iter().fold((0, 0), |(score, total), q| {
        let right = given.get(q.id.as_str()).is_some_and(|text| q.accepts(text));
        (score + if right { q.points } else { 0 }, total + q.points)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::quiz::{AnswerInput, QuestionType};

    fn question(id: &str, kind: QuestionType, points: i64, answer: &str, synonyms: &[&str]) -> GradingQuestion {
        GradingQuestion {
            id: id.into(),
            question_type: kind,
            points,
            correct_answer: answer.into(),
            synonyms: synonyms.iter().map(|s| (*s).to_owned()).collect(),
        }
    }

    fn answer(id: &str, text: &str) -> AnswerInput {
        AnswerInput { question_id: id.into(), selected_option: text.into() }
    }

    #[test]
    fn multiple_choice_and_true_false_need_the_exact_text() {
        let quiz = [
            question("mc", QuestionType::MultipleChoice, 1, "Photosynthesis", &[]),
            question("tf", QuestionType::TrueFalse, 1, "True", &[]),
        ];
        assert_eq!(grade(&quiz, &[answer("mc", "Photosynthesis"), answer("tf", "True")]), (2, 2));
        assert_eq!(grade(&quiz, &[answer("mc", "photosynthesis"), answer("tf", "true")]), (0, 2));
        assert_eq!(grade(&quiz, &[answer("mc", " Photosynthesis")]), (0, 2));
    }

    #[test]
    fn identification_ignores_case_and_spaces_and_takes_synonyms() {
        let quiz = [question("id", QuestionType::Identification, 1, "Halaman", &[" plants "])];
        assert_eq!(grade(&quiz, &[answer("id", "  HALAMAN ")]), (1, 1));
        assert_eq!(grade(&quiz, &[answer("id", "Plants")]), (1, 1));
        assert_eq!(grade(&quiz, &[answer("id", "halamann")]), (0, 1));
        let spanish = [question("es", QuestionType::Identification, 1, "mañana", &[])];
        assert_eq!(grade(&spanish, &[answer("es", "MAÑANA")]), (1, 1));
    }

    /// Review of #12: "plants, " split by a client gives a blank synonym, and auto-submit sends "" for an
    /// unanswered item; a blank answer must never score.
    #[test]
    fn a_blank_answer_never_matches_a_blank_synonym() {
        let quiz = [question("id", QuestionType::Identification, 1, "Halaman", &["plants", "", "  "])];
        assert_eq!(grade(&quiz, &[answer("id", "")]), (0, 1));
        assert_eq!(grade(&quiz, &[answer("id", "   ")]), (0, 1));
        assert_eq!(grade(&quiz, &[answer("id", "plants")]), (1, 1));
    }

    #[test]
    fn points_add_up() {
        let quiz = [
            question("a", QuestionType::Identification, 2, "x", &[]),
            question("b", QuestionType::Identification, 3, "y", &[]),
            question("c", QuestionType::Identification, 5, "z", &[]),
        ];
        assert_eq!(grade(&quiz, &[answer("a", "x"), answer("b", "nope")]), (2, 10), "c unanswered");
    }

    #[test]
    fn unknown_questions_are_ignored_and_the_last_answer_counts() {
        let quiz = [question("a", QuestionType::Identification, 1, "x", &[])];
        assert_eq!(grade(&quiz, &[answer("ghost", "x")]), (0, 1));
        assert_eq!(grade(&quiz, &[answer("a", "x"), answer("a", "wrong")]), (0, 1));
        assert_eq!(grade(&quiz, &[answer("a", "wrong"), answer("a", "x")]), (1, 1));
    }
}
