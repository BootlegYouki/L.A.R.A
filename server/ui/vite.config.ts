import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Tokens, Nunito and Phosphor are read straight from design-system/ at the repo root.
const repoRoot = fileURLToPath(new URL('../..', import.meta.url));

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // Keep unrelated PostCSS configuration in a parent folder out of the bundled Hub UI.
  css: { postcss: { plugins: [] } },
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
    fs: { allow: [repoRoot] },
  },
});
