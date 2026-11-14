import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type { VehiclePlan, VehiclePlanStop } from '@taktovia/api-client';
import { ResourceView } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useEffect, useRef, useState } from 'react';
import type { LiveMessage } from '../live/LiveChannel';
import { RunReplanDialog } from './RunReplanDialog';

const clock = (iso: string): string => new Date(iso).toLocaleTimeString('de-DE');

/** Grundlage der Prognosezeit in der Sprache der Leitstelle: Planzeit aus dem Routing ist keine Prognose des ETA-Grundmodells (OP-20). */
const BASIS: Readonly<Record<string, string>> = {
  PLAN_TIME: 'Planzeit aus dem Routing',
  REFERENCE_FORECAST: 'Referenzprognose',
  LIVE_POSITION: 'Prognose aus Live-Position',
};

const FALLBACK: Readonly<Record<string, string>> = {
  POSITION_STALE: 'Positionsdaten veraltet',
  POSITION_MISSING: 'keine Position bekannt',
  NO_APPROVED_MODEL: 'kein freigegebenes Prognosemodell',
  ROUTING_UNAVAILABLE: 'Routing nicht verfügbar',
  ROUTING_TIMEOUT: 'Routing antwortet nicht rechtzeitig',
  SPEED_LIMIT_DATA_UNAVAILABLE: 'Straßendaten ohne Geschwindigkeitsattribute',
  SPEED_LIMIT_MISSING: 'Geschwindigkeitsbegrenzung fehlt in den Routendaten',
};

/** Prognosezeit eines Stopps: nie erfunden; ohne Prognose steht „keine Prognose“. */
export function forecastText(s: VehiclePlanStop): string {
  if (!s.forecast) return 'keine Prognose';
  const f = s.forecast;
  const range =
    f.earliest === f.latest ? clock(f.earliest) : `${clock(f.earliest)}–${clock(f.latest)}`;
  return `${range} (${BASIS[f.basis] ?? f.basis})`;
}

/** Zusagefenster eines Stopps: Abholfenster am Einstieg, Ankunftsfenster am Ausstieg; verbindlich und von Soll- und Prognosezeit getrennt. */
export function windowsText(s: VehiclePlanStop): string {
  if (s.promise_windows.length === 0) return '–';
  return s.promise_windows
    .map(
      (w) =>
        `${w.kind === 'PICKUP' ? 'Abholung' : 'Ankunft'} ${clock(w.earliest)}–${clock(w.latest)}`,
    )
    .join('; ');
}

