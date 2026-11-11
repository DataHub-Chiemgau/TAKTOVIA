# LH-6.8-FLT-002 – Fahrzeugpool-Zuordnung zu Diensten

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul `fleet-safety`, Port zu `booking-dispatch`)
- **Arbeitspaket:** WP-M3-06
- **Status:** Bereit (unter Planungsannahme PA-02, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeugpool kann zeitlich und geografisch einem oder mehreren geeigneten Diensten zugeordnet werden.

**Originalabnahme (unverändert):** Ein Fahrzeug erhält nur Fahrten aus aktuell zugeordneten Diensten.

## Vertrag

- **Tabellen / DDL:** `vehicle_assignment`: In V1 nicht vorhanden; Kap. 04 §4.4 (DRV/FLT) nennt nur den Namen, WP-M1-34 legt die Tabelle als Stammteil für Fahrzeugrollen/Nutzungsintervalle an (Überlappungsverbot je Rolle und Fahrzeug), WP-M3-02 erweitert sie nur additiv. Vorgesehene M3-Spalten: `tenant_id`, `id`, `vehicle_id`, `service_id`, optional `service_area_id`, `valid_from` (inklusive) / `valid_to` (exklusiv, rechts offen). Fremdschlüssel `(tenant_id, vehicle_id) -> vehicle`, `(tenant_id, service_id) -> service`, optional `(tenant_id, service_area_id) -> service_area` (V1: `service_area` mit `service_id`, `valid_from`, `valid_to`; WP-M2-04); Mandant über zusammengesetzte Schlüssel erzwungen, Zuordnung zu Dienst oder Gebiet eines anderen Mandanten nicht speicherbar; RLS wie V1. Wichtig: Das Überlappungsverbot aus WP-M1-34 gilt je Rolle, es darf die gleichzeitige Zuordnung desselben Fahrzeugs zu mehreren Diensten nicht verhindern; ob Dienstzuordnung und Rollenzuordnung dieselbe Tabelle oder getrennte Tabellen sind, ist offen (siehe Offene Punkte).
- **API:** `/v1/operator/vehicles` Teilressource Dienstzuordnungen (anlegen, beenden, lesen; Pfad im OpenAPI-Entwurf). Modul-API/Port „für Dienst d zum Zeitpunkt t zulässige Fahrzeuge" in `fleet-safety`, gelesen über den Leseport `ResourceAvailability` (WP-M2-02) vom Fahrzeugkandidatenfilter (WP-M2-15). Hold-Commit prüft die Zuordnung erneut. Fremder Mandant: 404 `NOT_FOUND`.
- **Zustandsautomat:** keiner; Zuordnung wirkt zeitlich über das Intervall `[valid_from, valid_to)`. Beim Ende einer Zuordnung bleiben bereits zugesagte Fahrten sichtbar und erzeugen einen Hinweis an die Leitstelle (kein stilles Entfernen).
- **JSON-Schema / Ereignisse:** Outbox-Ereignis Zuordnung angelegt/beendet; Audit im `audit_event` (Akteur, Zeitpunkt).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11 Zuordnungstabelle je Fahrzeug (Leer-, Fehler-, Rechtezustand).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-25 (Abbildung „geografisch": Bediengebiet `service_area`, Betriebshof oder eigene Geometrie; Felder von `vehicle_assignment` sind im Pflichtenheft nur umrissen). Der Vertrag sieht vorläufig `service_area` als Gebietsbezug vor; Bau der DDL und des Gebietsfilters bis zur Klärung blockiert, der zeitliche Teil nicht. Zusätzlich offen und im WP nicht festgelegt: (a) was an einer Fahrt im Gebiet liegen muss (Abholung, Ziel oder beides) und (b) wie die „Eignung" eines Dienstes für das Fahrzeug (Originaltext: „geeigneten Diensten") geprüft wird (hängt an OP-14, Wertekatalog Fahrzeugtyp/Ausstattung); (c) Namens- und Zweckkollision `vehicle_assignment` zwischen WP-M1-34 (Rollen/Nutzungsintervalle, OP-26 des M1-Dokuments) und WP-M3-06 (Dienstzuordnung). Aus diesen Gründen bleibt der Vertrag in Arbeit.

- **Planungsannahme:** PA-02 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

## Tests

- **Positiv:** Fahrzeug Dienst A zugeordnet erhält Fahrt aus A; Fahrzeug gleichzeitig Dienst A und B zugeordnet erhält Fahrten aus beiden; Zuordnung anlegen und beenden schreibt `audit_event` und Outbox-Ereignis.
- **Negativ:** Fahrzeug von A erhält keine Fahrt aus B; Fahrzeug ohne jede Zuordnung erhält keine Fahrt; nach Ablauf (`valid_to` exklusiv, genau zum Zeitpunkt `valid_to` keine neue Fahrt, davor schon) keine neue Fahrt aus A; Zuordnung noch nicht begonnen (vor `valid_from`) keine Fahrt; außerhalb des zugeordneten Gebiets keine Fahrt, auch innerhalb des Intervalls (zeitlich und geografisch unabhängig wirksam, abhängig von OP-25); Zuordnung zu Dienst oder Gebiet eines anderen Mandanten nicht speicherbar; Fahrzeug fremder Mandant: 404; bereits zugesagte Fahrt bleibt beim Ende der Zuordnung sichtbar mit Hinweis an die Leitstelle und wird nicht still entfernt; deaktiviertes Fahrzeug (FLT-001) bleibt trotz Zuordnung ohne neue Fahrt.
- **Konkurrierend:** Zuordnung endet parallel zu einem Hold: der Commit prüft die Zuordnung erneut, keine Zusage nach Ende (beide Serialisierungen); zwei gleichzeitige Anlagen für dasselbe Fahrzeug und verschiedene Dienste werden beide gespeichert (kein Verlust durch ein Überlappungsverbot je Fahrzeug).
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehiclePoolAssignmentTest.kt::fahrzeug_erhaelt_nur_fahrten_zugeordneter_dienste; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehiclePoolAssignmentTest.kt::zuordnung_endet_parallel_zu_hold; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehiclePoolAssignmentTest.kt::valid_to_exklusiv_und_mehrere_dienste; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehiclePoolAssignmentTest.kt::beendete_zuordnung_zugesagte_fahrt_bleibt_mit_hinweis; geplant:db/tests/test_fahrzeugzuordnung.py::test_zuordnung_fremder_mandant_nicht_speicherbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
