#!/usr/bin/env node
/**
 * Barrierefreiheitsprüfung im echten Browser (WP-M1-43): axe-core inklusive Farbkontrast (WCAG 2.2 AA-Regelsatz), Reflow bei
 * 320 CSS-Pixeln, 200 % Textgröße, Touchflächen 44x44, sichtbarer Fokus und reduzierte Bewegung für die Rahmen von operator
 * und passenger. Braucht einen Chrome/Chromium (Umgebungsvariable CHROME_PATH, sonst übliche Pfade). Die API wird im Browser
 * mit /v1/me-Antworten simuliert; es läuft kein Backend.
 *
 * Aufruf (im Verzeichnis web/): npm run a11y:browser
 */
import { existsSync } from 'node:fs';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';
import { axeVerstoesse, fokusVerstoesse } from './a11y-regeln.mjs';

const CHROME =
    process.env.CHROME_PATH ??
    ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync);
if (!CHROME) {
    console.error('FEHLER: kein Chrome/Chromium gefunden (CHROME_PATH setzen)');
    process.exit(2);
}

const ME = {
    subject: 'user-1',
    issuer: 'https://idp.example/realms/test',
    tenant_id: '00000000-0000-4000-8000-000000000001',
    tenant_name: 'Verkehrsbetrieb Nord',
    roles: ['ADMIN', 'FINANCE', 'STAFF_PLANNING', 'CALLCENTER', 'PASSENGER'],
    // Funktionsscopes öffnen die Bereiche UI-12/UI-13/UI-11 (Navigationsrechte, der Server prüft jede Anfrage erneut)
    scopes: [
        'journeys:book',
        'passengers:read',
        'costs:allocate',
        'expenses:approve',
        'time-events:read',
        'rosters:read',
        'rosters:write',
        'rosters:publish',
        'driver-releases:write',
        'fleet:read',
        'fleet:write',
        'drivers:read',
        'drivers:write',
        'shifts:read',
        'shifts:write',
        'checklists:read',
        'checklists:write',
        'inspections:justify',
        'inspections:amend',
        'defects:triage',
        'defects:write',
        'defects:state',
        'defects:history',
        'repair:read',
        'repair:write',
        'fleet:release',
        'operator:read',
        'operator:write',
        'config:write',
        'memberships:read',
        'audit:read',
        // UI-19 Kennzahlenteil und Live-Dashboard (WP-M4-59, WP-M4-60)
        'analytics:read',
    ],
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [],
};

const ALL_APPS = [
    {
        name: 'operator',
        routes: [
            '/',
            '/callcenter',
            '/admin',
            '/admin/gebiete',
            '/admin/kooperation',
            '/admin/datenschutz',
            '/personal/dienstplan',
            '/zeit',
            '/flotte/pruefungen',
            '/flotte',
            '/flotte/fahrer',
            '/flotte/schichten',
            '/flotte/checklisten',
            '/flotte/schaeden',
            '/leitstelle/fahrzeuge',
            '/leitstelle/stoerungen',
            '/leitstelle/vorfaelle',
            '/personal',
            '/finanzen',
            '/finanzen/belege',
            '/finanzen/fahrzeugkosten',
            '/finanzen/kostenuebersicht',
            '/auswertung/kennzahlen',
            '/auswertung/live',
            '/konto',
            '/gibt-es-nicht',
        ],
    },
    {
        name: 'passenger',
        routes: [
            '/',
            '/angebote',
            '/checkout/x',
            '/reisen',
            '/reisen/x',
            '/konto',
            '/hilfe',
            '/gibt-es-nicht',
        ],
    },
];
// A11Y_APPS=passenger beschränkt den Lauf auf einzelne Anwendungen (Gate-Suite M2: nur die Passenger-PWA).
const ONLY = (process.env.A11Y_APPS ?? '').split(',').filter(Boolean);
const APPS = ALL_APPS.filter((a) => ONLY.length === 0 || ONLY.includes(a.name));
const VIEWPORTS = [
    { label: 'Desktop 1280x800', width: 1280, height: 800 },
    { label: 'Mobil 320x568', width: 320, height: 568 },
];

/**
 * Anmeldung gegen einen simulierten OIDC-Anbieter (Authorization Code mit PKCE): die Seite fordert Anmeldung (WP-M1-44), also leitet die Simulation den Autorisierungsaufruf
 * mit `code` und `state` zurück und beantwortet den Tokenaufruf. Tokens liegen nur im Tab, deshalb wird danach clientseitig navigiert (kein Neuladen).
 */
async function installOidcSimulation(page) {
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
}

async function openRoute(page, base, route, state) {
    if (!state.signedIn) {
        await page.goto(`${base}/`);
        await page.getByRole('button', { name: 'Anmelden' }).click();
        await page.waitForSelector('main h1, main [data-testid="state-view"]');
        state.signedIn = true;
    }
    await page.evaluate((r) => {
        history.pushState({}, '', r);
        window.dispatchEvent(new PopStateEvent('popstate'));
    }, route);
    await page.evaluate(() => document.getElementById('a11y-textscale')?.remove());
}

