import {useEffect,useState} from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import DocumentViewer from './DocumentViewer';
import {isDocumentMime} from '../services/documents';
import {convertFileSrc,invoke} from '@tauri-apps/api/core';
import type {CachedAccount} from '../services/auth';
import {t} from '../i18n';
import type {SubmissionAttachment} from '../services/grading';
interface Props {account:CachedAccount;submissionId:string;offline:boolean;attachments?:SubmissionAttachment[]}
export default function SubmissionPreview(props:Props) {
 const files=props.attachments?.length?props.attachments:[{name:t.work_submission}];
 return <>{files.map((file,index)=><FilePreview key={`${props.submissionId}/${index}`} {...props} attachmentIndex={index} name={file.name}/>)}</>;
}
function FilePreview({account,submissionId,offline,attachmentIndex,name}:Props&{attachmentIndex:number;name:string}) {
 const {ip,http_port,ws_port,name:hubName}=account.hub,userId=account.user.id;
 const [preview,setPreview]=useState<{url:string;mime:string}|null>(null),[error,setError]=useState(false),[retry,setRetry]=useState(0),[busy,setBusy]=useState(false),[notice,setNotice]=useState(''),[documentOpen,setDocumentOpen]=useState(false);
 useEffect(()=>{
  let current=true;setPreview(null);setError(false);
  void invoke<{path:string;mime:string}>('submission_preview',{hub:{ip,http_port,ws_port,name:hubName},userId,submissionId,offline,attachmentIndex}).then(result=>{
   if(current&&typeof result.path==='string'&&typeof result.mime==='string')setPreview({url:convertFileSrc(result.path),mime:result.mime});
  }).catch(()=>{if(current)setError(true)});
  return()=>{current=false};
 },[ip,http_port,ws_port,hubName,userId,submissionId,offline,retry,attachmentIndex]);
 async function exportFile(open:boolean) {
  setBusy(true);setNotice('');
  try {const path=await invoke<string>('export_submission',{hub:account.hub,userId:account.user.id,submissionId,open,attachmentIndex});setNotice(open?t.work_opened:t.work_saved_to.replace('{path}',path))}
  catch {setNotice(t.work_export_error)}finally{setBusy(false)}
 }
 return <section className="submission-preview" aria-label={name}>
  <h3>{name}</h3>
  {!preview&&!error&&<p role="status">{t.loading}</p>}
  {error&&<p role="alert">{t.work_preview_error}<button className="control text-button" onClick={()=>setRetry(v=>v+1)}>{t.retry}</button></p>}
  {preview&&(preview.mime.startsWith('image/')?<Dialog.Root><Dialog.Trigger asChild><button type="button" className="attachment-image" aria-label={t.attachment_enlarge.replace('{title}',t.work_submission)}><img src={preview.url} alt={t.work_submission}/></button></Dialog.Trigger><Dialog.Portal><Dialog.Overlay className="join-scrim"/><Dialog.Content className="join-dialog image-preview-dialog"><Dialog.Title>{t.work_submission}</Dialog.Title><Dialog.Description className="sr-only">{t.attachment_image_preview}</Dialog.Description><img src={preview.url} alt={t.work_submission}/><Dialog.Close asChild><button type="button" className="control tonal-button">{t.attachment_close}</button></Dialog.Close></Dialog.Content></Dialog.Portal></Dialog.Root>:<p>{t.work_document_help}</p>)}
  {preview&&<div>{isDocumentMime(preview.mime)&&<button type="button" className="control tonal-button" onClick={()=>setDocumentOpen(true)}>{t.document_view}</button>}<button type="button" className="control tonal-button" disabled={busy} onClick={()=>exportFile(true)}>{t.work_open_file}</button><button type="button" className="control text-button" disabled={busy} onClick={()=>exportFile(false)}>{t.work_download}</button></div>}
  {notice&&<p role="status">{notice}</p>}
  {documentOpen&&<DocumentViewer account={account} source={{kind:'submission',id:submissionId,attachment_index:attachmentIndex}} title={name} offline={offline} onClose={()=>setDocumentOpen(false)}/>}
 </section>;
}
