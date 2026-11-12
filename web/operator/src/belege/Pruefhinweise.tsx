import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { Expense, ExpenseDuplicate, ExpenseHint } from '@taktovia/api-client';
import { StatusBadge } from '@taktovia/design-system';
import { useState } from 'react';
import { formatMinor } from './betrag';

const RULE_LABEL: Record<string, string> = {
  FUEL_VOLUME: 'Tankmenge über der Obergrenze',
  FUEL_PRICE: 'Literpreis weicht vom Vergleichspreis ab',
  FUEL_ODOMETER_GAP: 'Kilometerstand oder Verbrauch auffällig',
  FUEL_CARD_MISMATCH: 'Tankkarte gehört nicht zum Fahrzeug',
};
const DUP_STATE: Record<string, string> = {
  OFFEN: 'offen, Entscheidung nötig',
  GLEICHE_EVIDENZ: 'gleiche Evidenz (kein eigener Kostenfall)',
  EIGENER_VORGANG: 'eigener Vorgang',
};

/**
 * UI-14 Prüfhinweise (WP-M4-31/-32/-38): Dublettenverknüpfung mit Gegenüberstellung und Entscheidung (`GLEICHE_EVIDENZ` oder `EIGENER_VORGANG`, Begründung Pflicht) sowie Plausibilitätshinweise mit
 * Quittierung (Begründung Pflicht). Beides blockiert die Freigabe, solange es offen ist; Entscheiden und Quittieren nur mit Scope `expenses:review`. Alle Elemente sind per Tastatur bedienbar,
 * Ergebnisse erscheinen in einem Statusbereich.
 */
export function Pruefhinweise({
  expense,
  onChanged,
  onConflict,
}: {
  expense: Expense;
  onChanged: (e: Expense) => void;
  onConflict?: () => void;
}) {
  const api = useApi();
  const session = useSession();
  const canReview =
    session.status === 'ready' && (session.me.scopes ?? []).includes('expenses:review');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | undefined>();
  const currency = typeof expense.fields.currency === 'string' ? expense.fields.currency : 'EUR';
  const reasonOk = reason.trim().length >= 3;
  const hints = expense.hints ?? [];
  const duplicates = expense.duplicates ?? [];

  const run = (action: () => Promise<Expense>, ok: string) => {
    setBusy(true);
    setNotice(undefined);
    action()
      .then((e) => {
        onChanged(e);
        setReason('');
        setNotice({ tone: 'ok', text: ok });
      })
      .catch((e: unknown) => {
        const status = e instanceof ApiError ? e.status : 0;
        if (status === 412 || status === 428) onConflict?.();
        setNotice({
          tone: 'error',
          text:
            status === 412 || status === 428
              ? 'Konflikt (412): Der Beleg wurde zwischenzeitlich geändert. Bitte den aktuellen Stand laden.'
              : status === 403
                ? 'Dafür fehlt das Recht (403).'
                : status === 422
                  ? 'Die Begründung wurde abgewiesen (422): mindestens 3 Zeichen.'
                  : status === 409
                    ? 'Diese Aktion ist im aktuellen Zustand nicht möglich (409).'
                    : 'Die Aktion ist fehlgeschlagen.',
        });
      })
      .finally(() => setBusy(false));
  };

  const decide = (d: ExpenseDuplicate, decision: 'GLEICHE_EVIDENZ' | 'EIGENER_VORGANG') =>
    run(
      () =>
        api.call('decideExpenseDuplicate', {
          path: { expense_id: expense.document_id, candidate_id: d.id },
          revision: expense.revision,
          body: { decision, reason: reason.trim() },
        }),
      'Die Dublette ist entschieden.',
    );
  const acknowledge = (h: ExpenseHint) =>
    run(
      () =>
        api.call('acknowledgeExpenseHint', {
          path: { expense_id: expense.document_id, hint_id: h.id },
          revision: expense.revision,
          body: { reason: reason.trim() },
        }),
      'Der Hinweis ist quittiert.',
    );

  return (
    <section aria-label="Prüfhinweise">
      <h3>Dubletten und Hinweise</h3>
      <h4>Dublettenverknüpfung</h4>
      {duplicates.length === 0 ? (
        <p>Keine Dublette erkannt.</p>
      ) : (
        <ul aria-label="Dublettentreffer">
          {duplicates.map((d) => (
            <li key={d.id}>
              <StatusBadge tone={d.state === 'OFFEN' ? 'warning' : 'info'}>
                {DUP_STATE[d.state] ?? d.state}
              </StatusBadge>{' '}
              {d.certainty === 'SAFE' ? 'Sicherer Treffer' : 'Möglicher Treffer'} (
              {d.match === 'HASH' ? 'gleiche Datei' : 'gleiche Fachdaten'}, {d.score} Punkte)
              {d.other && (
                <>
                  {' '}
                  – Gegenüber: {d.other.merchant ?? '–'}, {d.other.document_date ?? '–'},{' '}
                  {d.other.amount_minor !== null
                    ? formatMinor(d.other.amount_minor, d.other.currency ?? currency)
                    : '–'}
                  {d.other.document_number ? `, Nr. ${d.other.document_number}` : ''}
                </>
              )}
              {d.state === 'OFFEN' && d.role === 'NEWER' && (
                <span role="group" aria-label="Dublette entscheiden">
                  {' '}
                  <button
                    type="button"
                    disabled={!canReview || !reasonOk || busy}
                    onClick={() => decide(d, 'EIGENER_VORGANG')}
                  >
                    Ist ein eigener Vorgang
                  </button>{' '}
                  <button
                    type="button"
                    disabled={!canReview || !reasonOk || busy}
                    onClick={() => decide(d, 'GLEICHE_EVIDENZ')}
                  >
                    Ist gleiche Evidenz
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      <h4>Plausibilitätshinweise</h4>
      {hints.length === 0 ? (
        <p>Keine Hinweise.</p>
      ) : (
        <ul aria-label="Plausibilitätshinweise">
          {hints.map((h) => (
            <li key={h.id}>
              <StatusBadge tone={h.acknowledgement ? 'ok' : 'warning'}>
                {h.acknowledgement ? 'quittiert' : 'offen'}
              </StatusBadge>{' '}
              {RULE_LABEL[h.rule] ?? h.rule}: {h.actual_value} (Schwelle {h.threshold})
              {h.acknowledgement ? (
                <>
                  {' '}
                  – {h.acknowledgement.acknowledged_by}: {h.acknowledgement.reason}
                </>
              ) : (
                <>
                  {' '}
                  <button
                    type="button"
                    disabled={!canReview || !reasonOk || busy}
                    onClick={() => acknowledge(h)}
                  >
                    Hinweis quittieren
                  </button>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {(duplicates.some((d) => d.state === 'OFFEN') || hints.some((h) => !h.acknowledgement)) && (
        <div>
          <label htmlFor="hinweis-begruendung">Begründung für Entscheidung oder Quittierung</label>
          <input
            id="hinweis-begruendung"
            type="text"
            value={reason}
            maxLength={1000}
            onChange={(e) => setReason(e.target.value)}
          />
          {!canReview && <p>Ohne das Recht zur fachlichen Prüfung sind Entscheidungen gesperrt.</p>}
        </div>
      )}
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={notice.tone === 'error' ? 'error' : 'ok'}>
            {notice.tone === 'error' ? 'Fehler' : 'Erledigt'}
          </StatusBadge>{' '}
          {notice.text}
        </p>
      )}
    </section>
  );
}
