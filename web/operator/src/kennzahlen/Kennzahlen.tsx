import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type {
  AnalyticsMetrics,
  FunnelBreakdown,
  MetricDefinition,
  MetricValue,
  RejectionBreakdown,
} from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useState } from 'react';
import { useScreenContext } from '../ScreenContext';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DAY = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
const VEHICLE_TYPE = /^[A-Z][A-Z0-9_]{1,39}$/;

/** Gruppen der Kennzahlkarten; Abholung und Ankunft sind getrennt (KPI-007/-008), Kilometer, Auslastung und Nichtdurchführung stehen mit Formelverweis (WP-M4-55 bis -57). */
export const KENNZAHL_GRUPPEN: readonly { title: string; ids: readonly string[] }[] = [
  {
    title: 'Anfragen und Buchungen',
    ids: [
      'requests',
      'technical_request_errors',
      'offer_rate',
      'booking_conversion',
      'cancellation_rate_passenger',
      'cancellation_rate_operator',
    ],
  },
  {
    title: 'Ablehnungen',
    ids: [
      'capacity_rejections',
      'personnel_rejections',
      'equipment_rejections',
      'area_rejections',
      'time_window_rejections',
      'technical_partner_rejections',
    ],
  },
  {
    title: 'Pünktlichkeit der Abholung',
    ids: ['pickup_on_time', 'mean_pickup_delay'],
  },
  {
    title: 'Pünktlichkeit der Ankunft',
    ids: ['arrival_on_time', 'mean_arrival_delay'],
  },
  {
    title: 'Kilometer und Betriebsstunden',
    ids: ['vehicle_km', 'productive_km', 'empty_km_rate', 'passenger_km', 'operating_hours'],
  },
  {
    title: 'Auslastung und Nichtdurchführung',
    ids: ['capacity_utilization_distance_weighted', 'seat_time_utilization', 'no_show_rate'],
  },
];

const nf = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 3 });

/** Formatiert einen Kennzahlwert nach Art; Anteile liegen als Bruch 0..1 vor. */
export function formatValue(def: MetricDefinition | undefined, v: MetricValue): string {
  if (v.value === null) return '–';
  if (def?.value_kind === 'RATIO') return `${nf.format(v.value * 100)} %`;
  return `${nf.format(v.value)}${v.unit ? ` ${v.unit}` : ''}`;
}

function stateText(v: MetricValue): { tone: 'ok' | 'warning' | 'info'; label: string } {
  if (v.state === 'OK') return { tone: 'ok', label: 'Berechnet' };
  if (v.state === 'PROVISIONAL') {
    const cov = v.coverage === null ? '' : ` (Abdeckung ${nf.format(v.coverage * 100)} %)`;
    return { tone: 'warning', label: `Geringe Qualität, vorläufig${cov}` };
  }
  if (v.state === 'SUPPRESSED') return { tone: 'info', label: 'Unterdrückt (Kleingruppe)' };
  if (v.reason === 'NO_DATA') return { tone: 'info', label: 'Keine Daten' };
  if (v.reason === 'ZERO_DENOMINATOR')
    return { tone: 'info', label: 'Nicht berechenbar (Nenner 0)' };
  if (v.reason === 'NOT_EXECUTABLE_IN_THIS_RELEASE' || v.reason === 'NO_PARTNERS_IN_P1')
    return { tone: 'info', label: 'Nicht berechenbar (in diesem Stand nicht ausführbar)' };
  return { tone: 'info', label: 'Nicht berechenbar' };
}

type Filters = {
  from: string;
  to: string;
  service_id: string;
  operator_id: string;
  region_id: string;
  service_area_id: string;
  vehicle_type: string;
  day: string;
  hour: string;
};
const EMPTY: Filters = {
  from: '',
  to: '',
  service_id: '',
  operator_id: '',
  region_id: '',
  service_area_id: '',
  vehicle_type: '',
  day: '',
  hour: '',
};

type Loaded = {
  metrics: AnalyticsMetrics;
  funnel: FunnelBreakdown | null;
  rejections: RejectionBreakdown | null;
};

