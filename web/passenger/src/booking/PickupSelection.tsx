import { useCallback, useEffect, useId, useMemo, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { GeoServiceAreaPassenger, GeoStopPassenger } from '@taktovia/api-client';
import { EmptyState, ErrorState, StateView } from '@taktovia/design-system';
import { StopMap } from './StopMap';
import type { TileSource } from './kartenquelle';

export type { TileSource } from './kartenquelle';
export type PickupStop = GeoStopPassenger;

export interface PickupSelectionProps {
  serviceId: string;
  /** Suchpunkt (Start des Fahrgasts); ohne ihn entfällt die Umkreissuche und die Liste ist nach Name sortiert. */
  origin?: { latitude: number; longitude: number } | null;
  /** Zeitpunkt der Wirksamkeit (RFC 3339), Standard jetzt. */
  at?: string;
  /** Reichweite der Umkreissuche in Metern (Standard der Schnittstelle: 1000). */
  radiusM?: number;
  /** Kartenkacheln; `null` = keine Quelle konfiguriert (Karte ohne Hintergrund). */
  tileSource: TileSource | null;
  /** Wird erst aufgerufen, nachdem der Server den Haltepunkt noch einmal als aktiv und wirksam bestätigt hat. */
  onSelect: (stop: PickupStop) => void;
}

/** Durchschnittliche Gehgeschwindigkeit für die Gehzeitangabe (4,8 km/h); reine Anzeige, keine Zusage. */
const WALK_M_PER_MIN = 80;

type Load =
  | { status: 'loading' }
  | { status: 'error'; error: ApiError }
  | { status: 'ready'; stops: GeoStopPassenger[]; areas: GeoServiceAreaPassenger[] };

const num = (v: number, digits: number) =>
  v.toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits });

function distanceText(m: number): string {
  return m >= 1000 ? `${num(m / 1000, 1)} km` : `${m} m`;
}

function accessibilityText(a: GeoStopPassenger['accessibility']): string {
  const step =
    a.step_free === 'YES'
      ? 'stufenfrei'
      : a.step_free === 'NO'
        ? 'nicht stufenfrei'
        : 'Stufenfreiheit unbestätigt';
  const chair =
    a.wheelchair_boarding === 'YES'
      ? 'Rollstuhleinstieg möglich'
      : a.wheelchair_boarding === 'NO'
        ? 'kein Rollstuhleinstieg'
        : a.wheelchair_boarding === 'UNVERIFIED'
          ? 'Rollstuhleinstieg unbestätigt'
          : undefined;
  return chair ? `${step}, ${chair}` : step;
}

function dateText(iso: string): string {
  return new Date(iso).toLocaleDateString('de-DE', { timeZone: 'Europe/Berlin' });
}

/** Textbeschreibung eines Bediengebiets: Fläche, Ausdehnung und Gültigkeit (gleichwertig zur Karte). */
function areaText(a: GeoServiceAreaPassenger): string {
  const b = a.bounds;
  const validity = a.valid_to
    ? `gültig ab ${dateText(a.valid_from)} bis ${dateText(a.valid_to)}`
    : `gültig ab ${dateText(a.valid_from)}`;
  return (
    `Das Bediengebiet umfasst ca. ${num(a.area_square_km, 1)} km² zwischen ${num(b.min_latitude, 2)}° bis ${num(b.max_latitude, 2)}° Nord ` +
    `und ${num(b.min_longitude, 2)}° bis ${num(b.max_longitude, 2)}° Ost, ${validity}.`
  );
}

/**
 * Abholpunkt wählen (UI-01/UI-02, WP-M2-40, LH-6.3-PAX-004): Karte mit wirksamem Bediengebiet und buchbaren Haltepunkten **und** gleichwertige Haltestellenliste.
 * Beide beziehen dieselbe Menge (eine Abfrage), die Auswahl funktioniert vollständig über die Liste (native Radiogruppe: Tastatur und Screenreader) ohne Karteninteraktion.
 * Fällt die Kartenquelle aus, entfällt nur die Karte. Die Übernahme prüft den Haltepunkt beim Server erneut: ist er inzwischen deaktiviert oder geändert, wird die Auswahl
 * mit Hinweis abgewiesen und die Liste neu geladen.
 */
