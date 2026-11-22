import {beforeEach,expect,it,vi} from 'vitest';
import type {CachedAccount} from '../src/services/auth';
const{invoke}=vi.hoisted(()=>({invoke:vi.fn()}));vi.mock('@tauri-apps/api/core',()=>({invoke}));
import {loadDocument,isDocumentMime,pdfPageScale} from '../src/services/documents';
const account={hub:{ip:'192.168.100.45',http_port:8080},user:{id:'qa'}} as CachedAccount;
const source={kind:'submission' as const,id:'work',attachment_index:2};
beforeEach(()=>invoke.mockReset());
it('loads only an authorized native source and checks the exact binary length',async()=>{
 const data=new ArrayBuffer(12);invoke.mockResolvedValueOnce({mime:'application/pdf',size:12}).mockResolvedValueOnce(data);
 expect(await loadDocument(account,source,true)).toEqual({mime:'application/pdf',data});
 expect(invoke).toHaveBeenNthCalledWith(1,'prepare_document',{hub:account.hub,userId:'qa',source,offline:true});
 expect(invoke).toHaveBeenNthCalledWith(2,'read_document',{hub:account.hub,userId:'qa',source});
 invoke.mockResolvedValueOnce({mime:'application/pdf',size:12}).mockResolvedValueOnce(new ArrayBuffer(11));await expect(loadDocument(account,source,false)).rejects.toThrow('invalid_document');
});
it('rejects active formats, malformed sizes and oversized previews before reading bytes',async()=>{
 for(const meta of [{mime:'text/html',size:12},{mime:'application/pdf',size:0},{mime:'application/pdf',size:65*1024*1024}]){invoke.mockReset();invoke.mockResolvedValue(meta);await expect(loadDocument(account,source,false)).rejects.toThrow();expect(invoke).toHaveBeenCalledTimes(1)}
 expect(isDocumentMime('application/pdf')).toBe(true);expect(isDocumentMime('application/vnd.openxmlformats-officedocument.wordprocessingml.document')).toBe(true);expect(isDocumentMime('application/msword')).toBe(false);
});

it('bounds hostile PDF page dimensions without changing ordinary page zoom',()=>{
 expect(pdfPageScale(612,792,900,2)).toBe(1800/612);
 const scale=pdfPageScale(1e8,1e8,900,2);expect(1e8*scale).toBeLessThanOrEqual(4096);expect((1e8*scale)**2).toBeLessThanOrEqual(16*1024*1024);
 for(const size of [0,-1,NaN,Infinity])expect(()=>pdfPageScale(size,792,900,1)).toThrow('invalid_document');
});