function validate(f: Filters): string | null {
  if (!DAY.test(f.from) || !DAY.test(f.to)) return 'Zeitraum im Format JJJJ-MM-TT angeben.';
  if (f.from > f.to) return 'Der erste Betriebstag liegt nach dem letzten.';
  for (const k of ['service_id', 'operator_id', 'region_id', 'service_area_id'] as const)
    if (f[k].trim() !== '' && !UUID.test(f[k].trim())) return 'Kennungen müssen UUIDs sein.';
  if (f.vehicle_type.trim() !== '' && !VEHICLE_TYPE.test(f.vehicle_type.trim()))
    return 'Fahrzeugtyp in Großbuchstaben angeben (z. B. VAN).';
  if (f.day !== '' && (!DAY.test(f.day) || f.day < f.from || f.day > f.to))
    return 'Der Tag muss im Zeitraum liegen.';
  if (f.hour !== '' && !/^([0-9]|1\d|2[0-3])$/.test(f.hour)) return 'Stunde zwischen 0 und 23.';
  return null;
}

/**
 * Kennzahlenansicht (WP-M4-59, Kennzahlenteil von UI-19, Grundausbau): Filterleiste, Kennzahlkarten mit Zähler und Nenner, Definitionsversion, Datenstand und Vollständigkeit,
 * Nachfragetrichter (vier getrennte Zählgrößen), Ablehnungen nach Grund in einer Spalte, Abholung und Ankunft getrennt. Zustände: keine Daten, nicht berechenbar, geringe Qualität, unterdrückt
 * (nie als 0). Alle Darstellungen sind Tabellen (die Tabellenalternative zu Diagrammen; Diagramme gehören nicht zum Grundausbau). Der Server prüft `analytics:read` und den Dienstbezug bei
 * jeder Anfrage; die Anzeige ist keine Rechtequelle. Identische Filter liefern allen Benutzern dieselben Werte und denselben Datenstand (serverseitig), die Ansicht ändert keine Zahl.
 */
