import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type {
  CapacityConfiguration,
  CapacityPatch,
  Organization,
  VehicleEquipment,
  VehicleMaster,
} from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';
import { Kilometerstand } from '../fahrzeuge/Kilometerstand';
import { Dienstzuordnung } from './Dienstzuordnung';

/** Ausstattungsmerkmale (Platzhalterkatalog, OP-14), wie im Ressourcenvektor. */
export const EQUIPMENT_LABELS: Readonly<Record<VehicleEquipment, string>> = {
  RAMP: 'Rampe',
  LIFT: 'Hebebühne',
  CHILD_SEAT: 'Kindersitz',
  BOOSTER_SEAT: 'Sitzerhöhung',
  LUGGAGE_SPACE: 'Gepäckraum',
  ASSISTANCE_DOG_SPACE: 'Platz für Assistenzhund',
  STRETCHER_FIXING: 'Liegendbeförderung',
};
const EQUIPMENT = Object.keys(EQUIPMENT_LABELS) as VehicleEquipment[];

type Draft = {
  vehicle_label: string;
  vehicle_type: string;
  operator_organization_id: string;
  seats: string;
  wheelchair: string;
  equipment: VehicleEquipment[];
  active: boolean;
};
const EMPTY: Draft = {
  vehicle_label: '',
  vehicle_type: 'PKW',
  operator_organization_id: '',
  seats: '',
  wheelchair: '',
  equipment: [],
  active: true,
};

type CapacityDraft = {
  seat_places: string;
  wheelchair_spaces: string;
  seats_replaced_per_wheelchair: string;
  combinations: string;
  active_combination: string;
  max_total_persons: string;
};

/** Zahl aus einem Textfeld; ein ungültiger Text bleibt Text, damit der Server mit Feldverweis (422) antwortet. */
const num = (s: string): number =>
  s.trim() !== '' && /^-?\d+$/.test(s.trim()) ? Number(s) : (s as unknown as number);

const label = (v: VehicleMaster): string => v.vehicle_label ?? '(ohne Kennung)';

/**
 * UI-11 Flotte/Werkstatt: Fahrzeugstamm (WP-M3-03) und Kapazitäts- und Stellplatzkonfiguration (WP-M3-04). Zustände: Leerzustand, Fehlerzustand mit Wiederholung, Rechtezustand
 * (der Bereich ist ohne Recht nicht in der Navigation und ohne Daten), Feldfehler aus der API (422), Konflikt (409 doppelte Kennung), veraltete Revision (412). Die Tabelle der
 * Ressourcenvektoren ist die Tabellenalternative zum Editor. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage.
 */
