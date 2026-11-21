import { createElement } from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { create as createStore } from 'zustand';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { CachedAccount } from '../src/services/auth';
import { t } from '../src/i18n';

const hub = { name: 'Test', ip: '127.0.0.1', http_port: 8080, ws_port: 8081 };
const classrooms = ['Science', 'English'].map((name, index) => ({ id: `class-${index}`, name, section: 'A', class_code: 'ABC234', teacher_id: 'teacher', enrollment_status: 'ACTIVE' as const }));
const account: CachedAccount = { hub, user: { id: 'learner', full_name: 'Learner', lrn_or_id: 'synthetic', role: 'STUDENT' }, classrooms, persistent: true, expires_at: Date.now() + 60000 };
const useStore = createStore<{ status: string; account: CachedAccount | null; loadAccount: () => void; setAccount: (next: CachedAccount | null) => void }>(set => ({ status: 'ready', account, loadAccount: () => {}, setAccount: next => set({ account: next }) }));
vi.mock('../src/store/app', () => ({ useAppStore: () => useStore() }));
const connectionControl = vi.hoisted(() => ({status:'connected', update:null as null | ((state:unknown) => void)}));
vi.mock('../src/services/connection', () => ({ observeHubConnection: (update: (state: unknown) => void) => { connectionControl.update = update; update({ hub, status: connectionControl.status, persisted: true }); return { stop: () => {}, discovered: () => {} }; } }));
vi.mock('../src/services/discovery', () => ({ observeDiscovery: () => () => {} }));
vi.mock('../src/services/auth', async original => ({ ...await original<typeof import('../src/services/auth')>(), refreshClassrooms: async () => useStore.getState().account?.classrooms ?? classrooms }));
vi.mock('../src/services/realtime', () => ({ observeJoinUpdates: () => () => {} }));
vi.mock('../src/services/sync', () => ({ observeSync: () => ({ stop: () => {}, trigger: () => {} }) }));
vi.mock('../src/services/websocket-policy', () => ({ ensureWebSocketPolicy: () => true }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: async (command: string) => command === 'account_token' ? 'synthetic-token' : { announcements: [], materials: [] } }));
vi.mock('../src/pages/VideoLessonPage', () => ({ default: () => null }));
import App from '../src/App';
import ClassPage from '../src/pages/ClassPage';
import HomePage from '../src/pages/HomePage';
import SettingsPage from '../src/pages/SettingsPage';
import VideoLessonPage from '../src/pages/VideoLessonPage';
import Sidebar from '../src/components/Sidebar';

let renderer: ReactTestRenderer;
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('document', { documentElement: { lang: '' } });
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  connectionControl.status = 'connected';
  useStore.setState({ account });
});
afterEach(async () => {
  if (renderer) await act(async () => renderer.unmount());
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
async function openVideo() {
  await act(async () => { renderer = create(createElement(App)); });
  await act(async () => renderer.root.findByType(HomePage).props.onOpen(classrooms[0].id));
  await act(async () => renderer.root.findByType(ClassPage).props.onVideo('lesson'));
  expect(renderer.root.findByType(VideoLessonPage).props.materialId).toBe('lesson');
}
it.each(['Home', 'Class', 'Settings'] as const)('leaves a lesson video when global %s is selected', async destination => {
  await openVideo();
  const sidebar = renderer.root.findByType(Sidebar);
  await act(async () => {
    if (destination === 'Class') sidebar.props.onOpen(classrooms[1].id);
    else sidebar.findByProps({ 'aria-label': { Home: t.home, Settings: t.settings }[destination] }).props.onClick();
  });
  expect(renderer.root.findAllByType(VideoLessonPage)).toHaveLength(0);
  if (destination === 'Class') expect(renderer.root.findByType(ClassPage).props.classroom.id).toBe(classrooms[1].id);
  else if (destination === 'Settings') expect(renderer.root.findAllByType(SettingsPage)).toHaveLength(1);
  else expect(renderer.root.findAllByType(HomePage)).toHaveLength(1);
});
it('returns from a video to its original class with Back', async () => {
  await openVideo();
  await act(async () => renderer.root.findByType(VideoLessonPage).props.onBack());
  expect(renderer.root.findAllByType(VideoLessonPage)).toHaveLength(0);
  expect(renderer.root.findByType(ClassPage).props.classroom.id).toBe(classrooms[0].id);
});

it('shows standalone authentication without app navigation before sign-in', async () => {
  useStore.setState({account:null});
  await act(async () => { renderer = create(createElement(App)); });
  expect(renderer.root.findAllByType(Sidebar)).toHaveLength(0);
  expect(renderer.root.findAllByProps({className:'top-bar'})).toHaveLength(0);
  expect(renderer.root.findAllByProps({className:'auth-panel'})).toHaveLength(1);
  expect(renderer.root.findAllByProps({className:'hub-connection-details'})).toHaveLength(0);
  expect(renderer.root.findAllByProps({id:'hub-address'})).toHaveLength(0);
});
it('keeps Hub controls inside Settings and hides archived navigation', async () => {
  await act(async () => { renderer = create(createElement(App)); });
  expect(renderer.root.findAllByProps({'aria-label':t.archived_classes})).toHaveLength(0);
  expect(renderer.root.findAllByProps({className:'hub-connection-details'})).toHaveLength(0);
  expect(renderer.root.findAllByProps({'aria-label':t.auth_hub_settings})).toHaveLength(0);
  await act(async () => renderer.root.findByProps({'aria-label':t.settings}).props.onClick());
  expect(renderer.root.findAllByType(HomePage)).toHaveLength(0);
  expect(renderer.root.findAllByProps({id:'settings-hub-title'})).toHaveLength(1);
  await act(async () => renderer.root.findByProps({'aria-label':t.home}).props.onClick());
  expect(renderer.root.findAllByType(HomePage)).toHaveLength(1);
  await act(async () => renderer.root.findByProps({'aria-label':t.auth_switch_account}).props.onClick());
  expect(renderer.root.findAllByType(Sidebar)).toHaveLength(0);
  const cancel = renderer.root.findAllByType('button').find(button => button.children.includes(t.cancel));
  await act(async () => cancel!.props.onClick());
  expect(renderer.root.findAllByType(Sidebar)).toHaveLength(1);
});

it('connects to the Hub before showing login on an uncached startup', async () => {
  useStore.setState({account:null}); connectionControl.status = 'connecting';
  await act(async () => { renderer = create(createElement(App)); });
  expect(renderer.root.findAllByProps({className:'auth-panel'})).toHaveLength(0);
  expect(renderer.root.findAllByProps({id:'hub-address'})).toHaveLength(1);
  await act(async () => connectionControl.update!({hub,status:'connected',persisted:true}));
  expect(renderer.root.findAllByProps({className:'auth-panel'})).toHaveLength(1);
});
it('omits the archived Home section without changing the cached account', async () => {
  const cached = {...account,classrooms:[...classrooms,{...classrooms[0],id:'archived',name:'Archived QA',archived_at:1}]};
  useStore.setState({account:cached});
  await act(async () => { renderer = create(createElement(App)); });
  expect(renderer.root.findAllByProps({id:'archived-classes'})).toHaveLength(0);
  expect(useStore.getState().account!.classrooms.some(room => room.id === 'archived')).toBe(true);
});
