import { useEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { Classroom } from '../services/auth';
export const formatClassCode = (code: string) => `${code.slice(0, 3)}-${code.slice(3)}`;
export const validClassroomName = (name: string) => name.trim().length > 0;
interface Props { open: boolean; onOpenChange: (open: boolean) => void; busy: boolean; offline: boolean; error: string | null; retryAfter: number; result: Classroom | null; onCreate: (name: string, section: string) => void }
export default function CreateClassroomDialog({ open, onOpenChange, busy, offline, error, retryAfter, result, onCreate }: Props) {
  const [name, setName] = useState('');
  const [section, setSection] = useState('');
  const [copyMessage, setCopyMessage] = useState('');
  const returnFocus = useRef<HTMLElement | null>(null);
  const done = useRef<HTMLButtonElement | null>(null);
  useEffect(() => { if (open && result) done.current?.focus(); }, [open, result]);
  useEffect(() => { if (!open) { setName(''); setSection(''); setCopyMessage(''); } }, [open]);
  const blocked = busy || offline || retryAfter > 0;
  async function copy() {
    if (!result) return;
    try { await navigator.clipboard.writeText(formatClassCode(result.class_code)); setCopyMessage(t.class_code_copied); }
    catch { setCopyMessage(t.class_code_copy_failed); }
  }
  return <Dialog.Root open={open} onOpenChange={onOpenChange}><Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" onOpenAutoFocus={() => { returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; }} onCloseAutoFocus={(event) => { event.preventDefault(); returnFocus.current?.focus(); }}>
    <Dialog.Title>{result ? t.class_created : t.create_class}</Dialog.Title><Dialog.Description>{result ? t.class_created_help : t.create_class_help}</Dialog.Description>
    {result ? <><p className="generated-class-code">{formatClassCode(result.class_code)}</p>{error && <p role="alert" className="join-error">{error}</p>}<p role="status">{copyMessage}</p><div className="join-actions"><button className="control text-button" type="button" onClick={() => { void copy(); }}>{t.copy_class_code}</button><Dialog.Close asChild><button ref={done} type="button" className="control auth-submit">{t.done}</button></Dialog.Close></div></> : <form aria-busy={busy} onSubmit={(event) => { event.preventDefault(); if (!blocked && validClassroomName(name)) onCreate(name.trim(), section.trim()); }}>
      <div className="auth-field"><label htmlFor="class-name">{t.class_name}</label><input id="class-name" required value={name} disabled={blocked} onChange={(event) => setName(event.target.value)} /></div>
      <div className="auth-field"><label htmlFor="class-section">{t.class_section}</label><input id="class-section" value={section} disabled={blocked} onChange={(event) => setSection(event.target.value)} /></div>
      {offline && <p role="status">{t.create_class_offline}</p>}{error && <p role="alert" className="join-error">{error}</p>}{retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}
      <div className="join-actions"><Dialog.Close asChild><button type="button" className="control text-button">{t.cancel}</button></Dialog.Close><button type="submit" className="control auth-submit" disabled={blocked || !validClassroomName(name)}>{busy ? t.auth_working : t.create_class}</button></div>
    </form>}
  </Dialog.Content></Dialog.Portal></Dialog.Root>;
}
