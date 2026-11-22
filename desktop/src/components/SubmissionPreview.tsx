import {useEffect,useState} from 'react';
import {convertFileSrc,invoke} from '@tauri-apps/api/core';
import type {CachedAccount} from '../services/auth';
import {t} from '../i18n';
export default function SubmissionPreview({account,submissionId,offline}:{account:CachedAccount;submissionId:string;offline:boolean}) {
 const [preview,setPreview]=useState<{url:string;mime:string}|null>(null),[error,setError]=useState(false),[retry,setRetry]=useState(0);
 useEffect(()=>{
  let current=true;setPreview(null);setError(false);
  void invoke<{path:string;mime:string}>('submission_preview',{hub:account.hub,userId:account.user.id,submissionId,offline}).then(result=>{
   if(current&&typeof result.path==='string'&&typeof result.mime==='string')setPreview({url:convertFileSrc(result.path),mime:result.mime});
  }).catch(()=>{if(current)setError(true)});
  return()=>{current=false};
 },[account,submissionId,offline,retry]);
 return <section className="submission-preview" aria-label={t.work_submission}>
  {!preview&&!error&&<p role="status">{t.loading}</p>}
  {error&&<p role="alert">{t.work_preview_error}<button className="control text-button" onClick={()=>setRetry(v=>v+1)}>{t.retry}</button></p>}
  {preview&&(preview.mime.startsWith('image/')?<img src={preview.url} alt={t.work_submission}/>:preview.mime.startsWith('application/pdf')?<iframe title={t.work_submission} src={preview.url}/>:<p>{t.work_document_help}</p>)}
  {preview&&<a className="control text-button" href={preview.url} download>{t.work_download}</a>}
 </section>;
}
