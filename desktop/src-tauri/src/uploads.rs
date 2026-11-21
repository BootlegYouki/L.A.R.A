use crate::{
    account::{self, Accounts, Role},
    discovery::HubInfo,
};
use serde::{Deserialize, Serialize};
use sqlx::Row;
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};
use tauri::{AppHandle, Manager};
use tokio::sync::Mutex;

const LIMIT: u64 = 250 * 1024 * 1024;
const CHUNK_LIMIT: usize = 64 * 1024;
// A maximum-size transfer can take several minutes on a classroom LAN.
const UPLOAD_TIMEOUT_SECS: u64 = 15 * 60;
const ERROR: &str = "upload_unavailable";

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
enum Status {
    Staging,
    Ready,
    Uploading,
    Succeeded,
    Failed,
    Uncertain,
    Cancelled,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Job {
    id: String,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
    title: String,
    file_type: String,
    file_name: String,
    file_size: u64,
    topic_id: Option<String>,
    assignment_id: Option<String>,
    announcement_id: Option<String>,
    status: Status,
    uploaded_bytes: u64,
    material_id: Option<String>,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JobView {
    id: String,
    title: String,
    file_name: String,
    file_size: u64,
    status: Status,
    uploaded_bytes: u64,
}
impl JobView {
    fn new(job: &Job, progress: Option<u64>) -> Self {
        Self {
            id: job.id.clone(),
            title: job.title.clone(),
            file_name: job.file_name.clone(),
            file_size: job.file_size,
            status: job.status,
            uploaded_bytes: progress.unwrap_or(job.uploaded_bytes),
        }
    }
}

struct ActiveUpload {
    abort: Option<tokio::task::AbortHandle>,
    progress: Arc<AtomicU64>,
    _lease: std::fs::File,
}
#[derive(Default)]
pub struct UploadTasks {
    active: Mutex<std::collections::HashMap<String, ActiveUpload>>,
    staging: Mutex<std::collections::HashMap<String, std::fs::File>>,
    jobs: Mutex<()>,
}
fn reserve_task(
    active: &mut std::collections::HashMap<String, ActiveUpload>,
    key: String,
    progress: Arc<AtomicU64>,
    lease: std::fs::File,
) -> bool {
    if active.contains_key(&key) {
        return false;
    }
    active.insert(
        key,
        ActiveUpload {
            abort: None,
            progress,
            _lease: lease,
        },
    );
    true
}

fn job_dir(root: &Path, hub: &HubInfo, user_id: &str, id: &str) -> Result<PathBuf, String> {
    if !account::uuid(id) || !account::uuid(user_id) {
        return Err("invalid_upload".into());
    }
    Ok(root
        .join("material-uploads")
        .join(account::context(hub, user_id))
        .join(id))
}
fn same_hub(a: &HubInfo, b: &HubInfo) -> bool {
    a.ip == b.ip && a.http_port == b.http_port
}
fn load(root: &Path, hub: &HubInfo, user_id: &str, id: &str) -> Result<Job, String> {
    account::validate_hub(hub).map_err(|_| "invalid_account")?;
    let path = job_dir(root, hub, user_id, id)?.join("job.json");
    let job: Job =
        serde_json::from_slice(&std::fs::read(path).map_err(|_| ERROR)?).map_err(|_| ERROR)?;
    if job.id != id || job.user_id != user_id || !same_hub(&job.hub, hub) {
        return Err("invalid_upload".into());
    }
    Ok(job)
}
fn recover(root: &Path, mut job: Job, running: bool) -> Result<Job, String> {
    if job.status == Status::Succeeded {
        let _ = std::fs::remove_file(payload(root, &job)?);
    }
    if job.status == Status::Staging && !running {
        job.status = Status::Cancelled;
        save(root, &job)?;
        let _ = std::fs::remove_file(payload(root, &job)?);
    }
    if job.status == Status::Uploading && !running {
        job.status = Status::Uncertain;
        save(root, &job)?;
    }
    Ok(job)
}
fn save(root: &Path, job: &Job) -> Result<(), String> {
    let dir = job_dir(root, &job.hub, &job.user_id, &job.id)?;
    std::fs::create_dir_all(&dir).map_err(|_| ERROR)?;
    let tmp = dir.join("job.tmp");
    std::fs::write(&tmp, serde_json::to_vec(job).map_err(|_| ERROR)?).map_err(|_| ERROR)?;
    std::fs::rename(tmp, dir.join("job.json")).map_err(|_| ERROR.to_owned())
}
fn payload(root: &Path, job: &Job) -> Result<PathBuf, String> {
    Ok(job_dir(root, &job.hub, &job.user_id, &job.id)?.join("payload"))
}
fn acquire_lease(
    root: &Path,
    hub: &HubInfo,
    user_id: &str,
    id: &str,
) -> Result<std::fs::File, String> {
    let dir = job_dir(root, hub, user_id, id)?;
    std::fs::create_dir_all(&dir).map_err(|_| ERROR)?;
    let file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .open(dir.join("active.lock"))
        .map_err(|_| ERROR)?;
    match file.try_lock() {
        Ok(()) => Ok(file),
        Err(std::fs::TryLockError::WouldBlock) => Err("upload_busy".into()),
        Err(std::fs::TryLockError::Error(_)) => Err(ERROR.into()),
    }
}
fn valid_chunk(data: &[u8]) -> bool {
    !data.is_empty() && data.len() <= CHUNK_LIMIT
}
fn can_append(job: &Job, size: usize) -> bool {
    job.status == Status::Staging && job.uploaded_bytes.saturating_add(size as u64) <= job.file_size
}
fn payload_matches(path: &Path, size: u64) -> bool {
    std::fs::metadata(path).is_ok_and(|m| m.is_file() && m.len() == size && m.len() <= LIMIT)
}

async fn validate_context(
    root: &Path,
    hub: &HubInfo,
    user_id: &str,
    classroom_id: &str,
    topic_id: Option<&str>,
    assignment_id: Option<&str>,
    announcement_id: Option<&str>,
) -> Result<(), String> {
    account::validate_hub(hub).map_err(|_| "invalid_account")?;
    if !account::uuid(user_id)
        || !account::uuid(classroom_id)
        || topic_id.is_some_and(|id| !account::uuid(id))
        || assignment_id.is_some_and(|id| !account::uuid(id))
        || announcement_id.is_some_and(|id| !account::uuid(id))
        || (assignment_id.is_some() && announcement_id.is_some())
    {
        return Err("invalid_upload".into());
    }
    let mut db = account::database(root, &account::context(hub, user_id))
        .await
        .map_err(str::to_owned)?;
    let profile = account::profile(&mut db, user_id)
        .await
        .map_err(str::to_owned)?;
    if profile.user.role != Role::TEACHER {
        return Err("FORBIDDEN".into());
    }
    let class =
        sqlx::query("SELECT teacher_id,co_teacher_ids_json,archived_at FROM classrooms WHERE id=?")
            .bind(classroom_id)
            .fetch_optional(&mut db)
            .await
            .map_err(|_| ERROR)?
            .ok_or_else(|| "FORBIDDEN".to_owned())?;
    let teacher: String = class.get(0);
    let co: Option<String> = class.get(1);
    let archived: Option<i64> = class.get(2);
    let co: Vec<String> = serde_json::from_str(co.as_deref().unwrap_or("[]")).map_err(|_| ERROR)?;
    if archived.is_some() || (teacher != user_id && !co.iter().any(|id| id == user_id)) {
        return Err("FORBIDDEN".into());
    }
    for (table, id) in [
        ("topics", topic_id),
        ("assignments", assignment_id),
        ("announcements", announcement_id),
    ] {
        if let Some(id) = id {
            let query = format!("SELECT 1 FROM {table} WHERE id=? AND classroom_id=?");
            if sqlx::query_scalar::<_, i64>(&query)
                .bind(id)
                .bind(classroom_id)
                .fetch_optional(&mut db)
                .await
                .map_err(|_| ERROR)?
                .is_none()
            {
                return Err("FORBIDDEN".into());
            }
        }
    }
    Ok(())
}
async fn persist(app: &AppHandle, root: &Path, job: &Job) -> Result<(), String> {
    let state = app.state::<UploadTasks>();
    let _guard = state.jobs.lock().await;
    save(root, job)
}

#[tauri::command]
pub async fn begin_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
    title: String,
    file_type: String,
    file_name: String,
    size: u64,
    topic_id: Option<String>,
    assignment_id: Option<String>,
    announcement_id: Option<String>,
) -> Result<String, String> {
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    if title.trim().is_empty()
        || title.chars().any(char::is_control)
        || title.chars().count() > 500
        || !matches!(file_type.as_str(), "VIDEO" | "DOCUMENT" | "WORKSHEET")
        || file_name.is_empty()
        || file_name.chars().count() > 255
        || file_name.chars().any(char::is_control)
        || size == 0
        || size > LIMIT
    {
        return Err("invalid_upload".into());
    }
    let root = account::root(&app).map_err(str::to_owned)?;
    validate_context(
        &root,
        &hub,
        &user_id,
        &classroom_id,
        topic_id.as_deref(),
        assignment_id.as_deref(),
        announcement_id.as_deref(),
    )
    .await?;
    let id = uuid::Uuid::new_v4().to_string();
    let job = Job {
        id: id.clone(),
        hub: hub.clone(),
        user_id: user_id.clone(),
        classroom_id,
        title: title.trim().into(),
        file_type,
        file_name,
        file_size: size,
        topic_id,
        assignment_id,
        announcement_id,
        status: Status::Staging,
        uploaded_bytes: 0,
        material_id: None,
    };
    let dir = job_dir(&root, &hub, &user_id, &id)?;
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    std::fs::create_dir_all(&dir).map_err(|_| ERROR)?;
    let lease = acquire_lease(&root, &hub, &user_id, &id)?;
    std::fs::File::create(dir.join("payload")).map_err(|_| ERROR)?;
    save(&root, &job)?;
    task_state.staging.lock().await.insert(
        format!("{}:{}", account::context(&hub, &user_id), id),
        lease,
    );
    Ok(id)
}

#[tauri::command]
pub async fn append_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
    data: Vec<u8>,
) -> Result<(), String> {
    if !valid_chunk(&data) {
        return Err("invalid_upload_chunk".into());
    }
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    let root = account::root(&app).map_err(str::to_owned)?;
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    let job = load(&root, &hub, &user_id, &job_id)?;
    if !task_state.staging.lock().await.contains_key(&format!(
        "{}:{}",
        account::context(&hub, &user_id),
        job_id
    )) {
        return Err("invalid_upload_state".into());
    }
    if !can_append(&job, data.len()) {
        return Err("invalid_upload_state".into());
    }
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(payload(&root, &job)?)
        .map_err(|_| ERROR)?;
    file.write_all(&data).map_err(|_| ERROR)?;
    let mut job = job;
    job.uploaded_bytes += data.len() as u64;
    save(&root, &job)?;
    Ok(())
}

