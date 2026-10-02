import React from 'react';
import { createRoot } from 'react-dom/client';
import { createPortal } from 'react-dom';

window.React = React;
window.ReactDOM = { createRoot, createPortal };

const response = await fetch('/runtime-config.json', { cache: 'no-store' });
if (!response.ok) throw new Error('Application configuration is unavailable');
const config = await response.json();
for (const key of ['apiBase', 'minioPublicUrl']) {
  const url = new URL(config[key]);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new Error(`Invalid application URL: ${key}`);
  }
  config[key] = config[key].replace(/\/+$/, '');
}
window.UM_CONFIG = Object.freeze({ apiBase: config.apiBase, minioPublicUrl: config.minioPublicUrl });

await import('../mock.js');
await import('../api.js');
await import('../components/ui.jsx');
await import('../components/layout.jsx');
await import('../components/post-card.jsx');
await import('../pages/auth.jsx');
await import('../pages/feed.jsx');
await import('../pages/profile.jsx');
await import('../pages/post-page.jsx');
await import('../pages/settings.jsx');
await import('../pages/explore.jsx');
await import('../pages/admin.jsx');
await import('../App.jsx');
await import('../pages/about.jsx');
await import('../pages/notifications.jsx');

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(React.createElement(window.App));
