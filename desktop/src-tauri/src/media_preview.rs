use crate::{account::{self,Accounts},discovery::HubInfo};
use sqlx::Row;
use tauri::Manager;
use std::sync::Arc;
#[derive(Default)]
pub struct MediaPreviews(pub tokio::sync::Mutex<std::collections::HashMap<String,tokio::sync::oneshot::Sender<()>>>);
fn supported(mime:&str)->bool { matches!(mime,"image/png"|"image/jpeg"|"image/webp"|"image/gif"|"video/mp4"|"video/webm") }
#[tauri::command]
pub async fn open_material_preview(app:tauri::AppHandle,accounts:tauri::State<'_,Accounts>,hub:HubInfo,user_id:String,material_id:String)->Result<serde_json::Value,String>{
 account::validate_hub(&hub)?;
 if !account::uuid(&user_id)||!account::uuid(&material_id){return Err("invalid_preview".into())}
 accounts.require_sync_selection(&hub,&user_id)?;
 let root=account::root(&app)?;
 let mut db=account::database(&root,&account::context(&hub,&user_id)).await?;
 let row=sqlx::query("SELECT m.mime_type,m.file_type FROM materials m JOIN classrooms c ON c.id=m.classroom_id WHERE m.id=?").bind(&material_id).fetch_optional(&mut db).await.map_err(|_|"preview_unavailable")?.ok_or("preview_unavailable")?;
 let mut mime:String=row.get(0); let kind:String=row.get(1);
 let endpoint=if kind=="VIDEO"{"stream"}else{"download"};
 let upstream=format!("http://{}:{}/api/materials/{}/{}",hub.ip,hub.http_port,material_id,endpoint);
 let client=reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).connect_timeout(std::time::Duration::from_secs(10)).timeout(std::time::Duration::from_secs(900)).build().map_err(|_|"preview_unavailable")?;
 if !supported(&mime){
  let token=account::secure_token(&app,&accounts,&hub,&user_id).await?.ok_or("preview_unavailable")?;
  let mut response=client.get(&upstream).bearer_auth(token).header(reqwest::header::RANGE,"bytes=0-31").send().await.map_err(|_|"preview_unavailable")?;
  if !matches!(response.status().as_u16(),200|206){return Err("preview_unavailable".into())}
  let mut prefix=Vec::new();while prefix.len()<32 {let Some(chunk)=response.chunk().await.map_err(|_|"preview_unavailable")? else {break};let count=(32-prefix.len()).min(chunk.len());prefix.extend_from_slice(&chunk[..count]);}
  mime=sniff(&prefix).ok_or("unsupported_preview")?.into();
 }
 let media_mime=mime.clone();
 let listener=tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST,0)).await.map_err(|_|"preview_unavailable")?;
 let address=listener.local_addr().map_err(|_|"preview_unavailable")?;
 let id=uuid::Uuid::new_v4().to_string();let route=format!("/{id}");let mime=Arc::new(mime);
 let owner=app.clone();
 let image_cache=Arc::new(tokio::sync::OnceCell::<axum::body::Bytes>::new());
 let router=axum::Router::new().route(&route,axum::routing::get(move |request:axum::extract::Request|{
  let cache=image_cache.clone();
  let(app,hub,user,client,url,mime)=(app.clone(),hub.clone(),user_id.clone(),client.clone(),upstream.clone(),mime.clone());
  async move {
   use axum::{body::Body,http::{StatusCode,header},response::{Response,IntoResponse}};
   let token=match account::secure_token(&app,&app.state::<Accounts>(),&hub,&user).await {Ok(Some(token))=>token,_=>return StatusCode::NOT_FOUND.into_response()};
   if app.state::<Accounts>().require_sync_selection(&hub,&user).is_err(){return StatusCode::NOT_FOUND.into_response()}
   if mime.starts_with("image/") {
    let bytes=cache.get_or_try_init(||async {
     let mut reply=client.get(&url).bearer_auth(&token).send().await.map_err(|_|())?;
     if reply.status()!=reqwest::StatusCode::OK||reply.content_length().is_some_and(|n|n>8*1024*1024){return Err(())}
     let mut data=Vec::new();while let Some(chunk)=reply.chunk().await.map_err(|_|())?{if !image_fits(data.len(),chunk.len()){return Err(())}data.extend_from_slice(&chunk);}
     Ok(axum::body::Bytes::from(data))
    }).await;
    return match bytes {Ok(bytes)=>Response::builder().header(header::CONTENT_TYPE,mime.as_str()).header(header::CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(Body::from(bytes.clone())).unwrap_or_else(|_|StatusCode::BAD_GATEWAY.into_response()),Err(())=>StatusCode::BAD_GATEWAY.into_response()};
   }
   let mut fetch=client.get(url).bearer_auth(token);
   if let Some(range)=request.headers().get(header::RANGE){fetch=fetch.header(reqwest::header::RANGE,range.clone());}
   let result=match fetch.send().await {Ok(r)=>r,Err(_)=>return StatusCode::BAD_GATEWAY.into_response()};
   if !matches!(result.status().as_u16(),200|206|416){return StatusCode::BAD_GATEWAY.into_response()}
   if result.content_length().is_some_and(|n|n>250*1024*1024){return StatusCode::PAYLOAD_TOO_LARGE.into_response()}
   let mut response=Response::builder().status(result.status().as_u16()).header(header::CONTENT_TYPE,mime.as_str()).header(header::CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff");
   for name in [header::CONTENT_LENGTH,header::CONTENT_RANGE,header::ACCEPT_RANGES]{if let Some(value)=result.headers().get(&name){response=response.header(name,value);}}
   // Bound even chunked responses; never buffer an entire video in the webview.
   use futures_util::StreamExt;
   let stream=result.bytes_stream().scan(0u64,|total,chunk|{let item=match chunk{Ok(bytes)=>{*total+=bytes.len() as u64;if *total>250*1024*1024{Err(std::io::Error::other("preview too large"))}else{Ok(bytes)}},Err(_)=>Err(std::io::Error::other("preview unavailable"))};std::future::ready(Some(item))});
   response.body(Body::from_stream(stream)).unwrap_or_else(|_|StatusCode::BAD_GATEWAY.into_response())
  }
 }));
 let(tx,rx)=tokio::sync::oneshot::channel();
 owner.state::<MediaPreviews>().0.lock().await.insert(id.clone(),tx);
 let cleanup=owner.clone();let key=id.clone();
 tokio::spawn(async move {
  let _=axum::serve(listener,router).with_graceful_shutdown(async move {tokio::select!{_=rx=>{},_=tokio::time::sleep(std::time::Duration::from_secs(7200))=>{}}}).await;
  cleanup.state::<MediaPreviews>().0.lock().await.remove(&key);
 });
 Ok(serde_json::json!({"id":id,"mime":media_mime,"url":format!("http://{address}{route}")}))
}
#[tauri::command]
pub async fn close_material_preview(app:tauri::AppHandle,id:String){
 if let Some(stop)=app.state::<MediaPreviews>().0.lock().await.remove(&id){let _=stop.send(());}
}
#[cfg(test)]mod tests{use super::*;#[test]fn only_passive_media_types_are_previewed(){for mime in ["image/png","image/jpeg","image/webp","image/gif","video/mp4","video/webm"]{assert!(supported(mime))}for mime in ["text/html","image/svg+xml","application/pdf","application/octet-stream",""]{assert!(!supported(mime))}}}fn sniff(bytes:&[u8])->Option<&'static str>{
 if bytes.starts_with(b"\x89PNG\r\n\x1a\n"){Some("image/png")}
 else if bytes.starts_with(&[0xff,0xd8,0xff]){Some("image/jpeg")}
 else if bytes.starts_with(b"GIF87a")||bytes.starts_with(b"GIF89a"){Some("image/gif")}
 else if bytes.starts_with(b"RIFF")&&bytes.get(8..12)==Some(b"WEBP"){Some("image/webp")}
 else if bytes.get(4..8)==Some(b"ftyp"){Some("video/mp4")}
 else if bytes.starts_with(&[0x1a,0x45,0xdf,0xa3]){Some("video/webm")}
 else{None}
}
#[cfg(test)]mod sniff_tests{use super::*;#[test]fn detects_media_without_trusting_names(){assert_eq!(sniff(b"\x89PNG\r\n\x1a\n"),Some("image/png"));assert_eq!(sniff(b"GIF89a"),Some("image/gif"));assert_eq!(sniff(b"RIFF0000WEBP"),Some("image/webp"));assert_eq!(sniff(b"0000ftypisom"),Some("video/mp4"));for value in [b"%PDF-".as_slice(),b"<svg>",b"<html>",b"PK\x03\x04",b""]{assert_eq!(sniff(value),None)}}}
fn image_fits(current:usize,incoming:usize)->bool{current.checked_add(incoming).is_some_and(|total|total<=8*1024*1024)}
#[cfg(test)]mod cache_tests{use super::*;#[test]fn image_cache_size_is_bounded(){assert!(image_fits(0,8*1024*1024));assert!(!image_fits(8*1024*1024,1));assert!(!image_fits(usize::MAX,1));}}
