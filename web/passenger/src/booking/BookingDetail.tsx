import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { RideBooking } from '@taktovia/api-client';
import { ErrorState, useAnnounce } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { zahlstatusText } from './Checkout';
import { formatMoney } from './geld';
import { wendeAn } from './reiseStrom';
import type { EtaAenderung } from './reiseStrom';
import { fenster, ortszeit } from './Zeitanzeige';
import { tx } from '../texte';
import type { TextKey } from '../texte';

export interface BookingDetailProps {
  bookingId: string;
  zone: string;
  /** Abstand der Abfragen des Änderungsstroms in ms. */
  pollMs?: number;
  /** Zusätzliche Abschnitte unter den Fakten (Fußweg, Storno), die dieselbe Buchung zeigen. */
  children?: (b: RideBooking, reload: () => Promise<void>) => React.ReactNode;
}

function yesNo(v: unknown): string {
  return v === 'YES'
    ? tx('angebot.ja')
    : v === 'NO'
      ? tx('angebot.nein')
      : tx('angebot.unbestaetigt');
}

const STATE_KEY: Record<RideBooking['state'], TextKey> = {
  CONFIRMING: 'reise.state.confirming',
  CONFIRMED: 'reise.state.confirmed',
  RECONCILING: 'reise.state.reconciling',
  COMPENSATING: 'reise.state.compensating',
  CANCEL_PENDING: 'reise.state.cancelPending',
  CANCELED: 'reise.state.canceled',
  COMPLETED: 'reise.state.completed',
  FAILED: 'reise.state.failed',
};

export function stateText(state: RideBooking['state']): string {
  return tx(STATE_KEY[state]);
}

/**
 * UI-04 Buchungsdetail (WP-M2-43, LH-6.3-PAX-006): Buchungsnummer, fachlicher Status und Zahlungsstatus getrennt, Treffpunkt, verbindliche Zusage **getrennt** von der aktuellen ETA, Fahrzeug
 * (sobald zugewiesen), „Letzter Stand“. Die Ansicht folgt dem Änderungsstrom der Planrevisionen (`/v1/plan-changes`) ohne Neuladen: eine ETA-Änderung erscheint mit Hinweis (alt zu neu) und
 * wird über die zurückhaltende Live-Region angesagt, ohne den Fokus zu verschieben. Bei unterbrochener Verbindung gilt der Stand als veraltet; nach der Wiederverbindung wird der aktuelle
 * Stand geladen (Snapshot `GET /v1/bookings/{id}`). Der Stand wird nie auf eine ältere Revision zurückgesetzt (`sequence`-Prüfung, siehe `reiseStrom.ts`).
 */
