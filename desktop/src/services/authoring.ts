import { ApiError, requestHub, type AuthoringBody } from './api';
import { accountToken, forgetAccount, hasControlCharacters, type CachedAccount } from './auth';

const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
function teacherClass(account: CachedAccount, classroomId: string) {
  const room = account.classrooms.find(c => c.id === classroomId && (c.teacher_id === account.user.id || c.co_teacher_ids?.includes(account.user.id)));
  if (account.user.role !== 'TEACHER' || !uuid(classroomId) || !room) throw new ApiError('FORBIDDEN');
  if (room.archived_at != null) throw new ApiError('FORBIDDEN');
}
function requiredText(value: string) {
  if (typeof value !== 'string' || !value.trim() || hasControlCharacters(value)) throw new ApiError('invalid_fields');
  return value.trim();
}
async function create(account: CachedAccount, path: '/api/announcements' | '/api/assignments' | '/api/topics', body: AuthoringBody, signal?: AbortSignal) {
  try {
    const result = await requestHub(account.hub, path, {body, token: await accountToken(account), signal});
    if (typeof result !== 'object' || result === null || !('id' in result) || typeof result.id !== 'string' || !uuid(result.id)
      || !('classroom_id' in result) || result.classroom_id !== body.classroom_id) throw new ApiError('invalid_response');
    return result.id;
  } catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
}

export async function createAnnouncement(account: CachedAccount, classroomId: string, input: {title: string; content: string; allow_comments?: boolean}, signal?: AbortSignal) {
  teacherClass(account, classroomId);
  if (typeof input.content !== 'string' || hasControlCharacters(input.content.replace(/[\r\n\t]/g, ''))
    || (input.allow_comments !== undefined && typeof input.allow_comments !== 'boolean')) throw new ApiError('invalid_fields');
  return create(account, '/api/announcements', {classroom_id: classroomId, title: requiredText(input.title), content: input.content,
    ...(input.allow_comments === undefined ? {} : {allow_comments: input.allow_comments})}, signal);
}

export async function createAssignment(account: CachedAccount, classroomId: string, input: {title: string; instructions?: string; max_points: number; due_date: number; allow_late?: boolean; topic_id?: string}, signal?: AbortSignal) {
  teacherClass(account, classroomId);
  if (!Number.isSafeInteger(input.max_points) || input.max_points <= 0 || !Number.isSafeInteger(input.due_date) || input.due_date < 0
    || (input.instructions !== undefined && (typeof input.instructions !== 'string' || hasControlCharacters(input.instructions.replace(/[\r\n\t]/g, ''))))
    || (input.allow_late !== undefined && typeof input.allow_late !== 'boolean')
    || (input.topic_id !== undefined && !uuid(input.topic_id))) throw new ApiError('invalid_fields');
  return create(account, '/api/assignments', {classroom_id: classroomId, title: requiredText(input.title), max_points: input.max_points, due_date: input.due_date,
    ...(input.instructions === undefined ? {} : {instructions: input.instructions}), ...(input.allow_late === undefined ? {} : {allow_late: input.allow_late}),
    ...(input.topic_id === undefined ? {} : {topic_id: input.topic_id})}, signal);
}

export async function createTopic(account: CachedAccount, classroomId: string, name: string, signal?: AbortSignal) {
  teacherClass(account, classroomId);
  return create(account, '/api/topics', {classroom_id: classroomId, name: requiredText(name)}, signal);
}
