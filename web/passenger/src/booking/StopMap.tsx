import { useMemo } from 'react';
import type { GeoServiceAreaPassenger, GeoStopPassenger } from '@taktovia/api-client';
import type { TileSource } from './kartenquelle';

const W = 640;
const H = 360;
const PAD = 32;
const TILE = 256;
const MAX_ZOOM = 17;

/** Web-Mercator-Pixel der Welt bei Zoomstufe `z` (Kachelraster 256 px). */
export function project(lat: number, lon: number, z: number): { x: number; y: number } {
  const scale = TILE * 2 ** z;
  const s = Math.sin((Math.min(85.0511, Math.max(-85.0511, lat)) * Math.PI) / 180);
  return {
    x: ((lon + 180) / 360) * scale,
    y: (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * scale,
  };
}

interface Props {
  areas: GeoServiceAreaPassenger[];
  stops: GeoStopPassenger[];
  origin: { latitude: number; longitude: number } | null;
  tileSource: TileSource | null;
  selectedId: string | null;
  onPick: (stopId: string) => void;
  onTileError: () => void;
}

/**
 * Karte als SVG: Kacheln (optional, aus der konfigurierbaren Quelle), wirksames Bediengebiet als Fläche, Haltepunkte als Marker. Die Karte ist eine **Ergänzung**: für Hilfstechnik
 * ist sie ein beschriftetes Bild, die Marker sind ausgeblendet und nicht fokussierbar, die gleichwertige Liste ist der Zugang per Tastatur und Screenreader. Fällt eine Kachel
 * aus (`onError`), meldet die Karte das nach oben und die Seite blendet sie samt Hinweis aus.
 */
export function StopMap({
  areas,
  stops,
  origin,
  tileSource,
  selectedId,
  onPick,
  onTileError,
}: Props) {
  const view = useMemo(() => {
    const lats: number[] = [];
    const lons: number[] = [];
    for (const a of areas) {
      lats.push(a.bounds.min_latitude, a.bounds.max_latitude);
      lons.push(a.bounds.min_longitude, a.bounds.max_longitude);
    }
    for (const s of stops) {
      lats.push(s.point.latitude);
      lons.push(s.point.longitude);
    }
    if (origin) {
      lats.push(origin.latitude);
      lons.push(origin.longitude);
    }
    if (lats.length === 0) return null;
    const [minLat, maxLat, minLon, maxLon] = [
      Math.min(...lats),
      Math.max(...lats),
      Math.min(...lons),
      Math.max(...lons),
    ];
    let z = MAX_ZOOM;
    for (; z > 0; z--) {
      const a = project(maxLat, minLon, z);
      const b = project(minLat, maxLon, z);
      if (b.x - a.x <= W - 2 * PAD && b.y - a.y <= H - 2 * PAD) break;
    }
    const c = project((minLat + maxLat) / 2, (minLon + maxLon) / 2, z);
    const originX = c.x - W / 2;
    const originY = c.y - H / 2;
    const toXY = (lat: number, lon: number) => {
      const p = project(lat, lon, z);
      return { x: p.x - originX, y: p.y - originY };
    };
    const tiles: { x: number; y: number; tx: number; ty: number }[] = [];
    const n = 2 ** z;
    for (let tx = Math.floor(originX / TILE); tx <= Math.floor((originX + W) / TILE); tx++)
      for (let ty = Math.floor(originY / TILE); ty <= Math.floor((originY + H) / TILE); ty++)
        if (ty >= 0 && ty < n)
          tiles.push({
            x: tx * TILE - originX,
            y: ty * TILE - originY,
            tx: ((tx % n) + n) % n,
            ty,
          });
    return { z, toXY, tiles };
  }, [areas, stops, origin]);

  if (!view) return null;
  const pathOf = (area: GeoServiceAreaPassenger) =>
    area.area.coordinates
      .map((polygon) =>
        polygon
          .map(
            (ring) =>
              ring
                .map(
                  ([lon, lat], i) =>
                    `${i === 0 ? 'M' : 'L'}${view.toXY(lat ?? 0, lon ?? 0).x.toFixed(1)} ${view.toXY(lat ?? 0, lon ?? 0).y.toFixed(1)}`,
                )
                .join(' ') + ' Z',
          )
          .join(' '),
      )
      .join(' ');

  return (
    <svg
      data-testid="stop-map"
      role="img"
      aria-label={`Karte des Bediengebiets mit ${stops.length} Haltepunkten. Dieselben Haltepunkte stehen in der Liste darunter.`}
      viewBox={`0 0 ${W} ${H}`}
      className="tk-stopmap"
      style={{
        width: '100%',
        maxWidth: W,
        height: 'auto',
        background: 'var(--tk-map-bg, #eef1f4)',
      }}
    >
      {tileSource
        ? view.tiles.map((t) => (
            <image
              key={`${t.tx}/${t.ty}`}
              data-tile=""
              href={tileSource.tileUrl(view.z, t.tx, t.ty)}
              x={t.x}
              y={t.y}
              width={TILE}
              height={TILE}
              onError={onTileError}
            />
          ))
        : null}
      {areas.map((a) => (
        <path
          key={a.id}
          data-testid="service-area-shape"
          d={pathOf(a)}
          fill="rgba(0,102,204,0.15)"
          stroke="#0a5cad"
          strokeWidth={2}
          fillRule="evenodd"
        />
      ))}
      {origin ? (
        <rect
          data-testid="origin-marker"
          aria-hidden="true"
          x={view.toXY(origin.latitude, origin.longitude).x - 5}
          y={view.toXY(origin.latitude, origin.longitude).y - 5}
          width={10}
          height={10}
          fill="#222"
        />
      ) : null}
      <g aria-hidden="true">
        {stops.map((s) => {
          const p = view.toXY(s.point.latitude, s.point.longitude);
          return (
            <circle
              key={s.stop_id}
              data-stop-id={s.stop_id}
              cx={p.x}
              cy={p.y}
              r={selectedId === s.stop_id ? 10 : 7}
              fill={selectedId === s.stop_id ? '#b3261e' : '#0a5cad'}
              stroke="#fff"
              strokeWidth={2}
              style={{ cursor: 'pointer' }}
              onClick={() => onPick(s.stop_id)}
            />
          );
        })}
      </g>
    </svg>
  );
}
