import './Gradebook.css';
import OwlEmptyState from './OwlEmptyState';
import gradesOwl from '../assets/owls/owl-01.webp';
import {useEffect,useState} from 'react';
import {invoke} from '@tauri-apps/api/core';
import type {CachedAccount,Classroom} from '../services/auth';
import {t} from '../i18n';
interface Book {work:{id:string;title:string;max_points:number;kind:string}[];learners:{id:string;name:string;scores:Record<string,number|null>}[]}
export default function Gradebook({account,classroom,onPeople}:{account:CachedAccount;classroom:Classroom;onPeople?:()=>void}) {
 const [book,setBook]=useState<Book|null>(null),[error,setError]=useState(false),[retry,setRetry]=useState(0);
 useEffect(()=>{let current=true;setBook(null);setError(false);void invoke<Book>('read_gradebook',{hub:account.hub,userId:account.user.id,classroomId:classroom.id}).then(value=>{if(current)setBook(value)}).catch(()=>{if(current)setError(true)});return()=>{current=false}},[account,classroom.id,retry]);
 return <section className="gradebook"><header className="gradebook-heading"><h1>{t.work_grades}</h1><p>{classroom.name}{classroom.section?` / ${classroom.section}`:''}</p></header>
  {error&&<p role="alert">{t.work_load_error}<button className="control text-button" onClick={()=>setRetry(v=>v+1)}>{t.retry}</button></p>}
  {!book&&!error&&<div role="status" aria-label={t.loading} className="skeleton gradebook-skeleton"/>}
  {book&&(!book.learners.length?<OwlEmptyState variant="center" image={gradesOwl} title={t.grades_empty_title} body={t.grades_empty_help}>{onPeople&&<button type="button" className="control tonal-button" onClick={onPeople}>{t.grades_view_people}<i className="ph ph-arrow-right" aria-hidden="true"/></button>}</OwlEmptyState>:<div className="gradebook-scroll" tabIndex={0} role="region" aria-label={t.work_grades}><table><thead><tr><th scope="col">{t.work_learner}</th>{book.work.map(work=><th scope="col" key={work.id}>{work.title}<small>{work.max_points} {t.work_points}</small></th>)}</tr></thead><tbody>{book.learners.map(learner=><tr key={learner.id}><th scope="row">{learner.name}</th>{book.work.map(work=><td key={work.id}>{learner.scores[work.id]??'—'}</td>)}</tr>)}</tbody></table></div>)}
 </section>;
}
