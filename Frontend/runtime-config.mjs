// These are public browser addresses. Never expose the container environment here.
export function publicConfig(env) {
  const config = {
    apiBase: env.FRONTEND_API_BASE ?? env.VITE_API_BASE ?? 'http://localhost:8081',
    minioPublicUrl: env.MINIO_PUBLIC_URL ?? 'http://localhost:9000',
  };
  for (const [name, value] of Object.entries(config)) {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error(`Invalid public URL: ${name}`);
    }
    config[name] = value.replace(/\/+$/, '');
  }
  return config;
}
