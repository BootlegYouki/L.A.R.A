use crate::{account::{self, Accounts, Role, User}, discovery::HubInfo};
use serde_json::{json, Value};
use sqlx::{Row, SqliteConnection};
use std::io::Write;

async fn read(db: &mut SqliteConnection, user: &User, assignment_id: &str) -> Result<Value, &'static str> {
    let classroom_id: String = sqlx::query_scalar("SELECT classroom_id FROM assignments WHERE id=?")
        .bind(assignment_id).fetch_optional(&mut *db).await.map_err(|_| "assignment_unavailable")?
        .ok_or("assignment_unavailable")?;
    account::classes(db, user).await?.into_iter().find(|room| room.id == classroom_id
        && (user.role == Role::TEACHER || room.enrollment_status.as_deref() == Some("ACTIVE")))
        .ok_or("FORBIDDEN")?;
    let rows = sqlx::query("SELECT id,assignment_id,student_id,submitted_at,received_at,score,teacher_feedback,updated_at,sync_status FROM assignment_submissions WHERE assignment_id=? AND (? OR student_id=?) ORDER BY submitted_at,id")
        .bind(assignment_id).bind(user.role == Role::TEACHER).bind(&user.id)
        .fetch_all(db).await.map_err(|_| "assignment_unavailable")?;
    Ok(Value::Array(rows.iter().map(|row| json!({
        "id": row.get::<String,_>("id"), "assignment_id": row.get::<String,_>("assignment_id"),
        "student_id": row.get::<String,_>("student_id"), "submitted_at": row.get::<i64,_>("submitted_at"),
        "received_at": row.get::<Option<i64>,_>("received_at"), "score": row.get::<Option<i64>,_>("score"),
        "teacher_feedback": row.get::<Option<String>,_>("teacher_feedback"),
        "updated_at": row.get::<i64,_>("updated_at"), "sync_status": row.get::<String,_>("sync_status")
    })).collect()))
}

#[tauri::command]
pub async fn read_assignment_work(app: tauri::AppHandle, accounts: tauri::State<'_, Accounts>, hub: HubInfo, user_id: String, assignment_id: String) -> Result<Value, &'static str> {
    account::validate_hub(&hub)?;
    if !account::uuid(&user_id) || !account::uuid(&assignment_id) { return Err("invalid_fields"); }
    let user_id = user_id.to_ascii_lowercase();
    let _guard = accounts.0.lock().await;
    accounts.require_sync_selection(&hub, &user_id)?;
    let root = account::root(&app)?;
    let mut db = account::database(&root, &account::context(&hub, &user_id)).await?;
    let profile = account::profile(&mut db, &user_id).await?;
    read(&mut db, &profile.user, &assignment_id.to_ascii_lowercase()).await
}

fn extension(mime: &str) -> Option<&'static str> {
    match mime.split(';').next().unwrap_or("") {
        "image/jpeg"=>Some("jpg"), "image/png"=>Some("png"), "application/pdf"=>Some("pdf"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"=>Some("docx"), _=>None,
    }
}

async fn book(db:&mut SqliteConnection,user:&User,classroom_id:&str)->Result<Value,&'static str>{
    if user.role!=Role::TEACHER || !account::classes(db,user).await?.iter().any(|c|c.id==classroom_id){return Err("FORBIDDEN")}
    let mut work=Vec::new();
    for row in sqlx::query("SELECT id,title,max_points FROM assignments WHERE classroom_id=? ORDER BY due_date,id").bind(classroom_id).fetch_all(&mut *db).await.map_err(|_|"gradebook_unavailable")? {
        work.push(json!({"id":row.get::<String,_>(0),"title":row.get::<String,_>(1),"max_points":row.get::<i64,_>(2),"kind":"assignment"}));
    }
    let receipts:Value=serde_json::from_str(&account::state(db,"desktop:grade_receipts").await?.unwrap_or_else(||"{}".into())).map_err(|_|"gradebook_unavailable")?;
    let quiz_grades:Vec<&Value>=receipts.as_object().ok_or("gradebook_unavailable")?.values().collect();
    for row in sqlx::query("SELECT id,title FROM quizzes WHERE classroom_id=? AND status='CLOSED' ORDER BY created_at,id").bind(classroom_id).fetch_all(&mut *db).await.map_err(|_|"gradebook_unavailable")? {
        let id:String=row.get(0);let max=quiz_grades.iter().filter(|r|r["quiz_id"]==id).filter_map(|r|r["total_points"].as_i64()).max().unwrap_or(0);
        work.push(json!({"id":id,"title":row.get::<String,_>(1),"max_points":max,"kind":"quiz"}));
    }
    let rows=sqlx::query("SELECT s.student_id,s.assignment_id,s.score FROM assignment_submissions s JOIN assignments a ON a.id=s.assignment_id WHERE a.classroom_id=? AND s.score IS NOT NULL").bind(classroom_id).fetch_all(&mut *db).await.map_err(|_|"gradebook_unavailable")?;
    let roster=account::get_rosters(db,user).await?.remove(classroom_id).unwrap_or_default();
    let mut learners=Vec::new();
    for learner in roster.into_iter().filter(|r|r.status=="ACTIVE") {
        let mut scores=serde_json::Map::new();
        for row in &rows {if row.get::<String,_>(0)==learner.student_id {scores.insert(row.get(1),json!(row.get::<i64,_>(2)));}}
        for grade in &quiz_grades {if grade["student_id"]==learner.student_id && work.iter().any(|w|w["id"]==grade["quiz_id"]) {if let (Some(id),Some(score))=(grade["quiz_id"].as_str(),grade["score"].as_i64()){scores.insert(id.into(),json!(score));}}}
        learners.push(json!({"id":learner.student_id,"name":learner.full_name,"scores":scores}));
    }
    learners.sort_by(|a,b|a["name"].as_str().cmp(&b["name"].as_str()));
    Ok(json!({"work":work,"learners":learners}))
}

