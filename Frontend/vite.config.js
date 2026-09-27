import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const serveConfig = server => server.middlewares.use('/runtime-config.json', (_request, response) => {
    response.setHeader('Content-Type', 'application/json');
    response.setHeader('Cache-Control', 'no-store');
    response.end(JSON.stringify({
      apiBase: process.env.FRONTEND_API_BASE || env.FRONTEND_API_BASE || env.VITE_API_BASE || 'http://localhost:8082',
      minioPublicUrl: process.env.MINIO_PUBLIC_URL || env.MINIO_PUBLIC_URL || 'http://localhost:9000',
    }));
  });
  return {
    plugins: [react(), { name: 'runtime-config', configureServer: serveConfig, configurePreviewServer: serveConfig }],
    build: {
      target: 'esnext',
    },
  };
});
