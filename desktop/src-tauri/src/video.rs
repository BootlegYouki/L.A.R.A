use crate::account::{self, Accounts};
use crate::discovery::HubInfo;
use sqlx::Row;
use tauri::{AppHandle, Emitter, Manager};
use std::fs;
use std::io::Write;

async fn local_video_url(
    app: AppHandle,
    hub: HubInfo,
    user_id: String,
    path: std::path::PathBuf,
) -> Result<String, String> {
    use axum::{extract::Request, http::StatusCode, response::IntoResponse, routing::get, Router};
    use tower::ServiceExt;
    use tower_http::services::ServeFile;

    let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
        .await
        .map_err(|_| "Could not open offline video".to_string())?;
    let address = listener.local_addr().map_err(|_| "Missing video address".to_string())?;
    let route = format!("/{}", uuid::Uuid::new_v4());
    let router = Router::new().route(&route, get(move |request: Request| {
        let (app, hub, user_id, path) = (app.clone(), hub.clone(), user_id.clone(), path.clone());
        async move {
            let accounts = app.state::<Accounts>();
            let guard = accounts.0.lock().await;
            if accounts.require_sync_selection(&hub, &user_id).is_err() {
                return StatusCode::NOT_FOUND.into_response();
            }
            drop(guard);
            match ServeFile::new(path).oneshot(request).await {
                Ok(response) => response.into_response(),
                Err(_) => StatusCode::NOT_FOUND.into_response(),
            }
        }
    }));
    tauri::async_runtime::spawn(async move {
        let _ = axum::serve(listener, router)
            .with_graceful_shutdown(tokio::time::sleep(std::time::Duration::from_secs(7200)))
            .await;
    });
    Ok(format!("http://{address}{route}"))
}


