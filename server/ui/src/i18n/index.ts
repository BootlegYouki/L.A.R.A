import en from './en.json';

export type Dictionary = typeof en;

export function useStrings(): { t: Dictionary } {
  return { t: en };
}
