import { ApiError, useApi } from '@taktovia/api-client';
import type { DispatchReasonCode, OperatorBookingRow, RideOffer } from '@taktovia/api-client';
import { useState } from 'react';

/** Grundkatalog der Leitstelle für Anlage und Änderung (Arbeitskatalog, WP-M3-39). */
export const REASON_LABELS: Readonly<Record<DispatchReasonCode, string>> = {
  PHONE_REQUEST: 'Telefonische Anfrage',
  COUNTER_REQUEST: 'Anfrage am Schalter',
  PASSENGER_REQUEST: 'Wunsch des Fahrgasts',
  OPERATIONAL_NEED: 'Betriebliche Notwendigkeit',
  CORRECTION: 'Korrektur einer Angabe',
  OTHER: 'Sonstiges (Freitext nötig)',
};

/** Stornogründe, die die Disposition setzen darf (Katalog `BookingReason`, Betriebsgründe). */
const CANCEL_REASONS: Readonly<Record<string, string>> = {
  PASSENGER_REQUEST: 'Wunsch des Fahrgasts',
  OPERATOR_CANCELLATION: 'Storno durch den Betrieb',
  SAFETY: 'Sicherheitsgrund',
  VEHICLE_OUTAGE: 'Fahrzeugausfall',
};

export type EditorMode = 'create' | 'change' | 'cancel';

const money = (m: { amount_minor: number; currency: string; exponent: number }): string =>
  `${(m.amount_minor / 10 ** m.exponent).toFixed(m.exponent).replace('.', ',')} ${m.currency}`;
const timeOf = (iso: string): string =>
  new Date(iso).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' });

/** Verständliche Meldung zu einem Fehler der Leitstellenbuchung; ein veralteter Stand wird nie still überschrieben (412). */
export function problemText(e: unknown): string {
  if (!(e instanceof ApiError))
    return 'Die Verbindung ist unterbrochen. Bitte versuchen Sie es erneut.';
  switch (e.status) {
    case 412:
      return 'Die Buchung wurde zwischenzeitlich geändert. Laden Sie die Liste neu und prüfen Sie den aktuellen Stand; es wurde nichts überschrieben.';
    case 403:
    case 404:
      return 'Dafür fehlt Ihnen das Recht, oder die Buchung gehört nicht zu Ihren Diensten.';
    case 409:
      return e.problem?.code === 'CAPACITY_UNAVAILABLE'
        ? 'Für diesen Wunsch ist keine Kapazität mehr frei. Die bisherige Buchung bleibt unverändert.'
        : 'Der Vorgang ist nicht mehr möglich (Konflikt). Die bisherige Buchung bleibt unverändert.';
    case 422:
      return 'Die Angaben sind unvollständig oder ungültig (Grund, Fahrgast, Zustimmung).';
    default:
      return 'Der Vorgang ist fehlgeschlagen. Die bisherige Buchung bleibt unverändert.';
  }
}

/**
 * UI-07 „Eigene Buchung bearbeiten“ (WP-M3-39, LH-6.7-OPS-004): Anlegen und Ändern in den Schritten Angebot einholen, reservieren, bestätigen sowie Stornieren, jeweils mit Pflichtgrund und
 * Folgenanzeige. Ohne Grund ist keine Aktion auslösbar. Bei Preis- oder Zeitänderung verlangt der Server die Zustimmung des Fahrgasts (Kanal); die Oberfläche fragt sie ab, sobald sie nötig ist.
 * Die Aktionen erscheinen nur mit dem Recht (die Bildschirmlogik entscheidet der Aufrufer; der Server prüft jede Anfrage erneut). Mindestumfang bis zur Entscheidung D-12/OP-30.
 */
