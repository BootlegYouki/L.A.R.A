import {invoke} from '@tauri-apps/api/core';
import {ApiError,requestHub} from './api';
import {accountToken,type CachedAccount} from './auth';
import {withDatabaseTimeout} from './db';

export interface SubmissionAttachment {name:string;file_type:'IMAGE'|'DOCUMENT'}
export interface Submission {attachments?:SubmissionAttachment[];id:string;assignment_id:string;student_id:string;submitted_at:number;received_at:number|null;score:number|null;teacher_feedback:string|null;updated_at:number;sync_status?:string}
export interface Assignment {id:string;title:string;instructions:string|null;max_points:number;due_date:number}
const object=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null;
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v);
const integer=(v:unknown):v is number=>Number.isSafeInteger(v)&&Number(v)>=0;
export function scoreValue(input:string,maxPoints:number):number|null {
 if(input.trim()==='')return null;
 if(!integer(maxPoints)||!/^\d+$/.test(input)||!integer(Number(input))||Number(input)>maxPoints)throw new ApiError('invalid_fields');
 return Number(input);
}
export function parseSubmissions(value:unknown,assignmentId:string):Submission[] {
 if(!Array.isArray(value)||value.length>10000)throw new ApiError('invalid_response');
 const learners=new Set<string>(),ids=new Set<string>();
 return value.map(row=>{
  if(!object(row)||!uuid(row.id)||!uuid(row.student_id)||row.assignment_id!==assignmentId||ids.has(row.id)||learners.has(row.student_id)
   ||!integer(row.submitted_at)||!integer(row.updated_at)||(row.received_at!=null&&!integer(row.received_at))
   ||(row.score!==null&&!integer(row.score))||(row.teacher_feedback!==null&&typeof row.teacher_feedback!=='string'))throw new ApiError('invalid_response');
  if(row.attachments!==undefined&&(!Array.isArray(row.attachments)||row.attachments.length>5||!row.attachments.every(file=>object(file)&&typeof file.name==='string'&&file.name.length>0&&file.name.length<=4096&&['IMAGE','DOCUMENT'].includes(String(file.file_type)))))throw new ApiError('invalid_response');
  ids.add(row.id);learners.add(row.student_id);
  return {...(row.attachments?{attachments:row.attachments as SubmissionAttachment[]}:{}),id:row.id,assignment_id:assignmentId,student_id:row.student_id,submitted_at:row.submitted_at,received_at:row.received_at as number|null??null,score:row.score,teacher_feedback:row.teacher_feedback,updated_at:row.updated_at,...(typeof row.sync_status==='string'?{sync_status:row.sync_status}:{})};
 });
}
function teacher(account:CachedAccount,classId:string,id:string,write=false) {
 const room=account.classrooms.find(row=>row.id===classId&&(row.teacher_id===account.user.id||row.co_teacher_ids?.includes(account.user.id)));
 if(account.user.role!=='TEACHER'||!room||!uuid(id)||(write&&room.archived_at!=null))throw new ApiError('FORBIDDEN');
}
export async function readAssignmentWork(account:CachedAccount,assignmentId:string):Promise<Submission[]> {
 if(!uuid(assignmentId))throw new ApiError('invalid_fields');
 return parseSubmissions(await withDatabaseTimeout(invoke('read_assignment_work',{hub:account.hub,userId:account.user.id,assignmentId})),assignmentId);
}
export async function fetchSubmissions(account:CachedAccount,classId:string,assignmentId:string,signal?:AbortSignal):Promise<Submission[]> {
 teacher(account,classId,assignmentId);
 return parseSubmissions(await requestHub(account.hub,`/api/assignments/${assignmentId}/submissions`,{token:await accountToken(account),signal}),assignmentId);
}
export async function publishGrade(account:CachedAccount,classId:string,id:string,score:number,teacher_feedback:string,maxPoints:number,expectedUpdatedAt:number,signal?:AbortSignal):Promise<unknown> {
 teacher(account,classId,id,true);
 if(!integer(maxPoints)||!integer(score)||score>maxPoints||!integer(expectedUpdatedAt)||teacher_feedback.length>10000)throw new ApiError('invalid_fields');
 return requestHub(account.hub,`/api/submissions/${id}/grade-edit`,{method:'PATCH',body:{score,teacher_feedback,expected_updated_at:expectedUpdatedAt},token:await accountToken(account),signal});
}
