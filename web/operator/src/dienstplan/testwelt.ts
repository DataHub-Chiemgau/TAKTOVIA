import { ApiError } from '@taktovia/api-client';
import type {
  ApiClient,
  Depot,
  Driver,
  OperationId,
  QualificationRevision,
  Roster,
  RosterConflict,
  RosterSide,
  Shift,
} from '@taktovia/api-client';

/**
 * Testwelt für UI-12 (WP-M4-18/-19): zustandsbehafteter Server-Ersatz für die Dienstplan-Operationen mit dem dokumentierten Verhalten (Entwurf/veröffentlicht getrennt,
 * `If-Match` mit 412, Konfliktprüfung Qualifikation und Doppelbelegung, Rechte über Fehlerantworten). Ein Modell des Vertrags, kein Ersatz für den echten Server: das echte
 * Serververhalten prüfen die Backend-Tests (`RosterShiftModelTest`, `ShiftConflictCheckTest`, `RosterDraftVisibilityTest`). Nur für Tests.
 */

export const uuid = (n: number): string => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
export const IDS = {
  depotNord: uuid(0xd1),
  depotSued: uuid(0xd2),
  fremdesDepot: uuid(0xdf),
  operator: uuid(0x0b),
  anna: uuid(0xa1),
  ben: uuid(0xa2),
  cem: uuid(0xa3),
  fahrzeug: uuid(0xf1),
  rosterW1: uuid(0x301),
  rosterW2: uuid(0x302),
  rosterW3: uuid(0x303),
} as const;
export const W1 = '2026-11-30';
export const W2 = '2026-12-07';
export const TAG = (n: number): string => `2026-12-${String(n).padStart(2, '0')}`;

export function shift(partial: Partial<Shift> & Pick<Shift, 'id' | 'roster_id' | 'date'>): Shift {
  const { date } = partial;
  return {
    starts_local: `${date}T06:00:00`,
    ends_local: `${date}T14:00:00`,
    timezone: 'Europe/Berlin',
    starts_at: `${date}T05:00:00Z`,
    ends_at: `${date}T13:00:00Z`,
    duration_min: 480,
    break_plan: { schema_version: '1.0', breaks: [] },
    operator_id: IDS.operator,
    depot_id: IDS.depotNord,
    function: 'DRIVER',
    required_qualification: null,
    staffing_need: 1,
    driver_ref: null,
    vehicle_id: null,
    state: 'DRAFT',
    created_by: 'user:planer',
    created_at: '2026-11-20T08:00:00Z',
    revision: 1,
    ...partial,
  };
}

const driver = (id: string, name: string): Driver => ({
  id,
  membership_id: null,
  status: 'ACTIVE',
  qualification_seq: 1,
  revision: 1,
  hr_fields_visible: true,
  display_name: name,
  contact_channel: null,
  contact_value: null,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
});

const qual = (code: string): QualificationRevision => ({
  id: uuid(0x900),
  qualification_code: code,
  revision: 1,
  status: 'APPROVED',
  valid_from: '2026-01-01T00:00:00Z',
  valid_to: null,
  approved_by: 'user:hr',
  approved_at: '2026-01-02T00:00:00Z',
  driver_seq: 1,
  evidence_ref: null,
  effective: true,
  valid_at: true,
  created_at: '2026-01-01T00:00:00Z',
});

export type Aufruf = { id: OperationId; args: Record<string, unknown> };

export interface Testwelt {
  /** Handler für `SessionProvider`-Clients (ohne getMe). */
  handle: (id: OperationId, args: Record<string, unknown>) => unknown;
  calls: Aufruf[];
  /** Aktueller Stand der Schichten je Dienstplan (Entwurf). */
  entwurf: Map<string, Shift[]>;
  /** Fehler, den der nächste Aufruf der Operation liefert (einmalig). */
  scheitern: (id: OperationId, status: number) => void;
  /** Simuliert einen zweiten Planer, der die Schicht zwischenzeitlich ändert (Revision steigt). */
  fremdeAenderung: (shiftId: string) => void;
  zaehle: (id: OperationId) => number;
}

