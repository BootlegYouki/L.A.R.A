import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import HomePage from '../src/pages/HomePage';
import LoginPage, { validateLoginFields } from '../src/pages/LoginPage';
import Sidebar from '../src/components/Sidebar';
import type { AuthUser, Classroom } from '../src/services/auth';
const user: AuthUser = { id: 'u', lrn_or_id: 'T-0001', full_name: 'Teacher', role: 'TEACHER' };
const classroom: Classroom = { id: 'c', name: '<script>bad()</script>', section: 'A', class_code: 'SECRET', teacher_id: 'u' };
const noop = () => {};
it('keeps sidebar order stable across refreshed snapshots and class selection without mutating data', () => {
  const rooms = [
    { ...classroom, id: 'z', name: 'Science', section: 'B' },
    { ...classroom, id: 'a', name: 'Art', section: 'A' },
    { ...classroom, id: 's', name: 'Science', section: 'A' },
  ];
  const labels = (classes: Classroom[], selectedId: string) => renderToStaticMarkup(createElement(Sidebar, { user, classrooms: classes, selectedId, onHome: noop, onOpen: noop })).match(/<strong>.*?<\/strong><small>.*?<\/small>/g);
  const expected = ['<strong>Art</strong><small>A</small>', '<strong>Science</strong><small>A</small>', '<strong>Science</strong><small>B</small>'];
  expect(labels(rooms, 'z')).toEqual(expected);
  expect(labels([...rooms].reverse(), 'a')).toEqual(expected);
  expect(rooms.map(room => room.id)).toEqual(['z', 'a', 's']);
});
const home = (props = {}) => renderToStaticMarkup(createElement(HomePage, { user, classrooms: [], loading: false, error: null, onRetry: noop, onOpen: noop, ...props }));
it('preserves escaped cached cards during refresh and error without class codes or fictional progress', () => {
  const html = home({ classrooms: [classroom], loading: true, error: 'Refresh failed' });
  expect(html).toContain('&lt;script&gt;bad()&lt;/script&gt;');
  expect(html).toContain('Refresh failed');
  expect(html).toContain('Class details saved on this device');
  expect(html).not.toContain('SECRET');
  expect(html).not.toContain('All caught up');
});
it('keeps loading, failed and empty states distinct with disabled role actions', () => {
  expect(home({ loading: true })).toContain('class-skeleton');
  expect(home({ loading: true })).not.toContain('Add a class');
  expect(home({ error: 'Failed' })).toContain('Try again');
  expect(home({ error: 'Failed' })).not.toContain('Add a class');
  expect(home()).toMatch(/disabled=""[^>]*>.*Create class/s);
  expect(home({ user: { ...user, role: 'STUDENT' } })).toContain('Join class');
});
it('shows decorative owl art and keeps Home empty actions connected to the existing callbacks', () => {
  const html = home({ onCreate: noop });
  expect(html).toContain('class="empty-state empty-home"');
  expect(html).toContain('alt=""');
  expect(html).toMatch(/<button[^>]*>.*Create class/s);
  expect(home({ user: { ...user, role: 'STUDENT' }, onJoin: noop })).toMatch(/<button[^>]*>.*Join class/s);
});
it('disables classroom retries during the server cooldown while keeping cached cards', () => {
  const html = home({ classrooms: [classroom], error: 'Wait', retryAfter: 17 });
  expect(html).toContain('17 seconds');
  expect(html).toMatch(/disabled=""[^>]*>Try again/);
  expect(html).toContain('Class details saved on this device');
});
it('uses role labels and current navigation without class codes', () => {
  const html = renderToStaticMarkup(createElement(Sidebar, { user, classrooms: [classroom], onHome: noop, onOpen: noop }));
  expect(html).toContain('Teaching'); expect(html).toContain('aria-current="page"'); expect(html).not.toContain('SECRET');
  expect(renderToStaticMarkup(createElement(Sidebar, { user: { ...user, role: 'STUDENT' }, classrooms: [], onHome: noop, onOpen: noop }))).not.toContain('Enrolled');
  expect(renderToStaticMarkup(createElement(Sidebar, { user, classrooms: [], onHome: noop, onOpen: noop }))).not.toContain('Teaching');
});
it('masks the PIN and disables submission until a Hub is selected', () => {
  const html = renderToStaticMarkup(createElement(LoginPage, { hub: null, busy: false, error: null, retryAfter: 9, onSubmit: noop }));
  expect(html).toContain('type="password"'); expect(html).toContain('maxLength="6"'); expect(html).toContain('Six-digit PIN'); expect(html).toContain('9 seconds'); expect(html).toContain('Select a Hub address'); expect(html).toContain('disabled=""');
});
it('validates sign-in fields before submission', () => {
  expect(validateLoginFields({ lrn_or_id: 'T-0001', pin: '123456' })).toEqual({});
  expect(validateLoginFields({ lrn_or_id: 'x'.repeat(33), pin: 'abcd' })).toHaveProperty('pin');
  expect(validateLoginFields({ lrn_or_id: '', pin: '123456' })).toHaveProperty('lrn_or_id');
});

it('requires exactly six ASCII digits for sign-in PIN', () => {
  const fields = { lrn_or_id: 'T-0001' };
  for (const pin of ['123456', '000001']) expect(validateLoginFields({ ...fields, pin })).toEqual({});
  for (const pin of ['', '1234', '12345', '1234567', '12a456', '１２３４５６', ' 123456']) {
    expect(validateLoginFields({ ...fields, pin })).toHaveProperty('pin');
  }
});
