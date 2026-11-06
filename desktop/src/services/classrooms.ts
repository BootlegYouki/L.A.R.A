import { invoke } from '@tauri-apps/api/core';
import { ApiError, requestHub } from './api';
import { accountToken, forgetAccount, parseAccount, parseJoinReceipt, type CachedAccount, type JoinReceipt } from './auth';
import { withDatabaseTimeout } from './db';

export function normalizeClassCode(value: string): string | null {
  const code = value.replace(/[\s-]/g, '').toUpperCase();
  return /^[A-HJ-NP-Z2-9]{6}$/.test(code) ? code : null;
}
export async function saveJoinStatus(account: CachedAccount, receipt: JoinReceipt): Promise<CachedAccount> {
  return parseAccount(await withDatabaseTimeout(invoke('save_join_status', { hub: account.hub, userId: account.user.id, receipt: parseJoinReceipt(receipt) })), account.hub);
}
export async function joinClass(account: CachedAccount, input: string, signal?: AbortSignal): Promise<CachedAccount> {
  if (account.user.role !== 'STUDENT') throw new ApiError('FORBIDDEN');
  const class_code = normalizeClassCode(input);
  if (!class_code) throw new ApiError('invalid_code');
  const token = await accountToken(account);
  let receipt: JoinReceipt;
  try { receipt = parseJoinReceipt(await requestHub(account.hub, '/api/classrooms/join', { body: { class_code }, token, signal })); }
  catch (error) {
    if (error instanceof ApiError && error.code === 'UNAUTHORIZED') await forgetAccount(account).catch(() => {});
    throw error;
  }
  // A successful POST must be remembered even when the user closes its dialog.
  return saveJoinStatus(account, receipt);
}
