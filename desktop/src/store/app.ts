import { create } from 'zustand';
import { restoreAccount, type CachedAccount } from '../services/auth';
import type { HubInfo } from '../services/discovery';

interface AppState {
  status: 'loading' | 'ready' | 'error';
  account: CachedAccount | null;
  loadAccount: (hub: HubInfo | null) => Promise<void>;
  setAccount: (account: CachedAccount | null) => void;
}

let loading: Promise<void> | undefined;
let loadingKey = '';
let generation = 0;

export const useAppStore = create<AppState>((set) => ({
  status: 'loading',
  account: null,
  setAccount: (account) => { generation++; loading = undefined; set({ account, status: 'ready' }); },
  loadAccount: (hub) => {
    const key = hub ? `${hub.ip}:${hub.http_port}` : '';
    if (loading && loadingKey === key) return loading;
    const current = ++generation;
    loadingKey = key;
    set((state) => ({ status: 'loading', account: state.account && `${state.account.hub.ip}:${state.account.hub.http_port}` === key ? state.account : null }));
    loading = (hub ? restoreAccount(hub) : Promise.resolve(null))
      .then((account) => { if (current === generation) set({ account, status: 'ready' }); })
      .catch(() => { if (current === generation) set({ status: 'error' }); })
      .finally(() => { if (current === generation) loading = undefined; });
    return loading;
  },
}));