#[tauri::command]
pub async fn finish_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
) -> Result<JobView, String> {
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    let root = account::root(&app).map_err(str::to_owned)?;
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    let mut job = load(&root, &hub, &user_id, &job_id)?;
    if !task_state.staging.lock().await.contains_key(&format!(
        "{}:{}",
        account::context(&hub, &user_id),
        job_id
    )) {
        return Err("invalid_upload_state".into());
    }
    validate_context(
        &root,
        &hub,
        &user_id,
        &job.classroom_id,
        job.topic_id.as_deref(),
        job.assignment_id.as_deref(),
        job.announcement_id.as_deref(),
    )
    .await?;
    if job.status != Status::Staging
        || job.uploaded_bytes != job.file_size
        || std::fs::metadata(payload(&root, &job)?)
            .map(|m| m.len())
            .ok()
            != Some(job.file_size)
    {
        return Err("incomplete_upload".into());
    }
    job.status = Status::Ready;
    job.uploaded_bytes = 0;
    save(&root, &job)?;
    task_state.staging.lock().await.remove(&format!(
        "{}:{}",
        account::context(&hub, &user_id),
        job_id
    ));
    Ok(JobView::new(&job, None))
}

async fn post_material(
    app: AppHandle,
    root: PathBuf,
    mut job: Job,
    token: String,
    progress: Arc<AtomicU64>,
) {
    let outcome = async {
        let accounts = app.state::<Accounts>();
        accounts
            .require_sync_selection(&job.hub, &job.user_id)
            .map_err(str::to_owned)?;
        validate_context(
            &root,
            &job.hub,
            &job.user_id,
            &job.classroom_id,
            job.topic_id.as_deref(),
            job.assignment_id.as_deref(),
            job.announcement_id.as_deref(),
        )
        .await?;
        let path = payload(&root, &job)?;
        let metadata = tokio::fs::metadata(&path).await.map_err(|_| ERROR)?;
        if !metadata.is_file() || metadata.len() != job.file_size || metadata.len() > LIMIT {
            return Err("invalid_upload_payload".into());
        }
        let file = tokio::fs::File::open(path).await.map_err(|_| ERROR)?;
        let current_progress = progress.clone();
        let total = job.file_size;
        let stream = futures_util::stream::try_unfold((file, 0u64), move |(mut file, sent)| {
            let progress = current_progress.clone();
            async move {
                use tokio::io::AsyncReadExt;
                let remaining = total.saturating_sub(sent);
                if remaining == 0 {
                    let mut extra = [0u8; 1];
                    if file.read(&mut extra).await? != 0 {
                        return Err(std::io::Error::other(
                            "upload source grew beyond declared size",
                        ));
                    }
                    return Ok(None);
                }
                let mut buffer = vec![0u8; remaining.min(CHUNK_LIMIT as u64) as usize];
                let count = file.read(&mut buffer).await?;
                if count == 0 {
                    return Ok(None);
                }
                let sent = sent + count as u64;
                progress.store(sent, Ordering::Relaxed);
                buffer.truncate(count);
                Ok(Some((buffer, (file, sent))))
            }
        });
        let part = reqwest::multipart::Part::stream_with_length(
            reqwest::Body::wrap_stream(stream),
            job.file_size,
        )
        .file_name(job.file_name.clone())
        .mime_str("application/octet-stream")
        .map_err(|_| "invalid_upload")?;
        let mut form = reqwest::multipart::Form::new()
            .text("classroom_id", job.classroom_id.clone())
            .text("title", job.title.clone())
            .text("file_type", job.file_type.clone())
            .part("file", part);
        if let Some(v) = &job.topic_id {
            form = form.text("topic_id", v.clone());
        }
        if let Some(v) = &job.assignment_id {
            form = form.text("assignment_id", v.clone());
        }
        if let Some(v) = &job.announcement_id {
            form = form.text("announcement_id", v.clone());
        }
        let url = format!("http://{}:{}/api/materials", job.hub.ip, job.hub.http_port);
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .connect_timeout(std::time::Duration::from_secs(10))
            .timeout(std::time::Duration::from_secs(UPLOAD_TIMEOUT_SECS))
            .build()
            .map_err(|_| ERROR)?;
        let response = client
            .post(url)
            .bearer_auth(token)
            .multipart(form)
            .send()
            .await;
        let response = match response {
            Ok(r) => r,
            Err(_) => {
                job.status = Status::Uncertain;
                persist(&app, &root, &job).await?;
                return Err("upload_outcome_uncertain".to_owned());
            }
        };
        if response.status() != reqwest::StatusCode::OK {
            job.status =
                if response.status().is_server_error() || response.status().is_redirection() {
                    Status::Uncertain
                } else {
                    Status::Failed
                };
            persist(&app, &root, &job).await?;
            return Err("upload_failed".into());
        }
        use futures_util::StreamExt;
        let mut body_bytes = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|_| "upload_outcome_uncertain".to_owned())?;
            if body_bytes.len() + chunk.len() > 65536 {
                job.status = Status::Uncertain;
                persist(&app, &root, &job).await?;
                return Err("invalid_upload_receipt".into());
            }
            body_bytes.extend_from_slice(&chunk);
        }
        let body: serde_json::Value = serde_json::from_slice(&body_bytes)
            .map_err(|_| "upload_outcome_uncertain".to_owned())?;
        let material_id = body
            .get("id")
            .and_then(|v| v.as_str())
            .filter(|id| account::uuid(id))
            .ok_or_else(|| "upload_outcome_uncertain".to_owned())?;
        if body["classroom_id"] != job.classroom_id
            || body["title"] != job.title
            || body["file_type"] != job.file_type
            || body["file_size_bytes"].as_u64() != Some(job.file_size)
        {
            return Err("upload_outcome_uncertain".into());
        }
        job.material_id = Some(material_id.to_owned());
        job.status = Status::Succeeded;
        job.uploaded_bytes = job.file_size;
        persist(&app, &root, &job).await?;
        let _ = std::fs::remove_file(payload(&root, &job)?);
        Ok(())
    }
    .await;
    if outcome.is_err() && job.status == Status::Uploading {
        job.status = Status::Uncertain;
        let _ = persist(&app, &root, &job).await;
    }
    if job.status == Status::Succeeded {
        progress.store(job.file_size, Ordering::Relaxed);
    }
}

