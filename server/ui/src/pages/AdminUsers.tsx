import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { HubError, request, setupCompleted, type AuthSession, type User } from '../admin';
import { useStrings } from '../i18n';

function Dialog({ title, close, children, busy = false }: {
  title: string; close: () => void; children: ReactNode; busy?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (previous instanceof HTMLElement) previous.focus();
    };
  }, []);
  return <dialog ref={ref} aria-labelledby="dialog-title" className="admin-dialog" onCancel={event => {
    event.preventDefault();
    if (!busy) close();
  }}>
    <h2 id="dialog-title" className="mb-4 text-xl font-black">{title}</h2>
    {children}
  </dialog>;
}

function PinInput({ value, change }: { value: string; change: (value: string) => void }) {
  const { t } = useStrings();
  const inputs = useRef<(HTMLInputElement | null)[]>([]);
  const [lettersRefused, setLettersRefused] = useState(false);
  function digit(index: number, next: string) {
    const digits = value.padEnd(4, ' ').split('');
    digits[index] = next || ' ';
    change(digits.join(''));
  }
  return <fieldset className="flex flex-col gap-2"><legend className="mb-2 font-extrabold">{t.admin_pin}</legend>
    <div className="flex gap-2">{[0, 1, 2, 3].map(index => <input key={index} ref={node => { inputs.current[index] = node; }}
      className="admin-pin-digit" aria-label={t.admin_pin_digit.replace('{digit}', String(index + 1))}
      aria-describedby="pin-help" type="password" inputMode="numeric" pattern="[0-9]" maxLength={1}
      autoComplete="off" value={value[index]?.trim() ?? ''}
      onChange={event => {
        const next = event.target.value;
        const isDigit = /^[0-9]?$/.test(next);
        setLettersRefused(!isDigit);
        if (!isDigit) return;
        digit(index, next);
        if (next) inputs.current[index + 1]?.focus();
      }} onKeyDown={event => {
        if (event.key === 'Backspace' && !value[index]?.trim() && index > 0) {
          event.preventDefault(); digit(index - 1, ''); inputs.current[index - 1]?.focus();
        }
      }} onPaste={event => {
        const pasted = event.clipboardData.getData('text');
        if (/^[0-9]{4}$/.test(pasted)) {
          event.preventDefault(); change(pasted); inputs.current[3]?.focus();
        }
      }} />)}</div>
    <p id="pin-help" className="text-sm text-lara-ink-2" aria-live="polite">
      {lettersRefused ? t.admin_pin_digits_only : t.admin_pin_help}
    </p>
  </fieldset>;
}

