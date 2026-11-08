//! What an upload really is, read from its first bytes. The client's file name and part
//! `Content-Type` are never trusted (issue #53): a renamed `.exe` is refused, not stored as a PDF.

/// The formats the Hub keeps (PRD FR-3.1 documents and images, FR-3.3 videos, FR-3.5 homework).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FileFormat {
    Pdf,
    /// A Word document. `sniff` sees only that the file is a ZIP; `is_word_document` then reads its
    /// file list, so an APK, a spreadsheet or a plain archive is refused.
    Docx,
    Jpeg,
    Png,
    Mp4,
    Webm,
}

/// How many leading bytes `sniff` needs.
pub const HEAD_LEN: usize = 16;

/// How many trailing bytes `is_word_document` reads. A ZIP's file list and end record are at the
/// end of the file; a Word document's list is a few KB, so a list longer than this is refused.
pub const TAIL_LEN: usize = 1024 * 1024;

const ALL: [FileFormat; 6] =
    [FileFormat::Pdf, FileFormat::Docx, FileFormat::Jpeg, FileFormat::Png, FileFormat::Mp4, FileFormat::Webm];

/// MP4 major brands (bytes 8..12, after `ftyp`). Other ISO media files share the `ftyp` box but are
/// not MP4 video: QuickTime `.mov` (`qt  `), HEIC and AVIF photos, `.m4a` audio, 3GP. PRD FR-3.3
/// asks for MP4 or WebM.
const MP4_BRANDS: [[u8; 4]; 11] =
    [*b"isom", *b"iso2", *b"iso4", *b"iso5", *b"iso6", *b"mp41", *b"mp42", *b"avc1", *b"M4V ", *b"dash", *b"mmp4"];

const ZIP_END_SIG: [u8; 4] = *b"PK\x05\x06";
const ZIP_END_LEN: usize = 22;
const ZIP_ENTRY_SIG: [u8; 4] = *b"PK\x01\x02";
const ZIP_ENTRY_LEN: usize = 46;

/// Whether a ZIP is a Word document: its file list names `[Content_Types].xml` and
/// `word/document.xml`, as every DOCX does. `tail` is the end of the file (up to `TAIL_LEN`
/// bytes) and `file_len` the whole file's size.
pub fn is_word_document(tail: &[u8], file_len: u64) -> bool {
    let Some(names) = zip_entry_names(tail, file_len) else { return false };
    names.contains(&&b"[Content_Types].xml"[..]) && names.contains(&&b"word/document.xml"[..])
}

/// The entry names in a ZIP's central directory, or `None` if `tail` does not end with a
/// well-formed one: no end record, ZIP64, several disks, data before the ZIP, or a directory
/// longer than `tail`.
fn zip_entry_names(tail: &[u8], file_len: u64) -> Option<Vec<&[u8]>> {
    if tail.len() < ZIP_END_LEN || tail.len() as u64 > file_len {
        return None;
    }
    // The end record is last, followed only by its comment, so the right match ends the file.
    let end = (0..=tail.len() - ZIP_END_LEN)
        .rev()
        .find(|&at| tail[at..at + 4] == ZIP_END_SIG && le16(tail, at + 20).map(|c| at + ZIP_END_LEN + c) == Some(tail.len()))?;
    if le16(tail, end + 4)? != 0 || le16(tail, end + 6)? != 0 {
        return None;
    }
    let entries = le16(tail, end + 10)?;
    let dir_len = le32(tail, end + 12)?;
    let dir_offset = le32(tail, end + 16)?;
    if entries == 0xFFFF || dir_len == 0xFFFF_FFFF || dir_offset == 0xFFFF_FFFF {
        return None;
    }
    // The directory sits right before the end record, at the offset the record names.
    let end_in_file = file_len - tail.len() as u64 + end as u64;
    if dir_offset + dir_len != end_in_file {
        return None;
    }
    let mut at = end.checked_sub(usize::try_from(dir_len).ok()?)?;
    let mut names = Vec::with_capacity(entries);
    for _ in 0..entries {
        if tail.get(at..at + 4)? != ZIP_ENTRY_SIG {
            return None;
        }
        let name_len = le16(tail, at + 28)?;
        let extra_len = le16(tail, at + 30)?;
        let comment_len = le16(tail, at + 32)?;
        let name_start = at + ZIP_ENTRY_LEN;
        names.push(tail.get(name_start..name_start + name_len)?);
        at = name_start + name_len + extra_len + comment_len;
    }
    (at == end).then_some(names)
}

