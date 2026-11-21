import { beforeEach, describe, expect, it, vi } from 'vitest';
import { invoke } from '@tauri-apps/api/core';
import { enqueueUpload } from '../src/services/uploadQueue';
import type { CachedAccount } from '../src/services/auth';

vi.mock('@tauri-apps/api/core', () => ({ invoke: vi.fn() }));
const mockedInvoke = vi.mocked(invoke);
const userId='11111111-1111-4111-8111-111111111111';
const classId='22222222-2222-4222-8222-222222222222';
const account={hub:{name:'Hub',ip:'192.168.1.2',http_port:8080,ws_port:8081},user:{id:userId,role:'TEACHER',lrn_or_id:userId,full_name:'Teacher'},expires_at:1,persistent:true,
  classrooms:[{id:classId,teacher_id:userId,co_teacher_ids:[],name:'Class',section:'',class_code:'K7M4QX',archived_at:null}]} as CachedAccount;
beforeEach(()=>{mockedInvoke.mockReset();mockedInvoke.mockImplementation(async command=>{
  if(command==='begin_upload') return '33333333-3333-4333-8333-333333333333' as never;
  if(command==='finish_upload') return {id:'33333333-3333-4333-8333-333333333333',title:'Lesson',fileName:'lesson.pdf',fileSize:70000,status:'ready',uploadedBytes:70000} as never;
  return undefined as never;
});});

describe('durable material upload staging',()=>{
  it('sends source bytes in chunks no larger than 64 KiB before finishing and starting',async()=>{
    const file=new File([new Uint8Array(70000).fill(7)],'lesson.pdf',{type:'application/pdf'});
    const job=await enqueueUpload(account,{classroom_id:classId,title:' Lesson ',file_type:'DOCUMENT',file});
    const append=mockedInvoke.mock.calls.filter(([name])=>name==='append_upload');
    expect(append.map(([,args])=>(args as {data:number[]}).data.length)).toEqual([65536,4464]);
    expect(mockedInvoke.mock.calls.map(([name])=>name)).toEqual(['begin_upload','append_upload','append_upload','finish_upload','start_upload']);
    expect(job.status).toBe('ready');
  });

  it('keeps the finished durable job when an immediate start is unavailable',async()=>{
    mockedInvoke.mockImplementation(async command=>{
      if(command==='begin_upload') return '33333333-3333-4333-8333-333333333333' as never;
      if(command==='finish_upload') return {id:'33333333-3333-4333-8333-333333333333',title:'Lesson',fileName:'lesson.pdf',fileSize:1,status:'ready',uploadedBytes:1} as never;
      if(command==='start_upload') throw new Error('offline');
      return undefined as never;
    });
    const job=await enqueueUpload(account,{classroom_id:classId,title:'Lesson',file_type:'DOCUMENT',file:new File(['x'],'lesson.pdf')});
    expect(job.status).toBe('ready');
    expect(mockedInvoke).not.toHaveBeenCalledWith('cancel_upload',expect.anything());
  });
});
