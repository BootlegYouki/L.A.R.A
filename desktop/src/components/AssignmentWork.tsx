import {useEffect,useState,type ReactNode} from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import type {CachedAccount,Classroom} from '../services/auth';
import {readAssignmentWork,fetchSubmissions,publishGrade,scoreValue,type Assignment,type Submission} from '../services/grading';
import CommentThread from './CommentThread';
import SubmissionPreview from './SubmissionPreview';
import HomeworkUpload from './HomeworkUpload';
import {t} from '../i18n';
interface Props {account:CachedAccount;classroom:Classroom;assignment:Assignment;offline:boolean;attachments?:ReactNode;onBack:()=>void;onChanged:()=>void}
export default function AssignmentWork(props:Props) {
 return <Work key={`${props.account.hub.ip}:${props.account.hub.http_port}/${props.account.user.id}/${props.assignment.id}`} {...props}/>;
}
function Work({account,classroom,assignment,offline,attachments,onBack,onChanged}:Props) {
 const teacher=account.user.role==='TEACHER';
 const [rows,setRows]=useState<Submission[]>([]),[selected,setSelected]=useState(teacher?'':account.user.id),[loading,setLoading]=useState(true),[error,setError]=useState(false),[denied,setDenied]=useState(false),[refresh,setRefresh]=useState(0);
 const [filter,setFilter]=useState('all');
 const [dirty,setDirty]=useState(false),[leave,setLeave]=useState<string|null>(null);
 const navigate=(target:string)=>{if(dirty){setLeave(target);return;}if(target==='back')onBack();else setSelected(target)};
 const roster=account.rosters?.[classroom.id]?.filter(row=>row.status==='ACTIVE')??[];
 useEffect(()=>{
  const request=new AbortController();let current=true;setLoading(true);setError(false);
  void (async()=>{
   try {
    const cached=await readAssignmentWork(account,assignment.id);if(!current)return;setRows(cached);
    if(teacher&&!offline){const fresh=await fetchSubmissions(account,classroom.id,assignment.id,request.signal);if(current){setRows(fresh);setDenied(false)}}
   }catch(failure){if(current){setError(true);if(failure instanceof Error&&['FORBIDDEN','UNAUTHORIZED','NOT_FOUND'].includes(failure.message)){setRows([]);setDenied(true)}}}
   finally{if(current)setLoading(false)}
  })();return()=>{current=false;request.abort()};
 },[account,assignment.id,classroom.id,offline,refresh,teacher]);
 const update=()=>{setRefresh(value=>value+1);onChanged()};
 const submission=rows.find(row=>row.student_id===selected);
 const status=(row:Submission|undefined)=>row?.score!=null?'graded':row?'turned':'assigned';
 const label=(row:Submission|undefined)=>status(row)==='graded'?t.work_graded:status(row)==='turned'?t.work_turned:t.work_assigned;
 return <section className="assignment-work">
  <button type="button" className="control text-button" onClick={()=>navigate('back')}><i className="ph ph-arrow-left" aria-hidden="true"/>{t.classwork}</button>
  <header><h1>{assignment.title}</h1><p>{assignment.max_points} {t.work_points} · {t.work_due} {new Date(assignment.due_date).toLocaleString('en')}</p></header>
  <div className={teacher?'teacher-assignment-layout':'learner-assignment-layout'}><article className="assignment-instructions"><details open={!teacher}><summary>{t.work_instructions}</summary><p className="lesson-text">{assignment.instructions}</p></details>{attachments}</article>
  {loading&&<p role="status">{t.loading}</p>}
  {error&&<p role="alert">{t.work_load_error}<button className="control text-button" onClick={update}>{t.retry}</button></p>}
  {offline&&<p className="hub-status-banner banner-info">{t.work_offline}</p>}
  {!denied&&<div className={teacher?'work-review-grid':'work-learner-grid'}>
   {teacher&&<aside className="work-roster"><h2>{t.work_learner_work}</h2><label>{t.work_filter}<select value={filter} onChange={event=>setFilter(event.target.value)}><option value="all">{t.work_all}</option><option value="assigned">{t.work_assigned}</option><option value="turned">{t.work_turned}</option><option value="graded">{t.work_graded}</option></select></label>
    {!roster.length&&<p>{t.work_no_learners}</p>}
    {roster.filter(row=>filter==='all'||status(rows.find(s=>s.student_id===row.student_id))===filter).map(row=><button type="button" className="work-learner control" aria-pressed={selected===row.student_id} key={row.student_id} onClick={()=>navigate(row.student_id)}><strong>{row.full_name}</strong><small>{label(rows.find(s=>s.student_id===row.student_id))}</small></button>)}
   </aside>}
   {selected?<section className="work-selected" key={selected}><h2>{teacher?roster.find(row=>row.student_id===selected)?.full_name:t.work_your_work}</h2>
    <p className="work-status">{label(submission)}</p>
    {submission?<><p>{t.work_submitted} {new Date(submission.submitted_at).toLocaleString('en')}</p>{submission.score!=null&&<p className="work-score">{submission.score} / {assignment.max_points}</p>}{submission.teacher_feedback&&<p className="lesson-text">{submission.teacher_feedback}</p>}
      <SubmissionPreview account={account} submissionId={submission.id} attachments={submission.attachments} offline={offline}/>
      {teacher&&<GradeEditor key={submission.id} account={account} classroom={classroom} submission={submission} offline={offline} onChanged={update} onDirty={setDirty}/>}
    </>:teacher?<p>{t.work_no_submission}</p>:null}
    {!teacher&&<HomeworkUpload account={account} assignmentId={assignment.id} offline={offline} archived={classroom.archived_at!=null} onChanged={update}/>}
    <CommentThread account={account} target={{kind:'assignment',id:assignment.id,...(teacher?{student_id:selected}:{})}} offline={offline} onChanged={update}/>
   </section>:<p>{t.work_select_learner}</p>}
  </div>}
  </div><Dialog.Root open={leave!==null} onOpenChange={open=>{if(!open)setLeave(null)}}><Dialog.Portal><Dialog.Overlay className="dialog-overlay"/><Dialog.Content className="dialog-content"><Dialog.Title>{t.work_unsaved}</Dialog.Title><Dialog.Description>{t.work_unsaved_help}</Dialog.Description><button className="control tonal-button" onClick={()=>setLeave(null)}>{t.work_keep_editing}</button><button className="control text-button" onClick={()=>{const target=leave;setLeave(null);setDirty(false);if(target==='back')onBack();else if(target)setSelected(target)}}>{t.work_discard}</button></Dialog.Content></Dialog.Portal></Dialog.Root>
 </section>;
}
function GradeEditor({account,classroom,submission,offline,onChanged,onDirty}:{account:CachedAccount;classroom:Classroom;submission:Submission;offline:boolean;onChanged:()=>void;onDirty:(dirty:boolean)=>void}) {
 const [score,setScore]=useState(submission.score==null?'':String(submission.score));
 const [feedback,setFeedback]=useState(submission.teacher_feedback??'');
 const [error,setError]=useState(''),[busy,setBusy]=useState(false),[notice,setNotice]=useState('');
 let parsed:number|null=null,valid=true;
 try{parsed=scoreValue(score)}catch{valid=false}
 const dirty=parsed!==submission.score||feedback!==(submission.teacher_feedback??'');
 const readOnly=offline||classroom.archived_at!=null;
 useEffect(()=>{onDirty(dirty);return()=>onDirty(false)},[dirty,onDirty]);
 useEffect(()=>{if(!dirty)return;const warn=(event:BeforeUnloadEvent)=>event.preventDefault();window.addEventListener('beforeunload',warn);return()=>window.removeEventListener('beforeunload',warn)},[dirty]);
 async function save(){
  if(busy||readOnly||!valid||parsed===null)return;
  setBusy(true);setError('');setNotice('');
  try{await publishGrade(account,classroom.id,submission.id,parsed,feedback);setNotice(t.work_returned);onChanged()}
  catch{setError(t.work_save_error)}
  finally{setBusy(false)}
 }
 return <section className="work-grading"><h3>{t.work_grade}</h3>
  <label>{t.work_score}<input inputMode="numeric" value={score} disabled={readOnly||busy} onChange={event=>setScore(event.target.value)}/></label>
  <label>{t.work_feedback}<textarea rows={4} maxLength={10000} value={feedback} disabled={readOnly||busy} onChange={event=>setFeedback(event.target.value)}/></label>
  {!valid&&<p role="alert">{t.work_invalid_score}</p>}
  <div className="work-grade-actions"><button className="control auth-submit" disabled={readOnly||busy||!valid||parsed===null||!dirty} onClick={()=>void save()}>{t.work_publish_grade}</button></div>
  {error&&<p role="alert">{error}</p>}{notice&&<p role="status">{notice}</p>}
 </section>;
}