export function PickupSelection({
  serviceId,
  origin = null,
  at,
  radiusM,
  tileSource,
  onSelect,
}: PickupSelectionProps) {
  const api = useApi();
  const headingId = useId();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [selected, setSelected] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [mapFailed, setMapFailed] = useState(false);

  const fetchData = useCallback(async (): Promise<Load> => {
    try {
      const [stops, areas] = await Promise.all([
        api.call('listStops', {
          query: {
            service_id: serviceId,
            limit: 100,
            ...(at ? { at } : {}),
            ...(origin ? { near_latitude: origin.latitude, near_longitude: origin.longitude } : {}),
            ...(origin && radiusM ? { near_radius_m: radiusM } : {}),
          },
        }),
        api.call('listServiceAreas', { query: { service_id: serviceId, ...(at ? { at } : {}) } }),
      ]);
      // Die Fahrgastsicht liefert nur `GeoStopPassenger`; eine Verwaltungssicht (`approved_by`) darf hier nie ankommen.
      return {
        status: 'ready',
        stops: stops.items.filter((s): s is GeoStopPassenger => !('approved_by' in s)),
        areas: areas.items.filter((a): a is GeoServiceAreaPassenger => !('revision_id' in a)),
      };
    } catch (e) {
      return { status: 'error', error: e instanceof ApiError ? e : new ApiError(0) };
    }
  }, [api, serviceId, at, origin?.latitude, origin?.longitude, radiusM]); // eslint-disable-line react-hooks/exhaustive-deps

  const reload = useCallback(async () => setLoad(await fetchData()), [fetchData]);

  useEffect(() => {
    let current = true;
    void fetchData().then((next) => {
      if (current) setLoad(next);
    });
    return () => {
      current = false;
    };
  }, [fetchData]);

  const stops = useMemo(() => (load.status === 'ready' ? load.stops : []), [load]);

  const confirm = async (e: FormEvent) => {
    e.preventDefault();
    const chosen = stops.find((s) => s.stop_id === selected);
    if (!chosen) return;
    setBusy(true);
    setNotice(undefined);
    try {
      const fresh = await api.call('getStop', { path: { stop_id: chosen.stop_id }, query: {} });
      if (fresh.revision !== chosen.revision) {
        setNotice(
          `${chosen.name} wurde inzwischen geändert. Bitte wählen Sie den Abholpunkt erneut.`,
        );
        setSelected(null);
        await reload();
        return;
      }
      onSelect(chosen);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setNotice(
          `${chosen.name} ist nicht mehr verfügbar. Bitte wählen Sie einen anderen Abholpunkt.`,
        );
        setSelected(null);
        await reload();
      } else {
        setNotice(
          'Der Abholpunkt konnte gerade nicht bestätigt werden. Bitte versuchen Sie es erneut.',
        );
      }
    } finally {
      setBusy(false);
    }
  };

  if (load.status === 'loading')
    return <StateView kind="loading" title="Haltepunkte werden geladen" />;
  if (load.status === 'error') return <ErrorState error={load.error} />;

  const showMap = !mapFailed;
  return (
    <section aria-labelledby={headingId} className="tk-pickup">
      <h2 id={headingId}>Abholpunkt wählen</h2>

      <section aria-label="Bediengebiet" className="tk-pickup-area">
        {load.areas.length === 0 ? (
          <p>Zurzeit ist kein Bediengebiet wirksam.</p>
        ) : (
          load.areas.map((a) => <p key={a.id}>{areaText(a)}</p>)
        )}
      </section>

      {mapFailed ? (
        <p role="status" aria-label="Kartenquelle">
          Die Karte ist gerade nicht erreichbar. Sie können den Abholpunkt trotzdem aus der Liste
          wählen.
        </p>
      ) : null}
      {showMap ? (
        <StopMap
          areas={load.areas}
          stops={stops}
          origin={origin}
          tileSource={tileSource}
          selectedId={selected}
          onPick={(id) => {
            setNotice(undefined);
            setSelected(id);
          }}
          onTileError={() => setMapFailed(true)}
        />
      ) : null}

      {stops.length === 0 ? (
        <EmptyState
          title="Kein Haltepunkt in Reichweite"
          hint="Verschieben Sie den Startpunkt oder versuchen Sie es zu einer anderen Zeit erneut."
        />
      ) : (
        <form onSubmit={(e) => void confirm(e)}>
          <p role="status" aria-label="Ergebnis">
            {stops.length === 1 ? '1 Haltepunkt gefunden' : `${stops.length} Haltepunkte gefunden`}
          </p>
          <div role="radiogroup" aria-label="Abholpunkt" className="tk-pickup-list">
            {stops.map((s) => (
              <label key={s.stop_id} className="tk-pickup-item">
                <input
                  type="radio"
                  name={`${headingId}-pickup`}
                  value={s.stop_id}
                  checked={selected === s.stop_id}
                  onChange={() => {
                    setNotice(undefined);
                    setSelected(s.stop_id);
                  }}
                />{' '}
                <strong>{s.name}</strong>
                <span>
                  {s.distance_m !== null
                    ? `, ${distanceText(s.distance_m)}, ca. ${Math.max(1, Math.ceil(s.distance_m / WALK_M_PER_MIN))} Min. zu Fuß`
                    : ''}
                  {`, ${accessibilityText(s.accessibility)}`}
                </span>
              </label>
            ))}
          </div>
          {notice ? <p role="alert">{notice}</p> : null}
          <button
            type="submit"
            disabled={selected === null || busy}
            aria-busy={busy ? 'true' : undefined}
          >
            Abholpunkt übernehmen
          </button>
        </form>
      )}
      {stops.length === 0 && notice ? <p role="alert">{notice}</p> : null}
      {tileSource && showMap ? (
        <p className="tk-pickup-attribution">{tileSource.attribution}</p>
      ) : null}
    </section>
  );
}