/** Ein Plan nach dem Umschalten oder Laden: Revision, Datenstand, Stopps mit getrennten Zeitspalten, Besetzung, Klärfälle. */
function PlanBody({ plan, loadedAt }: { plan: VehiclePlan; loadedAt: string }) {
  return (
    <>
      <p>
        Planrevision {plan.plan_revision} · Umlauf {plan.run_state} · Datenstand {clock(plan.as_of)}{' '}
        (abgerufen {loadedAt})
      </p>
      <p>
        Prognosequelle:{' '}
        {plan.forecast_source === 'ETA_MODEL' ? 'ETA-Grundmodell' : 'Planzeit aus dem Routing'}
        {plan.forecast_data_stand ? `, Positionsstand ${clock(plan.forecast_data_stand)}` : ''}
        {plan.forecast_fallback_reason
          ? `, Rückfall: ${FALLBACK[plan.forecast_fallback_reason] ?? plan.forecast_fallback_reason}`
          : ''}
      </p>
      <p aria-label="Besetzung">
        Besetzung: Sitze {plan.occupancy.seats}
        {plan.capacity ? ` von ${plan.capacity.seats}` : ''}, Rollstuhlplätze{' '}
        {plan.occupancy.wheelchair}
        {plan.capacity ? ` von ${plan.capacity.wheelchair}` : ''}
      </p>
      <table className="tk-table">
        <caption>Stopps der aktuellen Planrevision</caption>
        <thead>
          <tr>
            <th scope="col">Nr.</th>
            <th scope="col">Haltepunkt</th>
            <th scope="col">Sollzeit</th>
            <th scope="col">Prognose</th>
            <th scope="col">Zusagefenster</th>
            <th scope="col">Einstieg / Ausstieg</th>
          </tr>
        </thead>
        <tbody>
          {plan.stops.map((s) => (
            <tr key={s.route_stop_id}>
              <td>{s.sequence}</td>
              <th scope="row">{s.stop_name}</th>
              <td>{clock(s.scheduled_at)}</td>
              <td data-forecast={s.forecast ? s.forecast.basis : 'NONE'}>{forecastText(s)}</td>
              <td>{windowsText(s)}</td>
              <td>
                {s.boarding_booking_ids.length} / {s.alighting_booking_ids.length}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {plan.review_required.length > 0 ? (
        <section aria-label="Klärfälle">
          <h4>Offene Klärfälle (nicht in der Besetzung)</h4>
          <ul>
            {plan.review_required.map((r) => (
              <li key={r.event_id}>
                {r.event_type} · {r.reason_code} · {clock(r.occurred_at)}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}

/**
 * UI-07 Detailpanel Fahrzeugplan (WP-M3-38, LH-6.7-OPS-003): Stoppreihenfolge, Sollzeit, Prognose, Zusagefenster und aktuelle Besetzung des **gewählten** Fahrzeugs mit Planrevision und
 * Datenstand. Beim Umschalten wird die Anzeige sofort geleert (keine Restanzeige des vorherigen Fahrzeugs); eine verspätete Antwort des vorigen Fahrzeugs wird verworfen, ebenso eine
 * Live-Nachricht, die nicht das gewählte Fahrzeug (oder seinen Umlauf) betrifft. Eine Live-Nachricht stößt nur den Neuabruf an: der Zustand kommt immer vom Server.
 */
export function VehiclePlanPanel({
  vehicleId,
  vehicleLabel,
  liveMessage,
}: {
  vehicleId: string;
  vehicleLabel?: string;
  liveMessage?: LiveMessage | undefined;
}) {
  const api = useApi();
  const session = useSession();
  const mayReplan = session.status === 'ready' && hasRight(session.me, 'dispatch.replan');
  const [replanning, setReplanning] = useState<VehiclePlan | undefined>(undefined);
  // Der Zustand trägt die Fahrzeug-ID, für die er gilt: gehört er nicht zum gewählten Fahrzeug, wird er nie gezeigt
  const [state, setState] = useState<{
    vehicleId: string;
    loadedAt?: string;
    value: ResourceState<VehiclePlan>;
  }>({ vehicleId, value: { status: 'loading' } });
  const planRef = useRef<VehiclePlan | undefined>(undefined);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .call('getOperatorVehicle', { path: { vehicle_id: vehicleId } })
      .then((v) => {
        // verspätete Antwort eines früher gewählten Fahrzeugs: verwerfen
        if (cancelled) return;
        const plan = v.plan ?? undefined;
        if (!plan || plan.vehicle_id !== vehicleId) {
          planRef.current = undefined;
          setState({ vehicleId, value: { status: 'error', error: { status: 0 } } });
          return;
        }
        planRef.current = plan;
        setState({
          vehicleId,
          loadedAt: new Date().toLocaleTimeString('de-DE'),
          value: { status: 'ready', data: plan },
        });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setState({
          vehicleId,
          value: { status: 'error', error: e instanceof ApiError ? e : { status: 0 } },
        });
      });
    return () => {
      cancelled = true;
    };
  }, [api, vehicleId, tick]);

  // Live-Nachricht: nur für das gewählte Fahrzeug bzw. seinen Umlauf, nie für ein anderes
  useEffect(() => {
    if (!liveMessage) return;
    const run = planRef.current?.run_id;
    if (liveMessage.aggregate_id === vehicleId || (run && liveMessage.aggregate_id === run))
      setTick((n) => n + 1);
  }, [liveMessage, vehicleId]);

  const shown = state.vehicleId === vehicleId ? state.value : ({ status: 'loading' } as const);
  const label = vehicleLabel ?? vehicleId.slice(0, 8);
  return (
    <section aria-label={`Fahrzeugplan ${label}`} data-vehicle-id={vehicleId}>
      <h3>Fahrzeugplan {label}</h3>
      <ResourceView
        state={shown}
        isEmpty={(d) => d.run_id === null}
        empty={{
          title: 'Fahrzeug ohne Fahrt',
          hint: 'Für dieses Fahrzeug gibt es keinen laufenden oder geplanten Umlauf.',
        }}
        errorAction={
          <button type="button" onClick={() => setTick((n) => n + 1)}>
            Erneut versuchen
          </button>
        }
      >
        {(plan) => (
          <>
            <PlanBody plan={plan} loadedAt={state.loadedAt ?? ''} />
            {mayReplan && replanning?.vehicle_id !== vehicleId ? (
              <p>
                <button type="button" onClick={() => setReplanning(plan)}>
                  Umplanen
                </button>
              </p>
            ) : null}
            {replanning && replanning.vehicle_id === vehicleId ? (
              <RunReplanDialog
                plan={replanning}
                currentRevision={plan.plan_revision}
                onClose={() => setReplanning(undefined)}
                onChanged={() => setTick((n) => n + 1)}
              />
            ) : null}
          </>
        )}
      </ResourceView>
    </section>
  );
}
