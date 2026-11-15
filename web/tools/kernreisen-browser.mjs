#!/usr/bin/env node
/**
 * Kernreisen im echten Browser (WP-M5-30): (1) Tastaturdurchlauf: auf den Kernreise-Routen von Leitstelle, Flotte, Belegen, Kosten und Verwaltung
 * sowie der Fahrgast-PWA ist jedes sichtbare Bedienelement des Hauptbereichs allein mit Tab erreichbar und trägt dabei einen sichtbaren Fokus.
 * (2) Mandantenwechsel mit zwei Tabs (Kap. 06 §6.9): der Wechsel per Tastatur in Tab 2 ändert Tab 1 nicht, und keine Anfrage eines Tabs trägt
 * die Mandantenbindung des anderen. Die API ist im Browser simuliert (nur /v1/me und der Mandantenwechsel), es läuft kein Backend und
 * kein Screenreader; die Ansage durch Hilfstechnik bleibt dem manuellen Prüfprotokoll vorbehalten.
 *
 * Aufruf (im Verzeichnis web/): npm run kernreisen:browser. Braucht Chrome (CHROME_PATH).
 */
import { existsSync } from 'node:fs';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';

const CHROME =
    process.env.CHROME_PATH ??
    ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync);
if (!CHROME) {
    console.error('FEHLER: kein Chrome/Chromium gefunden (CHROME_PATH setzen)');
    process.exit(2);
}

const A = '00000000-0000-4000-8000-00000000000a';
const B = '00000000-0000-4000-8000-00000000000b';
const SCOPES = [
    'operator:read',
    'operator:write',
    'fleet:read',
    'fleet:write',
    'defects:triage',
    'defects:write',
    'defects:state',
    'defects:history',
    'repair:read',
    'repair:write',
    'fleet:release',
    'receipts:read',
    'receipts:write',
    'expenses:review',
    'expenses:approve',
    'costs:allocate',
    'costs:read',
    'analytics:read',
    'config:write',
    'memberships:read',
    'audit:read',
    'journeys:book',
    'passengers:read',
    'tenant:switch',
];
const meFor = (tenant) => ({
    subject: 'user-1',
    issuer: 'https://idp.example/realms/test',
    tenant_id: tenant === 'B' ? B : A,
    tenant_name: tenant === 'B' ? 'Betrieb B' : 'Betrieb A',
    roles: ['ADMIN', 'FINANCE', 'CALLCENTER', 'PASSENGER'],
    scopes: SCOPES,
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [
        { tenant_id: A, name: 'Betrieb A' },
        { tenant_id: B, name: 'Betrieb B' },
    ],
});

const failures = [];
const fail = (m) => {
    failures.push(m);
    console.error('FEHLER', m);
};
let checks = 0;

async function oidc(page, log) {
    await page.route('**/protocol/openid-connect/auth*', (route) => {
        const u = new URL(route.request().url());
        route.fulfill({
            status: 302,
            headers: {
                location: `${u.searchParams.get('redirect_uri')}?code=sim&state=${u.searchParams.get('state')}`,
            },
        });
    });
    await page.route('**/protocol/openid-connect/token', (route) =>
        route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
                access_token: 'sim-token',
                refresh_token: 'sim-refresh',
                expires_in: 3600,
                token_type: 'Bearer',
            }),
        }),
    );
    // Mandantenbindung: ticket.<subject>.<tenant>.<sid>; ohne Bindung gilt der Standardmandant A
    const tenantOf = (headers) =>
        (headers['x-taktovia-context'] ?? '').includes(`.${B}.`) ? 'B' : 'A';
    await page.route('**/v1/me', (route) => {
        const t = tenantOf(route.request().headers());
        log.push({ path: '/v1/me', tenant: t });
        route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(meFor(t)),
        });
    });
    await page.route('**/v1/me/tenant-switch', (route) => {
        const body = JSON.parse(route.request().postData() ?? '{}');
        const t = body.tenant_id === B ? 'B' : 'A';
        log.push({ path: '/v1/me/tenant-switch', tenant: t });
        route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
                ...meFor(t),
                context_token: `ticket.user-1.${body.tenant_id}.sid-${t}`,
            }),
        });
    });
    // übrige API-Aufrufe: leere Seiten (Leerzustand), damit die Bildschirme ihre Bedienelemente zeigen
    await page.route(/\/v1\/(?!me)/, (route) =>
        route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ items: [], next_cursor: null, as_of: '2026-10-25T08:00:00Z' }),
        }),
    );
}

