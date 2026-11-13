import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  Defect,
  OperationId,
  RepairOrder,
  VehicleMaster,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { runAxe, formatViolations } from '@taktovia/design-system/testing';
import { App } from '../App';

const VEHICLE: VehicleMaster = {
  id: '00000000-0000-4000-8000-0000000000a1',
  vehicle_label: 'KA-100',
  vehicle_type: 'PKW',
  operator_organization_id: null,
  capacity: { seats: 6, wheelchair: 0 },
  equipment: [],
  active: true,
  dispatch_blocked: true,
  dispatchable: false,
  safety_revision: 7,
  has_capacity_layout: false,
  revision: 1,
  created_at: '2026-11-01T08:00:00Z',
  updated_at: '2026-11-01T08:00:00Z',
};

const defect = (over: Partial<Defect> = {}): Defect => ({
  id: '00000000-0000-4000-8000-0000000000d1',
  case_number: 'SCH-2026-000001',
  vehicle_id: VEHICLE.id,
  reported_by: '00000000-0000-4000-8000-0000000000c1',
  reported_at: '2026-11-11T08:00:00Z',
  occurred_at: '2026-11-11T07:30:00Z',
  category_code: 'BRAKES',
  kind: 'MALFUNCTION',
  vehicle_area: 'WHEELS_TIRES',
  observed_effect: 'NOT_ROADWORTHY',
  severity: 'COSMETIC',
  severity_rule_version: 'dmg-klassen-v1',
  suggested_severity: 'SAFETY_CRITICAL',
  status: 'NEW',
  assignee_ref: null,
  blocks_vehicle: false,
  evidence_ids: ['00000000-0000-4000-8000-0000000000e1'],
  evidence_pending: 0,
  content_version: 1,
  description: null,
  revision: 3,
  ...over,
});

const order = (over: Partial<RepairOrder> = {}): RepairOrder => ({
  id: '00000000-0000-4000-8000-0000000000f1',
  defect_id: '00000000-0000-4000-8000-0000000000d1',
  vehicle_id: VEHICLE.id,
  status: 'OPEN',
  workshop_ref: 'Werkstatt Nord',
  planned_at: null,
  cost_reference: 'KST-100',
  created_at: '2026-11-11T09:00:00Z',
  revision: 1,
  ...over,
});

const page = (items: Defect[], next: string | null = null) => ({
  items,
  next_cursor: next,
  as_of: '2026-11-11T10:00:00Z',
});
const ALL = [
  'me:read',
  'defects:triage',
  'defects:read',
  'defects:write',
  'defects:state',
  'fleet:read',
  'repair:read',
  'repair:write',
  'fleet:release',
];

type Call = { id: OperationId; args: Record<string, unknown> };
function setup(
  handler: (id: OperationId, args: Record<string, unknown>) => unknown,
  scopes: string[] = ALL,
) {
  const calls: Call[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['workshop'], scopes }));
      if (id === 'listVehicleMasters')
        return Promise.resolve({
          items: [VEHICLE],
          next_cursor: null,
          as_of: '2026-11-11T10:00:00Z',
        });
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <MemoryRouter initialEntries={['/flotte/schaeden']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { ...view, calls };
}
/** Aufrufe der Operation, die der Test erwartet (schlägt mit klarer Meldung fehl statt mit einem Null-Zugriff). */
function callsOf(calls: Call[], id: OperationId): Call[] {
  const found = calls.filter((c) => c.id === id);
  expect(found.length, `Aufruf ${id}`).toBeGreaterThan(0);
  return found;
}
const lastQuery = (calls: Call[]) =>
  [...calls].reverse().find((c) => c.id === 'listDefects')?.args['query'] as Record<
    string,
    unknown
  >;

