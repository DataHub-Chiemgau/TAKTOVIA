# LH-6.20-QAT-003 – Durchgängiger E2E-Referenzfall in Staging

- **Verantwortlicher:** Rolle QA/Akzeptanz (E2E in `tests/acceptance`)
- **Arbeitspaket:** WP-M5-23
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> E2E-Test prüft Buchung, Zuweisung, Fahrer-Check-in, Ausstieg, Reporting sowie Zahlung, soweit aktiviert.

**Originalabnahme (unverändert):** Durchgängiger Referenzfall läuft in Staging ohne manuelle DB-Korrektur.

## Vertrag

- **Tabellen / DDL:** keine; Szenario läuft ausschließlich unter Anwendungsrollen (Runtime-Rolle). Wächter „keine manuelle DB-Korrektur“: Datenbank-Auditvergleich belegt zwischen Start und Ende keine Schreibzugriffe außerhalb der Runtime-Rolle. Berührte Tabellen: `vehicle_run`, `payment_intent` (nur in der Zahlungsvariante), `outbox`, `audit_event`.
- **API:** (nur `/v1/driver/events` steht heute in `api/driver.yaml`; die übrigen Endpunkte entstehen in M2/M3/M4.) `/v1/ride-offers`, `/v1/bookings`, `/v1/payments/provider-events/{provider}` (PSP-Sandbox bzw. -Simulator), `/v1/driver/events`, `/v1/analytics/metrics`.
- **Zustandsautomat:** Drehbuch des Referenzfalls mit Fixture und erwarteten Status je Schritt (aus Zuständen M2/M3): Angebot, Buchung, Zuweisung im Fahrtenplan (`vehicle_run`), Dienststart mit Pflichtprüfung, Check-in, Ausstieg, Reporting.
- **JSON-Schema / Ereignisse:** Evidenz mit Ereignis- und Trace-IDs je Schritt (Kap. 08 §8.4), Evidenzformat aus WP-M0-22; nur synthetische Fahrgast-/Personaldaten; Evidenz besteht die Log-Stichprobe aus WP-M5-06.
- **Parameter und Kennzahlen:** Kennzahlen `Anfragen`, `Angebotsquote`, `Buchungskonversion`, `Abholung pünktlich`, `Ankunft pünktlich`, `Fahrgastfahrten pro Fahrzeugstunde` erscheinen mit erwartetem Zähler und Nenner und korrektem Datenstand.
- **Oberfläche:** UI-01 bis UI-03 (Passenger), UI-09 und UI-10 (Driver), UI-07 (Leitstelle), UI-15 bzw. Kennzahlansicht aus M4.
- **Varianten:** (a) Zahlung aktiviert: genau eine Zahlung im erwarteten Endzustand; (b) ohne Zahlung: kein `payment_intent`. Wiederholbar ohne Bereinigung (zweiter Lauf), für zwei Mandanten; protokolliert als Wiederholung von AT-01 mit Build und Evidenz.
- **Entscheidungen:** D-03 (Teil PSP-Auswahl): ohne Beschluss nur gegen PSP-Simulator; blockiert die Produktivschaltung, nicht den Bau.
- **Offene Punkte:** OP-24 (Referenzfall setzt veröffentlichte Fahrerschicht WP-M4-17 voraus, in QAT-003 nicht als Schritt genannt; M4 liefert keinen Berichts-/Exportjob, OP-13; „Reporting“ wird hier als Kennzahlansicht/`/v1/analytics/metrics` ausgelegt). Negativfälle liegen in WP-M5-24 (QAT-004).

## Tests

- **Positiv:** Referenzfall in Staging durchgängig, beide Varianten (mit/ohne Zahlung), erwartete Status je Schritt, Kennzahlwerte, Wächter ohne Fremdschreibzugriff, zweiter Lauf ohne Bereinigung, zwei Mandanten.
- **Negativ:** Szenario endet nicht mit den erwarteten Status je Schritt oder Kennzahlwert/Datenstand weicht ab, Lauf schlägt fehl; Wächter schlägt an, wenn ein Schreibzugriff außerhalb der Runtime-Rolle stattfindet (Probeeingriff); Variante ohne Zahlung erzeugt keinen `payment_intent`; Evidenz mit echten Personendaten oder Log-Stichprobe mit Klartexttreffer lässt den Lauf fehlschlagen; weitere Negativfälle siehe WP-M5-24.
- **Konkurrierend (Kapazität, Geld):** zwei Referenzfälle parallel in zwei Mandanten (keine Datenvermischung) und zwei im selben Mandanten auf demselben Fahrzeug (keine Überbuchung, genau eine Zahlung je Buchung).
- **Referenztests:** AT-01 (Wiederholung).
- Testfälle: AT-01; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_referenzfall_mit_zahlung; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_erwartete_status_und_kennzahlen_mit_datenstand; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_zweiter_lauf_ohne_bereinigung_zwei_mandanten; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_evidenz_synthetisch_und_log_stichprobe_ohne_treffer; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_waechter_schlaegt_bei_probeeingriff_an; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_parallele_referenzfaelle_zwei_mandanten_keine_vermischung; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_referenzfall_ohne_zahlung_kein_payment_intent; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_waechter_keine_manuelle_db_korrektur; geplant:tests/acceptance/e2e/test_referenzfall_staging.py::test_parallele_referenzfaelle_selbes_fahrzeug

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
