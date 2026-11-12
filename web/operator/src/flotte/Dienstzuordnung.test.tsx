import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  CapacityConfiguration,
  OperationId,
  Service,
  VehicleMaster,
  VehicleServiceAssignment,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { App } from '../App';
import { assignmentState } from './Dienstzuordnung';

const VEHICLE: VehicleMaster = {
  id: '00000000-0000-4000-8000-0000000000a1',
  vehicle_label: 'KA-100',
  vehicle_type: 'PKW',
  operator_organization_id: null,
  capacity: { seats: 6, wheelchair: 0 },
  equipment: [],
  active: true,
  dispatch_blocked: false,
  dispatchable: true,
  safety_revision: 1,
  has_capacity_layout: false,
  revision: 1,
  created_at: '2026-11-01T08:00:00Z',
  updated_at: '2026-11-01T08:00:00Z',
};
const CAPACITY: CapacityConfiguration = {
  vehicle_id: VEHICLE.id,
  layout: null,
  effective: { code: 'BASE', seats: 6, wheelchair: 0, equipment: [] },
  vectors: [{ code: 'BASE', seats: 6, wheelchair: 0, equipment: [] }],
  revision: 1,
};
const NORD: Service = {
  id: '00000000-0000-4000-8000-0000000000d1',
  operator_id: '00000000-0000-4000-8000-0000000000f1',
  name: 'Rufbus Nord',
  timezone: 'Europe/Berlin',
  config_revision_id: '00000000-0000-4000-8000-0000000000c1',
  revision: 1,
};
const SUED: Service = { ...NORD, id: '00000000-0000-4000-8000-0000000000d2', name: 'Rufbus Süd' };

const assignment = (over: Partial<VehicleServiceAssignment>): VehicleServiceAssignment => ({
  id: '00000000-0000-4000-8000-0000000000b1',
  vehicle_id: VEHICLE.id,
  service_id: NORD.id,
  service_area_id: null,
  valid_from: '2026-01-01T00:00:00Z',
  valid_to: null,
  revision: 1,
  created_at: '2026-01-01T00:00:00Z',
  committed_rides_after_end: [],
  ...over,
});

type Call = { id: OperationId; args: Record<string, unknown> };
type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;
const page = <T,>(items: T[]) => ({ items, next_cursor: null, as_of: '2026-11-11T08:00:00Z' });

function setup(handler: Handler, scopes = ['me:read', 'fleet:read', 'fleet:write']) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      if (id === 'listOrganizations') return Promise.resolve(page([]));
      if (id === 'listVehicleMasters') return Promise.resolve(page([VEHICLE]));
      if (id === 'getVehicleCapacity') return Promise.resolve(CAPACITY);
      if (id === 'listServices') return Promise.resolve(page([NORD, SUED]));
      if (id === 'listServiceAreas') return Promise.resolve(page([]));
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

const open = async () => {
  fireEvent.click(await screen.findByRole('button', { name: /(Bearbeiten|Ansehen) KA-100/ }));
  return screen.findByRole('heading', { name: /Dienstzuordnungen KA-100/ });
};

