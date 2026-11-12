import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { Expense } from '@taktovia/api-client';
import { StatusBadge, zeit } from '@taktovia/design-system';
import { useState } from 'react';
import { DEFAULT_ZONE } from '../ScreenContext';

const STATE_LABEL: Record<string, string> = {
  STORED: 'in Erfassung',
  IN_REVIEW: 'in fachlicher Prüfung',
  REVIEWED: 'geprüft, wartet auf Freigabe',
  WAITING_SECOND_APPROVAL: 'wartet auf zweite Freigabe',
  POSTED: 'freigegeben und gebucht',
};
const STEP_LABEL: Record<string, string> = {
  CAPTURE: 'Erfassung',
  REVIEW: 'Fachliche Prüfung',
  BUDGET: 'Budgetfreigabe',
  SECOND_APPROVAL: 'Zweite Freigabe',
};
const time = (iso: string) => zeit.toRfc3339(Date.parse(iso), DEFAULT_ZONE);

/** Meldung je Antwortstatus. Ein 403 nennt nie Daten des Belegs (keine Datenpreisgabe); jede Blockade der Freigabe hat ihren eigenen Text nach dem stabilen Problem-Code. */
type Notice = { tone: 'error' | 'ok'; text: string; stale?: boolean };

const BLOCKER_TEXT: Record<string, string> = {
  DUPLICATE_REVIEW_REQUIRED:
    'Ein möglicher Dublettentreffer ist offen und muss zuerst entschieden werden.',
  DUPLICATE_EVIDENCE_LINKED:
    'Der Beleg ist weitere Evidenz eines anderen Vorgangs und wird nicht freigegeben.',
  DOCUMENT_INTEGRITY_VIOLATION:
    'Das Original des Belegs stimmt nicht mehr mit dem erfassten Hash überein.',
  UNCONFIRMED_FIELDS: 'Pflichtfelder sind nicht bestätigt oder korrigiert.',
  COST_ASSIGNMENT_REQUIRED: 'Der Beleg hat keine erlaubte Kostenzuordnung.',
  FUEL_FIELDS_MISSING: 'Pflichtwerte des Tankbelegs fehlen.',
  SPLIT_INCOMPLETE: 'Die Kostenaufteilung ist unvollständig.',
  HINT_NOT_ACKNOWLEDGED: 'Plausibilitätshinweise sind nicht quittiert.',
};

/**
 * UI-14 Basis „Belegfreigabe“ (WP-M4-34, DOC-010): zeigt den Freigabestand eines Belegs und führt die getrennten Schritte aus: einreichen (Erfassung), fachliche Prüfung, Budgetfreigabe bzw. zweite
 * Freigabe und Ablehnung mit Begründung. Jede Schaltfläche ist nur mit dem eigenen Funktionsscope der Sitzung (`/v1/me` -> `scopes`) aktiv; der Erfasser kann nie selbst freigeben. Die Oberfläche ist
 * keine Rechtequelle: der Server prüft jede Anfrage erneut, ein 403 zeigt eine feste Meldung ohne Belegdaten. Veralteter Stand (412) bietet „Aktuellen Stand laden“. Bedienbar per Tastatur.
 */