export function BookingEditor({
  mode,
  row,
  onDone,
}: {
  mode: EditorMode;
  row?: OperatorBookingRow;
  onDone: (message: string) => void;
}) {
  const api = useApi();
  const [reason, setReason] = useState<string>('');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [offer, setOffer] = useState<RideOffer | undefined>(undefined);
  const [needsConsent, setNeedsConsent] = useState(false);
  const [consent, setConsent] = useState('');
  const [form, setForm] = useState({
    passenger: row?.passenger_ref ?? '',
    service: row?.service_id ?? '',
    olat: '51.05',
    olon: '13.74',
    dlat: '51.08',
    dlon: '13.78',
    at: '',
    persons: String(row?.passengers ?? 1),
  });

  const reasonMissing = reason === '' || (reason === 'OTHER' && text.trim().length < 3);
  const set = (k: keyof typeof form, v: string) => setForm({ ...form, [k]: v });

  const fail = (e: unknown) => {
    setError(problemText(e));
    setBusy(false);
  };

  const cancel = async () => {
    if (!row || reasonMissing) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.call('cancelBooking', {
        path: { booking_id: row.id },
        revision: row.revision,
        body: { reason: reason as never, ...(text.trim() ? { reason_text: text.trim() } : {}) },
      });
      onDone(
        `Die Buchung ${row.booking_number} wurde storniert. Die Reservierung ist freigegeben.`,
      );
    } catch (e) {
      fail(e);
    }
  };

  const getOffer = async () => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await api.call('createRideOffer', {
        body: {
          service_id: form.service,
          origin: { point: { latitude: Number(form.olat), longitude: Number(form.olon) } },
          destination: { point: { latitude: Number(form.dlat), longitude: Number(form.dlon) } },
          time_kind: 'DEPARTURE',
          desired: { desired_at: new Date(form.at).toISOString(), time_zone: 'Europe/Berlin' },
          passengers: Number(form.persons),
          booking_subject: { kind: 'DELEGATED', passenger_id: form.passenger },
        },
      });
      const first = r.offers[0];
      if (!first || r.rejection) {
        setError(r.rejection?.text ?? 'Für diesen Wunsch gibt es kein Angebot.');
        setBusy(false);
        return;
      }
      setOffer(first as unknown as RideOffer);
      setBusy(false);
    } catch (e) {
      fail(e);
    }
  };

  /** Reservieren und bestätigen bzw. tauschen: Hold, Stand neu lesen (ETag = Angebotsrevision), dann Buchung anlegen oder ändern. */
  const commit = async () => {
    if (!offer || reasonMissing) return;
    setBusy(true);
    setError(undefined);
    try {
      const hold = await api.call('holdRideOffer', {
        path: { ride_offer_id: offer.id },
        revision: offer.revision,
        body: { max_gross: offer.gross },
      });
      const reread = await api.call('getRideOffer', { path: { ride_offer_id: offer.id } });
      if (mode === 'create') {
        const b = await api.call('confirmBooking', {
          revision: reread.revision,
          body: {
            hold_id: hold.id,
            max_gross: offer.gross,
            reason_code: reason as DispatchReasonCode,
            ...(text.trim() ? { reason_text: text.trim() } : {}),
          },
        });
        onDone(`Die Buchung ${b.booking_number} wurde für den Fahrgast angelegt.`);
      } else if (row) {
        const r = await api.call('changeBooking', {
          path: { booking_id: row.id },
          revision: row.revision,
          body: {
            new_hold_id: hold.id,
            offer_revision: reread.revision,
            max_gross: offer.gross,
            reason_code: reason as DispatchReasonCode,
            ...(text.trim() ? { reason_text: text.trim() } : {}),
            ...(consent
              ? {
                  passenger_consent: {
                    channel: consent as 'PHONE',
                    obtained_at: new Date().toISOString(),
                  },
                }
              : {}),
          },
        });
        onDone(
          `Die Buchung ${row.booking_number} wurde durch ${r.booking.booking_number} ersetzt.` +
            (r.price_changed ? ' Der Preis hat sich geändert.' : '') +
            (r.time_changed ? ' Die Abholzeit hat sich geändert.' : ''),
        );
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && mode === 'change' && !consent) {
        setNeedsConsent(true);
        setError(
          'Preis oder Abholzeit ändern sich: Bitte holen Sie die Zustimmung des Fahrgasts ein und wählen Sie den Kanal.',
        );
        setBusy(false);
        return;
      }
      fail(e);
    }
  };

  const reasonField = (
    <>
      <label>
        Grund (Pflicht)
        <select value={reason} onChange={(e) => setReason(e.target.value)}>
          <option value="">Bitte wählen</option>
          {Object.entries(mode === 'cancel' ? CANCEL_REASONS : REASON_LABELS).map(([k, v]) => (
            <option key={k} value={k}>
              {v}
            </option>
          ))}
        </select>
      </label>
      <label>
        Anmerkung
        <input value={text} maxLength={500} onChange={(e) => setText(e.target.value)} />
      </label>
    </>
  );

  const title =
    mode === 'create'
      ? 'Buchung für Fahrgast anlegen'
      : mode === 'change'
        ? `Buchung ${row?.booking_number} ändern`
        : `Buchung ${row?.booking_number} stornieren`;

  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      {mode === 'cancel' ? (
        <>
          <p>
            Folgen: Die Reservierung wird freigegeben, der Fahrgast wird benachrichtigt, eine
            Erstattung richtet sich nach dem Verursacher des Stornos.
          </p>
          {reasonField}
          <button type="button" disabled={busy || reasonMissing} onClick={cancel}>
            Stornieren
          </button>
        </>
      ) : (
        <>
          {mode === 'change' ? (
            <p>
              Folgen: Es entsteht eine neue Buchung mit neuer Buchungsnummer, die bisherige wird
              storniert. Scheitert der neue Wunsch, bleibt die bisherige Buchung unverändert.
            </p>
          ) : null}
          <form
            aria-label="Wunsch"
            onSubmit={(e) => {
              e.preventDefault();
              void getOffer();
            }}
          >
            <label>
              Fahrgast der Buchung (Verweis)
              <input
                value={form.passenger}
                readOnly={mode === 'change'}
                onChange={(e) => set('passenger', e.target.value)}
              />
            </label>
            <label>
              Dienst
              <input
                value={form.service}
                readOnly={mode === 'change'}
                onChange={(e) => set('service', e.target.value)}
              />
            </label>
            <label>
              Abholung Breite
              <input value={form.olat} onChange={(e) => set('olat', e.target.value)} />
            </label>
            <label>
              Abholung Länge
              <input value={form.olon} onChange={(e) => set('olon', e.target.value)} />
            </label>
            <label>
              Ziel Breite
              <input value={form.dlat} onChange={(e) => set('dlat', e.target.value)} />
            </label>
            <label>
              Ziel Länge
              <input value={form.dlon} onChange={(e) => set('dlon', e.target.value)} />
            </label>
            <label>
              Abfahrtswunsch
              <input
                type="datetime-local"
                value={form.at}
                onChange={(e) => set('at', e.target.value)}
              />
            </label>
            <label>
              Personen
              <input
                type="number"
                min={1}
                max={99}
                value={form.persons}
                onChange={(e) => set('persons', e.target.value)}
              />
            </label>
            <button type="submit" disabled={busy || !form.passenger || !form.service || !form.at}>
              Angebot einholen
            </button>
          </form>
          {offer ? (
            <div role="region" aria-label="Angebot">
              <p>
                Angebot: {money(offer.gross)}, Abholung zwischen{' '}
                {timeOf(offer.pickup_window.earliest_at)} und{' '}
                {timeOf(offer.pickup_window.latest_at)}.
              </p>
              {reasonField}
              {needsConsent ? (
                <label>
                  Zustimmung des Fahrgasts eingeholt per
                  <select value={consent} onChange={(e) => setConsent(e.target.value)}>
                    <option value="">Bitte wählen</option>
                    <option value="PHONE">Telefon</option>
                    <option value="COUNTER">Schalter</option>
                    <option value="WRITTEN">Schriftlich</option>
                  </select>
                </label>
              ) : null}
              <button
                type="button"
                disabled={busy || reasonMissing || (needsConsent && !consent)}
                onClick={commit}
              >
                {mode === 'create'
                  ? 'Reservieren und Buchung anlegen'
                  : 'Reservieren und Buchung ändern'}
              </button>
            </div>
          ) : null}
        </>
      )}
      {error ? <p role="alert">{error}</p> : null}
    </section>
  );
}
