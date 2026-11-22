import { beforeEach, expect, it, vi } from 'vitest';
import type { CachedAccount } from '../src/services/auth';
const {requestHub, accountToken, forgetAccount} = vi.hoisted(() => ({requestHub: vi.fn(), accountToken: vi.fn(), forgetAccount: vi.fn()}));
vi.mock('../src/services/api', async importOriginal => ({...await importOriginal<typeof import('../src/services/api')>(), requestHub}));
vi.mock('../src/services/auth', async importOriginal => ({...await importOriginal<typeof import('../src/services/auth')>(), accountToken, forgetAccount}));
import { deleteClasswork, updateClasswork } from '../src/services/classworkMutations';

const id = '11111111-1111-4111-8111-111111111111';
const itemId = '22222222-2222-4222-8222-222222222222';
const topicId = '33333333-3333-4333-8333-333333333333';
const account: CachedAccount = {hub:{name:'Hub',ip:'192.168.1.2',http_port:8080,ws_port:8081},user:{id,lrn_or_id:'T-1',full_name:'Teacher',role:'TEACHER'},expires_at:9999999999999,persistent:true,classrooms:[{id,name:'Class',section:'A',class_code:'K7M4QX',teacher_id:id}]};
beforeEach(() => { vi.clearAllMocks(); accountToken.mockResolvedValue('secret-token'); forgetAccount.mockResolvedValue(undefined); requestHub.mockResolvedValue({id:itemId,classroom_id:id}); });

it('patches a full assignment, preserving multiline instructions and clearing its topic', async () => {
  await updateClasswork(account,id,'assignment',itemId,{title:'Work',instructions:'Line one\nLine two',max_points:10,due_date:100,allow_late:false,topic_id:null});
  expect(requestHub).toHaveBeenCalledWith(account.hub,`/api/assignments/${itemId}`,{body:{title:'Work',instructions:'Line one\nLine two',max_points:10,due_date:100,allow_late:false,topic_id:null},method:'PATCH',token:'secret-token'});
});
it('patches topic ordering and nullable material topics', async () => {
  await updateClasswork(account,id,'topic',itemId,{name:'Unit 1',order_index:2});
  expect(requestHub.mock.calls[0][2].body).toEqual({name:'Unit 1',order_index:2});
  await updateClasswork(account,id,'material',itemId,{title:'Handout',topic_id:null});
  expect(requestHub.mock.calls[1][2].body).toEqual({title:'Handout',topic_id:null});
});
it('rejects invalid fields and archived or foreign classes before network access', async () => {
  await expect(updateClasswork(account,id,'assignment',itemId,{title:'Work',instructions:'',max_points:0,due_date:1,allow_late:false,topic_id:topicId})).rejects.toMatchObject({code:'invalid_fields'});
  const archived = {...account,classrooms:[{...account.classrooms[0],archived_at:1}]};
  await expect(deleteClasswork(archived,id,'topic',itemId)).rejects.toMatchObject({code:'FORBIDDEN'});
  await expect(deleteClasswork(account,topicId,'topic',itemId)).rejects.toMatchObject({code:'FORBIDDEN'});
  expect(requestHub).not.toHaveBeenCalled();
});
it('rejects a mutation receipt from another classroom and allows valid delete receipts', async () => {
  requestHub.mockResolvedValueOnce({id:itemId,classroom_id:topicId});
  await expect(updateClasswork(account,id,'material',itemId,{title:'Handout',topic_id:null})).rejects.toMatchObject({code:'invalid_response'});
  requestHub.mockResolvedValueOnce({success:true});
  await expect(deleteClasswork(account,id,'topic',itemId)).resolves.toBeUndefined();
  expect(requestHub.mock.calls[1][2]).toEqual({method:'DELETE',token:'secret-token'});
  requestHub.mockResolvedValueOnce({ok:true});
  await expect(deleteClasswork(account,id,'topic',itemId)).rejects.toMatchObject({code:'invalid_response'});
});
it('forgets an expired account after an unauthorized mutation', async () => {
  const {ApiError} = await import('../src/services/api');
  requestHub.mockRejectedValueOnce(new ApiError('UNAUTHORIZED'));
  await expect(deleteClasswork(account,id,'assignment',itemId)).rejects.toMatchObject({code:'UNAUTHORIZED'});
  expect(forgetAccount).toHaveBeenCalledWith(account);
});
