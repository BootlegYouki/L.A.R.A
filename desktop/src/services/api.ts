import { fetch } from '@tauri-apps/plugin-http';
import { isHubInfo, type HubInfo } from './discovery';

// Reachability only: no credentials, classroom data or sign-in state is accepted here.
export async function probeHub(hub: HubInfo, signal?: AbortSignal): Promise<boolean> {
  if (!isHubInfo(hub) || signal?.aborted) return false;
  const url = new URL(`http://${hub.ip}:${hub.http_port}/api/classrooms`).href;
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let cancel!: () => void;
  const cancelled = new Promise<false>((resolve) => {
    cancel = () => { controller.abort(); resolve(false); };
    timer = setTimeout(cancel, 5000);
  });
  signal?.addEventListener('abort', cancel, { once: true });
  try {
    return await Promise.race([cancelled, fetch(url, {
      method: 'GET', credentials: 'omit', redirect: 'error', maxRedirections: 0,
      connectTimeout: 5000, signal: controller.signal,
    }).then((response) => {
      void response.body?.cancel().catch(() => {});
      return response.url === url && (response.status === 200 || response.status === 401);
    }).catch(() => false)]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
}
