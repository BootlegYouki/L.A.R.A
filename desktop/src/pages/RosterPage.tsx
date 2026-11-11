import { useRef, useState, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { t } from '../i18n';
import type { AuthUser, Classroom, RosterEntry } from '../services/auth';
interface Props { classroom: Classroom; user: AuthUser; teachers?: ReactNode; roster?: RosterEntry[]; loading: boolean; error: string | null; actionError?: string; offline: boolean; busy: boolean; retryAfter: number; onRetry: () => void; onDecide: (studentId: string, action: 'approve' | 'reject' | 'remove') => void }
export default function RosterPage({ classroom, user, teachers, roster, loading, error, actionError, offline, busy, retryAfter, onRetry, onDecide }: Props) {
  const [removing, setRemoving] = useState<RosterEntry | null>(null);
  const cancel = useRef<HTMLButtonElement | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  if (user.role !== 'TEACHER') return null;
  const archived = classroom.archived_at != null;
  const blocked = archived || offline || busy || retryAfter > 0;
  const pending = roster?.filter((entry) => entry.status === 'PENDING').length ?? 0;
  const labels = { PENDING: t.roster_pending, ACTIVE: t.roster_active, REJECTED: t.roster_rejected, REMOVED: t.roster_removed };
  const icons = { PENDING: 'hourglass', ACTIVE: 'check-circle', REJECTED: 'x-circle', REMOVED: 'minus-circle' };
  return <section className="roster-page" aria-label={`${t.people}: ${classroom.name}`}><h1>{t.people}</h1>
    {archived && <p className="hub-status-banner banner-info" role="status">{t.class_archived_read_only}</p>}
    {offline && !archived && <p className="hub-status-banner banner-warning" role="status"><i className="ph ph-cloud-slash" aria-hidden="true" />{t.roster_offline}</p>}
    {teachers}
    {!archived && pending > 0 && <p className="hub-status-banner banner-info" role="status"><i className="ph ph-info" aria-hidden="true" />{(pending === 1 ? t.roster_pending_help_single : t.roster_pending_help).replace('{count}', String(pending))}</p>}
    <div className="people-heading"><h2>{t.learners}</h2>{roster && <span>{(roster.length === 1 ? t.roster_count_single : t.roster_count).replace('{count}', String(roster.length))}</span>}<button type="button" className="control text-button" disabled={blocked || loading} onClick={onRetry}><i className="ph ph-arrow-clockwise" aria-hidden="true" />{t.roster_refresh}</button></div>
    {error && <div className="database-error" role="alert"><h3>{t.roster_error}</h3><p>{error}</p><button type="button" className="control tonal-button" disabled={blocked || loading} onClick={onRetry}>{t.retry}</button></div>}
    {actionError && <p className="hub-status-banner banner-warning" role="alert">{actionError}</p>}
    {retryAfter > 0 && <p role="status">{t.auth_retry_after.replace('{seconds}', String(retryAfter))}</p>}
    {roster === undefined ? loading ? <div role="status" aria-label={t.roster_loading}><div className="skeleton roster-skeleton" /><div className="skeleton roster-skeleton" /></div> : !error && <p>{t.roster_unavailable}</p> : roster.length === 0 ? <div className="empty-state"><span className="empty-art"><i className="ph ph-users" aria-hidden="true" /></span><div><h3>{t.roster_empty}</h3><p>{t.roster_empty_help}</p></div></div> : <div className="roster-table-scroll"><table className="roster-table"><thead><tr><th scope="col">{t.roster_name}</th><th scope="col">{t.roster_status}</th><th scope="col">{t.roster_actions}</th></tr></thead><tbody>{roster.map((entry) => <tr key={entry.enrollment_id}><th scope="row"><span className="person-name"><span className="person-avatar" aria-hidden="true">{entry.full_name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]).join('')}</span>{entry.full_name}</span></th><td><span className={`roster-status status-${entry.status.toLowerCase()}`}><i className={`ph ph-${icons[entry.status]}`} aria-hidden="true" />{labels[entry.status]}</span></td><td><div className="roster-actions">{!archived && entry.status === 'PENDING' && <><button type="button" className="control auth-submit" disabled={blocked} onClick={() => onDecide(entry.student_id, 'approve')}>{t.roster_accept}</button><button type="button" className="control text-button" disabled={blocked} onClick={() => onDecide(entry.student_id, 'reject')}>{t.roster_decline}</button></>}{!archived && entry.status === 'ACTIVE' && <button type="button" className="control text-button" disabled={blocked} onClick={(event) => { returnFocus.current = event.currentTarget; setRemoving(entry); }}>{t.roster_remove}</button>}</div></td></tr>)}</tbody></table></div>}
    {loading && roster !== undefined && <p role="status">{t.roster_refreshing}</p>}
    <Dialog.Root open={!!removing} onOpenChange={(open) => { if (!open) setRemoving(null); }}><Dialog.Portal><Dialog.Overlay className="join-scrim" /><Dialog.Content className="join-dialog" onOpenAutoFocus={(event) => { event.preventDefault(); cancel.current?.focus(); }} onCloseAutoFocus={(event) => { event.preventDefault(); returnFocus.current?.focus(); }}><Dialog.Title>{t.roster_remove_title.replace('{name}', removing?.full_name ?? '')}</Dialog.Title><Dialog.Description>{t.roster_remove_help}</Dialog.Description><div className="join-actions"><Dialog.Close asChild><button ref={cancel} type="button" className="control text-button">{t.cancel}</button></Dialog.Close><button type="button" className="control destructive-button" disabled={blocked} onClick={() => { if (removing && !blocked) { onDecide(removing.student_id, 'remove'); setRemoving(null); } }}>{t.roster_remove}</button></div></Dialog.Content></Dialog.Portal></Dialog.Root>
  </section>;
}
