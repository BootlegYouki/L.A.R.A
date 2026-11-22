import { invoke } from '@tauri-apps/api/core';
import type { CachedAccount } from './auth';

const WAIT_MS = 10_000;
const POLL_MS = 100;
const cancelled = (signal?: AbortSignal) => signal?.reason ?? new Error('cancelled');
function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>{signal?.removeEventListener('abort',abort);resolve();},ms);
    const abort=()=>{clearTimeout(timer);reject(cancelled(signal));};
    signal?.addEventListener('abort',abort,{once:true});
  });
}

export async function waitForCachedAssignment(account: CachedAccount, classroomId: string, assignmentId: string, signal?: AbortSignal, kind: 'assignments' | 'announcements' = 'assignments'): Promise<void> {
  if(signal?.aborted)throw cancelled(signal);
  let expired=false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let abort!: () => void;
  const stopped=new Promise<never>((_,reject)=>{
    timer=setTimeout(()=>{expired=true;reject(new Error('Assignment did not appear in the class cache within 10 seconds.'));},WAIT_MS);
    abort=()=>reject(cancelled(signal));
    signal?.addEventListener('abort',abort,{once:true});
    if(signal?.aborted)abort();
  });
  const poll=async()=>{
    while(true) {
      if(signal?.aborted)throw cancelled(signal);
      if(expired)throw new Error('Assignment did not appear in the class cache within 10 seconds.');
      let content:{assignments?:{id:string}[];announcements?:{id:string}[]};
      try { content=await invoke<{assignments?:{id:string}[];announcements?:{id:string}[]}>('read_class_content',{hub:account.hub,userId:account.user.id,classroomId}); }
      catch { await delay(POLL_MS,signal); continue; }
      if(signal?.aborted)throw cancelled(signal);
      if(expired)throw new Error('Assignment did not appear in the class cache within 10 seconds.');
      const items=content[kind];
      if(!Array.isArray(items))throw new Error('Invalid cached class content.');
      if(items.some(item=>item.id===assignmentId))return;
      await delay(POLL_MS,signal);
    }
  };
  try { await Promise.race([poll(),stopped]); }
  finally { clearTimeout(timer); signal?.removeEventListener('abort',abort); }
}
