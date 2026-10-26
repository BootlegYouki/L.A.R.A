import { useEffect, useState } from 'react';
import en from './en.json';
import fil from './fil.json';

export type Lang = 'en' | 'fil';
export type Dictionary = typeof en;

// Typed against English, so a key missing in Filipino fails `tsc` (the parity check).
const filipino: Dictionary = fil;
const dictionaries: Record<Lang, Dictionary> = { en, fil: filipino };

const STORAGE_KEY = 'lara-hub-lang';

function storedLang(): Lang {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'fil' ? 'fil' : 'en';
  } catch {
    return 'en';
  }
}

export function useStrings(): { t: Dictionary; toggle: () => void } {
  const [lang, setLang] = useState<Lang>(storedLang);

  useEffect(() => {
    document.documentElement.lang = lang;
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      // Remembering the language is a convenience; the window works without it.
    }
  }, [lang]);

  return { t: dictionaries[lang], toggle: () => setLang((l) => (l === 'en' ? 'fil' : 'en')) };
}
