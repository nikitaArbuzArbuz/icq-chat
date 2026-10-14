import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// При разработке dev-сервер Vite проксирует запросы к API и WebSocket на бэкенд,
// поэтому браузер работает с одним адресом, как и за nginx в Docker.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
      },
    },
  },
});
