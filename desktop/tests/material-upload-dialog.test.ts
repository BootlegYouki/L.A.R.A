import { act, create } from 'react-test-renderer';
import { createElement, type ReactNode } from 'react';
import { expect, it, vi } from 'vitest';
const { enqueueUpload }=vi.hoisted(()=>({enqueueUpload:vi.fn()}));
vi.mock('../src/services/uploadQueue',()=>({enqueueUpload}));
vi.mock('@radix-ui/react-dialog',()=>{const wrap=({children}:{children?:ReactNode})=>children;return {Root:wrap,Portal:wrap,Overlay:()=>null,Content:'section',Title:'h2',Description:'p',Close:wrap};});
import MaterialUploadDialog from '../src/components/MaterialUploadDialog';
import type { CachedAccount, Classroom } from '../src/services/auth';

it('queues a file for the selected announcement without claiming Hub completion',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);const job={id:'job-1'};enqueueUpload.mockResolvedValue(job);
  const queued=vi.fn(),open=vi.fn();const account={user:{id:'teacher'}} as CachedAccount;
  const classroom={id:'22222222-2222-4222-8222-222222222222',archived_at:null} as Classroom;
  let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(MaterialUploadDialog,{account,classroom,open:true,offline:false,announcements:[{id:'post-1',title:'Reminder'}],onOpenChange:open,onQueued:queued}));});
  await act(async()=>renderer.root.findByProps({id:'material-title'}).props.onChange({target:{value:'Handout'}}));
  await act(async()=>renderer.root.findByProps({id:'material-target'}).props.onChange({target:{value:'p:post-1'}}));
  await act(async()=>renderer.root.findByProps({id:'material-file'}).props.onChange({target:{files:[new File(['pdf'],'handout.pdf')]}}));
  await act(async()=>{await renderer.root.findByType('form').props.onSubmit({preventDefault(){}});});
  expect(enqueueUpload.mock.calls[0][1]).toMatchObject({announcement_id:'post-1',title:'Handout',classroom_id:classroom.id});
  expect(queued).toHaveBeenCalledWith(job);expect(open).toHaveBeenCalledWith(false);
  await act(async()=>renderer.unmount());vi.unstubAllGlobals();
});
