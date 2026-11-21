import { useEffect, useRef, useState, type ReactNode } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { t } from '../i18n';
import type { CachedAccount, Classroom } from '../services/auth';

interface Lesson { id: string; title: string; file_type: string; saved: boolean; chunks: {heading: string | null; text: string}[] }
interface Content { announcements: {id: string; title: string; content: string; created_at: number}[]; materials: Lesson[] }
interface Props { account: CachedAccount; classroom: Classroom; offline: boolean; people?: ReactNode; onVideo: (id: string) => void }
export default function ClassPage({account, classroom, people, onVideo}: Props) {
  const [tab, setTab] = useState('stream');
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
    <div className="material-detail-layout">
      <article className="material-detail-content"><header className="material-detail-heading"><span className="class-type-icon"><i className="ph ph-file-text" aria-hidden="true" /></span><div><h1>{lesson.title}</h1><p>{t.class_handout} / {classroom.name}</p></div></header>
        {lesson.chunks.length ? lesson.chunks.map((chunk, index) => <section key={index}>{chunk.heading && <h2>{chunk.heading}</h2>}<p className="lesson-text">{chunk.text}</p></section>) : <p>{t.lesson_text_missing}</p>}
      </article>
      <aside className="material-detail-actions" aria-label={t.class_home_study}><h2>{t.class_home_study}</h2><p className="material-save-help">{t.class_save_help}</p>
        {lesson.saved ? <p role="status">{t.lesson_saved}</p> : <button type="button" className="control tonal-button" disabled={saving || !lesson.chunks.length} onClick={() => void save(lesson)}>{saving ? t.auth_working : t.lesson_save}</button>}
        {saveError && <p role="alert">{t.lesson_save_error}</p>}
      </aside>
    </div>
  </section>;
  return <section className="class-page">
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
            <aside className="stream-sidebar" aria-labelledby="stream-classwork-title"><div><h2 id="stream-classwork-title">{t.classwork}</h2>{content && <p className="stream-material-count">{content.materials.length === 1 ? t.class_materials_one : t.class_materials_many.replace('{count}', String(content.materials.length))}</p>}</div><button type="button" className="control text-button" onClick={() => {setTab('classwork'); classworkTab.current?.focus();}}>{t.class_open_classwork}<i className="ph ph-arrow-right" aria-hidden="true" /></button></aside>
            <div className="stream-feed">{content && (content.announcements.length ? content.announcements.map(post =>
              <article className="stream-post" key={post.id}><header className="stream-post-header"><span className="class-type-icon"><i className="ph ph-megaphone" aria-hidden="true" /></span><div><h2>{post.title}</h2><time dateTime={new Date(post.created_at).toISOString()}>{new Date(post.created_at).toLocaleDateString('en')}</time></div></header><p className="lesson-text stream-post-body">{post.content}</p></article>) : <p className="empty-state">{t.stream_empty}</p>)}</div>
          </div>
        </> : <section className="classwork-section" aria-labelledby="classwork-title">
          <header className="classwork-heading"><div><h1 id="classwork-title">{t.classwork}</h1><p>{classroom.name} / {classroom.section}</p></div>{content && <p>{content.materials.length === 1 ? t.class_materials_one : t.class_materials_many.replace('{count}', String(content.materials.length))}</p>}</header>
          {content && (content.materials.length ? <ul className="material-list">{content.materials.map(item => <li key={item.id}>
            <details className="material-item"><summary className="material-row"><span className="class-type-icon"><i className={'ph '+(item.file_type === 'VIDEO' ? 'ph-play-circle' : 'ph-file-text')} aria-hidden="true" /></span><span className="material-name">{item.title}<span className="material-meta">{item.file_type === 'VIDEO' ? t.class_video : t.class_handout}</span></span>{item.saved && <small className="material-saved">{t.lesson_saved}</small>}<i className="ph ph-caret-down material-caret" aria-hidden="true" /></summary>
              <div className="material-preview"><p className="material-excerpt">{item.file_type === 'VIDEO' ? t.class_video_help : item.chunks[0]?.text || t.lesson_text_missing}</p><button type="button" className="control text-button material-open" onClick={() => {setSaveError(false); if (item.file_type === 'VIDEO') onVideo(item.id); else setLessonId(item.id);}}>{item.file_type === 'VIDEO' ? t.class_open_video : t.class_open_handout}<i className="ph ph-arrow-right" aria-hidden="true" /></button></div>
            </details></li>)}</ul> : <p className="empty-state">{t.classwork_empty}</p>)}
        </section>}

      </>}
    </div>
  </section>;
}
