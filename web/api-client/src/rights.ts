import type { Me } from './generated';

/**
 * Rechte, die das Web zur Navigation und zur Anzeige braucht. Quelle ist ausschließlich die Antwort von `/v1/me`
 * (serverseitig geprüfte Sitzung); die Anzeige ist keine Rechtequelle, der Server prüft jede Anfrage erneut (Kap. 06 §6.1).
 */
export type Right =
  | 'passenger.app'
  | 'callcenter'
  | 'finance'
  | 'staff'
  | 'time.own'
  | 'roster'
  | 'roster.write'
  | 'roster.publish'
  | 'fleet'
  | 'fleet.write'
  | 'drivers'
  | 'drivers.write'
  | 'shifts'
  | 'shifts.write'
  | 'checklists'
  | 'checklists.write'
  | 'inspections'
  | 'inspections.amend'
  | 'defects'
  | 'defects.write'
  | 'defects.state'
  | 'defects.history'
  | 'admin.ui17'
  | 'admin.ui18'
  | 'admin.ui20';

/**
 * Navigationsrechte aus den **Funktionsscopes** der Sitzung (`/v1/me` -> `scopes`, vom Server aus Rollen und Rechtematrix abgeleitet,
 * WP-M1-07). Ein Scope öffnet höchstens die Anzeige eines Bereichs; der Server prüft jede Anfrage erneut.
 */
export const SCOPE_RIGHTS: Readonly<Record<string, readonly Right[]>> = {
  'journeys:book': ['passenger.app'],
  'passengers:read': ['callcenter'],
  'costs:allocate': ['finance'],
  'expenses:approve': ['finance'],
  // UI-13 Eigene Zeit/Abwesenheit (WP-M4-20): jede Person mit Zeitrecht sieht ausschließlich ihre eigenen Daten; der Server bindet die Person an den Akteur.
  'time-events:read': ['time.own'],
  'rosters:read': ['roster'],
  'rosters:write': ['roster', 'roster.write'],
  'rosters:publish': ['staff', 'roster.publish'],
  'driver-releases:write': ['staff'],
  'fleet:read': ['fleet'],
  'fleet:write': ['fleet', 'fleet.write'],
  'drivers:read': ['drivers'],
  'drivers:write': ['drivers', 'drivers.write'],
  'shifts:read': ['shifts'],
  'shifts:write': ['shifts', 'shifts.write'],
  'checklists:read': ['checklists'],
  'checklists:write': ['checklists', 'checklists.write'],
  // UI-11 Prüfnachweis (WP-M3-14, WP-M3-15): Lesen der Prüfhistorie und Nachtrag mit Pflichtgrund
  // Der Bildschirm öffnet für Disposition, Werkstatt und Betriebsleitung (Scope `inspections:justify`); Fahrer arbeiten mit ihren Prüfungen in der Fahrer-App, nicht im Operator-Web.
  'inspections:justify': ['inspections'],
  'inspections:amend': ['inspections', 'inspections.amend'],
  // Der Bereich „Schadenakten“ des Operator-Web öffnet das Triagerecht (Disposition, Werkstatt); Fahrer lesen und melden über die Fahrer-App, nicht im Operator-Web.
  'defects:triage': ['defects'],
  'defects:write': ['defects.write'],
  'defects:state': ['defects.state'],
  'defects:history': ['defects.history'],
  'config:write': ['admin.ui17'],
  'memberships:read': ['admin.ui18'],
  'audit:read': ['admin.ui20'],
};

/**
 * VORLÄUFIGE Rückfallzuordnung Rolle -> Recht für Antworten ohne `scopes` (ältere Server, Tests). Rollencodes und Rechtematrix sind
 * fachlich noch nicht freigegeben (OP-10); sobald `/v1/me` Scopes liefert, gilt ausschließlich [SCOPE_RIGHTS]. Administrator erhält bewusst
 * weder Finanz- noch Personalrechte (Kap. 01 §1.7). Rollencodes werden ohne Beachtung der Groß-/Kleinschreibung verglichen.
 */
export const PROVISIONAL_ROLE_RIGHTS: Readonly<Record<string, readonly Right[]>> = {
  PASSENGER: ['passenger.app'],
  CALLCENTER: ['callcenter'],
  SUPPORT: ['callcenter', 'admin.ui20'],
  DISPATCHER: [
    'callcenter',
    'fleet',
    'fleet.write',
    'drivers',
    'shifts',
    'shifts.write',
    'defects',
    'defects.write',
    'defects.state',
  ],
  WORKSHOP: [
    'fleet',
    'fleet.write',
    'defects',
    'defects.write',
    'defects.state',
    'defects.history',
  ],
  PLANNER: ['admin.ui17', 'fleet', 'drivers', 'shifts', 'roster', 'roster.write'],
  ADMIN: ['admin.ui17', 'admin.ui18', 'admin.ui20'],
  TENANT_ADMIN: ['admin.ui17', 'admin.ui18', 'admin.ui20', 'defects.state', 'defects.history'],
  FINANCE: ['finance'],
  STAFF_PLANNING: [
    'staff',
    'drivers',
    'drivers.write',
    'shifts',
    'roster',
    'roster.write',
    'roster.publish',
  ],
};

export function rightsOf(me: Pick<Me, 'roles'> & { scopes?: string[] | undefined }): Set<Right> {
  const rights = new Set<Right>();
  if (me.scopes) {
    for (const scope of me.scopes) for (const r of SCOPE_RIGHTS[scope] ?? []) rights.add(r);
    return rights;
  }
  for (const role of me.roles) {
    for (const r of PROVISIONAL_ROLE_RIGHTS[role.toUpperCase()] ?? []) rights.add(r);
  }
  return rights;
}

export function hasRight(
  me: Pick<Me, 'roles'> & { scopes?: string[] | undefined },
  right: Right,
): boolean {
  return rightsOf(me).has(right);
}
