import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The API runs on :8080; the dev server proxies /api so the browser sees one origin.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8081',
      '/docs': 'http://localhost:8081',
    },
  },
});
