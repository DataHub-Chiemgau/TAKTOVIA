# M5 – P1-Härtung und Pilotabnahme

- **Meilenstein:** M5 – P1-Härtung und Pilotabnahme
- **Phase:** P1 (alle Anforderungen MUSS, Originalphase und -priorität unverändert)
- **Anforderungen:** 17 (NFR 11, QAT 6)
- **Arbeitspakete:** 31 (17 mit Anforderungen, 14 Enabler)

**Ziel:** Nach M5 ist die in M1 bis M4 gelieferte Einbetreiber-Plattform unter der Referenzlast mit mindestens zwei Mandanten gemessen, nach einem Datenbankausfall innerhalb der Zielzeiten wiederhergestellt und gegen Ausfälle ihrer Abhängigkeiten geprüft. Betrieb, Monitoring, Alarmierung und Verfügbarkeitsmessung laufen; Diagnosedaten sind maskiert; der durchgängige Referenzfall und die kritischen Negativfälle laufen in Staging ohne manuellen Datenbankeingriff. Alle 202 P1-Anforderungen sind mit Test und Evidenz verknüpft, die sieben Gates aus Kap. 08 §8.4 sind nachgewiesen, und der Abnehmer kann den Standardbetrieb mit den gelieferten Handbüchern demonstrieren.

**Hinweis zum Zuschnitt:** M5 liefert überwiegend Nachweise, Betriebsfähigkeit und Härtung, keine neue Fachfunktion. Die zu messenden Funktionen stammen aus M1 bis M4 und sind je WP als EXTERN-Abhängigkeit ausgewiesen. Alle Zielwerte (99,9 %, p95 ≤ 1 s / ≤ 5 s / ≤ 10 s, 30 s, 250 Fahrzeuge, 30.000 Buchungen/Tag, 5.000 Sessions, RPO ≤ 15 min, RTO ≤ 2 h) sind laut Kap. 08 §8.6 **vorläufige Lastenheftziele**; Peakprofil, Messgrenzen, Wartungsregel und Hosting stehen unter D-05. Das Routingbudget „1 s lokal / 3 s Partnerfenster / 1 s Reserve“ ist ein Planungsdefault aus Kap. 01 §1.4. Abhängigkeiten zu M0 bis M4 nennen die liefernden WP-IDs der jeweiligen WP-Dateien. Drei der sechs Gate-Tests (AT-25, AT-53, AT-59) und Teile von AT-54 und AT-61 stützen sich auf Funktionen, die in M1 bis M4 nicht geliefert werden und deren Anforderungen P2 sind; WP-M5-19 und WP-M5-20 weisen den hier zusätzlich nötigen P1-Mindestumfang ausdrücklich aus (OP-13).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M5-01 | Detailverträge und Abnahmeplan M5 | – (Enabler) | M | WP-M0-31, WP-M0-32, WP-M0-33 |
| WP-M5-02 | Staging- und Performance-Umgebung mit Referenzdatenbestand | – (Enabler) | M | WP-M5-01, WP-M0-11, WP-M0-23, WP-M0-25, WP-M1-40, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07, WP-M2-23, WP-M3-03, WP-M3-05, WP-M3-34, WP-M4-03, WP-M4-08, WP-M4-14, WP-M4-17, WP-M4-22, WP-M4-33 |
| WP-M5-03 | Telemetrie-Vollausbau: Metriken, Traces, Logs je Komponente | – (Enabler) | M | WP-M5-01, WP-M0-10, WP-M1-02, WP-M1-14, WP-M1-15, WP-M2-10, WP-M2-17, WP-M4-21, WP-M4-27 |
| WP-M5-04 | Alerting für kritische Fehlerszenarien | 1 | M | WP-M5-03, WP-M1-14, WP-M1-15, WP-M1-41, WP-M2-17, WP-M2-30, WP-M3-52 |
| WP-M5-05 | Verfügbarkeitsmessung mit synthetischen Proben und Monatsbericht | 1 | M | WP-M5-03, WP-M5-04, WP-M1-06, WP-M1-44, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M3-36, WP-M4-46, WP-M4-47 |
| WP-M5-06 | Maskierung von Diagnosedaten | 1 | M | WP-M5-03, WP-M1-12, WP-M1-31, WP-M1-37 |
| WP-M5-07 | Zentrale Zeitzonen- und Rundungsregeln mit Referenztests | 1 | M | WP-M5-01, WP-M1-18, WP-M2-08, WP-M2-09, WP-M4-10, WP-M4-35, WP-M4-45 |
| WP-M5-08 | Standortfrische, Qualitätsflag und Veraltet-Kennzeichnung | 1 | M | WP-M5-03, WP-M5-10, WP-M2-43, WP-M3-34, WP-M3-35, WP-M3-36 |
| WP-M5-09 | Netzwerkzustand und degradierte Funktion in den Apps | 1 | M | WP-M5-01, WP-M2-35, WP-M2-42, WP-M2-43, WP-M3-24, WP-M3-26, WP-M3-33, WP-M3-35 |
| WP-M5-10 | Referenzlastprofil und Skalierungstest | 1 | M | WP-M5-02, WP-M5-03, WP-M0-25, WP-M0-26, WP-M0-27, WP-M2-48 |
| WP-M5-11 | Standard-API p95 ≤ 1 s unter Referenzlast | 1 | M | WP-M5-10, WP-M1-01, WP-M1-02, WP-M1-45, WP-M2-48, WP-M3-56, WP-M4-08, WP-M4-14, WP-M4-22, WP-M4-46, WP-M4-63 |
| WP-M5-12 | Fahrtangebote p95 ≤ 5 s unter Referenzlast | 1 | M | WP-M5-10, WP-M2-10, WP-M2-17, WP-M2-21, WP-M2-22, WP-M2-23 |
| WP-M5-13 | Fahrer-Planrevision p95 ≤ 10 s bis zur Clientdarstellung | 1 | M | WP-M5-03, WP-M5-10, WP-M2-28, WP-M3-27, WP-M3-35, WP-M3-56 |
| WP-M5-14 | Replikation, PITR und Wiederanlaufdrehbuch | – (Enabler) | M | WP-M5-02, WP-M5-04, WP-M0-11, WP-M0-23, WP-M0-25, WP-M1-15, WP-M1-33, WP-M1-39, WP-M2-29, WP-M2-30 |
| WP-M5-15 | Ausfallübung mit RPO-/RTO-Messung und PSP-Abgleich | 1 | M | WP-M5-05, WP-M5-14, WP-M1-14, WP-M1-39, WP-M2-29, WP-M2-30 |
| WP-M5-16 | Fault-Injection für Datenbank, Worker, Cache, PSP, Kartenquelle, Messaging | – (Enabler) | M | WP-M5-02, WP-M5-04, WP-M5-09, WP-M5-14, WP-M5-23, WP-M0-23, WP-M1-10, WP-M1-14, WP-M1-15, WP-M2-10, WP-M2-22, WP-M2-30, WP-M4-21, WP-M4-27 |
| WP-M5-17 | Sicherheitsabnahme: Penetrationstest, RLS- und Feldzugriffsprüfung | – (Enabler) | M | WP-M5-02, WP-M5-06, WP-M0-18, WP-M0-19, WP-M1-01, WP-M1-07, WP-M1-11, WP-M1-31, WP-M1-38, WP-M1-45, WP-M2-29, WP-M2-48, WP-M3-44, WP-M3-50, WP-M3-56, WP-M4-16, WP-M4-21, WP-M4-58, WP-M4-63 |
| WP-M5-18 | Barrierefreiheitsprüfung Fahrer und Planer (AT-54) | – (Enabler) | M | WP-M5-01, WP-M5-02, WP-M1-43, WP-M3-14, WP-M3-24, WP-M3-29, WP-M3-33, WP-M3-44, WP-M4-11, WP-M4-18, WP-M4-19, WP-M4-20 |
| WP-M5-19 | Export-, Frist- und Archivnachweise (AT-25, AT-53, AT-61) | – (Enabler) | L | WP-M5-01, WP-M5-02, WP-M1-11, WP-M1-12, WP-M1-15, WP-M1-31, WP-M1-33, WP-M3-14, WP-M3-43, WP-M3-48, WP-M3-50, WP-M4-08, WP-M4-09, WP-M4-14, WP-M4-15, WP-M4-16 |
| WP-M5-20 | Standortdaten- und Medienminimierung (AT-59) | – (Enabler) | M | WP-M5-01, WP-M5-02, WP-M5-06, WP-M1-22, WP-M1-32, WP-M3-34, WP-M3-44, WP-M4-08, WP-M4-11, WP-M4-21, WP-M4-25 |
| WP-M5-21 | Zentraler Anforderungs-Backlog mit Besitzer und Testfall | 1 | S | WP-M5-01, WP-M0-31, WP-M0-32 |
| WP-M5-22 | Release-Gate in CI: Pflichtprüfungen für Kernbuchung und Disposition | 1 | M | WP-M5-21, WP-M0-03, WP-M0-12, WP-M0-13, WP-M0-14, WP-M0-17, WP-M1-03, WP-M1-45, WP-M2-17, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-48 |
| WP-M5-23 | Durchgängiger E2E-Referenzfall in Staging | 1 | M | WP-M5-02, WP-M5-25, WP-M0-22, WP-M2-23, WP-M2-30, WP-M2-42, WP-M2-48, WP-M3-14, WP-M3-29, WP-M3-36, WP-M3-56, WP-M4-17, WP-M4-46, WP-M4-51, WP-M4-53, WP-M4-54, WP-M4-56, WP-M4-59 |
| WP-M5-24 | Negative E2E-Fälle mit definierten Status und Aufräumprozessen | 1 | M | WP-M5-23, WP-M0-22, WP-M0-23, WP-M2-22, WP-M2-24, WP-M2-26, WP-M2-31, WP-M3-29, WP-M3-53, WP-M3-54 |
| WP-M5-25 | Stammdateneinspielung vor Betriebsstart mit Migrationsprotokoll | 1 | M | WP-M5-02, WP-M1-05, WP-M1-06, WP-M1-07, WP-M1-22, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07, WP-M3-03, WP-M3-05 |
| WP-M5-26 | Betriebshandbuch: Monitoring, Eskalation, Notbetrieb | – (Enabler) | M | WP-M5-04, WP-M5-05, WP-M5-14, WP-M5-15, WP-M5-16, WP-M1-41, WP-M3-33, WP-M3-54 |
| WP-M5-27 | Dokumentationspaket und Demonstration des Standardbetriebs | 1 | M | WP-M5-20, WP-M5-25, WP-M5-26, WP-M1-07, WP-M1-22, WP-M1-32, WP-M1-33, WP-M1-42, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07, WP-M3-56, WP-M4-11, WP-M4-18, WP-M4-25 |
| WP-M5-28 | Produktionsumgebung und gestaffeltes Rollout für den Pilot | – (Enabler) | M | WP-M5-03, WP-M5-04, WP-M5-05, WP-M5-06, WP-M5-14, WP-M5-22, WP-M0-11, WP-M0-14, WP-M1-04, WP-M1-39, WP-M1-40 |
| WP-M5-29 | Pilotbereitschaft: Abbruchkriterien, Rückfallbetrieb und Generalprobe | – (Enabler) | M | WP-M5-04, WP-M5-05, WP-M5-25, WP-M5-26, WP-M5-27, WP-M5-28 |
| WP-M5-30 | Barrierefreiheitsprüfung der übrigen Kernreisen (Fahrgast, Leitstelle, Flotte, Belege, Verwaltung) | – (Enabler) | M | WP-M5-18, WP-M1-43, WP-M1-44, WP-M2-35, WP-M2-42, WP-M2-43, WP-M2-47, WP-M3-03, WP-M3-36, WP-M3-56, WP-M4-38, WP-M4-44, WP-M4-59 |
| WP-M5-31 | Nachweisführung P1 und M5-Gate-Bericht | – (Enabler) | M | WP-M5-01 bis WP-M5-30, WP-M0-22, WP-M0-32, WP-M0-33, WP-M1-45, WP-M2-48, WP-M3-56, WP-M4-63 |

## Arbeitspakete

### WP-M5-01 – Detailverträge und Abnahmeplan M5

