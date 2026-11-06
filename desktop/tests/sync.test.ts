import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const mocks=vi.hoisted(()=>({invoke:vi.fn(),request:vi.fn(),token:vi.fn(),forget:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke:mocks.invoke}));
vi.mock('../src/services/api',async original=>({...await original<typeof import('../src/services/api')>(),requestHub:mocks.request}));
vi.mock('../src/services/auth',()=>({accountToken:mocks.token,forgetAccount:mocks.forget}));
import {observeSync,parseSyncPage} from '../src/services/sync';
import {ApiError} from '../src/services/api';
const hub={name:'Hub',ip:'127.0.0.1',http_port:8080,ws_port:8081};
const account={hub,user:{id:'11111111-1111-4111-8111-111111111111',full_name:'Teacher',lrn_or_id:'T-0001',role:'TEACHER' as const},persistent:true,expires_at:Date.now()+60000,classrooms:[]};
const page=(next_cursor=1,has_more=false)=>({server_time:100,hub_id:'22222222-2222-4222-8222-222222222222',sync_epoch:0,next_cursor,has_more,reset:false,announcements:[]});
const empty={quiz_attempts:[],comments:[],private_comments:[]};
beforeEach(()=>{vi.useFakeTimers();mocks.token.mockResolvedValue('synthetic-token');mocks.forget.mockResolvedValue(undefined);mocks.invoke.mockImplementation(async(cmd:string)=>cmd==='read_sync_state'?{cursor:0}:cmd==='read_sync_queue'?empty:undefined);mocks.request.mockResolvedValue(page());});
afterEach(()=>{vi.useRealTimers();vi.clearAllMocks()});
it('commits each page with its expected cursor and immediately follows has_more',async()=>{
 mocks.request.mockResolvedValueOnce(page(1,true)).mockResolvedValueOnce(page(2));const commit=vi.fn();const sync=observeSync(account,commit,vi.fn());
 await vi.advanceTimersByTimeAsync(0);expect(mocks.request).toHaveBeenCalledTimes(2);
 expect(mocks.invoke).toHaveBeenCalledWith('apply_sync_pull',{hub,userId:account.user.id,expected:{cursor:0},payload:page(1,true)});
 expect(mocks.request.mock.calls[1][2].body).toEqual({cursor:1,hub_id:page().hub_id,sync_epoch:0});expect(commit).toHaveBeenCalledTimes(2);sync.stop();
});
it('does not apply an interrupted response or a delayed response after disposal',async()=>{
 let release!:(v:unknown)=>void;mocks.request.mockImplementation(()=>new Promise(resolve=>release=resolve));const commit=vi.fn(),sync=observeSync(account,commit,vi.fn());await vi.advanceTimersByTimeAsync(0);sync.stop();release(page());await vi.advanceTimersByTimeAsync(0);
 expect(mocks.invoke.mock.calls.some(c=>c[0]==='apply_sync_pull')).toBe(false);expect(commit).not.toHaveBeenCalled();
});
it('coalesces event triggers while busy, then checks every 60 seconds',async()=>{
 let release!:(v:unknown)=>void;mocks.request.mockImplementationOnce(()=>new Promise(resolve=>release=resolve));const sync=observeSync(account,vi.fn(),vi.fn());await vi.advanceTimersByTimeAsync(0);for(let i=0;i<5;i++)sync.trigger();expect(mocks.request).toHaveBeenCalledTimes(1);release(page());await vi.advanceTimersByTimeAsync(0);expect(mocks.request).toHaveBeenCalledTimes(2);await vi.advanceTimersByTimeAsync(60000);expect(mocks.request).toHaveBeenCalledTimes(3);sync.stop();
});
it('pushes only the native queue snapshot and applies matching receipts before pulling grades',async()=>{
 const sent={...empty,comments:[{id:account.user.id,announcement_id:page().hub_id,content:'Synthetic comment',created_at:1}]};mocks.invoke.mockImplementation(async(cmd:string)=>cmd==='read_sync_state'?{cursor:0}:cmd==='read_sync_queue'?sent:undefined);
 const receipt={acknowledged:true,receipts:[{id:account.user.id,status:'SYNCED'}]};mocks.request.mockImplementation(async(_h:unknown,path:string)=>path.endsWith('/push')?receipt:page());const sync=observeSync(account,vi.fn(),vi.fn());await vi.advanceTimersByTimeAsync(0);
 expect(mocks.request).toHaveBeenCalledWith(hub,'/api/sync/push',expect.objectContaining({body:sent,token:'synthetic-token'}));expect(mocks.invoke).toHaveBeenCalledWith('apply_sync_receipts',{hub,userId:account.user.id,sent,payload:receipt});expect(mocks.request).toHaveBeenCalledTimes(3);sync.stop();
});
it('expires unauthorized sync and honors Retry-After without retrying early',async()=>{
 const error=vi.fn();mocks.request.mockRejectedValueOnce(new ApiError('TOO_MANY_ATTEMPTS',30));const sync=observeSync(account,vi.fn(),error);await vi.advanceTimersByTimeAsync(0);sync.trigger();await vi.advanceTimersByTimeAsync(29999);expect(mocks.request).toHaveBeenCalledTimes(1);await vi.advanceTimersByTimeAsync(1);expect(mocks.request).toHaveBeenCalledTimes(2);sync.stop();
 mocks.request.mockRejectedValue(new ApiError('UNAUTHORIZED'));const end=observeSync(account,vi.fn(),error);await vi.advanceTimersByTimeAsync(0);expect(mocks.forget).toHaveBeenCalledWith(account);await vi.advanceTimersByTimeAsync(60000);expect(mocks.request).toHaveBeenCalledTimes(3);end.stop();
});
it('rejects malformed, regressing and stalled cursors before native apply',()=>{
 for(const value of [{...page(),next_cursor:1.5},{...page(),reset:'false'},{...page(),has_more:true,next_cursor:0},{...page(),next_cursor:-1}])expect(()=>parseSyncPage(value,{cursor:0})).toThrow();
 expect(()=>parseSyncPage(page(1),{cursor:2})).toThrow();expect(parseSyncPage({...page(0),reset:true},{cursor:2}).next_cursor).toBe(0);
});
it('accepts the contracted opaque Hub identity without requiring a UUID',()=>{
 expect(parseSyncPage({...page(),hub_id:'school-hub-identity'},{cursor:0}).hub_id).toBe('school-hub-identity');
});
