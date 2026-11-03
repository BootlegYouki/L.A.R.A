import { useState } from 'react';
import type { Dictionary } from '../i18n';
import type { HubConnectionState } from '../services/connection';
import { manualEndpoint, type HubInfo } from '../services/discovery';

interface Props {
  t: Dictionary; hubs: HubInfo[]; failed: boolean; searching: boolean;
  connection: HubConnectionState; onConnect: (hub: HubInfo) => void;
}
export default function ConnectionPage({ t, hubs, failed, searching, connection, onConnect }: Props) {
  const [address, setAddress] = useState('');
  const [invalid, setInvalid] = useState(false);
  const connecting = connection.status === 'connecting';
  return <section className="discovery-panel" aria-labelledby="discovery-title">
    <h2 id="discovery-title"><i className="ph ph-broadcast" aria-hidden="true" />{t.discovery_title}</h2>
    <p>{t.discovery_help}</p>
    <div role="status">
      {failed ? <p><i className="ph ph-warning-circle" aria-hidden="true" /> {t.discovery_error}</p>
        : hubs.length === 0 && searching ? <div aria-label={t.discovery_loading} className="skeleton hub-skeleton" />
        : hubs.length === 0 ? <p>{t.discovery_empty}</p>
        : <ul className="hub-list">{hubs.map((hub) => <li key={`${hub.ip}:${hub.http_port}`}>
          <div><strong>{hub.name}</strong><p>{hub.ip}:{hub.http_port}</p></div>
          <button type="button" className="control tonal-button" disabled={connecting}
            aria-label={`${t.connect_to}: ${hub.name}`} onClick={() => { setInvalid(false); onConnect(hub); }}>{t.connect}</button>
        </li>)}</ul>}
    </div>
    {connection.hub && <p>{t.address_selected} {connection.hub.ip}:{connection.hub.http_port}</p>}
    <form onSubmit={(event) => {
      event.preventDefault();
      const endpoint = manualEndpoint(address);
      setInvalid(!endpoint);
      if (endpoint) onConnect({ ...endpoint, name: `${endpoint.ip}:${endpoint.http_port}` });
    }}>
      <label htmlFor="hub-address">{t.manual_address}</label><p id="hub-address-help">{t.manual_help}</p>
      <div className="manual-address-row">
        <input id="hub-address" value={address} autoComplete="off" spellCheck={false} maxLength={21}
          aria-invalid={invalid} aria-describedby={invalid ? 'hub-address-help hub-address-error' : 'hub-address-help'}
          onChange={(event) => { setAddress(event.target.value); setInvalid(false); }} />
        <button type="submit" className="control tonal-button" disabled={connecting}>{connecting ? t.connecting : t.connect}</button>
      </div>
      {invalid && <p id="hub-address-error" role="alert" className="address-error"><i className="ph ph-warning-circle" aria-hidden="true" /> {t.manual_invalid}</p>}
    </form>
  </section>;
}
