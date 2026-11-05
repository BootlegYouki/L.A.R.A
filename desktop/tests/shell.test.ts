import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, expect, it, vi } from 'vitest';
import App from '../src/App';

const { state } = vi.hoisted(() => ({ state: {
  language: 'en' as 'en' | 'fil', status: 'ready' as 'ready' | 'loading' | 'error',
  classrooms: [] as { id: string; name: string; section: string }[],
  toggleLanguage: vi.fn(), loadSavedClasses: vi.fn(),
} }));
vi.mock('../src/store/app', () => ({ useAppStore: () => state }));
beforeEach(() => { state.language = 'en'; state.status = 'ready'; state.classrooms = []; });
const render = () => renderToStaticMarkup(createElement(App));

it('offers the prototype empty Home shell without fake classes or active join actions', () => {
  const html = render();
  expect(html).toContain('Add a class to get started');
  expect(html).toContain('Hub not reachable. You are seeing what is saved on this device.');
  expect(html).toContain('aria-expanded="true"');
  expect(html).toContain('aria-current="page"');
  expect(html).toContain('ph-fill ph-house');
  expect(html).toMatch(/disabled="" aria-describedby="offline-notice"/);
  expect(html).not.toContain('next desktop steps');
  expect(html).not.toContain('Science 4');
});
it('renders English even if an old Filipino preference is present', () => {
  state.language = 'fil';
  const html = render();
  expect(html).toContain('Add a class to get started');
  expect(html).not.toContain('Switch to Filipino');
});
it('shows a retry rather than a false empty state when SQLite cannot open', () => {
  state.status = 'error';
  const html = render();
  expect(html).toContain('role="alert"');
  expect(html).toContain('Your saved work has not been cleared.');
  expect(html).toContain('Try again');
  expect(html).not.toContain('Add a class to get started');
});
it('announces loading before offering an empty state', () => {
  state.status = 'loading';
  const html = render();
  expect(html).toContain('Opening your saved classes');
  expect(html).not.toContain('Add a class to get started');
});
it('escapes saved class content and never displays a class code', () => {
  state.classrooms = [{ id: 'saved', name: '<script>bad()</script>', section: 'A' }];
  const html = render();
  expect(html).toContain('&lt;script&gt;bad()&lt;/script&gt;');
  expect(html).not.toContain('<script>');
  expect(html).toContain('Saved on this device');
});