#[tauri::command]
pub async fn start_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
) -> Result<(), String> {
    launch(app, accounts, hub, user_id, job_id, false).await
}
#[tauri::command]
pub async fn retry_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
) -> Result<(), String> {
    launch(app, accounts, hub, user_id, job_id, true).await
}
async fn launch(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
    retry: bool,
) -> Result<(), String> {
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    let key = format!("{}:{}", account::context(&hub, &user_id), job_id);
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    let mut tasks = task_state.active.lock().await;
    if tasks.contains_key(&key) {
        return Err("upload_already_running".into());
    }
    let root = account::root(&app).map_err(str::to_owned)?;
    let lease = acquire_lease(&root, &hub, &user_id, &job_id)?;
    let mut job = load(&root, &hub, &user_id, &job_id)?;
    if !(job.status == Status::Ready
        || (retry && matches!(job.status, Status::Failed | Status::Uncertain)))
    {
        return Err("invalid_upload_state".into());
    }
    validate_context(
        &root,
        &hub,
        &user_id,
        &job.classroom_id,
        job.topic_id.as_deref(),
        job.assignment_id.as_deref(),
        job.announcement_id.as_deref(),
    )
    .await?;
    let source = payload(&root, &job)?;
    let source_meta = std::fs::metadata(&source).map_err(|_| "invalid_upload_payload")?;
    if !source_meta.is_file() || source_meta.len() != job.file_size || source_meta.len() > LIMIT {
        return Err("invalid_upload_payload".into());
    }
    if !payload_matches(&source, job.file_size) {
        return Err("invalid_upload_payload".into());
    }
    let token = account::secure_token(&app, &accounts, &hub, &user_id)
        .await
        .map_err(str::to_owned)?
        .ok_or_else(|| "account_context_changed".to_owned())?;
    job.status = Status::Uploading;
    save(&root, &job)?;
    let progress = Arc::new(AtomicU64::new(0));
    if !reserve_task(&mut tasks, key.clone(), progress.clone(), lease) {
        return Err("upload_already_running".into());
    }
    let app_for_task = app.clone();
    let finished_key = key.clone();
    let handle = tokio::spawn(async move {
        post_material(app_for_task.clone(), root, job, token, progress).await;
        app_for_task
            .state::<UploadTasks>()
            .active
            .lock()
            .await
            .remove(&finished_key);
    });
    if let Some(active) = tasks.get_mut(&key) {
        active.abort = Some(handle.abort_handle());
    }
    Ok(())
}