export function Kennzahlen() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Kennzahlen' });
  const scopes = new Set(session.status === 'ready' ? session.me.scopes : []);
  const canRead = scopes.has('analytics:read');
  const [f, setF] = useState<Filters>(EMPTY);
  const [result, setResult] = useState<ResourceState<Loaded> | undefined>();
  const problem = validate(f);
  const set = (k: keyof Filters) => (e: { target: { value: string } }) =>
    setF((p) => ({ ...p, [k]: e.target.value }));

  const load = () => {
    if (problem !== null || !canRead) return;
    setResult({ status: 'loading' });
    const q: Record<string, string | number> = { from: f.from, to: f.to };
    for (const k of [
      'service_id',
      'operator_id',
      'region_id',
      'service_area_id',
      'vehicle_type',
      'day',
    ] as const)
      if (f[k].trim() !== '') q[k] = f[k].trim();
    if (f.hour !== '') q.hour = Number(f.hour);
    const query = q as { from: string; to: string };
    Promise.all([
      api.call('getAnalyticsMetrics', { query }),
      api.call('getAnalyticsMetrics', { query: { ...query, breakdown: 'funnel' } }),
      api.call('getAnalyticsMetrics', { query: { ...query, breakdown: 'rejection_reason' } }),
    ])
      .then(([metrics, fu, rej]) =>
        setResult({
          status: 'ready',
          data: { metrics, funnel: fu.funnel, rejections: rej.rejections },
        }),
      )
      .catch((e: unknown) =>
        setResult({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  if (!context) return null;
  return (
    <ScreenFrame title="Kennzahlen" context={context}>
      <p>
        Kennzahlen, Nachfragetrichter und Ablehnungsstatistik mit Definition, Datenstand und
        Vollständigkeit. Werte ohne ausreichende Datenbasis erscheinen nie als 0, sondern mit ihrem
        Zustand.
      </p>
      {!canRead && (
        <p role="status">Für diese Sitzung ist die Kennzahlenansicht nicht freigeschaltet.</p>
      )}
      <form
        aria-label="Filter der Kennzahlen"
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
      >
        <Field
          id="kz-von"
          label="Erster Betriebstag (JJJJ-MM-TT)"
          value={f.from}
          onChange={set('from')}
          placeholder="2026-06-01"
        />
        <Field
          id="kz-bis"
          label="Letzter Betriebstag (JJJJ-MM-TT)"
          value={f.to}
          onChange={set('to')}
          placeholder="2026-06-30"
        />
        <Field
          id="kz-dienst"
          label="Dienst (Kennung, leer = alle)"
          value={f.service_id}
          onChange={set('service_id')}
        />
        <Field
          id="kz-betreiber"
          label="Betreiber (Kennung)"
          value={f.operator_id}
          onChange={set('operator_id')}
        />
        <Field
          id="kz-region"
          label="Region (Kennung)"
          value={f.region_id}
          onChange={set('region_id')}
        />
        <Field
          id="kz-gebiet"
          label="Bediengebiet (Kennung)"
          value={f.service_area_id}
          onChange={set('service_area_id')}
        />
        <Field
          id="kz-typ"
          label="Fahrzeugtyp"
          value={f.vehicle_type}
          onChange={set('vehicle_type')}
        />
        <Field id="kz-tag" label="Tag (JJJJ-MM-TT)" value={f.day} onChange={set('day')} />
        <Field id="kz-stunde" label="Ortsstunde (0 bis 23)" value={f.hour} onChange={set('hour')} />
        {problem !== null && (f.from !== '' || f.to !== '') && <p role="alert">{problem}</p>}
        <button type="submit" disabled={problem !== null || !canRead}>
          Auswerten
        </button>
      </form>

      {result && (
        <ResourceView
          state={result}
          isEmpty={(d) => (d.metrics.values ?? []).length === 0}
          empty={{
            title: 'Keine Daten im Zeitraum',
            hint: 'Für den Zeitraum und die Filter liegen keine Kennzahlwerte vor.',
          }}
          errorAction={
            <button type="button" onClick={load}>
              Erneut versuchen
            </button>
          }
        >
          {(d) => <Ergebnis data={d} />}
        </ResourceView>
      )}
    </ScreenFrame>
  );
}

function Field(p: {
  id: string;
  label: string;
  value: string;
  onChange: (e: { target: { value: string } }) => void;
  placeholder?: string;
}) {
  return (
    <div>
      <label htmlFor={p.id}>{p.label}</label>
      <input
        id={p.id}
        type="text"
        value={p.value}
        onChange={p.onChange}
        placeholder={p.placeholder}
      />
    </div>
  );
}

function Ergebnis({ data }: { data: Loaded }) {
  const defs = new Map(data.metrics.definitions.map((d) => [`${d.metric_id}@${d.version}`, d]));
  const values = data.metrics.values ?? [];
  const byId = new Map(values.map((v) => [v.metric_id, v]));
  const grouped = new Set(KENNZAHL_GRUPPEN.flatMap((g) => g.ids));
  const other = values.filter((v) => !grouped.has(v.metric_id));
  return (
    <div>
      <p>Datenstand {data.metrics.as_of ?? '–'} · Alle Zahlen stammen aus einem Lesestand.</p>
      {KENNZAHL_GRUPPEN.map((g) => {
        const rows = g.ids
          .map((id) => byId.get(id))
          .filter((v): v is MetricValue => v !== undefined);
        if (rows.length === 0) return null;
        return <Gruppe key={g.title} title={g.title} rows={rows} defs={defs} />;
      })}
      {other.length > 0 && <Gruppe title="Weitere Kennzahlen" rows={other} defs={defs} />}
      <Trichter funnel={data.funnel} />
      <Ablehnungen rej={data.rejections} />
    </div>
  );
}

function Gruppe({
  title,
  rows,
  defs,
}: {
  title: string;
  rows: MetricValue[];
  defs: Map<string, MetricDefinition>;
}) {
  return (
    <section aria-label={title}>
      <h2>{title}</h2>
      <table aria-label={title}>
        <caption>{title}</caption>
        <thead>
          <tr>
            <th scope="col">Kennzahl</th>
            <th scope="col">Wert</th>
            <th scope="col">Zähler</th>
            <th scope="col">Nenner</th>
            <th scope="col">Zustand</th>
            <th scope="col">Definition</th>
            <th scope="col">Datenstand</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((v) => {
            const def = defs.get(`${v.metric_id}@${v.definition_version}`);
            const st = stateText(v);
            return (
              <tr key={v.metric_id}>
                <th scope="row">{def?.name ?? v.metric_id}</th>
                <td>{v.state === 'SUPPRESSED' ? 'unterdrückt' : formatValue(def, v)}</td>
                <td>{v.numerator === null ? '–' : nf.format(v.numerator)}</td>
                <td>{v.denominator === null ? '–' : nf.format(v.denominator)}</td>
                <td>
                  <StatusBadge tone={st.tone}>{st.label}</StatusBadge>
                </td>
                <td>
                  Version {v.definition_version}
                  {def && (
                    <details>
                      <summary>Formel</summary>
                      <p>Zähler: {def.numerator}</p>
                      <p>Nenner: {def.denominator}</p>
                      <p>Ausschlüsse: {def.exclusions}</p>
                    </details>
                  )}
                </td>
                <td>{v.as_of ?? '–'}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}

const cell = (n: number | null) => (n === null ? '–' : nf.format(n));

function Trichter({ funnel }: { funnel: FunnelBreakdown | null }) {
  if (funnel === null) return null;
  const hidden = funnel.suppressed || funnel.state === 'NOT_COMPUTABLE';
  return (
    <section aria-label="Nachfragetrichter">
      <h2>Nachfragetrichter</h2>
      <p>
        Datenstand {funnel.as_of ?? '–'} ·{' '}
        <StatusBadge tone={funnel.state === 'OK' && !hidden ? 'ok' : 'warning'}>
          {funnel.suppressed
            ? 'Unterdrückt (Kleingruppe)'
            : funnel.state === 'OK'
              ? 'Vollständig'
              : funnel.state === 'PROVISIONAL'
                ? `Vorläufig, Abdeckung ${funnel.coverage === null ? '–' : nf.format(funnel.coverage * 100) + ' %'}`
                : 'Nicht berechenbar'}
        </StatusBadge>
      </p>
      <table aria-label="Trichter-Zählgrößen">
        <caption>Vier getrennte Zählgrößen (keine Summe)</caption>
        <thead>
          <tr>
            <th scope="col">Stufe</th>
            <th scope="col">Anzahl</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">Anfragen</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.counts.requests)}</td>
          </tr>
          <tr>
            <th scope="row">Angebote angezeigt</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.counts.offers)}</td>
          </tr>
          <tr>
            <th scope="row">Bestätigte Buchungen</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.counts.confirmed_bookings)}</td>
          </tr>
          <tr>
            <th scope="row">Abgeschlossene Buchungen</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.counts.completed_bookings)}</td>
          </tr>
        </tbody>
      </table>
      <table aria-label="Trichter-Endzustände">
        <caption>Endzustände</caption>
        <thead>
          <tr>
            <th scope="col">Endzustand</th>
            <th scope="col">Anzahl</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">Anfrage abgelehnt</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.request_rejected)}</td>
          </tr>
          <tr>
            <th scope="row">Angebot nicht gebucht</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.offer_not_booked)}</td>
          </tr>
          <tr>
            <th scope="row">Zahlung fehlgeschlagen</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.payment_failed)}</td>
          </tr>
          <tr>
            <th scope="row">Storno Fahrgast</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.cancelled_by_passenger)}</td>
          </tr>
          <tr>
            <th scope="row">Storno Betreiber</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.cancelled_by_operator)}</td>
          </tr>
          <tr>
            <th scope="row">Sonstiges Storno</th>
            <td>{hidden ? 'unterdrückt' : cell(funnel.end_states.cancelled_other)}</td>
          </tr>
        </tbody>
      </table>
    </section>
  );
}

