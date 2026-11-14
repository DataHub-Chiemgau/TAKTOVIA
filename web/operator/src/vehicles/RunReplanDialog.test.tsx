import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  OperationId,
  OperatorVehicle,
  RunRevision,
  VehiclePlan,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { formatViolations, runAxe } from '@taktovia/design-system/testing';
import { moveChanges } from './RunReplanDialog';
import { VehiclePlanPanel } from './VehiclePlanPanel';

const VA = '00000000-0000-4000-8000-00000000000a';
const RUN = '00000000-0000-4000-8000-0000000000a1';
const S1 = '00000000-0000-4000-8000-000000000001';
const S2 = '00000000-0000-4000-8000-000000000002';
const S3 = '00000000-0000-4000-8000-000000000003';
const B1 = '00000000-0000-4000-8000-0000000000c1';

const plan = (revision: number): VehiclePlan => ({
  vehicle_id: VA,
  run_id: RUN,
  run_state: 'ACTIVE',
  plan_revision: revision,
  as_of: '2026-12-01T08:55:00Z',
  stops: [S1, S2, S3].map((id, i) => ({
    route_stop_id: id,
    sequence: i + 1,
    stop_name: `Halt ${i + 1}`,
    kind: 'PICKUP' as const,
    scheduled_at: `2026-12-01T09:0${i}:00Z`,
    forecast: null,
    promise_windows: [],
    boarding_booking_ids: [],
    alighting_booking_ids: [],
  })),
  capacity: { seats: 4, wheelchair: 1 },
  occupancy: { seats: 0, wheelchair: 0 },
  review_required: [],
  forecast_source: 'PLAN_TIME',
});

const revision = (n: number, committed: boolean): RunRevision => ({
  run_id: RUN,
  plan_revision: n,
  previous_revision: n - 1,
  cause: 'MANUAL_CHANGE',
  reason: 'Test',
  created_at: '2026-12-01T08:56:00Z',
  created_by: 'user:x',
  diff: [{ route_stop_id: S1, kind: 'MOVED', old_sequence: 1, new_sequence: 2 }],
  committed,
  impacts: [
    {
      booking_id: B1,
      kind: 'TIME_CHANGED',
      pickup_before: '2026-12-01T09:00:00Z',
      pickup_after: '2026-12-01T09:05:00Z',
      dropoff_before: '2026-12-01T09:15:00Z',
      dropoff_after: '2026-12-01T09:20:00Z',
      window_violations: [],
    },
  ],
});

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'operator:read', 'dispatch:write']) {
  const calls: { id: OperationId; args: Record<string, unknown> }[] = [];
  const client = {
    call: vi.fn((id: OperationId, args: Record<string, unknown>) => {
      calls.push({ id, args });
      if (id === 'getMe') return Promise.resolve(sampleMe({ roles: ['dispatcher'], scopes }));
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    }),
  } as unknown as ApiClient;
  const view = render(
    <SessionProvider client={client}>
      <VehiclePlanPanel vehicleId={VA} vehicleLabel="KA-A" />
    </SessionProvider>,
  );
  return { ...view, calls };
}

const vehicle = (p: VehiclePlan): OperatorVehicle =>
  ({ vehicle_id: VA, vehicle_label: 'KA-A', plan: p }) as unknown as OperatorVehicle;

const open = async () => {
  fireEvent.click(await screen.findByRole('button', { name: 'Umplanen' }));
  return screen.findByRole('dialog', { name: 'Umplanung' });
};

