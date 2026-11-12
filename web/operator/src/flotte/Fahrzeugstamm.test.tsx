import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  CapacityConfiguration,
  OperationId,
  Organization,
  VehicleMaster,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';

const ORG: Organization = {
  id: '00000000-0000-4000-8000-0000000000f1',
  legal_entity_id: '00000000-0000-4000-8000-0000000000e1',
  name: 'Betreiber Nord',
  federation_ref: 'nord',
  roles: ['TRANSPORT_COMPANY'],
  roles_valid_from: '2026-01-01T00:00:00Z',
  roles_valid_to: null,
  revision: 1,
};

const VEHICLE: VehicleMaster = {
  id: '00000000-0000-4000-8000-0000000000a1',
  vehicle_label: 'KA-100',
  vehicle_type: 'PKW',
  operator_organization_id: ORG.id,
  capacity: { seats: 6, wheelchair: 0 },
  equipment: ['RAMP'],
  active: true,
  dispatch_blocked: true,
  dispatchable: false,
  safety_revision: 1,
  has_capacity_layout: false,
  revision: 1,
  created_at: '2026-11-01T08:00:00Z',
  updated_at: '2026-11-01T08:00:00Z',
};

const CAPACITY: CapacityConfiguration = {
  vehicle_id: VEHICLE.id,
  layout: null,
  effective: { code: 'BASE', seats: 6, wheelchair: 0, equipment: ['RAMP'] },
  vectors: [{ code: 'BASE', seats: 6, wheelchair: 0, equipment: ['RAMP'] }],
  revision: 1,
};

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'fleet:read', 'fleet:write']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      if (id === 'listOrganizations')
        return Promise.resolve({ items: [ORG], next_cursor: null, as_of: '2026-11-11T08:00:00Z' });
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}

const page = (items: VehicleMaster[]) => ({
  items,
  next_cursor: null,
  as_of: '2026-11-11T08:00:00Z',
});

