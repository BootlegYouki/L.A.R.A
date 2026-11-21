import { ApiError, uploadMaterialRequest } from './api';
import { accountToken, forgetAccount, hasControlCharacters, type CachedAccount } from './auth';

export type MaterialFileType = 'VIDEO' | 'DOCUMENT' | 'WORKSHEET';
export interface CreatedMaterial { id: string; classroom_id: string; title: string; file_type: MaterialFileType; file_size_bytes: number; updated_at: number }
export interface MaterialUpload { classroom_id: string; title: string; file_type: MaterialFileType; file: File; topic_id?: string }
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;
const MAX_FILE_SIZE = 250 * 1024 * 1024;

export function validateMaterialUpload(input: MaterialUpload): void {
  if (!uuid(input.classroom_id) || !input.title.trim() || hasControlCharacters(input.title)
    || (input.topic_id!==undefined&&!uuid(input.topic_id))
    || !['VIDEO', 'DOCUMENT', 'WORKSHEET'].includes(input.file_type)) throw new ApiError('invalid_fields');
  if (!(input.file instanceof File) || !input.file.name || input.file.size <= 0 || input.file.size > MAX_FILE_SIZE) throw new ApiError('invalid_file');
}

function parseCreatedMaterial(value: unknown, expected: MaterialUpload): CreatedMaterial {
  if (!isObject(value) || !uuid(value.id) || value.classroom_id !== expected.classroom_id || value.title !== expected.title.trim()
    || value.file_type !== expected.file_type || !Number.isSafeInteger(value.file_size_bytes) || Number(value.file_size_bytes) !== expected.file.size
    || !Number.isSafeInteger(value.updated_at) || Number(value.updated_at) < 0) throw new ApiError('invalid_response');
  return { id: value.id, classroom_id: value.classroom_id as string, title: value.title as string, file_type: value.file_type as MaterialFileType,
    file_size_bytes: Number(value.file_size_bytes), updated_at: Number(value.updated_at) };
}

export async function uploadMaterial(account: CachedAccount, input: MaterialUpload, online: boolean, signal?: AbortSignal): Promise<CreatedMaterial> {
  if (account.user.role !== 'TEACHER') throw new ApiError('FORBIDDEN');
  const classroom = account.classrooms.find(row => row.id === input.classroom_id
    && (row.teacher_id === account.user.id || row.co_teacher_ids?.includes(account.user.id)));
  if (!classroom || classroom.archived_at != null) throw new ApiError('FORBIDDEN');
  validateMaterialUpload(input);
  if (!online) throw new ApiError('offline');
  const token = await accountToken(account);
  const form = new FormData();
  form.set('classroom_id', input.classroom_id);
  form.set('title', input.title.trim());
  form.set('file_type', input.file_type);
  if(input.topic_id)form.set('topic_id',input.topic_id);
  form.set('file', input.file, input.file.name);
  try { return parseCreatedMaterial(await uploadMaterialRequest(account.hub, token, form, signal), input); }
  catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
}