export function Fahrzeugstamm() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Fahrzeugstamm' });
  const canWrite = session.status === 'ready' && hasRight(session.me, 'fleet.write');
  const [rows, setRows] = useState<ResourceState<VehicleMaster[]>>({ status: 'loading' });
  const [orgs, setOrgs] = useState<Organization[]>([]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [editing, setEditing] = useState<VehicleMaster | undefined>(undefined);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [capacity, setCapacity] = useState<CapacityConfiguration | undefined>(undefined);
  const [capDraft, setCapDraft] = useState<CapacityDraft | undefined>(undefined);
  const [capProblem, setCapProblem] = useState<ProblemLike | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listVehicleMasters', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    api
      .call('listOrganizations', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setOrgs(page.items);
      })
      .catch(() => {
        if (!cancelled) setOrgs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht, Fahrzeuge zu verwalten.';
    if (e.status === 404) return 'Das Fahrzeug wurde nicht gefunden.';
    if (e.status === 409)
      return 'Konflikt: Diese Kennung ist bereits vergeben, oder Kapazität und Ausstattung werden über die Kapazitätskonfiguration gepflegt.';
    if (e.status === 412) return 'Das Fahrzeug wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const body = () => ({
    vehicle_label: draft.vehicle_label.trim(),
    vehicle_type: draft.vehicle_type.trim(),
    operator_organization_id: draft.operator_organization_id,
    capacity: { seats: num(draft.seats), wheelchair: num(draft.wheelchair) },
    equipment: draft.equipment,
    active: draft.active,
  });

  const save = async () => {
    setProblem(undefined);
    setNotice(undefined);
    try {
      if (editing) {
        const updated = await api.call('updateVehicleMaster', {
          path: { vehicle_id: editing.id },
          body: body(),
          revision: editing.revision,
        });
        setNotice(
          updated.active ? 'Das Fahrzeug wurde geändert.' : 'Das Fahrzeug wurde deaktiviert.',
        );
        startEdit(updated);
      } else {
        await api.call('createVehicleMaster', { body: body() });
        setDraft(EMPTY);
        setNotice(
          'Das Fahrzeug wurde angelegt. Es ist bis zur Einsatzfreigabe nicht disponierbar.',
        );
      }
      load();
    } catch (e) {
      if (e instanceof ApiError && (e.status === 422 || e.status === 409) && e.problem)
        setProblem(e.problem);
      else setNotice(explain(e, 'Das Fahrzeug konnte nicht gespeichert werden.'));
    }
  };

  const loadCapacity = (v: VehicleMaster) => {
    setCapacity(undefined);
    setCapDraft(undefined);
    api
      .call('getVehicleCapacity', { path: { vehicle_id: v.id } })
      .then((c) => showCapacity(c))
      .catch((e: unknown) => setNotice(explain(e, 'Die Kapazitätskonfiguration fehlt.')));
  };

  const showCapacity = (c: CapacityConfiguration) => {
    setCapacity(c);
    const l = c.layout;
    setCapDraft({
      seat_places: String(l?.spaces.seat_places ?? c.effective.seats),
      wheelchair_spaces: String(l?.spaces.wheelchair_spaces ?? c.effective.wheelchair),
      seats_replaced_per_wheelchair: String(l?.spaces.seats_replaced_per_wheelchair ?? 1),
      combinations: (
        l?.combinations ?? [{ code: 'STANDARD', wheelchair_places: c.effective.wheelchair }]
      )
        .map((k) => `${k.code}:${k.wheelchair_places}`)
        .join(', '),
      active_combination: l?.active_combination ?? 'STANDARD',
      max_total_persons: String(
        l?.restrictions.find((r) => r.code === 'MAX_TOTAL_PERSONS')?.value ?? '',
      ),
    });
  };

  const startEdit = (v: VehicleMaster) => {
    setEditing(v);
    setProblem(undefined);
    setDraft({
      vehicle_label: v.vehicle_label ?? '',
      vehicle_type: v.vehicle_type ?? '',
      operator_organization_id: v.operator_organization_id ?? '',
      seats: String(v.capacity.seats),
      wheelchair: String(v.capacity.wheelchair),
      equipment: v.equipment,
      active: v.active,
    });
    loadCapacity(v);
  };

  const saveCapacity = async () => {
    if (!editing || !capDraft) return;
    setCapProblem(undefined);
    setNotice(undefined);
    const combos = capDraft.combinations
      .split(/[,;]+/)
      .map((s) => s.trim())
      .filter(Boolean)
      .map((s) => {
        const [code = '', places = ''] = s.split(':');
        return { code: code.trim(), wheelchair_places: num(places) };
      });
    const patch: CapacityPatch = {
      spaces: {
        seat_places: num(capDraft.seat_places),
        wheelchair_spaces: num(capDraft.wheelchair_spaces),
        seats_replaced_per_wheelchair: num(capDraft.seats_replaced_per_wheelchair),
      },
      combinations: combos,
      active_combination: capDraft.active_combination.trim(),
      restrictions:
        capDraft.max_total_persons.trim() === ''
          ? []
          : [{ code: 'MAX_TOTAL_PERSONS', value: num(capDraft.max_total_persons) }],
    };
    try {
      const revision = capacity?.revision ?? editing.revision;
      const c = await api.call('updateVehicleCapacity', {
        path: { vehicle_id: editing.id },
        body: patch,
        revision,
      });
      showCapacity(c);
      setNotice(
        'Die Kapazitätskonfiguration wurde gespeichert; bestehende Reservierungen bleiben unverändert.',
      );
      load();
      const fresh = await api.call('getVehicleMaster', { path: { vehicle_id: editing.id } });
      setEditing(fresh);
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setCapProblem(e.problem);
      else setNotice(explain(e, 'Die Kapazitätskonfiguration konnte nicht gespeichert werden.'));
    }
  };

  const toggleEquipment = (code: VehicleEquipment) =>
    setDraft((d) => ({
      ...d,
      equipment: d.equipment.includes(code)
        ? d.equipment.filter((c) => c !== code)
        : [...d.equipment, code],
    }));

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Fahrzeugstamm" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Fahrzeuge in diesem Mandanten',
          hint: canWrite
            ? 'Legen Sie unten das erste Fahrzeug an.'
            : 'Es wurden noch keine Fahrzeuge angelegt.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Fahrzeuge des aktiven Mandanten"
            rows={data}
            rowKey={(v) => v.id}
            columns={[
              { key: 'label', header: 'Kennung', render: (v) => label(v) },
              { key: 'type', header: 'Typ', render: (v) => v.vehicle_type ?? '' },
              {
                key: 'cap',
                header: 'Sitze / Rollstuhlplätze',
                render: (v) => `${v.capacity.seats} / ${v.capacity.wheelchair}`,
              },
              {
                key: 'status',
                header: 'Status',
                render: (v) =>
                  !v.active
                    ? 'deaktiviert'
                    : v.dispatchable
                      ? 'disponierbar'
                      : 'gesperrt (keine Einsatzfreigabe)',
              },
              {
                key: 'act',
                header: 'Aktion',
                render: (v) => (
                  <button type="button" onClick={() => startEdit(v)}>
                    {canWrite ? 'Bearbeiten' : 'Ansehen'} {label(v)}
                  </button>
                ),
              },
            ]}
          />
        )}
      </ResourceView>

      {canWrite ? (
        <section aria-labelledby="stamm-titel">
          <h2 id="stamm-titel">
            {editing ? `Fahrzeug ${label(editing)} ändern` : 'Fahrzeug anlegen'}
          </h2>
          {editing ? (
            <button
              type="button"
              onClick={() => {
                setEditing(undefined);
                setDraft(EMPTY);
                setCapacity(undefined);
                setCapDraft(undefined);
              }}
            >
              Neues Fahrzeug anlegen
            </button>
          ) : null}
          <Form onSubmit={save} submitLabel={editing ? 'Speichern' : 'Anlegen'} problem={problem}>
            <Field
              pointer="/vehicle_label"
              label="Kennung"
              value={draft.vehicle_label}
              onChange={(e) => setDraft({ ...draft, vehicle_label: e.target.value })}
            />
            <Field
              pointer="/vehicle_type"
              label="Fahrzeugtyp (Code)"
              value={draft.vehicle_type}
              onChange={(e) => setDraft({ ...draft, vehicle_type: e.target.value })}
            />
            <div className="tk-field">
              <label htmlFor="operator-org">Betreiber</label>
              <select
                id="operator-org"
                value={draft.operator_organization_id}
                onChange={(e) => setDraft({ ...draft, operator_organization_id: e.target.value })}
              >
                <option value="">Bitte wählen</option>
                {orgs.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </div>
            <Field
              pointer="/capacity/seats"
              label="Sitzplätze"
              inputMode="numeric"
              value={draft.seats}
              onChange={(e) => setDraft({ ...draft, seats: e.target.value })}
            />
            <Field
              pointer="/capacity/wheelchair"
              label="Rollstuhlplätze"
              inputMode="numeric"
              value={draft.wheelchair}
              onChange={(e) => setDraft({ ...draft, wheelchair: e.target.value })}
            />
            <fieldset>
              <legend>Ausstattung</legend>
              {EQUIPMENT.map((code) => (
                <label key={code} style={{ display: 'block' }}>
                  <input
                    type="checkbox"
                    checked={draft.equipment.includes(code)}
                    onChange={() => toggleEquipment(code)}
                  />{' '}
                  {EQUIPMENT_LABELS[code]}
                </label>
              ))}
            </fieldset>
            <label style={{ display: 'block' }}>
              <input
                type="checkbox"
                checked={draft.active}
                onChange={(e) => setDraft({ ...draft, active: e.target.checked })}
              />{' '}
              Aktiv (ein deaktiviertes Fahrzeug wird nicht neu disponiert)
            </label>
          </Form>
        </section>
      ) : null}

      {editing && capacity && capDraft ? (
        <section aria-labelledby="kap-titel">
          <h2 id="kap-titel">Kapazitäts- und Stellplatzkonfiguration {label(editing)}</h2>
          <DataTable
            caption="Verfügbarer Ressourcenvektor je Konfiguration (Tabellenalternative)"
            rows={capacity.vectors}
            rowKey={(v) => v.code}
            columns={[
              { key: 'code', header: 'Konfiguration', render: (v) => v.code },
              { key: 'seats', header: 'Sitzplätze', render: (v) => String(v.seats) },
              { key: 'wc', header: 'Rollstuhlplätze', render: (v) => String(v.wheelchair) },
              {
                key: 'active',
                header: 'Wirksam',
                render: (v) => (v.code === capacity.effective.code && capacity.layout ? 'ja' : ''),
              },
            ]}
          />
          {canWrite ? (
            <Form
              onSubmit={saveCapacity}
              submitLabel="Konfiguration speichern"
              problem={capProblem}
            >
              <Field
                pointer="/spaces/seat_places"
                label="Sitzplätze insgesamt"
                inputMode="numeric"
                value={capDraft.seat_places}
                onChange={(e) => setCapDraft({ ...capDraft, seat_places: e.target.value })}
              />
              <Field
                pointer="/spaces/wheelchair_spaces"
                label="Barrierefreie Stellplätze"
                inputMode="numeric"
                value={capDraft.wheelchair_spaces}
                onChange={(e) => setCapDraft({ ...capDraft, wheelchair_spaces: e.target.value })}
              />
              <Field
                pointer="/spaces/seats_replaced_per_wheelchair"
                label="Ersetzte Sitzplätze je Rollstuhlplatz (n)"
                inputMode="numeric"
                value={capDraft.seats_replaced_per_wheelchair}
                onChange={(e) =>
                  setCapDraft({ ...capDraft, seats_replaced_per_wheelchair: e.target.value })
                }
              />
              <Field
                pointer="/combinations"
                label="Kombinationen (CODE:Rollstuhlplätze, getrennt durch Komma)"
                value={capDraft.combinations}
                onChange={(e) => setCapDraft({ ...capDraft, combinations: e.target.value })}
              />
              <Field
                pointer="/active_combination"
                label="Aktive Kombination"
                value={capDraft.active_combination}
                onChange={(e) => setCapDraft({ ...capDraft, active_combination: e.target.value })}
              />
              <Field
                pointer="/restrictions"
                label="Restriktion: höchstens Personen (leer = keine)"
                inputMode="numeric"
                value={capDraft.max_total_persons}
                onChange={(e) => setCapDraft({ ...capDraft, max_total_persons: e.target.value })}
              />
            </Form>
          ) : null}
        </section>
      ) : null}
      {editing ? <Dienstzuordnung api={api} vehicle={editing} canWrite={canWrite} /> : null}
      {editing &&
      session.status === 'ready' &&
      (session.me.scopes ?? []).includes('odometer:read') ? (
        <Kilometerstand vehicleId={editing.id} />
      ) : null}
    </ScreenFrame>
  );
}
