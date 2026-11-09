import { useState } from 'react';
import { t } from '../i18n';
import type { Classroom } from '../services/auth';
import { formatClassCode } from './CreateClassroomDialog';
interface Props { classroom: Classroom; onHome: () => void }
export default function TopAppBar({ classroom, onHome }: Props) {
  const [copyMessage, setCopyMessage] = useState<{ code: string; text: string } | null>(null);
  async function copy() {
    try { await navigator.clipboard.writeText(formatClassCode(classroom.class_code)); setCopyMessage({ code: classroom.class_code, text: t.class_code_copied }); }
    catch { setCopyMessage({ code: classroom.class_code, text: t.class_code_copy_failed }); }
  }
  return <div className="class-app-bar"><button type="button" className="control text-button" onClick={onHome}><i className="ph ph-house" aria-hidden="true" />{t.home}</button><div className="class-crumb"><strong>{classroom.name}</strong><span>{classroom.section}</span></div><div className="class-code-badge"><span>{t.join_code_label}: <strong>{formatClassCode(classroom.class_code)}</strong></span><button type="button" className="icon-button" aria-label={t.copy_class_code} onClick={() => { void copy(); }}><i className="ph ph-copy" aria-hidden="true" /></button></div><span role="status" className="copy-feedback">{copyMessage?.code === classroom.class_code ? copyMessage.text : ''}</span></div>;
}
