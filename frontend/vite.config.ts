import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    port: 5173,
    // Spring Boot runs on 8080; the app calls /api/* on its own origin and Vite forwards it.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        // Through the proxy the browser call is same-origin, so drop Origin; otherwise Spring's CORS
        // check rejects it whenever the dev server is not on the one allowed port.
        configure: proxy => proxy.on('proxyReq', req => req.removeHeader('origin')),
      },
    },
  },
});
