import { useEffect, useRef, useState } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { CachedAccount } from '../services/auth';
import { createQuiz, type QuizQuestionInput } from '../services/quizAuthoring';

interface Props { account: CachedAccount; classroomId: string; offline: boolean; onClose: () => void; onCreated: () => void }
interface QuestionDraft { id: string; text: string; options: string[]; correct: string; points: string }
const newQuestion = (): QuestionDraft => ({ id: crypto.randomUUID(), text: '', options: ['', '', '', ''], correct: '0', points: '1' });

export default function QuizCreateDialog({ account, classroomId, offline, onClose, onCreated }: Props) {
  const [title, setTitle] = useState('');
  const [duration, setDuration] = useState('10');
  const [questions, setQuestions] = useState([newQuestion()]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  const sending = useRef(false);
  const controller = useRef(new AbortController());
  useEffect(()=>{const active=new AbortController();controller.current=active;return()=>active.abort();},[]);

  function updateQuestion(id: string, change: Partial<QuestionDraft>) {
    setQuestions(previous => previous.map(question => question.id === id ? { ...question, ...change } : question));
  }
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (sending.current || offline) return;
    sending.current = true;
    setBusy(true);
    setError(false);
    try {
      const payload: QuizQuestionInput[] = questions.map((question, index) => ({
        id: question.id,
        order_index: index,
        question_text: question.text.trim(),
        question_type: 'MULTIPLE_CHOICE',
        options: question.options.map(option => option.trim()),
        points: Number(question.points),
        correct_answer: question.options[Number(question.correct)].trim(),
      }));
      await createQuiz(account, classroomId, { title, time_limit_minutes: Number(duration), questions: payload }, controller.current.signal);
      onCreated();
      onClose();
    } catch {
      if (!controller.current.signal.aborted) setError(true);
    } finally {
      sending.current = false;
      if (!controller.current.signal.aborted) setBusy(false);
    }
  }

  return <Dialog.Root open onOpenChange={open => { if (!open && !busy) onClose(); }}>
    <Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog authoring-dialog quiz-workspace" aria-busy={busy}>
      <header className="quiz-workspace-header"><Dialog.Title>{t.quiz_create}</Dialog.Title><Dialog.Description>{t.quiz_help}</Dialog.Description></header>
      <form onSubmit={event => void submit(event)}><div className="quiz-workspace-body"><aside className="quiz-settings">
        <div className="auth-field"><label htmlFor="quiz-title">{t.quiz_title}</label><input id="quiz-title" required maxLength={200} value={title} disabled={busy} onChange={event => setTitle(event.target.value)} /></div>
        <div className="auth-field"><label htmlFor="quiz-duration">{t.quiz_duration}</label><input id="quiz-duration" type="number" min="1" step="1" required value={duration} disabled={busy} onChange={event => setDuration(event.target.value)} /></div>
        </aside><section className="quiz-questions">{questions.map((question, index) => <fieldset className="quiz-question" key={question.id}>
          <legend>{t.quiz_question_heading} {index + 1}</legend>
          <div className="auth-field"><label htmlFor={`quiz-question-${question.id}`}>{t.quiz_question_text}</label><textarea id={`quiz-question-${question.id}`} required rows={3} value={question.text} disabled={busy} onChange={event => updateQuestion(question.id, { text: event.target.value })} /></div>
          <div className="quiz-options">{question.options.map((option, optionIndex) => <div className="auth-field" key={optionIndex}><label htmlFor={`quiz-option-${question.id}-${optionIndex}`}>{t.quiz_option_label} {optionIndex + 1}</label><input id={`quiz-option-${question.id}-${optionIndex}`} required value={option} disabled={busy} onChange={event => updateQuestion(question.id, { options: question.options.map((value, index) => index === optionIndex ? event.target.value : value) })} /></div>)}
          </div><div className="authoring-fields">
            <div className="auth-field"><label htmlFor={`quiz-answer-${question.id}`}>{t.quiz_correct_option}</label><select id={`quiz-answer-${question.id}`} value={question.correct} disabled={busy} onChange={event => updateQuestion(question.id, { correct: event.target.value })}>{question.options.map((_, optionIndex) => <option key={optionIndex} value={optionIndex}>{t.quiz_option_label} {optionIndex + 1}</option>)}</select></div>
            <div className="auth-field"><label htmlFor={`quiz-points-${question.id}`}>{t.quiz_points}</label><input id={`quiz-points-${question.id}`} type="number" min="1" step="1" required value={question.points} disabled={busy} onChange={event => updateQuestion(question.id, { points: event.target.value })} /></div>
          </div>
          {questions.length > 1 && <button type="button" className="control text-button" disabled={busy} onClick={() => setQuestions(previous => previous.filter(item => item.id !== question.id))}>{t.quiz_remove_question}</button>}
        </fieldset>)}
        <button type="button" className="control text-button" disabled={busy || questions.length >= 100} onClick={() => setQuestions(previous => [...previous, newQuestion()])}>{t.quiz_add_question}</button>
        </section></div>{offline && <p role="status">{t.quiz_offline}</p>}{error && <p role="alert" className="join-error">{t.quiz_error}</p>}
        <div className="join-actions quiz-workspace-footer"><button type="button" className="control text-button" disabled={busy} onClick={onClose}>{t.cancel}</button><button type="submit" className="control auth-submit" disabled={busy || offline || !title.trim()}>{busy ? t.auth_working : t.quiz_publish}</button></div>
      </form>
    </Dialog.Content></Dialog.Portal>
  </Dialog.Root>;
}
