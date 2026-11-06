import type { Dictionary } from '../i18n';
import type { HubConnectionState } from '../services/connection';

export default function HubStatusBanner({ t, connection }: { t: Dictionary; connection: HubConnectionState }) {
  const connected = connection.status === 'connected';
  const connecting = connection.status === 'connecting';
  const message = connected ? t.connected_to_hub.replace('{name}', connection.hub?.name ?? '')
    : connecting ? t.connecting_to_hub : connection.hub ? t.connection_failed : t.offline_body;
  return <div id="offline-notice" role="status" className={`hub-status-banner banner-${connected ? 'success' : connecting ? 'info' : 'warning'}`}>
    <i className={`ph ${connected ? 'ph-wifi-high' : connecting ? 'ph-broadcast' : 'ph-wifi-slash'}`} aria-hidden="true" />
    <div><p>{message}</p>{connected && !connection.persisted && <p>{t.preference_not_saved}</p>}</div>
  </div>;
}
