/**
 * Gemeinsame Barrierefreiheitsregeln für alle Browser-Prüfläufe (WP-M5-18): axe-core (WCAG 2.2 AA inklusive Farbkontrast), zugänglicher Name, Fokusfähigkeit und sichtbarer Fokus.
 * `a11y-browser.mjs` wendet sie auf die Oberflächen an, `a11y-gate-selftest.mjs` weist nach, dass sie bei Probekomponenten mit Mangel fehlschlagen.
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
export const axeSource = require.resolve('axe-core/axe.min.js');

export const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

/** axe im echten Browser (Farbkontrast eingeschlossen); liefert Verstöße als Texte. */
export async function axeVerstoesse(page) {
    await page.addScriptTag({ path: axeSource });
    return page.evaluate(async (tags) => {
        const r = await window.axe.run(document, { runOnly: { type: 'tag', values: tags } });
        return r.violations.map(
            (v) => `axe ${v.id}: ${v.help} (${v.nodes.length}) ${v.nodes[0]?.html.slice(0, 120)}`,
        );
    }, AXE_TAGS);
}

/**
 * Fokusregeln: (1) Elemente mit interaktiver ARIA-Rolle müssen fokussierbar sein (sonst nur mit Zeiger bedienbar), (2) kein positiver tabindex, (3) jeder Tabulatorstopp trägt einen
 * sichtbaren Fokusrahmen (Linie oder Schatten, mindestens 2 px).
 */
export async function fokusVerstoesse(page, maxStops = 12) {
    const found = await page.evaluate(() => {
        const ROLES =
            'button,link,checkbox,radio,switch,tab,menuitem,option,textbox,combobox,slider,spinbutton';
        const native =
            'a[href],button,input:not([type=hidden]),select,textarea,summary,[contenteditable=true]';
        const out = [];
        for (const el of document.querySelectorAll(
            ROLES.split(',')
                .map((r) => `[role=${r}]`)
                .join(','),
        )) {
            if (el.getClientRects().length === 0) continue;
            const ti = el.getAttribute('tabindex');
            if (!el.matches(native) && (ti === null || Number(ti) < 0))
                out.push(
                    `Rolle ${el.getAttribute('role')} nicht fokussierbar: ${el.outerHTML.slice(0, 100)}`,
                );
        }
        for (const el of document.querySelectorAll('[tabindex]'))
            if (Number(el.getAttribute('tabindex')) > 0)
                out.push(`positiver tabindex: ${el.outerHTML.slice(0, 100)}`);
        return out;
    });
    const problems = [...found];
    await page.evaluate(
        () => document.activeElement instanceof HTMLElement && document.activeElement.blur(),
    );
    for (let i = 0; i < maxStops; i += 1) {
        await page.keyboard.press('Tab');
        const stop = await page.evaluate(() => {
            const el = document.activeElement;
            if (!el || el === document.body) return null;
            const cs = getComputedStyle(el);
            const outline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) >= 2;
            const shadow = cs.boxShadow !== 'none' && cs.boxShadow !== '';
            return { visible: outline || shadow, html: el.outerHTML.slice(0, 100) };
        });
        if (stop === null) break;
        if (!stop.visible)
            problems.push(`Tabulatorstopp ${i + 1} ohne sichtbaren Fokus: ${stop.html}`);
    }
    return problems;
}

/** Alle Regeln auf der geladenen Seite. */
export async function alleVerstoesse(page) {
    return [...(await axeVerstoesse(page)), ...(await fokusVerstoesse(page))];
}
