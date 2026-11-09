//! Lesson text for the AI tutor (step 3.1, #10). A teacher's PDF or Word handout becomes ordered
//! `material_chunks` of at most 500 tokens, so phones never parse documents (PRD FR-3.2).
//! No HTTP or database types here.

pub mod chunk;
mod docx;
mod pdf;

/// A piece of a document, in reading order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Block {
    Heading(String),
    Paragraph(String),
}

/// One `material_chunks` row before it gets an id.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ChunkDraft {
    /// 1, 2, 3, ... as in the mock.
    pub order_index: i64,
    pub heading: Option<String>,
    pub text: String,
    /// Words x 1.4, at most `chunk::MAX_CHUNK_TOKENS`. A hint for clients only: the prompt budget is
    /// counted with the model's own tokenizer (rules/socratic-ai-guardrails.md 3.1, #261).
    pub token_estimate: i64,
}

#[derive(Debug, thiserror::Error)]
pub enum ParseError {
    #[error("could not read the file: {0}")]
    Io(#[from] std::io::Error),
    #[error("not a readable Word document: {0}")]
    Zip(String),
    #[error("unreadable Word XML: {0}")]
    Xml(String),
    #[error("unreadable PDF: {0}")]
    Pdf(String),
}
