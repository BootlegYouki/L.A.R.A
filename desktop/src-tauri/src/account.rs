use crate::discovery::HubInfo;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{sqlite::SqliteConnectOptions, Connection, Row, SqliteConnection};
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::Manager;
use tokio::sync::Mutex;

const ERROR: &str = "account_cache_unavailable";
const INVALID: &str = "invalid_account_data";
#[derive(Clone, Deserialize, Serialize)]
pub struct User {
    pub id: String,
    pub lrn_or_id: String,
    pub full_name: String,
    pub role: Role,
}
#[derive(Clone, Deserialize, Serialize, PartialEq)]
pub enum Role {
    TEACHER,
    STUDENT,
}
#[derive(Deserialize)]
pub struct AuthSession {
    pub token: String,
    pub expires_at: i64,
    pub user: User,
}
#[derive(Clone, Deserialize, Serialize)]
pub struct Classroom {
    pub id: String,
    pub name: String,
    pub section: String,
    pub class_code: String,
    pub teacher_id: String,
    #[serde(default)]
    pub co_teacher_ids: Vec<String>,
    #[serde(default)]
    pub archived_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub enrollment_status: Option<String>,
}
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct JoinReceipt {
    pub classroom_id: String,
    pub status: String,
}
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub struct RosterEntry {
    pub enrollment_id: String,
    pub student_id: String,
    pub full_name: String,
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub joined_at: Option<i64>,
}
#[derive(Serialize)]
pub struct CachedAccount {
    pub hub: HubInfo,
    pub user: User,
    pub expires_at: i64,
    pub persistent: bool,
    pub classrooms: Vec<Classroom>,
    pub join_requests: Vec<JoinReceipt>,
    pub rosters: HashMap<String, Vec<RosterEntry>>,
}
#[derive(Deserialize, Serialize)]
struct Profile {
    user: User,
    expires_at: i64,
    credential_id: String,
}
#[derive(Deserialize, Serialize)]
struct StoredToken {
    value: String,
    credential_id: String,
}
pub(crate) struct Token {
    credential_id: String,
    value: String,
    persistent: bool,
}
#[derive(Default)]
pub struct Accounts(
    pub(crate) Mutex<HashMap<String, Token>>,
    std::sync::Mutex<Option<String>>,
);
impl Accounts {
    fn select(&self, key: Option<String>) -> Result<(), &'static str> {
        *self.1.lock().map_err(|_| ERROR)? = key;
        Ok(())
    }
    pub(crate) fn require_sync_selection(
        &self,
        hub: &HubInfo,
        id: &str,
    ) -> Result<(), &'static str> {
        if self.1.lock().map_err(|_| ERROR)?.as_deref() != Some(context(hub, id).as_str()) {
            return Err("account_context_changed");
        }
        Ok(())
    }
}
fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
pub(crate) fn uuid(s: &str) -> bool {
    uuid::Uuid::parse_str(s)
        .is_ok_and(|v| v.hyphenated().to_string().eq_ignore_ascii_case(s) && !v.is_nil())
}
fn text(s: &str, max: usize) -> bool {
    !s.trim().is_empty() && s.chars().count() <= max && !s.chars().any(char::is_control)
}
pub(crate) fn validate_hub(hub: &HubInfo) -> Result<(), &'static str> {
    if !(hub.ip.is_private() || hub.ip.is_loopback())
        || hub.http_port == 0
        || hub.ws_port == 0
        || !text(&hub.name, 160)
    {
        return Err(INVALID);
    }
    Ok(())
}
fn validate_user(user: &User) -> Result<(), &'static str> {
    if !uuid(&user.id) || !text(&user.full_name, 100) || !text(&user.lrn_or_id, 32) {
        return Err(INVALID);
    }
    Ok(())
}
fn hub_key(h: &HubInfo) -> String {
    format!("{:x}", Sha256::digest(format!("{}:{}", h.ip, h.http_port)))
}
pub(crate) fn context(h: &HubInfo, id: &str) -> String {
    // SHA-256 makes a stable, traversal-safe filename without exposing the account identifier.
    format!(
        "{:x}",
        Sha256::digest(format!("{}:{}", hub_key(h), id.to_ascii_lowercase()))
    )
}
fn credential(key: &str) -> Result<keyring::Entry, keyring::Error> {
    keyring::Entry::new("org.lara.desktop.account", key)
}
fn read_token(key: &str, generation: &str) -> Option<String> {
    let stored: StoredToken =
        serde_json::from_str(&credential(key).ok()?.get_password().ok()?).ok()?;
    (stored.credential_id == generation).then_some(stored.value)
}
fn write_token(key: &str, token: &str) -> bool {
    credential(key).and_then(|e| e.set_password(token)).is_ok()
}
fn delete_token(key: &str) -> Result<(), &'static str> {
    match credential(key).and_then(|e| e.delete_credential()) {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(_) => Err("credential_service_unavailable"),
    }
}
pub(crate) fn root(app: &tauri::AppHandle) -> Result<PathBuf, &'static str> {
    let p = app
        .path()
        .app_data_dir()
        .map_err(|_| ERROR)?
        .join("accounts");
    std::fs::create_dir_all(&p).map_err(|_| ERROR)?;
    Ok(p)
}
fn pointer(root: &Path, hub: &HubInfo) -> PathBuf {
    root.join(format!("active-{}.json", hub_key(hub)))
}
fn active(root: &Path, hub: &HubInfo) -> Result<Option<String>, &'static str> {
    match std::fs::read(pointer(root, hub)) {
        Ok(bytes) => {
            let id: String = serde_json::from_slice(&bytes).map_err(|_| ERROR)?;
            if !uuid(&id) {
                return Err(ERROR);
            }
            Ok(Some(id))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err(ERROR),
    }
}
fn set_active(root: &Path, hub: &HubInfo, id: &str) -> Result<(), &'static str> {
    let path = pointer(root, hub);
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, serde_json::to_vec(id).map_err(|_| ERROR)?).map_err(|_| ERROR)?;
    std::fs::OpenOptions::new()
        .write(true)
        .open(&temporary)
        .and_then(|f| f.sync_all())
        .map_err(|_| ERROR)?;
    std::fs::rename(temporary, path).map_err(|_| ERROR)
}
pub(crate) async fn database(root: &Path, key: &str) -> Result<SqliteConnection, &'static str> {
    let options = SqliteConnectOptions::new()
        .filename(root.join(format!("account-{key}.db")))
        .create_if_missing(true)
        .foreign_keys(true);
    let mut db = SqliteConnection::connect_with(&options)
        .await
        .map_err(|_| ERROR)?;
    // Reuse the exact immutable plugin migrations, including SQLx's checksummed migration ledger.
    let migrations = sqlx::migrate::Migrator {
        migrations: std::borrow::Cow::Owned(vec![
            sqlx::migrate::Migration::new(
                1,
                "client_offline_schema".into(),
                sqlx::migrate::MigrationType::ReversibleUp,
                include_str!("../migrations/001_client_offline.sql").into(),
                false,
            ),
            sqlx::migrate::Migration::new(
                2,
                "sync_rejections".into(),
                sqlx::migrate::MigrationType::ReversibleUp,
                include_str!("../migrations/002_sync_rejections.sql").into(),
                false,
            ),
            sqlx::migrate::Migration::new(
                3,
                "submission_received_at".into(),
                sqlx::migrate::MigrationType::ReversibleUp,
                include_str!("../migrations/003_submission_received_at.sql").into(),
                false,
            ),
        ]),
        ..sqlx::migrate::Migrator::DEFAULT
    };
    migrations.run_direct(&mut db).await.map_err(|_| ERROR)?;
    Ok(db)
}
pub(crate) async fn state(
    db: &mut SqliteConnection,
    key: &str,
) -> Result<Option<String>, &'static str> {
    sqlx::query_scalar("SELECT value FROM sync_state WHERE key=?")
        .bind(key)
        .fetch_optional(db)
        .await
        .map_err(|_| ERROR)
}
pub(crate) async fn put(
    db: &mut SqliteConnection,
    key: &str,
    value: &str,
) -> Result<(), &'static str> {
    sqlx::query("INSERT INTO sync_state(key,value,updated_at) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at").bind(key).bind(value).bind(now()).execute(db).await.map_err(|_|ERROR)?;
    Ok(())
}
async fn profile(db: &mut SqliteConnection, id: &str) -> Result<Profile, &'static str> {
    let p: Profile = serde_json::from_str(&state(db, "account_profile").await?.ok_or(ERROR)?)
        .map_err(|_| ERROR)?;
    validate_user(&p.user)?;
    if p.user.id != id {
        return Err(ERROR);
    }
    Ok(p)
}
fn validate_classes(user: &User, classes: &[Classroom]) -> Result<(), &'static str> {
    if classes.len() > 10000 {
        return Err(INVALID);
    }
    let mut ids = HashSet::new();
    for c in classes {
        if !uuid(&c.id)
            || !ids.insert(&c.id)
            || !uuid(&c.teacher_id)
            || !text(&c.name, usize::MAX)
            || c.section.chars().any(char::is_control)
            || c.class_code.len() != 6
            || !c
                .class_code
                .bytes()
                .all(|b| b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains(&b))
            || c.co_teacher_ids.len() > 100
            || c.co_teacher_ids.iter().any(|id| !uuid(id))
            || c.archived_at.is_some_and(|n| n < 0)
            || c.enrollment_status
                .as_deref()
                .is_some_and(|s| !matches!(s, "ACTIVE" | "PENDING" | "REJECTED" | "REMOVED"))
            || (user.role == Role::TEACHER
                && c.teacher_id != user.id
                && !c.co_teacher_ids.contains(&user.id))
        {
            return Err(INVALID);
        }
    }
    Ok(())
}
fn visible(c: &Classroom, user: &User) -> bool {
    match user.role {
        Role::TEACHER => c.enrollment_status.as_deref().is_none_or(|s| s == "ACTIVE"),
        Role::STUDENT => match c.enrollment_status.as_deref() {
            Some("ACTIVE") => true,
            Some("PENDING") => c.archived_at.is_none(),
            _ => false,
        },
    }
}
pub(crate) async fn classes(
    db: &mut SqliteConnection,
    user: &User,
) -> Result<Vec<Classroom>, &'static str> {
    let ids: Vec<String> = match state(db, "account_visible_classes").await? {
        Some(s) => serde_json::from_str(&s).map_err(|_| ERROR)?,
        None => vec![],
    };
    let statuses: HashMap<String, String> = match state(db, "account_class_statuses").await? {
        Some(s) => serde_json::from_str(&s).map_err(|_| ERROR)?,
        None => HashMap::new(),
    };
    let mut out = Vec::new();
    for id in ids {
        let row=sqlx::query("SELECT id,name,section,class_code,teacher_id,co_teacher_ids_json,archived_at FROM classrooms WHERE id=?").bind(&id).fetch_optional(&mut *db).await.map_err(|_|ERROR)?.ok_or(ERROR)?;
        let c = Classroom {
            id: row.get(0),
            name: row.get(1),
            section: row.get(2),
            class_code: row.get(3),
            teacher_id: row.get(4),
            co_teacher_ids: serde_json::from_str(
                row.get::<Option<String>, _>(5).as_deref().unwrap_or("[]"),
            )
            .map_err(|_| ERROR)?,
            archived_at: row.get(6),
            enrollment_status: if user.role == Role::STUDENT {
                Some(
                    statuses
                        .get(&id)
                        .cloned()
                        .unwrap_or_else(|| "ACTIVE".into()),
                )
            } else {
                None
            },
        };
        validate_classes(user, std::slice::from_ref(&c))?;
        if visible(&c, user) {
            out.push(c);
        }
    }
    Ok(out)
}
async fn snapshot(
    db: &mut SqliteConnection,
    user: &User,
    items: &[Classroom],
) -> Result<Vec<Classroom>, &'static str> {
    validate_classes(user, items)?;
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    for c in items {
        sqlx::query("INSERT INTO classrooms(id,name,section,class_code,teacher_id,co_teacher_ids_json,archived_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,section=excluded.section,class_code=excluded.class_code,teacher_id=excluded.teacher_id,co_teacher_ids_json=excluded.co_teacher_ids_json,archived_at=excluded.archived_at,updated_at=excluded.updated_at")
            .bind(&c.id).bind(&c.name).bind(&c.section).bind(&c.class_code).bind(&c.teacher_id).bind(serde_json::to_string(&c.co_teacher_ids).map_err(|_|ERROR)?).bind(c.archived_at).bind(now()).bind(now()).execute(&mut *tx).await.map_err(|_|ERROR)?;
    }
    let mut receipts = join_requests(&mut tx).await?;
    for c in items {
        if let Some(status) = &c.enrollment_status {
            if let Some(receipt) = receipts.iter_mut().find(|r| r.classroom_id == c.id) {
                receipt.status = status.clone();
            }
        }
    }
    put(
        &mut tx,
        "account_join_requests",
        &serde_json::to_string(&receipts).map_err(|_| ERROR)?,
    )
    .await?;
    let statuses: HashMap<_, _> = items
        .iter()
        .filter_map(|c| c.enrollment_status.as_ref().map(|s| (&c.id, s)))
        .collect();
    put(
        &mut tx,
        "account_class_statuses",
        &serde_json::to_string(&statuses).map_err(|_| ERROR)?,
    )
    .await?;
    let visible: Vec<Classroom> = items.iter().filter(|c| visible(c, user)).cloned().collect();
    put(
        &mut tx,
        "account_visible_classes",
        &serde_json::to_string(&visible.iter().map(|c| &c.id).collect::<Vec<_>>())
            .map_err(|_| ERROR)?,
    )
    .await?;
    tx.commit().await.map_err(|_| ERROR)?;
    Ok(visible)
}
async fn join_requests(db: &mut SqliteConnection) -> Result<Vec<JoinReceipt>, &'static str> {
    match state(db, "account_join_requests").await? {
        Some(s) => serde_json::from_str(&s).map_err(|_| ERROR),
        None => Ok(vec![]),
    }
}
fn validate_receipt(user: &User, receipt: &JoinReceipt) -> Result<(), &'static str> {
    if user.role != Role::STUDENT
        || !uuid(&receipt.classroom_id)
        || !matches!(
            receipt.status.as_str(),
            "PENDING" | "ACTIVE" | "REJECTED" | "REMOVED"
        )
    {
        return Err(INVALID);
    }
    Ok(())
}
async fn save_receipt(
    db: &mut SqliteConnection,
    user: &User,
    receipt: JoinReceipt,
) -> Result<(), &'static str> {
    validate_receipt(user, &receipt)?;
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    let mut receipts = join_requests(&mut tx).await?;
    receipts.retain(|r| r.classroom_id != receipt.classroom_id);
    if receipt.status != "ACTIVE" {
        let mut statuses: HashMap<String, String> =
            match state(&mut tx, "account_class_statuses").await? {
                Some(s) => serde_json::from_str(&s).map_err(|_| ERROR)?,
                None => HashMap::new(),
            };
        statuses.insert(receipt.classroom_id.clone(), receipt.status.clone());
        put(
            &mut tx,
            "account_class_statuses",
            &serde_json::to_string(&statuses).map_err(|_| ERROR)?,
        )
        .await?;
    }
    receipts.push(receipt);
    put(
        &mut tx,
        "account_join_requests",
        &serde_json::to_string(&receipts).map_err(|_| ERROR)?,
    )
    .await?;
    tx.commit().await.map_err(|_| ERROR)
}
#[tauri::command]
pub async fn save_join_status(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    mut receipt: JoinReceipt,
) -> Result<CachedAccount, &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    receipt.classroom_id.make_ascii_lowercase();
    let memory = accounts.0.lock().await;
    let root = root(&app)?;
    if active(&root, &hub)?.as_deref() != Some(&user_id) {
        return Err("account_context_changed");
    }
    let key = context(&hub, &user_id);
    let mut db = database(&root, &key).await?;
    let p = profile(&mut db, &user_id).await?;
    save_receipt(&mut db, &p.user, receipt).await?;
    let persistent = p.expires_at > now()
        && memory
            .get(&key)
            .filter(|t| t.credential_id == p.credential_id)
            .map(|t| t.persistent)
            .unwrap_or_else(|| read_token(&key, &p.credential_id).is_some());
    Ok(CachedAccount {
        classrooms: classes(&mut db, &p.user).await?,
        rosters: get_rosters(&mut db, &p.user).await?,
        join_requests: join_requests(&mut db).await?,
        hub,
        user: p.user,
        expires_at: p.expires_at,
        persistent,
    })
}
#[tauri::command]
pub async fn restore_account_session(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
) -> Result<Option<CachedAccount>, &'static str> {
    validate_hub(&hub)?;
    let memory = accounts.0.lock().await;
    let root = root(&app)?;
    accounts.select(None)?;
    let Some(id) = active(&root, &hub)? else {
        return Ok(None);
    };
    let key = context(&hub, &id);
    accounts.select(Some(key.clone()))?;
    let mut db = database(&root, &key).await?;
    let p = profile(&mut db, &id).await?;
    let persistent = p.expires_at > now()
        && memory
            .get(&key)
            .filter(|t| t.credential_id == p.credential_id)
            .map(|t| t.persistent)
            .unwrap_or_else(|| read_token(&key, &p.credential_id).is_some());
    Ok(Some(CachedAccount {
        classrooms: classes(&mut db, &p.user).await?,
        rosters: get_rosters(&mut db, &p.user).await?,
        join_requests: join_requests(&mut db).await?,
        hub,
        user: p.user,
        expires_at: p.expires_at,
        persistent,
    }))
}
#[tauri::command]
pub async fn save_account_session(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    mut session: AuthSession,
) -> Result<CachedAccount, &'static str> {
    validate_hub(&hub)?;
    validate_user(&session.user)?;
    session.user.id.make_ascii_lowercase();
    if session.expires_at <= now()
        || session.expires_at > 9_007_199_254_740_991
        || !text(&session.token, 8192)
        || session.token.chars().any(char::is_whitespace)
    {
        return Err(INVALID);
    }
    let mut memory = accounts.0.lock().await;
    let root = root(&app)?;
    let key = context(&hub, &session.user.id);
    let mut db = database(&root, &key).await?;
    let generation = uuid::Uuid::new_v4().to_string();
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    sqlx::query("INSERT INTO users(id,lrn_or_id,full_name,role,created_at,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET lrn_or_id=excluded.lrn_or_id,full_name=excluded.full_name,role=excluded.role,updated_at=excluded.updated_at")
        .bind(&session.user.id).bind(&session.user.lrn_or_id).bind(&session.user.full_name).bind(if session.user.role==Role::TEACHER {"TEACHER"} else {"STUDENT"}).bind(now()).bind(now()).execute(&mut *tx).await.map_err(|_|ERROR)?;
    put(
        &mut tx,
        "account_profile",
        &serde_json::to_string(&Profile {
            user: session.user.clone(),
            expires_at: session.expires_at,
            credential_id: generation.clone(),
        })
        .map_err(|_| ERROR)?,
    )
    .await?;
    tx.commit().await.map_err(|_| ERROR)?;
    let classrooms = classes(&mut db, &session.user).await?;
    set_active(&root, &hub, &session.user.id)?;
    accounts.select(Some(key.clone()))?;
    let secret = serde_json::to_string(&StoredToken {
        value: session.token.clone(),
        credential_id: generation.clone(),
    })
    .map_err(|_| ERROR)?;
    let persistent = write_token(&key, &secret);
    memory.insert(
        key,
        Token {
            credential_id: generation,
            value: session.token,
            persistent,
        },
    );
    Ok(CachedAccount {
        hub,
        rosters: get_rosters(&mut db, &session.user).await?,
        user: session.user,
        expires_at: session.expires_at,
        persistent,
        classrooms,
        join_requests: join_requests(&mut db).await?,
    })
}
fn validate_roster(roster: &[RosterEntry]) -> Result<(), &'static str> {
    let mut enrollments = HashSet::new();
    let mut students = HashSet::new();
    for row in roster {
        if !uuid(&row.enrollment_id)
            || !uuid(&row.student_id)
            || !enrollments.insert(row.enrollment_id.to_ascii_lowercase())
            || !students.insert(row.student_id.to_ascii_lowercase())
            || !text(&row.full_name, usize::MAX)
            || !matches!(
                row.status.as_str(),
                "PENDING" | "ACTIVE" | "REJECTED" | "REMOVED"
            )
            || row.joined_at.is_some_and(|n| n < 0)
        {
            return Err(INVALID);
        }
    }
    Ok(())
}
async fn stored_rosters(
    db: &mut SqliteConnection,
) -> Result<HashMap<String, Vec<RosterEntry>>, &'static str> {
    match state(db, "account_rosters").await? {
        Some(raw) => serde_json::from_str(&raw).map_err(|_| ERROR),
        None => Ok(HashMap::new()),
    }
}
async fn get_rosters(
    db: &mut SqliteConnection,
    user: &User,
) -> Result<HashMap<String, Vec<RosterEntry>>, &'static str> {
    if user.role != Role::TEACHER {
        return Ok(HashMap::new());
    }
    let allowed: HashSet<_> = classes(db, user).await?.into_iter().map(|c| c.id).collect();
    let mut rosters = stored_rosters(db).await?;
    rosters.retain(|id, _| allowed.contains(id));
    for rows in rosters.values() {
        validate_roster(rows)?;
    }
    Ok(rosters)
}
pub(crate) fn require_roster_context(
    root: &Path,
    hub: &HubInfo,
    user_id: &str,
) -> Result<(), &'static str> {
    if active(root, hub)?.as_deref() != Some(user_id) {
        return Err("account_context_changed");
    }
    Ok(())
}
async fn authorize_roster(
    db: &mut SqliteConnection,
    user: &User,
    classroom_id: &str,
) -> Result<(), &'static str> {
    if user.role != Role::TEACHER
        || !uuid(classroom_id)
        || !classes(db, user)
            .await?
            .iter()
            .any(|c| c.id == classroom_id)
    {
        return Err(INVALID);
    }
    Ok(())
}
async fn clear_roster(
    db: &mut SqliteConnection,
    user: &User,
    classroom_id: &str,
) -> Result<(), &'static str> {
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    authorize_roster(&mut tx, user, classroom_id).await?;
    let mut all = stored_rosters(&mut tx).await?;
    all.remove(classroom_id);
    put(
        &mut tx,
        "account_rosters",
        &serde_json::to_string(&all).map_err(|_| ERROR)?,
    )
    .await?;
    tx.commit().await.map_err(|_| ERROR)
}
#[tauri::command]
pub async fn clear_roster_snapshot(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
) -> Result<(), &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) || !uuid(&classroom_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    let classroom_id = classroom_id.to_ascii_lowercase();
    let _memory = accounts.0.lock().await;
    let root = root(&app)?;
    require_roster_context(&root, &hub, &user_id)?;
    let mut db = database(&root, &context(&hub, &user_id)).await?;
    let p = profile(&mut db, &user_id).await?;
    clear_roster(&mut db, &p.user, &classroom_id).await
}
async fn roster_snapshot(
    db: &mut SqliteConnection,
    user: &User,
    classroom_id: &str,
    roster: &[RosterEntry],
) -> Result<Vec<RosterEntry>, &'static str> {
    if user.role != Role::TEACHER || !uuid(classroom_id) {
        return Err(INVALID);
    }
    validate_roster(roster)?;
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    authorize_roster(&mut tx, user, classroom_id).await?;
    let mut all = stored_rosters(&mut tx).await?;
    all.insert(classroom_id.into(), roster.to_vec());
    put(
        &mut tx,
        "account_rosters",
        &serde_json::to_string(&all).map_err(|_| ERROR)?,
    )
    .await?;
    tx.commit().await.map_err(|_| ERROR)?;
    Ok(roster.to_vec())
}
#[tauri::command]
pub async fn save_roster_snapshot(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
    mut roster: Vec<RosterEntry>,
) -> Result<Vec<RosterEntry>, &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) || !uuid(&classroom_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    let classroom_id = classroom_id.to_ascii_lowercase();
    for row in &mut roster {
        row.enrollment_id.make_ascii_lowercase();
        row.student_id.make_ascii_lowercase();
    }
    let _memory = accounts.0.lock().await;
    let root = root(&app)?;
    require_roster_context(&root, &hub, &user_id)?;
    let mut db = database(&root, &context(&hub, &user_id)).await?;
    let p = profile(&mut db, &user_id).await?;
    roster_snapshot(&mut db, &p.user, &classroom_id, &roster).await
}
fn token_for(profile: &Profile, memory: &HashMap<String, Token>, key: &str) -> Option<String> {
    if profile.expires_at <= now() {
        return None;
    }
    memory
        .get(key)
        .filter(|t| t.credential_id == profile.credential_id)
        .map(|t| t.value.clone())
        .or_else(|| read_token(key, &profile.credential_id))
}
#[tauri::command]
pub async fn account_token(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
) -> Result<Option<String>, &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    let memory = accounts.0.lock().await;
    let root = root(&app)?;
    if active(&root, &hub)?.as_deref() != Some(&user_id) {
        return Ok(None);
    }
    let key = context(&hub, &user_id);
    let mut db = database(&root, &key).await?;
    let p = profile(&mut db, &user_id).await?;
    Ok(token_for(&p, &memory, &key))
}
#[tauri::command]
pub async fn classroom_teacher_names(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    classroom_id: String,
) -> Result<HashMap<String, String>, &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) || !uuid(&classroom_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    let _memory = accounts.0.lock().await;
    let root = root(&app)?;
    if active(&root, &hub)?.as_deref() != Some(&user_id) {
        return Err("account_context_changed");
    }
    let mut db = database(&root, &context(&hub, &user_id)).await?;
    let p = profile(&mut db, &user_id).await?;
    if p.user.role != Role::TEACHER {
        return Err(INVALID);
    }
    let classroom = classes(&mut db, &p.user)
        .await?
        .into_iter()
        .find(|c| c.id.eq_ignore_ascii_case(&classroom_id))
        .ok_or(INVALID)?;
    let mut names = HashMap::from([(p.user.id.clone(), p.user.full_name)]);
    for id in std::iter::once(classroom.teacher_id).chain(classroom.co_teacher_ids) {
        let name: Option<String> =
            sqlx::query_scalar("SELECT full_name FROM users WHERE id=? AND role='TEACHER'")
                .bind(&id)
                .fetch_optional(&mut db)
                .await
                .map_err(|_| ERROR)?;
        if let Some(name) = name {
            names.insert(id, name);
        }
    }
    Ok(names)
}

