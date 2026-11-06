import { useCallback, useEffect, useRef, useState } from 'react';
import { t } from './i18n';
import { useAppStore } from './store/app';
import ConnectionPage from './pages/ConnectionPage';
import LoginPage from './pages/LoginPage';
import HomePage from './pages/HomePage';
import Sidebar from './components/Sidebar';
import HubStatusBanner from './components/HubStatusBanner';
import JoinClassModal from './components/JoinClassModal';
import CreateClassroomDialog from './components/CreateClassroomDialog';
import TopAppBar from './components/TopAppBar';
import RosterPage from './pages/RosterPage';
import { observeHubConnection, type HubConnectionState } from './services/connection';
import { observeDiscovery, type HubInfo } from './services/discovery';
import { AuthError, signIn, refreshClassrooms, forgetAccount, type Credentials, type CachedAccount, type JoinReceipt, type Classroom } from './services/auth';
import { joinClass, saveJoinStatus, createClassroom, refreshRoster, decideEnrollment } from './services/classrooms';
import { observeJoinUpdates } from './services/realtime';
import {ensureWebSocketPolicy} from './services/websocket-policy';

function errorMessage(error: unknown): string {
  if (!(error instanceof AuthError)) return t.auth_cache_error;
  switch (error.code) {
    case 'UNAUTHORIZED': return t.auth_bad_credentials;
    case 'reauth_required': return t.auth_sign_in_again;
    case 'ADMIN_LOCAL_ONLY': return t.auth_admin_only;
    case 'ALREADY_EXISTS': return t.auth_account_exists;
    case 'TOO_MANY_ATTEMPTS': return t.auth_rate_limited;
    case 'invalid_fields': case 'BAD_REQUEST': return t.auth_fields_error;
    case 'network': return t.auth_hub_unreachable;
    default: return t.auth_request_error;
  }
}
export default function App() {
  const { status, account, loadAccount, setAccount } = useAppStore();
  const [drawerOpen, setDrawerOpen] = useState(true);
  const [hubs, setHubs] = useState<HubInfo[]>([]);
  const [discoveryFailed, setDiscoveryFailed] = useState(false);
  const [searching, setSearching] = useState(true);
  const [connection, setConnection] = useState<HubConnectionState>({ status: 'offline', hub: null, persisted: true });
  const [loginVisible, setLoginVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);
  const [classError, setClassError] = useState<string | null>(null);
  const [retryUntil, setRetryUntil] = useState(0);
  const [retryAfter, setRetryAfter] = useState(0);
  const [selectedId, setSelectedId] = useState<string>();
  const [joinOpen, setJoinOpen] = useState(false);
  const [joining, setJoining] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createdClass, setCreatedClass] = useState<Classroom | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [rosterBusy, setRosterBusy] = useState(false);
  const [rosterLoading, setRosterLoading] = useState<Record<string, boolean>>({});
  const [rosterErrors, setRosterErrors] = useState<Record<string, string>>({});
  const controller = useRef<ReturnType<typeof observeHubConnection> | null>(null);
  const authRequest = useRef<AbortController | null>(null);
  const classRequest = useRef<AbortController | null>(null);
  const joinRequest = useRef<AbortController | null>(null);
  const teacherMutation = useRef<AbortController | null>(null);
  const rosterRequests = useRef(new Map<string, AbortController>());
  const rosterSweep = useRef(0);
  const epoch = useRef(0);
  const retryDeadline = useRef(0);
  const hub = connection.hub;
  // Hide the previous Hub's account in the same render as an address change.
  const activeAccount = account && hub && account.hub.ip === hub.ip && account.hub.http_port === hub.http_port ? account : null;
  const accountRef = useRef(activeAccount); accountRef.current = activeAccount;
  const publishAccount = useCallback((next: CachedAccount | null) => { accountRef.current = next; setAccount(next); }, [setAccount]);
  const identity = activeAccount ? `${activeAccount.hub.ip}:${activeAccount.hub.http_port}/${activeAccount.user.id}` : '';
  const selectedClass = activeAccount?.classrooms.find(item => item.id === selectedId
    && (activeAccount.user.role === 'TEACHER' || item.enrollment_status === 'ACTIVE'));
  const teacherClassIds = activeAccount?.user.role === 'TEACHER' ? activeAccount.classrooms.map(c => c.id).sort().join(',') : '';
  const pendingCounts = activeAccount?.user.role === 'TEACHER' ? Object.fromEntries(Object.entries(activeAccount.rosters ?? {}).map(([id, rows]) => [id, rows.filter(r => r.status === 'PENDING').length])) : {};
  const abortTeacherWork = useCallback(() => {
    teacherMutation.current?.abort(); rosterSweep.current++;
    for (const request of rosterRequests.current.values()) request.abort();
    rosterRequests.current.clear();
  }, []);
  useEffect(() => { document.documentElement.lang = 'en'; }, []);
  useEffect(() => { const timer = setTimeout(() => setSearching(false), 3000); return () => clearTimeout(timer); }, []);
  useEffect(() => {
    const active = observeHubConnection(setConnection); controller.current = active;
    const stop = observeDiscovery((found, failed) => { setHubs(found); setDiscoveryFailed(failed); active.discovered(found); });
    return () => { stop(); active.stop(); authRequest.current?.abort(); classRequest.current?.abort(); joinRequest.current?.abort(); abortTeacherWork(); };
  }, [abortTeacherWork]);
  useEffect(() => {
    epoch.current++; authRequest.current?.abort(); classRequest.current?.abort(); joinRequest.current?.abort();
    abortTeacherWork(); setCreating(false); setCreateOpen(false); setCreatedClass(null); setCreateError(null); setRosterBusy(false); setRosterErrors({}); setRosterLoading({});
    setJoinOpen(false); setJoining(false); setJoinError(null);
    setBusy(false); setRefreshing(false); setAuthError(null); setClassError(null); setSelectedId(undefined); setLoginVisible(false);
    void loadAccount(hub);
  }, [hub, loadAccount, abortTeacherWork]);
  useEffect(() => { if (connection.status === 'connected' && hub) ensureWebSocketPolicy(hub); }, [connection.status, hub]);
  useEffect(() => {
    if (!retryUntil) return;
    const update = () => {
      const seconds = Math.max(0, Math.ceil((retryUntil - Date.now()) / 1000));
      setRetryAfter(seconds); if (!seconds) setRetryUntil(0);
    };
    update(); const timer = setInterval(update, 1000); return () => clearInterval(timer);
  }, [retryUntil]);
  const expireSession = useCallback(() => {
    epoch.current++; classRequest.current?.abort(); joinRequest.current?.abort(); authRequest.current?.abort();
    abortTeacherWork(); setCreateOpen(false); setCreating(false); setRosterBusy(false);
    accountRef.current = null;
    publishAccount(null); setLoginVisible(true); setJoinOpen(false); setJoining(false); setRefreshing(false);
    setAuthError(t.auth_session_expired);
  }, [publishAccount, abortTeacherWork]);
  const teacherError = useCallback((error: unknown): string => {
    if (error instanceof AuthError) {
      if (error.code === 'UNAUTHORIZED') expireSession();
      if (error.retryAfter > 0) {
        retryDeadline.current = Date.now() + error.retryAfter * 1000;
        setRetryUntil(retryDeadline.current); setRetryAfter(error.retryAfter);
      }
      if (error.code === 'NOT_IMPLEMENTED') return t.roster_not_supported;
      if (error.code === 'FORBIDDEN') return t.roster_forbidden;
    }
    return errorMessage(error);
  }, [expireSession]);
  const refreshTeacherRoster = useCallback(async (target: CachedAccount, id: string) => {
    if (target.user.role !== 'TEACHER' || Date.now() < retryDeadline.current) return;
    rosterRequests.current.get(id)?.abort();
    const request = new AbortController(), current = epoch.current; rosterRequests.current.set(id, request);
    setRosterLoading(previous => ({...previous, [id]: true})); setRosterErrors(previous => ({...previous, [id]: ''}));
    try {
      const roster = await refreshRoster(target, id, request.signal);
      const latest = accountRef.current;
      if (!request.signal.aborted && current === epoch.current && latest?.user.id === target.user.id && latest.user.role === 'TEACHER' && latest.classrooms.some(c => c.id === id)) {
        publishAccount({...latest, rosters: {...latest.rosters, [id]: roster}});
      }
    } catch (error) {
      if (!request.signal.aborted && current === epoch.current) {
        if (error instanceof AuthError && error.code === 'FORBIDDEN' && accountRef.current) {
          const latest = accountRef.current;
          publishAccount({...latest, rosters: Object.fromEntries(Object.entries(latest.rosters ?? {}).filter(([key]) => key !== id))});
        }
        const message = teacherError(error);
        setRosterErrors(previous => ({...previous, [id]: message}));
      }
    } finally {
      if (rosterRequests.current.get(id) === request) { rosterRequests.current.delete(id); setRosterLoading(previous => ({...previous, [id]: false})); }
    }
  }, [publishAccount, teacherError]);
  const refreshTeacherRosters = useCallback(async (target: CachedAccount, classCode?: string) => {
    const rooms = target.classrooms.filter(c => !classCode || c.class_code === classCode);
    const sweep = classCode ? rosterSweep.current : ++rosterSweep.current, current = epoch.current;
    for (let start = 0; start < rooms.length; start += 4) {
      if (sweep !== rosterSweep.current || current !== epoch.current) return;
      await Promise.all(rooms.slice(start, start + 4).map(c => refreshTeacherRoster(target, c.id)));
    }
  }, [refreshTeacherRoster]);
  const refresh = useCallback(async (target: CachedAccount) => {
    if (Date.now() < retryDeadline.current) return;
    classRequest.current?.abort();
    const request = new AbortController(); classRequest.current = request;
    const current = epoch.current;
    setRefreshing(true); setClassError(null);
    try {
      const classrooms = await refreshClassrooms(target, request.signal);
      if (!request.signal.aborted && current === epoch.current && accountRef.current?.user.id === target.user.id) {
        const latest = accountRef.current ?? target;
        publishAccount({ ...latest, classrooms, join_requests: latest.join_requests?.map(receipt => {
          const found = classrooms.find(item => item.id === receipt.classroom_id);
          return found?.enrollment_status ? { ...receipt, status: found.enrollment_status } : receipt;
        }) });
      }
    } catch (error) {
      if (!request.signal.aborted && current === epoch.current) {
        if (error instanceof AuthError && error.code === 'UNAUTHORIZED') {
          expireSession();
        } else {
          setClassError(errorMessage(error));
          if (error instanceof AuthError && error.retryAfter > 0) {
            retryDeadline.current = Date.now() + error.retryAfter * 1000;
            setRetryAfter(error.retryAfter); setRetryUntil(retryDeadline.current);
          }
        }
      }
    } finally { if (classRequest.current === request) { setRefreshing(false); classRequest.current = null; } }
  }, [publishAccount, expireSession]);
  useEffect(() => {
    if (connection.status === 'connected' && identity && !retryAfter && accountRef.current) void refresh(accountRef.current);
  }, [connection.status, identity, refresh, retryAfter]);
  useEffect(() => {
    if (!classError || classError === t.auth_sign_in_again || connection.status !== 'connected' || !identity || retryAfter) return;
    const timer = setTimeout(() => { if (accountRef.current) void refresh(accountRef.current); }, 5000);
    return () => clearTimeout(timer);
  }, [classError, connection.status, identity, retryAfter, refresh]);
  useEffect(() => {
    if (connection.status === 'connected' && teacherClassIds && !loginVisible && !retryAfter && accountRef.current) void refreshTeacherRosters(accountRef.current);
  }, [connection.status, teacherClassIds, identity, loginVisible, retryAfter, refreshTeacherRosters]);
  useEffect(() => {
    if (connection.status !== 'connected' || !selectedId || !rosterErrors[selectedId] || [t.roster_forbidden, t.roster_not_supported].includes(rosterErrors[selectedId]) || rosterBusy || retryAfter) return;
    const timer = setTimeout(() => { if (accountRef.current) void refreshTeacherRoster(accountRef.current, selectedId); }, 5000);
    return () => clearTimeout(timer);
  }, [connection.status, selectedId, rosterErrors, rosterBusy, retryAfter, refreshTeacherRoster]);
  useEffect(() => {
    if (connection.status !== 'connected' || !identity || loginVisible || !accountRef.current) return;
    const target = accountRef.current, current = epoch.current;
    return observeJoinUpdates(target, async (receipt?: JoinReceipt) => {
      if (current !== epoch.current || accountRef.current?.user.id !== target.user.id) return;
      let next = accountRef.current;
      if (receipt) {
        classRequest.current?.abort();
        // Lock immediately; an ACTIVE event alone never grants access.
        if (receipt.status !== 'ACTIVE') {
          next = { ...next, classrooms: next.classrooms.map(c => c.id === receipt.classroom_id ? { ...c, enrollment_status: receipt.status } : c) };
          publishAccount(next);
        }
        try { next = await saveJoinStatus(next, receipt); }
        catch (error) {
          if (current === epoch.current) { setClassError(errorMessage(error)); if (accountRef.current) void refresh(accountRef.current); }
          return;
        }
        if (current !== epoch.current || accountRef.current?.user.id !== target.user.id) return;
        publishAccount(next);
      }
      // Do not queue an urgent lock behind a slow metadata request.
      void refresh(next);
    }, () => {
      if (current !== epoch.current) return;
      void forgetAccount(target).catch(() => {});
      expireSession();
    }, async (code?: string) => {
      if (current === epoch.current && accountRef.current?.user.role === 'TEACHER') void refreshTeacherRosters(accountRef.current, code);
    });
  }, [connection.status, identity, hub?.ws_port, loginVisible, refresh, publishAccount, expireSession, refreshTeacherRosters]);
  function openCreate() { setCreatedClass(null); setCreateError(null); setCreateOpen(true); }
  async function submitCreate(name: string, section: string) {
    if (!activeAccount || activeAccount.user.role !== 'TEACHER' || connection.status !== 'connected' || creating || rosterBusy || retryAfter) return;
    const target = activeAccount, current = epoch.current, request = new AbortController(); teacherMutation.current = request;
    setCreating(true); setCreateError(null);
    try {
      const result = await createClassroom(target, name, section, request.signal);
      if (current !== epoch.current || request.signal.aborted || accountRef.current?.user.id !== target.user.id) return;
      const next = {...accountRef.current, classrooms: result.classrooms};
      publishAccount(next); setCreatedClass(result.classroom); setCreateError(result.cacheSaved ? null : t.class_created_cache_error); setSelectedId(result.classroom.id); void refresh(next);
    } catch (error) { if (current === epoch.current && !request.signal.aborted) setCreateError(teacherError(error)); }
    finally { if (current === epoch.current) setCreating(false); }
  }
  async function decide(studentId: string, action: 'approve'|'reject'|'remove') {
    if (!activeAccount || activeAccount.user.role !== 'TEACHER' || !selectedClass || connection.status !== 'connected' || creating || rosterBusy || retryAfter) return;
    const target = activeAccount, id = selectedClass.id, current = epoch.current, request = new AbortController(); teacherMutation.current = request;
    rosterRequests.current.get(id)?.abort(); setRosterBusy(true); setRosterErrors(previous => ({...previous, [id]: ''}));
    try {
      await decideEnrollment(target, id, studentId, action, request.signal);
      if (current === epoch.current && !request.signal.aborted) await refreshTeacherRoster(accountRef.current ?? target, id);
    } catch (error) { if (current === epoch.current && !request.signal.aborted) { const message = teacherError(error); setRosterErrors(previous => ({...previous, [id]: message})); } }
    finally { if (current === epoch.current) setRosterBusy(false); }
  }
  function openJoin() { setJoinError(null); setJoinOpen(true); }
  async function submitJoin(code: string) {
    if (!activeAccount || connection.status !== 'connected' || joining || retryAfter > 0) return;
    const target = activeAccount, current = epoch.current, request = new AbortController();
    joinRequest.current = request; classRequest.current?.abort(); setJoining(true); setJoinError(null);
    try {
      const next = await joinClass(target, code, request.signal);
      if (current !== epoch.current || request.signal.aborted) return;
      publishAccount(next); setJoinOpen(false); setSelectedId(undefined); await refresh(next);
    } catch (error) {
      if (current !== epoch.current || request.signal.aborted) return;
      if (error instanceof AuthError && error.code === 'UNAUTHORIZED') {
        expireSession();
      } else {
        setJoinError(error instanceof AuthError && ['CLASS_CODE_INVALID', 'invalid_code'].includes(error.code) ? t.join_invalid_code
          : error instanceof AuthError && error.code === 'FORBIDDEN' ? t.join_forbidden : errorMessage(error));
        if (error instanceof AuthError && error.retryAfter > 0) {
          retryDeadline.current = Date.now() + error.retryAfter * 1000;
          setRetryUntil(retryDeadline.current); setRetryAfter(error.retryAfter);
        }
      }
    } finally { if (current === epoch.current) setJoining(false); }
  }
  async function submit(fields: Credentials, register: boolean) {
    if (!hub || busy || retryAfter > 0) return;
    epoch.current++; classRequest.current?.abort(); authRequest.current?.abort(); joinRequest.current?.abort(); abortTeacherWork(); setJoining(false); setJoinOpen(false); setCreateOpen(false); setCreating(false); setRosterBusy(false);
    const current = epoch.current, request = new AbortController(); authRequest.current = request;
    setBusy(true); setAuthError(null); setClassError(null);
    try {
      const next = await signIn(hub, fields, register, request.signal);
      if (current !== epoch.current || request.signal.aborted) return;
      const repeated = accountRef.current?.user.id === next.user.id;
      publishAccount(next); setLoginVisible(false); setSelectedId(undefined);
      // A repeated sign-in can keep the same identity, so it also needs an explicit refresh.
      if (repeated) void refresh(next);
    } catch (error) {
      if (current === epoch.current && !request.signal.aborted) {
        setAuthError(errorMessage(error));
        if (error instanceof AuthError && error.retryAfter > 0) {
          retryDeadline.current = Date.now() + error.retryAfter * 1000;
          setRetryAfter(error.retryAfter); setRetryUntil(retryDeadline.current);
        }
      }
    } finally { if (current === epoch.current) setBusy(false); }
  }
  return <div className={`app-shell ${drawerOpen ? '' : 'drawer-rail'}`}>
    <a href="#content" className="skip-link">{t.skip_content}</a>
    <header className="top-bar">
      <button type="button" className="icon-button menu-button" aria-label={t.toggle_menu} aria-expanded={drawerOpen} aria-controls="navigation" onClick={() => setDrawerOpen(!drawerOpen)}><i className="ph ph-list" aria-hidden="true" /></button>
      <span className="brand">{t.app_title}</span>
      <div className="top-actions">
        <span className={`hub-pill ${connection.status === 'connected' ? 'hub-connected' : 'hub-offline'}`}><i className={`ph ${connection.status === 'connected' ? 'ph-wifi-high' : 'ph-wifi-slash'}`} aria-hidden="true" /><span>{connection.status === 'connected' ? t.connected_to_hub.replace('{name}', hub?.name ?? '') : t.hub_offline}</span></span>
        <button type="button" className="icon-button" aria-label={activeAccount?.user.role === 'TEACHER' ? t.create_class : t.join_class} disabled={!activeAccount || busy || joining || creating || rosterBusy || loginVisible} onClick={() => activeAccount?.user.role === 'TEACHER' ? openCreate() : openJoin()}><i className="ph ph-plus" aria-hidden="true" /></button>
        {activeAccount ? <button type="button" className="avatar-placeholder icon-button" disabled={busy || joining || creating || rosterBusy} aria-label={t.auth_switch_account} title={activeAccount.user.full_name} onClick={() => { setAuthError(null); setLoginVisible(true); }}><i className="ph ph-user" aria-hidden="true" /></button> : <span className="avatar-placeholder" role="img" aria-label={t.account_placeholder}><i className="ph ph-user" aria-hidden="true" /></span>}
      </div>
    </header>
    <div className="shell-body">
      <Sidebar user={activeAccount?.user ?? null} classrooms={activeAccount?.classrooms ?? []} selectedId={selectedClass?.id} onHome={() => { setSelectedId(undefined); setAuthError(null); }} onOpen={setSelectedId} pendingCounts={pendingCounts} />
      <main id="content" className="content-sheet" tabIndex={-1}><div className="content-wrap">
        <HubStatusBanner t={t} connection={connection} />
        {activeAccount && !activeAccount.persistent && <p role="status" className="auth-session-note">{t.auth_session_only}</p>}
        {status === 'error' && <section className="database-error" role="alert"><h2>{t.database_error_title}</h2><p>{t.database_error_body}</p><button type="button" className="control tonal-button" onClick={() => { void loadAccount(hub); }}>{t.retry}</button></section>}
        {status === 'loading' && !activeAccount ? <div role="status" aria-label={t.loading} className="class-grid"><div className="skeleton class-skeleton" /></div> : !activeAccount || loginVisible ? <LoginPage hub={hub} busy={busy} error={authError} retryAfter={retryAfter} onSubmit={(fields, register) => { void submit(fields, register); }} onNavigate={() => setAuthError(null)} onCancel={activeAccount ? () => { setLoginVisible(false); setAuthError(null); } : undefined} /> : selectedClass && activeAccount.user.role === 'TEACHER' ? <><TopAppBar classroom={selectedClass} onHome={() => setSelectedId(undefined)} /><RosterPage key={`${identity}/${selectedClass.id}`} classroom={selectedClass} user={activeAccount.user} roster={activeAccount.rosters?.[selectedClass.id]} loading={!!rosterLoading[selectedClass.id]} error={rosterErrors[selectedClass.id] || null} offline={connection.status !== 'connected'} busy={rosterBusy || creating} retryAfter={retryAfter} onRetry={() => { void refreshTeacherRoster(activeAccount, selectedClass.id); }} onDecide={(id, action) => { void decide(id, action); }} /></> : selectedClass ? <section className="class-overview"><button type="button" className="control text-button" onClick={() => setSelectedId(undefined)}><i className="ph ph-arrow-left" aria-hidden="true" />{t.home}</button><header className="class-banner"><h1>{selectedClass.name}</h1><p>{selectedClass.section}</p></header><p><i className="ph ph-hard-drive" aria-hidden="true" />{t.class_details_saved}</p></section> : <HomePage user={activeAccount.user} classrooms={activeAccount.classrooms} loading={refreshing} error={classError} retryAfter={retryAfter} onRetry={() => { void refresh(activeAccount); }} onOpen={setSelectedId} joinRequests={activeAccount.join_requests} onJoin={openJoin} onCreate={openCreate} offline={connection.status !== 'connected'} />}
        <details className="hub-connection-details" open={!activeAccount || undefined}><summary>{t.auth_hub_settings}</summary><fieldset disabled={busy || joining || creating || rosterBusy} className="connection-fieldset"><ConnectionPage t={t} hubs={hubs} failed={discoveryFailed} searching={searching} connection={connection} onConnect={(next) => controller.current?.select(next)} /></fieldset></details>
        {activeAccount?.user.role === 'TEACHER' && !loginVisible && <CreateClassroomDialog open={createOpen} onOpenChange={setCreateOpen} busy={creating || rosterBusy} offline={connection.status !== 'connected'} error={createError} retryAfter={retryAfter} result={createdClass} onCreate={(name, section) => { void submitCreate(name, section); }} />}
        {activeAccount?.user.role === 'STUDENT' && !loginVisible && <JoinClassModal open={joinOpen} onOpenChange={setJoinOpen} fullName={activeAccount.user.full_name} busy={joining} offline={connection.status !== 'connected'} error={joinError} retryAfter={retryAfter} onJoin={(code) => { void submitJoin(code); }} />}
      </div></main>
    </div>
  </div>;
}
