import {expect,it,vi} from 'vitest';
const {invoke}=vi.hoisted(()=>({invoke:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke}));
import {queueHomework} from '../src/services/homework';
import type {CachedAccount} from '../src/services/auth';
const account={hub:{ip:'127.0.0.1',http_port:8080},user:{id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',role:'STUDENT'}} as CachedAccount;
it('durably stages bounded chunks before marking homework queued',async()=>{
 invoke.mockResolvedValueOnce('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb').mockResolvedValue(undefined);
 const file=new File([new Uint8Array(130000)],'work.pdf',{type:'application/pdf'});
 await queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',file);
 expect(invoke.mock.calls.map(call=>call[0])).toEqual(['begin_homework','append_homework','append_homework','finish_homework']);
 expect(invoke.mock.calls[1][1].data.length).toBe(65536);expect(invoke.mock.calls[2][1].offset).toBe(65536);
});
it('rejects unsupported homework files before staging',async()=>{
 invoke.mockClear();await expect(queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',new File(['x'],'script.exe'))).rejects.toThrow('invalid_homework');expect(invoke).not.toHaveBeenCalled();
});