describe('UI-11 Flotte: Dienstzuordnung des Fahrzeugs (WP-M3-06)', () => {
  it('Zustand einer Zuordnung: geplant, aktiv, beendet (Ende ausgeschlossen)', () => {
    const now = Date.parse('2026-06-01T00:00:00Z');
    expect(assignmentState(assignment({ valid_from: '2026-06-01T00:00:01Z' }), now)).toBe(
      'geplant',
    );
    expect(assignmentState(assignment({}), now)).toBe('aktiv');
    expect(assignmentState(assignment({ valid_to: '2026-06-01T00:00:01Z' }), now)).toBe('aktiv');
    expect(assignmentState(assignment({ valid_to: '2026-06-01T00:00:00Z' }), now)).toBe('beendet');
  });

  it('zeigt den Leerzustand und ordnet einem Dienst zu', async () => {
    const { calls } = setup((id) => {
      if (id === 'listVehicleServiceAssignments') return page([]);
      if (id === 'createVehicleServiceAssignment') return assignment({});
      return undefined;
    });
    await open();
    expect(await screen.findByText('Dieses Fahrzeug ist keinem Dienst zugeordnet')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Dienst'), { target: { value: NORD.id } });
    fireEvent.change(screen.getByLabelText('Gültig ab'), { target: { value: '2026-12-01T08:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Zuordnen' }));
    await waitFor(() =>
      expect(calls.some((c) => c.id === 'createVehicleServiceAssignment')).toBe(true),
    );
    const create = calls.find((c) => c.id === 'createVehicleServiceAssignment');
    expect(create?.args).toMatchObject({ path: { vehicle_id: VEHICLE.id } });
    expect(create?.args['body']).toMatchObject({
      service_id: NORD.id,
      service_area_id: null,
      valid_to: null,
    });
    expect(String((create?.args['body'] as { valid_from: string }).valid_from)).toMatch(
      /^2026-12-0\dT\d\d:00:00\.000Z$/,
    );
    expect(await screen.findByText('Das Fahrzeug wurde dem Dienst zugeordnet.')).toBeVisible();
  });

  it('listet mehrere Zuordnungen, beendet mit der gelesenen Revision und zeigt den Hinweis auf zugesagte Fahrten', async () => {
    const ride = {
      booking_id: '00000000-0000-4000-8000-0000000000e1',
      state: 'CONFIRMED',
      pickup_earliest: '2030-01-01T08:00:00Z',
      pickup_latest: '2030-01-01T08:15:00Z',
    };
    const { calls, container } = setup((id, args) => {
      if (id === 'listVehicleServiceAssignments')
        return page([
          assignment({ revision: 3 }),
          assignment({
            id: '00000000-0000-4000-8000-0000000000b2',
            service_id: SUED.id,
            valid_to: '2026-02-01T00:00:00Z',
            revision: 2,
            committed_rides_after_end: [ride],
          }),
        ]);
      if (id === 'endVehicleServiceAssignment')
        return assignment({
          revision: 4,
          valid_to: (args['body'] as { valid_to: string }).valid_to,
          committed_rides_after_end: [ride],
        });
      return undefined;
    });
    await open();
    const table = await screen.findByRole('table', { name: 'Dienstzuordnungen des Fahrzeugs' });
    expect(within(table).getByText('Rufbus Nord')).toBeVisible();
    expect(within(table).getByText('Rufbus Süd')).toBeVisible();
    expect(within(table).getByText(/1 \(bleiben bestehen, Umplanung nötig\)/)).toBeVisible();
    fireEvent.change(within(table).getByLabelText(/Ende für Rufbus Nord/), {
      target: { value: '2040-01-01T00:00' },
    });
    fireEvent.click(within(table).getByRole('button', { name: 'Beenden Rufbus Nord' }));
    await waitFor(() =>
      expect(calls.some((c) => c.id === 'endVehicleServiceAssignment')).toBe(true),
    );
    expect(calls.find((c) => c.id === 'endVehicleServiceAssignment')?.args).toMatchObject({
      path: { vehicle_id: VEHICLE.id, assignment_id: '00000000-0000-4000-8000-0000000000b1' },
      revision: 3,
    });
    expect(
      await screen.findByText(/Hinweis an die Leitstelle: 1 zugesagte Fahrt\(en\)/),
    ).toBeVisible();
    expect(formatViolations(await runAxe(container))).toBe('');
  });

  it('zeigt Feldfehler (422), Konflikt (409) und Fehlerzustand mit Wiederholung', async () => {
    let list = 0;
    setup((id) => {
      if (id === 'listVehicleServiceAssignments') {
        if (list++ === 0) throw new ApiError(503);
        return page([]);
      }
      if (id === 'createVehicleServiceAssignment') throw new ApiError(409);
      return undefined;
    });
    await open();
    fireEvent.click(await screen.findByRole('button', { name: 'Erneut versuchen' }));
    expect(await screen.findByText('Dieses Fahrzeug ist keinem Dienst zugeordnet')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Dienst'), { target: { value: NORD.id } });
    fireEvent.change(screen.getByLabelText('Gültig ab'), { target: { value: '2026-12-01T08:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Zuordnen' }));
    expect(
      await screen.findByText(
        /Konflikt: Das Fahrzeug ist diesem Dienst im Zeitraum bereits zugeordnet/,
      ),
    ).toBeVisible();
  });

  it('Rechtezustand: ohne Schreibrecht nur Anzeige, keine Aktionen', async () => {
    setup(
      (id) => (id === 'listVehicleServiceAssignments' ? page([assignment({})]) : undefined),
      ['me:read', 'fleet:read'],
    );
    await open();
    expect(
      await screen.findByRole('table', { name: 'Dienstzuordnungen des Fahrzeugs' }),
    ).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Zuordnen' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Beenden/ })).toBeNull();
  });
});
