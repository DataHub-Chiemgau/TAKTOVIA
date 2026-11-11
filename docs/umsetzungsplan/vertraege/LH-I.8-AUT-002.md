# LH-I.8-AUT-002 – Ablaufprotokoll automatisch ausgeführter Vorgänge

- **Verantwortlicher:** Rolle Dispatch/Leitstelle (Workflow-Konsument; Modulzuordnung offen, OP-07)
- **Arbeitspaket:** WP-M3-55
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder automatisch ausgeführte Vorgang zeigt Auslöser, Regelversion, Entscheidung und Folgeaktionen.

**Originalabnahme (unverändert):** Der Betriebsleiter kann den Ablauf eines Testvorfalls vollständig rekonstruieren.

## Vertrag

- **Tabellen / DDL:** Ablaufprotokoll am Incident bzw. eigene Tabelle (Arbeitsname in WP-M3-01, OP-02): je Workflow-Ausführung Auslöserereignis (Referenz), Regelrevision, Entscheidung, Folgeaktionen mit Ergebnis (inkl. fehlgeschlagen/übersprungen), `correlation_id` (Bezug zu `audit_event.correlation_id`, V1: `uuid NOT NULL`) und laufende Ordnungsnummer je Incident (eindeutig, auch bei parallelen Schreibern). Append-only: Trigger `reject_mutation()` (wie `audit_immutable`), kein UPDATE/DELETE für die Laufzeitrolle in `db/role_matrix.csv`. Referenzen statt Rohpayloads mit Personendaten. Mandantenschlüssel, RLS `tenant_isolation`.
- **API:** Ablauf als Bestandteil von `GET /v1/operator/incidents/{id}`; Leserecht nur für berechtigte Rollen (Betriebsleiter; Rollenliste im Pflichtenheft nicht festgelegt); fremder Mandant/Dienst → 404 (Kap. 05), fehlender Funktionsscope/ohne Rolle → 403; keine schreibenden Endpunkte (Änderungsversuch per API wird abgewiesen).
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Protokollschema (Detailvertrag vorab, `vertraege/schemas/ablaufprotokoll.v1.schema.json`, anzulegen); Quelle sind Ereignisse und Aktionen aus LH-I.8-AUT-001 und die Leitstellenentscheidungen aus LH-6.22-DMG-010 (Akteur, Grund, Zeit).
- **Parameter und Kennzahlen:** keine (Auswertungen über viele Vorfälle sind M8).
- **Oberfläche:** UI-07: Zeitleiste des Vorfalls für den Betriebsleiter mit Auslöser, Regelversion, Entscheidung und Folgeaktionen samt Ergebnis in zeitlicher Reihenfolge, inklusive fehlgeschlagener oder übersprungener Aktionen; Leerzustand, Ladefehler, Rechtezustand (Konkrete Element-IDs im Pflichtenheft nicht festgelegt).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-02 (Ablage des Protokolls); Rollen mit Leserecht und Protokollschema sind festzulegen (Detailvertrag vorab). Blockiert den Bau nicht; Vorbedingung ist LH-I.8-AUT-001 (WP-M3-53).

## Tests

- **Positiv:** Testvorfall aus WP-M3-53/54 wird allein aus der Zeitleiste vollständig rekonstruiert (Auslöser, Regelversion, Entscheidung, jede Folgeaktion mit Ergebnis, geordnet); fehlgeschlagene und übersprungene Aktionen erscheinen; Leitstellenentscheidung mit Akteur/Grund/Zeit erscheint.
- **Negativ:** Zugriff ohne Rolle → 403; Änderungsversuch am Protokoll per API und per Datenbank (UPDATE/DELETE) wird abgewiesen; Protokoll eines Incidents eines anderen Mandanten → 404; Protokoll enthält keine Rohpayloads mit Personendaten.
- **Konkurrierend:** Mehrere Folgeaktionen desselben Vorfalls schreiben gleichzeitig – alle Einträge bleiben erhalten und sind eindeutig geordnet (Ordnungsnummer ohne Lücken-Duplikate).
- **Referenztests:** Das WP nennt keine Katalog-ID; Testvorfall-Quelle ist der Ablauf aus WP-M3-53/54 (AT-50 in LH-6.22-DMG-010).
- Testfälle: geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::testvorfall_vollstaendig_rekonstruierbar; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::fehlgeschlagene_und_uebersprungene_aktionen_erscheinen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::zugriff_ohne_rolle_403_fremder_mandant_404; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::kein_rohpayload_mit_personendaten; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::aenderung_per_api_abgewiesen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WorkflowLogTest.kt::parallele_folgeaktionen_alle_eintraege_geordnet; geplant:db/tests/test_ablaufprotokoll.py::test_ablaufprotokoll_append_only

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
