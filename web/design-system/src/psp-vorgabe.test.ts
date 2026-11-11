import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * WP-M1-37 (SEC-008): Vorgabe an das Designsystem. Zahlungsdateneingabe erfolgt ausschließlich in PSP-gehosteten Komponenten; das
 * Designsystem und die Anwendungen enthalten keine Eingabefelder für Kartendaten (docs/umsetzungsplan/vertraege/psp-trennung.md).
 */
const FORBIDDEN =
  /autocomplete\s*=\s*["']?cc-|name\s*=\s*["'](card|cc|cvc|cvv|pan)[-_a-z]*["']|inputMode\s*=\s*["']numeric["'][^>]*(card|cvc)|\b(cardNumber|card_number|cvc|cvv|cardHolder|expiryDate)\b\s*[:=]/i;

export function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return name === 'node_modules' ? [] : sourceFiles(path);
    return /\.(tsx?|css)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

export function cardFieldFindings(files: Record<string, string>): string[] {
  return Object.entries(files)
    .filter(([, text]) => FORBIDDEN.test(text))
    .map(([file]) => file);
}

describe('PSP-Vorgabe: keine Kartendateneingabe im Designsystem', () => {
  const roots = ['../', '../../operator/src', '../../passenger/src'].map((r) =>
    resolve(__dirname, r),
  );

  it('keine Quelldatei enthält ein Eingabefeld oder einen Typ für Kartendaten', () => {
    const files = Object.fromEntries(
      roots.flatMap(sourceFiles).map((f) => [f, readFileSync(f, 'utf8')] as const),
    );
    expect(Object.keys(files).length).toBeGreaterThan(10);
    expect(cardFieldFindings(files)).toEqual([]);
  });

  it('die Prüfung schlägt an (negativ)', () => {
    expect(cardFieldFindings({ a: '<input autocomplete="cc-number" />' })).toEqual(['a']);
    expect(cardFieldFindings({ b: '<input name="card-number" />' })).toEqual(['b']);
    expect(cardFieldFindings({ c: 'const cardNumber = ""' })).toEqual(['c']);
    expect(cardFieldFindings({ d: '<input name="email" autocomplete="email" />' })).toEqual([]);
  });
});
