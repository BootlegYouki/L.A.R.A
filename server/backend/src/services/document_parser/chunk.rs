//! Packs a document's blocks into chunks of at most `MAX_CHUNK_TOKENS`. Pure: no I/O.

use super::{Block, ChunkDraft};

/// Issue #10: "structured paragraph chunks (~300-500 tokens each)". The hard cap.
pub const MAX_CHUNK_TOKENS: i64 = 500;
/// A whole textbook still uploads: past this many chunks the rest of the document is left out.
pub const MAX_CHUNKS: usize = 1000;
/// The most words that stay within `MAX_CHUNK_TOKENS` (357 x 1.4 = 499.8).
const MAX_WORDS: usize = MAX_CHUNK_TOKENS as usize * 10 / 14;

/// Whitespace-split words x 1.4, rounded up. A size measure for packing, not the model's count.
pub fn token_estimate(text: &str) -> i64 {
    tokens_for(text.split_whitespace().count())
}

fn tokens_for(words: usize) -> i64 {
    (words as i64 * 14 + 9) / 10
}

/// Walks the blocks in order. A heading closes the open chunk and names the ones after it (the
/// fallback names text before the first heading). Whole paragraphs are added while the chunk stays
/// within the cap; a longer paragraph is split at sentence ends, and a longer sentence by words.
/// Blank blocks are skipped. Stops at `MAX_CHUNKS`.
pub fn chunk_blocks(blocks: &[Block], fallback_heading: &str) -> Vec<ChunkDraft> {
    let mut packer = Packer { chunks: Vec::new(), heading: named(fallback_heading), text: String::new(), words: 0 };
    'blocks: for block in blocks {
        match block {
            Block::Heading(text) => {
                if let Some(heading) = named(text) {
                    packer.flush();
                    packer.heading = Some(heading);
                }
            }
            Block::Paragraph(text) => {
                for piece in pieces(text.trim()) {
                    packer.add(piece);
                    if packer.chunks.len() >= MAX_CHUNKS {
                        break 'blocks;
                    }
                }
            }
        }
    }
    packer.flush();
    packer.chunks.truncate(MAX_CHUNKS);
    packer.chunks
}

fn named(text: &str) -> Option<String> {
    let text = text.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

/// Part of a paragraph small enough for one chunk.
struct Piece<'a> {
    text: std::borrow::Cow<'a, str>,
    words: usize,
    /// The first piece of a paragraph joins the chunk with a blank line, the rest with a space.
    starts_paragraph: bool,
}

fn pieces(paragraph: &str) -> Vec<Piece<'_>> {
    let words = paragraph.split_whitespace().count();
    if words == 0 {
        return Vec::new();
    }
    if words <= MAX_WORDS {
        return vec![Piece { text: paragraph.into(), words, starts_paragraph: true }];
    }
    let mut out = Vec::new();
    for sentence in sentences(paragraph) {
        let words: Vec<&str> = sentence.split_whitespace().collect();
        if words.len() <= MAX_WORDS {
            out.push(Piece { text: sentence.into(), words: words.len(), starts_paragraph: false });
        } else {
            out.extend(words.chunks(MAX_WORDS).map(|group| Piece { text: group.join(" ").into(), words: group.len(), starts_paragraph: false }));
        }
    }
    if let Some(first) = out.first_mut() {
        first.starts_paragraph = true;
    }
    out
}

/// Splits after `.`, `?` or `!` followed by whitespace, keeping the mark with its sentence.
fn sentences(text: &str) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0;
    for (i, c) in text.char_indices() {
        let end = i + c.len_utf8();
        if matches!(c, '.' | '?' | '!') && text[end..].starts_with(char::is_whitespace) {
            out.extend(Some(text[start..end].trim()).filter(|s| !s.is_empty()));
            start = end;
        }
    }
    out.extend(Some(text[start..].trim()).filter(|s| !s.is_empty()));
    out
}

struct Packer {
    chunks: Vec<ChunkDraft>,
    heading: Option<String>,
    text: String,
    words: usize,
}