async function anmelden(page, base) {
    await page.goto(`${base}/`);
    await page.getByRole('button', { name: 'Anmelden' }).click();
    await page.waitForSelector('main h1, main [data-testid="state-view"]');
}

const ROUTEN = {
    operator: [
        '/leitstelle/fahrzeuge',
        '/leitstelle/stoerungen',
        '/leitstelle/vorfaelle',
        '/flotte',
        '/flotte/schaeden',
        '/finanzen/belege',
        '/finanzen/kostenuebersicht',
        '/admin/gebiete',
        '/admin/datenschutz',
    ],
    passenger: ['/', '/reisen', '/konto', '/hilfe'],
};

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const servers = {};
for (const name of ['operator', 'passenger']) {
    const server = await createServer({
        root: new URL(`../${name}/`, import.meta.url).pathname,
        server: { port: 0, host: '127.0.0.1' },
        logLevel: 'error',
    });
    await server.listen();
    servers[name] = { server, base: `http://127.0.0.1:${server.httpServer.address().port}` };
}

// (1) Tastaturdurchlauf: jedes sichtbare Bedienelement des Hauptbereichs ist per Tab erreichbar, der Fokus ist sichtbar
for (const [name, routes] of Object.entries(ROUTEN)) {
    const context = await browser.newContext({
        viewport: { width: 1280, height: 800 },
        locale: 'de-DE',
    });
    const page = await context.newPage();
    await oidc(page, []);
    await anmelden(page, servers[name].base);
    for (const route of routes) {
        const where = `${name} ${route}`;
        await page.evaluate((r) => {
            history.pushState({}, '', r);
            window.dispatchEvent(new PopStateEvent('popstate'));
        }, route);
        await page.waitForSelector('main h1, main [data-testid="state-view"]');
        await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]'));
        checks += 1;
        const soll = await page.evaluate(() =>
            // Rollen-Tabs (roving tabindex): nur der gewählte Reiter ist Tab-Stopp, die übrigen erreicht man mit den Pfeiltasten
            [
                ...document.querySelectorAll(
                    'main a[href], main button, main input:not([type=hidden]), main select, main textarea, main summary',
                ),
            ]
                .filter(
                    (el) =>
                        el.getClientRects().length > 0 &&
                        !el.disabled &&
                        !el.matches('[role=tab][aria-selected=false]') &&
                        getComputedStyle(el).visibility !== 'hidden',
                )
                .map((el) => el.outerHTML.slice(0, 90)),
        );
        await page.evaluate(
            () => document.activeElement instanceof HTMLElement && document.activeElement.blur(),
        );
        const erreicht = new Set();
        let zuletzt = null;
        for (let i = 0; i < 400; i += 1) {
            await page.keyboard.press('Tab');
            const stop = await page.evaluate(() => {
                const el = document.activeElement;
                if (!el || el === document.body) return null;
                const cs = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return {
                    html: el.outerHTML.slice(0, 90),
                    dbg:
                        cs.outlineStyle +
                        ' ' +
                        cs.outlineWidth +
                        ' fv=' +
                        el.matches(':focus-visible'),
                    inMain: !!el.closest('main'),
                    sichtbar:
                        (cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2) ||
                        (cs.boxShadow && cs.boxShadow !== 'none'),
                    groesse: r.width > 0 && r.height > 0,
                };
            });
            if (stop === null) break;
            if (!stop.groesse) fail(`${where}: Tabulatorstopp ohne Fläche: ${stop.html}`);
            // Datums- und Zeitfelder haben mehrere innere Tabulatorstopps (Feldteile, Kalenderschaltfläche); die Kalenderschaltfläche trägt
            // den nativen Fokusrahmen des Browsers, `:focus-visible` des Hostelements ist dort nicht gesetzt.
            const innerStopp =
                zuletzt === stop.html &&
                /type="(datetime-local|date|time|month|week)"/.test(stop.html);
            zuletzt = stop.html;
            if (!stop.sichtbar && !innerStopp)
                fail(`${where}: Tabulatorstopp ohne sichtbaren Fokus: ${stop.html} (${stop.dbg})`);
            if (stop.inMain) erreicht.add(stop.html);
        }
        for (const el of soll)
            if (!erreicht.has(el)) fail(`${where}: nicht per Tab erreichbar: ${el}`);
        if (soll.length === 0)
            console.log(`${where}: keine Bedienelemente im Hauptbereich (Zustandsansicht)`);
    }
    await context.close();
}

