import { act, create } from 'react-test-renderer';
import { createElement } from 'react';
import { expect, it, vi } from 'vitest';
const { listUploadJobs,retryUpload,cancelUpload }=vi.hoisted(()=>({listUploadJobs:vi.fn(),retryUpload:vi.fn(),cancelUpload:vi.fn()}));
vi.mock('../src/services/uploadQueue',()=>({listUploadJobs,retryUpload,cancelUpload}));
import UploadJobs from '../src/components/UploadJobs';
import type { CachedAccount } from '../src/services/auth';

it('refreshes the class once when a queued upload completes',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);vi.useFakeTimers();
  listUploadJobs.mockResolvedValue([{id:'job-1',title:'Handout',fileName:'handout.pdf',fileSize:10,uploadedBytes:10,status:'succeeded'}]);
  const account={hub:{ip:'127.0.0.1',http_port:8080},user:{id:'teacher'}} as CachedAccount;const completed=vi.fn();let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(UploadJobs,{account,classroomId:'class',onCompleted:completed}));});
  await act(async()=>{await vi.advanceTimersByTimeAsync(1100);});
  expect(listUploadJobs).toHaveBeenCalled();expect(completed).toHaveBeenCalledOnce();
  expect(renderer.toJSON()).toBeNull();
  await act(async()=>renderer.unmount());vi.useRealTimers();vi.unstubAllGlobals();
});

it('offers manual retry for a ready job and warns without retry controls for uncertain jobs',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  listUploadJobs.mockResolvedValue([
    {id:'ready',title:'Handout',fileName:'handout.pdf',fileSize:10,uploadedBytes:0,status:'ready'},
    {id:'uncertain',title:'Notice',fileName:'notice.pdf',fileSize:10,uploadedBytes:10,status:'uncertain'},
  ]);
  retryUpload.mockResolvedValue(undefined);const account={hub:{ip:'127.0.0.1',http_port:8080},user:{id:'teacher'}} as CachedAccount;
  let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(UploadJobs,{account,classroomId:'class',onCompleted:vi.fn()}));});
  await act(async()=>Promise.resolve());
  expect(renderer.root.findAllByProps({role:'status'})).toHaveLength(1);
  const retry=renderer.root.findAllByType('button').find(button=>button.children.join('')==='Retry upload');
  expect(retry).toBeDefined();
  await act(async()=>{retry!.props.onClick();await Promise.resolve();await Promise.resolve();});
  expect(retryUpload).toHaveBeenCalledWith(account,'class','ready');
  const uncertain=renderer.root.findAllByType('li')[1].findByType('button');
  await act(async()=>uncertain.props.onClick());expect(retryUpload).toHaveBeenCalledTimes(1);
  await act(async()=>{renderer.root.findAllByType('li')[1].findByType('button').props.onClick();await Promise.resolve();await Promise.resolve();});
  expect(retryUpload).toHaveBeenCalledWith(account,'class','uncertain');
  await act(async()=>renderer.unmount());vi.unstubAllGlobals();
});
