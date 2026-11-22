import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import { HubError, request } from '../admin';
import { useStrings } from '../i18n';

export type ActivityEntry = { seq: number; occurred_at: number; event_code: string; severity: string; result: string; service: string; details: { reason?: string; item_count?: number; duration_ms?: number; http_method?: string; endpoint?: string; status_code?: number } };
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
  const [expanded, setExpanded] = useState<Set<number>>(() => new Set());
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
      else if (cause instanceof HubError && cause.code === 'ACTIVITY_LOG_RESET') { cursor.current = null; setRows([]); setPage(null); setExpanded(new Set()); setReset(true); }
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
    HTTP_REQUEST: t.log_http_request, HUB_STARTED: t.log_hub_started, HUB_STOPPING: t.log_hub_stopping, UNCLEAN_RESTART: t.log_unclean_restart,
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

  const filtered = Boolean(query.trim() || severity || from || to);
  function toggleDetails(seq: number) {
    setExpanded(previous => { const next = new Set(previous); if (next.has(seq)) next.delete(seq); else next.add(seq); return next; });
  }
  function clearFilters() { setSearch(''); setQuery(''); setSeverity(''); setFrom(''); setTo(''); }
  function title(entry: ActivityEntry) {
    return entry.event_code === 'HTTP_REQUEST' ? entry.details.endpoint === 'UNMATCHED' ? t.activity_unmatched : entry.details.endpoint ?? t.activity_unknown
      : events[entry.event_code] ?? t.activity_unknown;
  }
  function clock(value: number) { const date = new Date(value); return Number.isNaN(date.getTime()) ? t.activity_unknown : date.toLocaleTimeString('en', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
  function day(value: number) { const date = new Date(value); return Number.isNaN(date.getTime()) ? t.activity_unknown : date.toLocaleDateString('en', { month: 'short', day: 'numeric' }); }
  function levelIcon(severity: string) { return severity === 'ERROR' ? 'ph-warning-circle' : severity === 'WARNING' ? 'ph-warning' : 'ph-info'; }

  return <section className="activity-page" aria-labelledby="activity-title">
    <header className="flex flex-wrap items-center justify-between gap-4">
      <div><div className="flex flex-wrap items-center gap-3"><h1 id="activity-title" className="text-2xl font-black">{t.activity_title}</h1>
        <span className={`activity-feed-state ${offline || failed ? 'text-lara-warning-dark' : paused ? 'text-lara-ink-2' : 'text-lara-primary-dark'}`}>
          <i className={`ph ${offline || failed ? 'ph-cloud-slash' : paused ? 'ph-pause-circle' : 'ph-broadcast'}`} aria-hidden="true" />
          {offline || failed ? t.activity_connection_lost : paused ? t.activity_paused : t.activity_live}
        </span></div><p className="mt-1 text-sm text-lara-ink-2">{t.activity_help}</p></div>
      <div className="flex items-center gap-2">
        <button type="button" className="activity-action" onClick={() => { setLoading(true); void load(); }}><i className="ph ph-arrow-clockwise" aria-hidden="true" />{t.activity_refresh}</button>
        <button type="button" className="activity-action" aria-pressed={paused} onClick={() => setPaused(value => !value)}><i className={`ph ${paused ? 'ph-play' : 'ph-pause'}`} aria-hidden="true" />{paused ? t.activity_resume : t.activity_pause}</button>
      </div>
    </header>

    <div className="activity-console">
      <div className="activity-filters">
        <label className="admin-label text-sm">{t.activity_search}<span className="relative"><i className="ph ph-magnifying-glass pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-lg text-lara-ink-2" aria-hidden="true" /><input className="activity-search-input" type="search" maxLength={64} value={search} onChange={event => setSearch(event.target.value)} /></span></label>
        <label className="admin-label text-sm">{t.activity_severity}<select value={severity} onChange={event => setSeverity(event.target.value)}><option value="">{t.activity_all_levels}</option>{Object.entries(levels).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></label>
        <label className="admin-label text-sm">{t.activity_from}<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
        <label className="admin-label text-sm">{t.activity_to}<input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
      </div>
      {invalidRange && <p className="admin-error mx-4 mb-4" role="alert">{t.activity_invalid_dates}</p>}
      {(offline || failed || reset) && <div className="activity-notice" role="status"><i className="ph ph-cloud-slash mt-0.5" aria-hidden="true" /><div className="flex-1"><strong>{reset ? t.activity_reset : offline ? t.admin_offline_title : t.activity_unavailable}</strong><p>{t.activity_stale}</p></div><button className="activity-action shrink-0" type="button" onClick={() => void load()}>{t.activity_retry}</button></div>}
      {page && (page.pruned_through_seq > 0 || page.dropped_total > 0 || page.recording_state !== 'ACTIVE') && <p className="activity-notice" role="status"><i className="ph ph-warning" aria-hidden="true" />{t.activity_incomplete}</p>}
      <div className="activity-meta"><p>{(rows.length === 1 ? t.activity_loaded_single : t.activity_loaded).replace('{count}', rows.length.toLocaleString('en'))}</p><div className="flex items-center gap-3">{filtered && <button type="button" className="activity-clear" onClick={clearFilters}>{t.activity_clear}</button>}<p>{t.activity_newest}</p></div></div>

      <div className="activity-table-scroll" role="region" aria-label={t.activity_title} tabIndex={0}>
        <table className="activity-table"><caption className="sr-only">{t.activity_help}</caption>
          <thead><tr><th scope="col">{t.activity_time}</th><th scope="col">{t.activity_severity}</th><th scope="col">{t.activity_event}</th><th scope="col">{t.activity_status_code}</th><th scope="col" className="text-right">{t.activity_duration}</th><th scope="col"><span className="sr-only">{t.activity_details}</span></th></tr></thead>
          <tbody>
            {loading && rows.length === 0 && !invalidRange ? <tr><td colSpan={6}><div role="status" aria-label={t.activity_loading} className="flex flex-col gap-4 py-2">{[0, 1, 2, 3].map(index => <div key={index} className="skeleton h-10 rounded-md bg-lara-border" />)}</div></td></tr>
              : rows.length === 0 ? <tr><td colSpan={6}><div className="activity-empty"><span className="flex h-20 w-20 items-center justify-center rounded-full bg-lara-primary-light"><i className="ph ph-list-dashes text-4xl text-lara-primary-dark" aria-hidden="true" /></span><h2 className="text-lg font-extrabold">{filtered ? t.activity_no_matches : t.activity_empty}</h2><p className="text-sm text-lara-ink-2">{filtered ? t.activity_no_matches_help : t.activity_empty_help}</p>{filtered && <button type="button" className="activity-action" onClick={clearFilters}>{t.activity_clear}</button>}</div></td></tr>
              : rows.map(entry => <Fragment key={`${page?.history_id}:${entry.seq}`}>
                <tr data-entry="true" className={expanded.has(entry.seq) ? 'activity-row-open' : undefined}>
                  <td className="whitespace-nowrap"><time dateTime={isoTime(entry.occurred_at)} title={time(entry.occurred_at)} className="tabular-nums"><span className="block font-bold">{clock(entry.occurred_at)}</span><span className="text-lara-ink-2">{day(entry.occurred_at)}</span></time></td>
                  <td><span className={`activity-level ${entry.severity === 'ERROR' ? 'activity-level-error' : entry.severity === 'WARNING' ? 'activity-level-warning' : 'activity-level-info'}`}><i className={`ph ${levelIcon(entry.severity)}`} aria-hidden="true" />{levels[entry.severity] ?? t.activity_unknown}</span></td>
                  <td className="activity-event-cell"><div className="flex flex-wrap items-start gap-x-2 gap-y-1">{entry.details.http_method && <span className="activity-method">{entry.details.http_method}</span>}<span className="min-w-0 break-all font-bold">{title(entry)}</span></div><p className="mt-1 text-xs text-lara-ink-2">{services[entry.service] ?? t.activity_unknown}</p></td>
                  <td className="whitespace-nowrap tabular-nums">{entry.details.status_code ?? results[entry.result] ?? t.activity_unknown}</td>
                  <td className="whitespace-nowrap text-right tabular-nums text-lara-ink-2">{entry.details.duration_ms === undefined ? t.activity_no_value : t.activity_milliseconds.replace('{count}', String(entry.details.duration_ms))}</td>
                  <td><button type="button" className="activity-expand" aria-expanded={expanded.has(entry.seq)} aria-controls={`activity-details-${entry.seq}`} aria-label={t.activity_entry_details.replace('{count}', String(entry.seq))} onClick={() => toggleDetails(entry.seq)}><i className={`ph ${expanded.has(entry.seq) ? 'ph-caret-up' : 'ph-caret-down'}`} aria-hidden="true" /></button></td>
                </tr>
                {expanded.has(entry.seq) && <tr className="activity-detail-row" id={`activity-details-${entry.seq}`}><td colSpan={6}><dl className="activity-details-grid">
                  <div><dt>{t.activity_time}</dt><dd>{time(entry.occurred_at)}</dd></div>
                  <div><dt>{t.activity_service}</dt><dd>{services[entry.service] ?? t.activity_unknown}</dd></div>
                  <div><dt>{t.activity_result}</dt><dd>{results[entry.result] ?? t.activity_unknown}</dd></div>
                  {entry.details.http_method && <div><dt>{t.activity_http_method}</dt><dd>{entry.details.http_method}</dd></div>}
                  {entry.details.endpoint && <div className="sm:col-span-2"><dt>{t.activity_endpoint}</dt><dd className="break-all">{title(entry)}</dd></div>}
                  {entry.details.status_code !== undefined && <div><dt>{t.activity_status_code}</dt><dd>{entry.details.status_code}</dd></div>}
                  {entry.details.reason && <div className="sm:col-span-2"><dt>{t.activity_reason}</dt><dd>{reasons[entry.details.reason] ?? t.activity_unknown}</dd></div>}
                  {entry.details.item_count !== undefined && <div><dt>{t.activity_count}</dt><dd>{entry.details.item_count}</dd></div>}
                  {entry.details.duration_ms !== undefined && <div><dt>{t.activity_duration}</dt><dd>{t.activity_milliseconds.replace('{count}', String(entry.details.duration_ms))}</dd></div>}
                </dl></td></tr>}
              </Fragment>)}
          </tbody>
        </table>
      </div>
      <footer className="activity-footer"><p>{lastUpdated === null ? t.activity_not_loaded : `${t.activity_updated} ${clock(lastUpdated)}`}</p><p>{t.activity_retention}</p></footer>
    </div>
    {cursor.current?.older != null && <button type="button" className="activity-action self-center" disabled={loading || offline || invalidRange} onClick={() => { setLoading(true); void load(true); }}><i className="ph ph-clock-counter-clockwise" aria-hidden="true" />{t.activity_older}</button>}
  </section>;
}
