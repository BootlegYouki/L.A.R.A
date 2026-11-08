import { useRef, useState, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import { countQueuedWork } from '../services/settings';
import type { CachedAccount } from '../services/auth';
import type { HubConnectionState } from '../services/connection';
import { version } from '../../src-tauri/tauri.conf.json';

interface Props {
  account: CachedAccount;
  connection: HubConnectionState;
  hubName?: string;
  busy: boolean;
  error: string | null;
  onSignOut: () => void;
  children: ReactNode;
}

export default function SettingsPage({ account, connection, hubName, busy, error, onSignOut, children }: Props) {
  const [open, setOpen] = useState(false);
  const [count, setCount] = useState<number | null>(null);
  const [countError, setCountError] = useState(false);
  const queueCheck = useRef(0);
  const address = connection.hub && `${connection.hub.ip}:${connection.hub.http_port}`;
  const name = hubName || connection.hub?.name;

  async function checkQueue() {
    const current = ++queueCheck.current;
    setCount(null);
    setCountError(false);
    try {
      const queued = await countQueuedWork(account);
      if (current === queueCheck.current) setCount(queued);
    } catch {
      if (current === queueCheck.current) setCountError(true);
    }
  }

  return <section className="settings-page" aria-labelledby="settings-title">
    <h1 id="settings-title">{t.settings}</h1>
    <section className="discovery-panel" aria-labelledby="settings-hub-title">
      <h2 id="settings-hub-title">{t.auth_hub_settings}</h2>
      {address && <p>{name && name !== address && <><strong>{name}</strong> · </>}{address}</p>}
      <p>{connection.status === 'connected' ? t.settings_hub_connected : connection.status === 'connecting' ? t.connecting : t.hub_offline}</p>
      <details><summary>{t.settings_choose_hub}</summary>{children}</details>
    </section>
    <section className="discovery-panel" aria-labelledby="settings-account-title">
      <h2 id="settings-account-title">{t.settings_account}</h2>
      <p>{account.user.full_name}</p>
      <Dialog.Root open={open} onOpenChange={next => {
        if (busy) return;
        setOpen(next);
        if (next) void checkQueue();
        else queueCheck.current++;
      }}>
        <Dialog.Trigger asChild><button type="button" className="control destructive-button" disabled={busy}>{t.sign_out}</button></Dialog.Trigger>
        <Dialog.Portal>
          <Dialog.Overlay className="join-scrim" />
          <Dialog.Content className="join-dialog" aria-busy={busy}>
            <Dialog.Title>{t.sign_out_title}</Dialog.Title>
            <Dialog.Description>{count === null ? t.sign_out_checking : count === 0 ? t.sign_out_confirm :
              (count === 1 ? t.sign_out_queued_one : t.sign_out_queued_many).replace('{count}', String(count))}</Dialog.Description>
            {countError && <p role="alert" className="join-error">{t.sign_out_count_error}</p>}
            {error && <p role="alert" className="join-error">{error}</p>}
            <div className="join-actions">
              <Dialog.Close asChild><button type="button" className="control text-button" disabled={busy}>{t.cancel}</button></Dialog.Close>
              {countError && <button type="button" className="control tonal-button" onClick={() => void checkQueue()}>{t.retry}</button>}
              <button type="button" className="control destructive-button" disabled={busy || count === null} onClick={onSignOut}>{busy ? t.sign_out_working : t.sign_out}</button>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </section>
    <section className="discovery-panel" aria-labelledby="settings-about-title">
      <h2 id="settings-about-title">{t.settings_about}</h2>
      <p>{t.settings_version.replace('{version}', version)}</p>
      <p>{t.settings_description}</p>
    </section>
  </section>;
}
