use crate::account::{self, Accounts};
use crate::discovery::HubInfo;
use sqlx::Row;
use tauri::{AppHandle, Manager};
use std::fs;
use std::io::Write;

#[tauri::command]
pub async fn get_material_by_id(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    material_id: String,
) -> Result<Option<serde_json::Value>, String> {
    let _guard = accounts.0.lock().await;
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(|e| e.to_string())?;

    let root = account::root(&app).map_err(|e| e.to_string())?;
    let key = account::context(&hub, &user_id);
    let mut db = account::database(&root, &key)
        .await
        .map_err(|e| e.to_string())?;

    let row = sqlx::query(
        r#"
        SELECT id, classroom_id, title, file_type, mime_type,
               file_size_bytes, download_url, local_file_path,
               topic_id, assignment_id, announcement_id,
               created_at, updated_at
        FROM materials
        WHERE id = ?1
        "#,
    )
    .bind(&material_id)
    .fetch_optional(&mut db)
    .await
    .map_err(|e| format!("Failed to fetch material: {}", e))?;

    Ok(row.map(|r| {
        let relative_path: Option<String> = r.get("local_file_path");
        
        // Convert relative path to absolute path if it exists
        let absolute_path = relative_path.and_then(|rel_path| {
            if rel_path.is_empty() {
                None
            } else {
                // Get app data dir and construct absolute path
                // The rel_path uses forward slashes (cross-platform), but join() will convert to OS-specific
                let app_data = app.path().app_data_dir().ok()?;
                
                // Split by forward slashes and rejoin with OS-specific separator
                let parts: Vec<&str> = rel_path.split('/').collect();
                let mut abs_path = app_data;
                for part in parts {
                    abs_path = abs_path.join(part);
                }
                
                // Verify file exists
                if abs_path.exists() {
                    Some(abs_path.display().to_string())
                } else {
                    None
                }
            }
        });
        
        serde_json::json!({
            "id": r.get::<String, _>("id"),
            "classroom_id": r.get::<String, _>("classroom_id"),
            "title": r.get::<String, _>("title"),
            "file_type": r.get::<String, _>("file_type"),
            "mime_type": r.get::<String, _>("mime_type"),
            "file_size_bytes": r.get::<i64, _>("file_size_bytes"),
            "download_url": r.get::<String, _>("download_url"),
            "local_file_path": absolute_path, // Return absolute path for playback
            "topic_id": r.get::<Option<String>, _>("topic_id"),
            "assignment_id": r.get::<Option<String>, _>("assignment_id"),
            "announcement_id": r.get::<Option<String>, _>("announcement_id"),
            "created_at": r.get::<i64, _>("created_at"),
            "updated_at": r.get::<i64, _>("updated_at"),
        })
    }))
}

#[tauri::command]
pub async fn download_video_for_offline(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    material_id: String,
    token: String,
) -> Result<String, String> {
    let _guard = accounts.0.lock().await;
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(|e| e.to_string())?;

    // Get app data directory (secure, controlled by Tauri)
    let app_data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app data directory: {}", e))?;
    
    // Create videos subdirectory
    let videos_dir = app_data_dir.join("videos");
    fs::create_dir_all(&videos_dir)
        .map_err(|e| format!("Failed to create videos directory: {}", e))?;
    
    // Get material metadata from database
    let root = account::root(&app).map_err(|e| e.to_string())?;
    let key = account::context(&hub, &user_id);
    let mut db = account::database(&root, &key)
        .await
        .map_err(|e| e.to_string())?;

    let material_row = sqlx::query(
        "SELECT id, mime_type FROM materials WHERE id = ?1"
    )
    .bind(&material_id)
    .fetch_one(&mut db)
    .await
    .map_err(|e| format!("Material not found: {}", e))?;
    
    let mime_type: String = material_row.get("mime_type");
    
    // Determine file extension from mime_type
    let extension = match mime_type.as_str() {
        "video/mp4" => "mp4",
        "video/webm" => "webm",
        "video/ogg" => "ogg",
        _ => "mp4", // fallback
    };
    
    // Secure file path (inside app data directory only)
    let filename = format!("{}.{}", material_id, extension);
    let file_path = videos_dir.join(&filename);
    
    // Build download URL
    let download_url = format!(
        "http://{}:{}/api/materials/{}/download",
        hub.ip, hub.http_port, material_id
    );
    
    drop(_guard); // Release the lock before making HTTP requests
    
    // HTTP client for download
    let client = reqwest::Client::new();
    
    // Check if file already exists - delete it to start fresh
    // This avoids 416 Range Not Satisfiable errors on re-download
    if file_path.exists() {
        fs::remove_file(&file_path)
            .map_err(|e| format!("Failed to remove existing file: {}", e))?;
    }
    
    // Build request - fresh download without Range header
    let request = client
        .get(&download_url)
        .query(&[("token", &token)]);
    
    // Execute request
    let response = request
        .send()
        .await
        .map_err(|e| format!("Download request failed: {}", e))?;
    
    // Accept both 200 OK (full download) and 206 Partial Content (resume)
    if !response.status().is_success() {
        return Err(format!("Download failed with status: {}", response.status()));
    }
    
    // Create new file (we deleted existing file above)
    let mut file = fs::File::create(&file_path)
        .map_err(|e| format!("Failed to create file: {}", e))?;
    
    // Stream and write bytes
    let bytes = response
        .bytes()
        .await
        .map_err(|e| format!("Failed to read response: {}", e))?;
    
    file.write_all(&bytes)
        .map_err(|e| format!("Failed to write file: {}", e))?;
    
    // Update database with local path (relative, using forward slashes for cross-platform)
    let relative_path = format!("videos/{}", filename);
    
    sqlx::query(
        "UPDATE materials SET local_file_path = ? WHERE id = ?"
    )
    .bind(&relative_path)
    .bind(&material_id)
    .execute(&mut db)
    .await
    .map_err(|e| format!("Failed to update database: {}", e))?;
    
    // Return absolute path for the video player (file_path already has correct separators)
    Ok(file_path.display().to_string())
}