impl Packer {
    fn add(&mut self, piece: Piece<'_>) {
        if !self.text.is_empty() && self.words + piece.words > MAX_WORDS {
            self.flush();
        }
        if !self.text.is_empty() {
            self.text.push_str(if piece.starts_paragraph { "\n\n" } else { " " });
        }
        self.text.push_str(&piece.text);
        self.words += piece.words;
    }

    fn flush(&mut self) {
        if self.text.is_empty() {
            return;
        }
        self.chunks.push(ChunkDraft {
            order_index: self.chunks.len() as i64 + 1,
            heading: self.heading.clone(),
            text: std::mem::take(&mut self.text),
            token_estimate: tokens_for(self.words),
        });
        self.words = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h(text: &str) -> Block {
        Block::Heading(text.to_owned())
    }

    fn p(text: &str) -> Block {
        Block::Paragraph(text.to_owned())
    }

    fn words(n: usize) -> String {
        (0..n).map(|i| format!("w{i}")).collect::<Vec<_>>().join(" ")
    }

    #[test]
    fn token_estimate_is_words_times_1_4_rounded_up() {
        assert_eq!(token_estimate("one two three"), 5);
        assert_eq!(token_estimate(""), 0);
        assert_eq!(token_estimate("  a \n b "), 3);
    }

    #[test]
    fn a_heading_starts_a_new_chunk() {
        let chunks = chunk_blocks(&[h("Evaporation"), p(&words(50)), h("Condensation"), p(&words(50))], "Lesson");
        assert_eq!(chunks.len(), 2);
        assert_eq!(chunks.iter().map(|c| c.heading.as_deref()).collect::<Vec<_>>(), [Some("Evaporation"), Some("Condensation")]);
        assert_eq!(chunks.iter().map(|c| c.order_index).collect::<Vec<_>>(), [1, 2]);
    }

    #[test]
    fn paragraphs_fill_a_chunk_up_to_500_tokens() {
        let mut blocks = vec![h("Plants")];
        blocks.extend((0..10).map(|_| p(&words(100))));
        let chunks = chunk_blocks(&blocks, "Lesson");
        assert_eq!(chunks.iter().map(|c| c.token_estimate).collect::<Vec<_>>(), [420, 420, 420, 140]);
        assert_eq!(chunks[0].text, [words(100), words(100), words(100)].join("\n\n"));
    }

    #[test]
    fn a_long_paragraph_is_split_at_sentence_ends() {
        let sentence = format!("{}.", words(10));
        let paragraph = vec![sentence; 60].join(" ");
        let chunks = chunk_blocks(&[p(&paragraph)], "Lesson");
        assert!(chunks.len() > 1);
        assert!(chunks.iter().all(|c| c.token_estimate <= MAX_CHUNK_TOKENS && token_estimate(&c.text) == c.token_estimate));
        assert!(chunks.iter().all(|c| c.text.ends_with('.')));
        assert_eq!(chunks.iter().map(|c| c.text.as_str()).collect::<Vec<_>>().join(" "), paragraph);
    }

    #[test]
    fn a_run_without_sentence_ends_is_split_by_words() {
        let run = words(2_000);
        let chunks = chunk_blocks(&[p(&run)], "Lesson");
        assert!(chunks.iter().all(|c| c.token_estimate <= MAX_CHUNK_TOKENS));
        assert_eq!(chunks.iter().map(|c| c.text.as_str()).collect::<Vec<_>>().join(" "), run, "every word, in order");
    }

    #[test]
    fn text_before_any_heading_uses_the_fallback() {
        let chunks = chunk_blocks(&[p("Hello there.")], "Lesson 1");
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].heading.as_deref(), Some("Lesson 1"));
        assert_eq!(chunks[0].text, "Hello there.");
    }

    #[test]
    fn blank_blocks_make_no_chunks() {
        assert!(chunk_blocks(&[p("   "), h(""), p("\n")], "Lesson").is_empty());
    }

    #[test]
    fn chunk_count_is_capped() {
        let blocks: Vec<Block> = (0..5_000).flat_map(|i| [h(&format!("Part {i}")), p("Some text.")]).collect();
        assert_eq!(chunk_blocks(&blocks, "Lesson").len(), MAX_CHUNKS);
    }
}
