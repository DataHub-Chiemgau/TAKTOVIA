# LH-I.6-KPI-011 – Filterdimensionen der Berichte ohne Mandantenüberschreitung

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-58
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Leistungsdaten können nach Mandant, Betreiber, Bediengebiet, Dienst, Tag, Stunde und Fahrzeugtyp gefiltert werden.

**Originalabnahme (unverändert):** Ein Bericht über zwei Regionen lässt sich auf eine Region und Stunde einschränken.

## Vertrag

- **Tabellen / DDL:** Faktentabellen des Moduls `analytics` (u. a. `aggregate_snapshot`, `dataset_watermark`, Kap. 04: Definition, Sichtfenster, Datenstand, Vollständigkeit) tragen die Dimensionsspalten `tenant_id`, Betreiber, Bediengebiet, Dienst, Fahrzeug, Fahrzeugtyp und Zeitstempel (Tag/Stunde werden in `reporting_timezone` abgeleitet, DST-sicher). RLS je Tabelle nach Kap. 04 §4.5 (Default D-02); Indizes beginnen mit `tenant_id`. Eine Spalte für den Fahrzeugtyp existiert weder in `vehicle` (V1: `owner_org_ref`, `capacity_config`, `active_block`, `safety_revision`) noch sonst im Schema; ihre Herkunft ist im Bau festzulegen (siehe Offene Punkte). Exakte DDL kommt mit der Migration des Baus (V1/V2/V3 enthalten die Analytics-Tabellen noch nicht).
- **API:** `GET /v1/analytics/metrics` mit Filterparametern `operator_id`, `service_area_id`, `service_id`, `vehicle_id`, `vehicle_type`, `from`/`to`, `day`, `hour` (Pfad in Kap. 05 §Reports/KPIs genannt, aber noch nicht in `api/openapi.yaml`; Parameternamen sind Vorschlag und im OpenAPI-Vertrag festzuschreiben); Antwort `{items,next_cursor,as_of}`. Filter bilden serverseitig die Schnittmenge mit der Berechtigung (Dienst-/Objektberechtigung) und erweitern sie nie. Der Mandant wird ausschließlich aus dem Token bestimmt; ein manipulierter Mandantenheader/`tenant_id` wird ignoriert bzw. abgewiesen; ein fremder Dienst/Fahrzeug liefert dieselbe Antwort wie ein nicht vorhandenes Objekt (404, Muster PH-AT-001) ohne Existenzdetails. Kleine Gruppen unter `kpi_aggregation_min_n` erscheinen als „unterdrückt“, nicht als 0.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Filterschema `vertraege/schemas/analytics-filter.v1.schema.json` (wird mit dem Bau angelegt).
- **Parameter und Kennzahlen:** `reporting_timezone` (Scope Mandant, IANA TZ), `kpi_aggregation_min_n` (Scope Mandant, Mindestgruppengröße); Filter wirken auf alle Kennzahlen des Katalogs.
- **Oberfläche:** UI-19 Kennzahlenteil (WP-M4-59): Filterleiste für Betreiber, Bediengebiet, Dienst, Tag, Stunde, Fahrzeugtyp; Zustände „keine Daten“, „unterdrückt“, Fehler, kein Berichtsrecht (Ansicht nicht erreichbar).
- **Entscheidungen:** D-02 (Isolationsstufe, RLS-Default, blockiert nicht).
- **Offene Punkte:** WP-Dokument OP 33: Dimension „Mandant“ ist innerhalb eines Mandantenkontexts ohne Föderation (M6) wirkungslos und „Betreiber“ im Einbetreiber-Pilot einwertig; der Filter wird trotzdem geliefert und mit zwei Regionen/Bediengebieten nachgewiesen. Schwelle `kpi_aggregation_min_n` nicht freigegeben (OP 33); Regel gegen Differenzbildung muss im Bau festgelegt werden. Quelle der Dimension „Fahrzeugtyp“ ist im Datenmodell nicht definiert (Ableitung z. B. aus `capacity_config` oder neues Attribut: Entscheidung im Bau, Vorschlag, nicht festgelegt). Blockiert den Bau nicht.

## Tests

- **Positiv:** Bericht über zwei Bediengebiete (Regionen) wird auf eine Region und eine Stunde eingeschränkt und stimmt mit der Referenzberechnung überein; je Dimension (Betreiber, Bediengebiet, Dienst, Tag, Stunde, Fahrzeugtyp, Mandant) einzeln und kombiniert ein Referenzvergleich; Stundengrenzen in `reporting_timezone` auch am DST-Wechsel; Filter „Mandant“ liefert nur den aktiven Mandanten.
- **Negativ:** fremde Dienst-ID, fremde Fahrzeug-ID, manipulierter Mandantenheader/`tenant_id` liefern keine Fremddaten und keine Existenzdetails (gleichartige Antwort wie nicht vorhandenes Objekt); Filter außerhalb der eigenen Berechtigung erweitert diese nicht; Gruppe unter `kpi_aggregation_min_n` wird „unterdrückt“, auch bei Differenzbildung benachbarter Filter.
- **Konkurrierend (Datentrennung):** parallele Abfragen zweier Mandanten über denselben Verbindungspool zeigen keine Vermischung (Isolationsharness analog PH-AT-001).
- **Referenztests:** PH-AT-021, PH-AT-001.
- Testfälle: PH-AT-021; PH-AT-001; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/MetricsFilterTest.kt::filter_region_und_stunde_stimmt_mit_referenz; geplant:db/tests/test_analytics_rls.py::test_filter_kein_fremder_mandant_bei_parallelen_abfragen

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
