import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { createElement } from 'react';
import { afterEach, expect, it, vi } from 'vitest';
const { invoke } = vi.hoisted(() => ({ invoke: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: (command:string,...args:unknown[]) => command==='list_uploads'?Promise.resolve([]):invoke(command,...args) }));
import ClassPage from '../src/pages/ClassPage';
import type { CachedAccount } from '../src/services/auth';
const room = {id:'class',name:'Science',section:'A',class_code:'ABC234',teacher_id:'teacher'};
const account: CachedAccount = {hub:{ip:'127.0.0.1',http_port:8080,ws_port:8081,name:'Hub'},user:{id:'teacher',role:'TEACHER',lrn_or_id:'T',full_name:'Teacher'},expires_at:1,persistent:true,classrooms:[room]};
let renderer: ReactTestRenderer;
afterEach(async () => {if(renderer) await act(async () => renderer.unmount()); vi.clearAllMocks(); vi.unstubAllGlobals();});
it('keeps the class mascot stable across reopening and renaming while varying between classes', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[]});
  const show = async (classroom: typeof room) => {
    await act(async () => {renderer=create(createElement(ClassPage,{account,classroom,offline:true,onVideo:vi.fn()}));});
    const image = renderer.root.findByProps({className:'class-banner-owl'});
    expect(image.props.alt).toBe('');
    const src = image.props.src;
    expect(src).toMatch(/owl-(0[12]|0[345]-transparent)\.webp/);
    await act(async () => renderer.unmount());
    return src;
  };
  const first = await show(room);
  expect(await show({...room,name:'Renamed class'})).toBe(first);
  const variants = new Set<string>();
  for (let id = 0; id < 20; id++) variants.add(await show({...room,id:`class-${id}`}));
  expect(variants.size).toBe(5);
});
it('opens cached classwork offline and routes a video through its actual material ID', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[{id:'lesson',title:'Food chain',file_type:'VIDEO',saved:true,chunks:[]}]});
  const open = vi.fn();
  await act(async () => {renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:open}));});
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  await act(async () => renderer.root.findByProps({className:'control text-button material-open'}).props.onClick());
  expect(open).toHaveBeenCalledWith('lesson','classwork');
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
it('shows decorative owl empty states for Stream and Classwork', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[]});
  await act(async () => {renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}));});
  const stream = renderer.root.findByProps({className:'empty-state empty-stream'});
  expect(stream.findByType('img').props.alt).toBe('');
  expect(stream.findByType('h2').children).toContain('No announcements yet');
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  const classwork = renderer.root.findByProps({className:'empty-state empty-center'});
  expect(classwork.findByType('img').props.alt).toBe('');
  expect(classwork.findByType('p').children).toContain('Materials shared by your teacher will appear here.');
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
  expect(page.findByProps({role:'tablist'})).toBeTruthy();
  const order=JSON.stringify(renderer.toJSON());
  expect(order.indexOf('"role":"tablist"')).toBeLessThan(order.indexOf('"className":"class-banner"'));
  expect(renderer.root.findAllByProps({className:'class-banner'})).toHaveLength(1);
  await act(async () => renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  expect(renderer.root.findAllByProps({className:'class-banner'})).toHaveLength(0);
  expect(renderer.root.findByProps({className:'material-item'}).findByType('summary').props.className).toBe('material-row');
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
it('offers real teacher authoring controls but none for learners or archived classes', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[],assignments:[],topics:[],quizzes:[]});
  await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:false,onVideo:vi.fn()}));});
  expect(renderer.root.findAllByProps({className:'control tonal-button new-announcement'})).toHaveLength(1);
  await act(async()=>renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  expect(renderer.root.findAllByProps({className:'class-create-menu'})).toHaveLength(1);
  await act(async()=>renderer.update(createElement(ClassPage,{account:{...account,user:{...account.user,role:'STUDENT'}},classroom:room,offline:false,onVideo:vi.fn()})));
  expect(renderer.root.findAllByProps({className:'class-create-menu'})).toHaveLength(0);
  await act(async()=>renderer.update(createElement(ClassPage,{account,classroom:{...room,archived_at:1},offline:false,onVideo:vi.fn()})));
  expect(renderer.root.findAllByProps({className:'class-create-menu'})).toHaveLength(0);
});
it('renders cached assignments and topics without a network read', async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[],assignments:[{id:'a',title:'Water cycle work',instructions:'Explain evaporation.',due_date:1795300000000,max_points:100}],topics:[{id:'t',name:'Water cycle'}],quizzes:[]});
  await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}));});
  await act(async()=>renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  expect(JSON.stringify(renderer.toJSON())).toContain('Water cycle work');
  expect(JSON.stringify(renderer.toJSON())).toContain('Explain evaporation.');
  expect(renderer.root.findAllByProps({id:'class-topic-filter'})).toHaveLength(1);
});

