#!/usr/bin/env node
/**
 * Selbsttest der Barrierefreiheitsprüfung als CI-Schritt (WP-M5-18, AK 4): eine saubere Probekomponente besteht, je eine Probekomponente ohne zugänglichen Namen, mit fehlendem Fokus
 * (nur per Zeiger bedienbar, Fokusrahmen entfernt) und mit unzureichendem Kontrast lässt dieselben Regeln (`a11y-regeln.mjs`) fehlschlagen. Der Lauf endet nur grün, wenn
 * alle vier Erwartungen eintreten. Braucht Chrome (CHROME_PATH). Aufruf im Verzeichnis web/: npm run a11y:selftest
 */
import { existsSync } from 'node:fs';
import { chromium } from 'playwright-core';
import { alleVerstoesse } from './a11y-regeln.mjs';

const CHROME =
    process.env.CHROME_PATH ??
    ['/usr/bin/google-chrome', '/usr/bin/chromium', '/usr/bin/chromium-browser'].find(existsSync);
if (!CHROME) {
    console.error('FEHLER: kein Chrome/Chromium gefunden (CHROME_PATH setzen)');
    process.exit(2);
}

const page = (body, style = '') =>
    `<!doctype html><html lang="de"><head><meta charset="utf-8"><title>Probe</title><style>body{background:#fff;color:#111;font:16px sans-serif}:focus-visible{outline:3px solid #0b57d0;outline-offset:2px}${style}</style></head><body><main><h1>Probe</h1>${body}</main></body></html>`;

const PROBEN = [
    {
        name: 'sauber',
        html: page('<button type="button">Speichern</button><a href="/x">Weiter</a>'),
        erwartet: null,
    },
    {
        name: 'ohne zugaenglichen Namen',
        html: page('<button type="button"></button>'),
        erwartet: /button-name/,
    },
    {
        name: 'fehlender Fokus',
        html: page(
            '<div role="button" onclick="void 0">Nur per Zeiger</div><button type="button" style="outline:none;box-shadow:none">Ohne Fokusrahmen</button>',
        ),
        erwartet: /nicht fokussierbar|ohne sichtbaren Fokus/,
    },
    {
        name: 'unzureichender Kontrast',
        html: page('<p style="color:#aaa;background:#fff">Hellgrau auf Weiss</p>'),
        erwartet: /color-contrast/,
    },
];

const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
const context = await browser.newContext({
    viewport: { width: 800, height: 600 },
    locale: 'de-DE',
});
let bad = 0;
for (const probe of PROBEN) {
    const p = await context.newPage();
    await p.setContent(probe.html);
    const verstoesse = await alleVerstoesse(p);
    await p.close();
    const gruen =
        probe.erwartet === null
            ? verstoesse.length === 0
            : verstoesse.some((v) => probe.erwartet.test(v));
    console.log(
        `${gruen ? 'OK    ' : 'FEHLER'} Probe "${probe.name}": ${verstoesse.length} Verstöße${verstoesse[0] ? ` (${verstoesse[0].slice(0, 90)})` : ''}`,
    );
    if (!gruen) bad += 1;
}
await browser.close();
console.log(
    bad === 0
        ? 'Selbsttest der Barrierefreiheitsprüfung: bestanden'
        : `Selbsttest fehlgeschlagen: ${bad} Erwartung(en) nicht erfüllt`,
);
process.exit(bad === 0 ? 0 : 1);
