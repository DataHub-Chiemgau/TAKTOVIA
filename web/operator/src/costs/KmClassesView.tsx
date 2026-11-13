import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { KmMetrics } from '@taktovia/api-client';
import { ResourceView, ScreenFrame, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useState } from 'react';
import { useScreenContext } from '../ScreenContext';

/** Die vier Kategorien der Fahrzeugkilometer (FCO-005) in der Reihenfolge der Anzeige; die nicht zuordenbaren Kilometer folgen als eigene Zeile. */
export const KILOMETERKLASSEN = [
  { key: 'PASSENGER', label: 'Fahrgastfahrt' },
  { key: 'EMPTY', label: 'Leerfahrt' },
  { key: 'TRANSFER', label: 'Überführung' },
  { key: 'WORKSHOP', label: 'Werkstattfahrt' },
] as const;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DAY = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

/** Tausendstel Kilometer (ganzzahlig) als Kilometer mit drei Nachkommastellen, deutsches Format. */
export function formatKm(thousandths: number): string {
  const km = Math.trunc(thousandths / 1000);
  const rest = String(thousandths % 1000).padStart(3, '0');
  return `${km.toLocaleString('de-DE')},${rest} km`;
}

/**
 * Kilometerklassen je Fahrzeug und Zeitraum (WP-M4-42, FCO-005; Baustein der Kostenübersicht UI-15 aus WP-M4-44 und des Kennzahlenteils): Kilometer je Kategorie mit **separater Zeile „nicht zuordenbar“**
 * (nie auf andere Kategorien verteilt), Gesamtkilometer, Datenstand und Vollständigkeit aus `getVehicleKmClasses`. Ohne Fahrten im Zeitraum erscheint „keine Fahrten im Zeitraum“. Es werden keine
 * Fahrerbezüge angezeigt. Die Anzeige ist keine Rechtequelle: der Server prüft `costs:read` bei jeder Anfrage.
 */
export function KmClassesView() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Kilometerklassen' });
  const scopes = new Set(session.status === 'ready' ? session.me.scopes : []);
  const canRead = scopes.has('costs:read');

  const [vehicle, setVehicle] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [result, setResult] = useState<ResourceState<KmMetrics> | undefined>();

  const vehicleOk = vehicle.trim() === '' || UUID.test(vehicle.trim());
  const valid = vehicleOk && DAY.test(from) && DAY.test(to) && from <= to;

  const load = () => {
    if (!valid || !canRead) return;
    setResult({ status: 'loading' });
    const query: { metric: 'VEHICLE_KM_CLASSES'; from: string; to: string; vehicle_id?: string } = {
      metric: 'VEHICLE_KM_CLASSES',
      from,
      to,
    };
    if (vehicle.trim() !== '') query.vehicle_id = vehicle.trim();
    api
      .call('getVehicleKmClasses', { query })
      .then((data) => setResult({ status: 'ready', data }))
      .catch((e: unknown) =>
        setResult({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } }),
      );
  };

  if (!context) return null;

  return (
    <ScreenFrame title="Kilometerklassen" context={context}>
      <p>
        Fahrzeugkilometer nach Fahrgastfahrt, Leerfahrt, Überführung und Werkstattfahrt. Kilometer
        ohne Fahrtenplan- oder Ereignisdeckung bleiben als „nicht zuordenbar“ separat sichtbar.
      </p>
      {!canRead && (
        <p role="status">Für diese Sitzung ist die Kilometerauswertung nicht freigeschaltet.</p>
      )}
      <form
        aria-label="Auswertung der Kilometerklassen"
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
      >
        <div>
          <label htmlFor="km-fahrzeug">Fahrzeug (Kennung, leer = alle)</label>
          <input
            id="km-fahrzeug"
            type="text"
            value={vehicle}
            onChange={(e) => setVehicle(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="km-von">Erster Betriebstag (JJJJ-MM-TT)</label>
          <input
            id="km-von"
            type="text"
            inputMode="numeric"
            placeholder="2026-06-01"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="km-bis">Letzter Betriebstag (JJJJ-MM-TT)</label>
          <input
            id="km-bis"
            type="text"
            inputMode="numeric"
            placeholder="2026-06-30"
            value={to}
            onChange={(e) => setTo(e.target.value)}
          />
        </div>
        <button type="submit" disabled={!valid || !canRead}>
          Auswerten
        </button>
      </form>

      {result && (
        <section aria-label="Kilometer je Kategorie">
          <h2>Kilometer je Kategorie</h2>
          <ResourceView
            state={result}
            isEmpty={(d) => d.days.length === 0}
            empty={{
              title: 'Keine Fahrten im Zeitraum',
              hint: 'Für diese Betriebstage liegt keine Kilometerklassifikation vor (keine Fahrten oder noch nicht klassifiziert).',
            }}
            errorAction={
              <button type="button" onClick={load}>
                Erneut versuchen
              </button>
            }
          >
            {(d) => <Klassen data={d} />}
          </ResourceView>
        </section>
      )}
    </ScreenFrame>
  );
}

function Klassen({ data }: { data: KmMetrics }) {
  const share = (n: number) =>
    data.total_km_thousandths === 0
      ? '–'
      : `${((n * 100) / data.total_km_thousandths).toFixed(1).replace('.', ',')} %`;
  return (
    <div>
      <p>
        Datenstand {data.as_of ?? '–'} · Regelversion {data.definition_version}{' '}
        <StatusBadge tone={data.completeness === 'COMPLETE' ? 'ok' : 'warning'}>
          {data.completeness === 'COMPLETE' ? 'Vollständig' : 'Unvollständig'}
        </StatusBadge>
      </p>
      <table aria-label="Kilometer je Kategorie">
        <caption>
          Fahrzeugkilometer {data.from} bis {data.to}
        </caption>
        <thead>
          <tr>
            <th scope="col">Kategorie</th>
            <th scope="col">Kilometer</th>
            <th scope="col">Anteil</th>
          </tr>
        </thead>
        <tbody>
          {KILOMETERKLASSEN.map((k) => (
            <tr key={k.key}>
              <th scope="row">{k.label}</th>
              <td>{formatKm(data.km_thousandths_by_category[k.key])}</td>
              <td>{share(data.km_thousandths_by_category[k.key])}</td>
            </tr>
          ))}
          <tr>
            <th scope="row">nicht zuordenbar</th>
            <td>{formatKm(data.unassigned_km_thousandths)}</td>
            <td>{share(data.unassigned_km_thousandths)}</td>
          </tr>
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Gesamtkilometer</th>
            <td>{formatKm(data.total_km_thousandths)}</td>
            <td>{data.total_km_thousandths === 0 ? '–' : '100,0 %'}</td>
          </tr>
        </tfoot>
      </table>
      {data.completeness === 'INCOMPLETE' && (
        <p role="status">
          Die Fahrerereignisse sind unvollständig: Fahrgast- und Leerkilometer können sich noch
          verschieben.
        </p>
      )}
    </div>
  );
}
