import { test } from 'node:test';
import assert from 'node:assert/strict';
import { publicConfig } from '../runtime-config.mjs';
import { loadRuntimeConfig } from '../src/runtime-config.js';

test('one compiled application consumes either runtime configuration and excludes secrets', async () => {
  for (const origin of ['https://first.example', 'https://second.example']) {
    const publicValues = publicConfig({ FRONTEND_API_BASE: origin, MINIO_PUBLIC_URL: `${origin}/media/`, JWT_SECRET: 'private' });
    const config = await loadRuntimeConfig(async (url, options) => {
      assert.equal(url, '/runtime-config.json');
      assert.equal(options.cache, 'no-store');
      return { ok: true, json: async () => publicValues };
    });
    assert.deepEqual(config, { apiBase: origin, minioPublicUrl: `${origin}/media` });
    assert.ok(Object.isFrozen(config));
    assert.equal(JSON.stringify(config).includes('private'), false);
  }
});

test('JSON encoding retains data without turning it into executable code', async () => {
  const values = publicConfig({ FRONTEND_API_BASE: 'https://api.example/";window.injected=1;//', MINIO_PUBLIC_URL: 'https://media.example' });
  const config = await loadRuntimeConfig(async () => ({ ok: true, json: async () => JSON.parse(JSON.stringify(values)) }));
  assert.equal(config.apiBase, 'https://api.example/";window.injected=1;');
  assert.equal(globalThis.injected, undefined);
});

test('missing, failed, non-http or credential-bearing configuration stops startup', async () => {
  await assert.rejects(loadRuntimeConfig(async () => ({ ok: false })));
  for (const apiBase of [undefined, 'javascript:alert(1)', 'https://user:password@example.com', 'https://example.com/?token=secret']) {
    await assert.rejects(loadRuntimeConfig(async () => ({ ok: true, json: async () => ({ apiBase, minioPublicUrl: 'https://media.example' }) })));
  }
});
