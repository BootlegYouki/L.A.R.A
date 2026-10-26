import { useEffect } from 'react';
import { dictionaries } from './i18n';
import { useAppStore } from './store/app';

export default function App() {
  const { language, status, classrooms, toggleLanguage, loadSavedClasses } = useAppStore();
  const t = dictionaries[language];

  useEffect(() => { document.documentElement.lang = language; }, [language]);
  useEffect(() => { void loadSavedClasses(); }, [loadSavedClasses]);

  return (
    <div className="min-h-screen bg-lara-canvas font-sans text-lara-ink">
      <a href="#content" className="sr-only focus:not-sr-only focus:block focus:p-4">{t.skip_content}</a>
      <header className="flex min-h-16 flex-wrap items-center gap-4 border-b border-lara-border bg-white px-6 py-3">
        <span className="flex items-center gap-3 text-lg font-extrabold text-lara-primary-dark">
          <i className="ph ph-chalkboard-teacher text-2xl" aria-hidden="true" />{t.app_title}
        </span>
        <span className="border-l border-lara-border pl-4 text-sm font-extrabold">{t.home}</span>
        <button type="button" onClick={toggleLanguage} aria-label={t.switch_language_label}
          className="control ml-auto border border-lara-border bg-white text-lara-primary-dark hover:bg-lara-subtle">
          <i className="ph ph-translate text-xl" aria-hidden="true" />{t.switch_language}
        </button>
      </header>
      <main id="content" className="mx-auto max-w-6xl px-6 py-8">
        <div role="status" className="mb-8 flex items-start gap-3 rounded-md border border-lara-warning-border bg-lara-warning-light p-4 text-sm text-lara-warning-dark">
          <i className="ph ph-wifi-slash mt-0.5 text-xl" aria-hidden="true" />
          <p><strong className="font-extrabold">{t.hub_offline}:</strong> {t.offline_body}</p>
        </div>
        <h1 className="text-[28px] leading-9 font-extrabold">{t.classes_title}</h1>
        <p className="mt-2 text-base leading-6 text-lara-ink-2">{t.classes_description}</p>
        {status === 'loading' ? (
          <div role="status" aria-label={t.loading} className="mt-8 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2].map((key) => <div key={key} className="skeleton h-48 rounded-lg bg-lara-border" />)}
          </div>
        ) : status === 'error' ? (
          <section role="alert" className="mt-8 rounded-lg border border-lara-danger-border bg-lara-danger-light p-6">
            <h2 className="flex items-center gap-2 text-lg font-extrabold text-lara-danger-dark">
              <i className="ph ph-warning-circle" aria-hidden="true" />{t.database_error_title}
            </h2>
            <p className="mt-2 text-base">{t.database_error_body}</p>
            <button type="button" onClick={() => { void loadSavedClasses(); }}
              className="control mt-4 border border-lara-border bg-white text-lara-primary-dark">{t.retry}</button>
          </section>
        ) : classrooms.length === 0 ? (
          <section className="mt-8 flex flex-col items-center rounded-lg border border-lara-border bg-white px-6 py-12 text-center shadow-card">
            <i className="ph ph-books flex h-20 w-20 items-center justify-center rounded-full bg-lara-primary-light text-[40px] text-lara-primary-dark" aria-hidden="true" />
            <h2 className="mt-6 text-lg leading-[26px] font-extrabold">{t.empty_title}</h2>
            <p className="mt-2 text-base text-lara-ink-2">{t.empty_body}</p>
          </section>
        ) : (
          <div className="mt-8 grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
            {classrooms.map((classroom) => (
              <article key={classroom.id} className="rounded-lg border border-lara-border bg-white p-6 shadow-card">
                <i className="ph ph-chalkboard-teacher flex h-14 w-14 items-center justify-center rounded-md bg-lara-primary-dark text-[28px] text-white" aria-hidden="true" />
                <h2 className="mt-4 break-words text-lg font-extrabold">{classroom.name}</h2>
                <p className="mt-2 break-words text-base text-lara-ink-2">{classroom.section}</p>
                <p className="mt-6 flex items-center gap-2 text-sm font-semibold text-lara-primary-dark">
                  <i className="ph ph-hard-drive" aria-hidden="true" />{t.saved_on_device}
                </p>
              </article>
            ))}
          </div>
        )}
        <p className="mt-6 text-sm leading-5 text-lara-ink-2">{t.foundation_note}</p>
      </main>
    </div>
  );
}