const failures = [];
const fail = (msg) => {
    failures.push(msg);
    console.error('FEHLER', msg);
};

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
let checks = 0;

for (const app of APPS) {
    const server = await createServer({
        root: new URL(`../${app.name}/`, import.meta.url).pathname,
        server: { port: 0, host: '127.0.0.1' },
        logLevel: 'error',
    });
    await server.listen();
    const base = `http://127.0.0.1:${server.httpServer.address().port}`;

    for (const vp of VIEWPORTS) {
        const context = await browser.newContext({
            viewport: { width: vp.width, height: vp.height },
            locale: 'de-DE',
            reducedMotion: 'reduce',
        });
        const page = await context.newPage();
        const authState = { signedIn: false };
        await installOidcSimulation(page);
        await page.route('**/v1/me', (route) =>
            route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(ME),
            }),
        );
        for (const route of app.routes) {
            for (const textScale of [100, 200]) {
                const where = `${app.name} ${route} ${vp.label} Text ${textScale} %`;
                await openRoute(page, base, route, authState);
                await page.waitForSelector('main h1, main [data-testid="state-view"]');
                await page.waitForFunction(() => !document.querySelector('[aria-busy="true"]'));
                // Inhaltsprüfung: das Dateninventar muss geladen sein, sonst prüft axe nur den Ladezustand.
                if (app.name === 'operator' && route === '/admin/datenschutz')
                    await page.waitForSelector('table', { timeout: 5000 }).catch(() => undefined);
                if (
                    app.name === 'operator' &&
                    route === '/admin/datenschutz' &&
                    (await page.locator('table').count()) === 0
                )
                    fail(`${where}: Dateninventar-Tabellen nicht gerendert`);
                if (textScale !== 100)
                    await page
                        .addStyleTag({
                            content: `html { font-size: ${textScale}% !important; }`,
                        })
                        .then((h) => h.evaluate((el) => (el.id = 'a11y-textscale')));
                checks += 1;
                if (process.env.A11Y_TRACE)
                    console.log(
                        where,
                        '->',
                        (
                            (await page
                                .locator('main h1')
                                .first()
                                .textContent()
                                .catch(() => '')) ?? ''
                        ).slice(0, 50),
                    );

                // Reflow: kein horizontales Scrollen der Seite.
                const overflow = await page.evaluate(
                    () =>
                        document.documentElement.scrollWidth - document.documentElement.clientWidth,
                );
                if (overflow > 0)
                    fail(`${where}: horizontales Scrollen (${overflow}px Überbreite)`);

                // Mobile Navigation einblenden, damit ihre Ziele mitgeprüft werden.
                const toggle = page.getByRole('button', { name: 'Menü' });
                if (await toggle.isVisible()) await toggle.click();

                // Touchflächen: sichtbare Schaltflächen, Navigationslinks und Eingaben mindestens 44x44 CSS-Pixel.
                const small = await page.evaluate(() =>
                    [
                        ...document.querySelectorAll(
                            'button, nav a, input:not([type=hidden]), select, textarea, .tk-skip-link:focus',
                        ),
                    ]
                        .filter((el) => el.getClientRects().length > 0)
                        .map((el) =>
                            el.matches('[type=checkbox], [type=radio]') && el.closest('label')
                                ? el.closest('label')
                                : el,
                        )
                        .map((el) => ({ el, r: el.getBoundingClientRect() }))
                        .filter(({ r }) => r.width > 0 && (r.width < 43.5 || r.height < 43.5))
                        .map(
                            ({ el, r }) =>
                                `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 30)}" ${Math.round(r.width)}x${Math.round(r.height)}`,
                        ),
                );
                for (const s of small) fail(`${where}: Touchfläche kleiner als 44x44: ${s}`);

                // axe im echten Browser, einschließlich Farbkontrast (gemeinsame Regeln, `a11y-regeln.mjs`).
                for (const v of await axeVerstoesse(page)) fail(`${where}: ${v}`);

                if (textScale === 100) {
                    // Fokusregeln: Rollen fokussierbar, kein positiver tabindex, sichtbarer Fokus an den Tabulatorstopps (gemeinsame Regeln).
                    for (const v of await fokusVerstoesse(page, 4)) fail(`${where}: ${v}`);
                    // Reduzierte Bewegung: Übergänge und Animationen sind praktisch aufgehoben.
                    const motion = await page.evaluate(() => {
                        const cs = getComputedStyle(document.querySelector('main'));
                        return {
                            t: parseFloat(cs.transitionDuration),
                            a: parseFloat(cs.animationDuration),
                        };
                    });
                    if (motion.t > 0.001 || motion.a > 0.001)
                        fail(`${where}: reduzierte Bewegung wird nicht respektiert`);
                }
            }
        }
        await context.close();
    }
    await server.close();
}
await browser.close();

console.log(
    `Browser-Barrierefreiheitsprüfung: ${checks} Seitenansichten geprüft, ${failures.length} Verstöße`,
);
process.exit(failures.length > 0 ? 1 : 0);