describe('UI-11 Schadenliste (WP-M3-49): sechs Filter, Sortierung, Seitenwechsel, Zustände', () => {
  it('übersetzt die sechs Filter und die Sortierung in die Abfrage und lädt Folgeseiten', async () => {
    const { calls } = setup((id, args) => {
      if (id === 'listDefects')
        return (args['query'] as Record<string, unknown>)['cursor']
          ? page([
              defect({
                id: '00000000-0000-4000-8000-0000000000d2',
                case_number: 'SCH-2026-000002',
              }),
            ])
          : page([defect()], 'cursor-2');
      return undefined;
    });
    expect(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ })).toBeVisible();
    expect(screen.getByText(/Stand 2026-11-11 10:00/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: 'Weitere laden' }));
    expect(await screen.findByRole('button', { name: /Öffnen SCH-2026-000002/ })).toBeVisible();
    expect(lastQuery(calls)['cursor']).toBe('cursor-2');

    fireEvent.change(screen.getByLabelText('Fahrzeug (Filter)'), { target: { value: VEHICLE.id } });
    fireEvent.change(screen.getByLabelText('Priorität (Filter)'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Status (Filter)'), { target: { value: 'REVIEWED' } });
    fireEvent.change(screen.getByLabelText('Gemeldet von'), {
      target: { value: '2026-11-01T00:00' },
    });
    fireEvent.change(screen.getByLabelText('Gemeldet bis'), {
      target: { value: '2026-11-30T00:00' },
    });
    fireEvent.change(screen.getByLabelText('Depot (Kennung)'), {
      target: { value: '00000000-0000-4000-8000-0000000000b1' },
    });
    fireEvent.change(screen.getByLabelText('Verantwortlicher (Kennung)'), {
      target: { value: '00000000-0000-4000-8000-0000000000c9' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Filter anwenden' }));
    await waitFor(() => expect(lastQuery(calls)['priority']).toBe(1));
    expect(lastQuery(calls)).toMatchObject({
      vehicle_id: VEHICLE.id,
      priority: 1,
      status: 'REVIEWED',
      depot_id: '00000000-0000-4000-8000-0000000000b1',
      assignee_ref: '00000000-0000-4000-8000-0000000000c9',
      limit: 50,
    });
    expect(String(lastQuery(calls)['reported_from'])).toMatch(/^2026-1[01]-/);
    expect(String(lastQuery(calls)['reported_to'])).toMatch(/^2026-1[12]-/);

    // Sortierung: aufsteigend, absteigend, zurück zum Standard
    fireEvent.click(screen.getByRole('button', { name: /Sortieren nach Priorität/ }));
    await waitFor(() => expect(lastQuery(calls)['sort']).toBe('priority'));
    fireEvent.click(screen.getByRole('button', { name: /Sortieren nach Priorität/ }));
    await waitFor(() => expect(lastQuery(calls)['sort']).toBe('-priority'));
    fireEvent.click(screen.getByRole('button', { name: /Sortieren nach Priorität/ }));
    await waitFor(() => expect(lastQuery(calls)['sort']).toBeUndefined());
    fireEvent.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }));
    await waitFor(() => expect(lastQuery(calls)['priority']).toBeUndefined());
  });

  it('zeigt Leerzustand mit und ohne Filter, Zeitraumfehler und Ladefehler mit Wiederholung', async () => {
    let fail = true;
    const { calls } = setup((id, args) => {
      if (id !== 'listDefects') return undefined;
      if (fail) throw new ApiError(503);
      return (args['query'] as Record<string, unknown>)['priority'] ? page([]) : page([]);
    });
    expect(await screen.findByRole('button', { name: 'Erneut versuchen' })).toBeVisible();
    fail = false;
    fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
    expect(await screen.findByText('Noch keine Schadenakten in diesem Mandanten')).toBeVisible();
    fireEvent.change(screen.getByLabelText('Gemeldet von'), {
      target: { value: '2026-11-20T00:00' },
    });
    fireEvent.change(screen.getByLabelText('Gemeldet bis'), {
      target: { value: '2026-11-10T00:00' },
    });
    const before = calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Filter anwenden' }));
    expect(screen.getByRole('alert')).toHaveTextContent('Zeitraum ist ungültig');
    expect(calls.length).toBe(before);
    fireEvent.change(screen.getByLabelText('Gemeldet bis'), {
      target: { value: '2026-11-30T00:00' },
    });
    fireEvent.change(screen.getByLabelText('Priorität (Filter)'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filter anwenden' }));
    expect(await screen.findByText('Keine Schadenakten für diese Filter')).toBeVisible();
  });

  it('ist ohne Verstoß gegen die Barrierefreiheitsprüfung (axe) bedienbar', async () => {
    const { container } = setup((id) =>
      id === 'listDefects' ? page([defect({ blocks_vehicle: true })]) : undefined,
    );
    expect(await screen.findByText(/sperrt das Fahrzeug/)).toBeVisible();
    const violations = await runAxe(container);
    expect(violations, formatViolations(violations)).toEqual([]);
  });
});

describe('UI-11 Triageansicht (WP-M3-45)', () => {
  it('zeigt Vorschlag und geltende Klasse, speichert mit Revision und weist die Korrektur ohne Grund ab', async () => {
    const triaged = defect({
      severity: 'SAFETY_CRITICAL',
      status: 'REVIEWED',
      blocks_vehicle: true,
      revision: 4,
    });
    const { calls } = setup((id, args) => {
      if (id === 'listDefects') return page([defect()]);
      if (id === 'getDefect') return defect();
      if (id === 'listRepairOrders')
        return { items: [], next_cursor: null, as_of: '2026-11-11T10:00:00Z' };
      if (id === 'triageDefect') {
        const body = args['body'] as Record<string, unknown>;
        if (!body['reason']) throw new ApiError(422);
        return triaged;
      }
      return undefined;
    });
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    const section = await screen.findByRole('region', { name: 'Triage' });
    expect(
      within(section).getByText(
        /Vorgeschlagene Klasse \(Regelmatrix dmg-klassen-v1\): sicherheitskritisch/,
      ),
    ).toBeVisible();
    expect(within(section).getByText(/Geltende Klasse: kosmetisch/)).toBeVisible();
    fireEvent.change(within(section).getByLabelText('Klasse'), {
      target: { value: 'SAFETY_CRITICAL' },
    });
    expect(within(section).getByLabelText(/Begründung \(Pflicht\)/)).toBeVisible();
    fireEvent.click(within(section).getByRole('button', { name: 'Triage speichern' }));
    expect(await within(section).findByText(/Bitte eine Begründung angeben/)).toBeVisible();
    fireEvent.change(within(section).getByLabelText(/Begründung/), {
      target: { value: 'Riss im Bremssattel' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Triage speichern' }));
    expect(
      await screen.findByText(/Das Fahrzeug ist gesperrt \(kritischer Mangel\)/),
    ).toBeVisible();
    const call = callsOf(calls, 'triageDefect').at(-1) as Call;
    expect(call.args['revision']).toBe(3);
    expect(call.args['body']).toMatchObject({
      severity: 'SAFETY_CRITICAL',
      decision: 'REVIEWED',
      reason: 'Riss im Bremssattel',
    });
  });

  it('blendet Reparatur und Freigabe ohne Reparaturrecht aus (nicht vorhanden statt ausgegraut)', async () => {
    setup(
      (id) => (id === 'listDefects' ? page([defect()]) : id === 'getDefect' ? defect() : undefined),
      ['me:read', 'defects:triage', 'defects:read', 'defects:state', 'fleet:read'],
    );
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));
    expect(await screen.findByRole('region', { name: 'Triage' })).toBeVisible();
    expect(screen.queryByText('Reparatur und Wiederfreigabe')).toBeNull();
  });
});

describe('UI-11 Reparaturauftrag und Wiederfreigabe (WP-M3-51)', () => {
  const open = async () =>
    fireEvent.click(await screen.findByRole('button', { name: /Öffnen SCH-2026-000001/ }));

  it('legt den Auftrag an, meldet den Nachweis und erteilt die Freigabe mit dem Sicherheitsstand als If-Match', async () => {
    let orders: RepairOrder[] = [];
    const { calls } = setup((id, args) => {
      if (id === 'listDefects')
        return page([
          defect({ status: 'IN_PROGRESS', severity: 'SAFETY_CRITICAL', blocks_vehicle: true }),
        ]);
      if (id === 'getDefect')
        return defect({ status: 'IN_PROGRESS', severity: 'SAFETY_CRITICAL', blocks_vehicle: true });
      if (id === 'listRepairOrders')
        return { items: orders, next_cursor: null, as_of: '2026-11-11T10:00:00Z' };
      if (id === 'createRepairOrder') {
        orders = [order()];
        return orders[0];
      }
      if (id === 'transitionRepairOrder') {
        const to = (args['body'] as { to: string }).to;
        orders = [
          to === 'IN_PROGRESS'
            ? order({ status: 'IN_PROGRESS', revision: 2 })
            : order({
                status: 'PROOF_RECORDED',
                proof_evidence_id: '00000000-0000-4000-8000-0000000000e1',
                revision: 3,
              }),
        ];
        return orders[0];
      }
      if (id === 'createVehicleRelease') return { decision: 'PENDING_SECOND_APPROVAL' };
      return undefined;
    });
    await open();
    const section = await screen.findByRole('region', { name: 'Reparatur und Wiederfreigabe' });
    expect(
      await within(section).findByText('Zu dieser Akte besteht noch kein Reparaturauftrag.'),
    ).toBeVisible();
    fireEvent.change(within(section).getByLabelText(/Kostenbezug/), {
      target: { value: 'KST-100' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Reparaturauftrag anlegen' }));
    expect(await within(section).findByText(/Reparaturauftrag angelegt/)).toBeVisible();
    expect(callsOf(calls, 'createRepairOrder')[0]?.args['body']).toMatchObject({
      defect_id: defect().id,
      cost_reference: 'KST-100',
    });

    fireEvent.click(
      await within(section).findByRole('button', { name: 'Auftrag in Arbeit nehmen' }),
    );
    expect(await within(section).findByText(/Reparaturauftrag in Arbeit/)).toBeVisible();
    fireEvent.change(within(section).getByLabelText('Nachweis (Foto der Akte)'), {
      target: { value: '00000000-0000-4000-8000-0000000000e1' },
    });
    fireEvent.click(
      within(section).getByRole('button', { name: 'Reparatur abgeschlossen melden' }),
    );
    expect(await within(section).findByText(/Nachweis vorhanden/)).toBeVisible();
    expect(callsOf(calls, 'transitionRepairOrder').at(-1)?.args['body']).toMatchObject({
      to: 'PROOF_RECORDED',
      proof_evidence_id: '00000000-0000-4000-8000-0000000000e1',
    });

    fireEvent.change(within(section).getByLabelText('Folgeprüfung (Kennung)'), {
      target: { value: '00000000-0000-4000-8000-0000000000a9' },
    });
    fireEvent.change(within(section).getByLabelText('Begründung der Freigabe'), {
      target: { value: 'Bremsanlage erneuert' },
    });
    fireEvent.click(within(section).getByRole('button', { name: 'Freigabe erteilen' }));
    expect(await screen.findByText(/Die erste Freigabe ist erfasst/)).toBeVisible();
    const release = callsOf(calls, 'createVehicleRelease')[0] as Call;
    expect(release.args['revision']).toBe(7);
    expect(release.args['body']).toMatchObject({
      follow_up_inspection_id: '00000000-0000-4000-8000-0000000000a9',
      reason: 'Bremsanlage erneuert',
    });
  });

  it('zeigt „Freigaberolle fehlt“ ohne Freigaberecht und blendet die Pflegeaktionen ohne Schreibrecht aus', async () => {
    setup(
      (id) => {
        if (id === 'listDefects') return page([defect({ status: 'REPAIR' })]);
        if (id === 'getDefect') return defect({ status: 'REPAIR' });
        if (id === 'listRepairOrders')
          return {
            items: [order({ status: 'PROOF_RECORDED' })],
            next_cursor: null,
            as_of: '2026-11-11T10:00:00Z',
          };
        return undefined;
      },
      ['me:read', 'defects:triage', 'defects:read', 'defects:state', 'fleet:read', 'repair:read'],
    );
    await open();
    const section = await screen.findByRole('region', { name: 'Reparatur und Wiederfreigabe' });
    expect(await within(section).findByText(/Freigaberolle fehlt/)).toBeVisible();
    expect(within(section).queryByRole('button', { name: 'Freigabe erteilen' })).toBeNull();
    expect(within(section).queryByRole('button', { name: 'Reparaturauftrag anlegen' })).toBeNull();
  });

  it('übersetzt abgewiesene Freigaben verständlich (409: Fahrzeug bleibt gesperrt, 412: Stand geändert)', async () => {
    let status = 409;
    setup((id) => {
      if (id === 'listDefects') return page([defect({ status: 'REPAIR' })]);
      if (id === 'getDefect') return defect({ status: 'REPAIR' });
      if (id === 'listRepairOrders')
        return {
          items: [order({ status: 'PROOF_RECORDED' })],
          next_cursor: null,
          as_of: '2026-11-11T10:00:00Z',
        };
      if (id === 'createVehicleRelease') throw new ApiError(status);
      return undefined;
    });
    await open();
    const section = await screen.findByRole('region', { name: 'Reparatur und Wiederfreigabe' });
    fireEvent.click(await within(section).findByRole('button', { name: 'Freigabe erteilen' }));
    expect(await screen.findByText(/das Fahrzeug bleibt gesperrt/)).toBeVisible();
    status = 412;
    fireEvent.click(within(section).getByRole('button', { name: 'Freigabe erteilen' }));
    expect(await screen.findByText(/Der Stand hat sich geändert/)).toBeVisible();
  });
});
