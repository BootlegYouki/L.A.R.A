import { isHubInfo, type HubInfo } from './discovery';
import { invoke } from '@tauri-apps/api/core';
import { withDatabaseTimeout } from './db';
import type { CachedAccount } from './auth';
const key = 'lara-desktop-hub';

export async function countQueuedWork(account: CachedAccount): Promise<number> {
  const count = await withDatabaseTimeout(invoke<unknown>('count_queued_work', { hub: account.hub, userId: account.user.id }));
  if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0) throw new Error('invalid_queue_count');
  return count;
}

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
