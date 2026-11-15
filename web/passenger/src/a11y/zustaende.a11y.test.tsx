import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

/**
 * WP-M5-30: Zustandsmatrix der Fahrgast-Kernreise (UI-01 Suche, UI-02 Angebote, UI-03 Checkout, UI-04 Reisen, UI-05 Konto; Kap. 06 §6.9) auf
 * dem vollständigen Anwendungsrahmen: je Route bei Serverfehler, fehlendem Recht, unterbrochener Verbindung und Gast ohne Rechte genau eine
 * Überschrift der Ebene 1, axe WCAG 2.2 AA (ohne Kontrast: `npm run a11y:browser`), benannte native Tabulatorstopps, angesagte Fehler (role=alert).
 * Die Erfolgsreise selbst (Tastatur, Live-Region, Hold-Ablauf) belegen `kernreise.a11y.test.tsx` und `booking/Checkout.test.tsx`.
 */
const ROUTEN = [
  ['UI-01 Suche', '/'],
  ['UI-02 Angebote', '/angebote?offer=00000000-0000-4000-8000-0000000000f3'],
  ['UI-03 Checkout', '/checkout/00000000-0000-4000-8000-0000000000f1'],
  ['UI-04 Reisen', '/reisen'],
  ['UI-04 Reise', '/reisen/00000000-0000-4000-8000-0000000000f2'],
  ['UI-05 Konto', '/konto'],
] as const;
type Modus = 'serverfehler' | 'kein_recht' | 'verbindung_weg' | 'nicht_gefunden';

function clientFor(modus: Modus): ApiClient {
  const fehler = { serverfehler: 500, kein_recht: 403, verbindung_weg: 0, nicht_gefunden: 404 }[
    modus
  ];
  return {
    call: (id: OperationId) =>
      id === 'getMe'
        ? Promise.resolve(
            sampleMe({ roles: ['PASSENGER'], scopes: ['journeys:book', 'passengers:read'] }),
          )
        : Promise.reject(new ApiError(fehler)),
  } as unknown as ApiClient;
}

describe('WP-M5-30 Fahrgast-Kernreise: Fehler- und Rechtezustände (UI-01 bis UI-05)', () => {
  afterEach(cleanup);
  for (const [ui, route] of ROUTEN) {
    for (const modus of [
      'serverfehler',
      'kein_recht',
      'verbindung_weg',
      'nicht_gefunden',
    ] as Modus[]) {
      it(`${route} ${modus}: Überschrift, axe WCAG 2.2 AA, benannte Tabulatorstopps, Zustand angesagt (außer Suche)`, async () => {
        const where = `${ui} (${modus})`;
        const { container } = render(
          <MemoryRouter initialEntries={[route]}>
            <SessionProvider
              client={clientFor(modus)}
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
        const main = screen.getByRole('main');
        expect(main.querySelectorAll('h1'), `${where}: genau eine h1`).toHaveLength(1);
        for (const el of container.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), input:not([type=hidden]), select, textarea, [tabindex]:not([tabindex="-1"])',
        )) {
          expect(
            Number(el.getAttribute('tabindex') ?? 0),
            `${where}: positiver tabindex`,
          ).toBeLessThanOrEqual(0);
          const name =
            el.getAttribute('aria-label') ??
            (el.id ? document.querySelector(`label[for="${el.id}"]`)?.textContent : null) ??
            el.closest('label')?.textContent ??
            el.textContent ??
            '';
          expect(
            name.trim().length,
            `${where}: ohne Namen ${el.outerHTML.slice(0, 80)}`,
          ).toBeGreaterThan(0);
        }
        // Fehlerzustände werden angesagt; die Suche (UI-01) lädt beim Öffnen nichts und hat daher keinen Fehlerzustand
        if (route !== '/')
          expect(
            main.querySelector('[role="alert"], [role="status"]'),
            `${where}: Zustand nicht angesagt`,
          ).not.toBeNull();
        expect(container.querySelector('[role="application"]')).toBeNull();
        expect(formatViolations(await runAxe(container)), where).toBe('');
      }, 20_000);
    }
  }
});
