import { fetch } from '@tauri-apps/plugin-http';
import { isHubInfo, type HubInfo } from './discovery';
import type {SyncState,SyncPush} from './sync';

export class ApiError extends Error {
  constructor(public code: string, public retryAfter = 0) { super(code); }
}

type AccountPath = `/api/announcements/${string}` | `/api/assignments/${string}/private-comments` | `/api/assignments/${string}/submissions` | `/api/submissions/${string}/grade-edit` | '/api/auth/login' | '/api/auth/register' | '/api/auth/logout' | '/api/classrooms' | '/api/classrooms/join' | '/api/sync/pull' | '/api/sync/push'
  | '/api/announcements' | '/api/assignments' | '/api/topics' | '/api/materials' | '/api/quizzes'
  | `/api/assignments/${string}` | `/api/materials/${string}` | `/api/topics/${string}`
  | `/api/classrooms/${string}` | `/api/classrooms/${string}/roster` | `/api/classrooms/${string}/approve` | `/api/classrooms/${string}/reject` | `/api/classrooms/${string}/remove`;
export interface ClassroomChange { name?: string; section?: string; archived?: boolean }
export interface ClassworkMutationBody { content?: string; allow_comments?: boolean; name?: string; order_index?: number; title?: string; instructions?: string; max_points?: number; due_date?: number; allow_late?: boolean; topic_id?: string | null }
export type AuthoringBody = {classroom_id: string; title: string; content: string; allow_comments?: boolean}
  | {classroom_id: string; title: string; instructions?: string; max_points: number; due_date: number; allow_late?: boolean; topic_id?: string}
  | {classroom_id: string; name: string}
  | {classroom_id: string; title: string; instructions?: string; time_limit_minutes: number; shuffle_questions?: boolean; release_scores_immediately?: boolean; topic_id?: string | null; questions: {id: string; order_index: number; question_text: string; question_type: 'MULTIPLE_CHOICE' | 'TRUE_FALSE' | 'IDENTIFICATION'; options: string[]; points: number; image_url?: string; correct_answer: string; synonyms?: string[]}[]};
export async function requestHub(hub: HubInfo, path: AccountPath, options: {
  body?: { lrn_or_id: string; pin: string; full_name?: string } | { class_code: string } | ClassroomChange | { lrn_or_id: string } | { student_id: string } | SyncState | SyncPush | {content:string;student_id:string} | {score:number;teacher_feedback:string;expected_updated_at:number} | ClassworkMutationBody | AuthoringBody; form?: FormData; token?: string; signal?: AbortSignal; method?: 'PATCH' | 'DELETE' | 'PUT';
} = {}): Promise<unknown> {
  if (options.signal?.aborted) throw new ApiError('cancelled');
  if (!isHubInfo(hub)) throw new ApiError('network');
  const classroomId = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
  const itemRoute=new RegExp('^/api/(announcements|assignments|materials|topics)/'+classroomId+'$','i');
  const classRoute = new RegExp('^/api/classrooms/' + classroomId + '$', 'i');
  const teacherRoute = new RegExp('^/api/classrooms/' + classroomId + '/teachers/' + classroomId + '$', 'i');
  const standardRoute = new RegExp('^/api/classrooms/' + classroomId + '/(roster|approve|reject|remove|teachers)$', 'i');
  const createRoute = ['/api/announcements', '/api/assignments', '/api/topics', '/api/quizzes'].includes(path);
  const privateRoute=new RegExp('^/api/assignments/'+classroomId+'/private-comments$','i');
  const submissionsRoute=new RegExp('^/api/assignments/'+classroomId+'/submissions$','i');
  const gradeRoute=new RegExp('^/api/submissions/'+classroomId+'/grade-edit$','i');
  const allowed = options.form ? path === '/api/materials' && !options.body && !options.method
    : path === '/api/materials' ? false
    : privateRoute.test(path) ? !options.method && !!options.body && 'content' in options.body && 'student_id' in options.body
    : submissionsRoute.test(path) ? !options.method && !options.body
    : gradeRoute.test(path) ? options.method==='PATCH' && !!options.body && 'score' in options.body && 'teacher_feedback' in options.body && 'expected_updated_at' in options.body
    : createRoute ? !!options.body && !options.method
    : options.method === 'PATCH' ? classRoute.test(path)||itemRoute.test(path)
    : options.method === 'DELETE' ? teacherRoute.test(path)||itemRoute.test(path)
    : standardRoute.test(path) || ['/api/auth/login', '/api/auth/register', '/api/auth/logout', '/api/classrooms',
      '/api/classrooms/join', '/api/sync/pull', '/api/sync/push'].includes(path);
  if (!allowed) throw new ApiError('invalid_fields');
  const url = new URL(`http://${hub.ip}:${hub.http_port}${path}`).href;
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let abort!: () => void;
  const cancelled = new Promise<never>((_, reject) => {
    abort = () => { controller.abort(); reject(new ApiError(options.signal?.aborted ? 'cancelled' : 'network')); };
    timer = setTimeout(abort, options.form ? 120000 : 10000);
  });
  options.signal?.addEventListener('abort', abort, { once: true });
  try {
    return await Promise.race([cancelled, (async () => {
      const response = await fetch(url, {
        method: options.method ?? (options.body || options.form || path === '/api/auth/logout' ? 'POST' : 'GET'), credentials: 'omit', redirect: 'error', maxRedirections: 0,
        connectTimeout: 10000, signal: controller.signal,
        headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}),
          ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}) },
        ...(options.body ? { body: JSON.stringify(options.body) } : options.form ? { body: options.form } : {}),
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
        throw new ApiError(typeof code === 'string' && ['ADMIN_LOCAL_ONLY', 'ALREADY_EXISTS', 'BAD_REQUEST', 'FORBIDDEN', 'CLASS_CODE_INVALID', 'NOT_IMPLEMENTED', 'NOT_FOUND', 'GRADE_DRAFT_CONFLICT'].includes(code) ? code : 'server');
      }
      return payload;
    })()]);
  } catch (error) { throw error instanceof ApiError ? error : new ApiError('network'); }
  finally { clearTimeout(timer); options.signal?.removeEventListener('abort', abort); }
}

export function uploadMaterialRequest(hub: HubInfo, token: string, form: FormData, signal?: AbortSignal) {
  return requestHub(hub, '/api/materials', {form, token, signal});
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