- **Ziel:** Vor Beginn der Härtung ist schriftlich festgelegt, was gemessen wird, an welchen Messpunkten, mit welchem Lastprofil und nach welchen Prüfplänen; alle 17 Anforderungen haben Verantwortlichen, Detailvertrag und mindestens einen ausführbaren Test (Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: keine Laufzeitsoftware; Dokumente unter `tests/performance` und `tests/acceptance`.
  - Kennzahlen: Definition der technischen Messgrößen (Verfügbarkeit, serverseitige Latenz je Endpunktklasse, Angebotslatenz, Latenz „Commit bis dargestellte Clientrevision“, Standortalter, RPO, RTO) mit Messpunkt, Zeitquelle, Aggregation und Ausschlüssen. Diese Messgrößen sind technische SLO-Größen und keine der 48 Kennzahlen aus `kataloge.csv`.
  - Konfigurationsparameter: Zuordnung, welche Katalogparameter die Messungen beeinflussen (`reporting_timezone`, `eta_max_data_age_sec`, `alert_escalation_delay_min`, `vehicle_unavailability_alert_min`).
- **Detailverträge vorab:** entfällt; dieses WP erstellt sie:
  1. Lastprofilvertrag (Requestmix, Peakfaktor, aktive Mandanten, Flotten- und Datenvolumen, Warm-/Cold-Cache) nach Kap. 08 §8.6 und Kap. 01 §1.8.
  2. Liste der „nicht-optimierenden Standard-API-Anfragen“ gegenüber optimierenden Aufrufen.
  3. Messpunktvertrag Verfügbarkeit (Proben, Messgrenze Plattform/Provider, Wartungsregel).
  4. Katalog „kritische Fehlerszenarien“ mit Alarm, Schwelle, Empfänger.
  5. Maskierungsregeln für Diagnosedaten.
  6. Prüfpläne Barrierefreiheit, Sicherheit, Restore, Fault-Injection.
  7. Protokollvorlagen mit den Pflichtfeldern aus Kap. 08 §8.4 (u. a. `source_key[]`, Build, Migrationsstand, Seed, Soll/Ist, Trace-IDs, Mangelklasse).
- **Akzeptanzkriterien:**
  1. Für jede der 17 Anforderungen dieser Einheit sind Verantwortlicher, Detailvertrag und mindestens ein positiver ausführbarer Test benannt und in `arbeitspakete.csv` (Spalten `detailvertrag`, `testfall`) eingetragen.
  2. Jeder vorläufige Zielwert ist mit seinem Status „vorläufig, D-05 offen“ oder „vom Auftraggeber bestätigt am …“ gekennzeichnet.
  3. Der Lastprofilvertrag nennt Spitzen-Requests/s und aktive Mandanten ausdrücklich; ein reiner Tagesdurchschnitt gilt als unvollständig (Kap. 08 §8.6).
  4. Die Zuordnung der Gate-Tests AT-23, AT-25, AT-53, AT-54, AT-59, AT-61 zu WPs dieser Datei ist mit dem Ergebnis aus WP-M0-33 abgeglichen.
- **Tests:** positiv: Review-Protokoll je Detailvertrag. negativ: ein Vertragsentwurf ohne Messpunkt oder ohne Test erfüllt die Definition of Ready nicht (Prüfung über WP-M0-32).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-32, WP-M0-33 (Detailvertragsprozess, bestätigte Testzuordnung, Nachverfolgungs-CI)
- **Entscheidungen:** D-05 (Peaklast, SLA, Hosting, Wartungsregel) blockiert die Verbindlichkeit der Zielwerte, nicht die Erstellung der Verträge; D-11 (Löschmatrix, Notbetrieb) blockiert die Prüfpläne für Export/Fristen nur in der Produktivfassung.
- **Größe:** M
- **Nicht im Umfang:** Durchführung der Messungen (WP-M5-10 bis WP-M5-16); Detailverträge fachlicher Funktionen aus M1 bis M4.

### WP-M5-02 – Staging- und Performance-Umgebung mit Referenzdatenbestand

- **Ziel:** Eine produktnahe Staging-Umgebung und eine getrennte Performance-Umgebung stehen mit mindestens zwei Mandanten und einem synthetischen Datenbestand in Referenzgröße bereit; Hardware und Datenvolumen sind protokolliert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `infra/` – Umgebungen Staging und Performance nach Kap. 01 §1.8 mit getrennten Secrets, PSP-Konten (Simulator bzw. Sandbox) und Identitätsdomänen; deklarative Infrastruktur; Protokoll der eingesetzten Hardware/Instanzgrößen.
  - Adapter/Worker: synthetischer Datengenerator unter `tests/performance` für je Mandant 250 Fahrzeuge, Fahrer, Schichten, Dienste, Bediengebiete, Haltepunkte, Tarife, Fahrgäste und einen Buchungsbestand in der Größenordnung mehrerer Betriebstage mit 30.000 Buchungen/Tag.
  - Datenbank: Befüllung ausschließlich über Migrationen und fachliche Importwege bzw. den Generator unter der Runtime-Rolle; betroffen u. a. `tenant`, `legal_entity`, `organization`, `membership`, `service`, `service_area`, `stop_revision`, `vehicle`, `staff_shift`, `resource_release`, `vehicle_run`, `capacity_hold`, `booking`, `fare_quote`, `travel_attempt`, `payment_intent`, `position_sample`.
- **Detailverträge vorab:** Lastprofilvertrag und Datenvolumen aus WP-M5-01; Seed-Regel (deterministischer Seed, im Protokoll genannt).
- **Akzeptanzkriterien:**
  1. Beide Umgebungen werden vollständig aus dem Repository aufgebaut; ein zweiter Aufbau ergibt denselben Migrationsstand.
  2. Der Datenbestand enthält mindestens zwei Mandanten mit je 250 Fahrzeugen; Zeilenzahlen je Tabelle sind im Umgebungsprotokoll festgehalten.
  3. Es werden keine Produktionsdaten verwendet (Kap. 01 §1.7); der Generator erzeugt keine echten Kontaktdaten.
  4. RLS ist aktiv; ein Stichprobenzugriff von Mandant A auf B-Daten in der befüllten Umgebung liefert nichts.
- **Tests:** positiv: Aufbau und Befüllung per Pipeline. negativ: Befüllung mit Migrator- oder Superuser-Verbindung wird vom Harness abgewiesen. konkurrierend (Datentrennung): parallele Befüllung beider Mandanten ohne Fremdreferenzen.
- **Abhängigkeiten:** intern: WP-M5-01.
  - extern: WP-M0-11, WP-M0-25, WP-M0-23 (IaC- und Umgebungsgerüst, Gerüst `tests/performance`, PSP-Simulator)
  - extern: WP-M1-40 (Trennung von Test- und Produktionsdaten, synthetischer Seed-Generator für Mandanten)
  - extern: WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07, WP-M2-23 (Dienste, Gebiete, Haltepunkte, Tarife, Buchungskern als befüllbare Objekte)
  - extern: WP-M3-03, WP-M3-05, WP-M3-34 (Fahrzeug- und Fahrerstamm, Positionserfassung)
  - extern: WP-M4-03, WP-M4-14, WP-M4-08, WP-M4-22, WP-M4-33, WP-M4-17 (Schichten und veröffentlichter Dienstplan, Zeitereignisse, Belege und Kostenereignisse als befüllbare Objekte; Fahrerschicht als Voraussetzung der Disponierbarkeit)
- **Entscheidungen:** D-05 (Hostingregion, Flottenzahl, Regionen) blockiert nur die Übertragbarkeit der Messwerte auf die Produktivumgebung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Produktionsumgebung und gestaffeltes Rollout (WP-M5-28); Einspielung realer Pilotstammdaten (WP-M5-25, WP-M5-29).

### WP-M5-03 – Telemetrie-Vollausbau: Metriken, Traces, Logs je Komponente

- **Ziel:** Jede Serverkomponente (Fachkern, Outbox-Relay, Routing-Worker, Dokumenten-Worker, Gateway/BFF) liefert Metriken, Traces und strukturierte Logs in eine zentrale Betriebsplattform; ein Vorgang ist über Komponenten hinweg per Korrelations-ID verfolgbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Instrumentierung aller Module über `backend/platform` (OpenTelemetry, W3C `traceparent`, Correlation-ID nach Kap. 05 §5.1); technische Metriken für HTTP-Latenz je Endpunktklasse, Fehlerraten, Datenbankpool, Sperrwartezeiten bei Hold/Confirm, Alter und Tiefe von `outbox`, Rückstand `inbox`, offene und überfällige `operation`-Leases, Zahl der `payment_intent` in unbestimmtem Zustand.
  - Adapter/Worker: Instrumentierung `workers/routing` (Solverlaufzeit, Timeouts, Queue-Wartezeit) und `workers/documents`; RabbitMQ-, Redis- und PostgreSQL-Exporter; zentrale Sammlung, Dashboards und Aufbewahrung unter `infra/`.
  - Ereignisse/Outbox: Messung der Verzögerung zwischen Commit und Relay-Veröffentlichung.
- **Detailverträge vorab:** Namens- und Labelkonvention; Verbot von Personen-/Ortssuchen und Fahrgastkontakten als Metriklabels (Kap. 01 §1.1, Kap. 05 §5.1); Aufbewahrungsdauer der Telemetrie (im Pflichtenheft nicht festgelegt, siehe OP-08).
- **Akzeptanzkriterien:**
  1. Für jede in Kap. 01 §1.2 genannte Serverkomponente sind Metriken, Traces und Logs in der Betriebsplattform sichtbar.
  2. Eine Buchung vom Gateway bis zum Routing-Worker ist als ein zusammenhängender Trace auffindbar.
  3. Eine automatisierte Prüfung findet in Metriklabels keine Kontaktdaten, Namen oder Suchbegriffe.
  4. Die Telemetrie ist mandantenfähig auswertbar, ohne dass ein Mandant fremde Telemetrie einsehen kann (Betriebszugang getrennt von Mandantenrollen).
- **Tests:** positiv: Trace-Durchstich je Komponente. negativ: ungültiger `traceparent` erzeugt neue Correlation-ID; Label mit Testkontakt wird vom Prüfskript erkannt. konkurrierend: Telemetrieausfall (Sammler gestoppt) beeinträchtigt keine Buchung.
- **Abhängigkeiten:** intern: WP-M5-01.
  - extern: WP-M0-10 (Observability-Grundgerüst)
  - extern: WP-M1-02, WP-M1-14, WP-M1-15 (API-Grundgerüst mit Tracing, Outbox-Relay, `operation`-Ressource)
  - extern: WP-M2-17, WP-M2-10 (Routing-Worker und Routingadapter als zu instrumentierende Komponenten)
  - extern: WP-M4-21, WP-M4-27 (Dokumenten-Worker (Malware-Prüfung, OCR) als zu instrumentierende Komponente)
- **Entscheidungen:** D-05 (Hosting, Betriebsmodell) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Alarmregeln (WP-M5-04); Verfügbarkeitsbericht (WP-M5-05); Maskierung (WP-M5-06); fachliche Kennzahlen aus `kataloge.csv` (M4).

### WP-M5-04 – Alerting für kritische Fehlerszenarien

- **Ziel:** Für die im Abnahmeplan definierten kritischen Fehlerszenarien entsteht jeweils ein Alarm, der einen zuständigen Empfänger erreicht und im Betriebshandbuch einen Handlungsschritt hat.
- **Anforderungen:** `LH-6.19-NFR-008`
- **Liefergegenstände:**
  - Adapter/Worker: Alarmregeln als Code unter `infra/` für mindestens: Ausfall der Kernbuchung, Datenbank nicht erreichbar bzw. Replikationsverzug, Stau in `outbox`, Messaging nicht erreichbar, Routing-Worker ohne Antwort bzw. Solver-Timeouts über Schwelle, PSP nicht erreichbar bzw. `payment_intent` länger als Schwelle unbestimmt, überfällige `operation`-Leases, Redis-Ausfall, ablaufende Zertifikate, Fehlerratenanstieg je Endpunktklasse. Die Regeln „fehlgeschlagenes Backup“ und „Archivierungs-/Replikationsverzug“ stehen im Katalog, werden aber erst mit den Metriken aus WP-M5-14 dort geliefert und ausgelöst (vermeidet eine zyklische Abhängigkeit).
  - Backend: Übergabe betrieblich relevanter technischer Alarme an die Fachalarmierung, soweit vorgesehen (`business_alert`, `alert_policy_revision`, `alert_acknowledgement` aus Kap. 04 §4.4) – Abgrenzung siehe OP-07.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `vehicle_unavailability_alert_min` (nur soweit technische Alarme in die fachliche Eskalation übergehen).
- **Detailverträge vorab:** Katalog „kritische Fehlerszenarien“ aus WP-M5-01 mit Schwelle, Schweregrad, Empfänger, Eskalationsstufe, Runbook-Verweis; Dedupe- und Stummschaltregel.
- **Akzeptanzkriterien:**
  1. Serverkomponenten sind über Metriken, Traces und Logs beobachtbar; je Komponente ist mindestens eine Alarmregel aktiv. (NFR-008)
  2. Für jedes Szenario des Katalogs wird durch gezielte Auslösung ein Alarm erzeugt und beim definierten Empfänger zugestellt; Auslösezeit und Zustellzeit sind protokolliert. Die Abnahme von NFR-008 ist erst vollständig, wenn auch die in WP-M5-14 gelieferten Backup-/Replikationsalarme ausgelöst wurden; der Katalogabgleich „Szenario ↔ ausgelöster Alarm“ weist keine Lücke aus. (NFR-008)
  3. Ein anhaltender Fehler erzeugt genau einen offenen Alarm und keine Alarmflut; nach Behebung schließt der Alarm.
  4. Alarmtexte enthalten keine Fahrgast- oder Personaldaten.
  5. Ein Ausfall der Alarmierungskette selbst (Sammler oder Zustellweg gestoppt) wird über eine unabhängige Totmannprüfung bemerkt.
  6. Anlegen, Ändern, Stummschalten und Quittieren von Alarmregeln ist nur Betriebsrollen möglich und nachvollziehbar protokolliert.
- **Tests:** positiv: je Szenario eine Auslösung. negativ: Schwelle knapp unterschritten erzeugt keinen Alarm; stummgeschalteter Wartungszeitraum erzeugt keinen Ruf, bleibt aber protokolliert. konkurrierend (Sicherheit): zwei gleichzeitige Ausfälle erzeugen zwei unterscheidbare Alarme.
- **Abhängigkeiten:** intern: WP-M5-03.
  - extern: WP-M1-14, WP-M1-15, WP-M1-41 (Outbox-Relay, `operation` mit Lease/Fencing, Sicherheitsvorfallprozess)
  - extern: WP-M2-30, WP-M2-17 (Zahlungsautomat mit unbestimmtem Zustand, Routing-Worker mit Timeout-Verhalten)
  - extern: WP-M3-52 (Alarm-Grundlage mit Dedupe und Quittierung)
- **Entscheidungen:** D-05 (Supportzeiten, Betrieb) blockiert die Produktivschaltung der Rufbereitschaft, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Regelbasierte Fachalarme und Empfehlungen (AUT P2, M8); Fault-Injection-Kampagne (WP-M5-16).

### WP-M5-05 – Verfügbarkeitsmessung mit synthetischen Proben und Monatsbericht

- **Ziel:** Die Verfügbarkeit von Kernbuchung und Operator wird von außen an vereinbarten Messpunkten gemessen; ein Monitoringbericht berechnet daraus die Monatsverfügbarkeit außerhalb vertraglich definierter Wartung.
- **Anforderungen:** `LH-6.19-NFR-001`
- **Liefergegenstände:**
  - Adapter/Worker: externe synthetische Proben unter `infra/` für die Kernbuchung (Suche und Buchung eines Probemandanten: `/v1/ride-offers`, `/v1/bookings`) und für den Operator (Anmeldung, `/v1/me`, `/v1/operator/vehicles`); getrennte Kennzeichnung von Plattform- und Providerfehlern (Kap. 01 §1.8); Wartungskalender.
  - Adapter/Worker: Berichtsgenerator „Monatsverfügbarkeit“ mit Formel, Messpunkten, Probenintervall, Wartungsfenstern, Ausfallminuten und Rohdatenverweis.
  - Datenbank: Probemandant mit eigenem Dienst und Probefahrzeug; Probebuchungen werden als solche markiert und aus Fachstatistik ausgeschlossen (Ausschlussregel siehe OP-03).
- **Detailverträge vorab:** Messpunktvertrag aus WP-M5-01 (Probenintervall, Erfolgskriterium, Messgrenze, Wartungsregel nach D-05); Formel der Monatsverfügbarkeit; Behandlung fehlender Proben.
- **Akzeptanzkriterien:**
  1. Der Monitoringbericht berechnet die Monatsverfügbarkeit für Kernbuchung und Operator getrennt aus den vereinbarten Messpunkten und weist das Ziel von mindestens 99,9 % pro Kalendermonat als erreicht oder verfehlt aus. (NFR-001)
  2. Ein in Staging künstlich erzeugter Ausfall bekannter Dauer erscheint im Bericht mit dieser Dauer (Toleranz ein Probenintervall); ein eingetragenes Wartungsfenster wird herausgerechnet und im Bericht ausgewiesen. (NFR-001)
  3. Ein Ausfall der Probe selbst wird als „keine Messung“ und nicht als Verfügbarkeit gewertet.
  4. Probebuchungen belegen keine Kapazität dauerhaft und erscheinen nicht in `Anfragen`, `Buchungskonversion` oder anderen Fachkennzahlen.
- **Tests:** positiv: Berichtslauf über einen Testzeitraum mit bekannter Ausfallzeit. negativ: Providerfehler (PSP-Simulator down) wird getrennt ausgewiesen; Probe mit ungültigen Zugangsdaten zählt nicht als Plattformausfall, sondern löst einen Probenalarm aus. konkurrierend (Kapazität): Probebuchung parallel zu echter Buchung auf dem letzten Platz verdrängt keine echte Buchung (Probefahrzeug getrennt).
- **Abhängigkeiten:** intern: WP-M5-03, WP-M5-04.
  - extern: WP-M1-06, WP-M1-44 (Anmeldung und `/v1/me`)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26 (Angebot, Hold, Confirm und Storno der Einbetreiberbuchung)
  - extern: WP-M3-36 (Leitstellenansicht Livebetrieb – `/v1/operator/vehicles`)
  - extern: WP-M4-47, WP-M4-46 (Versuchseintrag je Fahrtanfrage und Kennzahlregister, in denen Probevorgänge ausgeschlossen werden müssen)
- **Entscheidungen:** D-05 (SLA, Wartungsregel, Messgrenzen) blockiert die vertragliche Verbindlichkeit des Berichts, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Nachweis des 99,9-%-Ziels über einen vollen Produktivmonat (erst im Pilotbetrieb möglich, OP-02); Einrichtung der Proben in der Produktionsumgebung (WP-M5-28); Hochverfügbarkeitsumbau der Infrastruktur.

### WP-M5-06 – Maskierung von Diagnosedaten

- **Ziel:** Logs, Traces, Fehlerberichte und Alarmtexte enthalten keine ungeschützten Zahlungsdetails und keine unnötigen Kontaktinformationen; eine wiederholbare Stichprobenprüfung belegt das.
- **Anforderungen:** `LH-6.19-NFR-015`
- **Liefergegenstände:**
  - Backend: zentrale Maskierungsschicht in `backend/platform` für Logausgabe, Trace-Attribute und `application/problem+json`-Details; Positivliste protokollierbarer Felder je Modul; keine Request-/Response-Bodies personenbezogener Endpunkte im Log.
  - Adapter/Worker: gleiche Regeln in `workers/routing`, `workers/documents`, PSP-Adapter und Benachrichtigungsadapter; Mobile/Web: Fehlerberichte der Clients ohne Kontaktdaten.
  - Adapter/Worker: Prüfskript „Log-Stichprobe“ (Mustersuche nach Kartennummern, IBAN, E-Mail, Telefonnummer, Adresse, Token, Boarding-Code) als CI-Schritt und als Betriebsprüfung gegen die zentrale Logablage.
- **Detailverträge vorab:** Maskierungsregeln aus WP-M5-01 (Feldklassen, Ersatzdarstellung, zulässige technische Bezeichner wie UUID); Abgrenzung „unnötige Kontaktinformation“ (OP-09).
- **Akzeptanzkriterien:**
  1. Eine Stichprobe der Logs aus einem vollständigen E2E-Lauf mit Testkontakten und Testzahlungen zeigt, dass die Maskierungsregeln eingehalten sind: kein Treffer für Zahlungsdetails oder Kontaktdaten im Klartext. (NFR-015)
  2. Die Stichprobe umfasst Fachkern, Worker, Gateway, PSP-Adapter und Client-Fehlerberichte; Umfang, Zeitraum und Trefferliste sind protokolliert. (NFR-015)
  3. Ein absichtlich eingebauter Verstoß (Testkontakt im Log) wird vom Prüfskript erkannt und lässt den CI-Schritt fehlschlagen.
  4. `audit_event` und Sicherheitsereignisse bleiben trotz Maskierung über IDs nachvollziehbar.
- **Tests:** positiv: Stichprobe ohne Befund. negativ: Fehlerpfade (Validierungsfehler mit Kontaktfeld, PSP-Fehlerantwort, Ausnahme mit Objektinhalt) hinterlassen keine Klartextdaten; Stacktrace erscheint nicht in der API-Antwort. konkurrierend (Sicherheit/Datentrennung): Logs zweier Mandanten unter Last enthalten keine vertauschten Mandantenbezüge.
- **Abhängigkeiten:** intern: WP-M5-03.
  - extern: WP-M1-12, WP-M1-37, WP-M1-31 (Audit-Maskierung, Zahlungsdatentrennung mit Erkennungsmuster, Feldschutz)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff, Aufbewahrung) blockiert nur die Produktivschaltung der Logaufbewahrung.
- **Größe:** M
- **Nicht im Umfang:** Feldschutz in der API (M1); Lösch- und Auskunftsjobs (M8).

### WP-M5-07 – Zentrale Zeitzonen- und Rundungsregeln mit Referenztests

- **Ziel:** Zeit- und Preisberechnungen aller P1-Module verwenden eine einzige zentral definierte Regelbibliothek; Zeitumstellungs- und Rundungsreferenztests laufen in CI und liefern reproduzierbare Ergebnisse.
- **Anforderungen:** `LH-6.19-NFR-012`
- **Liefergegenstände:**
  - Backend: Geld- und Zeittypen in `backend/platform` als einzige zulässige Quelle; Regeln nach Kap. 05 §5.1 (UTC in der Datenbank, IANA-Zone, RFC 3339 mit Offset, DST-Lücke ablehnen, doppelte Ortszeit nur mit Offset) und Kap. 03 §2.1 (`bigint` in kleinster Einheit, Steuerrundung Standard `ROUND_HALF_UP` je Steuergruppe, Largest-Remainder-Umlage mit stabilem Gleichstand); Architekturtest, der eigene Rundung, Gleitkomma für Geld und `LocalDateTime` ohne Zone in Fachmodulen verbietet.
  - Backend: Regelübersicht „Zeit und Rundung“ als versioniertes Dokument mit Fundstelle je Regel und nutzenden Modulen (Tarif, Zahlung, Storno, Schichtdauer, Kostenumlage, KPI-Periode).
  - Konfigurationsparameter: `reporting_timezone` (Betriebstag und Periodenbasis).
  - Datenbank: Prüfung, dass Geldspalten in `fare_quote`, `payment_intent`, `economic_event`, `allocation_line`, `journal_line` ganzzahlig sind und Zeitspalten `timestamptz` verwenden.
- **Detailverträge vorab:** Referenztestkatalog mit festen Eingaben und erwarteten Ergebnissen; Preisrundungsregel der Tarifengine für streckenabhängige Preise (im Pflichtenheft nicht ausdrücklich festgelegt, OP-10).
- **Akzeptanzkriterien:**
  1. Zeitumstellungsreferenztests bestehen: 25.10.2026 02:30 `Europe/Berlin` mit +02:00 und +01:00 sind zwei Zeitpunkte; eine Ortszeit in der Frühjahrslücke wird abgelehnt; Schicht- und Fahrtdauern über die Umstellung ergeben sich aus absoluten Zeitpunkten. (NFR-012)
  2. Rundungsreferenztests bestehen: 100,00 EUR auf drei gleiche Anteile ergibt 33,34 / 33,33 / 33,33 EUR bei IDs A < B < C; 100,01 EUR ergibt 33,34 / 33,34 / 33,33 EUR; Summen sind exakt; die Rücknahme spiegelt die gespeicherten Teilbeträge. (NFR-012)
  3. Dieselben Eingaben ergeben in zwei Läufen, auf zwei Instanzen und nach Neustart identische Ergebnisse. (NFR-012)
  4. Der Architekturtest schlägt bei einer Probeberechnung mit Gleitkomma oder modulinterner Rundung fehl.
- **Tests:** positiv: Referenzkatalog. negativ: Betrag außerhalb ±9.007.199.254.740.991; Währung mit falschem Exponenten; Zeit ohne Offset. konkurrierend (Geld): parallele Umlage desselben Kostenereignisses liefert identische Zeilen und genau eine wirksame Umlage. Trägt bei zu PH-AT-013 und PH-AT-018 (dort jeweils im liefernden Meilenstein bestanden).
- **Abhängigkeiten:** intern: WP-M5-01.
  - extern: WP-M1-18 (Zeit-, Zeitzonen- und DST-Baustein)
  - extern: WP-M2-08, WP-M2-09 (Tarifengine und `fare_quote`)
  - extern: WP-M4-35, WP-M4-10, WP-M4-45 (centgenaue Belegaufteilung mit Largest Remainder, Zeitrechnung über Mitternacht/Zeitzone/DST, Analytics-Faktenbasis mit Periodenbezug)
- **Entscheidungen:** D-07 (einheitliche Kostenbewertung) und D-06 (Steuer-/Kontenregeln) blockieren nur die Produktivfreigabe abweichender Rundungsregeln.
- **Größe:** M
- **Nicht im Umfang:** Fremdwährungsumrechnung (P2); neue Fachregeln für Tarife oder Arbeitszeit.

### WP-M5-08 – Standortfrische, Qualitätsflag und Veraltet-Kennzeichnung

- **Ziel:** Jede Positionsmeldung trägt Uhrzeit und Qualitätsflag; im Normalbetrieb sind angezeigte Positionen jünger als 30 s, und ältere Positionen sind in Leitstelle und Fahrgastansicht sichtbar als veraltet markiert.
- **Anforderungen:** `LH-6.19-NFR-005`
- **Liefergegenstände:**
  - Backend: Härtung der Positionskette aus M3: Messzeit und Empfangszeit getrennt, Qualitätsflag je `position_sample`, serverseitige Altersberechnung gegen Serverzeit; Messung der Kette „Erfassung → Empfang → Anzeige“.
  - Datenbank: `position_sample` (Mess-/Empfangszeit, Qualität laut Kap. 04 §4.4); Prüfung des Index- und Mengenverhaltens unter Referenzlast (250 Fahrzeuge je Mandant).
  - Oberfläche: UI-07 (Operator Livebetrieb, Zustand „veralteter Standort“ aus Kap. 06 §6.9) und UI-04 (Reiseverfolgung) zeigen Standortzeit und eine Veraltet-Kennzeichnung, die nicht nur über Farbe erfolgt (Kap. 06 §6.1).
  - Kennzahlen: technische Messgröße Standortalter (p95, Anteil > 30 s, Lücken); als Datenqualitätsbasis für `Fahrzeugverfügbarkeit` und die Pünktlichkeitskennzahlen relevant, ohne deren Definition zu ändern.
  - Konfigurationsparameter: kein Katalogparameter für die Veraltet-Schwelle vorhanden (`eta_max_data_age_sec` gilt auf Ebene „Modell“); Schwelle 30 s als vorläufiges Lastenheftziel, siehe OP-11.
