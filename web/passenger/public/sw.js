// Service Worker der Passenger-PWA (WP-M2-35). Regel (Vertrag web-passenger-pwa.md):
// Gecacht werden NUR die statischen App-Shell-Assets (Startseite "/", /assets/*, Manifest, Symbole). Nie gecacht und nie abgefangen:
// API-Antworten (/v1/*), Anfragen mit Authorization-Kopf, Nicht-GET-Anfragen, fremde Herkünfte, Anmeldedienst. Es gibt keine Offline-Buchung.
const CACHE = 'taktovia-passenger-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png'];

function isShellAsset(url) {
  return (
    SHELL.includes(url.pathname) ||
    url.pathname.startsWith('/assets/') ||
    url.pathname.startsWith('/icons/')
  );
}

function isEligible(request, url) {
  if (request.method !== 'GET') return false;
  if (url.origin !== self.location.origin) return false;
  if (url.pathname.startsWith('/v1/') || url.pathname.startsWith('/realms/')) return false;
  if (request.headers.has('authorization')) return false;
  return true;
}

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (!isEligible(request, url)) return; // nicht abfangen: Browser verarbeitet normal
  if (request.mode === 'navigate') {
    // Seitenaufrufe: Netz zuerst; ohne Verbindung die gecachte Shell (enthaelt keine personenbezogenen Daten)
    event.respondWith(
      fetch(request).catch(() =>
        caches
          .open(CACHE)
          .then((c) => c.match('/'))
          .then((r) => r || Response.error()),
      ),
    );
    return;
  }
  if (!isShellAsset(url)) return;
  event.respondWith(
    caches.open(CACHE).then((cache) =>
      cache.match(request).then(
        (hit) =>
          hit ||
          fetch(request).then((response) => {
            if (response.ok && response.type === 'basic') cache.put(request, response.clone());
            return response;
          }),
      ),
    ),
  );
});
