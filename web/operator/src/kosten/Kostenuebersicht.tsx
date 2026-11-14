import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type {
  AnalyticsMetrics,
  CostClassCell,
  CostClassification,
  CostClassificationRow,
  CostEvent,
  KmMetrics,
  LegalEntity,
  MetricValue,
  VehicleTotalCost,
} from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useMemo, useState } from 'react';
import { formatMinor } from '../belege/betrag';
import { formatKm } from '../costs/KmClassesView';
import { useScreenContext } from '../ScreenContext';
import { KOSTENGRUPPEN, monthsBetween } from './Fahrzeugkosten';

const DAY = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
const CURRENCY = /^[A-Z]{3}$/;

/** Kostenverhalten der Zeilen der Kreuztabelle (Achse 1) und Zurechnung der Spalten (Achse 2); die Achsen werden nie addiert (Kap. 03 §1.1, FCO-001). */
const VERHALTEN = [
  { key: 'FIXED', label: 'fix' },
  { key: 'VARIABLE', label: 'variabel' },
  { key: 'MIXED', label: 'gemischt' },
] as const;
const ZURECHNUNG = [
  { key: 'DIRECT', label: 'direkt' },
  { key: 'ALLOCATED', label: 'umgelegt' },
] as const;

type Verhalten = (typeof VERHALTEN)[number]['key'] | 'UNCLASSIFIED';
type Zurechnung = (typeof ZURECHNUNG)[number]['key'];

/** Gewählter Drilldown: die Ursprungsereignisse hinter einer Zelle der Kreuztabelle oder einer Kostenart der Monatsauswertung. */
type Drill = { title: string; behavior?: Verhalten; attribution?: Zurechnung; category?: string };

const GRUND: Record<string, string> = {
  NOT_RELEASED: 'nicht freigegeben oder nicht gebucht',
  OPEN_HINT: 'offener Plausibilitätshinweis',
  MISSING_ENERGY_TYPE: 'Kraftstoffart fehlt',
  MISSING_QUANTITY: 'Menge fehlt',
  MISSING_VEHICLE: 'Fahrzeug fehlt',
  MIXED_ENERGY_VEHICLE: 'Fahrzeug mit beiden Energiearten',
  REVERSED: 'storniert',
  OTHER_CURRENCY: 'andere Währung',
};

