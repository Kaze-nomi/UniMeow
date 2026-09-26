import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';
import { publicConfig } from './runtime-config.mjs';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const serveConfig = server => server.middlewares.use('/runtime-config.json', (_request, response) => {
    response.setHeader('Content-Type', 'application/json');
    response.setHeader('Cache-Control', 'no-store');
    response.end(JSON.stringify(publicConfig({ ...env, ...process.env })));
  });
  return {
    plugins: [react(), { name: 'public-runtime-config', configureServer: serveConfig, configurePreviewServer: serveConfig }],
    build: {
      target: 'esnext',
    },
  };
});
