# LH-6.19-NFR-003 – Fahrtangebote p95 ≤ 5 s unter Referenzlast

- **Verantwortlicher:** Rolle Plattform/Routing (WP-M5-12)
- **Arbeitspaket:** WP-M5-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Bei Referenzlast antworten 95 % der Verfügbarkeits-/Fahrtangebote innerhalb von 5 s.

**Originalabnahme (unverändert):** Lasttest mit dokumentierter Testflotte erreicht P95-Ziel oder weist Abweichung aus.

## Vertrag

- **Tabellen / DDL:** keine fachliche Änderung. Messung der Angebotskette `/v1/ride-offers`: Zulässigkeitsprüfung, Kandidatenfilter, Routingmatrix (`route_matrix_cache`, Warm-/Cold-Cache), Einfügeheuristik, Tarif, Angebotsobjekt. Angebote ohne Hold versprechen keine Kapazität; `capacity_hold`/Confirm bleiben unverändert.
- **API:** `/v1/ride-offers`. Zeitbudget als Planungsdefault Kap. 01 §1.4: 1 s lokale Kandidatensuche, Reserve; Partnerfenster 3 s in P1 ungenutzt. Bei Solver-Timeout nur vollständig validierter zulässiger Plan oder begründete Ablehnung; ein nicht rechtzeitig berechnetes Angebot wird nie nachträglich als zugesagt dargestellt.
- **Zustandsautomat:** entfällt (Zustände Angebot/Hold/Confirm aus M2).
- **JSON-Schema / Ereignisse:** Protokoll mit lokalen Anteilen, Kandidaten-/Flottengröße, Routingdatenstand, Timeouts, Beschreibung der Testflotte (Fahrzeuge, Ressourcenvektoren, Vorbelegung der Fahrtenpläne, Gebietsgröße).
- **Parameter und Kennzahlen:** Gegenprobe Kennzahlen `Technische Anfragefehler` und `Kapazitätsablehnungen`: Solver-Timeouts zählen als technischer Fehler bzw. begründete Ablehnung, nicht als Kapazitätsablehnung. Keine Katalogparameter.
- **Oberfläche:** keine.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. D-09 (Routinganbieter) blockiert Abnahmewirkung und Produktivschaltung, nicht die Messung.
- **Offene Punkte:** OP-05 (Messpunkt serverseitig oder bis Client und Beschreibung der Testflotte fehlen; bis Klärung wird serverseitig gemessen und die Clientzeit zusätzlich protokolliert), OP-01. Blockiert den Bau nicht.

## Tests

- **Positiv:** Messlauf mit vorbelegter dokumentierter Testflotte unter Referenzlast; p95 ≤ 5 s oder Abweichung mit Ursache und Maßnahme; Protokoll enthält lokale Anteile, Flottengröße, Routingdatenstand, Timeouts.
- **Negativ:** kalter Routingcache; langsame Routingquelle; Anfrage ohne zulässiges Fahrzeug führt zu schneller Ablehnung; Solver-Timeout liefert nur validierten Plan oder begründete Ablehnung, kein Angebot verletzt zugesagte Zeitfenster.
- **Konkurrierend (Kapazität):** viele gleichzeitige Angebote auf dieselbe Restkapazität: Angebote ohne Hold versprechen keine Kapazität, Hold/Confirm bleiben korrekt (PH-AT-004 unter Last).
- **Referenztests:** PH-AT-004.
- Testfälle: PH-AT-004; tests/performance/test_angebot_p95.py::test_p95_angebot_mit_dokumentierter_testflotte; tests/performance/test_angebot_p95.py::test_solver_timeout_liefert_nur_validierten_plan_oder_ablehnung; tests/performance/test_angebot_p95.py::test_parallele_angebote_ohne_hold_versprechen_keine_kapazitaet; tests/performance/test_angebot_p95.py::test_solver_timeout_zaehlt_als_technischer_fehler_nicht_als_kapazitaetsablehnung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
