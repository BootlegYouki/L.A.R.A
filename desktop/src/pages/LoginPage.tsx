import { useState, type FormEvent } from 'react';
import { t } from '../i18n';
import type { HubInfo } from '../services/discovery';
type Fields = { lrn_or_id: string; pin: string };
type Errors = Partial<Record<keyof Fields, string>>;
export function validateLoginFields(fields: Fields): Errors {
  const errors: Errors = {};
  if (!/^.{1,32}$/u.test(fields.lrn_or_id.trim())) errors.lrn_or_id = t.auth_id_invalid;
  if (!/^[0-9]{6}$/.test(fields.pin)) errors.pin = t.auth_pin_invalid;
  return errors;
}
interface Props { hub: HubInfo | null; busy: boolean; error: string | null; retryAfter: number; onSubmit: (fields: Fields) => void; onCancel?: () => void }
export default function LoginPage({ hub, busy, error, retryAfter, onSubmit, onCancel }: Props) {
  const [fields, setFields] = useState<Fields>({ lrn_or_id: '', pin: '' });
  const [errors, setErrors] = useState<Errors>({});
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = validateLoginFields(fields); setErrors(next);
    if (Object.keys(next).length || busy || !hub || retryAfter > 0) return;
    onSubmit({ lrn_or_id: fields.lrn_or_id.trim(), pin: fields.pin });
    setFields({ ...fields, pin: '' });
  }
  function fieldError(key: keyof Fields) { return errors[key] ? <p id={`auth-${key}-error`} className="auth-field-error"><i className="ph ph-warning-circle" aria-hidden="true" />{errors[key]}</p> : null; }
  return <section className="auth-panel" aria-labelledby="auth-title">
    <div className="auth-heading"><span className="empty-art"><i className="ph ph-user-circle" aria-hidden="true" /></span><div><h1 id="auth-title">{t.auth_sign_in}</h1><p>{t.auth_sign_in_help}</p></div></div>
    <p className="auth-hub">{hub ? `${t.auth_selected_hub} ${hub.name}` : t.auth_need_hub}</p>
    <form onSubmit={submit} noValidate aria-busy={busy}>
      <div className="auth-field"><label htmlFor="auth-id">{t.auth_id}</label><input id="auth-id" name="lrn_or_id" autoComplete="username" inputMode="text" maxLength={32} value={fields.lrn_or_id} disabled={busy} aria-invalid={!!errors.lrn_or_id} aria-describedby={errors.lrn_or_id ? 'auth-lrn_or_id-error' : undefined} onChange={(e) => setFields({ ...fields, lrn_or_id: e.target.value })} />{fieldError('lrn_or_id')}</div>
      <div className="auth-field"><label htmlFor="auth-pin">{t.auth_pin}</label><input id="auth-pin" className="auth-pin" name="pin" type="password" inputMode="numeric" autoComplete="current-password" maxLength={6} value={fields.pin} disabled={busy} aria-invalid={!!errors.pin} aria-describedby={errors.pin ? 'auth-pin-error' : 'auth-pin-help'} onChange={(e) => setFields({ ...fields, pin: e.target.value.replace(/[^0-9]/g, '') })} /><p id="auth-pin-help">{t.auth_pin_help}</p>{fieldError('pin')}</div>
      {error && <p role="alert" className="auth-field-error"><i className="ph ph-warning-circle" aria-hidden="true" />{error}</p>}
      {retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}
      <div className="auth-actions"><button type="submit" className="control auth-submit" disabled={busy || !hub || retryAfter > 0}>{busy ? t.auth_working : t.auth_sign_in}</button>{onCancel && <button type="button" className="control text-button" disabled={busy} onClick={onCancel}>{t.cancel}</button>}</div>
    </form>
    <p className="auth-help-text">{t.auth_no_account_help}</p>
  </section>;
}
