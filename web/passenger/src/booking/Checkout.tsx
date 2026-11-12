import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { RideBooking } from '@taktovia/api-client';
import { BusyButton, ErrorState, useAnnounce } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { Countdown, restSekunden } from './Countdown';
import { beobachte } from './checkoutAblauf';
import { leseAngebot, bindungsStand } from './angebotLesen';
import type { Shown } from './angebotLesen';
import { formatMoney } from './geld';
import { SimulatorPsp } from './PspComponent';
import type { PspComponent } from './PspComponent';
import { fenster } from './Zeitanzeige';
import { useCommands } from '../api/useCommands';
import { tx } from '../texte';
import type { TextKey } from '../texte';

type Phase =
  | { k: 'review' }
  | { k: 'working'; step: 'hold' | 'confirm' | 'pay' | 'wait' }
  | { k: 'sca'; booking: RideBooking }
  | { k: 'success'; booking: RideBooking }
  | { k: 'declined'; booking: RideBooking }
  | { k: 'holdLost'; booking: RideBooking | null }
  | { k: 'unclear'; booking: RideBooking | null }
  | { k: 'expired' }
  | { k: 'capacity' };

export interface CheckoutProps {
  offerId: string;
  zone: string;
  now?: (() => number) | undefined;
  /** Abstand der Statusabfragen nach dem Start der Zahlung (ms) und deren Höchstzahl, danach „Bestätigung wird geprüft“. */
  pollMs?: number;
  maxPolls?: number;
  psp?: PspComponent;
  onNewSearch: () => void;
}

const SHOWN_PAY: Partial<Record<NonNullable<RideBooking['payment']>['status'], TextKey>> = {
  CREATED: 'checkout.zahl.created',
  REQUIRES_ACTION: 'checkout.zahl.sca',
  AUTHORIZED: 'checkout.zahl.authorized',
  AUTHORIZATION_FAILED: 'checkout.zahl.failed',
  CAPTURE_PENDING: 'checkout.zahl.capturePending',
  CAPTURED: 'checkout.zahl.captured',
  VOID_PENDING: 'checkout.zahl.voidPending',
  VOIDED: 'checkout.zahl.voided',
  UNKNOWN: 'checkout.zahl.unknown',
};

export function zahlstatusText(b: RideBooking): string {
  const key = b.payment ? SHOWN_PAY[b.payment.status] : undefined;
  return key ? tx(key) : tx('checkout.zahl.keine');
}

const wait = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * UI-03 Checkout (WP-M2-42, Kap. 06 §6.3). Ablauf: Prüfen (Zusammenfassung, Preisaufschlüsselung, Stornoregel, PSP-Zahlungsauswahl, getrennte Pflichtzustimmung)
 * → Hold („Plätze werden reserviert“) → Bestätigung → Zahlung (Operation) → Statusbeobachtung bis zu einem eindeutigen Ergebnis. Preis und Betrag sind Serverwerte
 * (nie berechnet). Jede Absicht trägt einen Idempotenzschlüssel, der bei unbekanntem Ausgang unverändert wiederholt wird; Neuladen setzt am Serverstand auf
 * (bestehender Hold, bestehende Buchung), nie mit einer zweiten Buchung. Zustandsdiagramm: `docs/umsetzungsplan/vertraege/checkout-zustaende.md`.
 */
