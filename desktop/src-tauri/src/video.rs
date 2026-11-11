use crate::account::{self, Accounts};
use crate::discovery::HubInfo;
use sqlx::Row;
use tauri::{AppHandle, Manager};

#[tauri::command]
pub async fn update_material_local_path(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    material_id: String,
    local_path: String,
) -> Result<(), String> {
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

    // Use current timestamp in milliseconds
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| format!("Time error: {}", e))?
        .as_millis() as i64;

    // Execute raw SQL update
    sqlx::query(
        r#"
        UPDATE materials
        SET local_file_path = ?1, updated_at = ?2
        WHERE id = ?3
        "#,
    )
    .bind(&local_path)
    .bind(now_ms)
    .bind(&material_id)
    .execute(&mut db)
    .await
    .map_err(|e| format!("Failed to update material: {}", e))?;

    Ok(())
}

#[tauri::command]
pub async fn get_app_data_path(app: AppHandle) -> Result<String, String> {
    let app_data = app
        .path()
        .app_data_dir()
        .map_err(|e| format!("Failed to get app data path: {}", e))?;
    
    app_data
        .to_str()
        .ok_or_else(|| "Invalid app data path".to_string())
        .map(|s| s.to_string())
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

    Ok(row.map(|r| {
        serde_json::json!({
            "id": r.get::<String, _>("id"),
            "classroom_id": r.get::<String, _>("classroom_id"),
            "title": r.get::<String, _>("title"),
            "file_type": r.get::<String, _>("file_type"),
            "mime_type": r.get::<String, _>("mime_type"),
            "file_size_bytes": r.get::<i64, _>("file_size_bytes"),
            "download_url": r.get::<String, _>("download_url"),
            "local_file_path": r.get::<Option<String>, _>("local_file_path"),
            "topic_id": r.get::<Option<String>, _>("topic_id"),
            "assignment_id": r.get::<Option<String>, _>("assignment_id"),
            "announcement_id": r.get::<Option<String>, _>("announcement_id"),
            "created_at": r.get::<i64, _>("created_at"),
            "updated_at": r.get::<i64, _>("updated_at"),
        })
    }))
}
