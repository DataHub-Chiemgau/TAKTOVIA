import type { RideBooking } from '@taktovia/api-client';

/**
 * Auswertung des Standes einer Buchung im Checkout (WP-M2-42, Zustandsdiagramm in `docs/umsetzungsplan/vertraege/checkout-zustaende.md`). Die Oberfläche
 * entscheidet nie selbst über Erfolg oder Misserfolg einer Zahlung: maßgeblich ist der vom Server gelieferte Buchungs- und Zahlstatus (Leitlinie 6).
 */
export type Beobachtung =
  | { kind: 'pending' }
  | { kind: 'sca' }
  | { kind: 'success' }
  | { kind: 'declined' }
  | { kind: 'holdLost' }
  | { kind: 'unclear' };

const ENDE: ReadonlySet<RideBooking['state']> = new Set([
  'CANCELED',
  'FAILED',
  'COMPENSATING',
  'CANCEL_PENDING',
]);

/** Fehlercodes der Zahlung, die bedeuten: Frist oder Reservierung waren nicht mehr gültig (nicht: Zahlungsmittel abgelehnt). */
const FRIST_CODES: ReadonlySet<string> = new Set([
  'AUTHORIZATION_EXPIRED',
  'BOOKING_RELEASED',
  'AMOUNT_MISMATCH',
]);

export function beobachte(b: RideBooking): Beobachtung {
  const pay = b.payment?.status;
  if (b.state === 'CONFIRMED' || b.state === 'COMPLETED') return { kind: 'success' };
  if (ENDE.has(b.state)) {
    if (b.payment?.failure_code && FRIST_CODES.has(b.payment.failure_code))
      return { kind: 'holdLost' };
    if (pay === 'AUTHORIZATION_FAILED' || b.cancel_reason === 'PAYMENT_FAILED')
      return { kind: 'declined' };
    return { kind: 'holdLost' };
  }
  if (pay === 'REQUIRES_ACTION') return { kind: 'sca' };
  if (pay === 'UNKNOWN' || b.state === 'RECONCILING') return { kind: 'unclear' };
  return { kind: 'pending' };
}
