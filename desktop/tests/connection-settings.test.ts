import { afterEach, expect, it, vi } from 'vitest';
import { readHubPreference, saveHubPreference } from '../src/services/settings';
const hub = { name: 'Room', ip: '192.168.1.50', http_port: 8080, ws_port: 8085 };
afterEach(() => vi.unstubAllGlobals());
it('persists REST and WebSocket bases separately without touching SQL', () => {
  const entries = new Map<string,string>();
  vi.stubGlobal('localStorage', { getItem: (k:string) => entries.get(k) ?? null, setItem: (k:string,v:string) => entries.set(k,v) });
  expect(saveHubPreference(hub)).toBe(true);
  const value = JSON.parse([...entries.values()][0]);
  expect(value.rest_base).toBe('http://192.168.1.50:8080');
  expect(value.websocket_url).toBe('ws://192.168.1.50:8085');
  expect(readHubPreference()).toEqual(hub);
});
it('ignores corrupt or foreign-origin preferences and reports unavailable storage', () => {
  vi.stubGlobal('localStorage', { getItem: () => '{bad', setItem: () => { throw Error('quota'); } });
  expect(readHubPreference()).toBeNull();
  expect(saveHubPreference(hub)).toBe(false);
  vi.stubGlobal('localStorage', { getItem: () => JSON.stringify({ ...hub, rest_base:'http://8.8.8.8', websocket_url:'ws://192.168.1.50:8085' }) });
  expect(readHubPreference()).toBeNull();
});
