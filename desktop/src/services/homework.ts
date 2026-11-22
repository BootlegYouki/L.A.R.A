import {invoke} from '@tauri-apps/api/core';
import type {CachedAccount} from './auth';
const args=(account:CachedAccount)=>({hub:account.hub,userId:account.user.id});
export interface HomeworkStatus {id:string;status:'QUEUED_FOR_SYNC'|'SYNCED'|'REJECTED';reason:string|null}
export async function queueHomework(account:CachedAccount,assignmentId:string,files:File[]):Promise<void> {
 if(account.user.role!=='STUDENT'||!files.length||files.length>5||files.some(file=>!file.size||!/^.+\.(jpe?g|png|pdf|docx)$/i.test(file.name))||files.reduce((sum,file)=>sum+file.size,0)>250*1024*1024)throw new Error('invalid_homework');
 const jobId=await invoke<string>('begin_homework_batch',{...args(account),assignmentId,attachments:files.map(file=>({fileName:file.name,size:file.size}))});
 try {
  for(let index=0;index<files.length;index++)for(let offset=0;offset<files[index].size;offset+=65536) {
   const data=Array.from(new Uint8Array(await files[index].slice(offset,offset+65536).arrayBuffer()));
   await invoke('append_homework_batch',{...args(account),jobId,attachmentIndex:index,offset,data});
  }
  await invoke('finish_homework_batch',{...args(account),jobId});
 }catch(error){await invoke('discard_homework_stage',{...args(account),jobId}).catch(()=>{});throw error;}
}
export const readHomework=(account:CachedAccount,assignmentId:string)=>invoke<HomeworkStatus|null>('read_homework_status',{...args(account),assignmentId});
export const flushHomework=(account:CachedAccount)=>invoke<number>('flush_homework',args(account));
