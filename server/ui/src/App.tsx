import { invoke } from '@tauri-apps/api/core';
import { useEffect, useState } from 'react';
import { type Dictionary, useStrings } from './i18n';

/** Answer of the `lan_address` Tauri command (`server/src-tauri/src/main.rs`). */
type HubStatus = { ip: string | null; running: boolean };

// DHCP can hand the Hub PC a new address while the window is open.
const REFRESH_MS = 5000;

function useHubStatus(): HubStatus | null {
  const [status, setStatus] = useState<HubStatus | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () =>
      invoke<HubStatus>('lan_address')
        .then((s) => alive && setStatus(s))
        .catch(() => alive && setStatus({ ip: null, running: false }));
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  return status;
}

function Beacon({ status, t }: { status: HubStatus; t: Dictionary }) {
  const [dot, label] = !status.running
    ? ['bg-lara-ink-3', t.beacon_stopped]
    : status.ip
      ? ['bg-lara-success', t.beacon_running]
      : ['bg-lara-ink-3', t.beacon_no_network];
  return (
    <span className="inline-flex items-center gap-2 rounded-full border border-lara-border bg-white px-3 py-1 text-xs leading-4 font-extrabold text-lara-ink uppercase">
      <span className={`h-2.5 w-2.5 rounded-full ${dot}`} aria-hidden="true" />
      {label}
    </span>
  );
}

export default function App() {
  const { t, toggle } = useStrings();
  const status = useHubStatus();

  return (
    <div className="min-h-screen bg-lara-canvas font-sans text-lara-ink">
      <header className="flex h-16 items-center gap-4 px-6">
        <span className="text-sm leading-5 font-extrabold">{t.app_title}</span>
        {status && <Beacon status={status} t={t} />}
        <button
          type="button"
          onClick={toggle}
          aria-label={t.switch_language_label}
          className="ml-auto inline-flex min-h-control items-center gap-2 rounded-full border-2 border-lara-border bg-white px-6 text-sm leading-5 font-extrabold text-lara-primary-dark hover:bg-lara-subtle focus-visible:outline-2 focus-visible:outline-lara-primary"
        >
          <i className="ph ph-translate text-xl" aria-hidden="true" />
          {t.switch_language}
        </button>
      </header>

      <main className="mx-auto flex max-w-2xl flex-col gap-4 px-6 pt-12">
        {status && !status.running && (
          <div
            role="alert"
            className="flex items-start gap-3 rounded-md border border-lara-danger-border bg-lara-danger-light px-4 py-3 text-sm leading-5 font-medium text-lara-danger-dark"
          >
            <i className="ph ph-warning-circle text-[22px] text-lara-danger" aria-hidden="true" />
            <p>
              <strong className="font-extrabold">{t.error_title}:</strong> {t.error_body}
            </p>
          </div>
        )}

        <section className="flex flex-col gap-4 rounded-lg border border-lara-border bg-white p-8 shadow-card">
          <h1 className="flex items-center gap-2 text-sm leading-5 font-extrabold text-lara-ink-2">
            <i className="ph ph-broadcast text-xl" aria-hidden="true" />
            {t.address_label}
          </h1>
          {status === null ? (
            <div role="status" aria-label={t.loading} className="flex flex-col gap-4">
              <div className="skeleton h-12 w-72 rounded-md bg-lara-subtle" />
              <div className="skeleton h-5 w-full rounded-md bg-lara-subtle" />
            </div>
          ) : status.ip ? (
            <>
              <p className="text-[40px] leading-[48px] font-black tracking-wide select-all">{status.ip}</p>
              {/* While stopped, nothing answers at this address, so do not send learners to it. */}
              {status.running && <p className="text-sm leading-5 font-medium text-lara-ink-2">{t.address_help}</p>}
            </>
          ) : (
            <p className="flex items-center gap-3 text-sm leading-5 font-medium text-lara-ink-2">
              <i className="ph ph-wifi-slash text-[28px]" aria-hidden="true" />
              {t.address_none}
            </p>
          )}
        </section>
      </main>
    </div>
  );
}
