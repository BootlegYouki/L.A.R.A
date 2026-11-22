use crate::{account::{self,Accounts,Role},assignment_work,discovery::HubInfo};
use serde::Deserialize;
use serde_json::{json,Value};
use sqlx::Row;
use std::{io::{Read,Write},path::PathBuf};
const MAX:u64=64*1024*1024;
#[derive(Clone,Deserialize)]
#[serde(tag="kind",rename_all="lowercase")]
pub enum DocumentSource {Submission{id:String,#[serde(default)] attachment_index:u8},Material{id:String}}
fn document_mime(bytes:&[u8])->Option<&'static str>{
 if bytes.starts_with(b"%PDF-"){Some("application/pdf")}
 else if bytes.starts_with(b"PK\x03\x04"){Some("application/vnd.openxmlformats-officedocument.wordprocessingml.document")}
 else{None}
}
fn validate_document(bytes:&[u8])->Result<&'static str,&'static str>{
 if bytes.is_empty()||bytes.len() as u64>MAX{return Err("document_too_large")}
 let mime=document_mime(bytes).ok_or("invalid_document")?;
 if mime.ends_with("document") {
  let mut archive=zip::ZipArchive::new(std::io::Cursor::new(bytes)).map_err(|_|"invalid_document")?;
  if archive.len()>4096{return Err("document_too_large")}
  let mut total=0u64;let mut main=false;
  for index in 0..archive.len(){let entry=archive.by_index_raw(index).map_err(|_|"invalid_document")?;total=total.checked_add(entry.size()).ok_or("document_too_large")?;if total>128*1024*1024{return Err("document_too_large")}if entry.name().map_err(|_|"invalid_document")?=="word/document.xml"{main=true}}
  if !main{return Err("invalid_document")}
 }
 Ok(mime)
}
async fn material_file(app:&tauri::AppHandle,accounts:&Accounts,hub:&HubInfo,user:&str,id:&str,offline:bool)->Result<PathBuf,String>{
 account::validate_hub(hub)?;
 if !account::uuid(user)||!account::uuid(id){return Err("invalid_fields".into())}
 accounts.require_sync_selection(hub,user)?;
 let root=account::root(app)?;let mut db=account::database(&root,&account::context(hub,user)).await?;
 let profile=account::profile(&mut db,user).await?;
 let row=sqlx::query("SELECT classroom_id,file_type,updated_at FROM materials WHERE id=?").bind(id).fetch_optional(&mut db).await.map_err(|_|"document_unavailable")?.ok_or("document_unavailable")?;
 let class:String=row.get(0);let kind:String=row.get(1);let revision:i64=row.get(2);
 if kind=="VIDEO"||!account::classes(&mut db,&profile.user).await?.iter().any(|c|c.id==class&&(profile.user.role==Role::TEACHER||c.enrollment_status.as_deref()==Some("ACTIVE"))){return Err("FORBIDDEN".into())}
 let directory=root.join("document-previews").join(account::context(hub,user));
 std::fs::create_dir_all(&directory).map_err(|_|"document_unavailable")?;
 let path=directory.join(format!("{id}-{revision}.document"));
 if path.is_file(){return Ok(path)}
 if offline{return Err("document_not_saved".into())}
 let token=account::secure_token(app,accounts,hub,user).await?.ok_or("UNAUTHORIZED")?;
 let mut response=reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).timeout(std::time::Duration::from_secs(180)).build().map_err(|_|"network")?.get(format!("http://{}:{}/api/materials/{}/download",hub.ip,hub.http_port,id)).bearer_auth(token).send().await.map_err(|_|"network")?;
 if response.status()!=reqwest::StatusCode::OK{return Err("document_unavailable".into())}
 if response.content_length().is_some_and(|n|n>MAX){return Err("document_too_large".into())}
 let temporary=directory.join(format!("{}.part",uuid::Uuid::new_v4()));
 let result=async {
  let mut file=std::fs::File::create(&temporary).map_err(|_|"document_unavailable")?;let mut total=0u64;
  while let Some(chunk)=response.chunk().await.map_err(|_|"network")?{total+=chunk.len() as u64;if total>MAX{return Err("document_too_large")}file.write_all(&chunk).map_err(|_|"document_unavailable")?;}
  file.sync_all().map_err(|_|"document_unavailable")?;drop(file);
  accounts.require_sync_selection(hub,user)?;
  let bytes=std::fs::read(&temporary).map_err(|_|"document_unavailable")?;validate_document(&bytes)?;
  std::fs::rename(&temporary,&path).map_err(|_|"document_unavailable")?;Ok(path)
 }.await;
 if result.is_err(){let _=std::fs::remove_file(temporary);}
 result.map_err(String::from)
}
async fn resolve(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,source:DocumentSource,offline:bool)->Result<PathBuf,String>{
 match source {
  DocumentSource::Submission{id,attachment_index}=>{let cached=assignment_work::submission_preview(app,accounts,hub,user_id,id,true,Some(attachment_index)).await?;Ok(PathBuf::from(cached["path"].as_str().ok_or("document_unavailable")?))},
  DocumentSource::Material{id}=>material_file(&app,&accounts,&hub,&user_id,&id,offline).await,
 }
}
#[tauri::command]
pub async fn prepare_document(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,source:DocumentSource,offline:bool)->Result<Value,String>{
 let path=resolve(app,accounts.clone(),hub.clone(),user_id.clone(),source,offline).await?;
 accounts.require_sync_selection(&hub,&user_id)?;
 let size=std::fs::metadata(&path).map_err(|_|"document_unavailable")?.len();if size>MAX{return Err("document_too_large".into())}
 let mut prefix=[0u8;8];let mut file=std::fs::File::open(path).map_err(|_|"document_unavailable")?;let count=file.read(&mut prefix).map_err(|_|"document_unavailable")?;
 Ok(json!({"mime":document_mime(&prefix[..count]).ok_or("invalid_document")?,"size":size}))
}
#[tauri::command]
pub async fn read_document(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,source:DocumentSource)->Result<tauri::ipc::Response,String>{
 let path=resolve(app,accounts.clone(),hub.clone(),user_id.clone(),source,true).await?;
 accounts.require_sync_selection(&hub,&user_id)?;
 if std::fs::metadata(&path).map_err(|_|"document_unavailable")?.len()>MAX{return Err("document_too_large".into())}
 let bytes=std::fs::read(path).map_err(|_|"document_unavailable")?;validate_document(&bytes)?;accounts.require_sync_selection(&hub,&user_id)?;
 Ok(tauri::ipc::Response::new(bytes))
}
#[cfg(test)]mod tests {
 use super::*;
 #[test]fn allows_only_pdf_or_real_word_archives(){assert_eq!(validate_document(b"%PDF-1.7 fixture"),Ok("application/pdf"));for bytes in [b"<html>".as_slice(),b"PK\x03\x04junk",b"",b"legacy doc"]{assert!(validate_document(bytes).is_err())}}
 #[test]fn validates_docx_and_rejects_archive_bombs_before_conversion(){
  let bytes=include_bytes!("../../tests/fixtures/preview.docx");assert!(validate_document(bytes).is_ok());
  let mut bomb=bytes.to_vec();let central=bomb.windows(4).position(|w|w==b"PK\x01\x02").unwrap();bomb[central+24..central+28].copy_from_slice(&(129u32*1024*1024).to_le_bytes());assert!(validate_document(&bomb).is_err());
 }
 #[test]fn source_never_accepts_a_client_filesystem_path(){let source:DocumentSource=serde_json::from_value(json!({"kind":"material","id":"test","path":"C:/private"})).unwrap();assert!(matches!(source,DocumentSource::Material{id} if id=="test"));assert!(serde_json::from_value::<DocumentSource>(json!({"kind":"path","path":"C:/private"})).is_err());}
}