describe('UI-07 Umplanung (WP-M3-40)', () => {
  it('bewegungsfolge_bringt_die_reihenfolge_sequentiell_wie_der_server', () => {
    expect(moveChanges([S1, S2, S3], [S1, S2, S3])).toEqual([]);
    expect(moveChanges([S1, S2, S3], [S2, S1, S3])).toEqual([
      { route_stop_id: S2, change: 'MOVE', new_sequence: 1 },
    ]);
    expect(moveChanges([S1, S2, S3], [S3, S1, S2])).toEqual([
      { route_stop_id: S3, change: 'MOVE', new_sequence: 1 },
    ]);
    expect(moveChanges([S1, S2, S3], [S3, S2, S1])).toEqual([
      { route_stop_id: S3, change: 'MOVE', new_sequence: 1 },
      { route_stop_id: S2, change: 'MOVE', new_sequence: 2 },
    ]);
  });

  it('umplanung_per_tastatur_planvergleich_dann_bestaetigen_mit_if_match', async () => {
    const { calls } = setup((id, args) => {
      if (id === 'getOperatorVehicle') return vehicle(plan(3));
      if (id === 'createRunRevision')
        return revision(4, !(args.body as { compare_only?: boolean }).compare_only);
      throw new ApiError(404);
    });
    const dialog = await open();
    // ohne Begründung und ohne Änderung ist nichts möglich
    const compare = within(dialog).getByRole('button', { name: 'Planvergleich anzeigen' });
    const confirm = within(dialog).getByRole('button', { name: 'Umplanung bestätigen' });
    expect((compare as HTMLButtonElement).disabled).toBe(true);
    // Umordnen ohne Maus: Stopp 2 nach oben
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stopp 2 Halt 2 nach oben' }));
    expect(within(dialog).getAllByRole('listitem')[0]?.textContent).toContain('Halt 2');
    expect((compare as HTMLButtonElement).disabled).toBe(true); // Begründung fehlt
    fireEvent.change(within(dialog).getByLabelText('Begründung (Pflicht)'), {
      target: { value: 'Fahrgast wünscht frühere Abholung' },
    });
    expect((compare as HTMLButtonElement).disabled).toBe(false);
    expect((confirm as HTMLButtonElement).disabled).toBe(true); // erst nach dem Planvergleich
    fireEvent.click(compare);
    const comparison = await within(dialog).findByRole('region', { name: 'Planvergleich' });
    expect(within(comparison).getByText(/verschoben: Position 1 → 2/)).toBeTruthy();
    expect(within(comparison).getByText(/Zusagefenster eingehalten/)).toBeTruthy();
    const first = calls.filter((c) => c.id === 'createRunRevision').at(-1);
    expect(first?.args.body).toMatchObject({
      compare_only: true,
      base_plan_revision: 3,
      reason: 'Fahrgast wünscht frühere Abholung',
      changes: [{ route_stop_id: S2, change: 'MOVE', new_sequence: 1 }],
    });
    // Bestätigen: gelesene Revision als If-Match
    fireEvent.click(within(dialog).getByRole('button', { name: 'Umplanung bestätigen' }));
    expect(await screen.findByText(/Umplanung festgeschrieben: Planrevision 4/)).toBeTruthy();
    const commit = calls.filter((c) => c.id === 'createRunRevision').at(-1);
    expect(commit?.args.headers).toMatchObject({ 'If-Match': '"3"' });
    expect(commit?.args.body).toMatchObject({ compare_only: false });
    const violations = await runAxe(document.body);
    expect(violations, formatViolations(violations)).toEqual([]);
  });

  it('konflikt_412_ueberschreibt_nicht_still_und_laedt_den_aktuellen_plan', async () => {
    let current = 3;
    const { calls } = setup((id, args) => {
      if (id === 'getOperatorVehicle') return vehicle(plan(current));
      if (id === 'createRunRevision') {
        if ((args.body as { compare_only?: boolean }).compare_only) return revision(4, false);
        // jemand anderes war schneller: der Plan steht inzwischen auf Revision 4
        current = 4;
        throw new ApiError(412, {
          type: 'x',
          title: 'Veraltete Revision',
          status: 412,
          code: 'REVISION_MISMATCH',
          correlation_id: '00000000-0000-4000-8000-0000000000f1',
        });
      }
      throw new ApiError(404);
    });
    const dialog = await open();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stopp 2 Halt 2 nach oben' }));
    fireEvent.change(within(dialog).getByLabelText('Begründung (Pflicht)'), {
      target: { value: 'Umordnung' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Planvergleich anzeigen' }));
    await within(dialog).findByRole('region', { name: 'Planvergleich' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Umplanung bestätigen' }));
    // Konflikt: sichtbarer Hinweis, keine Festschreibung, der Plan wird neu geladen (Revision 4)
    const alert = await within(dialog).findByRole('alert');
    expect(alert.textContent).toMatch(/nicht übernommen/);
    await waitFor(() => expect(screen.getAllByText(/Planrevision 4/).length).toBeGreaterThan(0));
    expect(screen.queryByText(/Umplanung festgeschrieben/)).toBeNull();
    // der Planvergleich ist verworfen: erneut prüfen und bestätigen ist nötig, es wird nie automatisch wiederholt
    expect(within(dialog).queryByRole('region', { name: 'Planvergleich' })).toBeNull();
    expect(
      (within(dialog).getByRole('button', { name: 'Umplanung bestätigen' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(
      calls.filter(
        (c) =>
          c.id === 'createRunRevision' && !(c.args.body as { compare_only?: boolean }).compare_only,
      ),
    ).toHaveLength(1);
    // der Entwurf beruht auf Revision 3, der Plan steht auf 4: kein Befehl mit alter Revision (Vergleich und Bestätigung gesperrt)
    expect(
      (within(dialog).getByRole('button', { name: 'Planvergleich anzeigen' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('regelverletzung_bietet_ausnahmeweg_mit_begruendung_und_ohne_recht_keine_umplanung', async () => {
    const { calls, unmount } = setup((id, args) => {
      if (id === 'getOperatorVehicle') return vehicle(plan(3));
      if (id === 'createRunRevision') {
        const body = args.body as { exception?: unknown };
        if (!body.exception)
          throw new ApiError(409, {
            type: 'x',
            title: 'Geschützter Stopphorizont',
            status: 409,
            code: 'PROTECTED_STOP_HORIZON',
            correlation_id: '00000000-0000-4000-8000-0000000000f2',
          });
        return revision(4, false);
      }
      throw new ApiError(404);
    });
    const dialog = await open();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stopp 2 Halt 2 nach oben' }));
    fireEvent.change(within(dialog).getByLabelText('Begründung (Pflicht)'), {
      target: { value: 'Umordnung' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Planvergleich anzeigen' }));
    expect(await within(dialog).findByText(/geschützten Horizont/)).toBeTruthy();
    fireEvent.click(within(dialog).getByLabelText(/Ausnahmeweg nutzen/));
    const compare = within(dialog).getByRole('button', { name: 'Planvergleich anzeigen' });
    fireEvent.change(within(dialog).getByLabelText(/Begründung der Ausnahme/), {
      target: { value: 'Fahrgast wartet bereits, genehmigt' },
    });
    fireEvent.click(compare);
    await within(dialog).findByRole('region', { name: 'Planvergleich' });
    expect(calls.filter((c) => c.id === 'createRunRevision').at(-1)?.args.body).toMatchObject({
      exception: {
        rules: ['PROTECTED_STOP_HORIZON'],
        justification: 'Fahrgast wartet bereits, genehmigt',
      },
    });
    unmount();
    // ohne Scope dispatch:write erscheint keine Umplanung (die Anzeige ist keine Rechtequelle, der Server prüft erneut)
    setup(
      (id) => (id === 'getOperatorVehicle' ? vehicle(plan(3)) : undefined),
      ['me:read', 'operator:read'],
    );
    await screen.findByRole('region', { name: 'Fahrzeugplan KA-A' });
    expect(screen.queryByRole('button', { name: 'Umplanen' })).toBeNull();
  });
});
