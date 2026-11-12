import { ApiError } from '@taktovia/api-client';
import type { useApi } from '@taktovia/api-client';
import type {
  Service,
  GeoServiceArea,
  GeoServiceAreaPassenger,
  VehicleMaster,
  VehicleServiceAssignment,
} from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';

type Api = ReturnType<typeof useApi>;

const NEVER_ENDS = 'unbegrenzt';
const when = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleString('de-DE') : NEVER_ENDS;

/** Datum und Uhrzeit aus einem `datetime-local`-Feld als absoluter Zeitpunkt (ISO 8601, UTC); ein ungültiger Text bleibt Text, damit der Server mit Feldverweis (422) antwortet. */
const instant = (s: string): string => {
  if (s.trim() === '') return s;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? s : d.toISOString();
};

/** Zustand einer Zuordnung zum Zeitpunkt `now`: geplant (noch nicht begonnen), aktiv (Intervall links geschlossen, rechts offen) oder beendet. */
export function assignmentState(
  a: VehicleServiceAssignment,
  now: number,
): 'geplant' | 'aktiv' | 'beendet' {
  if (new Date(a.valid_from).getTime() > now) return 'geplant';
  if (a.valid_to !== null && new Date(a.valid_to).getTime() <= now) return 'beendet';
  return 'aktiv';
}

/**
 * UI-11 Flotte/Werkstatt: Zuordnungstabelle je Fahrzeug (WP-M3-06, LH-6.8-FLT-002). Ein Fahrzeug kann gleichzeitig mehreren Diensten zugeordnet sein, jede Zuordnung gilt für ein
 * Intervall (Beginn eingeschlossen, Ende ausgeschlossen) und optional für ein Bediengebiet des Dienstes. Beim Beenden bleiben zugesagte Fahrten bestehen und werden als Hinweis für
 * die Leitstelle angezeigt (kein stilles Entfernen). Zustände: laden, Fehler mit Wiederholung, Leerzustand, Rechtezustand (ohne Schreibrecht nur Anzeige). Die Anzeige ist keine
 * Rechtequelle; der Server prüft jede Anfrage.
 */
