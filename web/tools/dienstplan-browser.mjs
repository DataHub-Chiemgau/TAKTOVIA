#!/usr/bin/env node
/**
 * Browserlauf für UI-12 Dienstplan (WP-M4-18/-19): echter Chrome, echte Drag-and-drop-Ereignisse, axe-core inklusive Farbkontrast, Reflow bei 320 Pixeln.
 * Die API wird vom zustandsbehafteten Server-Ersatz `src/dienstplan/testwelt.ts` beantwortet (Modell des Vertrags); es läuft KEIN Backend. Der Lauf ersetzt
 * deshalb kein E2E gegen den echten Server, belegt aber Darstellung, Drag-and-drop, Tastaturpfad und Barrierefreiheit im echten Browser.
 * Braucht Chrome/Chromium (CHROME_PATH oder übliche Pfade). Aufruf im Verzeichnis web/: npm run dienstplan:browser
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

const failures = [];
const fail = (msg) => {
    failures.push(msg);
    console.error('FEHLER', msg);
};
let checks = 0;
const ok = (cond, msg) => {
    checks += 1;
    if (!cond) fail(msg);
};

const server = await createServer({
    root: new URL('../operator/', import.meta.url).pathname,
    server: { port: 0, host: '127.0.0.1' },
    logLevel: 'error',
});
await server.listen();
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
const welt = await server.ssrLoadModule('/src/dienstplan/testwelt.ts');
const api = await server.ssrLoadModule('@taktovia/api-client');

const ME = {
    subject: 'user-1',
    issuer: 'https://idp.example/realms/test',
    tenant_id: '00000000-0000-4000-8000-000000000001',
    tenant_name: 'Verkehrsbetrieb Nord',
    roles: ['STAFF_PLANNING'],
    scopes: ['me:read', 'rosters:read', 'rosters:write', 'rosters:publish', 'drivers:read'],
    service_scope: null,
    session_id: '00000000-0000-4000-8000-000000000002',
    mfa: true,
    tenants: [],
};

const ops = Object.entries(api.operationTable).map(([id, o]) => ({
    id,
    method: o.method,
    rx: new RegExp(`^${o.path.replace(/\{([a-z_]+)\}/g, '(?<$1>[^/]+)')}$`),
}));

/** Beantwortet /v1/*-Aufrufe über den Server-Ersatz der Testwelt. */
async function installApi(page, w) {
    await page.route('**/v1/**', async (route) => {
        const req = route.request();
        const url = new URL(req.url());
        const json = (status, body) =>
            route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
        if (url.pathname === '/v1/me') return json(200, ME);
        const op = ops.find((o) => o.method === req.method() && o.rx.test(url.pathname));
        if (!op)
            return json(404, { status: 404, code: 'NOT_FOUND', type: 'NOT_FOUND', title: 'x' });
        const path = url.pathname.match(op.rx)?.groups ?? {};
        const query = Object.fromEntries(url.searchParams);
        const ifMatch = req.headers()['if-match'];
        const body = req.postData() ? JSON.parse(req.postData()) : undefined;
        try {
            const res = w.handle(op.id, {
                path,
                query,
                body,
                ...(ifMatch ? { revision: Number(ifMatch.replaceAll('"', '')) } : {}),
            });
            return json(op.method === 'POST' ? 201 : 200, res);
        } catch (e) {
            return json(e.status ?? 500, {
                status: e.status ?? 500,
                code: 'TEST',
                type: 'TEST',
                title: 'x',
            });
        }
    });
}

const axeAll = async (page, where) => {
    await page.addScriptTag({ path: axeSource });
    const violations = await page.evaluate(async () => {
        const r = await window.axe.run(document, {
            runOnly: {
                type: 'tag',
                values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'],
            },
        });
        return r.violations.map(
            (v) => `${v.id}: ${v.help} (${v.nodes.length}) ${v.nodes[0]?.html.slice(0, 140)}`,
        );
    });
    checks += 1;
    for (const v of violations) fail(`${where}: axe ${v}`);
};

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
// Der Prüfstand wird unter /dienstplan-harness.html ausgeliefert und startet auf der Startseite der Anwendung.
async function start(viewport) {
    const w = welt.baueTestwelt();
    const context = await browser.newContext({
        viewport,
        locale: 'de-DE',
        reducedMotion: 'reduce',
    });
    const page = await context.newPage();
    await page.clock.install({ time: new Date('2026-12-02T10:00:00Z') });
    await installApi(page, w);
    page.on('pageerror', (e) => console.error('pageerror', e.message));
    await page.goto(`${base}/dienstplan-harness.html`);
    return { w, context, page };
}

