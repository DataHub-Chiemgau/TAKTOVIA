# LH-6.6-DRV-007 – Fahrzeug- und Dienststatus

- **Verantwortlicher:** Rolle Fahrer-Backend (Modul `booking-dispatch`, dem `vehicle_run` gehört, mit Statusmeldung aus `staff-time`) mit Disponierbarkeits-Modul-API; ein Modul `driver` gibt es nicht (OP-07)
- **Arbeitspaket:** WP-M3-31
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeug- und Dienststatus unterscheiden Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung und Ende.

**Originalabnahme (unverändert):** Nicht verfügbares Fahrzeug wird nicht neu disponiert.

## Vertrag

- **Tabellen / DDL:** `vehicle_run.state` (V1, `text NOT NULL`) bzw. Statusfeld an `staff_shift.state` laut Bau (Entscheidung im Zustandsautomaten); Statushistorie als Zusatztabelle aus WP-M3-02 (Vorstatus, Neustatus, Auslöser Fahrer/System/Leitstelle, Zeit, `shift_id`, `tenant_id`; RLS, nicht überschreibbar). Sechs Werte: Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung, Ende (technische Bezeichner im Zustandsautomaten festlegen).
- **API:** Statusmeldung des Fahrers: Erweiterung von `POST /v1/driver/events` oder eigener Pfad (OP-03, Änderung in `api/driver.yaml` vor Codebeginn); Statusfeld in `/v1/operator/vehicles` (`api/driver.yaml` enthält bisher nur `POST /v1/driver/events`; beide Pfade vor Codebeginn prüfen bzw. ergänzen). Fahrer meldet nur den Status der eigenen angetretenen Schicht (sonst 404). Unzulässiger Übergang (z. B. Ende nach Anfahrt ohne neuen Dienstantritt) wird mit 409/422 abgewiesen. Die Modul-API „disponierbar" kombiniert Status und Readiness (`resource_release`) und wird vom Fahrzeugkandidatenfilter der Disposition (WP-M2-15) gefragt.
- **Zustandsautomat:** `vertraege/zustaende/fahrzeug_dienststatus.md` (zu erstellen): Übergänge und Auslöser; Anfahrt und Wartezeit aus Fahrerereignissen (DRV-005) abgeleitet, Pause/Störung/Ende durch den Fahrer; Wirkung von Pause auf bereits zugesagte Stopps festzulegen.
- **JSON-Schema / Ereignisse:** Outbox-Ereignis bei Status Störung, das den Störungsablauf (WP-M3-53) eröffnet.
- **Parameter und Kennzahlen:** Datengrundlage für die KPI `Fahrzeugverfügbarkeit`.
- **Oberfläche:** UI-10 Statusumschaltung; UI-07 Status je Fahrzeug; Hinweis, dass offline gemeldete Störung/Ende erst ab Serverempfang auf die Disponierbarkeit wirkt.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-03 (Statusmeldungen im `DriverEvent` nicht vorgesehen; Pfad vor Codebeginn festlegen). Die Wirkung von „Pause" auf bereits zugesagte Stopps ist im Pflichtenheft nicht festgelegt und vor Codebeginn des Zustandsautomaten zu bestimmen (Vorschlag fail-closed: Zusagen nicht still entziehen, Klärfall an die Leitstelle). Pausenplanung und Arbeitszeitrecht sind M4.

## Tests

- **Positiv:** Durchlauf Verfügbarkeit, Anfahrt, Wartezeit, Verfügbarkeit, Pause, Ende; alle sechs Status unterscheidbar gespeichert und in UI-10 und UI-07 sichtbar; Historie mit Auslöser, Zeit, Vorstatus.
- **Negativ:** unzulässiger Übergang abgewiesen (z. B. Ende nach Anfahrt ohne neuen Dienstantritt); Zuweisung an ein Fahrzeug in Pause, Störung oder Ende erfolgt nicht; fremde Schicht meldet Status (404); Störung erzeugt das Outbox-Ereignis.
- **Konkurrierend:** Statuswechsel auf Störung parallel zu einer neuen Zuweisung ergibt keine Zusage; zwei gleichzeitige Statusmeldungen derselben Schicht ergeben genau einen konsistenten Endstatus; offline erfasste Statusmeldung wird nach Sync genau einmal wirksam, Störung/Ende wirken erst ab Serverempfang.
- **Referenztests:** kein Katalogfall im WP genannt.
- Testfälle: geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::durchlauf_sechs_status_unterscheidbar_gespeichert; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::statushistorie_mit_ausloeser_zeit_vorstatus; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::pausierendes_fahrzeug_nicht_disponiert; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::stoerung_und_ende_nicht_disponiert; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::unzulaessiger_uebergang_abgewiesen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::fremde_schicht_meldet_status_ergibt_404; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::stoerung_erzeugt_outbox_ereignis; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::stoerung_parallel_zu_zuweisung_keine_zusage; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::offline_statusmeldung_genau_einmal_wirksam_ab_serverempfang; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/VehicleServiceStatusTest.kt::zwei_gleichzeitige_statusmeldungen_ein_endstatus; geplant:web/operator/src/fahrzeugstatus/VehicleStatus.test.tsx::ui07_zeigt_alle_sechs_status; geplant:mobile/driver/test/dienststatus_test.dart::ui10_statusumschaltung_und_offline_hinweis

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
