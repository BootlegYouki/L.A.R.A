import { beforeEach, expect, it, vi } from 'vitest';

const load = vi.hoisted(() => vi.fn());
vi.mock('@tauri-apps/plugin-sql', () => ({ default: { load } }));

beforeEach(() => { vi.resetModules(); load.mockReset(); });

it('retries a failed open instead of caching a rejected promise', async () => {
  const db = { execute: vi.fn().mockResolvedValue({}), select: vi.fn().mockResolvedValue([]) };
  load.mockRejectedValueOnce(new Error('busy')).mockResolvedValueOnce(db);
  const { readSavedClasses } = await import('../src/services/db');
  await expect(readSavedClasses()).rejects.toThrow('busy');
  await expect(readSavedClasses()).resolves.toEqual([]);
  expect(load).toHaveBeenCalledTimes(2);
  expect(db.execute).toHaveBeenCalledWith('PRAGMA foreign_keys = ON');
});

it('times out a stalled native request and permits a fresh open', async () => {
  vi.useFakeTimers();
  try {
    const db = { execute: vi.fn().mockResolvedValue({}), select: vi.fn().mockResolvedValue([]) };
    load.mockReturnValueOnce(new Promise(() => {})).mockResolvedValueOnce(db);
    const { readSavedClasses } = await import('../src/services/db');
    const pending = expect(readSavedClasses()).rejects.toThrow('Local database did not respond');
    await vi.advanceTimersByTimeAsync(15_000);
    await pending;
    await expect(readSavedClasses()).resolves.toEqual([]);
    expect(load).toHaveBeenCalledTimes(2);
  } finally {
    vi.useRealTimers();
  }
});
