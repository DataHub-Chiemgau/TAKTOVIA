import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  EffectiveConfig,
  EffectiveParameter,
  OperationId,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const id = (n: number) => `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;
const REGION = { id: id(1), name: 'Region Nord', revision: 1 };
const SERVICE = {
  id: id(2),
  operator_id: id(9),
  name: 'Rufbus Nord',
  timezone: 'Europe/Berlin',
  region_id: id(1),
  config_revision_id: id(3),
  revision: 1,
};

const entry = (
  level: EffectiveParameter['precedence'][number]['level'],
  outcome: EffectiveParameter['precedence'][number]['outcome'],
  value: unknown,
  revision: number | null,
) => ({
  level,
  outcome,
  scope_id: null,
  value,
  revision_id: revision ? id(40 + revision) : null,
  revision,
  violation: null,
  proposal: null,
});

/** Konflikt: Mandant 30, Region 20, Dienst 10 Minuten; der Dienst gilt, ein Entwurf schlägt 8 vor. */
const MAX_WAIT: EffectiveParameter = {
  parameter: 'max_wait_min',
  unit: 'MINUTES',
  status: 'EFFECTIVE',
  value: 10,
  level: 'SERVICE',
  revision_id: id(43),
  revision: 3,
  scope_id: id(2),
  effective_from: '2026-11-01T00:00:00Z',
  effective_to: null,
  schema_version: '1',
  rule: {
    code: 'MOST_SPECIFIC_LEVEL_WINS',
    description: 'Die spezifischste Ebene mit einem wirksamen, zulässigen Wert gilt.',
  },
  precedence: [
    entry('VEHICLE', 'NOT_IN_CONTEXT', null, null),
    {
      ...entry('SERVICE', 'APPLIED', 10, 3),
      proposal: { revision_id: id(50), state: 'DRAFT', value: 8 },
    },
    entry('REGION', 'OVERRIDDEN', 20, 2),
    entry('TENANT', 'OVERRIDDEN', 30, 1),
    entry('GLOBAL', 'NOT_SET', null, null),
  ],
};
const LIMIT: EffectiveParameter = {
  ...MAX_WAIT,
  parameter: 'min_capacity_reserve',
  unit: 'SEATS',
  status: 'NO_VALUE',
  value: null,
  level: null,
  revision_id: null,
  revision: null,
  scope_id: null,
  effective_from: null,
  rule: { code: 'NO_VALUE', description: 'Kein Wert zum Zeitpunkt.' },
  precedence: [],
};
const CONFIG = (items: EffectiveParameter[]): EffectiveConfig => ({
  as_of: '2026-11-02T08:00:00Z',
  values: { max_wait_min: 10 },
  revision_ids: [id(43)],
  schema_version: '1',
  items,
});

type Call = { id: OperationId; args: Record<string, unknown> };
const page = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-11-02T08:00:00Z' });

function setup(effective: EffectiveConfig | ApiError, roles = ['tenant_admin']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((op: OperationId, args: Record<string, unknown>) => {
      calls.push({ id: op, args });
      if (op === 'getMe')
        return Promise.resolve(
          sampleMe({
            roles,
            scopes: roles.includes('tenant_admin')
              ? ['me:read', 'config:read', 'config:write', 'organization:read', 'services:read']
              : ['me:read'],
          }),
        );
      if (op === 'getEffectiveConfig')
        return effective instanceof ApiError
          ? Promise.reject(effective)
          : Promise.resolve(effective);
      if (op === 'listRegions') return Promise.resolve(page([REGION]));
      if (op === 'listServices') return Promise.resolve(page([SERVICE]));
      return Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/konfiguration']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-17 Basis: Konfigurationseditor mit Herkunft und Vorrangregel', () => {
  it('ui17_zeigt_wirksamen_wert_herkunft_und_vorrangregel', async () => {
    const { container, calls } = setup(CONFIG([MAX_WAIT, LIMIT]));
    const table = await screen.findByRole('table', {
      name: 'Wirksame Werte mit Herkunft und Vorrangregel',
    });
    const row = within(table)
      .getByRole('button', { name: 'max_wait_min' })
      .closest('tr') as HTMLElement;
    expect(within(row).getByText('MINUTES')).toBeVisible();
    expect(within(row).getByText('10')).toBeVisible(); // aktueller Wert
    expect(within(row).getByText('8 (DRAFT)')).toBeVisible(); // vorgeschlagener Wert
    expect(within(row).getByText('Dienst, Revision 3')).toBeVisible(); // Herkunft
    expect(within(row).getByText(/Die spezifischste Ebene mit einem wirksamen/)).toBeVisible(); // Vorrangregel
    expect(within(row).getByText(/2026-11-01/)).toBeVisible(); // Wirksamkeitsdatum
    expect(within(table).getByText('kein Wert')).toBeVisible();
    // Konflikt: alle Ebenen mit Wert und Ergebnis
    fireEvent.click(within(row).getByRole('button', { name: 'max_wait_min' }));
    const levels = await screen.findByRole('table', {
      name: 'Vorrang der Ebenen für max_wait_min',
    });
    const rows = within(levels).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.textContent)).toEqual([
      'Fahrzeug–nicht im Kontext–',
      'Dienst10gilt3',
      'Region20überstimmt2',
      'Mandant30überstimmt1',
      'Global (Sicherheitsgrenze)–nicht gesetzt–',
    ]);
    expect(JSON.stringify(calls.map((c) => c.args))).not.toMatch(/tenant/i);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('der Kontext (Region, Dienst) wählt das Gebiet; die Anfrage nennt nur Region und Dienst', async () => {
    const { calls } = setup(CONFIG([MAX_WAIT]));
    await screen.findByRole('table', { name: 'Wirksame Werte mit Herkunft und Vorrangregel' });
    fireEvent.change(await screen.findByLabelText('Region'), { target: { value: REGION.id } });
    fireEvent.change(await screen.findByLabelText('Dienst'), { target: { value: SERVICE.id } });
    await waitFor(() =>
      expect(calls.filter((c) => c.id === 'getEffectiveConfig').at(-1)?.args).toEqual({
        query: { region_id: REGION.id, service_id: SERVICE.id },
      }),
    );
  });

  it('Fehlerzustand mit erneutem Laden', async () => {
    setup(new ApiError(503));
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeVisible();
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('rechtezustand: 403 zeigt keine Daten, Direktaufruf ohne Recht lädt nichts', async () => {
    setup(new ApiError(403));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('table')).toBeNull();
  });

  it('Direktaufruf ohne Recht', async () => {
    const { calls } = setup(CONFIG([MAX_WAIT]), ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(calls.some((c) => c.id === 'getEffectiveConfig')).toBe(false);
  });

  it('leerer Katalog zeigt den leeren Zustand', async () => {
    setup(CONFIG([]));
    expect(await screen.findByText('Keine Parameter im Katalog')).toBeVisible();
  });
});
