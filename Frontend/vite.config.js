import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiBase = env.VITE_API_BASE || 'http://localhost:8080';
  return {
    plugins: [react()],
    define: {
      __API_BASE__: JSON.stringify(apiBase),
    },
    build: {
      target: 'esnext',
    },
  };
});
