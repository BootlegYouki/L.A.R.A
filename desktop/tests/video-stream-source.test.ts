import {createElement} from 'react';
import {act,create,type ReactTestRenderer} from 'react-test-renderer';
import {afterEach,beforeEach,expect,it,vi} from 'vitest';
const{invoke,acquireMedia,release}=vi.hoisted(()=>({invoke:vi.fn(),acquireMedia:vi.fn(),release:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke,convertFileSrc:()=> 'http://asset.localhost/saved-video.mp4'}));
vi.mock('@tauri-apps/api/event',()=>({listen:async()=>()=>{}}));
vi.mock('../src/services/mediaCache',()=>({acquireMedia}));
vi.mock('../src/components/VideoPlayer',()=>({default:()=>null}));
import VideoLessonPage from '../src/pages/VideoLessonPage';
import VideoPlayer from '../src/components/VideoPlayer';
const hub={name:'Hub',ip:'192.168.1.50',http_port:8080,ws_port:8081};
const material={id:'video',file_type:'VIDEO',title:'Lesson',mime_type:'video/mp4',updated_at:1,local_file_path:null,download_url:'/api/materials/video/stream'};
let renderer:ReactTestRenderer;
beforeEach(()=>{vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT',true);vi.clearAllMocks();invoke.mockImplementation(async(command:string)=>command==='get_material_by_id'?material:false);acquireMedia.mockReturnValue({source:Promise.resolve({id:'lease',url:'http://127.0.0.1:3210/opaque',mime:'video/mp4'}),release});});
afterEach(async()=>{if(renderer)await act(async()=>renderer.unmount());vi.unstubAllGlobals();});
it('streams through the existing authenticated loopback proxy without a token in the media URL',async()=>{
 await act(async()=>{renderer=create(createElement(VideoLessonPage,{hub,userId:'learner',materialId:'video',token:'private-token',offline:false,onBack:vi.fn()}));});
 expect(renderer.root.findByType(VideoPlayer).props.src).toBe('http://127.0.0.1:3210/opaque');
 expect(acquireMedia).toHaveBeenCalledWith({hub,user:{id:'learner'}},'video',1,'video/mp4');
 expect(JSON.stringify(renderer.toJSON())).not.toContain('private-token');
 await act(async()=>renderer.unmount());expect(release).toHaveBeenCalledOnce();
});
it('keeps saved offline playback local without acquiring a remote stream',async()=>{
 invoke.mockResolvedValue({...material,local_file_path:'G:/saved/video.mp4'});
 await act(async()=>{renderer=create(createElement(VideoLessonPage,{hub,userId:'learner',materialId:'video',token:'',offline:true,onBack:vi.fn()}));});
 expect(renderer.root.findByType(VideoPlayer).props.src).toBe('http://asset.localhost/saved-video.mp4');expect(acquireMedia).not.toHaveBeenCalled();
});
it('reports a proxy failure without falling back to an unauthorised direct Hub URL',async()=>{
 acquireMedia.mockImplementation(()=>({source:Promise.reject(new Error('preview_unavailable')),release}));
 await act(async()=>{renderer=create(createElement(VideoLessonPage,{hub,userId:'learner',materialId:'video',token:'private-token',offline:false,onBack:vi.fn()}));});
 expect(renderer.root.findAllByType(VideoPlayer)).toHaveLength(0);
 expect(renderer.root.findByProps({role:'alert'}).children.join('')).toContain('Video could not be loaded');
});
