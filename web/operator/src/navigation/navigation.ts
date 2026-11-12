import type { NavEntry } from '@taktovia/design-system';
import type { Right } from '@taktovia/api-client';

export type OperatorNavEntry = NavEntry<Right>;

/**
 * Navigationsstruktur des Operator-Web je Recht (Kap. 06 §6.9). Ein Eintrag erscheint nur, wenn `/v1/me` das Recht liefert;
 * die Anzeige ist keine Rechtequelle, der Server prüft jede Anfrage erneut. Fachbildschirme UI-01 bis UI-16 und UI-19 folgen in M2 bis M8.
 */
export const OPERATOR_NAV: readonly OperatorNavEntry[] = [
  { id: 'start', label: 'Start', href: '/' },
  { id: 'callcenter', label: 'Callcenter', href: '/callcenter', requires: 'callcenter' },
  { id: 'finanzen', label: 'Finanzen', href: '/finanzen', requires: 'finance' },
  { id: 'personal', label: 'Personal', href: '/personal', requires: 'staff' },
  { id: 'ui11', label: 'Flotte und Werkstatt', href: '/flotte', requires: 'fleet' },
  {
    id: 'ui11-fahrer',
    label: 'Fahrer und Qualifikationen',
    href: '/flotte/fahrer',
    requires: 'drivers',
  },
  { id: 'ui11-schaeden', label: 'Schadenakten', href: '/flotte/schaeden', requires: 'defects' },
  { id: 'ui17', label: 'Gebiete, Stops, Tarife', href: '/admin/gebiete', requires: 'admin.ui17' },
  {
    id: 'ui18',
    label: 'Kooperation und Rollen',
    href: '/admin/kooperation',
    requires: 'admin.ui18',
  },
  {
    id: 'ui20',
    label: 'Datenschutz, Audit, Support',
    href: '/admin/datenschutz',
    requires: 'admin.ui20',
  },
];
