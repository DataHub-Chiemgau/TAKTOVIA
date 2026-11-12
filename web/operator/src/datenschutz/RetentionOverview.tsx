import { ApiError, useApi } from '@taktovia/api-client';
import type { ErasureJournalEntry, RetentionRule } from '@taktovia/api-client';
import { DataTable, ResourceView, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useState } from 'react';

interface Overview {
  rules: RetentionRule[];
  journal: ErasureJournalEntry[];
}

const ACTION_LABEL: Record<RetentionRule['action'], string> = {
  ARCHIVE: 'Archivieren',
  DELETE: 'Löschen',
  ANONYMIZE: 'Anonymisieren',
};

/**
 * UI-20 Basis (WP-M1-33): Regelübersicht und Löschprotokoll des aktiven Mandanten, nur lesend. Fristen sind nicht freigegeben (D-11): angezeigt werden nur
 * die vom Mandanten gepflegten Regeln mit Freigabestatus (nie nur als Farbe). Das Löschprotokoll nennt je Objekt Art, Kennung und Aktion, nie Personendaten.
 * Der Mandant steht nie in der Anfrage; 403 und 401 zeigen keine Daten.
 */
export function RetentionOverview() {
  const api = useApi();
  const [state, setState] = useState<ResourceState<Overview>>({ status: 'loading' });

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.call('listRetentionRules', { query: { limit: 100 } }),
      api.call('listErasureJournal', { query: { limit: 100 } }),
    ])
      .then(([rules, journal]) => {
        if (!cancelled)
          setState({ status: 'ready', data: { rules: rules.items, journal: journal.items } });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  return (
    <section aria-labelledby="retention-h">
      <h2 id="retention-h">Aufbewahrungsregeln und Löschprotokoll</h2>
      <ResourceView
        state={state}
        isEmpty={(d) => d.rules.length === 0 && d.journal.length === 0}
        empty={{
          title: 'Noch keine Aufbewahrungsregeln',
          hint: 'Regeln entstehen als Entwurf und gelten erst nach Freigabe durch eine zweite Person.',
        }}
      >
        {(d) => (
          <>
            <DataTable
              caption="Aufbewahrungsregeln"
              rows={d.rules}
              rowKey={(r) => r.id}
              columns={[
                { key: 'class', header: 'Datenklasse', render: (r) => r.data_category },
                { key: 'days', header: 'Frist (Tage)', render: (r) => String(r.retention_days) },
                { key: 'action', header: 'Aktion', render: (r) => ACTION_LABEL[r.action] },
                {
                  key: 'status',
                  header: 'Status',
                  render: (r) =>
                    r.status === 'APPROVED' ? (
                      <StatusBadge tone="ok">Freigegeben</StatusBadge>
                    ) : (
                      <StatusBadge tone="warning">Entwurf</StatusBadge>
                    ),
                },
              ]}
            />
            <DataTable
              caption="Löschprotokoll (ohne Personendaten)"
              rows={d.journal}
              rowKey={(r) => r.id}
              columns={[
                { key: 'when', header: 'Zeitpunkt', render: (r) => r.applied_at },
                { key: 'obj', header: 'Objekt', render: (r) => r.object_ref },
                { key: 'action', header: 'Aktion', render: (r) => ACTION_LABEL[r.action] },
              ]}
            />
          </>
        )}
      </ResourceView>
    </section>
  );
}
