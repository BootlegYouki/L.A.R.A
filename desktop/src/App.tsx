import { useCallback, useEffect, useRef, useState } from 'react';
import { t } from './i18n';
import { useAppStore } from './store/app';
import ConnectionPage from './pages/ConnectionPage';
import LoginPage from './pages/LoginPage';
import HomePage from './pages/HomePage';
import Sidebar from './components/Sidebar';
import HubStatusBanner from './components/HubStatusBanner';
import JoinClassModal from './components/JoinClassModal';
import { observeHubConnection, type HubConnectionState } from './services/connection';
import { observeDiscovery, type HubInfo } from './services/discovery';
import { AuthError, signIn, refreshClassrooms, forgetAccount, type Credentials, type CachedAccount, type JoinReceipt } from './services/auth';
import { joinClass, saveJoinStatus } from './services/classrooms';
import { observeJoinUpdates } from './services/realtime';

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
  const controller = useRef<ReturnType<typeof observeHubConnection> | null>(null);
  const authRequest = useRef<AbortController | null>(null);
  const classRequest = useRef<AbortController | null>(null);
  const joinRequest = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  const retryDeadline = useRef(0);
  const hub = connection.hub;
  // Hide the previous Hub's account in the same render as an address change.
  const activeAccount = account && hub && account.hub.ip === hub.ip && account.hub.http_port === hub.http_port ? account : null;
  const accountRef = useRef(activeAccount); accountRef.current = activeAccount;
  const identity = activeAccount ? `${activeAccount.hub.ip}:${activeAccount.hub.http_port}/${activeAccount.user.id}` : '';
  const selectedClass = activeAccount?.classrooms.find(item => item.id === selectedId
    && (activeAccount.user.role === 'TEACHER' || item.enrollment_status === 'ACTIVE'));
  useEffect(() => { document.documentElement.lang = 'en'; }, []);
  useEffect(() => { const timer = setTimeout(() => setSearching(false), 3000); return () => clearTimeout(timer); }, []);
  useEffect(() => {
    const active = observeHubConnection(setConnection); controller.current = active;
    const stop = observeDiscovery((found, failed) => { setHubs(found); setDiscoveryFailed(failed); active.discovered(found); });
    return () => { stop(); active.stop(); authRequest.current?.abort(); classRequest.current?.abort(); joinRequest.current?.abort(); };
  }, []);
  useEffect(() => {
    epoch.current++; authRequest.current?.abort(); classRequest.current?.abort(); joinRequest.current?.abort();
    setJoinOpen(false); setJoining(false); setJoinError(null);
    setBusy(false); setRefreshing(false); setAuthError(null); setClassError(null); setSelectedId(undefined); setLoginVisible(false);
    void loadAccount(hub);
  }, [hub, loadAccount]);
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
    accountRef.current = null;
    setAccount(null); setLoginVisible(true); setJoinOpen(false); setJoining(false); setRefreshing(false);
    setAuthError(t.auth_session_expired);
  }, [setAccount]);
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
        setAccount({ ...latest, classrooms, join_requests: latest.join_requests?.map(receipt => {
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
  }, [setAccount, expireSession]);
  useEffect(() => {
    if (connection.status === 'connected' && identity && !retryAfter && accountRef.current) void refresh(accountRef.current);
  }, [connection.status, identity, refresh, retryAfter]);
  useEffect(() => {
    if (!classError || classError === t.auth_sign_in_again || connection.status !== 'connected' || !identity || retryAfter) return;
    const timer = setTimeout(() => { if (accountRef.current) void refresh(accountRef.current); }, 5000);
    return () => clearTimeout(timer);
  }, [classError, connection.status, identity, retryAfter, refresh]);
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
          setAccount(next);
        }
        try { next = await saveJoinStatus(next, receipt); }
        catch (error) {
          if (current === epoch.current) { setClassError(errorMessage(error)); if (accountRef.current) void refresh(accountRef.current); }
          return;
        }
        if (current !== epoch.current || accountRef.current?.user.id !== target.user.id) return;
        setAccount(next);
      }
      // Do not queue an urgent lock behind a slow metadata request.
      void refresh(next);
    }, () => {
      if (current !== epoch.current) return;
      void forgetAccount(target).catch(() => {});
      expireSession();
    });
  }, [connection.status, identity, hub?.ws_port, loginVisible, refresh, setAccount, expireSession]);
  function openJoin() { setJoinError(null); setJoinOpen(true); }
  async function submitJoin(code: string) {
    if (!activeAccount || connection.status !== 'connected' || joining || retryAfter > 0) return;
    const target = activeAccount, current = epoch.current, request = new AbortController();
    joinRequest.current = request; classRequest.current?.abort(); setJoining(true); setJoinError(null);
    try {
      const next = await joinClass(target, code, request.signal);
      if (current !== epoch.current || request.signal.aborted) return;
      setAccount(next); setJoinOpen(false); setSelectedId(undefined); await refresh(next);
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
    epoch.current++; classRequest.current?.abort(); authRequest.current?.abort(); joinRequest.current?.abort(); setJoining(false); setJoinOpen(false);
    const current = epoch.current, request = new AbortController(); authRequest.current = request;
    setBusy(true); setAuthError(null); setClassError(null);
    try {
      const next = await signIn(hub, fields, register, request.signal);
      if (current !== epoch.current || request.signal.aborted) return;
      setAccount(next); setLoginVisible(false); setSelectedId(undefined);
      // A repeated sign-in can keep the same identity, so it also needs an explicit refresh.
      if (accountRef.current?.user.id === next.user.id) void refresh(next);
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
        <button type="button" className="icon-button" aria-label={activeAccount?.user.role === 'TEACHER' ? t.create_class : t.join_class} disabled={!activeAccount || activeAccount.user.role !== 'STUDENT' || busy || loginVisible} onClick={openJoin}><i className="ph ph-plus" aria-hidden="true" /></button>
        {activeAccount ? <button type="button" className="avatar-placeholder icon-button" disabled={busy || joining} aria-label={t.auth_switch_account} title={activeAccount.user.full_name} onClick={() => { setAuthError(null); setLoginVisible(true); }}><i className="ph ph-user" aria-hidden="true" /></button> : <span className="avatar-placeholder" role="img" aria-label={t.account_placeholder}><i className="ph ph-user" aria-hidden="true" /></span>}
      </div>
    </header>
    <div className="shell-body">
      <Sidebar user={activeAccount?.user ?? null} classrooms={activeAccount?.classrooms ?? []} selectedId={selectedClass?.id} onHome={() => { setSelectedId(undefined); setAuthError(null); }} onOpen={setSelectedId} />
      <main id="content" className="content-sheet" tabIndex={-1}><div className="content-wrap">
        <HubStatusBanner t={t} connection={connection} />
        {activeAccount && !activeAccount.persistent && <p role="status" className="auth-session-note">{t.auth_session_only}</p>}
        {status === 'error' && <section className="database-error" role="alert"><h2>{t.database_error_title}</h2><p>{t.database_error_body}</p><button type="button" className="control tonal-button" onClick={() => { void loadAccount(hub); }}>{t.retry}</button></section>}
        {status === 'loading' && !activeAccount ? <div role="status" aria-label={t.loading} className="class-grid"><div className="skeleton class-skeleton" /></div> : !activeAccount || loginVisible ? <LoginPage hub={hub} busy={busy} error={authError} retryAfter={retryAfter} onSubmit={(fields, register) => { void submit(fields, register); }} onNavigate={() => setAuthError(null)} onCancel={activeAccount ? () => { setLoginVisible(false); setAuthError(null); } : undefined} /> : selectedClass ? <section className="class-overview"><button type="button" className="control text-button" onClick={() => setSelectedId(undefined)}><i className="ph ph-arrow-left" aria-hidden="true" />{t.home}</button><header className="class-banner"><h1>{selectedClass.name}</h1><p>{selectedClass.section}</p></header><p><i className="ph ph-hard-drive" aria-hidden="true" />{t.class_details_saved}</p></section> : <HomePage user={activeAccount.user} classrooms={activeAccount.classrooms} loading={refreshing} error={classError} retryAfter={retryAfter} onRetry={() => { void refresh(activeAccount); }} onOpen={setSelectedId} joinRequests={activeAccount.join_requests} onJoin={openJoin} offline={connection.status !== 'connected'} />}
        <details className="hub-connection-details" open={!activeAccount || undefined}><summary>{t.auth_hub_settings}</summary><fieldset disabled={busy || joining} className="connection-fieldset"><ConnectionPage t={t} hubs={hubs} failed={discoveryFailed} searching={searching} connection={connection} onConnect={(next) => controller.current?.select(next)} /></fieldset></details>
        {activeAccount?.user.role === 'STUDENT' && !loginVisible && <JoinClassModal open={joinOpen} onOpenChange={setJoinOpen} fullName={activeAccount.user.full_name} busy={joining} offline={connection.status !== 'connected'} error={joinError} retryAfter={retryAfter} onJoin={(code) => { void submitJoin(code); }} />}
      </div></main>
    </div>
  </div>;
}
