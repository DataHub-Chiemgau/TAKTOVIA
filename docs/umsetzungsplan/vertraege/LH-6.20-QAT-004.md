# LH-6.20-QAT-004 – Negative E2E-Fälle mit definierten Status und Aufräumprozessen

- **Verantwortlicher:** Rolle QA (Abnahmetests `tests/acceptance`)
- **Arbeitspaket:** WP-M5-24
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Negative E2E-Tests behandeln Doppelbuchung, No-Show, Storno, Timeout und Fahrzeugausfall.

**Originalabnahme (unverändert):** Kritische Negativfälle führen zu definierten Status und Aufräumprozessen.

## Vertrag

- **Tabellen / DDL:** keine neue Migration; Tests laufen unter Anwendungsrollen. Geprüfte Tabellen (kernschema.sql): `capacity_hold` (`state` HELD/CONSUMED/RELEASED/EXPIRED), `booking` (`state`, `revision`), `journey`, `operation` (`status`, `lease_until`, `deadline_at`), `payment_intent` (`state`, `authorized_minor`, `captured_minor`, `refunded_minor`), `vehicle_run`, `outbox` (`published_at`), `audit_event` (`actor_ref`, `action`, `object_ref`). Mandantentrennung per RLS (`tenant_isolation`).
- **API (Kap. 05 §5.7 bzw. api/openapi.yaml):** `/v1/ride-offers`, `/v1/bookings` (Einbetreiberbuchung; Idempotenzschlüssel gemäß BKG-012), `/v1/federation/offers/{offer_id}/hold`, `/v1/federation/journeys/{journey_id}/confirm` (`409 REQUOTE_REQUIRED`), `/v1/federation/journeys/{journey_id}/cancellation-quotes` und `/cancel` (Storno), `GET /v1/operations/{operation_id}` (Fortschritt, Klärfall), `/v1/payments/provider-events/{provider}` (PSP-Ereignis, separate Signaturauthentisierung), `/v1/driver/events` (No-Show; Rückgabe `APPLIED`/`DUPLICATE`/`REVIEW_REQUIRED`), `/v1/operator/incidents` und `/v1/operator/vehicles` (Fahrzeugausfall, Sperre); abgelaufenes Angebot `409 OFFER_EXPIRED`; PSP-Simulator mit steuerbaren Fehlern (WP-M0-23).
- **Szenariogruppen (Liefergegenstand `tests/acceptance`, gegen Staging):** (a) Doppelbuchung: paralleler Griff auf den letzten Platz und doppelt gesendeter Befehl mit gleichem Idempotenzschlüssel; (b) No-Show nach `no_show_grace_min`; (c) Storno nach Disposition; (d) Timeout: Hold-Ablauf, PSP-Timeout mit späterem Abgleich, Solver-Timeout; (e) Fahrzeugausfall im Umlauf mit Sperre und Störungsablauf.
- **Tabelle Negativfall - Endstatus - Aufräumprozess - Frist:** Die konkreten Endstatus je Statusdimension und Fristen werden aus den Zustandsautomaten von M2/M3 (WP-M2-22/-24/-26/-31, WP-M3-29/-53/-54) übernommen und im Bau als Tabelle in der Szenariodokumentation fixiert; das Pflichtenheft nennt Fristen für das Aufräumen nicht (OP-17).
- **Erwartungen je Gruppe:** (a) höchstens eine bestätigte Reservierung, eindeutiger Abweisungsgrund, wiederholter Befehl liefert dieselbe Buchung; (b)/(c) definierte Status, Kapazität und Stopps freigegeben, Fahrgast und Leitstelle informiert, erfolgte Zahlung gemäß Regel behandelt; (d) abgelaufener Hold bestätigt nicht, PSP-Timeout endet nach Abgleich in genau einem Endzustand ohne zweite Belastung, Solver-Timeout liefert begründete Ablehnung; (e) Fahrzeug gesperrt, betroffene Buchungen im Ersatz- oder Stornofluss, kein Auftrag bleibt dem gesperrten Fahrzeug zugeordnet.
- **Aufräumprüfung nach jedem Szenario:** keine verwaisten `capacity_hold`, keine `operation` mit überfälliger Lease, kein `payment_intent` in unbestimmtem Zustand ohne Abgleichauftrag, keine unzugestellten `outbox`-Einträge, Fahrtenplan ohne stornierte Stopps; verbleibende Klärfälle sind als solche sichtbar, nicht als Erfolg oder Fehler verkürzt.
- **Zustandsautomat:** Verweis auf die Zustandsautomaten Buchung/Hold/Zahlung/Fahrzeug aus M2/M3 (hier keine neuen). **JSON-Schema / Ereignisse:** vorhandene `outbox`-Ereignisse; Statuswechsel mit Akteur bzw. Systemprozess in `audit_event`.
- **Parameter und Kennzahlen:** `no_show_grace_min`; Gegenprobe der Zählung in `No-Show-Rate`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`, `Kapazitätsablehnungen`.
- **Oberfläche:** keine eigene; Sicht der Leitstelle (UI-07) und Fahrgast-Benachrichtigung werden als Ergebnis geprüft.
- **Entscheidungen:** D-03 (Teil PSP-Auswahl): ohne Beschluss nur gegen PSP-Simulator, blockiert nur die Produktivschaltung. D-11 (Notbetriebsprozess Fahrzeugausfall) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-17 (Bedeutung von „Timeout“ ist nicht bestimmt: hier ausgelegt als Hold-Ablauf, PSP, Solver; Client-Timeout nicht im Umfang; Aufräumfristen fehlen, sie werden mit den Zustandsautomaten festgelegt). Nicht im Umfang: Partner-Timeouts und Kompensation über Betreibergrenzen (PH-AT-007, PH-AT-008, M6); Infrastrukturausfälle (WP-M5-16). Blockiert den Bau nicht.

## Tests

- **Positiv:** Kontrolle: der Referenzfall aus WP-M5-23 (QAT-003) läuft unmittelbar nach den Negativszenarien unverändert grün; jede Szenariogruppe endet im definierten Endstatus, Aufräumprüfung ohne Befund, für zwei Mandanten ausgeführt; Statuswechsel in `audit_event` nachvollziehbar.
- **Negativ:** die Szenarien (a) bis (e) selbst; gleicher Idempotenzschlüssel mit abweichendem Request liefert Konflikt ohne zweite Ausführung (PH-AT-011); PSP-Timeout mit später eintreffendem identischem Ereignis ohne Doppelbelastung (AT-15); Aufräumprozess eines Mandanten greift nie auf Objekte des anderen zu.
- **Konkurrierend (Kapazität, Geld):** 100 parallele Bestätigungen mit unterschiedlichen fachlichen Anfragen auf den letzten Platz (PH-AT-004: höchstens eine neue bestätigte Sitzreservierung, keine negative Belegung, alle Abweisungen mit eindeutigem Grund; Wiederholung mit belegtem Rollstuhlplatz bei freiem Sitz); Storno parallel zum Check-in; PSP-Webhook parallel zum Abgleichlauf (genau ein Endzustand, keine zweite Belastung).
- **Referenztests:** AT-03, AT-07, AT-08, AT-09, AT-15, PH-AT-004, PH-AT-011 (auf dem Abnahme-Build in Staging wiederholt).
- Testfälle: AT-03; AT-07; AT-08; AT-09; AT-15; PH-AT-004; PH-AT-011; geplant:tests/acceptance/test_negative_e2e.py::test_doppelbuchung_letzter_platz_hoechstens_eine_bestaetigung; geplant:tests/acceptance/test_negative_e2e.py::test_psp_timeout_genau_ein_endzustand_ohne_zweite_belastung; geplant:tests/acceptance/test_negative_e2e.py::test_no_show_nach_wartefrist_status_kapazitaet_freigegeben; geplant:tests/acceptance/test_negative_e2e.py::test_storno_nach_disposition_stopps_freigegeben_zahlung_regelkonform; geplant:tests/acceptance/test_negative_e2e.py::test_hold_ablauf_bestaetigt_nicht; geplant:tests/acceptance/test_negative_e2e.py::test_solver_timeout_begruendete_ablehnung; geplant:tests/acceptance/test_negative_e2e.py::test_gleicher_schluessel_anderer_request_konflikt_ohne_zweite_ausfuehrung; geplant:tests/acceptance/test_negative_e2e.py::test_negativfaelle_zwei_mandanten_ohne_fremdzugriff; geplant:tests/acceptance/test_negative_e2e.py::test_fahrzeugausfall_kein_auftrag_auf_gesperrtem_fahrzeug; geplant:tests/acceptance/test_negative_e2e.py::test_aufraeumpruefung_ohne_befund_nach_jedem_szenario

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

Zweitprüfung erforderlich.
