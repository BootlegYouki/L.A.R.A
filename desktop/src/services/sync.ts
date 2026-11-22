import {invoke} from '@tauri-apps/api/core';
import {ApiError,requestHub} from './api';
import {accountToken,forgetAccount,type CachedAccount} from './auth';
import {withDatabaseTimeout} from './db';

export interface SyncState {cursor: number; hub_id?: string; sync_epoch?: number}
export type SyncPush = Record<'quiz_attempts'|'comments'|'private_comments', Record<string,unknown>[]>;
const object=(v:unknown):v is Record<string,unknown>=>typeof v==='object'&&v!==null&&!Array.isArray(v);
const integer=(v:unknown):v is number=>typeof v==='number'&&Number.isSafeInteger(v)&&v>=0;
const hubId=(v:unknown):v is string=>typeof v==='string'&&v.length>0&&v.length<=256;
function state(v:unknown):SyncState {
  if(!object(v)||!integer(v.cursor)||(v.hub_id!==undefined&&!hubId(v.hub_id))
    ||(v.sync_epoch!==undefined&&!integer(v.sync_epoch))||(v.hub_id===undefined)!==(v.sync_epoch===undefined))throw new ApiError('invalid_response');
  return {cursor:v.cursor,...(typeof v.hub_id==='string'?{hub_id:v.hub_id,sync_epoch:Number(v.sync_epoch)}:{})};
}
// Native validates every nested wire record before opening its atomic transaction.
export function parseSyncPage(v:unknown,expected:SyncState) {
  if(!object(v)||!integer(v.server_time)||!hubId(v.hub_id)||!integer(v.sync_epoch)||!integer(v.next_cursor)
    ||typeof v.has_more!=='boolean'||typeof v.reset!=='boolean'
    ||(!v.reset&&(v.next_cursor<expected.cursor||(expected.hub_id!==undefined&&(expected.hub_id!==v.hub_id||expected.sync_epoch!==v.sync_epoch))))
    ||(v.has_more&&v.next_cursor<=(v.reset?0:expected.cursor)))throw new ApiError('invalid_response');
  return {...v,hub_id:v.hub_id,sync_epoch:v.sync_epoch,next_cursor:v.next_cursor,has_more:v.has_more,reset:v.reset};
}
function queue(v:unknown):SyncPush {
  if(!object(v))throw new ApiError('invalid_response');
  const rows=(key:string)=>{const a=v[key];if(!Array.isArray(a)||!a.every(object))throw new ApiError('invalid_response');return a;};
  return {quiz_attempts:rows('quiz_attempts'),comments:rows('comments'),private_comments:rows('private_comments')};
}
export function sameAccountSnapshot(left:CachedAccount|null,right:CachedAccount|null):boolean {
 // Sync's visible-class set and roster maps have no presentation ordering.
 const normalize=(value:CachedAccount|null)=>value&&({...value,
  classrooms:value.classrooms.map(room=>({...room,...(room.co_teacher_ids?{co_teacher_ids:[...room.co_teacher_ids].sort()}:{})})).sort((a,b)=>a.id.localeCompare(b.id)),
  ...(value.join_requests?{join_requests:[...value.join_requests].sort((a,b)=>a.classroom_id.localeCompare(b.classroom_id))}:{}),
  ...(value.rosters?{rosters:Object.fromEntries(Object.entries(value.rosters).map(([id,rows])=>[id,[...rows].sort((a,b)=>a.enrollment_id.localeCompare(b.enrollment_id))]))}:{})
 });
 const snapshot=(value:CachedAccount|null)=>JSON.stringify(normalize(value),(_key,row)=>object(row)?Object.fromEntries(Object.entries(row).sort(([a],[b])=>a.localeCompare(b))):row);
 return snapshot(left)===snapshot(right);
}
export function pageContentChanged(payload:Record<string,unknown>):boolean {
 return payload.reset===true||Object.entries(payload).some(([key,value])=>{
  if(!Array.isArray(value)||!value.length||['users','comments','private_comments'].includes(key))return false;
  return key!=='deleted'||value.some(row=>!object(row)||!['announcement_comments','private_comments'].includes(String(row.entity_table)));
 });
}
function commentsChanged(payload:Record<string,unknown>):boolean {
 return ['comments','private_comments'].some(key=>Array.isArray(payload[key])&&payload[key].length>0)||(Array.isArray(payload.deleted)&&payload.deleted.some(row=>object(row)&&['announcement_comments','private_comments'].includes(String(row.entity_table))));
}
export function observeSync(account:CachedAccount,onCommit:(contentChanged:boolean,commentsChanged?:boolean)=>void|Promise<void>,onError:(error:unknown)=>void) {
  let stopped=false,running=false,requested=false,failures=0,notBefore=0;
  let retry:ReturnType<typeof setTimeout>|undefined;
  const waiters=new Set<()=>void>();
  const settle=()=>{for(const done of waiters)done();waiters.clear()};
  const request=new AbortController(),args={hub:account.hub,userId:account.user.id};
  const native=(cmd:string,extra:Record<string,unknown>={})=>withDatabaseTimeout(invoke(cmd,{...args,...extra}));
  async function pull(token:string) {
    let expected=state(await native('read_sync_state'));
    while(!stopped) {
      const payload=parseSyncPage(await requestHub(account.hub,'/api/sync/pull',{body:expected,token,signal:request.signal}),expected);
      if(stopped)return;
      await native('apply_sync_pull',{expected,payload});
      if(stopped)return;
      await onCommit(pageContentChanged(payload),commentsChanged(payload));
      expected={cursor:payload.next_cursor,hub_id:payload.hub_id,sync_epoch:payload.sync_epoch};
      if(!payload.has_more)return;
    }
  }
  async function run() {
    if(stopped||running||Date.now()<notBefore)return;
    running=true;requested=false;
    try {
      const token=await accountToken(account);
      if(stopped)return;
      await pull(token);
      if(stopped)return;
      const sent=queue(await native('read_sync_queue'));
      if(stopped)return;
      if(Object.values(sent).some(rows=>rows.length>0)) {
        const payload=await requestHub(account.hub,'/api/sync/push',{body:sent,token,signal:request.signal});
        if(stopped)return;
        await native('apply_sync_receipts',{sent,payload});
        if(stopped)return;
        await onCommit(sent.quiz_attempts.length>0,sent.comments.length+sent.private_comments.length>0);await pull(token);
      }
      failures=0;notBefore=0;
    } catch(error) {
      if(stopped)return;
      if(error instanceof ApiError&&['UNAUTHORIZED','reauth_required'].includes(error.code)) {
        stop();await forgetAccount(account).catch(()=>{});onError(error);return;
      }
      const delay=error instanceof ApiError&&error.retryAfter>0?error.retryAfter*1000:Math.min(1000*2**Math.min(failures++,4),10000);
      notBefore=Date.now()+delay;clearTimeout(retry);retry=setTimeout(trigger,delay);onError(error);
    } finally {running=false;if(requested&&!stopped&&Date.now()>=notBefore)void run();else settle();}
  }
  function trigger(){if(stopped)return;requested=true;void run();}
  function refresh():Promise<void>{
    if(stopped||Date.now()<notBefore)return Promise.resolve();
    return new Promise(resolve=>{waiters.add(resolve);trigger()});
  }
  const timer=setInterval(trigger,60000);
  function stop(){stopped=true;request.abort();clearTimeout(retry);clearInterval(timer);settle();}
  trigger();return {trigger,refresh,stop};
}
