import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { CostEventReversal, Expense } from '@taktovia/api-client';
import { StatusBadge } from '@taktovia/design-system';
import { useState } from 'react';
import { formatMinor, parseMoney } from './betrag';

const PROBLEM_TEXT: Record<string, string> = {
  ALREADY_REVERSED:
    'Zu diesem Beleg besteht bereits ein Gegenbeleg in dieser Höhe (einmalige Wirkung).',
  PERIOD_CLOSED: 'Die Periode ist geschlossen; es wird die nächste offene Periode verwendet.',
};

/**
 * UI-14 „Gegenbeleg erstellen“ (WP-M4-36/-38): zu einem gebuchten Beleg legt die Finanzrolle ein Gegenereignis an (Gutschrift oder volle Korrektur). Das Original-Ereignis und seine Periode bleiben
 * unverändert; das Gegenereignis bucht in der nächsten offenen Periode mit Bezug auf den Ursprung. Aktiv nur mit Scope `costs:reverse` und einem Kostenereignis am Beleg; der Server prüft erneut.
 */
export function Gegenbeleg({ expense }: { expense: Expense }) {
  const api = useApi();
  const session = useSession();
  const scopes = new Set(session.status === 'ready' ? session.me.scopes : []);
  const [reason, setReason] = useState('');
  const [credit, setCredit] = useState('');
  const [date, setDate] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<CostEventReversal | undefined>();
  const [error, setError] = useState<string | undefined>();

  if (expense.state !== 'POSTED') return null;
  const eventId = expense.cost_event_id;
  const currency = typeof expense.fields.currency === 'string' ? expense.fields.currency : 'EUR';
  const creditMinor = credit.trim() === '' ? undefined : parseMoney(credit);
  const creditOk = credit.trim() === '' || (creditMinor !== undefined && creditMinor > 0n);
  const can = scopes.has('costs:reverse') && eventId != null && !busy;

  const run = () => {
    if (!eventId) return;
    setBusy(true);
    setError(undefined);
    api
      .call('createCostEventReversal', {
        path: { cost_event_id: eventId },
        body: {
          reason: reason.trim(),
          ...(creditMinor !== undefined ? { credit_gross_minor: Number(creditMinor) } : {}),
          ...(date ? { accounting_date: date } : {}),
        },
      })
      .then(setResult)
      .catch((e: unknown) => {
        const code = e instanceof ApiError ? e.problem?.code : undefined;
        const status = e instanceof ApiError ? e.status : 0;
        setError(
          (code && PROBLEM_TEXT[code]) ||
            (status === 403
              ? 'Dafür fehlt das Recht (403).'
              : status === 409
                ? 'Der Gegenbeleg ist nicht möglich (409): keine offene Periode oder bereits korrigiert.'
                : status === 422
                  ? 'Die Eingabe wurde abgewiesen (422): Begründung und Betrag prüfen.'
                  : 'Der Gegenbeleg konnte nicht erstellt werden.'),
        );
      })
      .finally(() => setBusy(false));
  };

  return (
    <section aria-label="Gegenbeleg">
      <h3>Gegenbeleg</h3>
      <p>
        Der Beleg ist gebucht und unveränderlich. Eine Korrektur entsteht als Gegenbeleg in der
        nächsten offenen Periode.
      </p>
      <div>
        <label htmlFor="gegenbeleg-grund">Begründung</label>
        <input
          id="gegenbeleg-grund"
          type="text"
          value={reason}
          maxLength={1000}
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="gegenbeleg-betrag">Bruttobetrag der Korrektur (leer = gesamter Rest)</label>
        <input
          id="gegenbeleg-betrag"
          type="text"
          inputMode="decimal"
          value={credit}
          aria-invalid={!creditOk}
          onChange={(e) => setCredit(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="gegenbeleg-datum">Buchungsdatum (leer = heute)</label>
        <input
          id="gegenbeleg-datum"
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
      </div>
      <button type="button" disabled={!can || reason.trim().length < 3 || !creditOk} onClick={run}>
        Gegenbeleg erstellen
      </button>
      {!scopes.has('costs:reverse') && (
        <p>Ohne das Recht zum Korrigieren ist der Gegenbeleg gesperrt.</p>
      )}
      {result && (
        <p role="status">
          <StatusBadge tone="ok">Erledigt</StatusBadge> Gegenbeleg über{' '}
          {formatMinor(result.event.gross_minor, currency)} gebucht am{' '}
          {result.journal.accounting_date}; Bezug auf das ursprüngliche Kostenereignis.
        </p>
      )}
      {error && (
        <p role="alert">
          <StatusBadge tone="error">Fehler</StatusBadge> {error}
        </p>
      )}
    </section>
  );
}
