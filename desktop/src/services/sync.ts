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
export function observeSync(account:CachedAccount,onCommit:()=>void|Promise<void>,onError:(error:unknown)=>void) {
  let stopped=false,running=false,requested=false,failures=0,notBefore=0;
  let retry:ReturnType<typeof setTimeout>|undefined;
  const request=new AbortController(),args={hub:account.hub,userId:account.user.id};
  const native=(cmd:string,extra:Record<string,unknown>={})=>withDatabaseTimeout(invoke(cmd,{...args,...extra}));
  async function pull(token:string) {
    let expected=state(await native('read_sync_state'));
    while(!stopped) {
      const payload=parseSyncPage(await requestHub(account.hub,'/api/sync/pull',{body:expected,token,signal:request.signal}),expected);
      if(stopped)return;
      await native('apply_sync_pull',{expected,payload});
      if(stopped)return;
      await onCommit();
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
        await onCommit();await pull(token);
      }
      failures=0;notBefore=0;
    } catch(error) {
      if(stopped)return;
      if(error instanceof ApiError&&['UNAUTHORIZED','reauth_required'].includes(error.code)) {
        stop();await forgetAccount(account).catch(()=>{});onError(error);return;
      }
      const delay=error instanceof ApiError&&error.retryAfter>0?error.retryAfter*1000:Math.min(1000*2**Math.min(failures++,4),10000);
      notBefore=Date.now()+delay;clearTimeout(retry);retry=setTimeout(trigger,delay);onError(error);
    } finally {running=false;if(requested&&!stopped&&Date.now()>=notBefore)void run();}
  }
  function trigger(){if(stopped)return;requested=true;void run();}
  const timer=setInterval(trigger,60000);
  function stop(){stopped=true;request.abort();clearTimeout(retry);clearInterval(timer);}
  trigger();return {trigger,stop};
}
