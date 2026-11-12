import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { SCOPE_RIGHTS, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Right } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { App } from '../App';
import { OPERATOR_NAV } from './navigation';
// Die Rechtematrix des Servers (WP-M1-07) ist die einzige Quelle: Rolle -> Funktionsscopes wird daraus gebildet, nicht von Hand gepflegt.
import matrixCsv from '../../../../backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv?raw';

interface Matrix {
  roles: string[];
  rows: { operationId: string; scope: string; allowed: Set<string> }[];
}

function parseMatrix(text: string): Matrix {
  const lines = text.split('\n').filter((l) => l.trim() !== '' && !l.startsWith('#'));
  const header = (lines[0] ?? '').split(';');
  const roles = header.slice(4);
  const rows = lines.slice(1).map((l) => {
    const c = l.split(';');
    return {
      operationId: c[0] ?? '',
      scope: c[1] ?? '',
      allowed: new Set(roles.filter((_, i) => c[4 + i] === 'J')),
    };
  });
  return { roles, rows };
}

const matrix = parseMatrix(matrixCsv);
const scopesOf = (role: string) =>
  [
    ...new Set(
      matrix.rows.filter((r) => r.allowed.has(role) && r.scope !== '-').map((r) => r.scope),
    ),
  ].sort();

/** Navigationspunkte, die der Operator-Rahmen für diese Rolle zeigen darf: Punkt erscheint nur, wenn ein Scope des Rechts in der Matrix für die Rolle erlaubt ist. */
function expectedLinks(role: string): string[] {
  const scopes = new Set(scopesOf(role));
  const rights = new Set<Right>();
  for (const [scope, list] of Object.entries(SCOPE_RIGHTS))
    if (scopes.has(scope)) list.forEach((r) => rights.add(r));
  return OPERATOR_NAV.filter((e) => !e.requires || rights.has(e.requires)).map((e) => e.label);
}

function renderFor(role: string) {
  const client = {
    call: (id: string) =>
      id === 'getMe'
        ? Promise.resolve(sampleMe({ roles: [role], scopes: scopesOf(role) }))
        : Promise.reject(new Error('nicht benötigt')),
  } as unknown as ApiClient;
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
}

async function links() {
  const nav = await screen.findByRole('navigation', { name: 'Hauptnavigation' });
  return within(nav)
    .getAllByRole('link')
    .map((a) => a.textContent);
}

describe('rollenbezogene Navigation (WP-M1-07): Anzeige folgt den Scopes aus der Rechtematrix, ist aber keine Rechtequelle', () => {
  it('die Matrix hat die sieben Pflichtrollen als Spalten', () => {
    for (const r of [
      'passenger',
      'driver',
      'callcenter',
      'support',
      'dispatcher',
      'planner',
      'tenant_admin',
    ])
      expect(matrix.roles).toContain(r);
  });

  it.each(matrix.roles)('nur_erlaubte_bereiche_sichtbar: Rolle %s', async (role) => {
    renderFor(role);
    expect(await links()).toEqual(expectedLinks(role));
  });

  it('nur_erlaubte_bereiche_sichtbar: feste Erwartungen der Fachlogik (Gegenprobe zur generierten Erwartung)', async () => {
    const cases: [string, string[]][] = [
      ['passenger', ['Start']],
      ['driver', ['Start', 'Eigene Zeit und Abwesenheit']],
      [
        'planner',
        [
          'Start',
          'Eigene Zeit und Abwesenheit',
          'Dienstplan',
          'Flotte und Werkstatt',
          'Fahrer und Qualifikationen',
          'Schichtzuweisung',
          'Gebiete, Stops, Tarife',
        ],
      ],
      [
        'tenant_admin',
        [
          'Start',
          'Checklistenvorlagen',
          'Gebiete, Stops, Tarife',
          'Kooperation und Rollen',
          'Datenschutz, Audit, Support',
        ],
      ],
      [
        'finance',
        ['Start', 'Finanzen', 'Belege prüfen und freigeben', 'Eigene Zeit und Abwesenheit'],
      ],
      [
        'staff_planning',
        [
          'Start',
          'Personal',
          'Eigene Zeit und Abwesenheit',
          'Dienstplan',
          'Flotte und Werkstatt',
          'Fahrer und Qualifikationen',
          'Schichtzuweisung',
        ],
      ],
      [
        'workshop',
        [
          'Start',
          'Eigene Zeit und Abwesenheit',
          'Flotte und Werkstatt',
          'Checklistenvorlagen',
          'Schadenakten',
        ],
      ],
    ];
    for (const [role, expected] of cases) {
      const view = renderFor(role);
      expect(await links()).toEqual(expected);
      view.unmount();
    }
  });

  it('der Administrator sieht weder Finanzen noch Personal noch Callcenter (keine PII-/HR-/Finanzrechte, Kap. 01 §1.7)', async () => {
    renderFor('tenant_admin');
    const all = await links();
    expect(all).not.toContain('Finanzen');
    expect(all).not.toContain('Personal');
    expect(all).not.toContain('Callcenter');
  });

  it('Anzeige ersetzt keine Prüfung: Direktaufruf eines nicht erlaubten Bereichs zeigt den Rechtezustand', async () => {
    const client = {
      call: (id: string) =>
        id === 'getMe'
          ? Promise.resolve(sampleMe({ roles: ['planner'], scopes: scopesOf('planner') }))
          : Promise.reject(new Error('x')),
    } as unknown as ApiClient;
    render(
      <MemoryRouter initialEntries={['/admin/kooperation']}>
        <SessionProvider client={client}>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    );
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
  });
});
