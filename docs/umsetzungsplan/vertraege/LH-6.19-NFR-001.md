# LH-6.19-NFR-001 – Verfügbarkeitsmessung mit synthetischen Proben und Monatsbericht

- **Verantwortlicher:** Rolle Betrieb/Plattform (Verfügbarkeitsmessung, WP-M5-05)
- **Arbeitspaket:** WP-M5-05
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ziel-Verfügbarkeit Kernbuchung und Operator liegt bei mindestens 99,9 % pro Kalendermonat außerhalb vertraglich definierter Wartung.

**Originalabnahme (unverändert):** Monitoringbericht berechnet Monatsverfügbarkeit aus vereinbarten Messpunkten.

## Vertrag

- **Tabellen / DDL:** keine neue Fachtabelle zwingend; Probemandant mit eigenem `tenant`, `service` und Probefahrzeug (`vehicle`) getrennt von Echtmandanten; Probebuchungen (`booking`, `travel_attempt`) tragen eine Probenkennzeichnung, Mechanismus (Spalte oder Mandantenmerkmal) wird im Bau festgelegt (OP-03). RLS unverändert; Probefahrzeug belegt keine Kapazität echter Fahrzeuge; Probebuchung wird nach dem Lauf storniert bzw. hält keine `capacity_hold` dauerhaft.
- **API:** Proben rufen ausschließlich bestehende Pfade auf: Kernbuchung `/v1/ride-offers` und `/v1/bookings`; Operator Anmeldung, `/v1/me`, `/v1/operator/vehicles`. Erfolgskriterium je Probe: erwarteter Statuscode und Inhalt (Angebot erhalten, Buchung bestätigt, Fahrzeugliste geladen). Keine neuen Endpunkte.
- **Zustandsautomat:** Probenergebnis je Lauf: `ok`, `fehler_plattform`, `fehler_provider` (z. B. PSP-Simulator down, getrennt ausgewiesen), `keine_messung` (Probe selbst ausgefallen oder Zugangsdaten ungültig; wird nicht als Verfügbarkeit gewertet, löst Probenalarm aus).
- **JSON-Schema / Ereignisse:** Berichtsformat „Monatsverfügbarkeit“ unter `infra/`: Formel, Messpunkte, Probenintervall, Wartungsfenster, Ausfallminuten, Rohdatenverweis; Schema-Datei unter `vertraege/schemas/verfuegbarkeitsbericht.v1.schema.json` (wird mit dem Bau angelegt).
- **Parameter und Kennzahlen:** keine Katalogparameter; technische SLO-Größe „Verfügbarkeit“ (nicht Teil der 48 KPI aus `kataloge.csv`); Probevorgänge dürfen `Anfragen` und `Buchungskonversion` nicht beeinflussen. Formel: Verfügbarkeit = (Messminuten − Ausfallminuten außerhalb Wartung) / (Messminuten − Wartungsminuten), je Kalendermonat getrennt für Kernbuchung und Operator; Ziel mindestens 99,9 %.
- **Oberfläche:** keine Endnutzeroberfläche; Bericht als Dokument/Dashboard der Betriebsplattform.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. Betroffen: Messgrenze Plattform/Provider und Wartungsregel.
- **Offene Punkte:** OP-02 (voller Kalendermonat Produktivbetrieb erst im Pilot nachweisbar; in M5 abnehmbar ist Messverfahren plus Bericht über Testzeitraum), OP-03 (Ausschlussregel Probebuchungen im Pflichtenheft nicht festgelegt), OP-28 (Probemandant in Produktion zulässig?). Probenintervall, Behandlung fehlender Proben und Wartungsregel stehen im Messpunktvertrag aus WP-M5-01; ohne D-05 wird mit Arbeitswerten gemessen. Blockiert den Bau nicht.

## Tests

- **Positiv:** Berichtslauf über einen Testzeitraum in Staging mit künstlich erzeugtem Ausfall bekannter Dauer; Bericht weist Dauer (Toleranz ein Probenintervall), Monatsverfügbarkeit je Bereich und „erreicht/verfehlt“ gegen 99,9 % aus; eingetragenes Wartungsfenster wird herausgerechnet und ausgewiesen.
- **Negativ:** PSP-Simulator down erscheint als `fehler_provider`, nicht als Plattformausfall; Probe mit ungültigen Zugangsdaten zählt nicht als Plattformausfall, sondern `keine_messung` plus Probenalarm; Ausfall der Probe selbst wird nicht als Verfügbarkeit gewertet; Probebuchung erscheint nicht in `Anfragen` oder `Buchungskonversion`.
- **Konkurrierend (Kapazität):** Probebuchung parallel zu echter Buchung auf dem letzten Platz verdrängt keine echte Buchung (Probefahrzeug getrennt); Probemandant sieht keine Echtdaten (Datentrennung).
- **Hinweis Operator/Kernbuchung:** Bericht und Tests weisen beide Bereiche getrennt aus (Originaltext nennt beide); `/v1/ride-offers`, `/v1/bookings`, `/v1/me`, `/v1/operator/vehicles` sind in Kap. 05 genannt, aber noch nicht in `api/*.yaml` spezifiziert.
- **Referenztests:** kein Katalogtest benannt; WP-M5-05 ist Nachweis für das Betriebs-Gate.
- Testfälle: geplant:tests/performance/availability/test_monatsbericht.py::test_ausfall_bekannter_dauer_und_wartung_im_bericht; geplant:tests/performance/availability/test_monatsbericht.py::test_probenausfall_ist_keine_messung_und_providerfehler_getrennt; geplant:tests/performance/availability/test_probebuchung.py::test_probebuchung_verdraengt_keine_echte_buchung; geplant:tests/performance/availability/test_probebuchung.py::test_probebuchung_erscheint_nicht_in_anfragen_und_buchungskonversion; geplant:tests/performance/availability/test_probebuchung.py::test_probemandant_sieht_keine_echtdaten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
