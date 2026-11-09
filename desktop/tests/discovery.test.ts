import { afterEach, expect, it, vi } from 'vitest';
import { isHubInfo, manualEndpoint, observeDiscovery } from '../src/services/discovery';
const { invoke, listen } = vi.hoisted(() => ({ invoke: vi.fn(), listen: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen }));
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });
const hub = { name: 'Room 1', ip: '192.168.1.50', http_port: 8080, ws_port: 8081 };
it('validates canonical local manual addresses and native payloads', () => {
  expect(manualEndpoint(' 192.168.1.50 ')).toEqual({ ip: hub.ip, http_port:8080, ws_port:8081 });
  expect(manualEndpoint('10.1.1.1:65535')?.http_port).toBe(65535);
  expect(isHubInfo(hub)).toBe(true);
  for (const ip of ['999.1.1.1','192.168.01.1','8.8.8.8','0.0.0.0','224.0.0.1','localhost','http://192.168.1.1','192.168.1.1:0','192.168.1.1:65536','192.168.1.1/path','192.168.1.1:08080']) {
    expect(manualEndpoint(ip), ip).toBeNull();
  }
  for (const value of [null, {}, {...hub,ws_port:0}, {...hub,http_port:'8080'}, {...hub,name:'\n'}, {...hub,ip:'8.8.8.8'}]) expect(isHubInfo(value)).toBe(false);
});
it('refreshes expired entries and unregisters without accepting late results', async () => {
  vi.useFakeTimers();
  const dispose = vi.fn();
  listen.mockResolvedValue(dispose);
  invoke.mockResolvedValueOnce([hub]).mockResolvedValueOnce([]);
  const update = vi.fn();
  const stop = observeDiscovery(update);
  await vi.advanceTimersByTimeAsync(0);
  expect(update).toHaveBeenLastCalledWith([hub],false);
  await vi.advanceTimersByTimeAsync(1000);
  expect(update).toHaveBeenLastCalledWith([],false);
  stop();
  await vi.advanceTimersByTimeAsync(2000);
  expect(invoke).toHaveBeenCalledTimes(2);
  expect(dispose).toHaveBeenCalledTimes(1);
});
it('cleans up a listener that resolves after unmount and ignores late IPC', async () => {
  let registration!: (value: () => void) => void;
  let result!: (value: unknown) => void;
  listen.mockReturnValue(new Promise((resolve) => { registration=resolve; }));
  invoke.mockReturnValue(new Promise((resolve) => { result=resolve; }));
  const dispose=vi.fn(), update=vi.fn();
  const stop=observeDiscovery(update); stop();
  registration(dispose); result([hub]);
  await Promise.resolve(); await Promise.resolve();
  expect(dispose).toHaveBeenCalledTimes(1);
  expect(update).not.toHaveBeenCalled();
});
it('rejects malformed IPC without trusting event data', async () => {
  listen.mockResolvedValue(vi.fn()); invoke.mockResolvedValue([{...hub,ip:'8.8.8.8'}]);
  const update=vi.fn(); const stop=observeDiscovery(update);
  await Promise.resolve(); await Promise.resolve();
  expect(update).toHaveBeenCalledWith([],true); stop();
});
