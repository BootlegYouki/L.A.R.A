import { beforeEach, expect, it, vi } from 'vitest';
import type { CachedAccount } from '../src/services/auth';
const { requestHub, accountToken } = vi.hoisted(() => ({ requestHub: vi.fn(), accountToken: vi.fn() }));
vi.mock('../src/services/api', async importOriginal => ({ ...await importOriginal<typeof import('../src/services/api')>(), requestHub }));
vi.mock('../src/services/auth', async importOriginal => ({ ...await importOriginal<typeof import('../src/services/auth')>(), accountToken }));
import { createQuiz } from '../src/services/quizAuthoring';

const teacherId = '11111111-1111-4111-8111-111111111111';
const roomId = '22222222-2222-4222-8222-222222222222';
const quizId = '33333333-3333-4333-8333-333333333333';
const questionId = '44444444-4444-4444-8444-444444444444';
const serverQuestionId = '55555555-5555-4555-8555-555555555555';
const account: CachedAccount = {
  hub: { name: 'Hub', ip: '192.168.1.2', http_port: 8080, ws_port: 8081 },
  user: { id: teacherId, lrn_or_id: 'T-1', full_name: 'Teacher', role: 'TEACHER' },
  expires_at: 9999999999999, persistent: true,
  classrooms: [{ id: roomId, name: 'Class', section: 'A', class_code: 'K7M4QX', teacher_id: teacherId }],
};
const question = { id: questionId, order_index: 0, question_text: '2 + 2?', question_type: 'MULTIPLE_CHOICE' as const,
  options: ['3', '4', '5', '6'], points: 2, correct_answer: '4' };
const input = { title: 'Quiz', time_limit_minutes: 10, questions: [question] };
beforeEach(() => {
  vi.clearAllMocks();
  accountToken.mockResolvedValue('token');
  requestHub.mockResolvedValue({ id: quizId, classroom_id: roomId, title: 'Quiz', time_limit_minutes: 10, status: 'DRAFT', questions: [question] });
});

it('creates a draft and returns no answer key in the receipt', async () => {
  await expect(createQuiz(account, roomId, input)).resolves.toEqual({ id: quizId, status: 'DRAFT' });
  expect(requestHub).toHaveBeenCalledWith(account.hub, '/api/quizzes', {
    body: { classroom_id: roomId, title: 'Quiz', time_limit_minutes: 10, questions: [question] }, token: 'token',
  });
});

it('accepts a server-generated question UUID in the created receipt', async () => {
  requestHub.mockResolvedValue({ id: quizId, classroom_id: roomId, title: 'Quiz', time_limit_minutes: 10, status: 'DRAFT', questions: [{ ...question, id: serverQuestionId }] });
  await expect(createQuiz(account, roomId, input)).resolves.toEqual({ id: quizId, status: 'DRAFT' });
});

it('allows a listed co-teacher to create a quiz', async () => {
  const coTeacherId = '66666666-6666-4666-8666-666666666666';
  const coTeacher: CachedAccount = { ...account, user: { ...account.user, id: coTeacherId }, classrooms: [{ ...account.classrooms[0], co_teacher_ids: [coTeacherId] }] };
  await expect(createQuiz(coTeacher, roomId, input)).resolves.toEqual({ id: quizId, status: 'DRAFT' });
});

it('rejects non-owned classes and invalid questions before requesting', async () => {
  await expect(createQuiz({ ...account, classrooms: [{ ...account.classrooms[0], teacher_id: quizId }] }, roomId, input)).rejects.toMatchObject({ code: 'FORBIDDEN' });
  await expect(createQuiz(account, roomId, { ...input, questions: [{ ...question, points: 0 }] })).rejects.toMatchObject({ code: 'invalid_fields' });
  expect(requestHub).not.toHaveBeenCalled();
});

it('rejects an incomplete server receipt', async () => {
  requestHub.mockResolvedValue({ id: quizId, classroom_id: roomId, title: 'Quiz', time_limit_minutes: 10, status: 'DRAFT', questions: [] });
  await expect(createQuiz(account, roomId, input)).rejects.toMatchObject({ code: 'invalid_response' });
});
it('preserves multiline question text when creating a quiz',async()=>{
 const multiline={...question,question_text:'Read this example.\nWhich answer is correct?'};
 requestHub.mockResolvedValue({id:quizId,classroom_id:roomId,title:'Quiz',time_limit_minutes:10,status:'DRAFT',questions:[{...multiline,id:serverQuestionId}]});
 await expect(createQuiz(account,roomId,{...input,questions:[multiline]})).resolves.toEqual({id:quizId,status:'DRAFT'});
 expect(requestHub.mock.calls[0][2].body.questions[0].question_text).toBe(multiline.question_text);
});
