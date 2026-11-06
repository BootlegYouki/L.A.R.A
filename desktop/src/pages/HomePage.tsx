import { t } from '../i18n';
import type { AuthUser, Classroom } from '../services/auth';
import ClassroomCard from '../components/ClassroomCard';
interface Props { user: AuthUser; classrooms: Classroom[]; loading: boolean; error: string | null; retryAfter?: number; onRetry: () => void; onOpen: (id: string) => void }
export default function HomePage({ user, classrooms, loading, error, retryAfter = 0, onRetry, onOpen }: Props) {
  const teacher = user.role === 'TEACHER';
  const action = teacher ? t.create_class : t.join_class;
  return <div className="home-page">
    <div className="classes-heading"><h1>{t.classes_title}</h1><button type="button" className="control text-button" disabled><i className="ph ph-plus" aria-hidden="true" />{action}</button></div>
    {error && <section className="database-error" role="alert"><h2><i className="ph ph-warning-circle" aria-hidden="true" />{t.classes_error_title}</h2><p>{error}</p>{retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}<button type="button" className="control tonal-button" disabled={loading || retryAfter > 0} onClick={onRetry}>{t.retry}</button></section>}
    {classrooms.length > 0 ? <><div className="class-grid">{classrooms.map((classroom) => <ClassroomCard key={classroom.id} classroom={classroom} onOpen={onOpen} />)}</div>{loading && <p role="status" className="refresh-status">{t.classes_refreshing}</p>}</> : loading ? <div role="status" aria-label={t.loading} className="class-grid">{[0, 1, 2].map((key) => <div key={key} className="skeleton class-skeleton" />)}</div> : !error && <section className="empty-state"><span className="empty-art"><i className="ph ph-graduation-cap" aria-hidden="true" /></span><div><h2>{t.empty_title}</h2><p>{teacher ? t.teacher_empty_body : t.learner_empty_body}</p><button type="button" className="control tonal-button" disabled><i className="ph ph-plus" aria-hidden="true" />{action}</button></div></section>}
  </div>;
}
