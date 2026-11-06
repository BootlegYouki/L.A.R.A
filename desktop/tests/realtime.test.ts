import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const { accountToken } = vi.hoisted(()=>({accountToken:vi.fn()}));
vi.mock('../src/services/auth',()=>({accountToken}));
vi.mock('../src/services/websocket-policy',()=>({ensureWebSocketPolicy:()=>true}));
import { observeJoinUpdates } from '../src/services/realtime';
const id='11111111-1111-4111-8111-111111111111', classroom_id='22222222-2222-4222-8222-222222222222';
const account={hub:{name:'Hub',ip:'127.0.0.1',http_port:8080,ws_port:8085},user:{id,lrn_or_id:'synthetic',full_name:'Test',role:'STUDENT' as const},expires_at:Date.now()+60000,persistent:true,classrooms:[]};
class Socket {
 static instances:Socket[]=[];
 onopen:(()=>void)|null=null; onmessage:((e:{data:string})=>void)|null=null; onclose:((e:{code:number})=>void)|null=null; onerror:(()=>void)|null=null;
 send=vi.fn(); close=vi.fn(); constructor(public url:string){Socket.instances.push(this);}
 message(data:unknown){this.onmessage?.({data:JSON.stringify(data)});}
}
const ack={event:'EVENT_HELLO_ACK',server_time:100,hub_id:classroom_id,sync_epoch:1};
const approval={event:'EVENT_JOIN_APPROVAL',classroom_id,student_id:id,status:'ACTIVE',timestamp:200};
beforeEach(()=>{vi.useFakeTimers(); Socket.instances=[];vi.stubGlobal('WebSocket',Socket);accountToken.mockResolvedValue('synthetic-token');});
afterEach(()=>{vi.useRealTimers();vi.unstubAllGlobals();vi.clearAllMocks();});
it('authenticates only the selected endpoint and ignores events before ACK or for another learner',async()=>{
 const update=vi.fn().mockResolvedValue(undefined), unauthorized=vi.fn(); const stop=observeJoinUpdates(account,update,unauthorized);
 await vi.advanceTimersByTimeAsync(0);const ws=Socket.instances[0];expect(ws.url).toBe('ws://127.0.0.1:8085');ws.onopen?.();
 expect(ws.send).toHaveBeenCalledWith(JSON.stringify({event:'EVENT_HELLO',token:'synthetic-token'}));
 ws.message(approval);await vi.advanceTimersByTimeAsync(0);expect(update).not.toHaveBeenCalled();
 ws.message(ack);await vi.advanceTimersByTimeAsync(0);expect(update).toHaveBeenCalledWith(undefined);
 update.mockClear();ws.message({...approval,student_id:classroom_id});ws.message({...approval,status:'UNKNOWN'});ws.message(approval);
 await vi.advanceTimersByTimeAsync(0);expect(update).toHaveBeenCalledTimes(1);expect(update).toHaveBeenCalledWith({classroom_id,status:'ACTIVE'});
 stop();expect(ws.close).toHaveBeenCalled();
});
it('backs off 1,2,4,8,10 seconds and stops retrying after 4401',async()=>{
 const stop=observeJoinUpdates(account,vi.fn().mockResolvedValue(undefined),vi.fn());await vi.advanceTimersByTimeAsync(0);
 for(const delay of [1000,2000,4000,8000,10000]){const n=Socket.instances.length;Socket.instances.at(-1)?.onclose?.({code:1006});await vi.advanceTimersByTimeAsync(delay-1);expect(Socket.instances).toHaveLength(n);await vi.advanceTimersByTimeAsync(1);expect(Socket.instances).toHaveLength(n+1);}
 stop();const unauthorized=vi.fn();const end=observeJoinUpdates(account,vi.fn(),unauthorized);await vi.advanceTimersByTimeAsync(0);Socket.instances.at(-1)?.onclose?.({code:4401});await vi.advanceTimersByTimeAsync(30000);expect(unauthorized).toHaveBeenCalledTimes(1);end();
});
it('never opens a socket after disposal while the credential read is pending',async()=>{
 let release!:(s:string)=>void;accountToken.mockReturnValue(new Promise<string>(resolve=>{release=resolve}));
 const stop=observeJoinUpdates(account,vi.fn(),vi.fn());stop();release('test');await vi.advanceTimersByTimeAsync(0);expect(Socket.instances).toHaveLength(0);
});
it('rejects a public Hub before reading credentials and refreshes once after every acknowledged reconnect',async()=>{
 const forbidden=observeJoinUpdates({...account,hub:{...account.hub,ip:'8.8.8.8'}},vi.fn(),vi.fn());
 await vi.advanceTimersByTimeAsync(0);expect(accountToken).not.toHaveBeenCalled();forbidden();
 const update=vi.fn().mockResolvedValue(undefined);const stop=observeJoinUpdates(account,update,vi.fn());await vi.advanceTimersByTimeAsync(0);
 Socket.instances[0].message(ack);await vi.advanceTimersByTimeAsync(0);expect(update).toHaveBeenCalledTimes(1);
 Socket.instances[0].onclose?.({code:1006});await vi.advanceTimersByTimeAsync(1000);
 Socket.instances[1].message(ack);await vi.advanceTimersByTimeAsync(0);expect(update).toHaveBeenCalledTimes(2);stop();
});
it('passes only a class-code hint to teachers and never exposes join-request names or LRNs to a learner handler',async()=>{
 const request={event:'EVENT_JOIN_REQUEST',class_code:'K7M4QX',student_id:id,full_name:'Synthetic learner',lrn:'private-lrn',timestamp:200};
 const teacher=vi.fn().mockResolvedValue(undefined),update=vi.fn().mockResolvedValue(undefined);
 const stop=observeJoinUpdates({...account,user:{...account.user,role:'TEACHER'}},update,vi.fn(),teacher);
 await vi.advanceTimersByTimeAsync(0);const ws=Socket.instances[0];ws.message(request);await vi.advanceTimersByTimeAsync(0);expect(teacher).not.toHaveBeenCalled();
 ws.message(ack);await vi.advanceTimersByTimeAsync(0);teacher.mockClear();ws.message(request);await vi.advanceTimersByTimeAsync(0);expect(teacher).toHaveBeenCalledWith('K7M4QX');expect(JSON.stringify(teacher.mock.calls)).not.toContain('private-lrn');stop();
 const learnerRequest=vi.fn();const end=observeJoinUpdates(account,update,vi.fn(),learnerRequest);await vi.advanceTimersByTimeAsync(0);const learner=Socket.instances.at(-1)!;learner.message(ack);learner.message(request);await vi.advanceTimersByTimeAsync(0);expect(learnerRequest).not.toHaveBeenCalled();end();
});
