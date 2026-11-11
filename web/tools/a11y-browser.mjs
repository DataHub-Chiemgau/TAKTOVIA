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
import { createRequire } from 'node:module';
import { createServer } from 'vite';
import { chromium } from 'playwright-core';

const require = createRequire(import.meta.url);
const axeSource = require.resolve('axe-core/axe.min.js');

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
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [],
};

const APPS = [
    {
        name: 'operator',
        routes: [
            '/',
            '/callcenter',
            '/admin',
            '/admin/gebiete',
            '/admin/kooperation',
            '/admin/datenschutz',
            '/gibt-es-nicht',
        ],
    },
    { name: 'passenger', routes: ['/', '/reisen', '/konto', '/gibt-es-nicht'] },
];
const VIEWPORTS = [
    { label: 'Desktop 1280x800', width: 1280, height: 800 },
    { label: 'Mobil 320x568', width: 320, height: 568 },
];

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
                await page.goto(base + route);
                await page.waitForSelector('main h1, main [data-testid="state-view"]');
                if (textScale !== 100)
                    await page.addStyleTag({
                        content: `html { font-size: ${textScale}% !important; }`,
                    });
                checks += 1;

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
                        .map((el) => ({ el, r: el.getBoundingClientRect() }))
                        .filter(({ r }) => r.width > 0 && (r.width < 43.5 || r.height < 43.5))
                        .map(
                            ({ el, r }) =>
                                `${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 30)}" ${Math.round(r.width)}x${Math.round(r.height)}`,
                        ),
                );
                for (const s of small) fail(`${where}: Touchfläche kleiner als 44x44: ${s}`);

                // axe im echten Browser, einschließlich Farbkontrast.
                await page.addScriptTag({ path: axeSource });
                const violations = await page.evaluate(async () => {
                    const r = await window.axe.run(document, {
                        runOnly: {
                            type: 'tag',
                            values: [
                                'wcag2a',
                                'wcag2aa',
                                'wcag21a',
                                'wcag21aa',
                                'wcag22aa',
                                'best-practice',
                            ],
                        },
                    });
                    return r.violations.map(
                        (v) =>
                            `${v.id}: ${v.help} (${v.nodes.length}) ${v.nodes[0]?.html.slice(0, 120)}`,
                    );
                });
                for (const v of violations) fail(`${where}: axe ${v}`);

                if (textScale === 100) {
                    // Sichtbarer Fokus: die ersten Tabulatorstopps tragen einen Fokusrahmen.
                    for (let i = 0; i < 4; i += 1) {
                        await page.keyboard.press('Tab');
                        const outline = await page.evaluate(() => {
                            const el = document.activeElement;
                            if (!el || el === document.body) return null;
                            const cs = getComputedStyle(el);
                            return { style: cs.outlineStyle, width: parseFloat(cs.outlineWidth) };
                        });
                        if (outline && (outline.style === 'none' || outline.width < 2))
                            fail(`${where}: Tabulatorstopp ${i + 1} ohne sichtbaren Fokus`);
                    }
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
