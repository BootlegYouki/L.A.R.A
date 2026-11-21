import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { createElement } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke }));
import ClassPage from '../src/pages/ClassPage';
import type { CachedAccount } from '../src/services/auth';
const room = {id:'class',name:'Science',section:'A',class_code:'ABC234',teacher_id:'teacher'};
const account: CachedAccount = {hub:{ip:'127.0.0.1',http_port:8080,ws_port:8081,name:'Hub'},user:{id:'teacher',role:'TEACHER',lrn_or_id:'T',full_name:'Teacher'},expires_at:1,persistent:true,classrooms:[room]};
let renderer: ReactTestRenderer;
afterEach(async () => {if(renderer) await act(async () => renderer.unmount()); vi.clearAllMocks(); vi.unstubAllGlobals();});
it('opens cached classwork offline and routes a video through its actual material ID', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[{id:'lesson',title:'Food chain',file_type:'VIDEO',saved:true,chunks:[]}]});
  const open = vi.fn();
  await act(async () => {renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:open}));});
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  await act(async () => renderer.root.findByProps({className:'control text-button material-open'}).props.onClick());
  expect(open).toHaveBeenCalledWith('lesson');
  expect(invoke).toHaveBeenCalledWith('read_class_content',{hub:account.hub,userId:'teacher',classroomId:'class'});
});

it('shows the read-only notice when a learner opens an archived class', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[]});
  const learner: CachedAccount = {...account,user:{...account.user,role:'STUDENT'}};
  await act(async () => {renderer=create(createElement(ClassPage,{account:learner,classroom:{...room,archived_at:1},offline:true,onVideo:vi.fn()}));});
  expect(renderer.root.findByProps({role:'status',className:'hub-status-banner banner-info'}).children)
    .toContain('This class is archived and read-only.');
});
it('saves cached handout text offline and prevents another save while pending', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  const lesson = {id:'handout',title:'Food chain',file_type:'DOCUMENT',saved:false,chunks:[{heading:null,text:'Cached lesson text'}]};
  const content = {announcements:[],materials:[lesson]};
  let finish!: (value: unknown) => void;
  invoke.mockReset().mockResolvedValueOnce(content).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await act(async () => { renderer = create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()})); });
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  await act(async () => renderer.root.findByProps({className:'control text-button material-open'}).props.onClick());
  expect(renderer.root.findByProps({className:'material-detail-actions'}).findByType('h2').children).toContain('Home study');
  expect(renderer.root.findByProps({className:'control tonal-button'}).props.disabled).toBe(false);
  await act(async () => renderer.root.findByProps({className:'control tonal-button'}).props.onClick());
  expect(renderer.root.findByProps({className:'control tonal-button'}).props.disabled).toBe(true);
  expect(invoke).toHaveBeenLastCalledWith('read_class_content',{hub:account.hub,userId:'teacher',classroomId:'class',saveHandout:'handout'});
  await act(async () => finish({...content,materials:[{...lesson,saved:true}]}));
  expect(renderer.root.findByProps({role:'status'}).children).toContain('Saved on this device');
});

it('keeps saving disabled offline when no handout text is cached', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  invoke.mockReset().mockResolvedValue({announcements:[],materials:[{id:'handout',title:'Food chain',file_type:'DOCUMENT',saved:false,chunks:[]}]});
  await act(async () => { renderer = create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()})); });
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  await act(async () => renderer.root.findByProps({className:'control text-button material-open'}).props.onClick());
  expect(renderer.root.findByProps({className:'control tonal-button'}).props.disabled).toBe(true);
  expect(invoke).toHaveBeenCalledTimes(1);
});

it('puts tabs before the Stream banner and uses expandable Classwork material rows', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockReset().mockResolvedValue({announcements:[{id:'post',title:'Read the lesson',content:'Bring your notes.',created_at:1}],materials:[{id:'handout',title:'Food chain',file_type:'DOCUMENT',saved:false,chunks:[{heading:null,text:'Cached lesson text'}]}]});
  await act(async () => {renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}));});
  const page=renderer.root.findByProps({className:'class-page'});
  const first = page.children[0];
  expect(typeof first === 'string' ? null : first.props.role).toBe('tablist');
  expect(renderer.root.findAllByProps({className:'class-banner'})).toHaveLength(1);
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  expect(renderer.root.findAllByProps({className:'class-banner'})).toHaveLength(0);
  expect(renderer.root.findByType('details').findByType('summary').props.className).toBe('material-row');
  expect(renderer.root.findByProps({className:'control text-button material-open'}).props['aria-label']).toBe('Open handout: Food chain');
});

it('keeps extracted paragraphs separate without blank-line spacer text', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockReset().mockResolvedValue({announcements:[],materials:[{id:'handout',title:'Food chain',file_type:'DOCUMENT',saved:false,chunks:[{heading:null,text:'First paragraph.\n\nSecond paragraph.'}]}]});
  await act(async () => {renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}));});
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  await act(async () => renderer.root.findByProps({className:'control text-button material-open'}).props.onClick());
  expect(renderer.root.findAllByProps({className:'lesson-text'}).map(node => node.children.join(''))).toEqual(['First paragraph.','Second paragraph.']);
});
