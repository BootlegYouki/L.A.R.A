import { fetch } from '@tauri-apps/plugin-http';
import { isHubInfo, type HubInfo } from './discovery';
import type {SyncState,SyncPush} from './sync';

export class ApiError extends Error {
  constructor(public code: string, public retryAfter = 0) { super(code); }
}

type AccountPath = '/api/auth/login' | '/api/auth/register' | '/api/auth/logout' | '/api/classrooms' | '/api/classrooms/join' | '/api/sync/pull' | '/api/sync/push'
  | `/api/classrooms/${string}` | `/api/classrooms/${string}/roster` | `/api/classrooms/${string}/approve` | `/api/classrooms/${string}/reject` | `/api/classrooms/${string}/remove`;
export interface ClassroomChange { name?: string; section?: string; archived?: boolean }
export async function requestHub(hub: HubInfo, path: AccountPath, options: {
  body?: { lrn_or_id: string; pin: string; full_name?: string } | { class_code: string } | ClassroomChange | { lrn_or_id: string } | { student_id: string } | SyncState | SyncPush; token?: string; signal?: AbortSignal; method?: 'PATCH' | 'DELETE';
} = {}): Promise<unknown> {
  if (options.signal?.aborted) throw new ApiError('cancelled');
  if (!isHubInfo(hub)) throw new ApiError('network');
  const classroomId = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
  const classRoute = new RegExp('^/api/classrooms/' + classroomId + '$', 'i');
  const teacherRoute = new RegExp('^/api/classrooms/' + classroomId + '/teachers/' + classroomId + '$', 'i');
  const standardRoute = new RegExp('^/api/classrooms/' + classroomId + '/(roster|approve|reject|remove|teachers)$', 'i');
  const allowed = options.method === 'PATCH' ? classRoute.test(path)
    : options.method === 'DELETE' ? teacherRoute.test(path)
    : standardRoute.test(path) || ['/api/auth/login', '/api/auth/register', '/api/auth/logout', '/api/classrooms',
      '/api/classrooms/join', '/api/sync/pull', '/api/sync/push'].includes(path);
  if (!allowed) throw new ApiError('invalid_fields');
  const url = new URL(`http://${hub.ip}:${hub.http_port}${path}`).href;
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let abort!: () => void;
  const cancelled = new Promise<never>((_, reject) => {
    abort = () => { controller.abort(); reject(new ApiError(options.signal?.aborted ? 'cancelled' : 'network')); };
    timer = setTimeout(abort, 10000);
  });
  options.signal?.addEventListener('abort', abort, { once: true });
  try {
    return await Promise.race([cancelled, (async () => {
      const response = await fetch(url, {
        method: options.method ?? (options.body || path === '/api/auth/logout' ? 'POST' : 'GET'), credentials: 'omit', redirect: 'error', maxRedirections: 0,
        connectTimeout: 10000, signal: controller.signal,
        headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}),
          ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
        ...(options.body ? { body: JSON.stringify(options.body) } : {}),
      });
      if (response.url !== url || (response.status >= 300 && response.status < 400)) {
        void response.body?.cancel().catch(() => {});
        throw new ApiError('invalid_response');
      }
      if (response.status === 429) {
        void response.body?.cancel().catch(() => {});
        const value = Number(response.headers.get('Retry-After'));
        throw new ApiError('TOO_MANY_ATTEMPTS', Number.isSafeInteger(value) && value > 0 ? value : 60);
      }
      if (response.status === 401) { void response.body?.cancel().catch(() => {}); throw new ApiError('UNAUTHORIZED'); }
      let payload: unknown;
      try { payload = await response.json(); } catch { throw new ApiError('invalid_response'); }
      if (response.status !== 200) {
        const error = typeof payload === 'object' && payload !== null && 'error' in payload ? payload.error : null;
        const code = typeof error === 'object' && error !== null && 'code' in error ? error.code : null;
        throw new ApiError(typeof code === 'string' && ['ADMIN_LOCAL_ONLY', 'ALREADY_EXISTS', 'BAD_REQUEST', 'FORBIDDEN', 'CLASS_CODE_INVALID', 'NOT_IMPLEMENTED', 'NOT_FOUND'].includes(code) ? code : 'server');
      }
      return payload;
    })()]);
  } catch (error) { throw error instanceof ApiError ? error : new ApiError('network'); }
  finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
}

// Reachability only: no credentials, classroom data or sign-in state is accepted here.
export async function probeHub(hub: HubInfo, signal?: AbortSignal): Promise<boolean> {
  if (!isHubInfo(hub) || signal?.aborted) return false;
  const url = new URL(`http://${hub.ip}:${hub.http_port}/api/classrooms`).href;
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let cancel!: () => void;
  const cancelled = new Promise<false>((resolve) => {
    cancel = () => { controller.abort(); resolve(false); };
    timer = setTimeout(cancel, 5000);
  });
  signal?.addEventListener('abort', cancel, { once: true });
  try {
    return await Promise.race([cancelled, fetch(url, {
      method: 'GET', credentials: 'omit', redirect: 'error', maxRedirections: 0,
      connectTimeout: 5000, signal: controller.signal,
    }).then((response) => {
      void response.body?.cancel().catch(() => {});
      return response.url === url && (response.status === 200 || response.status === 401);
    }).catch(() => false)]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
}
