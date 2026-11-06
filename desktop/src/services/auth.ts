import { invoke } from '@tauri-apps/api/core';
import { ApiError, requestHub } from './api';
import { isHubInfo, type HubInfo } from './discovery';
import { withDatabaseTimeout } from './db';

export { ApiError as AuthError } from './api';
export interface AuthUser { id: string; lrn_or_id: string; full_name: string; role: 'TEACHER' | 'STUDENT' }
export interface Classroom {
  id: string; name: string; section: string; class_code: string; teacher_id: string;
  co_teacher_ids?: string[]; archived_at?: number | null;
  enrollment_status?: 'ACTIVE' | 'PENDING' | 'REJECTED' | 'REMOVED';
}
export interface JoinReceipt { classroom_id: string; status: 'PENDING' | 'ACTIVE' | 'REJECTED' | 'REMOVED' }
export interface CachedAccount { hub: HubInfo; user: AuthUser; expires_at: number; persistent: boolean; classrooms: Classroom[]; join_requests?: JoinReceipt[] }
export interface Credentials { lrn_or_id: string; pin: string; full_name?: string }
const object = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

function parseUser(value: unknown): AuthUser {
  if (!object(value) || !uuid(value.id) || typeof value.lrn_or_id !== 'string' || !value.lrn_or_id.trim()
    || [...value.lrn_or_id].length > 32 || typeof value.full_name !== 'string' || !value.full_name.trim()
    || [...value.full_name].length > 100) throw new ApiError('invalid_response');
  if (value.role === 'ADMIN') throw new ApiError('ADMIN_LOCAL_ONLY');
  if (value.role !== 'TEACHER' && value.role !== 'STUDENT') throw new ApiError('invalid_response');
  return { id: value.id, lrn_or_id: value.lrn_or_id, full_name: value.full_name, role: value.role };
}
export function parseSession(value: unknown) {
  if (!object(value) || typeof value.token !== 'string' || !value.token || value.token.length > 8192
    || !Number.isSafeInteger(value.expires_at) || Number(value.expires_at) <= Date.now()) throw new ApiError('invalid_response');
  return { token: value.token, expires_at: Number(value.expires_at), user: parseUser(value.user) };
}
function parseClassrooms(value: unknown): Classroom[] {
  if (!Array.isArray(value)) throw new ApiError('invalid_response');
  const ids = new Set<string>();
  return value.map((row: unknown) => {
    if (!object(row) || !uuid(row.id) || ids.has(row.id) || !uuid(row.teacher_id)
      || typeof row.name !== 'string' || !row.name.trim() || typeof row.section !== 'string'
      || typeof row.class_code !== 'string' || !/^[A-HJ-NP-Z2-9]{6}$/.test(row.class_code)
      || (row.co_teacher_ids !== undefined && (!Array.isArray(row.co_teacher_ids) || !row.co_teacher_ids.every(uuid)))
      || (row.archived_at !== undefined && row.archived_at !== null && !Number.isSafeInteger(row.archived_at))
      || (row.enrollment_status !== undefined && !['ACTIVE', 'PENDING', 'REJECTED', 'REMOVED'].includes(String(row.enrollment_status)))) {
      throw new ApiError('invalid_response');
    }
    ids.add(row.id);
    return { id: row.id, name: row.name, section: row.section, class_code: row.class_code, teacher_id: row.teacher_id,
      ...(Array.isArray(row.co_teacher_ids) ? { co_teacher_ids: row.co_teacher_ids.filter(uuid) } : {}),
      ...(row.archived_at === null || typeof row.archived_at === 'number' ? { archived_at: row.archived_at } : {}),
      ...(row.enrollment_status !== undefined ? { enrollment_status: row.enrollment_status as Classroom['enrollment_status'] } : {}) };
  });
}
export function parseJoinReceipt(value: unknown): JoinReceipt {
  if (!object(value) || !uuid(value.classroom_id) || !['PENDING', 'ACTIVE', 'REJECTED', 'REMOVED'].includes(String(value.status))) throw new ApiError('invalid_response');
  return { classroom_id: value.classroom_id, status: value.status as JoinReceipt['status'] };
}
export function parseAccount(value: unknown, hub: HubInfo): CachedAccount {
  if (!object(value) || !isHubInfo(value.hub) || value.hub.ip !== hub.ip || value.hub.http_port !== hub.http_port
    || typeof value.persistent !== 'boolean' || !Number.isSafeInteger(value.expires_at)) throw new ApiError('invalid_response');
  if (value.join_requests !== undefined && (!Array.isArray(value.join_requests) || value.join_requests.length > 10000)) throw new ApiError('invalid_response');
  return { hub, user: parseUser(value.user), expires_at: Number(value.expires_at), persistent: value.persistent, classrooms: parseClassrooms(value.classrooms),
    ...(Array.isArray(value.join_requests) ? { join_requests: value.join_requests.map(parseJoinReceipt) } : {}) };
}
export async function accountToken(account: CachedAccount): Promise<string> {
  const token: unknown = await withDatabaseTimeout(invoke('account_token', { hub: account.hub, userId: account.user.id }));
  if (typeof token !== 'string' || !token) throw new ApiError('reauth_required');
  return token;
}
export async function restoreAccount(hub: HubInfo): Promise<CachedAccount | null> {
  if (!isHubInfo(hub)) return null;
  const value: unknown = await withDatabaseTimeout(invoke('restore_account_session', { hub }));
  return value === null ? null : parseAccount(value, hub);
}
export async function signIn(hub: HubInfo, input: Credentials, register: boolean, signal?: AbortSignal): Promise<CachedAccount> {
  const fields = { lrn_or_id: input.lrn_or_id.trim(), pin: input.pin,
    ...(register ? { full_name: input.full_name?.trim() } : {}) };
  if (!fields.lrn_or_id || [...fields.lrn_or_id].length > 32 || !/^[0-9]{4}$/.test(fields.pin)
    || (register && (!/^[0-9]{12}$/.test(fields.lrn_or_id) || !fields.full_name || [...fields.full_name].length > 100))) {
    throw new ApiError('invalid_fields');
  }
  if (fields.lrn_or_id === 'ADMIN-0001') throw new ApiError('ADMIN_LOCAL_ONLY');
  const session = parseSession(await requestHub(hub, register ? '/api/auth/register' : '/api/auth/login', { body: fields, signal }));
  if (signal?.aborted) throw new ApiError('cancelled');
  return parseAccount(await withDatabaseTimeout(invoke('save_account_session', { hub, session })), hub);
}
export async function refreshClassrooms(account: CachedAccount, signal?: AbortSignal): Promise<Classroom[]> {
  const { hub, user } = account;
  const token = await accountToken(account);
  let classrooms: Classroom[];
  try { classrooms = parseClassrooms(await requestHub(hub, '/api/classrooms', { token, signal })); }
  catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') {
      // A credential-service failure must not turn a confirmed 401 into a generic cache error.
      await forgetAccount(account).catch(() => {});
    }
    throw error;
  }
  if (signal?.aborted) throw new ApiError('cancelled');
  return parseClassrooms(await withDatabaseTimeout(invoke('save_classroom_snapshot', { hub, userId: user.id, classrooms })));
}
export async function forgetAccount(account: CachedAccount) {
  await withDatabaseTimeout(invoke('forget_account_session', { hub: account.hub, userId: account.user.id }));
}
