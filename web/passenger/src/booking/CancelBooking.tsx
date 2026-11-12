import { useState } from 'react';
import { ApiError } from '@taktovia/api-client';
import type { RideBooking, RideRefundHint } from '@taktovia/api-client';
import { BusyButton, ErrorState, useAnnounce } from '@taktovia/design-system';
import { Link } from 'react-router-dom';
import { formatMoney } from './geld';
import { ortszeit } from './Zeitanzeige';
import { useCommands } from '../api/useCommands';
import { tx } from '../texte';

const AKTIV: ReadonlySet<RideBooking['state']> = new Set(['CONFIRMING', 'CONFIRMED']);
const STORNIERT: ReadonlySet<RideBooking['state']> = new Set([
  'CANCELED',
  'CANCEL_PENDING',
  'COMPENSATING',
]);

/**
 * Erstattungshinweis in zwei Stufen (Kap. 06 §6.4): „Erstattung beauftragt“ bis zur PSP-Bestätigung, erst danach „erstattet“. Maßgeblich ist der Zahlungsblock der Buchung; der Hinweis der
 * Storno-Antwort gilt, solange der Zahlungsblock den Stand noch nicht trägt. Beträge sind Serverwerte.
 */
export function erstattungstext(b: RideBooking, hint?: RideRefundHint | null): string | null {
  const p = b.payment;
  const refund = p?.refund;
  if (refund) {
    if (refund.status === 'CONFIRMED')
      return `${tx('storno.hint.confirmed')} ${formatMoney(refund.refunded)}`;
    if (refund.status === 'REQUESTED')
      return `${tx('storno.hint.requested')} ${formatMoney(refund.requested)}`;
    if (refund.status === 'FAILED')
      return `${tx('storno.hint.failed')} ${formatMoney(refund.requested)}`;
    if (refund.status === 'PENDING_CAPTURE') return tx('storno.hint.pendingCapture');
    if (refund.status === 'NOT_DUE') return tx('storno.hint.notDue');
  }
  if (p?.status === 'VOIDED') return `${tx('storno.hint.voided')} ${formatMoney(p.authorized)}`;
  if (p?.status === 'VOID_PENDING') return `${tx('storno.hint.void')} ${formatMoney(p.authorized)}`;
  if (hint) {
    switch (hint.status) {
      case 'VOID_REQUESTED':
        return `${tx('storno.hint.void')} ${formatMoney(hint.amount)}`;
      case 'REQUESTED':
        return `${tx('storno.hint.requested')} ${formatMoney(hint.amount)}`;
      case 'CONFIRMED':
        return `${tx('storno.hint.confirmed')} ${formatMoney(hint.amount)}`;
      case 'PENDING_CAPTURE':
        return tx('storno.hint.pendingCapture');
      case 'NOT_DUE':
        return tx('storno.hint.notDue');
      case 'FAILED':
        return `${tx('storno.hint.failed')} ${formatMoney(hint.amount)}`;
      default:
        return tx('storno.hint.nichts');
    }
  }
  return null;
}

/** Erwartete Wirkung vor dem Storno, nach dem Zahlungsstand: nichts berechnet, Reservierung aufgehoben (Void) oder Erstattung nach der Regel. */
function erwartung(b: RideBooking): string {
  const p = b.payment;
  if (!p || p.status === 'CREATED' || p.status === 'AUTHORIZATION_FAILED')
    return tx('storno.erwartung.keine');
  if (p.status === 'AUTHORIZED' || p.status === 'REQUIRES_ACTION')
    return `${tx('storno.erwartung.void')} ${formatMoney(p.authorized)}`;
  return `${tx('storno.erwartung.refund')} ${formatMoney(p.captured)}`;
}

export interface CancelBookingProps {
  booking: RideBooking;
  zone: string;
  /** Liest die Buchung neu (nach Storno, veraltetem Stand oder Fristablauf). */
  onChanged: () => Promise<void> | void;
}

/**
 * UI-04 Selbststorno (WP-M2-46, LH-6.3-PAX-009): zeigt die geltende Regel (Frist, erwartete Wirkung auf die Zahlung), verlangt eine ausdrückliche Bestätigung mit Grundauswahl und zeigt danach
 * Status „storniert“ mit zweistufigem Erstattungshinweis. Die Aktion wird nur innerhalb der Regel angeboten (die Entscheidung trifft der Server beim Storno erneut); `If-Match` ist die gelesene
 * Buchungsrevision, der Idempotenzschlüssel gilt je Absicht und wird nach unbekanntem Ausgang unverändert wiederholt. Ablauf einstufig mit Regelanzeige vor der Bestätigung (OP-24-Annahme).
 */
