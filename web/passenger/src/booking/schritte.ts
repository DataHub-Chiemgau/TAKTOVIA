/**
 * Hauptschritte der Standardfahrt (WP-M2-37, LH-6.3-PAX-001, Arbeitsannahme OP-32): ein Hauptschritt ist ein Bildschirmabschnitt mit genau einer Entscheidung,
 * nach der es weitergeht. Standardfahrt = ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel (keine eigene Zahlungsmittelauswahl). Die Anmeldung zählt nicht mit.
 * Nachweis: docs/umsetzungsplan/nachweise/WP-M2-37-schrittzaehlung.md.
 */
export const HAUPTSCHRITTE = [
  { nr: 1, id: 'orte', label: 'Start und Ziel wählen' },
  { nr: 2, id: 'zeit', label: 'Zeit und Reisende angeben, Verbindung suchen' },
  { nr: 3, id: 'angebot', label: 'Angebot prüfen und wählen' },
  { nr: 4, id: 'buchen', label: 'Buchung bestätigen' },
] as const;

export type HauptschrittId = (typeof HAUPTSCHRITTE)[number]['id'];

/** Obergrenze laut Originalabnahme. */
export const MAX_HAUPTSCHRITTE = 5;
