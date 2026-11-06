import { afterEach, expect, it, vi } from 'vitest';
const { fetch, invoke } = vi.hoisted(() => ({ fetch: vi.fn(), invoke: vi.fn() }));
vi.mock('@tauri-apps/plugin-http', () => ({ fetch }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
import { joinClass } from '../src/services/classrooms';
const hub = { name: 'Hub', ip: '127.0.0.1', http_port: 8080, ws_port: 8081 };
const id = '11111111-1111-4111-8111-111111111111';
const account = { hub, user: { id, lrn_or_id: 'synthetic', full_name: 'Test', role: 'STUDENT' as const }, expires_at: Date.now()+60000, persistent:true, classrooms:[] };
const receipt = {classroom_id:'22222222-2222-4222-8222-222222222222',status:'PENDING'};
afterEach(()=>vi.clearAllMocks());
it('posts a normalized code once and persists the exact receipt without inventing enrollment metadata',async()=>{
 invoke.mockResolvedValueOnce('test-token').mockResolvedValueOnce({...account,join_requests:[receipt]});
 fetch.mockResolvedValue({status:200,url:'http://127.0.0.1:8080/api/classrooms/join',headers:new Headers(),json:async()=>receipt});
 await expect(joinClass(account,' k7m-4qx ')).resolves.toMatchObject({join_requests:[receipt]});
 expect(fetch).toHaveBeenCalledTimes(1);
 expect(fetch).toHaveBeenCalledWith('http://127.0.0.1:8080/api/classrooms/join',expect.objectContaining({body:JSON.stringify({class_code:'K7M4QX'}),headers:expect.objectContaining({Authorization:'Bearer test-token'})}));
 expect(invoke).toHaveBeenLastCalledWith('save_join_status',{hub,userId:id,receipt});
});
it('rejects teacher, invalid code and malformed receipt before saving membership',async()=>{
 await expect(joinClass({...account,user:{...account.user,role:'TEACHER'}},'K7M4QX')).rejects.toMatchObject({code:'FORBIDDEN'});
 await expect(joinClass(account,'O00111')).rejects.toMatchObject({code:'invalid_code'});
 expect(fetch).not.toHaveBeenCalled();
 invoke.mockResolvedValue('test-token');
 fetch.mockResolvedValue({status:200,url:'http://127.0.0.1:8080/api/classrooms/join',headers:new Headers(),json:async()=>({...receipt,classroom_id:'../escape'})});
 await expect(joinClass(account,'K7M4QX')).rejects.toMatchObject({code:'invalid_response'});
 expect(invoke).not.toHaveBeenCalledWith('save_join_status',expect.anything());
});
it('keeps invalid-code feedback and cooldowns without retrying or saving a failed request',async()=>{
 invoke.mockResolvedValue('test-token');
 fetch.mockResolvedValue({status:404,url:'http://127.0.0.1:8080/api/classrooms/join',headers:new Headers(),json:async()=>({error:{code:'CLASS_CODE_INVALID'}})});
 await expect(joinClass(account,'K7M4QX')).rejects.toMatchObject({code:'CLASS_CODE_INVALID'});
 expect(fetch).toHaveBeenCalledTimes(1);
 expect(invoke).not.toHaveBeenCalledWith('save_join_status',expect.anything());
});
