import { ApiError, hasRight, useApi, useSession } from '@taktovia/api-client';
import type {
  Defect,
  DefectArea,
  DefectCategory,
  DefectEffect,
  DefectKind,
  DefectRevision,
  VehicleMaster,
} from '@taktovia/api-client';
import { DataTable, Field, Form, ResourceView, ScreenFrame } from '@taktovia/design-system';
import type { ProblemLike, ResourceState } from '@taktovia/design-system';
import { useCallback, useEffect, useState } from 'react';
import { useScreenContext } from '../ScreenContext';

type Status = Defect['status'];

/** Anzeigenamen der Kataloge (Detailvertrag `m3-schadenakte.md`, Planungsannahmen). */
export const CATEGORY_LABELS: Readonly<Record<DefectCategory, string>> = {
  BODY: 'Karosserie',
  GLASS: 'Glas',
  TIRES_WHEELS: 'Räder und Reifen',
  LIGHTS: 'Beleuchtung',
  BRAKES: 'Bremsen',
  DRIVETRAIN: 'Antrieb',
  STEERING_SUSPENSION: 'Lenkung und Fahrwerk',
  ELECTRICAL_HV: 'Elektrik und Hochvolt',
  INTERIOR: 'Innenraum',
  ACCESSIBILITY_EQUIPMENT: 'Barrierefreiheits-Ausstattung',
  SAFETY_EQUIPMENT: 'Sicherheitsausstattung',
  CLEANLINESS: 'Sauberkeit',
  OTHER: 'Sonstiges',
};
export const KIND_LABELS: Readonly<Record<DefectKind, string>> = {
  SCRATCH: 'Kratzer',
  DENT: 'Delle',
  CRACK_BREAK: 'Riss oder Bruch',
  MALFUNCTION: 'Funktionsstörung',
  MISSING_PART: 'Teil fehlt',
  LEAK: 'Undicht',
  WEAR: 'Verschleiß',
  CONTAMINATION: 'Verschmutzung',
  OTHER: 'Sonstiges',
};
export const AREA_LABELS: Readonly<Record<DefectArea, string>> = {
  FRONT: 'Front',
  REAR: 'Heck',
  LEFT_SIDE: 'Linke Seite',
  RIGHT_SIDE: 'Rechte Seite',
  ROOF: 'Dach',
  UNDERBODY: 'Unterboden',
  WHEELS_TIRES: 'Räder und Reifen',
  INTERIOR_PASSENGER: 'Fahrgastraum',
  INTERIOR_DRIVER: 'Fahrerplatz',
  LUGGAGE: 'Gepäckraum',
  WHEELCHAIR_AREA: 'Rollstuhlplatz',
  ENGINE_BATTERY: 'Antrieb und Batterie',
  OTHER: 'Sonstiges',
};
export const EFFECT_LABELS: Readonly<Record<DefectEffect, string>> = {
  NONE_VISIBLE: 'Keine sichtbare Auswirkung',
  COSMETIC_ONLY: 'Nur optisch',
  FUNCTION_LIMITED: 'Funktion eingeschränkt',
  NOT_ROADWORTHY: 'Nicht fahrbereit',
  FLUID_LEAK: 'Flüssigkeit tritt aus',
  WARNING_LIGHT: 'Warnleuchte',
  NOISE_VIBRATION: 'Geräusch oder Vibration',
  OTHER: 'Sonstiges',
};
export const STATUS_LABELS: Readonly<Record<Status, string>> = {
  NEW: 'neu',
  REVIEWED: 'geprüft',
  IN_PROGRESS: 'in Bearbeitung',
  REPAIR: 'Reparatur',
  CLOSED: 'abgeschlossen',
  DISMISSED: 'verworfen',
};

/** Folgeaktionen je Status laut Übergangstabelle (`zustaende/defect_case.md`); der Server prüft Rolle und Vorbedingung jedes Übergangs erneut. */
const ACTIONS: Readonly<
  Record<Status, readonly { to: Exclude<Status, 'NEW'>; label: string; reason?: boolean }[]>
