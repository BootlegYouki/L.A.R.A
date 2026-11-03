import { useEffect, useState } from 'react';
import { dictionaries } from './i18n';
import { useAppStore } from './store/app';
import ConnectionPage from './pages/ConnectionPage';
import { observeDiscovery } from './services/discovery';
import { updateHubConnectionSeen } from './services/db';
import HubStatusBanner from './components/HubStatusBanner';

export default function App() {
  const { language, status, classrooms, loadSavedClasses } = useAppStore();
  const [drawerOpen, setDrawerOpen] = useState(true);
  const [connectedHub, setConnectedHub] = useState<{ address: string; name: string } | null>(null);
  const t = dictionaries[language];

  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => { void loadSavedClasses(); }, [loadSavedClasses]);
  
  // Monitor hub connection
  useEffect(() => {
    if (!connectedHub) return;
    
    let missedCount = 0;
    const maxMissed = 3; // Disconnect after 3 consecutive misses (3 seconds)
    
    const monitorConnection = observeDiscovery((found) => {
      const stillThere = found.some(
        (hub) => `${hub.ip}:${hub.http_port}` === connectedHub.address
      );
      
      if (stillThere) {
        missedCount = 0; // Reset counter when Hub is seen
        void updateHubConnectionSeen(connectedHub.address);
      } else {
        missedCount++;
        
        if (missedCount >= maxMissed) {
          setConnectedHub(null);
        }
      }
    });
    
    return monitorConnection;
  }, [connectedHub]);

  const handleConnected = (address: string, name: string) => {
    setConnectedHub({ address, name });
  };

  // Show connection page if not connected
  if (!connectedHub) {
    return (
      <div className="app-shell">
        <header className="top-bar">
          <span className="brand">{t.app_title}</span>
          <div className="top-actions">
            <span className="hub-pill hub-offline">
              <i className="ph ph-wifi-slash" aria-hidden="true" />
              <span>{t.hub_offline}</span>
            </span>
          </div>
        </header>
        <main className="content-sheet">
          <ConnectionPage t={t} onConnected={handleConnected} />
        </main>
      </div>
    );
  }

  return (
    <div className={`app-shell ${drawerOpen ? '' : 'drawer-rail'}`}>
      <a href="#content" className="skip-link">{t.skip_content}</a>
      <header className="top-bar">
        <button type="button" className="icon-button menu-button" aria-label={t.toggle_menu} aria-expanded={drawerOpen}
          aria-controls="navigation" onClick={() => setDrawerOpen(!drawerOpen)}><i className="ph ph-list" aria-hidden="true" /></button>
        <span className="brand">{t.app_title}</span>
        <div className="top-actions">
          <span className="hub-pill hub-connected">
            <i className="ph ph-wifi-high" aria-hidden="true" />
            <span>{connectedHub.name}</span>
          </span>
          <button type="button" className="icon-button" aria-label={t.join_class} disabled aria-describedby="offline-notice">
            <i className="ph ph-plus" aria-hidden="true" /></button>
          <span className="avatar-placeholder" role="img" aria-label={t.account_placeholder}><i className="ph ph-user" aria-hidden="true" /></span>
        </div>
      </header>
      <div className="shell-body">
        <nav id="navigation" className="drawer" aria-label={t.navigation}>
          <a href="#content" className="drawer-item selected" aria-current="page" aria-label={t.home}>
            <i className="ph-fill ph-house" aria-hidden="true" /><span className="drawer-label">{t.home}</span></a>
          <div className="drawer-section" aria-label={t.enrolled}><i className="ph ph-graduation-cap" aria-hidden="true" />
            <span className="drawer-label">{t.enrolled}</span></div>
          <div className="drawer-rule" />
          <button type="button" className="drawer-item" aria-label={t.archived_classes} disabled>
            <i className="ph ph-archive" aria-hidden="true" /><span className="drawer-label">{t.archived_classes}</span></button>
          <button type="button" className="drawer-item" aria-label={t.settings} disabled>
            <i className="ph ph-gear" aria-hidden="true" /><span className="drawer-label">{t.settings}</span></button>
        </nav>
        <main id="content" className="content-sheet" tabIndex={-1}>
          <div className="content-wrap">
            <HubStatusBanner t={t} variant="success" hubName={connectedHub.name} />
            <div className="classes-heading"><h1>{t.classes_title}</h1>
              <button type="button" className="control text-button" disabled aria-describedby="offline-notice">
                <i className="ph ph-plus" aria-hidden="true" />{t.join_class}</button></div>
            {status === 'loading' ? (
              <div role="status" aria-label={t.loading} className="class-grid">
                {[0, 1, 2].map((key) => <div key={key} className="skeleton class-skeleton" />)}</div>
            ) : status === 'error' ? (
              <section role="alert" className="database-error">
                <h2><i className="ph ph-warning-circle" aria-hidden="true" />{t.database_error_title}</h2>
                <p>{t.database_error_body}</p><button type="button" onClick={() => { void loadSavedClasses(); }}
                  className="control tonal-button">{t.retry}</button></section>
            ) : classrooms.length === 0 ? (
              <section className="empty-state"><span className="empty-art"><i className="ph ph-graduation-cap" aria-hidden="true" /></span>
                <div><h2>{t.empty_title}</h2><p>{t.empty_body}</p>
                  <button type="button" className="control tonal-button" disabled aria-describedby="offline-notice">
                    <i className="ph ph-plus" aria-hidden="true" />{t.join_class}</button></div></section>
            ) : (
              <div className="class-grid">{classrooms.map((classroom) => (
                <article key={classroom.id} className="class-card">
                  <i className="ph ph-chalkboard-teacher class-icon" aria-hidden="true" />
                  <h2>{classroom.name}</h2><p>{classroom.section}</p>
                  <span className="saved-label"><i className="ph ph-hard-drive" aria-hidden="true" />{t.saved_on_device}</span>
                </article>))}</div>
            )}
          </div>
        </main>
      </div>
    </div>
  );
}
