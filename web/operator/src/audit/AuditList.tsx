import { ApiError, useApi } from '@taktovia/api-client';
import type { AuditEvent } from '@taktovia/api-client';
import { DataTable, ResourceView } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useId, useState } from 'react';

type Purpose = 'security_review' | 'incident_response' | 'support_case' | 'compliance_check';

export const PURPOSES: readonly { value: Purpose; label: string }[] = [
  { value: 'security_review', label: 'Sicherheitsprüfung' },
  { value: 'incident_response', label: 'Sicherheitsvorfall' },
  { value: 'support_case', label: 'Supportfall' },
  { value: 'compliance_check', label: 'Compliance-Prüfung' },
];

/**
 * UI-20 Basis: Auditliste des aktiven Mandanten (nur lesend, WP-M1-12). Ohne Zweckangabe wird **keine** Anfrage gesendet (Zustand „fehlender
 * Zweck“); der Server lehnt eine Abfrage ohne Zweck ebenfalls ab (422). Jede Abfrage wird serverseitig selbst auditiert. 401 bedeutet
 * abgelaufenen Zugriff (Sitzung oder Mitgliedschaft nicht mehr gültig), 403 fehlende Berechtigung; in beiden Fällen erscheinen keine Daten.
 * Der Mandant steht nie in der Anfrage. Filter: Akteur, Objekt, Aktion, Zeitraum.
 */
export function AuditList() {
  const api = useApi();
  const ids = useId();
  const [purpose, setPurpose] = useState<Purpose | ''>('');
  const [filter, setFilter] = useState({
    actor_ref: '',
    object_ref: '',
    action: '',
    from: '',
    to: '',
  });
  const [missing, setMissing] = useState(false);
  const [state, setState] = useState<ResourceState<AuditEvent[]> | undefined>(undefined);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | undefined>(undefined);

  const query = (cursor?: string) => ({
    limit: 50,
    purpose: purpose as Purpose,
    ...(cursor ? { cursor } : {}),
    ...(filter.actor_ref.trim() ? { actor_ref: filter.actor_ref.trim() } : {}),
    ...(filter.object_ref.trim() ? { object_ref: filter.object_ref.trim() } : {}),
    ...(filter.action.trim() ? { action: filter.action.trim() } : {}),
    ...(filter.from ? { from: new Date(filter.from).toISOString() } : {}),
    ...(filter.to ? { to: new Date(filter.to).toISOString() } : {}),
  });

  const run = (cursor?: string) => {
    if (purpose === '') {
      setMissing(true);
      setState(undefined);
      return;
    }
    setMissing(false);
    if (!cursor) setState({ status: 'loading' });
    api
      .call('listAuditEvents', { query: query(cursor) })
      .then((page) => {
        setNextCursor(page.next_cursor);
        setAsOf(page.as_of);
        setState((old) =>
          cursor && old?.status === 'ready'
            ? { status: 'ready', data: [...old.data, ...page.items] }
            : { status: 'ready', data: page.items },
        );
      })
      .catch((e: unknown) =>
        setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  const expired = state?.status === 'error' && state.error.status === 401;
  return (
    <section aria-labelledby={`${ids}-h`}>
      <h2 id={`${ids}-h`}>Audit des Mandanten</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          run();
        }}
      >
        <label htmlFor={`${ids}-purpose`}>Zweck der Abfrage</label>
        <select
          id={`${ids}-purpose`}
          value={purpose}
          aria-invalid={missing ? 'true' : undefined}
          onChange={(e) => setPurpose(e.target.value as Purpose | '')}
        >
          <option value="">Bitte wählen</option>
          {PURPOSES.map((p) => (
            <option key={p.value} value={p.value}>
              {p.label}
            </option>
          ))}
        </select>
        {(['actor_ref', 'object_ref', 'action'] as const).map((k) => (
          <label key={k}>
            {{ actor_ref: 'Akteur', object_ref: 'Objekt', action: 'Aktion' }[k]}
            <input
              value={filter[k]}
              onChange={(e) => setFilter({ ...filter, [k]: e.target.value })}
            />
          </label>
        ))}
        <label>
          Von
          <input
            type="datetime-local"
            value={filter.from}
            onChange={(e) => setFilter({ ...filter, from: e.target.value })}
          />
        </label>
        <label>
          Bis
          <input
            type="datetime-local"
            value={filter.to}
            onChange={(e) => setFilter({ ...filter, to: e.target.value })}
          />
        </label>
        <button type="submit">Abfragen</button>
      </form>
      <p>Jede Abfrage wird selbst protokolliert (Akteur, Zweck, Filter).</p>
      {missing ? (
        <p role="alert">
          Zweck fehlt: Ohne Zweckangabe werden keine Auditdaten gelesen. Bitte einen Zweck wählen.
        </p>
      ) : null}
      {expired ? (
        <p role="alert">
          Der Zugriff ist abgelaufen. Bitte erneut anmelden; es werden keine Daten angezeigt.
        </p>
      ) : null}
      {state && !expired ? (
        <ResourceView
          state={state}
          isEmpty={(d) => d.length === 0}
          empty={{
            title: 'Keine Auditeinträge für diese Auswahl',
            hint: 'Zeitraum oder Filter anpassen.',
          }}
        >
          {(data) => (
            <DataTable
              caption={`Auditeinträge${asOf ? `, Stand ${asOf}` : ''}`}
              rows={data}
              rowKey={(r) => r.id}
              columns={[
                { key: 'when', header: 'Zeitpunkt', render: (r) => r.occurred_at },
                { key: 'actor', header: 'Akteur', render: (r) => r.actor_ref },
                { key: 'action', header: 'Aktivität', render: (r) => r.action },
                { key: 'object', header: 'Objekt', render: (r) => r.object_ref },
              ]}
              page={{ nextCursor, loading: false, onLoadMore: (c) => run(c) }}
            />
          )}
        </ResourceView>
      ) : null}
    </section>
  );
}
