import {beforeEach,expect,it,vi} from 'vitest';
import type {CachedAccount} from '../src/services/auth';
const {requestHub,accountToken}=vi.hoisted(()=>({requestHub:vi.fn(),accountToken:vi.fn()}));
vi.mock('../src/services/api',async original=>({...await original<typeof import('../src/services/api')>(),requestHub}));
vi.mock('../src/services/auth',async original=>({...await original<typeof import('../src/services/auth')>(),accountToken}));
import {parseSubmissions,publishGrade,scoreValue} from '../src/services/grading';
const id='11111111-1111-4111-8111-111111111111',student='22222222-2222-4222-8222-222222222222';
const account:CachedAccount={hub:{ip:'127.0.0.1',name:'Hub',http_port:8080,ws_port:8081},user:{id,role:'TEACHER',full_name:'Teacher',lrn_or_id:'T-1'},persistent:true,expires_at:9999999999999,classrooms:[{id,teacher_id:id,name:'Class',section:'A',class_code:'K7M4QX'}]};
beforeEach(()=>{vi.clearAllMocks();accountToken.mockResolvedValue('token');});
it('accepts zero, extra credit and safe integers, rejects negative fractional and overflow scores',()=>{
 expect(scoreValue('0')).toBe(0);expect(scoreValue('105')).toBe(105);expect(scoreValue('')).toBe(null);
 for(const v of ['-1','1.5','1e2','9007199254740992']) expect(()=>scoreValue(v)).toThrow();
});
it('rejects a submission from another assignment or duplicate learner',()=>{
 const row={id,assignment_id:id,student_id:student,submitted_at:1,received_at:1,score:0,teacher_feedback:'Good',updated_at:1};
 expect(parseSubmissions([row],id)[0].score).toBe(0);
 expect(()=>parseSubmissions([{...row,assignment_id:student}],id)).toThrow();
 expect(()=>parseSubmissions([row,row],id)).toThrow();
});
it('publishes a valid grade through the checked-in route',async()=>{
 requestHub.mockResolvedValue({id,score:0,teacher_feedback:'Good'});
 await publishGrade(account,id,id,0,'Good');
 expect(requestHub).toHaveBeenCalledWith(account.hub,`/api/submissions/${id}`,expect.objectContaining({method:'PATCH',body:{score:0,teacher_feedback:'Good'}}));
});
it('blocks learner and archived teacher writes before requesting credentials',async()=>{
 await expect(publishGrade({...account,user:{...account.user,role:'STUDENT'}},id,id,1,'')).rejects.toThrow();
 await expect(publishGrade({...account,classrooms:[{...account.classrooms[0],archived_at:1}]},id,id,1,'')).rejects.toThrow();
 expect(accountToken).not.toHaveBeenCalled();
});
