use std::{io, path::Path};
use serde::Serialize;

#[derive(Clone, Default, Serialize)]
pub struct StorageUse { pub videos: u64, pub documents: u64, pub homework: u64, pub database: u64 }

pub fn scan(root: &Path) -> io::Result<StorageUse> {
    let mut usage = StorageUse::default();
    for (folder, homework) in [("materials", false), ("submissions", true)] {
        for (index, entry) in std::fs::read_dir(root.join("storage").join(folder))?.enumerate() {
            if index >= 100_000 { return Err(io::Error::other("Storage inventory too large")); }
            let entry = entry?;
            // Stored files are flat UUID names; never follow links outside Hub storage.
            if !entry.file_type()?.is_file() { continue; }
            let bytes = entry.metadata()?.len();
            let video = entry.path().extension().is_some_and(|ext| matches!(ext.to_string_lossy().to_ascii_lowercase().as_str(), "mp4" | "webm" | "mkv" | "avi" | "mov"));
            let total = if homework { &mut usage.homework } else if video { &mut usage.videos } else { &mut usage.documents };
            *total = total.saturating_add(bytes);
        }
    }
    for name in ["lara.db", "lara.db-wal", "lara.db-shm"] {
        match root.join(name).symlink_metadata() {
            Ok(meta) if meta.is_file() => usage.database = usage.database.saturating_add(meta.len()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {},
            Ok(_) => {},
            Err(error) => return Err(error),
        }
    }
    Ok(usage)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn inventory_counts_bytes_by_kind_without_returning_paths() {
        let root = std::env::temp_dir().join(format!("lara-storage-count-{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir_all(root.join("storage/materials")).unwrap();
        std::fs::create_dir_all(root.join("storage/submissions")).unwrap();
        std::fs::write(root.join("storage/materials/a.mp4"), [0; 20]).unwrap();
        std::fs::write(root.join("storage/materials/b.pdf"), [0; 10]).unwrap();
        std::fs::write(root.join("storage/submissions/c.jpg"), [0; 5]).unwrap();
        std::fs::write(root.join("lara.db"), [0; 15]).unwrap();
        std::fs::write(root.join("lara.db-wal"), [0; 2]).unwrap();
        let value = scan(&root).unwrap();
        assert_eq!((value.videos, value.documents, value.homework, value.database), (20, 10, 5, 17));
        let canonical = root.canonicalize().unwrap();
        let temp = std::env::temp_dir().canonicalize().unwrap();
        assert_eq!(canonical.parent(), Some(temp.as_path()));
        std::fs::remove_dir_all(canonical).unwrap();
    }
}
