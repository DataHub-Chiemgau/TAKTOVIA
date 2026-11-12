import { useEffect, useId, useRef, useState } from 'react';
import { ApiError, useApi } from '@taktovia/api-client';
import type { PlaceHit, PlaceNearestStop, RidePlace } from '@taktovia/api-client';
import { tx } from '../texte';
import type { TextKey } from '../texte';

/** Gewählter, bestätigter Ort (WP-M2-38): validierte Koordinate, optional ein heute buchbarer Haltepunkt. */
export interface PlaceChoice {
  type: PlaceHit['type'];
  label: string;
  latitude: number;
  longitude: number;
  stopId: string | null;
  /** Wahr, wenn der Ort laut Dienst außerhalb des Bediengebiets liegt: nicht buchbar. */
  outsideServiceArea: boolean;
  nearestStop: PlaceNearestStop | null;
}

export function fromHit(h: PlaceHit): PlaceChoice {
  return {
    type: h.type,
    label: h.label,
    latitude: h.point.latitude,
    longitude: h.point.longitude,
    stopId: h.stop_id,
    outsideServiceArea: h.service_area === 'OUTSIDE',
    nearestStop: h.nearest_stop,
  };
}

/** Form von `origin`/`destination` in `POST /v1/ride-offers` (Schema `RidePlace`). */
export function toRidePlace(c: PlaceChoice): RidePlace {
  return {
    point: { latitude: c.latitude, longitude: c.longitude },
    label: c.label.slice(0, 200),
    ...(c.stopId ? { stop_id: c.stopId } : {}),
  };
}

const TYPE_KEY: Record<PlaceHit['type'], TextKey> = {
  ADDRESS: 'ort.typ.adresse',
  POI: 'ort.typ.poi',
  STOP: 'ort.typ.haltestelle',
  FAVORITE: 'ort.typ.favorit',
  MAP_POINT: 'ort.typ.kartenpunkt',
};

const MIN_CHARS = 2;
const DEBOUNCE_MS = 250;

/** Koordinate aus Text: Komma oder Punkt, im Wertebereich. */
function parseCoordinate(text: string, limit: number): number | undefined {
  const t = text.trim().replace(',', '.');
  if (!/^-?\d+(\.\d+)?$/.test(t)) return undefined;
  const n = Number(t);
  return Math.abs(n) <= limit ? n : undefined;
}

interface Outcome {
  hits: readonly PlaceHit[];
  message: string | undefined;
}

/**
 * Ortssuche mit fünf Eingabearten (WP-M2-38, LH-6.3-PAX-002): Textsuche (Adresse, POI, Haltestelle, Favorit mit gekennzeichnetem Typ), Favoritenauswahl und
 * „Punkt auf Karte wählen“ neben der Texteingabe. Ein gewählter Treffer wird als bestätigter Treffpunkt angezeigt. Alle Texte werden als Text dargestellt, nie als Markup.
 * Suchtexte werden nicht gespeichert (kein Speicher, kein Verlauf) und gehen nur an `GET /v1/places`. Fällt die Adresssuche aus, bleiben Haltestellen, Favoriten und
 * Kartenpunkt nutzbar. `bias` ist die Suchkoordinate der aktuellen Anfrage (z. B. bestätigter Gerätestandort, WP-M2-39).
 */
