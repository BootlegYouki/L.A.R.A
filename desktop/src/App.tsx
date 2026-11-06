import { useCallback, useEffect, useRef, useState } from 'react';
import { t } from './i18n';
import { useAppStore } from './store/app';
import ConnectionPage from './pages/ConnectionPage';
import LoginPage from './pages/LoginPage';
import HomePage from './pages/HomePage';
import Sidebar from './components/Sidebar';
import HubStatusBanner from './components/HubStatusBanner';
import { observeHubConnection, type HubConnectionState } from './services/connection';
import { observeDiscovery, type HubInfo } from './services/discovery';
import { AuthError, signIn, refreshClassrooms, type Credentials, type CachedAccount } from './services/auth';

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
  const controller = useRef<ReturnType<typeof observeHubConnection> | null>(null);
  const authRequest = useRef<AbortController | null>(null);
  const classRequest = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  const retryDeadline = useRef(0);
  const hub = connection.hub;
  // Hide the previous Hub's account in the same render as an address change.
  const activeAccount = account && hub && account.hub.ip === hub.ip && account.hub.http_port === hub.http_port ? account : null;
  const accountRef = useRef(activeAccount); accountRef.current = activeAccount;
  const identity = activeAccount ? `${activeAccount.hub.ip}:${activeAccount.hub.http_port}/${activeAccount.user.id}` : '';
  const selectedClass = activeAccount?.classrooms.find(item => item.id === selectedId);
  useEffect(() => { document.documentElement.lang = 'en'; }, []);
  useEffect(() => { const timer = setTimeout(() => setSearching(false), 3000); return () => clearTimeout(timer); }, []);
  useEffect(() => {
    const active = observeHubConnection(setConnection); controller.current = active;
    const stop = observeDiscovery((found, failed) => { setHubs(found); setDiscoveryFailed(failed); active.discovered(found); });
    return () => { stop(); active.stop(); authRequest.current?.abort(); classRequest.current?.abort(); };
  }, []);
  useEffect(() => {
    epoch.current++; authRequest.current?.abort(); classRequest.current?.abort();
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
  const refresh = useCallback(async (target: CachedAccount) => {
    if (Date.now() < retryDeadline.current) return;
    classRequest.current?.abort();
    const request = new AbortController(); classRequest.current = request;
    const current = epoch.current;
    setRefreshing(true); setClassError(null);
    try {
      const classrooms = await refreshClassrooms(target, request.signal);
      if (!request.signal.aborted && current === epoch.current && accountRef.current?.user.id === target.user.id) {
        setAccount({ ...target, classrooms });
      }
    } catch (error) {
      if (!request.signal.aborted && current === epoch.current) {
        if (error instanceof AuthError && error.code === 'UNAUTHORIZED') {
          setAccount(null); setLoginVisible(true); setAuthError(t.auth_session_expired);
        } else {
          setClassError(errorMessage(error));
          if (error instanceof AuthError && error.retryAfter > 0) {
            retryDeadline.current = Date.now() + error.retryAfter * 1000;
            setRetryAfter(error.retryAfter); setRetryUntil(retryDeadline.current);
          }
        }
      }
    } finally { if (classRequest.current === request) { setRefreshing(false); classRequest.current = null; } }
  }, [setAccount]);
  useEffect(() => {
    if (connection.status === 'connected' && identity && !retryAfter && accountRef.current) void refresh(accountRef.current);
  }, [connection.status, identity, refresh, retryAfter]);
  async function submit(fields: Credentials, register: boolean) {
    if (!hub || busy || retryAfter > 0) return;
    epoch.current++; classRequest.current?.abort(); authRequest.current?.abort();
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
        <button type="button" className="icon-button" aria-label={activeAccount?.user.role === 'TEACHER' ? t.create_class : t.join_class} disabled><i className="ph ph-plus" aria-hidden="true" /></button>
        {activeAccount ? <button type="button" className="avatar-placeholder icon-button" disabled={busy} aria-label={t.auth_switch_account} title={activeAccount.user.full_name} onClick={() => { setAuthError(null); setLoginVisible(true); }}><i className="ph ph-user" aria-hidden="true" /></button> : <span className="avatar-placeholder" role="img" aria-label={t.account_placeholder}><i className="ph ph-user" aria-hidden="true" /></span>}
      </div>
    </header>
    <div className="shell-body">
      <Sidebar user={activeAccount?.user ?? null} classrooms={activeAccount?.classrooms ?? []} selectedId={selectedClass?.id} onHome={() => setSelectedId(undefined)} onOpen={setSelectedId} />
      <main id="content" className="content-sheet" tabIndex={-1}><div className="content-wrap">
        <HubStatusBanner t={t} connection={connection} />
        {activeAccount && !activeAccount.persistent && <p role="status" className="auth-session-note">{t.auth_session_only}</p>}
        {status === 'error' && <section className="database-error" role="alert"><h2>{t.database_error_title}</h2><p>{t.database_error_body}</p><button type="button" className="control tonal-button" onClick={() => { void loadAccount(hub); }}>{t.retry}</button></section>}
        {status === 'loading' && !activeAccount ? <div role="status" aria-label={t.loading} className="class-grid"><div className="skeleton class-skeleton" /></div> : !activeAccount || loginVisible ? <LoginPage hub={hub} busy={busy} error={authError} retryAfter={retryAfter} onSubmit={(fields, register) => { void submit(fields, register); }} onCancel={activeAccount ? () => setLoginVisible(false) : undefined} /> : selectedClass ? <section className="class-overview"><button type="button" className="control text-button" onClick={() => setSelectedId(undefined)}><i className="ph ph-arrow-left" aria-hidden="true" />{t.home}</button><header className="class-banner"><h1>{selectedClass.name}</h1><p>{selectedClass.section}</p></header><p><i className="ph ph-hard-drive" aria-hidden="true" />{t.class_details_saved}</p></section> : <HomePage user={activeAccount.user} classrooms={activeAccount.classrooms} loading={refreshing} error={classError} retryAfter={retryAfter} onRetry={() => { void refresh(activeAccount); }} onOpen={setSelectedId} />}
        <details className="hub-connection-details" open={!activeAccount || undefined}><summary>{t.auth_hub_settings}</summary><fieldset disabled={busy} className="connection-fieldset"><ConnectionPage t={t} hubs={hubs} failed={discoveryFailed} searching={searching} connection={connection} onConnect={(next) => controller.current?.select(next)} /></fieldset></details>
      </div></main>
    </div>
  </div>;
}
