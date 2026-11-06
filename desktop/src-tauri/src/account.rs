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
#[derive(Serialize)]
pub struct CachedAccount {
    pub hub: HubInfo,
    pub user: User,
    pub expires_at: i64,
    pub persistent: bool,
    pub classrooms: Vec<Classroom>,
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
struct Token {
    credential_id: String,
    value: String,
    persistent: bool,
}
#[derive(Default)]
pub struct Accounts(Mutex<HashMap<String, Token>>);
fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
fn uuid(s: &str) -> bool {
    uuid::Uuid::parse_str(s)
        .is_ok_and(|v| v.hyphenated().to_string().eq_ignore_ascii_case(s) && !v.is_nil())
}
fn text(s: &str, max: usize) -> bool {
    !s.trim().is_empty() && s.chars().count() <= max && !s.chars().any(char::is_control)
}
fn validate_hub(hub: &HubInfo) -> Result<(), &'static str> {
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
fn context(h: &HubInfo, id: &str) -> String {
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
fn root(app: &tauri::AppHandle) -> Result<PathBuf, &'static str> {
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
async fn database(root: &Path, key: &str) -> Result<SqliteConnection, &'static str> {
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
        ]),
        ..sqlx::migrate::Migrator::DEFAULT
    };
    migrations.run_direct(&mut db).await.map_err(|_| ERROR)?;
    Ok(db)
}
async fn state(db: &mut SqliteConnection, key: &str) -> Result<Option<String>, &'static str> {
    sqlx::query_scalar("SELECT value FROM sync_state WHERE key=?")
        .bind(key)
        .fetch_optional(db)
        .await
        .map_err(|_| ERROR)
}
async fn put(db: &mut SqliteConnection, key: &str, value: &str) -> Result<(), &'static str> {
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
    c.archived_at.is_none()
        && match user.role {
            Role::TEACHER => c.enrollment_status.as_deref().is_none_or(|s| s == "ACTIVE"),
            Role::STUDENT => c.enrollment_status.as_deref() == Some("ACTIVE"),
        }
}
async fn classes(db: &mut SqliteConnection, user: &User) -> Result<Vec<Classroom>, &'static str> {
    let ids: Vec<String> = match state(db, "account_visible_classes").await? {
        Some(s) => serde_json::from_str(&s).map_err(|_| ERROR)?,
        None => vec![],
    };
    let mut out = Vec::new();
    for id in ids {
        let row=sqlx::query("SELECT id,name,section,class_code,teacher_id,co_teacher_ids_json,archived_at FROM classrooms WHERE id=?").bind(id).fetch_optional(&mut *db).await.map_err(|_|ERROR)?.ok_or(ERROR)?;
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
                Some("ACTIVE".into())
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
#[tauri::command]
pub async fn restore_account_session(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
) -> Result<Option<CachedAccount>, &'static str> {
    validate_hub(&hub)?;
    let memory = accounts.0.lock().await;
    let root = root(&app)?;
    let Some(id) = active(&root, &hub)? else {
        return Ok(None);
    };
    let key = context(&hub, &id);
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
        user: session.user,
        expires_at: session.expires_at,
        persistent,
        classrooms,
    })
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
        for status in ["PENDING", "REJECTED", "REMOVED"] {
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
        sqlx::raw_sql("CREATE TRIGGER fail_visibility BEFORE UPDATE ON sync_state WHEN NEW.key='account_visible_classes' BEGIN SELECT RAISE(ABORT,'synthetic failure'); END;").execute(&mut db).await.unwrap();
        assert!(snapshot(&mut db, &user(), &[changed]).await.is_err());
        assert_eq!(classes(&mut db, &user()).await.unwrap()[0].name, "Science");
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
