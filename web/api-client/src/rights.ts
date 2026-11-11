import type { Me } from './generated';

/**
 * Rechte, die das Web zur Navigation und zur Anzeige braucht. Quelle ist ausschließlich die Antwort von `/v1/me`
 * (serverseitig geprüfte Sitzung); die Anzeige ist keine Rechtequelle, der Server prüft jede Anfrage erneut (Kap. 06 §6.1).
 */
export type Right =
  'passenger.app' | 'callcenter' | 'finance' | 'staff' | 'admin.ui17' | 'admin.ui18' | 'admin.ui20';

/**
 * VORLÄUFIGE Zuordnung Rolle -> Recht. Rollencodes und Rechtematrix sind fachlich noch nicht freigegeben (OP-10, WP-M1-07);
 * sobald `/v1/me` Rechte oder Funktionsscopes liefert, ersetzt dies diese Tabelle. Administrator erhält bewusst weder
 * Finanz- noch Personalrechte (Kap. 01 §1.7).
 */
export const PROVISIONAL_ROLE_RIGHTS: Readonly<Record<string, readonly Right[]>> = {
  PASSENGER: ['passenger.app'],
  CALLCENTER: ['callcenter'],
  SUPPORT: ['callcenter', 'admin.ui20'],
  DISPATCHER: ['callcenter'],
  PLANNER: ['admin.ui17'],
  ADMIN: ['admin.ui17', 'admin.ui18', 'admin.ui20'],
  FINANCE: ['finance'],
  STAFF_PLANNING: ['staff'],
};

export function rightsOf(me: Pick<Me, 'roles'>): Set<Right> {
  const rights = new Set<Right>();
  for (const role of me.roles) {
    for (const r of PROVISIONAL_ROLE_RIGHTS[role] ?? []) rights.add(r);
  }
  return rights;
}

export function hasRight(me: Pick<Me, 'roles'>, right: Right): boolean {
  return rightsOf(me).has(right);
}