function Ablehnungen({ rej }: { rej: RejectionBreakdown | null }) {
  if (rej === null) return null;
  return (
    <section aria-label="Ablehnungen nach Grund">
      <h2>Ablehnungen nach Grund</h2>
      {rej.suppressed ? (
        <p role="status">
          Unterdrückt (Kleingruppe): die Ablehnungsstatistik wird für diese Auswahl nicht
          ausgewiesen.
        </p>
      ) : rej.state === 'NOT_COMPUTABLE' ? (
        <p role="status">Keine Daten: für diese Auswahl liegen keine Versuche vor.</p>
      ) : (
        <>
          <p>
            Datenstand {rej.as_of ?? '–'} · Versuche {cell(rej.attempts)} · Ablehnungen{' '}
            {cell(rej.rejections)} · technische Fehler {cell(rej.technical_errors)}{' '}
            {rej.state === 'PROVISIONAL' && <StatusBadge tone="warning">Vorläufig</StatusBadge>}
          </p>
          <table aria-label="Ablehnungsgründe">
            <caption>Ablehnungen nach Grund (Primärgrund zählt, Nebengründe nur als Label)</caption>
            <thead>
              <tr>
                <th scope="col">Ablehnungsgrund</th>
                <th scope="col">Gruppe</th>
                <th scope="col">Primär</th>
                <th scope="col">Nebengrund</th>
              </tr>
            </thead>
            <tbody>
              {rej.rows.map((r) => (
                <tr key={r.code}>
                  <th scope="row">{r.text}</th>
                  <td>{r.group}</td>
                  <td>{r.primary_count}</td>
                  <td>{r.secondary_count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
