import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { Alert } from '@taktovia/api-client';
import { DataTable, ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useScreenContext } from '../ScreenContext';
import { LiveStatusBadge, useLiveChannel } from '../live/LiveChannel';
import type { LiveMessage } from '../live/LiveChannel';

type Cause = Alert['acknowledgement'] extends infer A
  ? A extends { cause: infer C }
    ? NonNullable<C>
    : never
  : never;

const PRIORITY: Readonly<Record<string, string>> = {
  CRITICAL: 'kritisch',
  HIGH: 'hoch',
  NORMAL: 'normal',
};
const KIND: Readonly<Record<string, string>> = {
  VEHICLE_BLOCK_CRITICAL: 'Kritische Sperre',
  TECHNICAL_EVENT: 'Technikereignis',
  OVERDUE: 'Überfälligkeit',
  VEHICLE_OUTAGE: 'Fahrzeugausfall',
  PLAN_ACK_OVERDUE: 'Planänderung nicht bestätigt',
};
const CAUSES: ReadonlyArray<readonly [Cause, string]> = [
  ['HANDLED', 'Bearbeitet'],
  ['REPAIRED', 'Behoben'],
  ['NOT_RELEVANT', 'Nicht relevant'],
  ['FALSE_ALARM', 'Fehlalarm'],
  ['ESCALATED_ELSEWHERE', 'Anderweitig eskaliert'],
  ['OTHER', 'Sonstiges'],
];

const kindLabel = (k: string): string =>
  KIND[k] ?? (k.startsWith('TECH_') ? `Technischer Alarm (${k.slice(5)})` : k);

/** Alter in Minuten (abgerundet), nie negativ; `now` ist testbar. */
export function ageMinutes(createdAt: string, now: number): number {
  const ms = now - Date.parse(createdAt);
  return Number.isFinite(ms) ? Math.max(0, Math.floor(ms / 60_000)) : 0;
}

/**
 * UI-07 Leitstelle: Arbeitsliste „Offene Störungen“ (WP-M3-52, Alarm-Grundlage): Alarme der berechtigten Dienste mit Priorität, Alter, Eskalation und Quittierung. Der Live-Kanal (WP-M3-35) stößt
 * bei `alert.*`-Ereignissen den Neuabruf an; fehlt der Kanal, kennzeichnet die Ansicht sich als veraltet und zeigt den Datenstand. Leerzustand, Ladefehler mit letztem Datenstand und
 * Quittierung mit Ursache. Die Anzeige ist keine Rechtequelle: der Server prüft Rolle, Dienst und Revision (412 bei gleichzeitiger Quittierung) bei jeder Anfrage. Ein Alarm enthält nur Codes und
 * Kennungen, keine Fahrgastdaten.
 */
