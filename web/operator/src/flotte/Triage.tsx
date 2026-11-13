import { ApiError, useApi } from '@taktovia/api-client';
import type { Defect } from '@taktovia/api-client';
import { useState } from 'react';
import { SEVERITY_LABELS, STATUS_LABELS } from './schadenkatalog';

type Severity = NonNullable<Defect['severity']>;
type Decision = 'REVIEWED' | 'IN_PROGRESS' | 'DISMISSED';
const DECISIONS: readonly { value: Decision; label: string }[] = [
  { value: 'REVIEWED', label: 'Als geprüft markieren' },
  { value: 'IN_PROGRESS', label: 'In Bearbeitung nehmen' },
  { value: 'DISMISSED', label: 'Verwerfen (nur Werkstatt)' },
];

/**
 * UI-11 Triageansicht (WP-M3-45, DMG-005): vorgeschlagene Klasse der Regelmatrix neben der geltenden, Korrektur mit Begründung, Zuständiger und Folgeentscheidung. Wer das Triagerecht nicht hat, sieht
 * diesen Bereich nicht (nicht vorhanden statt ausgegraut). Eine sicherheitskritische Einstufung sperrt das Fahrzeug im selben Commit; eine Herabstufung hebt die Sperre nicht auf. Der Server prüft Rolle,
 * Grund und Revision erneut (403, 422, 409, 412).
 */
export function Triage({ defect, onUpdated }: { defect: Defect; onUpdated: (d: Defect) => void }) {
  const api = useApi();
  const suggested = defect.suggested_severity;
  const [severity, setSeverity] = useState<Severity>(defect.severity ?? suggested ?? 'OTHER');
  const [decision, setDecision] = useState<Decision>(
    defect.status === 'REVIEWED' ? 'IN_PROGRESS' : 'REVIEWED',
  );
  const [assignee, setAssignee] = useState(defect.assignee_ref ?? '');
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState<string | undefined>(undefined);
  const [reasonMissing, setReasonMissing] = useState(false);
  const open =
    defect.status === 'NEW' || defect.status === 'REVIEWED' || defect.status === 'IN_PROGRESS';
  const correcting = defect.severity !== null && severity !== defect.severity;

  if (!open)
    return (
      <section aria-labelledby="triage-titel">
        <h3 id="triage-titel">Triage</h3>
        <p>Eine Akte im Status „{STATUS_LABELS[defect.status]}“ wird nicht mehr triagiert.</p>
      </section>
    );

  const submit = async () => {
    setMessage(undefined);
    setReasonMissing(false);
    const body: Record<string, unknown> = { severity, decision };
    if (assignee.trim() !== '' || defect.assignee_ref)
      body['assignee_ref'] = assignee.trim() === '' ? null : assignee.trim();
    if (reason.trim() !== '') body['reason'] = reason.trim();
    try {
      const updated = await api.call('triageDefect', {
        path: { defect_id: defect.id },
        body: body as never,
        revision: defect.revision,
      });
      setReason('');
      setMessage(
        updated.blocks_vehicle
          ? 'Die Einstufung wurde gespeichert. Das Fahrzeug ist gesperrt (kritischer Mangel).'
          : 'Die Einstufung wurde gespeichert.',
      );
      onUpdated(updated);
    } catch (e) {
      if (e instanceof ApiError && e.status === 422) {
        setReasonMissing(true);
        setMessage('Bitte eine Begründung angeben (für Korrektur der Klasse und Verwerfen).');
      } else if (e instanceof ApiError && e.status === 403)
        setMessage(
          'Ihnen fehlt das Recht für diese Aktion (Herabstufung und Verwerfen nur durch die Werkstatt).',
        );
      else if (e instanceof ApiError && e.status === 412)
        setMessage('Die Akte wurde zwischenzeitlich geändert. Bitte neu laden.');
      else if (e instanceof ApiError && e.status === 409)
        setMessage('Diese Entscheidung ist für die Akte im aktuellen Status nicht zulässig.');
      else setMessage('Die Triage konnte nicht gespeichert werden.');
    }
  };

  return (
    <section aria-labelledby="triage-titel">
      <h3 id="triage-titel">Triage</h3>
      <p>
        Vorgeschlagene Klasse (Regelmatrix
        {defect.severity_rule_version ? ` ${defect.severity_rule_version}` : ''}
        ): {suggested ? SEVERITY_LABELS[suggested] : '–'}. Geltende Klasse:{' '}
        {defect.severity ? SEVERITY_LABELS[defect.severity] : 'nicht eingestuft'}.
      </p>
      {message ? <p role="status">{message}</p> : null}
      <div className="tk-field">
        <label htmlFor="triage-klasse">Klasse</label>
        <select
          id="triage-klasse"
          value={severity}
          onChange={(e) => setSeverity(e.target.value as Severity)}
        >
          {(Object.keys(SEVERITY_LABELS) as Severity[]).map((s) => (
            <option key={s} value={s}>
              {SEVERITY_LABELS[s]}
            </option>
          ))}
        </select>
      </div>
      <div className="tk-field">
        <label htmlFor="triage-entscheidung">Folgeentscheidung</label>
        <select
          id="triage-entscheidung"
          value={decision}
          onChange={(e) => setDecision(e.target.value as Decision)}
        >
          {DECISIONS.map((d) => (
            <option key={d.value} value={d.value}>
              {d.label}
            </option>
          ))}
        </select>
      </div>
      <div className="tk-field">
        <label htmlFor="triage-zustaendig">Zuständiger (Kennung, leer = keiner)</label>
        <input
          id="triage-zustaendig"
          value={assignee}
          onChange={(e) => setAssignee(e.target.value)}
        />
      </div>
      <div className="tk-field">
        <label htmlFor="triage-grund">
          Begründung
          {correcting || decision === 'DISMISSED' ? ' (Pflicht)' : ' (für Korrektur und Verwerfen)'}
        </label>
        <input
          id="triage-grund"
          value={reason}
          maxLength={500}
          aria-invalid={reasonMissing}
          onChange={(e) => setReason(e.target.value)}
        />
      </div>
      <button type="button" onClick={() => void submit()}>
        Triage speichern
      </button>
    </section>
  );
}
