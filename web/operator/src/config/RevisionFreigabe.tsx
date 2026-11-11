import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type {
  ConfigImpactReport,
  ConfigRevision,
  ConfigValidationReport,
  PublishedRevision,
} from '@taktovia/api-client';
import { StatusBadge, zeit } from '@taktovia/design-system';
import { useState } from 'react';
import { DEFAULT_ZONE } from '../ScreenContext';

type Violation = NonNullable<ConfigValidationReport['violations']>[number];

const STATE_LABEL: Record<ConfigRevision['state'], string> = {
  DRAFT: 'Entwurf',
  VALIDATED: 'geprüft',
  APPROVED: 'freigegeben',
  PUBLISHED: 'publiziert',
  SUPERSEDED: 'abgelöst',
};
const time = (iso: string) => zeit.toRfc3339(Date.parse(iso), DEFAULT_ZONE);

/** Meldung zu einem Fehlerantwort-Status: eigener Text je Zustand der Oberfläche (412-Konflikt, konkurrierende Version, fehlendes Recht, Prüfverletzungen). */
type Notice = { tone: 'error' | 'ok'; text: string; violations?: Violation[]; stale?: boolean };

/**
 * UI-17 Basis „Revision freigeben“ (WP-M1-22, LH-I.7-CFG-006): zeigt Validator-Ergebnis, Ersteller und Prüfer sowie das Wirksamkeitsdatum einer Konfigurationsrevision
 * und führt Prüfen, Freigeben (durch eine zweite Person) und Veröffentlichen aus. Ein unzulässiger Sicherheitswert blockiert Freigabe und Veröffentlichung; der Server prüft
 * alles erneut (die Oberfläche ist nie die Instanz der Prüfung). Zustände: konkurrierende Version (409), veralteter Stand (412 mit „Aktuellen Stand laden“), fehlendes Recht (403).
 * Bedienbar per Tastatur (native Schaltflächen, Fokusreihenfolge = Ablauf), Ergebnisse in `role="status"` bzw. `role="alert"` für Screenreader.
 */
