import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  CostCenter,
  Depot,
  OperationId,
  Region,
  Service,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const id = (n: number) => `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`;
const NORD: Region = { id: id(1), name: 'Region Nord', revision: 1 };
const SUED: Region = { id: id(2), name: 'Region Süd', revision: 1 };
const service = (n: number, name: string, region: string | null): Service => ({
  id: id(n),
  operator_id: id(90),
  name,
  timezone: 'Europe/Berlin',
  region_id: region,
  config_revision_id: id(n + 50),
  revision: 1,
});
const DEPOT: Depot = { id: id(10), region_id: NORD.id, name: 'Hof Mitte', revision: 1 };
const KST: CostCenter = {
  id: id(20),
  code: 'KST-1',
  name: 'Rufbus',
  legal_entity_id: id(91),
  region_id: null,
  service_id: id(3),
  revision: 1,
};

type Call = { id: OperationId; args: Record<string, unknown> };
const page = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-11-02T08:00:00Z' });

function setup(
  data: { regions: Region[]; services: Service[] } | ApiError,
  roles = ['tenant_admin'],
  others: Partial<Record<OperationId, () => unknown>> = {},
) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((op: OperationId, args: Record<string, unknown>) => {
      calls.push({ id: op, args });
      if (op === 'getMe')
        return Promise.resolve(
          sampleMe({
            roles,
            scopes: roles.includes('tenant_admin')
              ? [
                  'me:read',
                  'config:write',
                  'organization:read',
                  'organization:write',
                  'services:read',
                ]
              : ['me:read'],
          }),
        );
      if (data instanceof ApiError && op.startsWith('list')) return Promise.reject(data);
      if (!(data instanceof ApiError)) {
        if (op === 'listRegions') return Promise.resolve(page(data.regions));
        if (op === 'listServices') return Promise.resolve(page(data.services));
        if (op === 'listDepots') return Promise.resolve(page(data.regions.length ? [DEPOT] : []));
        if (op === 'listCostCenters')
          return Promise.resolve(page(data.services.length ? [KST] : []));
      }
      const other = others[op];
      return other ? Promise.resolve(other()) : Promise.reject(new ApiError(404));
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/admin/struktur']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

describe('UI-17 Basis: Strukturbaum Mandant, Region, Dienst', () => {
  it('strukturbaum_mandant_region_dienst_zeigt_leer_fehler_und_rechtezustand: Baum mit zwei Gebieten und Kontextzeile', async () => {
    const { container, calls } = setup({
      regions: [NORD, SUED],
      services: [service(3, 'Rufbus Nord', NORD.id), service(4, 'Rufbus Süd', SUED.id)],
    });
    const tree = await screen.findByRole('navigation', { name: 'Strukturbaum' });
    expect(within(tree).getByRole('button', { name: 'Region Region Nord' })).toBeVisible();
    expect(within(tree).getByRole('button', { name: 'Dienst Rufbus Süd' })).toBeVisible();
    expect(within(tree).getByText('Betriebshof Hof Mitte')).toBeVisible();
    expect(within(tree).getByText(/Kostenstelle KST-1/)).toBeVisible();
    // Auswahl setzt die Kontextzeile „Mandant/Region/Dienst“
    fireEvent.click(within(tree).getByRole('button', { name: 'Dienst Rufbus Nord' }));
    expect(screen.getByLabelText('Mandant, Region, Dienst')).toHaveTextContent(
      /\/ Region Nord \/ Rufbus Nord$/,
    );
    fireEvent.click(within(tree).getByRole('button', { name: 'Dienst Rufbus Süd' }));
    expect(screen.getByLabelText('Mandant, Region, Dienst')).toHaveTextContent(
      /\/ Region Süd \/ Rufbus Süd$/,
    );
    expect(JSON.stringify(calls.map((c) => c.args))).not.toMatch(/tenant/i);
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('leerer Zustand mit Hinweis auf die erste Region', async () => {
    setup({ regions: [], services: [] });
    expect(
      await screen.findByText('Noch keine Regionen oder Dienste in diesem Mandanten'),
    ).toBeVisible();
    expect(screen.queryByRole('navigation', { name: 'Strukturbaum' })).toBeNull();
  });

  it('Fehlerzustand mit erneutem Laden', async () => {
    setup(new ApiError(503));
    const retry = await screen.findByRole('button', { name: 'Erneut versuchen' });
    expect(screen.queryByRole('navigation', { name: 'Strukturbaum' })).toBeNull();
    expect(retry).toBeVisible();
  });

  it('rechtezustand: 403 der Liste und fehlendes Recht zeigen keine Daten', async () => {
    setup(new ApiError(403));
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(screen.queryByRole('navigation', { name: 'Strukturbaum' })).toBeNull();
  });

  it('Direktaufruf ohne Recht lädt nichts', async () => {
    const { calls } = setup({ regions: [NORD], services: [] }, ['passenger']);
    await waitFor(() =>
      expect(screen.getByTestId('state-view')).toHaveAttribute('data-kind', 'forbidden'),
    );
    expect(calls.some((c) => c.id === 'listRegions')).toBe(false);
  });

  it('Region anlegen sendet nur den Namen', async () => {
    const { calls } = setup({ regions: [], services: [] }, ['tenant_admin'], {
      createRegion: () => NORD,
    });
    fireEvent.change(await screen.findByLabelText('Name der Region'), {
      target: { value: ' Region Nord ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createRegion')).toBe(true));
    expect(calls.find((c) => c.id === 'createRegion')?.args).toEqual({
      body: { name: 'Region Nord' },
    });
    expect(await screen.findByRole('status')).toHaveTextContent('wurde angelegt');
  });
});