#[tauri::command]
pub async fn save_classroom_snapshot(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    mut classrooms: Vec<Classroom>,
) -> Result<Vec<Classroom>, &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    for c in &mut classrooms {
        c.id.make_ascii_lowercase();
        c.teacher_id.make_ascii_lowercase();
        for id in &mut c.co_teacher_ids {
            id.make_ascii_lowercase();
        }
    }
    let _memory = accounts.0.lock().await;
    let root = root(&app)?;
    if active(&root, &hub)?.as_deref() != Some(&user_id) {
        return Err("account_context_changed");
    }
    let mut db = database(&root, &context(&hub, &user_id)).await?;
    let p = profile(&mut db, &user_id).await?;
    snapshot(&mut db, &p.user, &classrooms).await
}
#[tauri::command]
pub async fn forget_account_session(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
) -> Result<(), &'static str> {
    validate_hub(&hub)?;
    if !uuid(&user_id) {
        return Err(INVALID);
    }
    let user_id = user_id.to_ascii_lowercase();
    let mut memory = accounts.0.lock().await;
    let root = root(&app)?;
    let key = context(&hub, &user_id);
    if active(&root, &hub)?.as_deref() == Some(&user_id) {
        std::fs::remove_file(pointer(&root, &hub)).map_err(|_| ERROR)?;
    }
    memory.remove(&key);
    if accounts.require_sync_selection(&hub, &user_id).is_ok() {
        accounts.select(None)?;
    }
    delete_token(&key)
}

