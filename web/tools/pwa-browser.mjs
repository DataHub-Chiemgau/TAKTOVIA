#!/usr/bin/env node
/**
 * Browser-Ende-zu-Ende-Prüfung der Passenger-PWA (WP-M2-35) im echten Chrome gegen den Produktionsbuild (vite preview):
 * Manifest und Installierbarkeit (CDP Page.getInstallabilityErrors), Service Worker aktiv, Cache enthält nur App-Shell
 * (kein /v1/*), Offlinezustand nach Neuladen ohne Verbindung und ohne Anmelde- oder Buchungsschaltfläche.
 * Aufruf (in web/, nach `npm run build -w @taktovia/passenger`): npm run e2e:pwa. Braucht Chrome (CHROME_PATH).
 * Es läuft kein Backend und kein Keycloak; die Anmeldung selbst prüfen die Vitest-Tests gegen den lokalen IdP-Ersatz.
 */
import { existsSync } from 'node:fs';
import { preview } from 'vite';
import { chromium } from 'playwright-core';

const CHROME =
    process.env.CHROME_PATH ??
    ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync);
if (!CHROME) {
    console.error('FEHLER: kein Chrome/Chromium gefunden (CHROME_PATH setzen)');
    process.exit(2);
}
const failures = [];
const check = (ok, msg) => {
    console.log(ok ? 'OK    ' : 'FEHLER', msg);
    if (!ok) failures.push(msg);
};

const server = await preview({
    root: new URL('../passenger/', import.meta.url).pathname,
    preview: { port: 0, host: '127.0.0.1' },
    logLevel: 'error',
});
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const context = await browser.newContext({ locale: 'de-DE' });
const page = await context.newPage();
const apiCalls = [];
page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith('/v1/')) apiCalls.push(r.url());
});

await page.goto(base + '/reisen');
await page.waitForSelector('[data-testid="auth-state"]');
check(
    (await page.getAttribute('[data-testid="auth-state"]', 'data-kind')) === 'signedOut',
    'ohne Anmeldung nur Anmeldezustand auf /reisen',
);
const manifestHref = await page.getAttribute('link[rel=manifest]', 'href');
const manifest = await (await page.request.get(base + manifestHref)).json();
check(manifest.display === 'standalone' && manifest.icons.length >= 2, 'Manifest mit Symbolen');

await page.evaluate(() => navigator.serviceWorker.ready);
await page.reload();
check(
    await page.evaluate(() => !!navigator.serviceWorker.controller),
    'Service Worker steuert die Seite',
);

const cdp = await context.newCDPSession(page);
const { installabilityErrors } = await cdp.send('Page.getInstallabilityErrors');
// Der Testkontext ist ein Inkognito-Kontext; `in-incognito` ist daher kein Mangel der Anwendung.
const relevant = installabilityErrors.filter((e) => e.errorId !== 'in-incognito');
check(relevant.length === 0, `installierbar (Fehler: ${JSON.stringify(relevant)})`);

const cached = await page.evaluate(async () => {
    const out = [];
    for (const name of await caches.keys())
        for (const req of await (await caches.open(name)).keys())
            out.push(new URL(req.url).pathname);
    return out;
});
check(
    cached.length > 0 && cached.every((p) => !p.startsWith('/v1/')),
    `Cache nur App-Shell: ${cached.join(', ')}`,
);
check(apiCalls.length === 0, 'ohne Anmeldung keine API-Anfrage');

await context.setOffline(true); // Netz wirklich gesperrt; die Seite wird nur noch von der Shell im Cache bedient
// Chromium meldet `navigator.onLine` bei emulierter Offline-Verbindung nach einer neuen Navigation weiterhin als true (beobachtet);
// ein echter Browser ohne Netz meldet false. Das setzen wir für die neu geladene Seite nach, der Rest (Sperre, Cache) ist echt.
await context.addInitScript(() => Object.defineProperty(navigator, 'onLine', { get: () => false }));
await page.goto(base + '/reisen').catch((e) => console.error('goto offline:', e.message));
await page.waitForSelector('[data-testid="state-view"]', { timeout: 5000 }).catch(async () =>
    console.error(
        'BODY:',
        (
            await page
                .locator('body')
                .innerText()
                .catch(() => '?')
        ).slice(0, 300),
    ),
);
check(
    (await page.getAttribute('[data-testid="state-view"]', 'data-kind')) === 'offline',
    'Offlinezustand nach Neuladen ohne Verbindung (Shell aus dem Cache)',
);
check(
    (await page.locator('main button').count()) === 0,
    'offline keine Anmelde- oder Buchungsschaltfläche',
);
await context.setOffline(false);

await browser.close();
await server.close();
if (failures.length) {
    console.error(`${failures.length} Verstöße`);
    process.exit(1);
}
console.log('PWA-Browserprüfung grün');
