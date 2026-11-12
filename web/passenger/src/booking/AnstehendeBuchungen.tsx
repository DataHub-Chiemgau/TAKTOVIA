import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@taktovia/api-client';
import type { RideBooking } from '@taktovia/api-client';
import { Link } from 'react-router-dom';
import { fenster } from './Zeitanzeige';
import { tx } from '../texte';

const STATUS_TEXT: Partial<Record<RideBooking['state'], string>> = {
  CONFIRMING: 'Wird bestätigt',
  CONFIRMED: 'Bestätigt',
  RECONCILING: 'Wird abgeglichen',
};

/**
 * „Anstehend“ im Sinne von UI-01 ist der Bereich `bevorstehend` der Buchungsübersicht (WP-M2-45): die Einteilung trifft der Server (Filter `bereich`), die Oberfläche teilt nie selbst ein.
 * Die Seiten werden bis zu [MAX_PAGES] gelesen.
 */
const MAX_PAGES = 5;

export function AnstehendeBuchungen({ zone }: { zone: string }) {
  const api = useApi();
  const [state, setState] = useState<
    { kind: 'loading' } | { kind: 'ready'; items: RideBooking[] } | { kind: 'error' }
  >({ kind: 'loading' });
  const load = useCallback(async () => {
    const all: RideBooking[] = [];
    let cursor: string | undefined;
    for (let page = 0; page < MAX_PAGES; page += 1) {
      const r = await api.call('listBookings', {
        query: { bereich: 'bevorstehend', limit: 100, ...(cursor ? { cursor } : {}) },
      });
      all.push(...r.items);
      if (!r.next_cursor) break;
      cursor = r.next_cursor;
    }
    return all.sort(
      (a, b) => Date.parse(a.promise.pickup_earliest) - Date.parse(b.promise.pickup_earliest),
    );
  }, [api]);
  const reload = useCallback(() => {
    load().then(
      (items) => setState({ kind: 'ready', items }),
      () => setState({ kind: 'error' }),
    );
  }, [load]);
  useEffect(() => {
    reload();
    // Statuswechsel während der Anzeige: beim Zurückkehren in den Tab neu lesen
    const onVisible = () => {
      if (document.visibilityState === 'visible') reload();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [reload]);
  return (
    <section aria-labelledby="anstehend-h" data-testid="anstehende-buchungen">
      <h2 id="anstehend-h">{tx('start.anstehend')}</h2>
      {state.kind === 'loading' ? <p aria-busy="true">…</p> : null}
      {state.kind === 'error' ? <p role="alert">{tx('start.anstehendFehler')}</p> : null}
      {state.kind === 'ready' && state.items.length === 0 ? (
        <p>{tx('start.anstehendLeer')}</p>
      ) : null}
      {state.kind === 'ready' && state.items.length > 0 ? (
        <ul>
          {state.items.map((b) => (
            <li key={b.id} data-booking-id={b.id}>
              <Link to={`/reisen/${b.id}`}>{b.booking_number}</Link>:{' '}
              {STATUS_TEXT[b.state] ?? b.state}; {tx('start.abholung')}{' '}
              {fenster(
                { earliest_at: b.promise.pickup_earliest, latest_at: b.promise.pickup_latest },
                zone,
              )}
            </li>
          ))}
        </ul>
      ) : null}
      <button type="button" onClick={reload}>
        {tx('start.aktualisieren')}
      </button>
    </section>
  );
}
