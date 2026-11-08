import { useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { Classroom } from '../services/auth';
import type { ClassSettingsAction } from '../services/classrooms';
import { validClassroomName } from './CreateClassroomDialog';

interface Props {
  classroom: Classroom;
  blocked: boolean;
  offline: boolean;
  busy: boolean;
  onChange: (action: ClassSettingsAction) => Promise<string | null>;
}
export default function ClassSettingsDialog({ classroom, blocked, offline, busy, onChange }: Props) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [name, setName] = useState('');
  const [section, setSection] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function submit(action: ClassSettingsAction) {
    setError(null);
    const message = await onChange(action);
    if (message) setError(message);
    else setOpen(false);
  }
  return <Dialog.Root open={open} onOpenChange={next => {
    if (busy) return;
    if (next) {
      setName(classroom.name);
      setSection(classroom.section);
      setConfirming(false);
      setError(null);
    }
    setOpen(next);
  }}>
    <Dialog.Trigger asChild><button type="button" className="icon-button" aria-label={t.class_settings} title={t.class_settings}><i className="ph ph-dots-three-vertical" aria-hidden="true" /></button></Dialog.Trigger>
    <Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" aria-busy={busy}>
      <Dialog.Title>{confirming ? t.class_archive_confirm : t.class_settings}</Dialog.Title>
      <Dialog.Description>{confirming ? t.class_archive_help : t.class_settings_help}</Dialog.Description>
      <form onSubmit={event => {
        event.preventDefault();
        if (blocked) return;
        if (confirming) void submit({kind: 'settings', change: {archived: true}});
        else if (validClassroomName(name)) void submit({kind: 'settings', change: {name: name.trim(), section: section.trim()}});
      }}>
        {!confirming && <>
          <div className="auth-field"><label htmlFor="settings-class-name">{t.class_name}</label><input id="settings-class-name" required value={name} disabled={blocked} onChange={event => setName(event.target.value)} /></div>
          <div className="auth-field"><label htmlFor="settings-class-section">{t.class_section}</label><input id="settings-class-section" value={section} disabled={blocked} onChange={event => setSection(event.target.value)} /></div>
        </>}
        {offline && <p role="status">{t.class_settings_offline}</p>}
        {error && <p role="alert" className="join-error">{error}</p>}
        <div className="join-actions">
          <Dialog.Close asChild><button type="button" className="control text-button" disabled={busy}>{t.cancel}</button></Dialog.Close>
          {!confirming && <button type="button" className="control text-button" disabled={blocked} onClick={() => { setConfirming(true); setError(null); }}>{t.class_archive}</button>}
          <button type="submit" className={confirming ? "control destructive-button" : "control auth-submit"} disabled={blocked || (!confirming && (!validClassroomName(name) || (name.trim() === classroom.name && section.trim() === classroom.section)))}>{confirming ? t.class_archive : t.class_save}</button>
        </div>
      </form>
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>;
}
