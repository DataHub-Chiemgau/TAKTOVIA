import { ApiError, useApi } from '@taktovia/api-client';
import type { OperatorFlagItem } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

/** Ursachen der Hinweise in der Sprache der Leitstelle (Arbeitsannahme OP-06). */
export const CAUSE_LABELS: Readonly<Record<OperatorFlagItem['cause'], string>> = {
  PICKUP_OVERDUE: 'Abholung überfällig',
  POSITION_STALE: 'Standort veraltet',
  PLAN_ACK_MISSING: 'Fahrerbestätigung der Planänderung fehlt',
  REVIEW_REQUIRED_OPEN: 'Fahrerereignis wartet auf Klärung',
};

const shortId = (id: string): string => id.slice(0, 8);
const timeOf = (iso: string): string =>
  new Date(iso).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' });

function FlagTable({ title, rows }: { title: string; rows: OperatorFlagItem[] }) {
  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p>Keine offenen Hinweise dieser Art.</p>
      ) : (
        <table className="tk-table">
          <caption>{title}</caption>
          <thead>
            <tr>
              <th scope="col">Ursache</th>
              <th scope="col">Betroffene Buchungen</th>
              <th scope="col">Fahrzeug</th>
              <th scope="col">Seit</th>
              <th scope="col">Schwelle</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((f) => (
              <tr key={f.id}>
                <th scope="row">
                  <StatusBadge tone={f.kind === 'OVERDUE' ? 'error' : 'warning'}>
                    {CAUSE_LABELS[f.cause]}
                  </StatusBadge>
                </th>
                <td>{f.affected_count}</td>
                <td>{f.vehicle_id ? shortId(f.vehicle_id) : 'ohne Fahrzeug'}</td>
                <td>{timeOf(f.opened_at)}</td>
                <td>{f.threshold_min === null ? '–' : `${f.threshold_min} min`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/**
 * UI-07 Arbeitsliste offener Hinweise (WP-M3-42, LH-6.7-OPS-006): Überfälligkeit (Verspätung) und ausstehende Informationen stehen **getrennt** in je einem Abschnitt, mit der Zahl der betroffenen
 * Buchungen. Zustände: laden, Leerzustand (keine offenen Hinweise), Fehler mit Wiederholen. Es erscheinen nur Hinweise der berechtigten Dienste; Namen gibt es nicht.
 */
export function OverdueFlags() {
  const api = useApi();
  const context = useScreenContext({ tone: 'ok', label: 'Hinweise' });
  const [rows, setRows] = useState<ResourceState<OperatorFlagItem[]>>({ status: 'loading' });
  const [asOf, setAsOf] = useState<string | undefined>(undefined);

  const load = useCallback(() => {
    api
      .call('listOperatorFlags', { query: { limit: 100 } })
      .then((page) => {
        setAsOf(page.as_of);
        setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) =>
        setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  }, [api]);

  useEffect(() => {
    load();
  }, [load]);

  if (!context) return null;
  return (
    <ScreenFrame title="Leitstelle: Überfälligkeit und ausstehende Informationen" context={context}>
      <p>
        Datenstand: {asOf ? new Date(asOf).toLocaleTimeString('de-DE') : 'wird geladen'}.
        Überfälligkeit (Verspätung) und ausstehende Informationen sind getrennte Zustände.
      </p>
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine offenen Hinweise',
          hint: 'Derzeit ist in Ihren Diensten nichts überfällig und keine Information ausstehend.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(list) => (
          <>
            <FlagTable
              title="Überfälligkeit (Verspätung)"
              rows={list.filter((f) => f.kind === 'OVERDUE')}
            />
            <FlagTable
              title="Information ausstehend"
              rows={list.filter((f) => f.kind === 'INFO_PENDING')}
            />
          </>
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
