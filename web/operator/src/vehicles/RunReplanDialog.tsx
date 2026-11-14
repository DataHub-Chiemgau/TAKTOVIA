import { ApiError, CommandKeys, runCommand, useApi } from '@taktovia/api-client';
import type { RunRevision, RunRevisionCreate, VehiclePlan } from '@taktovia/api-client';
import { useMemo, useRef, useState } from 'react';

const clock = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleTimeString('de-DE') : '–';

type Rule = 'PROTECTED_STOP_HORIZON' | 'PROMISE_WINDOW' | 'MAX_REASSIGNMENTS';

/** Fachliche Ablehnungen des Servers (stabile Codes, Kap. 05 §5.7) in der Sprache der Leitstelle; die drei Regeln bieten den dokumentierten Ausnahmeweg. */
const REFUSALS: Readonly<Record<string, { text: string; rule?: Rule }>> = {
  PROTECTED_STOP_HORIZON: {
    text: 'Ein Stopp im geschützten Horizont würde geändert.',
    rule: 'PROTECTED_STOP_HORIZON',
  },
  MAX_REASSIGNMENTS_EXCEEDED: {
    text: 'Die Höchstzahl an Umbuchungen einer Buchung ist erreicht.',
    rule: 'MAX_REASSIGNMENTS',
  },
  PROMISE_WINDOW_VIOLATION: {
    text: 'Ein zugesagtes Zeitfenster würde verletzt.',
    rule: 'PROMISE_WINDOW',
  },
  CAPACITY_UNAVAILABLE: { text: 'Die Umplanung überbucht das Fahrzeug (Kapazität je Dimension).' },
  CONFLICT: {
    text: 'Das Zielfahrzeug ist nicht einsetzbar (gesperrt, nicht freigegeben oder nicht im Dienst).',
  },
  INVALID_STATE_TRANSITION: {
    text: 'Im aktuellen Zustand des Umlaufs ist keine Planänderung möglich.',
  },
};

const DIFF_LABELS: Readonly<Record<string, string>> = {
  ADDED: 'neu',
  REMOVED: 'entfällt',
  MOVED: 'verschoben',
  TIME_CHANGED: 'Zeit geändert',
};

const VIOLATION_LABELS: Readonly<Record<string, string>> = {
  PICKUP_OUTSIDE_WINDOW: 'Abholung außerhalb des Zusagefensters',
  ARRIVAL_OUTSIDE_WINDOW: 'Ankunft außerhalb des Zusagefensters',
  ARRIVAL_AFTER_GRACE: 'Ankunft nach der Karenz',
  MAX_WAIT_EXCEEDED: 'Wartezeit über der Grenze',
  MAX_DETOUR_EXCEEDED: 'Umweg über der Grenze',
  NOT_IN_PLAN: 'Buchung ohne Plan',
};

/** Folge von MOVE-Änderungen, die [from] in die Reihenfolge [to] bringt (sequentiell angewandt, `new_sequence` 1-basiert wie der Server). */
export function moveChanges(from: string[], to: string[]): RunRevisionCreate['changes'] {
  const work = [...from];
  const changes: RunRevisionCreate['changes'] = [];
  to.forEach((id, i) => {
    const at = work.indexOf(id);
    if (at !== i) {
      work.splice(at, 1);
      work.splice(i, 0, id);
      changes.push({ route_stop_id: id, change: 'MOVE', new_sequence: i + 1 });
    }
  });
  return changes;
}

/**
 * UI-07 Umplanung per Dialog und Tastatur (WP-M3-40; Alternative zu Drag-and-drop): Stopps mit den Schaltflächen „nach oben“/„nach unten“ ordnen, Begründung eingeben (Pflicht), den **Planvergleich alt/neu mit
 * den Folgen für betroffene Fahrgäste** anzeigen und erst danach bestätigen. Der Befehl trägt die gelesene Planrevision als `If-Match`. Ein 412-Konflikt (jemand anderes hat den Plan geändert) überschreibt nie
 * still: die Änderung wird nicht übernommen, der aktuelle Plan wird geladen und die Umplanung muss neu geprüft und bestätigt werden. Verletzt die Umplanung eine ausnahmefähige Regel, erscheint der
 * dokumentierte Ausnahmeweg mit eigener Begründung (Pflicht, wird im Audit gesondert protokolliert).
 */
