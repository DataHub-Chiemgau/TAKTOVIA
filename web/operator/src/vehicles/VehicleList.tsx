import { ApiError, useApi } from '@taktovia/api-client';
import type {
  MissingRequirement,
  OperatorVehicle,
  VehicleBlock,
  VehicleOperationalState,
} from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState, StatusTone } from '@taktovia/design-system';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useScreenContext } from '../ScreenContext';
import { STATUS_LABELS } from '../fahrzeugstatus/VehicleStatus';
import { LiveStatusBadge, useLiveChannel } from '../live/LiveChannel';

/** Sperrgründe der Leitstellenansicht (Katalog OP-01); keine Diagnosetexte. */
const BLOCK_REASONS: Readonly<Record<string, string>> = {
  OPERATIONAL_DISRUPTION: 'Betriebsstörung',
  MANUAL: 'manuelle Sperre',
  DEFECT_CRITICAL: 'kritischer Schaden',
  INSPECTION_FAILED: 'kritischer Mangel',
  OTHER: 'sonstiger Grund',
};

/** Fehlende Voraussetzungen der Einsatzfreigabe (LH-6.21-CHK-001) in der Sprache der Leitstelle. */
const REQUIREMENTS: Readonly<Record<MissingRequirement['code'], string>> = {
  NO_INSPECTION: 'Vor-Schicht-Prüfung fehlt',
  INSPECTION_OPEN: 'Prüfung nicht abgeschlossen',
  INSPECTION_EXPIRED: 'Freigabe abgelaufen',
  DEFECT_BLOCK: 'Sicherheitssperre aktiv',
  OPERATIONAL_BLOCK: 'betriebliche Sperre aktiv',
  SAFETY_REVISION_CHANGED: 'Sicherheitsstand geändert, Neuprüfung nötig',
  QUALIFICATION_MISSING: 'Qualifikation des Fahrers fehlt',
  VEHICLE_CHANGED: 'Schicht passt nicht zum Fahrzeug',
  RELEASE_REVOKED: 'Freigabe widerrufen',
};

const TONES: Readonly<Record<VehicleOperationalState, StatusTone>> = {
  NOT_READY: 'warning',
  RELEASE_PENDING: 'warning',
  AVAILABLE: 'ok',
  EN_ROUTE: 'info',
  WAITING: 'info',
  BREAK: 'neutral',
  DISRUPTED: 'error',
  BLOCKED: 'error',
  RESTRICTED: 'warning',
  ENDED: 'neutral',
};

const STATE_OPTIONS = Object.keys(STATUS_LABELS) as VehicleOperationalState[];

const clock = (iso: string): string => new Date(iso).toLocaleTimeString('de-DE');

/** Sperrtext der Leitstelle: „gesperrt · kritischer Mangel“ mit Vorgangsnummer; eine Dienstsperre trifft nur ihre Schicht. */
export function blockText(b: VehicleBlock): string {
  const scope = b.block_kind === 'SERVICE' ? 'Dienst gesperrt' : 'gesperrt';
  return (
    `${scope} · ${BLOCK_REASONS[b.reason_code] ?? b.reason_code}` +
    (b.case_number ? ` (Vorgang ${b.case_number})` : '')
  );
}

/** Text der letzten Position: Alter, veraltet mit letztem Stand, keine Position (nie eine fiktive Liveposition). */
export function positionText(v: OperatorVehicle): string {
  if (v.position === null || v.position_measured_at === null) return 'keine Position bekannt';
  const age = v.position_age_seconds === null ? '' : `, vor ${v.position_age_seconds} s`;
  return v.position_stale
    ? `veraltet · letzter Stand ${clock(v.position_measured_at)}${age}`
    : `aktuell · ${clock(v.position_measured_at)}${age}`;
}

const requirementText = (m: MissingRequirement): string =>
  REQUIREMENTS[m.code] +
  (m.step_codes && m.step_codes.length > 0 ? ` (offen: ${m.step_codes.join(', ')})` : '');

