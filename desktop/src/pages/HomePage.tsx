import { useEffect, useRef } from 'react';
import { t } from '../i18n';
import type { AuthUser, Classroom } from '../services/auth';
import ClassroomCard from '../components/ClassroomCard';
interface JoinRequest { classroom_id: string; status: 'PENDING' | 'ACTIVE' | 'REJECTED' | 'REMOVED' }
interface Props { user: AuthUser; classrooms: Classroom[]; loading: boolean; error: string | null; retryAfter?: number; onRetry: () => void; onOpen: (id: string) => void; joinRequests?: JoinRequest[]; onJoin?: () => void; onCreate?: () => void; offline?: boolean; archiveVisit?: number; onTestVideo?: () => void; }
export default function HomePage({ user, classrooms, loading, error, retryAfter = 0, onRetry, onOpen, joinRequests = [], onJoin, onCreate, archiveVisit, onTestVideo }: Props) {
  const teacher = user.role === 'TEACHER';
  const action = teacher ? t.create_class : t.join_class;
  const receipts = teacher ? [] : joinRequests.filter((request) => !classrooms.some((classroom) => classroom.id === request.classroom_id));
  const active = classrooms.filter(c => c.archived_at == null);
  const archived = classrooms.filter(c => c.archived_at != null && (teacher || c.enrollment_status === 'ACTIVE'));
  const archiveHeading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { if (archiveVisit) archiveHeading.current?.focus(); }, [archiveVisit]);
  const hasCards = active.length > 0 || receipts.length > 0;
  
  function statusCard(id: string, status: JoinRequest['status'], classroom?: Classroom) {
    const title = status === 'REJECTED' ? t.join_rejected : status === 'REMOVED' ? t.join_removed : status === 'ACTIVE' ? t.join_waiting_details : t.join_pending;
    const body = status === 'REJECTED' ? t.join_rejected_body : status === 'REMOVED' ? t.join_removed_body : status === 'ACTIVE' ? t.join_waiting_details_body : t.join_pending_body;
    return <section key={id} className="class-card join-status-card"><span className="class-icon"><i className="ph ph-hourglass" aria-hidden="true" /></span><h2>{classroom?.name ?? t.join_request_title}</h2>{classroom && <p>{classroom.section}</p>}<span className="class-details-status">{title}</span><p>{body}</p></section>;
  }
  return <div className="home-page">
    <div className="classes-heading"><h1>{t.classes_title}</h1><button type="button" className="control text-button" disabled={teacher ? !onCreate : !onJoin} onClick={teacher ? onCreate : onJoin}><i className="ph ph-plus" aria-hidden="true" />{action}</button></div>
    {onTestVideo && <section className="database-error" role="status"><h2><i className="ph ph-video" aria-hidden="true" />{t.video_test_title}</h2><p>{t.video_test_help}</p><button type="button" className="control tonal-button" onClick={onTestVideo}><i className="ph ph-play-circle" aria-hidden="true" />{t.video_test_open}</button></section>}
    {error && <section className="database-error" role="alert"><h2><i className="ph ph-warning-circle" aria-hidden="true" />{t.classes_error_title}</h2><p>{error}</p>{retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}<button type="button" className="control tonal-button" disabled={loading || retryAfter > 0} onClick={onRetry}>{t.retry}</button></section>}
    {hasCards ? <><div className="class-grid">{active.map((classroom) => teacher || classroom.enrollment_status === 'ACTIVE' ? <ClassroomCard key={classroom.id} classroom={classroom} onOpen={onOpen} /> : statusCard(classroom.id, classroom.enrollment_status ?? 'PENDING', classroom))}{receipts.map((request) => statusCard(request.classroom_id, request.status))}</div>{loading && <p role="status" className="refresh-status">{t.classes_refreshing}</p>}</> : loading ? <div role="status" aria-label={t.loading} className="class-grid">{[0, 1, 2].map((key) => <div key={key} className="skeleton class-skeleton" />)}</div> : !error && <section className="empty-state"><span className="empty-art"><i className="ph ph-graduation-cap" aria-hidden="true" /></span><div><h2>{t.empty_title}</h2><p>{teacher ? t.teacher_empty_body : t.learner_empty_body}</p><button type="button" className="control tonal-button" disabled={teacher ? !onCreate : !onJoin} onClick={teacher ? onCreate : onJoin}><i className="ph ph-plus" aria-hidden="true" />{action}</button></div></section>}
    <section aria-labelledby="archived-classes"><div className="classes-heading"><h2 id="archived-classes" ref={archiveHeading} tabIndex={-1}>{t.archived_classes}</h2></div>
      {archived.length ? <div className="class-grid">{archived.map(classroom => <ClassroomCard key={classroom.id} classroom={classroom} onOpen={onOpen} />)}</div> : <p>{t.class_archive_empty}</p>}
    </section>
  </div>;
}
