import { useEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import { normalizeClassCode } from '../services/classrooms';
export { normalizeClassCode } from '../services/classrooms';
interface Props { open: boolean; onOpenChange: (open: boolean) => void; fullName: string; busy: boolean; offline: boolean; error: string | null; retryAfter: number; onJoin: (code: string) => void }
export default function JoinClassModal({ open, onOpenChange, fullName, busy, offline, error, retryAfter, onJoin }: Props) {
  const [digits, setDigits] = useState<string[]>(Array(6).fill(''));
  const returnFocus = useRef<HTMLElement | null>(null);
  const inputs = useRef<(HTMLInputElement | null)[]>([]);
  useEffect(() => { if (!open) setDigits(Array(6).fill('')); }, [open]);
  const blocked = busy || offline || retryAfter > 0;
  const code = normalizeClassCode(digits.join(''));
  function fill(value: string, index: number) {
    const clean = value.toUpperCase().replace(/[-\s]/g, '');
    if (!/^[A-HJ-NP-Z2-9]*$/.test(clean)) return;
    const start = clean.length === 6 ? 0 : index;
    const next = [...digits];
    if (!clean) next[index] = '';
    else [...clean].slice(0, 6 - start).forEach((letter, offset) => { next[start + offset] = letter; });
    setDigits(next);
    inputs.current[Math.min(start + clean.length, 5)]?.focus();
  }
  return <Dialog.Root open={open} onOpenChange={(next) => { if (!next) setDigits(Array(6).fill('')); onOpenChange(next); }}><Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" onOpenAutoFocus={() => { returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; }} onCloseAutoFocus={(event) => { event.preventDefault(); returnFocus.current?.focus(); }}>
    <Dialog.Title>{t.join_class}</Dialog.Title><Dialog.Description>{t.join_help}</Dialog.Description>
    <form aria-busy={busy} onSubmit={(event) => { event.preventDefault(); if (code && !blocked) onJoin(code); }}>
      <p>{t.join_as.replace('{name}', fullName)}</p>
      <fieldset className="join-code-field" disabled={blocked}><legend>{t.join_code_label}</legend><div className="join-code-boxes">{digits.map((digit, index) => <input key={index} ref={(element) => { inputs.current[index] = element; }} aria-label={t.join_code_character.replace('{number}', String(index + 1))} aria-invalid={!!error} aria-describedby="join-message" autoComplete="off" autoCapitalize="characters" inputMode="text" maxLength={1} value={digit} onChange={(event) => fill(event.target.value, index)} onPaste={(event) => { event.preventDefault(); fill(event.clipboardData.getData('text'), index); }} onFocus={(event) => event.target.select()} onKeyDown={(event) => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); inputs.current[Math.max(0, Math.min(5, index + (event.key === 'ArrowLeft' ? -1 : 1)))]?.focus(); }
        if (event.key === 'Backspace' && !digits[index] && index > 0) { event.preventDefault(); const next = [...digits]; next[index - 1] = ''; setDigits(next); inputs.current[index - 1]?.focus(); }
      }} />)}</div></fieldset>
      <div id="join-message" className="join-message">{error && <p role="alert" className="join-error"><i className="ph ph-warning-circle" aria-hidden="true" />{error}</p>}{offline && <p role="status">{t.join_offline}</p>}{retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}</div>
      <div className="join-actions"><Dialog.Close asChild><button type="button" className="control text-button">{t.cancel}</button></Dialog.Close><button type="submit" className="control auth-submit" disabled={blocked || !code}>{busy ? t.auth_working : t.join_submit}</button></div>
    </form>
  </Dialog.Content></Dialog.Portal></Dialog.Root>;
}