#[tauri::command]
pub async fn list_uploads(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
) -> Result<Vec<JobView>, String> {
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    if !account::uuid(&classroom_id) {
        return Err("invalid_upload".into());
    }
    let root = account::root(&app).map_err(str::to_owned)?;
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    let dir = root
        .join("material-uploads")
        .join(account::context(&hub, &user_id));
    let mut jobs = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            if let Some(id) = entry.file_name().to_str() {
                if let Ok(job) = load(&root, &hub, &user_id, id) {
                    let key = format!("{}:{}", account::context(&hub, &user_id), id);
                    let tasks = task_state.active.lock().await;
                    let active = tasks.get(&key);
                    let uploading = active.is_some();
                    let progress = active.map(|task| task.progress.load(Ordering::Relaxed));
                    drop(tasks);
                    let staging = task_state.staging.lock().await.contains_key(&key);
                    if uploading || staging {
                        if job.classroom_id == classroom_id {
                            jobs.push(JobView::new(&job, progress));
                        }
                    } else {
                        match acquire_lease(&root, &hub, &user_id, id) {
                            Ok(lease) => {
                                if let Ok(latest) = load(&root, &hub, &user_id, id) {
                                    if let Ok(job) = recover(&root, latest, false) {
                                        if job.classroom_id == classroom_id {
                                            jobs.push(JobView::new(&job, None));
                                        }
                                    }
                                }
                                drop(lease);
                            }
                            Err(_) => {
                                if job.classroom_id == classroom_id {
                                    jobs.push(JobView::new(&job, Some(job.uploaded_bytes)));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Ok(jobs)
}

#[tauri::command]
pub async fn cancel_upload(
    app: AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    job_id: String,
) -> Result<(), String> {
    let user_id = user_id.to_ascii_lowercase();
    accounts
        .require_sync_selection(&hub, &user_id)
        .map_err(str::to_owned)?;
    let root = account::root(&app).map_err(str::to_owned)?;
    let task_state = app.state::<UploadTasks>();
    let _jobs = task_state.jobs.lock().await;
    let key = format!("{}:{}", account::context(&hub, &user_id), job_id);
    let mut held_lease = task_state.staging.lock().await.remove(&key);
    let local_active = task_state.active.lock().await.remove(&key);
    if let Some(task) = &local_active {
        if let Some(abort) = &task.abort {
            abort.abort();
        }
    }
    if held_lease.is_none() {
        if let Some(task) = local_active {
            held_lease = Some(task._lease);
        }
    }
    if held_lease.is_none() {
        held_lease = Some(acquire_lease(&root, &hub, &user_id, &job_id)?);
    }
    let mut job = load(&root, &hub, &user_id, &job_id)?;
    if matches!(job.status, Status::Staging | Status::Ready | Status::Failed) {
        job.status = Status::Cancelled;
        save(&root, &job)?;
        let _ = std::fs::remove_file(payload(&root, &job)?);
    } else if job.status == Status::Uploading {
        job.status = Status::Uncertain;
        save(&root, &job)?;
    }
    drop(held_lease);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::discovery::HubInfo;
    use std::net::Ipv4Addr;
    fn root() -> PathBuf {
        let p = std::env::temp_dir().join(format!("lara-upload-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&p).unwrap();
        p
    }
    fn job(root: &Path) -> Job {
        let j = Job {
            id: "11111111-1111-4111-8111-111111111111".into(),
            hub: HubInfo {
                name: "Hub".into(),
                ip: Ipv4Addr::LOCALHOST,
                http_port: 8080,
                ws_port: 8081,
            },
            user_id: "22222222-2222-4222-8222-222222222222".into(),
            classroom_id: "33333333-3333-4333-8333-333333333333".into(),
            title: "Title".into(),
            file_type: "DOCUMENT".into(),
            file_name: "x.pdf".into(),
            file_size: 1,
            topic_id: None,
            assignment_id: None,
            announcement_id: None,
            status: Status::Ready,
            uploaded_bytes: 0,
            material_id: None,
        };
        save(root, &j).unwrap();
        std::fs::write(payload(root, &j).unwrap(), b"x").unwrap();
        j
    }
    #[test]
    fn metadata_never_contains_source_path_or_token_and_context_is_scoped() {
        let root = root();
        let j = job(&root);
        let raw = std::fs::read_to_string(
            job_dir(&root, &j.hub, &j.user_id, &j.id)
                .unwrap()
                .join("job.json"),
        )
        .unwrap();
        assert!(!raw.contains("token"));
        assert!(!raw.contains("payload"));
        assert_eq!(load(&root, &j.hub, &j.user_id, &j.id).unwrap().id, j.id);
        assert_eq!(
            load(
                &root,
                &HubInfo {
                    name: "Renamed Hub".into(),
                    ws_port: 9000,
                    ..j.hub.clone()
                },
                &j.user_id,
                &j.id
            )
            .unwrap()
            .id,
            j.id
        );
        assert!(load(
            &root,
            &HubInfo {
                ip: Ipv4Addr::new(192, 168, 1, 2),
                ..j.hub.clone()
            },
            &j.user_id,
            &j.id
        )
        .is_err());
    }
    #[test]
    fn uploading_recovery_is_uncertain_and_never_implicitly_ready() {
        let root = root();
        let mut j = job(&root);
        j.status = Status::Uploading;
        save(&root, &j).unwrap();
        assert_eq!(
            recover(&root, load(&root, &j.hub, &j.user_id, &j.id).unwrap(), true)
                .unwrap()
                .status,
            Status::Uploading
        );
        assert_eq!(
            recover(
                &root,
                load(&root, &j.hub, &j.user_id, &j.id).unwrap(),
                false
            )
            .unwrap()
            .status,
            Status::Uncertain
        );
    }
    #[test]
    fn staging_rejects_empty_oversized_and_overrun_chunks() {
        let mut j = Job {
            id: "11111111-1111-4111-8111-111111111111".into(),
            hub: HubInfo {
                name: "Hub".into(),
                ip: Ipv4Addr::LOCALHOST,
                http_port: 8080,
                ws_port: 8081,
            },
            user_id: "22222222-2222-4222-8222-222222222222".into(),
            classroom_id: "33333333-3333-4333-8333-333333333333".into(),
            title: "T".into(),
            file_type: "DOCUMENT".into(),
            file_name: "f".into(),
            file_size: 1,
            topic_id: None,
            assignment_id: None,
            announcement_id: None,
            status: Status::Staging,
            uploaded_bytes: 0,
            material_id: None,
        };
        assert!(!valid_chunk(&[]));
        assert!(!valid_chunk(&vec![0; CHUNK_LIMIT + 1]));
        assert!(valid_chunk(&[1]));
        assert!(can_append(&j, 1));
        j.uploaded_bytes = 1;
        assert!(!can_append(&j, 1));
    }
    #[test]
    fn upload_source_must_be_the_exact_private_file() {
        let root = root();
        let j = job(&root);
        let path = payload(&root, &j).unwrap();
        assert!(payload_matches(&path, 1));
        std::fs::write(&path, b"xx").unwrap();
        assert!(!payload_matches(&path, 1));
    }
    #[test]
    fn request_deadline_covers_a_maximum_size_lan_upload() {
        assert_eq!(LIMIT, 250 * 1024 * 1024);
        assert_eq!(UPLOAD_TIMEOUT_SECS, 15 * 60);
        assert!(UPLOAD_TIMEOUT_SECS > LIMIT / (2 * 1024 * 1024));
    }
    #[test]
    fn succeeded_recovery_removes_only_its_staged_payload() {
        let root = root();
        let mut job = job(&root);
        job.status = Status::Succeeded;
        save(&root, &job).unwrap();
        let source = payload(&root, &job).unwrap();
        assert!(source.exists());
        let recovered = recover(&root, job.clone(), false).unwrap();
        assert_eq!(recovered.status, Status::Succeeded);
        assert!(!source.exists());
        assert!(load(&root, &job.hub, &job.user_id, &job.id).is_ok());
    }
    #[test]
    fn a_job_key_has_only_one_active_request() {
        let root = root();
        let j = job(&root);
        let lease = acquire_lease(&root, &j.hub, &j.user_id, &j.id).unwrap();
        assert!(acquire_lease(&root, &j.hub, &j.user_id, &j.id).is_err());
        let mut active = std::collections::HashMap::new();
        assert!(reserve_task(
            &mut active,
            "account:job".into(),
            Arc::new(AtomicU64::new(0)),
            lease
        ));
        let second = std::fs::File::open(payload(&root, &j).unwrap()).unwrap();
        assert!(!reserve_task(
            &mut active,
            "account:job".into(),
            Arc::new(AtomicU64::new(0)),
            second
        ));
    }
    #[test]
    fn interrupted_staging_is_cancelled_and_partial_owned_file_removed() {
        let root = root();
        let mut j = job(&root);
        j.status = Status::Staging;
        j.uploaded_bytes = 1;
        save(&root, &j).unwrap();
        let path = payload(&root, &j).unwrap();
        assert!(path.exists());
        let recovered = recover(
            &root,
            load(&root, &j.hub, &j.user_id, &j.id).unwrap(),
            false,
        )
        .unwrap();
        assert_eq!(recovered.status, Status::Cancelled);
        assert!(!path.exists());
    }
    #[tokio::test]
    async fn upload_authorization_checks_teacher_co_teacher_archive_and_parent_class() {
        const USER: &str = "22222222-2222-4222-8222-222222222222";
        const OWNER: &str = "44444444-4444-4444-8444-444444444444";
        const CLASS: &str = "33333333-3333-4333-8333-333333333333";
        const OTHER: &str = "55555555-5555-4555-8555-555555555555";
        const TOPIC: &str = "66666666-6666-4666-8666-666666666666";
        const FOREIGN: &str = "77777777-7777-4777-8777-777777777777";
        let root = root();
        let hub = HubInfo {
            name: "Hub".into(),
            ip: Ipv4Addr::LOCALHOST,
            http_port: 8080,
            ws_port: 8081,
        };
        let mut db = account::database(&root, &account::context(&hub, USER))
            .await
            .unwrap();
        let profile = serde_json::json!({"user":{"id":USER,"lrn_or_id":"T1","full_name":"Teacher","role":"TEACHER"},"expires_at":9999999999999i64,"credential_id":"test"});
        account::put(&mut db, "account_profile", &profile.to_string())
            .await
            .unwrap();
        for (id, teacher, co, archived) in [
            (CLASS, OWNER, format!("[\"{USER}\"]"), None::<i64>),
            (OTHER, OWNER, "[]".into(), None),
        ] {
            sqlx::query("INSERT INTO classrooms(id,name,section,class_code,teacher_id,co_teacher_ids_json,archived_at,created_at,updated_at) VALUES(?, 'Class','A','K7M4QX',?,?,?,0,0)").bind(id).bind(teacher).bind(co).bind(archived).execute(&mut db).await.unwrap();
        }
        sqlx::query("INSERT INTO topics(id,classroom_id,name,order_index,created_at,updated_at) VALUES(?,?,'Topic',0,0,0)").bind(TOPIC).bind(CLASS).execute(&mut db).await.unwrap();
        assert!(
            validate_context(&root, &hub, USER, CLASS, Some(TOPIC), None, None)
                .await
                .is_ok()
        );
        assert!(
            validate_context(&root, &hub, USER, CLASS, Some(FOREIGN), None, None)
                .await
                .is_err()
        );
        sqlx::query("UPDATE classrooms SET archived_at=1 WHERE id=?")
            .bind(CLASS)
            .execute(&mut db)
            .await
            .unwrap();
        assert!(validate_context(&root, &hub, USER, CLASS, None, None, None)
            .await
            .is_err());
        drop(db);
        let _ = std::fs::remove_dir_all(root);
    }
}