export function BookingDetail({ bookingId, zone, pollMs = 5000, children }: BookingDetailProps) {
  const api = useApi();
  const announce = useAnnounce();
  const [booking, setBooking] = useState<RideBooking | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [stale, setStale] = useState(false);
  const [change, setChange] = useState<EtaAenderung | null>(null);
  const [lastUpdate, setLastUpdate] = useState<string | null>(null);
  const current = useRef<RideBooking | null>(null);
  const known = useRef(0);
  const cursor = useRef(0);
  const staleRef = useRef(false);

  /** Setzt den Stand aus dem Snapshot, außer er wäre älter als der gezeigte (Revision und Plansequenz). */
  const adopt = useCallback((b: RideBooking, at: string) => {
    const cur = current.current;
    const older =
      cur !== null &&
      (b.revision < cur.revision ||
        (b.revision === cur.revision &&
          (b.eta?.plan_sequence ?? 0) < (cur.eta?.plan_sequence ?? 0)));
    if (older) return;
    current.current = b;
    known.current = Math.max(known.current, b.eta?.plan_sequence ?? 0);
    setBooking(b);
    setLastUpdate(at);
  }, []);

  const load = useCallback(async () => {
    // Der Strom-Cursor wird vor dem Snapshot gelesen: bereits enthaltene Einträge fängt die Sequenzprüfung ab
    const snap = await api.call('getPlanChangesSnapshot', {});
    const b = await api.call('getBooking', { path: { booking_id: bookingId } });
    cursor.current = Math.max(cursor.current, snap.cursor);
    adopt(b, b.data_as_of ?? snap.as_of);
  }, [api, bookingId, adopt]);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const markStale = (v: boolean) => {
      staleRef.current = v;
      if (alive) setStale(v);
    };
    const tick = async () => {
      try {
        if (staleRef.current) {
          // Wiederverbindung: den aktuellen Stand laden, danach den Strom ab dem aktuellen Cursor lesen
          await load();
          markStale(false);
        }
        let more = true;
        while (more && alive) {
          const feed = await api.call('listPlanChanges', { query: { after: cursor.current } });
          more = feed.has_more;
          for (const entry of feed.items) {
            const cur = current.current;
            if (!cur) break;
            const r = wendeAn(cur, known.current, entry);
            known.current = r.bekannt;
            if (r.next.snapshot) {
              const b = await api.call('getBooking', { path: { booking_id: bookingId } });
              adopt(b, entry.recorded_at);
              known.current = Math.max(known.current, b.eta?.plan_sequence ?? 0);
            } else if (r.next.booking !== cur) {
              current.current = r.next.booking;
              if (alive) {
                setBooking(r.next.booking);
                setLastUpdate(entry.recorded_at);
              }
              if (r.next.aenderung && alive) {
                setChange(r.next.aenderung);
                announce(
                  `${tx('reise.etaGeaendertAnsage')} ${ortszeit(r.next.aenderung.nach ?? '', zone)}`,
                );
              }
            }
          }
          cursor.current = Math.max(cursor.current, feed.next_cursor);
        }
      } catch {
        markStale(true);
      }
      if (alive) timer = setTimeout(() => void tick(), pollMs);
    };
    load().then(
      () => {
        if (alive) timer = setTimeout(() => void tick(), pollMs);
      },
      (e: unknown) => {
        if (alive) setError(e instanceof ApiError ? e : new ApiError(0));
      },
    );
    return () => {
      alive = false;
      if (timer) clearTimeout(timer);
    };
  }, [api, bookingId, load, adopt, announce, pollMs, zone]);

  const reload = useCallback(async () => {
    const b = await api.call('getBooking', { path: { booking_id: bookingId } });
    adopt(b, b.data_as_of ?? new Date().toISOString());
  }, [api, bookingId, adopt]);

  if (error)
    return (
      <ErrorState error={error} action={<Link to="/reisen">{tx('reise.zurUebersicht')}</Link>} />
    );
  if (!booking) return <p aria-busy="true">{tx('reise.laedt')}</p>;

  const p = booking.promise;
  const acc = booking.pickup_stop?.accessibility as Record<string, unknown> | undefined;
  return (
    <article
      aria-labelledby="reise-h"
      data-testid="reise"
      data-booking-id={booking.id}
      data-revision={booking.revision}
    >
      <h2 id="reise-h">
        {tx('reise.titel')} {booking.booking_number}
      </h2>
      {stale ? (
        <p role="status" data-testid="daten-veraltet">
          {tx('reise.veraltet')} {lastUpdate ? ortszeit(lastUpdate, zone) : '–'}
        </p>
      ) : null}
      {change ? (
        <p data-testid="eta-aenderung">
          {tx('reise.etaGeaendert')} <del>{change.von ? ortszeit(change.von, zone) : '–'}</del>
          {' → '}
          <ins>{change.nach ? ortszeit(change.nach, zone) : '–'}</ins>.{' '}
          {tx('reise.zusageUnveraendert')}
        </p>
      ) : null}
      <dl>
        <dt>{tx('reise.nummer')}</dt>
        <dd data-testid="buchungsnummer">{booking.booking_number}</dd>
        <dt>{tx('reise.status')}</dt>
        <dd data-testid="buchung-status">{stateText(booking.state)}</dd>
        <dt>{tx('reise.zahlung')}</dt>
        <dd data-testid="zahlungsstatus">{zahlstatusText(booking)}</dd>
        <dt>{tx('reise.treffpunkt')}</dt>
        <dd data-testid="treffpunkt">{booking.pickup_stop?.name ?? '–'}</dd>
        {acc ? (
          <>
            <dt>{tx('reise.zugang')}</dt>
            <dd data-testid="zugang">
              {tx('angebot.stufenfrei')}: {yesNo(acc.step_free)}; {tx('angebot.rollstuhl')}:{' '}
              {yesNo(acc.wheelchair_boarding)}
            </dd>
          </>
        ) : null}
        <dt>{tx('reise.zusage')}</dt>
        <dd data-testid="zusage-abholung">
          {fenster({ earliest_at: p.pickup_earliest, latest_at: p.pickup_latest }, zone)}
        </dd>
        <dt>{tx('reise.zusageAnkunft')}</dt>
        <dd data-testid="zusage-ankunft">
          {fenster({ earliest_at: p.arrival_earliest, latest_at: p.arrival_latest }, zone)}
        </dd>
        <dt>{tx('reise.eta')}</dt>
        <dd data-testid="eta">
          {booking.eta ? (
            <>
              <time dateTime={booking.eta.pickup_at}>{ortszeit(booking.eta.pickup_at, zone)}</time>{' '}
              ({tx('reise.etaQuelle')})
            </>
          ) : (
            tx('reise.etaKeine')
          )}
        </dd>
        <dt>{tx('reise.fahrzeug')}</dt>
        <dd data-testid="fahrzeug">
          {booking.vehicle
            ? `${booking.vehicle.label}${booking.vehicle.vehicle_class ? ` (${booking.vehicle.vehicle_class})` : ''}`
            : tx('reise.fahrzeugKeins')}
        </dd>
        <dt>{tx('angebot.preis')}</dt>
        <dd data-testid="preis">{formatMoney(p.gross)}</dd>
        <dt>{tx('reise.letzterStand')}</dt>
        <dd data-testid="letzter-stand">{lastUpdate ? ortszeit(lastUpdate, zone) : '–'}</dd>
      </dl>
      {/* eslint-disable-next-line react-hooks/refs -- `reload` ist ein stabiler Rückruf (useCallback), der Ref-Zugriff geschieht erst beim Aufruf im Ereignis, nicht beim Rendern */}
      {children ? children(booking, reload) : null}
      <p>
        <Link to="/reisen">{tx('reise.zurUebersicht')}</Link>
      </p>
    </article>
  );
}
