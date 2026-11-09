import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import RosterPage from '../src/pages/RosterPage';
import { formatClassCode, validClassroomName } from '../src/components/CreateClassroomDialog';
import type { AuthUser, Classroom, RosterEntry } from '../src/services/auth';
const user: AuthUser = { id: 'private-teacher', lrn_or_id: 'T-0001', full_name: 'Teacher', role: 'TEACHER' };
const classroom: Classroom = { id: 'private-class', name: 'Science', section: 'A', class_code: 'K7M4QX', teacher_id: user.id };
const noop = () => {};
const roster: RosterEntry[] = ['PENDING', 'ACTIVE', 'REJECTED', 'REMOVED'].map((status, index) => ({ enrollment_id: `private-enrollment-${index}`, student_id: `private-student-${index}`, full_name: `Learner ${index}`, status: status as RosterEntry['status'] }));
const render = (props = {}) => renderToStaticMarkup(createElement(RosterPage, { classroom, user, roster, loading: false, error: null, offline: false, busy: false, retryAfter: 0, onRetry: noop, onDecide: noop, ...props }));
it('fails closed for learners and keeps identifiers private', () => {
  expect(render({ user: { ...user, role: 'STUDENT' } })).toBe('');
  expect(render()).not.toContain('private-'); expect(render()).not.toContain('T-0001');
});
it('renders retained statuses with only their allowed actions', () => {
  const html = render();
  for (const text of ['Pending', 'Active', 'Rejected', 'Removed', 'Accept', 'Decline', 'Remove']) expect(html).toContain(text);
  expect(html.match(/>Accept</g)).toHaveLength(1); expect(html.match(/>Remove</g)).toHaveLength(1);
  expect(html).toContain('scope="col"');
});
it('keeps cached rows read only offline and during cooldown', () => {
  const html = render({ offline: true }); expect(html).toContain('Learner 0'); expect(html).toContain('read only');
  expect(html).toMatch(/disabled=""[^>]*>Accept/); expect(render({ retryAfter: 3 })).toMatch(/disabled=""[^>]*>Accept/);
});
it('distinguishes loading, failed and confirmed empty roster', () => {
  expect(render({ roster: undefined, loading: true })).toContain('skeleton');
  expect(render({ roster: undefined, error: 'Failed' })).not.toContain('No learners yet');
  expect(render({ roster: [] })).toContain('No learners yet'); expect(render({ error: 'Failed' })).toContain('Learner 0');
});
it('formats generated codes and validates a required trimmed name', () => {
  expect(formatClassCode('K7M4QX')).toBe('K7M-4QX'); expect(validClassroomName('   ')).toBe(false); expect(validClassroomName(' Science ')).toBe(true);
});
import Sidebar from '../src/components/Sidebar';
import HomePage from '../src/pages/HomePage';
import TopAppBar from '../src/components/TopAppBar';
it('shows only known teacher pending counts', () => {
  const props = { user, classrooms: [classroom], onHome: noop, onOpen: noop, pendingCounts: { [classroom.id]: 2 } };
  expect(renderToStaticMarkup(createElement(Sidebar, props))).toContain('2 pending join requests');
  expect(renderToStaticMarkup(createElement(Sidebar, { ...props, user: { ...user, role: 'STUDENT' }, classrooms: [{ ...classroom, enrollment_status: 'ACTIVE' }] }))).not.toContain('pending join requests');
  expect(renderToStaticMarkup(createElement(Sidebar, { ...props, pendingCounts: {} }))).not.toContain('pending-count');
});
it('enables teacher creation only with a callback and keeps codes off Home', () => {
  const props = { user, classrooms: [], loading: false, error: null, onRetry: noop, onOpen: noop };
  expect(renderToStaticMarkup(createElement(HomePage, props))).toMatch(/disabled=""[^>]*>.*Create class/s);
  expect(renderToStaticMarkup(createElement(HomePage, { ...props, onCreate: noop }))).not.toContain('disabled=""');
  expect(renderToStaticMarkup(createElement(HomePage, { ...props, classrooms: [classroom], onCreate: noop }))).not.toContain('K7M');
});
it('displays the selected class and formatted code in the teacher bar', () => {
  const html = renderToStaticMarkup(createElement(TopAppBar, { classroom, onHome: noop }));
  expect(html).toContain('Science'); expect(html).toContain('K7M-4QX'); expect(html).toContain('Copy class code');
  expect(html).not.toContain('Class code copied.');
});
