// PWA-Grundgeruest (WP-M0-05): Service Worker ohne Caching-Strategie.
// Offline-Verhalten und Icons folgen mit den Passenger-Bildschirmen (M2 ff.).
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', () => {});
