import type { Point } from '@taktovia/api-client';

/** Durchschnittliche Gehgeschwindigkeit für die Gehzeitangabe (4,8 km/h); reine Anzeige, keine Zusage (wie PickupSelection). */
export const WALK_M_PER_MIN = 80;

/** Luftlinie in Metern (Haversine), aufgerundet auf ganze Meter. */
export function luftlinieMeter(a: Point, b: Point): number {
  const rad = (d: number) => (d * Math.PI) / 180;
  const dLat = rad(b.latitude - a.latitude);
  const dLon = rad(b.longitude - a.longitude);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(a.latitude)) * Math.cos(rad(b.latitude)) * Math.sin(dLon / 2) ** 2;
  return Math.ceil(2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h))));
}

export interface Fussweg {
  meter: number;
  minuten: number;
}

/**
 * Fußweg als Luftlinien-Näherung zwischen gewähltem Ort und Haltepunkt. Das Angebot (`RideOffer`) trägt keine Fußwegangabe aus dem Routing
 * (WP-M2-10 liefert nur Fahrzeiten); die Oberfläche kennzeichnet den Wert deshalb als „Luftlinie, ca.“.
 */
export function fussweg(from: Point, to: Point): Fussweg {
  const meter = luftlinieMeter(from, to);
  return { meter, minuten: Math.max(1, Math.ceil(meter / WALK_M_PER_MIN)) };
}
