import { useEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { CachedAccount } from '../services/auth';
import { createAnnouncement, createAssignment, createTopic } from '../services/authoring';
export type AuthoringKind = 'announcement' | 'assignment' | 'topic';
interface Props { kind: AuthoringKind; account: CachedAccount; classroomId: string; offline: boolean; topics?: {id: string; name: string}[]; onClose: () => void; onCreated: () => void }
export default function ClassAuthoringDialog({kind, account, classroomId, offline, topics=[], onClose, onCreated}: Props) {
  const [title,setTitle]=useState(''); const [body,setBody]=useState('');
  const [topic,setTopic]=useState('');
  const [points,setPoints]=useState('100'); const [due,setDue]=useState(''); const [allow,setAllow]=useState(kind==='announcement');
  const [busy,setBusy]=useState(false); const [error,setError]=useState('');
  const sending=useRef(false); const controller=useRef(new AbortController());
  useEffect(()=>{const active=new AbortController();controller.current=active;return()=>active.abort();},[]);
  const heading=kind==='announcement'?t.new_announcement:kind==='assignment'?t.new_assignment:t.new_topic;
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if(sending.current || offline)return;
    sending.current=true; setBusy(true);setError('');
    try {
      if(kind==='announcement')await createAnnouncement(account,classroomId,{title,content:body,allow_comments:allow},controller.current.signal);
      else if(kind==='assignment')await createAssignment(account,classroomId,{title,instructions:body,max_points:Number(points),due_date:new Date(due).getTime(),allow_late:allow,...(topic?{topic_id:topic}:{})},controller.current.signal);
      else await createTopic(account,classroomId,title,controller.current.signal);
      onCreated();onClose();
    } catch {if(!controller.current.signal.aborted)setError(t.authoring_error);}
    finally {sending.current=false;if(!controller.current.signal.aborted)setBusy(false);}
  }
  return <Dialog.Root open onOpenChange={open=>{if(!open&&!busy)onClose();}}><Dialog.Portal><Dialog.Overlay className="join-scrim"/><Dialog.Content className="join-dialog authoring-dialog"><Dialog.Title>{heading}</Dialog.Title><Dialog.Description>{t.authoring_help}</Dialog.Description>
    <form onSubmit={event=>void submit(event)}>
      <div className="auth-field"><label htmlFor="post-title">{kind==='topic'?t.topic_name:t.post_title}</label><input id="post-title" required maxLength={200} value={title} disabled={busy} onChange={e=>setTitle(e.target.value)}/></div>
      {kind!=='topic'&&<div className="auth-field"><label htmlFor="post-body">{kind==='announcement'?t.announcement_body:t.assignment_instructions}</label><textarea id="post-body" required={kind==='announcement'} maxLength={10000} rows={6} value={body} disabled={busy} onChange={e=>setBody(e.target.value)}/></div>}
      {kind==='assignment'&&topics.length>0&&<div className="auth-field"><label htmlFor="post-topic">{t.topic_name}</label><select id="post-topic" value={topic} disabled={busy} onChange={e=>setTopic(e.target.value)}><option value="">{t.no_topic}</option>{topics.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></div>}
      {kind==='assignment'&&<div className="authoring-fields"><div className="auth-field"><label htmlFor="post-points">{t.assignment_points}</label><input id="post-points" type="number" min="1" max="10000" required value={points} disabled={busy} onChange={e=>setPoints(e.target.value)}/></div><div className="auth-field"><label htmlFor="post-due">{t.assignment_due}</label><input id="post-due" type="datetime-local" required value={due} disabled={busy} onChange={e=>setDue(e.target.value)}/></div></div>}
      {kind!=='topic'&&<label className="authoring-check"><input type="checkbox" checked={allow} disabled={busy} onChange={e=>setAllow(e.target.checked)}/>{kind==='announcement'?t.announcement_allow_comments:t.assignment_allow_late}</label>}
      {offline&&<p role="status">{t.authoring_offline}</p>}{error&&<p role="alert" className="join-error">{error}</p>}
      <div className="join-actions"><button type="button" className="control text-button" disabled={busy} onClick={onClose}>{t.cancel}</button><button type="submit" className="control auth-submit" disabled={busy||offline||!title.trim()||(kind==='announcement'&&!body.trim())}>{busy?t.auth_working:kind==='announcement'?t.post_announcement:t.authoring_create}</button></div>
    </form></Dialog.Content></Dialog.Portal></Dialog.Root>;
}
