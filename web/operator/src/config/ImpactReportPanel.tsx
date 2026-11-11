import type { ConfigImpactReport } from '@taktovia/api-client';
import { DataTable, ResourceView, StatusBadge, zeit } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { DEFAULT_ZONE } from '../ScreenContext';

type Severity = ConfigImpactReport['highest_severity'];

const SEVERITY_LABEL: Record<Severity, string> = {
  NONE: 'keine Konflikte',
  INFO: 'Hinweis',
  WARNING: 'Warnung',
  BLOCKING: 'blockierend',
};
const SEVERITY_TONE: Record<Severity, 'ok' | 'neutral' | 'warning' | 'error'> = {
  NONE: 'ok',
  INFO: 'neutral',
  WARNING: 'warning',
  BLOCKING: 'error',
};
/** Verständliche Bezeichnung der Objektarten, die Fachmodule melden; unbekannte Arten erscheinen unverändert. */
const KIND_LABEL: Record<string, string> = {
  BOOKING: 'Bestätigte Buchungen',
  SHIFT: 'Schichten',
  OFFER: 'Zukünftige Angebote',
};
const MAX_SHOWN_REFS = 5;

/**
 * Wirkungsbericht einer Konfigurationsrevision (WP-M1-23, UI-17 Basis, Kap. 06 §6.8): Konfliktliste mit Art, Schweregrad, Anzahl und Objektverweisen,
 * Vorschau „betroffene zukünftige Angebote“ und der Hinweis, dass bestätigte Zusagen als unverändert gebundene Version bestehen bleiben. Der Bericht enthält
 * nur Objektverweise und Anzahlen, keine Personendaten; er ist unveränderlich und an den geprüften Revisionsstand gebunden.
 */
export function ImpactReportPanel({
  revision,
  state,
  onRetry,
}: {
  revision: number;
  state: ResourceState<ConfigImpactReport>;
  onRetry: () => void;
}) {
  return (
    <section aria-label="Wirkungsbericht">
      <h2>Wirkungsbericht zu Revision {revision}</h2>
      <ResourceView
        state={state}
        isEmpty={() => false}
        empty={{ title: 'Kein Wirkungsbericht vorhanden' }}
        errorAction={
          <button type="button" onClick={onRetry}>
            Erneut versuchen
          </button>
        }
      >
        {(r) => (
          <>
            <p role="status">
              <StatusBadge tone={SEVERITY_TONE[r.highest_severity]}>
                {SEVERITY_LABEL[r.highest_severity]}
              </StatusBadge>{' '}
              {r.conflict_count === 0
                ? 'Die Änderung berührt keine bestehenden Buchungen, Angebote oder Schichten.'
                : `${r.conflict_count} bestehende Objekte wären betroffen.`}
            </p>
            {r.affected.length === 0 ? null : (
              <DataTable
                caption="Konfliktliste: betroffene bestehende Objekte"
                rows={r.affected}
                rowKey={(a) => `${a.kind}/${a.severity}`}
                columns={[
                  { key: 'kind', header: 'Art', render: (a) => KIND_LABEL[a.kind] ?? a.kind },
                  {
                    key: 'sev',
                    header: 'Schweregrad',
                    render: (a) => (
                      <StatusBadge tone={SEVERITY_TONE[a.severity]}>
                        {SEVERITY_LABEL[a.severity]}
                      </StatusBadge>
                    ),
                  },
                  { key: 'count', header: 'Anzahl', render: (a) => a.count },
                  {
                    key: 'refs',
                    header: 'Objektverweise',
                    render: (a) => (
                      <>
                        {a.object_refs.slice(0, MAX_SHOWN_REFS).map((o) => (
                          <code key={o.id}>{o.id} </code>
                        ))}
                        {a.count > MAX_SHOWN_REFS ? `… (${a.count} insgesamt)` : null}
                      </>
                    ),
                  },
                ]}
              />
            )}
            <h3>Vorschau: betroffene zukünftige Angebote</h3>
            <p>
              {r.affected.filter((a) => a.kind === 'OFFER').reduce((n, a) => n + a.count, 0)}{' '}
              zukünftige Angebote wären betroffen. Bereits bestätigte Zusagen bleiben als
              unverändert gebundene Version bestehen; die Änderung wirkt nur auf neue Angebote.
            </p>
            <p>
              Bericht <code>{r.id}</code>, erstellt{' '}
              {zeit.toRfc3339(Date.parse(r.generated_at), DEFAULT_ZONE)} von {r.created_by};
              geprüfter Stand <code>{r.values_hash.slice(0, 12)}</code>. Die Publikation verweist
              auf diesen Bericht und gilt nur für den unveränderten Revisionsstand.
            </p>
          </>
        )}
      </ResourceView>
    </section>
  );
}