#[tauri::command]
pub async fn check_partial_download(
    app: AppHandle,
    material_id: String,
) -> Result<bool, String> {
    // Validate material_id is a valid UUID
    if uuid::Uuid::parse_str(&material_id).is_err() {
        return Ok(false);
    }

    // Get app data directory
    let app_data_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app data directory: {}", e))?;
    
    let videos_dir = app_data_dir.join("videos");
    let part_path = videos_dir.join(format!("{}.part", material_id));
    
    Ok(part_path.exists() && part_path.metadata().map(|m| m.len()).unwrap_or(0) > 0)
}

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

    let Some(r) = row else { return Ok(None) };
    {
        let relative_path: Option<String> = r.get("local_file_path");
        
        let absolute_path = relative_path.and_then(|relative| {
            let video_root = app.path().app_data_dir().ok()?.join("videos");
            let path = app.path().app_data_dir().ok()?.join(relative);
            let resolved = path.canonicalize().ok()?;
            let root = video_root.canonicalize().ok()?;
            (resolved.starts_with(root) && resolved.is_file())
                .then(|| path.to_string_lossy().into_owned())
        });

        let local_video_url = if cfg!(target_os = "linux") && r.get::<String, _>("file_type") == "VIDEO" {
            match &absolute_path {
                Some(path) => Some(local_video_url(app.clone(), hub, user_id, path.into()).await?),
                None => None,
            }
        } else {
            None
        };

        Ok(Some(serde_json::json!({
            "id": r.get::<String, _>("id"),
            "classroom_id": r.get::<String, _>("classroom_id"),
            "title": r.get::<String, _>("title"),
            "file_type": r.get::<String, _>("file_type"),
            "mime_type": r.get::<String, _>("mime_type"),
            "file_size_bytes": r.get::<i64, _>("file_size_bytes"),
            "download_url": r.get::<String, _>("download_url"),
            "local_video_url": local_video_url,
            "local_file_path": absolute_path, // Return absolute path for playback
            "topic_id": r.get::<Option<String>, _>("topic_id"),
            "assignment_id": r.get::<Option<String>, _>("assignment_id"),
            "announcement_id": r.get::<Option<String>, _>("announcement_id"),
            "created_at": r.get::<i64, _>("created_at"),
            "updated_at": r.get::<i64, _>("updated_at"),
        })))
    }
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
    use futures_util::StreamExt;
    
    let _guard = accounts.0.lock().await;
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(|e| e.to_string())?;

    // Validate material_id is a valid UUID before using in file path
    if uuid::Uuid::parse_str(&material_id).is_err() {
        return Err("Invalid material ID: not a valid UUID".to_string());
    }

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
        "SELECT id, mime_type, file_size_bytes FROM materials WHERE id = ?1"
    )
    .bind(&material_id)
    .fetch_one(&mut db)
    .await
    .map_err(|e| format!("Material not found: {}", e))?;
    
    let mime_type: String = material_row.get("mime_type");
    let expected_size: i64 = material_row.get("file_size_bytes");
    
    // Determine file extension from mime_type
    let extension = match mime_type.as_str() {
        "video/mp4" => "mp4",
        "video/webm" => "webm",
        "video/ogg" => "ogg",
        _ => "mp4", // fallback
    };
    
    // Secure file paths (inside app data directory only)
    let filename = format!("{}.{}", material_id, extension);
    let temp_path = videos_dir.join(format!("{}.part", material_id));
    let final_path = videos_dir.join(&filename);
    let etag_path = temp_path.with_extension("part.etag");
    
    // Build download URL using Hub's base URL (works for LAN and Remote/Suspension Mode)
    let base_url = format!("http://{}:{}", hub.ip, hub.http_port);
    let download_url = format!("{}/api/materials/{}/download", base_url, material_id);
    
    drop(_guard); // Release the lock before making HTTP requests
    
    // HTTP client for download
    let client = reqwest::Client::new();
    
    let saved_etag = fs::read(&etag_path).ok()
        .and_then(|bytes| reqwest::header::HeaderValue::from_bytes(&bytes).ok())
        .filter(|tag| tag.as_bytes().starts_with(b"\"") && tag.as_bytes().ends_with(b"\""));
    let mut start_byte = 0;
    let mut request = client.get(&download_url).query(&[("token", &token)]);
    if let Some(tag) = &saved_etag {
        start_byte = temp_path.metadata().map(|metadata| metadata.len()).unwrap_or(0);
        if start_byte > 0 {
            request = request
                .header(reqwest::header::RANGE, format!("bytes={start_byte}-"))
                .header(reqwest::header::IF_RANGE, tag);
        }
    }

    // Execute request
    let response = request
        .send()
        .await
        .map_err(|e| format!("Download request failed: {}", e))?;
    
    // Check status: 200 (full), 206 (partial), or 416 (restart needed)
    let status = response.status();
    let is_partial = status == reqwest::StatusCode::PARTIAL_CONTENT;
    
    if status == reqwest::StatusCode::RANGE_NOT_SATISFIABLE {
        // File changed on server or range invalid, delete .part and restart
        if temp_path.exists() {
            fs::remove_file(&temp_path)
                .map_err(|e| format!("Failed to remove stale partial file: {}", e))?;
        }
        return Err("Range not satisfiable, please retry download".to_string());
    }
    
    if status != reqwest::StatusCode::OK && !is_partial {
        return Err(format!("Download failed with status: {status}"));
    }

    let mut file = if is_partial {
        let expected_range = format!("bytes {start_byte}-{}/{expected_size}", expected_size - 1);
        if start_byte == 0
            || response.headers().get(reqwest::header::ETAG) != saved_etag.as_ref()
            || response.headers().get(reqwest::header::CONTENT_RANGE)
                .and_then(|value| value.to_str().ok()) != Some(expected_range.as_str())
        {
            return Err("Hub returned an unexpected partial video".to_string());
        }
        fs::OpenOptions::new().append(true).open(&temp_path)
    } else {
        start_byte = 0;
        let file = fs::File::create(&temp_path)
            .map_err(|e| format!("Failed to create partial file: {e}"))?;
        if let Some(tag) = response.headers().get(reqwest::header::ETAG) {
            fs::write(&etag_path, tag.as_bytes())
                .map_err(|e| format!("Failed to save video ETag: {e}"))?;
        } else if let Err(error) = fs::remove_file(&etag_path) {
            if error.kind() != std::io::ErrorKind::NotFound {
                return Err(format!("Failed to clear video ETag: {error}"));
            }
        }
        Ok(file)
    }.map_err(|e| format!("Failed to open partial file: {e}"))?;

    // Stream bytes to disk (chunk by chunk, not all in RAM)
    let mut stream = response.bytes_stream();
    let mut total_downloaded = start_byte;
    let mut last_progress_emit = start_byte;
    
    while let Some(chunk_result) = stream.next().await {
        let chunk = chunk_result
            .map_err(|e| format!("Failed to read chunk: {}", e))?;
        
        // Check if we're about to exceed expected size
        if total_downloaded + chunk.len() as u64 > expected_size as u64 {
            return Err(format!(
                "Download exceeded expected size: {} + {} > {}",
                total_downloaded, chunk.len(), expected_size
            ));
        }
        
        file.write_all(&chunk)
            .map_err(|e| format!("Failed to write chunk: {}", e))?;
        
        total_downloaded += chunk.len() as u64;
        
        // Emit progress event every 50KB or at completion
        if total_downloaded - last_progress_emit >= 51200 || total_downloaded == expected_size as u64 {
            let percentage = ((total_downloaded as f64 / expected_size as f64) * 100.0) as u8;
            let _ = app.emit("download-progress", serde_json::json!({
                "materialId": material_id,
                "downloaded": total_downloaded,
                "total": expected_size,
                "percentage": percentage
            }));
            last_progress_emit = total_downloaded;
        }
    }
    
    // Flush to ensure all bytes are written
    file.flush()
        .map_err(|e| format!("Failed to flush file: {}", e))?;
    
    // Verify file size matches expected
    let downloaded_size = temp_path.metadata()
        .map(|m| m.len())
        .unwrap_or(0);
    
    if downloaded_size != expected_size as u64 {
        return Err(format!(
            "Download incomplete: expected {} bytes, got {}",
            expected_size, downloaded_size
        ));
    }
    
    drop(file);

    // Download complete, rename .part to final name
    if final_path.exists() {
        fs::remove_file(&final_path)
            .map_err(|e| format!("Failed to remove old final file: {}", e))?;
    }
    
    fs::rename(&temp_path, &final_path)
        .map_err(|e| format!("Failed to rename to final file: {}", e))?;
    
    let _ = fs::remove_file(&etag_path);

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
    
    // Return absolute path for the video player (final_path already has correct separators)
    Ok(final_path.display().to_string())
}
