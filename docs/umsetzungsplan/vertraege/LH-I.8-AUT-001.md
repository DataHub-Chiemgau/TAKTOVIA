# LH-I.8-AUT-001 – Regelbasierter Workflow: Incident bei Fahrzeugausfall

- **Verantwortlicher:** Rolle Dispatch/Leitstelle (Workflow-Konsument; Modulzuordnung offen, OP-07)
- **Arbeitspaket:** WP-M3-53
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein regelbasierter Workflow verarbeitet Ereignisse wie Fahrzeugausfall, Schaden, Ablehnung und kritische Verspätung.

**Originalabnahme (unverändert):** Ein Fahrzeugausfall löst genau einen Incident und eine Ersatzfahrzeugsuche aus.

## Vertrag

- **Tabellen / DDL:** Incident-Ablage (neu; Kap. 04 §4.4 enthält keine Incident-Tabelle, OP-02; Arbeitsname in WP-M3-01), Workflow-Regelrevision (unveränderlich, Trigger `reject_mutation()`), `inbox` (V1: `tenant_id`, `consumer`, `event_id`, `payload_sha256`, `received_at`, PK `(tenant_id,consumer,event_id)`) zur Deduplikation wiederzugestellter Ereignisse, `operation` (V1: `kind`, `status`, `revision`, `deadline_at`, …; WP-M1-15) für die Ersatzfahrzeugsuche. Achtung: `operation.journey_id` ist `NOT NULL`; eine Suche je Fahrzeugausfall ohne Journey-Bezug braucht eine Festlegung (Teil von OP-02). Eindeutigkeit des Incidents über Dedupe-Schlüssel (Fahrzeug, Ausfallursache, offener Zeitraum) per Unique-Constraint/partiellem Unique-Index; alle Tabellen mit Mandantenschlüssel und RLS `tenant_isolation`.
- **API:** `GET /v1/operator/incidents` (Liste, Listenregeln Kap. 05) und `GET /v1/operator/incidents/{id}` (Detail); nur Incidents berechtigter Dienste, sonst 404; fehlender Scope 403.
- **Zustandsautomat:** Incident-Zustandsautomat (Detailvertrag vorab, noch zu erstellen: `vertraege/zustaende/incident.md`); Mindestzustände: offen (auch bei fehlgeschlagener Suche), Ersatz gesucht, abgeschlossen.
- **JSON-Schema / Ereignisse:** Konsumierte Ereignisse: „Fahrzeug gesperrt“ (WP-M3-08), „Störung gemeldet“ (WP-M3-31), „sicherheitskritischer Schaden“; als Ereignistypen registriert: „Schaden“, „Ablehnung“, „kritische Verspätung“. Erzeugte Outbox-Ereignisse: „Incident eröffnet“, „Ersatzfahrzeugsuche gestartet“. Regelformat als JSON-Schema `vertraege/schemas/workflow-regel.v1.schema.json` (anzulegen); Regelrevision bestimmt die Folgeaktionen neuer Ereignisse, nicht bereits verarbeiteter.
- **Parameter und Kennzahlen:** keine eigenen; `vehicle_unavailability_alert_min` gehört zum Alarm (WP-M3-52).
- **Oberfläche:** UI-07: Incident in der Arbeitsliste mit Fahrzeug, Ursache und Verknüpfungen; Leerzustand, Ladefehler mit Datenstand.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-02 (Incident-Ablage), OP-24 (Regelumfang für „Ablehnung“ und „kritische Verspätung“ in P1: ohne hinterlegte Regel nur protokollierter Eintrag „keine Aktion“); Regelformat, Dedupe-Schlüssel und Incident-Zustandsautomat als Detailvertrag; Verhalten bei unbekanntem Ereignistyp (abweisen/protokollieren) nicht festgelegt, Arbeitsannahme: protokollieren und abweisen. Inhalt der Ersatzsuche siehe LH-6.22-DMG-010. Blockiert den Bau nicht, OP-02 ist aber vor dem DDL festzulegen (WP-M3-01).

## Tests

- **Positiv:** Sperre eines Fahrzeugs → genau ein Incident, genau eine Ersatzfahrzeugsuche (`operation`); geänderte Regelrevision wirkt auf neue, nicht auf bereits verarbeitete Ereignisse; Ereignistypen „Schaden“, „Ablehnung“, „kritische Verspätung“ werden nach wirksamer Regelrevision verarbeitet.
- **Negativ:** Ereignis für fremden Mandanten wird nicht verarbeitet; unbekannter Ereignistyp wird abgewiesen/protokolliert; Ereignistyp ohne Regel → Eintrag „keine Aktion“, kein Fehler; schlägt die Suche fehl, bleibt der Incident offen und sichtbar; Incident eines fremden Dienstes → 404; Regelrevision ist unveränderlich (Update/Delete abgewiesen).
- **Konkurrierend:** Sperre, Schadenmeldung und Statusmeldung „Störung“ für dasselbe Fahrzeug treffen nahezu gleichzeitig ein (drei Ereignisse parallel) sowie 100-fache Wiederzustellung desselben Ereignisses (`inbox`) → ein Incident, eine Suche.
- **Referenztests:** AT-09 (Fahrzeugausfall); Testvorfall-Bezug AT-50 über LH-6.22-DMG-010
- Testfälle: AT-09; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::fahrzeugausfall_ergibt_genau_einen_incident_und_eine_suche; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::drei_ereignisse_parallel_und_100_wiederzustellungen_ein_incident; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::ereignis_ohne_regel_protokolliert_keine_aktion; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::unbekannter_ereignistyp_und_fremder_mandant_nicht_verarbeitet; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::regelrevision_wirkt_nur_auf_neue_ereignisse; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::fehlgeschlagene_suche_incident_bleibt_offen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/IncidentWorkflowTest.kt::incident_fremder_dienst_404; geplant:db/tests/test_workflow_regelrevision.py::test_regelrevision_unveraenderlich

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
