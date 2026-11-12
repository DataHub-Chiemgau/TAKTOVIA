import { useEffect, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { RideOffer, RidePlace } from '@taktovia/api-client';
import { ErrorState } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { leseAngebot } from './angebotLesen';
import type { Shown } from './angebotLesen';
import { formatMoney } from './geld';
import { fussweg } from './fussweg';
import { fenster, ortszeit } from './Zeitanzeige';
import { tx } from '../texte';

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

/**
 * UI-02 Angebotsliste und Angebotsdetails (WP-M2-41, LH-6.3-PAX-005). Gezeigt wird genau der Stand des Angebots (Serverwerte, nie gerechnet); die Auswahl führt zum
 * Checkout UI-03 (`/checkout/:offerId`, WP-M2-42), der Hold, Bestätigung und Zahlung führt und den Stand erneut prüft.
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
  const [shown, setShown] = useState<Shown[] | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const ids = offerIds.join(',');

  useEffect(() => {
    let alive = true;
    Promise.all(
      ids
        .split(',')
        .filter(Boolean)
        .map((id) => leseAngebot(api, id)),
    ).then(
      (list) => alive && setShown(list),
      (e: unknown) => alive && setLoadError(e instanceof ApiError ? e : new ApiError(0)),
    );
    return () => {
      alive = false;
    };
  }, [ids, api]);

  // Ablauf während der Anzeige: sekündlich prüfen (kein Preis- oder Zeitwert wird dabei verändert)
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  void tick;

  const abgelaufen = (o: RideOffer) =>
    o.state === 'EXPIRED' || (o.hold === null && now() >= Date.parse(o.expires_at));

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
      <ul className="tk-angebote" data-testid="angebotsliste">
        {shown.map((s) => {
          const o = s.offer;
          const expired = abgelaufen(o);
          const isSel = selected === o.id && !expired;
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
                    <Link
                      to={`/checkout/${o.id}`}
                      data-testid="zum-checkout"
                      aria-label={`${tx('angebot.zumCheckout')}: ${s.pickup?.name ?? ''} → ${s.dropoff?.name ?? ''}, ${formatMoney(o.gross)}`}
                    >
                      {tx('angebot.zumCheckout')}
                    </Link>
                  </section>
                ) : null}
                {!expired && !isSel ? (
                  <button
                    type="button"
                    aria-label={`${tx('angebot.auswaehlen')}: ${s.pickup?.name ?? ''} → ${s.dropoff?.name ?? ''}, ${formatMoney(o.gross)}`}
                    onClick={() => {
                      setSelected(o.id);
                    }}
                  >
                    {tx('angebot.auswaehlen')}
                  </button>
                ) : null}
              </article>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
