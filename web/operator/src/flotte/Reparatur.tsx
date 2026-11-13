import { ApiError, useApi } from '@taktovia/api-client';
import type { Defect, RepairOrder, VehicleMaster } from '@taktovia/api-client';
import { useCallback, useEffect, useState } from 'react';

const STATUS: Readonly<Record<RepairOrder['status'], string>> = {
  OPEN: 'angelegt',
  IN_PROGRESS: 'in Arbeit',
  PROOF_RECORDED: 'Abschluss gemeldet',
  CANCELED: 'storniert',
};

/**
 * UI-11 Reparaturauftrag und Wiederfreigabe (WP-M3-51): Auftrag zur Akte anlegen (Werkstatt, Akte in Bearbeitung), fortschreiben, Reparaturnachweis mit Kostenbezug melden, technische Freigabe erteilen
 * (Vieraugenpflicht: erste Freigabe Werkstatt, zweite eine andere Person). Rechtezustände sind prüfbar: ohne `repair.write` keine Pflegeaktionen, ohne `fleet.release` der Hinweis „Freigaberolle fehlt“.
 * Der Nachweis allein hebt die Sperre nicht auf; die Fahrzeugsperre fällt erst mit der abgeschlossenen Freigabe, die Einsatzfreigabe erst nach einer neuen Prüfung. „Frist überschritten“ setzt Fristen
 * voraus, die erst WP-M7c-33 liefert (OP-12). Der Server prüft Rolle, Bedingungen und Revisionen jeder Anfrage erneut.
 */
