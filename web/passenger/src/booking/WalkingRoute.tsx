import type { RideBooking, RideWalkRoute } from '@taktovia/api-client';
import { ortszeit } from './Zeitanzeige';
import { tx } from '../texte';

/** Karte des Fußwegs als SVG-Linienzug (ohne Kartenkacheln, die Quelle der Kartendaten ist offen, D-09): der Weg liegt maßstabsgetreu im Ausschnitt, Start und Treffpunkt sind markiert. */
function WegKarte({ walk, name }: { walk: RideWalkRoute; name: string }) {
  const pts = walk.geometry;
  const W = 320;
  const H = 200;
  const pad = 16;
  const lats = pts.map((p) => p.latitude);
  const lons = pts.map((p) => p.longitude);
  const minLat = Math.min(...lats);
  const maxLat = Math.max(...lats);
  const minLon = Math.min(...lons);
  const maxLon = Math.max(...lons);
  // Länge wird mit cos(Breite) gestaucht, damit der Weg nicht verzerrt wird
  const k = Math.cos(((minLat + maxLat) / 2) * (Math.PI / 180));
  const spanX = Math.max((maxLon - minLon) * k, 1e-9);
  const spanY = Math.max(maxLat - minLat, 1e-9);
  const scale = Math.min((W - 2 * pad) / spanX, (H - 2 * pad) / spanY);
  const x = (lon: number) => pad + (lon - minLon) * k * scale;
  const y = (lat: number) => H - pad - (lat - minLat) * scale;
  const d = pts.map((p) => `${x(p.longitude).toFixed(1)},${y(p.latitude).toFixed(1)}`);
  const first = pts[0];
  const last = pts[pts.length - 1];
  return (
    <svg
      role="img"
      viewBox={`0 0 ${W} ${H}`}
      width="100%"
      style={{ maxWidth: W, border: '1px solid currentColor' }}
      aria-label={`${tx('fuss.karte')}: ${walk.distance_m} m, etwa ${Math.max(1, Math.ceil(walk.duration_s / 60))} ${tx('fuss.minuten')}, ${walk.steps.length - 1} Wegabschnitte zum Treffpunkt ${name}. ${tx('fuss.kartenHinweis')}`}
      data-testid="fussweg-karte"
      data-snapshot={walk.snapshot_id}
      data-distance={walk.distance_m}
      data-duration={walk.duration_s}
      data-points={pts.length}
    >
      <polyline points={d.join(' ')} fill="none" stroke="currentColor" strokeWidth="3" />
      {first ? (
        <circle cx={x(first.longitude)} cy={y(first.latitude)} r="5" aria-hidden="true" />
      ) : null}
      {last ? (
        <rect
          x={x(last.longitude) - 5}
          y={y(last.latitude) - 5}
          width="10"
          height="10"
          aria-hidden="true"
        />
      ) : null}
    </svg>
  );
}

/**
 * Fußweg zum Treffpunkt in UI-04 (WP-M2-44, LH-6.3-PAX-007): Karte mit Weg **und** gleichwertige nummerierte Textanleitung aus demselben Snapshot (`snapshot_id`); die Textanleitung ist ohne
 * Karte vollständig verständlich und für Screenreader in Wegreihenfolge lesbar (geordnete Liste). Beim Rückfall (kein Routing) steht nur Name und Luftlinie, klar als Rückfall gekennzeichnet.
 */
export function WalkingRoute({ booking, zone }: { booking: RideBooking; zone: string }) {
  const walk = booking.walk;
  const name = walk?.pickup_stop_name ?? booking.pickup_stop?.name ?? '';
  return (
    <section aria-labelledby="fuss-h" data-testid="fussweg" data-snapshot={walk?.snapshot_id}>
      <h3 id="fuss-h">{tx('fuss.titel')}</h3>
      {!walk ? (
        <p data-testid="fussweg-keiner">
          {tx('fuss.nichtVerfuegbar')}
          {name ? ` ${tx('fuss.treffpunkt')}: ${name}.` : ''}
        </p>
      ) : walk.status === 'NO_WALK' ? (
        <p data-testid="fussweg-nicht-noetig">{tx('fuss.keinWeg')}</p>
      ) : (
        <>
          <p data-testid="fussweg-summe">
            {walk.status === 'FALLBACK' ? (
              <strong data-testid="fussweg-rueckfall-marke">{tx('fuss.rueckfall')}: </strong>
            ) : null}
            {tx('fuss.zusammenfassung')} {Math.max(1, Math.ceil(walk.duration_s / 60))}{' '}
            {tx('fuss.minuten')} ({walk.distance_m} m
            {walk.status === 'FALLBACK' ? ', Luftlinie' : ''})
          </p>
          {walk.status === 'FALLBACK' ? (
            <p role="note" data-testid="fussweg-rueckfall">
              {tx('fuss.rueckfallHinweis')}
            </p>
          ) : (
            <WegKarte walk={walk} name={name} />
          )}
          <h4 id="fuss-anleitung">{tx('fuss.anleitung')}</h4>
          <ol
            aria-labelledby="fuss-anleitung"
            data-testid="fussweg-anleitung"
            data-snapshot={walk.snapshot_id}
            data-steps={walk.steps.length}
          >
            {walk.steps.map((s) => (
              <li key={s.seq} data-maneuver={s.maneuver}>
                {s.instruction}
              </li>
            ))}
          </ol>
          <p>
            {tx('fuss.stand')}: {ortszeit(walk.computed_at, zone)}
            {walk.map_version ? ` (Kartenstand ${walk.map_version})` : ''}
          </p>
        </>
      )}
    </section>
  );
}
