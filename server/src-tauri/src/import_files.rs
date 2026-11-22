use lara_hub::auth::{sessions, Role};
use lara_hub::AppState;

use crate::Hub;

fn extension(filename: &str, bytes: &[u8]) -> Result<&'static str, &'static str> {
    if bytes.is_empty() || bytes.len() > 1024 * 1024 {
        return Err("INVALID_IMPORT_FILE");
    }
    match filename {
        "learner-template.csv" | "learner-credentials.csv" | "LARA-Hub-resource-usage.csv" => Ok("csv"),
        "learner-template.xlsx" => Ok("xlsx"),
        _ => Err("INVALID_IMPORT_FILE"),
    }
}

async fn authorize(app: &AppState, token: &str) -> Result<(), &'static str> {
    let user = sessions::user_for_token(&app.db, token, app.clock.now_ms())
        .await
        .map_err(|_| "HUB_UNAVAILABLE")?
        .ok_or("UNAUTHORIZED")?;
    if user.role != Role::Admin {
        return Err("FORBIDDEN");
    }
    Ok(())
}

#[tauri::command]
pub async fn save_import_file(
    hub: tauri::State<'_, Hub>,
    token: String,
    filename: String,
    bytes: Vec<u8>,
    title: String,
) -> Result<bool, &'static str> {
    let extension = extension(&filename, &bytes)?;
    if title.len() > 200 {
        return Err("INVALID_IMPORT_FILE");
    }
    let app = hub
        .handle
        .lock()
        .map_err(|_| "HUB_UNAVAILABLE")?
        .as_ref()
        .map(|handle| handle.state().clone())
        .ok_or("HUB_UNAVAILABLE")?;
    authorize(&app, &token).await?;
    let chosen = rfd::AsyncFileDialog::new()
        .set_title(&title)
        .set_file_name(&filename)
        .add_filter(extension, &[extension])
        .save_file()
        .await;
    let Some(file) = chosen else {
        return Ok(false);
    };
    // A sign-out or expiry while the native dialog was open must also stop the export.
    authorize(&app, &token).await?;
    file.write(&bytes).await.map_err(|_| "SAVE_FAILED")?;
    Ok(true)
}

#[cfg(test)]
mod tests {
    use super::extension;

    #[test]
    fn save_accepts_only_bounded_import_files() {
        assert_eq!(extension("learner-credentials.csv", b"data"), Ok("csv"));
        assert_eq!(extension("LARA-Hub-resource-usage.csv", b"data"), Ok("csv"));
        assert_eq!(extension("learner-template.xlsx", b"data"), Ok("xlsx"));
        assert!(extension("../other.csv", b"data").is_err());
        assert!(extension("learner-credentials.csv", &[]).is_err());
        assert!(extension("learner-credentials.csv", &vec![0; 1024 * 1024 + 1]).is_err());
    }
}
