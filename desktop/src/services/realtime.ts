import { accountToken, type CachedAccount, type JoinReceipt } from './auth';
import { isHubInfo } from './discovery';
import { ApiError } from './api';
import {ensureWebSocketPolicy} from './websocket-policy';

const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
// Events are hints: the caller refreshes authenticated REST data before unlocking a class.
// shortcut: reconnect refreshes membership only; #48 adds full sync and missed rejection recovery.
export function observeJoinUpdates(account: CachedAccount, update: (receipt?: JoinReceipt) => Promise<void>, unauthorized: () => void,
  teacherRequest?: (classCode?: string) => Promise<void>, syncHint?:()=>void): () => void {
  let stopped = false, attempt = 0, socket: WebSocket | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let handshake: ReturnType<typeof setTimeout> | undefined;
  let pending = Promise.resolve(), queued = 0;
  const valid = isHubInfo(account.hub);
  function expire() { if (!stopped) { stop(); unauthorized(); } }
  function enqueue(receipt?: JoinReceipt | string) {
    if (queued >= 32) { socket?.close(); return; }
    queued++;
    pending = pending.then(async () => {
      if (stopped) return;
      if (typeof receipt === 'string') await teacherRequest?.(receipt);
      else { await update(receipt); if (!receipt && account.user.role === 'TEACHER') await teacherRequest?.(); }
    }).catch(() => {
      // The caller owns visible REST/cache errors; the socket remains recoverable.
    }).finally(() => { queued--; });
  }
  function retry() {
    clearTimeout(handshake);
    if (stopped) return;
    timer = setTimeout(() => { void connect(); }, Math.min(1000 * 2 ** Math.min(attempt++, 4), 10000));
  }
  async function connect() {
    let token: string;
    try { token = await accountToken(account); }
    catch (error) { if (error instanceof ApiError && error.code === 'reauth_required') expire(); else retry(); return; }
    if (stopped) return;
    let ws: WebSocket;
    try { ws = new WebSocket(`ws://${account.hub.ip}:${account.hub.ws_port}`); }
    catch { retry(); return; }
    socket = ws;
    let acknowledged = false;
    handshake = setTimeout(() => ws.close(), 10000);
    ws.onopen = () => { if (!stopped) ws.send(JSON.stringify({ event: 'EVENT_HELLO', token })); token = ''; };
    ws.onmessage = (message) => {
      if (stopped || socket !== ws || typeof message.data !== 'string' || message.data.length > 65536) return;
      let frame: unknown;
      try { frame = JSON.parse(message.data); } catch { return; }
      if (typeof frame !== 'object' || frame === null || !('event' in frame)) return;
      const value = frame as Record<string, unknown>;
      if (value.event === 'EVENT_ERROR' && value.code === 'UNAUTHORIZED') { expire(); return; }
      if (value.event === 'EVENT_HELLO_ACK') {
        if (acknowledged || typeof value.hub_id !== 'string' || value.hub_id.length === 0 || value.hub_id.length > 256 || !Number.isSafeInteger(value.server_time) || !Number.isSafeInteger(value.sync_epoch) || Number(value.sync_epoch) < 0) return;
        acknowledged = true; attempt = 0; clearTimeout(handshake); enqueue();syncHint?.();
      } else if(acknowledged&&value.event==='EVENT_ANNOUNCEMENT_PUSH'&&uuid(value.classroom_id)&&uuid(value.announcement_id)
        &&typeof value.title==='string'&&Number.isSafeInteger(value.timestamp)) {
        syncHint?.();
      } else if (acknowledged && value.event === 'EVENT_JOIN_APPROVAL'
        && value.student_id === account.user.id && uuid(value.classroom_id)
        && Number.isSafeInteger(value.timestamp) && ['ACTIVE', 'REJECTED', 'REMOVED'].includes(String(value.status))) {
        enqueue({ classroom_id: value.classroom_id, status: value.status as JoinReceipt['status'] });
      } else if (acknowledged && account.user.role === 'TEACHER' && value.event === 'EVENT_JOIN_REQUEST'
        && typeof value.class_code === 'string' && /^[A-HJ-NP-Z2-9]{6}$/.test(value.class_code)
        && uuid(value.student_id) && Number.isSafeInteger(value.timestamp)) {
        // Discard names/LRNs from event payloads; only authenticated roster GET populates rows.
        enqueue(value.class_code);
      }
    };
    ws.onerror = () => ws.close();
    ws.onclose = (event) => {
      if (socket !== ws || stopped) return;
      socket = undefined;
      if (event.code === 4401) expire(); else retry();
    };
  }
  function stop() {
    stopped = true; clearTimeout(timer); clearTimeout(handshake);
    if (socket) { socket.onopen = null; socket.onmessage = null; socket.onclose = null; socket.onerror = null; socket.close(); socket = undefined; }
  }
  if (valid && ensureWebSocketPolicy(account.hub)) void connect();
  return stop;
}