describe('UI-11 Flotte: Fahrzeugstamm und Kapazitätskonfiguration', () => {
  it('zeigt den Leerzustand, legt ein Fahrzeug an und zeigt Feldfehler der API (422)', async () => {
    const { calls } = setup((id) => {
      if (id === 'listVehicleMasters') return page([]);
      if (id === 'createVehicleMaster') {
        throw new ApiError(422, {
          type: 'x',
          title: 'x',
          status: 422,
          code: 'VALIDATION_FAILED',
          correlation_id: 'c',
          violations: [{ pointer: '/capacity/seats', code: 'BELOW_MINIMUM', message: 'm' }],
        });
      }
      return undefined;
    });
    expect(await screen.findByText('Noch keine Fahrzeuge in diesem Mandanten')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Kennung'), { target: { value: 'KA-200' } });
    fireEvent.change(screen.getByLabelText('Betreiber'), { target: { value: ORG.id } });
    fireEvent.change(screen.getByLabelText('Sitzplätze'), { target: { value: '-1' } });
    fireEvent.change(screen.getByLabelText('Rollstuhlplätze'), { target: { value: '1' } });
    fireEvent.click(screen.getByLabelText(/Rampe/));
    fireEvent.click(screen.getByRole('button', { name: 'Anlegen' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'createVehicleMaster')).toBe(true));
    expect(calls.find((c) => c.id === 'createVehicleMaster')?.args['body']).toEqual({
      vehicle_label: 'KA-200',
      vehicle_type: 'PKW',
      operator_organization_id: ORG.id,
      capacity: { seats: -1, wheelchair: 1 },
      equipment: ['RAMP'],
      active: true,
    });
    // Feldfehler am Feld, nichts als erfolgreich gemeldet
    expect(await screen.findAllByText(/Der Wert ist zu klein/)).not.toHaveLength(0);
    expect(screen.queryByText(/wurde angelegt/)).toBeNull();
  });

  it('listet Fahrzeuge, ändert mit der gelesenen Revision und deaktiviert', async () => {
    const { calls, container } = setup((id, args) => {
      if (id === 'listVehicleMasters') return page([VEHICLE]);
      if (id === 'getVehicleCapacity') return CAPACITY;
      if (id === 'updateVehicleMaster')
        return { ...VEHICLE, ...(args['body'] as object), revision: 2 };
      return undefined;
    });
    const table = await screen.findByRole('table', { name: 'Fahrzeuge des aktiven Mandanten' });
    expect(within(table).getByText('KA-100')).toBeVisible();
    expect(within(table).getByText('gesperrt (keine Einsatzfreigabe)')).toBeVisible();
    fireEvent.click(within(table).getByRole('button', { name: /Bearbeiten KA-100/ }));
    expect(await screen.findByRole('heading', { name: 'Fahrzeug KA-100 ändern' })).toBeVisible();
    fireEvent.click(screen.getByLabelText(/Aktiv \(ein deaktiviertes/));
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
    await waitFor(() => expect(calls.some((c) => c.id === 'updateVehicleMaster')).toBe(true));
    const update = calls.find((c) => c.id === 'updateVehicleMaster');
    expect(update?.args).toMatchObject({ path: { vehicle_id: VEHICLE.id }, revision: 1 });
    expect(update?.args['body']).toMatchObject({ vehicle_label: 'KA-100', active: false });
    expect(await screen.findByRole('status')).toHaveTextContent('deaktiviert');
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt Konflikt (409) und veraltete Revision (412) verständlich', async () => {
    let n = 0;
    setup((id) => {
      if (id === 'listVehicleMasters') return page([VEHICLE]);
      if (id === 'getVehicleCapacity') return CAPACITY;
      if (id === 'updateVehicleMaster') throw new ApiError(n++ === 0 ? 409 : 412);
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: /Bearbeiten KA-100/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'Speichern' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Konflikt');
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('zwischenzeitlich geändert'),
    );
  });

  it('Kapazitätseditor: Tabellenalternative, Speichern mit Revision, Widerspruch als Feldfehler', async () => {
    let first = true;
    const { calls } = setup((id, args) => {
      if (id === 'listVehicleMasters') return page([VEHICLE]);
      if (id === 'getVehicleMaster') return { ...VEHICLE, revision: 2, has_capacity_layout: true };
      if (id === 'getVehicleCapacity') return CAPACITY;
      if (id === 'updateVehicleCapacity') {
        if (first) {
          first = false;
          throw new ApiError(422, {
            type: 'x',
            title: 'x',
            status: 422,
            code: 'VALIDATION_FAILED',
            correlation_id: 'c',
            violations: [
              {
                pointer: '/spaces/seats_replaced_per_wheelchair',
                code: 'ABOVE_MAXIMUM',
                message: 'm',
              },
            ],
          });
        }
        expect(args['revision']).toBe(1);
        return {
          ...CAPACITY,
          revision: 2,
          layout: {
            schema_version: '1.0',
            spaces: { seat_places: 6, wheelchair_spaces: 2, seats_replaced_per_wheelchair: 2 },
            equipment: ['RAMP'],
            combinations: [
              { code: 'STANDARD', wheelchair_places: 0 },
              { code: 'ONE_WC', wheelchair_places: 1 },
            ],
            active_combination: 'ONE_WC',
            restrictions: [],
          },
          effective: { code: 'ONE_WC', seats: 4, wheelchair: 1, equipment: ['RAMP'] },
          vectors: [
            { code: 'STANDARD', seats: 6, wheelchair: 0, equipment: ['RAMP'] },
            { code: 'ONE_WC', seats: 4, wheelchair: 1, equipment: ['RAMP'] },
          ],
        };
      }
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: /Bearbeiten KA-100/ }));
    const vectors = await screen.findByRole('table', { name: /Tabellenalternative/ });
    expect(within(vectors).getByText('BASE')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Barrierefreie Stellplätze'), {
      target: { value: '2' },
    });
    fireEvent.change(screen.getByLabelText(/Ersetzte Sitzplätze/), { target: { value: '4' } });
    fireEvent.change(screen.getByLabelText(/Kombinationen/), {
      target: { value: 'STANDARD:0, ONE_WC:1' },
    });
    fireEvent.change(screen.getByLabelText('Aktive Kombination'), { target: { value: 'ONE_WC' } });
    fireEvent.click(screen.getByRole('button', { name: 'Konfiguration speichern' }));
    await waitFor(() =>
      expect(calls.filter((c) => c.id === 'updateVehicleCapacity')).toHaveLength(1),
    );
    expect(await screen.findAllByText(/Der Wert ist zu groß/)).not.toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/Ersetzte Sitzplätze/), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Konfiguration speichern' }));
    await waitFor(() =>
      expect(calls.filter((c) => c.id === 'updateVehicleCapacity')).toHaveLength(2),
    );
    const body = calls.filter((c) => c.id === 'updateVehicleCapacity')[1]?.args['body'];
    expect(body).toMatchObject({
      spaces: { seat_places: 6, wheelchair_spaces: 2, seats_replaced_per_wheelchair: 2 },
      combinations: [
        { code: 'STANDARD', wheelchair_places: 0 },
        { code: 'ONE_WC', wheelchair_places: 1 },
      ],
      active_combination: 'ONE_WC',
    });
    expect(await screen.findByText(/bestehende Reservierungen bleiben unverändert/)).toBeVisible();
    const updated = await screen.findByRole('table', { name: /Tabellenalternative/ });
    expect(within(updated).getByText('ONE_WC')).toBeVisible();
  });

  it('Fehlerzustand mit Wiederholung', async () => {
    let n = 0;
    setup((id) => {
      if (id === 'listVehicleMasters') {
        if (n++ === 0) throw new ApiError(503);
        return page([VEHICLE]);
      }
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Erneut versuchen' }));
    expect(await screen.findByText('KA-100')).toBeVisible();
  });

  it('ohne Recht: Bereich nicht in der Navigation und ohne Daten (Rechtezustand)', async () => {
    const { calls } = setup(() => page([VEHICLE]), ['me:read']);
    expect(await screen.findByText(/Keine Berechtigung/)).toBeVisible();
    expect(screen.queryByRole('link', { name: 'Flotte und Werkstatt' })).toBeNull();
    expect(calls.some((c) => c.id === 'listVehicleMasters')).toBe(false);
  });

  it('nur Lesen: Liste ohne Formular', async () => {
    setup(
      (id) => (id === 'listVehicleMasters' ? page([VEHICLE]) : CAPACITY),
      ['me:read', 'fleet:read'],
    );
    expect(await screen.findByText('KA-100')).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Anlegen' })).toBeNull();
    expect(screen.getByRole('link', { name: 'Flotte und Werkstatt' })).toBeVisible();
  });
});
