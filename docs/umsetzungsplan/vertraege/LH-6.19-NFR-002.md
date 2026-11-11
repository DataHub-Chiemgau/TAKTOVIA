# LH-6.19-NFR-002 – Standard-API p95 ≤ 1 s unter Referenzlast

- **Verantwortlicher:** Rolle Plattform/Performance (WP-M5-11)
- **Arbeitspaket:** WP-M5-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Bei Referenzlast antworten 95 % der nicht-optimierenden Standard-API-Anfragen in höchstens 1 s serverseitig.

**Originalabnahme (unverändert):** Lasttestprotokoll nennt Perzentile, Hardware und Datenvolumen.

## Vertrag

- **Tabellen / DDL:** keine fachliche Änderung. Nachgezogene Indizes aus den Messbefunden (Kap. 04 §4.7) als Migration `V<n>__perf_indizes.sql` (n nach aktuellem Stand), expand/contract ohne buchungsblockierende Sperre, frisch und aus dem freigegebenen Vorstand migrierbar. RLS-Policies bleiben aktiv und werden auf Planverträglichkeit unter der Runtime-Rolle (nicht privilegiert) geprüft; Cache-Schlüssel tragen `tenant_id`.
- **API:** gemessen werden mindestens die Klassen `/v1/me`, `/v1/bookings` (Lesen, Confirm, Storno), `/v1/services`, `/v1/service-areas`, `/v1/stops`, `/v1/operator/vehicles`, `/v1/driver/events`, `/v1/driver/shifts/{id}/run`, `/v1/inspections`, `/v1/defects`, `/v1/rosters`, `/v1/time-events`, `/v1/expenses/{id}`, `/v1/config/effective`, `/v1/operations/{operation_id}`, `/v1/analytics/metrics`. Messpunkt „serverseitig“: Eingang Gateway bis Antwortende. Asynchrone `202`-Antworten mit `operation` zählen nur bis zur Annahme; Berichts-/Exportjobs sind nicht Teil der 1-s-Klasse. Keine Änderung fachlicher Verträge.
- **Zustandsautomat:** entfällt.
- **JSON-Schema / Ereignisse:** Lasttestprotokoll nach Berichtsformat aus WP-M0-25 (`tests/performance`): Perzentile p50/p95/p99 je Endpunktklasse, Fehlerrate, Endpointmix, Hardware, Datenvolumen (Zeilenzahlen aus WP-M5-02), Build, Migrationsstand, Seed.
- **Parameter und Kennzahlen:** keine Katalogparameter; technische Messgröße „serverseitige Latenz je Endpunktklasse“ (nicht Teil der 48 KPI).
- **Oberfläche:** keine.
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel): alle Zielwerte sind vorläufige Lastenheftziele (Kap. 08 §8.6), Status je Zielwert im Protokoll „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“. D-05 blockiert die Abnahmewirkung, nicht den Bau. D-05 betrifft insbesondere Hardware/Hosting.
- **Offene Punkte:** OP-04 (Liste „nicht-optimierende Standard-API“ und Hardwarebezug fehlen im Pflichtenheft; Liste kommt aus WP-M5-01, hier die obige Arbeitsliste), OP-01 (Referenzprofil nicht vereinbart, Messung mit Arbeitsprofil aus WP-M5-01). Blockiert den Bau nicht, nur die Abnahmewirkung.

## Tests

- **Positiv:** Messlauf unter Referenzlast mit zwei Mandanten, Runtime-Rolle und RLS; Protokoll nennt p50/p95/p99 je Endpunktklasse, Hardware und Datenvolumen; p95 ≤ 1 s oder Abweichung je Klasse mit Ursache und Maßnahme.
- **Negativ:** Listenabfrage mit `limit` 100 (Obergrenze laut Kap. 05) und tiefer Paginierung bleibt im Ziel oder wird als Klasse ausgewiesen; Abfrage auf fremde UUID liefert 404 und ist nicht messbar schneller/langsamer als eine eigene nicht gefundene UUID (kein Zeitseitenkanal); Index-Migration ohne Sperre auf Buchungstabellen.
- **Konkurrierend (Datentrennung):** Cache-Treffer von Mandant A wird nie an Mandant B ausgeliefert (identische Pfade, parallele Anfragen); Isolationssuite PH-AT-001 bleibt nach jeder Optimierung grün.
- **Referenztests:** PH-AT-001.
- Testfälle: PH-AT-001; geplant:tests/performance/test_standard_api_p95.py::test_p95_standard_api_unter_referenzlast; geplant:tests/performance/test_standard_api_p95.py::test_cache_liefert_nie_fremden_mandanten_aus; geplant:db/tests/test_perf_indizes.py::test_index_migration_ohne_buchungssperre_und_frisch_migrierbar; geplant:tests/performance/test_standard_api_p95.py::test_fremde_uuid_404_ohne_zeitseitenkanal

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
