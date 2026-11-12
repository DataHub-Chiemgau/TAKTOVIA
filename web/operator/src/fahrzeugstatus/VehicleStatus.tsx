import { ApiError } from '@taktovia/api-client';
import type { OperatorVehicle, VehicleOperationalState, useApi } from '@taktovia/api-client';
import { DataTable, ResourceView } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';

type Api = ReturnType<typeof useApi>;

/** Anzeigenamen der Fahrzeug- und Dienststatus (LH-6.6-DRV-007; Zustandsautomat `fahrzeug_dienststatus.md`). */
export const STATUS_LABELS: Readonly<Record<VehicleOperationalState, string>> = {
  NOT_READY: 'Nicht bereit',
  RELEASE_PENDING: 'Freigabe ausstehend',
  AVAILABLE: 'Verfügbar',
  EN_ROUTE: 'Anfahrt',
  WAITING: 'Wartezeit',
  BREAK: 'Pause',
  DISRUPTED: 'Störung',
  BLOCKED: 'Gesperrt',
  RESTRICTED: 'Eingeschränkt',
  ENDED: 'Ende',
};

/** Nur Verfügbarkeit, Anfahrt und Wartezeit werden neu disponiert; alle anderen Zustände erhalten keine neue Zuweisung (Originalabnahme DRV-007). */
const DISPATCHABLE: ReadonlySet<VehicleOperationalState> = new Set([
  'AVAILABLE',
  'EN_ROUTE',
  'WAITING',
]);

/** Ist das Fahrzeug in diesem Zustand für neue Zuweisungen vorgesehen? */
export const isDispatchable = (s: VehicleOperationalState): boolean => DISPATCHABLE.has(s);

/**
 * UI-07 Leitstelle, Status je Fahrzeug (WP-M3-31, LH-6.6-DRV-007): zeigt für jedes Fahrzeug den Dienststatus (Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung, Ende; dazu Nicht bereit) und ob es
 * neu disponiert wird. Der Status kommt vom Server (`GET /v1/operator/vehicles`); eine offline gemeldete Störung oder ein offline gemeldetes Ende wirkt erst ab Serverempfang, die Anzeige
 * nennt das. Zustände: laden, Fehler mit Wiederholung, Leerzustand. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage. Die Einbettung in den Livebetrieb folgt mit WP-M3-36.
 */
export function VehicleStatus({ api }: { api: Api }) {
  const [rows, setRows] = useState<ResourceState<OperatorVehicle[]>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listOperatorVehicles', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  return (
    <section aria-labelledby="fahrzeugstatus-titel">
      <h2 id="fahrzeugstatus-titel">Status je Fahrzeug</h2>
      <p>
        Fahrzeuge in Pause, Störung oder Ende erhalten keine neue Zuweisung. Eine offline gemeldete
        Störung oder ein offline gemeldetes Ende wirkt erst ab Serverempfang auf die
        Disponierbarkeit.
      </p>
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Keine Fahrzeuge',
          hint: 'Sobald Fahrzeuge angelegt sind, erscheint hier ihr Dienststatus.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Dienststatus der Fahrzeuge"
            rows={data}
            rowKey={(v) => v.vehicle_id}
            columns={[
              { key: 'vehicle', header: 'Fahrzeug', render: (v) => v.vehicle_label },
              {
                key: 'status',
                header: 'Status',
                render: (v) => (
                  <span data-status={v.operational_state}>
                    {STATUS_LABELS[v.operational_state]}
                  </span>
                ),
              },
              {
                key: 'dispatch',
                header: 'Neue Zuweisung',
                render: (v) => (isDispatchable(v.operational_state) ? 'möglich' : 'nicht möglich'),
              },
            ]}
          />
        )}
      </ResourceView>
    </section>
  );
}
