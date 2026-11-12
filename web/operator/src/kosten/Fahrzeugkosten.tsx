import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { VehicleTotalCost } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useState } from 'react';
import { formatMinor, parseMoney } from '../belege/betrag';
import { useScreenContext } from '../ScreenContext';

/** Die sechs Fahrzeugkostengruppen (FCO-002) in der Reihenfolge der Anzeige; Schlüssel = Kostenart des Katalogs. */
export const KOSTENGRUPPEN: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'ACQUISITION_LEASE', label: 'Anschaffung/Leasing' },
  { value: 'INSURANCE', label: 'Versicherung' },
  { value: 'VEHICLE_TAX', label: 'Kfz-Steuer' },
  { value: 'ENERGY', label: 'Energie' },
  { value: 'MAINTENANCE', label: 'Wartung' },
  { value: 'REPAIR', label: 'Reparatur' },
];

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** Alle Monate von `from` bis `to` (inklusive, `JJJJ-MM`); leer bei ungültigem oder umgekehrtem Bereich. */
export function monthsBetween(from: string, to: string): string[] {
  if (!MONTH.test(from) || !MONTH.test(to) || from > to) return [];
  const out: string[] = [];
  let [y, m] = from.split('-').map(Number) as [number, number];
  const [ty, tm] = to.split('-').map(Number) as [number, number];
  while (y < ty || (y === ty && m <= tm)) {
    out.push(`${y}-${String(m).padStart(2, '0')}`);
    m += 1;
    if (m > 12) {
      m = 1;
      y += 1;
    }
  }
  return out;
}

const monthLabel = (m: string) => `${m.slice(5)}/${m.slice(0, 4)}`;

/**
 * UI-15 Fahrzeugkosten (WP-M4-40, FCO-002; Grundausbau der Kostenübersicht folgt mit WP-M4-44): **Monatsmatrix Kostenart × Monat** je Fahrzeug aus `getVehicleTotalCost` mit Bewertungsart, Währung, Datenstand
 * und der Zuordnungsregel des Monats; leerer Zeitraum (Datenlücke) ist ein eigener Zustand, ebenso Ladefehler. Der Dialog „Kostenereignis erfassen“ legt ein **freigabepflichtiges** manuelles Kostenereignis an
 * (`createManualCostEvent`); ohne das Recht `costs:write` bleibt die Maske beim Lesen. Die Anzeige ist keine Rechtequelle: der Server prüft jede Anfrage erneut.
 */