export function Arbeitsliste({ now = () => Date.now() }: { now?: () => number } = {}) {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'warning', label: 'Offene Störungen' });
  const canAck = session.status === 'ready' && hasRight(session.me, 'alerts.ack');
  const [rows, setRows] = useState<ResourceState<Alert[]>>({ status: 'loading' });
  const [asOf, setAsOf] = useState<string | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [cause, setCause] = useState<Record<string, Cause>>({});
  const last = useRef<Alert[] | undefined>(undefined);

  const load = useCallback(() => {
    api
      .call('listOperatorAlerts', { query: { limit: 100 } })
      .then((page) => {
        last.current = page.items;
        setAsOf(page.as_of);
        setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        // Ladefehler: die zuletzt gezeigte Liste bleibt mit ihrem Datenstand erhalten, der Fehler steht davor
        if (last.current === undefined)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
        else
          setNotice(
            'Die Liste konnte nicht aktualisiert werden; angezeigt ist der Stand von ' +
              (asOf ?? 'unbekannt') +
              '.',
          );
      });
  }, [api, asOf]);

  useEffect(() => {
    load();
    // nur beim Einhängen laden; der Live-Kanal löst Neuabrufe aus
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const live = useLiveChannel({
    onMessage: (m: LiveMessage) => {
      if (m.topic === 'alert') load();
    },
    onResync: load,
  });

  const acknowledge = async (a: Alert) => {
    setNotice(undefined);
    try {
      await api.call('acknowledgeOperatorAlert', {
        path: { alert_id: a.id },
        body: { cause: cause[a.id] ?? 'HANDLED' },
        revision: a.revision,
      });
      setNotice('Der Alarm wurde quittiert.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 412) {
        setNotice(
          'Der Alarm wurde gleichzeitig geändert, etwa von einer anderen Person quittiert. Die Liste wird neu geladen.',
        );
        load();
      } else if (e instanceof ApiError && e.status === 403)
        setNotice('Ihnen fehlt das Recht, Alarme zu quittieren.');
      else if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
        setNotice('Der Alarm ist nicht mehr offen oder nicht mehr sichtbar.');
        load();
      } else setNotice('Der Alarm konnte nicht quittiert werden.');
    }
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Leitstelle: Offene Störungen" context={context}>
      <p>
        <LiveStatusBadge status={live} />
      </p>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine offenen Störungen',
          hint: 'Für Ihre Dienste liegt derzeit kein offener Alarm vor.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Offene Störungen der berechtigten Dienste, älteste zuerst"
            rows={data}
            rowKey={(a) => a.id}
            columns={[
              {
                key: 'prio',
                header: 'Priorität',
                render: (a) => (
                  <StatusBadge
                    tone={
                      a.priority === 'CRITICAL'
                        ? 'error'
                        : a.priority === 'HIGH'
                          ? 'warning'
                          : 'info'
                    }
                  >
                    {PRIORITY[a.priority] ?? a.priority}
                  </StatusBadge>
                ),
              },
              { key: 'kind', header: 'Störung', render: (a) => kindLabel(a.kind) },
              {
                key: 'age',
                header: 'Alter',
                render: (a) => `${ageMinutes(a.created_at, now())} Min.`,
              },
              {
                key: 'esc',
                header: 'Eskalation',
                render: (a) =>
                  a.escalation_level > 0
                    ? `Stufe ${a.escalation_level}${a.escalated_at ? ` seit ${new Date(a.escalated_at).toLocaleTimeString('de-DE')}` : ''}`
                    : a.escalate_at
                      ? `geplant ${new Date(a.escalate_at).toLocaleTimeString('de-DE')}`
                      : 'nein',
              },
              {
                key: 'ref',
                header: 'Bezug',
                render: (a) =>
                  a.vehicle_id
                    ? `Fahrzeug ${a.vehicle_id}`
                    : a.ref_id
                      ? `${a.ref_type ?? 'Objekt'} ${a.ref_id}`
                      : '–',
              },
              {
                key: 'act',
                header: 'Quittierung',
                render: (a) =>
                  a.state !== 'OPEN' ? (
                    `quittiert${a.acknowledgement ? ` von ${a.acknowledgement.acknowledged_by}` : ''}`
                  ) : canAck ? (
                    <span>
                      <label>
                        Ursache zu {kindLabel(a.kind)}{' '}
                        <select
                          value={cause[a.id] ?? 'HANDLED'}
                          onChange={(e) =>
                            setCause((c) => ({ ...c, [a.id]: e.target.value as Cause }))
                          }
                        >
                          {CAUSES.map(([v, l]) => (
                            <option key={v} value={v}>
                              {l}
                            </option>
                          ))}
                        </select>
                      </label>{' '}
                      <button type="button" onClick={() => void acknowledge(a)}>
                        Quittieren
                      </button>
                    </span>
                  ) : (
                    'offen'
                  ),
              },
            ]}
          />
        )}
      </ResourceView>
      {rows.status === 'ready' && asOf ? (
        <p>Datenstand der Liste: {new Date(asOf).toLocaleTimeString('de-DE')}</p>
      ) : null}
    </ScreenFrame>
  );
}