/** Konfliktprüfung des Entwurfs wie WP-M4-07 (Auszug): fehlende Qualifikation und Doppelbelegung derselben Person. */
function pruefe(shifts: Shift[], qualOf: Map<string, string[]>): RosterConflict[] {
  const out: RosterConflict[] = [];
  for (const s of shifts) {
    if (
      s.driver_ref &&
      s.required_qualification &&
      !(qualOf.get(s.driver_ref) ?? []).includes(s.required_qualification)
    )
      out.push({
        code: 'ROSTER_QUALIFICATION_MISSING',
        person_ref: s.driver_ref,
        shift_ids: [s.id],
        cause: 'Qualifikation fehlt',
        missing_qualification: s.required_qualification,
      });
    for (const t of shifts)
      if (
        t.id > s.id &&
        s.driver_ref &&
        s.driver_ref === t.driver_ref &&
        s.starts_at < t.ends_at &&
        t.starts_at < s.ends_at
      )
        out.push({
          code: 'ROSTER_DOUBLE_BOOKING',
          person_ref: s.driver_ref,
          shift_ids: [s.id, t.id],
          cause: 'Doppelbelegung',
          missing_qualification: null,
        });
  }
  return out;
}

export function baueTestwelt(opts: { mitW1Veroeffentlicht?: boolean } = {}): Testwelt {
  const depots: Depot[] = [
    { id: IDS.depotNord, region_id: uuid(0x71), name: 'Depot Nord', revision: 1 },
    { id: IDS.depotSued, region_id: uuid(0x71), name: 'Depot Süd', revision: 1 },
  ];
  const fahrer = [
    driver(IDS.anna, 'Anna Beispiel'),
    driver(IDS.ben, 'Ben Muster'),
    driver(IDS.cem, 'Cem Probe'),
  ];
  const qualOf = new Map<string, string[]>([
    [IDS.anna, ['PBEF', 'ROLLSTUHL']],
    [IDS.ben, ['PBEF']],
    [IDS.cem, []],
  ]);
  const etag = new Map<string, number>([
    [IDS.rosterW1, 3],
    [IDS.rosterW2, 1],
  ]);
  const entwurf = new Map<string, Shift[]>([
    [
      IDS.rosterW1,
      [
        shift({
          id: uuid(0x501),
          roster_id: IDS.rosterW1,
          date: TAG(1),
          driver_ref: IDS.anna,
          vehicle_id: IDS.fahrzeug,
        }),
        shift({
          id: uuid(0x502),
          roster_id: IDS.rosterW1,
          date: TAG(1),
          driver_ref: IDS.ben,
          starts_local: `${TAG(1)}T14:00:00`,
          ends_local: `${TAG(1)}T22:00:00`,
          starts_at: `${TAG(1)}T13:00:00Z`,
          ends_at: `${TAG(1)}T21:00:00Z`,
        }),
        shift({
          id: uuid(0x503),
          roster_id: IDS.rosterW1,
          date: TAG(2),
          driver_ref: null,
          required_qualification: 'ROLLSTUHL',
        }),
        shift({ id: uuid(0x504), roster_id: IDS.rosterW1, date: TAG(3), driver_ref: null }),
      ],
    ],
    [
      IDS.rosterW2,
      [shift({ id: uuid(0x511), roster_id: IDS.rosterW2, date: TAG(8), driver_ref: IDS.ben })],
    ],
  ]);
  const veroeffentlicht = new Map<string, Shift[]>();
  if (opts.mitW1Veroeffentlicht)
    veroeffentlicht.set(
      IDS.rosterW1,
      (entwurf.get(IDS.rosterW1) ?? []).map((s) => ({
        ...s,
        state: 'PUBLISHED',
        id: s.id.replace(/5/, '6'),
      })),
    );
  const calls: Aufruf[] = [];
  const failing = new Map<OperationId, number>();
  const weeks: Record<string, string> = { [IDS.rosterW1]: W1, [IDS.rosterW2]: W2 };

  const side = (rid: string, state: 'DRAFT' | 'PUBLISHED'): RosterSide => {
    const shifts = (state === 'DRAFT' ? entwurf : veroeffentlicht).get(rid) ?? [];
    return {
      revision: state === 'DRAFT' ? (veroeffentlicht.has(rid) ? 2 : 1) : 1,
      state,
      author: 'user:planer',
      created_at: '2026-11-20T08:00:00Z',
      published_at: state === 'PUBLISHED' ? '2026-11-21T08:00:00Z' : null,
      etag: etag.get(rid) ?? 1,
      shift_count: shifts.length,
      open_shifts: shifts.filter((s) => !s.driver_ref).length,
      unstaffed_need: shifts.filter((s) => !s.driver_ref).length,
      need_lines: [],
    };
  };
  const roster = (rid: string): Roster => ({
    id: rid,
    depot_id: IDS.depotNord,
    week_start: weeks[rid] ?? W1,
    draft: entwurf.has(rid) ? side(rid, 'DRAFT') : null,
    published: veroeffentlicht.has(rid) ? side(rid, 'PUBLISHED') : null,
    conflicts: pruefe(entwurf.get(rid) ?? [], qualOf),
    revision: etag.get(rid) ?? 1,
  });
  const asOf = '2026-12-02T09:00:00Z';

  const welt: Testwelt = {
    calls,
    entwurf,
    scheitern: (id, status) => void failing.set(id, status),
    zaehle: (id) => calls.filter((c) => c.id === id).length,
    fremdeAenderung: (shiftId) => {
      for (const list of entwurf.values()) {
        const i = list.findIndex((s) => s.id === shiftId);
        const cur = list[i];
        if (cur) list[i] = { ...cur, revision: cur.revision + 1 };
      }
    },
    handle: (id, args) => {
      calls.push({ id, args });
      const fail = failing.get(id);
      if (fail !== undefined) {
        failing.delete(id);
        throw new ApiError(fail, {
          status: fail,
          code: 'TEST',
          type: 'TEST',
          title: 'TEST',
        } as never);
      }
      const query = (args['query'] ?? {}) as Record<string, string | number | boolean | undefined>;
      const path = (args['path'] ?? {}) as Record<string, string>;
      switch (id) {
        case 'listDepots':
          return { items: depots, next_cursor: null, as_of: asOf };
        case 'listRosters': {
          if (query['depot_id'] && !depots.some((d) => d.id === query['depot_id']))
            throw new ApiError(404);
          const items = [IDS.rosterW1, IDS.rosterW2]
            .map(roster)
            .filter(
              (r) =>
                (!query['from'] || r.week_start >= String(query['from'])) &&
                (!query['to'] || r.week_start <= String(query['to'])),
            );
          return { items, next_cursor: null, as_of: asOf };
        }
        case 'getRoster': {
          const r = roster(String(path['roster_id']));
          return query['view'] === 'published' ? { ...r, draft: null, conflicts: null } : r;
        }
        case 'listRosterShifts': {
          const rid = String(query['roster_id']);
          const planner = query['view'] !== 'published';
          const list = planner && entwurf.has(rid) ? entwurf.get(rid) : veroeffentlicht.get(rid);
          return {
            items: [...(list ?? [])].sort((a, b) => a.starts_at.localeCompare(b.starts_at)),
            next_cursor: null,
            as_of: asOf,
          };
        }
        case 'listDrivers':
          return { items: fahrer, next_cursor: null, as_of: asOf };
        case 'listAbsences':
          return {
            items: [
              {
                id: uuid(0x700),
                person_ref: IDS.cem,
                from: TAG(3),
                to: TAG(4),
                availability: 'UNAVAILABLE',
              },
            ],
            next_cursor: null,
            as_of: asOf,
          };
        case 'listVehicleMasters':
          return {
            items: [{ id: IDS.fahrzeug, vehicle_label: 'Bus 12', active: true }],
            next_cursor: null,
            as_of: asOf,
          };
        case 'listDriverQualifications':
          return {
            items: (qualOf.get(String(path['driver_id'])) ?? []).map(qual),
            next_cursor: null,
            as_of: asOf,
            at: asOf,
          };
        case 'updateRosterShift': {
          const body = args['body'] as Record<string, unknown>;
          const rid = String(body['roster_id']);
          const list = entwurf.get(rid) ?? [];
          const i = list.findIndex((s) => s.id === path['shift_id']);
          const cur = list[i];
          if (!cur) throw new ApiError(404);
          if (Number(args['revision']) !== cur.revision) throw new ApiError(412);
          const day = String(body['starts_local']).slice(0, 10);
          const updated: Shift = {
            ...cur,
            date: day,
            starts_local: String(body['starts_local']),
            ends_local: String(body['ends_local']),
            starts_at: `${day}${cur.starts_at.slice(10)}`,
            ends_at: `${String(body['ends_local']).slice(0, 10)}${cur.ends_at.slice(10)}`,
            driver_ref: (body['driver_ref'] as string | null) ?? null,
            vehicle_id: (body['vehicle_id'] as string | null) ?? null,
            revision: cur.revision + 1,
          };
          list[i] = updated;
          etag.set(rid, (etag.get(rid) ?? 1) + 1);
          return {
            ...updated,
            conflicts: pruefe(list, qualOf).filter((c) => c.shift_ids.includes(updated.id)),
          };
        }
        case 'createRosterShift': {
          const body = args['body'] as Record<string, unknown>;
          const rid = String(body['roster_id']);
          const day = String(body['starts_local']).slice(0, 10);
          const created = shift({
            id: uuid(0x600 + (entwurf.get(rid)?.length ?? 0) + calls.length),
            roster_id: rid,
            date: day,
            starts_local: `${String(body['starts_local'])}:00`,
            ends_local: `${String(body['ends_local'])}:00`,
            function: String(body['function']),
            required_qualification: (body['required_qualification'] as string | undefined) ?? null,
            staffing_need: Number(body['staffing_need']),
            driver_ref: (body['driver_ref'] as string | null) ?? null,
            vehicle_id: (body['vehicle_id'] as string | null) ?? null,
          });
          const list = entwurf.get(rid) ?? [];
          list.push(created);
          entwurf.set(rid, list);
          etag.set(rid, (etag.get(rid) ?? 1) + 1);
          return {
            ...created,
            conflicts: pruefe(list, qualOf).filter((c) => c.shift_ids.includes(created.id)),
          };
        }
        case 'publishRoster': {
          const rid = String(path['roster_id']);
          if (Number(args['revision']) !== (etag.get(rid) ?? 1)) throw new ApiError(412);
          if (pruefe(entwurf.get(rid) ?? [], qualOf).length > 0) throw new ApiError(422);
          veroeffentlicht.set(
            rid,
            (entwurf.get(rid) ?? []).map((s) => ({ ...s, state: 'PUBLISHED' })),
          );
          entwurf.delete(rid);
          return {
            resource_id: rid,
            revision: 2,
            state: 'PUBLISHED',
            effective_from: '2026-12-07T00:00:00Z',
            audit_event_id: uuid(0x800),
          };
        }
        case 'createRoster':
          return { ...roster(IDS.rosterW3), id: IDS.rosterW3 };
        case 'openRosterDraft': {
          const rid = String(path['roster_id']);
          entwurf.set(
            rid,
            (veroeffentlicht.get(rid) ?? []).map((s) => ({ ...s, state: 'DRAFT' })),
          );
          return roster(rid);
        }
        default:
          throw new ApiError(404);
      }
    },
  };
  return welt;
}

export function clientFuer(
  handler: (id: OperationId, args: Record<string, unknown>) => unknown,
  me: () => unknown,
): ApiClient {
  return {
    call: (id: OperationId, args: Record<string, unknown>) => {
      if (id === 'getMe') return Promise.resolve(me());
      try {
        return Promise.resolve(handler(id, args));
      } catch (e) {
        return Promise.reject(e);
      }
    },
  } as unknown as ApiClient;
}