it('filters cached work by its real topic while retaining unassigned work in All topics', async()=>{
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
  invoke.mockResolvedValue({announcements:[],materials:[],assignments:[{id:'a',title:'In unit',instructions:'Read',due_date:1,max_points:1,topic_id:'t'},{id:'b',title:'Outside unit',instructions:'Write',due_date:1,max_points:1,topic_id:null}],topics:[{id:'t',name:'Unit'}],quizzes:[]});
  await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}));});
  await act(async()=>renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
  expect(JSON.stringify(renderer.toJSON())).toContain('Outside unit');
  await act(async()=>renderer.root.findByProps({id:'class-topic-filter'}).props.onChange({target:{value:'t'}}));
  expect(renderer.root.findByProps({'aria-label':'Topic options: Unit'}).props.className).toBe('icon-button');
  expect(renderer.root.findByProps({'aria-label':'Topic options: Unit'}).children).not.toContain('Topic options');
  expect(JSON.stringify(renderer.toJSON())).toContain('In unit');
  expect(JSON.stringify(renderer.toJSON())).not.toContain('Outside unit');
});
it('keeps assignment attachments under their parent instead of duplicating them as standalone work',async()=>{
 vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
 invoke.mockResolvedValue({announcements:[],materials:[{id:'file',title:'Attached sheet',assignment_id:'assignment',file_type:'DOCUMENT',saved:false,chunks:[]},{id:'standalone',title:'Independent lesson',file_type:'DOCUMENT',saved:false,chunks:[]}],assignments:[{id:'assignment',title:'Assignment',instructions:'Read',due_date:1,max_points:100,allow_late:true}],topics:[],quizzes:[]});
 await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:false,onVideo:vi.fn()}));});
 await act(async()=>renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
 const list=renderer.root.findByProps({className:'material-list'});
 expect(JSON.stringify(list.findAllByType('strong').map(node=>node.children))).not.toContain('Attached sheet');
 expect(JSON.stringify(list.findAllByType('strong').map(node=>node.children))).toContain('Independent lesson');
 const assignment=renderer.root.findByProps({className:'material-item assignment-preview'});
 expect(assignment.findByProps({'aria-label':'Open handout: Attached sheet'})).toBeTruthy();
 expect(assignment.findAllByType('button').some(node=>node.children.includes('Add attachment'))).toBe(false);
});
it('keeps public comments collapsed until the entry beneath the post is opened',async()=>{
 vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
 invoke.mockResolvedValue({announcements:[{id:'post',title:'Post',content:'Text',created_at:1}],materials:[]});
 await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}))});
 expect(renderer.root.findAllByProps({className:'comment-entry'})).toHaveLength(1);
 expect(renderer.root.findAllByType('textarea')).toHaveLength(0);
});

it('lists a Hub material in Classwork even when its automatic Stream post is linked',async()=>{
 vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);
 invoke.mockResolvedValue({announcements:[{id:'post',title:'Lesson',content:'Read',created_at:1}],materials:[{id:'handout',announcement_id:'post',title:'Lesson',file_type:'DOCUMENT',saved:true,chunks:[]}],assignments:[],topics:[],quizzes:[]});
 await act(async()=>{renderer=create(createElement(ClassPage,{account,classroom:room,offline:true,onVideo:vi.fn()}))});
 await act(async()=>renderer.root.findByProps({id:'tab-classwork'}).props.onClick());
 expect(renderer.root.findByProps({className:'material-list'}).findByProps({'aria-label':'Open handout: Lesson'})).toBeTruthy();
});
