import {invoke} from '@tauri-apps/api/core';
import {ApiError,requestHub} from './api';
import {accountToken,hasControlCharacters,type CachedAccount} from './auth';
import {withDatabaseTimeout} from './db';
export type ThreadTarget={kind:'announcement';id:string}|{kind:'assignment';id:string;student_id?:string};
export interface Comment {id:string;author_id:string;author_name:string|null;content:string;created_at:number;sync_status:'SYNCED'|'QUEUED_FOR_SYNC'|'REJECTED';sync_reject_reason:string|null}
export interface ThreadData {items:Comment[];write_mode:'queue'|'online'|'readonly'}
const object=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null&&!Array.isArray(v);
const uuid=(v:unknown):v is string=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)&&v!=='00000000-0000-0000-0000-000000000000';
export function parseThread(value:unknown):ThreadData {
 if(!object(value)||(typeof value.write_mode!=='string'||!['queue','online','readonly'].includes(value.write_mode))||!Array.isArray(value.items))throw new ApiError('invalid_response');
 const items=value.items.map(row=>{
  if(!object(row)||!uuid(row.id)||!uuid(row.author_id)||typeof row.content!=='string'||!row.content.trim()||[...row.content].length>10000||hasControlCharacters(row.content.replace(/[\r\n\t]/g,''))||!Number.isSafeInteger(row.created_at)||(Number(row.created_at)<0||Number(row.created_at)>8640000000000000)||(typeof row.sync_status!=='string'||!['SYNCED','QUEUED_FOR_SYNC','REJECTED'].includes(row.sync_status))||(row.author_name!=null&&typeof row.author_name!=='string')||(row.sync_reject_reason!=null&&typeof row.sync_reject_reason!=='string'))throw new ApiError('invalid_response');
  return {id:row.id,author_id:row.author_id,author_name:row.author_name??null,content:row.content,created_at:Number(row.created_at),sync_status:row.sync_status,sync_reject_reason:row.sync_reject_reason??null} as Comment;
 });
 return {items,write_mode:value.write_mode as ThreadData['write_mode']};
}
function validateTarget(account:CachedAccount,target:ThreadTarget) {
 if(!uuid(target.id)||(target.kind==='assignment'&&target.student_id!==undefined&&!uuid(target.student_id))||(target.kind==='assignment'&&account.user.role==='STUDENT'&&target.student_id!==undefined&&target.student_id.toLowerCase()!==account.user.id.toLowerCase()))throw new ApiError('comment_invalid');
}
export async function readThread(account:CachedAccount,target:ThreadTarget):Promise<ThreadData> {
 validateTarget(account,target);
 return parseThread(await withDatabaseTimeout(invoke('read_comment_thread',{hub:account.hub,userId:account.user.id,target})));
}
export async function sendComment(account:CachedAccount,target:ThreadTarget,id:string,input:string,offline:boolean,signal?:AbortSignal):Promise<ThreadData|null> {
 validateTarget(account,target);const content=input.trim();
 if(!uuid(id)||!content||[...content].length>10000||hasControlCharacters(content.replace(/[\r\n\t]/g,'')))throw new ApiError('comment_invalid');
 if(target.kind==='assignment'&&account.user.role==='TEACHER') {
  if(offline)throw new ApiError('comment_online_required');
  if(!target.student_id)throw new ApiError('comment_invalid');
  const thread=await readThread(account,target);if(thread.write_mode!=='online')throw new ApiError('comment_read_only');
  const token=await accountToken(account);
  const value=await requestHub(account.hub,`/api/assignments/${target.id}/private-comments`,{body:{content,student_id:target.student_id},token,signal});
  if(!object(value)||!uuid(value.id)||value.assignment_id!==target.id||value.student_id!==target.student_id||value.author_id!==account.user.id||value.content!==content||!Number.isSafeInteger(value.created_at)||Number(value.created_at)<0||Number(value.created_at)>8640000000000000)throw new ApiError('invalid_response');
  return null;
 }
 return parseThread(await withDatabaseTimeout(invoke('queue_comment',{hub:account.hub,userId:account.user.id,target,id,content})));
}
