# LH-6.23-SCH-010 – Änderungs- und Veröffentlichungsrevisionen der Schicht

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-15
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für jede Schicht existiert eine Änderungs- und Veröffentlichungsrevision mit Autor und Zeitstempel.

**Originalabnahme (unverändert):** Historische veröffentlichte Fassungen sind auditierbar.

## Vertrag

- **Tabellen / DDL:** `roster_revision` (unveränderlich nach Publikation; UPDATE/DELETE auf veröffentlichte Revision scheitert per DB-Regel/Trigger, Muster V2/V3); Schichtstände je Revision als Snapshot je Revision (Vorschlag dieses Plans; Tabellenname mit WP-M4-01 festzulegen, OP-8); `audit_event` (append-only) für Akteur, Aktion, Objekt, Zeitstempel. Jede Schichtänderung und jede Publikation schreibt eine Revision mit Autor und Zeitstempel.
- **API:** `/v1/rosters` Revisionsliste und Abruf einer historischen veröffentlichten Fassung (Pfad-Unterstruktur Vorschlag dieses Plans, Kap. 05 §5.7); nur lesend; Zugriff nur mit Auditrecht und Depotberechtigung (SCH-009).
- **Zustandsautomat:** Revision: Entwurf -> veröffentlicht (final, unveränderlich).
- **JSON-Schema / Ereignisse:** keine eigenen.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine eigene im P1-Umfang (Vergleichsansicht SCH-025 ist P2).
- **Entscheidungen:** D-11 (Aufbewahrungsdauer der Planhistorie; blockiert nur Produktivschaltung).
- **Offene Punkte:** Speicherform historischer Schichtstände (Snapshot als Vorschlag, OP-8); Auditfelder im Detailvertrag WP-M4-01.

## Tests

- **Positiv:** drei Veröffentlichungen einer Schicht; die erste Fassung ist nach zwei weiteren Veröffentlichungen unverändert abrufbar; für eine Schicht sind alle Änderungen und Veröffentlichungen mit Autor und Zeitstempel abrufbar.
- **Negativ:** UPDATE oder DELETE auf eine veröffentlichte Revision scheitert; Abruf der Historie ohne Auditrecht abgewiesen; Abruf einer Revision eines fremden Depots/Mandanten -> 404.
- **Konkurrierend:** parallele Veröffentlichung und Abruf: Abruf sieht nur vollständig committete Revisionen; zwei parallele Publikationen erzeugen genau eine neue Revisionsnummer ohne Lücke oder Doppelvergabe.
- **Referenztests:** keine Katalog-ID im WP-Dokument genannt.
- Testfälle: geplant:db/tests/test_roster_revision.py::test_veroeffentlichte_revision_unveraenderlich; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterRevisionHistoryTest.kt::fruehere_fassung_nach_zwei_weiteren_veroeffentlichungen_unveraendert_abrufbar; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterRevisionHistoryTest.kt::aenderungen_und_veroeffentlichungen_einer_schicht_mit_autor_und_zeitstempel; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterRevisionHistoryTest.kt::historie_ohne_auditrecht_oder_fremdes_depot_abgewiesen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
