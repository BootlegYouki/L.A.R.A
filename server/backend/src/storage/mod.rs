//! Lesson files and homework on the Hub disk: `<data_dir>/storage/{materials,submissions,tmp}`.
//! No database access and no HTTP types (TECH_SPEC section 2).
//!
//! An upload streams into `tmp/` while its bytes are counted, so a file over the cap is refused
//! without ever being kept, and a half-received file is never visible. Only a complete file is
//! renamed into `materials/` or `submissions/`, under a name the Hub generates. The database
//! stores that name relative to `storage/` (`materials/<uuid>.pdf`), never the client's name.
//!
//! Every stage owns its file and deletes it when dropped, so any early return (a refused check,
//! a dropped connection, a failed commit) cleans up after itself.

mod format;

use std::io;
use std::path::{Path, PathBuf};

use tokio::fs;
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt, BufWriter};
use uuid::Uuid;

pub use format::FileFormat;
use format::{HEAD_LEN, TAIL_LEN};

/// Contract `POST /api/materials` (max 250 MB). Homework uses the same cap (TECH_SPEC Q10).
pub const MAX_UPLOAD_BYTES: u64 = 250 * 1024 * 1024;

/// Multipart chunks are small (8 to 64 KB); batching them saves one blocking-pool round trip per
/// chunk on a 250 MB video. 256 KiB per upload in progress keeps 40 at once around 10 MB.
const WRITE_BUFFER: usize = 256 * 1024;

const TMP: &str = "tmp";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StorageKind {
    Material,
    Submission,
}

