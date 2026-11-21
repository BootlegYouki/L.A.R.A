import { afterEach, expect, it, vi } from 'vitest';
const { invoke }=vi.hoisted(()=>({invoke:vi.fn()}));
vi.mock('@tauri-apps/api/core',()=>({invoke}));
import { waitForCachedAssignment } from '../src/services/classworkCache';
import type { CachedAccount } from '../src/services/auth';

const account={hub:{ip:'127.0.0.1',http_port:8080},user:{id:'teacher'}} as CachedAccount;
const content=(ids:string[])=>({assignments:ids.map(id=>({id}))});
afterEach(()=>{vi.useRealTimers();vi.clearAllMocks();});

it('waits for the sync cache to contain the new assignment',async()=>{
  vi.useFakeTimers();invoke.mockRejectedValueOnce(new Error('database busy')).mockResolvedValueOnce(content([])).mockResolvedValueOnce(content(['assignment']));
  const waiting=waitForCachedAssignment(account,'class','assignment');
  await vi.advanceTimersByTimeAsync(250);await waiting;
  expect(invoke).toHaveBeenCalledTimes(3);
  expect(invoke).toHaveBeenCalledWith('read_class_content',{hub:account.hub,userId:'teacher',classroomId:'class'});
});

it('stops after the bounded wait when the cache never updates',async()=>{
  vi.useFakeTimers();invoke.mockRejectedValue(new Error('database unavailable'));
  const waiting=waitForCachedAssignment(account,'class','assignment');
  const rejected=expect(waiting).rejects.toThrow('within 10 seconds');
  await vi.advanceTimersByTimeAsync(10_000);await rejected;
  expect(invoke.mock.calls.length).toBeLessThanOrEqual(101);
});

it('aborts promptly while waiting for the cache',async()=>{
  vi.useFakeTimers();invoke.mockResolvedValue(content([]));const controller=new AbortController();
  const waiting=waitForCachedAssignment(account,'class','assignment',controller.signal);
  await Promise.resolve();controller.abort(new Error('cancelled'));
  await expect(waiting).rejects.toThrow('cancelled');
});
