# LH-6.6-DRV-001 – Dienstanmeldung und Schicht-/Fahrzeugzuordnung

- **Verantwortlicher:** Rolle Fahrer-Backend (Modul `staff-time`, Run-Lesesicht über Modul-API `booking-dispatch`; ein Modul `driver` gibt es nicht, Zuschnitt OP-07)
- **Arbeitspaket:** WP-M3-25
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer kann sich authentifizieren und seinem gültigen Dienst sowie Fahrzeug zuordnen.

**Originalabnahme (unverändert):** Ohne gültige Schicht werden keine ausführbaren Aufträge freigegeben.

## Vertrag

- **Tabellen / DDL:** `staff_shift` (V1: `driver_ref`, `vehicle_id`, `starts_at`, `ends_at`, `timezone`, `roster_revision` NOT NULL, `state`, CHECK `ends_at>starts_at`) mit Zustandsübergang geplant → angetreten; `vehicle_run` (`shift_id`, `vehicle_id`, `revision`, `state`). RLS über `tenant_id` (V1). Keine neue Tabelle in diesem Vertrag; die Zusatztabellen aus WP-M3-02 bleiben unberührt. Invariante (Kap. 04 §4.5, Zeile `vehicle`/`staff_shift`/`resource_release`): Einsatz nur mit gültiger Schicht; der Dienstantritt allein ist keine Einsatzfreigabe (`resource_release`, WP-M3-09).
- **API:** `GET /v1/driver/shifts/{id}/run` (Fahrerrolle): 200 nur bei gültiger, angetretener, eigener Schicht mit Aufträgen; keine gültige Schicht bzw. kein Auftrag → leere Liste mit Leerzustand; nicht-eigene oder fremde Schicht-ID → 404 (einheitlich, auch bei fremdem Mandant); 401 ohne Sitzung, 403 ohne Fahrerrolle oder bei gesperrter Sitzung. `GET /v1/me` liefert Rolle und Mandant. In `api/driver.yaml` steht bisher ausschließlich `POST /v1/driver/events`; weder `GET /v1/driver/shifts/{id}/run` noch `/v1/me` noch der Pfad für den Dienstantritt (Zuordnung Fahrer ↔ Schicht ↔ Fahrzeug bestätigen) sind dort enthalten und werden vor Codebeginn ergänzt (Antritt idempotent über `Idempotency-Key`; ein `vehicle_id`, das nicht dem der Schicht entspricht, wird abgewiesen; 409 bei paralleler Bestätigung eines zweiten Geräts mit abweichendem Fahrzeug). Ein `tenant_id` im Request erzeugt keine Rechte.
- **Zustandsautomat:** [zustaende/staff_shift.md](zustaende/staff_shift.md) (Zustände geplant, angetreten, beendet, entzogen; Auslöser Dienstantritt, Dienstende, Entzug der Zuweisung; Fehlerfälle: Antritt außerhalb der Toleranz, fremde Schicht).
- **JSON-Schema / Ereignisse:** Einträge in `audit_event` für Dienstantritt und Dienstende mit Akteur, Gerät und Zeit; Arbeitszeiterfassung bleibt getrennt (Kap. 06 §6.7).
- **Parameter und Kennzahlen:** keine. Die Toleranz für den Antritt vor Schichtbeginn ist kein Katalogparameter in `kataloge.csv` (siehe Offene Punkte).
- **Oberfläche:** UI-09 Driver Dienststart (Text „Nächster Dienst 06:00–14:00 · Fahrzeug A-12"; Fahrzeugfreigabe getrennt von der Arbeitszeit; Zustände Offline, Mangel, andere Fahrzeugrevision aus Kap. 06); Leerzustand der Stoppliste „keine gültige Schicht"; Zustand gesperrte Sitzung.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-07 (Modul-/Meilensteinzuschnitt Schicht; `staff_shift.roster_revision` ist NOT NULL, obwohl `roster_revision` erst in M4 entsteht; vor Codebeginn Platzhalterregel festlegen). Die Toleranz für den Antritt vor Schichtbeginn (WP-M3-25 „Detailverträge vorab") ist im Pflichtenheft nicht festgelegt und vor Codebeginn der Server-Prüfung zu bestimmen. Fahrerkonto/OIDC aus WP-M3-24, `vehicle_run` aus WP-M2-16.

## Tests

- **Positiv:** Fahrer mit eigener, gültiger Schicht meldet sich an, tritt Dienst an und erhält den Run seines Fahrzeugs; `audit_event` für Antritt und Dienstende vorhanden (Akteur, Gerät, Zeit; Spalten `actor_ref`, `action`, `object_ref`, `occurred_at`); Arbeitszeiterfassung unberührt.
- **Negativ:** keine Schicht, abgelaufene Schicht (`ends_at` überschritten), Schicht nach Entzug der Zuweisung (keine Aufträge), fremde Schicht-ID und fremder Mandant (404), Konto ohne Fahrerrolle (403), gesperrte Sitzung (kein Antritt), Antritt mit einem Fahrzeug, das nicht das der Schicht ist (abgewiesen), Authentifizierung nur über das Fahrerkonto (OIDC, WP-M3-24), `tenant_id` im Request ohne Wirkung; Dienstantritt ohne `resource_release` liefert keine ausführbaren Aufträge.
- **Konkurrierend:** zwei Geräte desselben Fahrers treten gleichzeitig an, genau ein konsistenter Zustand; Entzug der Zuweisung parallel zu einem Run-Abruf, danach keine Auslieferung.
- **Referenztests:** kein Katalogfall im WP genannt.
- Umsetzung WP-M3-25 (Abweichungen vom Vorabvertrag): Dienstantritt und -ende laufen über die Sammelpfade `POST /v1/driver/shift-starts` und `POST /v1/driver/shift-ends` (Idempotency-Key, ohne If-Match laut Linterregel für Pfade ohne Variable), die eigenen Schichten über `GET /v1/driver/shifts` (alle in `api/driver.yaml`); `GET /v1/driver/shifts/{id}/run` liefert für eine eigene, aber abgelaufene, entzogene, beendete oder nicht angetretene Schicht 200 mit Leerzustand (`shift_valid` false, keine Stopps, `shift_state`), für fremde Schichten 404. Die Antrittstoleranz ist die Planungsannahme 30 Minuten (`StafftimeSettings`, `taktovia.stafftime.shift-start-tolerance-minutes`), Zustände in [zustaende/staff_shift.md](zustaende/staff_shift.md). Die Stoppliste selbst liefert WP-M3-26 über den Port `DriverRunSource`; der Server fragt ihn nur bei gültiger, angetretener, freigegebener Schicht. Weitere Tests: `backend/app/src/test/kotlin/de/taktovia/app/driver/DriverShiftApiTest.kt` (HTTP mit Test-Issuer), `db/tests/test_m3_25_dienstantritt.py`; die Flutter-Gegenstelle `mobile/driver/lib/shift/shift_start.dart` mit `test/shift/shift_start_test.dart` ist nicht ausgeführt.
- Testfälle: geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::ohne_gueltige_schicht_keine_auftraege; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::abgelaufene_schicht_und_entzug_keine_auftraege; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::fremde_schicht_ergibt_404; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::dienstantritt_ohne_resource_release_keine_auftraege; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::konto_ohne_fahrerrolle_oder_gesperrte_sitzung_kein_antritt; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::antritt_schreibt_audit_event; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::zwei_geraete_treten_gleichzeitig_an; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftStartTest.kt::entzug_parallel_zu_run_abruf_keine_auslieferung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
