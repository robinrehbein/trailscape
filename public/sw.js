const CACHE_NAME = 'trailscape-v2';
const TILE_CACHE = 'trailscape-tiles';
const TILE_CACHE_LIMIT = 2000;

self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames
          .filter((name) => name !== CACHE_NAME && name !== TILE_CACHE)
          .map((name) => caches.delete(name))
      );
    })
  );
  clients.claim();
});

function isTileRequest(url) {
  return url.hostname === 'tile.openstreetmap.org' || url.hostname.endsWith('.tile.opentopomap.org');
}

function trimTileCache(cache) {
  return cache.keys().then((keys) => {
    if (keys.length <= TILE_CACHE_LIMIT) {
      return;
    }
    const excess = keys.length - TILE_CACHE_LIMIT;
    return Promise.all(keys.slice(0, excess).map((key) => cache.delete(key)));
  });
}

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') {
    return;
  }

  const url = new URL(event.request.url);

  if (isTileRequest(url)) {
    event.respondWith(
      caches.open(TILE_CACHE).then((cache) => {
        return cache.match(event.request).then((cached) => {
          if (cached) {
            return cached;
          }
          return fetch(event.request).then((response) => {
            const responseToCache = response.clone();
            cache.put(event.request, responseToCache);
            event.waitUntil(trimTileCache(cache));
            return response;
          });
        });
      })
    );
    return;
  }

  // Only handle same-origin GET requests
  if (url.origin !== self.location.origin) {
    return;
  }

  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok) {
          const responseToCache = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseToCache);
          });
        }
        return response;
      })
      .catch(() => {
        return caches.match(event.request);
      })
  );
});
