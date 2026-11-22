import './AttachmentPicker.css';
import { useEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import {ApiError} from '../services/api';
import { t } from '../i18n';
import type { CachedAccount } from '../services/auth';
import { createAnnouncement, createAssignment, createTopic } from '../services/authoring';
import { updateClasswork } from '../services/classworkMutations';
import { waitForCachedAssignment } from '../services/classworkCache';
import { enqueueUpload } from '../services/uploadQueue';
export type AuthoringKind = 'announcement' | 'assignment' | 'topic';
interface Props { kind: AuthoringKind; account: CachedAccount; classroomId: string; offline: boolean; topics?: {id: string; name: string}[]; onClose: () => void; onCreated: () => void }
export default function ClassAuthoringDialog({kind, account, classroomId, offline, topics=[], onClose, onCreated}: Props) {
  const [title,setTitle]=useState(''); const [body,setBody]=useState('');
  const [topic,setTopic]=useState(''); const [files,setFiles]=useState<File[]>([]);
  const [staged,setStaged]=useState<Set<File>>(()=>new Set()); const [savedParentId,setSavedParentId]=useState<string|null>(null);
  const [points,setPoints]=useState('100'); const [due,setDue]=useState(''); const [allow,setAllow]=useState(kind==='announcement');
  const [busy,setBusy]=useState(false); const [error,setError]=useState('');
  const sending=useRef(false); const controller=useRef(new AbortController());
  useEffect(()=>{const active=new AbortController();controller.current=active;return()=>active.abort();},[]);
  useEffect(()=>{setTitle('');setBody('');setTopic('');setFiles([]);setStaged(new Set());setSavedParentId(null);setPoints('100');setDue('');setAllow(kind==='announcement');setError('');},[kind,classroomId,account.user.id]);
  const heading=kind==='announcement'?t.new_announcement:kind==='assignment'?t.new_assignment:t.new_topic;
  async function submit(event: React.FormEvent) {
    event.preventDefault(); if(sending.current || (offline && !savedParentId))return;
    sending.current=true; setBusy(true);setError('');
    let assignmentSaved=Boolean(savedParentId), refreshed=false, queueing=false;
    try {
      if(kind==='announcement') {
        let parentId=savedParentId;
        if(parentId){if(!offline)await updateClasswork(account,classroomId,'announcement',parentId,{title,content:body,allow_comments:allow},controller.current.signal);}
        else {parentId=await createAnnouncement(account,classroomId,{title,content:body,...(account.user.role==='TEACHER'?{allow_comments:allow}:{})},controller.current.signal);setSavedParentId(parentId);assignmentSaved=true;onCreated();refreshed=true;}
        if(files.some(file=>!staged.has(file))){queueing=true;await waitForCachedAssignment(account,classroomId,parentId,controller.current.signal,'announcements');}
        for(const file of files.filter(file=>!staged.has(file))) {
          await enqueueUpload(account,{classroom_id:classroomId,title:file.name.replace(/\.[^.]+$/,'')||file.name,file_type:file.type.startsWith('video/')?'VIDEO':'DOCUMENT',file,announcement_id:parentId},controller.current.signal);
          setStaged(previous=>new Set(previous).add(file));
        }
      }
      else if(kind==='assignment') {
        const input={title,instructions:body,max_points:Number(points),due_date:new Date(due).getTime(),allow_late:allow,topic_id:topic||null};
        let assignmentId=savedParentId;
        if(assignmentId) { if(!offline) await updateClasswork(account,classroomId,'assignment',assignmentId,input,controller.current.signal); }
        else { assignmentId=await createAssignment(account,classroomId,{...input,topic_id:topic||undefined},controller.current.signal); setSavedParentId(assignmentId); assignmentSaved=true; onCreated(); refreshed=true;
          if(files.length) { queueing=true; await waitForCachedAssignment(account,classroomId,assignmentId,controller.current.signal); }
        }
        const remaining=files.filter(file=>!staged.has(file)); queueing=true;
        for(const file of remaining) {
          await enqueueUpload(account,{classroom_id:classroomId,title:file.name.replace(/\.[^.]+$/,'')||file.name,file_type:file.type.startsWith('video/')?'VIDEO':'DOCUMENT',file,...(topic?{topic_id:topic}:{}),assignment_id:assignmentId},controller.current.signal);
          setStaged(previous=>new Set(previous).add(file));
        }
      } else await createTopic(account,classroomId,title,controller.current.signal);
      if(!refreshed)onCreated();onClose();
    } catch (cause) {if(!controller.current.signal.aborted)setError(kind==='announcement'&&account.user.role==='STUDENT'&&cause instanceof ApiError&&cause.code==='FORBIDDEN'?t.learner_post_hub_pending:assignmentSaved?(queueing?(kind==='assignment'?t.assignment_partial_saved:t.post_partial_saved):t.classwork_mutation_error):t.authoring_error);}
    finally {sending.current=false;if(!controller.current.signal.aborted)setBusy(false);}
  }
  const metadataBlocked=busy||(offline&&Boolean(savedParentId));
  return <Dialog.Root open onOpenChange={open=>{if(!open&&!busy)onClose();}}><Dialog.Portal><Dialog.Overlay className="join-scrim"/><Dialog.Content className="join-dialog authoring-dialog"><Dialog.Title>{heading}</Dialog.Title><Dialog.Description>{t.authoring_help}</Dialog.Description>
    <form onSubmit={event=>void submit(event)}>
      <div className="auth-field"><label htmlFor="post-title">{kind==='topic'?t.topic_name:t.post_title}</label><input id="post-title" required maxLength={200} value={title} disabled={metadataBlocked} onChange={e=>setTitle(e.target.value)}/></div>
      {kind!=='topic'&&<div className="auth-field"><label htmlFor="post-body">{kind==='announcement'?t.announcement_body:t.assignment_instructions}</label><textarea id="post-body" required={kind==='announcement'} maxLength={10000} rows={6} value={body} disabled={metadataBlocked} onChange={e=>setBody(e.target.value)}/></div>}
      {kind==='assignment'&&topics.length>0&&<div className="auth-field"><label htmlFor="post-topic">{t.topic_name}</label><select id="post-topic" value={topic} disabled={metadataBlocked} onChange={e=>setTopic(e.target.value)}><option value="">{t.no_topic}</option>{topics.map(item=><option key={item.id} value={item.id}>{item.name}</option>)}</select></div>}
      {kind==='assignment'&&<div className="authoring-fields"><div className="auth-field"><label htmlFor="post-points">{t.assignment_points}</label><input id="post-points" type="number" min="1" max="10000" required value={points} disabled={metadataBlocked} onChange={e=>setPoints(e.target.value)}/></div><div className="auth-field"><label htmlFor="post-due">{t.assignment_due}</label><input id="post-due" type="datetime-local" required value={due} disabled={metadataBlocked} onChange={e=>setDue(e.target.value)}/></div></div>}
      {kind!=='topic'&&account.user.role==='TEACHER'&&<div className="auth-field"><label htmlFor="assignment-files">{t.assignment_files}</label><div className="authoring-file-picker" data-disabled={busy}><span aria-hidden="true" title={files.map(file=>file.name).join(', ')}>{files.length?`${files.length===1?t.authoring_chosen_file:t.authoring_chosen_files} ${files.map(file=>file.name).join(', ')}`:t.authoring_choose_files}</span><input id="assignment-files" type="file" multiple disabled={busy} onChange={e=>setFiles(Array.from(e.target.files??[]))}/></div></div>}
      {kind!=='topic'&&account.user.role==='TEACHER'&&<label className="authoring-check"><input type="checkbox" checked={allow} disabled={metadataBlocked} onChange={e=>setAllow(e.target.checked)}/>{kind==='announcement'?t.announcement_allow_comments:t.assignment_allow_late}</label>}
      {savedParentId&&<p role="status">{kind==='assignment'?t.assignment_parent_saved:t.post_parent_saved}</p>}
      {offline&&!savedParentId&&<p role="status">{t.authoring_offline}</p>}{error&&<p role="status" className="join-error">{error}</p>}
      <div className="join-actions"><button type="button" className="control text-button" disabled={busy} onClick={onClose}>{t.cancel}</button><button type="submit" className="control auth-submit" disabled={busy||(offline&&!savedParentId)||!title.trim()||(kind==='announcement'&&!body.trim())}>{busy?t.assignment_queueing:kind==='announcement'?t.post_announcement:savedParentId?t.classwork_save:t.authoring_create}</button></div>
    </form></Dialog.Content></Dialog.Portal></Dialog.Root>;
}