export function RunReplanDialog({
  plan,
  currentRevision,
  onClose,
  onChanged,
}: {
  plan: VehiclePlan;
  /** Planrevision des aktuell geladenen Plans (ändert sich durch Live-Aktualisierung unter dem offenen Dialog). */
  currentRevision: number | null | undefined;
  onClose: () => void;
  /** Eine Revision wurde festgeschrieben, oder der Plan hat sich geändert (412): die Anzeige lädt neu. */
  onChanged: () => void;
}) {
  const api = useApi();
  const baseRevision = plan.plan_revision ?? 0;
  const runId = plan.run_id ?? '';
  const initial = useMemo(() => plan.stops.map((s) => s.route_stop_id), [plan.stops]);
  const [order, setOrder] = useState<string[]>(initial);
  const [reason, setReason] = useState('');
  const [useException, setUseException] = useState(false);
  const [justification, setJustification] = useState('');
  const [preview, setPreview] = useState<RunRevision | undefined>(undefined);
  const [refusal, setRefusal] = useState<{ code: string; text: string; rule?: Rule } | undefined>(
    undefined,
  );
  const [conflict, setConflict] = useState<string | undefined>(undefined);
  const [done, setDone] = useState<RunRevision | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const keys = useRef(new CommandKeys());
  const byId = useMemo(() => new Map(plan.stops.map((s) => [s.route_stop_id, s])), [plan.stops]);
  const changes = moveChanges(initial, order);
  const stale =
    currentRevision !== undefined && currentRevision !== null && currentRevision !== baseRevision;
  const exception: RunRevisionCreate['exception'] | undefined =
    useException && refusal?.rule && justification.trim().length >= 10
      ? { rules: [refusal.rule], justification: justification.trim() }
      : undefined;
  const reasonOk = reason.trim().length > 0;

  const edit = (next: string[]) => {
    setOrder(next);
    setPreview(undefined);
    setRefusal(undefined);
  };
  const move = (i: number, by: -1 | 1) => {
    const next = [...order];
    const target = i + by;
    const a = next[i];
    const b = next[target];
    if (a === undefined || b === undefined) return;
    next[i] = b;
    next[target] = a;
    edit(next);
  };

  const send = async (compareOnly: boolean) => {
    setBusy(true);
    setConflict(undefined);
    if (compareOnly) setRefusal(undefined);
    try {
      const body: RunRevisionCreate = {
        changes,
        reason: reason.trim(),
        base_plan_revision: baseRevision,
        compare_only: compareOnly,
        ...(exception ? { exception } : {}),
      };
      const intent = `replan:${runId}:${baseRevision}:${compareOnly ? 'compare' : 'commit'}:${JSON.stringify(body)}`;
      const result = await runCommand(api, keys.current, intent, 'createRunRevision', {
        path: { run_id: runId },
        headers: { 'If-Match': `"${baseRevision}"`, 'Idempotency-Key': '' },
        body,
      });
      if (compareOnly) setPreview(result);
      else {
        setDone(result);
        onChanged();
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        // nie still überschreiben: die Änderung wurde nicht übernommen, der aktuelle Plan wird geladen
        setConflict(
          'Der Plan wurde zwischenzeitlich von jemand anderem geändert. Ihre Umplanung wurde nicht übernommen. Der aktuelle Plan wird geladen; bitte prüfen Sie die Umplanung erneut.',
        );
        setPreview(undefined);
        onChanged();
      } else if (e instanceof ApiError && e.problem && REFUSALS[e.problem.code]) {
        const known = REFUSALS[e.problem.code];
        setRefusal({
          code: e.problem.code,
          text: known?.text ?? e.problem.code,
          ...(known?.rule ? { rule: known.rule } : {}),
        });
        setPreview(undefined);
      } else {
        setRefusal({
          code: e instanceof ApiError ? (e.problem?.code ?? `HTTP_${e.status}`) : 'NETWORK',
          text: 'Die Umplanung konnte nicht ausgeführt werden.',
        });
      }
    } finally {
      setBusy(false);
    }
  };

  if (done)
    return (
      <section role="dialog" aria-label="Umplanung" aria-modal="false">
        <p role="status">
          Umplanung festgeschrieben: Planrevision {done.plan_revision}. Fahrer und betroffene
          Fahrgäste erhalten die neue Revision.
        </p>
        <button type="button" onClick={onClose}>
          Schließen
        </button>
      </section>
    );

  return (
    <section role="dialog" aria-label="Umplanung" aria-modal="false">
      <h4>Umplanung (Planrevision {baseRevision})</h4>
      {conflict ? <p role="alert">{conflict}</p> : null}
      {stale && !conflict ? (
        <p role="alert">
          Der Plan hat sich geändert (Revision {currentRevision}); Ihr Entwurf beruht auf Revision{' '}
          {baseRevision}. Bitte schließen Sie den Dialog und beginnen Sie mit dem aktuellen Plan.
        </p>
      ) : null}
      <ol aria-label="Reihenfolge der Stopps">
        {order.map((id, i) => {
          const s = byId.get(id);
          return (
            <li key={id}>
              {i + 1}. {s?.stop_name ?? id} · Soll {s ? clock(s.scheduled_at) : '–'}{' '}
              <button
                type="button"
                aria-label={`Stopp ${i + 1} ${s?.stop_name ?? ''} nach oben`}
                disabled={i === 0 || busy}
                onClick={() => move(i, -1)}
              >
                nach oben
              </button>{' '}
              <button
                type="button"
                aria-label={`Stopp ${i + 1} ${s?.stop_name ?? ''} nach unten`}
                disabled={i === order.length - 1 || busy}
                onClick={() => move(i, 1)}
              >
                nach unten
              </button>
            </li>
          );
        })}
      </ol>
      <label>
        Begründung (Pflicht)
        <textarea
          value={reason}
          maxLength={500}
          onChange={(e) => {
            setReason(e.target.value);
            setPreview(undefined);
          }}
        />
      </label>
      {refusal ? (
        <div role="alert">
          <p>
            {refusal.text} ({refusal.code})
          </p>
          {refusal.rule ? (
            <>
              <label>
                <input
                  type="checkbox"
                  checked={useException}
                  onChange={(e) => {
                    setUseException(e.target.checked);
                    setPreview(undefined);
                  }}
                />{' '}
                Ausnahmeweg nutzen (wird gesondert protokolliert)
              </label>
              {useException ? (
                <label>
                  Begründung der Ausnahme (mindestens 10 Zeichen)
                  <textarea
                    value={justification}
                    maxLength={500}
                    onChange={(e) => {
                      setJustification(e.target.value);
                      setPreview(undefined);
                    }}
                  />
                </label>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
      <p>
        <button
          type="button"
          disabled={busy || changes.length === 0 || !reasonOk || stale}
          onClick={() => void send(true)}
        >
          Planvergleich anzeigen
        </button>{' '}
        <button
          type="button"
          disabled={busy || !preview || !reasonOk || stale}
          onClick={() => void send(false)}
        >
          Umplanung bestätigen
        </button>{' '}
        <button type="button" onClick={onClose}>
          Abbrechen
        </button>
      </p>
      {preview ? (
        <section aria-label="Planvergleich">
          <h5>Planvergleich alt/neu</h5>
          <ul aria-label="Änderungen am Plan">
            {preview.diff.map((d) => (
              <li key={`${d.route_stop_id}-${d.kind}`}>
                {DIFF_LABELS[d.kind] ?? d.kind}: Position {d.old_sequence ?? '–'} →{' '}
                {d.new_sequence ?? '–'}
              </li>
            ))}
          </ul>
          <h5>Folgen für betroffene Fahrgäste</h5>
          <ul aria-label="Folgen für Fahrgäste">
            {(preview.impacts ?? []).map((i) => (
              <li key={i.booking_id}>
                Buchung {i.booking_id.slice(0, 8)}: Abholung {clock(i.pickup_before)} →{' '}
                {clock(i.pickup_after)}, Ankunft {clock(i.dropoff_before)} →{' '}
                {clock(i.dropoff_after)}
                {i.window_violations && i.window_violations.length > 0
                  ? ` · ${i.window_violations.map((v) => VIOLATION_LABELS[v] ?? v).join(', ')}`
                  : ' · Zusagefenster eingehalten'}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </section>
  );
}
