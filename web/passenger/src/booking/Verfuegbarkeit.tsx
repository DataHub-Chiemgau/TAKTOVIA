import { useEffect, useState } from 'react';
import { useApi } from '@taktovia/api-client';
import type { ServiceAvailability } from '@taktovia/api-client';
import { ortszeit } from './Zeitanzeige';
import { tx } from '../texte';

/** Verfügbarkeit nach Arbeitsannahme OP-32: Betriebsstatus des Dienstes (in Betrieb / außer Betrieb mit nächster Betriebszeit), keine freie Kapazität. */
export function Verfuegbarkeit({
  serviceId,
  onZone,
}: {
  serviceId: string;
  /** Zeitzone des Dienstes, sobald bekannt. */
  onZone?: (zone: string) => void;
}) {
  const api = useApi();
  const [loaded, setLoaded] = useState<{
    forId: string;
    result: { kind: 'ready'; data: ServiceAvailability } | { kind: 'error' };
  } | null>(null);
  const state = loaded?.forId === serviceId ? loaded.result : ({ kind: 'loading' } as const);
  useEffect(() => {
    let alive = true;
    api
      .call('getServiceAvailability', { path: { service_id: serviceId }, query: {} })
      .then((data) => {
        if (!alive) return;
        setLoaded({ forId: serviceId, result: { kind: 'ready', data } });
        onZone?.(data.timezone);
      })
      .catch(() => {
        if (alive) setLoaded({ forId: serviceId, result: { kind: 'error' } });
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, serviceId]);
  return (
    <section aria-labelledby="verfuegbarkeit-h" data-testid="verfuegbarkeit">
      <h2 id="verfuegbarkeit-h">{tx('start.verfuegbarkeit')}</h2>
      {state.kind === 'loading' ? <p aria-busy="true">…</p> : null}
      {state.kind === 'error' ? <p role="status">{tx('start.verfuegbarkeitFehler')}</p> : null}
      {state.kind === 'ready' ? (
        state.data.in_operation ? (
          <p role="status" data-in-operation="true">
            {tx('start.inBetrieb')}
          </p>
        ) : (
          <p role="status" data-in-operation="false">
            {tx('start.ausserBetrieb')}{' '}
            {state.data.next_opening ? (
              <>
                {tx('start.naechsteBetriebszeit')}{' '}
                <time dateTime={state.data.next_opening}>
                  {ortszeit(state.data.next_opening, state.data.timezone)}
                </time>
              </>
            ) : (
              tx('start.keineBetriebszeit')
            )}
          </p>
        )
      ) : null}
    </section>
  );
}
