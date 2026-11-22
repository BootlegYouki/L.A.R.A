import { ApiError, requestHub, type ClassworkMutationBody } from './api';
import { accountToken, forgetAccount, hasControlCharacters, type CachedAccount } from './auth';

type Kind = 'announcement' | 'assignment' | 'material' | 'topic';
export type AnnouncementMutation = {title:string;content:string;allow_comments:boolean};
export type AssignmentMutation = {title: string; instructions: string; max_points: number; due_date: number; allow_late: boolean; topic_id: string | null};
export type MaterialMutation = {title: string; topic_id: string | null};
export type TopicMutation = {name: string; order_index: number};
const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);

function validateClass(account: CachedAccount, classroomId: string, id: string) {
  const room = account.classrooms.find(item => item.id === classroomId && (item.teacher_id === account.user.id || item.co_teacher_ids?.includes(account.user.id)));
  if (account.user.role !== 'TEACHER' || !uuid(classroomId) || !uuid(id) || !room || room.archived_at != null) throw new ApiError('FORBIDDEN');
}
function text(value: string, multiline = false) {
  if (typeof value !== 'string' || !value.trim() || hasControlCharacters(multiline ? value.replace(/[\r\n\t]/g, '') : value)) throw new ApiError('invalid_fields');
  return value.trim();
}

export function updateClasswork(account: CachedAccount, classroomId: string, kind: 'announcement', id: string, input: AnnouncementMutation, signal?: AbortSignal): Promise<string>;
export function updateClasswork(account: CachedAccount, classroomId: string, kind: 'assignment', id: string, input: AssignmentMutation, signal?: AbortSignal): Promise<string>;
export function updateClasswork(account: CachedAccount, classroomId: string, kind: 'material', id: string, input: MaterialMutation, signal?: AbortSignal): Promise<string>;
export function updateClasswork(account: CachedAccount, classroomId: string, kind: 'topic', id: string, input: TopicMutation, signal?: AbortSignal): Promise<string>;
export async function updateClasswork(account: CachedAccount, classroomId: string, kind: Kind, id: string,
  input: AnnouncementMutation | AssignmentMutation | MaterialMutation | TopicMutation, signal?: AbortSignal): Promise<string> {
  validateClass(account, classroomId, id);
  let body: ClassworkMutationBody;
  if(kind==='announcement' && 'content' in input) {
    if(typeof input.allow_comments!=='boolean'||[...input.content].length>10000)throw new ApiError('invalid_fields');
    body={title:text(input.title),content:text(input.content,true),allow_comments:input.allow_comments};
  } else if (kind === 'assignment' && 'max_points' in input) {
    if (!Number.isSafeInteger(input.max_points) || input.max_points < 1 || !Number.isSafeInteger(input.due_date) || input.due_date < 0
      || typeof input.allow_late !== 'boolean' || (input.topic_id !== null && !uuid(input.topic_id))) throw new ApiError('invalid_fields');
    if (typeof input.instructions !== 'string' || hasControlCharacters(input.instructions.replace(/[\r\n\t]/g, ''))) throw new ApiError('invalid_fields');
    body = {title: text(input.title), instructions: input.instructions.trim(), max_points: input.max_points,
      due_date: input.due_date, allow_late: input.allow_late, topic_id: input.topic_id};
  } else if (kind === 'material' && 'topic_id' in input) {
    if (input.topic_id !== null && !uuid(input.topic_id)) throw new ApiError('invalid_fields');
    body = {title: text(input.title), topic_id: input.topic_id};
  } else if (kind === 'topic' && 'order_index' in input) {
    if (!Number.isSafeInteger(input.order_index) || input.order_index < 0) throw new ApiError('invalid_fields');
    body = {name: text(input.name), order_index: input.order_index};
  } else throw new ApiError('invalid_fields');

  const path: `/api/announcements/${string}` | `/api/assignments/${string}` | `/api/materials/${string}` | `/api/topics/${string}` = kind==='announcement'? `/api/announcements/${id}` : kind === 'assignment' ? `/api/assignments/${id}` : kind === 'material' ? `/api/materials/${id}` : `/api/topics/${id}`;
  try {
    const result = await requestHub(account.hub, path, {body, method: 'PATCH', token: await accountToken(account), signal});
    if (typeof result !== 'object' || result === null || !('id' in result) || result.id !== id
      || !('classroom_id' in result) || result.classroom_id !== classroomId) throw new ApiError('invalid_response');
    return id;
  } catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
}

export async function deleteClasswork(account: CachedAccount, classroomId: string, kind: Kind, id: string, signal?: AbortSignal) {
  validateClass(account, classroomId, id);
  try {
    const path: `/api/announcements/${string}` | `/api/assignments/${string}` | `/api/materials/${string}` | `/api/topics/${string}` = kind==='announcement'? `/api/announcements/${id}` : kind === 'assignment' ? `/api/assignments/${id}` : kind === 'material' ? `/api/materials/${id}` : `/api/topics/${id}`;
    const result = await requestHub(account.hub, path, {method: 'DELETE', token: await accountToken(account), signal});
    if (typeof result !== 'object' || result === null || !('success' in result) || result.success !== true) throw new ApiError('invalid_response');
  } catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
}
