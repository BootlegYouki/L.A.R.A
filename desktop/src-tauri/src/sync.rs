//! The selected account owns both the mirror transaction and its ledger cursor.
use crate::{
    account::{self, Accounts, Role, User},
    discovery::HubInfo,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use sqlx::{Connection, Row, SqliteConnection};
use std::collections::{HashMap, HashSet};

const INVALID: &str = "invalid_sync_data";
const ERROR: &str = "sync_cache_unavailable";
const MAX_INTEGER: i64 = 9_007_199_254_740_991;
const LIMIT: usize = 10_000;
#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
pub struct SyncState {
    pub cursor: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hub_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub sync_epoch: Option<i64>,
}
#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq)]
pub struct PushPayload {
    pub quiz_attempts: Vec<Value>,
    pub comments: Vec<Value>,
    pub private_comments: Vec<Value>,
}
#[derive(Clone, Copy)]
struct Entity {
    wire: &'static str,
    table: &'static str,
    // Only these contract fields may cross the persistence boundary. ! means required.
    fields: &'static [(&'static str, &'static str)],
}
const ENTITIES: &[Entity] = &[
    Entity {
        wire: "users",
        table: "users",
        fields: &[
            ("id", "uuid!"),
            ("full_name", "string!"),
            ("role", "TEACHER|STUDENT!"),
        ],
    },
    Entity {
        wire: "classrooms",
        table: "classrooms",
        fields: &[
            ("id", "uuid!"),
            ("name", "string!"),
            ("section", "string!"),
            ("class_code", "code!"),
            ("teacher_id", "uuid!"),
            ("co_teacher_ids", "uuids"),
            ("archived_at", "nullable_int"),
            ("enrollment_status", "PENDING|ACTIVE|REJECTED|REMOVED"),
        ],
    },
    Entity {
        wire: "enrollments",
        table: "enrollments",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("student_id", "uuid!"),
            ("status", "PENDING|ACTIVE|REJECTED|REMOVED!"),
            ("joined_at", "int"),
            ("updated_at", "int!"),
        ],
    },
    Entity {
        wire: "topics",
        table: "topics",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("name", "string!"),
            ("order_index", "int!"),
            ("updated_at", "int!"),
        ],
    },
    Entity {
        wire: "announcements",
        table: "announcements",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("title", "string!"),
            ("content", "string!"),
            ("allow_comments", "bool"),
            ("created_at", "int!"),
            ("updated_at", "int!"),
        ],
    },
    Entity {
        wire: "assignments",
        table: "assignments",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("title", "string!"),
            ("instructions", "string"),
            ("max_points", "int!"),
            ("due_date", "int!"),
            ("allow_late", "bool"),
            ("topic_id", "nullable_uuid"),
            ("updated_at", "int"),
        ],
    },
    Entity {
        wire: "materials",
        table: "materials",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("title", "string!"),
            ("file_type", "VIDEO|DOCUMENT|WORKSHEET!"),
            ("file_size_bytes", "int!"),
            ("download_url", "url"),
            ("topic_id", "nullable_uuid"),
            ("assignment_id", "nullable_uuid"),
            ("announcement_id", "nullable_uuid"),
            ("updated_at", "int!"),
        ],
    },
    Entity {
        wire: "material_chunks",
        table: "material_chunks",
        fields: &[
            ("id", "uuid!"),
            ("material_id", "uuid!"),
            ("order_index", "int!"),
            ("heading", "string"),
            ("text", "string!"),
        ],
    },
    Entity {
        wire: "quizzes",
        table: "quizzes",
        fields: &[
            ("id", "uuid!"),
            ("classroom_id", "uuid!"),
            ("title", "string!"),
            ("status", "DRAFT|ACTIVE|CLOSED!"),
            ("time_limit_minutes", "int!"),
            ("started_at", "nullable_int"),
        ],
    },
    Entity {
        wire: "comments",
        table: "announcement_comments",
        fields: &[
            ("id", "uuid!"),
            ("announcement_id", "uuid!"),
            ("author_id", "uuid!"),
            ("content", "string!"),
            ("created_at", "int!"),
            ("updated_at", "int"),
        ],
    },
    Entity {
        wire: "private_comments",
        table: "private_comments",
        fields: &[
            ("id", "uuid!"),
            ("assignment_id", "uuid!"),
            ("student_id", "uuid!"),
            ("author_id", "uuid!"),
            ("content", "string!"),
            ("created_at", "int!"),
            ("updated_at", "int"),
        ],
    },
    Entity {
        wire: "submissions",
        table: "assignment_submissions",
        fields: &[
            ("id", "uuid!"),
            ("assignment_id", "uuid!"),
            ("student_id", "uuid!"),
            ("file_type", "IMAGE|DOCUMENT!"),
            ("submitted_at", "int!"),
            ("received_at", "int"),
            ("score", "nullable_int"),
            ("teacher_feedback", "nullable_string"),
            ("updated_at", "int"),
        ],
    },
    Entity {
        wire: "quiz_attempts",
        table: "quiz_attempts",
        fields: &[
            ("attempt_id", "uuid!"),
            ("quiz_id", "uuid"),
            ("student_id", "uuid"),
            ("score", "nullable_int!"),
            ("total_points", "int!"),
            ("submitted_at", "int!"),
        ],
    },
];
const TABLES: &[&str] = &[
    "users",
    "classrooms",
    "enrollments",
    "topics",
    "announcements",
    "assignments",
    "materials",
    "material_chunks",
    "quizzes",
    "quiz_questions",
    "announcement_comments",
    "private_comments",
    "assignment_submissions",
    "quiz_attempts",
    "ai_chat_messages",
];
// Children precede their parents when rows are removed.
const LINKS: &[(&str, &str, &str)] = &[
    ("enrollments", "classroom_id", "classrooms"),
    ("topics", "classroom_id", "classrooms"),
    ("announcements", "classroom_id", "classrooms"),
    ("assignments", "classroom_id", "classrooms"),
    ("materials", "classroom_id", "classrooms"),
    ("material_chunks", "material_id", "materials"),
    ("quizzes", "classroom_id", "classrooms"),
    ("quiz_questions", "quiz_id", "quizzes"),
    ("announcement_comments", "announcement_id", "announcements"),
    ("private_comments", "assignment_id", "assignments"),
    ("assignment_submissions", "assignment_id", "assignments"),
    ("quiz_attempts", "quiz_id", "quizzes"),
    ("ai_chat_messages", "classroom_id", "classrooms"),
];
fn integer(v: &Value) -> bool {
    v.as_i64().is_some_and(|n| (0..=MAX_INTEGER).contains(&n))
}
fn valid(v: &Value, kind: &str) -> bool {
    let kind = kind.trim_end_matches('!');
    if let Some(inner) = kind.strip_prefix("nullable_") {
        return v.is_null() || valid(v, inner);
    }
    match kind {
        "uuid" => v.as_str().is_some_and(account::uuid),
        "int" => integer(v),
        "bool" => v.is_boolean(),
        "string" => v.as_str().is_some_and(|s| s.len() <= 1_000_000),
        "code" => v.as_str().is_some_and(|s| {
            s.len() == 6
                && s.bytes()
                    .all(|b| b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789".contains(&b))
        }),
        "uuids" => v
            .as_array()
            .is_some_and(|a| a.len() <= LIMIT && a.iter().all(|x| valid(x, "uuid"))),
        "url" => v.as_str().is_some_and(|s| {
            s.starts_with("/api/materials/")
                && !s.contains(['?', '#', '\\'])
                && s.split('/').all(|p| p != "..")
                && s.len() < 512
        }),
        values => v
            .as_str()
            .is_some_and(|s| values.split('|').any(|k| k == s)),
    }
}
fn fields(value: &Value, spec: &[(&str, &str)]) -> Result<Map<String, Value>, &'static str> {
    let input = value.as_object().ok_or(INVALID)?;
    let mut out = Map::new();
    for &(name, kind) in spec {
        match input.get(name) {
            Some(v) if valid(v, kind) => {
                out.insert(name.into(), v.clone());
            }
            None if !kind.ends_with('!') => (),
            _ => return Err(INVALID),
        }
    }
    Ok(out)
}
fn array<'a>(value: &'a Value, key: &str) -> Result<&'a [Value], &'static str> {
    match value.get(key) {
        None => Ok(&[]),
        Some(Value::Array(a)) if a.len() <= LIMIT => Ok(a),
        _ => Err(INVALID),
    }
}
async fn read_state(db: &mut SqliteConnection) -> Result<SyncState, &'static str> {
    let cursor = account::state(db, "cursor")
        .await?
        .map(|s| s.parse::<i64>().map_err(|_| ERROR))
        .transpose()?
        .unwrap_or(0);
    let sync_epoch = account::state(db, "sync_epoch")
        .await?
        .map(|s| s.parse::<i64>().map_err(|_| ERROR))
        .transpose()?;
    let hub_id = account::state(db, "hub_id").await?;
    if cursor < 0
        || cursor > MAX_INTEGER
        || sync_epoch.is_some_and(|e| !(0..=MAX_INTEGER).contains(&e))
    {
        return Err(ERROR);
    }
    Ok(SyncState {
        cursor,
        hub_id,
        sync_epoch,
    })
}
fn id(row: &Map<String, Value>, key: &str) -> String {
    row[key].as_str().unwrap().to_ascii_lowercase()
}
fn default(row: &mut Map<String, Value>, name: &str, value: Value) {
    row.entry(name).or_insert(value);
}
fn normalize(entity: Entity, mut row: Map<String, Value>, user: &User) -> Map<String, Value> {
    for (name, kind) in entity.fields {
        if kind.trim_end_matches('!').ends_with("uuid")
            && row.get(*name).is_some_and(Value::is_string)
        {
            row.insert((*name).into(), json!(id(&row, name)));
        }
    }
    match entity.table {
        "users" => {
            if id(&row, "id") == user.id {
                row.insert("lrn_or_id".into(), json!(user.lrn_or_id));
            }
            default(&mut row, "created_at", json!(0));
            default(&mut row, "updated_at", json!(0));
        }
        "classrooms" => {
            let co = row.remove("co_teacher_ids").unwrap_or(json!([]));
            row.insert(
                "co_teacher_ids_json".into(),
                json!(co.to_string().to_ascii_lowercase()),
            );
            row.remove("enrollment_status");
            default(&mut row, "archived_at", Value::Null);
            default(&mut row, "created_at", json!(0));
            default(&mut row, "updated_at", json!(0));
        }
        "enrollments" => default(&mut row, "joined_at", json!(0)),
        "announcements" => default(&mut row, "allow_comments", json!(true)),
        "assignments" => {
            default(&mut row, "instructions", json!(""));
            default(&mut row, "allow_late", json!(false));
            default(&mut row, "created_at", json!(0));
            default(&mut row, "updated_at", json!(0));
            default(&mut row, "topic_id", Value::Null);
        }
        "materials" => {
            default(&mut row, "created_at", json!(0));
            for k in [
                "topic_id",
                "assignment_id",
                "announcement_id",
                "download_url",
            ] {
                default(&mut row, k, Value::Null);
            }
        }
        "material_chunks" => {
            default(&mut row, "updated_at", json!(0));
            default(&mut row, "heading", Value::Null);
        }
        "quizzes" => {
            default(&mut row, "started_at", Value::Null);
            default(&mut row, "created_at", json!(0));
            default(&mut row, "updated_at", json!(0));
        }
        "assignment_submissions" => {
            row.insert("sync_status".into(), json!("SYNCED"));
            row.insert("file_path".into(), json!(""));
            default(&mut row, "score", Value::Null);
            default(&mut row, "teacher_feedback", Value::Null);
            default(&mut row, "updated_at", json!(0));
        }
        "announcement_comments" | "private_comments" => {
            let created = row["created_at"].clone();
            default(&mut row, "updated_at", created);
        }
        "topics" => default(&mut row, "created_at", json!(0)),
        _ => (),
    }
    row
}
async fn upsert(
    db: &mut SqliteConnection,
    table: &str,
    row: &Map<String, Value>,
) -> Result<(), &'static str> {
    let columns: Vec<_> = row.keys().map(String::as_str).collect();
    let updates = columns
        .iter()
        .filter(|&&c| {
            c != "id"
                && c != "file_path"
                && c != "created_at"
                && !(table == "users" && c == "lrn_or_id")
        })
        .map(|c| format!("{c}=excluded.{c}"))
        .collect::<Vec<_>>()
        .join(",");
    // All identifiers originate in ENTITIES/normalize; only values originate on the wire.
    let sql = format!(
        "INSERT INTO {table}({}) VALUES({}) ON CONFLICT(id) DO UPDATE SET {updates}",
        columns.join(","),
        vec!["?"; columns.len()].join(",")
    );
    let mut query = sqlx::query(&sql);
    for c in columns {
        query = match &row[c] {
            Value::Null => query.bind(None::<String>),
            Value::String(s) => query.bind(s),
            Value::Number(n) => query.bind(n.as_i64().ok_or(INVALID)?),
            Value::Bool(b) => query.bind(*b),
            _ => return Err(INVALID),
        };
    }
    query.execute(db).await.map_err(|_| ERROR)?;
    Ok(())
}
type Protected = HashMap<&'static str, HashSet<String>>;
async fn protected(db: &mut SqliteConnection, user: &User) -> Result<Protected, &'static str> {
    let mut keep: Protected = TABLES.iter().map(|&t| (t, HashSet::new())).collect();
    keep.get_mut("users").unwrap().insert(user.id.clone());
    for &table in TABLES {
        let condition = match table {
            "quiz_attempts" => "sync_status!='SYNCED' OR status='IN_PROGRESS'",
            "announcement_comments" | "private_comments" | "assignment_submissions" => {
                "sync_status!='SYNCED'"
            }
            "materials" => "local_file_path IS NOT NULL",
            "quiz_questions" => {
                "quiz_id IN (SELECT quiz_id FROM quiz_attempts WHERE status='IN_PROGRESS')"
            }
            "ai_chat_messages" => "1=1",
            _ => continue,
        };
        let ids: Vec<String> =
            sqlx::query_scalar(&format!("SELECT id FROM {table} WHERE {condition}"))
                .fetch_all(&mut *db)
                .await
                .map_err(|_| ERROR)?;
        keep.get_mut(table).unwrap().extend(ids);
    }
    // Traverse twice: a queued child can need an assignment/material and then its class.
    for _ in 0..3 {
        for &(child, column, parent) in LINKS {
            let rows = sqlx::query(&format!("SELECT id,{column} FROM {child}"))
                .fetch_all(&mut *db)
                .await
                .map_err(|_| ERROR)?;
            let parents: Vec<String> = rows
                .iter()
                .filter(|r| keep[child].contains(&r.get::<String, _>(0)))
                .map(|r| r.get(1))
                .collect();
            keep.get_mut(parent).unwrap().extend(parents);
        }
    }
    Ok(keep)
}
async fn remove_rows(
    db: &mut SqliteConnection,
    keep: &Protected,
    targets: &Protected,
) -> Result<(), &'static str> {
    for &table in TABLES.iter().rev() {
        if let Some(ids) = targets.get(table) {
            for id in ids {
                if !keep[table].contains(id) {
                    sqlx::query(&format!("DELETE FROM {table} WHERE id=?"))
                        .bind(id)
                        .execute(&mut *db)
                        .await
                        .map_err(|_| ERROR)?;
                }
            }
        }
    }
    Ok(())
}
async fn wipe(db: &mut SqliteConnection, keep: &Protected) -> Result<(), &'static str> {
    let mut targets = Protected::new();
    for &table in TABLES {
        let ids: Vec<String> = sqlx::query_scalar(&format!("SELECT id FROM {table}"))
            .fetch_all(&mut *db)
            .await
            .map_err(|_| ERROR)?;
        targets.insert(table, ids.into_iter().collect());
    }
    remove_rows(db, keep, &targets).await
}
async fn tombstones(
    db: &mut SqliteConnection,
    keep: &Protected,
    deleted: &[Value],
) -> Result<HashSet<String>, &'static str> {
    let mut targets: Protected = TABLES.iter().map(|&t| (t, HashSet::new())).collect();
    for d in deleted {
        let table = d["entity_table"].as_str().ok_or(INVALID)?;
        let canonical = *TABLES.iter().find(|&&t| t == table).ok_or(INVALID)?;
        targets
            .get_mut(canonical)
            .unwrap()
            .insert(d["entity_id"].as_str().ok_or(INVALID)?.to_ascii_lowercase());
    }
    // Expand cascades explicitly so retained parents never keep unrelated deleted children visible.
    for _ in 0..3 {
        for &(child, column, parent) in LINKS {
            let rows = sqlx::query(&format!("SELECT id,{column} FROM {child}"))
                .fetch_all(&mut *db)
                .await
                .map_err(|_| ERROR)?;
            let ids: Vec<String> = rows
                .iter()
                .filter(|r| targets[parent].contains(&r.get::<String, _>(1)))
                .map(|r| r.get(0))
                .collect();
            targets.get_mut(child).unwrap().extend(ids);
        }
    }
    remove_rows(db, keep, &targets).await?;
    Ok(targets["classrooms"].clone())
}
async fn json_state<T: serde::de::DeserializeOwned + Default>(
    db: &mut SqliteConnection,
    key: &str,
) -> Result<T, &'static str> {
    account::state(db, key)
        .await?
        .map(|s| serde_json::from_str(&s).map_err(|_| ERROR))
        .transpose()
        .map(Option::unwrap_or_default)
}
async fn metadata(
    db: &mut SqliteConnection,
    user: &User,
    records: &[(Entity, Vec<Map<String, Value>>)],
    reset: bool,
    removed: &HashSet<String>,
    deleted: &[Value],
) -> Result<(), &'static str> {
    let mut visible: HashSet<String> = if reset {
        HashSet::new()
    } else {
        json_state::<Vec<String>>(db, "account_visible_classes")
            .await?
            .into_iter()
            .collect()
    };
    let mut statuses: HashMap<String, String> = if reset {
        HashMap::new()
    } else {
        json_state(db, "account_class_statuses").await?
    };
    let mut requests: Vec<account::JoinReceipt> = json_state(db, "account_join_requests").await?;
    for (entity, rows) in records {
        for row in rows {
            match entity.table {
                "classrooms" => {
                    let cid = id(row, "id");
                    if user.role == Role::TEACHER {
                        let own = id(row, "teacher_id") == user.id
                            || row
                                .get("co_teacher_ids")
                                .and_then(Value::as_array)
                                .is_some_and(|a| {
                                    a.iter().any(|i| {
                                        i.as_str().is_some_and(|s| s.eq_ignore_ascii_case(&user.id))
                                    })
                                });
                        if own {
                            visible.insert(cid);
                        } else {
                            visible.remove(&cid);
                        }
                    } else if let Some(status) =
                        row.get("enrollment_status").and_then(Value::as_str)
                    {
                        statuses.insert(cid.clone(), status.into());
                        if status == "ACTIVE"
                            || (status == "PENDING"
                                && row.get("archived_at").is_none_or(Value::is_null))
                        {
                            visible.insert(cid);
                        } else {
                            visible.remove(&cid);
                        }
                    }
                }
                "enrollments" if id(row, "student_id") == user.id => {
                    let cid = id(row, "classroom_id");
                    let status = row["status"].as_str().unwrap();
                    statuses.insert(cid.clone(), status.into());
                    if matches!(status, "ACTIVE" | "PENDING") {
                        visible.insert(cid.clone());
                    } else {
                        visible.remove(&cid);
                    }
                    if let Some(r) = requests.iter_mut().find(|r| r.classroom_id == cid) {
                        r.status = status.into();
                    }
                }
                _ => (),
            }
        }
    }
    for cid in removed {
        visible.remove(cid);
        statuses.remove(cid);
    }
    // Retained FK parents must not restore a revoked learner/co-teacher's access.
    let rows = sqlx::query("SELECT id,archived_at FROM classrooms")
        .fetch_all(&mut *db)
        .await
        .map_err(|_| ERROR)?;
    let present: HashSet<String> = rows
        .into_iter()
        .filter_map(|row| {
            let cid: String = row.get(0);
            (user.role == Role::TEACHER
                || row.get::<Option<i64>, _>(1).is_none()
                || statuses.get(&cid).is_some_and(|status| status == "ACTIVE"))
            .then_some(cid)
        })
        .collect();
    visible.retain(|cid| present.contains(cid));
    account::put(db, "account_visible_classes", &json!(visible).to_string()).await?;
    account::put(db, "account_class_statuses", &json!(statuses).to_string()).await?;
    for (cid, status) in &statuses {
        if let Some(r) = requests.iter_mut().find(|r| r.classroom_id == *cid) {
            r.status = status.clone();
        } else {
            requests.push(account::JoinReceipt {
                classroom_id: cid.clone(),
                status: status.clone(),
            });
        }
    }
    account::put(db, "account_join_requests", &json!(requests).to_string()).await?;
    let mut rosters: HashMap<String, Vec<account::RosterEntry>> = if reset {
        HashMap::new()
    } else {
        json_state(db, "account_rosters").await?
    };
    rosters.retain(|cid, _| user.role == Role::TEACHER && visible.contains(cid));
    for list in rosters.values_mut() {
        list.retain(|r| {
            !deleted.iter().any(|d| {
                d["entity_table"] == "enrollments"
                    && d["entity_id"].as_str() == Some(&r.enrollment_id)
            })
        });
    }
    if user.role == Role::TEACHER {
        let rows=sqlx::query("SELECT e.id,e.classroom_id,e.student_id,e.status,e.joined_at,u.full_name FROM enrollments e LEFT JOIN users u ON u.id=e.student_id").fetch_all(&mut *db).await.map_err(|_|ERROR)?;
        for row in rows {
            let cid: String = row.get(1);
            if !visible.contains(&cid) {
                continue;
            }
            let enrollment_id: String = row.get(0);
            let student_id: String = row.get(2);
            let list = rosters.entry(cid).or_default();
            let name: Option<String> = row.get::<Option<String>, _>(5).or_else(|| {
                list.iter()
                    .find(|r| r.student_id == student_id)
                    .map(|r| r.full_name.clone())
            });
            if let Some(full_name) = name {
                let entry = account::RosterEntry {
                    enrollment_id,
                    student_id,
                    full_name,
                    status: row.get(3),
                    joined_at: Some(row.get(4)),
                };
                list.retain(|r| r.student_id != entry.student_id);
                list.push(entry);
            }
        }
    }
    account::put(db, "account_rosters", &json!(rosters).to_string()).await?;
    Ok(())
}
async fn grade(
    db: &mut SqliteConnection,
    user: &User,
    row: &Map<String, Value>,
) -> Result<(), &'static str> {
    if user.role == Role::STUDENT
        && row
            .get("student_id")
            .and_then(Value::as_str)
            .is_some_and(|id| !id.eq_ignore_ascii_case(&user.id))
    {
        return Err(INVALID);
    }
    let existing = sqlx::query("SELECT quiz_id,student_id,status FROM quiz_attempts WHERE id=?")
        .bind(id(row, "attempt_id"))
        .fetch_optional(&mut *db)
        .await
        .map_err(|_| ERROR)?;
    let Some(old) = existing else {
        return Ok(());
    };
    for (column, index) in [("quiz_id", 0), ("student_id", 1)] {
        if row
            .get(column)
            .and_then(Value::as_str)
            .is_some_and(|id| !id.eq_ignore_ascii_case(&old.get::<String, _>(index)))
        {
            return Err(INVALID);
        }
    }
    if old.get::<String, _>(2) == "IN_PROGRESS" {
        return Ok(());
    }
    sqlx::query(
        "UPDATE quiz_attempts SET score=?,total_points=?,submitted_at=?,updated_at=? WHERE id=?",
    )
    .bind(row["score"].as_i64())
    .bind(row["total_points"].as_i64().ok_or(INVALID)?)
    .bind(row["submitted_at"].as_i64().ok_or(INVALID)?)
    .bind(row["submitted_at"].as_i64().ok_or(INVALID)?)
    .bind(id(row, "attempt_id"))
    .execute(db)
    .await
    .map_err(|_| ERROR)?;
    Ok(())
}
async fn apply(
    db: &mut SqliteConnection,
    user: &User,
    expected: &SyncState,
    payload: Value,
) -> Result<(), &'static str> {
    if !payload.is_object()
        || !integer(&payload["server_time"])
        || !integer(&payload["sync_epoch"])
        || !integer(&payload["next_cursor"])
        || !payload["has_more"].is_boolean()
        || !payload["reset"].is_boolean()
        || !payload["hub_id"]
            .as_str()
            .is_some_and(|s| !s.is_empty() && s.len() <= 256)
    {
        return Err(INVALID);
    }
    let reset = payload["reset"].as_bool().unwrap();
    let cursor = payload["next_cursor"].as_i64().unwrap();
    if !reset
        && (cursor < expected.cursor
            || expected
                .hub_id
                .as_ref()
                .is_some_and(|h| Some(h.as_str()) != payload["hub_id"].as_str())
            || expected
                .sync_epoch
                .is_some_and(|e| Some(e) != payload["sync_epoch"].as_i64()))
    {
        return Err(INVALID);
    }
    if payload["has_more"] == true && !reset && cursor == expected.cursor {
        return Err(INVALID);
    }
    let mut records = Vec::new();
    for &entity in ENTITIES {
        let mut rows = Vec::new();
        let mut ids = HashSet::new();
        for raw in array(&payload, entity.wire)? {
            let row = fields(raw, entity.fields)?;
            let key = if entity.table == "quiz_attempts" {
                "attempt_id"
            } else {
                "id"
            };
            if !ids.insert(id(&row, key)) {
                return Err(INVALID);
            }
            rows.push(row);
        }
        records.push((entity, rows));
    }
    let deleted = array(&payload, "deleted")?;
    for d in deleted {
        let map = fields(d, &[("entity_table", "string!"), ("entity_id", "uuid!")])?;
        if !TABLES.contains(&map["entity_table"].as_str().unwrap()) {
            return Err(INVALID);
        }
    }
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    if read_state(&mut tx).await? != *expected {
        return Err("sync_state_changed");
    }
    let keep = protected(&mut tx, user).await?;
    if reset {
        wipe(&mut tx, &keep).await?;
    }
    for (entity, rows) in &records {
        for row in rows {
            if entity.table == "quiz_attempts" {
                continue;
            }
            if user.role == Role::STUDENT
                && matches!(entity.table, "private_comments" | "assignment_submissions")
                && id(row, "student_id") != user.id
            {
                return Err(INVALID);
            }
            if matches!(
                entity.table,
                "announcement_comments" | "private_comments" | "assignment_submissions"
            ) {
                let queued: Option<String> = sqlx::query_scalar(&format!(
                    "SELECT sync_status FROM {} WHERE id=?",
                    entity.table
                ))
                .bind(id(row, "id"))
                .fetch_optional(&mut *tx)
                .await
                .map_err(|_| ERROR)?;
                if queued.as_deref().is_some_and(|s| s != "SYNCED") {
                    continue;
                }
            }
            upsert(
                &mut tx,
                entity.table,
                &normalize(*entity, row.clone(), user),
            )
            .await?;
        }
    }
    let mut grades: HashMap<String, Value> = if reset {
        HashMap::new()
    } else {
        json_state(&mut tx, "desktop:grade_receipts").await?
    };
    for (entity, rows) in &records {
        if entity.table == "quiz_attempts" {
            for row in rows {
                grades.insert(id(row, "attempt_id"), Value::Object(row.clone()));
            }
        }
    }
    for receipt in grades.values() {
        grade(&mut tx, user, receipt.as_object().ok_or(ERROR)?).await?;
    }
    let removed = tombstones(&mut tx, &keep, deleted).await?;
    metadata(&mut tx, user, &records, reset, &removed, deleted).await?;
    let visible: HashSet<String> = json_state::<Vec<String>>(&mut tx, "account_visible_classes")
        .await?
        .into_iter()
        .collect();
    let statuses: HashMap<String, String> = json_state(&mut tx, "account_class_statuses").await?;
    for (entity, rows) in &records {
        for row in rows {
            if entity.table == "enrollments"
                && user.role == Role::STUDENT
                && id(row, "student_id") != user.id
            {
                return Err(INVALID);
            }
            let class = if let Some(cid) = row.get("classroom_id").and_then(Value::as_str) {
                Some(cid.to_ascii_lowercase())
            } else {
                let parent = match entity.table {
                    "announcement_comments" => Some(("announcements", "announcement_id")),
                    "private_comments" | "assignment_submissions" => {
                        Some(("assignments", "assignment_id"))
                    }
                    "material_chunks" => Some(("materials", "material_id")),
                    "quiz_attempts" => Some(("quizzes", "quiz_id")),
                    _ => None,
                };
                if let Some((table, column)) = parent {
                    let parent_id = if entity.table == "quiz_attempts" {
                        row.get("quiz_id")
                            .and_then(Value::as_str)
                            .map(str::to_owned)
                            .or(sqlx::query_scalar::<_, String>(
                                "SELECT quiz_id FROM quiz_attempts WHERE id=?",
                            )
                            .bind(id(row, "attempt_id"))
                            .fetch_optional(&mut *tx)
                            .await
                            .map_err(|_| ERROR)?)
                    } else {
                        Some(id(row, column))
                    };
                    let Some(parent_id) = parent_id else { continue };
                    Some(
                        sqlx::query_scalar::<_, String>(&format!(
                            "SELECT classroom_id FROM {table} WHERE id=?"
                        ))
                        .bind(parent_id)
                        .fetch_optional(&mut *tx)
                        .await
                        .map_err(|_| ERROR)?
                        .ok_or(INVALID)?,
                    )
                } else {
                    None
                }
            };
            if !matches!(entity.table, "classrooms" | "enrollments")
                && class.is_some_and(|c| {
                    !visible.contains(&c)
                        || (user.role == Role::STUDENT
                            && statuses.get(&c).is_none_or(|s| s != "ACTIVE"))
                })
            {
                return Err(INVALID);
            }
        }
    }
    let mut revoked = Vec::new();
    for (entity, rows) in &records {
        for row in rows {
            let cid = match entity.table {
                "classrooms" => Some(id(row, "id")),
                "enrollments" if id(row, "student_id") == user.id => Some(id(row, "classroom_id")),
                _ => None,
            };
            if let Some(cid) = cid {
                if !visible.contains(&cid) {
                    revoked.push(json!({"entity_table":"classrooms","entity_id":cid}));
                }
            }
        }
    }
    let quiz_rows = sqlx::query("SELECT id,classroom_id FROM quizzes")
        .fetch_all(&mut *tx)
        .await
        .map_err(|_| ERROR)?;
    let allowed_quizzes: HashSet<String> = quiz_rows
        .iter()
        .filter(|r| visible.contains(&r.get::<String, _>(1)))
        .map(|r| r.get(0))
        .collect();
    grades.retain(|aid, g| {
        !deleted
            .iter()
            .any(|d| d["entity_table"] == "quiz_attempts" && d["entity_id"].as_str() == Some(aid))
            && g.get("quiz_id")
                .and_then(Value::as_str)
                .is_none_or(|qid| allowed_quizzes.contains(&qid.to_ascii_lowercase()))
    });
    account::put(
        &mut tx,
        "desktop:grade_receipts",
        &json!(grades).to_string(),
    )
    .await?;
    tombstones(&mut tx, &keep, &revoked).await?;
    account::put(&mut tx, "cursor", &cursor.to_string()).await?;
    account::put(&mut tx, "hub_id", payload["hub_id"].as_str().unwrap()).await?;
    account::put(&mut tx, "sync_epoch", &payload["sync_epoch"].to_string()).await?;
    account::put(&mut tx, "current_user_id", &user.id).await?;
    account::sync_profile(&mut tx, user).await?;
    tx.commit().await.map_err(|_| ERROR)
}
const COMMENT_PUSH: &[(&str, &str)] = &[
    ("id", "uuid!"),
    ("announcement_id", "uuid!"),
    ("content", "string!"),
    ("created_at", "int!"),
];
const PRIVATE_PUSH: &[(&str, &str)] = &[
    ("id", "uuid!"),
    ("assignment_id", "uuid!"),
    ("content", "string!"),
    ("created_at", "int!"),
];
const QUIZ_PUSH: &[(&str, &str)] = &[
    ("id", "uuid!"),
    ("quiz_id", "uuid!"),
    ("started_at", "int!"),
    ("submitted_at", "int!"),
];
fn push_item(raw: &Value, quiz: bool, private: bool) -> Result<Value, &'static str> {
    let spec = if quiz {
        QUIZ_PUSH
    } else if private {
        PRIVATE_PUSH
    } else {
        COMMENT_PUSH
    };
    let mut value = fields(raw, spec)?;
    let allowed = spec.len() + usize::from(quiz);
    if raw.as_object().ok_or(INVALID)?.len() != allowed {
        return Err(INVALID);
    }
    if quiz {
        let answers = raw
            .get("answers")
            .and_then(Value::as_array)
            .filter(|a| a.len() <= LIMIT)
            .ok_or(INVALID)?;
        let mut clean = Vec::new();
        let mut seen = HashSet::new();
        for answer in answers {
            let a = fields(
                answer,
                &[("question_id", "uuid!"), ("selected_option", "string!")],
            )?;
            if answer.as_object().unwrap().len() != 2 || !seen.insert(id(&a, "question_id")) {
                return Err(INVALID);
            }
            clean.push(Value::Object(a));
        }
        value.insert("answers".into(), json!(clean));
        if value["submitted_at"].as_i64() < value["started_at"].as_i64() {
            return Err(INVALID);
        }
    }
    Ok(Value::Object(value))
}
fn validate_push(sent: &PushPayload) -> Result<(), &'static str> {
    let mut seen = HashSet::new();
    for (items, quiz, private) in [
        (&sent.quiz_attempts, true, false),
        (&sent.comments, false, false),
        (&sent.private_comments, false, true),
    ] {
        if items.len() > 100 {
            return Err(INVALID);
        }
        for item in items {
            push_item(item, quiz, private)?;
            if !seen.insert(item["id"].as_str().unwrap()) {
                return Err(INVALID);
            }
        }
    }
    Ok(())
}
async fn queue(db: &mut SqliteConnection, user: &User) -> Result<PushPayload, &'static str> {
    let mut out = PushPayload::default();
    for (table, parent, owner, quiz, private) in [
        ("quiz_attempts", "quiz_id", "student_id", true, false),
        (
            "announcement_comments",
            "announcement_id",
            "author_id",
            false,
            false,
        ),
        (
            "private_comments",
            "assignment_id",
            "author_id",
            false,
            true,
        ),
    ] {
        if (quiz || private) && user.role != Role::STUDENT {
            continue;
        }
        let extra = if quiz {
            " AND status='SUBMITTED'"
        } else if private {
            " AND student_id=?"
        } else {
            ""
        };
        let sql=format!("SELECT * FROM {table} WHERE sync_status='QUEUED_FOR_SYNC' AND {owner}=?{extra} ORDER BY id LIMIT 100");
        let mut q = sqlx::query(&sql).bind(&user.id);
        if private {
            q = q.bind(&user.id)
        }
        let rows = q.fetch_all(&mut *db).await.map_err(|_| ERROR)?;
        for row in rows {
            let mut value =
                json!({"id":row.get::<String,_>("id"),(parent):row.get::<String,_>(parent)});
            if quiz {
                value["started_at"] = json!(row.get::<i64, _>("started_at"));
                value["submitted_at"] = json!(row.get::<i64, _>("submitted_at"));
                value["answers"] = serde_json::from_str(&row.get::<String, _>("answers_json"))
                    .map_err(|_| INVALID)?;
            } else {
                value["content"] = json!(row.get::<String, _>("content"));
                value["created_at"] = json!(row.get::<i64, _>("created_at"));
            }
            let value = push_item(&value, quiz, private)?;
            if quiz {
                out.quiz_attempts.push(value)
            } else if private {
                out.private_comments.push(value)
            } else {
                out.comments.push(value)
            }
        }
    }
    validate_push(&out)?;
    Ok(out)
}
async fn receipts(
    db: &mut SqliteConnection,
    user: &User,
    sent: &PushPayload,
    payload: Value,
) -> Result<(), &'static str> {
    validate_push(sent)?;
    if payload["acknowledged"] != true || !payload["receipts"].is_array() {
        return Err(INVALID);
    }
    let mut seen = HashSet::new();
    let mut entries = Vec::new();
    for raw in array(&payload, "receipts")? {
        let row = fields(
            raw,
            &[
                ("id", "uuid!"),
                ("status", "SYNCED|REJECTED!"),
                ("reason", "string"),
            ],
        )?;
        let rid = id(&row, "id");
        if !seen.insert(rid.clone()) {
            return Err(INVALID);
        }
        let status = row["status"].as_str().unwrap();
        if status == "REJECTED"
            && !row
                .get("reason")
                .and_then(Value::as_str)
                .is_some_and(|s| !s.trim().is_empty())
        {
            return Err(INVALID);
        }
        let mut found = None;
        for (table, items) in [
            ("quiz_attempts", &sent.quiz_attempts),
            ("announcement_comments", &sent.comments),
            ("private_comments", &sent.private_comments),
        ] {
            if let Some(item) = items.iter().find(|i| i["id"].as_str() == Some(&rid)) {
                found = Some((table, item.clone()));
                break;
            }
        }
        let (table, item) = found.ok_or(INVALID)?;
        entries.push((
            table,
            item,
            status.to_owned(),
            row.get("reason").and_then(Value::as_str).map(str::to_owned),
        ));
    }
    let mut tx = db.begin().await.map_err(|_| ERROR)?;
    let current = queue(&mut tx, user).await?;
    for (table, item, status, reason) in entries {
        let items = match table {
            "quiz_attempts" => &current.quiz_attempts,
            "announcement_comments" => &current.comments,
            _ => &current.private_comments,
        };
        if !items.contains(&item) {
            continue;
        }
        sqlx::query(&format!("UPDATE {table} SET sync_status=?,sync_reject_reason=? WHERE id=? AND sync_status='QUEUED_FOR_SYNC'")) .bind(&status).bind(if status=="REJECTED"{reason}else{None}).bind(item["id"].as_str().unwrap()).execute(&mut *tx).await.map_err(|_|ERROR)?;
    }
    tx.commit().await.map_err(|_| ERROR)
}
async fn context(
    app: &tauri::AppHandle,
    hub: &HubInfo,
    user_id: &str,
) -> Result<(SqliteConnection, User), &'static str> {
    account::validate_hub(hub)?;
    if !account::uuid(user_id) {
        return Err(INVALID);
    }
    let root = account::root(app)?;
    account::require_roster_context(&root, hub, user_id)?;
    let mut db = account::database(&root, &account::context(hub, user_id)).await?;
    let user = account::sync_user(&mut db, user_id).await?;
    Ok((db, user))
}
#[tauri::command]
pub async fn read_sync_state(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
) -> Result<SyncState, &'static str> {
    let _guard = accounts.0.lock().await;
    accounts.require_sync_selection(&hub, &user_id.to_ascii_lowercase())?;
    let (mut db, _) = context(&app, &hub, &user_id.to_ascii_lowercase()).await?;
    read_state(&mut db).await
}
#[tauri::command]
pub async fn apply_sync_pull(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    expected: SyncState,
    payload: Value,
) -> Result<(), &'static str> {
    let _guard = accounts.0.lock().await;
    accounts.require_sync_selection(&hub, &user_id.to_ascii_lowercase())?;
    let (mut db, user) = context(&app, &hub, &user_id.to_ascii_lowercase()).await?;
    apply(&mut db, &user, &expected, payload).await
}
#[tauri::command]
pub async fn count_queued_work(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
) -> Result<i64, &'static str> {
    let _guard = accounts.0.lock().await;
    let user_id = user_id.to_ascii_lowercase();
    accounts.require_sync_selection(&hub, &user_id)?;
    let (mut db, _) = context(&app, &hub, &user_id).await?;
    sqlx::query_scalar("SELECT
        (SELECT COUNT(*) FROM announcement_comments WHERE sync_status = 'QUEUED_FOR_SYNC' AND author_id = ?1) +
        (SELECT COUNT(*) FROM private_comments WHERE sync_status = 'QUEUED_FOR_SYNC' AND author_id = ?1) +
        (SELECT COUNT(*) FROM quiz_attempts WHERE sync_status = 'QUEUED_FOR_SYNC' AND student_id = ?1) +
        (SELECT COUNT(*) FROM assignment_submissions WHERE sync_status = 'QUEUED_FOR_SYNC' AND student_id = ?1)")
        .bind(user_id)
        .fetch_one(&mut db)
        .await
        .map_err(|_| ERROR)
}

