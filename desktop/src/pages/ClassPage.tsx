import { useEffect, useState, type ReactNode } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { t } from '../i18n';
import type { CachedAccount, Classroom } from '../services/auth';

interface Lesson { id: string; title: string; file_type: string; saved: boolean; chunks: {heading: string | null; text: string}[] }
interface Content { announcements: {id: string; title: string; content: string; created_at: number}[]; materials: Lesson[] }
interface Props { account: CachedAccount; classroom: Classroom; offline: boolean; people?: ReactNode; onVideo: (id: string) => void }
export default function ClassPage({account, classroom, offline, people, onVideo}: Props) {
  const [tab, setTab] = useState('stream');
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
  const lesson = content?.materials.find(item => item.id === lessonId);
  async function save(item: Lesson) {
    setSaving(true); setSaveError(false);
    try {
      const value = await invoke<Content>('read_class_content', {hub: account.hub, userId: account.user.id, classroomId: classroom.id, saveHandout: item.id});
      setContent(value);
    } catch {setSaveError(true);}
    finally {setSaving(false);}
  }
  if (lesson) return <section className="lesson-reader">
    <button type="button" className="control text-button" onClick={() => setLessonId(undefined)}><i className="ph ph-arrow-left" aria-hidden="true" />{t.classwork}</button>
    <h1>{lesson.title}</h1>
    {lesson.saved ? <p role="status">{t.lesson_saved}</p> : <button type="button" className="control tonal-button" disabled={offline || saving || !lesson.chunks.length} onClick={() => void save(lesson)}>{saving ? t.auth_working : t.lesson_save}</button>}
    {saveError && <p role="alert">{t.lesson_save_error}</p>}
    {lesson.chunks.length ? lesson.chunks.map((chunk, index) => <section key={index}>{chunk.heading && <h2>{chunk.heading}</h2>}<p className="lesson-text">{chunk.text}</p></section>) : <p>{t.lesson_text_missing}</p>}
  </section>;
  return <section className="class-page">
    <header className="class-banner"><h1>{classroom.name}</h1><p>{classroom.section}</p></header>
    {classroom.archived_at != null && <p className="hub-status-banner banner-info" role="status">{t.class_archived_read_only}</p>}
    <div className="class-tabs" role="tablist" aria-label={t.class_tabs}>
      {[['stream', t.stream], ['classwork', t.classwork], ...(people ? [['people', t.people]] : [])].map(([id,label]) =>
        <button key={id} id={'tab-'+id} type="button" role="tab" aria-selected={tab === id} aria-controls={'panel-'+id} tabIndex={tab === id ? 0 : -1}
          onClick={() => setTab(id)} onKeyDown={event => {
            const buttons = Array.from(event.currentTarget.parentElement!.querySelectorAll<HTMLButtonElement>('[role=tab]'));
            const index = buttons.indexOf(event.currentTarget);
            const next = event.key === 'ArrowRight' ? (index + 1) % buttons.length : event.key === 'ArrowLeft' ? (index + buttons.length - 1) % buttons.length : event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : -1;
            if (next >= 0) {event.preventDefault(); buttons[next].click(); buttons[next].focus();}
          }}>{label}</button>)}
    </div>
    <div id={'panel-'+tab} role="tabpanel" aria-labelledby={'tab-'+tab}>
      {tab === 'people' ? people : <>
        {error && <p role="alert">{t.class_content_error}<button type="button" className="control text-button" onClick={() => setRetry(value => value + 1)}>{t.retry}</button></p>}
        {!content && !error && <div className="skeleton class-skeleton" role="status" aria-label={t.loading} />}
        {content && (tab === 'stream' ? content.announcements.length ? content.announcements.map(post =>
          <article className="stream-post" key={post.id}><time dateTime={new Date(post.created_at).toISOString()}>{new Date(post.created_at).toLocaleDateString('en')}</time><h2>{post.title}</h2><p className="lesson-text">{post.content}</p></article>) : <p className="empty-state">{t.stream_empty}</p>
          : content.materials.length ? <ul className="material-list">{content.materials.map(item => <li key={item.id}>
            <button type="button" className="material-row" onClick={() => {setSaveError(false); if (item.file_type === 'VIDEO') onVideo(item.id); else setLessonId(item.id);}}>
              <i className={'ph '+(item.file_type === 'VIDEO' ? 'ph-play-circle' : 'ph-file-text')} aria-hidden="true" /><span>{item.title}</span>{item.saved && <small>{t.lesson_saved}</small>}<i className="ph ph-arrow-right" aria-hidden="true" />
            </button></li>)}</ul> : <p className="empty-state">{t.classwork_empty}</p>)}
      </>}
    </div>
  </section>;
}
