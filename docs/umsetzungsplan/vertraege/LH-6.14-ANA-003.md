# LH-6.14-ANA-003 – Filterdimensionen der Berichte ohne Mandantenüberschreitung

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`); Zuständigkeit Isolation Plattform
- **Arbeitspaket:** WP-M4-58
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Berichte lassen sich nach Betreiber, Dienst, Gebiet, Zeitraum und Fahrzeug filtern.

**Originalabnahme (unverändert):** Jeder Filter verändert Ergebnisse korrekt ohne Mandantenüberschreitung.

## Vertrag

- **Tabellen / DDL:** Dimensionsspalten in den Faktentabellen des Moduls `analytics` (Mandant [nur der aktive], Betreiber, Bediengebiet, Dienst, Zeitraum/Tag/Stunde in `reporting_timezone`, Fahrzeug, Fahrzeugtyp); `tenant_id` führt jeden Index, RLS aus V1 bleibt die erste Schicht (D-02 RLS-Default). Filter sind eine zweite Schicht und dürfen die Autorisierung nur einschränken.
- **API:** `GET /v1/analytics/metrics` mit Filterparametern für Betreiber, Dienst, Gebiet, Zeitraum, Fahrzeug (und Fahrzeugtyp, Stunde); Antwort `{items,next_cursor,as_of}`. Serverseitig Schnittmenge aus Filter und Berechtigung (Dienst-/Objektberechtigung WP-M1-08, Schema `membership.service_scope`). Unbekannte oder fremde Filterwerte: 404 bzw. leere Menge nach Kap. 05 §5.1, nie Daten fremder Mandanten; ein `tenant_id`-Parameter im Filter wird nicht ausgewertet.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** `reporting_timezone` (DST-sicher), `kpi_aggregation_min_n` (Gruppen darunter werden unterdrückt, auch gegen Differenzbildung benachbarter Filter).
- **Oberfläche:** Filterleiste der Kennzahlenansicht (WP-M4-59); fremde Dienste/Fahrzeuge nicht auswählbar; Zustände leer/Fehler/keine Rechte.
- **Entscheidungen:** D-02 (blockiert nicht).
- **Offene Punkte:** OP-33 (WP-M4): Dimension „Mandant“ innerhalb eines Mandantenkontexts ist wirkungslos, „Betreiber“ im Einbetreiber-Pilot einwertig; Schwelle `kpi_aggregation_min_n` ist nicht freigegeben, Differenzbildungsregel (GOV-012 ist P2) wird von WP-M4-58 trotzdem gebaut. Beides blockiert den Bau nicht, die Schwelle muss vor Produktivschaltung freigegeben werden.

## Tests

- **Positiv:** je Filter (Betreiber, Dienst, Gebiet, Zeitraum, Fahrzeug) ein Referenzvergleich gegen unabhängig berechnete Werte; Dimensionen einzeln und kombiniert (Region + Stunde); Bericht über zwei Regionen lässt sich auf eine Region und eine Stunde einschränken.
- **Negativ:** fremde Dienst-ID, fremde Fahrzeug-ID, manipulierte `tenant_id` liefern keine fremden Daten; Gruppe unter `kpi_aggregation_min_n` wird unterdrückt, auch bei Differenzbildung zweier benachbarter Filter; Filter erweitert nie den Berechtigungsscope (`membership.service_scope`); direkte Abfrage der Faktentabellen unter der Anwendungsrolle zeigt keine fremden Zeilen (RLS).
- **Konkurrierend:** parallele Abfragen zweier Mandanten über denselben Verbindungspool zeigen keine Vermischung (RLS-Kontext je Anfrage, kein fortwirkender Kontext nach Pool-Wiederverwendung); Scope-Entzug parallel zu laufender Abfrage wirkt ab Commit.
- **Referenztests:** PH-AT-021 (Aggregationsschutz), PH-AT-001 (Isolation, analog; vom WP als Vorlage für den Isolationsteil genannt).
- Testfälle: PH-AT-021; PH-AT-001; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::jeder_filter_veraendert_ergebnis_gegen_referenz; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::region_und_stunde_einschraenkbar; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::fremde_dienst_und_fahrzeug_id_liefern_keine_fremden_daten; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::manipulierte_tenant_id_liefert_keine_fremden_daten; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::filter_erweitert_berechtigungsscope_nie; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::kleingruppe_nicht_durch_differenzbildung_offengelegt; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::parallele_mandanten_abfragen_ohne_vermischung; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/ReportFilterTest.kt::scope_entzug_wirkt_ab_commit; geplant:db/tests/test_analytics_fakten_rls.py::test_faktentabellen_rls_isoliert_mandanten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
