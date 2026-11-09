//! Word handouts: the headings and paragraphs of `word/document.xml`, in reading order.

use std::fs::File;
use std::io::Read;
use std::path::Path;

use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesStart, Event};
use quick_xml::{Reader, XmlVersion};

use super::{Block, ParseError};

/// The text part of even a long handout is a few MB; this stops a ZIP bomb from filling memory.
const MAX_XML_BYTES: u64 = 64 * 1024 * 1024;

/// Each `w:p` is one block, with the text of its `w:t` runs. A tab or line break inside a run is a
/// space. A paragraph styled `Title` or `Heading…` is a heading. Table cells are ordinary
/// paragraphs. Headers, footers and footnotes live in other parts of the ZIP, so they are not read.
pub(super) fn docx_blocks(path: &Path) -> Result<Vec<Block>, ParseError> {
    let mut archive = zip::ZipArchive::new(File::open(path)?).map_err(|e| ParseError::Zip(e.to_string()))?;
    let part = archive.by_name("word/document.xml").map_err(|e| ParseError::Zip(e.to_string()))?;
    let mut xml = Vec::new();
    part.take(MAX_XML_BYTES).read_to_end(&mut xml)?;
    let xml = String::from_utf8(xml).map_err(|_| ParseError::Xml("word/document.xml is not UTF-8".to_owned()))?;
    blocks_from_xml(&xml)
}

/// One paragraph being read. Word nests paragraphs only inside text boxes, which get their own entry.
#[derive(Default)]
struct Open {
    text: String,
    heading: bool,
}

fn blocks_from_xml(xml: &str) -> Result<Vec<Block>, ParseError> {
    let xml_error = |e: quick_xml::Error| ParseError::Xml(e.to_string());
    let mut reader = Reader::from_str(xml);
    let mut blocks = Vec::new();
    let mut open: Vec<Open> = Vec::new();
    let (mut in_run, mut in_text) = (0usize, false);
    loop {
        match reader.read_event().map_err(xml_error)? {
            Event::Start(e) => match e.name().as_ref() {
                "w:p" => open.push(Open::default()),
                "w:r" => in_run += 1,
                "w:t" => in_text = true,
                "w:pStyle" => mark_heading(&e, &mut open),
                _ => {}
            },
            Event::Empty(e) => match e.name().as_ref() {
                "w:tab" | "w:br" | "w:cr" if in_run > 0 => push(&mut open, " "),
                "w:pStyle" => mark_heading(&e, &mut open),
                _ => {}
            },
            Event::End(e) => match e.name().as_ref() {
                "w:p" => {
                    if let Some(p) = open.pop() {
                        let text = p.text.trim();
                        if !text.is_empty() {
                            blocks.push(if p.heading { Block::Heading(text.to_owned()) } else { Block::Paragraph(text.to_owned()) });
                        }
                    }
                }
                "w:r" => in_run = in_run.saturating_sub(1),
                "w:t" => in_text = false,
                _ => {}
            },
            Event::Text(t) if in_text => push(&mut open, &t.xml10_content()),
            Event::GeneralRef(r) if in_text => {
                let resolved = match r.resolve_char_ref().map_err(xml_error)? {
                    Some(c) => c.to_string(),
                    None => resolve_predefined_entity(&r).unwrap_or_default().to_owned(),
                };
                push(&mut open, &resolved);
            }
            Event::Eof => break,
            _ => {}
        }
    }
    Ok(blocks)
}

fn push(open: &mut [Open], text: &str) {
    if let Some(p) = open.last_mut() {
        p.text.push_str(text);
    }
}

fn mark_heading(style: &BytesStart<'_>, open: &mut [Open]) {
    let value = style.try_get_attribute("w:val").ok().flatten().and_then(|a| a.normalized_value(XmlVersion::Implicit1_0).ok().map(|v| v.into_owned()));
    if let (Some(value), Some(p)) = (value, open.last_mut()) {
        let value = value.to_ascii_lowercase();
        p.heading = value == "title" || value.starts_with("heading");
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;
    use std::path::PathBuf;

    use super::*;

    fn fixture(name: &str) -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
    }

    fn lesson() -> Vec<Block> {
        docx_blocks(&fixture("lesson.docx")).unwrap()
    }

    fn paragraphs(blocks: &[Block]) -> Vec<&str> {
        blocks.iter().filter_map(|b| if let Block::Paragraph(t) = b { Some(t.as_str()) } else { None }).collect()
    }

    #[test]
    fn docx_headings_come_from_heading_styles() {
        let blocks = lesson();
        let headings: Vec<&str> = blocks.iter().filter_map(|b| if let Block::Heading(t) = b { Some(t.as_str()) } else { None }).collect();
        assert_eq!(headings, ["The Water Cycle", "Evaporation", "Condensation", "Precipitation"]);
    }

    #[test]
    fn docx_keeps_tables_and_filipino_letters() {
        let blocks = lesson();
        let paragraphs = paragraphs(&blocks);
        assert!(paragraphs.iter().any(|p| p.contains("Paraño")), "{paragraphs:?}");
        for cell in ["Rain", "Snow", "Hail", "Sleet"] {
            assert!(paragraphs.contains(&cell), "{cell}");
        }
        assert!(paragraphs.contains(&"Remember: the sun drives the whole cycle."), "the tab became a space");
        assert!(paragraphs.contains(&"Clouds form when vapour cools. Tiny droplets join together and the cloud grows darker."), "the line break became a space");
        assert!(blocks.iter().all(|b| !format!("{b:?}").contains("Quarter 1")), "the page header is not lesson text");
    }

    #[test]
    fn a_zip_without_word_parts_is_an_error() {
        let file = tempfile::NamedTempFile::new().unwrap();
        let mut zip = zip::ZipWriter::new(file.reopen().unwrap());
        zip.start_file("classes.dex", zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored)).unwrap();
        zip.write_all(b"dex\n035").unwrap();
        zip.finish().unwrap();
        assert!(matches!(docx_blocks(file.path()), Err(ParseError::Zip(_))));
    }
}
