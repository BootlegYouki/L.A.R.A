import { useCallback, useEffect, useRef, useState } from 'react';
import { HubError, request } from '../admin';
import { useStrings } from '../i18n';

export type ActivityEntry = { seq: number; occurred_at: number; event_code: string; severity: string; result: string; service: string; details: { reason?: string; item_count?: number; duration_ms?: number } };
type Page = { history_id: string; entries: ActivityEntry[]; high_water_seq: number; next_before_seq: number | null; next_after_seq: number | null; pruned_through_seq: number; dropped_total: number; recording_state: string; server_time: number };

export default function ActivityLogs({ token, active, expired }: { token: string; active: boolean; expired: () => void }) {
  const { t } = useStrings();
  const [rows, setRows] = useState<ActivityEntry[]>([]);
  const [page, setPage] = useState<Page | null>(null);
  const [loading, setLoading] = useState(true);
  const [offline, setOffline] = useState(false);
  const [failed, setFailed] = useState(false);
  const [paused, setPaused] = useState(false);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [severity, setSeverity] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [reset, setReset] = useState(false);
  const cursor = useRef<{ history: string; high: number; snapshot: number; older: number | null } | null>(null);
  const busy = useRef(false);
  const generation = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const onExpired = useRef(expired);
  onExpired.current = expired;
  useEffect(() => { const timer = setTimeout(() => setQuery(search), 300); return () => clearTimeout(timer); }, [search]);

  const invalidRange = Boolean(from && to && from > to);
  const load = useCallback(async (older = false) => {
    if (busy.current || invalidRange) return;
    busy.current = true;
    const current = generation.current;
    const abort = new AbortController();
    controller.current = abort;
    const old = cursor.current;
    const filters = new URLSearchParams();
    if (query.trim()) filters.set('q', query.trim());
    if (severity) filters.set('severity', severity);
    if (from) filters.set('from_time', String(new Date(`${from}T00:00:00`).getTime()));
    if (to) { const end = new Date(`${to}T00:00:00`); end.setDate(end.getDate() + 1); filters.set('to_time', String(end.getTime())); }
    if (old) {
      filters.set('history_id', old.history);
      if (older) { if (old.older === null) { busy.current = false; return; } filters.set('before_seq', String(old.older)); filters.set('snapshot_seq', String(old.snapshot)); }
      else filters.set('after_seq', String(old.high));
    }
    const collected: ActivityEntry[] = [];
    try {
      let data: Page;
      do {
        data = await request<Page>(`/api/admin/activity-logs?${filters}`, token, undefined, abort.signal);
        if (current !== generation.current) return;
        collected.push(...data.entries);
        if (!older && data.next_after_seq !== null) {
          filters.set('after_seq', String(data.next_after_seq)); filters.set('snapshot_seq', String(data.high_water_seq));
        }
      } while (!older && data.next_after_seq !== null);
      cursor.current = old ? { ...old, ...(older ? { older: data.next_before_seq } : { high: data.high_water_seq }) }
        : { history: data.history_id, high: data.high_water_seq, snapshot: data.high_water_seq, older: data.next_before_seq };
      setRows(previous => {
        const merged = new Map((old ? previous : []).map(entry => [entry.seq, entry]));
        collected.forEach(entry => merged.set(entry.seq, entry));
        return [...merged.values()].filter(entry => entry.occurred_at >= data.server_time - 7 * 86400000).sort((a, b) => b.seq - a.seq).slice(0, 20000);
      });
      setPage(data); setOffline(false); setFailed(false); setLastUpdated(Date.now()); setReset(false);
    } catch (cause) {
      if (current !== generation.current || abort.signal.aborted) return;
      if (cause instanceof HubError && cause.code === 'UNAUTHORIZED') onExpired.current();
      else if (cause instanceof HubError && cause.code === 'ACTIVITY_LOG_RESET') { cursor.current = null; setRows([]); setPage(null); setReset(true); }
      else if (cause instanceof HubError && cause.code === 'HUB_UNREACHABLE') setOffline(true);
      else setFailed(true);
    } finally {
      if (current === generation.current) { busy.current = false; setLoading(false); }
    }
  }, [token, query, severity, from, to, invalidRange]);

  useEffect(() => {
    generation.current += 1; controller.current?.abort(); busy.current = false;
    cursor.current = null; setRows([]); setPage(null); setLoading(true); setLastUpdated(null); setOffline(false); setFailed(false);
    if (active) void load();
    return () => { generation.current += 1; controller.current?.abort(); busy.current = false; };
  }, [load]);
  useEffect(() => {
    if (!active || paused) return;
    void load();
    const timer = setInterval(() => { if (!document.hidden) void load(); }, 5000);
    return () => { clearInterval(timer); generation.current += 1; controller.current?.abort(); busy.current = false; };
  }, [active, paused, load]);

  const events: Record<string, string> = {
    HUB_STARTED: t.log_hub_started, HUB_STOPPING: t.log_hub_stopping, UNCLEAN_RESTART: t.log_unclean_restart,
    SERVICE_UNAVAILABLE: t.log_service_unavailable, SERVICE_RECOVERED: t.log_service_recovered, CONNECTION_PROBLEM: t.log_connection_problem,
    DOCUMENT_UPLOADED: t.log_document_uploaded, DOCUMENT_UPLOAD_FAILED: t.log_document_upload_failed, DOCUMENT_PROCESSED: t.log_document_processed, DOCUMENT_PROCESSING_FAILED: t.log_document_processing_failed,
    QUIZ_STARTED: t.log_quiz_started, QUIZ_START_FAILED: t.log_quiz_start_failed, QUIZ_CLOSED: t.log_quiz_closed, QUIZ_CLOSE_FAILED: t.log_quiz_close_failed,
    ACCOUNT_CREATED: t.log_account_created, ACCOUNT_CREATE_FAILED: t.log_account_create_failed, PIN_RESET: t.log_pin_reset, PIN_RESET_FAILED: t.log_pin_reset_failed, LOG_GAP: t.log_gap,
  };
  const reasons: Record<string, string> = { PORT_IN_USE: t.log_reason_port, NETWORK_UNAVAILABLE: t.log_reason_network, TIMEOUT: t.log_reason_timeout, STORAGE_UNAVAILABLE: t.log_reason_storage, DATABASE_UNAVAILABLE: t.log_reason_database, UNREADABLE_DOCUMENT: t.log_reason_document, NO_TEXT_LAYER: t.log_reason_no_text, SIZE_LIMIT: t.log_reason_size, INVALID_INPUT: t.log_reason_input, NOT_ALLOWED: t.log_reason_permission, CONFLICT: t.log_reason_conflict, QUEUE_FULL: t.log_reason_queue, WRITE_FAILED: t.log_reason_write, REPEATED_EVENT: t.log_reason_repeated, UNCLEAN_EXIT: t.log_reason_exit, INTERNAL_FAILURE: t.log_reason_internal };
  const services: Record<string, string> = { HUB: t.app_title, DATABASE: t.log_service_database, HTTP: t.log_service_http, WEBSOCKET: t.log_service_websocket, DISCOVERY: t.log_service_discovery, STORAGE: t.log_service_storage, DOCUMENTS: t.log_service_documents, QUIZZES: t.log_service_quizzes, ACCOUNTS: t.admin_accounts, ACTIVITY_LOG: t.activity_title };
  const levels: Record<string, string> = { INFO: t.activity_info, WARNING: t.activity_warning, ERROR: t.activity_error };
  const results: Record<string, string> = { SUCCEEDED: t.activity_succeeded, FAILED: t.activity_failed, DEGRADED: t.activity_degraded };
  function time(value: number) { const date = new Date(value); return Number.isNaN(date.getTime()) ? t.activity_unknown : date.toLocaleString('en', { dateStyle: 'medium', timeStyle: 'medium' }); }
  function isoTime(value: number) { const date = new Date(value); return Number.isNaN(date.getTime()) ? undefined : date.toISOString(); }

  return <section className="flex min-w-0 flex-col gap-4" aria-labelledby="activity-title">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h1 id="activity-title" className="text-2xl font-black">{t.activity_title}</h1><p className="mt-1 text-sm text-lara-ink-2">{t.activity_help}</p></div>
      <button type="button" className="admin-secondary" aria-pressed={paused} onClick={() => setPaused(value => !value)}><i className={`ph ${paused ? 'ph-play' : 'ph-pause'}`} aria-hidden="true" />{paused ? t.activity_resume : t.activity_pause}</button></div>
    <div className="grid gap-3 rounded-lg border border-lara-border bg-white p-4 sm:grid-cols-2">
      <label className="admin-label">{t.activity_search}<input type="search" maxLength={64} value={search} onChange={event => setSearch(event.target.value)} /></label>
      <label className="admin-label">{t.activity_severity}<select value={severity} onChange={event => setSeverity(event.target.value)}><option value="">{t.activity_all_levels}</option>{Object.entries(levels).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></label>
      <label className="admin-label">{t.activity_from}<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label className="admin-label">{t.activity_to}<input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
    </div>
    {invalidRange && <p className="admin-error" role="alert">{t.activity_invalid_dates}</p>}
    {(offline || failed || reset) && <div className="admin-offline" role="status"><i className="ph ph-warning-circle" aria-hidden="true" /><div><strong>{reset ? t.activity_reset : offline ? t.admin_offline_title : t.activity_unavailable}</strong><p>{t.activity_stale}</p><button className="admin-secondary mt-3" type="button" onClick={() => void load()}>{t.activity_retry}</button></div></div>}
    {page && (page.pruned_through_seq > 0 || page.dropped_total > 0 || page.recording_state !== 'ACTIVE') && <p className="admin-offline" role="status"><i className="ph ph-info" aria-hidden="true" />{t.activity_incomplete}</p>}
    <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-lara-ink-2"><p>{paused ? t.activity_paused : t.activity_live}</p><p>{lastUpdated === null ? t.activity_not_loaded : `${t.activity_updated} ${time(lastUpdated)}`}</p></div>
    {loading && rows.length === 0 && !invalidRange ? <div role="status" aria-label={t.activity_loading} className="flex flex-col gap-3">{[0, 1, 2].map(index => <div key={index} className="skeleton h-16 rounded-lg bg-lara-border" />)}</div>
      : rows.length === 0 ? <div className="rounded-lg border border-lara-border bg-white p-8"><i className="ph ph-list-dashes text-2xl text-lara-ink-2" aria-hidden="true" /><h2 className="mt-3 text-lg font-extrabold">{t.activity_empty}</h2><p className="mt-1 text-sm text-lara-ink-2">{t.activity_empty_help}</p></div>
      : <ol className="divide-y divide-lara-border overflow-hidden rounded-lg border border-lara-border bg-white">{rows.map(entry => <li key={`${page?.history_id}:${entry.seq}`} className="p-4">
        <div className="flex items-start gap-3"><i className={`ph ${entry.severity === 'INFO' ? 'ph-check-circle text-lara-success' : entry.severity === 'ERROR' ? 'ph-warning-circle text-lara-danger' : 'ph-warning text-lara-warning-dark'} mt-1 text-xl`} aria-hidden="true" /><div className="min-w-0 flex-1"><div className="flex flex-wrap justify-between gap-2"><p className="font-extrabold">{events[entry.event_code] ?? t.activity_unknown}</p><time className="text-xs text-lara-ink-2" dateTime={isoTime(entry.occurred_at)}>{time(entry.occurred_at)}</time></div><p className="mt-1 text-xs text-lara-ink-2">{levels[entry.severity] ?? t.activity_unknown} · {results[entry.result] ?? t.activity_unknown}</p>
          <details className="mt-2"><summary className="flex min-h-10 cursor-pointer items-center gap-2 text-sm font-bold text-lara-primary-dark">{t.activity_details}<i className="ph ph-caret-down" aria-hidden="true" /></summary><dl className="grid gap-2 rounded-md bg-lara-subtle p-3 text-sm"><div><dt className="font-bold">{t.activity_service}</dt><dd>{services[entry.service] ?? t.activity_unknown}</dd></div>{entry.details.reason && <div><dt className="font-bold">{t.activity_reason}</dt><dd>{reasons[entry.details.reason] ?? t.activity_unknown}</dd></div>}{entry.details.item_count !== undefined && <div><dt className="font-bold">{t.activity_count}</dt><dd>{entry.details.item_count}</dd></div>}{entry.details.duration_ms !== undefined && <div><dt className="font-bold">{t.activity_duration}</dt><dd>{t.activity_milliseconds.replace('{count}', String(entry.details.duration_ms))}</dd></div>}</dl></details>
        </div></div></li>)}</ol>}
    {cursor.current?.older != null && <button type="button" className="admin-secondary self-start" disabled={loading || offline || invalidRange} onClick={() => { setLoading(true); void load(true); }}>{t.activity_older}</button>}
  </section>;
}
