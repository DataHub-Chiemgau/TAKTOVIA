# LH-6.19-NFR-015 – Maskierung von Diagnosedaten

- **Verantwortlicher:** Rolle Plattform/Sicherheit (zentrale Maskierungsschicht in `backend/platform`)
- **Arbeitspaket:** WP-M5-06
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Diagnosedaten enthalten keine ungeschützten Zahlungsdetails oder unnötige Kontaktinformationen.

**Originalabnahme (unverändert):** Stichprobe von Logs zeigt eingehaltene Maskierungsregeln.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. `audit_event` und Sicherheitsereignisse bleiben trotz Maskierung über IDs nachvollziehbar.
- **API:** Fehlerantworten `application/problem+json` ohne Stacktrace und ohne Klartext-Kontaktdaten in `detail`; keine Request-/Response-Bodies personenbezogener Endpunkte im Log.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** Maskierungsregeln aus WP-M5-01: Feldklassen, Ersatzdarstellung, zulässige technische Bezeichner (z. B. UUID); Positivliste protokollierbarer Felder je Modul.
- **Geltungsbereich:** zentrale Maskierungsschicht für Logausgabe, Trace-Attribute und Problem-Details in `backend/platform`; gleiche Regeln in `workers/routing`, `workers/documents`, PSP-Adapter, Benachrichtigungsadapter; Client-Fehlerberichte (Mobile/Web) ohne Kontaktdaten; Alarmtexte.
- **Prüfskript „Log-Stichprobe“:** Mustersuche nach Kartennummern, IBAN, E-Mail, Telefonnummer, Adresse, Token, Boarding-Code als CI-Schritt und als Betriebsprüfung gegen die zentrale Logablage; protokolliert Umfang, Zeitraum und Trefferliste; Stichprobe umfasst Fachkern, Worker, Gateway, PSP-Adapter, Client-Fehlerberichte aus einem vollständigen E2E-Lauf mit Testkontakten und Testzahlungen. Ein absichtlich eingebauter Verstoß lässt den CI-Schritt fehlschlagen.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine.
- **Entscheidungen:** D-11 (Supportzugriff, Aufbewahrung) blockiert nur die Produktivschaltung der Logaufbewahrung.
- **Offene Punkte:** OP-09 (Maskierungsregeln im Pflichtenheft nicht festgelegt; „unnötige Kontaktinformation“ nicht abgegrenzt, z. B. Fahrgast-UUID, Haltepunktname, Start-/Zielkoordinate; Stichprobenumfang und -verfahren), OP-08 (Aufbewahrung, D-11). Die Regeln sind vor dem Bau der Prüfmuster im Detailvertrag WP-M5-01 festzulegen; Arbeitsannahme: Zahlungsdetails und Kontaktdaten immer maskiert, UUIDs zulässig.

## Tests

- **Positiv:** Stichprobe (Fachkern, Worker, Gateway, PSP-Adapter, Client-Fehlerberichte; Umfang, Zeitraum, Trefferliste protokolliert) aus vollständigem E2E-Lauf ohne Befund (kein Klartexttreffer für Zahlungsdetails oder Kontaktdaten); Audit-Nachvollziehbarkeit über IDs bleibt erhalten.
- **Negativ:** Fehlerpfade (Validierungsfehler mit Kontaktfeld, PSP-Fehlerantwort, Ausnahme mit Objektinhalt) hinterlassen keine Klartextdaten; Stacktrace erscheint nicht in der API-Antwort; absichtlich eingebauter Testkontakt im Log wird erkannt und lässt den CI-Schritt fehlschlagen; Client-Fehlerbericht ohne Kontaktdaten; Alarmtexte, Trace-Attribute und `problem+json`-`detail` ohne Klartext-Kontaktdaten oder Zahlungsdetails; Feld außerhalb der Positivliste wird nicht protokolliert.
- **Konkurrierend (Sicherheit/Datentrennung):** Logs zweier Mandanten unter Last enthalten keine vertauschten Mandantenbezüge.
- **Referenztests:** keine Katalog-ID im WP genannt; Wiederverwendung der Log-Stichprobe in QAT-003 (WP-M5-23).
- Testfälle: geplant:backend/platform/src/test/kotlin/de/taktovia/platform/logging/MaskierungTest.kt::fehlerpfade_ohne_klartextdaten; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/logging/MaskierungTest.kt::trace_attribute_problem_details_und_alarmtexte_maskiert_ohne_stacktrace; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/logging/MaskierungTest.kt::positivliste_unbekanntes_feld_nicht_protokolliert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/logging/MaskierungTest.kt::audit_event_bleibt_ueber_ids_nachvollziehbar; geplant:tests/acceptance/logging/test_log_stichprobe.py::test_stichprobe_umfasst_alle_komponenten_und_protokolliert_trefferliste; geplant:tests/acceptance/logging/test_log_stichprobe.py::test_client_fehlerberichte_ohne_kontaktdaten; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/logging/MaskierungTest.kt::mandantenbezug_unter_last_nicht_vertauscht; geplant:tests/acceptance/logging/test_log_stichprobe.py::test_stichprobe_e2e_lauf_ohne_befund; geplant:tests/acceptance/logging/test_log_stichprobe.py::test_absichtlicher_verstoss_laesst_ci_fehlschlagen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