fn le16(bytes: &[u8], at: usize) -> Option<usize> {
    let b = bytes.get(at..at + 2)?;
    Some(usize::from(u16::from_le_bytes([b[0], b[1]])))
}

fn le32(bytes: &[u8], at: usize) -> Option<u64> {
    let b = bytes.get(at..at + 4)?;
    Some(u64::from(u32::from_le_bytes([b[0], b[1], b[2], b[3]])))
}

/// A ZIP of empty, uncompressed files with these names, for tests.
#[cfg(test)]
pub fn test_zip(names: &[&str]) -> Vec<u8> {
    let mut zip = Vec::new();
    let mut directory = Vec::new();
    for name in names {
        let offset = zip.len() as u32;
        let name_len = (name.len() as u16).to_le_bytes();
        // Local header: version, flags, method, time, date; CRC and sizes; name and extra lengths.
        zip.extend_from_slice(b"PK\x03\x04");
        zip.extend_from_slice(&[20, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        zip.extend_from_slice(&[0; 12]);
        zip.extend_from_slice(&name_len);
        zip.extend_from_slice(&[0; 2]);
        zip.extend_from_slice(name.as_bytes());
        // Directory entry: versions, flags, method, time, date; CRC and sizes; name length; extra,
        // comment, disk and attribute fields; the local header's offset.
        directory.extend_from_slice(&ZIP_ENTRY_SIG);
        directory.extend_from_slice(&[20, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
        directory.extend_from_slice(&[0; 12]);
        directory.extend_from_slice(&name_len);
        directory.extend_from_slice(&[0; 12]);
        directory.extend_from_slice(&offset.to_le_bytes());
        directory.extend_from_slice(name.as_bytes());
    }
    let dir_offset = zip.len() as u32;
    let count = (names.len() as u16).to_le_bytes();
    zip.extend_from_slice(&directory);
    zip.extend_from_slice(&ZIP_END_SIG);
    zip.extend_from_slice(&[0; 4]);
    zip.extend_from_slice(&count);
    zip.extend_from_slice(&count);
    zip.extend_from_slice(&(directory.len() as u32).to_le_bytes());
    zip.extend_from_slice(&dir_offset.to_le_bytes());
    zip.extend_from_slice(&[0; 2]);
    zip
}

impl FileFormat {
    pub fn sniff(head: &[u8]) -> Option<Self> {
        if head.starts_with(b"%PDF-") {
            Some(Self::Pdf)
        } else if head.starts_with(b"PK\x03\x04") {
            Some(Self::Docx)
        } else if head.starts_with(&[0xFF, 0xD8, 0xFF]) {
            Some(Self::Jpeg)
        } else if head.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
            Some(Self::Png)
        } else if head.get(4..8) == Some(&b"ftyp"[..])
            && head.get(8..12).is_some_and(|brand| MP4_BRANDS.iter().any(|mp4| brand == mp4.as_slice()))
        {
            // ISO base media: a box size, `ftyp`, then the major brand. Covers .mp4 and .m4v.
            Some(Self::Mp4)
        } else if head.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
            Some(Self::Webm)
        } else {
            None
        }
    }

    pub fn mime(self) -> &'static str {
        match self {
            Self::Pdf => "application/pdf",
            Self::Docx => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Self::Jpeg => "image/jpeg",
            Self::Png => "image/png",
            Self::Mp4 => "video/mp4",
            Self::Webm => "video/webm",
        }
    }

    /// The stored file's extension, so the format is known again when the file is served.
    pub fn extension(self) -> &'static str {
        match self {
            Self::Pdf => "pdf",
            Self::Docx => "docx",
            Self::Jpeg => "jpg",
            Self::Png => "png",
            Self::Mp4 => "mp4",
            Self::Webm => "webm",
        }
    }

    pub fn from_extension(ext: &str) -> Option<Self> {
        ALL.into_iter().find(|f| f.extension() == ext)
    }

    pub fn is_video(self) -> bool {
        matches!(self, Self::Mp4 | Self::Webm)
    }

    /// `Submission.file_type`: photos are `IMAGE`, PDF and Word are `DOCUMENT`. Homework is never a video.
    pub fn submission_type(self) -> Option<&'static str> {
        match self {
            Self::Jpeg | Self::Png => Some("IMAGE"),
            Self::Pdf | Self::Docx => Some("DOCUMENT"),
            Self::Mp4 | Self::Webm => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sniff_knows_each_accepted_format() {
        let cases: [(&[u8], FileFormat); 6] = [
            (b"%PDF-1.4\n", FileFormat::Pdf),
            (b"PK\x03\x04\x14\x00", FileFormat::Docx),
            (&[0xFF, 0xD8, 0xFF, 0xE0], FileFormat::Jpeg),
            (&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0], FileFormat::Png),
            (b"\0\0\0\x18ftypmp42", FileFormat::Mp4),
            (&[0x1A, 0x45, 0xDF, 0xA3, 0x9F], FileFormat::Webm),
        ];
        for (head, format) in cases {
            assert_eq!(FileFormat::sniff(head), Some(format));
        }
        // HTML, a Windows program, the mock's placeholder bodies, nothing at all, and ISO media that
        // is not MP4 video: QuickTime, a HEIC photo, AAC audio.
        let refused: [&[u8]; 8] = [
            b"<html><body>",
            b"MZ\x90\x00",
            b"PDF",
            b"JPEGDATA",
            b"",
            b"\0\0\0\x14ftypqt  \0\0\0\0",
            b"\0\0\0\x18ftypheic\0\0\0\0",
            b"\0\0\0\x20ftypM4A \0\0\0\0",
        ];
        for head in refused {
            assert_eq!(FileFormat::sniff(head), None, "{head:?}");
        }
    }

    #[test]
    fn only_a_zip_that_lists_word_parts_is_a_word_document() {
        let word = test_zip(&["[Content_Types].xml", "_rels/.rels", "word/document.xml"]);
        assert_eq!(FileFormat::sniff(&word[..HEAD_LEN]), Some(FileFormat::Docx));
        assert!(is_word_document(&word, word.len() as u64));
        // Only the end of a file is read: the same bytes as the tail of the whole file.
        assert!(is_word_document(&word[40..], word.len() as u64));

        let spreadsheet = test_zip(&["[Content_Types].xml", "xl/workbook.xml"]);
        let apk = test_zip(&["AndroidManifest.xml", "classes.dex"]);
        for other in [&spreadsheet, &apk] {
            assert!(!is_word_document(other, other.len() as u64));
        }
        // Cut short, a tail too small to hold the file list, and not a ZIP at all.
        let cut = &word[..word.len() - 1];
        assert!(!is_word_document(cut, cut.len() as u64));
        assert!(!is_word_document(&word[word.len() - ZIP_END_LEN..], word.len() as u64));
        assert!(!is_word_document(b"%PDF-1.4\n", 9));
    }

    #[test]
    fn extension_round_trips() {
        for format in ALL {
            assert_eq!(FileFormat::from_extension(format.extension()), Some(format));
        }
        assert_eq!(FileFormat::from_extension("exe"), None);
    }

    #[test]
    fn only_photos_and_documents_are_homework() {
        assert_eq!(FileFormat::Jpeg.submission_type(), Some("IMAGE"));
        assert_eq!(FileFormat::Docx.submission_type(), Some("DOCUMENT"));
        assert_eq!(FileFormat::Mp4.submission_type(), None);
    }
}
