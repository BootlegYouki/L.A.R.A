import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readSavedClasses } from '../src/services/db';
import { useAppStore } from '../src/store/app';
import { dictionaries } from '../src/i18n';

vi.mock('../src/services/db', () => ({ readSavedClasses: vi.fn() }));
const read = vi.mocked(readSavedClasses);

describe('offline application state', () => {
  beforeEach(() => {
    read.mockReset();
    useAppStore.setState({ language: 'en', status: 'loading', classrooms: [] });
  });

  it('loads cached classes without making a network request', async () => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const classrooms = [{ id: 'saved', name: 'Science', section: 'A' }];
    read.mockResolvedValue(classrooms);
    await useAppStore.getState().loadSavedClasses();
    expect(useAppStore.getState()).toMatchObject({ status: 'ready', classrooms });
    expect(fetch).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it('handles a database failure without deleting cached work and allows retry', async () => {
    const classrooms = [{ id: 'saved', name: 'Science', section: 'A' }];
    useAppStore.setState({ classrooms });
    read.mockRejectedValueOnce(new Error('database unavailable')).mockResolvedValueOnce(classrooms);
    await useAppStore.getState().loadSavedClasses();
    expect(useAppStore.getState()).toMatchObject({ status: 'error', classrooms });
    await useAppStore.getState().loadSavedClasses();
    expect(useAppStore.getState()).toMatchObject({ status: 'ready', classrooms });
  });

  it('deduplicates concurrent startup reads', async () => {
    let resolve: (value: []) => void = () => {};
    read.mockReturnValue(new Promise<[]>((done) => { resolve = done; }));
    const first = useAppStore.getState().loadSavedClasses();
    const second = useAppStore.getState().loadSavedClasses();
    expect(first).toBe(second);
    expect(read).toHaveBeenCalledTimes(1);
    resolve([]);
    await first;
    expect(useAppStore.getState().status).toBe('ready');
  });

  it('switches language even when preference storage is unavailable', () => {
    expect(Object.keys(dictionaries.en).sort()).toEqual(Object.keys(dictionaries.fil).sort());
    useAppStore.getState().toggleLanguage();
    expect(useAppStore.getState().language).toBe('fil');
    useAppStore.getState().toggleLanguage();
    expect(useAppStore.getState().language).toBe('en');
  });
});
