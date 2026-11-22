import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import {readFileSync,readdirSync} from 'node:fs';
const fonts=new URL('./node_modules/pdfjs-dist/standard_fonts/',import.meta.url);
const fontNames=readdirSync(fonts);


export default defineConfig({
  plugins: [react(), tailwindcss(), {
    name:'bundled-pdf-fonts',
    generateBundle(){for(const name of fontNames)this.emitFile({type:'asset',fileName:'pdf-fonts/'+name,source:readFileSync(new URL(name,fonts))})},
    configureServer(server){server.middlewares.use('/pdf-fonts',(request,response,next)=>{const name=request.url?.slice(1);if(!name||!fontNames.includes(name))return next();response.end(readFileSync(new URL(name,fonts)));})},
  }],
  clearScreen: false,
  server: {
    host: '127.0.0.1',
    port: 1420,
    strictPort: true,
    watch: {
      ignored: ['**/src-tauri/**'],
    },
  },
  test: { environment: 'node', include: ['tests/**/*.test.ts'] },
});
