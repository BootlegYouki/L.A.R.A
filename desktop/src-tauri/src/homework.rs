use crate::{account::{self,Accounts,Role},discovery::HubInfo};
use serde::{Serialize,Deserialize};
use serde_json::{json,Value};
use sqlx::{Row,SqliteConnection};
use std::{path::{Path,PathBuf},io::{Read,Write}};
use tauri::Manager;
const ERROR:&str="homework_unavailable";
const INVALID:&str="invalid_homework";
const LIMIT:u64=250*1024*1024;
#[derive(Default)]
pub struct HomeworkTasks(pub tokio::sync::Mutex<()>);
#[derive(Serialize,Deserialize)]
struct Job {id:String,assignment_id:String,user_id:String,file_name:String,size:u64,turned_in_at:i64,#[serde(default)]attachments:Vec<JobAttachment>}
#[derive(Clone,Serialize,Deserialize)]
struct JobAttachment {file_name:String,size:u64,#[serde(default)]file_type:Option<String>}
#[derive(Deserialize)]
#[serde(rename_all="camelCase")]
pub struct AttachmentInput {file_name:String,size:u64}
fn valid_name(name:&str)->bool { name.len()<=255&&!name.contains(['/', '\\'])&&!name.chars().any(char::is_control)&&[".jpg",".jpeg",".png",".pdf",".docx"].iter().any(|ext|name.to_ascii_lowercase().ends_with(ext)) }
fn payload_path(dir:&Path,index:usize,batch:bool)->PathBuf {dir.join(if batch {format!("payload-{index}")}else{"payload".into()})}
fn directory(root:&Path,hub:&HubInfo,user:&str,id:&str)->Result<PathBuf,&'static str>{
 if !account::uuid(id)||!account::uuid(user){return Err(INVALID)}
 Ok(root.join("homework").join(account::context(hub,user)).join(id))
}
fn load(root:&Path,hub:&HubInfo,user:&str,id:&str)->Result<Job,&'static str>{
 let job:Job=serde_json::from_slice(&std::fs::read(directory(root,hub,user,id)?.join("job.json")).map_err(|_|ERROR)?).map_err(|_|ERROR)?;
 if job.id!=id||job.user_id!=user||!account::uuid(&job.assignment_id){return Err(INVALID)}
 Ok(job)
}
fn save_job(dir:&Path,job:&Job)->Result<(),&'static str>{
 let temporary=dir.join(format!("{}.part",uuid::Uuid::new_v4()));
 let result=(||{let mut file=std::fs::File::create(&temporary)?;file.write_all(&serde_json::to_vec(job).map_err(std::io::Error::other)?)?;file.sync_all()?;drop(file);std::fs::rename(&temporary,dir.join("job.json"))})();
 if result.is_err(){let _=std::fs::remove_file(temporary);}
 result.map_err(|_|ERROR)
}
fn file_type(name:&str,header:&[u8])->Option<&'static str>{
 let name=name.to_ascii_lowercase();
 if (name.ends_with(".jpg")||name.ends_with(".jpeg"))&&header.starts_with(&[0xff,0xd8,0xff]){Some("IMAGE")}
 else if name.ends_with(".png")&&header.starts_with(b"\x89PNG\r\n\x1a\n"){Some("IMAGE")}
 else if name.ends_with(".pdf")&&header.starts_with(b"%PDF-"){Some("DOCUMENT")}
 else if name.ends_with(".docx")&&header.starts_with(b"PK\x03\x04"){Some("DOCUMENT")}
 else {None}
}
async fn context(app:&tauri::AppHandle,accounts:&Accounts,hub:&HubInfo,user:&str)->Result<(PathBuf,SqliteConnection),&'static str>{
 account::validate_hub(hub)?;if !account::uuid(user){return Err(INVALID)}
 accounts.require_sync_selection(hub,user)?;
 let root=account::root(app)?;account::require_roster_context(&root,hub,user)?;
 let mut db=account::database(&root,&account::context(hub,user)).await?;
 if account::sync_user(&mut db,user).await?.role!=Role::STUDENT{return Err("FORBIDDEN")}
 Ok((root,db))
}
async fn writable(db:&mut SqliteConnection,user:&str,assignment:&str)->Result<(),&'static str>{
 let count:i64=sqlx::query_scalar("SELECT COUNT(*) FROM assignments a JOIN classrooms c ON c.id=a.classroom_id JOIN enrollments e ON e.classroom_id=c.id WHERE a.id=? AND e.student_id=? AND e.status='ACTIVE' AND c.archived_at IS NULL").bind(assignment).bind(user).fetch_one(db).await.map_err(|_|ERROR)?;
 if count!=1{return Err("FORBIDDEN")}Ok(())
}
#[tauri::command]
pub async fn begin_homework(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,assignment_id:String,file_name:String,size:u64)->Result<String,&'static str>{
 let _guard=accounts.0.lock().await;
 if !account::uuid(&assignment_id)||size==0||size>LIMIT||file_name.len()>255||file_name.contains(['/', '\\'])||file_name.chars().any(char::is_control){return Err(INVALID)}
 let name=file_name.to_ascii_lowercase();if ![".jpg",".jpeg",".png",".pdf",".docx"].iter().any(|ext|name.ends_with(ext)){return Err(INVALID)}
 let (root,mut db)=context(&app,&accounts,&hub,&user_id).await?;writable(&mut db,&user_id,&assignment_id).await?;
 let existing=sqlx::query("SELECT score,sync_status FROM assignment_submissions WHERE assignment_id=? AND student_id=?").bind(&assignment_id).bind(&user_id).fetch_optional(&mut db).await.map_err(|_|ERROR)?;
 if let Some(row)=existing{if row.get::<Option<i64>,_>("score").is_some(){return Err("ALREADY_GRADED")}if row.get::<String,_>("sync_status")=="QUEUED_FOR_SYNC"{return Err("homework_pending")}}
 let id=uuid::Uuid::new_v4().to_string();let dir=directory(&root,&hub,&user_id,&id)?;std::fs::create_dir_all(&dir).map_err(|_|ERROR)?;
 let now=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|_|ERROR)?.as_millis() as i64;
 let job=Job{id:id.clone(),assignment_id,user_id,file_name:file_name.clone(),size,turned_in_at:now,attachments:vec![]};
 save_job(&dir,&job)?;
 std::fs::File::create(dir.join("payload")).map_err(|_|ERROR)?;Ok(id)
}
#[tauri::command]
pub async fn begin_homework_batch(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,assignment_id:String,attachments:Vec<AttachmentInput>)->Result<String,&'static str>{
 let _guard=accounts.0.lock().await;
 let total=attachments.iter().try_fold(0u64,|sum,file|sum.checked_add(file.size)).ok_or(INVALID)?;
 if !account::uuid(&assignment_id)||attachments.is_empty()||attachments.len()>5||total==0||total>LIMIT||attachments.iter().any(|f|f.size==0||!valid_name(&f.file_name)){return Err(INVALID)}
 let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;writable(&mut db,&user_id,&assignment_id).await?;
 let existing=sqlx::query("SELECT score,sync_status FROM assignment_submissions WHERE assignment_id=? AND student_id=?").bind(&assignment_id).bind(&user_id).fetch_optional(&mut db).await.map_err(|_|ERROR)?;
 if let Some(row)=existing{if row.get::<Option<i64>,_>("score").is_some(){return Err("ALREADY_GRADED")}if row.get::<String,_>("sync_status")=="QUEUED_FOR_SYNC"{return Err("homework_pending")}}
 let id=uuid::Uuid::new_v4().to_string();let dir=directory(&root,&hub,&user_id,&id)?;std::fs::create_dir_all(&dir).map_err(|_|ERROR)?;
 let now=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|_|ERROR)?.as_millis() as i64;
 let job=Job{id:id.clone(),assignment_id,user_id,file_name:attachments[0].file_name.clone(),size:attachments[0].size,turned_in_at:now,attachments:attachments.into_iter().map(|f|JobAttachment{file_name:f.file_name,size:f.size,file_type:None}).collect()};
 save_job(&dir,&job)?;
 for index in 0..job.attachments.len(){std::fs::File::create(payload_path(&dir,index,true)).map_err(|_|ERROR)?;}Ok(id)
}
#[tauri::command]
pub async fn append_homework(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,job_id:String,offset:u64,data:Vec<u8>)->Result<(),&'static str>{
 let _guard=accounts.0.lock().await;let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;
 let job=load(&root,&hub,&user_id,&job_id)?;
 let exists:i64=sqlx::query_scalar("SELECT COUNT(*) FROM assignment_submissions WHERE id=?").bind(&job_id).fetch_one(&mut db).await.map_err(|_|ERROR)?;
 if exists!=0||data.is_empty()||data.len()>65536||offset.saturating_add(data.len() as u64)>job.size{return Err(INVALID)}
 let path=directory(&root,&hub,&user_id,&job_id)?.join("payload");
 if std::fs::metadata(&path).map_err(|_|ERROR)?.len()!=offset{return Err(INVALID)}
 let mut file=std::fs::OpenOptions::new().append(true).open(path).map_err(|_|ERROR)?;file.write_all(&data).map_err(|_|ERROR)?;Ok(())
}
async fn commit(db:&mut SqliteConnection,job:&Job,path:&Path,kind:&str,attachments_json:&str)->Result<(),&'static str>{
 writable(db,&job.user_id,&job.assignment_id).await?;
 let rows=sqlx::query("INSERT INTO assignment_submissions(id,assignment_id,student_id,file_path,file_type,submitted_at,created_at,updated_at,sync_status,attachments_json) VALUES(?,?,?,?,?,?,?,?,'QUEUED_FOR_SYNC',?) ON CONFLICT(assignment_id,student_id) DO UPDATE SET id=excluded.id,file_path=excluded.file_path,file_type=excluded.file_type,submitted_at=excluded.submitted_at,created_at=excluded.created_at,updated_at=excluded.updated_at,received_at=NULL,teacher_feedback=NULL,sync_status='QUEUED_FOR_SYNC',sync_reject_reason=NULL,attachments_json=excluded.attachments_json WHERE assignment_submissions.score IS NULL AND (assignment_submissions.sync_status!='QUEUED_FOR_SYNC' OR assignment_submissions.id=excluded.id)")
 .bind(&job.id).bind(&job.assignment_id).bind(&job.user_id).bind(path.to_str().ok_or(INVALID)?).bind(kind).bind(job.turned_in_at).bind(job.turned_in_at).bind(job.turned_in_at).bind(attachments_json).execute(db).await.map_err(|_|ERROR)?.rows_affected();
 if rows!=1{return Err("homework_pending")}Ok(())
}
#[tauri::command]
pub async fn finish_homework(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,job_id:String)->Result<(),&'static str>{
 let _guard=accounts.0.lock().await;let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;let job=load(&root,&hub,&user_id,&job_id)?;
 let dir=directory(&root,&hub,&user_id,&job_id)?;let path=payload_path(&dir,0,false);let mut file=std::fs::OpenOptions::new().read(true).write(true).open(&path).map_err(|_|ERROR)?;
 if file.metadata().map_err(|_|ERROR)?.len()!=job.size{return Err(INVALID)}
 let mut header=[0u8;8];let len=file.read(&mut header).map_err(|_|ERROR)?;let kind=file_type(&job.file_name,&header[..len]).ok_or(INVALID)?;
 file.sync_all().map_err(|_|ERROR)?;let metadata=serde_json::to_string(&json!([{"name":job.file_name,"file_type":kind}])).map_err(|_|ERROR)?;commit(&mut db,&job,&path,kind,&metadata).await
}
#[tauri::command]
pub async fn append_homework_batch(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,job_id:String,attachment_index:usize,offset:u64,data:Vec<u8>)->Result<(),&'static str>{
 let _guard=accounts.0.lock().await;let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;let job=load(&root,&hub,&user_id,&job_id)?;
 let file=job.attachments.get(attachment_index).ok_or(INVALID)?;let exists:i64=sqlx::query_scalar("SELECT COUNT(*) FROM assignment_submissions WHERE id=?").bind(&job_id).fetch_one(&mut db).await.map_err(|_|ERROR)?;
 if job.attachments.is_empty()||exists!=0||data.is_empty()||data.len()>65536||offset.saturating_add(data.len() as u64)>file.size{return Err(INVALID)}
 let path=payload_path(&directory(&root,&hub,&user_id,&job_id)?,attachment_index,true);if std::fs::metadata(&path).map_err(|_|ERROR)?.len()!=offset{return Err(INVALID)}
 let mut out=std::fs::OpenOptions::new().append(true).open(path).map_err(|_|ERROR)?;out.write_all(&data).map_err(|_|ERROR)?;Ok(())
}
#[tauri::command]
pub async fn finish_homework_batch(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,job_id:String)->Result<(),&'static str>{
 let _guard=accounts.0.lock().await;let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;let mut job=load(&root,&hub,&user_id,&job_id)?;
 if job.attachments.is_empty(){return Err(INVALID)}let dir=directory(&root,&hub,&user_id,&job_id)?;let mut meta=Vec::with_capacity(job.attachments.len());let mut kinds=Vec::with_capacity(job.attachments.len());
 for(index,attachment)in job.attachments.iter_mut().enumerate(){let path=payload_path(&dir,index,true);let mut file=std::fs::OpenOptions::new().read(true).write(true).open(&path).map_err(|_|ERROR)?;if file.metadata().map_err(|_|ERROR)?.len()!=attachment.size{return Err(INVALID)}let mut header=[0u8;8];let len=file.read(&mut header).map_err(|_|ERROR)?;let kind=file_type(&attachment.file_name,&header[..len]).ok_or(INVALID)?;file.sync_all().map_err(|_|ERROR)?;attachment.file_type=Some(kind.to_owned());kinds.push(kind);meta.push(json!({"name":attachment.file_name,"file_type":kind}));}
 save_job(&dir,&job)?;
 let metadata=serde_json::to_string(&meta).map_err(|_|ERROR)?;commit(&mut db,&job,&payload_path(&dir,0,true),kinds[0],&metadata).await
}
#[tauri::command]
pub async fn discard_homework_stage(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,job_id:String)->Result<(),&'static str>{
 let _guard=accounts.0.lock().await;let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;
 let exists:i64=sqlx::query_scalar("SELECT COUNT(*) FROM assignment_submissions WHERE id=?").bind(&job_id).fetch_one(&mut db).await.map_err(|_|ERROR)?;
 if exists==0 {let path=directory(&root,&hub,&user_id,&job_id)?;if path.exists(){std::fs::remove_dir_all(path).map_err(|_|ERROR)?;}}Ok(())
}
#[tauri::command]
pub async fn read_homework_status(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,assignment_id:String)->Result<Value,&'static str>{
 let _guard=accounts.0.lock().await;let(_,mut db)=context(&app,&accounts,&hub,&user_id).await?;
 let row=sqlx::query("SELECT id,sync_status,sync_reject_reason FROM assignment_submissions WHERE assignment_id=? AND student_id=?").bind(assignment_id).bind(user_id).fetch_optional(&mut db).await.map_err(|_|ERROR)?;
 Ok(row.map(|row|json!({"id":row.get::<String,_>("id"),"status":row.get::<String,_>("sync_status"),"reason":row.get::<Option<String>,_>("sync_reject_reason")})).unwrap_or(Value::Null))
}
fn receipt_matches(value:&Value,job:&Job)->bool {
 let files=if job.attachments.is_empty(){vec![(job.file_name.as_str(),value["file_type"].as_str())]}else{job.attachments.iter().map(|f|(f.file_name.as_str(),f.file_type.as_deref())).collect()};
 let attachment_match=if let Some(receipt)=value["attachments"].as_array(){receipt.len()==files.len()&&receipt.iter().zip(files).all(|(actual,(name,kind))|actual["name"]==name&&actual["file_type"].as_str()==kind)}else{job.attachments.is_empty()&&matches!(value["file_type"].as_str(),Some("IMAGE"|"DOCUMENT"))};
 value["id"]==job.id&&value["assignment_id"]==job.assignment_id&&value["student_id"]==job.user_id&&attachment_match&&value["submitted_at"].as_i64().is_some_and(|n|n>=0)&&value["received_at"].as_i64().is_some_and(|n|n>=0)&&(value["score"].is_null()||value["score"].as_i64().is_some_and(|n|n>=0))&&(value["teacher_feedback"].is_null()||value["teacher_feedback"].is_string())
}
async fn post(root:&Path,hub:&HubInfo,job:&Job,token:&str)->Result<(u16,Value),&'static str>{
 use tokio::io::AsyncReadExt;use futures_util::StreamExt;
 async fn part(path:PathBuf,name:String,size:u64)->Result<reqwest::multipart::Part,&'static str>{
  let file=tokio::fs::File::open(path).await.map_err(|_|ERROR)?;if file.metadata().await.map_err(|_|ERROR)?.len()!=size{return Err(INVALID)}
  let stream=futures_util::stream::try_unfold(file,|mut file|async move{let mut data=vec![0;65536];let count=file.read(&mut data).await?;if count==0{return Ok::<_,std::io::Error>(None)}data.truncate(count);Ok(Some((data,file)))});
  Ok(reqwest::multipart::Part::stream_with_length(reqwest::Body::wrap_stream(stream),size).file_name(name).mime_str("application/octet-stream").map_err(|_|INVALID)?)
 }
 let dir=directory(root,hub,&job.user_id,&job.id)?;let batch=!job.attachments.is_empty();let mut form=reqwest::multipart::Form::new().text("submission_id",job.id.clone()).text("turned_in_at",job.turned_in_at.to_string());
 if batch {for(index,file)in job.attachments.iter().enumerate(){form=form.part(format!("file_{}",index+1),part(payload_path(&dir,index,true),file.file_name.clone(),file.size).await?);}}
 else {form=form.part("file",part(payload_path(&dir,0,false),job.file_name.clone(),job.size).await?);}
 let client=reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).connect_timeout(std::time::Duration::from_secs(10)).timeout(std::time::Duration::from_secs(120)).build().map_err(|_|ERROR)?;
 let response=client.post(format!("http://{}:{}/api/assignments/{}/submit",hub.ip,hub.http_port,job.assignment_id)).bearer_auth(token).multipart(form).send().await.map_err(|_|ERROR)?;
 let status=response.status().as_u16();let mut bytes=Vec::new();let mut stream=response.bytes_stream();while let Some(chunk)=stream.next().await{let chunk=chunk.map_err(|_|ERROR)?;if bytes.len()+chunk.len()>65536{return Err(ERROR)}bytes.extend_from_slice(&chunk);}
 Ok((status,serde_json::from_slice(&bytes).map_err(|_|ERROR)?))
}
fn rejection_reason(status:u16,body:&Value)->Option<&str>{
 let code=body["error"]["code"].as_str()?;
 match (status,code){
  (409,"ALREADY_GRADED"|"LATE_SUBMISSION")|(403,"FORBIDDEN")|(404,"NOT_FOUND")=>Some(code),
  _=>None,
 }
}
#[tauri::command]
pub async fn flush_homework(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String)->Result<u64,&'static str>{
 // One foreground flusher avoids simultaneous replacement uploads for the same account.
 let tasks=app.state::<HomeworkTasks>();let Ok(_running)=tasks.0.try_lock() else{return Ok(0)};
 let(root,mut db)=context(&app,&accounts,&hub,&user_id).await?;
 let ids:Vec<String>=sqlx::query_scalar("SELECT id FROM assignment_submissions WHERE student_id=? AND sync_status='QUEUED_FOR_SYNC' ORDER BY submitted_at").bind(&user_id).fetch_all(&mut db).await.map_err(|_|ERROR)?;
 let mut completed=0;
 for id in ids {
  accounts.require_sync_selection(&hub,&user_id)?;
  let job=load(&root,&hub,&user_id,&id)?;
  let token=account::secure_token(&app,&accounts,&hub,&user_id).await?.ok_or("UNAUTHORIZED")?;
  let(status,body)=post(&root,&hub,&job,&token).await?;
  if status==200&&receipt_matches(&body,&job) {
   sqlx::query("UPDATE assignment_submissions SET sync_status='SYNCED',sync_reject_reason=NULL,submitted_at=?,received_at=?,score=?,teacher_feedback=?,updated_at=? WHERE id=? AND student_id=? AND sync_status='QUEUED_FOR_SYNC'")
   .bind(body["submitted_at"].as_i64()).bind(body["received_at"].as_i64()).bind(body["score"].as_i64()).bind(body["teacher_feedback"].as_str()).bind(body["updated_at"].as_i64().or(body["received_at"].as_i64())).bind(&id).bind(&user_id).execute(&mut db).await.map_err(|_|ERROR)?;completed+=1;
  } else if let Some(reason)=rejection_reason(status,&body) {
   sqlx::query("UPDATE assignment_submissions SET sync_status='REJECTED',sync_reject_reason=? WHERE id=? AND student_id=? AND sync_status='QUEUED_FOR_SYNC'").bind(reason).bind(&id).bind(&user_id).execute(&mut db).await.map_err(|_|ERROR)?;completed+=1;
  } else {return Err(ERROR)}
 }
 Ok(completed)
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test]fn staging_file_and_job_metadata_are_flushed_before_queueing(){
  let root=std::env::temp_dir().join(format!("lara-homework-{}",uuid::Uuid::new_v4()));std::fs::create_dir_all(&root).unwrap();
  let path=root.join("payload-0");std::fs::write(&path,b"%PDF-fixture").unwrap();
  let file=std::fs::OpenOptions::new().read(true).write(true).open(&path).unwrap();file.sync_all().unwrap();drop(file);
  let job=job("job");save_job(&root,&job).unwrap();let persisted:Job=serde_json::from_slice(&std::fs::read(root.join("job.json")).unwrap()).unwrap();assert_eq!(persisted.id,job.id);
  std::fs::remove_file(path).unwrap();std::fs::remove_file(root.join("job.json")).unwrap();std::fs::remove_dir(root).unwrap();
 }
 #[test]fn terminal_hub_rejections_release_the_queue_for_later_work(){
  for (status,code) in [(409,"LATE_SUBMISSION"),(409,"ALREADY_GRADED"),(403,"FORBIDDEN"),(404,"NOT_FOUND")] {
   assert_eq!(rejection_reason(status,&json!({"error":{"code":code}})),Some(code));
  }
  for (status,code) in [(401,"UNAUTHORIZED"),(503,"NETWORK"),(403,"OTHER")] {
   assert_eq!(rejection_reason(status,&json!({"error":{"code":code}})),None);
  }
 }
 #[test]fn validates_file_headers(){assert_eq!(file_type("work.pdf",b"%PDF-1.7"),Some("DOCUMENT"));assert_eq!(file_type("work.png",b"not image"),None);assert_eq!(file_type("x.exe",b"%PDF-"),None);}
 #[test]fn receipt_is_bound_to_learner_assignment_and_stable_submission(){let job=Job{id:"id".into(),assignment_id:"assignment".into(),user_id:"learner".into(),file_name:"x.pdf".into(),size:10,turned_in_at:1,attachments:vec![]};let mut receipt=json!({"id":"id","assignment_id":"assignment","student_id":"learner","file_type":"DOCUMENT","submitted_at":1,"received_at":2});assert!(receipt_matches(&receipt,&job));receipt["student_id"]=json!("other");assert!(!receipt_matches(&receipt,&job));}
 #[test]fn legacy_jobs_load_and_batch_receipts_must_preserve_order(){
  let old=r#"{"id":"id","assignment_id":"assignment","user_id":"learner","file_name":"x.pdf","size":10,"turned_in_at":1}"#;
  let legacy:Job=serde_json::from_str(old).unwrap();assert!(legacy.attachments.is_empty());
  let job=Job{id:"id".into(),assignment_id:"assignment".into(),user_id:"learner".into(),file_name:"a.pdf".into(),size:10,turned_in_at:1,attachments:vec![JobAttachment{file_name:"a.pdf".into(),size:5,file_type:Some("DOCUMENT".into())},JobAttachment{file_name:"b.png".into(),size:5,file_type:Some("IMAGE".into())}]};
  let mut receipt=json!({"id":"id","assignment_id":"assignment","student_id":"learner","attachments":[{"name":"a.pdf","file_type":"DOCUMENT"},{"name":"b.png","file_type":"IMAGE"}],"submitted_at":1,"received_at":2});assert!(receipt_matches(&receipt,&job));receipt["attachments"].as_array_mut().unwrap().reverse();assert!(!receipt_matches(&receipt,&job));
 }

 async fn db()->SqliteConnection {
  use sqlx::Connection;
  let mut db=SqliteConnection::connect("sqlite::memory:").await.unwrap();
  for(_,_,sql) in crate::migrations::SQL{sqlx::raw_sql(sql).execute(&mut db).await.unwrap();}
  sqlx::raw_sql("INSERT INTO classrooms(id,name,section,class_code,teacher_id,created_at,updated_at) VALUES('class','QA','A','ABC234','teacher',0,0); INSERT INTO assignments(id,classroom_id,title,instructions,due_date,created_at,updated_at) VALUES('assignment','class','QA','Read',100,0,0); INSERT INTO enrollments(id,classroom_id,student_id,status,joined_at,created_at,updated_at) VALUES('enrollment','class','learner','ACTIVE',0,0,0);").execute(&mut db).await.unwrap();db
 }
 fn job(id:&str)->Job{Job{id:id.into(),assignment_id:"assignment".into(),user_id:"learner".into(),file_name:"work.pdf".into(),size:12,turned_in_at:23,attachments:vec![]}}
 #[tokio::test]async fn queues_stable_identity_and_time_and_does_not_overwrite_pending_or_graded_work(){
  let mut db=db().await;let first=job("first");
  commit(&mut db,&first,Path::new("local-file"),"DOCUMENT","[]").await.unwrap();
  commit(&mut db,&first,Path::new("local-file"),"DOCUMENT","[]").await.unwrap();
  let row=sqlx::query("SELECT id,submitted_at,sync_status FROM assignment_submissions").fetch_one(&mut db).await.unwrap();
  assert_eq!(row.get::<String,_>("id"),"first");assert_eq!(row.get::<i64,_>("submitted_at"),23);assert_eq!(row.get::<String,_>("sync_status"),"QUEUED_FOR_SYNC");
  assert!(commit(&mut db,&job("second"),Path::new("other-file"),"DOCUMENT","[]").await.is_err());
  sqlx::query("UPDATE assignment_submissions SET sync_status='SYNCED',score=10").execute(&mut db).await.unwrap();
  assert!(commit(&mut db,&job("second"),Path::new("other-file"),"DOCUMENT","[]").await.is_err());
 }
 #[tokio::test]async fn archive_and_foreign_learner_cannot_queue(){
  let mut db=db().await;let mut foreign=job("foreign");foreign.user_id="other".into();
  assert!(commit(&mut db,&foreign,Path::new("local"),"DOCUMENT","[]").await.is_err());
  sqlx::query("UPDATE classrooms SET archived_at=1").execute(&mut db).await.unwrap();
  assert!(commit(&mut db,&job("first"),Path::new("local"),"DOCUMENT","[]").await.is_err());
 }
}