/** Hinweise je Fahrzeug: Sperren mit Grund, fehlende Voraussetzungen, entfallene Ressourcen. */
export function notesOf(v: OperatorVehicle): string[] {
  const notes = v.active_blocks.map(blockText);
  if (v.operational_state === 'RELEASE_PENDING')
    notes.push(...v.missing_requirements.map((m) => `nicht einsatzbereit: ${requirementText(m)}`));
  if (v.unavailable_dimensions?.includes('wheelchair'))
    notes.push('eingeschränkt: keine Rollstuhlfahrten (Prüfpunkt Rampe/Lift nicht freigegeben)');
  if (v.open_defect_count > 0) notes.push(`${v.open_defect_count} offene Schadenakte(n)`);
  return notes;
}

const shortId = (id: string): string => id.slice(0, 8);

/** Schematische Positionsübersicht (Kartenquelle D-09 noch nicht freigegeben): nur gemessene Punkte, nie Fortschreibungen; die Liste bleibt die vollständige Bedienung. */
function SchematicMap({
  rows,
  onSelect,
}: {
  rows: OperatorVehicle[];
  onSelect: (id: string) => void;
}) {
  const placed = rows.flatMap((v) =>
    v.position ? [{ v, lat: v.position.latitude, lon: v.position.longitude }] : [],
  );
  if (placed.length === 0) return <p>Keine Fahrzeugposition bekannt.</p>;
  const lats = placed.map((p) => p.lat);
  const lons = placed.map((p) => p.lon);
  const [minLat, maxLat, minLon, maxLon] = [
    Math.min(...lats),
    Math.max(...lats),
    Math.min(...lons),
    Math.max(...lons),
  ];
  const scale = (x: number, lo: number, hi: number, size: number) =>
    hi === lo ? size / 2 : 20 + ((x - lo) / (hi - lo)) * (size - 40);
  return (
    <svg
      viewBox="0 0 400 240"
      role="group"
      aria-label={`Schematische Karte mit ${placed.length} Fahrzeugpositionen`}
      style={{ width: '100%', maxWidth: '40rem', border: '1px solid currentColor' }}
    >
      {placed.map(({ v, lat, lon }) => {
        const x = scale(lon, minLon, maxLon, 400);
        const y = 240 - scale(lat, minLat, maxLat, 240);
        return (
          <g
            key={v.vehicle_id}
            tabIndex={-1}
            onClick={() => onSelect(v.vehicle_id)}
            data-stale={v.position_stale ? 'true' : 'false'}
          >
            <title>{`${v.vehicle_label}: ${positionText(v)}`}</title>
            <circle
              cx={x}
              cy={y}
              r={7}
              fill={v.position_stale ? 'none' : 'currentColor'}
              stroke="currentColor"
              strokeWidth={2}
              strokeDasharray={v.position_stale ? '3 2' : undefined}
            />
            <text x={x + 10} y={y + 4} fontSize="11" fill="currentColor">
              {v.vehicle_label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function Detail({ v }: { v: OperatorVehicle }) {
  const notes = notesOf(v);
  return (
    <aside aria-label={`Details zu ${v.vehicle_label}`}>
      <h2>{v.vehicle_label}</h2>
      <dl>
        <dt>Zustand</dt>
        <dd>{STATUS_LABELS[v.operational_state]}</dd>
        <dt>Letzte Position</dt>
        <dd>{positionText(v)}</dd>
        <dt>Einsatzfreigabe</dt>
        <dd>
          {v.release_valid_until
            ? `gültig bis ${clock(v.release_valid_until)}`
            : 'keine gültige Freigabe'}
        </dd>
        <dt>Dienste</dt>
        <dd>{(v.service_ids ?? []).map(shortId).join(', ') || 'keinem Dienst zugeordnet'}</dd>
      </dl>
      {notes.length > 0 ? (
        <ul aria-label="Hinweise">
          {notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      ) : null}
      <h3>Aufträge</h3>
      {(v.orders ?? []).length === 0 ? (
        <p>Keine laufenden oder nächsten Aufträge.</p>
      ) : (
        <ul aria-label="Aufträge">
          {(v.orders ?? []).map((o) => (
            <li key={o.booking_id}>
              {o.phase === 'RUNNING' ? 'laufend' : 'nächster'} ·{' '}
              {o.booking_no ?? shortId(o.booking_id)} · Einstieg {clock(o.planned_pickup_at)}
            </li>
          ))}
        </ul>
      )}
    </aside>
  );
}

interface Filter {
  service: string;
  state: string;
  incidents: boolean;
}

/**
 * UI-07 Operator Livebetrieb (WP-M3-36, LH-6.7-OPS-001): aktive Fahrzeuge der berechtigten Dienste mit Zustand, Sperre und Grund, letzter Position samt Alter, Aufträgen und fehlenden Voraussetzungen der
 * Einsatzfreigabe. Die **Liste** ist die vollständige, gleichwertige Bedienung (Tastatur, Screenreader); die schematische Karte ist ein zusätzlicher Blick, der sich ausblenden lässt. Zustände: laden,
 * Leerzustand, Ladefehler mit letztem Datenstand, Sperre (mit Grund), veralteter Standort (mit letztem Stand) und „keine Fremdrechte“ (Fahrzeuge anderer Dienste oder Mandanten sind nicht vorhanden,
 * nie ausgegraut). Filter schränken nur ein; der Server begrenzt die Liste auf die berechtigten Dienste und prüft jede Anfrage. Der Live-Kanal (WP-M3-35) stößt den Neuabruf an.
 */
export function VehicleList() {
  const api = useApi();
  const context = useScreenContext({ tone: 'ok', label: 'Livebetrieb' });
  const [filter, setFilter] = useState<Filter>({ service: '', state: '', incidents: false });
  const [rows, setRows] = useState<ResourceState<OperatorVehicle[]>>({ status: 'loading' });
  const [cursor, setCursor] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [showMap, setShowMap] = useState(true);
  const [selected, setSelected] = useState<string | undefined>(undefined);
  const [services, setServices] = useState<string[]>([]);
  const last = useRef<OperatorVehicle[] | undefined>(undefined);
  const lastAsOf = useRef<string | undefined>(undefined);

  const query = useCallback(
    (f: Filter, after?: string) => ({
      limit: 100,
      ...(f.service ? { service_id: f.service } : {}),
      ...(f.state ? { state: f.state as VehicleOperationalState } : {}),
      ...(f.incidents ? { open_incidents: true } : {}),
      ...(after ? { cursor: after } : {}),
    }),
    [],
  );

  const load = useCallback(
    (f: Filter, after?: string) => {
      api
        .call('listOperatorVehicles', { query: query(f, after) })
        .then((page) => {
          const data = after && last.current ? [...last.current, ...page.items] : page.items;
          last.current = data;
          lastAsOf.current = page.as_of;
          setAsOf(page.as_of);
          setCursor(page.next_cursor);
          setNotice(undefined);
          setRows({ status: 'ready', data });
          setServices((known) =>
            [...new Set([...known, ...page.items.flatMap((v) => v.service_ids ?? [])])].sort(),
          );
        })
        .catch((e: unknown) => {
          // Ladefehler: die zuletzt gezeigte Liste bleibt mit ihrem Datenstand stehen, der Hinweis steht davor
          if (last.current === undefined)
            setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
          else
            setNotice(
              `Die Liste konnte nicht aktualisiert werden; angezeigt ist der Datenstand von ${lastAsOf.current ? clock(lastAsOf.current) : 'unbekannt'}.`,
            );
        });
    },
    [api, query],
  );

  useEffect(() => {
    load(filter);
    // nur beim Einhängen; ein Filterwechsel beginnt die Liste im Ereignis neu (applyFilter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** Ein Filter schränkt nur ein und beginnt die Liste neu (Cursor und Datenstand gelten nur für den bisherigen Filter). */
  const applyFilter = (next: Filter) => {
    setFilter(next);
    last.current = undefined;
    setCursor(null);
    setRows({ status: 'loading' });
    load(next);
  };

  const live = useLiveChannel({ onMessage: () => load(filter), onResync: () => load(filter) });

  const detail = useMemo(
    () => (rows.status === 'ready' ? rows.data.find((v) => v.vehicle_id === selected) : undefined),
    [rows, selected],
  );

  if (!context) return null;
  return (
    <ScreenFrame title="Leitstelle: Livebetrieb Fahrzeuge" context={context}>
      <p>
        <LiveStatusBadge status={live} /> Datenstand: {asOf ? clock(asOf) : 'wird geladen'}
      </p>
      <p>
        Angezeigt werden nur aktive Fahrzeuge Ihrer berechtigten Dienste. Fahrzeuge anderer Dienste
        und Mandanten sind nicht vorhanden. Filter schränken die Ansicht nur weiter ein.
      </p>
      <form
        aria-label="Filter"
        onSubmit={(e) => e.preventDefault()}
        style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}
      >
        <label>
          Dienst
          <select
            value={filter.service}
            onChange={(e) => applyFilter({ ...filter, service: e.target.value })}
          >
            <option value="">Alle berechtigten Dienste</option>
            {services.map((s) => (
              <option key={s} value={s}>{`Dienst ${shortId(s)}`}</option>
            ))}
          </select>
        </label>
        <label>
          Zustand
          <select
            value={filter.state}
            onChange={(e) => applyFilter({ ...filter, state: e.target.value })}
          >
            <option value="">Alle Zustände</option>
            {STATE_OPTIONS.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </label>
        <label>
          <input
            type="checkbox"
            checked={filter.incidents}
            onChange={(e) => applyFilter({ ...filter, incidents: e.target.checked })}
          />{' '}
          Nur mit offenen Störungen
        </label>
      </form>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine Fahrzeuge',
          hint:
            filter.service || filter.state || filter.incidents
              ? 'Für diese Filter gibt es keine Fahrzeuge in Ihren Diensten.'
              : 'In Ihren Diensten sind derzeit keine aktiven Fahrzeuge zugeordnet.',
        }}
        errorAction={
          <button type="button" onClick={() => load(filter)}>
            Erneut versuchen
          </button>
        }
      >
        {(list) => (
          <>
            <table className="tk-table">
              <caption>Fahrzeuge der berechtigten Dienste mit Zustand und letzter Position</caption>
              <thead>
                <tr>
                  <th scope="col">Fahrzeug</th>
                  <th scope="col">Zustand</th>
                  <th scope="col">Letzte Position</th>
                  <th scope="col">Aufträge</th>
                  <th scope="col">Hinweise</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {list.map((v) => (
                  <tr key={v.vehicle_id} aria-selected={v.vehicle_id === selected}>
                    <th scope="row">{v.vehicle_label}</th>
                    <td>
                      <StatusBadge tone={TONES[v.operational_state]}>
                        {STATUS_LABELS[v.operational_state]}
                      </StatusBadge>
                    </td>
                    <td data-stale={v.position_stale ? 'true' : 'false'}>{positionText(v)}</td>
                    <td>{(v.orders ?? []).length}</td>
                    <td>
                      {notesOf(v).length === 0 ? (
                        '–'
                      ) : (
                        <ul>
                          {notesOf(v).map((n) => (
                            <li key={n}>{n}</li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      <button
                        type="button"
                        aria-label={`Details zu ${v.vehicle_label}`}
                        aria-pressed={v.vehicle_id === selected}
                        onClick={() =>
                          setSelected(v.vehicle_id === selected ? undefined : v.vehicle_id)
                        }
                      >
                        Details
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {cursor ? (
              <p>
                <button type="button" onClick={() => load(filter, cursor)}>
                  Weitere Fahrzeuge laden
                </button>
              </p>
            ) : null}
            <section aria-label="Karte">
              <button type="button" aria-expanded={showMap} onClick={() => setShowMap(!showMap)}>
                {showMap ? 'Karte ausblenden' : 'Karte einblenden'}
              </button>
              {showMap ? (
                <>
                  <p>
                    Schematische Darstellung der letzten gemessenen Positionen; die Kartenquelle ist
                    noch nicht freigegeben. Die Liste enthält alle Angaben.
                  </p>
                  <SchematicMap rows={list} onSelect={setSelected} />
                </>
              ) : null}
            </section>
            {detail ? <Detail v={detail} /> : null}
          </>
        )}
      </ResourceView>
    </ScreenFrame>
  );
}
