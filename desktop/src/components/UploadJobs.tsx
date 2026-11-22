import { useEffect, useRef, useState } from 'react';
import { t } from '../i18n';
import type { CachedAccount } from '../services/auth';
import { cancelUpload, listUploadJobs, retryUpload, type UploadJob } from '../services/uploadQueue';

interface Props { account: CachedAccount; classroomId: string; onCompleted: () => void }
const labels: Record<UploadJob['status'], string> = {
  staging:t.upload_staging, ready:t.upload_ready, uploading:t.upload_uploading, succeeded:t.upload_succeeded,
  failed:t.upload_failed, uncertain:t.upload_uncertain, cancelled:t.upload_cancelled,
};
export default function UploadJobs({account,classroomId,onCompleted}:Props) {
  const [jobs,setJobs]=useState<UploadJob[]>([]); const [busy,setBusy]=useState<Set<string>>(()=>new Set());
  const [confirming,setConfirming]=useState<Set<string>>(()=>new Set()); const [error,setError]=useState('');
  const completed=useRef(new Set<string>()); const completedCallback=useRef(onCompleted); completedCallback.current=onCompleted;
  const latestAccount=useRef(account); latestAccount.current=account;
  useEffect(()=>{
    let active=true,loading=false;
    const refresh=async()=>{
      if(loading)return; loading=true;
      try {
        const rows=await listUploadJobs(latestAccount.current,classroomId);
        if(!Array.isArray(rows))throw new Error('invalid upload list');
        if(active) {
          setError('');
          setJobs(rows);
          const newSuccess=rows.filter(row=>row.status==='succeeded'&&!completed.current.has(row.id));
          newSuccess.forEach(row=>completed.current.add(row.id));
          if(newSuccess.length)completedCallback.current();
        }
      } catch { if(active)setError(t.upload_jobs_error); }
      finally {loading=false;}
    };
    void refresh(); const timer=setInterval(()=>void refresh(),1000);
    return()=>{active=false;clearInterval(timer);};
  },[account.hub.ip,account.hub.http_port,account.user.id,classroomId]);
  async function act(id:string,action:()=>Promise<unknown>) {
    setBusy(previous=>new Set(previous).add(id));
    try { await action(); const rows=await listUploadJobs(latestAccount.current,classroomId); if(!Array.isArray(rows))throw new Error('invalid upload list'); setError(''); setJobs(rows); }
    catch { setError(t.upload_queue_error); }
    finally { setBusy(previous=>{const next=new Set(previous);next.delete(id);return next;}); }
  }
  const visibleJobs=jobs.filter(job=>job.status!=='succeeded'&&job.status!=='cancelled');
  if(!visibleJobs.length&&!error)return null;
  return <section aria-labelledby="upload-jobs-title"><h2 id="upload-jobs-title">{t.upload_jobs}</h2>{error&&<p role="alert">{error}</p>}<ul>
    {visibleJobs.map(job=><li key={job.id} aria-live="polite"><strong>{job.title||job.fileName}</strong> <span>{labels[job.status]}</span>
      {(job.status==='uploading'||job.status==='staging')&&<progress max={job.fileSize||1} value={job.uploadedBytes} aria-label={job.title||job.fileName}/>}
      {job.status==='uncertain'&&<><p role="status">{t.upload_uncertain_help}</p><button type="button" className="control text-button" disabled={busy.has(job.id)} onClick={()=>{if(confirming.has(job.id)){setConfirming(previous=>{const next=new Set(previous);next.delete(job.id);return next;});void act(job.id,()=>retryUpload(account,classroomId,job.id));}else setConfirming(previous=>new Set(previous).add(job.id));}}>{confirming.has(job.id)?t.upload_retry_confirm:t.upload_retry}</button></>}
      {(job.status==='ready'||job.status==='failed')&&<button type="button" className="control text-button" disabled={busy.has(job.id)} onClick={()=>void act(job.id,()=>retryUpload(account,classroomId,job.id))}>{t.upload_retry}</button>}
      {['staging','ready','uploading','failed'].includes(job.status)&&<button type="button" className="control text-button" disabled={busy.has(job.id)} onClick={()=>void act(job.id,()=>cancelUpload(account,classroomId,job.id))}>{t.upload_cancel}</button>}
    </li>)}
  </ul></section>;
}
