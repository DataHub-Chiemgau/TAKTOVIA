import { ApiError, useApi, useSession } from '@taktovia/api-client';
import type { OdometerConflict, OdometerReading } from '@taktovia/api-client';
import { DataTable, ResourceView, StatusBadge } from '@taktovia/design-system';
import type { ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';

const SOURCE_LABEL: Record<OdometerReading['source'], string> = {
  DRIVER_CHECK: 'Fahrercheck',
  FUEL_RECEIPT: 'Tankbeleg',
  WORKSHOP: 'Werkstatt',
  TELEMATICS: 'Telematik',
};
const KIND_LABEL: Record<OdometerConflict['kind'], string> = {
  DECREASING: 'Rückläufiger Stand',
  SOURCE_MISMATCH: 'Widersprüchliche Quellen im selben Zeitraum',
};
const when = (iso: string): string => new Date(iso).toLocaleString('de-DE');
const km = (n: number): string => `${n.toLocaleString('de-DE')} km`;

type View = { readings: OdometerReading[]; conflicts: OdometerConflict[] };

/** Herkunft eines Standes in einer Zeile: Quelle, Bezug, Messzeit und Erfassungszeit. */
function Origin({ r }: { r: OdometerReading }) {
  return (
    <>
      {SOURCE_LABEL[r.source]} (Bezug {r.source_ref}), gemessen {when(r.measured_at)}, erfasst{' '}
      {when(r.recorded_at)}
    </>
  );
}

/**
 * UI Fahrzeugdetail, Kilometerstände (WP-M4-41, FCO-003): Verlauf mit Quelle je Eintrag, Konfliktliste mit Gegenüberstellung der Quellen und Auflösungsdialog. Die Begründung ist Pflicht (mindestens
 * 3 Zeichen); gewählt wird nie automatisch. Ohne das Recht `odometer:resolve` nur Lesen. Zustände: laden, Fehler mit Wiederholung, Leerzustand ohne Stände, Rechtezustand. Die Anzeige ist keine
 * Rechtequelle; der Server prüft jede Anfrage.
 */
export function Kilometerstand({ vehicleId }: { vehicleId: string }) {
  const api = useApi();
  const session = useSession();
  const canResolve =
    session.status === 'ready' && (session.me.scopes ?? []).includes('odometer:resolve');
  const [state, setState] = useState<ResourceState<View>>({ status: 'loading' });
  const [tick, setTick] = useState(0);
  const [resolving, setResolving] = useState<string | undefined>(undefined);
  const [decision, setDecision] = useState<'CHOSEN' | 'DISCARDED'>('CHOSEN');
  const [chosen, setChosen] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | undefined>();

  const reload = useCallback(() => {
    setState({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api.call('listOdometerReadings', { path: { vehicle_id: vehicleId }, query: { limit: 100 } }),
      api.call('listOdometerConflicts', { path: { vehicle_id: vehicleId }, query: { limit: 100 } }),
    ])
      .then(([r, c]) => {
        if (!cancelled)
          setState({ status: 'ready', data: { readings: r.items, conflicts: c.items } });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setState({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    return () => {
      cancelled = true;
    };
  }, [api, vehicleId, tick]);

  const open = (c: OdometerConflict) => {
    setResolving(c.id);
    setDecision('CHOSEN');
    setChosen(c.earlier.id);
    setReason('');
    setNotice(undefined);
  };

  const reasonOk = reason.trim().length >= 3;

  const submit = (c: OdometerConflict) => {
    setBusy(true);
    setNotice(undefined);
    api
      .call('resolveOdometerConflict', {
        path: { vehicle_id: vehicleId, conflict_id: c.id },
        body: {
          decision,
          reason: reason.trim(),
          ...(decision === 'CHOSEN' ? { chosen_reading_id: chosen } : {}),
        },
      })
      .then(() => {
        setResolving(undefined);
        setNotice({ tone: 'ok', text: 'Der Konflikt ist aufgelöst und protokolliert.' });
        reload();
      })
      .catch((e: unknown) => {
        const status = e instanceof ApiError ? e.status : 0;
        setNotice({
          tone: 'error',
          text:
            status === 409
              ? 'Der Konflikt wurde bereits aufgelöst (409).'
              : status === 403
                ? 'Dafür fehlt das Recht (403).'
                : status === 422
                  ? 'Die Auflösung wurde abgewiesen (422): Grund mit mindestens 3 Zeichen und ein Stand des Konflikts.'
                  : 'Die Auflösung ist fehlgeschlagen.',
        });
      })
      .finally(() => setBusy(false));
  };

  return (
    <section aria-labelledby="km-titel">
      <h2 id="km-titel">Kilometerstände</h2>
      {notice ? (
        <p role={notice.tone === 'error' ? 'alert' : 'status'}>
          <StatusBadge tone={notice.tone === 'error' ? 'error' : 'ok'}>
            {notice.tone === 'error' ? 'Fehler' : 'Erledigt'}
          </StatusBadge>{' '}
          {notice.text}
        </p>
      ) : null}
      <ResourceView
        state={state}
        isEmpty={(d) => d.readings.length === 0 && d.conflicts.length === 0}
        empty={{
          title: 'Für dieses Fahrzeug liegen noch keine Kilometerstände vor',
          hint: 'Stände kommen aus Fahrercheck, Tankbeleg, Werkstatt und Telematik und erscheinen hier mit ihrer Herkunft.',
        }}
        errorAction={
          <button type="button" onClick={reload}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <>
            <h3>Konflikte</h3>
            {data.conflicts.length === 0 ? (
              <p>Keine Konflikte.</p>
            ) : (
              <ul aria-label="Kilometerstandskonflikte">
                {data.conflicts.map((c) => (
                  <li key={c.id}>
                    <StatusBadge tone={c.status === 'OPEN' ? 'warning' : 'ok'}>
                      {c.status === 'OPEN' ? 'offen' : 'aufgelöst'}
                    </StatusBadge>{' '}
                    <strong>{KIND_LABEL[c.kind]}</strong> (Toleranz {c.tolerance_km} km)
                    <dl aria-label="Gegenüberstellung">
                      <dt>Früher gemessen</dt>
                      <dd>
                        {km(c.earlier.reading_km)}: <Origin r={c.earlier} />
                      </dd>
                      <dt>Später gemessen</dt>
                      <dd>
                        {km(c.later.reading_km)}: <Origin r={c.later} />
                      </dd>
                    </dl>
                    {c.resolution ? (
                      <p>
                        Aufgelöst von {c.resolution.resolved_by} am {when(c.resolution.resolved_at)}
                        :{' '}
                        {c.resolution.decision === 'CHOSEN'
                          ? `Stand ${km(c.resolution.chosen_reading_id === c.earlier.id ? c.earlier.reading_km : c.later.reading_km)} gewählt`
                          : 'beide Stände verworfen'}
                        . Grund: {c.resolution.reason}
                      </p>
                    ) : canResolve ? (
                      resolving === c.id ? (
                        <form
                          aria-label="Konflikt auflösen"
                          onSubmit={(e) => {
                            e.preventDefault();
                            if (reasonOk) submit(c);
                          }}
                        >
                          <fieldset>
                            <legend>Entscheidung</legend>
                            <label>
                              <input
                                type="radio"
                                name={`entscheidung-${c.id}`}
                                checked={decision === 'CHOSEN' && chosen === c.earlier.id}
                                onChange={() => {
                                  setDecision('CHOSEN');
                                  setChosen(c.earlier.id);
                                }}
                              />{' '}
                              Früheren Stand ({km(c.earlier.reading_km)}) übernehmen
                            </label>{' '}
                            <label>
                              <input
                                type="radio"
                                name={`entscheidung-${c.id}`}
                                checked={decision === 'CHOSEN' && chosen === c.later.id}
                                onChange={() => {
                                  setDecision('CHOSEN');
                                  setChosen(c.later.id);
                                }}
                              />{' '}
                              Späteren Stand ({km(c.later.reading_km)}) übernehmen
                            </label>{' '}
                            <label>
                              <input
                                type="radio"
                                name={`entscheidung-${c.id}`}
                                checked={decision === 'DISCARDED'}
                                onChange={() => setDecision('DISCARDED')}
                              />{' '}
                              Beide Stände verwerfen
                            </label>
                          </fieldset>
                          <label htmlFor={`grund-${c.id}`}>Grund (Pflicht)</label>
                          <input
                            id={`grund-${c.id}`}
                            type="text"
                            value={reason}
                            maxLength={1000}
                            onChange={(e) => setReason(e.target.value)}
                          />
                          <button type="submit" disabled={!reasonOk || busy}>
                            Auflösung speichern
                          </button>{' '}
                          <button type="button" onClick={() => setResolving(undefined)}>
                            Abbrechen
                          </button>
                        </form>
                      ) : (
                        <button type="button" onClick={() => open(c)}>
                          Konflikt auflösen
                        </button>
                      )
                    ) : (
                      <p>Ohne das Recht zur Auflösung ist der Konflikt nur lesbar.</p>
                    )}
                  </li>
                ))}
              </ul>
            )}
            <DataTable
              caption="Kilometerstandsverlauf"
              rows={data.readings}
              rowKey={(r) => r.id}
              columns={[
                { key: 'km', header: 'Stand', render: (r) => km(r.reading_km) },
                { key: 'quelle', header: 'Quelle', render: (r) => SOURCE_LABEL[r.source] },
                { key: 'bezug', header: 'Bezug', render: (r) => r.source_ref },
                { key: 'gemessen', header: 'Gemessen', render: (r) => when(r.measured_at) },
                { key: 'erfasst', header: 'Erfasst', render: (r) => when(r.recorded_at) },
              ]}
            />
          </>
        )}
      </ResourceView>
    </section>
  );
}
