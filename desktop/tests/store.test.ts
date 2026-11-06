import { beforeEach, describe, expect, it, vi } from 'vitest';
import { restoreAccount, type CachedAccount } from '../src/services/auth';
import { useAppStore } from '../src/store/app';
vi.mock('../src/services/auth', () => ({ restoreAccount: vi.fn() }));
const restore = vi.mocked(restoreAccount);
const hub = {name:'Hub',ip:'192.168.1.50',http_port:8080,ws_port:8081};
const cached: CachedAccount = {hub,user:{id:'10000000-0000-4000-8000-000000000001',lrn_or_id:'T-0001',full_name:'Teacher',role:'TEACHER'},expires_at:1,persistent:true,classrooms:[]};
describe('account-isolated offline state', () => {
 beforeEach(()=>{restore.mockReset();useAppStore.getState().setAccount(null)});
 it('restores cached accounts without a network request',async()=>{
  const fetch=vi.fn();vi.stubGlobal('fetch',fetch);restore.mockResolvedValue(cached);
  await useAppStore.getState().loadAccount(hub);
  expect(useAppStore.getState()).toMatchObject({status:'ready',account:cached});expect(fetch).not.toHaveBeenCalled();vi.unstubAllGlobals();
 });
 it('preserves the matching cached account on a read failure and allows retry',async()=>{
  useAppStore.getState().setAccount(cached);restore.mockRejectedValueOnce(new Error('busy')).mockResolvedValueOnce(cached);
  await useAppStore.getState().loadAccount(hub);expect(useAppStore.getState()).toMatchObject({status:'error',account:cached});
  await useAppStore.getState().loadAccount(hub);expect(useAppStore.getState().status).toBe('ready');
 });
 it('deduplicates restores and ignores a stale result after switching Hub',async()=>{
  let resolve!:(value:CachedAccount)=>void;restore.mockReturnValueOnce(new Promise(done=>{resolve=done})).mockResolvedValueOnce(null);
  const first=useAppStore.getState().loadAccount(hub);expect(useAppStore.getState().loadAccount(hub)).toBe(first);
  await useAppStore.getState().loadAccount({...hub,ip:'192.168.1.51'});resolve(cached);await first;
  expect(useAppStore.getState()).toMatchObject({account:null,status:'ready'});expect(restore).toHaveBeenCalledTimes(2);
 });
 it('never hydrates language or account rows from browser preferences',()=>{
  expect(useAppStore.getState()).not.toHaveProperty('language');expect(useAppStore).not.toHaveProperty('persist');
 });
});
