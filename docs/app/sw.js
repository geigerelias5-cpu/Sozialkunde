/* Offline-Speicher: App-Dateien zwischenspeichern, damit der Trainer auch ohne Internet startet.
   Beim Veröffentlichen einer neuen Version die Versionsnummer erhöhen. */
const VERSION = 'sozialkunde-v9';
const FILES = ['./', './index.html', './fonts.css', './manifest.webmanifest',
  './icon-180.png', './icon-192.png', './icon-512.png',
  './fonts/doto-900.woff2', './fonts/hanken-400.woff2', './fonts/hanken-500.woff2', './fonts/hanken-700.woff2'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});
/* Erst das Netz fragen (für Updates), bei fehlender Verbindung aus dem Speicher laden */
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    fetch(e.request).then(r => {
      const copy = r.clone();
      caches.open(VERSION).then(c => c.put(e.request, copy));
      return r;
    }).catch(() => caches.match(e.request, {ignoreSearch: true}).then(r => r || caches.match('./index.html')))
  );
});
