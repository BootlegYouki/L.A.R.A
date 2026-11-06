import { afterEach, expect, it, vi } from 'vitest';
const { fetch, invoke } = vi.hoisted(() => ({ fetch: vi.fn(), invoke: vi.fn() }));
vi.mock('@tauri-apps/plugin-http', () => ({ fetch }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
import { signIn, refreshClassrooms, restoreAccount, AuthError, parseSession } from '../src/services/auth';

const hub = { name: 'Hub', ip: '192.168.1.50', http_port: 8080, ws_port: 8081 };
const user = { id: '10000000-0000-4000-8000-000000000001', lrn_or_id: 'T-0001', full_name: 'Teacher', role: 'TEACHER' as const };
const account = { hub, user, expires_at: Date.now() + 60000, persistent: true, classrooms: [] };
const response = (status: number, data: unknown, path = '/api/auth/login', retry?: string) => ({
  status, url: `http://192.168.1.50:8080${path}`, headers: new Headers(retry ? { 'Retry-After': retry } : {}), json: async () => data,
});
afterEach(() => { vi.clearAllMocks(); vi.useRealTimers(); });

it('stores a validated session natively without persisting the PIN or arbitrary server fields', async () => {
  fetch.mockResolvedValue(response(200, { token: 'test-token', expires_at: account.expires_at, user: { ...user, pin_hash: 'must-not-persist' } }));
  invoke.mockResolvedValue(account);
  await expect(signIn(hub, { lrn_or_id: ' T-0001 ', pin: '1234' }, false)).resolves.toEqual(account);
  expect(invoke).toHaveBeenCalledWith('save_account_session', { hub, session: { token: 'test-token', expires_at: account.expires_at, user } });
  expect(fetch).toHaveBeenCalledWith('http://192.168.1.50:8080/api/auth/login', expect.objectContaining({
    body: JSON.stringify({ lrn_or_id: 'T-0001', pin: '1234' }), redirect: 'error', maxRedirections: 0, credentials: 'omit',
  }));
});

it('rejects malformed/admin sessions and invalid local input before saving anything', async () => {
  expect(() => parseSession({ token: 'x', expires_at: account.expires_at, user: { ...user, role: 'ADMIN' } })).toThrow(AuthError);
  await expect(signIn(hub, { lrn_or_id: 'x', pin: '12' }, false)).rejects.toMatchObject({ code: 'invalid_fields' });
  await expect(signIn(hub, { lrn_or_id: 'ADMIN-0001', pin: '1234' }, false)).rejects.toMatchObject({ code: 'ADMIN_LOCAL_ONLY' });
  await expect(signIn({ ...hub, ip: '8.8.8.8' }, { lrn_or_id: 'T-0001', pin: '1234' }, false)).rejects.toMatchObject({ code: 'network' });
  expect(fetch).not.toHaveBeenCalled(); expect(invoke).not.toHaveBeenCalled();
});

it('honors login rate limits without an automatic retry', async () => {
  fetch.mockResolvedValue(response(429, { error: { code: 'TOO_MANY_ATTEMPTS', message: 'ignored' } }, undefined, '42'));
  await expect(signIn(hub, { lrn_or_id: 'T-0001', pin: '1234' }, false)).rejects.toMatchObject({ code: 'TOO_MANY_ATTEMPTS', retryAfter: 42 });
  expect(fetch).toHaveBeenCalledTimes(1); expect(invoke).not.toHaveBeenCalled();
});

it('treats 401 as expired authentication, not as an unreachable Hub', async () => {
  invoke.mockResolvedValueOnce('test-token').mockResolvedValueOnce(undefined);
  fetch.mockResolvedValue(response(401, { error: { code: 'UNAUTHORIZED' } }, '/api/classrooms'));
  await expect(refreshClassrooms(account)).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
  expect(invoke).toHaveBeenLastCalledWith('forget_account_session', { hub, userId: user.id });
});

it('never stores a redirected or malformed classroom response', async () => {
  invoke.mockResolvedValue('test-token');
  fetch.mockResolvedValue({ ...response(200, [], '/api/classrooms'), url: 'http://8.8.8.8/api/classrooms' });
  await expect(refreshClassrooms(account)).rejects.toMatchObject({ code: 'invalid_response' });
  expect(invoke).not.toHaveBeenCalledWith('save_classroom_snapshot', expect.anything());
});

it('still requires sign-in after 401 when the credential service cannot delete a token', async () => {
  invoke.mockResolvedValueOnce('test-token').mockRejectedValueOnce(new Error('credential service unavailable'));
  fetch.mockResolvedValue(response(401, { error: { code: 'UNAUTHORIZED' } }, '/api/classrooms'));
  await expect(refreshClassrooms(account)).rejects.toMatchObject({ code: 'UNAUTHORIZED' });
});

it('bounds a stalled native account restore and permits a fresh retry', async () => {
  vi.useFakeTimers(); invoke.mockReturnValueOnce(new Promise(() => {})).mockResolvedValueOnce(account);
  const failure = expect(restoreAccount(hub)).rejects.toThrow('Local database did not respond');
  await vi.advanceTimersByTimeAsync(15000); await failure;
  await expect(restoreAccount(hub)).resolves.toEqual(account);
});

it('bounds stalled metadata calls and respects cancellation before persistence', async () => {
  vi.useFakeTimers(); fetch.mockReturnValue(new Promise(() => {}));
  const failure = expect(signIn(hub, { lrn_or_id: 'T-0001', pin: '1234' }, false)).rejects.toMatchObject({ code: 'network' });
  await vi.advanceTimersByTimeAsync(10000); await failure;
  const controller = new AbortController(); controller.abort();
  await expect(signIn(hub, { lrn_or_id: 'T-0001', pin: '1234' }, false, controller.signal)).rejects.toMatchObject({ code: 'cancelled' });
  expect(invoke).not.toHaveBeenCalled();
});