impl StorageKind {
    fn dir(self) -> &'static str {
        match self {
            Self::Material => "materials",
            Self::Submission => "submissions",
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum UploadError {
    #[error("the file is over the upload limit")]
    TooLarge,
    #[error(transparent)]
    Io(#[from] io::Error),
}

#[derive(Debug)]
pub struct Storage {
    root: PathBuf,
}

impl Storage {
    /// Creates the folders and empties `tmp/`: anything there is an upload a crash or power cut
    /// interrupted, and no row refers to it.
    pub async fn open(data_dir: &Path) -> io::Result<Self> {
        let root = data_dir.join("storage");
        for kind in [StorageKind::Material, StorageKind::Submission] {
            fs::create_dir_all(root.join(kind.dir())).await?;
        }
        let tmp = root.join(TMP);
        if fs::try_exists(&tmp).await? {
            fs::remove_dir_all(&tmp).await?;
        }
        fs::create_dir_all(&tmp).await?;
        Ok(Self { root })
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub async fn temp(&self) -> io::Result<TempUpload> {
        TempUpload::create(self.root.join(TMP).join(Uuid::new_v4().to_string()), MAX_UPLOAD_BYTES).await
    }

    /// Moves a complete upload into place under a new name. The caller has already refused an
    /// unrecognised format; one that slips through is an error, never a nameless file.
    pub async fn commit(&self, mut upload: Received, kind: StorageKind) -> io::Result<StoredFile> {
        let format = upload
            .format
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "unrecognised file format"))?;
        let relative = format!("{}/{}.{}", kind.dir(), Uuid::new_v4(), format.extension());
        let target = self.root.join(&relative);
        fs::rename(&upload.guard.path, &target).await?;
        upload.guard.disarm();
        Ok(StoredFile { guard: RemoveOnDrop::new(target), relative })
    }

    /// The disk path of a stored name, or `None` for anything the Hub could not have generated
    /// (`..`, absolute paths, `tmp/`, nested folders). Database values are checked anyway.
    pub fn path(&self, stored: &str) -> Option<PathBuf> {
        let (dir, name) = stored.split_once('/')?;
        if dir != StorageKind::Material.dir() && dir != StorageKind::Submission.dir() {
            return None;
        }
        let (stem, _) = name.rsplit_once('.')?;
        // Exactly the form `commit` writes: `parse_str` alone also takes `{...}`, `urn:uuid:...`,
        // no hyphens and upper case, and a colon on a Windows Hub would name an NTFS stream.
        if !Uuid::parse_str(stem).is_ok_and(|id| id.to_string() == stem) || format_of(stored).is_none() {
            return None;
        }
        Some(self.root.join(dir).join(name))
    }

    /// Best effort: a replaced homework file that cannot be deleted only wastes space.
    pub async fn remove(&self, stored: &str) {
        let Some(path) = self.path(stored) else {
            tracing::warn!("not removing a stored file with an unexpected name");
            return;
        };
        if let Err(error) = fs::remove_file(&path).await {
            if error.kind() != io::ErrorKind::NotFound {
                tracing::warn!(%error, stored, "could not remove a replaced file");
            }
        }
    }
}

/// The format of a stored name, from the extension the Hub gave it.
pub fn format_of(stored: &str) -> Option<FileFormat> {
    FileFormat::from_extension(stored.rsplit_once('.')?.1)
}

/// A file being received in `tmp/`.
pub struct TempUpload {
    // Declared before `guard` so the handle closes before the file is deleted.
    file: BufWriter<fs::File>,
    guard: RemoveOnDrop,
    size: u64,
    cap: u64,
    head: Vec<u8>,
}

impl TempUpload {
    async fn create(path: PathBuf, cap: u64) -> io::Result<Self> {
        let file = BufWriter::with_capacity(WRITE_BUFFER, fs::File::create(&path).await?);
        Ok(Self { file, guard: RemoveOnDrop::new(path), size: 0, cap, head: Vec::with_capacity(HEAD_LEN) })
    }

    /// Refuses the chunk that would pass the cap before writing any of it.
    pub async fn write(&mut self, chunk: &[u8]) -> Result<(), UploadError> {
        let size = self.size + chunk.len() as u64;
        if size > self.cap {
            return Err(UploadError::TooLarge);
        }
        if self.head.len() < HEAD_LEN {
            let take = (HEAD_LEN - self.head.len()).min(chunk.len());
            self.head.extend_from_slice(&chunk[..take]);
        }
        self.file.write_all(chunk).await?;
        self.size = size;
        Ok(())
    }

    /// Flushes to disk (so a power cut cannot leave a renamed but empty file) and identifies it.
    /// The guard moves into `Received`, so the file stays owned by one guard throughout.
    pub async fn finish(self) -> io::Result<Received> {
        let Self { mut file, guard, size, head, .. } = self;
        file.flush().await?;
        file.get_ref().sync_all().await?;
        drop(file);
        let mut format = FileFormat::sniff(&head);
        if format == Some(FileFormat::Docx) && !is_word_file(&guard.path, size).await? {
            format = None;
        }
        Ok(Received { guard, size, format })
    }
}

/// A ZIP is kept as Word only if its file list says so (`format::is_word_document`). The list is
/// at the end of the file, so only the last `TAIL_LEN` bytes are read.
async fn is_word_file(path: &Path, size: u64) -> io::Result<bool> {
    let tail_len = size.min(TAIL_LEN as u64);
    let mut file = fs::File::open(path).await?;
    file.seek(io::SeekFrom::Start(size - tail_len)).await?;
    let mut tail = vec![0; usize::try_from(tail_len).unwrap_or(TAIL_LEN)];
    file.read_exact(&mut tail).await?;
    Ok(format::is_word_document(&tail, size))
}

/// A complete upload, still in `tmp/` until [`Storage::commit`].
pub struct Received {
    guard: RemoveOnDrop,
    pub size: u64,
    /// `None` when the bytes are not a format the Hub keeps.
    pub format: Option<FileFormat>,
}

/// A file in `materials/` or `submissions/`. Removed again on drop unless [`StoredFile::keep`] is
/// called after the row that names it commits.
pub struct StoredFile {
    guard: RemoveOnDrop,
    /// The `file_path` column value.
    pub relative: String,
}

impl StoredFile {
    pub fn keep(mut self) {
        self.guard.disarm();
    }
}

/// Deletes its file when dropped unless disarmed. `Drop` cannot await, so this is one blocking
/// unlink, too quick to matter on the async runtime.
struct RemoveOnDrop {
    path: PathBuf,
    armed: bool,
}

impl RemoveOnDrop {
    fn new(path: PathBuf) -> Self {
        Self { path, armed: true }
    }

    fn disarm(&mut self) {
        self.armed = false;
    }
}

impl Drop for RemoveOnDrop {
    fn drop(&mut self) {
        if self.armed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PDF: &[u8] = b"%PDF-1.4\n% lesson\n";

    fn files_in(dir: &Path) -> usize {
        std::fs::read_dir(dir).unwrap().count()
    }

    async fn storage() -> (tempfile::TempDir, Storage) {
        let dir = tempfile::tempdir().unwrap();
        let storage = Storage::open(dir.path()).await.unwrap();
        (dir, storage)
    }

    async fn received(storage: &Storage, bytes: &[u8]) -> Received {
        let mut upload = storage.temp().await.unwrap();
        upload.write(bytes).await.unwrap();
        upload.finish().await.unwrap()
    }

    #[tokio::test]
    async fn write_refuses_past_the_cap() {
        let (_dir, storage) = storage().await;
        let path = storage.root().join(TMP).join("capped");
        let mut upload = TempUpload::create(path, 8).await.unwrap();
        upload.write(b"12345678").await.unwrap();
        assert!(matches!(upload.write(b"9").await, Err(UploadError::TooLarge)));
        drop(upload);
        assert_eq!(files_in(&storage.root().join(TMP)), 0);
    }

    #[tokio::test]
    async fn drop_removes_unfinished_upload() {
        let (_dir, storage) = storage().await;
        let mut upload = storage.temp().await.unwrap();
        upload.write(PDF).await.unwrap();
        assert_eq!(files_in(&storage.root().join(TMP)), 1);
        drop(upload);
        assert_eq!(files_in(&storage.root().join(TMP)), 0);
        let finished = received(&storage, PDF).await;
        drop(finished);
        assert_eq!(files_in(&storage.root().join(TMP)), 0, "a finished but uncommitted upload is removed too");
    }

    #[tokio::test]
    async fn commit_moves_the_file_under_a_generated_name() {
        let (_dir, storage) = storage().await;
        let upload = received(&storage, PDF).await;
        assert_eq!((upload.size, upload.format), (PDF.len() as u64, Some(FileFormat::Pdf)));
        let stored = storage.commit(upload, StorageKind::Material).await.unwrap();
        let (dir, name) = stored.relative.split_once('/').unwrap();
        assert_eq!(dir, "materials");
        let (stem, ext) = name.rsplit_once('.').unwrap();
        assert!(Uuid::parse_str(stem).is_ok() && ext == "pdf", "{name}");
        let path = storage.path(&stored.relative).unwrap();
        assert_eq!(std::fs::read(&path).unwrap(), PDF);
        assert_eq!(files_in(&storage.root().join(TMP)), 0);
        stored.keep();
        assert!(path.exists());
    }

    #[tokio::test]
    async fn an_unrecognised_upload_is_never_committed() {
        let (_dir, storage) = storage().await;
        let upload = received(&storage, b"<html>").await;
        assert_eq!(upload.format, None);
        assert!(storage.commit(upload, StorageKind::Material).await.is_err());
        assert_eq!(files_in(&storage.root().join(TMP)), 0);
        assert_eq!(files_in(&storage.root().join("materials")), 0);
    }

    #[tokio::test]
    async fn a_zip_is_word_only_when_its_file_list_says_so() {
        let (_dir, storage) = storage().await;
        let word = format::test_zip(&["[Content_Types].xml", "word/document.xml"]);
        assert_eq!(received(&storage, &word).await.format, Some(FileFormat::Docx));
        let apk = format::test_zip(&["AndroidManifest.xml", "classes.dex"]);
        assert_eq!(received(&storage, &apk).await.format, None);
        // Written in pieces, as a multipart upload arrives, then read back from the end.
        let mut upload = storage.temp().await.unwrap();
        for piece in word.chunks(7) {
            upload.write(piece).await.unwrap();
        }
        assert_eq!(upload.finish().await.unwrap().format, Some(FileFormat::Docx));
        assert_eq!(files_in(&storage.root().join(TMP)), 0);
    }

    #[tokio::test]
    async fn stored_file_drop_removes_it_unless_kept() {
        let (_dir, storage) = storage().await;
        let dropped = storage.commit(received(&storage, PDF).await, StorageKind::Submission).await.unwrap();
        let path = storage.path(&dropped.relative).unwrap();
        drop(dropped);
        assert!(!path.exists());
        let kept = storage.commit(received(&storage, PDF).await, StorageKind::Submission).await.unwrap();
        let relative = kept.relative.clone();
        kept.keep();
        assert!(storage.path(&relative).unwrap().exists());
        storage.remove(&relative).await;
        assert!(!storage.path(&relative).unwrap().exists());
    }

    #[tokio::test]
    async fn path_rejects_anything_but_a_stored_name() {
        let (_dir, storage) = storage().await;
        let id = Uuid::new_v4();
        for bad in [
            "../lara.db".to_string(),
            "/etc/passwd".to_string(),
            format!("materials/../../{id}.pdf"),
            format!("tmp/{id}.pdf"),
            format!("materials/a/{id}.pdf"),
            format!("materials/{id}.exe"),
            format!("materials/{id}"),
            "materials/lesson.pdf".to_string(),
            // Other spellings of a UUID that the Hub never writes.
            format!("materials/{}.pdf", id.simple()),
            format!("materials/{{{id}}}.pdf"),
            format!("materials/{}.pdf", id.urn()),
            format!("materials/{}.pdf", id.to_string().to_uppercase()),
        ] {
            assert_eq!(storage.path(&bad), None, "{bad}");
        }
        assert!(storage.path(&format!("submissions/{id}.jpg")).is_some());
    }

    #[tokio::test]
    async fn open_clears_stale_temp_files() {
        let dir = tempfile::tempdir().unwrap();
        let storage = Storage::open(dir.path()).await.unwrap();
        let kept = storage.commit(received(&storage, PDF).await, StorageKind::Material).await.unwrap();
        let relative = kept.relative.clone();
        kept.keep();
        std::fs::write(storage.root().join(TMP).join("left-by-a-crash"), b"partial").unwrap();
        let reopened = Storage::open(dir.path()).await.unwrap();
        assert_eq!(files_in(&reopened.root().join(TMP)), 0);
        assert!(reopened.path(&relative).unwrap().exists(), "stored files survive a restart");
    }
}
