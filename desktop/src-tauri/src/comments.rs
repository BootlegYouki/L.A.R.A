use crate::account::{self, Accounts, Role, User};
use crate::discovery::HubInfo;
use serde::Deserialize;
use serde_json::{json, Value};
use sqlx::{Connection, Row, SqliteConnection};
use std::collections::{BTreeMap, BTreeSet};

const ERROR: &str = "comment_unavailable";
#[derive(Clone, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ThreadTarget {
    Announcement { id: String },
    Assignment { id: String, student_id: Option<String> },
}
struct Scope { id: String, classroom: String, student: Option<String>, mode: &'static str }
async fn scope(db: &mut SqliteConnection, user: &User, target: &ThreadTarget) -> Result<Scope, &'static str> {
    let id = match target { ThreadTarget::Announcement{id} | ThreadTarget::Assignment{id,..} => id.to_ascii_lowercase() };
    if !account::uuid(&id) { return Err(ERROR); }
    let parent = match target {
        ThreadTarget::Announcement{..} => sqlx::query("SELECT classroom_id,allow_comments FROM announcements WHERE id=?").bind(&id).fetch_optional(&mut *db).await,
        ThreadTarget::Assignment{..} => sqlx::query("SELECT classroom_id,1 AS allow_comments FROM assignments WHERE id=?").bind(&id).fetch_optional(&mut *db).await,
    }.map_err(|_| ERROR)?.ok_or(ERROR)?;
    let class_id: String = parent.get("classroom_id");
    let room = account::classes(db,user).await?.into_iter().find(|room| room.id == class_id).ok_or(ERROR)?;
    if user.role == Role::STUDENT {
        let active: Option<String> = sqlx::query_scalar("SELECT status FROM enrollments WHERE classroom_id=? AND student_id=?").bind(&class_id).bind(&user.id).fetch_optional(&mut *db).await.map_err(|_|ERROR)?;
        if active.as_deref() != Some("ACTIVE") { return Err(ERROR); }
    }
    let mut writable = room.archived_at.is_none() && parent.get::<i64,_>("allow_comments") != 0;
    let student = match target {
        ThreadTarget::Announcement{..} => None,
        ThreadTarget::Assignment{student_id,..} => {
            let selected = if user.role == Role::STUDENT {
                if student_id.as_ref().is_some_and(|id| !id.eq_ignore_ascii_case(&user.id)) { return Err(ERROR); }
                user.id.clone()
            } else {student_id.as_ref().ok_or(ERROR)?.to_ascii_lowercase()};
            if !account::uuid(&selected) { return Err(ERROR); }
            let status: Option<String> = sqlx::query_scalar("SELECT status FROM enrollments WHERE classroom_id=? AND student_id=?").bind(&class_id).bind(&selected).fetch_optional(&mut *db).await.map_err(|_|ERROR)?;
            if status.is_none() { return Err(ERROR); }
            writable &= status.as_deref() == Some("ACTIVE");
            Some(selected)
        }
    };
    let mode = if !writable {"readonly"} else if student.is_some() && user.role == Role::TEACHER {"online"} else {"queue"};
    Ok(Scope{id,classroom:class_id,student,mode})
}
async fn read_thread_db(db: &mut SqliteConnection, user: &User, target: &ThreadTarget) -> Result<Value, &'static str> {
    let scope=scope(db,user,target).await?;
    let rows=if let Some(student)=&scope.student {
        sqlx::query("SELECT c.id,c.author_id,c.content,c.created_at,c.sync_status,c.sync_reject_reason,u.full_name AS author_name FROM private_comments c LEFT JOIN users u ON u.id=c.author_id WHERE c.assignment_id=? AND c.student_id=? ORDER BY c.created_at,c.id").bind(&scope.id).bind(student).fetch_all(&mut *db).await
    } else {
        sqlx::query("SELECT c.id,c.author_id,c.content,c.created_at,c.sync_status,c.sync_reject_reason,u.full_name AS author_name FROM announcement_comments c LEFT JOIN users u ON u.id=c.author_id WHERE c.announcement_id=? ORDER BY c.created_at,c.id").bind(&scope.id).fetch_all(&mut *db).await
    }.map_err(|_|ERROR)?;
    let items:Vec<Value>=rows.iter().map(|row|json!({"id":row.get::<String,_>("id"),"author_id":row.get::<String,_>("author_id"),"author_name":row.get::<Option<String>,_>("author_name"),"content":row.get::<String,_>("content"),"created_at":row.get::<i64,_>("created_at"),"sync_status":row.get::<String,_>("sync_status"),"sync_reject_reason":row.get::<Option<String>,_>("sync_reject_reason")})).collect();
    Ok(json!({"items":items,"write_mode":scope.mode}))
}
async fn queue_comment_db(db: &mut SqliteConnection, user: &User, target: &ThreadTarget, id: &str, content: &str) -> Result<Value, &'static str> {
    let content=content.trim();
    if !account::uuid(id) || content.is_empty() || content.chars().count()>10_000 || content.chars().any(|c| c.is_control() && !matches!(c,'\n'|'\r'|'\t')) { return Err("comment_invalid"); }
    let scope=scope(db,user,target).await?;
    if scope.mode != "queue" { return Err(if scope.mode == "online" {"comment_online_required"} else {"comment_read_only"}); }
    let now=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|_|ERROR)?.as_millis() as i64;
    let id=id.to_ascii_lowercase();
    if let Some(student)=&scope.student {
        sqlx::query("INSERT INTO private_comments(id,assignment_id,student_id,author_id,content,created_at,updated_at,sync_status) VALUES(?,?,?,?,?,?,?,'QUEUED_FOR_SYNC') ON CONFLICT(id) DO NOTHING").bind(&id).bind(&scope.id).bind(student).bind(&user.id).bind(content).bind(now).bind(now).execute(&mut *db).await.map_err(|_|ERROR)?;
    } else {
        sqlx::query("INSERT INTO announcement_comments(id,announcement_id,author_id,content,created_at,updated_at,sync_status) VALUES(?,?,?,?,?,?,'QUEUED_FOR_SYNC') ON CONFLICT(id) DO NOTHING").bind(&id).bind(&scope.id).bind(&user.id).bind(content).bind(now).bind(now).execute(&mut *db).await.map_err(|_|ERROR)?;
    }
    let row=if scope.student.is_some() {
        sqlx::query("SELECT assignment_id AS parent,student_id,author_id,content FROM private_comments WHERE id=?").bind(&id).fetch_one(&mut *db).await
    } else {
        sqlx::query("SELECT announcement_id AS parent,NULL AS student_id,author_id,content FROM announcement_comments WHERE id=?").bind(&id).fetch_one(&mut *db).await
    }.map_err(|_|ERROR)?;
    if row.get::<String,_>("parent")!=scope.id || row.get::<String,_>("author_id")!=user.id || row.get::<String,_>("content")!=content || row.get::<Option<String>,_>("student_id")!=scope.student {return Err("comment_invalid");}
    read_thread_db(db,user,target).await
}
async fn selected_context(app:&tauri::AppHandle, accounts:&Accounts, hub:&HubInfo, id:&str)->Result<(SqliteConnection,User),&'static str> {
    account::validate_hub(hub)?;
    if !account::uuid(id) {return Err(ERROR);}
    accounts.require_sync_selection(hub,id)?;
    let root=account::root(app)?;
    account::require_roster_context(&root,hub,id)?;
    let mut db=account::database(&root,&account::context(hub,id)).await?;
    let user=account::sync_user(&mut db,id).await?;
    Ok((db,user))
}
async fn read_counts_db(db:&mut SqliteConnection,user:&User,classroom:&str)->Result<Value,&'static str>{
    if !account::uuid(classroom)||!account::classes(db,user).await?.iter().any(|room|room.id==classroom){return Err(ERROR)}
    if user.role==Role::STUDENT {
        let active:i64=sqlx::query_scalar("SELECT COUNT(*) FROM enrollments WHERE classroom_id=? AND student_id=? AND status='ACTIVE'").bind(classroom).bind(&user.id).fetch_one(&mut *db).await.map_err(|_|ERROR)?;
        if active!=1{return Err(ERROR)}
    }
    let rows=sqlx::query("SELECT a.id,COUNT(c.id) AS count FROM announcements a LEFT JOIN announcement_comments c ON c.announcement_id=a.id WHERE a.classroom_id=? GROUP BY a.id").bind(classroom).fetch_all(db).await.map_err(|_|ERROR)?;
    Ok(Value::Object(rows.into_iter().map(|row|(row.get::<String,_>("id"),json!(row.get::<i64,_>("count")))).collect()))
}
fn private_read_key(classroom:&str)->String {format!("desktop:private_comment_read:{classroom}")}
async fn private_seen(db:&mut SqliteConnection,classroom:&str)->Result<BTreeSet<String>,&'static str>{
    match account::state(db,&private_read_key(classroom)).await? {
        Some(value)=>serde_json::from_str(&value).map_err(|_|ERROR),None=>Ok(BTreeSet::new())
    }
}
async fn private_unread_db(db:&mut SqliteConnection,user:&User,classroom:&str)->Result<Value,&'static str>{
    if user.role!=Role::TEACHER||!account::uuid(classroom)||!account::classes(db,user).await?.iter().any(|room|room.id==classroom){return Err(ERROR)}
    Ok(json!(unread_in_class(db,classroom).await?))
}
async fn unread_in_class(db:&mut SqliteConnection,classroom:&str)->Result<BTreeMap<String,BTreeMap<String,u64>>,&'static str>{
    let seen=private_seen(db,classroom).await?;
    let rows=sqlx::query("SELECT c.id,c.assignment_id,c.student_id FROM private_comments c JOIN assignments a ON a.id=c.assignment_id JOIN enrollments e ON e.classroom_id=a.classroom_id AND e.student_id=c.student_id WHERE a.classroom_id=? AND e.status='ACTIVE' AND c.author_id=c.student_id AND c.sync_status!='REJECTED'").bind(classroom).fetch_all(db).await.map_err(|_|ERROR)?;
    let mut counts:BTreeMap<String,BTreeMap<String,u64>>=BTreeMap::new();
    for row in rows {if !seen.contains(&row.get::<String,_>("id")){*counts.entry(row.get("assignment_id")).or_default().entry(row.get("student_id")).or_default()+=1;}}
    Ok(counts)
}
async fn private_unread_classes_db(db:&mut SqliteConnection,user:&User)->Result<Value,&'static str>{
    if user.role!=Role::TEACHER{return Err(ERROR)}
    let rooms=account::classes(db,user).await?;let mut counts=BTreeMap::new();
    for room in rooms.into_iter().filter(|room|room.archived_at.is_none()) {
        let unread=unread_in_class(db,&room.id).await?;
        let count:u64=unread.values().flat_map(|students|students.values()).sum();
        if count>0{counts.insert(room.id,count);}
    }
    Ok(json!(counts))
}
async fn mark_private_read_db(db:&mut SqliteConnection,user:&User,target:&ThreadTarget,ids:&[String])->Result<bool,&'static str>{
    if user.role!=Role::TEACHER||ids.len()>10000{return Err(ERROR)}
    let scope=scope(db,user,target).await?;let student=scope.student.ok_or(ERROR)?;
    let mut tx=db.begin().await.map_err(|_|ERROR)?;
    let rows=sqlx::query("SELECT c.id,c.assignment_id,c.student_id FROM private_comments c JOIN assignments a ON a.id=c.assignment_id WHERE a.classroom_id=? AND c.author_id=c.student_id AND c.sync_status!='REJECTED'").bind(&scope.classroom).fetch_all(&mut *tx).await.map_err(|_|ERROR)?;
    let live:BTreeSet<String>=rows.iter().map(|row|row.get("id")).collect();
    let allowed:BTreeSet<String>=rows.iter().filter(|row|row.get::<String,_>("assignment_id")==scope.id&&row.get::<String,_>("student_id")==student).map(|row|row.get("id")).collect();
    if ids.iter().any(|id|!account::uuid(id)||!allowed.contains(&id.to_ascii_lowercase())){return Err(ERROR)}
    let mut seen=private_seen(&mut tx,&scope.classroom).await?;let before=seen.clone();
    // Exact IDs keep a delayed offline message unread even when its timestamp is older.
    seen.extend(ids.iter().map(|id|id.to_ascii_lowercase()));seen.retain(|id|live.contains(id));
    let changed=seen!=before;
    if changed {account::put(&mut tx,&private_read_key(&scope.classroom),&serde_json::to_string(&seen).map_err(|_|ERROR)?).await?;}
    tx.commit().await.map_err(|_|ERROR)?;Ok(changed)
}
#[tauri::command]
pub async fn read_private_unread_class_counts(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String)->Result<Value,&'static str>{
    let _guard=accounts.0.lock().await;let(mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    private_unread_classes_db(&mut db,&user).await
}
#[tauri::command]
pub async fn read_private_comment_unread(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,classroom_id:String)->Result<Value,&'static str>{
    let _guard=accounts.0.lock().await;let(mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    private_unread_db(&mut db,&user,&classroom_id.to_ascii_lowercase()).await
}
#[tauri::command]
pub async fn mark_private_comments_read(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,target:ThreadTarget,comment_ids:Vec<String>)->Result<bool,&'static str>{
    let _guard=accounts.0.lock().await;let(mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    mark_private_read_db(&mut db,&user,&target,&comment_ids).await
}
#[tauri::command]
pub async fn read_class_comment_counts(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,classroom_id:String)->Result<Value,&'static str>{
    let _guard=accounts.0.lock().await;
    let(mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    read_counts_db(&mut db,&user,&classroom_id.to_ascii_lowercase()).await
}
#[tauri::command]
pub async fn read_comment_thread(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,target:ThreadTarget)->Result<Value,&'static str>{
    let _guard=accounts.0.lock().await;
    let (mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    read_thread_db(&mut db,&user,&target).await
}
#[tauri::command]
pub async fn queue_comment(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,target:ThreadTarget,id:String,content:String)->Result<Value,&'static str>{
    let _guard=accounts.0.lock().await;
    let (mut db,user)=selected_context(&app,&accounts,&hub,&user_id.to_ascii_lowercase()).await?;
    queue_comment_db(&mut db,&user,&target,&id,&content).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::account::put;
    use sqlx::Connection;
    const TEACHER: &str = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const LEARNER: &str = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    const OTHER: &str = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    const CLASS: &str = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    const POST: &str = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    const ASSIGN: &str = "ffffffff-ffff-4fff-8fff-ffffffffffff";
    const MESSAGE: &str = "11111111-1111-4111-8111-111111111111";
    fn user(id: &str, role: Role) -> User { User {id:id.into(),lrn_or_id:"synthetic".into(),full_name:"QA".into(),role} }
    fn public() -> ThreadTarget {ThreadTarget::Announcement{id:POST.into()}}
    fn private(student: &str) -> ThreadTarget {ThreadTarget::Assignment{id:ASSIGN.into(),student_id:Some(student.into())}}
    async fn db() -> SqliteConnection {
        let mut db=SqliteConnection::connect("sqlite::memory:").await.unwrap();
        for (_,_,sql) in crate::migrations::SQL {sqlx::raw_sql(sql).execute(&mut db).await.unwrap();}
        sqlx::query("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES(?,'QA','A','ABC234',?,0,0)").bind(CLASS).bind(TEACHER).execute(&mut db).await.unwrap();
        put(&mut db,"account_visible_classes",&serde_json::json!([CLASS]).to_string()).await.unwrap();
        put(&mut db,"account_class_statuses",&serde_json::json!({CLASS:"ACTIVE"}).to_string()).await.unwrap();
        for (id,student) in [("22222222-2222-4222-8222-222222222222",LEARNER),("33333333-3333-4333-8333-333333333333",OTHER)] {
            sqlx::query("INSERT INTO enrollments(id,classroom_id,student_id,status,joined_at,created_at,updated_at) VALUES(?,?,?,'ACTIVE',0,0,0)").bind(id).bind(CLASS).bind(student).execute(&mut db).await.unwrap();
        }
        sqlx::query("INSERT INTO announcements(id,classroom_id,title,content,allow_comments,created_at,updated_at) VALUES(?,?,'QA','Text',1,0,0)").bind(POST).bind(CLASS).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO assignments(id,classroom_id,title,instructions,due_date,created_at,updated_at) VALUES(?,?,'QA','Read',100,0,0)").bind(ASSIGN).bind(CLASS).execute(&mut db).await.unwrap();
        db
    }
    #[tokio::test]
    async fn private_unread_is_thread_scoped_and_tracks_ids_instead_of_timestamps(){
        let mut db=db().await;let teacher=user(TEACHER,Role::TEACHER);let learner=user(LEARNER,Role::STUDENT);
        let other_id="44444444-4444-4444-8444-444444444444";let late="55555555-5555-4555-8555-555555555555";
        queue_comment_db(&mut db,&learner,&private(LEARNER),MESSAGE,"First learner message").await.unwrap();
        queue_comment_db(&mut db,&user(OTHER,Role::STUDENT),&private(OTHER),other_id,"Other learner message").await.unwrap();
        sqlx::query("INSERT INTO private_comments(id,assignment_id,student_id,author_id,content,created_at,updated_at,sync_status) VALUES('66666666-6666-4666-8666-666666666666',?,?,?,'Teacher reply',1,1,'SYNCED')").bind(ASSIGN).bind(LEARNER).bind(TEACHER).execute(&mut db).await.unwrap();
        let counts=private_unread_db(&mut db,&teacher,CLASS).await.unwrap();assert_eq!(counts[ASSIGN][LEARNER],1);assert_eq!(counts[ASSIGN][OTHER],1);assert_eq!(private_unread_classes_db(&mut db,&teacher).await.unwrap()[CLASS],2);
        assert!(mark_private_read_db(&mut db,&teacher,&private(LEARNER),&[MESSAGE.into()]).await.unwrap());
        assert!(!mark_private_read_db(&mut db,&teacher,&private(LEARNER),&[MESSAGE.into()]).await.unwrap());
        let counts=private_unread_db(&mut db,&teacher,CLASS).await.unwrap();assert!(counts[ASSIGN].get(LEARNER).is_none());assert_eq!(counts[ASSIGN][OTHER],1);assert_eq!(private_unread_classes_db(&mut db,&teacher).await.unwrap()[CLASS],1);
        queue_comment_db(&mut db,&learner,&private(LEARNER),late,"Late offline message").await.unwrap();
        sqlx::query("UPDATE private_comments SET created_at=0 WHERE id=?").bind(late).execute(&mut db).await.unwrap();
        assert_eq!(private_unread_db(&mut db,&teacher,CLASS).await.unwrap()[ASSIGN][LEARNER],1);
        assert!(mark_private_read_db(&mut db,&teacher,&private(LEARNER),&[other_id.into()]).await.is_err());
        assert!(private_unread_db(&mut db,&learner,CLASS).await.is_err());
        assert!(private_unread_classes_db(&mut db,&learner).await.is_err());
        assert!(private_unread_classes_db(&mut db,&user(OTHER,Role::TEACHER)).await.is_err());
        assert!(private_unread_db(&mut db,&user(OTHER,Role::TEACHER),CLASS).await.is_err());
        assert!(private_unread_db(&mut db,&teacher,ASSIGN).await.is_err());
        assert!(mark_private_read_db(&mut db,&learner,&private(LEARNER),&[MESSAGE.into()]).await.is_err());
        assert!(mark_private_read_db(&mut db,&teacher,&public(),&[MESSAGE.into()]).await.is_err());
        sqlx::query("UPDATE enrollments SET status='REMOVED' WHERE student_id=?").bind(OTHER).execute(&mut db).await.unwrap();
        assert!(private_unread_db(&mut db,&teacher,CLASS).await.unwrap()[ASSIGN].get(OTHER).is_none());
    }    #[tokio::test]
    async fn comment_counts_are_scoped_to_an_active_visible_class(){
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);
        queue_comment_db(&mut db,&learner,&public(),MESSAGE,"Counted comment").await.unwrap();
        assert_eq!(read_counts_db(&mut db,&learner,CLASS).await.unwrap()[POST],1);
        assert!(read_counts_db(&mut db,&learner,ASSIGN).await.is_err());
        sqlx::query("UPDATE enrollments SET status='REMOVED' WHERE student_id=?").bind(LEARNER).execute(&mut db).await.unwrap();
        assert!(read_counts_db(&mut db,&learner,CLASS).await.is_err());
    }
    #[tokio::test]
    async fn queue_is_own_author_and_idempotent_without_overwriting_changed_content() {
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);
        let result=queue_comment_db(&mut db,&learner,&public(),MESSAGE,"Hello\nclass").await.unwrap();
        assert_eq!(result["items"][0]["author_id"],LEARNER);
        assert_eq!(result["items"][0]["sync_status"],"QUEUED_FOR_SYNC");
        let retry=queue_comment_db(&mut db,&learner,&public(),MESSAGE,"Hello\nclass").await.unwrap();
        assert_eq!(retry["items"].as_array().unwrap().len(),1);
        assert!(queue_comment_db(&mut db,&learner,&public(),MESSAGE,"Changed").await.is_err());
    }
    #[tokio::test]
    async fn learner_cannot_read_or_queue_another_learners_private_thread() {
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);
        assert!(read_thread_db(&mut db,&learner,&private(OTHER)).await.is_err());
        assert!(queue_comment_db(&mut db,&learner,&private(OTHER),MESSAGE,"Private").await.is_err());
        queue_comment_db(&mut db,&learner,&private(LEARNER),MESSAGE,"Own thread").await.unwrap();
        let other=user(OTHER,Role::STUDENT);
        assert_eq!(read_thread_db(&mut db,&other,&private(OTHER)).await.unwrap()["items"].as_array().unwrap().len(),0);
    }
    #[tokio::test]
    async fn teacher_reads_only_selected_thread_and_private_replies_require_online_route() {
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);let teacher=user(TEACHER,Role::TEACHER);
        queue_comment_db(&mut db,&learner,&private(LEARNER),MESSAGE,"Private").await.unwrap();
        let result=read_thread_db(&mut db,&teacher,&private(LEARNER)).await.unwrap();
        assert_eq!(result["items"].as_array().unwrap().len(),1);
        assert_eq!(result["write_mode"],"online");
        assert!(queue_comment_db(&mut db,&teacher,&private(LEARNER),MESSAGE,"Teacher").await.is_err());
        assert!(read_thread_db(&mut db,&teacher,&ThreadTarget::Assignment{id:ASSIGN.into(),student_id:None}).await.is_err());
    }
    #[tokio::test]
    async fn disabled_and_archived_threads_cannot_accept_queued_comments() {
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);
        sqlx::query("UPDATE announcements SET allow_comments=0").execute(&mut db).await.unwrap();
        assert_eq!(read_thread_db(&mut db,&learner,&public()).await.unwrap()["write_mode"],"readonly");
        assert!(queue_comment_db(&mut db,&learner,&public(),MESSAGE,"No").await.is_err());
        sqlx::query("UPDATE announcements SET allow_comments=1").execute(&mut db).await.unwrap();
        sqlx::query("UPDATE classrooms SET archived_at=1").execute(&mut db).await.unwrap();
        assert!(queue_comment_db(&mut db,&learner,&private(LEARNER),MESSAGE,"No").await.is_err());
    }
    #[tokio::test]
    async fn invisible_class_and_invalid_content_are_rejected() {
        let mut db=db().await;let learner=user(LEARNER,Role::STUDENT);
        assert!(queue_comment_db(&mut db,&learner,&public(),MESSAGE," \n ").await.is_err());
        assert!(queue_comment_db(&mut db,&learner,&public(),MESSAGE,"Bad\0text").await.is_err());
        put(&mut db,"account_visible_classes","[]").await.unwrap();
        assert!(read_thread_db(&mut db,&learner,&public()).await.is_err());
    }
}
