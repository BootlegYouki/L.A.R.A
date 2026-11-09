//! Lesson text for the AI tutor (step 3.1, #10). A teacher's PDF or Word handout becomes ordered
//! `material_chunks` of at most 500 tokens, so phones never parse documents (PRD FR-3.2).
//! No HTTP or database types here.

pub mod chunk;
mod docx;
mod pdf;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

use crate::storage::FileFormat;

/// How many handouts the Hub reads at once (review of #267). Parsing is CPU work on a blocking
/// thread; two leave the Hub's other cores for requests and the AI tutor.
pub const PARSE_SLOTS: usize = 2;

/// The chunks of a stored handout, or none. Never fails: a file with no text layer, an unreadable
/// file, a PDF over 50 MB and a parser panic (lopdf has known panic paths on broken fonts) all give
/// an empty list and one warning that names no file contents. Images and videos have no lesson text.
pub fn extract_chunks(path: &Path, format: FileFormat, title: &str) -> Vec<ChunkDraft> {
    let parse: fn(&Path) -> Result<Vec<Block>, ParseError> = match format {
        FileFormat::Pdf => pdf::pdf_blocks,
        FileFormat::Docx => docx::docx_blocks,
        _ => return Vec::new(),
    };
    match catch_unwind(AssertUnwindSafe(|| parse(path))) {
        Ok(Ok(blocks)) => chunk::chunk_blocks(&blocks, title),
        Ok(Err(error)) => {
            tracing::warn!(reason = error.kind(), "lesson text not extracted");
            Vec::new()
        }
        Err(_) => {
            tracing::warn!(reason = "parser panicked", "lesson text not extracted");
            Vec::new()
        }
    }
}

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
    #[error("PDF too large to read for lesson text")]
    TooLarge,
}

impl ParseError {
    /// For logs: the kind only, since a parser's message can quote the file.
    pub fn kind(&self) -> &'static str {
        match self {
            Self::Io(_) => "file could not be read",
            Self::Zip(_) => "not a readable Word document",
            Self::Xml(_) => "unreadable Word XML",
            Self::Pdf(_) => "unreadable PDF",
            Self::TooLarge => "PDF too large to read",
        }
    }
}
