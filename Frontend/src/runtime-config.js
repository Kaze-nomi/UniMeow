export async function loadRuntimeConfig(fetcher = fetch) {
  const response = await fetcher('/runtime-config.json', { cache: 'no-store', credentials: 'same-origin' });
  if (!response.ok) throw new Error('Public application configuration is unavailable');
  const config = await response.json();
  for (const key of ['apiBase', 'minioPublicUrl']) {
    const url = new URL(config[key]);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new Error(`Invalid public application configuration: ${key}`);
    }
  }
  return Object.freeze({ apiBase: config.apiBase.replace(/\/+$/, ''), minioPublicUrl: config.minioPublicUrl.replace(/\/+$/, '') });
}
