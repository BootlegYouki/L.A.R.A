import { useState } from 'react';
import type { Dictionary } from '../i18n';
import { manualEndpoint, type HubInfo } from '../services/discovery';

export default function HubDiscovery({ t, hubs, failed, searching }: { t: Dictionary; hubs: HubInfo[]; failed: boolean; searching: boolean }) {
  const [address, setAddress] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  return <section className="discovery-panel" aria-labelledby="discovery-title">
    <h2 id="discovery-title"><i className="ph ph-broadcast" aria-hidden="true" />{t.discovery_title}</h2>
    <p>{t.discovery_help}</p>
    <div role="status">
      {failed ? <p><i className="ph ph-warning-circle" aria-hidden="true" /> {t.discovery_error}</p>
        : hubs.length === 0 && searching ? <div role="status" aria-label={t.discovery_loading} className="skeleton hub-skeleton" /> : hubs.length === 0 ? <p>{t.discovery_empty}</p> : <ul className="hub-list">{hubs.map((hub) => {
          const endpoint = `${hub.ip}:${hub.http_port}`;
          return <li key={endpoint}><div><strong>{hub.name}</strong><p>{endpoint}</p></div>
            <button type="button" className="control tonal-button" aria-label={`${t.select_address}: ${hub.name}`} onClick={() => { setAddress(endpoint); setInvalid(false); setSelected(endpoint); }}>{t.select_address}</button></li>;
        })}</ul>}
    </div>
    <form onSubmit={(event) => {
      event.preventDefault();
      const endpoint = manualEndpoint(address);
      setInvalid(endpoint === null);
      if (endpoint) setSelected(`${endpoint.ip}:${endpoint.http_port}`);
      else setSelected(null);
    }}>
      <label htmlFor="hub-address">{t.manual_address}</label>
      <p id="hub-address-help">{t.manual_help}</p>
      <div className="manual-address-row"><input id="hub-address" value={address} autoComplete="off" spellCheck={false}
        maxLength={21} aria-invalid={invalid} aria-describedby={invalid ? 'hub-address-help hub-address-error' : 'hub-address-help'}
        onChange={(event) => { setAddress(event.target.value); setInvalid(false); setSelected(null); }} />
        <button type="submit" className="control tonal-button">{t.select_address}</button></div>
      {invalid && <p id="hub-address-error" role="alert" className="address-error"><i className="ph ph-warning-circle" aria-hidden="true" /> {t.manual_invalid}</p>}
    </form>
    {selected && <p role="status">{t.address_selected} <strong>{selected}</strong>. {t.address_unverified}</p>}
  </section>;
}
