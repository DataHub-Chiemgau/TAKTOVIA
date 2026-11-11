# LH-6.21-CHK-015 – Fahrzeugwechsel in laufender Schicht

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`, Zusammenspiel `fleet`/Schicht)
- **Arbeitspaket:** WP-M3-22
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Bei einem Fahrzeugwechsel während einer Schicht ist eine neue fahrzeugspezifische Prüfung zwingend erforderlich.

**Originalabnahme (unverändert):** Nach Wechsel bleibt das Ersatzfahrzeug gesperrt, bis seine Prüfung bestätigt wurde.

## Vertrag

- **Tabellen / DDL:** `staff_shift.vehicle_id` (V1: genau ein Fahrzeug je Schicht), `resource_release` (alte Freigabe über `revoked_at` widerrufen, neue erst nach bestätigter Prüfung; `inspection_ref NOT NULL`), `vehicle_run`, `inspection_run`; ggf. `vehicle_assignment` – Schemaentscheidung OP-08 (Wechsel ändert Schicht, erzeugt neue Schicht oder läuft über `vehicle_assignment`) ist vor Codebeginn zu treffen. Invariante: keine `resource_release` für das Ersatzfahrzeug ohne eigenen bestätigten `inspection_run` dieses Fahrzeugs (`resource_release.vehicle_id` = Fahrzeug des `inspection_run`; Freigabe und Sperrprüfung im selben Commit). `vehicle.active_block` und `safety_revision` (V1) sind Lesequelle der Sperre; Mehrfachsperrgründe sind OP-01. Tabellen `inspection_run` und `vehicle_assignment` sind in V1–V3 nicht vorhanden (`vehicle_run` und `staff_shift` sind es).
- **API:** Fahrzeugwechsel an der Schicht (Pfad im OpenAPI-Entwurf festzulegen; `/v1/driver/shifts/{id}/run` steht in Kap. 05 §5.7, nicht in `api/*.yaml`); Wechsel in fremder Schicht bzw. durch nicht berechtigten Akteur: 404/403 nach Kap. 05 §5.1; `/v1/driver/shifts/{id}/run` liefert das neue Fahrzeug und „Prüfung erforderlich"; Aufträge werden bis zur Freigabe nicht ausführbar ausgeliefert. Wechsel ohne Qualifikation für den Typ des Ersatzfahrzeugs: abgewiesen (422/409); Wechsel auf gesperrtes Fahrzeug abgewiesen. Wechsel mit Akteur, Begründung, altem und neuem Fahrzeug in `audit_event`; Prüfhistorie beider Fahrzeuge unverändert.
- **Zustandsautomat:** Prüflauf/Freigabe (`vertraege/zustaende/pruefung.md`): nach Wechsel Ersatzfahrzeug „Prüfung ausstehend" -> bestätigt -> freigegeben.
- **JSON-Schema / Ereignisse:** Ereignis Fahrzeugwechsel (Outbox, ohne Freitext).
- **Parameter und Kennzahlen:** `vehicle_readiness_require_checks` nur lesend betroffen (OP-09).
- **Oberfläche:** UI-09 – Zustand „andere Fahrzeugrevision", neue Pflichtprüfung; UI-07 – Wechseldialog mit Begründung; Altschäden des Ersatzfahrzeugs vor dessen Prüfung (WP-M3-21).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-08 (Schema des Wechsels; wer darf auslösen – Fahrer oder Leitstelle). Übergang offener Stopps auf das Ersatzfahrzeug gehört zu WP-M3-40. OP-08 blockiert den Bau des Wechselkommandos bis zur Entscheidung; Sperr- und Freigaberegel selbst sind eindeutig.

## Tests

- **Positiv:** Wechsel, neue fahrzeugspezifische Prüfung, danach Freigabe des Ersatzfahrzeugs (AT-30).
- **Negativ:** Bereitmeldung ohne neue Prüfung abgewiesen; alte `resource_release` ist nach Wechsel widerrufen (`revoked_at` gesetzt) und trägt nicht für das Ersatzfahrzeug; Aufträge werden bis zur Freigabe nicht ausführbar ausgeliefert; Wechsel in fremder Schicht/fremdem Mandanten (404); Prüfhistorie beider Fahrzeuge unverändert, Wechsel im `audit_event` mit Akteur, Begründung, altem und neuem Fahrzeug; Prüfung des alten Fahrzeugs gilt nicht für das Ersatzfahrzeug (keine Vorabfreigabe); Wechsel auf gesperrtes Fahrzeug; Wechsel ohne Qualifikation für den Fahrzeugtyp; Ersatzfahrzeug nicht disponierbar bis Prüfung bestätigt.
- **Konkurrierend:** Wechsel parallel zu einer Zuweisung auf das Ersatzfahrzeug – keine Zusage vor der Prüfung; Prüfabschluss parallel zu neuer Sperre – keine Freigabe; zwei gleichzeitige Wechsel derselben Schicht – genau ein Ersatzfahrzeug.
- **Referenztests:** AT-30 (verwandt AT-50, OP-08).
- Testfälle: AT-30; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::ersatzfahrzeug_bleibt_bis_pruefung_gesperrt; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::bereitmeldung_ohne_neue_pruefung_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::pruefung_altes_fahrzeug_gilt_nicht_fuer_ersatzfahrzeug; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::wechsel_ohne_qualifikation_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::wechsel_auf_gesperrtes_fahrzeug_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::alte_freigabe_widerrufen_und_wechsel_im_audit; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::wechsel_parallel_zu_zuweisung_keine_zusage_vor_pruefung; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::pruefabschluss_parallel_zu_neuer_sperre_keine_freigabe; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/VehicleSwitchTest.kt::zwei_gleichzeitige_wechsel_genau_ein_ersatzfahrzeug; geplant:db/tests/test_vehicle_switch.py::test_keine_freigabe_fuer_ersatzfahrzeug_ohne_eigene_pruefung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