#[tauri::command]
pub async fn read_gradebook(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,classroom_id:String)->Result<Value,&'static str>{
    account::validate_hub(&hub)?;
    if !account::uuid(&user_id)||!account::uuid(&classroom_id){return Err("invalid_fields")}
    let _guard=accounts.0.lock().await;
    accounts.require_sync_selection(&hub,&user_id)?;
    let root=account::root(&app)?;let mut db=account::database(&root,&account::context(&hub,&user_id)).await?;
    let user=account::profile(&mut db,&user_id).await?.user;
    book(&mut db,&user,&classroom_id).await
}

#[tauri::command]
pub async fn submission_preview(app: tauri::AppHandle, accounts: tauri::State<'_, Accounts>, hub: HubInfo, user_id: String, submission_id: String, offline: bool) -> Result<Value, String> {
    use futures_util::StreamExt;
    account::validate_hub(&hub)?;
    if !account::uuid(&user_id)||!account::uuid(&submission_id){return Err("invalid_fields".into())}
    let user_id=user_id.to_ascii_lowercase();let submission_id=submission_id.to_ascii_lowercase();
    accounts.require_sync_selection(&hub,&user_id)?;
    let root=account::root(&app)?;
    let mut db=account::database(&root,&account::context(&hub,&user_id)).await?;
    let profile=account::profile(&mut db,&user_id).await?;
    let assignment:String=sqlx::query_scalar("SELECT assignment_id FROM assignment_submissions WHERE id=?")
        .bind(&submission_id).fetch_optional(&mut db).await.map_err(|_|"assignment_unavailable")?.ok_or("assignment_unavailable")?;
    if !read(&mut db,&profile.user,&assignment).await?.as_array().is_some_and(|rows|rows.iter().any(|row|row["id"]==submission_id)){return Err("FORBIDDEN".into())}
    let directory=root.join("submission-previews").join(account::context(&hub,&user_id));
    std::fs::create_dir_all(&directory).map_err(|_|"file_unavailable")?;
    if offline {
        for (ext,mime) in [("jpg","image/jpeg"),("png","image/png"),("pdf","application/pdf"),("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document")] {
            let path=directory.join(format!("{submission_id}.{ext}"));
            if path.is_file(){return Ok(json!({"path":path,"mime":mime}))}
        }
        return Err("file_not_saved".into());
    }
    let token=account::secure_token(&app,&accounts,&hub,&user_id).await?.ok_or("UNAUTHORIZED")?;
    let response=reqwest::Client::builder().redirect(reqwest::redirect::Policy::none()).timeout(std::time::Duration::from_secs(180)).build().map_err(|_|"network")?
        .get(format!("http://{}:{}/api/submissions/{}/file",hub.ip,hub.http_port,submission_id)).bearer_auth(token).send().await.map_err(|_|"network")?;
    if response.status()!=reqwest::StatusCode::OK{return Err(match response.status().as_u16(){401=>"UNAUTHORIZED",403=>"FORBIDDEN",404=>"NOT_FOUND",_=>"network"}.into())}
    let mime=response.headers().get(reqwest::header::CONTENT_TYPE).and_then(|v|v.to_str().ok()).ok_or("invalid_file")?.to_owned();
    let ext=extension(&mime).ok_or("invalid_file")?;
    const MAX:u64=250*1024*1024;
    if response.content_length().is_some_and(|size|size>MAX){return Err("file_too_large".into())}
    let path=directory.join(format!("{submission_id}.{ext}"));
    let temporary=directory.join(format!("{}.part",uuid::Uuid::new_v4()));
    let result=async {
        let mut file=std::fs::File::create(&temporary).map_err(|_|"file_unavailable")?;
        let mut stream=response.bytes_stream();let mut total=0u64;
        while let Some(chunk)=stream.next().await {
            let chunk=chunk.map_err(|_|"network")?;total+=chunk.len() as u64;
            if total>MAX{return Err("file_too_large")}
            file.write_all(&chunk).map_err(|_|"file_unavailable")?;
        }
        file.sync_all().map_err(|_|"file_unavailable")?;drop(file);
        accounts.require_sync_selection(&hub,&user_id)?;
        if total==0{return Err("invalid_file")}
        std::fs::rename(&temporary,&path).map_err(|_|"file_unavailable")?;
        Ok(json!({"path":path,"mime":mime}))
    }.await;
    if result.is_err(){let _=std::fs::remove_file(&temporary);}
    result.map_err(String::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use sqlx::Connection;
    #[test]
    fn preview_only_accepts_contract_document_formats() {
        assert_eq!(extension("image/jpeg"),Some("jpg"));
        assert_eq!(extension("application/pdf; charset=binary"),Some("pdf"));
        assert_eq!(extension("text/html"),None);
        assert_eq!(extension("image/svg+xml"),None);
    }
    #[tokio::test]
    async fn learner_reads_only_own_submission_and_revoked_class_is_denied() {
        let mut db=SqliteConnection::connect("sqlite::memory:").await.unwrap();
        sqlx::raw_sql(include_str!("../../../contracts/schema/client_offline.sql")).execute(&mut db).await.unwrap();
        let teacher="11111111-1111-4111-8111-111111111111";
        let learner="22222222-2222-4222-8222-222222222222";
        let room="33333333-3333-4333-8333-333333333333";
        let assignment="44444444-4444-4444-8444-444444444444";
        sqlx::query("INSERT INTO users(id,full_name,role,lrn_or_id,created_at,updated_at) VALUES(?, 'Teacher','TEACHER','T-1',0,0),(?,'Learner','STUDENT','L-1',0,0)").bind(teacher).bind(learner).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES(?,'Class','A','K7M4QX',?,0,0)").bind(room).bind(teacher).execute(&mut db).await.unwrap();
        sqlx::query("INSERT INTO assignments(id,classroom_id,title,due_date,created_at,updated_at) VALUES(?,?,'Work',1,0,0)").bind(assignment).bind(room).execute(&mut db).await.unwrap();
        for (id,author) in [("one",learner),("two",teacher)] {
            sqlx::query("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,submitted_at,created_at,updated_at) VALUES(?,?,?,'private',1,1,1)").bind(id).bind(assignment).bind(author).execute(&mut db).await.unwrap();
        }
        account::put(&mut db,"account_visible_classes",&json!([room]).to_string()).await.unwrap();
        let user=User{id:learner.into(),lrn_or_id:"L-1".into(),full_name:"Learner".into(),role:Role::STUDENT};
        let result=read(&mut db,&user,assignment).await.unwrap();
        assert_eq!(result.as_array().unwrap().len(),1);
        assert_eq!(result[0]["student_id"],learner);
        assert!(result[0].get("file_path").is_none());
        assert!(book(&mut db,&user,room).await.is_err());
        let instructor=User{id:teacher.into(),role:Role::TEACHER,..user.clone()};
        account::put(&mut db,"account_rosters",&json!({room:[{"enrollment_id":"55555555-5555-4555-8555-555555555555","student_id":learner,"full_name":"Learner","status":"ACTIVE"}]}).to_string()).await.unwrap();
        sqlx::query("UPDATE assignment_submissions SET score=0 WHERE student_id=?").bind(learner).execute(&mut db).await.unwrap();
        let grades=book(&mut db,&instructor,room).await.unwrap();
        assert_eq!(grades["learners"][0]["scores"][assignment],0);
        account::put(&mut db,"account_class_statuses",&json!({room:"REMOVED"}).to_string()).await.unwrap();
        assert!(read(&mut db,&user,assignment).await.is_err());
    }
}
