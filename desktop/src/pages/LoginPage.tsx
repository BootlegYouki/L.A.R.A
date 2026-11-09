import { useState, type FormEvent } from 'react';
import { t } from '../i18n';
import type { HubInfo } from '../services/discovery';
type Fields = { lrn_or_id: string; pin: string; full_name?: string };
type Errors = Partial<Record<keyof Fields, string>>;
export function validateLoginFields(fields: Fields, register: boolean): Errors {
  const errors: Errors = {};
  if (register ? !/^\d{12}$/.test(fields.lrn_or_id.trim()) : !/^.{1,32}$/u.test(fields.lrn_or_id.trim())) errors.lrn_or_id = register ? t.auth_lrn_invalid : t.auth_id_invalid;
  if (!/^\d{4}$/.test(fields.pin)) errors.pin = t.auth_pin_invalid;
  if (register && (!fields.full_name?.trim() || [...fields.full_name.trim()].length > 100)) errors.full_name = t.auth_name_invalid;
  return errors;
}
interface Props { hub: HubInfo | null; busy: boolean; error: string | null; retryAfter: number; onSubmit: (fields: Fields, register: boolean) => void; onCancel?: () => void; onNavigate?: () => void }
export default function LoginPage({ hub, busy, error, retryAfter, onSubmit, onCancel, onNavigate }: Props) {
  const [register, setRegister] = useState(false);
  const [fields, setFields] = useState<Fields>({ lrn_or_id: '', pin: '', full_name: '' });
  const [errors, setErrors] = useState<Errors>({});
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = validateLoginFields(fields, register); setErrors(next);
    if (Object.keys(next).length || busy || !hub || retryAfter > 0) return;
    onSubmit({ lrn_or_id: fields.lrn_or_id.trim(), pin: fields.pin, ...(register ? { full_name: fields.full_name?.trim() } : {}) }, register);
    setFields({ ...fields, pin: '' });
  }
  function fieldError(key: keyof Fields) { return errors[key] ? <p id={`auth-${key}-error`} className="auth-field-error"><i className="ph ph-warning-circle" aria-hidden="true" />{errors[key]}</p> : null; }
  return <section className="auth-panel" aria-labelledby="auth-title">
    <div className="auth-heading"><span className="empty-art"><i className="ph ph-user-circle" aria-hidden="true" /></span><div><h1 id="auth-title">{register ? t.auth_register : t.auth_sign_in}</h1><p>{register ? t.auth_register_help : t.auth_sign_in_help}</p></div></div>
    <p className="auth-hub">{hub ? `${t.auth_selected_hub} ${hub.name}` : t.auth_need_hub}</p>
    <form onSubmit={submit} noValidate aria-busy={busy}>
      {register && <div className="auth-field"><label htmlFor="auth-name">{t.auth_full_name}</label><input id="auth-name" name="full_name" autoComplete="name" maxLength={100} value={fields.full_name} disabled={busy} aria-invalid={!!errors.full_name} aria-describedby={errors.full_name ? 'auth-full_name-error' : undefined} onChange={(e) => setFields({ ...fields, full_name: e.target.value })} />{fieldError('full_name')}</div>}
      <div className="auth-field"><label htmlFor="auth-id">{register ? t.auth_lrn : t.auth_id}</label><input id="auth-id" name="lrn_or_id" autoComplete="username" inputMode={register ? 'numeric' : 'text'} maxLength={register ? 12 : 32} value={fields.lrn_or_id} disabled={busy} aria-invalid={!!errors.lrn_or_id} aria-describedby={errors.lrn_or_id ? 'auth-lrn_or_id-error' : undefined} onChange={(e) => setFields({ ...fields, lrn_or_id: e.target.value })} />{fieldError('lrn_or_id')}</div>
      <div className="auth-field"><label htmlFor="auth-pin">{t.auth_pin}</label><input id="auth-pin" className="auth-pin" name="pin" type="password" inputMode="numeric" autoComplete={register ? 'new-password' : 'current-password'} maxLength={4} value={fields.pin} disabled={busy} aria-invalid={!!errors.pin} aria-describedby={errors.pin ? 'auth-pin-error' : 'auth-pin-help'} onChange={(e) => setFields({ ...fields, pin: e.target.value })} /><p id="auth-pin-help">{t.auth_pin_help}</p>{fieldError('pin')}</div>
      {error && <p role="alert" className="auth-field-error"><i className="ph ph-warning-circle" aria-hidden="true" />{error}</p>}
      {retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}
      <div className="auth-actions"><button type="submit" className="control auth-submit" disabled={busy || !hub || retryAfter > 0}>{busy ? t.auth_working : register ? t.auth_register : t.auth_sign_in}</button>{onCancel && <button type="button" className="control text-button" disabled={busy} onClick={onCancel}>{t.cancel}</button>}</div>
    </form>
    <button type="button" className="control text-button auth-toggle" disabled={busy} onClick={() => { onNavigate?.(); setRegister(!register); setFields({ lrn_or_id: '', pin: '', full_name: '' }); setErrors({}); }}>{register ? t.auth_have_account : t.auth_new_learner}</button>
  </section>;
}
