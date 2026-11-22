import {beforeEach,expect,it,vi} from 'vitest';
import type {CachedAccount} from '../src/services/auth';
const {invoke,requestHub,accountToken}=vi.hoisted(()=>({invoke:vi.fn(),requestHub:vi.fn(),accountToken:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke}));
vi.mock('../src/services/api',async original=>({...await original<typeof import('../src/services/api')>(),requestHub}));
vi.mock('../src/services/auth',async original=>({...await original<typeof import('../src/services/auth')>(),accountToken}));
import {sendComment,parseThread,readPrivateUnread,markPrivateCommentsRead} from '../src/services/comments';
const userId='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',parentId='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',studentId='cccccccc-cccc-4ccc-8ccc-cccccccccccc',messageId='dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const account:CachedAccount={hub:{name:'QA',ip:'127.0.0.1',http_port:8080,ws_port:8081},user:{id:userId,lrn_or_id:'synthetic',full_name:'QA',role:'STUDENT'},classrooms:[],persistent:false,expires_at:1};
beforeEach(()=>{vi.clearAllMocks();invoke.mockResolvedValue({items:[],write_mode:'queue'});accountToken.mockResolvedValue('synthetic-token')});
it('queues the same public comment ID with native account scope and no author override',async()=>{
 const target={kind:'announcement' as const,id:parentId};await sendComment(account,target,messageId,' Hello ',true);await sendComment(account,target,messageId,' Hello ',true);
 expect(invoke).toHaveBeenNthCalledWith(1,'queue_comment',{hub:account.hub,userId,target,id:messageId,content:'Hello'});expect(invoke).toHaveBeenNthCalledWith(2,'queue_comment',{hub:account.hub,userId,target,id:messageId,content:'Hello'});expect(requestHub).not.toHaveBeenCalled();
});
it('rejects a learner attempt to select a foreign private thread before IPC',async()=>{
 await expect(sendComment(account,{kind:'assignment',id:parentId,student_id:studentId},messageId,'No',false)).rejects.toThrow('comment_invalid');expect(invoke).not.toHaveBeenCalled();
});
it('does not queue teacher private replies offline',async()=>{
 const teacher={...account,user:{...account.user,role:'TEACHER' as const}};
 await expect(sendComment(teacher,{kind:'assignment',id:parentId,student_id:studentId},messageId,'Reply',true)).rejects.toThrow('comment_online_required');expect(invoke).not.toHaveBeenCalled();expect(requestHub).not.toHaveBeenCalled();
});
it('posts a teacher reply only after native thread authorization and validates the recipient',async()=>{
 const teacher={...account,user:{...account.user,role:'TEACHER' as const}},target={kind:'assignment' as const,id:parentId,student_id:studentId};invoke.mockResolvedValue({items:[],write_mode:'online'});requestHub.mockResolvedValue({id:messageId,assignment_id:parentId,student_id:studentId,author_id:userId,content:'Reply',created_at:1});
 expect(await sendComment(teacher,target,messageId,'Reply',false)).toBe(null);expect(invoke).toHaveBeenCalledWith('read_comment_thread',{hub:account.hub,userId,target});expect(requestHub).toHaveBeenCalledWith(account.hub,`/api/assignments/${parentId}/private-comments`,expect.objectContaining({body:{content:'Reply',student_id:studentId}}));
 requestHub.mockResolvedValue({id:messageId,assignment_id:parentId,student_id:userId,author_id:userId,content:'Reply',created_at:1});await expect(sendComment(teacher,target,messageId,'Reply',false)).rejects.toThrow('invalid_response');
});
it('rejects malformed cached comment statuses',()=>{expect(()=>parseThread({write_mode:'queue',items:[{id:messageId,author_id:userId,content:'x',created_at:1,sync_status:'SENT'}]})).toThrow('invalid_response')});
it('rejects malformed comment bodies and invalid dates from cached threads',()=>{
 const row={id:messageId,author_id:userId,content:'\u0000',created_at:1,sync_status:'SYNCED'};
 expect(()=>parseThread({write_mode:'queue',items:[row]})).toThrow('invalid_response');
 expect(()=>parseThread({write_mode:'queue',items:[{...row,content:'hello',created_at:8640000000000001}]})).toThrow('invalid_response');
});

it('keeps private unread counts and read receipts in native account scope without Hub writes',async()=>{
 const teacher={...account,user:{...account.user,role:'TEACHER' as const}};
 invoke.mockResolvedValue({[parentId]:{[studentId]:2}});expect(await readPrivateUnread(teacher,parentId)).toEqual({[parentId]:{[studentId]:2}});
 expect(invoke).toHaveBeenLastCalledWith('read_private_comment_unread',{hub:teacher.hub,userId,classroomId:parentId});
 await markPrivateCommentsRead(teacher,{kind:'assignment',id:parentId,student_id:studentId},[messageId]);
 expect(invoke).toHaveBeenLastCalledWith('mark_private_comments_read',{hub:teacher.hub,userId,target:{kind:'assignment',id:parentId,student_id:studentId},commentIds:[messageId]});expect(requestHub).not.toHaveBeenCalled();
 invoke.mockClear();expect(await readPrivateUnread(account,parentId)).toEqual({});await expect(markPrivateCommentsRead(account,{kind:'assignment',id:parentId},[messageId])).rejects.toThrow('comment_invalid');expect(invoke).not.toHaveBeenCalled();
 invoke.mockResolvedValue({[parentId]:{[studentId]:-1}});await expect(readPrivateUnread(teacher,parentId)).rejects.toThrow('invalid_response');
});