#[tauri::command]
pub async fn read_sync_queue(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
) -> Result<PushPayload, &'static str> {
    let _guard = accounts.0.lock().await;
    accounts.require_sync_selection(&hub, &user_id.to_ascii_lowercase())?;
    let (mut db, user) = context(&app, &hub, &user_id.to_ascii_lowercase()).await?;
    queue(&mut db, &user).await
}
#[tauri::command]
pub async fn apply_sync_receipts(
    app: tauri::AppHandle,
    accounts: tauri::State<'_, Accounts>,
    hub: HubInfo,
    user_id: String,
    sent: PushPayload,
    payload: Value,
) -> Result<(), &'static str> {
    let _guard = accounts.0.lock().await;
    accounts.require_sync_selection(&hub, &user_id.to_ascii_lowercase())?;
    let (mut db, user) = context(&app, &hub, &user_id.to_ascii_lowercase()).await?;
    receipts(&mut db, &user, &sent, payload).await
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::account::{Role, User};
    use serde_json::json;
    const USER: &str = "11111111-1111-4111-8111-111111111111";
    const CLASS: &str = "33333333-3333-4333-8333-333333333333";
    fn user() -> User {
        User {
            id: USER.into(),
            lrn_or_id: "123456789012".into(),
            full_name: "Learner".into(),
            role: Role::STUDENT,
        }
    }
    fn page(cursor: i64) -> Value {
        json!({"server_time":10,"hub_id":"test-hub","sync_epoch":1,"next_cursor":cursor,"has_more":false,"reset":false,"users":[{"id":USER,"full_name":"Learner","role":"STUDENT"}],"classrooms":[{"id":CLASS,"name":"Science","section":"A","class_code":"K7M4QX","teacher_id":"22222222-2222-4222-8222-222222222222","enrollment_status":"ACTIVE"}]})
    }
    async fn db() -> (SqliteConnection, std::path::PathBuf) {
        let root = std::env::temp_dir().join(format!("lara-sync-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&root).unwrap();
        let options = sqlx::sqlite::SqliteConnectOptions::new()
            .filename(root.join("test.db"))
            .create_if_missing(true)
            .foreign_keys(true);
        let mut db = SqliteConnection::connect_with(&options).await.unwrap();
        sqlx::raw_sql(include_str!("../migrations/001_client_offline.sql"))
            .execute(&mut db)
            .await
            .unwrap();
        sqlx::raw_sql(include_str!("../migrations/002_sync_rejections.sql"))
            .execute(&mut db)
            .await
            .unwrap();
        sqlx::raw_sql(include_str!("../migrations/003_submission_received_at.sql"))
            .execute(&mut db)
            .await
            .unwrap();
        (db, root)
    }
    #[tokio::test]
    async fn pull_commits_records_and_integer_cursor_and_rolls_back_failed_page() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(5))
            .await
            .unwrap();
        assert_eq!(read_state(&mut db).await.unwrap().cursor, 5);
        sqlx::raw_sql("CREATE TRIGGER fail_cursor BEFORE UPDATE ON sync_state WHEN NEW.key='cursor' BEGIN SELECT RAISE(ABORT,'failure'); END;").execute(&mut db).await.unwrap();
        let mut next = page(6);
        next["classrooms"][0]["name"] = json!("Changed");
        let expected = read_state(&mut db).await.unwrap();
        assert!(apply(&mut db, &user(), &expected, next).await.is_err());
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT name FROM classrooms")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "Science"
        );
        assert_eq!(read_state(&mut db).await.unwrap().cursor, 5);
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    const ANN: &str = "44444444-4444-4444-8444-444444444444";
    const ASSIGN: &str = "55555555-5555-4555-8555-555555555555";
    const QUIZ: &str = "66666666-6666-4666-8666-666666666666";
    const COMMENT: &str = "77777777-7777-4777-8777-777777777777";
    const PRIVATE: &str = "88888888-8888-4888-8888-888888888888";
    const ATTEMPT: &str = "99999999-9999-4999-8999-999999999999";
    async fn seed(db: &mut SqliteConnection) {
        sqlx::query("INSERT INTO announcements VALUES(?,?, 'Title','Body',1,0,0)")
            .bind(ANN)
            .bind(CLASS)
            .execute(&mut *db)
            .await
            .unwrap();
        sqlx::query("INSERT INTO assignments(id,classroom_id,title,due_date,created_at,updated_at) VALUES(?,?,'Work',0,0,0)").bind(ASSIGN).bind(CLASS).execute(&mut *db).await.unwrap();
        sqlx::query("INSERT INTO quizzes(id,classroom_id,title,time_limit_minutes,created_at,updated_at) VALUES(?,?,'Quiz',10,0,0)").bind(QUIZ).bind(CLASS).execute(&mut *db).await.unwrap();
        sqlx::query("INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES(?,?,?,'queued',0,0,'QUEUED_FOR_SYNC')").bind(COMMENT).bind(ANN).bind(USER).execute(&mut *db).await.unwrap();
        sqlx::query("INSERT INTO private_comments(id,assignment_id,student_id,author_id,content,created_at,updated_at,sync_status,sync_reject_reason) VALUES(?,?,?,?,'rejected',0,0,'REJECTED','FORBIDDEN')").bind(PRIVATE).bind(ASSIGN).bind(USER).bind(USER).execute(&mut *db).await.unwrap();
        sqlx::query("INSERT INTO quiz_attempts(id,quiz_id,student_id,started_at,updated_at) VALUES(?,?,?,0,0)").bind(ATTEMPT).bind(QUIZ).bind(USER).execute(&mut *db).await.unwrap();
    }
    async fn finish(db: SqliteConnection, root: std::path::PathBuf) {
        db.close().await.unwrap();
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn reset_preserves_queued_rejected_parents_local_media_chat_and_active_quiz() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(5))
            .await
            .unwrap();
        seed(&mut db).await;
        sqlx::query("INSERT INTO materials(id,classroom_id,title,file_type,file_size_bytes,local_file_path,created_at,updated_at) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',?,'cached','VIDEO',1,'G:/cached.mp4',0,0)").bind(CLASS).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,submitted_at,updated_at,sync_status) VALUES('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',?,?,'G:/photo.jpg',0,0,'REJECTED')").bind(ASSIGN).bind(USER).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO ai_chat_messages(id,classroom_id,student_id,role,content,created_at) VALUES('cccccccc-cccc-4ccc-8ccc-cccccccccccc',?,?,'USER','history',0)").bind(CLASS).bind(USER).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO announcements VALUES('dddddddd-dddd-4ddd-8ddd-dddddddddddd',?,'ordinary','removed',1,0,0)").bind(CLASS).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO quiz_questions(id,quiz_id,order_index,question_text,question_type,points,created_at,updated_at) VALUES('eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',?,0,'Keep this question','IDENTIFICATION',1,0,0)").bind(QUIZ).execute(&mut db).await.unwrap();
        let expected = read_state(&mut db).await.unwrap();
        let reset = json!({"server_time":0,"hub_id":"test-hub","sync_epoch":2,"next_cursor":1,"has_more":false,"reset":true});
        apply(&mut db, &user(), &expected, reset).await.unwrap();
        for table in [
            "announcement_comments",
            "private_comments",
            "quiz_attempts",
            "assignment_submissions",
            "materials",
            "ai_chat_messages",
            "assignments",
            "quizzes",
            "quiz_questions",
            "classrooms",
        ] {
            assert_eq!(
                sqlx::query_scalar::<_, i64>(&format!("SELECT count(*) FROM {table}"))
                    .fetch_one(&mut db)
                    .await
                    .unwrap(),
                1,
                "{table}"
            );
        }
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM announcements")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            1
        );
        assert!(account::classes(&mut db, &user()).await.unwrap().is_empty());
        assert_eq!(read_state(&mut db).await.unwrap().cursor, 1);
        finish(db, root).await;
    }
    #[tokio::test]
    async fn tombstones_remove_unprotected_children_but_preserve_rejected_parent_chain() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(1))
            .await
            .unwrap();
        seed(&mut db).await;
        sqlx::query("INSERT INTO private_comments(id,assignment_id,student_id,author_id,content,created_at,updated_at) VALUES('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',?,?,?,'synced',0,0)").bind(ASSIGN).bind(USER).bind(USER).execute(&mut db).await.unwrap();
        let mut next = page(2);
        next["deleted"] = json!([{"entity_table":"assignments","entity_id":ASSIGN}]);
        let expected = read_state(&mut db).await.unwrap();
        apply(&mut db, &user(), &expected, next).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM private_comments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            1
        );
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT sync_status FROM private_comments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "REJECTED"
        );
        finish(db, root).await;
    }
    #[tokio::test]
    async fn validation_is_nested_private_and_rejects_stale_state_and_cursor_rewind() {
        let (mut db, root) = db().await;
        let mut first = page(1);
        first["users"][0]["lrn_or_id"] = json!("malicious");
        first["users"][0]["pin_hash"] = json!("secret");
        apply(&mut db, &user(), &SyncState::default(), first)
            .await
            .unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT lrn_or_id FROM users")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            user().lrn_or_id
        );
        let current = read_state(&mut db).await.unwrap();
        assert_eq!(
            apply(&mut db, &user(), &SyncState::default(), page(2)).await,
            Err("sync_state_changed")
        );
        assert!(apply(&mut db, &user(), &current, page(0)).await.is_err());
        for (family, record) in [
            ("comments", json!({"id":COMMENT})),
            (
                "material_chunks",
                json!({"id":ANN,"material_id":ASSIGN,"order_index":"bad","text":"lesson"}),
            ),
            (
                "private_comments",
                json!({"id":PRIVATE,"assignment_id":ASSIGN,"student_id":QUIZ,"author_id":QUIZ,"content":"private","created_at":0}),
            ),
        ] {
            let mut next = page(2);
            next[family] = json!([record]);
            assert!(apply(&mut db, &user(), &current, next).await.is_err());
            assert_eq!(read_state(&mut db).await.unwrap(), current);
        }
        let mut next = page(2);
        next["deleted"] = json!([{"entity_table":"sync_state","entity_id":CLASS}]);
        assert!(apply(&mut db, &user(), &current, next).await.is_err());
        finish(db, root).await;
    }
    #[tokio::test]
    async fn receipts_match_unchanged_sent_rows_and_do_not_push_photos_or_active_quizzes() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(1))
            .await
            .unwrap();
        seed(&mut db).await;
        let sent = queue(&mut db, &user()).await.unwrap();
        assert_eq!(sent.comments.len(), 1);
        assert!(sent.quiz_attempts.is_empty() && sent.private_comments.is_empty());
        let receipt = json!({"acknowledged":true,"receipts":[{"id":COMMENT,"status":"SYNCED"}]});
        sqlx::query("UPDATE announcement_comments SET content='edited' WHERE id=?")
            .bind(COMMENT)
            .execute(&mut db)
            .await
            .unwrap();
        receipts(&mut db, &user(), &sent, receipt.clone())
            .await
            .unwrap();
        assert_eq!(queue(&mut db, &user()).await.unwrap().comments.len(), 1);
        let sent = queue(&mut db, &user()).await.unwrap();
        receipts(&mut db,&user(),&sent,json!({"acknowledged":true,"receipts":[{"id":COMMENT,"status":"REJECTED","reason":"COMMENTS_DISABLED"}]})).await.unwrap();
        assert!(queue(&mut db, &user()).await.unwrap().comments.is_empty());
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT sync_reject_reason FROM announcement_comments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "COMMENTS_DISABLED"
        );
        receipts(&mut db, &user(), &sent, receipt).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT sync_status FROM announcement_comments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "REJECTED"
        );
        finish(db, root).await;
    }
    #[tokio::test]
    async fn held_grade_does_not_clear_queue_or_destroy_active_attempt() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(1))
            .await
            .unwrap();
        seed(&mut db).await;
        let mut next = page(2);
        next["quiz_attempts"] =
            json!([{"attempt_id":ATTEMPT,"score":null,"total_points":10,"submitted_at":10}]);
        let expected = read_state(&mut db).await.unwrap();
        apply(&mut db, &user(), &expected, next.clone())
            .await
            .unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT status FROM quiz_attempts")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "IN_PROGRESS"
        );
        sqlx::query("UPDATE quiz_attempts SET status='SUBMITTED',submitted_at=10,score=8")
            .execute(&mut db)
            .await
            .unwrap();
        next["next_cursor"] = json!(3);
        let expected = read_state(&mut db).await.unwrap();
        apply(&mut db, &user(), &expected, next).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, Option<i64>>("SELECT score FROM quiz_attempts")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            None
        );
        assert_eq!(
            queue(&mut db, &user()).await.unwrap().quiz_attempts.len(),
            1
        );
        finish(db, root).await;
    }
    #[tokio::test]
    async fn revoked_membership_hides_saved_parent_and_rejects_unrelated_class_records() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(1))
            .await
            .unwrap();
        seed(&mut db).await;
        let mut next = page(2);
        next["classrooms"][0]["enrollment_status"] = json!("REMOVED");
        let expected = read_state(&mut db).await.unwrap();
        apply(&mut db, &user(), &expected, next).await.unwrap();
        assert!(account::classes(&mut db, &user()).await.unwrap().is_empty());
        let mut next = page(3);
        next["classrooms"][0]["teacher_id"] = json!(USER);
        next["classrooms"][0]["enrollment_status"] = json!("REMOVED");
        next["announcements"] = json!([{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","classroom_id":CLASS,"title":"unauthorized","content":"hidden","created_at":0,"updated_at":0}]);
        let expected = read_state(&mut db).await.unwrap();
        assert!(apply(&mut db, &user(), &expected, next).await.is_err());
        finish(db, root).await;
    }
    #[tokio::test]
    async fn complete_contract_page_maps_every_family_without_wire_secrets_or_false_outbox() {
        let (mut db, root) = db().await;
        let mut p = page(1);
        p["enrollments"] = json!([{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","classroom_id":CLASS,"student_id":USER,"status":"ACTIVE","updated_at":0}]);
        p["topics"] = json!([{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","classroom_id":CLASS,"name":"Unit","order_index":0,"updated_at":0}]);
        p["announcements"] = json!([{"id":ANN,"classroom_id":CLASS,"title":"Title","content":"Body","created_at":0,"updated_at":0}]);
        p["assignments"] = json!([{"id":ASSIGN,"classroom_id":CLASS,"title":"Homework","max_points":10,"due_date":0}]);
        p["materials"] = json!([{"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","classroom_id":CLASS,"title":"Lesson","file_type":"DOCUMENT","file_size_bytes":10,"download_url":"/api/materials/cccccccc-cccc-4ccc-8ccc-cccccccccccc/download","file_path":"SECRET_SERVER_PATH","local_file_path":"SECRET_WIRE_PATH","updated_at":0}]);
        p["material_chunks"] = json!([{"id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","material_id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","order_index":0,"heading":"Heading","text":"Lesson"}]);
        p["quizzes"] = json!([{"id":QUIZ,"classroom_id":CLASS,"title":"Quiz","status":"CLOSED","time_limit_minutes":10,"started_at":null,"correct_answer":"secret"}]);
        p["comments"] = json!([{"id":COMMENT,"announcement_id":ANN,"author_id":USER,"content":"Comment","created_at":0}]);
        p["private_comments"] = json!([{"id":PRIVATE,"assignment_id":ASSIGN,"student_id":USER,"author_id":USER,"content":"Private","created_at":0}]);
        p["submissions"] = json!([{"id":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee","assignment_id":ASSIGN,"student_id":USER,"file_type":"IMAGE","submitted_at":0,"received_at":2,"score":null,"teacher_feedback":null,"file_path":"SECRET_SERVER_PHOTO"}]);
        p["quiz_attempts"] = json!([{"attempt_id":ATTEMPT,"quiz_id":QUIZ,"student_id":USER,"score":null,"total_points":10,"submitted_at":0}]);
        apply(&mut db, &user(), &SyncState::default(), p)
            .await
            .unwrap();
        for e in ENTITIES {
            if e.table == "quiz_attempts" {
                continue;
            }
            assert_eq!(
                sqlx::query_scalar::<_, i64>(&format!("SELECT count(*) FROM {}", e.table))
                    .fetch_one(&mut db)
                    .await
                    .unwrap(),
                1,
                "{}",
                e.table
            );
        }
        assert_eq!(
            queue(&mut db, &user()).await.unwrap(),
            PushPayload::default()
        );
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT count(*) FROM quiz_attempts")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            0
        );
        let grades: HashMap<String, Value> =
            json_state(&mut db, "desktop:grade_receipts").await.unwrap();
        assert_eq!(grades.len(), 1);
        assert!(!grades[ATTEMPT]
            .as_object()
            .unwrap()
            .contains_key("started_at"));
        assert_eq!(
            sqlx::query_scalar::<_, i64>("SELECT received_at FROM assignment_submissions")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            2
        );
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT file_path FROM assignment_submissions")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            ""
        );
        assert_eq!(
            sqlx::query_scalar::<_, Option<String>>("SELECT local_file_path FROM materials")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            None
        );
        assert_eq!(account::classes(&mut db, &user()).await.unwrap().len(), 1);
        finish(db, root).await;
    }
    #[tokio::test]
    async fn valid_receipt_is_only_ack_and_malformed_receipt_batch_is_atomic() {
        let (mut db, root) = db().await;
        apply(&mut db, &user(), &SyncState::default(), page(1))
            .await
            .unwrap();
        seed(&mut db).await;
        let sent = queue(&mut db, &user()).await.unwrap();
        assert!(receipts(&mut db,&user(),&sent,json!({"acknowledged":true,"receipts":[{"id":COMMENT,"status":"SYNCED"},{"id":PRIVATE,"status":"SYNCED"}]})).await.is_err());
        assert_eq!(queue(&mut db, &user()).await.unwrap(), sent);
        let receipt = json!({"acknowledged":true,"receipts":[{"id":COMMENT,"status":"SYNCED"}]});
        receipts(&mut db, &user(), &sent, receipt.clone())
            .await
            .unwrap();
        receipts(&mut db, &user(), &sent, receipt).await.unwrap();
        assert_eq!(
            sqlx::query_scalar::<_, String>("SELECT sync_status FROM announcement_comments")
                .fetch_one(&mut db)
                .await
                .unwrap(),
            "SYNCED"
        );
        assert!(queue(&mut db, &user()).await.unwrap().comments.is_empty());
        finish(db, root).await;
    }
    #[tokio::test]
    async fn unmatched_grade_reconciles_later_and_teacher_roster_uses_canonical_deltas() {
        let (mut db, root) = db().await;
        let mut teacher = user();
        teacher.id = "22222222-2222-4222-8222-222222222222".into();
        teacher.role = Role::TEACHER;
        let mut p = page(1);
        p["users"][0]["full_name"] = json!("Original");
        p["classrooms"][0]
            .as_object_mut()
            .unwrap()
            .remove("enrollment_status");
        p["enrollments"] = json!([{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","classroom_id":CLASS,"student_id":USER,"status":"PENDING","updated_at":0}]);
        p["quizzes"] = json!([{"id":QUIZ,"classroom_id":CLASS,"title":"Quiz","status":"CLOSED","time_limit_minutes":10}]);
        p["quiz_attempts"] = json!([{"attempt_id":ATTEMPT,"quiz_id":QUIZ,"student_id":USER,"score":4,"total_points":10,"submitted_at":10}]);
        apply(&mut db, &teacher, &SyncState::default(), p)
            .await
            .unwrap();
        let roster: HashMap<String, Vec<account::RosterEntry>> =
            json_state(&mut db, "account_rosters").await.unwrap();
        assert_eq!(roster[CLASS][0].status, "PENDING");
        sqlx::query("INSERT INTO quiz_attempts(id,quiz_id,student_id,status,started_at,submitted_at,updated_at,sync_status) VALUES(?,?,?,'SUBMITTED',3,10,10,'SYNCED')").bind(ATTEMPT).bind(QUIZ).bind(USER).execute(&mut db).await.unwrap();
        let p = json!({"server_time":0,"hub_id":"test-hub","sync_epoch":1,"next_cursor":2,"has_more":false,"reset":false,"users":[{"id":USER,"full_name":"Renamed","role":"STUDENT"}],"enrollments":[{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","classroom_id":CLASS,"student_id":USER,"status":"ACTIVE","updated_at":1}]});
        let expected = read_state(&mut db).await.unwrap();
        apply(&mut db, &teacher, &expected, p).await.unwrap();
        let roster: HashMap<String, Vec<account::RosterEntry>> =
            json_state(&mut db, "account_rosters").await.unwrap();
        assert_eq!(roster[CLASS][0].status, "ACTIVE");
        assert_eq!(roster[CLASS][0].full_name, "Renamed");
        let row = sqlx::query("SELECT started_at,score FROM quiz_attempts")
            .fetch_one(&mut db)
            .await
            .unwrap();
        assert_eq!(row.get::<i64, _>(0), 3);
        assert_eq!(row.get::<i64, _>(1), 4);
        finish(db, root).await;
    }
}
