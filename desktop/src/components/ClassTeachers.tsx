import { useEffect, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { CachedAccount, Classroom } from '../services/auth';
import { classroomTeacherNames, type ClassSettingsAction } from '../services/classrooms';

interface Props {
  account: CachedAccount;
  classroom: Classroom;
  blocked: boolean;
  busy: boolean;
  onChange: (action: ClassSettingsAction) => Promise<string | null>;
}
export default function ClassTeachers({ account, classroom, blocked, busy, onChange }: Props) {
  const [names, setNames] = useState<Record<string, string>>({});
  const [adding, setAdding] = useState(false);
  const [teacherId, setTeacherId] = useState('');
  const [removing, setRemoving] = useState<{ id: string; name: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const canManage = classroom.teacher_id === account.user.id && classroom.archived_at == null;
  useEffect(() => {
    let current = true;
    void classroomTeacherNames(account, classroom.id).then(next => {
      if (current) setNames(next);
    }).catch(() => {});
    return () => { current = false; };
  }, [account, classroom.id]);

  async function submit(action: ClassSettingsAction) {
    setError(null);
    const message = await onChange(action);
    if (message) setError(message);
    else { setAdding(false); setRemoving(null); }
  }
  const teachers = [classroom.teacher_id, ...classroom.co_teacher_ids ?? []];
  return <section aria-labelledby="class-teachers-title">
    <Dialog.Root open={canManage && (adding || removing !== null)} onOpenChange={next => {
      if (!next && !busy) { setAdding(false); setRemoving(null); }
    }}>
      <div className="people-heading">
        <h2 id="class-teachers-title">{t.class_teachers}</h2>
        {canManage && <button type="button" className="control text-button" disabled={blocked} onClick={() => { setAdding(true); setTeacherId(''); setError(null); }}><i className="ph ph-plus" aria-hidden="true" />{t.class_add_teacher}</button>}
      </div>
      <ul className="class-teacher-list">{teachers.map((id, index) => {
        const role = index === 0 ? t.class_owner : t.class_co_teacher;
        const name = (id === account.user.id ? account.user.full_name : names[id]) ?? (index === 0 ? role : t.class_teacher_unknown.replace('{number}', String(index)));
        return <li key={id}><span><strong>{name}</strong>{name !== role && <small>{role}</small>}</span>
          {canManage && index > 0 && <button type="button" className="control text-button" disabled={blocked} aria-label={t.class_remove_teacher.replace('{name}', name)} onClick={() => { setRemoving({id, name}); setError(null); }}>{t.roster_remove}</button>}
        </li>;
      })}</ul>
      <Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" aria-busy={busy}>
        <Dialog.Title>{removing ? t.class_remove_teacher_confirm.replace('{name}', removing.name) : t.class_add_teacher}</Dialog.Title>
        <Dialog.Description>{removing ? t.class_remove_teacher_help : t.class_teacher_id}</Dialog.Description>
        <form onSubmit={event => {
          event.preventDefault();
          if (blocked) return;
          if (removing) void submit({kind: 'remove', teacherId: removing.id});
          else if (teacherId.trim()) void submit({kind: 'add', teacherId});
        }}>
          {!removing && <div className="auth-field"><label htmlFor="co-teacher-id">{t.class_teacher_id}</label><input id="co-teacher-id" value={teacherId} required maxLength={32} disabled={blocked} onChange={event => setTeacherId(event.target.value)} /></div>}
          {error && <p role="alert" className="join-error">{error}</p>}
          <div className="join-actions">
            <Dialog.Close asChild><button type="button" className="control text-button" disabled={busy}>{t.cancel}</button></Dialog.Close>
            <button type="submit" className={removing ? "control destructive-button" : "control auth-submit"} disabled={blocked || (!removing && !teacherId.trim())}>{removing ? t.roster_remove : t.class_add_teacher}</button>
          </div>
        </form>
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  </section>;
}
