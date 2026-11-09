import {isHubInfo, type HubInfo} from './discovery';
import {readHubPreference} from './settings';

let endpoint: string | null = null, installed = false, reloading = false;
const address = (hub: HubInfo | null) => hub && isHubInfo(hub) ? `ws://${hub.ip}:${hub.ws_port}` : null;
// CSP policies intersect and cannot be relaxed by changing/removing their meta element.
export function installWebSocketPolicy(hub: HubInfo | null) {
  endpoint = address(hub); installed = true; reloading = false;
  const policy = document.createElement('meta');
  policy.httpEquiv = 'Content-Security-Policy';
  policy.content = `connect-src 'self' ipc: http://ipc.localhost${endpoint ? ` ${endpoint}` : ''}${import.meta.env.DEV ? ' ws://127.0.0.1:1420' : ''}`;
  document.head.appendChild(policy);
}
export function ensureWebSocketPolicy(hub: HubInfo): boolean {
  const next = address(hub);
  if (!installed || !next) return false;
  if (next === endpoint) return true;
  // A new document replaces the immutable policy instead of accumulating allowed Hubs.
  if (!reloading && address(readHubPreference()) === next) { reloading = true; window.location.reload(); }
  return false;
}
