import { act, create } from 'react-test-renderer';
import { createElement, type ReactNode } from 'react';
import { beforeEach, expect, it, vi } from 'vitest';
const { createAssignment, updateClasswork, enqueueUpload, waitForCachedAssignment } = vi.hoisted(() => ({ createAssignment:vi.fn(), updateClasswork:vi.fn(), enqueueUpload:vi.fn(), waitForCachedAssignment:vi.fn() }));
vi.mock('../src/services/authoring',()=>({createAnnouncement:vi.fn(),createAssignment,createTopic:vi.fn()}));
vi.mock('../src/services/classworkMutations',()=>({updateClasswork}));
vi.mock('../src/services/classworkCache',()=>({waitForCachedAssignment}));
vi.mock('../src/services/uploadQueue',()=>({enqueueUpload}));
vi.mock('@radix-ui/react-dialog',()=>{const wrap=({children}:{children?:ReactNode})=>children;return {Root:wrap,Portal:wrap,Overlay:()=>null,Content:'section',Title:'h2',Description:'p'};});
import ClassAuthoringDialog from '../src/components/ClassAuthoringDialog';
import type { CachedAccount } from '../src/services/auth';

const account={user:{id:'teacher'}} as CachedAccount;
const id='33333333-3333-4333-8333-333333333333';
beforeEach(()=>{vi.clearAllMocks();createAssignment.mockResolvedValue(id);updateClasswork.mockResolvedValue(id);waitForCachedAssignment.mockResolvedValue(undefined);});

it('saves assignment once and retries only files that did not enter the durable queue',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  enqueueUpload.mockResolvedValueOnce({id:'job-1'}).mockRejectedValueOnce(new Error('disk full')).mockResolvedValueOnce({id:'job-2'});
  const closed=vi.fn(),created=vi.fn();let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(ClassAuthoringDialog,{kind:'assignment',account,classroomId:id,offline:false,onClose:closed,onCreated:created}));});
  await act(async()=>renderer.root.findByProps({id:'post-title'}).props.onChange({target:{value:'Reading'}}));
  await act(async()=>renderer.root.findByProps({id:'post-body'}).props.onChange({target:{value:'Read chapter 2'}}));
  await act(async()=>renderer.root.findByProps({id:'post-due'}).props.onChange({target:{value:'2026-12-01T12:00'}}));
  await act(async()=>renderer.root.findByProps({id:'assignment-files'}).props.onChange({target:{files:[new File(['one'],'one.pdf'),new File(['two'],'two.pdf')]}}));
  await act(async()=>{await renderer.root.findByType('form').props.onSubmit({preventDefault(){}});});
  expect(createAssignment).toHaveBeenCalledOnce();expect(enqueueUpload).toHaveBeenCalledTimes(2);expect(closed).not.toHaveBeenCalled();expect(created).toHaveBeenCalledOnce();
  expect(waitForCachedAssignment).toHaveBeenCalledOnce();
  expect(renderer.root.findAllByProps({role:'status'}).flatMap(node=>node.children).join(' ')).toContain('assignment was saved');
  await act(async()=>{await renderer.root.findByType('form').props.onSubmit({preventDefault(){}});});
  expect(createAssignment).toHaveBeenCalledOnce();expect(updateClasswork).toHaveBeenCalledOnce();expect(updateClasswork.mock.calls[0][3]).toBe(id);
  expect(enqueueUpload).toHaveBeenCalledTimes(3);expect(enqueueUpload.mock.calls[2][1].file.name).toBe('two.pdf');expect(closed).toHaveBeenCalledOnce();
  await act(async()=>renderer.unmount());vi.unstubAllGlobals();
});

it('clears a draft when the classroom changes',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(ClassAuthoringDialog,{kind:'assignment',account,classroomId:id,offline:false,onClose:vi.fn(),onCreated:vi.fn()}));});
  await act(async()=>renderer.root.findByProps({id:'post-title'}).props.onChange({target:{value:'Old class draft'}}));
  await act(async()=>renderer.update(createElement(ClassAuthoringDialog,{kind:'assignment',account,classroomId:'44444444-4444-4444-8444-444444444444',offline:false,onClose:vi.fn(),onCreated:vi.fn()})));
  expect(renderer.root.findByProps({id:'post-title'}).props.value).toBe('');
  await act(async()=>renderer.unmount());vi.unstubAllGlobals();
});

it('ignores a second submit while attachment staging is still running',async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);let release!:()=>void;
  enqueueUpload.mockReturnValue(new Promise(resolve=>{release=()=>resolve({id:'job-1'});}));
  const closed=vi.fn(),created=vi.fn();let renderer!:ReturnType<typeof create>;
  await act(async()=>{renderer=create(createElement(ClassAuthoringDialog,{kind:'assignment',account,classroomId:id,offline:false,onClose:closed,onCreated:created}));});
  await act(async()=>renderer.root.findByProps({id:'post-title'}).props.onChange({target:{value:'Reading'}}));
  await act(async()=>renderer.root.findByProps({id:'post-due'}).props.onChange({target:{value:'2026-12-01T12:00'}}));
  await act(async()=>renderer.root.findByProps({id:'assignment-files'}).props.onChange({target:{files:[new File(['one'],'one.pdf')]}}));
  await act(async()=>{const form=renderer.root.findByType('form');form.props.onSubmit({preventDefault(){}});form.props.onSubmit({preventDefault(){}});await Promise.resolve();});
  expect(createAssignment).toHaveBeenCalledOnce();expect(enqueueUpload).toHaveBeenCalledOnce();
  await act(async()=>release());expect(closed).toHaveBeenCalledOnce();
  await act(async()=>renderer.unmount());vi.unstubAllGlobals();
});