- **Detailverträge vorab:** Wertebereich des Qualitätsflags; Schwelle und Darstellung „veraltet“; Sendeintervall der Driver-App (D-05 „GPS-Frequenz“); Messprotokoll nach Kap. 08 §8.6 (Erfassungs-/Empfangszeit, Lücken, sichtbares Veraltet-Flag).
- **Akzeptanzkriterien:**
  1. Jede gespeicherte Position hat Uhrzeit (Messzeit und Empfangszeit) und ein Qualitätsflag; eine Meldung ohne diese Angaben wird abgewiesen. (NFR-005)
  2. Die Anzeige markiert mindestens einen künstlich veralteten Standort sichtbar als veraltet, mit Zeit der letzten Position und ohne fiktive Fortschreibung. (NFR-005)
  3. Unter Referenzlast im Normalbetrieb liegt das Standortalter in der Anzeige unter 30 s; das Messprotokoll weist p95, Lücken und Überschreitungen aus. (NFR-005)
  4. Eine Position mit Messzeit in der Zukunft oder mit grober Uhrenabweichung wird über das Qualitätsflag als nicht verwendbar geführt und nicht als frisch angezeigt (GPS-Messzeit ist keine vertrauenswürdige Serverzeit, Kap. 01 §1.5; Wertebereich des Flags siehe OP-11).
  5. Die Veraltet-Kennzeichnung ist per Screenreader wahrnehmbar und in der Listenalternative zur Karte enthalten (Kap. 06 §6.1).
  6. Die Fahrgastansicht (UI-04) zeigt die Fahrzeugposition nur für die eigene aktive Buchung; ein veralteter Standort wird dort nicht als Live-Position und nicht als Grundlage einer laufenden ETA dargestellt.
- **Tests:** positiv: laufender Positionsstrom. negativ: Strom unterbrochen (wie AT-10, in M3 bestanden; hier unter Last wiederholt); Meldung in falscher Reihenfolge; Meldung eines Fahrers für ein nicht zugeordnetes Fahrzeug. konkurrierend (Datentrennung): Positionsströme zweier Mandanten parallel – keine Position erscheint im fremden Mandanten.
- **Abhängigkeiten:** intern: WP-M5-03, WP-M5-10 (Referenzlast für Kriterium 3).
  - extern: WP-M2-43 (UI-04 Buchungsdetail mit Status und ETA)
  - extern: WP-M3-34, WP-M3-35, WP-M3-36 (Positionserfassung und Standortalter, Live-Aktualisierungskanal, Leitstelle Livebetrieb)
- **Entscheidungen:** D-05 (GPS-Frequenz) und D-09 (Telematik) blockieren die endgültige Schwelle; D-11 (Aufbewahrung der Rohpositionen) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrzeugtelematik-Adapter; `driving_observation` für ETA (M8); Partitionierung von `position_sample` sofern die Messung sie nicht erzwingt.

### WP-M5-09 – Netzwerkzustand und degradierte Funktion in den Apps

- **Ziel:** Driver-App und Passenger-PWA zeigen den Netzwerkzustand und jede eingeschränkte Funktion verständlich an; offline bleiben die relevanten letzten Aufträge mit ihrem Datenstand sichtbar, ohne Echtzeit vorzutäuschen.
- **Anforderungen:** `LH-6.19-NFR-009`
- **Liefergegenstände:**
  - Oberfläche: UI-09 und UI-10 (Driver): Offlinebanner mit Zeit des letzten Plans (Kap. 06 §6.7), getrennte Zustände „lokal gespeichert“, „synchronisiert“, „freigegeben“; gesperrte Aktionen (Einsatzfreigabe, Aufhebung einer Sperre) mit Begründung statt stiller Deaktivierung.
  - Oberfläche: UI-04 (Passenger): bei Verbindungsverlust letzter bekannter Buchungsstatus mit Datenstand; ETA und Fahrzeugposition als „Stand hh:mm“ statt live; UI-03: Checkout ohne Verbindung nicht abschließbar, kein vorgetäuschter Erfolg.
  - Oberfläche: UI-07 (Operator): Verbindungsverlust des Livekanals als sichtbarer Zustand mit Datenstand.
  - Backend: Datenstand-/Revisionsangabe in den Antworten, die die Clients für die Anzeige benötigen (`/v1/driver/shifts/{id}/run`, `/v1/bookings`).
- **Detailverträge vorab:** Zustandsautomat Verbindungszustand (online, eingeschränkt, offline, Synchronisation läuft, Synchronisationskonflikt) mit Texten; Liste der je Zustand erlaubten und gesperrten Aktionen nach Kap. 01 §1.5; Definition „relevante letzte Aufträge“ (OP-12).
- **Akzeptanzkriterien:**
  1. Im Offline-Test zeigt die Driver-App den zuletzt bestätigten Dienst und die Stoppliste mit Zeit des Datenstands; keine Anzeige behauptet Echtzeit (keine laufende ETA, keine „live“-Kennzeichnung). (NFR-009)
  2. Der Netzwerkzustand ist in beiden Apps jederzeit erkennbar und per Screenreader zugänglich; der Wechsel wird über eine Live-Region gemeldet, nicht nur über Farbe. (NFR-009)
  3. Jede offline nicht verfügbare Funktion nennt den Grund und den nächsten möglichen Schritt; neue verbindliche Buchung, Fahrzeugfreigabe und Aufhebung einer Sperre sind offline nicht möglich. (NFR-009)
  4. Nach Wiederverbindung verschwindet der Offlinezustand erst, wenn der Serverstand geladen ist; ausstehende Ereignisse werden als „wird übertragen“ gezeigt.
- **Tests:** positiv: Verbindungsverlust und Rückkehr auf Zielgerät und im Browser. negativ: instabile Verbindung (Paketverlust, hohe Latenz) führt nicht zu flackernder Anzeige oder doppelten Aktionen; Checkout-Abbruch mitten in der Zahlung zeigt „Ergebnis wird geprüft“ statt Erfolg oder Fehler. konkurrierend: Offline erfasster Check-in trifft auf serverseitiges Storno – Klärfall statt stillem Überschreiben (Verhalten aus M3, hier Anzeige geprüft). Wiederholt AT-11 als Anzeigeprüfung.
- **Abhängigkeiten:** intern: WP-M5-01.
  - extern: WP-M2-35, WP-M2-42, WP-M2-43 (Passenger-PWA mit Buchungsdetail und Checkout)
  - extern: WP-M3-24, WP-M3-26, WP-M3-33, WP-M3-35 (Driver-App-Basis mit Outbox, Stoppliste, Offline-Entwürfe, Live-Kanal)
- **Entscheidungen:** D-11 (Notbetrieb, Offline-Umfang) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Offline-Queue und Deduplikation selbst (M3); native Passenger-App (M7).

### WP-M5-10 – Referenzlastprofil und Skalierungstest

- **Ziel:** Das vereinbarte Referenzprofil (initial 250 Fahrzeuge je Mandant, 30.000 Buchungen/Tag, 5.000 parallele Sessions) ist als ausführbares Lastszenario mit mindestens zwei gleichzeitig aktiven Mandanten umgesetzt, und ein Skalierungstest dokumentiert die Performance unter diesem Profil.
- **Anforderungen:** `LH-6.19-NFR-006`
- **Liefergegenstände:**
  - Adapter/Worker: Lastszenarien unter `tests/performance`: Fahrgastsitzungen (Suche, Angebot, Hold, Confirm, Storno, Buchungsstatus), Fahrersitzungen für 250 Fahrzeuge je Mandant (Positionsmeldungen, `/v1/driver/events`, Planabruf), Leitstellensitzungen (Listen, Livekanal), Hintergrundlast (Outbox, Benachrichtigungen, KPI-Projektion); Tagesganglinie mit Peak; Skalierungsstufen (50 %, 100 %, Überlast) und Lauf mit zwei Mandanten gleichzeitig.
  - Adapter/Worker: Messbericht mit Perzentilen, Fehlerrate, Durchsatz, Sättigung (CPU, Speicher, DB-Verbindungen, Sperrwartezeiten, Queue-Tiefe), Hardware, Datenvolumen, Build, Migrationsstand und Seed.
  - Kennzahlen: Kontrolle, dass Lasttestverkehr die Fachkennzahlen `Anfragen`, `Technische Anfragefehler` und `Angebotsquote` der Performance-Umgebung plausibel füllt (Gegenprobe zur Zählung, keine Abnahme der KPI-Definition).
- **Detailverträge vorab:** Lastprofilvertrag aus WP-M5-01 (Requestmix, Peakfaktor, Sitzungsdefinition „parallele Session“, Mandantenzahl); Abbruchkriterien eines Laufs; Berichtsformat aus WP-M0-25.
- **Akzeptanzkriterien:**
  1. Der Skalierungstest läuft mit 250 Fahrzeugen je Mandant, einer Buchungsrate entsprechend 30.000 Buchungen/Tag einschließlich vereinbartem Peak und 5.000 parallelen Sessions und dokumentiert die Performance unter dem vereinbarten Referenzprofil. (NFR-006)
  2. Der Lauf erfolgt mit mindestens zwei Mandanten gleichzeitig; der Bericht weist die Werte je Mandant und gesamt aus (Kap. 01 §1.8). (NFR-006)
  3. Unter Last tritt keine Überbuchung auf: kein Kapazitätsabschnitt über Kapazität, keine doppelte Bestätigung, keine Buchung im fremden Mandanten; die Prüfung erfolgt nach dem Lauf per Invariantenabfrage (Kap. 04 §4.5).
  4. Abweichungen vom Profil oder von den Zielen sind mit Ursache, Engpass und Maßnahme im Bericht ausgewiesen; die Überlaststufe zeigt kontrollierte Degradation (429 mit Retry-After, keine Datenkorruption).
- **Tests:** positiv: Profil 100 %. negativ: Überlaststufe; Lauf mit kaltem Cache. konkurrierend (Kapazität, Datentrennung): 100 parallele Bestätigungen auf den letzten Platz während der Grundlast (Szenario aus PH-AT-004 unter Last); zwei Mandanten mit identischen Objektnamen parallel.
- **Abhängigkeiten:** intern: WP-M5-02, WP-M5-03.
  - extern: WP-M0-25, WP-M0-26, WP-M0-27 (Gerüst `tests/performance`, Spike-Messwerte Hold/Confirm und Solver)
  - extern: WP-M2-48 (fortlaufender Lasttest und seine Szenarien)
- **Entscheidungen:** D-05 (Peak-Anfragen/s, gleichzeitige Suchen/Buchungen, Mandantenzahl, Hosting) – ohne Beschluss wird mit dem Arbeitsprofil aus WP-M5-01 gemessen; das Ergebnis gilt dann nicht als Abnahme des Betriebs-Gates.
- **Größe:** M
- **Nicht im Umfang:** Einzelnachweise der p95-Ziele (WP-M5-11 bis WP-M5-13); Lastanteile von Föderation und Partnern (P2, M6).

### WP-M5-11 – Standard-API p95 ≤ 1 s unter Referenzlast

- **Ziel:** Für die nicht-optimierenden Standard-API-Anfragen ist nachgewiesen, dass 95 % bei Referenzlast serverseitig in höchstens 1 s antworten; Engpässe sind behoben oder als Abweichung ausgewiesen.
- **Anforderungen:** `LH-6.19-NFR-002`
- **Liefergegenstände:**
  - Backend: Optimierungen aus den Messbefunden (Indizes nach Kap. 04 §4.7, Abfragepläne, N+1, Poolgrößen, Cache-Schlüssel mit Mandantenbezug); keine Änderung fachlicher Verträge.
  - API: Messung je Endpunktklasse, mindestens `/v1/me`, `/v1/bookings` (Lesen, Confirm, Storno), `/v1/services`, `/v1/service-areas`, `/v1/stops`, `/v1/operator/vehicles`, `/v1/driver/events`, `/v1/driver/shifts/{id}/run`, `/v1/inspections`, `/v1/defects`, `/v1/rosters`, `/v1/time-events`, `/v1/expenses/{id}`, `/v1/config/effective`, `/v1/operations/{operation_id}`, `/v1/analytics/metrics`.
  - Datenbank: nachgezogene Indizes als Migration; Prüfung der RLS-Policies auf Planverträglichkeit unter der Runtime-Rolle.
- **Detailverträge vorab:** Liste „nicht-optimierende Standard-API“ aus WP-M5-01; Messpunkt „serverseitig“ (Eingang Gateway bis Antwortende); Behandlung asynchroner Antworten (`202` mit `operation`).
- **Akzeptanzkriterien:**
  1. Das Lasttestprotokoll nennt Perzentile (mindestens p50, p95, p99) je Endpunktklasse, die Hardware und das Datenvolumen. (NFR-002)
  2. Bei Referenzlast liegt p95 der nicht-optimierenden Standard-API-Anfragen serverseitig bei höchstens 1 s; jede Endpunktklasse, die das Ziel verfehlt, ist einzeln mit Ursache und Maßnahme ausgewiesen. (NFR-002)
  3. Die Messung erfolgt mit RLS und unter der nicht privilegierten Runtime-Rolle, mit zwei Mandanten und dem Datenvolumen aus WP-M5-02.
  4. Eine Optimierung verändert kein Autorisierungsergebnis: Die Isolationssuite (PH-AT-001) bleibt nach jeder Optimierung grün.
  5. Jede nachgezogene Index-Migration läuft als expand/contract ohne Sperre, die Buchungen blockiert, und ist frisch sowie aus dem freigegebenen Vorstand migrierbar.
- **Tests:** positiv: Messlauf. negativ: Listenabfrage mit `limit` 100 und tiefer Paginierung; Abfrage auf fremde UUID bleibt 404 und gleich schnell (kein Zeitseitenkanal über die Schwelle hinaus). konkurrierend (Datentrennung): Cache-Treffer von Mandant A wird nie an Mandant B ausgeliefert (gezielter Test mit identischen Pfaden).
- **Abhängigkeiten:** intern: WP-M5-10.
  - extern: WP-M1-02, WP-M1-01, WP-M1-45 (API-Grundgerüst, Tenantkontext/RLS, Isolationssuite)
  - extern: WP-M2-48 (Buchungs- und Stammdatenendpunkte – Gate-Nachweis)
  - extern: WP-M3-56 (Fahrer-, Prüf-, Schaden- und Leitstellenendpunkte)
  - extern: WP-M4-14, WP-M4-08, WP-M4-22, WP-M4-46, WP-M4-63 (Dienstplan-, Zeit-, Beleg- und Kennzahlendpunkte)
- **Entscheidungen:** D-05 (Hardware/Hosting, Peak) blockiert die Abnahmewirkung, nicht die Messung.
- **Größe:** M
- **Nicht im Umfang:** Angebotsbildung (WP-M5-12); Berichts- und Exportjobs (asynchron, nicht Teil der 1-s-Klasse).

### WP-M5-12 – Fahrtangebote p95 ≤ 5 s unter Referenzlast

- **Ziel:** Für Verfügbarkeits-/Fahrtangebote ist mit dokumentierter Testflotte nachgewiesen, dass 95 % innerhalb von 5 s beantwortet werden, oder die Abweichung ist ausgewiesen.
- **Anforderungen:** `LH-6.19-NFR-003`
- **Liefergegenstände:**
  - Backend: Messung und Abstimmung der Angebotskette `/v1/ride-offers` (Zulässigkeitsprüfung, Kandidatenfilter, Routingmatrix, Einfügeheuristik, Tarif, Angebotsobjekt); Zeitbudget als Planungsdefault aus Kap. 01 §1.4 (1 s lokale Kandidatensuche, Reserve; das Partnerfenster von 3 s ist in P1 ohne Partner ungenutzt).
  - Adapter/Worker: `workers/routing` – Skalierung der Worker, Queue-Wartezeit, Solver-Timeout-Verhalten; Routingadapter mit Warm-/Cold-Cache (`route_matrix_cache`).
  - Kennzahlen: Gegenprobe, dass Solver-Timeouts als `Technische Anfragefehler` bzw. begründete Ablehnung gezählt werden und nicht als `Kapazitätsablehnungen`.
- **Detailverträge vorab:** Beschreibung der Testflotte (Fahrzeuge, Ressourcenvektoren, Vorbelegung der Fahrtenpläne, Gebietsgröße, Routingdatenstand); Messpunkt (serverseitig oder bis Client, OP-05); Zeitbudgetaufteilung.
- **Akzeptanzkriterien:**
  1. Der Lasttest mit dokumentierter Testflotte erreicht p95 ≤ 5 s für Angebotsanfragen bei Referenzlast oder weist die Abweichung mit Ursache und Maßnahme aus. (NFR-003)
  2. Das Protokoll enthält lokale Anteile, Kandidaten-/Flottengröße, Routingdatenstand und Timeouts (Kap. 08 §8.6). (NFR-003)
  3. Bei Solver-Timeout wird nur ein vollständig validierter zulässiger Plan oder eine begründete Ablehnung geliefert; kein Angebot verletzt zugesagte Zeitfenster.
  4. Ein nicht rechtzeitig berechnetes Angebot wird nicht nachträglich als zugesagt dargestellt.
- **Tests:** positiv: Messlauf mit vorbelegter Flotte. negativ: kalter Routingcache; Routingquelle langsam; Anfrage ohne zulässiges Fahrzeug (schnelle Ablehnung). konkurrierend (Kapazität): viele gleichzeitige Angebote auf dieselbe Restkapazität – Angebote ohne Hold versprechen keine Kapazität, Hold/Confirm bleiben korrekt (PH-AT-004 unter Last).
- **Abhängigkeiten:** intern: WP-M5-10.
  - extern: WP-M2-10, WP-M2-17, WP-M2-21, WP-M2-22, WP-M2-23 (Angebotsbildung, Einfügeheuristik, Routingadapter, Hold/Confirm)
- **Entscheidungen:** D-05 (Flottenzahl, Peak, Zeitbudget) und D-09 (Routinganbieter) blockieren die Abnahmewirkung und die Produktivschaltung, nicht die Messung.
- **Größe:** M
- **Nicht im Umfang:** Partner-Fanout und kombinierte Angebote (M6); Verbesserung der Lösungsqualität des Solvers.

### WP-M5-13 – Fahrer-Planrevision p95 ≤ 10 s bis zur Clientdarstellung

- **Ziel:** Ein zeitgestempelter Ende-zu-Ende-Test belegt, dass eine neue bestätigte Fahrer-Planrevision bei bestehender Verbindung in 95 % der Fälle binnen 10 s in der Driver-App angezeigt wird.
- **Anforderungen:** `LH-6.19-NFR-004`
- **Liefergegenstände:**
  - Backend: Zeitstempel der Kette „Commit der Planrevision (`vehicle_run`) → Outbox → Relay → Live-Kanal/Push → Empfang → Darstellung“; Korrelation über Revisionsnummer.
  - Oberfläche: UI-10 (Driver Fahrt) meldet im Testmodus den Zeitpunkt, zu dem die Revision tatsächlich dargestellt ist; keine Messung bloß des Netzwerkempfangs.
  - Adapter/Worker: Messharness mit realen Zielgeräten bzw. Gerätesimulation unter `tests/performance`; Auswertung p50/p95/p99 und Ausreißeranalyse.
  - Ereignisse/Outbox: Messung von Outbox-Alter und Relay-Verzug unter Referenzlast.
- **Detailverträge vorab:** Messpunkte nach Kap. 08 §8.6 („Commit bis tatsächlich dargestellte Clientrevision“); Definition „bestehende Verbindung“ und „bestätigte Planrevision“; Uhrenabgleich zwischen Server und Gerät im Test.
- **Akzeptanzkriterien:**
  1. Der zeitgestempelte Ende-zu-Ende-Test zeigt p95 ≤ 10 s vom Commit der Planrevision bis zur Darstellung in der Driver-App bei bestehender Verbindung; Stichprobengröße und Verteilung sind protokolliert. (NFR-004)
  2. Die Messung findet unter Referenzlast statt (250 verbundene Fahrzeuge je Mandant, zwei Mandanten). (NFR-004)
  3. Eine ältere Revision überschreibt nie eine neuere; verlorene Zustellungen werden durch Nachladen geschlossen und erscheinen im Protokoll als Ausreißer, nicht als Erfolg.
  4. Ein Fahrer erhält ausschließlich Revisionen seines zugeordneten Fahrtenplans.