export function Fahrzeugkosten() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Fahrzeugkosten je Monat' });
  const scopes = new Set(session.status === 'ready' ? session.me.scopes : []);
  const canRead = scopes.has('costs:read');
  const canWrite = scopes.has('costs:write');

  const [vehicle, setVehicle] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [result, setResult] = useState<ResourceState<VehicleTotalCost> | undefined>();
  const [asked, setAsked] = useState<{ from: string; to: string } | undefined>();

  const valid = UUID.test(vehicle.trim()) && MONTH.test(from) && MONTH.test(to) && from <= to;

  const load = () => {
    if (!valid || !canRead) return;
    setResult({ status: 'loading' });
    setAsked({ from, to });
    api
      .call('getVehicleTotalCost', {
        path: { vehicle_id: vehicle.trim() },
        query: { valuation: 'ACTUAL', currency: 'EUR', month_from: from, month_to: to },
      })
      .then((data) => setResult({ status: 'ready', data }))
      .catch((e: unknown) =>
        setResult({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  if (!context) return null;

  return (
    <ScreenFrame title="Fahrzeugkosten je Monat und Kostenart" context={context}>
      <p>
        Kosten je Fahrzeug und Monat nach Kostenart, ohne manuelle Tabellen. Anschaffung/Leasing
        erscheint mit der erfassten Position (keine Abschreibung, keine Verteilung).
      </p>
      {!canRead && (
        <p role="status">Für diese Sitzung ist die Kostenauswertung nicht freigeschaltet.</p>
      )}
      <form
        aria-label="Auswertung"
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
      >
        <div>
          <label htmlFor="fk-fahrzeug">Fahrzeug (Kennung)</label>
          <input
            id="fk-fahrzeug"
            type="text"
            value={vehicle}
            onChange={(e) => setVehicle(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="fk-von">Erster Monat (JJJJ-MM)</label>
          <input
            id="fk-von"
            type="text"
            inputMode="numeric"
            placeholder="2026-09"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="fk-bis">Letzter Monat (JJJJ-MM)</label>
          <input
            id="fk-bis"
            type="text"
            inputMode="numeric"
            placeholder="2026-11"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <button type="submit" disabled={!valid || !canRead}>
          Auswerten
        </button>
      </form>

      {result && (
        <section aria-label="Monatsmatrix">
          <h2>Monatsmatrix Kostenart × Monat</h2>
          <ResourceView
            state={result}
            isEmpty={(d) => d.items.length === 0}
            empty={{
              title: 'Keine Kosten im gewählten Zeitraum',
              hint: 'Für diese Monate sind keine gebuchten Kosten erfasst (Datenlücke, kein Fehler). Erfassen Sie fehlende Kosten über „Kostenereignis erfassen“.',
            }}
            errorAction={
              <button type="button" onClick={load}>
                Erneut versuchen
              </button>
            }
          >
            {(d) => <Matrix data={d} range={asked ?? { from: d.month_from, to: d.month_to }} />}
          </ResourceView>
        </section>
      )}

      <ErfassenDialog
        vehicle={vehicle.trim()}
        canWrite={canWrite}
        onCreated={() => {
          if (valid) load();
        }}
      />
    </ScreenFrame>
  );
}

function Matrix({ data, range }: { data: VehicleTotalCost; range: { from: string; to: string } }) {
  const months = monthsBetween(range.from, range.to);
  const order = new Map(KOSTENGRUPPEN.map((g, i) => [g.value, i]));
  const categories = [...new Set(data.items.map((i) => i.category))].sort(
    (a, b) => (order.get(a) ?? 99) - (order.get(b) ?? 99) || a.localeCompare(b),
  );
  const cell = (cat: string, m: string) =>
    data.items.find((i) => i.category === cat && i.month === m)?.amount_minor;
  const label = (cat: string) =>
    data.items.find((i) => i.category === cat)?.label ??
    KOSTENGRUPPEN.find((g) => g.value === cat)?.label ??
    cat;
  const monthTotal = (m: string) => data.month_totals.find((t) => t.month === m)?.amount_minor ?? 0;
  const catTotal = (cat: string) =>
    data.category_totals.find((t) => t.category === cat)?.amount_minor ?? 0;
  return (
    <div>
      <p>
        Bewertungsart {data.valuation} · Währung {data.currency} · Datenstand {data.as_of}
      </p>
      <p>Monatszuordnung: {data.assignment_rule}</p>
      <table aria-label="Kosten je Monat und Kostenart">
        <caption>
          Kosten des Fahrzeugs je Monat ({monthLabel(range.from)} bis {monthLabel(range.to)})
        </caption>
        <thead>
          <tr>
            <th scope="col">Kostenart</th>
            {months.map((m) => (
              <th key={m} scope="col">
                {monthLabel(m)}
              </th>
            ))}
            <th scope="col">Summe</th>
          </tr>
        </thead>
        <tbody>
          {categories.map((cat) => (
            <tr key={cat}>
              <th scope="row">{label(cat)}</th>
              {months.map((m) => {
                const v = cell(cat, m);
                return <td key={m}>{v === undefined ? '–' : formatMinor(v, data.currency)}</td>;
              })}
              <td>{formatMinor(catTotal(cat), data.currency)}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Summe</th>
            {months.map((m) => (
              <td key={m}>{formatMinor(monthTotal(m), data.currency)}</td>
            ))}
            <td>{formatMinor(data.total_minor, data.currency)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/** Dialog „Kostenereignis erfassen“: Fahrzeug, Kostenart, Betrag, Leistungszeitraum, Ursprungsschlüssel; ohne `costs:write` nur der Hinweis „nur Lesen“. */
function ErfassenDialog({
  vehicle,
  canWrite,
  onCreated,
}: {
  vehicle: string;
  canWrite: boolean;
  onCreated: () => void;
}) {
  const api = useApi();
  const [entity, setEntity] = useState('');
  const [category, setCategory] = useState('INSURANCE');
  const [amount, setAmount] = useState('');
  const [date, setDate] = useState('');
  const [serviceFrom, setServiceFrom] = useState('');
  const [serviceTo, setServiceTo] = useState('');
  const [origin, setOrigin] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | undefined>();

  if (!canWrite) {
    return (
      <section aria-label="Kostenereignis erfassen">
        <h2>Kostenereignis erfassen</h2>
        <p role="status">
          Nur Lesen: für das Erfassen manueller Kostenereignisse fehlt das Recht (costs:write).
        </p>
      </section>
    );
  }

  const minor = parseMoney(amount);
  const ready =
    UUID.test(vehicle) &&
    UUID.test(entity.trim()) &&
    minor !== undefined &&
    minor > 0n &&
    /^\d{4}-\d{2}-\d{2}$/.test(date) &&
    origin.trim().length > 0 &&
    !busy;

  const submit = () => {
    if (!ready || minor === undefined) return;
    setBusy(true);
    setMessage(undefined);
    api
      .call('createManualCostEvent', {
        body: {
          legal_entity_id: entity.trim(),
          vehicle_id: vehicle,
          category,
          gross: { amount_minor: Number(minor), currency: 'EUR', exponent: 2 },
          accounting_date: date,
          ...(serviceFrom ? { service_from: serviceFrom } : {}),
          ...(serviceTo ? { service_to: serviceTo } : {}),
          origin_key: origin.trim(),
        },
      })
      .then((e) => {
        setMessage({
          kind: 'ok',
          text: `Kostenereignis erfasst und zur Prüfung eingereicht (Status ${e.state}). Es wird erst nach der Freigabe gebucht.`,
        });
        onCreated();
      })
      .catch((e: unknown) => {
        const status = e instanceof ApiError ? e.status : 0;
        setMessage({
          kind: 'error',
          text:
            status === 409
              ? 'Der Ursprungsschlüssel ist bereits mit anderem Inhalt erfasst oder die Kontierung fehlt (409).'
              : status === 404
                ? 'Fahrzeug oder Rechtsträger nicht gefunden (404).'
                : status === 422
                  ? 'Die Eingabe wurde abgewiesen (422): Kostenart, Betrag und Datum prüfen.'
                  : status === 403
                    ? 'Dafür fehlt das Recht (403).'
                    : 'Das Kostenereignis konnte nicht erfasst werden.',
        });
      })
      .finally(() => setBusy(false));
  };

  return (
    <section aria-label="Kostenereignis erfassen">
      <h2>Kostenereignis erfassen</h2>
      <p>
        Das Ereignis ist freigabepflichtig: Prüfung und Freigabe erfolgen durch andere Personen
        (Vier-Augen-Prinzip); erst danach wird es gebucht.
      </p>
      <div>
        <label htmlFor="ke-rechtstraeger">Rechtsträger (Kennung)</label>
        <input
          id="ke-rechtstraeger"
          type="text"
          value={entity}
          onChange={(e) => setEntity(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="ke-kostenart">Kostenart</label>
        <select id="ke-kostenart" value={category} onChange={(e) => setCategory(e.target.value)}>
          {KOSTENGRUPPEN.map((g) => (
            <option key={g.value} value={g.value}>
              {g.label}
            </option>
          ))}
        </select>
      </div>
      <div>
        <label htmlFor="ke-betrag">Bruttobetrag in EUR</label>
        <input
          id="ke-betrag"
          type="text"
          inputMode="decimal"
          value={amount}
          aria-invalid={amount !== '' && minor === undefined}
          onChange={(e) => setAmount(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="ke-buchung">Buchungsdatum</label>
        <input id="ke-buchung" type="date" value={date} onChange={(e) => setDate(e.target.value)} />
      </div>
      <div>
        <label htmlFor="ke-leistung-von">Leistungszeitraum ab (optional)</label>
        <input
          id="ke-leistung-von"
          type="date"
          value={serviceFrom}
          onChange={(e) => setServiceFrom(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="ke-leistung-bis">Leistungszeitraum bis (optional)</label>
        <input
          id="ke-leistung-bis"
          type="date"
          value={serviceTo}
          onChange={(e) => setServiceTo(e.target.value)}
        />
      </div>
      <div>
        <label htmlFor="ke-ursprung">Ursprungsschlüssel</label>
        <input
          id="ke-ursprung"
          type="text"
          value={origin}
          maxLength={200}
          onChange={(e) => setOrigin(e.target.value)}
        />
      </div>
      <button type="button" disabled={!ready} onClick={submit}>
        Kostenereignis erfassen
      </button>
      {!UUID.test(vehicle) && <p>Wählen Sie zuerst ein Fahrzeug (Kennung oben eintragen).</p>}
      {message?.kind === 'ok' && (
        <p role="status">
          <StatusBadge tone="ok">Erfasst</StatusBadge> {message.text}
        </p>
      )}
      {message?.kind === 'error' && (
        <p role="alert">
          <StatusBadge tone="error">Fehler</StatusBadge> {message.text}
        </p>
      )}
    </section>
  );
}
