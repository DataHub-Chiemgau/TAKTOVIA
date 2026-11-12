import { useEffect, useState } from 'react';
import { useApi } from '@taktovia/api-client';
import type { Favorite, FavoriteInput, GeoStopPassenger } from '@taktovia/api-client';
import { Field } from '@taktovia/design-system';
import type { ProblemLike } from '@taktovia/design-system';

/** Bearbeitungsstand eines Favoriten (UI-05): Koordinaten als Text, damit unfertige Eingaben nicht verloren gehen. */
export interface FavoriteDraft {
  label: string;
  lat: string;
  lon: string;
  stopId: string | null;
  /** Vom Server geliefert: der Haltepunkt ist heute buchbar. Neue Zeilen: `false`, solange kein Haltepunkt gewählt ist. */
  stopBookable: boolean;
}

export const MAX_FAVORITES = 20;

export const emptyFavorite = (): FavoriteDraft => ({
  label: '',
  lat: '',
  lon: '',
  stopId: null,
  stopBookable: false,
});

export function favoritesToDraft(list: readonly Favorite[]): FavoriteDraft[] {
  return list.map((f) => ({
    label: f.label,
    lat: String(f.lat),
    lon: String(f.lon),
    stopId: f.stop_id,
    stopBookable: f.stop_bookable,
  }));
}

/** Zahl im Wertebereich oder `undefined` (nie 0/0 wird erst vom Paar entschieden). */
function coordinate(text: string, min: number, max: number): number | undefined {
  const t = text.trim().replace(',', '.');
  if (t === '' || !/^-?\d+(\.\d+)?$/.test(t)) return undefined;
  const n = Number(t);
  return n >= min && n <= max ? n : undefined;
}

/**
 * Prüfung vor dem Senden: Bezeichnung vorhanden, Koordinate im Wertebereich und nicht 0/0 (der Server prüft dasselbe verbindlich und ist maßgeblich).
 * Liefert `undefined`, wenn alles gültig ist, sonst ein Problem mit Feldzeigern für das Formular.
 */
export function validateFavorites(list: readonly FavoriteDraft[]): ProblemLike | undefined {
  const violations: { pointer: string; code: string }[] = [];
  list.forEach((f, i) => {
    if (f.label.trim() === '')
      violations.push({ pointer: `/favorites/${i}/label`, code: 'REQUIRED' });
    const lat = coordinate(f.lat, -90, 90);
    const lon = coordinate(f.lon, -180, 180);
    if (lat === undefined)
      violations.push({ pointer: `/favorites/${i}/lat`, code: 'INVALID_VALUE' });
    if (lon === undefined)
      violations.push({ pointer: `/favorites/${i}/lon`, code: 'INVALID_VALUE' });
    if (lat === 0 && lon === 0)
      violations.push({ pointer: `/favorites/${i}/lat`, code: 'INVALID_VALUE' });
  });
  return violations.length > 0 ? { status: 422, code: 'VALIDATION_FAILED', violations } : undefined;
}

/** Anfrageform: Koordinate immer mitgeben (bestätigter Ort), Haltepunkt nur wenn gewählt. */
export function favoritesToInput(list: readonly FavoriteDraft[]): FavoriteInput[] {
  return list.map((f) => ({
    label: f.label.trim(),
    lat: coordinate(f.lat, -90, 90) as number,
    lon: coordinate(f.lon, -180, 180) as number,
    ...(f.stopId ? { stop_id: f.stopId } : {}),
  }));
}

/** Haltestellenliste des Fahrgasts (nur aktive, heute wirksame Haltepunkte, GEO-002). Ein Ausfall lässt die manuelle Ortsangabe unberührt. */
function useStops(): readonly GeoStopPassenger[] {
  const api = useApi();
  const [stops, setStops] = useState<readonly GeoStopPassenger[]>([]);
  useEffect(() => {
    let live = true;
    api.call('listStops', { query: { limit: 100 } }).then(
      (page) => live && setStops(page.items as GeoStopPassenger[]),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api]);
  return stops;
}

/**
 * Favoriten in UI-05 (WP-M2-36): Name, bestätigter Ort (Koordinate, optional ein Haltepunkt aus der Haltestellenliste), Entfernen. Zustände: leer („noch keine
 * Favoriten“), Haltepunkt inzwischen inaktiv (der Ort bleibt nutzbar, wird aber nicht als Haltestelle vorgeschlagen). Die Feldfehler zeigt das umgebende Formular.
 */
export function FavoritenEditor({
  value,
  onChange,
}: {
  value: readonly FavoriteDraft[];
  onChange: (next: FavoriteDraft[]) => void;
}) {
  const stops = useStops();
  const patch = (i: number, p: Partial<FavoriteDraft>) =>
    onChange(value.map((f, j) => (j === i ? { ...f, ...p } : f)));
  const chooseStop = (i: number, id: string) => {
    if (id === '') return patch(i, { stopId: null, stopBookable: false });
    const s = stops.find((x) => x.stop_id === id);
    if (!s) return;
    patch(i, {
      stopId: id,
      stopBookable: true,
      lat: String(s.point.latitude),
      lon: String(s.point.longitude),
      label: value[i]?.label.trim() === '' ? s.name : (value[i]?.label ?? s.name),
    });
  };
  return (
    <fieldset>
      <legend>Favoriten</legend>
      {value.length === 0 ? <p role="status">Noch keine Favoriten.</p> : null}
      {value.map((f, i) => (
        <div key={i} role="group" aria-label={`Favorit ${i + 1}`}>
          <Field
            pointer={`/favorites/${i}/label`}
            label={`Bezeichnung Favorit ${i + 1}`}
            value={f.label}
            maxLength={80}
            onChange={(e) => patch(i, { label: e.target.value })}
          />
          <Field
            pointer={`/favorites/${i}/lat`}
            label={`Breitengrad Favorit ${i + 1}`}
            inputMode="decimal"
            value={f.lat}
            onChange={(e) => patch(i, { lat: e.target.value })}
          />
          <Field
            pointer={`/favorites/${i}/lon`}
            label={`Längengrad Favorit ${i + 1}`}
            inputMode="decimal"
            value={f.lon}
            onChange={(e) => patch(i, { lon: e.target.value })}
          />
          <label>
            {`Haltestelle Favorit ${i + 1}`}
            <select value={f.stopId ?? ''} onChange={(e) => chooseStop(i, e.target.value)}>
              <option value="">Keine Haltestelle</option>
              {f.stopId && !stops.some((s) => s.stop_id === f.stopId) ? (
                <option value={f.stopId}>Haltestelle (nicht mehr aktiv)</option>
              ) : null}
              {stops.map((s) => (
                <option key={s.stop_id} value={s.stop_id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          {f.stopId && !f.stopBookable ? (
            <p role="status">
              {`Die Haltestelle von Favorit ${i + 1} ist nicht mehr aktiv. Der Ort bleibt nutzbar, wird aber nicht als Haltestelle vorgeschlagen.`}
            </p>
          ) : null}
          <button type="button" onClick={() => onChange(value.filter((_, j) => j !== i))}>
            {`Favorit ${i + 1} entfernen`}
          </button>
        </div>
      ))}
      <button
        type="button"
        disabled={value.length >= MAX_FAVORITES}
        onClick={() => onChange([...value, emptyFavorite()])}
      >
        Favorit hinzufügen
      </button>
    </fieldset>
  );
}
