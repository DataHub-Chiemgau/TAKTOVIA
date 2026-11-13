import { ApiError, useApi } from '@taktovia/api-client';
import type { Defect, VehicleMaster } from '@taktovia/api-client';
import { DataTable, ResourceView } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { CATEGORY_LABELS, STATUS_LABELS } from './schadenkatalog';

type SortColumn = 'reported_at' | 'case_number' | 'vehicle' | 'priority' | 'status' | 'assignee';
type Sort = { column: SortColumn; descending: boolean } | undefined;

/** Priorität der Liste, abgeleitet aus der Klasse (Arbeitsannahme WP-M3-49): 1 sicherheitskritisch bis 4 sonstig. */
const PRIORITY: Readonly<Record<NonNullable<Defect['severity']>, { n: number; label: string }>> = {
  SAFETY_CRITICAL: { n: 1, label: '1 sicherheitskritisch' },
  OPERATIONALLY_RELEVANT: { n: 2, label: '2 einsatzrelevant' },
  COSMETIC: { n: 3, label: '3 kosmetisch' },
  OTHER: { n: 4, label: '4 sonstig' },
};
const PRIORITY_OPTIONS = [1, 2, 3, 4] as const;

/** Die sechs Filter der Schadenliste (DMG-009); leere Felder sind kein Filter. */
type Filters = {
  vehicle_id: string;
  priority: string;
  status: string;
  reported_from: string;
  reported_to: string;
  depot_id: string;
  assignee_ref: string;
};
export const NO_FILTERS: Filters = {
  vehicle_id: '',
  priority: '',
  status: '',
  reported_from: '',
  reported_to: '',
  depot_id: '',
  assignee_ref: '',
};

const fmt = (iso: string): string => iso.replace('T', ' ').replace(/:\d\d(\.\d+)?Z$/, ' UTC');
const iso = (local: string): string => (local === '' ? '' : new Date(local).toISOString());

const COLUMNS: readonly { key: SortColumn; header: string }[] = [
  { key: 'case_number', header: 'Fallnummer' },
  { key: 'vehicle', header: 'Fahrzeug' },
  { key: 'priority', header: 'Priorität' },
  { key: 'status', header: 'Status' },
  { key: 'reported_at', header: 'Gemeldet' },
  { key: 'assignee', header: 'Verantwortlicher' },
];

/**
 * UI-11 Schadenliste der Leitstelle (WP-M3-49, DMG-009): Filterleiste mit sechs Filtern (Fahrzeug, Priorität, Status, Zeitraum, Depot, Verantwortlicher), sortierbare Spalten (Umschalten aufsteigend,
 * absteigend, Standard), „Weitere laden“ über den Cursor, Leerzustand, Ladefehler mit Wiederholung und Datenstand (`as_of`). Die Listenprojektion enthält keinen Beschreibungstext und keine vertraulichen
 * Inhalte; die Anzeige ist keine Rechtequelle, der Server filtert nach Rolle und Dienst. [reloadKey] lädt die Liste nach einer Änderung neu.
 */
