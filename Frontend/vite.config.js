import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const apiBase = env.VITE_API_BASE || 'http://localhost:8081';
  const minioPublicUrl = env.MINIO_PUBLIC_URL || 'http://localhost:9000';
  return {
    plugins: [react()],
    define: {
      __API_BASE__: JSON.stringify(apiBase),
      __MINIO_PUBLIC_URL__: JSON.stringify(minioPublicUrl),
    },
    build: {
      target: 'esnext',
    },
  };
});