- **Tests:** positiv: Serie von Planänderungen. negativ: Verbindung kurz unterbrochen (zählt nicht zur Zielmenge, wird aber getrennt ausgewiesen); Relay gestoppt und wieder gestartet. konkurrierend: zwei Planänderungen kurz nacheinander – dargestellt wird die höhere Revision; Planänderung parallel zu Fahrerereignis mit alter Revision führt zu definierter Konfliktantwort.
- **Abhängigkeiten:** intern: WP-M5-03, WP-M5-10.
  - extern: WP-M2-28 (Planrevisionen verteilen und Clients synchronisieren)
  - extern: WP-M3-27, WP-M3-35, WP-M3-56 (Planänderungen in der Driver-App, Live-Aktualisierungskanal, Messprotokoll der Gate-Suite)
- **Entscheidungen:** D-05 (Zielwerte, Geräte) blockiert die Abnahmewirkung, nicht die Messung.
- **Größe:** M
- **Nicht im Umfang:** Zustellung bei getrennter Verbindung (Offline-Sync, M3); Fahrgastbenachrichtigung über Planänderungen.

### WP-M5-14 – Replikation, PITR und Wiederanlaufdrehbuch

- **Ziel:** Datenbank und Objektspeicher sind so gesichert und repliziert, dass RPO ≤ 15 min und RTO ≤ 2 h technisch erreichbar sind; das Wiederanlaufdrehbuch beschreibt jeden Schritt bis zur funktionsfähigen End-to-End-Probe.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `infra/` – kontinuierliche WAL-Archivierung und PITR für PostgreSQL, Replikat bzw. Standby gemäß Betriebsmodell, versionierter Objektspeicher mit Replikation; Metriken für Archivierungsverzug, Replikationsverzug und Backup-Erfolg sowie die zugehörigen Alarmregeln „fehlgeschlagenes Backup“ und „Archivierungs-/Replikationsverzug“ im Alarmrahmen aus WP-M5-04.
  - Adapter/Worker: Wiederanlaufdrehbuch unter `tests/performance` (Vorlage aus WP-M0-25): Entscheidung Failover/Restore, Wiederherstellung auf Zeitpunkt, Wiedereinspielen des Lösch-/Sperrjournals vor Freigabe (Kap. 01 §1.7), Neustart der Worker mit Lease/Fencing, Abgleich externer Zahlungen, Freigabeprobe.
  - Backend: Abgleichlauf nach Wiederanlauf für `payment_intent` und `operation` in unbestimmtem Zustand anhand der Providerreferenzen; kein Wiederholen unsicherer Schritte mit neuer Referenz.
  - Datenbank: Konsistenzprüfungen nach Restore (`outbox`/`inbox`-Stand, `idempotency_record`, Kapazitätsinvarianten aus Kap. 04 §4.5, `audit_event`-Kette).
- **Detailverträge vorab:** Definition von RPO-Messpunkt (letzter wiederherstellbarer Commit) und RTO-Ende (Zeitpunkt der funktionsfähigen End-to-End-Probe) nach Kap. 08 §8.6; Zuständigkeiten und Freigabeberechtigte im Drehbuch.
- **Akzeptanzkriterien:**
  1. Der Archivierungsverzug liegt im Normalbetrieb und unter Referenzlast dauerhaft unter 15 min; eine Überschreitung und ein fehlgeschlagenes Backup lösen jeweils einen Alarm aus (Beitrag zur Abnahme von `LH-6.19-NFR-008` in WP-M5-04).
  2. Das Drehbuch ist von einer Person ausführbar, die es nicht geschrieben hat (Trockenlauf protokolliert).
  3. Der Abgleichlauf findet eine im PSP-Simulator vollzogene, lokal aber verlorene Zahlung und führt sie zu genau einem konsistenten Zustand ohne zweite Belastung.
  4. Backups und Replikate unterliegen derselben Verschlüsselung und Zugriffstrennung wie in WP-M1-39 nachgewiesen; ein Restore in eine andere Umgebung als die Quellumgebung ist technisch unterbunden (keine Produktionsdaten in Staging/Development, Kap. 01 §1.7).
  5. Die Konsistenzprüfungen nach Restore laufen automatisiert und liefern ein Protokoll; ein verletzter Befund blockiert den Freigabeschritt des Drehbuchs.
- **Tests:** positiv: PITR auf einen gewählten Zeitpunkt. negativ: beschädigtes WAL-Segment wird erkannt; Restore ohne Wiedereinspielen des Löschjournals wird vom Drehbuch-Prüfschritt blockiert. konkurrierend (Geld): Abgleichlauf parallel zu eintreffendem PSP-Webhook erzeugt genau eine Zustandswirkung.
- **Abhängigkeiten:** intern: WP-M5-02, WP-M5-04 (Alarmrahmen, Empfänger, Runbook-Verweis).
  - extern: WP-M0-11, WP-M0-25, WP-M0-23 (IaC-Gerüst, Vorlage Restore-Drehbuch, PSP-Simulator)
  - extern: WP-M1-39, WP-M1-15, WP-M1-33 (verschlüsselte Backups und Restore-Test, `operation` mit Lease/Fencing, Lösch-/Sperrjournal)
  - extern: WP-M2-29, WP-M2-30 (PSP-Adapter mit Statusabfrage über Providerreferenz, Zahlungsautomat)
- **Entscheidungen:** D-05 (Hosting, Betriebsmodell) und D-11 (Wiederanlauf, Notbetrieb) blockieren die Produktivschaltung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Durchführung und Messung der Ausfallübung (WP-M5-15); Mehrregionenbetrieb.

### WP-M5-15 – Ausfallübung mit RPO-/RTO-Messung und PSP-Abgleich

- **Ziel:** Eine Ausfallübung auf produktnaher Datenbank belegt die Wiederherstellung innerhalb der Zielzeiten (RPO höchstens 15 Minuten, RTO höchstens 2 Stunden) einschließlich des Abgleichs extern vollzogener Zahlungen.
- **Anforderungen:** `LH-6.19-NFR-007`
- **Liefergegenstände:**
  - Adapter/Worker: Durchführung der Übung in der Staging- oder Performance-Umgebung unter laufender Last: Ausfall der Primärdatenbank, Wiederherstellung nach Drehbuch, Wiederanlauf aller Komponenten, End-to-End-Probe (synthetische Buchung aus WP-M5-05).
  - Adapter/Worker: Übungsprotokoll mit Ausfallzeitpunkt, letztem wiederherstellbarem Commit, Zeitpunkt der funktionsfähigen End-to-End-Probe, gemessenem RPO und RTO, Abweichungen, Maßnahmen (Kap. 08 §8.6).
  - Backend: Abgleichbericht über Buchungen und Zahlungen im Verlustfenster (`booking`, `capacity_hold`, `payment_intent`, `operation`): Zahl der betroffenen Vorgänge, Ergebnis je Vorgang.
- **Detailverträge vorab:** Übungsplan (Szenario, Lastniveau, Beobachter, Abbruchkriterien); Messdefinitionen aus WP-M5-14.
- **Akzeptanzkriterien:**
  1. Die Ausfallübung belegt die Wiederherstellung in der Zielzeit: gemessenes RPO ≤ 15 min und gemessenes RTO ≤ 2 h, jeweils mit Zeitstempeln aus dem Protokoll. (NFR-007)
  2. Backups und Replikation sind die tatsächlich für die Übung verwendeten Mittel; die Übung nutzt keine Testabkürzung, die im Betrieb nicht verfügbar wäre. (NFR-007)
  3. Nach dem Restore werden externe Zahlungen mit Providerreferenzen abgeglichen, bevor unsichere Schritte wiederholt oder Kunden als ungebucht markiert werden; es entsteht keine zweite Belastung und keine Buchung ohne Kapazität.
  4. Lösch- und Sperrvorgänge aus dem Zeitraum vor dem Ausfall sind nach dem Restore wieder wirksam (keine wiederauferstandenen gelöschten Daten, keine aufgehobene Fahrzeugsperre).
  5. AT-23 ist mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen.
  6. Nach dem Wiederanlauf ist die Mandantentrennung unverändert wirksam (Stichprobe der Isolationssuite auf der wiederhergestellten Datenbank; Runtime-Rolle ohne Superuser, BYPASSRLS oder Tabelleneigentum); Fahrer-Apps mit offline erfassten Ereignissen aus dem Verlustfenster synchronisieren ohne Doppelwirkung.
- **Tests:** positiv: Übung mit Erfolg in Zielzeit. negativ: Übung mit zusätzlich ausgefallenem Messaging; Restore auf einen Zeitpunkt mitten in einer Buchungstransaktion. konkurrierend (Geld, Kapazität): Zahlungen und Bestätigungen laufen bis zum Ausfallzeitpunkt; nach Wiederanlauf treffen verspätete PSP-Ereignisse ein – genau eine Wirkung je Vorgang. Besteht AT-23.
- **Abhängigkeiten:** intern: WP-M5-05, WP-M5-14.
  - extern: WP-M1-39, WP-M1-14 (Restore-Test der Backups, Inbox-Deduplikation)
  - extern: WP-M2-29, WP-M2-30 (Zahlungsautomat, PSP-Adapter und Provider-Ereignisse)
- **Entscheidungen:** D-05 (RPO/RTO als vorläufige Ziele, Hosting) – Übung in produktnaher Umgebung ist ohne Beschluss möglich; Übertragbarkeit auf die Produktivumgebung erst nach Hostingentscheid. D-03 (Teil PSP-Auswahl): ohne Beschluss Abgleich nur gegen PSP-Simulator.
- **Größe:** M
- **Nicht im Umfang:** Partnerabgleich föderierter Buchungen (M6); regelmäßige Wiederholung der Übung im Betrieb (Betriebshandbuch, WP-M5-26).

### WP-M5-16 – Fault-Injection für Datenbank, Worker, Cache, PSP, Kartenquelle, Messaging

- **Ziel:** Für jeden Ausfall einer Abhängigkeit ist nachgewiesen, dass die Plattform definiert degradiert, keine falsche Zusage gibt, sich nach Rückkehr selbst erholt und einen Alarm erzeugt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Fault-Injection-Szenarien unter `tests/performance` bzw. `tests/acceptance` für Datenbank (Verbindungsabriss, Failover), Routing-/Dokumenten-Worker (Absturz, Hänger), Redis/Cache (Ausfall, Datenverlust), PSP (Timeout, verlorene Antwort, Duplikat über den PSP-Simulator), Kartenquelle/Routingadapter (Ausfall, Latenz), Messaging (RabbitMQ-Ausfall, Queue-Stau).
  - Backend: Nachbesserungen aus den Befunden (Timeouts, Circuit Breaker, Fail-closed-Verhalten der Sitzungssperre ohne Redis, Rückstau-Begrenzung).
  - Ereignisse/Outbox: Nachweis, dass nach Messaging-Ausfall alle `outbox`-Einträge zugestellt und über `inbox` genau einmal wirksam werden.
- **Detailverträge vorab:** Erwartungstabelle je Szenario (Nutzerwirkung, Fehlercode, Alarm, Erholung) aus WP-M5-01; Bezug zu Kap. 01 §1.5 und Kap. 02 §7.4 (unbestimmte Zustände bleiben unbestimmt).
- **Akzeptanzkriterien:**
  1. Jedes der sechs Szenarien ist ausgeführt und protokolliert; je Szenario sind Nutzerwirkung, Alarm (aus WP-M5-04) und Erholungszeit festgehalten.
  2. Redis-Ausfall verändert weder Kapazität noch Preis noch Zahlungszustand; Hold und Confirm bleiben korrekt (Kap. 01 §1.4).
  3. PSP-Timeout führt zu „Ergebnis wird geprüft“ und Abgleich, nie zu einer Neubuchung mit neuer Referenz oder zu einer definitiven Fehlermeldung ohne Nachweis.
  4. Ausfall der Kartenquelle führt zu begründeter Ablehnung neuer Angebote oder zu Angeboten aus validiertem Cache; bestehende Fahrtenpläne bleiben abrufbar.
  5. Nach jedem Szenario sind die Invarianten aus Kap. 04 §4.5 erfüllt (keine Überbuchung, keine doppelte Wirkung).
  6. Während eines Datenbank- oder Redis-Ausfalls wird keine Anfrage ohne gültigen Mandanten- und Sitzungskontext beantwortet (fail-closed); nach Rückkehr wirkt kein Tenantkontext einer früheren Verbindung fort.
  7. Fahrer-App und Passenger-PWA zeigen während jedes Szenarios den degradierten Zustand aus WP-M5-09 und kein falsches Erfolgs- oder Echtzeitversprechen.
- **Tests:** positiv: nach Rückkehr jeder Abhängigkeit läuft der Referenzfall aus WP-M5-23 ohne Eingriff grün. negativ: die sechs Szenarien selbst, jeweils unter Grundlast; Dokumenten-Worker hängt – kein ungeprüfter Upload wird ausgeliefert; Messaging-Stau mit vertauschter und mehrfacher Zustellung nach Rückkehr (Muster aus PH-AT-017, AT-24); PSP-Timeout mit später eintreffendem identischem Ereignis (AT-15 als Wiederholung). konkurrierend (Geld, Kapazität): Worker-Absturz zwischen PSP-Aufruf und lokalem Commit (Einbetreiber-Variante des Musters aus PH-AT-009); Datenbank-Failover während 100 paralleler Bestätigungen.
- **Abhängigkeiten:** intern: WP-M5-02, WP-M5-04, WP-M5-09, WP-M5-14, WP-M5-23 (Kriterium 7 und der Positivtest; keine Rückabhängigkeit dieser WPs).
  - extern: WP-M0-23 (PSP-Simulator mit steuerbaren Fehlern)
  - extern: WP-M1-14, WP-M1-15, WP-M1-10 (Outbox/Inbox, `operation` mit Lease/Fencing, Sitzungssperre fail-closed)
  - extern: WP-M2-10, WP-M2-22, WP-M2-30 (Routingadapter, Hold/Confirm, Zahlungsautomat)
  - extern: WP-M4-21, WP-M4-27 (Uploadticket mit Quarantäne und Malware-Prüfung, OCR-Adapter als Dokumenten-Worker)
- **Entscheidungen:** keine für den Bau; D-05 für die Übertragbarkeit auf die Produktivumgebung.
- **Größe:** M
- **Nicht im Umfang:** Ausfall von Partnern (Kap. 08 §8.6 nennt ihn; in P1 gibt es keine buchbaren Partner – M6); Chaos-Tests in Produktion.

### WP-M5-17 – Sicherheitsabnahme: Penetrationstest, RLS- und Feldzugriffsprüfung

- **Ziel:** Das Sicherheits-Gate aus Kap. 08 §8.4 ist nachgewiesen: RLS-, Rollen- und Objektprüfung über den gesamten P1-Umfang, Upload- und Exportberechtigungen, Penetrationstest mit behandelten kritischen Befunden.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: vollständiger Lauf der Isolationssuite über alle P1-Tabellen und -Endpunkte (Kerntabellen aus `kernschema.sql` und die in M2 bis M4 ergänzten Tabellen aus Kap. 04 §4.4) unter der Runtime-Rolle; Vollständigkeitswächter „Tabelle ohne Policy“; Feldzugriffsmatrix Rolle × Feldklasse (PII, Unterstützungsmerkmale, HR, Finanzen).
  - Adapter/Worker: Penetrationstest durch eine vom Entwicklungsteam unabhängige Stelle gegen Staging (Web, API, Driver-App, Upload, Webhook-Eingang `/v1/payments/provider-events/{provider}`); Befundliste mit Mangelklasse; Nachtest.
  - Backend: Behebung der Befunde der Klassen A und B.
- **Detailverträge vorab:** Prüfplan Sicherheit aus WP-M5-01 (Umfang, Regeln, Testkonten, Zeitfenster); Zuordnung der Befundklassen des Prüfers zu den Mangelklassen A/B/C.
- **Akzeptanzkriterien:**
  1. Die Isolationssuite ist für alle P1-Tabellen und -Endpunkte grün; der Wächter meldet keine Tabelle ohne Policy und keinen Endpunkt ohne Negativtest.
  2. PH-AT-001 ist auf dem Abnahme-Build erneut bestanden (Regression über den gesamten P1-Umfang, einschließlich leerem Transaktionskontext und Pool-Wiederverwendung).
  3. Der Penetrationstestbericht liegt vor; es gibt keinen offenen kritischen Befund, jeder behobene Befund hat einen Nachtest (Abnahmebezug `LH-6.18-SEC-009`, in M1 zugeordnet).
  4. Upload- und Exportberechtigungen sind geprüft: kein Abruf fremder Medien oder Exporte über erratene oder abgelaufene URLs.
  5. Die Isolation außerhalb der Datenbank ist für den P1-Umfang geprüft: Objektspeicher, Cache-Schlüssel, Live-Kanal/WebSocket, Ereignisse und Supportzugriff liefern keine fremden Mandantendaten (Kap. 01 §1.7).
  6. Der Testzugang des Prüfers ist befristet, protokolliert und nach Abschluss entzogen; der Bericht enthält keine echten Personendaten.
- **Tests:** positiv: berechtigter Zugriff bleibt möglich. negativ: fremde UUID, manipulierter Mandantenheader, abgelaufene Upload-URL, Rolle ohne Feldrecht. konkurrierend (Sicherheit, Datentrennung): Rollenentzug während aktiver Sitzung unter Last; parallele Anfragen zweier Mandanten über denselben Verbindungspool.
- **Abhängigkeiten:** intern: WP-M5-02, WP-M5-06.
  - extern: WP-M0-18, WP-M0-19 (RLS-Laufzeittests, Tenantkontext-Testharness)
  - extern: WP-M1-01, WP-M1-07, WP-M1-31, WP-M1-11, WP-M1-38, WP-M1-45 (Isolations-Testharness, Rechtematrix, Feldschutz, Isolation außerhalb der Datenbank, Sicherheits-Scans in CI, M1-Gate-Suite)
  - extern: WP-M2-48, WP-M2-29 (Negativtests je Buchungs- und Zahlungsendpunkt, Webhook-Eingang des PSP)
  - extern: WP-M3-56, WP-M3-50, WP-M3-44 (Negativtests je Fahrer-, Prüf-, Schaden- und Leitstellenendpunkt, Vertraulichkeit der Schadenakte, Fotoevidenz-Upload)
  - extern: WP-M4-21, WP-M4-16, WP-M4-58, WP-M4-63 (Uploadticket mit Quarantäne, Depot-/Mandantenberechtigung im Dienstplan, Filter ohne Mandantenüberschreitung, M4-Gate-Suite)
- **Entscheidungen:** D-02 (Isolationsstufe) – Prüfung erfolgt gegen den RLS-Default; ein späterer abweichender Beschluss erfordert Wiederholung.
- **Größe:** M
- **Nicht im Umfang:** SAST/DAST-Pipeline (M1); Partner- und Föderationsprojektionen (PH-AT-002, M6).

### WP-M5-18 – Barrierefreiheitsprüfung Fahrer und Planer (AT-54)

