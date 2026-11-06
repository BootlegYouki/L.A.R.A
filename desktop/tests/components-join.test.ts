import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import HomePage from '../src/pages/HomePage';
import Sidebar from '../src/components/Sidebar';
import { normalizeClassCode } from '../src/components/JoinClassModal';
import type { AuthUser, Classroom } from '../src/services/auth';
const user: AuthUser = { id: 'student', lrn_or_id: '123456789013', full_name: 'Ana', role: 'STUDENT' };
const noop = () => {};
const classroom: Classroom = { id: 'c', name: 'Science', section: 'A', teacher_id: 'teacher', class_code: 'SECRET', enrollment_status: 'PENDING' };
it('accepts teacher code formatting and rejects ambiguous, incomplete or extra characters', () => {
  expect(normalizeClassCode(' k7m-4qx ')).toBe('K7M4QX');
  for (const code of ['K7M4Q', 'K7M4QXX', 'K7O4QX', 'K7I4QX', 'K704QX', 'K714QX', 'K7M!4QX']) expect(normalizeClassCode(code)).toBeNull();
});
it('pending metadata cannot open a class or expose its code; active cards can open', () => {
  const html = renderToStaticMarkup(createElement(HomePage, { user, classrooms: [classroom], loading: false, error: null, onRetry: noop, onOpen: noop, onJoin: noop }));
  expect(html).toContain('Waiting for approval'); expect(html).not.toContain('>Open'); expect(html).not.toContain('SECRET');
  expect(renderToStaticMarkup(createElement(Sidebar, { user, classrooms: [classroom], onHome: noop, onOpen: noop }))).not.toContain('Science');
  expect(renderToStaticMarkup(createElement(HomePage, { user, classrooms: [{ ...classroom, enrollment_status: 'ACTIVE' }], loading: false, error: null, onRetry: noop, onOpen: noop }))).toContain('>Open');
});
it('retains receipt statuses without metadata and never grants access on an unknown approval', () => {
  const html = renderToStaticMarkup(createElement(HomePage, { user, classrooms: [], loading: false, error: null, onRetry: noop, onOpen: noop, joinRequests: [{ classroom_id: 'r', status: 'REJECTED' }, { classroom_id: 'm', status: 'REMOVED' }, { classroom_id: 'a', status: 'ACTIVE' }] }));
  expect(html).toContain('Request not approved'); expect(html).toContain('Class access removed'); expect(html).toContain('Waiting for class details'); expect(html).not.toContain('>Open'); expect(html).not.toContain('Add a class');
});
