import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '@taktovia/api-client';
import type { ApiClient, OperationId, OperatorVehicle, VehiclePlan } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import type { LiveMessage } from '../live/LiveChannel';
import { VehiclePlanPanel } from './VehiclePlanPanel';

const VA = '00000000-0000-4000-8000-00000000000a';
const VB = '00000000-0000-4000-8000-00000000000b';
const RUN_A = '00000000-0000-4000-8000-0000000000a1';
const RUN_B = '00000000-0000-4000-8000-0000000000b1';

const stop = (
  n: number,
  name: string,
  over: Partial<VehiclePlan['stops'][number]> = {},
): VehiclePlan['stops'][number] => ({
  route_stop_id: `00000000-0000-4000-8000-0000000000${String(n).padStart(2, '0')}`,
  sequence: n,
  stop_name: name,
  kind: 'PICKUP',
  scheduled_at: '2026-12-01T09:00:00Z',
  forecast: {
    earliest: '2026-12-01T09:01:00Z',
    latest: '2026-12-01T09:01:00Z',
    basis: 'PLAN_TIME',
  },
  promise_windows: [],
  boarding_booking_ids: [],
  alighting_booking_ids: [],
  ...over,
});

const planOf = (
  vehicle: string,
  run: string | null,
  over: Partial<VehiclePlan> = {},
): VehiclePlan => ({
  vehicle_id: vehicle,
  run_id: run,
  run_state: run ? 'ACTIVE' : null,
  plan_revision: run ? 3 : null,
  as_of: '2026-12-01T08:55:00Z',
  stops: run
    ? [
        stop(1, `Markt ${vehicle.slice(-1)}`, {
          promise_windows: [
            {
              booking_id: '00000000-0000-4000-8000-0000000000c1',
              kind: 'PICKUP',
              earliest: '2026-12-01T08:58:00Z',
              latest: '2026-12-01T09:13:00Z',
            },
          ],
          boarding_booking_ids: ['00000000-0000-4000-8000-0000000000c1'],
        }),
        stop(2, `Bahnhof ${vehicle.slice(-1)}`, { forecast: null, kind: 'DROPOFF' }),
      ]
    : [],
  capacity: run ? { seats: 4, wheelchair: 1 } : null,
  occupancy: { seats: run ? 2 : 0, wheelchair: 0 },
  review_required: [],
  forecast_source: run ? 'PLAN_TIME' : 'NONE',
  ...over,
});

const vehicle = (id: string, plan: VehiclePlan): OperatorVehicle =>
  ({
    vehicle_id: id,
    vehicle_label: id === VA ? 'KA-A' : 'KA-B',
    plan,
  }) as unknown as OperatorVehicle;

type Handler = (id: OperationId, args: Record<string, unknown>) => Promise<unknown>;

function client(handler: Handler): ApiClient {
  return {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) =>
      id === 'getMe'
        ? Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes: ['me:read', 'operator:read'] }))
        : handler(id, args),
    ),
  } as unknown as ApiClient;
}

function Harness({ initial, message }: { initial: string; message?: LiveMessage }) {
  const [id, setId] = useState(initial);
  return (
    <>
      <button type="button" onClick={() => setId(VA)}>
        Fahrzeug A
      </button>
      <button type="button" onClick={() => setId(VB)}>
        Fahrzeug B
      </button>
      <VehiclePlanPanel
        vehicleId={id}
        vehicleLabel={id === VA ? 'KA-A' : 'KA-B'}
        liveMessage={message}
      />
    </>
  );
}

const mount = (c: ApiClient, initial = VA, message?: LiveMessage) =>
  render(
    <SessionProvider client={c}>
      <Harness initial={initial} {...(message ? { message } : {})} />
    </SessionProvider>,
  );

const message = (aggregate: string, revision: number): LiveMessage => ({
  event_id: `e-${aggregate}-${revision}`,
  cursor: String(revision),
  type: 'run.revised',
  topic: 'dispatch',
  aggregate_id: aggregate,
  aggregate_revision: revision,
  service_id: null,
  shift_id: null,
  status: {},
  occurred_at: '2026-12-01T09:00:00Z',
});

