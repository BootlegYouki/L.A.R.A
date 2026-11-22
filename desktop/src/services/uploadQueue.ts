import { invoke } from '@tauri-apps/api/core';
import type { CachedAccount } from './auth';
import type { MaterialFileType } from './materialUpload';

const MAX_BYTES = 250 * 1024 * 1024;
const CHUNK_BYTES = 64 * 1024;
export type UploadStatus = 'staging' | 'ready' | 'uploading' | 'succeeded' | 'failed' | 'uncertain' | 'cancelled';
export interface UploadJob { id: string; title: string; fileName: string; fileSize: number; status: UploadStatus; uploadedBytes: number; }
export interface UploadInput {
  classroom_id: string; title: string; file_type: MaterialFileType; file: File;
  topic_id?: string; assignment_id?: string; announcement_id?: string;
}

function context(account: CachedAccount, classroomId: string) {
  return { hub: account.hub, userId: account.user.id, classroomId };
}

export async function enqueueUpload(account: CachedAccount, input: UploadInput, signal?: AbortSignal): Promise<UploadJob> {
  if (account.user.role !== 'TEACHER' || !account.classrooms.some(c => c.id === input.classroom_id && c.archived_at == null
    && (c.teacher_id === account.user.id || c.co_teacher_ids?.includes(account.user.id)))) throw new Error('FORBIDDEN');
  if (!(input.file instanceof File) || !input.file.name || input.file.size < 1 || input.file.size > MAX_BYTES
    || !input.title.trim() || (input.assignment_id && input.announcement_id)) throw new Error('invalid_upload');
  if (signal?.aborted) throw new Error('cancelled');
  const args = context(account, input.classroom_id);
  const jobId = await invoke<string>('begin_upload', { ...args, title: input.title.trim(), fileType: input.file_type,
    fileName: input.file.name, size: input.file.size, topicId: input.topic_id ?? null,
    assignmentId: input.assignment_id ?? null, announcementId: input.announcement_id ?? null });
  let ready: UploadJob;
  try {
    const reader = input.file.stream().getReader();
    const cancel = () => { void reader.cancel().catch(() => {}); };
    signal?.addEventListener('abort', cancel, { once: true });
    let pending = new Uint8Array(0);
    try {
      while (true) {
        if (signal?.aborted) throw new Error('cancelled');
        const { value, done } = await reader.read();
        if (signal?.aborted) throw new Error('cancelled');
        const chunk = value ?? new Uint8Array();
        const bytes = done ? pending : new Uint8Array(pending.length + chunk.length);
        if (!done) { bytes.set(pending); bytes.set(chunk, pending.length); }
        let offset = 0;
        while (offset + CHUNK_BYTES <= bytes.length) {
          if (signal?.aborted) throw new Error('cancelled');
          await invoke('append_upload', { hub: account.hub, userId: account.user.id, jobId, data: Array.from(bytes.subarray(offset, offset + CHUNK_BYTES)) });
          offset += CHUNK_BYTES;
        }
        pending = bytes.slice(offset);
        if (done) break;
      }
      if (pending.length) await invoke('append_upload', { hub: account.hub, userId: account.user.id, jobId, data: Array.from(pending) });
      if (signal?.aborted) throw new Error('cancelled');
      ready = await invoke<UploadJob>('finish_upload', { hub: account.hub, userId: account.user.id, jobId });
    } finally {
      signal?.removeEventListener('abort', cancel);
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
  } catch (error) {
    await invoke('cancel_upload', { hub: account.hub, userId: account.user.id, jobId }).catch(() => {});
    throw error;
  }
  await invoke('start_upload', { hub: account.hub, userId: account.user.id, jobId }).catch(() => {});
  return ready;
}

export function listUploadJobs(account: CachedAccount, classroomId: string): Promise<UploadJob[]> {
  return invoke<UploadJob[]>('list_uploads', context(account, classroomId));
}
export function retryUpload(account: CachedAccount, classroomId: string, jobId: string): Promise<void> {
  return invoke('retry_upload', { ...context(account, classroomId), jobId });
}
export function cancelUpload(account: CachedAccount, classroomId: string, jobId: string): Promise<void> {
  return invoke('cancel_upload', { ...context(account, classroomId), jobId });
}
