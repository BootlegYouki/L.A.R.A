import { useEffect, useState } from 'react';
import { dictionaries } from './i18n';
import { useAppStore } from './store/app';

export default function App() {
  const { language, status, classrooms, toggleLanguage, loadSavedClasses } = useAppStore();
  const [drawerOpen, setDrawerOpen] = useState(true);
  const t = dictionaries[language];
  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => { void loadSavedClasses(); }, [loadSavedClasses]);
  return (
    <div className={`app-shell ${drawerOpen ? '' : 'drawer-rail'}`}>
      <a href="#content" className="skip-link">{t.skip_content}</a>
      <header className="top-bar">
        <button type="button" className="icon-button menu-button" aria-label={t.toggle_menu} aria-expanded={drawerOpen}
          aria-controls="navigation" onClick={() => setDrawerOpen(!drawerOpen)}><i className="ph ph-list" aria-hidden="true" /></button>
        <span className="brand">{t.app_title}</span>
        <div className="top-actions">
          <span className="hub-pill"><i className="ph ph-wifi-slash" aria-hidden="true" /><span>{t.hub_offline}</span></span>
          <button type="button" onClick={toggleLanguage} aria-label={t.switch_language_label} className="control language-button">
            <i className="ph ph-translate" aria-hidden="true" />{t.switch_language}</button>
          <button type="button" className="icon-button" aria-label={t.join_class} disabled aria-describedby="offline-notice">
            <i className="ph ph-plus" aria-hidden="true" /></button>
          <span className="avatar-placeholder" role="img" aria-label={t.account_placeholder}><i className="ph ph-user" aria-hidden="true" /></span>
        </div>
      </header>
      <div className="shell-body">
        <nav id="navigation" className="drawer" aria-label={t.navigation}>
          <a href="#content" className="drawer-item selected" aria-current="page" aria-label={t.home}>
            <i className="ph ph-house" aria-hidden="true" /><span className="drawer-label">{t.home}</span></a>
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
            <div id="offline-notice" role="status" className="offline-banner">
              <i className="ph ph-wifi-slash" aria-hidden="true" /><p>{t.offline_body}</p></div>
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