describe('UI-07 Fahrzeugplan (WP-M3-38, LH-6.7-OPS-003)', () => {
  it('umschalten_zeigt_nur_daten_des_gewaehlten_fahrzeugs', async () => {
    const plans: Record<string, VehiclePlan> = {
      [VA]: planOf(VA, RUN_A),
      [VB]: planOf(VB, RUN_B, { occupancy: { seats: 0, wheelchair: 1 }, plan_revision: 7 }),
    };
    const c = client((id, args) => {
      const vid = (args.path as { vehicle_id: string }).vehicle_id;
      return Promise.resolve(vehicle(vid, plans[vid] ?? planOf(vid, null)));
    });
    mount(c);
    const a = await screen.findByRole('region', { name: 'Fahrzeugplan KA-A' });
    expect(within(a).getByText('Markt a')).toBeTruthy();
    expect(within(a).getByText(/Planrevision 3/)).toBeTruthy();
    expect(within(a).getByLabelText('Besetzung').textContent).toContain('Sitze 2 von 4');
    // Sollzeit, Prognose und Zusagefenster sind getrennte Spalten; die Prognose ist als Planzeit aus dem Routing gekennzeichnet
    expect(within(a).getByRole('columnheader', { name: 'Sollzeit' })).toBeTruthy();
    expect(within(a).getByRole('columnheader', { name: 'Prognose' })).toBeTruthy();
    expect(within(a).getByRole('columnheader', { name: 'Zusagefenster' })).toBeTruthy();
    expect(within(a).getByText(/Planzeit aus dem Routing\)/)).toBeTruthy();
    expect(within(a).getByText(/Abholung .*–/)).toBeTruthy();
    // fehlende Prognose wird ausgewiesen, nicht erfunden
    expect(within(a).getByText('keine Prognose')).toBeTruthy();
    // Umschalten: nur die Daten von B, keine Reste von A
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug B' }));
    const b = await screen.findByRole('region', { name: 'Fahrzeugplan KA-B' });
    await waitFor(() => expect(within(b).getByText('Markt b')).toBeTruthy());
    expect(within(b).getByText(/Planrevision 7/)).toBeTruthy();
    expect(within(b).getByLabelText('Besetzung').textContent).toContain('Rollstuhlplätze 1 von 1');
    expect(screen.queryByText('Markt a')).toBeNull();
    expect(screen.queryByText('Bahnhof a')).toBeNull();
    expect(document.body.textContent).not.toContain('Sitze 2 von 4');
    // Leerzustand und Barrierefreiheit
    const violations = await runAxe(document.body);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('leerzustand_fahrzeug_ohne_fahrt_und_klaerfaelle', async () => {
    const c = client((id, args) => {
      const vid = (args.path as { vehicle_id: string }).vehicle_id;
      return Promise.resolve(
        vehicle(
          vid,
          vid === VA
            ? planOf(VA, null)
            : planOf(VB, RUN_B, {
                review_required: [
                  {
                    event_id: '00000000-0000-4000-8000-0000000000e1',
                    event_type: 'CHECK_IN',
                    booking_id: null,
                    reason_code: 'OUTSIDE_STOP',
                    occurred_at: '2026-12-01T09:02:00Z',
                  },
                ],
                forecast_source: 'ETA_MODEL',
                forecast_fallback_reason: 'POSITION_STALE',
              }),
        ),
      );
    });
    mount(c);
    expect(await screen.findByText('Fahrzeug ohne Fahrt')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug B' }));
    expect(await screen.findByText(/OUTSIDE_STOP/)).toBeTruthy();
    expect(screen.getByText(/nicht in der Besetzung/)).toBeTruthy();
    expect(screen.getByText(/Rückfall: Positionsdaten veraltet/)).toBeTruthy();
    expect(screen.queryByText('Fahrzeug ohne Fahrt')).toBeNull();
  });

  it('schnelles_umschalten_mit_live_updates_mischt_nicht', async () => {
    // Antworten werden von Hand freigegeben: die Antwort für A trifft erst nach dem Umschalten auf B ein
    const pending: Record<string, ((v: OperatorVehicle) => void)[]> = { [VA]: [], [VB]: [] };
    const queue = (id: string): ((v: OperatorVehicle) => void)[] => pending[id] ?? [];
    const answer = (id: string, index: number, v: OperatorVehicle): void => {
      const resolve = queue(id).at(index);
      if (!resolve) throw new Error('keine offene Anfrage');
      resolve(v);
    };
    const c = client(
      (id, args) =>
        new Promise((resolve) => {
          queue((args.path as { vehicle_id: string }).vehicle_id).push(resolve as never);
        }),
    );
    const view = mount(c);
    await waitFor(() => expect(queue(VA)).toHaveLength(1));
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug B' }));
    await waitFor(() => expect(queue(VB)).toHaveLength(1));
    // die späte Antwort von A wird verworfen, die Antwort von B gezeigt
    await act(async () => {
      answer(VB, 0, vehicle(VB, planOf(VB, RUN_B)));
      answer(VA, 0, vehicle(VA, planOf(VA, RUN_A)));
    });
    expect(await screen.findByText('Markt b')).toBeTruthy();
    expect(screen.queryByText('Markt a')).toBeNull();
    // Live-Nachrichten für A (anderes Fahrzeug) lösen keinen Abruf für B aus und mischen nichts
    const calls = () =>
      (c.call as unknown as { mock: { calls: unknown[][] } }).mock.calls.filter(
        (x) => x[0] === 'getOperatorVehicle',
      ).length;
    const before = calls();
    view.rerender(
      <SessionProvider client={c}>
        <Harness initial={VB} message={message(VA, 9)} />
      </SessionProvider>,
    );
    await act(async () => {});
    expect(calls()).toBe(before);
    expect(screen.queryByText('Markt a')).toBeNull();
    // eine Nachricht zum gewählten Fahrzeug stößt genau einen Neuabruf an
    view.rerender(
      <SessionProvider client={c}>
        <Harness initial={VB} message={message(VB, 10)} />
      </SessionProvider>,
    );
    await waitFor(() => expect(calls()).toBe(before + 1));
    await act(async () => {
      answer(VB, 1, vehicle(VB, planOf(VB, RUN_B, { plan_revision: 4 })));
    });
    expect(await screen.findByText(/Planrevision 4/)).toBeTruthy();
    // erneut schnell hin und her: nach dem letzten Wechsel gilt nur das zuletzt gewählte Fahrzeug
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug A' }));
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug B' }));
    fireEvent.click(screen.getByRole('button', { name: 'Fahrzeug A' }));
    await waitFor(() => expect(queue(VA).length).toBeGreaterThanOrEqual(2));
    await act(async () => {
      answer(VA, -1, vehicle(VA, planOf(VA, RUN_A)));
      answer(VB, -1, vehicle(VB, planOf(VB, RUN_B)));
    });
    expect(await screen.findByText('Markt a')).toBeTruthy();
    expect(screen.queryByText('Markt b')).toBeNull();
  });
});
