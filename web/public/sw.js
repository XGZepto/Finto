const CACHE = 'finto-shell-v4';
const LOGOS = 'finto-logos-v1';
const LOGO_HOSTS = ['www.google.com'];
const SHELL = ['/index.html', '/manifest.webmanifest', '/favicon.svg?v=3', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', (event) => {
  /* No skipWaiting: every route is a lazy chunk, and activating while a tab is
     open deletes the cache that tab still resolves chunks from. The new worker
     takes over on the next cold load instead. */
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(
      keys.filter((key) => key.startsWith('finto-shell-') && key !== CACHE)
        .map((key) => caches.delete(key)),
    )),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);

  /* Brand marks are immutable for our purposes and the host only allows a
     half-hour of HTTP caching, so serve them from the cache and refresh in the
     background rather than re-fetching a logo per row on every visit. */
  if (LOGO_HOSTS.includes(url.hostname) && url.pathname.startsWith('/s2/favicons')) {
    event.respondWith(
      caches.open(LOGOS).then((cache) => cache.match(request).then((cached) => {
        const network = fetch(request).then((response) => {
          if (response.ok || response.type === 'opaque') cache.put(request, response.clone());
          return response;
        }).catch(() => cached);
        return cached || network;
      })),
    );
    return;
  }

  if (url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;

  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/index.html')));
    return;
  }

  if (['image', 'manifest'].includes(request.destination)) {
    event.respondWith(fetch(request).then((response) => {
      if (response.ok) caches.open(CACHE).then((cache) => cache.put(request, response.clone()));
      return response;
    }).catch(() => caches.match(request)));
    return;
  }

  /* WebKit often leaves destination empty on dynamic import(), so match by
     path as well. Cache-first with a network fallback: an installed PWA can
     paint the shell before the radio is up, and without a cached lazy chunk
     the outlet stays blank. Keep the matcher in step with isHashedAssetRequest. */
  const hashedAsset = ['script', 'style', 'font'].includes(request.destination)
    || /\.(?:js|css|woff2?)$/.test(url.pathname);
  if (hashedAsset) {
    event.respondWith(
      caches.open(CACHE).then((cache) => cache.match(request).then((cached) => {
        const network = fetch(request).then((response) => {
          if (response.ok) cache.put(request, response.clone());
          return response;
        }).catch(() => cached);
        return cached || network;
      })),
    );
  }
});
