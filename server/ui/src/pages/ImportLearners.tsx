import { useEffect, useRef, useState } from 'react';
import { HubError, request, type AuthSession, type User } from '../admin';
import { useStrings } from '../i18n';
import { credentialCsv, createRoster, markExisting, MAX_IMPORT_BYTES, rosterTemplate, type ImportIssue, type ImportRow } from '../learnerImport';
import { saveImportFile } from '../importFiles';
import Dialog from './AdminDialog';

export default function ImportLearners({ session, users, close, refresh, fail }: {
  session: AuthSession; users: User[]; close: () => void; refresh: () => Promise<void>; fail: (error: unknown) => void;
}) {
  const { t } = useStrings();
  const [rows, setRows] = useState<ImportRow[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [started, setStarted] = useState(false);
  const [discard, setDiscard] = useState(false);
  const active = useRef(true);
  const worker = useRef<Worker | null>(null);
  const issueText: Record<ImportIssue, string> = {
    file: t.import_error_file, headers: t.import_error_headers, sheet: t.import_error_sheet,
    limit: t.import_error_limit, empty: t.import_error_empty, lrn: t.import_error_lrn,
    name: t.import_error_name, formula: t.import_error_formula, duplicate: t.import_error_duplicate,
  };
  const statusText = {
    pending: t.import_pending, skipped: t.import_skipped, invalid: t.import_invalid,
    created: t.import_created, uncertain: t.import_uncertain, failed: t.import_failed,
  };
  useEffect(() => {
    active.current = true;
    return () => { active.current = false; worker.current?.terminate(); };
  }, []);
  const count = (status: ImportRow['status']) => rows.filter(row => row.status === status).length;
  const created = count('created');
  const pending = count('pending');
  const invalid = count('invalid');
  const uncertain = count('uncertain');

  async function choose(file: File | undefined) {
    if (!file) return;
    setRows([]); setStarted(false); setError(''); setNotice(''); setDiscard(false);
    if (file.size > MAX_IMPORT_BYTES || !/\.(xlsx|csv)$/i.test(file.name)) { setError(t.import_error_file); return; }
    setBusy(true);
    try {
      const bytes = await file.arrayBuffer();
      if (!active.current) return;
      const parser = new Worker(new URL('../learnerImport.worker.ts', import.meta.url), { type: 'module' });
      worker.current = parser;
      parser.onmessage = (event: MessageEvent<{ rows?: ImportRow[]; issue?: ImportIssue }>) => {
        if (active.current) {
          if (event.data.rows) setRows(markExisting(event.data.rows, users));
          else setError(issueText[event.data.issue ?? 'file']);
          setBusy(false);
        }
        parser.terminate(); worker.current = null;
      };
      parser.onerror = () => {
        if (active.current) { setError(t.import_error_file); setBusy(false); }
        parser.terminate(); worker.current = null;
      };
      parser.postMessage({ bytes, filename: file.name }, [bytes]);
    } catch {
      if (active.current) { setError(t.import_error_file); setBusy(false); }
    }
  }

  async function run() {
    if (busy || invalid) return;
    setBusy(true); setStarted(true); setError(''); setNotice('');
    try {
      const final = await createRoster(rows, {
        list: () => request<User[]>('/api/admin/users', session.token),
        create: (row, pin) => request<User>('/api/admin/users', session.token,
          { lrn_or_id: row.lrn, full_name: row.name, role: 'STUDENT', pin }),
      }, setRows, () => active.current);
      if (active.current) { setRows(final); setNotice(t.import_complete); }
    } catch (cause) {
      if (active.current) {
        setError(t.import_interrupted);
        if (cause instanceof HubError && (cause.status === 401 || cause.status === 403)) fail(cause);
      }
    } finally {
      if (active.current) { setBusy(false); void refresh(); }
    }
  }

  async function save(excel?: boolean) {
    setBusy(true); setError(''); setNotice('');
    try {
      const credentials = excel === undefined;
      const filename = credentials ? 'learner-credentials.csv' : `learner-template.${excel ? 'xlsx' : 'csv'}`;
      const saved = await saveImportFile(session.token, filename, credentials ? credentialCsv(rows) : rosterTemplate(excel), t.import_save_title);
      if (active.current && saved) setNotice(credentials ? t.import_saved : t.import_template_saved);
    } catch { if (active.current) setError(t.import_save_failed); }
    finally { if (active.current) setBusy(false); }
  }

  function dismiss() {
    if (busy) return;
    if (created > 0 && !discard) setDiscard(true);
    else close();
  }

  return <Dialog title={t.import_title} close={dismiss} busy={busy} wide>
    <div className="flex flex-col gap-4">
      <p>{t.import_help}</p>
      <p className="rounded-md border border-lara-border bg-lara-subtle p-3 text-sm">{t.import_pin_help}</p>
      {!started && <>
        <div className="flex flex-wrap gap-2">
          <button className="admin-secondary" disabled={busy} onClick={() => void save(false)}>{t.import_csv_template}</button>
          <button className="admin-secondary" disabled={busy} onClick={() => void save(true)}>{t.import_excel_template}</button>
        </div>
        <label className="admin-label">{t.import_file}
          <input type="file" accept=".csv,.xlsx" disabled={busy} onClick={event => { event.currentTarget.value = ''; }}
            onChange={event => { void choose(event.target.files?.[0]); }} />
        </label>
      </>}
      {error && <p role="alert" className="admin-error">{error}</p>}
      {notice && <p role="status">{notice}</p>}
      <p role="status" aria-live="polite" aria-atomic="true">{busy && !rows.length ? t.import_reading :
        t.import_counts.replace('{pending}', String(pending)).replace('{skipped}', String(count('skipped')))
          .replace('{invalid}', String(invalid)).replace('{created}', String(created)).replace('{uncertain}', String(uncertain + count('failed')))}</p>
      {busy && !rows.length && <div className="skeleton h-12 rounded-md bg-lara-border" aria-hidden="true" />}
      {busy && rows.length > 0 && <progress className="h-4 w-full accent-lara-primary-dark" aria-label={t.import_progress}
        max={rows.length} value={rows.length - pending - uncertain} />}
      {rows.length > 0 && <div className="max-h-72 overflow-auto rounded-md border border-lara-border">
        <table className="admin-table"><caption className="sr-only">{t.import_preview}</caption>
          <thead><tr><th scope="col">{t.import_row}</th><th scope="col">{t.import_lrn}</th><th scope="col">{t.admin_full_name}</th><th scope="col">{t.import_status}</th></tr></thead>
          <tbody>{rows.map(row => <tr key={row.row}><th scope="row">{row.row}</th><td>{row.lrn}</td><td>{row.name}</td>
            <td>{row.issue ? issueText[row.issue] : statusText[row.status]}</td></tr>)}</tbody>
        </table>
      </div>}
      {uncertain > 0 && <p>{t.import_uncertain_help}</p>}
      {discard && <p role="alert">{t.import_discard_help}</p>}
      <div className="flex flex-wrap justify-end gap-2">
        <button className="admin-secondary" disabled={busy} onClick={dismiss}>{discard ? t.import_discard : t.import_close}</button>
        {discard && <button className="admin-secondary" disabled={busy} onClick={() => setDiscard(false)}>{t.import_keep_open}</button>}
        {created > 0 && <button className="admin-secondary" disabled={busy} onClick={() => void save()}>{t.import_download}</button>}
        {pending + uncertain + invalid > 0 && <button className="admin-primary" disabled={busy || invalid > 0} onClick={() => void run()}>
          {busy ? t.admin_working : started ? t.import_resume : t.import_start}
        </button>}
      </div>
    </div>
  </Dialog>;
}
