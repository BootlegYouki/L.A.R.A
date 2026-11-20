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
vi.mock('../src/services/connection', () => ({ observeHubConnection: (update: (state: unknown) => void) => { update({ hub, status: 'connected', persisted: true }); return { stop: () => {}, discovered: () => {} }; } }));
vi.mock('../src/services/discovery', () => ({ observeDiscovery: () => () => {} }));
vi.mock('../src/services/auth', async original => ({ ...await original<typeof import('../src/services/auth')>(), refreshClassrooms: async () => classrooms }));
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
it.each(['Home', 'Archived', 'Class', 'Settings'] as const)('leaves a lesson video when global %s is selected', async destination => {
  await openVideo();
  const sidebar = renderer.root.findByType(Sidebar);
  await act(async () => {
    if (destination === 'Class') sidebar.props.onOpen(classrooms[1].id);
    else sidebar.findByProps({ 'aria-label': { Home: t.home, Archived: t.archived_classes, Settings: t.settings }[destination] }).props.onClick();
  });
  expect(renderer.root.findAllByType(VideoLessonPage)).toHaveLength(0);
  if (destination === 'Class') expect(renderer.root.findByType(ClassPage).props.classroom.id).toBe(classrooms[1].id);
  else if (destination === 'Settings') expect(renderer.root.findAllByType(SettingsPage)).toHaveLength(1);
  else expect(renderer.root.findByType(HomePage).props.archiveVisit).toBe(destination === 'Archived' ? 1 : 0);
});
it('returns from a video to its original class with Back', async () => {
  await openVideo();
  await act(async () => renderer.root.findByType(VideoLessonPage).props.onBack());
  expect(renderer.root.findAllByType(VideoLessonPage)).toHaveLength(0);
  expect(renderer.root.findByType(ClassPage).props.classroom.id).toBe(classrooms[0].id);
});
