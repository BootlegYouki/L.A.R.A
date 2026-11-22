import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../src/services/api';
import { uploadMaterial, validateMaterialUpload } from '../src/services/materialUpload';
import type { CachedAccount } from '../src/services/auth';

const mocks = vi.hoisted(() => ({ token: vi.fn(), request: vi.fn(), forget: vi.fn() }));
vi.mock('../src/services/api', async original => ({ ...await original<typeof import('../src/services/api')>(), uploadMaterialRequest: mocks.request }));
vi.mock('../src/services/auth', async original => ({ ...await original<typeof import('../src/services/auth')>(), accountToken: mocks.token, forgetAccount: mocks.forget }));

const userId = '11111111-1111-4111-8111-111111111111';
const classroomId = '22222222-2222-4222-8222-222222222222';
const account = (role: 'TEACHER' | 'STUDENT' = 'TEACHER', archived_at: number | null = null) => ({
  hub: { name: 'Hub', ip: '192.168.1.2', http_port: 8080, ws_port: 8081 },
  user: { id: userId, role, lrn_or_id: userId, full_name: 'User' }, expires_at: 1, persistent: true,
  classrooms: [{ id: classroomId, teacher_id: userId, name: 'Class', section: '', class_code: 'K7M4QX', archived_at }],
}) as CachedAccount;
const input = (file = new File(['lesson'], 'lesson.pdf', { type: 'application/pdf' })) => ({ classroom_id: classroomId, title: ' Lesson ', file_type: 'DOCUMENT' as const, file });

beforeEach(() => { vi.clearAllMocks(); mocks.token.mockResolvedValue('token'); });

describe('material upload', () => {
  it('rejects invalid metadata and empty or oversized files', () => {
    expect(() => validateMaterialUpload({ ...input(), title: '  ' })).toThrow(ApiError);
    expect(() => validateMaterialUpload(input(new File([], 'empty.pdf')))).toThrow(ApiError);
    const oversized = new File(['x'], 'large.bin');
    Object.defineProperty(oversized, 'size', { value: 250 * 1024 * 1024 + 1 });
    expect(() => validateMaterialUpload(input(oversized))).toThrow(ApiError);
  });

  it('blocks student, archived, and offline uploads before network access', async () => {
    await expect(uploadMaterial(account('STUDENT'), input(), true)).rejects.toThrow('FORBIDDEN');
    await expect(uploadMaterial(account('TEACHER', 1), input(), true)).rejects.toThrow('FORBIDDEN');
    await expect(uploadMaterial(account(), input(), false)).rejects.toThrow('offline');
    expect(mocks.request).not.toHaveBeenCalled();
    expect(mocks.token).not.toHaveBeenCalled();
  });

  it('sends one multipart request and validates the Hub receipt', async () => {
    const payload = { id: '33333333-3333-4333-8333-333333333333', classroom_id: classroomId, title: 'Lesson', file_type: 'DOCUMENT', file_size_bytes: 6, updated_at: 10 };
    mocks.request.mockResolvedValue(payload);
    await expect(uploadMaterial(account(), input(), true)).resolves.toEqual(payload);
    expect(mocks.request).toHaveBeenCalledOnce();
    const form = mocks.request.mock.calls[0][2] as FormData;
    expect(form.get('classroom_id')).toBe(classroomId);
    expect(form.get('title')).toBe('Lesson');
    expect(form.get('file_type')).toBe('DOCUMENT');
    expect(form.get('file')).toBeInstanceOf(File);
    mocks.request.mockResolvedValue({ ...payload, classroom_id: userId });
    await expect(uploadMaterial(account(), input(), true)).rejects.toThrow('invalid_response');
  });
});
