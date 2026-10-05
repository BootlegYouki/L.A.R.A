/**
 * L.A.R.A. Tailwind CSS Theme Extensions
 * Import or spread directly into tailwind.config.ts / src/index.css
 */

export const laraTailwindTheme = {
  colors: {
    lara: {
      green: {
        DEFAULT: '#2E9B4B',
        dark: '#176B36',
        light: '#E4F6E8',
      },
      purple: {
        DEFAULT: '#5145E5',
        dark: '#4035C9',
        light: '#EEEAFE',
      },
      canvas: '#F7FBFA',
      surface: '#FFFFFF',
      subtle: '#F2F6F5',
      border: '#E4EAF0',
      text: {
        primary: '#17213D',
        secondary: '#667085',
        muted: '#98A2B3',
      },
      status: {
        success: '#22A447',
        warning: '#F5B82E',
        danger: '#EF5350',
        info: '#3B82F6',
      },
    },
  },
  fontFamily: {
    nunito: ['Nunito', 'sans-serif'],
  },
  boxShadow: {
    'lara-card': '0 1px 3px 0 rgba(23, 33, 61, 0.05), 0 1px 2px -1px rgba(23, 33, 61, 0.05)',
    'lara-hover': '0 4px 6px -1px rgba(23, 33, 61, 0.07), 0 2px 4px -2px rgba(23, 33, 61, 0.05)',
    'lara-elevated': '0 10px 15px -3px rgba(23, 33, 61, 0.08), 0 4px 6px -4px rgba(23, 33, 61, 0.04)',
    'lara-toast': '0 12px 28px -4px rgba(23, 33, 61, 0.12), 0 4px 8px -2px rgba(23, 33, 61, 0.06)',
  },
  borderRadius: {
    'lara-sm': '8px',
    'lara-md': '12px',
    'lara-lg': '16px',
    'lara-pill': '9999px',
  },
};