- **Ziel:** Der Gate-Test AT-54 ist auf dem P1-Umfang bestanden: Pflichtprüfung und Fahrt in der Driver-App sowie Dienstplan und eigene Zeiten im Web sind mit Tastatur und Screenreader bedienbar, und jede Pflichtaktion ist ohne Drag-and-drop oder visuelle Barriere möglich. Zusätzlich steht die automatisierte Barrierefreiheitsprüfung als CI-Schritt für alle P1-Oberflächen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: Prüfung und Nachbesserung der Kernreisen in UI-09 (Driver Dienststart: Pflichtprüfung abschließen; Zustände Offline, Mangel, andere Fahrzeugrevision), UI-10 (Driver Fahrt/Schaden: Check-in/out, Mangel melden; Zustände falscher Stopp/Code, vergangene Revision), UI-12 (Dienstplan: Entwurf veröffentlichen; Zustände Ruhezeit, Doppelbelegung, unbesetzter Dienst) und UI-13 (eigene Zeit/Abwesenheit).
  - Oberfläche: gleichwertiger Tastatur- und Listenweg für jede Schichtaktion in UI-12, die M4 per Drag-and-drop anbietet (WP-M4-19 liefert Klick, Kopie und Drag-and-drop; der vollständige Listen-/Screenreaderweg ist als eigene P2-Lieferung in WP-M7d-06 geplant, siehe OP-14).
  - Adapter/Worker: automatisierte Prüfungen im Web-Build und im Mobile-Build (Kontrast, Rollen, Beschriftungen, Fokusreihenfolge) als CI-Schritt für alle P1-Oberflächen; manuelles Prüfprotokoll mit Tastatur und Screenreader je Kernreise; Prüfung von 200-%-Textvergrößerung und Reflow bei 320 CSS-Pixeln (Kap. 06 §6.1); für die Driver-App zusätzlich Bedienung mit den Screenreadern der Zielplattformen.
- **Detailverträge vorab:** Prüfplan Barrierefreiheit aus WP-M5-01 (Kernreisen und Pflichtaktionen je Rolle, eingesetzte Screenreader/Browser/Geräte); Abnahmeziel WCAG 2.2 AA laut Kap. 06 §6.1 – als P1-Abnahmemaßstab zu bestätigen (OP-14); Protokollvorlage mit Mangelklasse.
- **Akzeptanzkriterien:**
  1. Checkliste (UI-09) und Schichtplan (UI-12) sind per Tastatur und Screenreader bedienbar; alle Pflichtaktionen (Prüfpunkt beantworten, Mangel mit Foto melden, Prüfung abschließen; Schicht anlegen, zuweisen, verschieben, Konflikt einsehen, Plan veröffentlichen) sind ohne Drag-and-drop oder visuelle Barriere möglich; AT-54 ist mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen.
  2. Der UI-Abnahmefall „Driverprüfpfad mit kritischem Nein und Offline-Sync“ aus Kap. 06 §6.9 ist per Screenreader durchlaufen: Sperrwirkung, Offlinezustand und Synchronisationsergebnis werden angesagt.
  3. Status wird nie nur über Farbe vermittelt; asynchrone Statusmeldungen laufen über eine Live-Region; Fehler- und Rechtezustände (Validierung, 412-Konflikt, fehlende Freigaberolle, Sperre) sind per Tastatur erreichbar und werden angesagt.
  4. Der CI-Schritt schlägt bei einer Probekomponente ohne zugänglichen Namen, mit fehlendem Fokus oder unzureichendem Kontrast fehl.
  5. Jede blockierte Kernreise ist behoben; verbleibende Befunde sind mit Mangelklasse und Termin dokumentiert.
- **Tests:** positiv: Kernreise Fahrer (Dienststart bis Fahrtende) und Planer (Entwurf bis Veröffentlichung) mit Tastatur und Screenreader. negativ: Pflichtaktion, die nur per Zeiger oder Drag-and-drop erreichbar ist, gilt als Fehler; Fokus geht nach Dialogen nicht verloren; Offline- und Sperrzustand werden angesagt. Besteht AT-54.
- **Abhängigkeiten:** intern: WP-M5-01, WP-M5-02.
  - extern: WP-M1-43 (Web-Designsystem und Anwendungsrahmen)
  - extern: WP-M3-24, WP-M3-14, WP-M3-29, WP-M3-33, WP-M3-44 (Driver-App-Basis, Pflichtprüfung und Fahrt in UI-09/UI-10, Offline-Prüfung)
  - extern: WP-M4-18, WP-M4-19, WP-M4-20, WP-M4-11 (UI-12 Planer-Dashboard und Schichtaktionen, UI-13 eigene Zeiten, Stempeln in der Fahrer-App)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** übrige Kernreisen (WP-M5-30); vollständige WCAG-2.2-AA-Konformitätserklärung aller Oberflächen (`LH-6.19-NFR-011`, P2, M8); barrierefreie Checklisten und Schichtlisten als eigene P2-Anforderungen (WP-M7c-31, WP-M7d-06).

### WP-M5-19 – Export-, Frist- und Archivnachweise (AT-25, AT-53, AT-61)

- **Ziel:** Die Gate-Tests zu Datenexport, Aufbewahrungsfristen und Archivberechtigungen sind auf dem P1-Umfang ausgeführt: Exporte sind berechtigungsgeprüft und vollständig, Archivregeln greifen, veröffentlichte Revisionen bleiben unverändert, fremde Mandantendaten werden verweigert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Szenarien in `tests/acceptance` für AT-25 (PBefG-Datenexport nach Freigabecheckliste), AT-53 (Export von Checkliste, Schadenakte, Stundenabrechnung; Archivregeln), AT-61 (alter veröffentlichter Plan und frühere Prüfungen; Zugriff aus anderem Mandanten).
  - Backend: Schließen der Lücken, die diese Tests im P1-Umfang aufdecken, im jeweils zuständigen Modul; Exporte als asynchrone Jobs mit Empfängerrechten und Snapshot/As-of (Kap. 05 §5.1) über `export_job`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Kap. 05 §5.7) als Zielvertrag für Exportauftrag und Abruf. M4 liefert diese Endpunkte nicht (WP-M4-46 verweist sie nach M8); der für AT-53 und AT-61 nötige P1-Mindestumfang (Auftrag anlegen, Status lesen, berechtigter Abruf für Prüfnachweis, Schadenakte und Zeitnachweis) ist deshalb in diesem WP zu liefern, sofern die Tests nicht nach OP-13 zugeschnitten werden. Es entsteht kein Pfad außerhalb von Kap. 05 §5.7.
  - Datenbank: `export_job` (Kap. 04 §4.4; in M1 bis M4 nicht angelegt, Spalten im Pflichtenheft nicht spezifiziert – Migration mit RLS und Pflichtspalte Mandant in diesem WP nach Detailvertrag), `retention_rule`, `erasure_job` (aus WP-M1-33); gelesen werden `inspection_run`, `inspection_answer`, `defect_case`, `defect_evidence`, `roster_revision`, `time_event`.
  - Backend: „Stundenabrechnung“ ist in P1 kein eigenes Objekt (M4 liefert Ist-Arbeitszeit als Zeitereignisse, WP-M4-08, WP-M4-09); exportiert wird der Zeitnachweis aus `time_event`, eine Lohn- oder Abrechnungslogik entsteht hier nicht (OP-13).
  - Ereignisse/Outbox: Exportauftrag und Abruf erzeugen je einen Eintrag in `audit_event` (wer, wann, welcher Umfang), ohne Exportinhalt.
  - Konfigurationsparameter: `invoice_archive_years` (soweit Belegarchiv im Test berührt wird).
- **Detailverträge vorab:** Freigabecheckliste der gesetzlich relevanten Kategorien für AT-25 (im Pflichtenheft nicht enthalten, OP-13); Pflichtfelder je Export; Löschmatrix-Auszug für die getesteten Datenklassen (D-11).
- **Akzeptanzkriterien:**
  1. AT-25: Der Export der Testdaten enthält alle Kategorien der freigegebenen Checkliste vollständig; fehlende Kategorien werden im Export als fehlend ausgewiesen, nicht stillschweigend weggelassen.
  2. AT-53: Checkliste, Schadenakte und Stundenabrechnung lassen sich nur mit Berechtigung exportieren; Pflichtfelder und Nachweise (Hash, Revision, Zeitstempel) sind enthalten; die Archiv- und Löschregel greift im Test wie konfiguriert.
  3. AT-61: Ein alter veröffentlichter Plan und frühere Prüfungen des eigenen Mandanten sind unverändert lesbar; derselbe Zugriff aus einem anderen Mandanten wird verweigert (404 ohne Existenzhinweis).
  4. Exportdateien enthalten keine Daten außerhalb der Berechtigung des Auftraggebers des Exports; Download-URLs sind kurzlebig und mandantengebunden.
  5. Alle drei Tests sind mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen; Umfangsabweichungen sind mit dem Auftraggeber dokumentiert.
  6. `export_job` ist mandantengebunden: Die Runtime-Rolle sieht ohne Tenantkontext keine Zeile; ein Auftrag mit fremdem Objekt- oder Mandantenbezug wird abgewiesen; ein wiederholter Auftrag mit gleichem Idempotenzschlüssel erzeugt keinen zweiten Export.
  7. Vertrauliche Teile der Schadenakte und HR-nahe Zeitdaten erscheinen im Export nur für Rollen mit dem jeweiligen Feldrecht; Export und Abruf sind in `audit_event` nachvollziehbar.
  8. Ein Datensatz unter Aufbewahrungspflicht wird vor Fristablauf nicht gelöscht, ein Datensatz nach Fristablauf wird im Test gelöscht bzw. gesperrt; der Export nach der Löschung enthält ihn nicht mehr.
- **Tests:** positiv: berechtigter Export. negativ: Export ohne Rolle, mit fremder Objekt-ID, nach Ablauf der URL; Änderungsversuch an veröffentlichter Revision und an abgeschlossenem Prüfnachweis; Löschversuch vor Fristablauf; gleicher Idempotenzschlüssel mit anderem Exportumfang (Konflikt, Muster aus PH-AT-011). konkurrierend (Datentrennung): Exporte zweier Mandanten parallel – keine Vermischung; Export parallel zu Löschjob – konsistenter As-of-Stand. Besteht AT-25, AT-53, AT-61.
- **Abhängigkeiten:** intern: WP-M5-01, WP-M5-02.
  - extern: WP-M1-33, WP-M1-11, WP-M1-12, WP-M1-31, WP-M1-15 (Aufbewahrungs-, Archivierungs- und Löschregeln, Isolation außerhalb der Datenbank, Audit, Feldschutz, `operation`-Ressource für asynchrone Aufträge)
  - extern: WP-M3-14, WP-M3-43, WP-M3-48, WP-M3-50 (unveränderlicher Prüfnachweis, Schadenakte mit Historie und Vertraulichkeit)
  - extern: WP-M4-14, WP-M4-15, WP-M4-16, WP-M4-08, WP-M4-09 (Veröffentlichung und Revisionen des Dienstplans, Depot-/Mandantenberechtigung, Ist-Arbeitszeit als Zeitereignisse). Ein Berichts-/Exportjob wird von M4 nicht geliefert.
- **Entscheidungen:** D-11 (Löschfristen, Legal Hold, Exportprozess) blockiert die Produktivschaltung; die Tests laufen mit Testfristen.
- **Größe:** L
- **Nicht im Umfang:** Mobilitätsdatenexport, Berichtsjobs für Kennzahlen und PBefG-Freigabeprozess als Fachfunktion (M8); Auskunfts-/Löschanfragen `/v1/privacy/requests` (M8); Lohn-/Stundenabrechnung als Fachfunktion (TIM P2, M7); konfigurierbare Export-/Archivstrategie (`LH-6.19-NFR-020`, P2).

### WP-M5-20 – Standortdaten- und Medienminimierung (AT-59)

- **Ziel:** Der Gate-Test zur Standortminimierung ist bestanden: Ohne Freigabe wird beim Stempeln kein Standort erhoben, und hochgeladene Bilder werden von nicht benötigten Metadaten (EXIF) befreit.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Szenario in `tests/acceptance` für AT-59 (GPS-Stempeln ohne Freigabe konfigurieren; Medienbilder hochladen).
  - Backend: Zeitereignis ohne Standort, wenn die Geozone nicht freigegeben ist (in P1 bleibt `clock_in_geofence_enabled` laut WP-M4-11 aus; das standortgestützte Stempeln selbst ist P2, WP-M7d-40). EXIF-Minimierung bei Schadens-, Prüf- und Belegfotos vor Ablage der abrufbaren Fassung: Weder M3 (WP-M3-44) noch M4 (WP-M4-21, WP-M4-25) liefern sie, die zugehörige P2-Lieferung ist WP-M7c-27; der für AT-59 nötige P1-Mindestumfang im Uploadpfad ist deshalb hier zu liefern, sofern der Test nicht nach OP-13 zugeschnitten wird.
  - Backend: Validierungsregel der Konfiguration „`clock_in_geofence_enabled` nur mit dokumentierter Freigabe“ (Katalogvorgabe „Nur mit legitimer Zweckbindung“) im Validierungsrahmen aus WP-M1-22.
  - Datenbank: `time_event` (kein Standortfeld befüllt ohne Freigabe), `defect_evidence`, `expense_document`; `position_sample` nur während zugeordneter Schicht.
  - Konfigurationsparameter: `clock_in_geofence_enabled`.
- **Detailverträge vorab:** Regel, welche Bildmetadaten entfernt und welche als Nachweis erhalten bleiben (Verhältnis zu „unveränderliches Original mit Hash“, OP-15); Freigabevoraussetzung für `clock_in_geofence_enabled` (Mitbestimmung, D-10/D-11).
- **Akzeptanzkriterien:**
  1. Mit `clock_in_geofence_enabled` = aus enthält kein Zeitereignis einen Standort; die App fragt keine Standortberechtigung für das Stempeln an.
  2. Der Versuch, die Geozone ohne dokumentierte Freigabe zu aktivieren, wird von der Konfigurationsvalidierung abgewiesen.
  3. Ein hochgeladenes Testbild mit GPS-EXIF ist in der ausgelieferten Fassung ohne Standort- und Gerätemetadaten; der Nachweis erfolgt durch Metadatenvergleich.
  4. Außerhalb einer zugeordneten Schicht entstehen keine `position_sample`-Einträge.
  5. AT-59 ist mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen.
  6. Verworfene Koordinaten und entfernte Bildmetadaten erscheinen weder in der Datenbank noch in Logs, Traces oder der Offline-Outbox der App (Prüfung mit dem Skript aus WP-M5-06).
  7. Die Bereinigung verändert die Nachweiskraft nicht: Hash und Version der gespeicherten Fassung bleiben prüfbar; die Regel zum Original folgt dem Detailvertrag (OP-15).
- **Tests:** positiv: Stempeln ohne Standort, Upload mit bereinigten Metadaten. negativ: Client sendet trotz deaktivierter Geozone Koordinaten – Server verwirft sie; Bild mit eingebetteter Vorschau samt GPS. konkurrierend (Sicherheit): Konfigurationswechsel der Geozone während laufender Schicht wirkt erst ab Wirksamkeitszeitpunkt. Besteht AT-59.
- **Abhängigkeiten:** intern: WP-M5-01, WP-M5-02, WP-M5-06 (Prüfskript für Kriterium 6).
  - extern: WP-M1-22, WP-M1-32 (Konfigurationsvalidierung und Freigabe, Dateninventar)
  - extern: WP-M3-44, WP-M3-34 (mobile Schadenerfassung mit Fotoevidenz, Positionserfassung)
  - extern: WP-M4-08, WP-M4-11, WP-M4-21, WP-M4-25 (Ist-Arbeitszeit als Zeitereignisse, Stempeln in der Fahrer-App, Uploadticket mit Quarantäne, Belegerfassung per Kamera)
- **Entscheidungen:** D-10 und D-11 blockieren die Produktivaktivierung der Geozone, nicht den Test.
- **Größe:** M
- **Nicht im Umfang:** Auswertung von Fahrerstandorten für ETA (M8); Geozonenregeln für Stempeln als Fachfunktion (TIM P2, M7).

### WP-M5-21 – Zentraler Anforderungs-Backlog mit Besitzer und Testfall

- **Ziel:** Alle Anforderungen sind in einem zentralen Backlog mit ID, Besitzer, Priorität und Testfall geführt; für jede Pflichtanforderung führt die Stichprobe zu mindestens einem Testfall.
- **Anforderungen:** `LH-6.20-QAT-001`
- **Liefergegenstände:**
  - Adapter/Worker: `docs/umsetzungsplan/arbeitspakete.csv` als zentraler Backlog mit `source_key`, Original-ID, Priorität, Phase, Arbeitspaket, Detailvertrag, Testfall, Status; Ergänzung der Spalte „Besitzer“ (heute nicht vorhanden, OP-16) über `build_zuordnung.py` bzw. `check_wp.py`.
  - Adapter/Worker: Prüfschritt in CI: jede MUSS-Zeile des freizugebenden Release hat Besitzer und mindestens einen existierenden, ausführbaren Testfall; der Testfall referenziert die `source_key` zurück (bidirektionale Prüfung).
  - Adapter/Worker: Stichprobenwerkzeug, das zu einer zufälligen Menge von Pflichtanforderungen Testfall, letzten Lauf, Build und Evidenz ausgibt.
- **Detailverträge vorab:** Spaltenvertrag des Backlogs; Regel für doppelte Original-IDs (`COM-001` bis `COM-012`: ausschließlich `source_key`, Kap. 08 §8.1); Definition „Testfall“ (ausführbar, mit ID und Version).
- **Akzeptanzkriterien:**
  1. Jede der 672 Anforderungen steht mit ID, Besitzer und Priorität genau einmal im Backlog; die 202 P1-MUSS haben zusätzlich mindestens einen Testfall. (QAT-001)
  2. Eine vom Abnehmer gezogene Stichprobe von Pflichtanforderungen führt für jede gezogene Anforderung zu mindestens einem Testfall mit letztem Laufergebnis. (QAT-001)
  3. Eine Probezeile „MUSS, umgesetzt, ohne Besitzer“ oder „ohne Testfall“ lässt die Pipeline fehlschlagen.
  4. Ein Testfall, der auf eine nicht existierende `source_key` verweist, wird gemeldet.
  5. Originalphase und -priorität jeder Zeile stimmen mit dem Quellkatalog überein; eine abweichende Probezeile lässt die Pipeline fehlschlagen (Prioritätsänderung nur mit dokumentiertem AG-Beschluss, Kap. 01 §1.8).
- **Tests:** positiv: Vollständigkeitslauf. negativ: Probezeilen wie in Kriterium 3 und 4; doppelte Zuordnung einer Anforderung.
- **Abhängigkeiten:** intern: WP-M5-01.
  - extern: WP-M0-31, WP-M0-32 (Detailvertragsprozess, Nachverfolgungs-CI für `arbeitspakete.csv` und `e2e.csv`)
- **Entscheidungen:** D-12 (Quellbereinigung der doppelten COM-IDs) blockiert nichts; bis dahin gilt `source_key`.
- **Größe:** S
- **Nicht im Umfang:** Inhaltliche Testabdeckung der P2/P3-Anforderungen; Auswahl eines externen Testmanagementwerkzeugs (OP-16).

### WP-M5-22 – Release-Gate in CI: Pflichtprüfungen für Kernbuchung und Disposition

- **Ziel:** Automatisierte Unit-, Integrations- und Contract-Tests decken Kernbuchung und Disposition ab, und die CI-Pipeline verweigert ein Release, sobald eine Pflichtprüfung fehlschlägt.
- **Anforderungen:** `LH-6.20-QAT-002`
- **Liefergegenstände:**
  - Adapter/Worker: Release-Pipeline mit verbindlichen Stufen: Unit-Tests, Datenbankintegrationstests unter der Runtime-Rolle, API-Contract-Tests gegen `api/`, Migration frisch und aus freigegebenem Vorstand, Architekturtest Modulgrenzen, Isolationssuite, semantische Contract-Fälle aus Kap. 05 §5.8, Lieferkettenprüfung; ein Release-Artefakt entsteht nur bei vollständig grünem Lauf (CI-Gate aus Kap. 08 §8.4).
  - Adapter/Worker: Abdeckungsnachweis je Fachfunktion von Kernbuchung und Disposition (Angebot, Hold, Confirm, Ablauf, Storno, Planrevision, Einfügeheuristik, Constraint-Validator): Zuordnung Funktion → Unit-, Integrations-, Contract-Test.
  - Adapter/Worker: Schutz gegen Umgehung: kein manuelles Überspringen einer Pflichtstufe ohne protokollierte Ausnahme mit Freigeber.
