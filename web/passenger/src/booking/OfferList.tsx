import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { GeoStopPassenger, RideBooking, RideOffer, RidePlace } from '@taktovia/api-client';
import { BusyButton, ErrorState } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { formatMoney } from './geld';
import { fussweg } from './fussweg';
import { fenster, ortszeit } from './Zeitanzeige';
import { useCommands } from '../api/useCommands';
import { tx } from '../texte';

/** Angebot mit den Haltepunkten, die zur Anzeige gelesen wurden (Name, Lage, Barrierefreiheit). */
interface Shown {
  offer: RideOffer;
  pickup: GeoStopPassenger | null;
  dropoff: GeoStopPassenger | null;
}

type Notice =
  | { kind: 'changed'; priceChanged: boolean }
  | { kind: 'expired' }
  | { kind: 'capacity' }
  | { kind: 'unclear' };

export interface OfferListProps {
  offerIds: readonly string[];
  /** Gewählter Start/Ziel aus UI-01 für die Fußwegangabe; fehlen sie (z. B. nach Neuladen), ist der Fußweg „nicht verfügbar“. */
  origin?: RidePlace | undefined;
  destination?: RidePlace | undefined;
  zone: string;
  /** Planungsfahrzeit je Angebot aus der Suchantwort (`direct_seconds`); ohne sie wird die Dauer aus den Zeitfenstern abgeleitet. */
  directSeconds?: Readonly<Record<string, number>> | undefined;
  now?: (() => number) | undefined;
  onNewSearch: () => void;
}

/** Identität des angezeigten Standes: alles, was der Fahrgast sieht und was bei Hold/Bestätigung gelten muss. */
export function standKey(o: RideOffer): string {
  return JSON.stringify([
    o.revision,
    o.gross,
    o.fare_components,
    o.pickup_window,
    o.arrival_window,
    o.pickup_stop,
    o.dropoff_stop,
    o.expires_at,
  ]);
}

/** Abgeleitete Fahrtdauer (Näherung): früheste Ankunft minus früheste Abholung. */
function dauerMinuten(o: RideOffer, direct: number | undefined): { min: number; ca: boolean } {
  if (direct !== undefined) return { min: Math.max(1, Math.ceil(direct / 60)), ca: false };
  const diff = Date.parse(o.arrival_window.earliest_at) - Date.parse(o.pickup_window.earliest_at);
  return { min: Math.max(1, Math.ceil(diff / 60000)), ca: true };
}

function yesNo(v: string | undefined): string {
  return v === 'YES'
    ? tx('angebot.ja')
    : v === 'NO'
      ? tx('angebot.nein')
      : tx('angebot.unbestaetigt');
}

const STATUS_TEXT: Partial<Record<RideBooking['state'], string>> = {
  CONFIRMING: tx('angebot.statusConfirming'),
  CONFIRMED: tx('angebot.statusConfirmed'),
};

/**
 * UI-02 Angebotsliste und Angebotsdetails (WP-M2-41, LH-6.3-PAX-005) mit dem Bestätigungsschritt der Standardfahrt (Hauptschritt 4; der volle Checkout UI-03
 * folgt mit WP-M2-42). Gezeigt wird genau der Stand, der bei Hold und Bestätigung gilt: Die Oberfläche hält Angebots-ID und Revision (ETag) und übergibt beide unverändert.
 * Preise sind Serverwerte und werden nie gerechnet oder gerundet. Hat sich das Angebot geändert (`REQUOTE_REQUIRED`, `412`) oder ist es abgelaufen, wird nie still bestätigt:
 * die Oberfläche zeigt den neuen Stand und verlangt die erneute Auswahl.
 */