for (const vp of [
    { label: 'Desktop 1280x1000', width: 1280, height: 1000 },
    { label: 'Mobil 320x568', width: 320, height: 568 },
]) {
    const where = `Dienstplan ${vp.label}`;
    const { w, context, page } = await start({ width: vp.width, height: vp.height });
    // Die Anwendung startet auf "/", Navigation per Link wie ein Planer.
    await page.waitForSelector('nav[aria-label="Hauptnavigation"]', { state: 'attached' });
    const menue = page.getByRole('button', { name: 'Menü' });
    if (await menue.isVisible()) await menue.click();
    await page.getByRole('link', { name: 'Dienstplan' }).click();
    await page.waitForSelector('table.dp-tabelle');
    await page.evaluate(() => {
        window.__marker = 'gleiche-seite';
    });

    ok((await page.locator('th.dp-tag').count()) === 7, `${where}: Wochenansicht hat nicht 7 Tage`);
    await page.getByLabel('Wochen').selectOption('2');
    await page.waitForFunction(() => document.querySelectorAll('th.dp-tag').length === 14);
    ok(
        (await page.getByRole('rowheader', { name: /Offene Dienste/ }).textContent())?.includes(
            '2 offen',
        ),
        `${where}: Zähler offener Dienste fehlt`,
    );
    const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    ok(overflow <= 0, `${where}: horizontales Scrollen der Seite (${overflow}px)`);
    await axeAll(page, `${where} Raster`);

    // zurück auf eine Woche, damit Quelle und Ziel gemeinsam sichtbar sind
    await page.getByLabel('Wochen').selectOption('1');
    await page.waitForFunction(() => document.querySelectorAll('th.dp-tag').length === 7);
    // Zuweisen der offenen Rollstuhl-Schicht (Mittwoch, 2. Dezember) an Anna Beispiel (ROLLSTUHL-qualifiziert)
    const quelle = page.locator(`[data-shift-id="${welt.uuid(0x503)}"]`);
    const annaZeile = page
        .getByRole('rowheader', { name: /Anna Beispiel/ })
        .locator('xpath=ancestor::tr');
    if (vp.width >= 1000) {
        // echtes Drag-and-drop mit der Maus (Tag 3 der Spalten = Mittwoch)
        const ziel = annaZeile.getByRole('cell').nth(2);
        await ziel.scrollIntoViewIfNeeded();
        await quelle.scrollIntoViewIfNeeded();
        const von = await quelle.boundingBox();
        const nach = await ziel.boundingBox();
        await page.mouse.move(von.x + 6, von.y + 6);
        await page.mouse.down();
        await page.mouse.move(von.x + 20, von.y + 20, { steps: 5 });
        await page.mouse.move(nach.x + nach.width / 2, nach.y + 10, { steps: 10 });
        await page.mouse.move(nach.x + nach.width / 2 + 2, nach.y + 12, { steps: 3 });
        await page.mouse.up();
    } else {
        // schmaler Viewport ohne Maus: gleichwertiger Tastaturpfad über den Dialog
        const k = quelle.getByRole('button', { name: /Verschieben\/Zuweisen/ });
        await k.focus();
        await page.keyboard.press('Enter');
        await page.getByRole('dialog').waitFor();
        await page.waitForFunction(
            () =>
                document.querySelectorAll('optgroup[label="Geeignete Personen"] option').length > 0,
        );
        await page.getByLabel('Person').selectOption({ label: 'Anna Beispiel' });
        await page.getByRole('button', { name: 'Speichern' }).click();
    }
    await page.waitForFunction(() =>
        document.querySelector('.dp-live')?.textContent?.startsWith('Gespeichert'),
    );
    ok(
        w.entwurf.get(welt.IDS.rosterW1).find((s) => s.id === welt.uuid(0x503)).driver_ref ===
            welt.IDS.anna,
        `${where}: Zuweisung nicht gespeichert`,
    );
    ok(
        (await page.evaluate(() => window.__marker)) === 'gleiche-seite',
        `${where}: Seite wurde neu geladen`,
    );
    ok(
        (await annaZeile.locator(`[data-shift-id="${welt.uuid(0x503)}"]`).count()) === 1,
        `${where}: Schicht nicht in der Zeile der Person`,
    );
    ok(
        (await page.locator('[data-shift-id]:has-text("Konflikt")').count()) === 0,
        `${where}: unerwarteter Konflikt bei geeigneter Person`,
    );

    // Tastaturpfad: Schaltfläche fokussieren, per Enter öffnen, per Escape schließen
    const knopf = page
        .locator(`[data-shift-id="${welt.uuid(0x504)}"]`)
        .getByRole('button', { name: /Verschieben\/Zuweisen/ });
    await knopf.focus();
    await page.keyboard.press('Enter');
    await page.getByRole('dialog').waitFor();
    await page.waitForFunction(
        () => document.querySelectorAll('optgroup[label="Geeignete Personen"] option').length > 0,
    );
    await axeAll(page, `${where} Dialog`);
    await page.keyboard.press('Escape');
    await page.getByRole('dialog').waitFor({ state: 'detached' });
    ok(
        await knopf.evaluate((el) => el === document.activeElement),
        `${where}: Fokus kehrt nach Escape nicht zur Schaltfläche zurück`,
    );

    // Tabellenalternative
    await page.getByRole('button', { name: 'Tabellenalternative anzeigen' }).click();
    await page.getByRole('table', { name: /Tabellenalternative/ }).waitFor();
    await axeAll(page, `${where} Tabellenalternative`);

    await context.close();
}

await browser.close();
await server.close();
console.log(`Browserlauf UI-12 Dienstplan: ${checks} Prüfungen, ${failures.length} Verstöße`);
process.exit(failures.length > 0 ? 1 : 0);