- **Detailverträge vorab:** Liste der Pflichtprüfungen und ihre Zuordnung zu den Stufen; Definition „Kernbuchung“ und „Disposition“ als Endpunkt-/Modulliste (`/v1/ride-offers`, `/v1/bookings`, `/v1/runs/{id}/revisions`, Modul `booking-dispatch`, `workers/routing`); Ausnahmeverfahren.
- **Akzeptanzkriterien:**
  1. Für jede Fachfunktion der Kernbuchung und Disposition existiert mindestens je ein automatisierter Unit-, Integrations- und Contract-Test; die Zuordnung ist maschinell prüfbar. (QAT-002)
  2. Ein absichtlich fehlschlagender Pflichttest (je Stufe einer) verhindert die Erzeugung und Signatur des Release-Artefakts. (QAT-002)
  3. Die Invariantentests aus Kap. 04 §4.5 für Kapazität laufen mit mindestens zwei parallelen Transaktionen als Pflichtstufe.
  4. Ein Release-Artefakt trägt Build-ID, Migrationsstand und die Liste der bestandenen Pflichtprüfungen.
  5. Die semantischen Contract-Fälle aus Kap. 05 §5.8 (für P1 einschlägig: abgelaufene Holds, SCA länger als Hold, Confirm-Timeout mit spät positivem Status, 100 identische Webhooks, Manipulation `tenant_id`, unzulässige Fremd-ID, Offline-Check-in auf alter Planrevision) laufen für die Endpunkte der Kernbuchung und Disposition als Pflichtstufe; zusätzlich PH-AT-011 (gleicher Idempotenzschlüssel mit abweichendem Request).
  6. Eine protokollierte Ausnahme vom Gate nennt Freigeber, Grund und Ablauf; ohne diese drei Angaben bleibt das Release gesperrt.
- **Tests:** positiv: grüner Release-Lauf. negativ: roter Unit-, Integrations-, Contract-, Migrations- und Isolationstest je einmal; Versuch, eine Stufe zu überspringen. konkurrierend (Kapazität, Idempotenz): PH-AT-004 und PH-AT-011 als Pflichtstufe.
- **Abhängigkeiten:** intern: WP-M5-21.
  - extern: WP-M0-12, WP-M0-13, WP-M0-14, WP-M0-17, WP-M0-03 (CI-Stufen Build/Unit, Vertragsprüfung, Lieferkette, Datenbankintegration, Architekturtest)
  - extern: WP-M1-03, WP-M1-45 (Contract-Test-Pipeline, Isolationssuite)
  - extern: WP-M2-17, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-48 (Tests zu Angebot, Hold, Confirm, Storno, Einfügeheuristik; Gate-Nachweis M2)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** E2E-Läufe in Staging (WP-M5-23, WP-M5-24); Rollback der Releasebereitstellung (`LH-6.19-NFR-013`, P2).

### WP-M5-23 – Durchgängiger E2E-Referenzfall in Staging

- **Ziel:** Ein automatisierter E2E-Test führt in Staging Buchung, Zuweisung, Fahrer-Check-in, Ausstieg, Reporting und – soweit aktiviert – Zahlung als einen zusammenhängenden Referenzfall aus, ohne manuelle Datenbankkorrektur.
- **Anforderungen:** `LH-6.20-QAT-003`
- **Liefergegenstände:**
  - Adapter/Worker: Szenario in `tests/acceptance`, das ausschließlich öffentliche Schnittstellen und Oberflächen nutzt: Angebot und Buchung (`/v1/ride-offers`, `/v1/bookings`; UI-01 bis UI-03), Zahlung über PSP-Sandbox bzw. -Simulator (`/v1/payments/provider-events/{provider}`), Zuweisung im Fahrtenplan (`vehicle_run`), Dienststart mit Pflichtprüfung (UI-09), Check-in und Ausstieg (`/v1/driver/events`; UI-10), Sicht der Leitstelle (UI-07), Auswertung (`/v1/analytics/metrics`; UI-15 bzw. Kennzahlansicht aus M4).
  - Adapter/Worker: Wächter „keine manuelle DB-Korrektur“: Das Szenario läuft unter Anwendungsrollen; ein Datenbank-Auditvergleich belegt, dass zwischen Start und Ende keine Schreibzugriffe außerhalb der Runtime-Rolle stattfanden.
  - Kennzahlen: Prüfung, dass der Referenzfall in `Anfragen`, `Angebotsquote`, `Buchungskonversion`, `Abholung pünktlich`, `Ankunft pünktlich` und `Fahrgastfahrten pro Fahrzeugstunde` mit dem erwarteten Zähler und Nenner erscheint.
  - Ereignisse/Outbox: Evidenz mit Ereignis- und Trace-IDs je Schritt (Kap. 08 §8.4).
- **Detailverträge vorab:** Drehbuch des Referenzfalls mit Fixture, erwarteten Status je Schritt und erwarteten Kennzahlwerten; Regel „Zahlung soweit aktiviert“ (mit und ohne Zahlung als zwei Varianten).
- **Akzeptanzkriterien:**
  1. Der Referenzfall läuft in Staging durchgängig von Buchung über Zuweisung, Fahrer-Check-in und Ausstieg bis zum Reporting und endet mit den erwarteten Status; der Wächter belegt, dass keine manuelle DB-Korrektur erfolgte. (QAT-003)
  2. Die Variante mit aktivierter Zahlung endet mit genau einer Zahlung im erwarteten Endzustand; die Variante ohne Zahlung erzeugt keinen `payment_intent`. (QAT-003)
  3. Der Fall erscheint im Reporting mit korrektem Datenstand und den erwarteten Kennzahlwerten.
  4. Das Szenario ist wiederholbar (zweiter Lauf ohne Bereinigung der Umgebung erfolgreich) und läuft für zwei Mandanten.
  5. Der Lauf ist als Wiederholung von AT-01 mit Build und Evidenz protokolliert.
  6. Die Evidenz des Laufs enthält nur synthetische Fahrgast- und Personaldaten und besteht die Log-Stichprobe aus WP-M5-06.
- **Tests:** positiv: beide Varianten. negativ: siehe WP-M5-24. konkurrierend (Kapazität, Geld): zwei Referenzfälle parallel in zwei Mandanten und zwei im selben Mandanten auf demselben Fahrzeug.
- **Abhängigkeiten:** intern: WP-M5-02, WP-M5-25.
  - extern: WP-M0-22 (Akzeptanztestgerüst und Evidenzformat)
  - extern: WP-M2-23, WP-M2-30, WP-M2-42, WP-M2-48 (Buchung, Zahlung, Passenger-PWA, Gate-Suite M2)
  - extern: WP-M3-14, WP-M3-29, WP-M3-36, WP-M3-56 (Driver-App, Pflichtprüfung, Fahrerereignisse, Leitstelle, Gate-Suite M3)
  - extern: WP-M4-17, WP-M4-46, WP-M4-51, WP-M4-53, WP-M4-54, WP-M4-56, WP-M4-59 (Fahrerschicht und Fahrzeugbereitstellung als Voraussetzung der Disponierbarkeit, Kennzahlregister, Nachfragetrichter, Pünktlichkeitskennzahlen, Kennzahlenansicht)
- **Entscheidungen:** D-03 (Teil PSP-Auswahl): ohne Beschluss läuft die Zahlungsvariante nur gegen den PSP-Simulator; blockiert die Produktivschaltung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Negativfälle (WP-M5-24); Mehrbetreiberreise (PH-AT-005, M6).

### WP-M5-24 – Negative E2E-Fälle mit definierten Status und Aufräumprozessen