export function Ortswahl({
  label,
  serviceId,
  bias,
  value,
  onChange,
}: {
  label: string;
  serviceId?: string | undefined;
  bias?: { latitude: number; longitude: number } | null | undefined;
  value: PlaceChoice | null;
  onChange: (choice: PlaceChoice | null) => void;
}) {
  const api = useApi();
  const uid = useId();
  const listId = `${uid}-list`;
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const [textResult, setTextResult] = useState<Outcome | null>(null);
  const [mapResult, setMapResult] = useState<Outcome | null>(null);
  const [favorites, setFavorites] = useState<readonly PlaceHit[]>([]);
  const [formMessage, setFormMessage] = useState<string | undefined>(undefined);
  const [active, setActive] = useState(-1);
  const [mapOpen, setMapOpen] = useState(false);
  const [mapLat, setMapLat] = useState('');
  const [mapLon, setMapLon] = useState('');
  const sequence = useRef(0);

  // Favoriten des Fahrgasts für die leere Eingabe; ein Fehler (z. B. Konto ohne Fahrgastprofil) lässt sie einfach weg.
  useEffect(() => {
    let live = true;
    api.call('getMyProfile', {}).then(
      (p) => {
        if (!live) return;
        setFavorites(
          p.favorites.map((f) => ({
            type: 'FAVORITE',
            label: f.label,
            point: { latitude: f.lat, longitude: f.lon },
            stop_id: f.stop_bookable ? f.stop_id : null,
            source: 'favorite',
            data_as_of: p.created_at,
            service_area: 'UNKNOWN',
            nearest_stop: null,
          })),
        );
      },
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api]);

  /** Ruft die Ortssuche; verworfen wird eine Antwort, wenn inzwischen eine neuere Eingabe gestellt wurde. */
  const search = async (query: Record<string, string | number>): Promise<Outcome | undefined> => {
    const mine = ++sequence.current;
    try {
      const result = await api.call('searchPlaces', {
        query: { ...query, ...(serviceId ? { service_id: serviceId } : {}), limit: 8 },
      });
      if (mine !== sequence.current) return undefined;
      const notes: string[] = [];
      if (result.notices.includes('GEOCODER_UNAVAILABLE')) notes.push(tx('ort.geocoderAus'));
      if (result.items.length === 0) notes.push(tx('ort.keinTreffer'));
      return { hits: result.items, message: notes.join(' ') || undefined };
    } catch (e) {
      if (mine !== sequence.current) return undefined;
      return {
        hits: [],
        message:
          e instanceof ApiError && e.status === 429
            ? tx('ort.zuVieleAnfragen')
            : e instanceof ApiError && e.status === 422
              ? tx('ort.zuKurz')
              : tx('ort.fehler'),
      };
    }
  };

  const trimmed = text.trim();
  const mode = trimmed === '' ? 'empty' : trimmed.length < MIN_CHARS ? 'short' : 'search';

  // Entprellte Textsuche; jede neue Eingabe verwirft ältere Antworten. Kurzer oder leerer Text sucht nicht (keine Anfrage).
  useEffect(() => {
    sequence.current++;
    if (mode !== 'search') return;
    const timer = setTimeout(
      () =>
        void search({
          q: trimmed,
          ...(bias ? { bias_latitude: bias.latitude, bias_longitude: bias.longitude } : {}),
        }).then((o) => o && setTextResult(o)),
      DEBOUNCE_MS,
    );
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `search` ist bei gleichem Text/Dienst/Standort gleichwertig
  }, [mode, trimmed, serviceId, bias?.latitude, bias?.longitude]);

  // Sichtbare Vorschläge: Kartenpunkt-Ergebnis (nur bei leerer Eingabe), Favoriten (leere Eingabe), Textsuche.
  const showingMap = mode === 'empty' && mapResult !== null;
  const showingFavorites = mode === 'empty' && !showingMap;
  const hits: readonly PlaceHit[] = showingMap
    ? (mapResult?.hits ?? [])
    : showingFavorites
      ? favorites
      : mode === 'search'
        ? (textResult?.hits ?? [])
        : [];
  const message =
    formMessage ??
    (mode === 'short'
      ? tx('ort.zuKurz')
      : showingMap
        ? mapResult?.message
        : mode === 'search'
          ? textResult?.message
          : undefined);

  const choose = (h: PlaceHit) => {
    onChange(fromHit(h));
    setText('');
    setOpen(false);
    setTextResult(null);
    setMapResult(null);
    setFormMessage(undefined);
  };

  const checkMapPoint = () => {
    const lat = parseCoordinate(mapLat, 90);
    const lon = parseCoordinate(mapLon, 180);
    if (lat === undefined || lon === undefined || (lat === 0 && lon === 0)) {
      setFormMessage(tx('ort.karteUngueltig'));
      return;
    }
    setFormMessage(undefined);
    setText('');
    setOpen(true);
    setActive(-1);
    void search({ map_latitude: lat, map_longitude: lon }).then((o) => o && setMapResult(o));
  };

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((i) => Math.min(hits.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter' && open && active >= 0 && hits[active]) {
      e.preventDefault();
      choose(hits[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  const typeLabel = (h: PlaceHit) => tx(TYPE_KEY[h.type]);

  return (
    <div className="tk-ortswahl" role="group" aria-label={label}>
      {value ? (
        <div role="status" data-testid="treffpunkt">
          <p>
            <strong>{`${label}: ${value.label}`}</strong>
            {` (${tx(TYPE_KEY[value.type])})`}
          </p>
          {value.outsideServiceArea ? (
            <p role="alert">{`${tx('ort.ausserhalb')}: ${tx('ort.ausserhalbHinweis')}`}</p>
          ) : (
            <p>{tx('ort.bestaetigt')}</p>
          )}
          {value.nearestStop && value.type !== 'STOP' ? (
            <p>{`${tx('ort.nachsteHaltestelle')}: ${value.nearestStop.name} (${value.nearestStop.distance_m} m)`}</p>
          ) : null}
          <button type="button" onClick={() => onChange(null)}>
            {`${label} ${tx('ort.aendern')}`}
          </button>
        </div>
      ) : (
        <>
          <label htmlFor={`${uid}-input`}>{label}</label>
          <input
            id={`${uid}-input`}
            role="combobox"
            type="text"
            autoComplete="off"
            aria-expanded={open && hits.length > 0}
            aria-controls={listId}
            aria-autocomplete="list"
            aria-describedby={`${uid}-hint`}
            aria-activedescendant={active >= 0 ? `${uid}-opt-${active}` : undefined}
            maxLength={200}
            value={text}
            onChange={(e) => {
              setText(e.target.value);
              setMapResult(null);
              setFormMessage(undefined);
              setActive(-1);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={onKeyDown}
          />
          <p id={`${uid}-hint`}>{tx('ort.suche.hint')}</p>
          <ul
            id={listId}
            role="listbox"
            aria-label={`${label}: Vorschläge`}
            hidden={!open || hits.length === 0}
          >
            {showingFavorites && hits.length > 0 ? (
              <li role="presentation">{tx('ort.favoriten')}</li>
            ) : null}
            {hits.map((h, i) => (
              <li
                key={`${h.type}-${h.label}-${h.point.latitude}-${h.point.longitude}-${i}`}
                id={`${uid}-opt-${i}`}
                role="option"
                aria-selected={i === active}
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => choose(h)}
              >
                <span>{h.label}</span>
                {' – '}
                <span>{typeLabel(h)}</span>
                {h.service_area === 'OUTSIDE' ? <span>{` – ${tx('ort.ausserhalb')}`}</span> : null}
              </li>
            ))}
          </ul>
          <button type="button" aria-expanded={mapOpen} onClick={() => setMapOpen((v) => !v)}>
            {tx('ort.karte')}
          </button>
          {mapOpen ? (
            <div role="group" aria-label={tx('ort.karte')}>
              <p>{tx('ort.karteHinweis')}</p>
              <label>
                {`${label}: Breite`}
                <input
                  inputMode="decimal"
                  value={mapLat}
                  onChange={(e) => setMapLat(e.target.value)}
                />
              </label>
              <label>
                {`${label}: Länge`}
                <input
                  inputMode="decimal"
                  value={mapLon}
                  onChange={(e) => setMapLon(e.target.value)}
                />
              </label>
              <button type="button" onClick={checkMapPoint}>
                {tx('ort.karteUebernehmen')}
              </button>
            </div>
          ) : null}
          {message ? <p role="status">{message}</p> : null}
        </>
      )}
    </div>
  );
}
