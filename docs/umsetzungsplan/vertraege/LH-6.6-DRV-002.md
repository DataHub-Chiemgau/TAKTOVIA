# LH-6.6-DRV-002 – Stoppliste des Fahrers

- **Verantwortlicher:** Rolle Fahrer-Backend (Modul `staff-time`, Lesezugriff über Modul-API `booking-dispatch`; ein Modul `driver` gibt es nicht, Zuschnitt OP-07)
- **Arbeitspaket:** WP-M3-26
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer sieht geordnete Liste kommender Stopps mit Soll-/Prognosezeit und Fahrgastanzahl.

**Originalabnahme (unverändert):** Auftrag aus Leitstelle erscheint mit korrekter Reihenfolge.

## Vertrag

- **Tabellen / DDL:** Nur Lesezugriff auf `vehicle_run` (`revision`), `route_stop` (Sequenz eindeutig pro Runrevision, Kap. 04 §4.4 BKG) und `passenger_trip` (M2) über Modul-API; `stop_revision` für den Haltepunkt. `route_stop` und `passenger_trip` stehen in Kap. 04 §4.4, sind aber nicht in `db/migrations/V1__kernschema.sql` (V1 enthält nur `vehicle_run`, `stop_revision`); sie entstehen mit WP-M2-14/WP-M2-16, die Fahrerprojektion setzt sie voraus. Keine eigene Tabelle. RLS über `tenant_id`.
- **API:** `GET /v1/driver/shifts/{id}/run` (in `api/driver.yaml` noch nicht enthalten, siehe DRV-001) mit `ETag` = Planrevision und bedingtem Abruf (304 bei unverändert); 404 für fremde Schicht, 401/403 wie DRV-001. Das Antwortschema der Fahrerprojektion ist in `api/driver.yaml` noch nicht beschrieben und wird vor Codebeginn ergänzt: je Stopp Haltepunkt, Sollzeit, Prognosezeit oder Kennzeichen „keine Prognose", Personenzahl (Ein-/Aussteiger), Ressourcenbedarf, Planrevision; ausdrücklich keine Kontakt-, Zahlungs- oder Diagnosedaten (Kap. 05 §5.1 Minimierung). Reihenfolge = `route_stop`-Sequenz.
- **Zustandsautomat:** keiner (Lesesicht auf `vehicle_run`).
- **JSON-Schema / Ereignisse:** `vertraege/schemas/driver-run-projection.v1.schema.json` (zu erstellen).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-10 Driver Fahrt: Liste mit nächstem Handlungsschritt, Stopp, Personenzahl; Plan-Stand sichtbar; unterscheidbare Zustände Leer (kein Auftrag), Fehler (Abruf gescheitert, letzter Stand mit Zeit) und Offline; „keine Prognose" statt erfundener Zeit.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-20 (Prognosezeit vor dem ETA-Grundmodell: bis M4 nur Planzeit aus dem Routing; Bestätigung durch den Auftraggeber steht aus; kein Bau-Blocker, Vorgabe für M3: Planzeit bzw. Ausweis „keine Prognose"). Vollabnahme mit ETA-Grundmodell erst mit WP-M4-61/WP-M4-62.

## Festlegungen des Baus (WP-M3-26)