> = {
  NEW: [
    { to: 'REVIEWED', label: 'Als geprüft markieren' },
    { to: 'DISMISSED', label: 'Verwerfen', reason: true },
  ],
  REVIEWED: [
    { to: 'IN_PROGRESS', label: 'In Bearbeitung nehmen' },
    { to: 'CLOSED', label: 'Ohne Reparatur abschließen' },
    { to: 'DISMISSED', label: 'Verwerfen', reason: true },
  ],
  IN_PROGRESS: [
    { to: 'REPAIR', label: 'In Reparatur geben' },
    { to: 'CLOSED', label: 'Ohne Reparatur abschließen' },
    { to: 'DISMISSED', label: 'Verwerfen', reason: true },
  ],
  REPAIR: [
    { to: 'CLOSED', label: 'Nach Reparatur abschließen' },
    { to: 'IN_PROGRESS', label: 'Zurück in Bearbeitung' },
  ],
  CLOSED: [{ to: 'REVIEWED', label: 'Wiedereröffnen', reason: true }],
  DISMISSED: [{ to: 'REVIEWED', label: 'Wiedereröffnen', reason: true }],
};

type Draft = {
  vehicle_id: string;
  occurred_at: string;
  category_code: string;
  kind: string;
  vehicle_area: string;
  observed_effect: string;
  description: string;
};
const EMPTY: Draft = {
  vehicle_id: '',
  occurred_at: '',
  category_code: '',
  kind: '',
  vehicle_area: '',
  observed_effect: '',
  description: '',
};

const fmt = (iso: string): string => iso.replace('T', ' ').replace(/:\d\d(\.\d+)?Z$/, ' UTC');
const text = (v: unknown): string => (v === null || v === undefined ? '–' : String(v));

const FIELD_LABELS: Readonly<Record<string, string>> = {
  occurred_at: 'Ereigniszeit',
  location_kind: 'Ortsart',
  location_value: 'Ort',
  category_code: 'Kategorie',
  kind: 'Art',
  vehicle_area: 'Fahrzeugbereich',
  observed_effect: 'Beobachtete Auswirkung',
  description: 'Beschreibung',
  source_inspection_id: 'Quellprüfung',
  source_step_code: 'Prüfschritt',
};

/**
 * UI-11 Schadenakte (WP-M3-43, -47, -48): Aktenliste, Erfassung durch Mitarbeiter, Aktenansicht mit nur zulässigen Folgeaktionen, Nachtrag mit Änderungsgrund und Versionsverlauf mit Vergleich (nur Prüfer).
 * Zustände: Leerzustand, Fehlerzustand mit Wiederholung, Rechtezustand (ohne Recht keine Formulare und kein Verlauf), Feldfehler (422), unzulässiger Übergang (409), veraltete Revision (412).
 * Fotos in Prüfung erscheinen als Zähler, nie als Bild. Die Anzeige ist keine Rechtequelle; der Server prüft jede Anfrage.
 */
