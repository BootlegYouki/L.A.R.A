import en from './en.json';
import fil from './fil.json';

export type Language = 'en' | 'fil';
export type Dictionary = typeof en;
export const dictionaries: Record<Language, Dictionary> = { en, fil };
