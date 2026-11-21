import { beforeEach, expect, it, vi } from 'vitest';
import type { CachedAccount } from '../src/services/auth';
const {requestHub, accountToken} = vi.hoisted(() => ({requestHub: vi.fn(), accountToken: vi.fn()}));
vi.mock('../src/services/api', async (importOriginal) => ({...await importOriginal<typeof import('../src/services/api')>(), requestHub}));
vi.mock('../src/services/auth', async (importOriginal) => ({...await importOriginal<typeof import('../src/services/auth')>(), accountToken}));
import { createAnnouncement, createAssignment, createTopic } from '../src/services/authoring';
const id = '11111111-1111-4111-8111-111111111111';
const account: CachedAccount = {hub:{name:'Hub',ip:'192.168.1.2',http_port:8080,ws_port:8081},user:{id,lrn_or_id:'T-1',full_name:'Teacher',role:'TEACHER'},expires_at:9999999999999,persistent:true,classrooms:[{id,name:'Class',section:'A',class_code:'K7M4QX',teacher_id:id}]};
beforeEach(() => { vi.clearAllMocks(); accountToken.mockResolvedValue('secret-token'); requestHub.mockResolvedValue({id:'22222222-2222-4222-8222-222222222222',classroom_id:id}); });
it('creates an announcement with the account token and selected classroom', async () => {
 await createAnnouncement(account,id,{title:' Notice ',content:'Body',allow_comments:false});
 expect(requestHub).toHaveBeenCalledWith(account.hub,'/api/announcements',{body:{classroom_id:id,title:'Notice',content:'Body',allow_comments:false},token:'secret-token'});
});
it('rejects classwork when the selected class is archived', async () => {
 const archived = {...account,classrooms:[{...account.classrooms[0],archived_at:1}]};
 await expect(createTopic(archived,id,'Unit')).rejects.toMatchObject({code:'FORBIDDEN'});
 expect(requestHub).not.toHaveBeenCalled();
});
it('rejects invalid assignment bounds before requesting a token', async () => {
 await expect(createAssignment(account,id,{title:'Work',max_points:0,due_date:1})).rejects.toMatchObject({code:'invalid_fields'});
 expect(accountToken).not.toHaveBeenCalled();
});


it('preserves line breaks in announcement content', async () => {
 await createAnnouncement(account,id,{title:'Notice',content:'First line\nSecond line'});
 expect(requestHub.mock.calls[0][2].body.content).toBe('First line\nSecond line');
});
it('rejects a creation receipt for a different classroom', async () => {
 requestHub.mockResolvedValue({id:'22222222-2222-4222-8222-222222222222',classroom_id:'33333333-3333-4333-8333-333333333333'});
 await expect(createTopic(account,id,'Unit')).rejects.toMatchObject({code:'invalid_response'});
});
