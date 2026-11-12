import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { texte } from '../texte';
import { REJECTION_CODES, RejectionMessage } from './RejectionMessage';

afterEach(cleanup);

describe('RejectionMessage (WP-M2-13)', () => {
  it('textbaustein_mit_naechster_handlung_je_grund', () => {
    const seen = new Set<string>();
    for (const code of REJECTION_CODES) {
      const { unmount } = render(<RejectionMessage code={code} />);
      const box = screen.getByTestId('rejection');
      expect(box.getAttribute('data-code')).toBe(code);
      const [reason, action] = Array.from(box.querySelectorAll('p')).map(
        (p) => p.textContent ?? '',
      );
      expect(reason?.length).toBeGreaterThan(10);
      expect(action).toContain('Nächste Handlung:');
      expect(action?.replace('Nächste Handlung:', '').trim().length).toBeGreaterThan(10);
      seen.add(reason ?? '');
      unmount();
    }
    // jeder Grund hat einen eigenen Text (Kapazität, Regelverbot und Gebiet sind für den Fahrgast unterscheidbar)
    expect(seen.size).toBe(REJECTION_CODES.length);
  });

  it('nennt_nur_den_primaergrund_und_ist_als_status_ausgezeichnet', async () => {
    const { container } = render(<RejectionMessage code="OUTSIDE_AREA" />);
    expect(screen.getByRole('status').textContent).toContain('Bediengebiet');
    expect(screen.queryByText(/Kapazität|Platz/)).toBeNull();
    const violations = await runAxe(container);
    expect(formatViolations(violations)).toBe('');
  });

  it('texte_entsprechen_dem_katalog_der_datenbank', () => {
    // Die Textbausteine sind eine Kopie des Katalogs `rejection_reason` (Migration V20130); ein Auseinanderlaufen fällt hier auf.
    const sql = readFileSync(
      join(process.cwd(), '..', '..', 'db', 'migrations', 'V20130__ablehnungsgruende.sql'),
      'utf-8',
    );
    const rows = [
      ...sql.matchAll(
        /^\s+\('([A-Z_]+)',\s+\d+,\s+(?:true|false),\s+(?:'[A-Z_]+'|NULL),\s+'([^']+)',\s+'([^']+)'\)[,;]/gm,
      ),
    ];
    expect(rows.length).toBe(REJECTION_CODES.length + 1); // plus TECHNICAL (kein Ablehnungsgrund, nur Meldung)
    const catalog = texte as Record<string, string>;
    for (const [, code, label, next] of rows) {
      if (code === 'TECHNICAL') continue;
      const key = (code ?? '')
        .toLowerCase()
        .split('_')
        .map((p, i) => (i === 0 ? p : p.charAt(0).toUpperCase() + p.slice(1)))
        .join('');
      expect(catalog[`rej.${key}.text`]).toBe(label);
      expect(catalog[`rej.${key}.next`]).toBe(next);
    }
  });
});
