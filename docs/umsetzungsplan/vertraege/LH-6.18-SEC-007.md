# LH-6.18-SEC-007 – Aufbewahrungs-, Archivierungs- und Löschregeln

- **Verantwortlicher:** Rolle Plattform (Module `identity`/`platform`, Löschworker)
- **Arbeitspaket:** WP-M1-33
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Personenbezogene Daten haben definierte Aufbewahrungs-, Archivierungs- und Löschregeln.

**Originalabnahme (unverändert):** Testdatensatz wird nach freigegebener Regel entfernt oder anonymisiert.

## Vertrag

- **Tabellen / DDL:** neue Tabellen `retention_rule` und `erasure_job` (Kap. 04 §4.4 CFG/SEC/INT) mit `tenant_id`, lokalen zusammengesetzten FKs, RLS `tenant_isolation`, Revision und Audit; Feldliste ist im Pflichtenheft nicht festgelegt und wird mit dem Bau vorgeschlagen: `retention_rule` (Datenklasse, Frist, Aktion `delete`/`anonymize`/`archive`, Freigabestatus, Gültigkeit), `erasure_job` (Regelbezug, Status, Fortsetzungsmarke, Zähler). Nutzung von `passenger_private.retention_until`. Unveränderliche Tabellen (z. B. `audit_event`) nur über kontrollierten Lifecycleprozess (§4.5). Lösch-/Sperrjournal für Restore (WP-M1-39), damit Gelöschtes nicht wiederbelebt wird.
- **API:** Regelverwaltung und Jobstatus (kein Pfad in Kap. 05 §5.7; Detailvertrag als `api/`-Ergänzung, offen). Mandantenisolation per 404.
- **Zustandsautomat:** `erasure_job` (läuft/fortsetzbar/abgeschlossen/abgebrochen) und Regel (Entwurf/freigegeben) – Detail in `vertraege/zustaende/erasure_job.md` mit dem Bau; Zustandsnamen sind hier nicht festgelegt.
- **JSON-Schema / Ereignisse:** keine; Löschmatrix Datenklasse × Frist × Aktion als Anlage.
- **Parameter und Kennzahlen:** `invoice_archive_years` als angrenzender Parameter (Schema folgt M4, nicht Umfang).
- **Ablauf:** Löschlauf als fortsetzbarer Job über `operation`; wendet nur freigegebene Regeln an, nur im Mandantenkontext; protokolliert jedes betroffene Objekt ohne Personendaten; Archivregel überführt in Archivzustand (für operative Rollen nicht lesbar, bis Fristende für berechtigte Rolle abrufbar); Löschworker für DB und Objektspeicher; Trennung aufbewahrungspflichtiger Bezüge von entbehrlicher PII (Kap. 01 §1.7). Regeländerungen und Läufe sind auditiert. Für jede im Dateninventar (SEC-004) personenbezogene Datenklasse muss eine Regel existieren; fehlende Regel wird in CI gemeldet.
- **Oberfläche:** UI-20 Basis – Regelübersicht und Löschprotokoll.
- **Entscheidungen:** D-11 (Fristen und Löschmatrix) – Bau mit Testfristen möglich; Produktivschaltung personenbezogener Daten blockiert, bis die Löschmatrix beschlossen ist.
- **Offene Punkte:** OP-25 (Feldliste von `retention_rule`/`erasure_job`; Fristen nicht festgelegt; Grenze zum M8-Umfang „Lösch- und Auskunftsjobs, Legal Hold" – Betroffenenanfragen `/v1/privacy/requests`, Legal Hold, Suchindex/Partnerprojektion, Finanzarchiv sind nicht Umfang). Keine Fristen werden erfunden; Testfristen gelten nur für Tests.

## Tests

- **Positiv:** Testdatensatz (Fahrgastkonto mit Kontakt- und Unterstützungsdaten) wird nach Ablauf der freigegebenen Test-Regel gelöscht bzw. anonymisiert, danach liefert weder API noch SQL Personendaten; Archivierung nach Regel; zugehöriges Objekt im Objektspeicher wird gelöscht; Löschprotokoll ohne Personendaten; Restore belebt Gelöschtes nicht wieder.
- **Negativ:** nicht freigegebene Regel wird nicht angewendet; Datensatz mit noch laufender Frist bleibt; Lauf im Kontext A berührt Mandant B nicht; fehlende Regel für personenbezogene Datenklasse wird in CI gemeldet; Abbruch des Laufs, danach fortsetzbar ohne Doppelverarbeitung; archivierter Datensatz ist für operative Rollen nicht lesbar.
- **Konkurrierend:** Löschlauf parallel zu Profiländerung desselben Datensatzes – konsistentes Ergebnis ohne wiederauflebende PII; zwei Läufe parallel verarbeiten ein Objekt nicht doppelt.
- **Referenztests:** keine Katalog-ID im WP genannt (AT-53 betrifft Archivregeln für M3/M4-Exporte und ist im WP nicht zugeordnet).
- Testfälle: geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::testdatensatz_wird_nach_freigegebener_regel_entfernt; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::archivierung_nach_regel_operative_rolle_ohne_zugriff; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::abbruch_und_fortsetzung_ohne_doppelverarbeitung; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::lauf_in_mandant_a_beruehrt_b_nicht; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::zwei_laeufe_parallel_ohne_doppelverarbeitung; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::objektspeicher_objekt_wird_geloescht; geplant:tests/acceptance/security/test_loeschjournal_restore.py::test_restore_belebt_geloeschtes_nicht_wieder; geplant:db/tests/test_10_retention.py::test_regel_fuer_personenbezogene_datenklasse_vorhanden; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::nicht_freigegebene_regel_wird_nicht_angewendet; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/ErasureJobTest.kt::loeschlauf_parallel_zu_profilaenderung; geplant:db/tests/test_10_retention.py::test_retention_rule_erasure_job_rls

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
