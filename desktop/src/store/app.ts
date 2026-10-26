import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import { readSavedClasses, type SavedClassroom } from '../services/db';
import type { Language } from '../i18n';

interface AppState {
  language: Language;
  status: 'loading' | 'ready' | 'error';
  classrooms: SavedClassroom[];
  toggleLanguage: () => void;
  loadSavedClasses: () => Promise<void>;
}

let loading: Promise<void> | undefined;

export const useAppStore = create<AppState>()(persist((set, get) => ({
  language: 'en',
  status: 'loading',
  classrooms: [],
  toggleLanguage: () => set({ language: get().language === 'en' ? 'fil' : 'en' }),
  loadSavedClasses: () => {
    if (loading) return loading;
    set({ status: 'loading' });
    loading = readSavedClasses()
      .then((classrooms) => { set({ classrooms, status: 'ready' }); })
      .catch(() => { set({ status: 'error' }); })
      .finally(() => { loading = undefined; });
    return loading;
  },
}), {
  name: 'lara-desktop-preferences',
  storage: createJSONStorage(() => ({
    getItem: (name) => { try { return localStorage.getItem(name); } catch { return null; } },
    setItem: (name, value) => { try { localStorage.setItem(name, value); } catch { /* Language still works for this session. */ } },
    removeItem: (name) => { try { localStorage.removeItem(name); } catch { /* No classroom data is stored here. */ } },
  })),
  partialize: ({ language }) => ({ language }),
  merge: (persisted, current) => {
    const language = typeof persisted === 'object' && persisted !== null && 'language' in persisted
      && persisted.language === 'fil' ? 'fil' : 'en';
    return { ...current, language };
  },
}));
