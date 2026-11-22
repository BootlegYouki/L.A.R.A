import {useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import type {CachedAccount,Classroom} from '../services/auth';
import {t} from '../i18n';
interface Book {work:{id:string;title:string;max_points:number;kind:string}[];learners:{id:string;name:string;scores:Record<string,number|null>}[]}
export default function Gradebook({account,classroom}:{account:CachedAccount;classroom:Classroom}) {
 const [book,setBook]=useState<Book|null>(null),[error,setError]=useState(false),[retry,setRetry]=useState(0);
 useEffect(()=>{let current=true;setBook(null);setError(false);void invoke<Book>('read_gradebook',{hub:account.hub,userId:account.user.id,classroomId:classroom.id}).then(value=>{if(current)setBook(value)}).catch(()=>{if(current)setError(true)});return()=>{current=false}},[account,classroom.id,retry]);
 return <section className="gradebook"><h1>{t.work_grades}</h1><p>{t.work_grades_help}</p>
  {error&&<p role="alert">{t.work_load_error}<button className="control text-button" onClick={()=>setRetry(v=>v+1)}>{t.retry}</button></p>}
  {!book&&!error&&<p role="status">{t.loading}</p>}
  {book&&(!book.learners.length?<p>{t.work_no_learners}</p>:<div className="gradebook-scroll" tabIndex={0} role="region" aria-label={t.work_grades}><table><thead><tr><th scope="col">{t.work_learner}</th>{book.work.map(work=><th scope="col" key={work.id}>{work.title}<small>{work.max_points} {t.work_points}</small></th>)}</tr></thead><tbody>{book.learners.map(learner=><tr key={learner.id}><th scope="row">{learner.name}</th>{book.work.map(work=><td key={work.id}>{learner.scores[work.id]??'—'}</td>)}</tr>)}</tbody></table></div>)}
 </section>;
}
