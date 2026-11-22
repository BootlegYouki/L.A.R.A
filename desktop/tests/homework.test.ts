import {expect,it,vi} from 'vitest';
const {invoke}=vi.hoisted(()=>({invoke:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke}));
import {queueHomework} from '../src/services/homework';
import type {CachedAccount} from '../src/services/auth';
const account={hub:{ip:'127.0.0.1',http_port:8080},user:{id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',role:'STUDENT'}} as CachedAccount;
it('durably stages bounded chunks before marking homework queued',async()=>{
 invoke.mockResolvedValueOnce('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb').mockResolvedValue(undefined);
 const files=[new File([new Uint8Array(130000)],'work.pdf',{type:'application/pdf'}),new File(['photo'],'photo.jpg',{type:'image/jpeg'})];
 await queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',files);
 expect(invoke.mock.calls.map(call=>call[0])).toEqual(['begin_homework_batch','append_homework_batch','append_homework_batch','append_homework_batch','finish_homework_batch']);
 expect(invoke.mock.calls[0][1].attachments.map((a:{fileName:string})=>a.fileName)).toEqual(['work.pdf','photo.jpg']);
 expect(invoke.mock.calls[1][1].data.length).toBe(65536);expect(invoke.mock.calls[2][1].offset).toBe(65536);expect(invoke.mock.calls[3][1].attachmentIndex).toBe(1);
});
it('rejects unsupported homework files before staging',async()=>{
 invoke.mockClear();await expect(queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',[new File(['x'],'script.exe')])).rejects.toThrow('invalid_homework');expect(invoke).not.toHaveBeenCalled();
});
it('rejects excessive count or aggregate size without staging a partial batch',async()=>{
 invoke.mockClear();const files=Array.from({length:6},(_,i)=>new File(['x'],`work-${i}.pdf`));
 await expect(queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',files)).rejects.toThrow('invalid_homework');expect(invoke).not.toHaveBeenCalled();
 const large=new File(['x'],'large.pdf');Object.defineProperty(large,'size',{value:250*1024*1024});
 await expect(queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',[large,new File(['x'],'extra.pdf')])).rejects.toThrow('invalid_homework');expect(invoke).not.toHaveBeenCalled();
});
it('discards a failed staged batch before finish so no subset is queued',async()=>{
 invoke.mockReset().mockResolvedValueOnce('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb').mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('chunk failed')).mockResolvedValueOnce(undefined);
 const files=[new File(['first'],'first.pdf'),new File(['second'],'second.pdf')];
 await expect(queueHomework(account,'cccccccc-cccc-4ccc-8ccc-cccccccccccc',files)).rejects.toThrow('chunk failed');
 expect(invoke.mock.calls.map(call=>call[0])).toEqual(['begin_homework_batch','append_homework_batch','append_homework_batch','discard_homework_stage']);
});