export function BelegFreigabe({
  expense,
  onChanged,
}: {
  expense: Expense;
  onChanged?: (e: Expense) => void;
}) {
  const api = useApi();
  const session = useSession();
  const [current, setCurrent] = useState<Expense>(expense);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const me = session.status === 'ready' ? session.me : undefined;
  const scopes = new Set(me?.scopes ?? []);
  const reasonOk = reason.trim().length >= 3;
  const state = current.state;
  const steps = current.approval_steps ?? [];
  const creator = steps.find((s) => s.step === 'CAPTURE')?.decided_by;
  const firstApprover = [...steps]
    .reverse()
    .find((s) => s.step === 'BUDGET' && s.decision === 'APPROVED')?.decided_by;
  const isSelf =
    me !== undefined &&
    (me.subject === creator ||
      (state === 'WAITING_SECOND_APPROVAL' && me.subject === firstApprover));
  const canSubmit = state === 'STORED' && scopes.has('receipts:write') && !busy;
  const canReview = state === 'IN_REVIEW' && scopes.has('expenses:review') && !busy;
  const canApprove =
    (state === 'REVIEWED' || state === 'WAITING_SECOND_APPROVAL') &&
    scopes.has('expenses:approve') &&
    !isSelf &&
    !busy;
  const canReject =
    (state === 'REVIEWED' || state === 'WAITING_SECOND_APPROVAL') &&
    scopes.has('expenses:approve') &&
    !busy;

  const apply = (next: Expense) => {
    setCurrent(next);
    onChanged?.(next);
  };

  const fail = (e: unknown) => {
    const status = e instanceof ApiError ? e.status : 0;
    const code = e instanceof ApiError ? e.problem?.code : undefined;
    if (status === 412 || status === 428)
      setNotice({
        tone: 'error',
        stale: true,
        text: 'Konflikt (412): Der Beleg wurde zwischenzeitlich geändert. Bitte den aktuellen Stand laden.',
      });
    else if (status === 403)
      setNotice({
        tone: 'error',
        text: 'Dafür fehlt das Recht, oder die Freigabe muss durch eine andere Person erfolgen (403).',
      });
    else if (code && BLOCKER_TEXT[code])
      setNotice({
        tone: 'error',
        text: BLOCKER_TEXT[code] + ' Die Freigabe wurde nicht durchgeführt.',
      });
    else if (status === 409)
      setNotice({
        tone: 'error',
        stale: true,
        text: 'Diese Aktion ist im aktuellen Zustand des Belegs nicht möglich (409).',
      });
    else if (status === 422)
      setNotice({
        tone: 'error',
        text: 'Die Eingabe wurde abgewiesen (422). Eine Begründung mit mindestens 3 Zeichen ist erforderlich.',
      });
    else
      setNotice({ tone: 'error', text: 'Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.' });
  };

  const run = (action: () => Promise<void>) => {
    setBusy(true);
    setNotice(undefined);
    action()
      .catch(fail)
      .finally(() => setBusy(false));
  };

  const reload = () =>
    run(async () => {
      apply(await api.call('getExpense', { path: { expense_id: current.document_id } }));
    });

  const submit = () =>
    run(async () => {
      apply(
        await api.call('submitExpense', {
          path: { expense_id: current.document_id },
          revision: current.revision,
        }),
      );
      setNotice({ tone: 'ok', text: 'Der Beleg ist zur fachlichen Prüfung eingereicht.' });
    });

  const review = (decision: 'APPROVED' | 'REJECTED') =>
    run(async () => {
      apply(
        await api.call('reviewExpense', {
          path: { expense_id: current.document_id },
          revision: current.revision,
          body: { decision, reason: reason.trim() },
        }),
      );
      setReason('');
      setNotice({
        tone: 'ok',
        text:
          decision === 'APPROVED'
            ? 'Fachliche Prüfung bestanden.'
            : 'Der Beleg ist in die Erfassung zurückgegeben.',
      });
    });

  const approve = () =>
    run(async () => {
      const r = await api.call('approveExpense', {
        path: { expense_id: current.document_id },
        revision: current.revision,
        body: { reason: reason.trim(), validated_document_revision: current.revision },
      });
      setReason('');
      apply({ ...current, state: r.state, revision: r.revision });
      setNotice({
        tone: 'ok',
        text:
          r.state === 'POSTED'
            ? 'Freigegeben und gebucht.'
            : 'Erste Freigabe erteilt: eine zweite Person muss freigeben.',
      });
    });

  const reject = () =>
    run(async () => {
      apply(
        await api.call('rejectExpense', {
          path: { expense_id: current.document_id },
          revision: current.revision,
          body: { reason: reason.trim() },
        }),
      );
      setReason('');
      setNotice({ tone: 'ok', text: 'Der Beleg ist in die Erfassung zurückgegeben.' });
    });

  return (
    <section aria-label="Belegfreigabe">
      <h2>Belegfreigabe</h2>
      <dl>
        <dt>Status</dt>
        <dd>
          <StatusBadge tone={state === 'POSTED' ? 'ok' : 'neutral'}>
            {STATE_LABEL[state] ?? state}
          </StatusBadge>
        </dd>
        <dt>Exportstatus</dt>
        <dd>{current.export_state === 'EXPORTED' ? 'exportiert' : 'nicht exportiert'}</dd>
      </dl>

      <h3>Schritte</h3>
      {steps.length === 0 ? (
        <p>Noch keine Schritte.</p>
      ) : (
        <ol aria-label="Freigabeschritte">
          {steps.map((s) => (
            <li key={s.id}>
              {STEP_LABEL[s.step] ?? s.step}:{' '}
              {s.decision === 'APPROVED' ? 'bestätigt' : 'abgelehnt'} von {s.decided_by} am{' '}
              {time(s.decided_at)} (Revision {s.document_revision}), {s.reason}
            </li>
          ))}
        </ol>
      )}

      <div>
        <label htmlFor="beleg-begruendung">Begründung</label>
        <input
          id="beleg-begruendung"
          type="text"
          value={reason}
          maxLength={1000}
          onChange={(e) => setReason(e.target.value)}
        />
      </div>

      <div role="group" aria-label="Schritte">
        <button type="button" disabled={!canSubmit} onClick={submit}>
          Zur Prüfung einreichen
        </button>
        <button type="button" disabled={!canReview || !reasonOk} onClick={() => review('APPROVED')}>
          Prüfung bestanden
        </button>
        <button type="button" disabled={!canReview || !reasonOk} onClick={() => review('REJECTED')}>
          Prüfung ablehnen
        </button>
        <button type="button" disabled={!canApprove || !reasonOk} onClick={approve}>
          {state === 'WAITING_SECOND_APPROVAL' ? 'Zweite Freigabe erteilen' : 'Freigeben'}
        </button>
        <button type="button" disabled={!canReject || !reasonOk} onClick={reject}>
          Ablehnen
        </button>
      </div>

      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={notice.tone === 'error' ? 'error' : 'ok'}>
            {notice.tone === 'error' ? 'Fehler' : 'Erledigt'}
          </StatusBadge>{' '}
          {notice.text}
          {notice.stale && (
            <>
              {' '}
              <button type="button" onClick={reload}>
                Aktuellen Stand laden
              </button>
            </>
          )}
        </p>
      )}
    </section>
  );
}
