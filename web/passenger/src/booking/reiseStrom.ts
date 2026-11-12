import type { PassengerPlanChange, RideBooking } from '@taktovia/api-client';

/**
 * Anwendung des Änderungsstroms der Planrevisionen auf die geöffnete Buchung (WP-M2-43, Protokoll aus WP-M2-28): Ein Eintrag wird nur übernommen, wenn seine `sequence` genau eins über
 * dem bekannten Stand liegt; gleiche oder ältere (doppelt oder ungeordnet zugestellt) werden verworfen, eine Lücke verlangt den Snapshot (`getBooking`). Der Stand wird nie auf eine ältere
 * Revision zurückgesetzt.
 */
export type Entscheidung = 'apply' | 'ignore' | 'snapshot';

export function entscheide(bekannt: number, sequence: number): Entscheidung {
  if (sequence <= bekannt) return 'ignore';
  return sequence === bekannt + 1 ? 'apply' : 'snapshot';
}

export interface EtaAenderung {
  von: string | null;
  nach: string | null;
}

export interface Angewendet {
  booking: RideBooking;
  /** Die ETA hat sich verändert (alt zu neu); `null` bei Ausfall des Plans, ADDED oder unveränderter Zeit. */
  aenderung: EtaAenderung | null;
  /** Der Eintrag verlangt den Snapshot (Haltepunkt geändert oder Lücke). */
  snapshot: boolean;
}

/** Wendet einen Eintrag des Stroms für die eigene Buchung an; fremde Buchungen (andere ID) ändern nichts. */
export function wendeAn(
  b: RideBooking,
  bekannt: number,
  entry: PassengerPlanChange,
): { next: Angewendet; bekannt: number } {
  const mine = entry.bookings.find((x) => x.booking_id === b.id);
  if (!mine) return { next: { booking: b, aenderung: null, snapshot: false }, bekannt };
  const d = entscheide(bekannt, mine.sequence);
  if (d === 'ignore') return { next: { booking: b, aenderung: null, snapshot: false }, bekannt };
  // Haltepunktwechsel und erstes Erscheinen im Plan (Fahrzeugzuordnung) verlangen den vollständigen Stand
  if (d === 'snapshot' || mine.kind === 'STOP_CHANGED' || mine.kind === 'ADDED')
    return { next: { booking: b, aenderung: null, snapshot: true }, bekannt };
  if (mine.kind === 'REMOVED') {
    const { eta: _eta, vehicle: _vehicle, ...rest } = b;
    void _eta;
    void _vehicle;
    return { next: { booking: rest, aenderung: null, snapshot: false }, bekannt: mine.sequence };
  }
  const alt = b.eta?.pickup_at ?? mine.pickup_before;
  const eta = {
    pickup_at: mine.pickup_after ?? b.eta?.pickup_at ?? entry.recorded_at,
    dropoff_at: mine.dropoff_after,
    previous_pickup_at: mine.kind === 'TIME_CHANGED' ? mine.pickup_before : null,
    plan_sequence: mine.sequence,
    plan_revision: b.eta?.plan_revision ?? 1,
    data_as_of: entry.recorded_at,
    source: 'PLAN' as const,
  };
  const geaendert = alt !== null && alt !== eta.pickup_at;
  return {
    next: {
      booking: { ...b, eta },
      aenderung: geaendert ? { von: alt, nach: eta.pickup_at } : null,
      snapshot: false,
    },
    bekannt: mine.sequence,
  };
}
