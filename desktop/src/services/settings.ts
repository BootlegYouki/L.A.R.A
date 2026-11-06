import { isHubInfo, type HubInfo } from './discovery';
const key = 'lara-desktop-hub';

export function saveHubPreference(hub: HubInfo): boolean {
  if (!isHubInfo(hub)) return false;
  try {
    localStorage.setItem(key, JSON.stringify({ ...hub,
      rest_base: `http://${hub.ip}:${hub.http_port}`, websocket_url: `ws://${hub.ip}:${hub.ws_port}`,
    }));
    return true;
  } catch { return false; }
}
export function readHubPreference(): HubInfo | null {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? 'null');
    if (!isHubInfo(value) || !('rest_base' in value) || !('websocket_url' in value)
      || value.rest_base !== `http://${value.ip}:${value.http_port}`
      || value.websocket_url !== `ws://${value.ip}:${value.ws_port}`) return null;
    return { name: value.name, ip: value.ip, http_port: value.http_port, ws_port: value.ws_port };
  } catch { return null; }
}
