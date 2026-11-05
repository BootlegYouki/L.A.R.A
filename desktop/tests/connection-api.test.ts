import { afterEach, expect, it, vi } from 'vitest';
import { probeHub } from '../src/services/api';
const { fetch } = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock('@tauri-apps/plugin-http', () => ({ fetch }));
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks(); });
const hub = { name: 'Room', ip: '192.168.1.50', http_port: 8080, ws_port: 8085 };
it('uses native contracted probe, accepts unauthenticated 401 and releases its body', async () => {
  const cancel = vi.fn().mockResolvedValue(undefined);
  fetch.mockResolvedValue({ status: 401, url: 'http://192.168.1.50:8080/api/classrooms', body: { cancel } });
  expect(await probeHub(hub)).toBe(true);
  expect(fetch).toHaveBeenCalledWith('http://192.168.1.50:8080/api/classrooms', expect.objectContaining({ method: 'GET', maxRedirections: 0, credentials: 'omit' }));
  expect(cancel).toHaveBeenCalledTimes(1);
});
it('rejects public addresses, redirects and unrelated error pages', async () => {
  expect(await probeHub({ ...hub, ip: '8.8.8.8' })).toBe(false);
  expect(fetch).not.toHaveBeenCalled();
  for (const status of [302, 404, 501, 500]) {
    fetch.mockResolvedValue({ status, url: 'http://192.168.1.50:8080/api/classrooms' });
    expect(await probeHub(hub)).toBe(false);
  }
  fetch.mockResolvedValue({ status: 200, url: 'http://8.8.8.8/api/classrooms' });
  expect(await probeHub(hub)).toBe(false);
});
it('aborts a stalled probe and returns without leaving its deadline active', async () => {
  vi.useFakeTimers();
  fetch.mockImplementation((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new Error('aborted')));
  }));
  const result = probeHub(hub);
  await vi.advanceTimersByTimeAsync(5000);
  expect(await result).toBe(false);
  expect(vi.getTimerCount()).toBe(0);
});

it('normalizes a default HTTP port and cancels obsolete selections', async () => {
  fetch.mockResolvedValue({ status:401, url:'http://192.168.1.50/api/classrooms' });
  expect(await probeHub({...hub,http_port:80})).toBe(true);
  fetch.mockImplementation((_url,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Error('abort')))));
  const controller=new AbortController(), result=probeHub(hub,controller.signal);
  controller.abort();
  expect(await result).toBe(false);
});
