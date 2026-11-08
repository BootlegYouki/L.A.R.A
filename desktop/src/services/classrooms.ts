import { invoke } from '@tauri-apps/api/core';
import { ApiError, requestHub, type ClassroomChange } from './api';
import { accountToken, forgetAccount, parseAccount, parseClassrooms, parseRoster, parseJoinReceipt, hasControlCharacters, type CachedAccount, type JoinReceipt, type RosterEntry } from './auth';
import { withDatabaseTimeout } from './db';

export function normalizeClassCode(value: string): string | null {
  const code = value.replace(/[\s-]/g, '').toUpperCase();
  return /^[A-HJ-NP-Z2-9]{6}$/.test(code) ? code : null;
}
export async function saveJoinStatus(account: CachedAccount, receipt: JoinReceipt): Promise<CachedAccount> {
  return parseAccount(await withDatabaseTimeout(invoke('save_join_status', { hub: account.hub, userId: account.user.id, receipt: parseJoinReceipt(receipt) })), account.hub);
}
export async function joinClass(account: CachedAccount, input: string, signal?: AbortSignal): Promise<CachedAccount> {
  if (account.user.role !== 'STUDENT') throw new ApiError('FORBIDDEN');
  const class_code = normalizeClassCode(input);
  if (!class_code) throw new ApiError('invalid_code');
  const token = await accountToken(account);
  let receipt: JoinReceipt;
  try { receipt = parseJoinReceipt(await requestHub(account.hub, '/api/classrooms/join', { body: { class_code }, token, signal })); }
  catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
  // A successful POST must be remembered even when the user closes its dialog.
  return saveJoinStatus(account, receipt);
}
const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
function teacherClass(account: CachedAccount, id: string) {
  const classroom = account.classrooms.find(c => c.id === id && (c.teacher_id === account.user.id || c.co_teacher_ids?.includes(account.user.id)));
  if (account.user.role !== 'TEACHER' || !uuid(id) || !classroom) throw new ApiError('FORBIDDEN');
  return classroom;
}
async function teacherRequest(account: CachedAccount, path: Parameters<typeof requestHub>[1], body?: NonNullable<Parameters<typeof requestHub>[2]>['body'], signal?: AbortSignal, method?: 'PATCH' | 'DELETE') {
  if (account.user.role !== 'TEACHER') throw new ApiError('FORBIDDEN');
  const token = await accountToken(account);
  try { return await requestHub(account.hub, path, {body, token, signal, method}); }
  catch (error) { if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {}); throw error; }
}
export async function createClassroom(account: CachedAccount, name: string, section: string, signal?: AbortSignal) {
  if (!name.trim() || hasControlCharacters(name + section)) throw new ApiError('invalid_fields');
  const classroom = parseClassrooms([await teacherRequest(account, '/api/classrooms', {name: name.trim(), section: section.trim()}, signal)])[0];
  if (classroom.teacher_id !== account.user.id) throw new ApiError('invalid_response');
  try {
    const classrooms = parseClassrooms(await withDatabaseTimeout(invoke('save_classroom_snapshot', {
      hub: account.hub, userId: account.user.id, classrooms: [...account.classrooms.filter(c => c.id !== classroom.id), classroom],
    })));
    return {classroom, classrooms, cacheSaved: true};
  } catch {
    // The Hub already created it: keep its receipt instead of offering another POST.
    return {classroom, classrooms: account.classrooms, cacheSaved: false};
  }
}
export async function refreshRoster(account: CachedAccount, classroomId: string, signal?: AbortSignal): Promise<RosterEntry[]> {
  teacherClass(account, classroomId);
  let roster: RosterEntry[];
  try { roster = parseRoster(await teacherRequest(account, `/api/classrooms/${classroomId}/roster`, undefined, signal)); }
  catch (error) {
    if (error instanceof ApiError && error.code === 'FORBIDDEN') {
      await withDatabaseTimeout(invoke('clear_roster_snapshot', {hub: account.hub, userId: account.user.id, classroomId})).catch(() => {});
    }
    throw error;
  }
  if (signal?.aborted) throw new ApiError('cancelled');
  return parseRoster(await withDatabaseTimeout(invoke('save_roster_snapshot', {hub: account.hub, userId: account.user.id, classroomId, roster})));
}
export async function decideEnrollment(account: CachedAccount, classroomId: string, studentId: string, action: 'approve'|'reject'|'remove', signal?: AbortSignal): Promise<void> {
  const classroom = teacherClass(account, classroomId);
  if (classroom.archived_at != null || !uuid(studentId) || !['approve','reject','remove'].includes(action)) throw new ApiError('FORBIDDEN');
  const receipt = await teacherRequest(account, `/api/classrooms/${classroomId}/${action}`, {student_id: studentId}, signal);
  if (typeof receipt !== 'object' || receipt === null || !('success' in receipt) || receipt.success !== true) throw new ApiError('invalid_response');
}

export type ClassSettingsAction =
  | { kind: 'settings'; change: ClassroomChange }
  | { kind: 'add'; teacherId: string }
  | { kind: 'remove'; teacherId: string };

export async function changeClassroom(account: CachedAccount, id: string, action: ClassSettingsAction, signal: AbortSignal) {
  const room = teacherClass(account, id);
  if (room.teacher_id !== account.user.id || room.archived_at != null) throw new ApiError('FORBIDDEN');
  const path = `/api/classrooms/${id}` as const;
  if (action.kind === 'remove') {
    if (!uuid(action.teacherId) || !room.co_teacher_ids?.includes(action.teacherId)) throw new ApiError('invalid_fields');
    const receipt = await teacherRequest(account, `${path}/teachers/${action.teacherId}`, undefined, signal, 'DELETE');
    if (typeof receipt !== 'object' || receipt === null || !('success' in receipt) || receipt.success !== true) throw new ApiError('invalid_response');
    return null;
  }
  let payload: unknown;
  if (action.kind === 'add') {
    const lrn_or_id = action.teacherId.trim();
    if (!lrn_or_id || [...lrn_or_id].length > 32 || hasControlCharacters(lrn_or_id)) throw new ApiError('invalid_fields');
    payload = await teacherRequest(account, `${path}/teachers`, {lrn_or_id}, signal);
  } else {
    const {name, section} = action.change;
    if (name !== undefined && !name.trim() || hasControlCharacters((name ?? '') + (section ?? ''))) throw new ApiError('invalid_fields');
    payload = await teacherRequest(account, path, action.change, signal, 'PATCH');
  }
  const updated = parseClassrooms([payload])[0];
  if (updated.id !== id || updated.teacher_id !== account.user.id) throw new ApiError('invalid_response');
  return updated;
}

export function classroomTeacherNames(account: CachedAccount, classroomId: string) {
  return withDatabaseTimeout(invoke<Record<string, string>>('classroom_teacher_names', {
    hub: account.hub, userId: account.user.id, classroomId,
  }));
}
