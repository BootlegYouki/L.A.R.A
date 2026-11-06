import { t } from '../i18n';
import type { AuthUser, Classroom } from '../services/auth';
interface Props { user: AuthUser | null; classrooms: Classroom[]; onHome: () => void; onOpen: (id: string) => void; selectedId?: string }
export default function Sidebar({ user, classrooms, onHome, onOpen, selectedId }: Props) {
  return <nav id="navigation" className="drawer" aria-label={t.navigation}>
    <button type="button" className={`drawer-item ${!selectedId ? 'selected' : ''}`} aria-current={!selectedId ? 'page' : undefined} aria-label={t.home} onClick={onHome}><i className="ph-fill ph-house" aria-hidden="true" /><span className="drawer-label">{t.home}</span></button>
    {user && <><div className="drawer-section"><i className="ph ph-graduation-cap" aria-hidden="true" /><span className="drawer-label">{user.role === 'TEACHER' ? t.teaching : t.enrolled}</span></div><div className="drawer-classes">{classrooms.map((classroom) => <button type="button" key={classroom.id} className={`drawer-item ${selectedId === classroom.id ? 'selected' : ''}`} aria-current={selectedId === classroom.id ? 'page' : undefined} onClick={() => onOpen(classroom.id)}><span className="drawer-initial" aria-hidden="true">{classroom.name.trim().slice(0, 1)}</span><span className="drawer-class-label"><strong>{classroom.name}</strong><small>{classroom.section}</small></span></button>)}</div></>}
    <div className="drawer-rule" />
    <button type="button" className="drawer-item" aria-label={t.archived_classes} disabled><i className="ph ph-archive" aria-hidden="true" /><span className="drawer-label">{t.archived_classes}</span></button>
    <button type="button" className="drawer-item" aria-label={t.settings} disabled><i className="ph ph-gear" aria-hidden="true" /><span className="drawer-label">{t.settings}</span></button>
  </nav>;
}
