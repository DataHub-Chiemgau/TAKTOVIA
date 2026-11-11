# LH-6.19-NFR-005 – Standortfrische, Qualitätsflag und Veraltet-Kennzeichnung

- **Verantwortlicher:** Rolle Plattform/Fahrzeugbetrieb (WP-M5-08)
- **Arbeitspaket:** WP-M5-08
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Positionsupdates erhalten Uhrzeit und Qualitätsflag; frische Positionen sind im Normalbetrieb jünger als 30 s.

**Originalabnahme (unverändert):** Anzeige markiert mindestens einen künstlich veralteten Standort sichtbar.

## Vertrag

- **Tabellen / DDL:** `position_sample` (Kap. 04 §4.4: Mess- und Empfangszeit, Qualität; in V1/V2 noch nicht vorhanden bzw. aus M3 WP-M3-34): Messzeit und Empfangszeit als `timestamptz NOT NULL` getrennt, Qualitätsflag NOT NULL; Meldung ohne diese Angaben wird abgewiesen. Altersberechnung serverseitig gegen Serverzeit; Messzeit in der Zukunft oder grobe Uhrenabweichung wird über das Qualitätsflag als nicht verwendbar geführt (GPS-Messzeit ist keine vertrauenswürdige Serverzeit, Kap. 01 §1.5). Prüfung von Index- und Mengenverhalten bei 250 Fahrzeugen je Mandant. RLS mandantengetrennt.
- **API:** Positionsmeldung der Driver-App aus M3 (Endpunkt im Pflichtenheft nicht festgelegt); Antworten für UI-04/UI-07 tragen Standortzeit und Veraltet-Kennzeichen. Fahrgastansicht liefert die Position nur für die eigene aktive Buchung.
- **Zustandsautomat:** Standortzustand `frisch` (Alter < 30 s), `veraltet`, `nicht_verwendbar`; veraltete Position wird nicht fortgeschrieben und nicht als Grundlage einer laufenden ETA angezeigt.
- **JSON-Schema / Ereignisse:** Messprotokoll nach Kap. 08 §8.6: Erfassungs-/Empfangszeit, p95 Standortalter, Anteil > 30 s, Lücken, sichtbares Veraltet-Flag.
- **Parameter und Kennzahlen:** kein Katalogparameter für die Veraltet-Schwelle (`eta_max_data_age_sec` gilt für das ETA-Modell und wird nicht umgewidmet); Schwelle 30 s vorläufiges Lastenheftziel. Technische Messgröße Standortalter; Datenqualitätsbasis für `Fahrzeugverfügbarkeit` und Pünktlichkeitskennzahlen ohne Änderung deren Definition.
- **Oberfläche:** UI-07 (Operator Livebetrieb, Zustand „veralteter Standort“, Kap. 06 §6.9) und UI-04 (Reiseverfolgung): Standortzeit und Veraltet-Kennzeichnung nicht nur über Farbe, per Screenreader wahrnehmbar, auch in der Listenalternative zur Karte (Kap. 06 §6.1).
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. D-05 (GPS-Frequenz), D-09 (Telematik) blockieren die endgültige Schwelle; D-11 (Aufbewahrung Rohpositionen) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-11 (Wertebereich Qualitätsflag, Sendeintervall, Endpunkt, Abgrenzung „Normalbetrieb“ fehlen; Wertebereich wird im Detailvertrag aus WP-M5-01 festgelegt, Siehe M3 OP-04). Blockiert den Bau nicht.

## Tests

- **Positiv:** laufender Positionsstrom unter Referenzlast; jede gespeicherte Position hat Mess- und Empfangszeit sowie Qualitätsflag; Standortalter in der Anzeige unter 30 s, Protokoll weist p95, Lücken und Überschreitungen aus; mindestens ein künstlich veralteter Standort wird in UI-07 und UI-04 sichtbar als veraltet markiert, mit Zeit der letzten Position und ohne Fortschreibung.
- **Negativ:** Meldung ohne Messzeit, Empfangszeit oder Qualitätsflag wird abgewiesen; Messzeit in der Zukunft oder grobe Uhrenabweichung wird als nicht verwendbar geführt und nicht als frisch angezeigt; Strom unterbrochen (wie AT-10, hier unter Last); Meldung in falscher Reihenfolge; Meldung eines Fahrers für ein nicht zugeordnetes Fahrzeug; veralteter Standort in UI-04 nicht als Live-Position und nicht als ETA-Grundlage; Veraltet-Kennzeichnung per Screenreader wahrnehmbar.
- **Konkurrierend (Datentrennung):** Positionsströme zweier Mandanten parallel: keine Position erscheint im fremden Mandanten; Fahrgast sieht nur das Fahrzeug der eigenen aktiven Buchung.
- **Referenztests:** AT-10.
- Testfälle: AT-10; geplant:db/tests/test_position_sample.py::test_position_ohne_zeit_oder_qualitaet_wird_abgewiesen; geplant:tests/performance/test_standortfrische.py::test_standortalter_p95_unter_30s_unter_referenzlast; geplant:web/operator/src/livebetrieb/veralteterStandort.test.tsx::zeigt_veraltet_kennzeichnung_nicht_nur_ueber_farbe; geplant:web/passenger/src/reise/veralteterStandort.test.tsx::veralteter_standort_nicht_als_live_position_und_nicht_als_eta_grundlage; geplant:tests/performance/test_standortfrische.py::test_positionsstroeme_zweier_mandanten_ohne_fremdposition

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
