/**
 * L.A.R.A. Tailwind theme extension (Tailwind 3 style config, or Tailwind 4 via `@config`).
 * Mirrors the "React + Tailwind" code in docs/design-system-showcase.html.
 *
 * Usage in tailwind.config.ts:
 *   import { laraTheme } from './design-system/desktop/tailwind.theme';
 *   export default { content: [...], theme: { extend: laraTheme } };
 *
 * Tailwind 4 projects can instead import desktop/theme.css (same tokens as an @theme block).
 * Never write a hex value in a component; use these names (bg-lara-primary, text-lara-ink-2, shadow-card).
 */
export const laraTheme = {
  colors: {
    lara: {
      primary: '#2E9B4B',
      'primary-dark': '#176B36',
      'primary-light': '#E4F6E8',
      ai: '#5145E5',
      'ai-dark': '#4035C9',
      'ai-light': '#EEEAFE',
      'ai-border': '#D6CCFC',
      'ai-bubble-text': '#2D237A',
      'ai-chat-bg': '#FAF9FE',
      canvas: '#F7FBFA',
      subtle: '#F2F6F5',
      border: '#E4EAF0',
      ink: '#17213D',
      'ink-2': '#667085',
      'ink-3': '#98A2B3',
      success: '#22A447',
      warning: '#F5B82E',
      danger: '#EF5350',
      info: '#3B82F6',
      'success-border': '#C4ECCB',
      'warning-light': '#FEF0C7',
      'warning-dark': '#B54708',
      'warning-border': '#FEDF89',
      'danger-light': '#FEE4E2',
      'danger-dark': '#B42318',
      'danger-border': '#FECDCA',
      'info-light': '#E0F2FE',
      'info-dark': '#0369A1',
      'info-border': '#BAE6FD',
    },
  },
  fontFamily: {
    sans: ['Nunito', 'system-ui', 'sans-serif'],
  },
  boxShadow: {
    card: '0 1px 3px 0 rgba(23, 33, 61, 0.05), 0 1px 2px -1px rgba(23, 33, 61, 0.05)',
    'card-hover': '0 4px 6px -1px rgba(23, 33, 61, 0.07), 0 2px 4px -2px rgba(23, 33, 61, 0.05)',
    elevated: '0 10px 15px -3px rgba(23, 33, 61, 0.08), 0 4px 6px -4px rgba(23, 33, 61, 0.04)',
    toast: '0 12px 28px -4px rgba(23, 33, 61, 0.12), 0 4px 8px -2px rgba(23, 33, 61, 0.06)',
    modal: '0 24px 48px -12px rgba(23, 33, 61, 0.22), 0 8px 16px -4px rgba(23, 33, 61, 0.08)',
  },
  borderRadius: {
    md: '12px',
    lg: '16px',
    xl: '24px',
  },
  minHeight: {
    touch: '52px',
    'touch-primary': '56px',
  },
  minWidth: {
    touch: '52px',
    'touch-primary': '56px',
  },
} as const;

/** Typography utility map from the design system (use these class strings, do not improvise sizes). */
export const laraType = {
  display: 'text-[40px] leading-[48px] font-black',
  h1: 'text-[28px] leading-9 font-extrabold',
  h2: 'text-[22px] leading-[30px] font-bold',
  title: 'text-lg leading-[26px] font-extrabold',
  body: 'text-base leading-6 font-medium',
  label: 'text-sm leading-5 font-extrabold',
  caption: 'text-xs leading-4 font-semibold',
} as const;
