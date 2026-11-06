import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const {readHubPreference}=vi.hoisted(()=>({readHubPreference:vi.fn()}));
vi.mock('../src/services/settings',()=>({readHubPreference}));
import {installWebSocketPolicy,ensureWebSocketPolicy} from '../src/services/websocket-policy';
const hub={name:'Test',ip:'192.168.1.50',http_port:8080,ws_port:8081};
const head={appendChild:vi.fn()};const reload=vi.fn();
beforeEach(()=>{vi.stubGlobal('document',{head,createElement:()=>({httpEquiv:'',content:''})});vi.stubGlobal('window',{location:{reload}});readHubPreference.mockReturnValue(hub);});
afterEach(()=>{vi.unstubAllGlobals();vi.clearAllMocks();});
it('enforces only the selected private WebSocket endpoint before connecting',()=>{
 installWebSocketPolicy(hub);const policy=head.appendChild.mock.calls.at(-1)?.[0];
 expect(policy.httpEquiv).toBe('Content-Security-Policy');expect(policy.content).toContain('ws://192.168.1.50:8081');
 expect(policy.content).not.toMatch(/\sws:\s/);expect(ensureWebSocketPolicy(hub)).toBe(true);expect(reload).not.toHaveBeenCalled();
});
it('allows no WebSocket when no valid Hub is saved and reloads only for a persisted replacement',()=>{
 installWebSocketPolicy(null);expect(head.appendChild.mock.calls.at(-1)?.[0].content).not.toContain('ws://192.168');
 readHubPreference.mockReturnValue(null);expect(ensureWebSocketPolicy(hub)).toBe(false);expect(reload).not.toHaveBeenCalled();
 readHubPreference.mockReturnValue(hub);expect(ensureWebSocketPolicy(hub)).toBe(false);expect(reload).toHaveBeenCalledTimes(1);
 installWebSocketPolicy({...hub,ip:'8.8.8.8'});expect(head.appendChild.mock.calls.at(-1)?.[0].content).not.toContain('8.8.8.8');
});