export function Reparatur({
  defect,
  vehicle,
  canWrite,
  canRelease,
  onChanged,
}: {
  defect: Defect;
  vehicle: VehicleMaster | undefined;
  canWrite: boolean;
  canRelease: boolean;
  onChanged: () => void;
}) {
  const api = useApi();
  const [orders, setOrders] = useState<RepairOrder[] | undefined>(undefined);
  const [message, setMessage] = useState<string | undefined>(undefined);
  const [workshop, setWorkshop] = useState('');
  const [cost, setCost] = useState('');
  const [proof, setProof] = useState('');
  const [cancelReason, setCancelReason] = useState('');
  const [inspection, setInspection] = useState('');
  const [releaseReason, setReleaseReason] = useState('');
  const [second, setSecond] = useState('');
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listRepairOrders', { query: { defect_id: defect.id } })
      .then((p) => {
        if (!cancelled) setOrders(p.items);
      })
      .catch(() => {
        if (!cancelled) setOrders([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api, defect.id, tick]);

  const refresh = useCallback(() => {
    setTick((n) => n + 1);
    onChanged();
  }, [onChanged]);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht für diese Aktion.';
    if (e.status === 404) return 'Der Vorgang wurde nicht gefunden.';
    if (e.status === 409)
      return 'Das ist im aktuellen Stand nicht zulässig; das Fahrzeug bleibt gesperrt.';
    if (e.status === 412)
      return 'Der Stand hat sich geändert (z. B. neue Sperre). Bitte neu laden.';
    if (e.status === 422) return 'Bitte die Pflichtangaben prüfen.';
    return fallback;
  };

  const active = orders?.find((o) => o.status !== 'CANCELED');

  const create = async () => {
    setMessage(undefined);
    try {
      await api.call('createRepairOrder', {
        body: {
          defect_id: defect.id,
          ...(workshop.trim() ? { workshop_ref: workshop.trim() } : {}),
          ...(cost.trim() ? { cost_reference: cost.trim() } : {}),
        },
      });
      setMessage('Der Reparaturauftrag wurde angelegt; die Akte ist in Reparatur.');
      refresh();
    } catch (e) {
      setMessage(explain(e, 'Der Reparaturauftrag konnte nicht angelegt werden.'));
    }
  };

  const move = async (
    o: RepairOrder,
    body: { to: 'IN_PROGRESS' | 'PROOF_RECORDED' | 'CANCELED'; [k: string]: unknown },
    ok: string,
  ) => {
    setMessage(undefined);
    try {
      await api.call('transitionRepairOrder', {
        path: { repair_order_id: o.id },
        body: body as never,
        revision: o.revision,
      });
      setMessage(ok);
      refresh();
    } catch (e) {
      setMessage(explain(e, 'Der Übergang konnte nicht ausgeführt werden.'));
    }
  };

  const release = async (o: RepairOrder) => {
    setMessage(undefined);
    if (!vehicle) return;
    try {
      const d = await api.call('createVehicleRelease', {
        path: { vehicle_id: o.vehicle_id },
        body: {
          repair_order_id: o.id,
          follow_up_inspection_id: inspection.trim(),
          reason: releaseReason.trim(),
          ...(second.trim() ? { second_approver_ref: second.trim() } : {}),
        },
        revision: vehicle.safety_revision,
      });
      setMessage(
        d.decision === 'RELEASED'
          ? 'Die technische Freigabe ist abgeschlossen; die Sperre dieser Akte ist aufgehoben. Die Einsatzfreigabe folgt mit einer neuen Prüfung.'
          : 'Die erste Freigabe ist erfasst. Eine zweite, andere Person muss bestätigen; das Fahrzeug bleibt gesperrt.',
      );
      refresh();
    } catch (e) {
      setMessage(explain(e, 'Die Freigabe konnte nicht erteilt werden.'));
    }
  };

  return (
    <section aria-labelledby="reparatur-titel">
      <h3 id="reparatur-titel">Reparatur und Wiederfreigabe</h3>
      {message ? <p role="status">{message}</p> : null}
      {orders === undefined ? <p>Lade Reparaturaufträge …</p> : null}
      {orders && orders.length === 0 ? (
        <p>Zu dieser Akte besteht noch kein Reparaturauftrag.</p>
      ) : null}
      {orders && orders.length > 0 ? (
        <ul aria-label="Reparaturaufträge der Akte">
          {orders.map((o) => (
            <li key={o.id}>
              Reparaturauftrag {STATUS[o.status]}
              {o.workshop_ref ? `, Werkstatt ${o.workshop_ref}` : ''}
              {o.cost_reference ? `, Kostenbezug ${o.cost_reference}` : ''}
              {o.proof_evidence_id ? ', Nachweis vorhanden' : ''}
            </li>
          ))}
        </ul>
      ) : null}

      {canWrite && defect.status === 'IN_PROGRESS' && !active ? (
        <div role="group" aria-label="Reparaturauftrag anlegen">
          <div className="tk-field">
            <label htmlFor="rep-werkstatt">Werkstatt (optional)</label>
            <input
              id="rep-werkstatt"
              value={workshop}
              onChange={(e) => setWorkshop(e.target.value)}
              maxLength={200}
            />
          </div>
          <div className="tk-field">
            <label htmlFor="rep-kosten">Kostenbezug (Kostenstelle, optional)</label>
            <input
              id="rep-kosten"
              value={cost}
              onChange={(e) => setCost(e.target.value)}
              maxLength={64}
            />
          </div>
          <button type="button" onClick={() => void create()}>
            Reparaturauftrag anlegen
          </button>
        </div>
      ) : null}

      {canWrite && active && active.status === 'OPEN' ? (
        <div role="group" aria-label="Auftrag übernehmen">
          <button
            type="button"
            onClick={() => void move(active, { to: 'IN_PROGRESS' }, 'Der Auftrag ist in Arbeit.')}
          >
            Auftrag in Arbeit nehmen
          </button>
        </div>
      ) : null}

      {canWrite && active && (active.status === 'OPEN' || active.status === 'IN_PROGRESS') ? (
        <div role="group" aria-label="Stornieren">
          <div className="tk-field">
            <label htmlFor="rep-storno">Begründung der Stornierung</label>
            <input
              id="rep-storno"
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
              maxLength={500}
            />
          </div>
          <button
            type="button"
            onClick={() =>
              void move(
                active,
                { to: 'CANCELED', reason: cancelReason.trim() },
                'Der Auftrag wurde storniert.',
              )
            }
          >
            Auftrag stornieren
          </button>
        </div>
      ) : null}

      {canWrite && active && active.status === 'IN_PROGRESS' ? (
        <div role="group" aria-label="Reparatur abschließen melden">
          <div className="tk-field">
            <label htmlFor="rep-nachweis">Nachweis (Foto der Akte)</label>
            <select id="rep-nachweis" value={proof} onChange={(e) => setProof(e.target.value)}>
              <option value="">Bitte wählen</option>
              {defect.evidence_ids.map((id) => (
                <option key={id} value={id}>
                  Foto {id.slice(0, 8)}
                </option>
              ))}
            </select>
          </div>
          <div className="tk-field">
            <label htmlFor="rep-kosten2">Kostenbezug (Kostenstelle)</label>
            <input
              id="rep-kosten2"
              value={cost}
              onChange={(e) => setCost(e.target.value)}
              maxLength={64}
            />
          </div>
          <button
            type="button"
            onClick={() =>
              void move(
                active,
                {
                  to: 'PROOF_RECORDED',
                  proof_evidence_id: proof,
                  ...(cost.trim() ? { cost_reference: cost.trim() } : {}),
                },
                'Der Reparaturabschluss ist gemeldet. Das Fahrzeug bleibt bis zur technischen Freigabe gesperrt.',
              )
            }
          >
            Reparatur abgeschlossen melden
          </button>
        </div>
      ) : null}

      {active && active.status === 'PROOF_RECORDED' ? (
        canRelease ? (
          <div role="group" aria-label="Technische Freigabe">
            <p>Die technische Freigabe verlangt zwei verschiedene Personen (Vieraugenpflicht).</p>
            <div className="tk-field">
              <label htmlFor="frei-pruefung">Folgeprüfung (Kennung)</label>
              <input
                id="frei-pruefung"
                value={inspection}
                onChange={(e) => setInspection(e.target.value)}
              />
            </div>
            <div className="tk-field">
              <label htmlFor="frei-grund">Begründung der Freigabe</label>
              <input
                id="frei-grund"
                value={releaseReason}
                onChange={(e) => setReleaseReason(e.target.value)}
                maxLength={500}
              />
            </div>
            <div className="tk-field">
              <label htmlFor="frei-zweite">Zweite Person (Kennung, optional)</label>
              <input id="frei-zweite" value={second} onChange={(e) => setSecond(e.target.value)} />
            </div>
            <button type="button" disabled={!vehicle} onClick={() => void release(active)}>
              Freigabe erteilen
            </button>
          </div>
        ) : (
          <p role="status">
            Freigaberolle fehlt: Die technische Freigabe erteilen nur Werkstatt und Betriebsleitung.
          </p>
        )
      ) : null}
    </section>
  );
}
