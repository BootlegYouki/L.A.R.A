//! What an upload really is, read from its first bytes. The client's file name and part
//! `Content-Type` are never trusted (issue #53): a renamed `.exe` is refused, not stored as a PDF.

/// The formats the Hub keeps (PRD FR-3.1 documents and images, FR-3.3 videos, FR-3.5 homework).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FileFormat {
    Pdf,
    /// Any ZIP container. DOCX is one; step 3.1's parser finds no text in other ZIPs.
    Docx,
    Jpeg,
    Png,
    Mp4,
    Webm,
}

/// How many leading bytes `sniff` needs.
pub const HEAD_LEN: usize = 16;

const ALL: [FileFormat; 6] =
    [FileFormat::Pdf, FileFormat::Docx, FileFormat::Jpeg, FileFormat::Png, FileFormat::Mp4, FileFormat::Webm];

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
        } else if head.get(4..8) == Some(&b"ftyp"[..]) {
            // ISO base media: a box size, then `ftyp`. Covers .mp4 and .m4v.
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
        // HTML, a Windows program, the mock's placeholder bodies, and nothing at all.
        for head in [&b"<html><body>"[..], b"MZ\x90\x00", b"PDF", b"JPEGDATA", b""] {
            assert_eq!(FileFormat::sniff(head), None, "{head:?}");
        }
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