// (2) Mandantenwechsel mit zwei Tabs, nur Tastatur
{
    const context = await browser.newContext({
        viewport: { width: 1280, height: 800 },
        locale: 'de-DE',
    });
    const log1 = [];
    const log2 = [];
    const base = servers.operator.base;
    const tab1 = await context.newPage();
    const tab2 = await context.newPage();
    await oidc(tab1, log1);
    await oidc(tab2, log2);
    const kopf = (p) => p.locator('[aria-label="Arbeitskontext"]').first().textContent();
    await anmelden(tab1, base);
    await anmelden(tab2, base);
    checks += 1;
    if (!(await kopf(tab1)).includes('Betrieb A'))
        fail('Tab 1 zeigt nach der Anmeldung nicht Betrieb A');
    // Tab 2: per Tastatur zu Betrieb B wechseln (Auswahl fokussieren, Pfeil, Schaltfläche mit Enter)
    await tab2.getByLabel(/Mandant/).focus();
    await tab2.keyboard.press('ArrowDown');
    await tab2.getByRole('button', { name: /Wechseln|Mandant wechseln/ }).focus();
    await tab2.keyboard.press('Enter');
    await tab2.waitForFunction(() =>
        document.querySelector('[aria-label="Arbeitskontext"]')?.textContent?.includes('Betrieb B'),
    );
    checks += 1;
    if (!(await kopf(tab2)).includes('Betrieb B'))
        fail('Tab 2 zeigt nach dem Wechsel nicht Betrieb B');
    // Tab 1 bleibt in A, auch nach einem Neuladen der Sitzung (die Mandantenbindung liegt je Tab im sessionStorage)
    checks += 1;
    if (!(await kopf(tab1)).includes('Betrieb A'))
        fail('Tab 1 zeigt nach dem Wechsel in Tab 2 nicht mehr Betrieb A');
    await tab1.reload();
    await anmelden(tab1, base);
    if (!(await kopf(tab1)).includes('Betrieb A'))
        fail('Tab 1 zeigt nach dem Neuladen einen fremden Mandanten');
    await tab2.reload();
    await anmelden(tab2, base);
    if (!(await kopf(tab2)).includes('Betrieb B'))
        fail('Tab 2 hat die Mandantenbindung nach dem Neuladen verloren');
    // keine Anfrage von Tab 1 hat je Betrieb B angefordert
    checks += 1;
    if (log1.some((l) => l.tenant === 'B')) fail('Tab 1 hat Daten von Betrieb B angefordert');
    // der Fokus ist nach dem Wechsel nicht verloren, sondern liegt auf einem Element der Seite
    await tab2.keyboard.press('Tab');
    const fokus = await tab2.evaluate(() => document.activeElement?.tagName ?? 'NONE');
    if (fokus === 'BODY' || fokus === 'NONE')
        fail('Tab 2: nach dem Mandantenwechsel führt Tab nicht zu einem Bedienelement');
    await context.close();
}

await browser.close();
for (const s of Object.values(servers)) await s.server.close();
console.log(`Kernreisen im Browser: ${checks} Prüfungen, ${failures.length} Verstöße`);
process.exit(failures.length > 0 ? 1 : 0);