export function OfferList({
  offerIds,
  origin,
  destination,
  zone,
  directSeconds,
  now = Date.now,
  onNewSearch,
}: OfferListProps) {
  const api = useApi();
  const commands = useCommands();
  const [shown, setShown] = useState<Shown[] | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ id: string; notice: Notice } | null>(null);
  const [booked, setBooked] = useState<{ booking: RideBooking; shown: Shown } | null>(null);
  const [tick, setTick] = useState(0);
  const [bookError, setBookError] = useState<ApiError | null>(null);
  const progress = useRef<Map<string, { holdId: string; revision: number }>>(new Map());
  const ids = offerIds.join(',');

  const readOne = useCallback(
    async (id: string): Promise<Shown> => {
      const offer = await api.call('getRideOffer', { path: { ride_offer_id: id } });
      const stop = (s: { stop_id: string }) =>
        api.call('getStop', { path: { stop_id: s.stop_id } }).then(
          (r) => ('stop_id' in r && 'accessibility' in r ? (r as GeoStopPassenger) : null),
          () => null,
        );
      const [pickup, dropoff] = await Promise.all([
        stop(offer.pickup_stop),
        stop(offer.dropoff_stop),
      ]);
      return { offer, pickup, dropoff };
    },
    [api],
  );

  useEffect(() => {
    let alive = true;
    Promise.all(ids.split(',').filter(Boolean).map(readOne)).then(
      (list) => alive && setShown(list),
      (e: unknown) => alive && setLoadError(e instanceof ApiError ? e : new ApiError(0)),
    );
    return () => {
      alive = false;
    };
  }, [ids, readOne]);

  // Ablauf während der Anzeige: sekündlich prüfen (kein Preis- oder Zeitwert wird dabei verändert)
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  void tick;

  const abgelaufen = (o: RideOffer) =>
    o.state === 'EXPIRED' || (o.hold === null && now() >= Date.parse(o.expires_at));

  /** Liest den aktuellen Stand neu und ersetzt die Anzeige; die Auswahl wird zurückgenommen (erneute Auswahl nötig). */
  const refresh = async (id: string, n: Notice) => {
    try {
      const fresh = await readOne(id);
      setShown((cur) => (cur ?? []).map((s) => (s.offer.id === id ? fresh : s)));
    } catch {
      // alter Stand bleibt sichtbar, die Meldung weist trotzdem auf die Änderung hin
    }
    setSelected(null);
    setNotice({ id, notice: n });
  };

  const buchen = async (current: Shown) => {
    const { offer } = current;
    const id = offer.id;
    setBookError(null);
    setNotice(null);
    try {
      let step = progress.current.get(id);
      if (!step) {
        const hold = await commands.run(`hold:${id}:${offer.revision}`, 'holdRideOffer', {
          path: { ride_offer_id: id },
          revision: offer.revision,
          body: { max_gross: offer.gross },
        });
        // ETag nach dem Hold = Angebotsrevision; der Stand muss unverändert sein, sonst wird nicht bestätigt
        const reread = await readOne(id);
        const after = reread.offer;
        const same =
          after.hold?.id === hold.id &&
          JSON.stringify([
            after.gross,
            after.fare_components,
            after.pickup_window,
            after.arrival_window,
            after.pickup_stop,
            after.dropoff_stop,
          ]) ===
            JSON.stringify([
              offer.gross,
              offer.fare_components,
              offer.pickup_window,
              offer.arrival_window,
              offer.pickup_stop,
              offer.dropoff_stop,
            ]);
        if (!same) {
          await commands
            .run(`release:${hold.id}`, 'releaseHold', {
              path: { ride_offer_id: id },
              revision: after.revision,
            })
            .catch(() => undefined);
          await refresh(id, {
            kind: 'changed',
            priceChanged: after.gross.amount_minor !== offer.gross.amount_minor,
          });
          return;
        }
        step = { holdId: hold.id, revision: after.revision };
        progress.current.set(id, step);
      }
      const booking = await commands.run(`confirm:${step.holdId}`, 'confirmBooking', {
        revision: step.revision,
        body: { hold_id: step.holdId, max_gross: offer.gross },
      });
      progress.current.delete(id);
      setBooked({ booking, shown: current });
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError(0);
      const code = err.problem?.code;
      if (err.status === 409 && (code === 'OFFER_EXPIRED' || code === 'HOLD_EXPIRED')) {
        progress.current.delete(id);
        await refresh(id, { kind: 'expired' });
      } else if (err.status === 409 && code === 'REQUOTE_REQUIRED') {
        progress.current.delete(id);
        await refresh(id, { kind: 'changed', priceChanged: true });
      } else if (err.status === 412 || (err.status === 409 && code === 'REVISION_MISMATCH')) {
        progress.current.delete(id);
        await refresh(id, { kind: 'changed', priceChanged: false });
      } else if (err.status === 409 && code === 'CAPACITY_UNAVAILABLE') {
        progress.current.delete(id);
        setSelected(null);
        setNotice({ id, notice: { kind: 'capacity' } });
      } else if (
        err.status === 0 ||
        err.status >= 500 ||
        (err.status === 409 && code === 'OPERATION_IN_PROGRESS')
      ) {
        // Ausgang unbekannt: weder Erfolg noch Ablehnung behaupten; „Stand prüfen“ wiederholt mit demselben Schlüssel
        setNotice({ id, notice: { kind: 'unclear' } });
      } else {
        setBookError(err);
      }
    }
  };

  if (loadError)
    return (
      <ErrorState
        error={loadError}
        action={
          <button type="button" onClick={onNewSearch}>
            {tx('angebot.neueSuche')}
          </button>
        }
      />
    );
  if (shown === null) return <p aria-busy="true">{tx('angebot.laedt')}</p>;

  if (booked) {
    const { booking, shown: s } = booked;
    const p = booking.promise;
    return (
      <section
        aria-labelledby="bestaetigt-h"
        data-testid="buchung-bestaetigt"
        data-booking-id={booking.id}
      >
        <h2 id="bestaetigt-h">{tx('angebot.bestaetigtTitel')}</h2>
        <p role="status">
          {tx('angebot.buchungsnummer')}:{' '}
          <strong data-testid="buchungsnummer">{booking.booking_number}</strong>
        </p>
        <dl>
          <dt>{tx('angebot.status')}</dt>
          <dd data-testid="buchung-status">{STATUS_TEXT[booking.state] ?? booking.state}</dd>
          <dt>{tx('angebot.abholpunkt')}</dt>
          <dd>{s.pickup?.name ?? ''}</dd>
          <dt>{tx('angebot.zielpunkt')}</dt>
          <dd>{s.dropoff?.name ?? ''}</dd>
          <dt>{tx('angebot.abholfenster')}</dt>
          <dd data-testid="buchung-abholfenster">
            {fenster({ earliest_at: p.pickup_earliest, latest_at: p.pickup_latest }, zone)}
          </dd>
          <dt>{tx('angebot.ankunftsfenster')}</dt>
          <dd data-testid="buchung-ankunftsfenster">
            {fenster({ earliest_at: p.arrival_earliest, latest_at: p.arrival_latest }, zone)}
          </dd>
          <dt>{tx('angebot.preis')}</dt>
          <dd data-testid="buchung-preis">{formatMoney(p.gross)}</dd>
        </dl>
        <p>
          <Link to="/reisen">{tx('angebot.zuReisen')}</Link>
        </p>
      </section>
    );
  }

  if (shown.length === 0)
    return (
      <p>
        {tx('angebot.keine')}{' '}
        <button type="button" onClick={onNewSearch}>
          {tx('angebot.neueSuche')}
        </button>
      </p>
    );

  return (
    <div>
      {bookError ? (
        <ErrorState
          error={bookError}
          action={
            <button type="button" onClick={onNewSearch}>
              {tx('angebot.neueSuche')}
            </button>
          }
        />
      ) : null}
      <ul className="tk-angebote" data-testid="angebotsliste">
        {shown.map((s) => {
          const o = s.offer;
          const expired = abgelaufen(o);
          const isSel = selected === o.id && !expired;
          const n = notice?.id === o.id ? notice.notice : null;
          const dauer = dauerMinuten(o, directSeconds?.[o.id]);
          const walkIn = origin && s.pickup ? fussweg(origin.point, s.pickup.point) : null;
          const walkOut =
            destination && s.dropoff ? fussweg(s.dropoff.point, destination.point) : null;
          const unbestaetigt = [s.pickup, s.dropoff].some(
            (st) =>
              st &&
              (st.accessibility.step_free === 'UNVERIFIED' ||
                st.accessibility.wheelchair_boarding === 'UNVERIFIED'),
          );
          const heading = `angebot-${o.id}`;
          return (
            <li key={o.id}>
              <article
                aria-labelledby={heading}
                data-testid="angebot"
                data-offer-id={o.id}
                data-revision={o.revision}
                data-expired={expired ? 'true' : 'false'}
              >
                <h2 id={heading}>
                  {s.pickup?.name ?? tx('angebot.abholpunkt')} →{' '}
                  {s.dropoff?.name ?? tx('angebot.zielpunkt')}
                </h2>
                {n?.kind === 'changed' ? (
                  <p role="alert" data-testid="hinweis-geaendert">
                    {tx('angebot.geaendert')}{' '}
                    {n.priceChanged ? (
                      <>
                        {tx('angebot.geaendertPreis')} <strong>{formatMoney(o.gross)}</strong>
                      </>
                    ) : null}
                  </p>
                ) : null}
                {n?.kind === 'capacity' ? (
                  <p role="alert">{tx('angebot.nichtMehrVerfuegbar')}</p>
                ) : null}
                {n?.kind === 'unclear' ? (
                  <p role="status" data-testid="hinweis-unklar">
                    {tx('angebot.unklar')}
                  </p>
                ) : null}
                {expired ? (
                  <p role="alert" data-testid="hinweis-abgelaufen">
                    <strong>{tx('angebot.abgelaufenTitel')}.</strong> {tx('angebot.abgelaufen')}{' '}
                    <button type="button" onClick={onNewSearch}>
                      {tx('angebot.neueSuche')}
                    </button>
                  </p>
                ) : null}
                <dl>
                  <dt>{tx('angebot.abholpunkt')}</dt>
                  <dd>{s.pickup?.name ?? '–'}</dd>
                  <dt>{tx('angebot.fussweg')} (Start)</dt>
                  <dd data-testid="fussweg-start">
                    {walkIn
                      ? `${walkIn.meter} m, ca. ${walkIn.minuten} min (${tx('angebot.fussnote')})`
                      : tx('angebot.fussUnbekannt')}
                  </dd>
                  <dt>{tx('angebot.zielpunkt')}</dt>
                  <dd>{s.dropoff?.name ?? '–'}</dd>
                  <dt>{tx('angebot.fussweg')} (Ziel)</dt>
                  <dd data-testid="fussweg-ziel">
                    {walkOut
                      ? `${walkOut.meter} m, ca. ${walkOut.minuten} min (${tx('angebot.fussnote')})`
                      : tx('angebot.fussUnbekannt')}
                  </dd>
                  <dt>{tx('angebot.abholfenster')}</dt>
                  <dd data-testid="abholfenster">{fenster(o.pickup_window, zone)}</dd>
                  <dt>{tx('angebot.ankunftsfenster')}</dt>
                  <dd data-testid="ankunftsfenster">{fenster(o.arrival_window, zone)}</dd>
                  <dt>{tx('angebot.fahrtdauer')}</dt>
                  <dd data-testid="fahrtdauer">
                    {dauer.ca ? 'ca. ' : ''}
                    {dauer.min} min
                  </dd>
                  <dt>{tx('angebot.preis')}</dt>
                  <dd data-testid="preis">{formatMoney(o.gross)}</dd>
                  <dt>{tx('angebot.gueltigBis')}</dt>
                  <dd>
                    <time dateTime={o.expires_at}>{ortszeit(o.expires_at, zone)}</time>
                  </dd>
                </dl>
                {unbestaetigt ? (
                  <p data-testid="barrierefreiheit-unbestaetigt">
                    {tx('angebot.barrierefreiheitUnbestaetigt')}
                  </p>
                ) : null}
                {isSel ? (
                  <section aria-label="Details und Bedingungen" data-testid="details">
                    <h3>{tx('angebot.preisAufschluesselung')}</h3>
                    <ul>
                      {o.fare_components.map((c, i) => (
                        <li key={`${c.code}-${i}`} data-code={c.code}>
                          {c.description}: {formatMoney(c.amount)}
                        </li>
                      ))}
                    </ul>
                    <p>
                      {tx('angebot.gesamtpreis')}: <strong>{formatMoney(o.gross)}</strong>
                    </p>
                    <h3>{tx('angebot.bedingungen')}</h3>
                    <ul>
                      {o.cancellation_policy_ref ? (
                        <li>
                          {tx('angebot.stornoregel')}: {o.cancellation_policy_ref}
                        </li>
                      ) : null}
                      {s.pickup ? (
                        <li>
                          {tx('angebot.stufenfrei')} ({s.pickup.name}):{' '}
                          {yesNo(s.pickup.accessibility.step_free)}; {tx('angebot.rollstuhl')}:{' '}
                          {yesNo(s.pickup.accessibility.wheelchair_boarding)}
                        </li>
                      ) : null}
                    </ul>
                    <p>{tx('angebot.vorhaltung')}</p>
                    <BusyButton onAction={() => buchen(s)}>
                      {`${tx('angebot.buchen')} – ${formatMoney(o.gross)}`}
                    </BusyButton>
                  </section>
                ) : null}
                {!expired && !isSel && n?.kind !== 'unclear' ? (
                  <button
                    type="button"
                    aria-label={`${tx('angebot.auswaehlen')}: ${s.pickup?.name ?? ''} → ${s.dropoff?.name ?? ''}, ${formatMoney(o.gross)}`}
                    onClick={() => {
                      setSelected(o.id);
                      setNotice(null);
                    }}
                  >
                    {tx('angebot.auswaehlen')}
                  </button>
                ) : null}
                {n?.kind === 'unclear' ? (
                  <BusyButton onAction={() => buchen(s)}>{tx('angebot.pruefen')}</BusyButton>
                ) : null}
              </article>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
