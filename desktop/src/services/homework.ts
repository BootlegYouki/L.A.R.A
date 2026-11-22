import {invoke} from '@tauri-apps/api/core';
import type {CachedAccount} from './auth';
const args=(account:CachedAccount)=>({hub:account.hub,userId:account.user.id});
export interface HomeworkStatus {id:string;status:'QUEUED_FOR_SYNC'|'SYNCED'|'REJECTED';reason:string|null}
export async function queueHomework(account:CachedAccount,assignmentId:string,file:File):Promise<void> {
 if(account.user.role!=='STUDENT'||!file.size||file.size>250*1024*1024||!/^.+\.(jpe?g|png|pdf|docx)$/i.test(file.name))throw new Error('invalid_homework');
 const jobId=await invoke<string>('begin_homework',{...args(account),assignmentId,fileName:file.name,size:file.size});
 try {
  for(let offset=0;offset<file.size;offset+=65536) {
   const data=Array.from(new Uint8Array(await file.slice(offset,offset+65536).arrayBuffer()));
   await invoke('append_homework',{...args(account),jobId,offset,data});
  }
  await invoke('finish_homework',{...args(account),jobId});
 }catch(error){await invoke('discard_homework_stage',{...args(account),jobId}).catch(()=>{});throw error;}
}
export const readHomework=(account:CachedAccount,assignmentId:string)=>invoke<HomeworkStatus|null>('read_homework_status',{...args(account),assignmentId});
export const flushHomework=(account:CachedAccount)=>invoke<number>('flush_homework',args(account));
