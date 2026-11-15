import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

/**
 * WP-M5-30: Zustandsmatrix der Kernreisen der Leitstelle, Flotte, Belegprüfung, Kostenübersicht, Verwaltung und Audit (UI-07, UI-11, UI-14, UI-15,
 * UI-17, UI-20; Kap. 06 §6.9) auf dem vollständigen Anwendungsrahmen. Je Route und Zustand (Daten leer, Serverfehler, fehlendes Recht, Verbindung
 * unterbrochen) wird geprüft: genau eine Überschrift der Ebene 1, axe WCAG 2.2 AA (ohne Kontrast, den prüft `npm run a11y:browser` im echten
 * Browser), jedes Bedienelement ist ein nativer, benannter Tabulatorstopp ohne positiven tabindex, Fehler- und Rechtezustände sind als `alert`
 * angesagt, und es gibt keine Karte als einzige Bedienmöglichkeit. NICHT geprüft (jsdom): Kontrast, Reflow, Screenreader-Ausgabe.
 */

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
];

interface Reise {
  ui: string;
  route: string;
  /** Rolle in /v1/me */
  roles: string[];
}

const REISEN: Reise[] = [
  { ui: 'UI-07 Leitstelle Livebetrieb', route: '/leitstelle/fahrzeuge', roles: ['CALLCENTER'] },
  { ui: 'UI-07 Störungen', route: '/leitstelle/stoerungen', roles: ['CALLCENTER'] },
  { ui: 'UI-11 Flotte Schäden und Reparatur', route: '/flotte/schaeden', roles: ['ADMIN'] },
  { ui: 'UI-11 Fahrzeugstamm', route: '/flotte', roles: ['ADMIN'] },
  { ui: 'UI-14 Belege prüfen und freigeben', route: '/finanzen/belege', roles: ['FINANCE'] },
  { ui: 'UI-15 Kostenübersicht', route: '/finanzen/kostenuebersicht', roles: ['FINANCE'] },
  { ui: 'UI-17 Gebiete, Stops, Tarife', route: '/admin/gebiete', roles: ['ADMIN'] },
  { ui: 'UI-20 Datenschutz, Audit, Support', route: '/admin/datenschutz', roles: ['ADMIN'] },
];

type Modus = 'leer' | 'serverfehler' | 'kein_recht' | 'verbindung_weg' | 'ohne_scope';

function clientFor(roles: string[], modus: Modus): ApiClient {
  return {
    call: (id: OperationId) => {
      if (id === 'getMe')
        return Promise.resolve(sampleMe({ roles, scopes: modus === 'ohne_scope' ? [] : SCOPES }));
      if (modus === 'serverfehler') return Promise.reject(new ApiError(500));
      if (modus === 'kein_recht') return Promise.reject(new ApiError(403));
      if (modus === 'verbindung_weg') return Promise.reject(new ApiError(0));
      return Promise.resolve({
        items: [],
        next_cursor: null,
        as_of: '2026-10-25T08:00:00Z',
      } as never);
    },
  } as unknown as ApiClient;
}

function tabbable(root: Element): HTMLElement[] {
  const sel =
    'a[href], button, input:not([type=hidden]), select, textarea, summary, [tabindex]:not([tabindex="-1"])';
  return [...root.querySelectorAll<HTMLElement>(sel)].filter(
    (el) =>
      !(el as HTMLButtonElement).disabled && !el.closest('[hidden], [aria-hidden="true"], [inert]'),
  );
}

function accessibleName(el: HTMLElement): string {
  const labelledby = el.getAttribute('aria-labelledby');
  const byRef = labelledby
    ? labelledby
        .split(' ')
        .map((id) => document.getElementById(id)?.textContent ?? '')
        .join(' ')
    : '';
  return (
    el.getAttribute('aria-label') ??
    (byRef.trim() || null) ??
    (el.id ? document.querySelector(`label[for="${el.id}"]`)?.textContent : null) ??
    el.closest('label')?.textContent ??
    el.getAttribute('title') ??
    el.textContent ??
    ''
  ).trim();
}

async function zeige(r: Reise, modus: Modus) {
  const view = render(
    <MemoryRouter initialEntries={[r.route]}>
      <SessionProvider
        client={clientFor(r.roles, modus)}
        now={() => Date.parse('2026-10-25T09:00:00+01:00')}
      >
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  await screen.findByRole('navigation', { name: 'Hauptnavigation' });
  await waitFor(() => expect(document.querySelector('[aria-busy="true"]')).toBeNull(), {
    timeout: 4000,
  });
  return view;
}

function pruefeBedienbarkeit(where: string, root: Element) {
  for (const el of tabbable(root)) {
    expect(
      Number(el.getAttribute('tabindex') ?? 0),
      `${where}: positiver tabindex`,
    ).toBeLessThanOrEqual(0);
    expect(
      accessibleName(el).length,
      `${where}: Bedienelement ohne Namen ${el.outerHTML.slice(0, 90)}`,
    ).toBeGreaterThan(0);
    expect(el.closest('svg'), `${where}: Zeigerelement im Tabulatorpfad`).toBeNull();
  }
  expect(root.querySelector('[role="application"]'), `${where}: Karte als Anwendung`).toBeNull();
}

describe('WP-M5-30 Kernreisen Leitstelle, Flotte, Belege, Verwaltung: Zustandsmatrix', () => {
  afterEach(cleanup);

  for (const r of REISEN) {
    for (const modus of [
      'leer',
      'serverfehler',
      'kein_recht',
      'verbindung_weg',
      'ohne_scope',
    ] as Modus[]) {
      it(`${r.route} ${modus}: eine Ebene-1-Überschrift, axe WCAG 2.2 AA, benannte Tabulatorstopps, Fehler angesagt`, async () => {
        const where = `${r.ui} (${modus})`;
        const { container } = await zeige(r, modus);
        const main = screen.getByRole('main');
        expect(main.querySelectorAll('h1'), `${where}: genau eine h1`).toHaveLength(1);
        if (modus === 'ohne_scope') {
          // fehlende Freigaberolle: der Zustand wird angesagt (alert oder status) und nennt das Fehlen des Rechts, es werden keine Daten geladen
          const ansage = main.querySelector('[role="alert"], [role="status"]');
          expect(ansage, `${where}: fehlendes Recht nicht angesagt`).not.toBeNull();
          expect((ansage?.textContent ?? '').trim().length).toBeGreaterThan(10);
        } else if (modus !== 'leer') {
          // Fehler- und Rechtezustände werden angesagt (role=alert) und sind nicht nur farblich erkennbar (Überschrift und Text)
          const alert = main.querySelector('[role="alert"]');
          expect(alert, `${where}: Zustand nicht als alert angesagt`).not.toBeNull();
          expect((alert?.textContent ?? '').trim().length).toBeGreaterThan(10);
        }
        pruefeBedienbarkeit(where, container);
        expect(formatViolations(await runAxe(container)), where).toBe('');
      }, 30_000);
    }
  }
});