- **Ort der Tests:** `staff-time` kennt `booking-dispatch` nicht (Modulgrenze); die Projektion wird in `booking-dispatch` (`DriverRunPlans`, Lesesicht auf `vehicle_run`, `route_stop`, `stop_revision`, `passenger_trip`) gebildet und in der Anwendung (`BookingDriverRunSource`) auf `DriverRunSource` von `staff-time` abgebildet. Die Tests mit echten Plänen und Buchungen laufen deshalb in der Anwendung über HTTP (`DriverRunProjectionTest`), die Zugriffsregeln von `staff-time` in `DriverAssistanceVisibilityTest`.
- **Antwortschema:** `vertraege/schemas/driver-run-projection.v1.schema.json` (Spiegel von `DriverRun`/`DriverRunStop` in `api/driver.yaml`). Neu je Stopp (additiv): `stop_revision_id`, `boarding_count`, `alighting_count`, `resource_need` (Sitze, Rollstuhlplätze, Ausstattung der Einsteiger) und `kind` `PICKUP_DROPOFF` (Einsteiger und Aussteiger am selben Stopp); `passenger_count` = Ein- und Aussteiger zusammen.
- **Prognosezeit vor dem ETA-Grundmodell (OP-20):** `planned_at` ist die Sollzeit der Planrevision (aus dem Routing); `forecast_at` ist die Planzeit aus dem Routing, wenn die Fahrzeit zum Stopp aus dem Routing stammt (erster Stopp: Abfahrt; sonst muss der vorige Abschnitt eine Routing-Fahrzeit tragen), sonst `null` = „keine Prognose“. Es wird nie eine Zeit erfunden; die Quelle wechselt mit WP-M4-61/-62 (kein Bau-Blocker).
- **Bedingter Abruf:** `ETag` = Planrevision; `If-None-Match` mit dem aktuellen ETag liefert 304 nur bei gültiger, freigegebener Schicht mit Stopps. Leerzustand, Schichtende, Entzug und fehlende Freigabe antworten immer 200, damit der Client nie einen alten Plan behält.
- **Konsistenz:** Umlauf und Stopps werden in einer schreibgeschützten Transaktion gelesen, die Stopps stammen aus genau der gelesenen Revision (append-only), ETag und Inhalt passen immer zusammen.
- **Flutter (nicht ausgeführt):** `mobile/driver/lib/run/*` (Modell, bedingter Abruf, Zustände Liste/Leer/Fehler/Offline mit Plan-Stand, Widget `RunListScreen`); Flutter war in der Erstellungsumgebung nicht verfügbar, die Dart-Tests wurden nicht gestartet.

## Tests

- **Positiv:** Run mit drei Stopps, Reihenfolge der App gleich `route_stop`-Sequenz; von der Leitstelle neu zugewiesener Auftrag erscheint an korrekter Position; Sollzeit, Prognosezeit (bzw. Ausweis) und Personenzahl je Stopp; bedingter Abruf mit unverändertem `ETag` liefert 304; UI-10 zeigt Plan-Stand sowie Leer-, Fehler- (letzter Stand mit Zeit) und Offline-Zustand unterscheidbar.
- **Negativ:** fremde Schicht (404), anderer Mandant, Run ohne Stopps (Leerzustand), fehlende Prognose wird ausgewiesen; Projektion enthält keine Kontakt-, Zahlungs- oder Diagnosefelder (Schema-Test auf Feldliste, je Stopp nur Haltepunkt, Zeiten, Personenzahl, Ressourcenbedarf); Liste enthält ausschließlich Stopps der eigenen Schicht; `tenant_id` im Request ohne Wirkung; 401/403 ohne Sitzung bzw. Fahrerrolle.
- **Konkurrierend:** Abruf während eines Plan-Commits liefert eine konsistente Revision (ETag passt zum Inhalt); Abruf parallel zu Schichtende liefert nie Stopps einer fremden Schicht.
- **Referenztests:** kein Katalogfall im WP genannt.
- Testfälle: backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::stoppliste_folgt_route_stop_sequenz_mit_zeiten_personenzahl_und_ressourcenbedarf; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::stopp_zeigt_sollzeit_prognose_oder_keine_prognose_und_personenzahl; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::neu_zugewiesener_auftrag_an_korrekter_position; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::fremde_schicht_und_fremder_mandant_ergeben_404; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::run_ohne_stopps_leerzustand; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::bedingter_abruf_etag_304; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::projektion_ohne_kontakt_und_zahlungsdaten; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::abruf_waehrend_plan_commit_konsistent; backend/app/src/test/kotlin/de/taktovia/app/driver/DriverRunProjectionTest.kt::abruf_parallel_zu_schichtende_keine_fremden_stopps; nicht ausgeführt:mobile/driver/test/stoppliste_zustaende_test.dart::leer_fehler_offline_unterscheidbar_mit_plan_stand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
