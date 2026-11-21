import { useEffect, useRef, useState, type ReactNode } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { t } from '../i18n';
import ClassAuthoringDialog, { type AuthoringKind } from '../components/ClassAuthoringDialog';
import QuizCreateDialog from '../components/QuizCreateDialog';
import MaterialUploadDialog from '../components/MaterialUploadDialog';
import ClassworkEditDialog, { type ClassworkItem } from '../components/ClassworkEditDialog';
import UploadJobs from '../components/UploadJobs';
import OwlEmptyState from '../components/OwlEmptyState';
import streamOwl from '../assets/owls/owl-03.webp';
import classworkOwl from '../assets/owls/owl-11.webp';
import type { CachedAccount, Classroom } from '../services/auth';

interface Lesson { assignment_id?: string | null; announcement_id?: string | null; topic_id?: string | null; id: string; title: string; file_type: string; saved: boolean; chunks: {heading: string | null; text: string}[] }
interface Content { announcements: {id: string; title: string; content: string; created_at: number}[]; materials: Lesson[]; assignments?: {topic_id?: string | null; id: string; title: string; instructions: string | null; due_date: number; max_points: number; allow_late?: boolean}[]; topics?: {id: string; name: string; order_index?: number}[]; quizzes?: {topic_id?: string | null; id: string; title: string}[] }
interface Props { account: CachedAccount; classroom: Classroom; offline: boolean; people?: ReactNode; onVideo: (id: string) => void; onContentCreated?: () => void }
export default function ClassPage({account, classroom, people, onVideo, offline, onContentCreated}: Props) {
  const [tab, setTab] = useState('stream');
  const [composer,setComposer]=useState<AuthoringKind | 'material' | 'quiz' | null>(null);
  const [topicFilter,setTopicFilter]=useState('');
  const [notice,setNotice]=useState('');
  const [editor,setEditor]=useState<{item:ClassworkItem;action:'edit'|'delete'}|null>(null);
  const [uploadParent,setUploadParent]=useState<{kind:'assignment'|'announcement';id:string}>();
  const teacher=account.user.role==='TEACHER' && classroom.archived_at==null;
  const createdContent=()=>{setNotice(t.authoring_saved);onContentCreated?.();setRetry(value=>value+1);};
  const classworkTab = useRef<HTMLButtonElement>(null);
  const [content, setContent] = useState<Content | null>(null);
  const [error, setError] = useState(false);
  const [lessonId, setLessonId] = useState<string>();
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(false);
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    let current = true;
    void invoke<Content>('read_class_content', {hub: account.hub, userId: account.user.id, classroomId: classroom.id})
      .then(value => { if (current) {setContent(value); setError(false);} })
      .catch(() => {if (current) setError(true);});
    return () => {current = false;};
  }, [account, classroom.id, retry]);
  const materials=content?.materials.filter(item=>!item.assignment_id&&(!topicFilter||item.topic_id===topicFilter))??[];
  const assignments=content?.assignments?.filter(item=>!topicFilter||item.topic_id===topicFilter)??[];
  const quizzes=content?.quizzes?.filter(item=>!topicFilter||item.topic_id===topicFilter)??[];
  const itemCount=materials.length+assignments.length+quizzes.length;
  const lesson = content?.materials.find(item => item.id === lessonId);
  async function save(item: Lesson) {
    setSaving(true); setSaveError(false);
    try {
      const value = await invoke<Content>('read_class_content', {hub: account.hub, userId: account.user.id, classroomId: classroom.id, saveHandout: item.id});
      setContent(value);
    } catch {setSaveError(true);}
    finally {setSaving(false);}
  }
  function uploadTo(kind:'assignment'|'announcement',id:string){setUploadParent({kind,id});setComposer('material');}
  function itemActions(item:ClassworkItem){return teacher&&<details className="classwork-actions"><summary className="icon-button" aria-label={t.classwork_actions+': '+(item.kind==='topic'?item.name:item.title)}><i className="ph ph-dots-three-vertical" aria-hidden="true"/></summary><div><button type="button" disabled={offline} onClick={e=>{e.currentTarget.closest('details')?.removeAttribute('open');setEditor({item,action:'edit'});}}>{t.classwork_edit}</button><button type="button" disabled={offline} onClick={e=>{e.currentTarget.closest('details')?.removeAttribute('open');setEditor({item,action:'delete'});}}>{t.classwork_delete}</button></div></details>;}
  function attachment(item:Lesson){return <div className="attachment-entry" key={item.id}><button type="button" className="control text-button material-open" aria-label={(item.file_type==='VIDEO'?t.class_open_video:t.class_open_handout)+': '+item.title} onClick={()=>{if(item.file_type==='VIDEO')onVideo(item.id);else setLessonId(item.id);}}><span className="attachment-file-info"><strong>{item.title}</strong><small>{item.file_type==='VIDEO'?t.class_video:t.class_handout}</small></span><span className="attachment-file-art" aria-hidden="true"><i className={'ph '+(item.file_type==='VIDEO'?'ph-play-circle':'ph-file-text')}/></span></button>{itemActions({kind:'material',...item})}</div>;}
  if (lesson) return <section className="lesson-reader">
    <button type="button" className="control text-button" onClick={() => setLessonId(undefined)}><i className="ph ph-arrow-left" aria-hidden="true" />{t.classwork}</button>
    <div className="material-detail-layout">
      <article className="material-detail-content"><header className="material-detail-heading"><span className="class-type-icon"><i className="ph ph-file-text" aria-hidden="true" /></span><div><h1>{lesson.title}</h1><p>{t.class_handout} / {classroom.name}</p></div></header>
        <div className="lesson-text-card">{lesson.chunks.length ? lesson.chunks.map((chunk, index) => <section key={index}>{chunk.heading && <h2>{chunk.heading}</h2>}{chunk.text.split(/\r?\n\s*\r?\n/).map((text, paragraph) => <p className="lesson-text" key={paragraph}>{text}</p>)}</section>) : <p>{t.lesson_text_missing}</p>}</div>
      </article>
      <aside className="material-detail-actions" aria-label={t.class_home_study}><h2>{t.class_home_study}</h2><p className="material-save-help">{t.class_save_help}</p>
        {lesson.saved ? <p role="status">{t.lesson_saved}</p> : <button type="button" className="control tonal-button" disabled={saving || !lesson.chunks.length} onClick={() => void save(lesson)}>{saving ? t.auth_working : t.lesson_save}</button>}
        {saveError && <p role="alert">{t.lesson_save_error}</p>}
      </aside>
    </div>
  </section>;
  return <section className="class-page">
    {composer && composer!=='material' && composer!=='quiz' && <ClassAuthoringDialog key={composer} kind={composer} account={account} classroomId={classroom.id} offline={offline} topics={content?.topics} onClose={()=>setComposer(null)} onCreated={createdContent}/>}
    {composer==='quiz' && <QuizCreateDialog account={account} classroomId={classroom.id} offline={offline} onClose={()=>setComposer(null)} onCreated={createdContent}/>}
    {editor&&<ClassworkEditDialog key={editor.item.id+'/'+editor.action} account={account} classroomId={classroom.id} item={editor.item} action={editor.action} topics={content?.topics??[]} offline={offline} onClose={()=>setEditor(null)} onChanged={()=>{if(editor.item.kind==='topic'&&editor.action==='delete')setTopicFilter('');createdContent();setNotice(t.classwork_changed);}}/>}
    {composer==='material' && <MaterialUploadDialog account={account} classroom={classroom} open offline={offline} topics={content?.topics} assignments={content?.assignments} announcements={content?.announcements} fixedParent={uploadParent} onOpenChange={open=>{if(!open)setComposer(null);}} onQueued={()=>{setNotice(t.upload_queued);setComposer(null);}}/>}
    {notice && <p role="status" className="authoring-receipt">{notice}</p>}
    {teacher&&<UploadJobs account={account} classroomId={classroom.id} onCompleted={createdContent}/>}
    <div className="class-tabs" role="tablist" aria-label={t.class_tabs}>
      {[['stream', t.stream], ['classwork', t.classwork], ...(people ? [['people', t.people]] : [])].map(([id,label]) =>
        <button key={id} ref={id === 'classwork' ? classworkTab : undefined} id={'tab-'+id} type="button" role="tab" aria-selected={tab === id} aria-controls={'panel-'+id} tabIndex={tab === id ? 0 : -1}
          onClick={() => setTab(id)} onKeyDown={event => {
            const buttons = Array.from(event.currentTarget.parentElement!.querySelectorAll<HTMLButtonElement>('[role=tab]'));
            const index = buttons.indexOf(event.currentTarget);
            const next = event.key === 'ArrowRight' ? (index + 1) % buttons.length : event.key === 'ArrowLeft' ? (index + buttons.length - 1) % buttons.length : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1;
            if (next >= 0) {event.preventDefault(); buttons[next].click(); buttons[next].focus();}
          }}>{label}</button>)}
    </div>
    {classroom.archived_at != null && <p className="hub-status-banner banner-info" role="status">{t.class_archived_read_only}</p>}
    <div id={'panel-'+tab} role="tabpanel" aria-labelledby={'tab-'+tab}>
      {tab === 'people' ? people : <>
        {error && <p role="alert">{t.class_content_error}<button type="button" className="control text-button" onClick={() => setRetry(value => value + 1)}>{t.retry}</button></p>}
        {!content && !error && <div className="skeleton class-skeleton" role="status" aria-label={t.loading} />}
        {tab === 'stream' ? <>
          <header className="class-banner"><div><h1>{classroom.name}</h1><p>{classroom.section}</p></div><i className="ph ph-graduation-cap class-banner-art" aria-hidden="true" /></header>
          <div className="stream-layout">
            <aside className="stream-sidebar" aria-labelledby="stream-classwork-title"><div><h2 id="stream-classwork-title">{t.classwork}</h2>{content && <p className="stream-material-count">{itemCount === 1 ? t.class_items_one : t.class_items_many.replace('{count}', String(itemCount))}</p>}</div><button type="button" className="control text-button" onClick={() => {setTab('classwork'); classworkTab.current?.focus();}}>{t.class_open_classwork}<i className="ph ph-arrow-right" aria-hidden="true" /></button></aside>
            <div className="stream-feed">{teacher && <button type="button" className="control tonal-button new-announcement" disabled={offline} onClick={()=>setComposer('announcement')}><i className="ph ph-pencil-simple" aria-hidden="true"/>{t.new_announcement}</button>}{content && (content.announcements.length ? content.announcements.map(post =>
              <article className="stream-post" key={post.id}><header className="stream-post-header"><span className="class-type-icon"><i className="ph ph-megaphone" aria-hidden="true" /></span><div><h2>{post.title}</h2><time dateTime={new Date(post.created_at).toISOString()}>{new Date(post.created_at).toLocaleDateString('en')}</time></div></header><p className="lesson-text stream-post-body">{post.content}</p><div className="material-preview">{content.materials.filter(item=>item.announcement_id===post.id&&!item.assignment_id).map(attachment)}{teacher&&<button type="button" className="control text-button" onClick={()=>uploadTo('announcement',post.id)}>{t.classwork_add_attachment}</button>}</div></article>) : <OwlEmptyState variant="stream" image={streamOwl} title={t.stream_empty} />)}</div>
          </div>
        </> : <section className="classwork-section" aria-labelledby="classwork-title">
          {teacher && <details className="class-create-menu"><summary className="control auth-submit"><i className="ph ph-plus" aria-hidden="true"/>{t.authoring_create}</summary><div>{([['assignment',t.new_assignment],['quiz',t.quiz_create],['material',t.material_upload],['topic',t.new_topic]] as const).map(([kind,label])=><button type="button" key={kind} disabled={offline&&kind!=='material'} onClick={event=>{event.currentTarget.closest('details')?.removeAttribute('open');setUploadParent(undefined);setComposer(kind);}}><i className={'ph '+({assignment:'ph-clipboard-text',quiz:'ph-exam',material:'ph-book-open',topic:'ph-list-bullets'}[kind])} aria-hidden="true"/>{label}</button>)}</div></details>}
          <header className="classwork-heading"><div><h1 id="classwork-title">{t.classwork}</h1><p>{classroom.name} / {classroom.section}</p></div>{content && <p>{itemCount === 1 ? t.class_items_one : t.class_items_many.replace('{count}', String(itemCount))}</p>}</header>
          {!!content?.topics?.length&&<div className="auth-field class-topic-filter"><label htmlFor="class-topic-filter">{t.topic_name}</label><select id="class-topic-filter" value={topicFilter} onChange={e=>setTopicFilter(e.target.value)}><option value="">{t.all_topics}</option>{content.topics.map(topic=><option key={topic.id} value={topic.id}>{topic.name}</option>)}</select>{teacher&&topicFilter&&content.topics.filter(topic=>topic.id===topicFilter).map(topic=><div key={topic.id} className="topic-actions">{itemActions({kind:'topic',...topic})}</div>)}</div>}
          {assignments.map(item=><article key={item.id} className="classwork-item-shell">{itemActions({kind:'assignment',...item})}<details className="material-item assignment-preview"><summary className="material-row"><span className="class-type-icon"><i className="ph ph-clipboard-text" aria-hidden="true"/></span><span className="material-name">{item.title}</span><span>{new Date(item.due_date).toLocaleDateString('en')}</span></summary><div className="material-preview"><p className="lesson-text">{item.instructions}</p><p>{t.assignment_points}: {item.max_points}</p>{content?.materials.filter(file=>file.assignment_id===item.id).map(attachment)}{teacher&&<button type="button" className="control text-button" onClick={()=>uploadTo('assignment',item.id)}>{t.classwork_add_attachment}</button>}</div></details></article>)}
          {quizzes.map(item=><article className="material-item material-row" key={item.id}><span className="class-type-icon"><i className="ph ph-exam" aria-hidden="true"/></span><h2>{item.title}</h2></article>)}
          {content && (materials.length ? <ul className="material-list">{materials.map(item => <li key={item.id} className="classwork-item-shell">{itemActions({kind:'material',...item})}
            <details className="material-item"><summary className="material-row"><span className="class-type-icon"><i className={'ph '+(item.file_type === 'VIDEO' ? 'ph-play-circle' : 'ph-file-text')} aria-hidden="true" /></span><span className="material-name">{item.title}<span className="material-meta">{item.file_type === 'VIDEO' ? t.class_video : t.class_handout}</span></span>{item.saved && <small className="material-saved">{t.lesson_saved}</small>}<i className="ph ph-caret-down material-caret" aria-hidden="true" /></summary>
              <div className="material-preview"><p className="material-excerpt">{item.file_type === 'VIDEO' ? t.class_video_help : item.chunks[0]?.text || t.lesson_text_missing}</p><button type="button" className="control text-button material-open" aria-label={(item.file_type === 'VIDEO' ? t.class_open_video : t.class_open_handout)+': '+item.title} onClick={() => {setSaveError(false); if (item.file_type === 'VIDEO') onVideo(item.id); else setLessonId(item.id);}}><span className="attachment-file-info"><strong>{item.title}</strong><small>{item.file_type === 'VIDEO' ? t.class_video : t.class_handout}</small></span><span className="attachment-file-art" aria-hidden="true"><i className={'ph '+(item.file_type === 'VIDEO' ? 'ph-play-circle' : 'ph-file-text')} /></span></button></div>
            </details></li>)}</ul> : !assignments.length && !quizzes.length && <OwlEmptyState variant="center" image={classworkOwl} title={t.classwork_empty} body={t.classwork_empty_help} />)}
        </section>}

      </>}
    </div>
  </section>;
}
