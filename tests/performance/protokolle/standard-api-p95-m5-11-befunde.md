# Messbefunde Standard-API p95 (WP-M5-11, LH-6.19-NFR-002)

Lasttestprotokolle (Perzentile je Endpunktklasse, Hardware, Datenvolumen, Build, Migrationsstand, Seed): [`standard-api-p95-m5-11.md`](standard-api-p95-m5-11.md) (nach dem Index `V50110`) und
[`standard-api-p95-m5-11-vor-index.md`](standard-api-p95-m5-11-vor-index.md) (vorheriger Lauf ohne den Index). Erzeugt von `test_standard_api_p95.py` (Opt-in `TAKTOVIA_PERF_RUN=1`, Jar, Datenbankumgebung).
Status der Zielwerte: **vorläufig, D-05 offen**; Referenzprofil und Liste „nicht-optimierende Standard-API“ sind nicht vereinbart (OP-01, OP-04), gemessen wurde mit dem Arbeitsprofil aus WP-M5-01/-02 und der Arbeitsliste des Vertrags.

## Ergebnis

Alle 21 gemessenen Klassen liegen bei p95 **unter 1 s** (größter Wert `GET /v1/operator/vehicles` mit p95 128 ms im Lauf nach dem Index, 214 ms im Lauf davor). Kein Fehler (Transport, 5xx) in 8.229 Anfragen.
**Diese Aussage gilt nur für die Messbedingungen**: Clientzeit des Generators auf derselben 4-CPU-Maschine (obere Schranke der serverseitigen Zeit), zwei Mandanten mit je 250 Fahrzeugen und 4.500 Buchungen (mittlere Skala,
nicht der Referenzumfang von 30.000 Buchungen/Tag), 183 req/s Durchsatz (die Referenzspitze der Annahme OP-01 mit 5.000 Sitzungen je Mandant wird nicht erreicht). Klassen ohne Datensatz im Messaufbau
(`expenses/{id}`, `operations/{id}`, `inspections/{id}`) sind über den 404-Pfad gemessen, `POST /v1/inspections` ist nicht gemessen (Prüflauf-Start braucht Checklistenvorlage und Freigabekette). Eine Abnahme
des Betriebs-Gates ist das nicht (D-05).

## Befunde und Maßnahmen

1. **Auftragsübersicht (`GET /v1/operator/bookings`) sortierte je Seite alle Buchungen der Dienste.** Der Index `booking_by_service (tenant_id, service_id, created_at, id)` liefert über mehrere Dienste keine
   Gesamtordnung nach `(created_at, id)`; der Plan las 1.500 Buchungen je Mandant per Bitmap Scan und sortierte sie (Top-N) für jede Seite: 4,2 ms und 280 Puffer je Seite bei 4.500 Buchungen, wachsend mit
   dem Bestand (EXPLAIN ANALYZE unter der Runtime-Rolle mit Tenantkontext). **Maßnahme:** Index `booking_by_created (tenant_id, created_at, id) WHERE booking_no IS NOT NULL` (`V50110`, nebenläufig aufgebaut,
   Test `db/tests/test_perf_indizes.py`): Schlüsselreihenfolge, 101 Zeilen je Seite, 0,9 ms, unabhängig vom Bestand. Die Gesamtlatenz der Klasse im Lauf ist kein Beleg für die Wirkung (die Läufe unterscheiden sich
   in der Maschinenlast); der Beleg ist der Plan.
2. **`GET /v1/operator/vehicles` ist die langsamste Klasse (p50 69 bis 102 ms für 100 Fahrzeuge).** Die Fahrzeug-SQL selbst braucht 0,4 ms; die Zeit entsteht beim Zusammensetzen: je Fahrzeug mit Schicht ruft die Seite
   `readiness.requirementsIn` einzeln auf (N+1-Muster, bis zu 100 Aufrufe je Seite; nicht einzeln profiliert, Annahme aus dem Code und der gemessenen SQL-Zeit). **Maßnahme (nicht umgesetzt, ausgewiesen):** Bündelung der Einsatzbereitschaftsprüfung je Seite in `fleet-safety`; das Ziel p95 <= 1 s
   ist erfüllt, die Klasse hat die geringste Reserve (Faktor 8). Bei Referenzumfang neu messen.
3. **Letzte Position je Fahrzeug (`position_sample`)** liest 60 Proben je Fahrzeug und dedupliziert per `DISTINCT ON` (10 ms für 100 Fahrzeuge); mit dem Referenzbestand (Probe alle 5 Minuten, mehrere Betriebstage)
   wächst das linear. **Maßnahme (nicht umgesetzt):** Abfrage je Fahrzeug mit `LIMIT 1` über den vorhandenen Index `position_sample_latest`. Kein Verstoß gegen das Ziel.
4. **Synthetischer Bestand (WP-M5-02):** der Generator legte Buchungen ohne `booking_no`, Fahrgast, Reise und Statushistorie an; die Fahrgastübersicht ergab 500 (Zusagenschnappschuss unvollständig) und die
   Auftragsübersicht keine Zeile. **Maßnahme:** Generator ergänzt `booking_no`, `passenger_id`, `journey_id`, `created_at` und die Statushistorie; die Fahrgastübersicht/Lesen wird an Buchungen der zwei
   Buchungsmandanten (echter Ablauf Angebot, Hold, Confirm, Storno) gemessen, weil der Schnappschuss des Generators nicht dem der Anwendung entspricht (Befund, nicht behoben).
5. **Zeitseitenkanal:** fremde UUID (existiert im anderen Mandanten) gegen eigene nicht vorhandene UUID: beide 404, Mediane 4,00 ms gegen 4,02 ms. **Cache:** 1.680 identische Pfadanfragen beider Mandanten parallel,
   keine Antwort mit Kennungen des anderen Mandanten (die Anwendung hält in dieser Konfiguration keinen Antwortcache; der Test bleibt als Sperre für spätere Caches).
6. **Isolationssuite nach der Optimierung:** der Index ändert kein Autorisierungsergebnis (reiner Zugriffspfad); PH-AT-001-Fälle laufen im Backend-Build (`CrossTenantIsolationTest`) und in den DB-Tests (`test_isolation_alle_tabellen`) grün.
