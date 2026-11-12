import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { ApiError, SessionProvider } from '@taktovia/api-client';
import type {
  ApiClient,
  Money,
  RideBooking,
  RideOffer,
  RidePayment,
  RideRejectionCode,
} from '@taktovia/api-client';
import { sampleMe } from '@taktovia/api-client/testing';
import { CheckoutScreen } from './CheckoutScreen';
import { OfferScreen } from './OfferScreen';
import { StartView } from './StartView';

export const SERVICE = '22222222-2222-4222-8222-222222222222';
export const OFFER_ID = '33333333-3333-4333-8333-333333333333';
const STOP_PICKUP = '44444444-4444-4444-8444-444444444441';
const STOP_DROP = '44444444-4444-4444-8444-444444444442';
const REV = '55555555-5555-4555-8555-555555555555';
/** Testzeit: 20.11.2026 07:50 UTC (08:50 in Berlin). */
export const NOW = Date.parse('2026-11-20T07:50:00Z');

const eur = (amount_minor: number): Money => ({ amount_minor, currency: 'EUR', exponent: 2 });

export interface WeltOptions {
  /** Dienst außer Betrieb (mit nächster Betriebszeit). */
  closed?: boolean;
  rejection?: RideRejectionCode;
  bookings?: RideBooking[];
  /** Latenz der Angebotssuche in ms (für Abbruchtests); die Antwort kommt erst danach. */
  searchDelayMs?: number;
  /** Zahl der Statusabfragen (`getBooking`) nach dem Zahlungsstart, bis der PSP-Status vorliegt (Standard 1). */
  settleAfterPolls?: number;
}

/** Zahlungsmittelverweise des PSP-Simulators und was sie in der Welt bewirken. */
export type PspVerweis = 'pm_ok' | 'pm_sca' | 'pm_decline' | 'pm_unknown' | 'pm_expired';

/**
 * Ersatz des Servers für die Buchungsansichten (kein Ersatz für die Komponenten): hält Angebote, Holds und Buchungen im Speicher, prüft `If-Match` (412),
 * `max_gross` (409 REQUOTE_REQUIRED) und den Ablauf (409 OFFER_EXPIRED) wie der Server und leitet die `promise` der Buchung aus dem Angebot ab.
 * Das echte Serververhalten belegen `StandardfahrtHttpApiTest` und `BookingListApiTest` im Backend.
 */