/** Letzter Tag der Periode (exklusives Ende minus ein Tag), als `JJJJ-MM-TT`; Rechnung in UTC, ohne Zeitzone. */
export function lastDay(periodTo: string): string {
  const d = new Date(`${periodTo}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

const percent = (n: number) => `${(n * 100).toFixed(1).replace('.', ',')} %`;

function cellOf(
  row: CostClassificationRow,
  b: Verhalten,
  a: Zurechnung | null,
): CostClassCell | undefined {
  return row.cells.find(
    (c) => (c.cost_behavior ?? 'UNCLASSIFIED') === b && c.attribution === (a ?? null),
  );
}

/**
 * UI-15 Kostenübersicht je Kostenobjekt, **Grundausbau** (WP-M4-44; Kap. 06 §6.6/§6.9): Rechtsträger, Periode, Währung und die Sicht „Ist gebucht“ (nur gebuchte Journale). Je Kostenobjekt (Fahrzeug)
 * die **Kreuztabelle fix/variabel × direkt/umgelegt** mit Summen aus `getCostClassification` (die Achsen werden nicht addiert, die Gesamtsumme kommt vom Server), die **Monatsauswertung nach Kostenart**
 * (`getVehicleTotalCost`), **Energiekosten je 100 km mit Nenner** (`getAnalyticsMetrics`, je Energieart getrennt), die Hinweise „Datenvollständigkeit“, „ungeklärte Belege“ und „km unzugeordnet“
 * (`getVehicleKmClasses`) und der **Drilldown auf Ursprungsereignisse** (`listCostEvents` mit denselben Filtern wie die Auswertung). Zustände: Datenlücke (nichts gebucht, ohne Scheinwert), Nenner 0
 * („nicht berechenbar“), vorläufiger Wert mit Abdeckungsgrad, Ladefehler. In P1 ausgeblendet (Abgrenzung, FCO-006 ff., KPI-027, M8): Sichten Plan, Abweichung, Deckungsbeitrag und Partnerkosten. Die Rechtsträger
 * kommen vom Server (nur die des Mandanten); die Anzeige ist keine Rechtequelle, der Server prüft `costs:read` und `analytics:read` bei jeder Anfrage erneut.
 */
export function Kostenuebersicht() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Kostenübersicht je Kostenobjekt' });
  const scopes = new Set(session.status === 'ready' ? session.me.scopes : []);
  const canRead = scopes.has('costs:read');
  const canMetrics = scopes.has('analytics:read');

  const [entities, setEntities] = useState<ResourceState<LegalEntity[]>>({ status: 'loading' });
  const [entity, setEntity] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [result, setResult] = useState<ResourceState<CostClassification> | undefined>();
  const [asked, setAsked] = useState<
    { from: string; to: string; currency: string; entity: string } | undefined
  >();

  useEffect(() => {
    if (!canRead) return;
    let alive = true;
    api
      .call('listLegalEntities', { query: { limit: 100 } })
      .then((page) => {
        if (!alive) return;
        setEntities({ status: 'ready', data: page.items });
        setEntity((cur) => cur || page.items[0]?.id || '');
      })
      .catch(
        (e: unknown) =>
          alive &&
          setEntities({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
    return () => {
      alive = false;
    };
  }, [api, canRead]);

  const entityList = entities.status === 'ready' ? entities.data : [];
  const valid =
    entity !== '' && DAY.test(from) && DAY.test(to) && from < to && CURRENCY.test(currency);

  const load = () => {
    if (!valid || !canRead) return;
    setResult({ status: 'loading' });
    setAsked({ from, to, currency, entity });
    api
      .call('getCostClassification', {
        query: {
          valuation: 'ACTUAL',
          currency,
          period_from: from,
          period_to: to,
          legal_entity_id: entity,
        },
      })
      .then((data) => setResult({ status: 'ready', data }))
      .catch((e: unknown) =>
        setResult({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  if (!context) return null;

  return (
    <ScreenFrame title="Kostenübersicht je Kostenobjekt" context={context}>
      <p>
        Gebuchte Ist-Kosten je Kostenobjekt als Kreuztabelle aus Kostenverhalten (fix, variabel) und
        Zurechnung (direkt, umgelegt), mit Kostenarten, Kilometern und Energiekosten. Plan,
        Abweichung, Deckungsbeitrag und Partnerkosten sind in dieser Ausbaustufe ausgeblendet.
      </p>
      {!canRead && (
        <p role="status">Für diese Sitzung ist die Kostenübersicht nicht freigeschaltet.</p>
      )}
      <form
        aria-label="Auswertung der Kostenübersicht"
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
      >
        <div>
          <label htmlFor="ku-rechtstraeger">Rechtsträger</label>
          <select
            id="ku-rechtstraeger"
            value={entity}
            onChange={(e) => setEntity(e.target.value)}
            disabled={!canRead || entityList.length === 0}
          >
            {entityList.length === 0 && <option value="">Kein Rechtsträger verfügbar</option>}
            {entityList.map((le) => (
              <option key={le.id} value={le.id}>
                {le.name}
              </option>
            ))}
          </select>
          {entities.status === 'error' && (
            <p role="alert">Die Rechtsträger konnten nicht geladen werden.</p>
          )}
        </div>
        <div>
          <label htmlFor="ku-von">Beginn der Periode (JJJJ-MM-TT, inklusive)</label>
          <input
            id="ku-von"
            type="text"
            inputMode="numeric"
            placeholder="2026-09-01"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="ku-bis">Ende der Periode (JJJJ-MM-TT, exklusive)</label>
          <input
            id="ku-bis"
            type="text"
            inputMode="numeric"
            placeholder="2026-12-01"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="ku-waehrung">Währung</label>
          <input
            id="ku-waehrung"
            type="text"
            maxLength={3}
            value={currency}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
          />
        </div>
        <p>
          Sicht: <StatusBadge tone="info">Ist gebucht</StatusBadge>
        </p>
        <button type="submit" disabled={!valid || !canRead}>
          Auswerten
        </button>
      </form>

      {result && asked && (
        <section aria-label="Kostenübersicht">
          <ResourceView
            state={result}
            isEmpty={(d) => d.rows.length === 0}
            empty={{
              title: 'Keine gebuchten Kosten im Zeitraum',
              hint: 'Für diese Periode sind keine gebuchten Kosten vorhanden (Datenlücke, kein Fehler). Es wird kein Wert 0 ausgewiesen; ungebuchte Belege zählen erst nach der Freigabe.',
            }}
            errorAction={
              <button type="button" onClick={load}>
                Erneut versuchen
              </button>
            }
          >
            {(d) => <Ergebnis data={d} asked={asked} canMetrics={canMetrics} />}
          </ResourceView>
        </section>
      )}
    </ScreenFrame>
  );
}

function Ergebnis({
  data,
  asked,
  canMetrics,
}: {
  data: CostClassification;
  asked: { from: string; to: string; currency: string; entity: string };
  canMetrics: boolean;
}) {
  const [selected, setSelected] = useState<string | undefined>();
  const rows = data.rows;
  const key = (r: CostClassificationRow) => r.cost_object_id ?? 'UNALLOCATED';
  const row = rows.find((r) => key(r) === selected);
  const unassigned = rows.filter((r) => r.cost_object_id === null);
  const unclarified =
    data.totals.unclassified_minor +
    unassigned.reduce((a, r) => a + r.totals.total_minor - r.totals.unclassified_minor, 0);
  const unclarifiedEvents = data.rows.reduce(
    (a, r) =>
      a +
      r.cells
        .filter((c) => c.cost_behavior === null || r.cost_object_id === null)
        .reduce((b, c) => b + c.event_count, 0),
    0,
  );
  return (
    <div>
      <p>
        Bewertungsart {data.valuation} · Währung {data.currency} · Periode {data.period_from} bis{' '}
        {data.period_to} (exklusive, Buchungsdatum) · Datenstand {data.as_of} · {data.event_count}{' '}
        Ereignisse
      </p>
      <section aria-label="Hinweise">
        <h2>Hinweise</h2>
        <ul>
          <li>
            Datenvollständigkeit: nur gebuchte Ereignisse bis zum Datenstand {data.as_of}; nicht
            freigegebene Belege fehlen noch.
          </li>
          <li>
            Ungeklärte Belege:{' '}
            {unclarified === 0
              ? 'keine (alle Kosten klassifiziert und einem Kostenobjekt zugeordnet).'
              : `${formatMinor(unclarified, data.currency)} in ${unclarifiedEvents} Ereignissen ohne Klassifikation oder ohne Kostenobjekt.`}
          </li>
        </ul>
      </section>
      <table aria-label="Kostenobjekte">
        <caption>Kostenobjekte der Periode</caption>
        <thead>
          <tr>
            <th scope="col">Kostenobjekt</th>
            <th scope="col">Art</th>
            <th scope="col">Summe</th>
            <th scope="col">Auswahl</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={key(r)}>
              <th scope="row">
                {r.cost_object_id === null
                  ? 'nicht zugeordnet'
                  : (r.business_ref ?? r.cost_object_id)}
              </th>
              <td>{r.cost_object_kind}</td>
              <td>{formatMinor(r.totals.total_minor, data.currency)}</td>
              <td>
                <button
                  type="button"
                  aria-pressed={selected === key(r)}
                  onClick={() => setSelected(key(r))}
                >
                  Anzeigen
                  <span className="tk-sr-only"> {r.business_ref ?? r.cost_object_kind}</span>
                </button>
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Gesamt</th>
            <td />
            <td>{formatMinor(data.totals.total_minor, data.currency)}</td>
            <td />
          </tr>
        </tfoot>
      </table>
      {row && <Objekt key={key(row)} row={row} data={data} asked={asked} canMetrics={canMetrics} />}
    </div>
  );
}

function Objekt({
  row,
  data,
  asked,
  canMetrics,
}: {
  row: CostClassificationRow;
  data: CostClassification;
  asked: { from: string; to: string; currency: string; entity: string };
  canMetrics: boolean;
}) {
  const api = useApi();
  const cur = data.currency;
  const vehicle = row.vehicle_id;
  const [drill, setDrill] = useState<Drill | undefined>();
  const [monthly, setMonthly] = useState<ResourceState<VehicleTotalCost>>({ status: 'loading' });
  const [energy, setEnergy] = useState<ResourceState<AnalyticsMetrics>>({ status: 'loading' });
  const [km, setKm] = useState<ResourceState<KmMetrics>>({ status: 'loading' });
  const monthFrom = asked.from.slice(0, 7);
  const monthTo = lastDay(asked.to).slice(0, 7);

  useEffect(() => {
    if (!vehicle) return;
    let alive = true;
    const fail = (e: unknown) => ({
      status: 'error' as const,
      error: e instanceof ApiError ? e : { status: 0 },
    });
    api
      .call('getVehicleTotalCost', {
        path: { vehicle_id: vehicle },
        query: { valuation: 'ACTUAL', currency: cur, month_from: monthFrom, month_to: monthTo },
      })
      .then((d) => alive && setMonthly({ status: 'ready', data: d }))
      .catch((e: unknown) => alive && setMonthly(fail(e)));
    if (canMetrics)
      api
        .call('getAnalyticsMetrics', {
          query: {
            metric_id: 'energy_cost_per_100km',
            from: asked.from,
            to: lastDay(asked.to),
            vehicle_id: vehicle,
            currency: cur,
          },
        })
        .then((d) => alive && setEnergy({ status: 'ready', data: d }))
        .catch((e: unknown) => alive && setEnergy(fail(e)));
    api
      .call('getVehicleKmClasses', {
        query: {
          metric: 'VEHICLE_KM_CLASSES',
          from: asked.from,
          to: lastDay(asked.to),
          vehicle_id: vehicle,
        },
      })
      .then((d) => alive && setKm({ status: 'ready', data: d }))
      .catch((e: unknown) => alive && setKm(fail(e)));
    return () => {
      alive = false;
    };
  }, [api, vehicle, cur, monthFrom, monthTo, asked.from, asked.to, canMetrics]);

  const titel =
    row.cost_object_id === null ? 'nicht zugeordnet' : (row.business_ref ?? row.cost_object_kind);
  return (
    <section aria-label={`Kostenobjekt ${titel}`}>
      <h2>Kostenobjekt {titel}</h2>
      <Kreuztabelle row={row} currency={cur} onDrill={(d) => setDrill(d)} />
      {vehicle && (
        <>
          <Hinweise km={km} />
          <section aria-label="Monatsauswertung">
            <h3>Monatsauswertung nach Kostenart</h3>
            <ResourceView
              state={monthly}
              isEmpty={(d) => d.items.length === 0}
              empty={{
                title: 'Keine Kosten im gewählten Zeitraum',
                hint: 'Für diese Monate sind keine gebuchten Kosten des Fahrzeugs erfasst (Datenlücke, kein Fehler).',
              }}
            >
              {(d) => (
                <Monate
                  data={d}
                  range={{ from: monthFrom, to: monthTo }}
                  onDrill={(category, label) => setDrill({ title: `Kostenart ${label}`, category })}
                />
              )}
            </ResourceView>
          </section>
          <section aria-label="Energiekosten">
            <h3>Energiekosten je 100 km</h3>
            {!canMetrics ? (
              <p role="status">Für diese Sitzung sind die Kennzahlen nicht freigeschaltet.</p>
            ) : (
              <ResourceView
                state={energy}
                isEmpty={(d) => !d.values || d.values.length === 0}
                empty={{ title: 'Keine Energiekosten', hint: 'Keine Auswertung vorhanden.' }}
              >
                {(d) => <Energie values={d.values ?? []} />}
              </ResourceView>
            )}
          </section>
        </>
      )}
      {drill && (
        <Ursprung
          key={JSON.stringify(drill)}
          drill={drill}
          row={row}
          asked={asked}
          currency={cur}
          onClose={() => setDrill(undefined)}
        />
      )}
    </section>
  );
}

function Kreuztabelle({
  row,
  currency,
  onDrill,
}: {
  row: CostClassificationRow;
  currency: string;
  onDrill: (d: Drill) => void;
}) {
  const t = row.totals;
  const money = (n: number) => formatMinor(n, currency);
  const rowSum = (b: Verhalten) =>
    b === 'UNCLASSIFIED'
      ? t.unclassified_minor
      : { FIXED: t.fixed_minor, VARIABLE: t.variable_minor, MIXED: t.mixed_minor }[b];
  return (
    <table aria-label="Kosten nach Kostenverhalten und Zurechnung">
      <caption>Kreuztabelle fix/variabel × direkt/umgelegt</caption>
      <thead>
        <tr>
          <th scope="col">Kostenverhalten</th>
          {ZURECHNUNG.map((z) => (
            <th key={z.key} scope="col">
              {z.label}
            </th>
          ))}
          <th scope="col">Summe</th>
        </tr>
      </thead>
      <tbody>
        {VERHALTEN.map((v) => (
          <tr key={v.key}>
            <th scope="row">{v.label}</th>
            {ZURECHNUNG.map((z) => {
              const c = cellOf(row, v.key, z.key);
              return (
                <td key={z.key}>
                  {c ? (
                    <>
                      {money(c.amount_minor)}{' '}
                      <button
                        type="button"
                        onClick={() =>
                          onDrill({
                            title: `${v.label} · ${z.label}`,
                            behavior: v.key,
                            attribution: z.key,
                          })
                        }
                      >
                        Ereignisse
                        <span className="tk-sr-only">
                          {' '}
                          {v.label} {z.label}
                        </span>
                      </button>
                    </>
                  ) : (
                    '–'
                  )}
                </td>
              );
            })}
            <td>{money(rowSum(v.key))}</td>
          </tr>
        ))}
        <tr>
          <th scope="row">nicht klassifiziert</th>
          <td colSpan={2}>
            {t.unclassified_minor > 0 ? (
              <>
                {money(t.unclassified_minor)}{' '}
                <button
                  type="button"
                  onClick={() =>
                    onDrill({ title: 'nicht klassifiziert', behavior: 'UNCLASSIFIED' })
                  }
                >
                  Ereignisse<span className="tk-sr-only"> nicht klassifiziert</span>
                </button>
              </>
            ) : (
              '–'
            )}
          </td>
          <td>{money(t.unclassified_minor)}</td>
        </tr>
      </tbody>
      <tfoot>
        <tr>
          <th scope="row">Summe</th>
          <td>{money(t.direct_minor)}</td>
          <td>{money(t.allocated_minor)}</td>
          <td>{money(t.total_minor)}</td>
        </tr>
      </tfoot>
    </table>
  );
}

function Hinweise({ km }: { km: ResourceState<KmMetrics> }) {
  return (
    <section aria-label="Kilometer">
      <h3>Kilometer</h3>
      <ResourceView
        state={km}
        isEmpty={(d) => d.days.length === 0}
        empty={{
          title: 'Keine Fahrten im Zeitraum',
          hint: 'Keine Kilometerklassifikation vorhanden (keine Fahrten oder noch nicht klassifiziert).',
        }}
      >
        {(d) => (
          <p>
            Gesamtkilometer {formatKm(d.total_km_thousandths)} · km unzugeordnet:{' '}
            {formatKm(d.unassigned_km_thousandths)}{' '}
            <StatusBadge tone={d.completeness === 'COMPLETE' ? 'ok' : 'warning'}>
              {d.completeness === 'COMPLETE' ? 'Vollständig' : 'Unvollständig'}
            </StatusBadge>
          </p>
        )}
      </ResourceView>
    </section>
  );
}

function Monate({
  data,
  range,
  onDrill,
}: {
  data: VehicleTotalCost;
  range: { from: string; to: string };
  onDrill: (category: string, label: string) => void;
}) {
  const months = monthsBetween(range.from, range.to);
  const order = new Map(KOSTENGRUPPEN.map((g, i) => [g.value, i]));
  const categories = [...new Set(data.items.map((i) => i.category))].sort(
    (a, b) => (order.get(a) ?? 99) - (order.get(b) ?? 99) || a.localeCompare(b),
  );
  const label = (cat: string) =>
    data.items.find((i) => i.category === cat)?.label ??
    KOSTENGRUPPEN.find((g) => g.value === cat)?.label ??
    cat;
  const cell = (cat: string, m: string) =>
    data.items.find((i) => i.category === cat && i.month === m)?.amount_minor;
  const catTotal = (cat: string) =>
    data.category_totals.find((t) => t.category === cat)?.amount_minor ?? 0;
  const monthTotal = (m: string) => data.month_totals.find((t) => t.month === m)?.amount_minor ?? 0;
  const money = (n: number | undefined) => (n === undefined ? '–' : formatMinor(n, data.currency));
  return (
    <div>
      <p>
        Datenstand {data.as_of} · Monatszuordnung: {data.assignment_rule}
      </p>
      <table aria-label="Kosten je Monat und Kostenart">
        <caption>Kosten des Fahrzeugs je Monat</caption>
        <thead>
          <tr>
            <th scope="col">Kostenart</th>
            {months.map((m) => (
              <th key={m} scope="col">
                {m.slice(5)}/{m.slice(0, 4)}
              </th>
            ))}
            <th scope="col">Summe</th>
            <th scope="col">Ursprung</th>
          </tr>
        </thead>
        <tbody>
          {categories.map((cat) => (
            <tr key={cat}>
              <th scope="row">{label(cat)}</th>
              {months.map((m) => (
                <td key={m}>{money(cell(cat, m))}</td>
              ))}
              <td>{money(catTotal(cat))}</td>
              <td>
                <button type="button" onClick={() => onDrill(cat, label(cat))}>
                  Ereignisse<span className="tk-sr-only"> {label(cat)}</span>
                </button>
              </td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Summe</th>
            {months.map((m) => (
              <td key={m}>{money(monthTotal(m))}</td>
            ))}
            <td>{money(data.total_minor)}</td>
            <td />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

const ENERGIEART: Record<string, string> = { FUEL: 'Kraftstoff (Liter)', ELECTRIC: 'Strom (kWh)' };

function Energie({ values }: { values: MetricValue[] }) {
  return (
    <div>
      {values.map((v, i) => (
        <EnergieWert key={`${v.filter.energy}-${v.filter.currency ?? i}`} v={v} />
      ))}
      <p>
        P1-Näherung: Erwerbsbasis ohne Bestandsführung und Ladeverluste (getankte, nicht verbrauchte
        Energie). Kraftstoff und Strom werden nie gemischt, Währungen nie addiert.
      </p>
    </div>
  );
}

function EnergieWert({ v }: { v: MetricValue }) {
  const art = ENERGIEART[v.filter.energy ?? ''] ?? 'Energie';
  const cur = v.filter.currency ?? '';
  let inhalt;
  if (v.state === 'NOT_COMPUTABLE') {
    inhalt =
      v.reason === 'ZERO_DENOMINATOR' ? (
        <p>
          <strong>nicht berechenbar</strong>: Nenner 0 (keine Kilometer im Zeitraum), Kostenbasis{' '}
          {cur ? formatMinor(v.numerator ?? 0, cur) : '–'}.
        </p>
      ) : (
        <p>
          <strong>keine plausibilisierten Tankvorgänge</strong> im Zeitraum (kein Wert, nicht 0
          Kosten).
        </p>
      );
  } else {
    inhalt = (
      <p>
        <strong>{formatMinor(v.value ?? 0, cur)} je 100 km</strong> · Kostenbasis{' '}
        {formatMinor(v.numerator ?? 0, cur)} · Nenner{' '}
        {formatKm(Math.round((v.denominator ?? 0) * 1000))}{' '}
        {v.state === 'PROVISIONAL' && (
          <StatusBadge tone="warning">vorläufig, Abdeckung {percent(v.coverage ?? 0)}</StatusBadge>
        )}
      </p>
    );
  }
  return (
    <section aria-label={`Energiekosten ${art}`}>
      <h4>{art}</h4>
      {inhalt}
      <p>
        Definition Version {v.definition_version} · Datenstand {v.as_of ?? '–'}
      </p>
      {v.exclusions.length > 0 && (
        <details>
          <summary>{v.exclusions.length} Tankvorgänge nicht berücksichtigt</summary>
          <ul>
            {v.exclusions.map((e) => (
              <li key={`${e.document_id}-${e.reason}`}>
                Beleg {e.document_id}: {GRUND[e.reason] ?? e.reason}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}

type Ereignisse = { items: CostEvent[]; next: string | null };

function Ursprung({
  drill,
  row,
  asked,
  currency,
  onClose,
}: {
  drill: Drill;
  row: CostClassificationRow;
  asked: { from: string; to: string; entity: string };
  currency: string;
  onClose: () => void;
}) {
  const api = useApi();
  const [page, setPage] = useState<ResourceState<Ereignisse>>({ status: 'loading' });
  const query = useMemo(() => {
    const q: Record<string, string> = {
      legal_entity_id: asked.entity,
      currency,
      period_from: asked.from,
      period_to: asked.to,
    };
    if (row.cost_object_id) q.cost_object_id = row.cost_object_id;
    if (drill.behavior) q.cost_behavior = drill.behavior;
    if (drill.attribution) q.attribution = drill.attribution;
    if (drill.category) q.category = drill.category;
    return q;
  }, [asked, currency, row.cost_object_id, drill]);

  const load = (cursor?: string) => {
    api
      .call('listCostEvents', { query: (cursor ? { ...query, cursor } : query) as never })
      .then((p) =>
        setPage((cur) => ({
          status: 'ready',
          data: {
            items: [...(cursor && cur.status === 'ready' ? cur.data.items : []), ...p.items],
            next: p.next_cursor,
          },
        })),
      )
      .catch((e: unknown) =>
        setPage({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- nur bei geänderter Auswahl neu laden
  }, [api, query]);

  return (
    <section aria-label="Ursprungsereignisse">
      <h3>Ursprungsereignisse: {drill.title}</h3>
      <button type="button" onClick={onClose}>
        Schließen
      </button>
      <ResourceView
        state={page}
        isEmpty={(d) => d.items.length === 0}
        empty={{
          title: 'Keine Ereignisse',
          hint: 'Zu dieser Auswahl gibt es keine gebuchten Ereignisse.',
        }}
        errorAction={
          <button type="button" onClick={() => load()}>
            Erneut versuchen
          </button>
        }
      >
        {(d) => (
          <div>
            <table aria-label="Ursprungsereignisse der Auswahl">
              <thead>
                <tr>
                  <th scope="col">Kostenart</th>
                  <th scope="col">Quelle</th>
                  <th scope="col">Beleg</th>
                  <th scope="col">Gebucht am</th>
                  <th scope="col">Kostenbasis</th>
                </tr>
              </thead>
              <tbody>
                {d.items.map((e) => (
                  <tr key={e.id}>
                    <th scope="row">{e.category}</th>
                    <td>
                      {e.source_system} {e.source_ref}
                    </td>
                    <td>{e.document_id ?? '–'}</td>
                    <td>{e.booked_at}</td>
                    <td>{formatMinor(e.cost_basis_minor, e.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {d.next !== null && (
              <button type="button" onClick={() => load(d.next ?? undefined)}>
                Weitere Ereignisse laden
              </button>
            )}
          </div>
        )}
      </ResourceView>
    </section>
  );
}
