# LH-6.24-TIM-004 – Trennung von Schicht-Soll und Arbeitszeit-Ist

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-09
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schicht-Sollzeit und Arbeitszeit-Istwert werden getrennt gespeichert; Planwerte ersetzen nicht stillschweigend tatsächlich erfasste Zeiten.

**Originalabnahme (unverändert):** Fahrt- und Schichtplanung verändern vorhandene Ist-Zeiten nicht.

## Vertrag

- **Tabellen / DDL:** getrennte Tabellen `staff_shift` (Soll: `starts_at`, `ends_at`, `timezone`, `roster_revision`, `state`; V1) und `time_event` (Ist; neue Migration, siehe TIM-002). Keine Fremdschlüssel oder Trigger, die Plan- oder Dispositionsänderungen auf `time_event` durchschlagen lassen. DML-Rechte der Modulzugänge beschränkt (Kap. 04 §4.6, `db/migrations/R__runtime_grants.sql`): Dienstplan- und Dispositionspfade haben keine Schreibrechte auf `time_event`. Stand im Repository: `db/migrations/R__runtime_grants.sql` kennt nur die eine Rolle `taktovia_runtime`; `time_event` erhält dort nur SELECT,INSERT (kein UPDATE/DELETE). Eine Rollentrennung zwischen Dienstplan- und Stempelpfad innerhalb des Moduls `staff-time` ist nicht festgelegt; bis dahin beweist der Architekturtest die Trennung der Schreibpfade.
- **API:** `GET /v1/time-events` Lesesicht „Soll/Ist je Tag“ je Person als reine Leseprojektion aus `staff_shift` (Soll) und `time_event` (Ist); fehlende Ist-Buchung wird gekennzeichnet (Schema der Sicht ist vorab zu definieren, siehe Offene Punkte). Statuscodes 200, 401, 403, 404.
- **Zustandsautomat:** keiner (Projektion).
- **JSON-Schema / Ereignisse:** keine neuen.
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** Zeitübersicht mit Soll und Ist je Tag; Vorbereitungszeit vor der Fahrzeugprüfung als erfasste Arbeitszeit; Kennzeichnung „keine Ist-Buchung“ (Bereich Soll/Ist in UI-13 „Eigene Zeit/Abwesenheit“, WP-M4-20; Zustand „fehlende Buchung“ nach Kap. 06 §6.8).
- **Entscheidungen:** keine.
- **Offene Punkte:** Schema der Soll/Ist-Sicht und Kennzeichnung fehlender Ist-Buchung als Detailvertrag vorab (laut WP-M4-09), kein Pflichtenheft-Offener-Punkt; blockiert den Bau nicht. Zeitkonten, Rundungs- und Pausenlogik sind nicht Umfang (TIM-011, M7).

## Tests

- **Positiv:** Änderung oder Neuveröffentlichung einer Schicht und Umplanung von Fahrten (Planrevision) lassen vorhandene Ist-Zeiten unverändert (Originalabnahme); Soll/Ist-Auswertung für AT-45 zeigt Vorbereitungszeit als erfasste Arbeitszeit.
- **Negativ:** Schreibversuch UPDATE/DELETE auf `time_event` unter `taktovia_runtime` scheitert an Rechten (SQL); falls ein eigener Zugang für den Dienstplanpfad festgelegt wird, scheitert auch dessen INSERT; Architekturtest: Dienstplan- und Dispositionsbefehle haben keinen Schreibzugriff auf `time_event`; fehlende Ist-Buchung wird gekennzeichnet, nicht mit Planwert gefüllt.
- **Konkurrierend:** Neuveröffentlichung einer Schicht parallel zu einem Stempel derselben Person – beide Wirkungen bleiben erhalten, kein Ist-Wert geht verloren (Datentrennung).
- **Referenztests:** AT-45.
- Testfälle: AT-45; geplant:db/tests/test_time_soll_ist_trennung.py::test_runtime_rolle_ohne_update_delete_auf_time_event; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/SollIstTrennungTest.kt::dienstplan_und_dispositionsbefehle_ohne_schreibzugriff_auf_time_event; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/SollIstTrennungTest.kt::fehlende_ist_buchung_gekennzeichnet_nicht_mit_planwert_gefuellt; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/SollIstTrennungTest.kt::umplanung_von_fahrten_aendert_ist_zeiten_nicht; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/SollIstTrennungTest.kt::neuveroeffentlichung_parallel_zu_stempel_beide_erhalten; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/SollIstTrennungTest.kt::neuveroeffentlichung_aendert_ist_zeiten_nicht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