export function CancelBooking({ booking, zone, onChanged }: CancelBookingProps) {
  const commands = useCommands();
  const announce = useAnnounce();
  const [offen, setOffen] = useState(false);
  const [grund, setGrund] = useState('PASSENGER_REQUEST');
  const [hinweis, setHinweis] = useState<RideRefundHint | null>(null);
  const [fertig, setFertig] = useState(false);
  const [meldung, setMeldung] = useState<'geaendert' | 'bereits' | 'nichtMehr' | 'unklar' | null>(
    null,
  );
  const [fehler, setFehler] = useState<ApiError | null>(null);

  const storniert = fertig || STORNIERT.has(booking.state);
  const regel = booking.cancellation;

  const stornieren = async () => {
    setFehler(null);
    setMeldung(null);
    try {
      const r = await commands.run(`cancel:${booking.id}:${booking.revision}`, 'cancelBooking', {
        path: { booking_id: booking.id },
        revision: booking.revision,
        body: { reason: grund as 'PASSENGER_REQUEST' },
      });
      setHinweis(r.refund_hint ?? null);
      setFertig(true);
      setOffen(false);
      announce(`${tx('storno.ergebnis')}. ${tx('storno.status')}`);
      await onChanged();
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError(0);
      const code = err.problem?.code;
      if (err.status === 409 && code === 'CANCEL_DEADLINE_EXPIRED') setMeldung('nichtMehr');
      else if (err.status === 412 || (err.status === 409 && code === 'REVISION_MISMATCH'))
        setMeldung('geaendert');
      else if (err.status === 409 && code === 'INVALID_STATE_TRANSITION') setMeldung('bereits');
      else if (
        err.status === 0 ||
        err.status >= 500 ||
        (err.status === 409 && code === 'OPERATION_IN_PROGRESS')
      )
        setMeldung('unklar');
      else setFehler(err);
      if (err.status !== 0 && err.status < 500 && code !== 'OPERATION_IN_PROGRESS') setOffen(false);
      if (err.status !== 0 && err.status < 500 && code !== 'OPERATION_IN_PROGRESS')
        await onChanged();
    }
  };

  if (storniert) {
    const text = erstattungstext(booking, hinweis);
    return (
      <section aria-labelledby="storno-h" data-testid="storno-ergebnis">
        <h3 id="storno-h">{tx('storno.ergebnis')}</h3>
        <p data-testid="storno-status">{tx('storno.status')}</p>
        {text ? <p data-testid="erstattungshinweis">{text}</p> : null}
      </section>
    );
  }
  if (!AKTIV.has(booking.state) || !regel) return null;

  const nichtMehr = !regel.allowed || meldung === 'nichtMehr';
  return (
    <section aria-labelledby="storno-h" data-testid="storno">
      <h3 id="storno-h">{tx('storno.titel')}</h3>
      {nichtMehr ? (
        <div data-testid="storno-nicht-mehr">
          <p role="alert">
            <strong>{tx('storno.nichtMehr')}.</strong> {tx('storno.nichtMehrText')}{' '}
            <Link to="/hilfe">{tx('nav.hilfe')}</Link>.
          </p>
        </div>
      ) : (
        <>
          <p data-testid="storno-regel">
            {tx('storno.frist')}{' '}
            <time dateTime={regel.deadline_at}>{ortszeit(regel.deadline_at, zone)}</time>{' '}
            {tx('storno.fristEnde')} {regel.deadline_min}
            {regel.deadline_defaulted ? `; ${tx('storno.fristDefault')}` : ''}).
          </p>
          <p data-testid="storno-erwartung">{erwartung(booking)}</p>
          {meldung === 'geaendert' ? (
            <p role="alert" data-testid="storno-geaendert">
              {tx('storno.geaendert')}
            </p>
          ) : null}
          {meldung === 'bereits' ? <p role="alert">{tx('storno.bereits')}</p> : null}
          {meldung === 'unklar' ? (
            <div data-testid="storno-unklar">
              <p role="status">{tx('storno.unklar')}</p>
              <BusyButton onAction={stornieren}>{tx('storno.pruefen')}</BusyButton>
            </div>
          ) : null}
          {fehler ? <ErrorState error={fehler} /> : null}
          {!offen && meldung !== 'unklar' ? (
            <button type="button" onClick={() => setOffen(true)} data-testid="storno-starten">
              {tx('storno.starten')}
            </button>
          ) : null}
          {offen && meldung !== 'unklar' ? (
            <div role="group" aria-labelledby="storno-frage" data-testid="storno-bestaetigung">
              <p id="storno-frage">{tx('storno.bestaetigenFrage')}</p>
              <p>
                <label>
                  {tx('storno.grund')}{' '}
                  <select value={grund} onChange={(e) => setGrund(e.target.value)}>
                    <option value="PASSENGER_REQUEST">{tx('storno.grundEigen')}</option>
                  </select>
                </label>
              </p>
              <BusyButton onAction={stornieren} data-testid="storno-bestaetigen">
                {tx('storno.bestaetigen')}
              </BusyButton>{' '}
              <button type="button" onClick={() => setOffen(false)}>
                {tx('storno.abbrechen')}
              </button>
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}
