import { useEffect, useRef, useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { CachedAccount, Classroom } from '../services/auth';
import { ApiError } from '../services/api';
import { enqueueUpload, type UploadJob } from '../services/uploadQueue';

interface Parent {kind:'assignment'|'announcement';id:string}
interface Props { account: CachedAccount; classroom: Classroom; open: boolean; offline: boolean; topics?: {id:string;name:string}[]; assignments?: {id:string;title:string}[]; announcements?: {id:string;title:string}[]; fixedParent?: Parent; onOpenChange: (open: boolean) => void; onQueued: (job: UploadJob) => void }
const MAX_FILE_SIZE = 250 * 1024 * 1024;
export default function MaterialUploadDialog({ account, classroom, open, offline, topics=[], assignments=[], announcements=[], fixedParent, onOpenChange, onQueued }: Props) {
  const [title, setTitle] = useState('');
  const [topic,setTopic]=useState('');
  const [fileType, setFileType] = useState<'VIDEO'|'DOCUMENT'|'WORKSHEET'>('DOCUMENT');
  const [target,setTarget]=useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const sending=useRef(false);
  const controller=useRef(new AbortController());
  useEffect(()=>{const active=new AbortController();controller.current=active;return()=>active.abort();},[]);
  useEffect(()=>{if(open){setTitle('');setTopic('');setTarget('');setFileType('DOCUMENT');setFile(null);setError(null);}},[open,classroom.id,account.user.id,fixedParent?.kind,fixedParent?.id]);
  const blocked = busy || classroom.archived_at != null;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (sending.current || blocked || !file) { if (!file) setError(t.material_upload_file_error); return; }
    if (file.size <= 0 || file.size > MAX_FILE_SIZE) { setError(t.material_upload_size_error); return; }
    sending.current=true; setBusy(true); setError(null);
    try {
      const parent=fixedParent ?? (target.startsWith('a:') ? {kind:'assignment' as const,id:target.slice(2)} : target.startsWith('p:') ? {kind:'announcement' as const,id:target.slice(2)} : undefined);
      const job=await enqueueUpload(account, { classroom_id: classroom.id, title, file_type: fileType, file,
        ...(topic?{topic_id:topic}:{}), ...(parent?.kind==='assignment'?{assignment_id:parent.id}:{}), ...(parent?.kind==='announcement'?{announcement_id:parent.id}:{}) }, controller.current.signal);
      onQueued(job);
      onOpenChange(false);
    } catch (cause) {
      if(!controller.current.signal.aborted)setError(cause instanceof ApiError && cause.code === 'invalid_file' ? t.material_upload_file_error : t.upload_queue_error);
    } finally { sending.current=false; if(!controller.current.signal.aborted)setBusy(false); }
  }

  return <Dialog.Root open={open} onOpenChange={next => { if (!busy) { if (!next) { setTitle(''); setTopic(''); setTarget(''); setFileType('DOCUMENT'); setFile(null); setError(null); } onOpenChange(next); } }}>
    <Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" aria-busy={busy}>
      <Dialog.Title>{t.material_upload_title}</Dialog.Title><Dialog.Description>{t.material_upload_help}</Dialog.Description>
      <form onSubmit={event => void submit(event)}>
        <div className="auth-field"><label htmlFor="material-title">{t.material_title}</label><input id="material-title" required value={title} disabled={blocked} onChange={event => setTitle(event.target.value)} /></div>
        <div className="auth-field"><label htmlFor="material-type">{t.material_file_type}</label><select id="material-type" value={fileType} disabled={blocked} onChange={event => setFileType(event.target.value as typeof fileType)}>
          <option value="DOCUMENT">{t.material_type_document}</option><option value="WORKSHEET">{t.material_type_worksheet}</option><option value="VIDEO">{t.material_type_video}</option>
        </select></div>
        {topics.length>0&&<div className="auth-field"><label htmlFor="material-topic">{t.topic_name}</label><select id="material-topic" value={topic} disabled={blocked} onChange={e=>setTopic(e.target.value)}><option value="">{t.no_topic}</option>{topics.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></div>}
        {!fixedParent&&<div className="auth-field"><label htmlFor="material-target">{t.upload_target}</label><select id="material-target" value={target} disabled={blocked} onChange={e=>setTarget(e.target.value)}><option value="">{t.upload_standalone}</option>{assignments.map(item=><option key={item.id} value={'a:'+item.id}>{t.upload_assignment}: {item.title}</option>)}{announcements.map(item=><option key={item.id} value={'p:'+item.id}>{t.upload_announcement}: {item.title}</option>)}</select></div>}
        <div className="auth-field"><label htmlFor="material-file">{t.material_file}</label><input id="material-file" type="file" required disabled={blocked} onChange={event => { setFile(event.target.files?.[0] ?? null); setError(null); }} /></div>
        {offline && <p role="status">{t.upload_offline_staging}</p>}
        {error && <p role="alert" className="join-error">{error}</p>}
        <div className="join-actions"><Dialog.Close asChild><button type="button" className="control text-button" disabled={busy}>{t.material_upload_cancel}</button></Dialog.Close>
          <button type="submit" className="control auth-submit" disabled={blocked || !title.trim() || !file}>{busy ? t.assignment_queueing : t.material_upload_submit}</button></div>
      </form>
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>;
}
