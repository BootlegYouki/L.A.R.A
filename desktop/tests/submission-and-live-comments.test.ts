import {createElement} from 'react';
import {act,create,type ReactTestRenderer} from 'react-test-renderer';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const {invoke,readThread,sendComment}=vi.hoisted(()=>({invoke:vi.fn(),readThread:vi.fn(),sendComment:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke,convertFileSrc:()=> 'http://asset.localhost/submission.pdf'}));
vi.mock('../src/services/comments',()=>({readThread,sendComment}));
import SubmissionPreview from '../src/components/SubmissionPreview';
import CommentThread from '../src/components/CommentThread';
import type {CachedAccount} from '../src/services/auth';
const account={hub:{ip:'127.0.0.1',name:'QA',http_port:8080,ws_port:8081},user:{id:'qa',role:'TEACHER'},classrooms:[]} as unknown as CachedAccount;
let renderer:ReactTestRenderer;
beforeEach(()=>{vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);vi.stubGlobal('document',{visibilityState:'visible'});vi.clearAllMocks();readThread.mockResolvedValue({items:[],write_mode:'online'});invoke.mockResolvedValue({path:'G:/qa/submission.pdf',mime:'application/pdf'});});
afterEach(async()=>{if(renderer)await act(async()=>renderer.unmount());vi.useRealTimers();vi.unstubAllGlobals();});
it('opens PDF through native file handling and downloads without navigating the app',async()=>{
 await act(async()=>{renderer=create(createElement(SubmissionPreview,{account,submissionId:'submission',offline:false}));});
 expect(renderer.root.findAllByType('a')).toHaveLength(0);
 const open=renderer.root.findAllByType('button').find(button=>button.children.includes('Open file'));
 expect(open).toBeDefined();await act(async()=>{await open!.props.onClick()});
 expect(invoke).toHaveBeenLastCalledWith('export_submission',expect.objectContaining({submissionId:'submission',open:true}));
});
it('automatically requests Hub sync while a comment thread is open without losing its draft',async()=>{
 vi.useFakeTimers();const onChanged=vi.fn();
 await act(async()=>{renderer=create(createElement(CommentThread,{account,target:{kind:'announcement',id:'post'},offline:false,onChanged}));});
 await act(async()=>renderer.root.findByType('textarea').props.onChange({target:{value:'Unsent draft'}}));
 await act(async()=>{vi.advanceTimersByTime(5000)});
 expect(onChanged).toHaveBeenCalled();expect(readThread.mock.calls.length).toBeGreaterThan(1);expect(renderer.root.findByType('textarea').props.value).toBe('Unsent draft');
});
it('does not poll the Hub while offline or after the thread closes',async()=>{
 vi.useFakeTimers();const onChanged=vi.fn();
 await act(async()=>{renderer=create(createElement(CommentThread,{account,target:{kind:'announcement',id:'post'},offline:true,onChanged}));});
 await act(async()=>{vi.advanceTimersByTime(10000)});expect(onChanged).not.toHaveBeenCalled();
 await act(async()=>renderer.unmount());await act(async()=>{vi.advanceTimersByTime(10000)});expect(onChanged).not.toHaveBeenCalled();
});
