import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { Incident, WorkflowLogEntry } from '@taktovia/api-client';
import { DataTable, ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState, StatusTone } from '@taktovia/design-system';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useScreenContext } from '../ScreenContext';
import { LiveStatusBadge, useLiveChannel } from '../live/LiveChannel';
import type { LiveMessage } from '../live/LiveChannel';

const KIND: Readonly<Record<string, string>> = {
  VEHICLE_OUTAGE: 'Fahrzeugausfall',
  DEFECT: 'Schaden',
  REJECTION: 'Ablehnung',
  CRITICAL_DELAY: 'Kritische Verspätung',
};
const STATUS: Readonly<Record<string, string>> = {
  OPEN: 'offen',
  REPLACEMENT_SEARCH: 'Ersatzsuche läuft',
  RESOLVED: 'gelöst',
  CLOSED_NO_REPLACEMENT: 'ohne Ersatz abgeschlossen',
};
const EVENT: Readonly<Record<string, string>> = {
  VEHICLE_BLOCKED: 'Fahrzeug gesperrt',
  DISRUPTION_REPORTED: 'Störung gemeldet',
  CRITICAL_DEFECT: 'Sicherheitskritischer Schaden',
  DEFECT_REPORTED: 'Schaden gemeldet',
  REJECTION: 'Ablehnung',
  CRITICAL_DELAY: 'Kritische Verspätung',
  REPLACEMENT_SEARCH: 'Ersatzfahrzeugsuche',
  DISPATCH: 'Leitstelle',
};
const ACTION: Readonly<Record<string, string>> = {
  EVALUATE_RULE: 'Regel angewendet',
  OPEN_INCIDENT: 'Incident eröffnen',
  START_REPLACEMENT_SEARCH: 'Ersatzfahrzeugsuche starten',
  SEARCH_RESULT: 'Ergebnis der Ersatzsuche',
  NO_ACTION: 'Keine Aktion',
  DISPATCH_DECISION: 'Entscheidung der Leitstelle',
  REJECT_EVENT: 'Ereignis abgewiesen',
  SKIP_EVENT: 'Ereignis übersprungen',
};

type Tone = StatusTone;

/** Ergebnis eines Protokolleintrags als Text und Ton: Fehlgeschlagenes und Übersprungenes ist nie nur an der Farbe erkennbar. */
export function outcomeLabel(e: Pick<WorkflowLogEntry, 'outcome'>): { text: string; tone: Tone } {
  const o = e.outcome;
  if (o.startsWith('FAILED'))
    return { text: `fehlgeschlagen (${o.slice(7) || 'ohne Code'})`, tone: 'error' };
  if (o.startsWith('SKIPPED') || o === 'ALREADY_STARTED' || o === 'INCIDENT_NOT_OPEN')
    return { text: `übersprungen (${o})`, tone: 'warning' };
  if (o === 'NO_ACTION' || o === 'NO_RULE' || o === 'NO_RESULT')
    return { text: 'keine Aktion', tone: 'info' };
  if (o.startsWith('PROPOSALS:')) return { text: `${o.slice(10)} Vorschläge`, tone: 'ok' };
  if (o.startsWith('MATCHED:')) return { text: `Regel passt, ${o.slice(8)} Aktionen`, tone: 'ok' };
  if (o === 'CREATED') return { text: 'eröffnet', tone: 'ok' };
  if (o === 'JOINED') return { text: 'bestehendem Incident zugeordnet', tone: 'info' };
  if (o === 'STARTED') return { text: 'gestartet', tone: 'ok' };
  return { text: o, tone: 'info' };
}

/**
 * UI-07 Leitstelle: Vorfälle (Incidents) und Zeitleiste des Vorfalls (WP-M3-53/-55). Die Liste zeigt Fahrzeug, Ursache, Zustand und Verknüpfungen (Schadenakten, betroffene Fahrten); die
 * Zeitleiste zeigt aus `getOperatorIncident` den Ablauf in der Reihenfolge der Ordnungsnummer: Auslöser, Regelversion, Entscheidung und jede Folgeaktion mit Ergebnis, auch fehlgeschlagene
 * und übersprungene. Das Protokoll enthält nur Codes und Kennungen. Leerzustand, Ladefehler mit letztem Datenstand und Rechtezustand (403 aus dem Server) sind getrennt; die Anzeige ist keine
 * Rechtequelle. Der Live-Kanal stößt bei `incident.*` den Neuabruf an.
 */