#[cfg(test)]
mod tests {
    use super::*;
    const STUDENT: &str = "11111111-1111-4111-8111-111111111111";
    const TEACHER: &str = "22222222-2222-4222-8222-222222222222";
    const CLASS: &str = "33333333-3333-4333-8333-333333333333";
    fn hub() -> HubInfo {
        HubInfo {
            name: "Test Hub".into(),
            ip: "127.0.0.1".parse().unwrap(),
            http_port: 8080,
            ws_port: 8081,
        }
    }
    fn user() -> User {
        User {
            id: STUDENT.into(),
            lrn_or_id: "test-only".into(),
            full_name: "Synthetic Learner".into(),
            role: Role::STUDENT,
        }
    }
    fn class() -> Classroom {
        Classroom {
            id: CLASS.into(),
            name: "Science".into(),
            section: "A".into(),
            class_code: "ABC234".into(),
            teacher_id: TEACHER.into(),
            co_teacher_ids: vec![],
            archived_at: None,
            enrollment_status: Some("ACTIVE".into()),
        }
    }
    fn temporary() -> PathBuf {
        let p = std::env::temp_dir().join(format!("lara-account-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&p).unwrap();
        p
    }
    #[test]
    fn validates_boundary_and_context_identity() {
        let mut h = hub();
        assert!(validate_hub(&h).is_ok());
        let key = context(&h, STUDENT);
        h.name = "Renamed Hub".into();
        h.ws_port = 9999;
        assert_eq!(key, context(&h, STUDENT));
        h.http_port = 8082;
        assert_ne!(key, context(&h, STUDENT));
        assert_ne!(key, context(&hub(), TEACHER));
        h.ip = "8.8.8.8".parse().unwrap();
        assert!(validate_hub(&h).is_err());
        h = hub();
        h.http_port = 0;
        assert!(validate_hub(&h).is_err());
        assert!(uuid("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"));
        assert_eq!(
            context(&hub(), "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            context(&hub(), "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        );
        let mut large = class();
        large.name = "x".repeat(201);
        large.section = String::new();
        assert!(validate_classes(&user(), &[large]).is_ok());
        let mut long_user = user();
        long_user.full_name = "x".repeat(101);
        assert!(validate_user(&long_user).is_err());
        long_user = user();
        long_user.lrn_or_id = "x".repeat(33);
        assert!(validate_user(&long_user).is_err());
        assert!(!uuid("../other.db"));
        assert!(!uuid("00000000-0000-0000-0000-000000000000"));
        assert!(serde_json::from_value::<User>(
            serde_json::json!({"id":STUDENT,"lrn_or_id":"test","full_name":"Test","role":"ADMIN"})
        )
        .is_err());
        let c = class();
        assert!(visible(&c, &user()));
        let mut missing_status = c.clone();
        missing_status.enrollment_status = None;
        assert!(!visible(&missing_status, &user()));
        for status in ["REJECTED", "REMOVED"] {
            let mut c = c.clone();
            c.enrollment_status = Some(status.into());
            assert!(!visible(&c, &user()));
        }
        let mut teacher = user();
        teacher.role = Role::TEACHER;
        assert!(validate_classes(&teacher, &[c]).is_err());
        teacher.id = TEACHER.into();
        assert!(validate_classes(&teacher, std::slice::from_ref(&missing_status)).is_ok());
        assert!(visible(&missing_status, &teacher));
    }
    #[test]
    fn credential_failure_keeps_memory_only_and_expired_tokens_are_unusable() {
        let key = format!("lara-nonexistent-test-{}", uuid::Uuid::new_v4());
        let mut profile = Profile {
            user: user(),
            expires_at: now() + 60000,
            credential_id: "current".into(),
        };
        let mut memory = HashMap::new();
        memory.insert(
            key.clone(),
            Token {
                value: "synthetic-memory-token".into(),
                credential_id: "current".into(),
                persistent: false,
            },
        );
        assert_eq!(
            token_for(&profile, &memory, &key).as_deref(),
            Some("synthetic-memory-token")
        );
        assert!(!memory[&key].persistent);
        profile.credential_id = "new-session".into();
        assert!(token_for(&profile, &memory, &key).is_none());
        profile.credential_id = "current".into();
        profile.expires_at = now() - 1;
        assert!(token_for(&profile, &memory, &key).is_none());
        let wire = serde_json::to_string(&profile).unwrap();
        assert!(!wire.contains("synthetic-memory-token"));
    }
    #[tokio::test]
    async fn archived_class_restores_only_for_an_active_learner() {
        let root = temporary();
        let mut db = database(&root, &context(&hub(), STUDENT)).await.unwrap();
        let mut archived = class();
        archived.archived_at = Some(123);
        snapshot(&mut db, &user(), std::slice::from_ref(&archived))
            .await
            .unwrap();
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0].archived_at,
            Some(123)
        );
        archived.enrollment_status = Some("PENDING".into());
        snapshot(&mut db, &user(), &[archived]).await.unwrap();
        assert!(classes(&mut db, &user()).await.unwrap().is_empty());
        drop(db);
        let _ = std::fs::remove_dir_all(root);
    }
    #[tokio::test]
    async fn snapshot_is_atomic_preserves_queued_parents_and_isolates_contexts() {
        let root = temporary();
        let key = context(&hub(), STUDENT);
        let mut db = database(&root, &key).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, i64>("PRAGMA foreign_keys")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            1
        );
        snapshot(&mut db, &user(), &[class()]).await.unwrap();
        sqlx::raw_sql("INSERT INTO announcements VALUES('announcement','33333333-3333-4333-8333-333333333333','Title','Body',1,0,0); INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('comment','announcement','11111111-1111-4111-8111-111111111111','queued',0,0,'QUEUED_FOR_SYNC');").execute(&mut db).await.unwrap();
        let mut changed = class();
        changed.name = "Changed".into();
        changed.enrollment_status = Some("PENDING".into());
        sqlx::raw_sql("CREATE TRIGGER fail_visibility BEFORE UPDATE ON sync_state WHEN NEW.key='account_visible_classes' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").execute(&mut db).await.unwrap();
        assert!(snapshot(&mut db, &user(), &[changed]).await.is_err());
        assert_eq!(classes(&mut db, &user()).await.unwrap()[0].name, "Science");
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0]
                .enrollment_status
                .as_deref(),
            Some("ACTIVE")
        );
        sqlx::raw_sql("DROP TRIGGER fail_visibility")
            .execute(&mut db)
            .await
            .unwrap();
        snapshot(&mut db, &user(), &[]).await.unwrap();
        assert!(classes(&mut db, &user()).await.unwrap().is_empty());
        assert_eq!(
            sqlx::query_scalar::<_, String>(
                "SELECT sync_status FROM announcement_comments WHERE id='comment'"
            )
            .fetch_one(&mut db)
            .await
            .unwrap(),
            "QUEUED_FOR_SYNC"
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM classrooms")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            1
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM enrollments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            0
        );
        let mut other = database(&root, &context(&hub(), TEACHER)).await.unwrap();
        assert!(classes(&mut other, &user()).await.unwrap().is_empty());
        set_active(&root, &hub(), STUDENT).unwrap();
        assert_eq!(active(&root, &hub()).unwrap().as_deref(), Some(STUDENT));
        set_active(&root, &hub(), TEACHER).unwrap();
        assert_eq!(active(&root, &hub()).unwrap().as_deref(), Some(TEACHER));
        db.close().await.unwrap();
        other.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn legacy_cache_defaults_and_receipt_lock_roll_back_together() {
        let root = temporary();
        let mut db = database(&root, &context(&hub(), STUDENT)).await.unwrap();
        snapshot(&mut db, &user(), &[class()]).await.unwrap();
        sqlx::query("DELETE FROM sync_state WHERE key IN ('account_class_statuses','account_join_requests')").execute(&mut db).await.unwrap();
        assert!(join_requests(&mut db).await.unwrap().is_empty());
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0]
                .enrollment_status
                .as_deref(),
            Some("ACTIVE")
        );
        sqlx::raw_sql("CREATE TRIGGER fail_receipt BEFORE INSERT ON sync_state WHEN NEW.key='account_join_requests' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").execute(&mut db).await.unwrap();
        assert!(save_receipt(
            &mut db,
            &user(),
            JoinReceipt {
                classroom_id: CLASS.into(),
                status: "REMOVED".into()
            }
        )
        .await
        .is_err());
        assert!(join_requests(&mut db).await.unwrap().is_empty());
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0]
                .enrollment_status
                .as_deref(),
            Some("ACTIVE")
        );
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn receipts_lock_access_until_canonical_active_and_survive_omission() {
        let root = temporary();
        let key = context(&hub(), STUDENT);
        let mut db = database(&root, &key).await.unwrap();
        snapshot(&mut db, &user(), &[class()]).await.unwrap();
        sqlx::raw_sql("INSERT INTO announcements VALUES('join-announcement','33333333-3333-4333-8333-333333333333','Title','Body',1,0,0); INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('join-comment','join-announcement','11111111-1111-4111-8111-111111111111','queued',0,0,'QUEUED_FOR_SYNC');").execute(&mut db).await.unwrap();
        for status in ["PENDING", "REJECTED", "REMOVED"] {
            save_receipt(
                &mut db,
                &user(),
                JoinReceipt {
                    classroom_id: CLASS.into(),
                    status: status.into(),
                },
            )
            .await
            .unwrap();
            let cached = classes(&mut db, &user()).await.unwrap();
            assert!(cached
                .iter()
                .all(|c| c.enrollment_status.as_deref() != Some("ACTIVE")));
        }
        save_receipt(
            &mut db,
            &user(),
            JoinReceipt {
                classroom_id: CLASS.into(),
                status: "ACTIVE".into(),
            },
        )
        .await
        .unwrap();
        assert!(classes(&mut db, &user()).await.unwrap().is_empty());
        snapshot(&mut db, &user(), &[class()]).await.unwrap();
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0]
                .enrollment_status
                .as_deref(),
            Some("ACTIVE")
        );
        save_receipt(
            &mut db,
            &user(),
            JoinReceipt {
                classroom_id: CLASS.into(),
                status: "REMOVED".into(),
            },
        )
        .await
        .unwrap();
        snapshot(&mut db, &user(), &[]).await.unwrap();
        db.close().await.unwrap();
        let mut db = database(&root, &key).await.unwrap();
        assert_eq!(join_requests(&mut db).await.unwrap()[0].status, "REMOVED");
        assert!(classes(&mut db, &user()).await.unwrap().is_empty());
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM classrooms")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            1
        );
        assert_eq!(
            sqlx::query_scalar::<_, String>(
                "SELECT sync_status FROM announcement_comments WHERE id='join-comment'"
            )
            .fetch_one(&mut db)
            .await
            .unwrap(),
            "QUEUED_FOR_SYNC"
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM enrollments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            0
        );
        let mut teacher = user();
        teacher.role = Role::TEACHER;
        assert!(save_receipt(
            &mut db,
            &teacher,
            JoinReceipt {
                classroom_id: CLASS.into(),
                status: "ACTIVE".into()
            }
        )
        .await
        .is_err());
        assert!(save_receipt(
            &mut db,
            &user(),
            JoinReceipt {
                classroom_id: "bad".into(),
                status: "ACTIVE".into()
            }
        )
        .await
        .is_err());
        assert!(save_receipt(
            &mut db,
            &user(),
            JoinReceipt {
                classroom_id: CLASS.into(),
                status: "bad".into()
            }
        )
        .await
        .is_err());
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn pending_metadata_survives_restart() {
        let root = temporary();
        let key = context(&hub(), STUDENT);
        let mut db = database(&root, &key).await.unwrap();
        let mut pending = class();
        pending.enrollment_status = Some("PENDING".into());
        assert_eq!(
            snapshot(&mut db, &user(), &[pending]).await.unwrap().len(),
            1
        );
        db.close().await.unwrap();
        let mut db = database(&root, &key).await.unwrap();
        assert_eq!(
            classes(&mut db, &user()).await.unwrap()[0]
                .enrollment_status
                .as_deref(),
            Some("PENDING")
        );
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn roster_cache_is_teacher_only_and_atomic() {
        let root = temporary();
        let key = context(&hub(), TEACHER);
        let mut db = database(&root, &key).await.unwrap();
        let mut teacher = user();
        teacher.id = TEACHER.into();
        teacher.role = Role::TEACHER;
        snapshot(&mut db, &teacher, &[class()]).await.unwrap();
        assert!(get_rosters(&mut db, &teacher).await.unwrap().is_empty());
        assert!(roster_snapshot(&mut db, &user(), CLASS, &[]).await.is_err());
        assert!(roster_snapshot(&mut db, &teacher, STUDENT, &[])
            .await
            .is_err());
        let raw = serde_json::json!({"enrollment_id":"44444444-4444-4444-8444-444444444444","student_id":STUDENT,"full_name":"Name","status":"ACTIVE","lrn":"private","pin_hash":"secret","token":"secret"});
        let entry: RosterEntry = serde_json::from_value(raw).unwrap();
        let wire = serde_json::to_string(&entry).unwrap();
        assert!(!wire.contains("lrn") && !wire.contains("pin") && !wire.contains("token"));
        assert!(
            roster_snapshot(&mut db, &teacher, CLASS, &[entry.clone(), entry.clone()])
                .await
                .is_err()
        );
        let mut invalid = entry.clone();
        invalid.joined_at = Some(-1);
        assert!(roster_snapshot(&mut db, &teacher, CLASS, &[invalid])
            .await
            .is_err());
        let mut invalid = entry.clone();
        invalid.status = "UNKNOWN".into();
        assert!(roster_snapshot(&mut db, &teacher, CLASS, &[invalid])
            .await
            .is_err());
        let mut invalid = entry.clone();
        invalid.full_name = " ".into();
        assert!(roster_snapshot(&mut db, &teacher, CLASS, &[invalid])
            .await
            .is_err());
        let mut long = entry.clone();
        long.full_name = "x".repeat(1000);
        assert!(validate_roster(&[long]).is_ok());
        sqlx::raw_sql("INSERT INTO announcements VALUES('roster-announcement','33333333-3333-4333-8333-333333333333','Title','Body',1,0,0); INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('roster-comment','roster-announcement','11111111-1111-4111-8111-111111111111','queued',0,0,'QUEUED_FOR_SYNC');").execute(&mut db).await.unwrap();
        roster_snapshot(&mut db, &teacher, CLASS, &[entry])
            .await
            .unwrap();
        assert!(get_rosters(&mut db, &user()).await.unwrap().is_empty());
        sqlx::raw_sql("CREATE TRIGGER fail_roster BEFORE UPDATE ON sync_state WHEN NEW.key='account_rosters' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").execute(&mut db).await.unwrap();
        assert!(roster_snapshot(&mut db, &teacher, CLASS, &[])
            .await
            .is_err());
        assert_eq!(
            get_rosters(&mut db, &teacher).await.unwrap()[CLASS].len(),
            1
        );
        sqlx::raw_sql("DROP TRIGGER fail_roster")
            .execute(&mut db)
            .await
            .unwrap();
        snapshot(&mut db, &teacher, &[]).await.unwrap();
        assert!(get_rosters(&mut db, &teacher).await.unwrap().is_empty());
        snapshot(&mut db, &teacher, &[class()]).await.unwrap();
        db.close().await.unwrap();
        let mut db = database(&root, &key).await.unwrap();
        assert_eq!(
            get_rosters(&mut db, &teacher).await.unwrap()[CLASS].len(),
            1
        );
        roster_snapshot(&mut db, &teacher, CLASS, &[])
            .await
            .unwrap();
        assert!(get_rosters(&mut db, &teacher).await.unwrap()[CLASS].is_empty());
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM enrollments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            0
        );
        assert_eq!(
            sqlx::query_scalar::<_, String>(
                "SELECT sync_status FROM announcement_comments WHERE id='roster-comment'"
            )
            .fetch_one(&mut db)
            .await
            .unwrap(),
            "QUEUED_FOR_SYNC"
        );
        assert!(state(&mut db, "cursor").await.unwrap().is_none());
        let mut other = database(&root, &context(&hub(), STUDENT)).await.unwrap();
        assert!(get_rosters(&mut other, &teacher).await.unwrap().is_empty());
        db.close().await.unwrap();
        other.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn clearing_roster_forgets_only_requested_snapshot() {
        let root = temporary();
        let key = context(&hub(), TEACHER);
        let mut db = database(&root, &key).await.unwrap();
        let mut teacher = user();
        teacher.id = TEACHER.into();
        teacher.role = Role::TEACHER;
        let mut other = class();
        other.id = "55555555-5555-4555-8555-555555555555".into();
        snapshot(&mut db, &teacher, &[class(), other.clone()])
            .await
            .unwrap();
        roster_snapshot(&mut db, &teacher, CLASS, &[])
            .await
            .unwrap();
        roster_snapshot(&mut db, &teacher, &other.id, &[])
            .await
            .unwrap();
        sqlx::raw_sql("INSERT INTO announcements VALUES('clear-announcement','33333333-3333-4333-8333-333333333333','Title','Body',1,0,0); INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES('clear-comment','clear-announcement','11111111-1111-4111-8111-111111111111','queued',0,0,'QUEUED_FOR_SYNC');").execute(&mut db).await.unwrap();
        assert!(clear_roster(&mut db, &user(), CLASS).await.is_err());
        assert!(clear_roster(&mut db, &teacher, STUDENT).await.is_err());
        sqlx::raw_sql("CREATE TRIGGER fail_clear BEFORE UPDATE ON sync_state WHEN NEW.key='account_rosters' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").execute(&mut db).await.unwrap();
        assert!(clear_roster(&mut db, &teacher, CLASS).await.is_err());
        assert!(get_rosters(&mut db, &teacher)
            .await
            .unwrap()
            .contains_key(CLASS));
        sqlx::raw_sql("DROP TRIGGER fail_clear")
            .execute(&mut db)
            .await
            .unwrap();
        clear_roster(&mut db, &teacher, CLASS).await.unwrap();
        db.close().await.unwrap();
        let mut db = database(&root, &key).await.unwrap();
        assert!(require_roster_context(&root, &hub(), TEACHER).is_err());
        set_active(&root, &hub(), STUDENT).unwrap();
        assert!(require_roster_context(&root, &hub(), TEACHER).is_err());
        set_active(&root, &hub(), TEACHER).unwrap();
        assert!(require_roster_context(&root, &hub(), TEACHER).is_ok());
        let mut other_hub = hub();
        other_hub.http_port = 8082;
        assert!(require_roster_context(&root, &other_hub, TEACHER).is_err());
        let restored = get_rosters(&mut db, &teacher).await.unwrap();
        assert!(!restored.contains_key(CLASS));
        assert!(restored.contains_key(&other.id));
        assert_eq!(
            sqlx::query_scalar::<_, String>(
                "SELECT sync_status FROM announcement_comments WHERE id='clear-comment'"
            )
            .fetch_one(&mut db)
            .await
            .unwrap(),
            "QUEUED_FOR_SYNC"
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM classrooms")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            2
        );
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "Opt-in: writes then deletes a synthetic OS credential, never real accounts"]
    fn windows_os_credential_round_trip() {
        let key = format!("lara-test-{}", uuid::Uuid::new_v4());
        let secret = serde_json::to_string(&StoredToken {
            value: "synthetic-token".into(),
            credential_id: "generation".into(),
        })
        .unwrap();
        assert!(write_token(&key, &secret));
        let found = read_token(&key, "generation");
        let mismatch = read_token(&key, "other-generation");
        let removed = delete_token(&key);
        assert!(removed.is_ok());
        assert_eq!(found.as_deref(), Some("synthetic-token"));
        assert!(mismatch.is_none());
        assert!(read_token(&key, "generation").is_none());
    }
}

