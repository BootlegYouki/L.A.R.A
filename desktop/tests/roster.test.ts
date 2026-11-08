import { afterEach, expect, it, vi } from 'vitest';
const {fetch,invoke}=vi.hoisted(()=>({fetch:vi.fn(),invoke:vi.fn()}));
vi.mock('@tauri-apps/plugin-http',()=>({fetch}));vi.mock('@tauri-apps/api/core',()=>({invoke}));
import {createClassroom,refreshRoster,decideEnrollment,changeClassroom} from '../src/services/classrooms';
const uid='11111111-1111-4111-8111-111111111111',cid='22222222-2222-4222-8222-222222222222',sid='33333333-3333-4333-8333-333333333333';
const hub={name:'Test',ip:'127.0.0.1',http_port:8080,ws_port:8081};
const classroom={id:cid,name:'Science',section:'A',class_code:'K7M4QX',teacher_id:uid};
const account={hub,user:{id:uid,full_name:'Teacher',lrn_or_id:'T-0001',role:'TEACHER' as const},expires_at:Date.now()+60000,persistent:true,classrooms:[classroom]};
const row={enrollment_id:'44444444-4444-4444-8444-444444444444',student_id:sid,full_name:'Synthetic learner',status:'PENDING',joined_at:1};
const response=(path:string,payload:unknown,status=200)=>({url:`http://127.0.0.1:8080${path}`,status,headers:new Headers(),json:async()=>payload});
afterEach(()=>vi.clearAllMocks());
it('caches a teacher roster with exact server enrollment IDs and no other learner LRN or private fields',async()=>{
 invoke.mockResolvedValueOnce('test-token').mockResolvedValueOnce([row]);fetch.mockResolvedValue(response(`/api/classrooms/${cid}/roster`,[{...row,lrn:'private-lrn',pin_hash:'secret'}]));
 await expect(refreshRoster(account,cid)).resolves.toEqual([row]);
 expect(invoke).toHaveBeenLastCalledWith('save_roster_snapshot',{hub,userId:uid,classroomId:cid,roster:[row]});
});
it('rejects learner, unknown classroom, malformed route IDs and duplicate students before caching',async()=>{
 await expect(refreshRoster({...account,user:{...account.user,role:'STUDENT'}},cid)).rejects.toMatchObject({code:'FORBIDDEN'});
 await expect(refreshRoster(account,sid)).rejects.toMatchObject({code:'FORBIDDEN'});
 await expect(decideEnrollment(account,'../escape',sid,'approve')).rejects.toMatchObject({code:'FORBIDDEN'});
 expect(fetch).not.toHaveBeenCalled();
 invoke.mockResolvedValue('test-token');fetch.mockResolvedValue(response(`/api/classrooms/${cid}/roster`,[row,row]));
 await expect(refreshRoster(account,cid)).rejects.toMatchObject({code:'invalid_response'});
 expect(invoke).not.toHaveBeenCalledWith('save_roster_snapshot',expect.anything());
});
it('sends a decision once and preserves failures without inventing a local accepted state',async()=>{
 invoke.mockResolvedValue('test-token');fetch.mockResolvedValue(response(`/api/classrooms/${cid}/remove`,{error:{code:'NOT_IMPLEMENTED'}},501));
 await expect(decideEnrollment(account,cid,sid,'remove')).rejects.toMatchObject({code:'NOT_IMPLEMENTED'});
 expect(fetch).toHaveBeenCalledTimes(1);expect(invoke).not.toHaveBeenCalledWith('save_roster_snapshot',expect.anything());
});
it('creates and caches the canonical classroom/code without replaying a successful POST',async()=>{
 invoke.mockResolvedValueOnce('test-token').mockResolvedValueOnce([classroom]);fetch.mockResolvedValue(response('/api/classrooms',classroom));
 await expect(createClassroom({...account,classrooms:[]},' Science ',' A ')).resolves.toMatchObject({classroom,classrooms:[classroom]});
 expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/api/classrooms',expect.objectContaining({body:JSON.stringify({name:'Science',section:'A'})}));expect(fetch).toHaveBeenCalledTimes(1);
});
it('invalidates only a forbidden class cache and preserves the original authorization error',async()=>{
 invoke.mockResolvedValueOnce('test-token').mockRejectedValueOnce(new Error('synthetic cache failure'));
 fetch.mockResolvedValue(response(`/api/classrooms/${cid}/roster`,{error:{code:'FORBIDDEN'}},403));
 await expect(refreshRoster(account,cid)).rejects.toMatchObject({code:'FORBIDDEN'});
 expect(invoke).toHaveBeenLastCalledWith('clear_roster_snapshot',{hub,userId:uid,classroomId:cid});
});
it('keeps a successful creation receipt when local caching fails, so the UI cannot invite a duplicate POST',async()=>{
 invoke.mockResolvedValueOnce('test-token').mockRejectedValueOnce(new Error('synthetic disk failure'));
 fetch.mockResolvedValue(response('/api/classrooms',classroom));
 await expect(createClassroom({...account,classrooms:[]},'Science','A')).resolves.toMatchObject({classroom,classrooms:[],cacheSaved:false});
 expect(fetch).toHaveBeenCalledTimes(1);
});

it('uses the owner-only class routes and accepts only a receipt for the selected class', async () => {
  const signal = new AbortController().signal;
  const action = {kind: 'settings', change: {archived: true}} as const;
  const path = '/api/classrooms/' + cid;
  const coTeacher = {...classroom, teacher_id: sid, co_teacher_ids: [uid]};
  await expect(changeClassroom({...account, classrooms: [coTeacher]}, cid, action, signal)).rejects.toMatchObject({code: 'FORBIDDEN'});
  await expect(changeClassroom({...account, classrooms: [{...classroom, archived_at: 1}]}, cid, action, signal)).rejects.toMatchObject({code: 'FORBIDDEN'});
  expect(fetch).not.toHaveBeenCalled();

  invoke.mockResolvedValue('test-token');
  fetch.mockResolvedValueOnce(response(path, {...classroom, id: sid}));
  await expect(changeClassroom(account, cid, action, signal)).rejects.toMatchObject({code: 'invalid_response'});
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:8080' + path, expect.objectContaining({method: 'PATCH', body: '{"archived":true}'}));

  const updated = {...classroom, co_teacher_ids: [sid]};
  fetch.mockResolvedValueOnce(response(path + '/teachers', updated));
  await expect(changeClassroom(account, cid, {kind: 'add', teacherId: ' T-0002 '}, signal)).resolves.toEqual(updated);
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:8080' + path + '/teachers', expect.objectContaining({method: 'POST', body: '{"lrn_or_id":"T-0002"}'}));

  fetch.mockResolvedValueOnce(response(path + '/teachers/' + sid, {success: true}));
  await expect(changeClassroom({...account, classrooms: [updated]}, cid, {kind: 'remove', teacherId: sid}, signal)).resolves.toBeNull();
  expect(fetch).toHaveBeenLastCalledWith('http://127.0.0.1:8080' + path + '/teachers/' + sid, expect.objectContaining({method: 'DELETE'}));
});