export function Schadenliste({
  vehicles,
  onOpen,
  reloadKey,
  canWrite,
}: {
  vehicles: readonly VehicleMaster[];
  onOpen: (d: Defect) => void;
  reloadKey: number;
  canWrite: boolean;
}) {
  const api = useApi();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [applied, setApplied] = useState<Filters>(NO_FILTERS);
  const [sort, setSort] = useState<Sort>(undefined);
  const [rows, setRows] = useState<ResourceState<Defect[]>>({ status: 'loading' });
  const [next, setNext] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);
  const [formProblem, setFormProblem] = useState<string | undefined>(undefined);

  const query = useCallback(
    (cursor?: string) => {
      const q: Record<string, string | number> = { limit: 50 };
      if (applied.vehicle_id) q['vehicle_id'] = applied.vehicle_id;
      if (applied.priority) q['priority'] = Number(applied.priority);
      if (applied.status) q['status'] = applied.status;
      if (applied.reported_from) q['reported_from'] = iso(applied.reported_from);
      if (applied.reported_to) q['reported_to'] = iso(applied.reported_to);
      if (applied.depot_id) q['depot_id'] = applied.depot_id;
      if (applied.assignee_ref) q['assignee_ref'] = applied.assignee_ref;
      if (sort) q['sort'] = (sort.descending ? '-' : '') + sort.column;
      if (cursor) q['cursor'] = cursor;
      return q;
    },
    [applied, sort],
  );

  useEffect(() => {
    let cancelled = false;
    api
      .call('listDefects', { query: query() as never })
      .then((p) => {
        if (cancelled) return;
        setRows({ status: 'ready', data: p.items });
        setNext(p.next_cursor);
        setAsOf(p.as_of);
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, query, tick, reloadKey]);

  const more = async () => {
    if (!next || rows.status !== 'ready') return;
    try {
      const p = await api.call('listDefects', { query: query(next) as never });
      setRows({ status: 'ready', data: [...rows.data, ...p.items] });
      setNext(p.next_cursor);
    } catch (e) {
      setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
    }
  };

  const apply = () => {
    if (
      filters.reported_from &&
      filters.reported_to &&
      filters.reported_from >= filters.reported_to
    ) {
      setFormProblem('Der Zeitraum ist ungültig: „Bis“ muss nach „Von“ liegen.');
      return;
    }
    setFormProblem(undefined);
    setRows({ status: 'loading' });
    setApplied(filters);
  };
  const reset = () => {
    setFilters(NO_FILTERS);
    setRows({ status: 'loading' });
    setApplied(NO_FILTERS);
    setFormProblem(undefined);
  };
  const toggleSort = (column: SortColumn) => {
    setRows({ status: 'loading' });
    setSort((cur) =>
      !cur || cur.column !== column
        ? { column, descending: false }
        : cur.descending
          ? undefined
          : { column, descending: true },
    );
  };
  const vehicleLabel = (id: string): string =>
    vehicles.find((v) => v.id === id)?.vehicle_label ?? id.slice(0, 8);
  const set = (k: keyof Filters, v: string) => setFilters((f) => ({ ...f, [k]: v }));

  return (
    <section aria-labelledby="liste-titel">
      <h2 id="liste-titel">Schadenliste</h2>
      <form
        role="search"
        aria-label="Filter der Schadenliste"
        onSubmit={(e) => {
          e.preventDefault();
          apply();
        }}
      >
        <div className="tk-field">
          <label htmlFor="f-fahrzeug">Fahrzeug (Filter)</label>
          <select
            id="f-fahrzeug"
            value={filters.vehicle_id}
            onChange={(e) => set('vehicle_id', e.target.value)}
          >
            <option value="">Alle Fahrzeuge</option>
            {vehicles.map((v) => (
              <option key={v.id} value={v.id}>
                {v.vehicle_label ?? v.id.slice(0, 8)}
              </option>
            ))}
          </select>
        </div>
        <div className="tk-field">
          <label htmlFor="f-prio">Priorität (Filter)</label>
          <select
            id="f-prio"
            value={filters.priority}
            onChange={(e) => set('priority', e.target.value)}
          >
            <option value="">Alle Prioritäten</option>
            {PRIORITY_OPTIONS.map((n) => (
              <option key={n} value={n}>
                {Object.values(PRIORITY).find((p) => p.n === n)?.label}
              </option>
            ))}
          </select>
        </div>
        <div className="tk-field">
          <label htmlFor="f-status">Status (Filter)</label>
          <select
            id="f-status"
            value={filters.status}
            onChange={(e) => set('status', e.target.value)}
          >
            <option value="">Alle Status</option>
            {(Object.keys(STATUS_LABELS) as (keyof typeof STATUS_LABELS)[]).map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
        <div className="tk-field">
          <label htmlFor="f-von">Gemeldet von</label>
          <input
            id="f-von"
            type="datetime-local"
            value={filters.reported_from}
            onChange={(e) => set('reported_from', e.target.value)}
          />
        </div>
        <div className="tk-field">
          <label htmlFor="f-bis">Gemeldet bis</label>
          <input
            id="f-bis"
            type="datetime-local"
            value={filters.reported_to}
            onChange={(e) => set('reported_to', e.target.value)}
          />
        </div>
        <div className="tk-field">
          <label htmlFor="f-depot">Depot (Kennung)</label>
          <input
            id="f-depot"
            value={filters.depot_id}
            onChange={(e) => set('depot_id', e.target.value.trim())}
          />
        </div>
        <div className="tk-field">
          <label htmlFor="f-verantw">Verantwortlicher (Kennung)</label>
          <input
            id="f-verantw"
            value={filters.assignee_ref}
            onChange={(e) => set('assignee_ref', e.target.value.trim())}
          />
        </div>
        {formProblem ? <p role="alert">{formProblem}</p> : null}
        <button type="submit">Filter anwenden</button>
        <button type="button" onClick={reset}>
          Filter zurücksetzen
        </button>
      </form>

      <div role="group" aria-label="Sortierung">
        {COLUMNS.map((c) => (
          <button
            key={c.key}
            type="button"
            aria-pressed={sort?.column === c.key}
            onClick={() => toggleSort(c.key)}
          >
            Sortieren nach {c.header}
            {sort?.column === c.key ? (sort.descending ? ' (absteigend)' : ' (aufsteigend)') : ''}
          </button>
        ))}
      </div>

      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: Object.values(applied).some((v) => v !== '')
            ? 'Keine Schadenakten für diese Filter'
            : 'Noch keine Schadenakten in diesem Mandanten',
          hint: Object.values(applied).some((v) => v !== '')
            ? 'Ändern oder entfernen Sie Filter.'
            : canWrite
              ? 'Erfassen Sie unten den ersten Schaden.'
              : 'Es wurden noch keine Schäden gemeldet.',
        }}
        errorAction={
          <button
            type="button"
            onClick={() => {
              setRows({ status: 'loading' });
              setTick((n) => n + 1);
            }}
          >
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <>
            <DataTable
              caption="Schadenakten des aktiven Mandanten"
              rows={data}
              rowKey={(d) => d.id}
              columns={[
                { key: 'nr', header: 'Fallnummer', render: (d) => d.case_number },
                { key: 'veh', header: 'Fahrzeug', render: (d) => vehicleLabel(d.vehicle_id) },
                {
                  key: 'cat',
                  header: 'Kategorie',
                  render: (d) => CATEGORY_LABELS[d.category_code],
                },
                {
                  key: 'prio',
                  header: 'Priorität',
                  render: (d) => (d.severity ? PRIORITY[d.severity].label : 'nicht eingestuft'),
                },
                {
                  key: 'st',
                  header: 'Status',
                  render: (d) => (
                    <>
                      {STATUS_LABELS[d.status]}
                      {d.blocks_vehicle ? ' · sperrt das Fahrzeug' : ''}
                    </>
                  ),
                },
                { key: 'at', header: 'Gemeldet', render: (d) => fmt(d.reported_at) },
                { key: 'who', header: 'Verantwortlicher', render: (d) => d.assignee_ref ?? '–' },
                {
                  key: 'act',
                  header: 'Aktion',
                  render: (d) => (
                    <button type="button" onClick={() => onOpen(d)}>
                      Öffnen {d.case_number}
                    </button>
                  ),
                },
              ]}
            />
            <p>
              {data.length} Akten{asOf ? `, Stand ${fmt(asOf)}` : ''}
            </p>
            {next ? (
              <button type="button" onClick={() => void more()}>
                Weitere laden
              </button>
            ) : null}
          </>
        )}
      </ResourceView>
    </section>
  );
}
