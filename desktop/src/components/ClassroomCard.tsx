import { t } from '../i18n';
import type { Classroom } from '../services/auth';
export default function ClassroomCard({ classroom, onOpen }: { classroom: Classroom; onOpen: (id: string) => void }) {
  return <button type="button" className="class-card classroom-card-button" onClick={() => onOpen(classroom.id)}>
    <span className="class-icon"><i className="ph ph-chalkboard-teacher" aria-hidden="true" /></span>
    <h2>{classroom.name}</h2><p>{classroom.section}</p>
    <span className="class-details-status"><i className="ph ph-hard-drive" aria-hidden="true" />{t.class_details_saved}</span>
    <span className="class-card-footer">{t.open_class}<i className="ph ph-arrow-right" aria-hidden="true" /></span>
  </button>;
}
