# LH-6.23-SCH-009 – Mandanten- und Depotberechtigung im Dienstplan

- **Verantwortlicher:** Rolle Plattform/Personal (Modul `staff-time`, Port in `platform`)
- **Arbeitspaket:** WP-M4-16
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Dienstpläne dürfen mandanten- und depotübergreifend nur bei ausdrücklicher Berechtigung bearbeitet werden.

**Originalabnahme (unverändert):** Planer eines Depots kann keine fremden Dienstpläne verändern.

## Vertrag

- **Tabellen / DDL:** RLS (Mandant, V1-Muster) auf `roster_revision`, `staff_shift`, `time_event`, `absence_request`; Depotbezug als Spalte für die Objektprüfung (Spaltenname mit WP-M4-03 festzulegen); Mandantenisolation bleibt RLS, Depotbindung ist zweite Schicht in der Anwendung (Port `canRead/canWrite` aus LH-6.2-IAM-005).
- **API:** alle Pfade unter `/v1/rosters` (inkl. `POST /v1/rosters/{roster_id}/publish`) prüfen das Depotrecht; nicht sichtbare Dienstpläne -> 404, fehlender Scope -> 403; Listenfilter dürfen den Depotscope nur einschränken; `tenant_id` im Payload wird nie übernommen (Kap. 05 §5.8).
- **Rechtematrix:** Planerrolle × Depot; „ausdrückliches depotübergreifendes Recht“ ist ein eigenes, explizit vergebenes Recht; Definition im Detailvertrag WP-M4-01 (Vorschlag: eigener Scope bzw. Rolle je Depotmenge). Bearbeitung mit diesem Recht wird im `audit_event` protokolliert.
- **Mandantenübergreifend:** Im P1-Umfang gibt es kein Recht zur mandantenübergreifenden Bearbeitung von Dienstplänen; Mandantenisolation bleibt ausnahmslos (RLS). Eine ausdrückliche mandantenübergreifende Berechtigung ist nicht Teil dieses Vertrags (mandantenübergreifende Fahrerfreigabe liegt in M1/M6); der Vertrag sieht dafür keinen Pfad vor.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-12 zeigt nur berechtigte Depots; Fehlerzustand 404/403 ohne Existenzhinweis.
- **Entscheidungen:** keine.
- **Offene Punkte:** Definition des depotübergreifenden Rechts und Semantik leerer Depotscope (analog OP-04 aus WP-M1-08, Vorschlag fail-closed); gemeinsamer Pool über Bereiche (SCH-029) ist P2.

## Tests

- **Positiv:** Planer bearbeitet und veröffentlicht Dienstplan des eigenen Depots; Planer mit ausdrücklichem depotübergreifendem Recht bearbeitet fremdes Depot, Aktion steht im `audit_event`.
- **Negativ:** Planer von Depot A kann Dienstplan von Depot B weder lesen (404) noch ändern noch veröffentlichen; Dienstpläne eines anderen Mandanten sind in keiner Rolle erreichbar, auch nicht mit manipulierter ID oder manipulierter `tenant_id` im Payload (Contract-Fall Kap. 05 §5.8); Depotfilter in Liste/Cursor/Sortierung liefert nie fremde Depots; fehlender Scope -> 403.
- **Konkurrierend:** Rechteentzug während offener Bearbeitung: der nächste Schreibbefehl scheitert; Entzug parallel zu einer Publikation: nach Commit des Entzugs wird die Publikation abgewiesen.
- **Referenztests:** keine Katalog-ID im WP-Dokument genannt; Baustein für PH-AT-001 (Isolation in Datenbank, API, Suche und Export) und den Arbeitszeitteil von AT-49; vom WP nicht als Testfall dieser Anforderung genannt, daher nicht als Token geführt.
- Testfälle: geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDepotAuthorizationTest.kt::planer_fremdes_depot_lesen_aendern_veroeffentlichen_abgewiesen; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDepotAuthorizationTest.kt::manipulierte_tenant_id_im_payload_wirkungslos; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDepotAuthorizationTest.kt::rechteentzug_waehrend_bearbeitung_naechster_schreibbefehl_scheitert; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDepotAuthorizationTest.kt::depotuebergreifendes_recht_erlaubt_bearbeitung_und_wird_auditiert; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/RosterDepotAuthorizationTest.kt::leerer_depotscope_liefert_nichts_fail_closed

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
