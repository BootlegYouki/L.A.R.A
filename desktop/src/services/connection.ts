import { probeHub } from './api';
import { isHubInfo, type HubInfo } from './discovery';
import { readHubPreference, saveHubPreference } from './settings';

export interface HubConnectionState {
  status: 'offline' | 'connecting' | 'connected';
  hub: HubInfo | null;
  persisted: boolean;
}
export function observeHubConnection(update: (state: HubConnectionState) => void) {
  let target = readHubPreference();
  let persisted = true;
  let saveAttempted = target !== null;
  let stopped = false;
  let generation = 0;
  let misses = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let request: AbortController | undefined;
  const run = async () => {
    if (stopped || !target) return;
    const current = generation, hub = target;
    request = new AbortController();
    const reachable = await probeHub(hub, request.signal);
    if (stopped || current !== generation) return;
    if (reachable && !saveAttempted) {
      persisted = saveHubPreference(hub);
      saveAttempted = true;
    }
    update({ status: reachable ? 'connected' : 'offline', hub, persisted });
    const delay = reachable ? 3000 : Math.min(1000 * 2 ** misses, 10000);
    misses = reachable ? 0 : Math.min(misses + 1, 4);
    timer = setTimeout(() => { void run(); }, delay);
  };
  const select = (hub: HubInfo) => {
    if (stopped || !isHubInfo(hub)) return;
    generation++;
    request?.abort();
    clearTimeout(timer);
    target = hub;
    misses = 0;
    saveAttempted = false;
    persisted = false;
    update({ status: 'connecting', hub, persisted });
    void run();
  };
  if (target) {
    update({ status: 'connecting', hub: target, persisted });
    void run();
  }
  return {
    select,
    discovered: (hubs: HubInfo[]) => { if (!target && hubs.length > 0) select(hubs[0]); },
    stop: () => { stopped = true; generation++; clearTimeout(timer); request?.abort(); },
  };
}
