# LH-I.7-CFG-003 – Änderungsprotokoll und Rekonstruktion von Konfigurationen

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-20
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Konfigurationsänderungen werden mit Benutzer, Zeitpunkt, Altwert, Neuwert, Grund und Gültigkeitsbeginn protokolliert.

**Originalabnahme:** Eine frühere Einstellung ist für einen historischen Dispositionsfall rekonstruierbar.

## Vertrag

- **Tabellen / DDL:** `config_revision` wie bei SYS-002; Diff Altwert/Neuwert gegen die Vorgängerrevision wird berechnet, nicht gespeichert. Publizierte Zeilen sind per Trigger unveränderlich (analog `reject_mutation()`), auch für die Runtime-Rolle per Recht.
- **API:** `GET /v1/config/revisions` (Liste), `GET /v1/config/revisions/{id}`; Parameter `as_of` liefert die zum Zeitpunkt wirksame Revision samt Revisions-ID. Jede Ansicht eines Konfigurationswerts nennt die verwendete Revision.
- **Zustandsautomat:** siehe SYS-002. **Ereignisse:** `config.revision_created`; Audit über WP-M1-46.
- **Parameter und Kennzahlen:** keine neuen. **Oberfläche:** UI-17 Basis mit Diff Alt/Neu und Grund.
- **Entscheidungen:** keine. **Offene Punkte:** Nachweis an echtem Dispositionsfall mit gebundener `config_revision_id` erst mit M2 (WP-M2-04, -07, -21, -23).

## Tests

- **Positiv:** drei Revisionen, As-of-Abfrage auf jede liefert den damals wirksamen Stand.
- **Negativ:** Änderung ohne Grund; Update oder Löschen einer publizierten Revision per API und per SQL unter der Runtime-Rolle; fremder Scope.
- **Konkurrierend:** zwei parallele Entwürfe mit derselben Revisionsnummer.
- **Referenztests:** E2E-096, E2E-098, E2E-103 (KPI-Definition geändert), PH-AT-018.

## Reviews

Noch keine Reviewer benannt (D-12).