export default function AdminUsers() {
  const { t } = useStrings();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [setup, setSetup] = useState(() => !setupCompleted());
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(false);
  const [offline, setOffline] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [form, setForm] = useState<'create' | User | null>(null);
  const [receipt, setReceipt] = useState<{ name: string; pin: string } | null>(null);
  const [name, setName] = useState('');
  const [pin, setPin] = useState('');
  const [id, setId] = useState('');
  const [role, setRole] = useState<'TEACHER' | 'STUDENT'>('TEACHER');

  const fail = useCallback((cause: unknown) => {
    const code = cause instanceof HubError ? cause.code : 'UNKNOWN';
    if (code === 'HUB_UNREACHABLE') setOffline(true);
    else if (code === 'UNAUTHORIZED' && session) {
      setSession(null); setUsers([]); setForm(null); setReceipt(null); setPin('');
      setNotice(t.admin_session_expired);
    } else {
      setError(code === 'UNAUTHORIZED' ? t.admin_wrong_pin
        : code === 'ALREADY_EXISTS' ? t.admin_duplicate
        : code === 'TOO_MANY_ATTEMPTS' ? t.admin_rate_limit
        : code === 'NOT_FOUND' ? t.admin_not_found
        : code === 'FORBIDDEN' || code === 'ADMIN_LOCAL_ONLY' ? t.admin_forbidden
        : code === 'BAD_REQUEST' ? t.admin_invalid
        : t.admin_error);
    }
  }, [session, t]);

  const refresh = useCallback(async () => {
    if (!session) return;
    try {
      const data = await request<User[]>('/api/admin/users', session.token);
      setUsers(data); setOffline(false);
    } catch (cause) { fail(cause); }
    finally { setLoading(false); }
  }, [session, fail]);

  useEffect(() => {
    if (!session) return;
    setLoading(true);
    void refresh();
    const timer = setInterval(() => void refresh(), 5000);
    return () => clearInterval(timer);
  }, [session, refresh]);

  useEffect(() => {
    if (!session) return;
    const timer = setTimeout(() => {
      setSession(null); setUsers([]); setPin(''); setReceipt(null); setForm(null);
      setNotice(t.admin_session_expired);
    }, Math.max(0, session.expires_at - Date.now()));
    return () => clearTimeout(timer);
  }, [session, t]);

  // The digit boxes are not `required`, so a short PIN gets this message in the form's error area
  // instead of the browser's "Please fill out this field." bubble on one box.
  function pinComplete() {
    if (/^[0-9]{4}$/.test(pin)) return true;
    setError(t.admin_pin_short);
    return false;
  }

  async function authenticate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!pinComplete()) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const data = await request<AuthSession>(setup ? '/api/admin/setup' : '/api/auth/login', undefined,
        setup ? { full_name: name.trim(), pin } : { lrn_or_id: 'ADMIN-0001', pin });
      if (data.user.role !== 'ADMIN' || data.user.lrn_or_id !== 'ADMIN-0001') throw new HubError('FORBIDDEN', 403);
      setupCompleted(true); setSetup(false); setLoading(true); setSession(data); setOffline(false); setPin(''); setName('');
    } catch (cause) {
      if (cause instanceof HubError && cause.code === 'ADMIN_EXISTS') {
        setupCompleted(true); setSetup(false); setPin(''); setNotice(t.admin_exists);
      } else { fail(cause); }
    } finally { setBusy(false); }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!session || !form || !pinComplete()) return;
    setBusy(true); setError('');
    try {
      const created = form === 'create'
        ? await request<User>('/api/admin/users', session.token, { lrn_or_id: id.trim(), full_name: name.trim(), role, pin })
        : null;
      if (form !== 'create') await request(`/api/admin/users/${encodeURIComponent(form.id)}/reset-pin`, session.token, { pin });
      if (created) setUsers(previous => [...previous, created]);
      setReceipt({ name: created?.full_name ?? (form as User).full_name, pin });
      setPin(''); setName(''); setId(''); setForm(null); setOffline(false);
      void refresh();
    } catch (cause) { fail(cause); }
    finally { setBusy(false); }
  }

  async function signOut() {
    if (!session) return;
    setBusy(true); setError('');
    try {
      await request('/api/auth/logout', session.token, {});
    } catch {
      // Forget the local token even if the Hub cannot revoke it; the server session expires.
    } finally {
      setSession(null); setUsers([]); setPin(''); setReceipt(null); setForm(null); setOffline(false); setBusy(false);
    }
  }

  function closeForm() { setForm(null); setPin(''); setName(''); setId(''); setError(''); }
  const pinInput = <PinInput value={pin} change={setPin} />;
  const feedback = <>
    {offline && <div className="admin-offline" role="status"><i className="ph ph-cloud-slash" aria-hidden="true" />
      <p><strong>{t.admin_offline_title}: </strong>{session ? t.admin_offline_body : t.admin_offline_auth}</p>
    </div>}
    {error && <p role="alert" className="admin-error"><i className="ph ph-warning-circle" aria-hidden="true" />{error}</p>}
    {notice && <p role="status" className="text-sm text-lara-ink-2">{notice}</p>}
  </>;

  if (!session) return <section className="admin-auth" aria-labelledby="admin-title">
    <h1 id="admin-title" className="text-2xl font-black">{setup ? t.admin_setup_title : t.admin_login_title}</h1>
    <p className="text-sm text-lara-ink-2">{setup ? t.admin_setup_help : t.admin_login_help}</p>
    <form className="flex flex-col gap-4" onSubmit={authenticate}>
      <fieldset disabled={busy} className="flex flex-col gap-4">
        {setup && <label className="admin-label">{t.admin_full_name}<input name="full_name" required value={name} onChange={e => setName(e.target.value)} /></label>}
        {pinInput}
        {feedback}
        <button className="admin-primary" type="submit">{busy ? t.admin_working : setup ? t.admin_setup_action : t.admin_login_action}</button>
        <button type="button" className="admin-secondary" onClick={() => { setSetup(!setup); setPin(''); setError(''); setNotice(''); }}>
          {setup ? t.admin_existing_action : t.admin_first_run_action}
        </button>
      </fieldset>
    </form>
  </section>;

  return <section className="flex flex-col gap-4" aria-labelledby="admin-title">
    <div className="flex flex-wrap items-center justify-between gap-4">
      <div><h1 id="admin-title" className="text-2xl font-black">{t.admin_accounts}</h1><p className="text-sm text-lara-ink-2">{t.admin_accounts_help}</p></div>
      <button type="button" className="admin-secondary" disabled={busy} onClick={() => void signOut()}>{t.admin_sign_out}</button>
    </div>
    {!form && feedback}
    <div className="flex flex-wrap gap-2">
      <button type="button" className="admin-primary" disabled={offline || busy} onClick={() => {
        setError(''); setPin(''); setName(''); setId(''); setRole('TEACHER'); setForm('create');
      }}><i className="ph ph-user-plus" aria-hidden="true" />{t.admin_create}</button>
      <button type="button" className="admin-secondary" onClick={() => { setLoading(true); void refresh(); }}>{t.admin_refresh}</button>
    </div>
    {loading && users.length === 0 ? <div role="status" aria-label={t.admin_loading} className="flex flex-col gap-3 rounded-lg bg-white p-6">
      <p>{t.admin_loading}</p>{[0, 1, 2].map(n => <div key={n} className="skeleton h-control rounded-md bg-lara-border" />)}
    </div> : users.length === 0 ? <div className="rounded-lg border border-lara-border bg-white p-8">
      <h2 className="text-xl font-black">{t.admin_empty_title}</h2><p>{t.admin_empty_body}</p>
    </div> : <div className="overflow-x-auto rounded-lg border border-lara-border bg-white">
      <table className="admin-table"><caption className="sr-only">{t.admin_accounts}</caption>
        <thead><tr><th scope="col">{t.admin_full_name}</th><th scope="col">{t.admin_id}</th><th scope="col">{t.admin_role}</th><th scope="col">{t.admin_action}</th></tr></thead>
        <tbody>{users.map(user => <tr key={user.id}><th scope="row">{user.full_name}</th><td>{user.lrn_or_id}</td>
          <td>{user.role === 'ADMIN' ? t.admin_role_admin : user.role === 'TEACHER' ? t.admin_role_teacher : t.admin_role_learner}</td>
          <td>{user.role !== 'ADMIN' && <button className="admin-secondary" type="button" disabled={offline || busy}
            aria-label={`${t.admin_reset} ${user.full_name}`} onClick={() => { setPin(''); setError(''); setForm(user); }}>{t.admin_reset}</button>}</td>
        </tr>)}</tbody>
      </table>
    </div>}
    {form && <Dialog title={form === 'create' ? t.admin_create : t.admin_reset} close={closeForm} busy={busy}>
      <form onSubmit={save} className="flex flex-col gap-4"><fieldset disabled={busy} className="flex flex-col gap-4">
        {form === 'create' ? <>
          <label className="admin-label">{t.admin_full_name}<input name="full_name" required value={name} onChange={e => setName(e.target.value)} /></label>
          <label className="admin-label">{t.admin_id}<input name="lrn_or_id" required value={id} onChange={e => setId(e.target.value)} /></label>
          <fieldset className="flex flex-wrap gap-4"><legend className="mb-2 font-extrabold">{t.admin_role}</legend>
            {(['TEACHER', 'STUDENT'] as const).map(value => <label key={value} className="flex min-h-control items-center gap-2 font-bold">
              <input type="radio" name="role" value={value} checked={role === value} onChange={() => setRole(value)} />
              {value === 'TEACHER' ? t.admin_role_teacher : t.admin_role_learner}
            </label>)}
          </fieldset>
        </> : <p>{t.admin_reset_help} <strong>{form.full_name}</strong></p>}
        {pinInput}{feedback}
        <div className="flex flex-wrap justify-end gap-2"><button type="button" className="admin-secondary" onClick={closeForm}>{t.admin_cancel}</button>
          <button type="submit" className="admin-primary" disabled={offline}>{busy ? t.admin_working : form === 'create' ? t.admin_create : t.admin_reset}</button></div>
      </fieldset></form>
    </Dialog>}
    {receipt && <Dialog title={t.admin_pin_saved} close={() => setReceipt(null)}>
      <p className="mb-2 font-bold">{receipt.name}</p><p className="mb-4 text-sm">{t.admin_pin_once}</p>
      <p className="mb-6 text-3xl font-black tracking-widest">{receipt.pin}</p>
      <button className="admin-primary" type="button" onClick={() => setReceipt(null)}>{t.admin_pin_dismiss}</button>
    </Dialog>}
  </section>;
}