- **Ziel:** Automatisierte negative E2E-Tests für Doppelbuchung, No-Show, Storno, Timeout und Fahrzeugausfall enden jeweils in einem definierten Status, und die Aufräumprozesse hinterlassen keine hängenden Ressourcen.
- **Anforderungen:** `LH-6.20-QAT-004`
- **Liefergegenstände:**
  - Adapter/Worker: fünf Szenariogruppen in `tests/acceptance` gegen Staging: (a) Doppelbuchung – paralleler Griff auf den letzten Platz und doppelt gesendeter Befehl mit gleichem Idempotenzschlüssel; (b) No-Show – Wartefrist nach `no_show_grace_min`, Grund, Status; (c) Storno nach Disposition; (d) Timeout – Ablauf des Holds, PSP-Timeout mit späterem Abgleich, Solver-Timeout; (e) Fahrzeugausfall im Umlauf mit Sperre und Störungsablauf.
  - Adapter/Worker: Aufräumprüfung nach jedem Szenario: keine verwaisten `capacity_hold`, keine `operation` mit überfälliger Lease, kein `payment_intent` in unbestimmtem Zustand ohne Abgleichauftrag, keine unzugestellten `outbox`-Einträge, Fahrtenplan ohne stornierte Stopps.
  - Kennzahlen: Gegenprobe der Zählung in `No-Show-Rate`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`, `Kapazitätsablehnungen`.
- **Detailverträge vorab:** Tabelle „Negativfall → erwarteter Endstatus je Statusdimension → Aufräumprozess → Frist“; Abgrenzung, welche Timeouts gemeint sind (OP-17).
- **Akzeptanzkriterien:**
  1. Doppelbuchung: höchstens eine bestätigte Reservierung; die abgewiesene Anfrage erhält einen eindeutigen Grund; der wiederholte Befehl liefert dieselbe Buchung. (QAT-004)
  2. No-Show und Storno führen zu den definierten Status; Kapazität und Stopps sind freigegeben; Fahrgast und Leitstelle sind informiert; eine erfolgte Zahlung ist gemäß Regel behandelt. (QAT-004)
  3. Timeout: Ein abgelaufener Hold bestätigt nicht; ein PSP-Timeout endet nach Abgleich in genau einem Endzustand ohne zweite Belastung; ein Solver-Timeout liefert eine begründete Ablehnung. (QAT-004)
  4. Fahrzeugausfall: Das Fahrzeug ist gesperrt, betroffene Buchungen befinden sich im Ersatz- oder Stornofluss, kein Auftrag bleibt einem gesperrten Fahrzeug zugeordnet. (QAT-004)
  5. Die Aufräumprüfung ist nach jedem Szenario ohne Befund; verbleibende Klärfälle sind als solche sichtbar und nicht als Erfolg oder Fehler verkürzt.
  6. Jeder Negativfall ist für zwei Mandanten ausgeführt; kein Aufräumprozess greift auf Objekte des anderen Mandanten zu; jeder Statuswechsel ist mit Akteur bzw. Systemprozess in `audit_event` nachvollziehbar.
- **Tests:** negativ: die Szenarien selbst, zusätzlich gleicher Idempotenzschlüssel mit abweichendem Request (Konflikt ohne zweite Ausführung, PH-AT-011) und PSP-Timeout mit später eintreffendem identischem Ereignis (AT-15); positiv als Kontrolle: der Referenzfall aus WP-M5-23 läuft unmittelbar danach unverändert grün. konkurrierend (Kapazität, Geld): 100 parallele Bestätigungen auf den letzten Platz; Storno parallel zum Check-in; PSP-Webhook parallel zum Abgleichlauf. Wiederholt AT-03, AT-07, AT-08, AT-09 und PH-AT-004 auf dem Abnahme-Build in Staging.
- **Abhängigkeiten:** intern: WP-M5-23.
  - extern: WP-M0-23, WP-M0-22 (PSP-Simulator mit steuerbaren Fehlern, Akzeptanztestgerüst)
  - extern: WP-M2-22, WP-M2-24, WP-M2-26, WP-M2-31 (Hold-Ablauf, Storno, Void/Erstattung, Idempotenz der Buchungsbefehle)
  - extern: WP-M3-29, WP-M3-53, WP-M3-54 (No-Show-Ereignis, Fahrzeugausfall mit Störungsablauf)
- **Entscheidungen:** D-03 (Teil PSP-Auswahl) wie in WP-M5-23; D-11 (Notbetriebsprozess beim Fahrzeugausfall) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partner-Timeouts und Kompensation über Betreibergrenzen (PH-AT-007, PH-AT-008, M6); Infrastrukturausfälle (WP-M5-16).

### WP-M5-25 – Stammdateneinspielung vor Betriebsstart mit Migrationsprotokoll

- **Ziel:** Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer des Pilotbetriebs werden datenkonsistent eingespielt; ein Migrationsprotokoll bestätigt Soll- und Ist-Bestände und führt eine Fehlerliste.
- **Anforderungen:** `LH-6.20-QAT-005`
- **Liefergegenstände:**
  - Adapter/Worker: Einspielwerkzeug, das Quelldateien validiert und ausschließlich über die fachlichen Schreibwege der Module lädt (`/v1/services`, `/v1/service-areas`, `/v1/stops`, Tarifpflege, Fahrzeug-/Fahrerstamm, Mitgliedschaften; Benutzer über den IdP), mit Trockenlauf, Wiederaufnahme und Idempotenz je Datensatz.
  - Adapter/Worker: Migrationsprotokoll je Lauf: Quelle, Prüfsumme, Soll-Anzahl je Objektart, Ist-Anzahl nach Lauf, abgelehnte Datensätze mit Grund, Build, Migrationsstand, Ausführender, Freigeber.
  - Datenbank: Zielbestände in `tenant`, `legal_entity`, `organization`, `membership`, `service`, `service_area`, `stop_revision`, `tariff_revision`, `tariff_rule`, `vehicle`, `driver`, `qualification_revision`, `config_revision`; keine direkte SQL-Befüllung.
  - Konfigurationsparameter: Erstwerte der für den Pilot benötigten Parameter als freigegebene `config_revision` (Werte sind fachlich freizugeben, Kap. 01 §1.6).
- **Detailverträge vorab:** Quellformat je Objektart (im Pflichtenheft nicht festgelegt, OP-18); Konsistenzregeln (Geometrievalidität, Haltepunkt im Gebiet, Tarif mit Gültigkeit, Fahrzeug mit Ressourcenvektor, Benutzer mit Rolle und Mandant); Reihenfolge und Abhängigkeiten der Objektarten.
- **Akzeptanzkriterien:**
  1. Das Migrationsprotokoll bestätigt je Objektart (Bediengebiet, Tarif, Betreiber, Fahrzeuge, Benutzer) Soll- und Ist-Bestand und enthält die Fehlerliste; Soll und Ist stimmen überein oder jede Differenz ist einem Fehlereintrag zugeordnet. (QAT-005)
  2. Nach der Einspielung sind die Daten konsistent: keine verwaisten Referenzen, jedes Bediengebiet ein valides Polygon, jeder Tarif mit Gültigkeitsintervall, jedes Fahrzeug einem Betreiber und Dienst zugeordnet, jeder Benutzer mit Mitgliedschaft und Rolle. (QAT-005)
  3. Ein zweiter Lauf derselben Quelle verändert keine Bestände (idempotent); ein abgebrochener Lauf lässt sich fortsetzen.
  4. Die Einspielung für Mandant A erzeugt keine Daten in Mandant B; sie läuft unter einer Rolle mit auf den Zielmandanten begrenzten Rechten und ist in `audit_event` nachvollziehbar.
  5. Eine anschließende Probebuchung im eingespielten Gebiet mit eingespieltem Tarif und Fahrzeug gelingt.
  6. Quelldateien mit Personendaten (Benutzer, Fahrer) werden nur in der Zielumgebung verarbeitet, nicht im Repository oder in Logs abgelegt und nach dokumentierter Frist gelöscht; das Migrationsprotokoll enthält Zählwerte und Kennungen, keine Kontaktdaten.
  7. Die Abnahme erfolgt mit Referenzdaten in Staging; die Einspielung der echten Pilotdaten in die Produktionsumgebung nutzt dasselbe Werkzeug und erzeugt ein eigenes Migrationsprotokoll (Durchführung in WP-M5-29).
- **Tests:** positiv: vollständige Einspielung eines Referenzdatensatzes. negativ: ungültige Geometrie, Haltepunkt außerhalb des Gebiets, doppeltes Kennzeichen, Tarif ohne Gültigkeit, Benutzer ohne Rolle – jeweils Ablehnung mit Fehlerlisteneintrag, übrige Datensätze unberührt. konkurrierend (Datentrennung): Einspielung zweier Mandanten parallel; Einspielung parallel zu laufendem Buchungsbetrieb des anderen Mandanten.
- **Abhängigkeiten:** intern: WP-M5-02.
  - extern: WP-M1-05, WP-M1-06, WP-M1-07, WP-M1-22 (Mandant, Rechtsträger, Organisation, Mitgliedschaft, Rollen, Konfigurationsrevisionen mit Freigabe)
  - extern: WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07 (Dienste, Bediengebiete, Haltepunkte, Tarifrevision)
  - extern: WP-M3-03, WP-M3-05 (Fahrzeugstamm, Fahrerstamm und Qualifikationen)
- **Entscheidungen:** D-05 (Pilotbetreiber, Region, Flotte) blockiert die Einspielung der echten Pilotdaten, nicht den Bau und die Abnahme mit Referenzdaten; D-01 (Rollen der Rechtsträger) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Übernahme historischer Buchungen oder Finanzdaten aus Altsystemen (nicht gefordert); Einspielung von Schichten und Dienstplänen (nicht in der Anforderung genannt, OP-18); Produktionsumgebung (WP-M5-28); Portabilitätsexport (`LH-6.19-NFR-020`, P2).

### WP-M5-26 – Betriebshandbuch: Monitoring, Eskalation, Notbetrieb

- **Ziel:** Ein Betriebshandbuch beschreibt den Regelbetrieb, die Überwachung, die Eskalation und den Notbetrieb so, dass der Betreiber die Plattform ohne das Entwicklungsteam betreiben kann (Betriebs-Gate aus Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Betriebshandbuch im Repository (versioniert) mit: Systemübersicht, Umgebungen, Deployment und Migration (expand/contract, Vorwärtskorrektur statt Löschen bestätigter Buchungen, Kap. 01 §1.8), Dashboards, Runbook je Alarm aus WP-M5-04, Eskalationsstufen und Erreichbarkeiten, Backup/Restore-Drehbuch (WP-M5-14), wiederkehrende Übungen, Wartungsfenster, Umgang mit Klärfällen (unbestimmte Zahlungen, überfällige Operationen).
  - Adapter/Worker: Notbetriebsverfahren: Betrieb bei Ausfall der Kernbuchung, der Driver-App-Verbindung, der Leitstellenoberfläche, des PSP; was Fahrer offline dürfen und was serverpflichtig bleibt (Kap. 01 §1.5, D-11).
  - Konfigurationsparameter: Betriebsrelevante Parameter mit Wirkung im Handbuch erläutert (`alert_escalation_delay_min`, `vehicle_unavailability_alert_min`, `notification_quiet_hours`, `daily_report_time`).
- **Detailverträge vorab:** Gliederung; Runbook-Vorlage (Auslöser, Diagnose, Sofortmaßnahme, Eskalation, Nacharbeit); Notbetriebs-/Kontaktprozess und berechtigte Freigaberollen des Betreibers (von diesem zu liefern, D-11).
- **Akzeptanzkriterien:**
  1. Für jeden Alarm aus WP-M5-04 existiert ein Runbook; eine Übung je Schweregrad wurde von Betriebspersonal ohne Entwicklerhilfe durchgeführt und protokolliert.
  2. Das Notbetriebsverfahren ist für jeden der vier Ausfallfälle beschrieben und in einer Tischübung mit dem Betreiber durchgespielt.
  3. Das Handbuch nennt für jeden Schritt die erforderliche Rolle; kein Schritt verlangt Superuser- oder direkten Datenbankzugriff im Regelbetrieb.
  4. Erkenntnisse aus WP-M5-15 und WP-M5-16 sind eingearbeitet.
- **Tests:** positiv: Runbook-Übungen. negativ: Übung mit einem Alarm, dessen Runbook fehlt, wird vom Vollständigkeitsabgleich Alarmkatalog ↔ Runbooks erkannt.
- **Abhängigkeiten:** intern: WP-M5-04, WP-M5-05, WP-M5-14, WP-M5-15, WP-M5-16.
  - extern: WP-M1-41 (Sicherheitsvorfallprozess)
  - extern: WP-M3-54, WP-M3-33 (Störungsablauf Fahrzeugausfall, Offline-Verhalten der Driver-App)
- **Entscheidungen:** D-11 (Notbetrieb, Kontaktprozess, Freigaberollen) und D-05 (Supportzeiten, Betrieb) blockieren die Endfassung und die Produktivschaltung, nicht den Entwurf.
- **Größe:** M
- **Nicht im Umfang:** Abgleich des Kapitels „Deployment und Rollout“ gegen die fertige Produktionsumgebung (erfolgt in WP-M5-29 nach WP-M5-28); Administratorhandbuch, Fahrerschulung, Datenschutzdokumentation (WP-M5-27); vertragliche Supportkanäle und Fehlerklassen P0 bis P3 (`LH-6.20-QAT-009`, P2).

### WP-M5-27 – Dokumentationspaket und Demonstration des Standardbetriebs

- **Ziel:** Betriebshandbuch, Administratorhandbuch, Fahrerschulung und Datenschutzdokumentation sind geliefert, und der Abnehmer demonstriert den Standardbetrieb allein mit diesen Materialien.
- **Anforderungen:** `LH-6.20-QAT-006`
- **Liefergegenstände:**
  - Adapter/Worker: Administratorhandbuch (Mandant und Organisation, Benutzer und Rollen, Dienste, Gebiete, Haltepunkte, Tarife, Fahrzeuge, Checklistenvorlagen, Konfigurationsrevisionen mit Vier-Augen-Freigabe und Rollback; UI-17, UI-20, UI-11, UI-12).
  - Adapter/Worker: Fahrerschulung (Unterlagen und Übungsablauf in einer Schulungsumgebung: Dienststart, Pflichtprüfung, Stoppliste, Check-in/Ausstieg/No-Show, Schadenmeldung, Offlineverhalten, Beleg; UI-09, UI-10).
  - Adapter/Worker: Datenschutzdokumentation: Zusammenstellung aus Dateninventar, Löschregeln, DSFA, Rollen-/Feldzugriffskonzept, Standort- und Medienminimierung, Protokollierung und Maskierung.
  - Adapter/Worker: Betriebshandbuch aus WP-M5-26 als Teil des Pakets; Übergabeprotokoll mit Versionsstand je Dokument.
- **Detailverträge vorab:** Definition „Standardbetrieb“ als Liste der zu demonstrierenden Abläufe (OP-19); Dokumentationsstandard (Sprache Deutsch, Versionierung, Bezug auf Build); Schulungsumgebung mit synthetischen Daten.
- **Akzeptanzkriterien:**
  1. Betriebshandbuch, Administratorhandbuch, Fahrerschulung und Datenschutzdokumentation sind versioniert geliefert und auf den Abnahme-Build bezogen. (QAT-006)
  2. Der Abnehmer demonstriert den Standardbetrieb mit den gelieferten Materialien ohne Unterstützung durch das Entwicklungsteam: Benutzer und Fahrzeug anlegen, Dienst und Tarif prüfen, Fahrer startet Dienst mit Pflichtprüfung, Buchung wird disponiert und gefahren, Leitstelle überwacht, Alarm wird nach Runbook behandelt; Abweichungen sind protokolliert. (QAT-006)
  3. Jede in den Handbüchern beschriebene Oberfläche und jeder Schritt existiert im Abnahme-Build (Abgleich Screenshots/Schritte gegen Build).
  4. Die Datenschutzdokumentation enthält keine offenen Platzhalter für Punkte, die das Pflichtenheft festlegt; von D-11 abhängige Fristen sind als „durch Auftraggeber festzulegen“ gekennzeichnet.
  5. Die Unterlagen enthalten keine echten Personendaten (Screenshots und Beispiele aus der Schulungsumgebung mit synthetischen Daten); Handbücher und Schulungsunterlagen sind als zugängliche Dokumente geliefert (strukturierte Überschriften, Alternativtexte) – Maßstab siehe OP-19.
  6. Die Fahrerschulung deckt die sicherheitsrelevanten Abläufe ab: Pflichtprüfung mit kritischem Mangel, Fahrzeugsperre, Verhalten offline und bei Ausfall der App (Notbetrieb aus WP-M5-26).
- **Tests:** positiv: Demonstration durch den Abnehmer. negativ: Durchlauf eines Handbuchkapitels durch eine projektfremde Person deckt fehlende Schritte auf (Befunde werden behoben).
- **Abhängigkeiten:** intern: WP-M5-20 (Standort- und Medienminimierung als Inhalt der Datenschutzdokumentation), WP-M5-25, WP-M5-26.
  - extern: WP-M1-32, WP-M1-33, WP-M1-42, WP-M1-07, WP-M1-22 (Dateninventar, Aufbewahrungs- und Löschregeln, Datenschutz-Folgenabschätzung, Rollenmodell, Konfigurationsfreigabe)
  - extern: WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07 (Pflege von Diensten, Gebieten, Haltepunkten und Tarifen)
  - extern: WP-M3-56 (Driver-App und Leitstelle im abnahmefähigen Stand)
  - extern: WP-M4-18, WP-M4-11, WP-M4-25 (Dienstplan UI-12, Stempeln und Belegerfassung in der Fahrer-App)
- **Entscheidungen:** D-11 (Aufbewahrung, Löschfristen) und D-10 (Fahrerdaten, Mitbestimmung) blockieren die Endfassung der Datenschutzdokumentation und die Produktivfreigabe, nicht die Erstellung.
- **Größe:** M
- **Nicht im Umfang:** Übergabe von Konfigurationen und Wiederherstellungsmitteln zum Projektabschluss (`LH-6.20-QAT-012`, P2); Schulungsdurchführung für das gesamte Pilotpersonal (Betreiberaufgabe).

### WP-M5-28 – Produktionsumgebung und gestaffeltes Rollout für den Pilot

- **Ziel:** Die Produktionsumgebung des Pilotbetriebs ist aus dem Repository aufgebaut und von Staging und Performance getrennt; der Abnahme-Build wird als signiertes Artefakt gestaffelt ausgerollt, und Monitoring, Alarmierung, Proben, Maskierung und Backup/PITR sind dort wirksam, bevor Pilotdaten eingespielt werden. (M0 und M1 liefern nur das Gerüst und die Trennung; die endgültige Umgebung mit gestaffeltem Rollout ist dort ausdrücklich nach M5 verwiesen.)
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `infra/` – Umgebung Production nach Kap. 01 §1.8 mit eigenen Secrets, eigenem PSP-Konto und eigener Identitätsdomäne; kein Netzpfad aus Development, Staging oder Performance zur Produktionsdatenbank; Übernahme der Telemetrie (WP-M5-03), Alarmregeln (WP-M5-04), synthetischen Proben und des Wartungskalenders (WP-M5-05), der Maskierung (WP-M5-06) und von WAL-Archivierung/PITR (WP-M5-14).
  - Adapter/Worker: Bereitstellungsverfahren: ausschließlich signierte Artefakte aus dem Release-Gate (WP-M5-22); expand/contract-Migrationen über den separaten kurzlebigen Migrationszugang; gestaffeltes Rollout mit Rauchprobe je Stufe; bei Fehlern Vorwärtskorrektur statt Löschen bestätigter Buchungen (Kap. 01 §1.8).
  - Datenbank: Runtime-Rolle ohne Superuser, BYPASSRLS und Tabelleneigentum (Kap. 01 §1.7); Wächter „Tabelle ohne Policy“ gegen das Produktionsschema; keine Seed- oder Testdaten außer dem gekennzeichneten Probemandanten aus WP-M5-05.
- **Detailverträge vorab:** Hostingentscheid und Betriebsmodell (D-05); Rollout-Stufen, Rauchprobe und Stoppkriterien (im Pflichtenheft nicht festgelegt, OP-26); Zugriffskonzept des Betriebs einschließlich protokolliertem Notfallzugang; produktives PSP-Konto (D-03).
- **Akzeptanzkriterien:**
  1. Die Produktionsumgebung wird vollständig aus dem Repository aufgebaut; ihr Migrationsstand entspricht dem des Abnahme-Builds in Staging.
  2. Ein unsigniertes Artefakt oder ein Artefakt ohne vollständig bestandenes Release-Gate wird von der Bereitstellung abgewiesen.
  3. Die Prüfung der Runtime-Rolle zeigt weder Superuser noch BYPASSRLS noch Tabelleneigentum; Zugangsdaten und Tokens aus Staging werden in Production abgewiesen und umgekehrt.
  4. Nach dem Rollout läuft die synthetische Probe grün, ein Testalarm erreicht den definierten Empfänger, und der Archivierungsverzug liegt unter 15 min.
  5. Ein in Staging geprobter Abbruch einer Rollout-Stufe unter laufender Buchungslast lässt bestätigte Buchungen, Kapazitätssperren und Zahlungszustände unverändert.
  6. Jeder Betriebszugriff auf Production ist personengebunden, mit MFA geschützt und protokolliert; es gibt keinen geteilten Administratorzugang.
- **Tests:** positiv: Aufbau und gestaffeltes Rollout des Abnahme-Builds mit Rauchprobe. negativ: unsigniertes Artefakt; Migration mit der Runtime-Rolle; Staging-Secret in Production; fehlgeschlagene Rauchprobe stoppt das Rollout. konkurrierend (Kapazität, Geld, Sicherheit): Rollout während laufender Buchungs- und Zahlungslast in Staging – keine verlorene oder doppelte Bestätigung; alte und neue Version verarbeiten dieselben Ereignisse kompatibel; Invariantenabfrage nach Kap. 04 §4.5 ohne Befund.
- **Abhängigkeiten:** intern: WP-M5-03, WP-M5-04, WP-M5-05, WP-M5-06, WP-M5-14, WP-M5-22.
  - extern: WP-M0-11, WP-M0-14 (IaC- und Umgebungsgerüst, CI-Stufe Lieferkette mit signierten Images)
  - extern: WP-M1-40, WP-M1-39, WP-M1-04 (Trennung von Test- und Produktionsdaten und -zugängen, verschlüsselte Backups, OIDC-Anmeldung mit MFA für privilegierte Konten)
- **Entscheidungen:** D-05 (Hosting, Region, Betriebsmodell, Supportzeiten) blockiert den Bau der Produktionsumgebung beim Zielanbieter; ohne Beschluss ist nur eine produktionsgleiche Vorstufe baubar. D-03 (PSP) blockiert die Produktivschaltung der Zahlung, D-11 die Produktivfreigabe personenbezogener Daten.
- **Größe:** M
- **Nicht im Umfang:** Release-Rollback ohne Verlust bestätigter Buchungen als eigene Anforderung (`LH-6.19-NFR-013`, P2, WP-M8e-29); Mehrregionenbetrieb; Einspielung der Pilotstammdaten (WP-M5-29).

### WP-M5-29 – Pilotbereitschaft: Abbruchkriterien, Rückfallbetrieb und Generalprobe

- **Ziel:** Vor Pilotbeginn sind Abbruchkriterien und Rückfallbetrieb festgelegt und das Pilot-Gate aus Kap. 08 §8.4 ist nachgewiesen: End-to-End-Betrieb mit freigegebenem Gebiet, Personal, Tarif, Flotte und Prozessverantwortlichen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Pilotbereitschaftsdokument: freigegebenes Gebiet, Tarif, Flotte, Personal und Prozessverantwortliche (Bezug auf das Migrationsprotokoll aus WP-M5-25), messbare Abbruchkriterien (z. B. Mangel der Klasse A, Verfügbarkeit unter Schwelle, ungeklärte Zahlungen über Schwelle – Schwellen vom Auftraggeber festzulegen), Rückfallbetrieb (wie Fahrten ohne Plattform disponiert und Fahrgäste informiert werden), Entscheidungsweg und Wiederaufnahme.
  - Adapter/Worker: Generalprobe: ein Betriebstag in Staging mit Pilotstammdaten und Betreiberpersonal.
  - Adapter/Worker: Einspielung der freigegebenen Pilotstammdaten in die Produktionsumgebung (WP-M5-28) mit dem Werkzeug aus WP-M5-25 und eigenem Migrationsprotokoll; Abgleich des Betriebshandbuch-Kapitels „Deployment und Rollout“ gegen die Produktionsumgebung.
- **Detailverträge vorab:** Vorlage Abbruchkriterium (Messgröße, Schwelle, Messquelle, Entscheider); Rückfallprozess des Betreibers (von diesem zu liefern, D-11).
- **Akzeptanzkriterien:**
  1. Abbruchkriterien und Rückfallbetrieb sind vor Pilotbeginn vom Auftraggeber freigegeben; jedes Kriterium ist aus dem Monitoring (WP-M5-04, WP-M5-05) messbar.
  2. Die Generalprobe ist ohne Mangel der Klasse A abgeschlossen; Befunde sind mit Mangelklasse erfasst.
  3. Der Rückfallbetrieb wurde einmal geübt (Tischübung oder Teilübung) und protokolliert.
  4. Das Migrationsprotokoll der Produktionseinspielung bestätigt Soll- und Ist-Bestände für Bediengebiet, Tarif, Betreiber, Fahrzeuge und Benutzer; die Produktionsdatenbank enthält außer dem gekennzeichneten Probemandanten keine Testdaten.
  5. Für jede Prozessverantwortung des Pilot-Gates (Leitstelle, Werkstatt/Fahrzeugfreigabe, Zahlungsklärung, Datenschutz, Betrieb/Rufbereitschaft) ist eine Person mit Vertretung benannt und in der Plattform mit der passenden Rolle angelegt.
- **Tests:** positiv: Generalprobe; Produktionseinspielung mit Probebuchung des Probemandanten. negativ: simuliertes Erreichen eines Abbruchkriteriums löst den Entscheidungsweg aus; Einspielversuch mit einer Rolle ohne Rechte am Zielmandanten wird abgewiesen.
- **Abhängigkeiten:** intern: WP-M5-04, WP-M5-05, WP-M5-25, WP-M5-26, WP-M5-27, WP-M5-28.
- **Entscheidungen:** D-05 (Pilotbetreiber, Region) und D-11 (Notbetrieb) blockieren den Abschluss dieses WP und den Pilotbeginn; D-12 (Termin) bestimmt den Zeitpunkt.
- **Größe:** M
- **Nicht im Umfang:** Durchführung des Pilotbetriebs; Pilot mit begrenztem Zeitraum als eigene Anforderung (`LH-6.20-QAT-008`, P2 SOLL, M8).

### WP-M5-30 – Barrierefreiheitsprüfung der übrigen Kernreisen (Fahrgast, Leitstelle, Flotte, Belege, Verwaltung)

- **Ziel:** Das UX-Gate aus Kap. 08 §8.4 ist für alle übrigen P1-Rollen nachgewiesen: Die Kernreisen von Fahrgast, Leitstelle, Flotte/Werkstatt, Belegprüfung und Verwaltung sind mit Tastatur und Screenreader bedienbar, und jede Pflichtaktion ist ohne Karten- oder Drag-and-drop-Zwang möglich.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: Prüfung und Nachbesserung der Kernreisen in UI-01 bis UI-05 (Fahrgast: Suche, Angebot, Checkout, Reise/Storno, Konto), UI-07 (Operator Livebetrieb einschließlich Zustand „veralteter Standort“), UI-11 (Flotte/Werkstatt: Reparatur und Freigabe), UI-14 (Belege prüfen und freigeben), UI-15 (Kostenübersicht im Grundausbau aus M4), UI-17 (Gebiete/Stops/Tarife) und UI-20 (Audit/Support im Grundumfang aus M1); jeweils einschließlich der Leer-, Fehler- und Rechtezustände aus Kap. 06 §6.9.
  - Adapter/Worker: manuelles Prüfprotokoll mit Tastatur und Screenreader je Kernreise; Nutzung des CI-Schritts aus WP-M5-18; Prüfung von 200-%-Textvergrößerung und Reflow bei 320 CSS-Pixeln (Kap. 06 §6.1).
- **Detailverträge vorab:** Prüfplan Barrierefreiheit aus WP-M5-01 (Kernreisen und Pflichtaktionen je Rolle); Abnahmemaßstab wie WP-M5-18 (OP-14).
- **Akzeptanzkriterien:**
  1. Jede Kernreise des Prüfplans ist per Tastatur und Screenreader vollständig durchlaufen; jede Karte hat eine gleichwertige Listenalternative; Status wird nie nur über Farbe vermittelt; asynchrone Statusmeldungen laufen über eine Live-Region.
  2. Die P1-relevanten UI-Abnahmefälle aus Kap. 06 §6.9 sind per Tastatur und Screenreader durchlaufen: Financebeleg mit Dublette und Rundungsrest (UI-14); Mandantenwechsel mit zwei Tabs; 412-Konflikt ohne stilles Überschreiben.
  3. Der Buchungsweg des Fahrgasts besteht AT-19 auf dem Abnahme-Build erneut (Regression; in M2 bestanden).
  4. Fehler- und Rechtezustände (Holdverlust, Preisänderung, Sperre, fehlende Freigaberolle, Datenlücke) werden angesagt und sind per Tastatur erreichbar; der Fokus geht nach Dialogen nicht verloren.
  5. Jede blockierte Kernreise ist behoben; verbleibende Befunde sind mit Mangelklasse und Termin dokumentiert.
- **Tests:** positiv: Kernreise je Rolle mit Tastatur und Screenreader. negativ: Pflichtaktion nur per Karte oder Zeiger gilt als Fehler; Zeitlimit des Checkouts (Hold-Ablauf) wird rechtzeitig und wahrnehmbar angekündigt; Mandantenwechsel im zweiten Tab zeigt im ersten Tab keinen fremden Datenstand.
- **Abhängigkeiten:** intern: WP-M5-18 (CI-Schritt, Protokollvorlage).
  - extern: WP-M1-43, WP-M1-44 (Web-Designsystem, Anmeldung und Mandantenwechsel)
  - extern: WP-M2-35, WP-M2-42, WP-M2-43, WP-M2-47 (Passenger-PWA UI-01 bis UI-05, barrierefreier Buchungsweg)
  - extern: WP-M3-36, WP-M3-03, WP-M3-56 (Leitstelle Livebetrieb UI-07, Flotte/Werkstatt UI-11)
  - extern: WP-M4-38, WP-M4-44, WP-M4-59 (UI-14 Belegprüfung und Freigabe, UI-15 Kostenübersicht, Kennzahlenansicht)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fahrer- und Planeroberflächen (WP-M5-18); vollständige WCAG-2.2-AA-Konformitätserklärung (`LH-6.19-NFR-011`, P2, M8); Gerätetestmatrix (`LH-6.19-NFR-010`, P2, M8); Callcenter- und Zweibetreiber-Abnahmefälle aus Kap. 06 §6.9 (M6/M7).

### WP-M5-31 – Nachweisführung P1 und M5-Gate-Bericht

- **Ziel:** Alle 202 P1-Anforderungen sind mit Test und Evidenz verknüpft, alle sieben Gates aus Kap. 08 §8.4 sind mit Nachweis belegt, und der Gate-Bericht weist keine offenen Mängel der Klasse A aus.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Regressionslauf aller Gate-Suiten aus M1 bis M4 auf dem Abnahme-Build in Staging; Eintrag von AT-23, AT-25, AT-53, AT-54, AT-59, AT-61 und aller wiederholten Tests mit Build, Evidenz und Entscheidung in `e2e.csv` (ausschließlich aus Testläufen, Umsetzungsplan §6).
  - Adapter/Worker: Nachweismatrix P1: je `source_key` Testfall, letzter Lauf, Build, Evidenzverweis, Mangelklasse offener Befunde; erzeugt aus `arbeitspakete.csv` und den Evidenzdatensätzen.
  - Adapter/Worker: Review-Protokoll für das Gate „Fachmodell“: Durchsicht der in M1 bis M4 umgesetzten Zustandsautomaten, Entitäten sowie Preis- und Kostenregeln gegen die Detailverträge, mit Liste der offenen Geschäftsentscheidungen und der dadurch gesperrten produktiven Aktivierungen.
  - Adapter/Worker: Gate-Bericht mit je einem Abschnitt für Fachmodell, CI, Partnerfreigabe, Sicherheit, UX, Betrieb, Pilot; Mängelliste nach Klassen A/B/C mit Workaround und Termin; Liste der Entscheidungen D-01 bis D-12 mit Stand und Wirkung auf die Produktivschaltung.
- **Detailverträge vorab:** Berichtsgliederung; Regel für das Gate „Partnerfreigabe“ in P1 (keine buchbaren Partner: Nachweis, dass alle Partnerfähigkeiten in der Capability Registry deaktiviert sind, OP-20); Evidenzformat aus WP-M0-22.
- **Akzeptanzkriterien:**
  1. Die Nachweismatrix enthält alle 202 P1-Anforderungen mit mindestens einem grünen positiven Test und Evidenz; Sicherheits-, Geld-, Kapazitäts- und Datentrennungsanforderungen haben zusätzlich negative und konkurrierende Tests.
  2. AT-23, AT-25, AT-53, AT-54, AT-59 und AT-61 sind in `e2e.csv` mit Build, Evidenz und Entscheidung eingetragen.
  3. Für jedes der sieben Gates liegt der in Kap. 08 §8.4 geforderte Nachweis vor oder die Sperrwirkung ist ausdrücklich festgehalten.
  4. Es gibt keinen offenen Mangel der Klasse A; jeder Mangel der Klasse B hat freigegebenen Workaround und Termin, jeder der Klasse C einen Maßnahmenplan.
  5. Die Evidenz enthält keine unnötigen Fahrgast- oder Personaldaten (Stichprobe mit dem Prüfskript aus WP-M5-06).
  6. Das Review-Protokoll zum Gate „Fachmodell“ liegt vor; jede ungeklärte Geld- oder Beförderungsrolle ist mit der dadurch gesperrten produktiven Aktivierung benannt.
  7. Alle Evidenzen des Berichts stammen vom selben Abnahme-Build und Migrationsstand; nach einer Fehlerbehebung sind die betroffenen Tests und die Gate-Suiten auf dem neuen Build wiederholt (kein Mischen von Builds).
  8. Jeder Evidenzdatensatz enthält die Pflichtfelder aus Kap. 08 §8.4 (`source_key[]`, Test-ID/Version, Build, Migrationsstand, Konfigurations-/Tarifrevision, Seed und Datenstand, Rollen und Mandanten, Soll/Ist, Ereignis-/Trace-IDs, Ergebnis, Mangelklasse, Verantwortlicher, Abnahmeentscheidung).
- **Tests:** positiv: die Regressions- und Gate-Suiten auf dem Abnahme-Build (u. a. PH-AT-001, PH-AT-004, PH-AT-011, PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-017 bis PH-AT-021 sowie die AT-/E2E-Tests der Gates M1 bis M4). negativ: eine absichtlich entfernte Evidenz lässt die Nachweismatrix unvollständig erscheinen und den Berichtslauf fehlschlagen; eine Evidenz von einem anderen Build wird abgewiesen. konkurrierend: die Kapazitäts-, Geld- und Datentrennungstests der Gate-Suiten (PH-AT-001, PH-AT-004) laufen mit parallelen Transaktionen bzw. zwei Mandanten.
- **Abhängigkeiten:** intern: WP-M5-01, WP-M5-02, WP-M5-03, WP-M5-04, WP-M5-05, WP-M5-06, WP-M5-07, WP-M5-08, WP-M5-09, WP-M5-10, WP-M5-11, WP-M5-12, WP-M5-13, WP-M5-14, WP-M5-15, WP-M5-16, WP-M5-17, WP-M5-18, WP-M5-19, WP-M5-20, WP-M5-21, WP-M5-22, WP-M5-23, WP-M5-24, WP-M5-25, WP-M5-26, WP-M5-27, WP-M5-28, WP-M5-29, WP-M5-30 (alle WPs dieser Datei).
  - extern: WP-M0-22, WP-M0-32, WP-M0-33 (Akzeptanztestgerüst, Nachverfolgungs-CI, bestätigte Testzuordnung)
  - extern: WP-M1-45 (M1-Gate-Abnahmesuite)
  - extern: WP-M2-48 (Gate-Nachweis M2)
  - extern: WP-M3-56 (M3-Gate-Abnahmesuite)
  - extern: WP-M4-63 (M4-Gate-Abnahmesuite)
- **Entscheidungen:** D-01 bis D-12 werden im Bericht mit Stand geführt; offene Entscheidungen blockieren die jeweilige Produktivschaltung (insbesondere D-05, D-11, D-03 Teil PSP, D-06/D-07, D-09, D-10), nicht die Erstellung des Berichts.
- **Größe:** M
- **Nicht im Umfang:** Abnahmeentscheidung selbst (Auftraggeber); Nachweise für P2/P3.

## Offene Punkte

- **OP-01 Referenzprofil nicht vereinbart** (`LH-6.19-NFR-006`, `LH-6.19-NFR-002`, `LH-6.19-NFR-003`): Die Abnahmen verlangen ein „vereinbartes Referenzprofil“. Peak-Anfragen/s, gleichzeitige Suchen/Buchungen, Zahl aktiver Mandanten, Peakfaktor und die Definition einer „parallelen Session“ fehlen (D-05; Kap. 08 §8.6: „Tagesdurchschnitt allein genügt nicht“). Unklar ist auch, ob 30.000 Buchungen/Tag und 5.000 Sessions je Mandant gelten (so Kap. 01 §1.8) oder insgesamt (Wortlaut NFR-006 nennt „je Mandant“ nur bei den Fahrzeugen).
- **OP-02 Verfügbarkeit erst im Betrieb nachweisbar** (`LH-6.19-NFR-001`): Die Abnahme verlangt einen Monitoringbericht über die Monatsverfügbarkeit. In M5 lässt sich nur das Messverfahren und ein Bericht über einen Testzeitraum abnehmen, nicht ein voller Kalendermonat Produktivbetrieb. Messpunkte, Messgrenze Plattform/Provider und die „vertraglich definierte Wartung“ sind offen (D-05).
- **OP-03 Probebuchungen und Statistik** (`LH-6.19-NFR-001`): Externe funktionale Buchungsproben (Kap. 08 §8.6) erzeugen Anfragen und Buchungen. Das Pflichtenheft nennt keine Kennzeichnung oder Ausschlussregel für synthetische Vorgänge in `travel_attempt` und den Kennzahlen.
- **OP-04 „Nicht-optimierende Standard-API“** (`LH-6.19-NFR-002`): Eine Liste der betroffenen Endpunkte fehlt. Ebenso fehlen Vorgaben zur Hardware, auf die sich das Ziel bezieht (Hosting unter D-05), und zur Behandlung asynchroner Antworten (`202`).
- **OP-05 Messpunkt der Angebotslatenz** (`LH-6.19-NFR-003`): Die Anforderung sagt „innerhalb von 5 s“ ohne „serverseitig“; der Meilensteinplan verlangt „Messung der p95-Ziele bis zur Clientdarstellung“. Ob das 5-s-Ziel serverseitig oder bis zum Client gilt, ist festzulegen. Die „dokumentierte Testflotte“ (Größe, Vorbelegung, Gebiet) ist nicht beschrieben.
- **OP-06 Zustellweg der Planrevision** (`LH-6.19-NFR-004`): Ob die Driver-App Planrevisionen per Push, Live-Kanal oder Polling erhält, ist im Pflichtenheft nicht festgelegt (siehe M3 OP-05). „Bestätigte Planrevision“ und „bestehende Verbindung“ sind nicht definiert; die Messung „bis zur Darstellung“ verlangt eine Testinstrumentierung der App.
- **OP-07 Kritische Fehlerszenarien und Alarmabgrenzung** (`LH-6.19-NFR-008`): Die „definierten kritischen Fehlerszenarien“ sind im Pflichtenheft nicht aufgezählt. Offen ist ferner, ob technische Alarme in `business_alert`/`alert_policy_revision` (Fachalarme, Kap. 04 §4.4) geführt werden oder ausschließlich im Betriebsmonitoring, und wer Empfänger ist (Supportzeiten unter D-05).
- **OP-08 Aufbewahrung von Telemetrie und Logs** (`LH-6.19-NFR-008`, `LH-6.19-NFR-015`): Fristen für Logs, Traces und Metriken sind nicht festgelegt (D-11).
- **OP-09 Maskierungsregeln** (`LH-6.19-NFR-015`): Die Abnahme prüft „eingehaltene Maskierungsregeln“; die Regeln selbst fehlen. „Unnötige Kontaktinformationen“ ist nicht abgegrenzt (z. B. ob eine Fahrgast-UUID, ein Haltepunktname oder eine Start-/Zielkoordinate im Log zulässig ist). Stichprobenumfang und -verfahren sind nicht genannt.
- **OP-10 Rundungsregeln für Preise und Zeit** (`LH-6.19-NFR-012`): Kap. 03 §2.1 regelt Steuerrundung und Umlage, nicht aber die Rundung streckenabhängiger Fahrpreise (Grundpreis/Strecke), von Rabatten oder Stornoentgelten. Für Zeit ist keine Rundungsregel beschrieben (z. B. Anzeige von ETA-Minuten, Arbeitszeit in Minuten). Die „Rundungsreferenztests“ der Abnahme sind nicht benannt; dieser Plan verwendet die Beispiele aus Kap. 03 §2.1, PH-AT-013 und PH-AT-018.
- **OP-11 Schwelle und Qualitätsflag der Position** (`LH-6.19-NFR-005`): `kataloge.csv` enthält keinen Parameter für die Veraltet-Schwelle der Anzeige (`eta_max_data_age_sec` gilt für das ETA-Modell). Die Werte des Qualitätsflags, das Sendeintervall und ein Endpunkt für Positionsmeldungen fehlen (siehe M3 OP-04). „Normalbetrieb“ ist nicht abgegrenzt.
- **OP-12 Geltungsbereich „App“** (`LH-6.19-NFR-009`): Unklar ist, ob die Anforderung nur die Driver-App meint (Abnahme: „letzte Aufträge“) oder auch Passenger-PWA und Operator-Web. Dieser Plan behandelt Driver-App und Passenger-PWA als Pflicht und den Operator-Livekanal als Ergänzung. „Relevante letzte Aufträge“ ist nicht definiert (Zeitraum, Umfang).
- **OP-13 Gate-Tests ohne tragende P1-Anforderung in dieser Einheit** (AT-25, AT-53, AT-59, AT-61): Keine der 17 Anforderungen fordert Exporte, Archivregeln oder EXIF-Minimierung. Die Funktionen liegen fachlich bei M1 (Löschregeln), M3 (Prüfnachweis, Schadenakte; deren Export wurde dort ausdrücklich nach M5 verwiesen) und M4 (Stundenabrechnung, Berichtsjobs) oder sind P2 (`LH-6.19-NFR-020`). Die „Freigabecheckliste“ der gesetzlich relevanten Kategorien für den PBefG-Datenexport (AT-25) ist im Pflichtenheft nicht enthalten; Felder von `export_job` sind nicht spezifiziert. In WP-M0-33 ist zu klären, ob diese Tests im P1-Umfang zugeschnitten oder einem späteren Gate zugeordnet werden; WP-M5-19 und WP-M5-20 planen den Mindestumfang. Konkret fehlen in M1 bis M4: die Endpunkte `/v1/reports/jobs` und `/v1/reports/jobs/{id}` sowie die Tabelle `export_job` (in M4 nach M8 verwiesen), ein Objekt „Stundenabrechnung“ (M4 liefert nur Zeitereignisse), die EXIF-Minimierung (P2-Lieferung WP-M7c-27) und das standortgestützte Stempeln (`clock_in_geofence_enabled` bleibt in P1 aus; P2-Lieferung WP-M7d-40). Die P2-Einheiten beanspruchen selbst Anteile dieser Tests (WP-M7d-40 „Stempelanteil von AT-59“, WP-M7c-27 „Medienanteil von AT-59“, WP-M8e-20 Regression AT-25). Ohne Beschluss entsteht entweder in M5 Funktionsumfang ohne tragende P1-Anforderung oder das M5-Gate ist mit P1-Mitteln nicht vollständig bestehbar.
- **OP-14 Barrierefreiheit in P1** (AT-54; Bezug `LH-6.19-NFR-011`, P2): Das UX-Gate verlangt Tastatur- und Screenreaderbedienung der Kernreisen bereits für P1, die WCAG-Anforderung selbst ist P2. Das Prüfniveau für die P1-Abnahme (vollständig WCAG 2.2 AA oder nur Kernreisen) und die Liste der Kernreisen je Rolle sind zu bestätigen. AT-54 wird zusätzlich von P2-Lieferungen beansprucht (WP-M7d-06 „Planeranteil von AT-54“, WP-M7c-31 barrierefreie Checklisten); M4 liefert Schichtaktionen per Klick, Kopie und Drag-and-drop (WP-M4-19), aber keinen ausdrücklich zugesagten vollständigen Tastatur-/Listenweg. Ob WP-M5-18 diesen Weg bereits in P1 nachbessert oder AT-54 erst mit M7 vollständig bestehbar ist, muss entschieden werden.
- **OP-15 EXIF-Minimierung gegenüber unveränderlichem Original** (AT-59): Das Pflichtenheft verlangt für Belege und Evidenz das unveränderte Original mit Hash, AT-59 verlangt EXIF-Minimierung. Ob das Original mit Metadaten geschützt archiviert und nur die Ansichtsfassung bereinigt wird oder ob bereits vor dem Hash bereinigt wird, ist nicht festgelegt.
- **OP-16 „Zentraler Backlog“ und Besitzer** (`LH-6.20-QAT-001`): `arbeitspakete.csv` hat keine Spalte für den Besitzer; ob diese Datei als zentraler Backlog gilt oder ein Werkzeug des Auftraggebers verlangt wird, ist offen. Umfang und Ziehung der Abnahmestichprobe sind nicht beschrieben.
- **OP-17 Pflichtprüfungen und Negativfälle** (`LH-6.20-QAT-002`, `LH-6.20-QAT-004`): Die Liste der „Pflichtprüfungen“ und ein Abdeckungsmaß für „decken ab“ fehlen. Bei QAT-004 ist „Timeout“ nicht näher bestimmt (Hold-Ablauf, PSP, Solver, Client); die „definierten Status und Aufräumprozesse“ je Negativfall müssen aus den Zustandsautomaten von M2/M3 abgeleitet werden, Fristen für das Aufräumen sind nicht genannt.
- **OP-18 Quellformate der Stammdaten** (`LH-6.20-QAT-005`): Quellsysteme, Dateiformate und Mengengerüst des Pilotbetreibers sind unbekannt (D-05). Kap. 05 §5.7 nennt keine Importendpunkte; für Benutzer ist offen, ob sie im IdP angelegt, eingeladen oder importiert werden. Ob Fahrer und Schichten zu „Benutzer“ bzw. zum Einspielumfang zählen, sagt die Anforderung nicht.
- **OP-19 Umfang der Dokumentation und „Standardbetrieb“** (`LH-6.20-QAT-006`): Die Abläufe, die als Standardbetrieb zu demonstrieren sind, Form und Sprache der Unterlagen sowie der Umfang der Fahrerschulung (Unterlagen oder Durchführung) sind nicht festgelegt. Die Abgrenzung der „Datenschutzdokumentation“ zu Dateninventar (WP-M1-32) und DSFA (WP-M1-42) ist zu bestätigen.
- **OP-20 Gate „Partnerfreigabe“ und Partner-Fault-Injection in P1** (Kap. 08 §8.4, §8.6): P1 enthält keine buchbaren Partner (FED 0 P1-Anforderungen). Der Plan wertet das Gate als erfüllt, wenn alle Partnerfähigkeiten deaktiviert sind; die Capability Registry (`adapter_capability`) entsteht jedoch erst in M6. Der Partnerausfall der Fault-Injection wird nach M6 verschoben.
- **OP-21 Replikation und Wiederanlaufumfang** (`LH-6.19-NFR-007`): Topologie (Standby, Region), Einbezug des Objektspeichers in RPO/RTO, das Ende der RTO-Messung und der Umfang des PSP-Abgleichs sind nur in Kap. 08 §8.6 umrissen; Hosting und Betriebsmodell stehen unter D-05. Ohne PSP-Entscheid (D-03 Teil) ist der Abgleich nur gegen den Simulator nachweisbar.
- **OP-22 Abbruchkriterien und Rückfallbetrieb** (Pilot-Gate Kap. 08 §8.4; Bezug `LH-6.20-QAT-008`, P2 SOLL): Das Gate verlangt beides vor Pilotbeginn, die zugehörige Anforderung ist P2. Schwellen und der Rückfallprozess müssen vom Auftraggeber bzw. Betreiber kommen (D-11).
- **OP-23 Penetrationstest** (Bezug `LH-6.18-SEC-009`, in M1 zugeordnet): Prüfstelle, Umfang und Zeitpunkt sind nicht festgelegt; die Durchführung ist hier in WP-M5-17 geplant, die Anforderung bleibt in M1 zugeordnet.
- **OP-24 M4-Bezüge**: Die EXTERN[M4]-Abhängigkeiten sind auf die WP-IDs der M4-Datei aufgelöst. Offen bleibt, dass M4 keinen Berichts-/Exportjob liefert (siehe OP-13) und dass der durchgängige Referenzfall (WP-M5-23) eine veröffentlichte Fahrerschicht voraussetzt (WP-M4-17), die in `LH-6.20-QAT-003` nicht als Schritt genannt ist.
- **OP-25 Abgleich mit `arbeitspakete.csv`**: Die dortige Zuordnung führt M5 gröber. Nach Freigabe dieser Datei sind WP-IDs, Detailverträge und Testfälle je Anforderung dort nachzutragen (außerhalb dieses Auftrags).
- **OP-26 Produktionsumgebung und gestaffeltes Rollout** (Kap. 01 §1.8; Bezug `LH-6.19-NFR-013`, P2): Keine der 17 Anforderungen verlangt den Aufbau der Produktionsumgebung; M0 und M1 verweisen die endgültige Umgebung mit gestaffeltem Rollout nach M5, das Pilot-Gate setzt sie voraus. WP-M5-28 plant sie als Enabler. Rollout-Stufen, Rauchprobe, Stoppkriterien, Zugriffskonzept des Betriebs und Hostinganbieter sind im Pflichtenheft nicht festgelegt (D-05); die Rollback-Anforderung selbst ist P2.
- **OP-27 Geräte und Testmittel** (`LH-6.19-NFR-004`, `LH-6.19-NFR-009`, AT-54): Zielgeräte und Betriebssystemversionen der Driver-App, die eingesetzten Screenreader sowie die Testinstrumentierung „Revision dargestellt“ sind nicht festgelegt (Gerätetestmatrix `LH-6.19-NFR-010` ist P2). Ohne Festlegung sind die Messung bis zur Clientdarstellung und die Screenreaderprüfung nur für die im Prüfplan aus WP-M5-01 gewählten Geräte aussagekräftig.
- **OP-28 Probemandant in Produktion** (`LH-6.19-NFR-001`): Die funktionale Buchungsprobe benötigt in der Produktionsumgebung einen Probemandanten mit Dienst und Fahrzeug. Ob ein solcher technischer Mandant vertraglich und datenschutzrechtlich zulässig ist und wie er in Mandantenlisten, Abrechnung und Kennzahlen ausgeblendet wird, ist nicht geregelt (ergänzt OP-03).
- **OP-29 Umfang der Fault-Injection für den Dokumenten-Worker** (Kap. 08 §8.6): „Worker“ ist dort nicht aufgeschlüsselt. Dieser Plan bezieht Routing- und Dokumenten-Worker sowie das Outbox-Relay ein; erwartetes Verhalten bei hängender Malware-Prüfung oder OCR ist aus M4 (WP-M4-21, WP-M4-27) abzuleiten und im Pflichtenheft nicht als Ausfallfall beschrieben.
