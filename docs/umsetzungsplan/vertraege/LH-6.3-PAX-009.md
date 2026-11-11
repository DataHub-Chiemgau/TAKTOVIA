# LH-6.3-PAX-009 – UI-04 Selbststorno

- **Verantwortlicher:** Rolle Passenger-PWA (Web `web/passenger`) mit Rolle Buchung/Disposition (Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-46
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Nutzer können Buchungen nach konfigurierten Regeln selbst stornieren.

**Originalabnahme (unverändert):** Nach Stornierung sind Status, Erstattungshinweis und freie Kapazität konsistent.

## Vertrag

- **Tabellen / DDL:** keine eigene Tabelle; nutzt `booking` (`state`, `revision`), `capacity_hold` (Freigabe), Statushistorie (WP-M2-25), `payment_intent` (`authorized_minor`, `captured_minor`, `refunded_minor`; CHECK `refunded_minor<=captured_minor`) und `refund_case`/Void aus WP-M2-31. Konsistenzvergleich Status in UI = `booking.state` = Historie = Kapazität = Zahlungsstand ist ein automatisierter Test, kein neues Schema.
- **API:** `POST /v1/bookings/{id}/cancel` (Pfadvorschlag, OP-21) mit `Idempotency-Key` (Pflicht), `If-Match` (fehlend 428, veraltet 412 `REVISION_MISMATCH`) und Grundcode; Antwort mit Status und Erstattungshinweis; Ablehnung außerhalb der Regel mit definiertem Code (aus WP-M2-26); `GET /v1/bookings/{id}` mit ETag; 404 bei fremder Buchung; `409 OPERATION_IN_PROGRESS`/`IDEMPOTENCY_CONFLICT` nach WP-M2-24.
- **Zustandsautomat:** `booking.state` (WP-M2-01, OP-4): bestätigt → storniert; Zahlung nach Kap. 03 §4.2 (Void bei nur autorisiert, Erstattung bei captured); Erstattungshinweis in zwei Stufen nach Kap. 06 §6.4: „Erstattung beauftragt“ bis zur PSP-Bestätigung, danach „erstattet“.
- **JSON-Schema / Ereignisse:** Ereignisse „Buchung storniert (mit Grund)“, „Planrevision geändert“, „Erstattung“ (Katalog WP-M2-01); UI zeigt keine Rohdaten daraus.
- **Parameter und Kennzahlen:** Stornofristen und zulässige Gründe je Dienst (nicht im Katalog `kataloge.csv`, neue Dienstparameter, WP-M2-26/OP-23); Kennzahl Datenbasis `Buchungsstornoquote Fahrgast`.
- **Oberfläche:** UI-04 Stornoaktion mit Anzeige der geltenden Regel (Frist, erwartete Erstattung), ausdrücklicher Bestätigung und Grundauswahl; Ergebnisansicht Status „storniert“ mit Erstattungshinweis; Zustand „Storno nicht mehr möglich“ mit Kontaktmöglichkeit; Schaltfläche gegen Doppelklick gesperrt; Fehlerzustände 409/412/Netzabbruch; Aktion nur für eigene Buchungen.
- **Entscheidungen:** keine für den Bau; D-05 (freigegebene Stornoregeln des Piloten) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-23 (Stornofristen/-gründe ohne Katalogparameter; P1-Folge nach Fristablauf: Arbeitsannahme Ablehnung ohne Gebühr, Gebührenregeln sind PAY-006/P2); OP-24 (ein- oder zweistufiger Ablauf; Arbeitsannahme einstufig mit Regelanzeige vor der Bestätigung); OP-21 (Pfade); OP-26/OP-27 (Zahlungsumfang und Capture-Zeitpunkt bestimmen, ob Void oder Erstattung greift). Keine dieser Punkte blockiert den Bau der Oberfläche.

## Tests

- **Positiv:** Fahrgast storniert eigene Buchung innerhalb der Frist: Status „storniert“, Erstattungshinweis entspricht dem Zahlungsstand (Void bei nur autorisiert, Erstattung bei captured); danach erhält eine zweite Testbuchung denselben Platz; automatischer Konsistenzvergleich Status/`booking.state`/Historie/Kapazität/`payment_intent`/`refund_case` ohne Widerspruch.
- **Negativ:** Frist überschritten (Aktion nicht angeboten, serverseitig mit definiertem Code abgewiesen); bereits storniert; veralteter `If-Match` → 412; fehlender `If-Match` → 428; fehlender `Idempotency-Key`; fremde Buchung → 404; nicht zulässiger Grund.
- **Konkurrierend:** Doppelklick und Wiederholung nach Verbindungsabbruch → genau ein Storno und eine Erstattung; Storno in zwei Tabs gleichzeitig → ein Erfolg, der andere erhält idempotent dasselbe Ergebnis oder `OPERATION_IN_PROGRESS`; Storno parallel zur Neubuchung desselben Platzes → nie Überbelegung, nie doppelt freigegebene Kapazität; Storno parallel zu Webhook-Ereignis der Zahlung → keine zweite Erstattung.
- **Referenztests:** AT-07 (Ende-zu-Ende über die Oberfläche).
- Testfälle: AT-07; geplant:web/passenger/src/booking/CancelBooking.test.tsx::storno_zeigt_status_und_erstattungshinweis; geplant:web/passenger/src/booking/CancelBooking.test.tsx::doppelklick_ein_storno; geplant:tests/acceptance/test_selbststorno.py::storno_und_neubuchung_konsistent; geplant:tests/acceptance/test_selbststorno.py::storno_zwei_tabs_parallel; geplant:tests/acceptance/test_selbststorno.py::storno_ausserhalb_frist_nicht_angeboten_und_abgewiesen; geplant:tests/acceptance/test_selbststorno.py::fremde_buchung_404_und_header_pflicht; geplant:tests/acceptance/test_selbststorno.py::storno_parallel_zu_psp_webhook_keine_zweite_erstattung; geplant:tests/acceptance/test_selbststorno.py::storno_void_und_erstattung_hinweis_je_zahlungsstand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
