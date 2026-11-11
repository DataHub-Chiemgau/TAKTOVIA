# LH-6.5-OPT-003 – Einfügeheuristik im Routing-Worker

- **Verantwortlicher:** Rolle Routing/Disposition (Worker `workers/routing/`, Aufruf im Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-17
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Engine prüft mögliche Einfügepositionen für Ein- und Ausstieg in bestehende Umläufe.

**Originalabnahme (unverändert):** Eine neue Buchung kann zwischen vorhandene Stopps eingefügt werden.

## Vertrag

- **Tabellen / DDL:** keine neuen; liest Planrevisionen (`vehicle_run.revision`, `route_stop` aus LH-6.5-OPT-004) und `stop_revision`; schreibt selbst nichts (Varianten werden vom Kern validiert und erst in Hold/Confirm persistiert).
- **API:** keine öffentliche; Schnittstelle Kern zu Worker: Eingang Planrevisionen der Kandidatenfahrzeuge, Bedarf (Ressourcenvektor), Zeitfenster, Fahrzeitmatrix (WP-M2-10), Parameter; Ausgang zulässige Varianten mit Einfügepositionen (Einstieg, Ausstieg), Planzeiten, Planrevision, gegen die gerechnet wurde, und Grundcodes. Synchron oder über RabbitMQ ist nicht festgelegt (OP-38).
- **Algorithmus:** je Kandidatenfahrzeug Aufzählung aller Positionspaare (Einstieg, Ausstieg) mit Einstieg vor Ausstieg, Zeitpropagation mit `stop_dwell_sec` und `wheelchair_boarding_sec`, Prüfung per Constraint Validator aus LH-6.5-OPT-004; Leerfahrzeug als Sonderfall (neuer Umlauf). Bei `pooling_allowed=false` keine Variante mit überlappender Mitfahrt verschiedener Buchungen. Zeitbudget 1 s lokale Kandidatensuche (Planungsdefault unter D-05); bei Timeout nur ein bereits vollständig validierter Plan, sonst begründete Ablehnung (Kap. 01 §1.4). Nie ein ungeprüfter Plan. Fahrzeugscope und Mandant im Eingang fest; keine fremden Pläne.
- **Zustandsautomat / Schema / Ereignisse:** keine eigenen (Schema der Worker-Nachricht als JSON-Schema im Bau).
- **Parameter und Kennzahlen:** `pooling_allowed` (Dienst), `stop_dwell_sec` (Dienst), `wheelchair_boarding_sec` (Fahrzeugtyp).
- **Oberfläche:** keine.
- **Entscheidungen:** D-05 (exakte Routingbudgets und Peaklast; Bau mit Planungsdefault 1 s, Produktivwerte nach Beschluss).
- **Offene Punkte:** OP-38 (Schnittstelle Kern/Worker synchron oder Queue), OP-48 (Erzeugung `vehicle_run`), OP-39 (Lastprofil). Nicht im Umfang: Reoptimierung, Fahrzeitunsicherheit, Pausen, dynamische Haltestellenwahl. Nicht blockierend.

## Tests

- **Positiv:** Einfügen am Anfang, in der Mitte und am Ende eines Umlaufs mit Stopps A–B (Einstieg und/oder Ausstieg zwischen A und B); leeres Fahrzeug (neuer Umlauf).
- **Negativ:** keine zulässige Position: begründete Ablehnung; Solver-Timeout: nur validiertes Teilergebnis oder Ablehnung; bei `pooling_allowed=false` keine überlappende Mitfahrt verschiedener Buchungen; jede Variante besteht den Validator (Property-Tests gegen den Validator aus OPT-004); jede Variante nennt ihre Planrevision.
- **Konkurrierend (Kapazität):** Variante auf veralteter Planrevision wird vom Kern bei Hold/Confirm als Revisionskonflikt abgewiesen (Verbindlichkeit in WP-M2-22); zwei parallele Anfragen auf denselben Umlauf erhalten Varianten mit jeweils eigener Planrevision, nie ungeprüft.
- **Referenztests:** AT-02 (gemeinsame Fahrzeugroute zweier überlappender Relationen); Laufzeitmessung im Lasttestgerüst `tests/performance`.
- Testfälle: AT-02; geplant:workers/routing/tests/test_insertion_heuristic.py::test_einfuegen_zwischen_vorhandene_stopps; geplant:workers/routing/tests/test_insertion_heuristic.py::test_einfuegen_anfang_mitte_ende; geplant:workers/routing/tests/test_insertion_heuristic.py::test_leeres_fahrzeug_neuer_umlauf; geplant:workers/routing/tests/test_insertion_heuristic.py::test_keine_zulaessige_position_begruendete_ablehnung; geplant:workers/routing/tests/test_insertion_heuristic.py::test_alle_varianten_bestehen_validator; geplant:workers/routing/tests/test_insertion_heuristic.py::test_jede_variante_nennt_planrevision; geplant:workers/routing/tests/test_insertion_heuristic.py::test_pooling_aus_keine_ueberlappende_mitfahrt; geplant:workers/routing/tests/test_insertion_heuristic.py::test_timeout_nur_validierter_plan_oder_ablehnung; geplant:workers/routing/tests/test_insertion_heuristic.py::test_fremder_mandant_und_fahrzeugscope_nicht_ueberschritten; geplant:workers/routing/tests/test_insertion_heuristic.py::test_parallele_anfragen_eigene_planrevision; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/InsertionPlanRevisionTest.kt::veraltete_planrevision_ergibt_revisionskonflikt_bei_hold; geplant:tests/performance/test_insertion_budget.py::test_kandidatensuche_im_zeitbudget

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
