import { useEffect, useState } from 'react';
import type { Dictionary } from '../i18n';
import HubStatusBanner, { type BannerVariant } from '../components/HubStatusBanner';
import { manualEndpoint, observeDiscovery, probeHub, type HubInfo } from '../services/discovery';
import { getLastHubConnection, saveHubConnection } from '../services/db';

interface ConnectionPageProps {
  t: Dictionary;
  onConnected: (address: string, hubName: string) => void;
}

export default function ConnectionPage({ t, onConnected }: ConnectionPageProps) {
  const [hubs, setHubs] = useState<HubInfo[]>([]);
  const [discoveryFailed, setDiscoveryFailed] = useState(false);
  const [searching, setSearching] = useState(true);
  const [address, setAddress] = useState('');
  const [invalid, setInvalid] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [bannerVariant, setBannerVariant] = useState<BannerVariant>('info');
  const [bannerMessage, setBannerMessage] = useState<string>(t.searching_for_hubs);
  const [connectedHub, setConnectedHub] = useState<string | null>(null);

  // Discovery timeout
  useEffect(() => {
    const timer = setTimeout(() => setSearching(false), 3000);
    return () => clearTimeout(timer);
  }, []);

  // Observe discovery
  useEffect(() => observeDiscovery((found, failed) => {
    setHubs(found);
    setDiscoveryFailed(failed);
    if (!connecting) {
      if (failed) {
        setBannerVariant('warning');
        setBannerMessage(t.discovery_error);
      } else if (found.length > 0 && searching) {
        setBannerVariant('info');
        setBannerMessage(t.hubs_found);
      } else if (!searching && found.length === 0) {
        setBannerVariant('warning');
        setBannerMessage(t.no_hubs_found);
      }
    }
  }), [connecting, searching, t]);

  // Auto-connect to last hub
  useEffect(() => {
    void (async () => {
      try {
        const lastHub = await getLastHubConnection();
        if (lastHub) {
          setAddress(lastHub);
          // Don't auto-connect immediately - let user see the UI and click Connect
          // This avoids the 11-13 second hang if Hub is offline
        }
      } catch {
        // No previous connection, proceed with manual selection
      }
    })();
  }, []);

  const handleConnect = async (targetAddress: string) => {
    const endpoint = manualEndpoint(targetAddress);
    
    if (!endpoint) {
      setInvalid(true);
      setBannerVariant('danger');
      setBannerMessage(t.invalid_address);
      return;
    }

    setConnecting(true);
    setInvalid(false);
    setBannerVariant('info');
    setBannerMessage(t.connecting_to_hub);

    try {
      const result = await probeHub(targetAddress);
      
      if (result.reachable) {
        const hubAddress = `${endpoint.ip}:${endpoint.http_port}`;
        const hubName = result.name || hubAddress;
        
        await saveHubConnection(hubAddress);
        setConnectedHub(hubName);
        setBannerVariant('success');
        setBannerMessage(t.connected_to_hub.replace('{name}', hubName));
        
        // Notify parent component
        onConnected(hubAddress, hubName);
      } else {
        setBannerVariant('danger');
        setBannerMessage(t.connection_failed);
        setConnecting(false);
      }
    } catch (error) {
      console.error('[ConnectionPage] Error in handleConnect:', error);
      setBannerVariant('danger');
      setBannerMessage(t.connection_error);
      setConnecting(false);
    }
  };

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    void handleConnect(address);
  };

  const handleSelectHub = (hub: HubInfo) => {
    const hubAddress = `${hub.ip}:${hub.http_port}`;
    setAddress(hubAddress);
    void handleConnect(hubAddress);
  };

  return (
    <div className="connection-page">
      <HubStatusBanner
        t={t}
        variant={bannerVariant}
        hubName={connectedHub || undefined}
        message={bannerMessage}
      />

      <section className="discovery-panel" aria-labelledby="discovery-title">
        <h2 id="discovery-title">
          <i className="ph ph-broadcast" aria-hidden="true" />
          {t.discovery_title}
        </h2>
        <p>{t.discovery_help}</p>

        <div role="status">
          {discoveryFailed ? (
            <p>
              <i className="ph ph-warning-circle" aria-hidden="true" /> {t.discovery_error}
            </p>
          ) : hubs.length === 0 && searching ? (
            <div role="status" aria-label={t.discovery_loading} className="skeleton hub-skeleton" />
          ) : hubs.length === 0 ? (
            <p>{t.discovery_empty}</p>
          ) : (
            <ul className="hub-list">
              {hubs.map((hub) => {
                const endpoint = `${hub.ip}:${hub.http_port}`;
                return (
                  <li key={endpoint}>
                    <div>
                      <strong>{hub.name}</strong>
                      <p>{endpoint}</p>
                    </div>
                    <button
                      type="button"
                      className="control tonal-button"
                      aria-label={`${t.connect_to}: ${hub.name}`}
                      onClick={() => handleSelectHub(hub)}
                      disabled={connecting}
                    >
                      {t.connect}
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <form onSubmit={handleSubmit}>
          <label htmlFor="hub-address">{t.manual_address}</label>
          <p id="hub-address-help">{t.manual_help}</p>
          <div className="manual-address-row">
            <input
              id="hub-address"
              value={address}
              autoComplete="off"
              spellCheck={false}
              maxLength={21}
              aria-invalid={invalid}
              aria-describedby={invalid ? 'hub-address-help hub-address-error' : 'hub-address-help'}
              onChange={(event) => {
                setAddress(event.target.value);
                setInvalid(false);
              }}
              disabled={connecting}
            />
            <button
              type="submit"
              className="control tonal-button"
              disabled={connecting || !address.trim()}
            >
              {connecting ? t.connecting : t.connect}
            </button>
          </div>
          {invalid && (
            <p id="hub-address-error" role="alert" className="address-error">
              <i className="ph ph-warning-circle" aria-hidden="true" /> {t.manual_invalid}
            </p>
          )}
        </form>
      </section>
    </div>
  );
}
