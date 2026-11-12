import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  PassengerPlanChange,
  PlanChangeBooking,
  RideBooking,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { LiveRegionProvider } from '@taktovia/design-system';

export const SERVICE = '22222222-2222-4222-8222-222222222222';
export const BOOKING_ID = '99999999-9999-4999-8999-999999999999';
export const OTHER_BOOKING_ID = '99999999-9999-4999-8999-999999999998';
const STOP = '44444444-4444-4444-8444-444444444441';
const REV = '55555555-5555-4555-8555-555555555555';

const eur = (amount_minor: number) => ({ amount_minor, currency: 'EUR' as const, exponent: 2 });

export function basisBuchung(over: Partial<RideBooking> = {}): RideBooking {
  return {
    id: BOOKING_ID,
    booking_number: 'BK-2026-000001',
    service_id: SERVICE,
    hold_id: '88888888-8888-4888-8888-888888888881',
    state: 'CONFIRMED',
    bereich: 'bevorstehend',
    created_at: '2026-11-19T07:51:00Z',
    passengers: 1,
    wheelchair_spaces: 0,
    revision: 3,
    promise: {
      pickup_earliest: '2026-11-21T07:55:00Z',
      pickup_latest: '2026-11-21T08:10:00Z',
      arrival_earliest: '2026-11-21T08:07:00Z',
      arrival_latest: '2026-11-21T08:22:00Z',
      gross: eur(950),
      cancellation_policy_ref: 'std-24h',
      resource_vector: { schema_version: '1.0', seats: 1, wheelchair: 0 },
    },
    payment: {
      id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      status: 'AUTHORIZED',
      authorized: eur(950),
      captured: eur(0),
      refunded: eur(0),
      revision: 2,
    },
    pickup_stop: {
      stop_id: STOP,
      revision_id: REV,
      revision: 1,
      name: 'Altmarkt',
      point: { latitude: 51.0505, longitude: 13.74 },
      accessibility: { schema_version: '1.0', step_free: 'YES', wheelchair_boarding: 'UNVERIFIED' },
    },
    eta: {
      pickup_at: '2026-11-21T08:00:00Z',
      dropoff_at: '2026-11-21T08:12:00Z',
      previous_pickup_at: null,
      plan_sequence: 1,
      plan_revision: 2,
      data_as_of: '2026-11-21T06:00:00Z',
      source: 'PLAN',
    },
    vehicle: { label: 'Bus 12', vehicle_class: 'KLEINBUS' },
    cancellation: {
      allowed: true,
      deadline_at: '2026-11-21T06:55:00Z',
      deadline_min: 60,
      deadline_defaulted: false,
    },
    data_as_of: '2026-11-21T06:00:00Z',
    ...over,
  };
}

/**
 * Ersatz des Servers für Reisedetail, Übersicht und Storno (kein Ersatz der Komponenten): hält eine Buchung und den Änderungsstrom ihrer Planrevisionen im Speicher. `planChange`
 * verhält sich wie eine neue Planrevision des Servers (neue ETA, `sequence` + 1, Eintrag im Strom); `down` simuliert den Verbindungsabbruch. Das echte Serververhalten belegen
 * `BookingDetailApiTest` und `PlanChangeHttpApiTest` im Backend.
 */
export interface ReiseWelt {
  booking: RideBooking;
  others: RideBooking[];
  feed: PassengerPlanChange[];
  cursor: number;
  down: boolean;
  calls: { id: string; args: unknown }[];
  before: Map<string, () => void>;
  failNext: Map<string, ApiError>;
  count: (id: string) => number;
  entry: (change: Omit<PlanChangeBooking, 'booking_id'>, bookingId?: string) => PassengerPlanChange;
  planChange: (pickupAt: string, dropoffAt?: string) => PassengerPlanChange;
  /** Alle Buchungen der Welt (die geöffnete und die weiteren). */
  alle: () => RideBooking[];
  /** Ändert eine Buchung wie ein Statuswechsel auf dem Server; `mitEintrag` legt zusätzlich einen Eintrag im Änderungsstrom an (Plan ohne die Buchung). */
  setze: (id: string, patch: Partial<RideBooking>, mitEintrag?: boolean) => void;
}

