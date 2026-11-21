import { act, create } from 'react-test-renderer';
import { createElement, type ReactNode } from 'react';
import { expect, it, vi } from 'vitest';
import type { CachedAccount } from '../src/services/auth';
const {update, remove} = vi.hoisted(() => ({update: vi.fn(), remove: vi.fn()}));
vi.mock('../src/services/classworkMutations', () => ({updateClasswork: update, deleteClasswork: remove}));
vi.mock('@radix-ui/react-dialog', () => { const wrap = ({children}: {children?: ReactNode}) => children; return {Root: wrap, Portal: wrap, Overlay: () => null, Content: 'section', Title: 'h2', Description: 'p'}; });
import ClassworkEditDialog from '../src/components/ClassworkEditDialog';

const id = '11111111-1111-4111-8111-111111111111';
const account = {user: {id, role: 'TEACHER'}, classrooms: [{id, teacher_id: id}]} as unknown as CachedAccount;
it('submits edited assignment fields and keeps the form mounted while saving', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true); update.mockResolvedValue(id);
  const closed = vi.fn(), changed = vi.fn();
  let view!: ReturnType<typeof create>;
  await act(async () => { view = create(createElement(ClassworkEditDialog, {account, classroomId: id, item: {kind: 'assignment', id, title: 'Old', instructions: 'Old text', max_points: 10, due_date: 1_800_000_000_000, allow_late: false, topic_id: null}, topics: [], offline: false, onClose: closed, onChanged: changed})); });
  await act(async () => view.root.findByProps({id: 'classwork-edit-title'}).props.onChange({target: {value: 'New'}}));
  await act(async () => view.root.findByType('form').props.onSubmit({preventDefault() {}}));
  expect(update).toHaveBeenCalledWith(account, id, 'assignment', id, expect.objectContaining({title: 'New', instructions: 'Old text', max_points: 10, allow_late: false, topic_id: null}), expect.any(AbortSignal));
  expect(changed).toHaveBeenCalledOnce(); expect(closed).toHaveBeenCalledOnce();
  await act(async () => view.unmount()); vi.unstubAllGlobals();
});

it('confirms a deletion through the mutation service', async () => {
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true); remove.mockResolvedValue(undefined);
  const closed = vi.fn(), changed = vi.fn();
  let view!: ReturnType<typeof create>;
  await act(async () => { view = create(createElement(ClassworkEditDialog, {account, classroomId: id, item: {kind: 'topic', id, name: 'Unit', order_index: 0}, topics: [], offline: false, action: 'delete', onClose: closed, onChanged: changed})); });
  await act(async () => view.root.findByType('form').props.onSubmit({preventDefault() {}}));
  expect(remove).toHaveBeenCalledWith(account, id, 'topic', id, expect.any(AbortSignal));
  expect(changed).toHaveBeenCalledOnce(); expect(closed).toHaveBeenCalledOnce();
  await act(async () => view.unmount()); vi.unstubAllGlobals();
});
