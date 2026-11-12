import type { ApiClient, GeoStopPassenger, RideOffer } from '@taktovia/api-client';

/** Angebot mit den Haltepunkten, die zur Anzeige gelesen wurden (Name, Lage, Barrierefreiheit). */
export interface Shown {
  offer: RideOffer;
  pickup: GeoStopPassenger | null;
  dropoff: GeoStopPassenger | null;
}

/** Liest Angebot und Haltepunkte; ein nicht lesbarer Haltepunkt ist `null` (die Anzeige weist dann nur den Namen nicht aus). */
export async function leseAngebot(api: ApiClient, id: string): Promise<Shown> {
  const offer = await api.call('getRideOffer', { path: { ride_offer_id: id } });
  const stop = (s: { stop_id: string }) =>
    api.call('getStop', { path: { stop_id: s.stop_id } }).then(
      (r) => ('stop_id' in r && 'accessibility' in r ? (r as GeoStopPassenger) : null),
      () => null,
    );
  const [pickup, dropoff] = await Promise.all([stop(offer.pickup_stop), stop(offer.dropoff_stop)]);
  return { offer, pickup, dropoff };
}

/** Was der Fahrgast bei Hold und Bestätigung bindet: Preis, Komponenten, Fenster, Haltepunkte (ohne Revision und Hold). */
export function bindungsStand(o: RideOffer): string {
  return JSON.stringify([
    o.gross,
    o.fare_components,
    o.pickup_window,
    o.arrival_window,
    o.pickup_stop,
    o.dropoff_stop,
  ]);
}