export function Schadenakte() {
  const api = useApi();
  const session = useSession();
  const context = useScreenContext({ tone: 'info', label: 'Schadenakten' });
  const me = session.status === 'ready' ? session.me : undefined;
  const canWrite = me !== undefined && hasRight(me, 'defects.write');
  const canState = me !== undefined && hasRight(me, 'defects.state');
  const canHistory = me !== undefined && hasRight(me, 'defects.history');
  const [rows, setRows] = useState<ResourceState<Defect[]>>({ status: 'loading' });
  const [vehicles, setVehicles] = useState<VehicleMaster[]>([]);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [problem, setProblem] = useState<ProblemLike | undefined>(undefined);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const [selected, setSelected] = useState<Defect | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [amendReason, setAmendReason] = useState('');
  const [amendDescription, setAmendDescription] = useState('');
  const [amendProblem, setAmendProblem] = useState<ProblemLike | undefined>(undefined);
  const [history, setHistory] = useState<DefectRevision[] | undefined>(undefined);
  const [versionA, setVersionA] = useState(1);
  const [versionB, setVersionB] = useState(1);
  const [tick, setTick] = useState(0);

  const load = useCallback(() => {
    setRows({ status: 'loading' });
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .call('listDefects', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setRows({ status: 'ready', data: page.items });
      })
      .catch((e: unknown) => {
        if (!cancelled)
          setRows({ status: 'error', error: e instanceof ApiError ? e : { status: 0 } });
      });
    api
      .call('listVehicleMasters', { query: { limit: 100 } })
      .then((page) => {
        if (!cancelled) setVehicles(page.items);
      })
      .catch(() => {
        if (!cancelled) setVehicles([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api, tick]);

  const explain = (e: unknown, fallback: string): string => {
    if (!(e instanceof ApiError)) return fallback;
    if (e.status === 403) return 'Ihnen fehlt das Recht für diese Aktion.';
    if (e.status === 404) return 'Die Schadenakte wurde nicht gefunden.';
    if (e.status === 409)
      return 'Dieser Übergang ist für die Akte im aktuellen Status nicht zulässig.';
    if (e.status === 412) return 'Die Akte wurde zwischenzeitlich geändert. Bitte neu laden.';
    return fallback;
  };

  const openDefect = async (d: Defect) => {
    setNotice(undefined);
    setReason('');
    setAmendReason('');
    setAmendDescription('');
    setAmendProblem(undefined);
    setHistory(undefined);
    try {
      const fresh = await api.call('getDefect', { path: { defect_id: d.id } });
      setSelected(fresh);
      if (canHistory) {
        const page = await api.call('listDefectHistory', { path: { defect_id: d.id } });
        setHistory(page.items);
        const last = page.items.length;
        setVersionA(Math.max(1, last - 1));
        setVersionB(Math.max(1, last));
      }
    } catch (e) {
      setSelected(undefined);
      setNotice(explain(e, 'Die Schadenakte konnte nicht geladen werden.'));
    }
  };

  const create = async () => {
    setProblem(undefined);
    setNotice(undefined);
    const body: Record<string, unknown> = {
      vehicle_id: draft.vehicle_id,
      occurred_at: draft.occurred_at === '' ? '' : new Date(draft.occurred_at).toISOString(),
      category_code: draft.category_code,
      description: draft.description,
    };
    if (draft.kind) body['kind'] = draft.kind;
    if (draft.vehicle_area) body['vehicle_area'] = draft.vehicle_area;
    if (draft.observed_effect) body['observed_effect'] = draft.observed_effect;
    try {
      const created = await api.call('createDefect', { body: body as never });
      setDraft(EMPTY);
      setNotice(`Die Schadenakte ${created.case_number} wurde angelegt.`);
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setProblem(e.problem);
      else setNotice(explain(e, 'Die Schadenakte konnte nicht angelegt werden.'));
    }
  };

  const transition = async (to: Exclude<Status, 'NEW'>, needsReason: boolean | undefined) => {
    if (!selected) return;
    setNotice(undefined);
    const reopen = selected.status === 'CLOSED' || selected.status === 'DISMISSED';
    try {
      const updated = await api.call('transitionDefect', {
        path: { defect_id: selected.id },
        body: { to, ...(needsReason ? { reason } : {}), ...(reopen ? { reopen: true } : {}) },
        revision: selected.revision,
      });
      setSelected(updated);
      setReason('');
      setNotice(`Der Status ist jetzt „${STATUS_LABELS[updated.status]}“.`);
      load();
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem)
        setNotice('Bitte eine Begründung angeben.');
      else setNotice(explain(e, 'Der Übergang konnte nicht ausgeführt werden.'));
    }
  };

  const amend = async () => {
    if (!selected) return;
    setAmendProblem(undefined);
    setNotice(undefined);
    try {
      const body: Record<string, unknown> = { change_reason: amendReason };
      if (amendDescription.trim() !== '') body['description'] = amendDescription;
      const updated = await api.call('amendDefect', {
        path: { defect_id: selected.id },
        body: body as never,
      });
      setSelected(updated);
      setAmendReason('');
      setAmendDescription('');
      setNotice(`Der Nachtrag wurde als Version ${updated.content_version ?? ''} gespeichert.`);
      if (canHistory) {
        const page = await api.call('listDefectHistory', { path: { defect_id: selected.id } });
        setHistory(page.items);
        setVersionA(Math.max(1, page.items.length - 1));
        setVersionB(Math.max(1, page.items.length));
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 422 && e.problem) setAmendProblem(e.problem);
      else setNotice(explain(e, 'Der Nachtrag konnte nicht gespeichert werden.'));
    }
  };

  const vehicleLabel = (id: string): string =>
    vehicles.find((v) => v.id === id)?.vehicle_label ?? id.slice(0, 8);
  const revisionOf = (v: number): DefectRevision | undefined =>
    history?.find((r) => r.version === v);
  const content = (r: DefectRevision | undefined, key: string): string =>
    r ? text((r.content as unknown as Record<string, unknown>)[key]) : '–';

  if (!context) return null;
  return (
    <ScreenFrame title="Flotte und Werkstatt: Schadenakten" context={context}>
      {notice ? <p role="status">{notice}</p> : null}
      <ResourceView
        state={rows}
        isEmpty={(d) => d.length === 0}
        empty={{
          title: 'Noch keine Schadenakten in diesem Mandanten',
          hint: canWrite
            ? 'Erfassen Sie unten den ersten Schaden.'
            : 'Es wurden noch keine Schäden gemeldet.',
        }}
        errorAction={
          <button type="button" onClick={load}>
            Erneut versuchen
          </button>
        }
      >
        {(data) => (
          <DataTable
            caption="Schadenakten des aktiven Mandanten"
            rows={data}
            rowKey={(d) => d.id}
            columns={[
              { key: 'nr', header: 'Fallnummer', render: (d) => d.case_number },
              { key: 'veh', header: 'Fahrzeug', render: (d) => vehicleLabel(d.vehicle_id) },
              { key: 'cat', header: 'Kategorie', render: (d) => CATEGORY_LABELS[d.category_code] },
              { key: 'st', header: 'Status', render: (d) => STATUS_LABELS[d.status] },
              { key: 'at', header: 'Gemeldet', render: (d) => fmt(d.reported_at) },
              {
                key: 'act',
                header: 'Aktion',
                render: (d) => (
                  <button type="button" onClick={() => void openDefect(d)}>
                    Öffnen {d.case_number}
                  </button>
                ),
              },
            ]}
          />
        )}
      </ResourceView>

      {selected ? (
        <section aria-labelledby="akte-titel">
          <h2 id="akte-titel">Schadenakte {selected.case_number}</h2>
          <dl>
            <dt>Status</dt>
            <dd>{STATUS_LABELS[selected.status]}</dd>
            <dt>Fahrzeug</dt>
            <dd>{vehicleLabel(selected.vehicle_id)}</dd>
            <dt>Kategorie</dt>
            <dd>{CATEGORY_LABELS[selected.category_code]}</dd>
            <dt>Art, Bereich, Auswirkung</dt>
            <dd>
              {[
                selected.kind ? KIND_LABELS[selected.kind] : '–',
                selected.vehicle_area ? AREA_LABELS[selected.vehicle_area] : '–',
                selected.observed_effect ? EFFECT_LABELS[selected.observed_effect] : '–',
              ].join(', ')}
            </dd>
            <dt>Beschreibung</dt>
            <dd>{text(selected.description)}</dd>
            <dt>Fotos</dt>
            <dd>
              {selected.evidence_ids.length} freigegeben
              {selected.evidence_pending ? `, ${selected.evidence_pending} in Prüfung` : ''}
            </dd>
            <dt>Version</dt>
            <dd>{selected.content_version ?? 1}</dd>
          </dl>

          {canState ? (
            <div role="group" aria-label="Folgeaktionen">
              {ACTIONS[selected.status].some((a) => a.reason) ? (
                <div className="tk-field">
                  <label htmlFor="akte-grund">Begründung (für Verwerfen und Wiedereröffnen)</label>
                  <input
                    id="akte-grund"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    maxLength={500}
                  />
                </div>
              ) : null}
              {ACTIONS[selected.status].map((a) => (
                <button key={a.to} type="button" onClick={() => void transition(a.to, a.reason)}>
                  {a.label}
                </button>
              ))}
            </div>
          ) : null}

          {canWrite ? (
            <section aria-labelledby="nachtrag-titel">
              <h3 id="nachtrag-titel">Nachtrag</h3>
              <Form onSubmit={amend} submitLabel="Nachtrag speichern" problem={amendProblem}>
                <Field
                  pointer="/change_reason"
                  label="Änderungsgrund"
                  value={amendReason}
                  onChange={(e) => setAmendReason(e.target.value)}
                />
                <Field
                  pointer="/description"
                  label="Beschreibung (neu)"
                  value={amendDescription}
                  onChange={(e) => setAmendDescription(e.target.value)}
                />
              </Form>
            </section>
          ) : null}

          {canHistory && history ? (
            <section aria-labelledby="verlauf-titel">
              <h3 id="verlauf-titel">Versionsverlauf</h3>
              <DataTable
                caption="Versionen der Meldung"
                rows={history}
                rowKey={(r) => String(r.version)}
                columns={[
                  { key: 'v', header: 'Version', render: (r) => String(r.version) },
                  { key: 'at', header: 'Zeit', render: (r) => fmt(r.created_at) },
                  { key: 'by', header: 'Autor', render: (r) => r.author },
                  { key: 'why', header: 'Änderungsgrund', render: (r) => r.change_reason },
                ]}
              />
              <div className="tk-field">
                <label htmlFor="ver-a">Version A</label>
                <select
                  id="ver-a"
                  value={versionA}
                  onChange={(e) => setVersionA(Number(e.target.value))}
                >
                  {history.map((r) => (
                    <option key={r.version} value={r.version}>
                      {r.version}
                    </option>
                  ))}
                </select>
              </div>
              <div className="tk-field">
                <label htmlFor="ver-b">Version B</label>
                <select
                  id="ver-b"
                  value={versionB}
                  onChange={(e) => setVersionB(Number(e.target.value))}
                >
                  {history.map((r) => (
                    <option key={r.version} value={r.version}>
                      {r.version}
                    </option>
                  ))}
                </select>
              </div>
              <DataTable
                caption={`Vergleich der Versionen ${versionA} und ${versionB}`}
                rows={Object.keys(FIELD_LABELS)}
                rowKey={(k) => k}
                columns={[
                  { key: 'f', header: 'Feld', render: (k) => FIELD_LABELS[k] ?? k },
                  {
                    key: 'a',
                    header: `Version ${versionA}`,
                    render: (k) => content(revisionOf(versionA), k),
                  },
                  {
                    key: 'b',
                    header: `Version ${versionB}`,
                    render: (k) => content(revisionOf(versionB), k),
                  },
                  {
                    key: 'c',
                    header: 'Änderung',
                    render: (k) =>
                      content(revisionOf(versionA), k) === content(revisionOf(versionB), k)
                        ? 'unverändert'
                        : 'geändert',
                  },
                ]}
              />
            </section>
          ) : null}
        </section>
      ) : null}

      {canWrite ? (
        <section aria-labelledby="erfassung-titel">
          <h2 id="erfassung-titel">Schaden erfassen</h2>
          <Form onSubmit={create} submitLabel="Schaden melden" problem={problem}>
            <div className="tk-field">
              <label htmlFor="schaden-fahrzeug">Fahrzeug</label>
              <select
                id="schaden-fahrzeug"
                value={draft.vehicle_id}
                onChange={(e) => setDraft({ ...draft, vehicle_id: e.target.value })}
              >
                <option value="">Bitte wählen</option>
                {vehicles.map((v) => (
                  <option key={v.id} value={v.id}>
                    {v.vehicle_label ?? v.id.slice(0, 8)}
                  </option>
                ))}
              </select>
            </div>
            <Field
              pointer="/occurred_at"
              label="Ereigniszeit"
              type="datetime-local"
              value={draft.occurred_at}
              onChange={(e) => setDraft({ ...draft, occurred_at: e.target.value })}
            />
            <div className="tk-field">
              <label htmlFor="schaden-kategorie">Kategorie</label>
              <select
                id="schaden-kategorie"
                value={draft.category_code}
                onChange={(e) => setDraft({ ...draft, category_code: e.target.value })}
              >
                <option value="">Bitte wählen</option>
                {(Object.keys(CATEGORY_LABELS) as DefectCategory[]).map((c) => (
                  <option key={c} value={c}>
                    {CATEGORY_LABELS[c]}
                  </option>
                ))}
              </select>
            </div>
            <div className="tk-field">
              <label htmlFor="schaden-art">Art (optional)</label>
              <select
                id="schaden-art"
                value={draft.kind}
                onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
              >
                <option value="">Keine Angabe</option>
                {(Object.keys(KIND_LABELS) as DefectKind[]).map((c) => (
                  <option key={c} value={c}>
                    {KIND_LABELS[c]}
                  </option>
                ))}
              </select>
            </div>
            <Field
              pointer="/description"
              label="Beschreibung"
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
            />
          </Form>
        </section>
      ) : null}
    </ScreenFrame>
  );
}
