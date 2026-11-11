import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const css = readFileSync(resolve(__dirname, 'styles.css'), 'utf8');

/**
 * Statische Prüfung der Stilregeln. jsdom berechnet kein Layout; die tatsächliche Messung (Reflow bei 320 CSS-Pixeln,
 * 200 % Text, Touchflächen 44x44, Kontrast) macht web/tools/a11y-browser.mjs in einem echten Browser.
 */
describe('Stilregeln des Designsystems (statisch)', () => {
  it('sichtbarer Fokus für alle bedienbaren Elemente', () => {
    expect(css).toMatch(/:focus-visible\s*\{[^}]*outline:\s*var\(--tk-focus-width\)/);
    expect(css).not.toMatch(/outline:\s*(none|0)\b/);
  });

  it('Touchflächen mindestens 44x44 CSS-Pixel über ein Token', () => {
    expect(css).toMatch(/--tk-touch-min:\s*44px/);
    expect(css).toMatch(/min-height:\s*var\(--tk-touch-min\)/);
    expect(css).toMatch(/min-width:\s*var\(--tk-touch-min\)/);
  });

  it('reduzierte Bewegung wird respektiert', () => {
    expect(css).toMatch(/@media \(prefers-reduced-motion:\s*reduce\)/);
  });

  it('Schriftgrößen relativ (rem/em), damit 200 % Textvergrößerung greift', () => {
    expect(css).not.toMatch(/font-size:\s*\d+px/);
  });

  it('kein erzwungenes horizontales Scrollen: keine feste Breite über 320px, Umbruch langer Wörter', () => {
    expect(css).not.toMatch(/(^|[^-])width:\s*(3[2-9]\d|[4-9]\d\d|\d{4,})px/m);
    expect(css).toMatch(/overflow-wrap:\s*anywhere/);
    expect(css).toMatch(/@media \(min-width:/);
  });

  it('Tabellen scrollen in einem eigenen Behälter statt die Seite zu verbreitern', () => {
    expect(css).toMatch(/\.tk-table-wrap\s*\{[^}]*overflow-x:\s*auto/);
  });
});
