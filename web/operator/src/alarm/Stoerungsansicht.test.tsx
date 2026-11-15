import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type { ApiClient, Incident, OperationId, OutageBooking } from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { App } from '../App';

const INCIDENT_ID = '00000000-0000-4000-8000-0000000000c1';
const VEHICLE = '00000000-0000-4000-8000-0000000000b1';
const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const incident = (bookings: OutageBooking[]): Incident => ({
  id: INCIDENT_ID,
  kind: 'VEHICLE_OUTAGE',
  vehicle_id: VEHICLE,
  status: 'REPLACEMENT_SEARCH',
  dedupe_key: `VEHICLE_OUTAGE:${VEHICLE}`,
  opened_at: '2026-12-01T08:00:00Z',
  updated_at: '2026-12-01T08:03:00Z',
  rule_revision_id: null,
  affected_run_ids: [uuid(7)],
  defect_ids: [uuid(9)],
  replacement_operation_id: uuid(8),
  revision: 3,
  workflow_log: [],
  outage: {
    bookings,
    boarded_booking_ids: bookings.filter((b) => b.state === 'BOARDED').map((b) => b.booking_id),
    open_count: bookings.filter((b) =>
      ['OPEN', 'PROPOSED', 'OPEN_ISSUE', 'BOARDED'].includes(b.state),
    ).length,
  },
});

const booking = (n: number, over: Partial<OutageBooking>): OutageBooking => ({
  id: uuid(100 + n),
  booking_id: uuid(200 + n),
  booking_number: `BK-2026-00000${n}`,
  run_id: uuid(7),
  vehicle_id: VEHICLE,
  state: 'PROPOSED',
  issue_code: null,
  decision: null,
  decision_reason: null,
  decided_by: null,
  decided_at: null,
  replacement_run_id: null,
  passenger_notice_requested: false,
  revision: 2,
  passengers: 1,
  wheelchair_spaces: 0,
  pickup_window: { earliest_at: '2026-12-01T09:00:00Z', latest_at: '2026-12-01T09:15:00Z' },
  proposals: [
    {
      id: uuid(300 + n),
      vehicle_id: uuid(400 + n),
      run_id: uuid(500 + n),
      run_revision: 1,
      pickup_at: '2026-12-01T09:05:00Z',
      dropoff_at: '2026-12-01T09:20:00Z',
      window_effect: 'IN_WINDOW',
      window_violations: [],
    },
  ],
  actions: ['CHECK_OPTIONS', 'REBOOK', 'CANCEL'],
  ...over,
});

type Handler = (id: OperationId, args: Record<string, unknown>) => unknown;

function setup(handler: Handler, scopes = ['me:read', 'operator:read', 'operator:write']) {
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
  render(
    <MemoryRouter initialEntries={['/leitstelle/vorfaelle']}>
      <SessionProvider client={client}>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  );
  return { calls };
}

const page = (i: Incident) => ({ items: [i], next_cursor: null, as_of: '2026-12-01T08:05:00Z' });

async function open() {
  fireEvent.click(await screen.findByRole('button', { name: 'Ablauf anzeigen' }));
  return screen.findByRole('region', { name: 'Störungsansicht' });
}

describe('UI-07 Störungsansicht (WP-M3-54)', () => {
  it('zeigt betroffene Fahrgäste, Vorschläge mit Fensterwirkung, eingestiegene gesondert und Sprung zur Schadenakte', async () => {
    const inc = incident([
      booking(1, {}),
      booking(2, { state: 'BOARDED', proposals: [], actions: [] }),
      booking(3, {
        state: 'OPEN_ISSUE',
        issue_code: 'NO_REPLACEMENT',
        proposals: [],
        actions: ['CHECK_OPTIONS', 'CANCEL'],
      }),
    ]);
    setup((id) => {
      if (id === 'listOperatorIncidents') return page(inc);
      if (id === 'getOperatorIncident') return inc;
      throw new ApiError(404);
    });
    const view = await open();
    expect(within(view).getByText('Ersatz vorgeschlagen')).toBeTruthy();
    expect(within(view).getByText('im Zusagefenster')).toBeTruthy();
    expect(within(view).getByText(/kein geeignetes Ersatzfahrzeug/)).toBeTruthy();
    expect(within(view).getByRole('note').textContent).toMatch(
      /nicht automatisch umgebucht oder storniert/,
    );
    expect(within(view).getByText(`Schadenakten: ${uuid(9)}`)).toBeTruthy();
    // Aktionen je Zustand: der eingestiegene Fahrgast hat keine
    expect(within(view).getAllByRole('button', { name: 'Umbuchen' })).toHaveLength(1);
    expect(
      within(view).getAllByRole('button', { name: 'Stornieren (Fahrzeugausfall)' }),
    ).toHaveLength(2);
    expect(within(view).getByRole('button', { name: 'Ersatzoptionen prüfen' })).toBeTruthy();
  });

  it('bucht mit If-Match der Buchung um und meldet einen überholten Stand ohne stilles Überschreiben', async () => {
    const inc = incident([booking(1, {})]);
    let conflict = true;
    const { calls } = setup((id) => {
      if (id === 'listOperatorIncidents') return page(inc);
      if (id === 'getOperatorIncident') return inc;
      if (id === 'rebookOutageBooking') {
        if (conflict) throw new ApiError(412);
        return booking(1, { state: 'REBOOKED', revision: 3, actions: ['INFORM_PASSENGER'] });
      }
      throw new ApiError(404);
    });
    const view = await open();
    fireEvent.click(within(view).getByRole('button', { name: 'Umbuchen' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/zwischenzeitlich/);
    expect(calls.filter((c) => c.id === 'rebookOutageBooking')[0]?.args).toMatchObject({
      path: { incident_id: INCIDENT_ID, booking_id: uuid(201) },
      headers: { 'If-Match': '"2"' },
      body: { proposal_id: uuid(301), reason_code: 'VEHICLE_OUTAGE', accept_window_effect: false },
    });
    conflict = false;
    fireEvent.click(
      within(await screen.findByRole('region', { name: 'Störungsansicht' })).getByRole('button', {
        name: 'Umbuchen',
      }),
    );
    expect(await screen.findByText(/Die Buchung wurde umgebucht/)).toBeTruthy();
  });

  it('ohne Recht keine Aktionen und Leerzustand ohne betroffene Fahrten', async () => {
    const inc = incident([booking(1, {})]);
    setup(
      (id) => {
        if (id === 'listOperatorIncidents') return page(inc);
        if (id === 'getOperatorIncident') return inc;
        throw new ApiError(404);
      },
      ['me:read', 'operator:read'],
    );
    const view = await open();
    expect(within(view).queryByRole('button', { name: 'Umbuchen' })).toBeNull();
    expect(within(view).queryByRole('button', { name: 'Ersatzoptionen prüfen' })).toBeNull();
    expect(within(view).getByText(/nur Disponentinnen und Disponenten/)).toBeTruthy();
  });

  it('zeigt den Leerzustand', async () => {
    const inc = incident([]);
    setup((id) => {
      if (id === 'listOperatorIncidents') return page(inc);
      if (id === 'getOperatorIncident') return inc;
      throw new ApiError(404);
    });
    const view = await open();
    expect(within(view).getByText(/keine offene Fahrt betroffen/)).toBeTruthy();
  });
});
