import { useEffect, useRef, useState, type FormEvent } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { enqueueUpload } from '../services/uploadQueue';
import { t } from '../i18n';
import type { CachedAccount } from '../services/auth';
import { deleteClasswork, updateClasswork } from '../services/classworkMutations';

export type ClassworkItem =
  | {kind: 'announcement'; id:string;title:string;content:string;allow_comments?:boolean;author_id?:string|null}
  | {kind: 'assignment'; id: string; title: string; instructions: string | null; max_points: number; due_date: number; allow_late?: boolean; topic_id?: string | null}
  | {kind: 'material'; id: string; title: string; topic_id?: string | null}
  | {kind: 'topic'; id: string; name: string; order_index?: number};
interface Props { account: CachedAccount; classroomId: string; item: ClassworkItem; attachments?: {id:string;title:string}[]; topics: {id: string; name: string}[]; offline: boolean; action?: 'edit' | 'delete'; onClose: () => void; onChanged: () => void }
const localDateTime = (epoch: number) => {
  const date = new Date(epoch - new Date(epoch).getTimezoneOffset() * 60000);
  return date.toISOString().slice(0, 16);
};

export default function ClassworkEditDialog({account, classroomId, item, topics, attachments=[], offline, action='edit', onClose, onChanged}: Props) {
  const [files,setFiles]=useState<File[]>([]);
  const staged=useRef(new Set<File>());
  const [removed,setRemoved]=useState<string[]>([]);
  const [title, setTitle] = useState(item.kind === 'topic' ? item.name : item.title);
  const [instructions, setInstructions] = useState(item.kind==='announcement'?item.content:item.kind === 'assignment' ? item.instructions ?? '' : '');
  const [points, setPoints] = useState(item.kind === 'assignment' ? String(item.max_points) : '100');
  const [due, setDue] = useState(item.kind === 'assignment' ? localDateTime(item.due_date) : '');
  const [allowLate, setAllowLate] = useState(item.kind==='announcement'?item.allow_comments??true:item.kind === 'assignment' ? item.allow_late ?? false : false);
  const [topicId, setTopicId] = useState(item.kind === 'topic'||item.kind==='announcement' ? '' : item.topic_id ?? '');
  const [order, setOrder] = useState(item.kind === 'topic' ? String(item.order_index ?? 0) : '0');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const sending = useRef(false);
  const controller = useRef(new AbortController());
  useEffect(() => { const active = new AbortController(); controller.current = active; return () => active.abort(); }, []);
  const blocked = busy || offline || account.classrooms.find(room => room.id === classroomId)?.archived_at != null;
  const label = item.kind==='announcement'?t.classwork_announcement:item.kind === 'assignment' ? t.classwork_assignment : item.kind === 'material' ? t.classwork_material : t.classwork_topic;

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (sending.current || blocked) return;
    sending.current = true; setBusy(true); setError(false);
    try {
      if (action === 'delete') await deleteClasswork(account, classroomId, item.kind, item.id, controller.current.signal);
      else if(item.kind==='announcement')await updateClasswork(account,classroomId,item.kind,item.id,{title,content:instructions,allow_comments:allowLate},controller.current.signal);
      else if (item.kind === 'assignment') await updateClasswork(account, classroomId, item.kind, item.id, {
        title, instructions, max_points: Number(points), due_date: new Date(due).getTime(), allow_late: allowLate, topic_id: topicId || null,
      }, controller.current.signal);
      else if (item.kind === 'material') await updateClasswork(account, classroomId, item.kind, item.id, {title, topic_id: topicId || null}, controller.current.signal);
      else await updateClasswork(account, classroomId, item.kind, item.id, {name: title, order_index: Number(order)}, controller.current.signal);
      if(action==='edit'&&(item.kind==='assignment'||item.kind==='announcement')) {
        for(const file of files.filter(file=>!staged.current.has(file))) {
          await enqueueUpload(account,{classroom_id:classroomId,title:file.name.replace(/\.[^.]+$/,'')||file.name,file_type:file.type.startsWith('video/')?'VIDEO':'DOCUMENT',file,...(item.kind==='assignment'?{assignment_id:item.id}:{announcement_id:item.id})},controller.current.signal);
          staged.current.add(file);
        }
      }
      onChanged(); onClose();
    } catch {
      if (!controller.current.signal.aborted) setError(true);
    } finally { sending.current = false; if (!controller.current.signal.aborted) setBusy(false); }
  }

  return <Dialog.Root open onOpenChange={open => { if (!open && !busy) onClose(); }}><Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog authoring-dialog" aria-busy={busy}>
    <Dialog.Title>{action === 'delete' ? t.classwork_delete_title : t.classwork_edit_title}</Dialog.Title><Dialog.Description>{action === 'delete' ? t.classwork_delete_help : t.classwork_edit_help}</Dialog.Description>
    <form onSubmit={event => void submit(event)}>
      {action === 'delete' ? <p>{t.classwork_delete_confirm.replace('{item}', label)}</p> : <>
        <div className="auth-field"><label htmlFor="classwork-edit-title">{item.kind === 'topic' ? t.topic_name : t.post_title}</label><input id="classwork-edit-title" required maxLength={200} value={title} disabled={blocked} onChange={event => setTitle(event.target.value)} /></div>
        {item.kind==='announcement'&&<><div className="auth-field"><label htmlFor="announcement-edit-content">{t.announcement_body}</label><textarea id="announcement-edit-content" required maxLength={10000} value={instructions} disabled={blocked} onChange={e=>setInstructions(e.target.value)}/></div><label className="authoring-check"><input type="checkbox" checked={allowLate} disabled={blocked} onChange={e=>setAllowLate(e.target.checked)}/>{t.announcement_allow_comments}</label></>}
        {item.kind === 'assignment' && <>
          <div className="auth-field"><label htmlFor="classwork-edit-instructions">{t.assignment_instructions}</label><textarea id="classwork-edit-instructions" rows={6} value={instructions} disabled={blocked} onChange={event => setInstructions(event.target.value)} /></div>
          <div className="authoring-fields"><div className="auth-field"><label htmlFor="classwork-edit-points">{t.assignment_points}</label><input id="classwork-edit-points" type="number" min="1" step="1" required value={points} disabled={blocked} onChange={event => setPoints(event.target.value)} /></div>
            <div className="auth-field"><label htmlFor="classwork-edit-due">{t.assignment_due}</label><input id="classwork-edit-due" type="datetime-local" required value={due} disabled={blocked} onChange={event => setDue(event.target.value)} /></div></div>
          <label className="authoring-check"><input type="checkbox" checked={allowLate} disabled={blocked} onChange={event => setAllowLate(event.target.checked)} />{t.assignment_allow_late}</label>
        </>}
        {item.kind !== 'topic' && item.kind!=='announcement' && <div className="auth-field"><label htmlFor="classwork-edit-topic">{t.topic_name}</label><select id="classwork-edit-topic" value={topicId} disabled={blocked} onChange={event => setTopicId(event.target.value)}><option value="">{t.no_topic}</option>{topics.map(topic => <option key={topic.id} value={topic.id}>{topic.name}</option>)}</select></div>}
        {item.kind === 'topic' && <div className="auth-field"><label htmlFor="classwork-edit-order">{t.classwork_order}</label><input id="classwork-edit-order" type="number" min="0" step="1" required value={order} disabled={blocked} onChange={event => setOrder(event.target.value)} /></div>}
      </>}
      {action==='edit'&&(item.kind==='assignment'||item.kind==='announcement')&&<div className="auth-field"><label htmlFor="edit-attachments">{t.assignment_files}</label><input id="edit-attachments" type="file" multiple disabled={busy} onChange={e=>setFiles(Array.from(e.target.files??[]))}/>{attachments.filter(file=>!removed.includes(file.id)).map(file=><div className="attachment-entry" key={file.id}><span>{file.title}</span><button type="button" className="control text-button" disabled={blocked} onClick={()=>{void deleteClasswork(account,classroomId,'material',file.id,controller.current.signal).then(()=>{setRemoved(ids=>[...ids,file.id]);onChanged();}).catch(()=>setError(true));}}>{t.classwork_delete}</button></div>)}</div>}
      {offline && <p role="status">{t.classwork_offline}</p>}{error && <p role="alert" className="join-error">{t.classwork_mutation_error}</p>}
      <div className="join-actions"><button type="button" className="control text-button" disabled={busy} onClick={onClose}>{t.cancel}</button><button type="submit" className="control auth-submit" disabled={blocked || (action === 'edit' && !title.trim())}>{busy ? t.auth_working : action === 'delete' ? t.classwork_delete : t.classwork_save}</button></div>
    </form>
  </Dialog.Content></Dialog.Portal></Dialog.Root>;
}