export function buchungsWelt(options: WeltOptions = {}) {
  const w = {
    now: NOW,
    calls: [] as { id: string; args: unknown }[],
    bookings: [...(options.bookings ?? [])] as RideBooking[],
    offer: undefined as RideOffer | undefined,
    holdSeq: 0,
    failNext: new Map<string, ApiError>(),
    /** Haken, der vor der Verarbeitung der Operation läuft (z. B. paralleler Eingriff am Angebot). */
    before: new Map<string, () => void>(),
    count: (id: string) => w.calls.filter((c) => c.id === id).length,
    /** Zahlungen je Buchung: gewählter PSP-Verweis, Statusabfragen seit dem Start und ob die SCA-Rückkehr eingegangen ist. */
    pay: new Map<string, { method: PspVerweis; polls: number; returned: boolean }>(),
  };
  /** Der Server entscheidet über den Ausgang der Zahlung: je Statusabfrage schreitet der PSP-Status voran (verifizierter Status, nicht Browser-Rückkehr). */
  const advance = (b: RideBooking) => {
    const p = w.pay.get(b.id);
    if (!p || !b.payment) return;
    p.polls += 1;
    if (p.polls < (options.settleAfterPolls ?? 1)) return;
    const aktuell: RidePayment = b.payment;
    const setPay = (status: RidePayment['status'], failure?: string) => {
      b.payment = { ...aktuell, status, ...(failure ? { failure_code: failure } : {}) };
      b.revision += 1;
    };
    if (b.payment.status !== 'CREATED' && b.payment.status !== 'REQUIRES_ACTION') return;
    if (p.method === 'pm_ok' || (p.method === 'pm_sca' && p.returned)) {
      setPay('AUTHORIZED');
      b.state = 'CONFIRMED';
    } else if (p.method === 'pm_sca') setPay('REQUIRES_ACTION');
    else if (p.method === 'pm_decline') {
      setPay('AUTHORIZATION_FAILED', 'DECLINED');
      b.state = 'CANCELED';
      b.cancel_reason = 'PAYMENT_FAILED';
    } else if (p.method === 'pm_unknown') setPay('UNKNOWN', 'AUTHORIZE_OUTCOME_UNKNOWN');
    else if (p.method === 'pm_expired') {
      setPay('VOIDED', 'AUTHORIZATION_EXPIRED');
      b.state = 'CANCELED';
      b.cancel_reason = 'PAYMENT_FAILED';
    }
  };
  const makeOffer = (): RideOffer => ({
    id: OFFER_ID,
    attempt_id: '66666666-6666-4666-8666-666666666666',
    service_id: SERVICE,
    state: 'OFFERED',
    pickup_window: { earliest_at: '2026-11-20T07:55:00Z', latest_at: '2026-11-20T08:10:00Z' },
    arrival_window: { earliest_at: '2026-11-20T08:07:00Z', latest_at: '2026-11-20T08:22:00Z' },
    expires_at: '2026-11-20T08:30:00Z',
    gross: eur(950),
    tariff_revision: 3,
    cancellation_policy_ref: 'std-24h',
    // bewusst Komponenten, deren Summe nicht `gross` ist: die Oberfläche darf nie rechnen
    fare_components: [
      {
        code: 'TRANSPORT',
        description: 'Fahrpreis (netto)',
        amount: eur(841),
        tariff_revision: '3',
      },
      {
        code: 'SURCHARGE',
        description: 'Buchungsgebühr (netto)',
        amount: eur(47),
        tariff_revision: '3',
      },
      {
        code: 'TAX',
        description: 'Umsatzsteuer (enthalten)',
        amount: eur(62),
        tariff_revision: '3',
      },
    ],
    pickup_stop: { stop_id: STOP_PICKUP, revision_id: REV },
    dropoff_stop: { stop_id: STOP_DROP, revision_id: REV },
    demand: { schema_version: '1.0', seats: 1, wheelchair: 0 },
    hold: null,
    revision: 1,
  });
  const hit = (label: string, outside: boolean) => ({
    type: 'ADDRESS',
    label,
    point: { latitude: 51.05, longitude: 13.74 },
    stop_id: null,
    source: 'test',
    data_as_of: '2026-11-20T07:00:00Z',
    service_area: outside ? 'OUTSIDE' : 'INSIDE',
    nearest_stop: null,
  });
  const stop = (id: string, name: string, lat: number) => ({
    stop_id: id,
    revision: 1,
    name,
    point: { latitude: lat, longitude: 13.74 },
    accessibility: { schema_version: '1.0', step_free: 'YES', wheelchair_boarding: 'UNVERIFIED' },
    distance_m: null,
  });
  const problem = (status: number, code: string) =>
    new ApiError(status, {
      type: 'about:blank',
      title: code,
      status,
      code,
      correlation_id: '77777777-7777-4777-8777-777777777777',
    });
  const reply = (v: unknown) => Promise.resolve(structuredClone(v));

  const client = {
    async call(id: string, args: Record<string, unknown>) {
      w.calls.push({ id, args });
      w.before.get(id)?.();
      const failed = w.failNext.get(id);
      if (failed) {
        w.failNext.delete(id);
        throw failed;
      }
      const query = (args.query ?? {}) as Record<string, string | undefined>;
      switch (id) {
        case 'getMe':
          return reply({ ...sampleMe({ roles: ['PASSENGER'] }), service_scope: [SERVICE] });
        case 'getMyProfile':
          throw new ApiError(404);
        case 'getServiceAvailability':
          return reply({
            service_id: SERVICE,
            at: '2026-11-20T07:50:00Z',
            in_operation: !options.closed,
            next_opening: options.closed ? '2026-11-21T05:00:00Z' : '2026-11-20T07:50:00Z',
            timezone: 'Europe/Berlin',
            traffic_product: null,
          });
        case 'listBookings':
          return reply({ items: w.bookings, next_cursor: null, as_of: '2026-11-20T07:50:00Z' });
        case 'searchPlaces':
          return reply({
            items: [
              hit(query.q === 'berlin' ? 'Berlin' : 'Dresden Altmarkt', query.q === 'berlin'),
            ],
            notices: [],
            as_of: '2026-11-20T07:50:00Z',
          });
        case 'createRideOffer': {
          if (options.searchDelayMs) {
            const signal = args.signal as AbortSignal | undefined;
            await new Promise<void>((resolve, reject) => {
              const t = setTimeout(resolve, options.searchDelayMs);
              signal?.addEventListener('abort', () => {
                clearTimeout(t);
                reject(new ApiError(0));
              });
            });
          }
          if (options.rejection)
            return reply({
              attempt_id: '66666666-6666-4666-8666-666666666666',
              offers: [],
              rejection: {
                code: options.rejection,
                secondary_codes: [],
                text: 'x',
                next_action: 'y',
              },
            });
          w.offer = makeOffer();
          return reply({
            attempt_id: w.offer.attempt_id,
            offers: [
              {
                ...w.offer,
                booking_kind: 'IMMEDIATE',
                time_kind: 'DEPARTURE',
                arrival_grace_min: 5,
                direct_seconds: 720,
              },
            ],
            rejection: null,
          });
        }
        case 'getRideOffer':
          if (!w.offer) throw new ApiError(404);
          return reply(w.offer);
        case 'getStop': {
          const sid = (args.path as { stop_id: string }).stop_id;
          return reply(
            sid === STOP_PICKUP
              ? stop(STOP_PICKUP, 'Altmarkt', 51.0505)
              : stop(STOP_DROP, 'Hauptbahnhof', 51.0405),
          );
        }
        case 'holdRideOffer': {
          const o = w.offer;
          if (!o) throw new ApiError(404);
          if (w.now >= Date.parse(o.expires_at)) throw problem(409, 'OFFER_EXPIRED');
          if (String(args.revision) !== String(o.revision)) throw problem(412, 'REVISION_MISMATCH');
          const max = (args.body as { max_gross: Money }).max_gross;
          if (max.amount_minor !== o.gross.amount_minor) throw problem(409, 'REQUOTE_REQUIRED');
          w.holdSeq += 1;
          const hold = {
            id: `88888888-8888-4888-8888-88888888888${w.holdSeq}`,
            state: 'HELD' as const,
            expires_at: '2026-11-20T07:53:00Z',
            capacity_vector: o.demand,
          };
          o.hold = hold;
          o.state = 'HELD';
          o.revision += 1;
          return reply(hold);
        }
        case 'releaseHold': {
          const o = w.offer;
          if (o) {
            o.hold = null;
            o.state = 'OFFERED';
            o.revision += 1;
          }
          return reply({});
        }
        case 'getBooking': {
          const b = w.bookings.find(
            (x) => x.id === (args.path as { booking_id: string }).booking_id,
          );
          if (!b) throw problem(404, 'NOT_FOUND');
          advance(b);
          return reply(b);
        }
        case 'startBookingPayment': {
          const b = w.bookings.find(
            (x) => x.id === (args.path as { booking_id: string }).booking_id,
          );
          if (!b) throw problem(404, 'NOT_FOUND');
          const body = args.body as { payment_method_ref: PspVerweis; amount: Money };
          if (body.amount.amount_minor !== b.promise.gross.amount_minor)
            throw problem(409, 'REQUOTE_REQUIRED');
          if (!w.pay.has(b.id)) {
            w.pay.set(b.id, { method: body.payment_method_ref, polls: 0, returned: false });
            b.payment = {
              id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
              status: 'CREATED',
              authorized: { ...b.promise.gross, amount_minor: 0 },
              captured: { ...b.promise.gross, amount_minor: 0 },
              refunded: { ...b.promise.gross, amount_minor: 0 },
              revision: 1,
            };
          }
          return reply({
            operation_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
            kind: 'AUTHORIZE_PAYMENT',
            status: 'RUNNING',
            journey_id: null,
            created_at: '2026-11-20T07:51:00Z',
            updated_at: '2026-11-20T07:51:00Z',
            result_code: null,
            retry_after_seconds: 1,
          });
        }
        case 'returnBookingPayment': {
          const p = w.pay.get((args.path as { booking_id: string }).booking_id);
          if (p) p.returned = true;
          return reply({
            operation_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
            kind: 'RECONCILE_PAYMENT',
            status: 'RUNNING',
            journey_id: null,
            created_at: '2026-11-20T07:51:00Z',
            updated_at: '2026-11-20T07:51:00Z',
            result_code: null,
            retry_after_seconds: 1,
          });
        }
        case 'confirmBooking': {
          const o = w.offer;
          const body = args.body as { hold_id: string; max_gross: Money };
          if (!o || o.hold?.id !== body.hold_id) throw new ApiError(404);
          // Wiederholte Bestätigung desselben Holds liefert dieselbe Buchung (wie der Server); die Antwort trägt keinen Zahlungsblock
          const vorhanden = w.bookings.find((x) => x.hold_id === body.hold_id);
          if (vorhanden) {
            const { payment: _payment, ...ohne } = vorhanden;
            void _payment;
            return reply(ohne);
          }
          if (String(args.revision) !== String(o.revision)) throw problem(412, 'REVISION_MISMATCH');
          if (body.max_gross.amount_minor !== o.gross.amount_minor)
            throw problem(409, 'REQUOTE_REQUIRED');
          const b: RideBooking = {
            id: '99999999-9999-4999-8999-999999999999',
            booking_number: 'BK-2026-000001',
            service_id: SERVICE,
            hold_id: body.hold_id,
            state: 'CONFIRMING',
            bereich: 'bevorstehend',
            created_at: '2026-11-20T07:51:00Z',
            passengers: 1,
            wheelchair_spaces: 0,
            revision: 1,
            promise: {
              pickup_earliest: o.pickup_window.earliest_at,
              pickup_latest: o.pickup_window.latest_at,
              arrival_earliest: o.arrival_window.earliest_at,
              arrival_latest: o.arrival_window.latest_at,
              gross: o.gross,
              cancellation_policy_ref: o.cancellation_policy_ref ?? 'std-24h',
              resource_vector: o.demand,
            },
          };
          w.bookings.push(b);
          return reply(b);
        }
        default:
          throw new ApiError(404);
      }
    },
  };
  return { world: w, client: client as unknown as ApiClient };
}

function Pfad({ onPath }: { onPath: (p: string) => void }) {
  const l = useLocation();
  onPath(l.pathname);
  return null;
}

/** UI-01 und UI-02 mit Router: der Test kann den Weg der Standardfahrt vollständig durchlaufen. */
export function renderBuchung(client: ApiClient, now: () => number, paths: string[] = []) {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider client={client}>
        <Pfad onPath={(p) => paths.push(p)} />
        <Routes>
          <Route path="/" element={<StartView now={now} />} />
          <Route path="/angebote" element={<OfferScreen now={now} />} />
          <Route path="/checkout/:offerId" element={<CheckoutScreen now={now} pollMs={5} />} />
          <Route path="/reisen" element={<p>Meine Reisen</p>} />
          <Route path="/reisen/:bookingId" element={<p>Reise</p>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}
