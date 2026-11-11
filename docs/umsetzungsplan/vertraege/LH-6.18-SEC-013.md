# LH-6.18-SEC-013 – Datenschutz-Folgenabschätzung

- **Verantwortlicher:** Rolle Plattform/Datenschutz (DSFA-Dokument), Gate-Prüfung durch Rolle Betrieb/CI
- **Arbeitspaket:** WP-M1-42
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Datenschutz-Folgenabschätzung wird für relevante Verarbeitungen vor Produktion geprüft und dokumentiert.

**Originalabnahme (unverändert):** Freigabe enthält Bewertung von Bewegungs-/Mobilitätsdaten und Maßnahmen.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Voraussetzung: im Dateninventar (WP-M1-32) wird jedes Feld als „Bewegungs-/Mobilitätsdatum“ markiert (Kennzeichnung ist Detailvertrag vorab).
- **API:** keine.
- **Dokument / Gate:** DSFA-Dokument im Repository (Verarbeitungen, Risiken, Maßnahmen mit Verweis auf umsetzende WPs, Restrisiko, Datum, Freigabevermerk der verantwortlichen Stelle), gespeist aus Dateninventar (WP-M1-32), Löschregeln (WP-M1-33), Feldschutz (WP-M1-31) und Isolationsnachweisen (WP-M1-01, WP-M1-11). Release-Gate-Prüfung: „DSFA-Version freigegeben und deckt alle im Inventar als Bewegungs-/Mobilitätsdaten markierten Felder ab“; ohne Freigabevermerk oder bei einem nicht referenzierten Feld schlägt sie fehl.
- **Zustandsautomat / Schema / Ereignisse:** keine. **Parameter und Kennzahlen:** keine. **Oberfläche:** keine.
- **Abgrenzung des Nachweises:** Die in M1 freigegebene DSFA bewertet die in M1 vorhandenen Verarbeitungen und die laut Pflichtenheft geplanten Standort-, Fahrer- und ETA-Daten; mit den Verarbeitungen aus WP-M3-34 (`position_sample`), WP-M4-61 und WP-M4-62 erzwingt die Gate-Prüfung den Nachtrag am realen Datenfluss (Vollabnahme). Die rechtliche Bewertung selbst liegt beim Auftraggeber/Datenschutz; P2-Verarbeitungen (M6–M8) sind nicht im Umfang.
- **Entscheidungen:** D-11 und D-10 – blockieren die Produktivschaltung, nicht die Erstellung; D-05 (Hostingregion).
- **Offene Punkte:** OP-32 – DSFA-Vorlage, Verantwortliche und freigebende Stelle (Datenschutzbeauftragte des Auftraggebers/der Betreiber) sind nicht benannt; die Freigabe selbst ist organisatorisch und nicht allein durch Software erfüllbar. Der Gate-Bau ist davon nicht blockiert.

## Tests

- **Positiv:** Gate-Prüfung mit freigegebener, datierter DSFA, die alle als Bewegungs-/Mobilitätsdatum markierten Inventarfelder referenziert, besteht; Freigabeunterlage enthält Bewertung der Bewegungs-/Mobilitätsdaten und Maßnahmen mit WP-Verweisen.
- **Negativ:** neues, als Mobilitätsdatum markiertes Inventarfeld ohne DSFA-Referenz lässt die Prüfung scheitern; fehlender Freigabevermerk bzw. fehlendes Datum lässt die Prüfung scheitern; Maßnahme ohne WP-Verweis wird beanstandet; DSFA ohne Bewertung der Bewegungs-/Mobilitätsdaten (einschließlich der laut Pflichtenheft geplanten Standort-, Fahrer- und ETA-Daten) wird beanstandet.
- **Konkurrierend:** nicht einschlägig (Dokument- und Gate-Prüfung, so im WP festgehalten).
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:tests/acceptance/test_dsfa_gate.py::test_gate_besteht_mit_freigegebener_dsfa; geplant:tests/acceptance/test_dsfa_gate.py::test_mobilitaetsfeld_ohne_dsfa_referenz_scheitert; geplant:tests/acceptance/test_dsfa_gate.py::test_fehlender_freigabevermerk_scheitert; geplant:tests/acceptance/test_dsfa_gate.py::test_massnahme_ohne_wp_verweis_wird_beanstandet; geplant:tests/acceptance/test_dsfa_gate.py::test_dsfa_ohne_bewertung_der_bewegungsdaten_scheitert; geplant:tests/acceptance/test_dsfa_gate.py::test_fehlendes_freigabedatum_scheitert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
