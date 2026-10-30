import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';

export interface HubInfo {
  name: string;
  ip: string;
  http_port: number;
  ws_port: number;
}
export function manualEndpoint(input: string): Omit<HubInfo, 'name'> | null {
  const match = /^(\d{1,3}(?:\.\d{1,3}){3})(?::([1-9]\d{0,4}))?$/.exec(input.trim());
  if (!match) return null;
  const octets = match[1].split('.');
  if (octets.some((part) => Number(part) > 255 || (part.length > 1 && part.startsWith('0')))) return null;
  const [a, b] = octets.map(Number);
  if (!(a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168))) return null;
  const port = match[2] ? Number(match[2]) : 8080;
  return port <= 65535 ? { ip: match[1], http_port: port, ws_port: 8081 } : null;
}
export function isHubInfo(value: unknown): value is HubInfo {
  if (typeof value !== 'object' || value === null) return false;
  if (!('name' in value && typeof value.name === 'string' && value.name.trim() && [...value.name].length <= 160
    && !/\p{Cc}/u.test(value.name) && 'ip' in value && typeof value.ip === 'string'
    && 'http_port' in value && typeof value.http_port === 'number' && Number.isInteger(value.http_port)
    && 'ws_port' in value && typeof value.ws_port === 'number' && Number.isInteger(value.ws_port))) return false;
  return manualEndpoint(`${value.ip}:${value.http_port}`) !== null && value.ws_port > 0 && value.ws_port <= 65535;
}

export function observeDiscovery(update: (hubs: HubInfo[], failed: boolean) => void): () => void {
  let stopped = false;
  let pending = false;
  let unlisten: (() => void) | undefined;
  const refresh = async () => {
    if (stopped || pending) return;
    pending = true;
    try {
      const payload: unknown = await invoke('get_discovered_hubs');
      if (!Array.isArray(payload) || payload.length > 64 || !payload.every(isHubInfo)) throw new Error('invalid_discovery');
      if (!stopped) update(payload, false);
    } catch { if (!stopped) update([], true); }
    finally { pending = false; }
  };
  // Polling also removes expired peers; hub-found shortens the first-discovery delay.
  void listen('hub-found', () => { void refresh(); }).then((dispose) => {
    if (stopped) dispose(); else unlisten = dispose;
  }).catch(() => { /* Snapshot polling remains available if event registration fails. */ });
  void refresh();
  const timer = setInterval(() => { void refresh(); }, 1000);
  return () => { stopped = true; clearInterval(timer); unlisten?.(); };
}
