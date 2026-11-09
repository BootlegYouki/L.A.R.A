//! PDF handouts: the text layer, split into headings and paragraphs by layout. A scan has no text
//! layer and gives no blocks (no OCR, PRD FR-3.2).

use std::path::Path;

use super::{Block, ParseError};

pub(super) fn pdf_blocks(path: &Path) -> Result<Vec<Block>, ParseError> {
    let text = pdf_extract::extract_text(path).map_err(|e| ParseError::Pdf(e.to_string()))?;
    Ok(text_to_blocks(&text))
}

/// PDFs keep no structure, so layout decides: blank lines separate blocks, and a block that is one
/// short line (2 to 60 characters) with no closing punctuation and no lowercase start is a heading.
/// Bare page numbers and "Page N of M" are dropped. A hyphen at a line end joins the word split
/// across the break; any other line break inside a block is a space. pdf-extract sometimes puts a
/// blank line at an ordinary wrap, so a block that starts lowercase continues the block before it
/// unless that one ended a sentence, and the two become one paragraph.
fn text_to_blocks(text: &str) -> Vec<Block> {
    let mut blocks: Vec<Block> = Vec::new();
    let mut lines: Vec<&str> = Vec::new();
    for line in text.lines().map(str::trim).chain(std::iter::once("")) {
        if !line.is_empty() {
            lines.push(line);
            continue;
        }
        if lines.is_empty() {
            continue;
        }
        let joined = join_lines(&lines);
        let one_line = lines.len() == 1;
        lines.clear();
        if is_page_number(&joined) {
            continue;
        }
        if joined.starts_with(char::is_lowercase) {
            if let Some(Block::Heading(prev) | Block::Paragraph(prev)) = blocks.last() {
                if !prev.ends_with(['.', '?', '!', ':']) {
                    let merged = join_lines(&[prev.as_str(), joined.as_str()]);
                    if let Some(last) = blocks.last_mut() {
                        *last = Block::Paragraph(merged);
                    }
                    continue;
                }
            }
        }
        blocks.push(if one_line && is_heading(&joined) { Block::Heading(joined) } else { Block::Paragraph(joined) });
    }
    blocks
}

fn join_lines(lines: &[&str]) -> String {
    let mut out = String::new();
    for line in lines {
        if out.is_empty() {
            out.push_str(line);
        } else if out.ends_with('-') && line.starts_with(char::is_lowercase) {
            out.pop();
            out.push_str(line);
        } else {
            out.push(' ');
            out.push_str(line);
        }
    }
    out
}

fn is_page_number(text: &str) -> bool {
    let all_digits = |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_digit());
    let words: Vec<&str> = text.split_whitespace().collect();
    match words.as_slice() {
        [n] => all_digits(n),
        [page, n, of, m] => page.eq_ignore_ascii_case("page") && of.eq_ignore_ascii_case("of") && all_digits(n) && all_digits(m),
        _ => false,
    }
}

fn is_heading(line: &str) -> bool {
    let chars = line.chars().count();
    (2..=60).contains(&chars)
        && !line.ends_with(['.', ',', ';', ':', '?', '!'])
        && !line.starts_with(char::is_lowercase)
        && line.chars().any(char::is_alphabetic)
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;

    fn h(text: &str) -> Block {
        Block::Heading(text.to_owned())
    }

    fn p(text: &str) -> Block {
        Block::Paragraph(text.to_owned())
    }

    fn fixture(name: &str) -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
    }

    #[test]
    fn short_unpunctuated_lines_are_headings() {
        assert_eq!(
            text_to_blocks("Evaporation\n\nWater turns into vapour when the sun heats it.\n"),
            [h("Evaporation"), p("Water turns into vapour when the sun heats it.")]
        );
    }

    #[test]
    fn page_numbers_are_dropped() {
        assert_eq!(text_to_blocks("First page text.\n\n3\n\nPage 4 of 10\n\nMore text."), [p("First page text."), p("More text.")]);
    }

    #[test]
    fn hyphenated_line_breaks_are_joined() {
        assert_eq!(text_to_blocks("photo-\nsynthesis makes food."), [p("photosynthesis makes food.")]);
        assert_eq!(text_to_blocks("Water turns\ninto vapour."), [p("Water turns into vapour.")], "other line breaks are a space");
    }

    #[test]
    fn a_line_wrapped_into_its_own_block_rejoins_its_paragraph() {
        // pdf-extract puts a blank line at some wraps (seen on Chrome-printed handouts).
        assert_eq!(
            text_to_blocks("This journey never stops, and it is called\n\nthe water cycle.\n\nNext paragraph."),
            [p("This journey never stops, and it is called the water cycle."), p("Next paragraph.")]
        );
    }

    #[test]
    fn fixture_pdf_keeps_the_sections_in_order() {
        let blocks = pdf_blocks(&fixture("lesson.pdf")).unwrap();
        let text = format!("{blocks:?}");
        let at = |word: &str| text.find(word).unwrap_or_else(|| panic!("{word} missing from {text}"));
        assert!(at("Evaporation") < at("Condensation") && at("Condensation") < at("Precipitation"), "{text}");
        assert!(text.contains("Paraño"), "{text}");
    }

    #[test]
    fn scanned_pdf_has_no_blocks() {
        assert_eq!(pdf_blocks(&fixture("scanned.pdf")).unwrap(), []);
    }

    #[test]
    fn garbage_is_an_error_not_a_panic() {
        let file = tempfile::NamedTempFile::new().unwrap();
        std::fs::write(file.path(), b"%PDF-1.4\n% not a real pdf\n").unwrap();
        assert!(pdf_blocks(file.path()).is_err());
    }
}
