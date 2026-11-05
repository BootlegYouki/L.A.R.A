import { create } from 'zustand';
import { readSavedClasses, type SavedClassroom } from '../services/db';

interface AppState {
  status: 'loading' | 'ready' | 'error';
  classrooms: SavedClassroom[];
  loadSavedClasses: () => Promise<void>;
}

let loading: Promise<void> | undefined;

export const useAppStore = create<AppState>((set) => ({
  status: 'loading',
  classrooms: [],
  loadSavedClasses: () => {
    if (loading) return loading;
    set({ status: 'loading' });
    loading = readSavedClasses()
      .then((classrooms) => { set({ classrooms, status: 'ready' }); })
      .catch(() => { set({ status: 'error' }); })
      .finally(() => { loading = undefined; });
    return loading;
  },
}));