export function Vorfaelle() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'warning', label: 'Vorfälle' });
  const [rows, setRows] = useState<ResourceState<Incident[]>>({ status: 'loading' });
  const [asOf, setAsOf] = useState<string | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [detail, setDetail] = useState<ResourceState<Incident> | undefined>(undefined);
  const [openId, setOpenId] = useState<string | undefined>(undefined);
  const last = useRef<Incident[] | undefined>(undefined);

  const load = useCallback(() => {
    api
      .call('listOperatorIncidents', { query: { limit: 100 } })
      .then((page) => {
        last.current = page.items;
        setAsOf(page.as_of);
        setNotice(undefined);
        setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (last.current === undefined)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
        else
          setNotice(
            'Die Liste konnte nicht aktualisiert werden; angezeigt ist der Stand von ' +
              (asOf ? new Date(asOf).toLocaleTimeString('de-DE') : 'unbekannt') +
              '.',
          );
      });
  }, [api, asOf]);

  const show = useCallback(
    (id: string) => {
      setOpenId(id);
      setDetail({ status: 'loading' });
      api
        .call('getOperatorIncident', { path: { incident_id: id } })
        .then((i) => setDetail({ status: 'ready', data: i }))
        .catch((e: unknown) =>
          setDetail({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
        );
    },
    [api],
  );

  useEffect(() => {
    load();
    // nur beim Einhängen laden; der Live-Kanal löst Neuabrufe aus
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const live = useLiveChannel({
    onMessage: (m: LiveMessage) => {
      if (m.topic === 'incident') {
        load();
        if (openId) show(openId);
      }
    },
    onResync: load,
  });

  if (!context || session.status !== 'ready') return null;
  return (
    <ScreenFrame title="Leitstelle: Vorfälle und Ablauf" context={context}>
      <p>
        <LiveStatusBadge status={live} />
      </p>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine Vorfälle',
          hint: 'Für Ihre Dienste liegt derzeit kein Vorfall vor.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Vorfälle der berechtigten Dienste, älteste zuerst"
            rows={data}
            rowKey={(i) => i.id}
            columns={[
              { key: 'kind', header: 'Ursache', render: (i) => KIND[i.kind] ?? i.kind },
              {
                key: 'status',
                header: 'Zustand',
                render: (i) => (
                  <StatusBadge
                    tone={i.status === 'OPEN' ? 'warning' : i.status === 'RESOLVED' ? 'ok' : 'info'}
                  >
                    {STATUS[i.status] ?? i.status}
                  </StatusBadge>
                ),
              },
              { key: 'vehicle', header: 'Fahrzeug', render: (i) => i.vehicle_id ?? '–' },
              {
                key: 'links',
                header: 'Verknüpfungen',
                render: (i) =>
                  `${i.defect_ids?.length ?? 0} Schadenakten, ${i.affected_run_ids.length} Fahrten`,
              },
              {
                key: 'opened',
                header: 'Eröffnet',
                render: (i) => new Date(i.opened_at).toLocaleString('de-DE'),
              },
              {
                key: 'act',
                header: 'Ablauf',
                render: (i) => (
                  <button type="button" onClick={() => show(i.id)}>
                    Ablauf anzeigen
                  </button>
                ),
              },
            ]}
          />
        )}
      </ResourceView>
      {rows.status === 'ready' && asOf ? (
        <p>Datenstand der Liste: {new Date(asOf).toLocaleTimeString('de-DE')}</p>
      ) : null}
      {detail ? (
        <section aria-label="Zeitleiste des Vorfalls">
          <h2>Zeitleiste des Vorfalls</h2>
          <ResourceView
            state={detail}
            isEmpty={(d) => (d.workflow_log ?? []).length === 0}
            empty={{
              title: 'Kein Ablauf protokolliert',
              hint: 'Zu diesem Vorfall liegt noch kein Protokolleintrag vor.',
            }}
          >
            {(d) => (
              <ol>
                {(d.workflow_log ?? []).map((e) => {
                  const o = outcomeLabel(e);
                  return (
                    <li key={e.seq + e.event_id + e.action}>
                      <strong>
                        {e.seq}. {ACTION[e.action] ?? e.action}
                      </strong>{' '}
                      <StatusBadge tone={o.tone}>{o.text}</StatusBadge>
                      <br />
                      <small>
                        {new Date(e.at).toLocaleString('de-DE')}
                        {e.event_type ? `, Auslöser: ${EVENT[e.event_type] ?? e.event_type}` : ''}
                        {e.rule_revision_no ? `, Regelversion ${e.rule_revision_no}` : ''}
                        {e.decision ? `, Entscheidung: ${e.decision}` : ''}
                        {e.reason_code ? `, Grund: ${e.reason_code}` : ''}
                        {`, Akteur: ${e.actor}`}
                        {e.ref ? `, Bezug: ${e.ref}` : ''}
                      </small>
                    </li>
                  );
                })}
              </ol>
            )}
          </ResourceView>
        </section>
      ) : null}
    </ScreenFrame>
  );
}