export function Checkout({
  offerId,
  zone,
  now = Date.now,
  pollMs = 2000,
  maxPolls = 30,
  psp: Psp = SimulatorPsp,
  onNewSearch,
}: CheckoutProps) {
  const api = useApi();
  const commands = useCommands();
  const announce = useAnnounce();
  const [shown, setShown] = useState<Shown | null>(null);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [phase, setPhase] = useState<Phase>({ k: 'review' });
  const [method, setMethod] = useState<string | null>(null);
  const [agree, setAgree] = useState(false);
  const [priceAck, setPriceAck] = useState(false);
  const [changedPrice, setChangedPrice] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [tick, setTick] = useState(0);
  const progress = useRef<{ holdId?: string; holdRevision?: number; booking?: RideBooking }>({});
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  useEffect(() => {
    let live = true;
    leseAngebot(api, offerId).then(
      (s) => live && setShown(s),
      (e: unknown) => live && setLoadError(e instanceof ApiError ? e : new ApiError(0)),
    );
    return () => {
      live = false;
    };
  }, [api, offerId]);

  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, []);
  void tick;

  const refresh = useCallback(async () => {
    try {
      const fresh = await leseAngebot(api, offerId);
      if (alive.current) setShown(fresh);
      return fresh;
    } catch {
      return null;
    }
  }, [api, offerId]);

  /** Bestehende Buchung zu einem Hold (Neuladen oder zweiter Tab): die Bestätigung desselben Holds liefert dieselbe Buchung. */
  const zeigeStand = (p: Phase) => {
    if (!alive.current) return;
    setPhase(p);
    if (p.k === 'success') announce(tx('checkout.erfolg.ansage'));
  };

  const beobachten = async (booking: RideBooking): Promise<Phase> => {
    let current = booking;
    for (let i = 0; i < maxPolls; i += 1) {
      const o = beobachte(current);
      if (o.kind === 'success') return { k: 'success', booking: current };
      if (o.kind === 'declined') return { k: 'declined', booking: current };
      if (o.kind === 'holdLost') return { k: 'holdLost', booking: current };
      if (o.kind === 'sca') return { k: 'sca', booking: current };
      if (o.kind === 'unclear') return { k: 'unclear', booking: current };
      await wait(pollMs);
      if (!alive.current) return { k: 'unclear', booking: current };
      try {
        current = await api.call('getBooking', { path: { booking_id: booking.id } });
        progress.current.booking = current;
      } catch {
        return { k: 'unclear', booking: current };
      }
    }
    return { k: 'unclear', booking: current };
  };

  const zahlen = async (booking: RideBooking, amountOffer: Shown['offer']) => {
    // Den vollständigen Stand lesen: die Antwort der Bestätigung enthält keinen Zahlungsblock. Eine bereits begonnene Zahlung (Neuladen, zweiter Tab)
    // wird nur beobachtet; ein zweiter Start würde ohnehin die laufende Operation liefern, bleibt aber unterlassen.
    let current = await api.call('getBooking', { path: { booking_id: booking.id } });
    progress.current.booking = current;
    if (current.state === 'CONFIRMING' && !current.payment) {
      setPhase({ k: 'working', step: 'pay' });
      await commands.run(`pay:${booking.id}`, 'startBookingPayment', {
        path: { booking_id: booking.id },
        body: { payment_method_ref: method ?? '', amount: amountOffer.gross },
      });
      current = await api.call('getBooking', { path: { booking_id: booking.id } });
      progress.current.booking = current;
    }
    setPhase({ k: 'working', step: 'wait' });
    zeigeStand(await beobachten(current));
  };

  const buchen = async () => {
    if (!shown) return;
    const { offer } = shown;
    setError(null);
    setChangedPrice(null);
    try {
      let holdId = progress.current.holdId;
      let revision = progress.current.holdRevision;
      if (!holdId) {
        if (offer.hold && offer.hold.state === 'HELD') {
          holdId = offer.hold.id;
          revision = offer.revision;
        } else {
          setPhase({ k: 'working', step: 'hold' });
          const hold = await commands.run(`hold:${offerId}:${offer.revision}`, 'holdRideOffer', {
            path: { ride_offer_id: offerId },
            revision: offer.revision,
            body: { max_gross: offer.gross },
          });
          // ETag nach dem Hold = Angebotsrevision; der Stand muss unverändert sein, sonst wird nicht bestätigt
          const reread = await leseAngebot(api, offerId);
          if (
            reread.offer.hold?.id !== hold.id ||
            bindungsStand(reread.offer) !== bindungsStand(offer)
          ) {
            await commands
              .run(`release:${hold.id}`, 'releaseHold', {
                path: { ride_offer_id: offerId },
                revision: reread.offer.revision,
              })
              .catch(() => undefined);
            await geaendert(reread.offer.gross.amount_minor !== offer.gross.amount_minor);
            return;
          }
          holdId = hold.id;
          revision = reread.offer.revision;
          setShown(reread);
        }
        progress.current.holdId = holdId;
        progress.current.holdRevision = revision;
      }
      let booking = progress.current.booking;
      if (!booking) {
        setPhase({ k: 'working', step: 'confirm' });
        booking = await commands.run(`confirm:${holdId}`, 'confirmBooking', {
          revision: revision ?? offer.revision,
          body: { hold_id: holdId, max_gross: offer.gross },
        });
        progress.current.booking = booking;
      }
      await zahlen(booking, offer);
    } catch (e) {
      await fehler(e);
    }
  };

  const geaendert = async (priceChanged: boolean) => {
    progress.current = {};
    const fresh = await refresh();
    setAgree(false);
    setPriceAck(false);
    setChangedPrice(priceChanged && fresh ? formatMoney(fresh.offer.gross) : '');
    setPhase({ k: 'review' });
  };

  const fehler = async (e: unknown) => {
    const err = e instanceof ApiError ? e : new ApiError(0);
    const code = err.problem?.code;
    const bookingNow = progress.current.booking ?? null;
    if (err.status === 409 && (code === 'OFFER_EXPIRED' || code === 'HOLD_EXPIRED')) {
      progress.current = {};
      await refresh();
      zeigeStand(bookingNow ? { k: 'holdLost', booking: bookingNow } : { k: 'expired' });
    } else if (err.status === 409 && code === 'REQUOTE_REQUIRED') {
      await geaendert(true);
    } else if (err.status === 412 || (err.status === 409 && code === 'REVISION_MISMATCH')) {
      await geaendert(false);
    } else if (err.status === 409 && code === 'CAPACITY_UNAVAILABLE') {
      progress.current = {};
      setPhase({ k: 'capacity' });
    } else if (
      err.status === 0 ||
      err.status >= 500 ||
      (err.status === 409 && code === 'OPERATION_IN_PROGRESS')
    ) {
      // Ausgang unbekannt: weder Erfolg noch Ablehnung behaupten; „Stand prüfen“ wiederholt mit denselben Schlüsseln
      setPhase({ k: 'unclear', booking: bookingNow });
    } else {
      setError(err);
      setPhase({ k: 'review' });
    }
  };

  /** SCA-Rückkehr: stellt nur einen Abgleich ein; erst der verifizierte Status ändert die Anzeige. */
  const zurueckVonSca = async (booking: RideBooking) => {
    setError(null);
    try {
      setPhase({ k: 'working', step: 'wait' });
      await commands.run(`return:${booking.id}`, 'returnBookingPayment', {
        path: { booking_id: booking.id },
      });
      const fresh = await api.call('getBooking', { path: { booking_id: booking.id } });
      progress.current.booking = fresh;
      zeigeStand(await beobachten(fresh));
    } catch (e) {
      await fehler(e);
    }
  };

  /** „Stand prüfen“ nach unklarem Ergebnis: setzt dort fort, wo der Ablauf stand, mit denselben Idempotenzschlüsseln. */
  const pruefen = async () => {
    setError(null);
    const b = progress.current.booking;
    try {
      if (b) {
        const fresh = await api.call('getBooking', { path: { booking_id: b.id } });
        progress.current.booking = fresh;
        if (shown && fresh.state === 'CONFIRMING' && !fresh.payment)
          await zahlen(fresh, shown.offer);
        else zeigeStand(await beobachten(fresh));
      } else {
        await buchen();
      }
    } catch (e) {
      await fehler(e);
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

  const { offer, pickup, dropoff } = shown;
  const betrag = formatMoney(offer.gross);
  const hold = offer.hold && offer.hold.state === 'HELD' ? offer.hold : null;
  const nowMs = now();
  const holdRest = hold ? restSekunden(hold.expires_at, nowMs) : null;
  const angebotAbgelaufen = hold === null && nowMs >= Date.parse(offer.expires_at);

  const neueSuche = (
    <button type="button" onClick={onNewSearch}>
      {tx('angebot.neueSuche')}
    </button>
  );
  const kontakt = (
    <p>
      {tx('checkout.kontakt')} <Link to="/hilfe">{tx('nav.hilfe')}</Link>
    </p>
  );

  if (phase.k === 'success') {
    const b = phase.booking;
    return (
      <section aria-labelledby="co-erfolg" data-testid="checkout-erfolg" data-booking-id={b.id}>
        <h2 id="co-erfolg">{tx('angebot.bestaetigtTitel')}</h2>
        <dl>
          <dt>{tx('angebot.buchungsnummer')}</dt>
          <dd data-testid="buchungsnummer">{b.booking_number}</dd>
          <dt>{tx('angebot.status')}</dt>
          <dd data-testid="buchung-status">{tx('angebot.statusConfirmed')}</dd>
          <dt>{tx('checkout.zahlungsstatus')}</dt>
          <dd data-testid="zahlungsstatus">{zahlstatusText(b)}</dd>
          <dt>{tx('angebot.abholpunkt')}</dt>
          <dd>{pickup?.name ?? '–'}</dd>
          <dt>{tx('angebot.abholfenster')}</dt>
          <dd data-testid="buchung-abholfenster">
            {fenster(
              { earliest_at: b.promise.pickup_earliest, latest_at: b.promise.pickup_latest },
              zone,
            )}
          </dd>
          <dt>{tx('angebot.preis')}</dt>
          <dd data-testid="buchung-preis">{formatMoney(b.promise.gross)}</dd>
        </dl>
        <p>
          <Link to={`/reisen/${b.id}`}>{tx('checkout.zurReise')}</Link>
        </p>
      </section>
    );
  }
  if (phase.k === 'declined')
    return (
      <section aria-labelledby="co-abl" data-testid="checkout-abgelehnt">
        <h2 id="co-abl">{tx('checkout.abgelehnt.titel')}</h2>
        <p role="alert">{tx('checkout.abgelehnt.text')}</p>
        <p data-testid="zahlungsstatus">{zahlstatusText(phase.booking)}</p>
        {neueSuche}
      </section>
    );
  if (phase.k === 'holdLost')
    return (
      <section aria-labelledby="co-hold" data-testid="checkout-holdverlust">
        <h2 id="co-hold">{tx('checkout.holdverlust.titel')}</h2>
        <p role="alert">{tx('checkout.holdverlust.text')}</p>
        {phase.booking ? (
          <p data-testid="zahlungsstatus">
            {tx('checkout.zahlungsstatus')}: {zahlstatusText(phase.booking)}
          </p>
        ) : null}
        {neueSuche}
      </section>
    );
  if (phase.k === 'expired')
    return (
      <section aria-labelledby="co-exp" data-testid="checkout-abgelaufen">
        <h2 id="co-exp">{tx('angebot.abgelaufenTitel')}</h2>
        <p role="alert">{tx('angebot.abgelaufen')}</p>
        {neueSuche}
      </section>
    );
  if (phase.k === 'capacity')
    return (
      <section aria-labelledby="co-cap" data-testid="checkout-kapazitaet">
        <h2 id="co-cap">{tx('checkout.kapazitaet.titel')}</h2>
        <p role="alert">{tx('angebot.nichtMehrVerfuegbar')}</p>
        {neueSuche}
      </section>
    );
  if (phase.k === 'unclear')
    return (
      <section aria-labelledby="co-unk" data-testid="checkout-unklar">
        <h2 id="co-unk">{tx('checkout.unklar.titel')}</h2>
        <p role="status" data-testid="hinweis-unklar">
          {tx('checkout.unklar.text')}
        </p>
        <p>
          {tx('angebot.preis')}: <strong>{betrag}</strong>
        </p>
        {kontakt}
        <BusyButton onAction={pruefen}>{tx('angebot.pruefen')}</BusyButton>
      </section>
    );
  if (phase.k === 'sca')
    return (
      <section aria-labelledby="co-sca" data-testid="checkout-sca">
        <h2 id="co-sca">{tx('checkout.sca.titel')}</h2>
        <p role="status">{tx('checkout.sca.text')}</p>
        {hold ? <Countdown until={hold.expires_at} now={now} /> : null}
        {holdRest !== null && holdRest <= 0 ? (
          <p role="alert" data-testid="hold-abgelaufen-hinweis">
            {tx('checkout.holdAblaufWaehrend')}
          </p>
        ) : null}
        <BusyButton onAction={() => zurueckVonSca(phase.booking)}>
          {tx('checkout.sca.zurueck')}
        </BusyButton>{' '}
        {neueSuche}
      </section>
    );

  const arbeitet = phase.k === 'working';
  const kannBuchen =
    agree && method !== null && (changedPrice === null || changedPrice === '' || priceAck);
  const schritt = arbeitet
    ? phase.step === 'hold'
      ? tx('checkout.reserviert')
      : tx('angebot.bucht')
    : null;
  return (
    <section aria-labelledby="co-titel" data-testid="checkout" data-revision={offer.revision}>
      <h2 id="co-titel">{tx('checkout.pruefen')}</h2>
      {angebotAbgelaufen ? (
        <p role="alert" data-testid="hinweis-abgelaufen">
          <strong>{tx('angebot.abgelaufenTitel')}.</strong> {tx('angebot.abgelaufen')} {neueSuche}
        </p>
      ) : null}
      {changedPrice !== null ? (
        <p role="alert" data-testid="hinweis-geaendert">
          {tx('angebot.geaendert')}{' '}
          {changedPrice ? (
            <>
              {tx('angebot.geaendertPreis')} <strong>{changedPrice}</strong>
            </>
          ) : null}
        </p>
      ) : null}
      {schritt ? (
        <p role="status" data-testid="checkout-fortschritt">
          {schritt}
        </p>
      ) : null}
      {hold ? <Countdown until={hold.expires_at} now={now} /> : null}
      {hold && holdRest !== null && holdRest <= 0 ? (
        <p role="alert" data-testid="hold-abgelaufen-hinweis">
          {tx('checkout.holdAblaufWaehrend')} {neueSuche}
        </p>
      ) : null}
      {error ? <ErrorState error={error} /> : null}
      <h3>{tx('checkout.zusammenfassung')}</h3>
      <dl>
        <dt>{tx('angebot.abholpunkt')}</dt>
        <dd>{pickup?.name ?? '–'}</dd>
        <dt>{tx('angebot.zielpunkt')}</dt>
        <dd>{dropoff?.name ?? '–'}</dd>
        <dt>{tx('angebot.abholfenster')}</dt>
        <dd data-testid="abholfenster">{fenster(offer.pickup_window, zone)}</dd>
        <dt>{tx('angebot.ankunftsfenster')}</dt>
        <dd data-testid="ankunftsfenster">{fenster(offer.arrival_window, zone)}</dd>
        <dt>{tx('angebot.preis')}</dt>
        <dd data-testid="preis">{betrag}</dd>
      </dl>
      <h3>{tx('angebot.preisAufschluesselung')}</h3>
      <ul data-testid="preisaufschluesselung">
        {offer.fare_components.map((c, i) => (
          <li key={`${c.code}-${i}`} data-code={c.code}>
            {c.description}: {formatMoney(c.amount)}
          </li>
        ))}
      </ul>
      <p>
        {tx('angebot.gesamtpreis')}: <strong>{betrag}</strong>
      </p>
      <h3>{tx('angebot.bedingungen')}</h3>
      <p data-testid="stornoregel">
        {tx('angebot.stornoregel')}: {offer.cancellation_policy_ref ?? '–'}
      </p>
      <Psp value={method} onChange={setMethod} disabled={arbeitet} />
      <p>
        <label>
          <input
            type="checkbox"
            checked={agree}
            disabled={arbeitet}
            onChange={(e) => setAgree(e.target.checked)}
          />{' '}
          {tx('checkout.zustimmung')}
        </label>
      </p>
      {changedPrice ? (
        <p>
          <label>
            <input
              type="checkbox"
              checked={priceAck}
              disabled={arbeitet}
              onChange={(e) => setPriceAck(e.target.checked)}
            />{' '}
            {tx('checkout.preisZustimmung')} {changedPrice}
          </label>
        </p>
      ) : null}
      <p>{tx('angebot.vorhaltung')}</p>
      <BusyButton
        locked={arbeitet || !kannBuchen || angebotAbgelaufen}
        onAction={buchen}
        data-testid="zahlungspflichtig-buchen"
      >
        {`${tx('checkout.buchen')} – ${betrag}`}
      </BusyButton>
      {!kannBuchen && !arbeitet ? (
        <p data-testid="checkout-voraussetzung">{tx('checkout.voraussetzung')}</p>
      ) : null}
    </section>
  );
}