export function Dienstzuordnung({
  api,
  vehicle,
  canWrite,
}: {
  api: Api;
  vehicle: VehicleMaster;
  canWrite: boolean;
}) {
  const [rows, setRows] = useState<ResourceState<VehicleServiceAssignment[]>>({
    status: 'loading',
  });
  const [services, setServices] = useState<Service[] | undefined>(undefined);
  const [areas, setAreas] = useState<(GeoServiceArea | GeoServiceAreaPassenger)[]>([]);
  const [draft, setDraft] = useState({
    service_id: '',
    service_area_id: '',
    valid_from: '',
    valid_to: '',
  });
  const [endAt, setEndAt] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listVehicleServiceAssignments', {
        path: { vehicle_id: vehicle.id },
        query: { limit: 100 },
      })
      .then((page) => {
        if (!cancelled) {
          setNow(Date.now());
          setRows({ status: 'ready', data: page.items });
        }
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    api
      .call('listServices', { query: { limit: 100 } })
      .then((p) => {
        if (!cancelled) setServices(p.items);
      })
      .catch(() => {
        if (!cancelled) setServices(undefined);
      });
    return () => {
      cancelled = true;
    };
  }, [api, vehicle.id, tick]);

  // Gebiete des gewählten Dienstes (optional; ohne Recht bleibt die Auswahl leer, die Zuordnung gilt dann ohne Gebietsbezug)
  useEffect(() => {
    let cancelled = false;
    if (draft.service_id !== '')
      api
        .call('listServiceAreas', { query: { service_id: draft.service_id, limit: 100 } })
        .then((p) => {
          if (!cancelled) setAreas(p.items);
        })
        .catch(() => {
          if (!cancelled) setAreas([]);
        });
    return () => {
      cancelled = true;
    };
  }, [api, draft.service_id]);

  const serviceName = (id: string): string => services?.find((s) => s.id === id)?.name ?? id;

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht, Dienstzuordnungen zu pflegen.';
    if (e.status === 404) return 'Fahrzeug, Dienst oder Gebiet wurde nicht gefunden.';
    if (e.status === 409)
      return 'Konflikt: Das Fahrzeug ist diesem Dienst im Zeitraum bereits zugeordnet, oder die Zuordnung ist schon beendet.';
    if (e.status === 412) return 'Die Zuordnung wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      await api.call('createVehicleServiceAssignment', {
        path: { vehicle_id: vehicle.id },
        body: {
          service_id: draft.service_id.trim(),
          service_area_id: draft.service_area_id === '' ? null : draft.service_area_id,
          valid_from: instant(draft.valid_from),
          valid_to: draft.valid_to.trim() === '' ? null : instant(draft.valid_to),
        },
      });
      setDraft({ service_id: '', service_area_id: '', valid_from: '', valid_to: '' });
      setNotice('Das Fahrzeug wurde dem Dienst zugeordnet.');
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else setNotice(explain(e, 'Die Zuordnung konnte nicht gespeichert werden.'));
    }
  };

  const end = async (a: VehicleServiceAssignment) => {
    setNotice(undefined);
    try {
      const ended = await api.call('endVehicleServiceAssignment', {
        path: { vehicle_id: vehicle.id, assignment_id: a.id },
        body: { valid_to: instant(endAt[a.id] ?? '') },
        revision: a.revision,
      });
      const n = ended.committed_rides_after_end.length;
      setNotice(
        n > 0
          ? `Die Zuordnung wurde beendet. Hinweis an die Leitstelle: ${n} zugesagte Fahrt(en) liegen nach dem Ende; sie bleiben bestehen und müssen umgeplant werden.`
          : 'Die Zuordnung wurde beendet.',
      );
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422)
        setNotice(
          'Das Ende muss nach dem Beginn liegen und darf nicht in der Vergangenheit liegen.',
        );
      else setNotice(explain(e, 'Die Zuordnung konnte nicht beendet werden.'));
    }
  };

  return (
    <section aria-labelledby="zuordnung-titel">
      <h2 id="zuordnung-titel">Dienstzuordnungen {vehicle.vehicle_label ?? ''}</h2>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Dieses Fahrzeug ist keinem Dienst zugeordnet',
          hint: canWrite
            ? 'Ohne Zuordnung erhält das Fahrzeug keine Fahrten. Ordnen Sie es unten einem Dienst zu.'
            : 'Ohne Zuordnung erhält das Fahrzeug keine Fahrten.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Dienstzuordnungen des Fahrzeugs"
            rows={data}
            rowKey={(a) => a.id}
            columns={[
              { key: 'service', header: 'Dienst', render: (a) => serviceName(a.service_id) },
              {
                key: 'area',
                header: 'Gebiet',
                render: (a) =>
                  a.service_area_id ? 'eingeschränkt auf Bediengebiet' : 'ohne Einschränkung',
              },
              { key: 'from', header: 'Gültig ab', render: (a) => when(a.valid_from) },
              { key: 'to', header: 'Gültig bis (ausschließlich)', render: (a) => when(a.valid_to) },
              { key: 'state', header: 'Status', render: (a) => assignmentState(a, now) },
              {
                key: 'rides',
                header: 'Zugesagte Fahrten nach dem Ende',
                render: (a) =>
                  a.committed_rides_after_end.length > 0
                    ? `${a.committed_rides_after_end.length} (bleiben bestehen, Umplanung nötig)`
                    : '',
              },
              {
                key: 'act',
                header: 'Aktion',
                render: (a) =>
                  canWrite && a.valid_to === null ? (
                    <span>
                      <label>
                        Ende für {serviceName(a.service_id)}{' '}
                        <input
                          type="datetime-local"
                          value={endAt[a.id] ?? ''}
                          onChange={(e) => setEndAt({ ...endAt, [a.id]: e.target.value })}
                        />
                      </label>{' '}
                      <button type="button" onClick={() => void end(a)}>
                        Beenden {serviceName(a.service_id)}
                      </button>
                    </span>
                  ) : null,
              },
            ]}
          />
        )}
      </ResourceView>
      {canWrite ? (
        <Form onSubmit={create} submitLabel="Zuordnen" problem={problem}>
          {services ? (
            <div className="tk-field">
              <label htmlFor="zuordnung-dienst">Dienst</label>
              <select
                id="zuordnung-dienst"
                value={draft.service_id}
                onChange={(e) =>
                  setDraft({ ...draft, service_id: e.target.value, service_area_id: '' })
                }
              >
                <option value="">Bitte wählen</option>
                {services.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <Field
              pointer="/service_id"
              label="Dienst (ID)"
              value={draft.service_id}
              onChange={(e) =>
                setDraft({ ...draft, service_id: e.target.value, service_area_id: '' })
              }
            />
          )}
          <div className="tk-field">
            <label htmlFor="zuordnung-gebiet">Bediengebiet (optional)</label>
            <select
              id="zuordnung-gebiet"
              value={draft.service_area_id}
              onChange={(e) => setDraft({ ...draft, service_area_id: e.target.value })}
            >
              <option value="">Ohne Einschränkung</option>
              {areas
                .filter((a) => a.service_id === draft.service_id)
                .map((a) => (
                  <option key={a.id} value={a.id}>
                    {`Gebiet Revision ${a.revision}`}
                  </option>
                ))}
            </select>
          </div>
          <Field
            pointer="/valid_from"
            label="Gültig ab"
            type="datetime-local"
            value={draft.valid_from}
            onChange={(e) => setDraft({ ...draft, valid_from: e.target.value })}
          />
          <Field
            pointer="/valid_to"
            label="Gültig bis (leer = unbegrenzt)"
            type="datetime-local"
            value={draft.valid_to}
            onChange={(e) => setDraft({ ...draft, valid_to: e.target.value })}
          />
        </Form>
      ) : null}
    </section>
  );
}
