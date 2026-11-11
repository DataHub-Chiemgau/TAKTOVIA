// @vitest-environment node
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';

/** Lädt public/sw.js unverändert in eine Ersatzumgebung (Service-Worker-Globals) und ruft die echten Ereignisbehandler auf. */
function load(options: { online: boolean }) {
  const listeners: Record<string, (e: unknown) => void> = {};
  const store = new Map<string, Response>();
  const fetched: string[] = [];
  const cache = {
    addAll: (urls: string[]) => {
      for (const u of urls) store.set(u, new Response('shell'));
      return Promise.resolve();
    },
    match: (r: string | { url: string }) =>
      Promise.resolve(
        store.get(typeof r === 'string' ? r : new URL(r.url).pathname + new URL(r.url).search),
      ),
    put: (r: { url: string }, res: Response) => {
      store.set(new URL(r.url).pathname + new URL(r.url).search, res);
      return Promise.resolve();
    },
  };
  const self = {
    location: { origin: 'https://app.test' },
    addEventListener: (n: string, f: (e: unknown) => void) => void (listeners[n] = f),
    skipWaiting: () => Promise.resolve(),
    clients: { claim: () => Promise.resolve() },
  };
  const context = {
    self,
    URL,
    Response,
    caches: {
      open: () => Promise.resolve(cache),
      keys: () => Promise.resolve([]),
      delete: () => Promise.resolve(true),
    },
    fetch: (r: { url: string }) => {
      fetched.push(r.url);
      if (!options.online) return Promise.reject(new TypeError('offline'));
      const res = new Response('body-of-' + r.url, { status: 200 });
      Object.defineProperty(res, 'type', { value: 'basic' }); // wie eine echte Antwort derselben Herkunft
      return Promise.resolve(res);
    },
  };
  runInNewContext(readFileSync(new URL('../../public/sw.js', import.meta.url), 'utf8'), context);
  async function install() {
    let p: Promise<unknown> = Promise.resolve();
    listeners['install']?.({ waitUntil: (x: Promise<unknown>) => (p = x) });
    await p;
  }
  async function request(
    url: string,
    init: { method?: string; mode?: string; headers?: Record<string, string> } = {},
  ) {
    const headers = new Headers(init.headers);
    let answer: Promise<Response> | undefined;
    listeners['fetch']?.({
      request: { url, method: init.method ?? 'GET', mode: init.mode ?? 'cors', headers },
      respondWith: (p: Promise<Response>) => (answer = p),
    });
    return answer ? await answer : undefined; // undefined = nicht abgefangen
  }
  return { install, request, store, fetched };
}

describe('Service Worker: nur App-Shell (WP-M2-35, Kap. 01 §1.5)', () => {
  it('legt beim Installieren nur Shell, Manifest und Symbole ab', async () => {
    const sw = load({ online: true });
    await sw.install();
    expect([...sw.store.keys()].sort()).toEqual(
      ['/', '/icons/icon-192.png', '/icons/icon-512.png', '/manifest.webmanifest'].sort(),
    );
  });

  it('API-Anfragen, Anfragen mit Authorization, Nicht-GET und fremde Herkünfte werden nicht abgefangen und nie gespeichert', async () => {
    const sw = load({ online: true });
    await sw.install();
    const before = [...sw.store.keys()].sort();
    for (const [url, init] of [
      ['https://app.test/v1/me', {}],
      ['https://app.test/v1/bookings?limit=5', {}],
      ['https://app.test/assets/app.js', { headers: { authorization: 'Bearer x' } }],
      ['https://app.test/assets/app.js', { method: 'POST' }],
      ['https://idp.test/realms/taktovia/protocol/openid-connect/token', {}],
      ['https://app.test/realms/taktovia/x', {}],
      ['https://app.test/konto-export.json', {}],
    ] as const) {
      expect(await sw.request(url, init)).toBeUndefined();
    }
    expect([...sw.store.keys()].sort()).toEqual(before);
    expect(sw.fetched).toEqual([]);
  });

  it('statische Assets werden einmal geholt und danach aus dem Cache bedient', async () => {
    const sw = load({ online: true });
    await sw.install();
    const first = await sw.request('https://app.test/assets/app-1.js');
    expect(await first?.text()).toBe('body-of-https://app.test/assets/app-1.js');
    expect(sw.store.has('/assets/app-1.js')).toBe(true);
    await sw.request('https://app.test/assets/app-1.js');
    expect(sw.fetched).toEqual(['https://app.test/assets/app-1.js']);
  });

  it('Seitenaufruf ohne Verbindung liefert die Shell statt eines Browserfehlers', async () => {
    const sw = load({ online: false });
    await sw.install();
    const res = await sw.request('https://app.test/reisen', { mode: 'navigate' });
    expect(await res?.text()).toBe('shell');
  });

  it('Seitenaufrufe werden nicht gespeichert (Netz zuerst)', async () => {
    const sw = load({ online: true });
    await sw.install();
    await sw.request('https://app.test/konto', { mode: 'navigate' });
    expect(sw.store.has('/konto')).toBe(false);
  });
});
