import { ApiError, requestHub } from './api';
import { accountToken, forgetAccount, hasControlCharacters, type CachedAccount } from './auth';

export interface QuizQuestionInput {
  id: string;
  order_index: number;
  question_text: string;
  question_type: 'MULTIPLE_CHOICE';
  options: string[];
  points: number;
  correct_answer: string;
}
export interface QuizInput {
  classroom_id: string;
  title: string;
  time_limit_minutes: number;
  questions: QuizQuestionInput[];
}
export interface QuizReceipt { id: string; status: 'DRAFT' }

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const text = (value: string, multiline=false) => typeof value === 'string' && !!value.trim() && !hasControlCharacters(multiline?value.replace(/[\r\n\t]/g,''):value);

export async function createQuiz(account: CachedAccount, classroomId: string, input: { title: string; time_limit_minutes: number; questions: QuizQuestionInput[] }, signal?: AbortSignal): Promise<QuizReceipt> {
  const classroom = account.classrooms.find(item => item.id === classroomId && (item.teacher_id === account.user.id || item.co_teacher_ids?.includes(account.user.id)));
  if (account.user.role !== 'TEACHER' || !uuid(classroomId) || !classroom || classroom.archived_at != null) throw new ApiError('FORBIDDEN');
  if (!text(input.title) || input.title.length > 200 || !Number.isSafeInteger(input.time_limit_minutes) || input.time_limit_minutes < 1
    || !Array.isArray(input.questions) || !input.questions.length || input.questions.length > 100
    || input.questions.some((question, index) => !uuid(question.id) || question.order_index !== index || question.question_type !== 'MULTIPLE_CHOICE'
      || !text(question.question_text,true) || !Number.isSafeInteger(question.points) || question.points < 1
      || !Array.isArray(question.options) || question.options.length < 2 || question.options.length > 6
      || question.options.some(option => !text(option)) || new Set(question.options).size !== question.options.length
      || !question.options.includes(question.correct_answer))) throw new ApiError('invalid_fields');
  const body: QuizInput = { classroom_id: classroomId, title: input.title.trim(), time_limit_minutes: input.time_limit_minutes, questions: input.questions };
  try {
    const response = await requestHub(account.hub, '/api/quizzes', { body, token: await accountToken(account), signal });
    const questionIds = new Set<string>();
    if (typeof response !== 'object' || response === null || !('id' in response) || !uuid(response.id) || !('status' in response) || response.status !== 'DRAFT'
      || !('classroom_id' in response) || response.classroom_id !== classroomId || !('title' in response) || response.title !== input.title.trim()
      || !('time_limit_minutes' in response) || response.time_limit_minutes !== input.time_limit_minutes
      || !('questions' in response) || !Array.isArray(response.questions) || response.questions.length !== input.questions.length
      || response.questions.some((question, index) => {
        if (typeof question !== 'object' || question === null || !('id' in question) || !uuid(question.id) || questionIds.has(question.id)) return true;
        questionIds.add(question.id);
        return !('order_index' in question) || question.order_index !== index
          || !('question_text' in question) || question.question_text !== input.questions[index].question_text
          || !('question_type' in question) || question.question_type !== 'MULTIPLE_CHOICE'
          || !('options' in question) || !Array.isArray(question.options) || question.options.length !== input.questions[index].options.length
          || !question.options.every((option: unknown, optionIndex: number) => option === input.questions[index].options[optionIndex])
          || !('points' in question) || question.points !== input.questions[index].points
          || !('correct_answer' in question) || question.correct_answer !== input.questions[index].correct_answer;
      })) {
      throw new ApiError('invalid_response');
    }
    // Keep the teacher answer key out of client persistence and the returned receipt.
    return { id: response.id, status: 'DRAFT' };
  } catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
}