export function reiseWelt(initial: RideBooking = basisBuchung(), others: RideBooking[] = []) {
  const w: ReiseWelt = {
    booking: initial,
    others,
    feed: [] as PassengerPlanChange[],
    cursor: 0,
    down: false,
    calls: [] as { id: string; args: unknown }[],
    /** Haken je Operation vor der Verarbeitung. */
    before: new Map<string, () => void>(),
    failNext: new Map<string, ApiError>(),
    count: (id: string) => w.calls.filter((c) => c.id === id).length,
    alle: () => [w.booking, ...w.others],
    setze(id, patch, mitEintrag = false) {
      if (w.booking.id === id) w.booking = { ...w.booking, ...patch };
      else w.others = w.others.map((b) => (b.id === id ? { ...b, ...patch } : b));
      if (mitEintrag)
        w.entry(
          {
            kind: 'REMOVED',
            sequence: 99,
            pickup_before: null,
            pickup_after: null,
            dropoff_before: null,
            dropoff_after: null,
          },
          id,
        );
    },
    entry(change: Omit<PlanChangeBooking, 'booking_id'>, bookingId = w.booking.id) {
      w.cursor += 1;
      const e: PassengerPlanChange = {
        cursor: w.cursor,
        cause: 'MANUAL_CHANGE',
        recorded_at: '2026-11-21T06:30:00Z',
        bookings: [{ booking_id: bookingId, ...change }],
      };
      w.feed.push(e);
      return e;
    },
    /** Neue Planrevision: ETA ändert sich, Stand und Eintrag im Strom entstehen gemeinsam. */
    planChange(pickupAt: string, dropoffAt = '2026-11-21T08:27:00Z') {
      const eta = w.booking.eta;
      const sequence = (eta?.plan_sequence ?? 0) + 1;
      const before = eta?.pickup_at ?? null;
      w.booking = {
        ...w.booking,
        eta: {
          pickup_at: pickupAt,
          dropoff_at: dropoffAt,
          previous_pickup_at: before,
          plan_sequence: sequence,
          plan_revision: (eta?.plan_revision ?? 1) + 1,
          data_as_of: '2026-11-21T06:30:00Z',
          source: 'PLAN',
        },
      };
      return w.entry({
        kind: 'TIME_CHANGED',
        sequence,
        pickup_before: before,
        pickup_after: pickupAt,
        dropoff_before: eta?.dropoff_at ?? null,
        dropoff_after: dropoffAt,
      });
    },
  };
  const client = {
    async call(id: string, args: Record<string, unknown>) {
      w.calls.push({ id, args });
      w.before.get(id)?.();
      const failed = w.failNext.get(id);
      if (failed) {
        w.failNext.delete(id);
        throw failed;
      }
      if (
        w.down &&
        (id === 'listPlanChanges' || id === 'getBooking' || id === 'getPlanChangesSnapshot')
      )
        throw new ApiError(0);
      switch (id) {
        case 'getMe':
          return structuredClone({
            ...sampleMe({ roles: ['PASSENGER'] }),
            service_scope: [SERVICE],
          });
        case 'getMyProfile':
          throw new ApiError(404);
        case 'getBooking': {
          const bid = (args.path as { booking_id: string }).booking_id;
          const b = [w.booking, ...w.others].find((x) => x.id === bid);
          if (!b)
            throw new ApiError(404, {
              type: 'about:blank',
              title: 'x',
              status: 404,
              code: 'NOT_FOUND',
              correlation_id: '77777777-7777-4777-8777-777777777777',
            });
          return structuredClone(b);
        }
        case 'listBookings': {
          const bereich = (args.query as { bereich?: string } | undefined)?.bereich;
          if (
            bereich !== undefined &&
            !['bevorstehend', 'laufend', 'vergangen', 'storniert'].includes(bereich)
          )
            throw new ApiError(422, {
              type: 'about:blank',
              title: 'x',
              status: 422,
              code: 'VALIDATION_FAILED',
              correlation_id: '77777777-7777-4777-8777-777777777777',
            });
          if (w.down) throw new ApiError(0);
          return structuredClone({
            items: w.alle().filter((b) => bereich === undefined || b.bereich === bereich),
            next_cursor: null,
            as_of: '2026-11-21T06:00:00Z',
          });
        }
        case 'getPlanChangesSnapshot':
          return {
            as_of: '2026-11-21T06:00:00Z',
            cursor: w.cursor,
            bookings: [],
          };
        case 'listPlanChanges': {
          const after = Number((args.query as { after?: number } | undefined)?.after ?? 0);
          // Der Strom darf ungeordnet und doppelt zustellen: die Test-Welt liefert die Einträge in Listenreihenfolge, deren Cursor über `after` liegt
          const items = w.feed.filter((e) => e.cursor > after);
          return structuredClone({
            items,
            next_cursor: items.length ? Math.max(...items.map((e) => e.cursor)) : after,
            has_more: false,
          });
        }
        default:
          throw new ApiError(404);
      }
    },
  };
  return { world: w, client: client as unknown as ApiClient };
}

export function renderReise(
  client: ApiClient,
  ui: (id: string) => React.ReactNode,
  path = `/reisen/${BOOKING_ID}`,
) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider client={client}>
        <LiveRegionProvider>
          <Routes>
            <Route path="/reisen/:bookingId" element={<Detail ui={ui} />} />
            <Route path="/reisen" element={<p>Übersicht</p>} />
          </Routes>
        </LiveRegionProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

import { useParams } from 'react-router-dom';
function Detail({ ui }: { ui: (id: string) => React.ReactNode }) {
  const { bookingId } = useParams();
  return <>{ui(bookingId ?? '')}</>;
}