export function RevisionFreigabe({
  revision,
  onChanged,
}: {
  revision: ConfigRevision;
  onChanged?: (r: ConfigRevision) => void;
}) {
  const api = useApi();
  const session = useSession();
  const [current, setCurrent] = useState<ConfigRevision>(revision);
  const [validation, setValidation] = useState<ConfigValidationReport | undefined>(undefined);
  const [report, setReport] = useState<ConfigImpactReport | undefined>(undefined);
  const [published, setPublished] = useState<PublishedRevision | undefined>(undefined);
  const [notice, setNotice] = useState<Notice | undefined>(undefined);
  const [busy, setBusy] = useState(false);

  const me = session.status === 'ready' ? session.me : undefined;
  const isCreator = me !== undefined && current.created_by === `user:${me.subject}`;
  const unsafe = (validation?.violations ?? []).some((v) => v.code === 'SAFETY_INVARIANT_VIOLATED');
  const invalid = validation !== undefined && !validation.valid;
  const rowVersion = current.row_version;

  const update = (next: ConfigRevision) => {
    setCurrent(next);
    onChanged?.(next);
  };

  const fail = (e: unknown) => {
    const status = e instanceof ApiError ? e.status : 0;
    const violations = e instanceof ApiError ? e.problem?.violations : undefined;
    if (status === 412 || status === 428)
      setNotice({
        tone: 'error',
        stale: true,
        text: 'Konflikt (412): Die Revision wurde zwischenzeitlich geändert. Bitte den aktuellen Stand laden und erneut prüfen.',
      });
    else if (status === 409 && e instanceof ApiError && e.problem?.code === 'CONFLICT')
      setNotice({
        tone: 'error',
        text: 'Konkurrierende Version: Für diesen Bereich ist bereits eine Revision mit überlappender Wirksamkeit veröffentlicht oder der Stand hat sich geändert. Die Veröffentlichung wurde nicht durchgeführt.',
        stale: true,
      });
    else if (status === 409)
      setNotice({
        tone: 'error',
        text: 'Diese Aktion ist im aktuellen Zustand der Revision nicht möglich (409).',
        stale: true,
      });
    else if (status === 403)
      setNotice({
        tone: 'error',
        text: 'Dafür fehlt das Recht, oder die Freigabe muss durch eine zweite Person erfolgen (403).',
      });
    else if (status === 422)
      setNotice({
        tone: 'error',
        text: 'Die Eingabe wurde abgewiesen (422). Die Veröffentlichung ist blockiert.',
        violations: violations ?? [],
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

  const validate = () =>
    run(async () => {
      const r = await api.call('validateConfigRevision', {
        path: { revision_id: current.id },
        ...(rowVersion === undefined ? {} : { revision: rowVersion }),
      });
      setValidation(r);
      if (r.state && r.row_version !== undefined)
        update({ ...current, state: r.state, row_version: r.row_version });
      setNotice(
        r.valid
          ? { tone: 'ok', text: 'Die Prüfung war erfolgreich.' }
          : { tone: 'error', text: 'Die Prüfung hat Verstöße ergeben.', violations: r.violations },
      );
    });

  const approve = () =>
    run(async () => {
      const r = await api.call('approveConfigRevision', {
        path: { revision_id: current.id },
        ...(rowVersion === undefined ? {} : { revision: rowVersion }),
      });
      update(r);
      setNotice({
        tone: 'ok',
        text: `Freigegeben von ${r.approved_by ?? 'einer zweiten Person'}.`,
      });
    });

  const analyze = () =>
    run(async () => {
      const r = await api.call('requestConfigImpact', {
        path: { revision_id: current.id },
        ...(rowVersion === undefined ? {} : { revision: rowVersion }),
      });
      setReport(r);
    });

  const publish = () =>
    run(async () => {
      if (!report) return;
      const r = await api.call('publishConfig', {
        path: { revision_id: current.id },
        body: {
          effective_from: current.effective_from,
          reason: current.reason ?? 'Veröffentlichung',
          impact_report_id: report.id,
        },
        ...(rowVersion === undefined ? {} : { revision: rowVersion }),
      });
      setPublished(r);
      update({ ...current, state: 'PUBLISHED', row_version: r.revision });
      setNotice({ tone: 'ok', text: 'Die Revision ist veröffentlicht.' });
    });

  const reload = () =>
    run(async () => {
      const r = await api.call('getConfigRevision', { path: { revision_id: current.id } });
      setValidation(undefined);
      setReport(undefined);
      update(r);
    });

  const canValidate = current.state === 'DRAFT' && !busy;
  const canApprove = current.state === 'VALIDATED' && !busy && !isCreator && !unsafe;
  const canAnalyze = (current.state === 'APPROVED' || current.state === 'VALIDATED') && !busy;
  const canPublish = current.state === 'APPROVED' && report !== undefined && !busy && !unsafe;

  return (
    <section aria-label="Revision freigeben">
      <h2>Revision {current.revision} freigeben</h2>
      <dl>
        <dt>Status</dt>
        <dd>
          <StatusBadge tone={current.state === 'PUBLISHED' ? 'ok' : 'neutral'}>
            {STATE_LABEL[current.state]}
          </StatusBadge>
        </dd>
        <dt>Ersteller</dt>
        <dd>{current.created_by}</dd>
        <dt>Prüfer (Freigabe durch eine zweite Person)</dt>
        <dd>{current.approved_by ?? 'noch nicht freigegeben'}</dd>
        <dt>Wirksamkeitsdatum</dt>
        <dd>ab {time(current.effective_from)}</dd>
        <dt>Grund</dt>
        <dd>{current.reason ?? '–'}</dd>
      </dl>

      <h3>Validator-Ergebnis</h3>
      {validation === undefined ? (
        <p>Noch nicht geprüft.</p>
      ) : (
        <p role="status">
          <StatusBadge tone={validation.valid ? 'ok' : 'error'}>
            {validation.valid ? 'gültig' : 'ungültig'}
          </StatusBadge>{' '}
          {validation.valid
            ? 'Alle Werte liegen innerhalb der Systemgrenzen und erfüllen die Modellregeln.'
            : `${validation.violations.length} Verstoß/Verstöße.`}
        </p>
      )}
      {unsafe ? (
        <p role="alert">
          Unzulässiger Sicherheitswert: Freigabe und Veröffentlichung sind blockiert, bis der Wert
          korrigiert ist.
        </p>
      ) : null}
      {invalid && !unsafe ? (
        <p role="alert">Die Revision ist nicht gültig; die Veröffentlichung ist blockiert.</p>
      ) : null}

      <div role="group" aria-label="Schritte der Freigabe">
        <button type="button" onClick={validate} disabled={!canValidate}>
          Prüfen
        </button>{' '}
        <button type="button" onClick={approve} disabled={!canApprove}>
          Freigeben (zweite Person)
        </button>{' '}
        <button type="button" onClick={analyze} disabled={!canAnalyze}>
          Wirkung prüfen
        </button>{' '}
        <button type="button" onClick={publish} disabled={!canPublish}>
          Veröffentlichen
        </button>
      </div>
      {isCreator && current.state === 'VALIDATED' ? (
        <p>Sie haben diese Revision erstellt; die Freigabe muss eine zweite Person vornehmen.</p>
      ) : null}
      {report ? (
        <p role="status">
          Wirkungsbericht <code>{report.id}</code>:{' '}
          {report.conflict_count === 0
            ? 'keine Konflikte'
            : `${report.conflict_count} betroffene bestehende Objekte`}
          . Die Veröffentlichung verweist auf diesen Bericht.
        </p>
      ) : null}

      {notice ? (
        <div role={notice.tone === 'error' ? 'alert' : 'status'}>
          <p>{notice.text}</p>
          {notice.violations && notice.violations.length > 0 ? (
            <ul>
              {notice.violations.map((v) => (
                <li key={`${v.pointer}/${v.code}`}>
                  <code>{v.pointer}</code>: {v.message}
                </li>
              ))}
            </ul>
          ) : null}
          {notice.stale ? (
            <button type="button" onClick={reload}>
              Aktuellen Stand laden
            </button>
          ) : null}
        </div>
      ) : null}
      {published ? (
        <p role="status">
          Veröffentlicht, wirksam ab {time(published.effective_from)}. Auditeintrag{' '}
          <code>{published.audit_event_id}</code>.
        </p>
      ) : null}
    </section>
  );
}