pub(crate) async fn sync_user(db: &mut SqliteConnection, id: &str) -> Result<User, &'static str> {
    Ok(profile(db, id).await?.user)
}

pub(crate) async fn sync_profile(
    db: &mut SqliteConnection,
    user: &User,
) -> Result<(), &'static str> {
    let Some(raw) = state(db, "account_profile").await? else {
        return Ok(());
    };
    let mut p: Profile = serde_json::from_str(&raw).map_err(|_| ERROR)?;
    if p.user.id != user.id {
        return Err(ERROR);
    }
    if let Some(row) = sqlx::query("SELECT full_name,role FROM users WHERE id=?")
        .bind(&user.id)
        .fetch_optional(&mut *db)
        .await
        .map_err(|_| ERROR)?
    {
        p.user.full_name = row.get(0);
        p.user.role = match row.get::<String, _>(1).as_str() {
            "TEACHER" => Role::TEACHER,
            "STUDENT" => Role::STUDENT,
            _ => return Err(INVALID),
        };
        validate_user(&p.user)?;
        put(
            db,
            "account_profile",
            &serde_json::to_string(&p).map_err(|_| ERROR)?,
        )
        .await?;
    }
    Ok(())
}
#[cfg(test)]
mod sync_context_tests {
    use super::*;
    #[test]
    fn switching_hub_or_account_invalidates_the_old_native_sync_context() {
        let accounts = Accounts::default();
        let a = HubInfo {
            name: "A".into(),
            ip: "127.0.0.1".parse().unwrap(),
            http_port: 8080,
            ws_port: 8081,
        };
        let mut b = a.clone();
        b.http_port = 8082;
        let id = "11111111-1111-4111-8111-111111111111";
        let other = "22222222-2222-4222-8222-222222222222";
        assert!(accounts.require_sync_selection(&a, id).is_err());
        accounts.select(Some(context(&a, id))).unwrap();
        assert!(accounts.require_sync_selection(&a, id).is_ok());
        accounts.select(Some(context(&b, id))).unwrap();
        assert!(accounts.require_sync_selection(&a, id).is_err());
        assert!(accounts.require_sync_selection(&b, id).is_ok());
        accounts.select(Some(context(&b, other))).unwrap();
        assert!(accounts.require_sync_selection(&b, id).is_err());
        accounts.select(None).unwrap();
        assert!(accounts.require_sync_selection(&b, other).is_err());
    }
}
