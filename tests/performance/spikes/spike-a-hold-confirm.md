# Spike (a) WP-M0-26: Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS

Stand 11.11.2026. Alle Zahlen in diesem Bericht stammen aus `spike-a-data/results.json` (Messlauf mit `run_spike_a.py`, Seed 1) und werden von `build_report.py` eingesetzt. Wo etwas nicht gemessen wurde, steht es ausdrücklich so da.
Der Prototyp ist ein Wegwerf-Prototyp und nicht Teil des Produktivcodes.

## 1. Ergebnis gegen die Erfolgskriterien

| Nr. | Kriterium | Urteil | Beleg |
|---:|---|---|---|
| 1 | Korrektheit PH-AT-004: höchstens eine Bestätigung bei einem freien Sitz, 100 parallel, ≥ 1.000 Durchläufe ohne Verstoß | erfüllt | 1000 Durchläufe × 100 Anfragen, genau 1 Gewinner je Durchlauf, 0 Verstöße; jede Abweisung mit Grund `NO_CAPACITY_SEATS` |
| 2 | Varianten: Sitz frei bei belegtem Rollstuhlplatz; veränderte Routenrevision | erfüllt | Rollstuhlvariante 100 Durchläufe, 0 ersetzt; Revision vor Hold: 0 bestätigt; nach Hold: 0 Buchungen |
| 3 | Wiederholte Bestätigung findet dieselbe Buchung; Ablauf/Confirm am Grenzzeitpunkt nie „abgelaufen und gebucht“ | erfüllt | Wiederholung 100 Durchläufe × 100 parallel; Grenzrennen 6000 Holds, Verstöße 0 |
| 4 | Kein unbehandelter Deadlock; jede Transaktion endet mit Erfolg oder fachlichem Grund | erfüllt | Empfohlene Strategie: 0 Deadlocks (Client) in E1/E4/E5; Server-Zähler außer E4-Gegenprobe 0 (`pg_stat_database`) |
| 5 | p95 je Hold/Confirm unter 100 parallelen Transaktionen ≤ 1 s | erfüllt | höchster gemessener p95 über alle Szenarien und beide Indexvarianten: 561.3 ms (Szenarien siehe Abschnitt 5) |
| 6 | Bericht mit Rohdaten und reproduzierbarem Skript | erfüllt | `run_spike_a.py` (Seed 1), `spike-a-data/results.json`, Rohdaten je Durchlauf/Ereignis als `.csv.gz`, dieser Bericht per `build_report.py` |
| 7 | PostGIS: erwartete Ergebnisse für innen/außen/Grenze; p95 unter 100 parallelen Anfragen gemessen; Plan belegt Indexnutzung bei zwei Mandanten | teilweise | Korrektheit bestanden; höchster p95 unter 100 parallel für Gebiet/KNN/Gitterzelle 86.2 ms. Der GiST-Index wird unter RLS für Bereichsprädikate (`&&`, `ST_DWithin`, `ST_Covers`) NICHT genutzt (Befund B-04); Indexnutzung belegt für KNN (`stop_geo`) und Gitterzelle (`stop_cell`) |
| 8 | Mandantentrennung: kein Hold/keine Buchung/keine Geoabfrage von A sieht oder sperrt Zeilen von B; alles unter Runtime-Rolle mit transaktionslokalem Kontext | erfüllt | siehe Abschnitt 7 |

## 2. Messaufbau und Messprotokoll

| Messprotokoll | Wert |
|---|---|
| Hardware | Intel(R) Xeon(R) Processor @ 2.10GHz · 4 logische CPUs · 16086 MB RAM · Linux-6.18.44-fc-v378-x86_64-with-glibc2.39 |
| Virtualisierung | nicht geprüft |
| Last auf dem Rechner (Load Average 1/5/15 min) | Beginn 15.5, 7.37, 4.15 · Ende 64.0, 50.7, 28.3 (enthält die eigenen 100 Verbindungen; auf dem Rechner liefen zugleich Prozesse anderer Arbeiten, z. B. Gradle-Builds) |
| PostgreSQL / PostGIS | PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1) · PostGIS 3.4 USE_GEOS=1 USE_PROJ=1 USE_STATS=1 |
| Server-Einstellungen | max_connections=300, shared_buffers=128MB, work_mem=4MB, effective_cache_size=4GB, fsync=on, synchronous_commit=on, deadlock_timeout=1s, default_transaction_isolation=read committed |
| Verbindungen | 100 gleichzeitig offene Verbindungen unter der Rolle `taktovia_runtime` (psycopg 3); Ausführung: processes (4 Prozesse); je Auftrag eigener Thread und eigene Verbindung; alle Aufträge einer Runde starten gleichzeitig |
| Isolationsstufe | READ COMMITTED (Standard); REPEATABLE READ nur als Vergleichsvariante |
| Software-Stand | c2f2a84cadf165065bd29a821bd7528781ad3e07 (git-head); Arbeitskopie mit nicht eingecheckten Änderungen |
| Skript, Seed | `run_spike_a.py`, Seed 1, Laufzeit 2304.6 s, Beginn 2026-11-11T08:09:41Z |

**Abweichungen vom Plan, ausdrücklich:**

- **Eigener PostgreSQL-Cluster statt des geteilten lokalen Clusters.** Der geteilte Cluster hat `max_connections=100` (davon 3 reserviert und weitere durch andere Läufe belegt); 100 wirklich gleichzeitig offene Transaktionen sind dort nicht möglich, und eine Umstellung hätte einen Neustart des geteilten Clusters verlangt. Der Spike lief deshalb auf einem eigenen Cluster (gleiche PostgreSQL-16-Binärdateien, gleiche PostGIS-Version, Standardeinstellungen außer Port und `max_connections=300`), angelegt mit `spike_a/cluster.py` und nach dem Lauf verworfen. Der Messaufbau auf PostgreSQL 17 (Zielversion, Versionsmatrix) steht aus.
- **Lastgenerator in Python.** 100 Threads in einem Python-Prozess verfälschen die Latenz durch die GIL stark (Basislinie `SELECT 1` mit 100 Threads: p50 etwa 48 ms in einem Entwicklungs-Vorlauf, nicht in den Rohdaten). Der Messlauf verteilt die 100 Verbindungen deshalb auf 4 Prozesse mit je 25 Threads; die verbleibende Eigenlast des Generators zeigt die Basislinie unten.
- **Geteilter Rechner.** Der Rechner hatte nur 4 virtuelle CPUs, die sich Datenbank, Lastgenerator und andere Arbeiten (Gradle-Builds anderer Aufgaben) teilten (Load Average in der Tabelle). Alle Latenzen gelten für diese Umgebung und sind obere Schranken gegenüber einem dedizierten Datenbankserver.
- **Geschlossene Last ohne Denkzeit.** Die 100 Arbeiter senden ohne Pause. Das ist ein Sättigungstest: Die Latenz ist dann im Wesentlichen Wartezeit in der Warteschlange (Durchsatz × Parallelität). Ein Szenario mit 300 ms Denkzeit zeigt die Latenz unterhalb der Sättigung.
- **Datenmenge.** Die Basisdaten bilden einen Betriebstag nach der Referenzlast ab (250 Fahrzeuge je Mandant, 60 Buchungen je Fahrzeug, zusammen 30.000 Buchungen); die Geodaten liegen in einer synthetischen Stellvertreterregion (D-05 offen).

Basislinie des Lastgenerators (100 parallele `SELECT 1` mit Commit): p50 4.34 ms, p95 18.3 ms, p99 29.0 ms (n = 10000). Diese Werte sind der Mindestanteil, den Generator und Rechner jeder Messung hinzufügen.

### Datenstand

| Tabelle | Mandant A | Mandant B | Größe gesamt (beide Mandanten, inkl. Indexe) |
|---|---:|---:|---:|
| vehicle | 251 | 251 |  |
| staff_shift | 250 | 250 |  |
| vehicle_run | 6250 | 6250 |  |
| service | 41 | 41 |  |
| capacity_hold | 15000 | 15000 | 11 MB |
| booking | 14250 | 14250 | 11 MB |
| outbox | 15000 | 15000 | 8 MB |
| stop_revision | 24000 | 24000 | 16 MB |
| service_area | 120 | 120 | 0 MB |

`vehicle` enthält 250 Fahrzeuge je Mandant und ein Fahrzeug aus dem Seed des Akzeptanzgerüsts; `vehicle_run` enthält 250 Basisläufe (mit je 60 Holds) und 6.000 Reserveläufe je Mandant, auf denen die Messdurchläufe starten. Die Zahlen sind der Stand vor den Messungen.

## 3. Prototyp und Sperrstrategie

`spike_a/capacity.py` setzt Kap. 01 §1.4 Schritte 5 und 6 um, nur in PostgreSQL unter der Runtime-Rolle mit transaktionslokalem Kontext (`set_config('app.tenant_id', …, true)`):

- **Hold** (eine Transaktion): Lauf(e) in stabiler Reihenfolge sperren, Revision, Zustand und Sicherheitssperre prüfen, Belegung je Abschnitt aus `capacity_hold` summieren (Zustände `HELD` mit `now < expires_at` und `CONSUMED`), Ressourcenvektor je Dimension (Sitz, Rollstuhlplatz) gegen die Fahrzeugkonfiguration prüfen, `capacity_hold` und die `outbox`-Zeile `CapacityHeld` in derselben Transaktion schreiben. Wiederholung derselben Anfrage (gleiche `federation_ref`) findet denselben Hold.
- **Confirm** (eine Transaktion): Lauf(e) sperren, Hold-Zeilen `FOR UPDATE`, vorhandene Buchung zuerst suchen (Wiederholung findet dieselbe Buchung), Ablauf nach Serverzeit `clock_timestamp() >= expires_at` nach dem Sperrerwerb prüfen (bei Ablauf wird `EXPIRED` festgeschrieben, nie gebucht), Planrevision gegen `run_revision` prüfen, Hold auf `CONSUMED`, Buchung und `outbox`-Zeile `BookingConfirmed` schreiben.
- **Freigabe, Storno und Expiryjob** verwenden denselben Sperrpfad; der Expiryjob ist nur eine Beschleunigung, die Ablaufprüfung im Confirm ist maßgeblich (Kap. 02 §7.1).
- **Abschnitte** sind die halb offenen Ganzzahlintervalle `[interval_from, interval_to)` je `vehicle_run`; der **Ressourcenvektor** ist `capacity_vector` (jsonb `{"seats":n,"wheelchair":m}`), je Dimension geprüft: Ein freier Sitz ersetzt keinen Rollstuhlplatz.

Verglichene Strategien:

| Strategie | Beschreibung | Rolle im Spike |
|---|---|---|
| `rc_run_row` | READ COMMITTED, Zeilensperre `FOR UPDATE` auf `vehicle_run`, Läufe aufsteigend nach ID | **Empfehlung** |
| `rc_advisory` | READ COMMITTED, `pg_advisory_xact_lock` je Abschnitt, aufsteigend nach Schlüssel | Alternative |
| `rr_touch` | REPEATABLE READ, `UPDATE vehicle_run SET state = state` (optimistisch), Wiederholung bei 40001 | Alternative |
| `rr_run_row` | REPEATABLE READ, Zeilensperre wie `rc_run_row` | Negativkontrolle: veralteter Snapshot |
| `rc_none` | keine Sperre | Negativkontrolle: zeigt die Race Condition |
| `rc_run_row_naive`, `rc_advisory_naive` | Sperren in Anfragereihenfolge statt stabiler Reihenfolge | Gegenprobe für Deadlocks |

Nicht gemessen: eine eigene Tabelle mit einer Zeile je Kapazitätsabschnitt (die dritte Alternative des Plans, „Abschnittszeilen“). Sie braucht eine Schemaerweiterung außerhalb des Kernschemas; sie wird in Abschnitt 9 als Option für M2 bewertet, nicht als Messwert.

## 4. Korrektheit unter Konkurrenz (Erfolgskriterien 1 bis 4)

### 4.1 PH-AT-004: letzter Platz, 100 parallele Bestätigungen

Aufbau je Durchlauf: ein frischer Lauf mit 4 Sitzen und 1 Rollstuhlplatz, 3 Sitze auf allen 8 Abschnitten durch bestätigte Buchungen belegt (genau ein freier Sitz); 100 unterschiedliche fachliche Anfragen
(jede mit eigener Referenz und zufälligem, aber sich überlappendem Abschnittsintervall) führen gleichzeitig Hold und Confirm aus, verteilt auf beide Mandanten (abwechselnd). Nach jedem Durchlauf prüft ein getrennter Leser: Belegung je Abschnitt
≤ Kapazität, keine negative Belegung, Zahl der Buchungen = Zahl der Bestätigungen, jede Abweisung mit dem Grund `NO_CAPACITY_SEATS`.

| Strategie | Durchläufe | Anfragen | bestätigt | Durchläufe überbucht | Durchläufe ohne Gewinner | Durchläufe mit Kapazitätsverstoß | unerwartete Ergebnisse | Deadlocks | Serialisierungsfehler | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rc_run_row (Empfehlung) | 1000 | 100000 | 1000 | 0 | 0 | 0 | 0 | 0 | 0 | 272.1 | 492.9 | 545.0 |
| rc_advisory | 200 | 20000 | 200 | 0 | 0 | 0 | 0 | 0 | 0 | 319.6 | 577.5 | 621.2 |
| rc_none | 100 | 10000 | 2328 | 100 | 0 | 100 | 0 | 0 | 0 | 138.4 | 200.4 | 229.2 |
| rr_run_row | 100 | 10000 | 9667 | 100 | 0 | 100 | 0 | 0 | 0 | 933.3 | 1139.7 | 1208.9 |
| rr_touch | 200 | 20000 | 200 | 0 | 0 | 0 | 0 | 0 | 0 | 353.1 | 618.4 | 666.1 |

Die empfohlene Strategie `rc_run_row` besteht alle 1000 Durchläufe: in jedem genau eine Bestätigung (also auch keine unnötige Abweisung bei vorhandenem freien Platz), keine Überbelegung, keine negative Belegung, 0 Deadlocks, 0 Serialisierungsfehler.
Die Latenz der Spalten p50 bis p99 ist die Gesamtdauer von Hold plus gegebenenfalls Confirm je Anfrage im Worst Case: alle 100 Anfragen konkurrieren um **denselben** Lauf; sie sind durch die Sperre serialisiert, die Zeit ist im Wesentlichen Warteschlange.

Die Negativkontrollen belegen, dass der Test die Race Condition tatsächlich provoziert: **ohne Sperre** (`rc_none`) ist jeder der 100 Durchläufe überbucht (im Mittel mehr als 23 Bestätigungen statt einer);
**REPEATABLE READ mit Zeilensperre** (`rr_run_row`) ist ebenso in jedem Durchlauf überbucht: Der Snapshot der Transaktion entsteht mit der ersten Anweisung, also vor dem Warten auf die Sperre, und die spätere Belegungsabfrage sieht
die inzwischen bestätigten Holds nicht. **Merksatz für M2:** Die Sperre allein genügt unter REPEATABLE READ nicht.

### 4.2 Varianten aus PH-AT-004

| Variante | Durchläufe | Anfragen | bestätigt | abgewiesen Rollstuhl | abgewiesen Sitz | Rollstuhl ersetzt / REVISION_CONFLICT |
|---|---:|---:|---:|---:|---:|---:|
| Sitz frei, Rollstuhlplatz belegt (50 % Rollstuhlanfragen) | 100 | 10000 | 100 | 5000 | 4900 | 0 |
| Routenrevision vor dem Hold verändert | 50 | 5000 | 0 | – | – | 5000 |
| Routenrevision zwischen Hold und Confirm verändert | 50 | 5000 | 0 | – | – | 5000 |

Der freie Sitz ersetzt keinen fehlenden Rollstuhlplatz: In der gemischten Last (je Hälfte Rollstuhl- und Sitzanfragen) wird nie eine Rollstuhlanfrage bestätigt, und genau eine Sitzanfrage gewinnt. Bei verändertem `vehicle_run.revision` weist der Hold
alle Anfragen mit `REVISION_CONFLICT` ab (Neuplanung oder 409); wird die Revision zwischen Hold und Confirm verändert, weist der Confirm ab und die Hold-Zeilen bleiben unverändert (`HELD`, laufen ab oder werden freigegeben).

### 4.3 Wiederholte Bestätigung und Ablauf am Grenzzeitpunkt

| Fall | Durchläufe | Operationen | Ergebnis |
|---|---:|---:|---|
| Wiederholte Bestätigung desselben Holds (100 parallel) | 100 | 10000 | Verstöße: 0 |
| Ablauf gegen Confirm gleichzeitig um den Grenzzeitpunkt (20 Holds je Durchlauf) | 300 | 6000 | bestätigt 1050, abgelaufen 4950, Verstöße „abgelaufen und gebucht“: 0, weitere Verstöße: 0 |
| Confirm auf Hold mit expires_at = Einfügezeitpunkt | 50 | 50 | REJECTED:HOLD_EXPIRED / state=EXPIRED / bookings=0: 50 |

Beim Grenzrennen laufen je Durchlauf 20 Holds mit `expires_at` 5 bis 45 ms nach dem Anlegen; Confirm-Aufrufe (zufällig 0 bis 50 ms verzögert) und ein Expiryjob (4 Läufe) kommen gleichzeitig. Es ergeben sich beide Ausgänge (bestätigt und abgelaufen), und in keinem Fall
„abgelaufen und gebucht“, „bestätigt ohne Buchung“ oder „weder noch“. Entscheidend ist, dass Confirm und Expiryjob denselben Lock verwenden und der Confirm die Ablaufzeit **nach** dem Sperrerwerb mit `clock_timestamp()` prüft.
Ein Hold mit `expires_at` gleich dem Einfügezeitpunkt (Grenzzeitpunkt oder bereits überschritten) ist beim Confirm immer abgelaufen (`now >= expires_at`), der Ablauf wird festgeschrieben.

### 4.4 Deadlocks bei gegenläufiger Sperrreihenfolge

Je Durchlauf 100 Anfragen, die je zwei Läufe belegen; die Hälfte nennt die Läufe in umgekehrter Reihenfolge (bei `advisory` die Abschnitte).

| Strategie | Durchläufe | Anfragen (je Hälfte gegenläufig) | Deadlocks (Client) | bestätigt | abgewiesen (Kapazität) | Ausnahmen | Kapazitätsverstöße | Dauer je Durchlauf s | p95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rc_advisory | 50 | 5000 | 0 | 200 | 4800 | 0 | 0 | 0.94 | 895.3 |
| rc_advisory_naive | 20 | 2000 | 1960 | 39 | 1 | 0 | 0 | 1.49 | 1033.3 |
| rc_run_row | 50 | 5000 | 0 | 200 | 4800 | 0 | 0 | 0.95 | 911.9 |
| rc_run_row_naive | 20 | 2000 | 1970 | 27 | 3 | 0 | 0 | 13.7 | 30058.7 |

Ohne stabile Reihenfolge (`*_naive`) enden fast alle Anfragen im Deadlock (Zähler des Clients; der Server zählt dieselbe Zahl, Tabelle unten), und jeder Deadlock kostet `deadlock_timeout` (1 s), bei `rc_run_row_naive` dauert ein Durchlauf entsprechend viele Sekunden (Spalte „Dauer je Durchlauf“).
Mit stabiler Reihenfolge (aufsteigend nach Lauf-ID bzw. Schlüssel) entsteht kein einziger Deadlock. Die Sperrreihenfolge ist also **keine Optimierung, sondern Voraussetzung** und gehört in einen Architekturtest (jede Fachoperation sperrt über dieselbe Hilfsfunktion).

| Messblock | Deadlocks laut pg_stat_database |
|---|---:|
| e0_e1 | 0 |
| e2 | 0 |
| e3 | 0 |
| e4 | 3930 |
| e5 | 0 |
| e6 | 0 |
| e7_e8 | 0 |

Die Deadlocks stammen vollständig aus der Gegenprobe (Block `e4`); in allen anderen Blöcken, auch bei der empfohlenen Strategie, zählt der Server 0.

## 5. Latenz unter 100 parallelen Transaktionen (Erfolgskriterium 5)

Arbeitslast je Arbeiter: Hold, bei Erfolg Confirm, bei Erfolg Storno (Kapazität wird frei, die Belegung bleibt im Mittel stabil); 100 Zyklen je Arbeiter, zwei Mandanten abwechselnd.
Szenarien: `spread` (Läufe zufällig über alle 250 je Mandant, Normalfall), `hot5` (nur 5 Läufe je Mandant, starke Konkurrenz), `single` (ein Lauf je Mandant: Worst Case, 50 Arbeiter je Lauf), `spread_paced300ms` (wie `spread` mit 300 ms Denkzeit, unterhalb der Sättigung).
Zwei Indexvarianten: nur die Indexe des Kernschemas (`capacity_hold` hat keinen Index auf `run_id`) und mit zusätzlichem Index `(tenant_id, run_id) INCLUDE (state, interval_from, interval_to)`.

| Variante | genutzter Index | Ausführung ms (EXPLAIN ANALYZE, einzeln) | Puffer hit | Puffer read |
|---|---|---:|---:|---:|
| Kernschema-Indexe | keiner (Seq Scan) | 4.54 | 1479 | 0 |
| mit Index (tenant_id, run_id) | capacity_hold_run | 1.26 | 369 | 14 |

| Index | Szenario | Operation | n | p50 ms | p95 ms | p99 ms | max ms | Sperrwartezeit p95 ms | Durchsatz tx/s | Deadlocks |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| nur Kernschema-Indexe | hot5 | hold | 10000 | 154.0 | 446.9 | 573.8 | 751.9 | 416.1 | 572.6 | 0 |
| nur Kernschema-Indexe | hot5 | confirm | 9689 | 136.0 | 434.7 | 569.8 | 728.7 | 416.6 |  |  |
| nur Kernschema-Indexe | hot5 | cancel | 9689 | 121.4 | 423.7 | 556.9 | 710.8 | 409.9 |  |  |
| nur Kernschema-Indexe | single | hold | 10000 | 335.7 | 561.3 | 635.5 | 732.6 | 542.9 | 285.5 | 0 |
| nur Kernschema-Indexe | single | confirm | 7665 | 329.4 | 553.8 | 636.7 | 706.5 | 546.7 |  |  |
| nur Kernschema-Indexe | single | cancel | 7665 | 326.1 | 547.6 | 617.8 | 719.9 | 540.5 |  |  |
| nur Kernschema-Indexe | spread | hold | 10000 | 138.1 | 262.1 | 348.0 | 695.8 | 77.8 | 801.8 | 0 |
| nur Kernschema-Indexe | spread | confirm | 9922 | 107.1 | 251.4 | 346.7 | 604.2 | 112.5 |  |  |
| nur Kernschema-Indexe | spread | cancel | 9922 | 86.0 | 214.4 | 303.5 | 533.3 | 103.3 |  |  |
| nur Kernschema-Indexe | spread_paced300ms | hold | 3300 | 185.9 | 292.7 | 340.3 | 441.5 | 100.0 | 479.4 | 0 |
| nur Kernschema-Indexe | spread_paced300ms | confirm | 3279 | 69.2 | 176.0 | 231.0 | 306.0 | 63.5 |  |  |
| nur Kernschema-Indexe | spread_paced300ms | cancel | 3279 | 29.8 | 93.3 | 153.2 | 293.3 | 23.7 |  |  |
| mit Index (tenant_id, run_id) | hot5 | hold | 10000 | 97.3 | 356.3 | 548.0 | 1304.1 | 335.1 | 772.1 | 0 |
| mit Index (tenant_id, run_id) | hot5 | confirm | 9687 | 94.4 | 340.9 | 518.0 | 1288.1 | 321.8 |  |  |
| mit Index (tenant_id, run_id) | hot5 | cancel | 9687 | 88.0 | 341.0 | 522.7 | 1294.5 | 326.5 |  |  |
| mit Index (tenant_id, run_id) | single | hold | 10000 | 231.5 | 358.2 | 407.9 | 526.0 | 347.9 | 414.0 | 0 |
| mit Index (tenant_id, run_id) | single | confirm | 7698 | 229.1 | 353.4 | 411.1 | 527.8 | 346.6 |  |  |
| mit Index (tenant_id, run_id) | single | cancel | 7698 | 226.6 | 353.0 | 401.8 | 520.0 | 346.5 |  |  |
| mit Index (tenant_id, run_id) | spread | hold | 10000 | 53.6 | 139.6 | 192.8 | 354.9 | 46.8 | 1492.9 | 0 |
| mit Index (tenant_id, run_id) | spread | confirm | 9921 | 60.2 | 158.4 | 221.6 | 465.1 | 57.6 |  |  |
| mit Index (tenant_id, run_id) | spread | cancel | 9921 | 50.3 | 139.5 | 199.7 | 315.0 | 56.8 |  |  |
| mit Index (tenant_id, run_id) | spread_paced300ms | hold | 3300 | 10.7 | 64.8 | 125.7 | 233.1 | 10.3 | 812.3 | 0 |
| mit Index (tenant_id, run_id) | spread_paced300ms | confirm | 3279 | 11.0 | 62.3 | 105.7 | 145.3 | 9.89 |  |  |
| mit Index (tenant_id, run_id) | spread_paced300ms | cancel | 3279 | 7.37 | 41.1 | 68.5 | 121.1 | 7.38 |  |  |

Lesehilfe: Die Dauer enthält die Wartezeit auf die Zeilensperre (Spalte Sperrwartezeit) und die Eigenlast des Generators (Basislinie in Abschnitt 2). Ohne Denkzeit gilt Durchsatz ≈ 100 / mittlere Latenz: Die Zahlen beschreiben die **Sättigung** der 4-CPU-Umgebung, nicht die Latenz im Normalbetrieb. Das Szenario mit Denkzeit zeigt die Latenz unterhalb der Sättigung.

Ergebnis: Der höchste p95 von Hold und Confirm über alle Szenarien und beide Indexvarianten liegt unter dem Ziel von 1 s („Standard-API p95 ≤ 1 s“, vorläufiges Lastenheftziel). Einzelne Maxima liegen darüber (z. B. `hot5` mit Index: max 1304.1 ms),
das Ziel bezieht sich auf p95. Der fehlende Index auf `capacity_hold.run_id` kostet Durchsatz und Latenz (Szenario `spread`: 801.8 gegen 1492.9 Transaktionen je Sekunde; p95 Hold 262.1 ms gegen 139.6 ms).

### Alternative Sperrstrategien unter gleicher Last (mit Index)

| Strategie | Szenario | Operation | n | p50 ms | p95 ms | p99 ms | Durchsatz tx/s | Deadlocks | Serialisierungsfehler | Wiederholungen |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| rc_advisory | spread | hold | 10000 | 59.6 | 174.1 | 256.1 | 1288.3 | 0 | 0 | 0 |
| rc_advisory | spread | confirm | 9921 | 69.6 | 194.9 | 284.9 | 1288.3 | 0 | 0 | 0 |
| rc_advisory_hot5 | hot5 | hold | 10000 | 85.3 | 208.8 | 290.4 | 1035.8 | 0 | 0 | 0 |
| rc_advisory_hot5 | hot5 | confirm | 9685 | 87.0 | 216.9 | 300.0 | 1035.8 | 0 | 0 | 0 |
| rr_touch | spread | hold | 10000 | 72.5 | 218.7 | 348.3 | 1051.3 | 0 | 1 | 4405 |
| rr_touch | spread | confirm | 9921 | 79.0 | 241.1 | 386.9 | 1051.3 | 0 | 1 | 4405 |
| rr_touch_hot5 | hot5 | hold | 10000 | 145.5 | 548.4 | 782.7 | 472.2 | 0 | 1858 | 44103 |
| rr_touch_hot5 | hot5 | confirm | 2430 | 157.5 | 579.2 | 757.7 | 472.2 | 0 | 1858 | 44103 |

`rc_advisory` ist unter `hot5` schneller als `rc_run_row`, weil Anfragen auf disjunkten Abschnitten desselben Laufs parallel laufen; unter `spread` liegt es leicht hinter der Zeilensperre. `rr_touch` (REPEATABLE READ mit optimistischem Anfassen der Laufzeile) ist unter Streuung noch brauchbar, **kollabiert** aber bei
Konkurrenz auf wenigen Läufen: Viele Transaktionen scheitern nach allen Wiederholungen endgültig mit Serialisierungsfehler (Spalte „Serialisierungsfehler“), das verletzt Kriterium 4 („jede Transaktion endet mit Erfolg oder fachlichem Grund“).

## 6. PostGIS-Anteil (Erfolgskriterium 7, Befund B-04)

### 6.1 Korrektheit

| Prüfung | Fälle | bestanden |
|---|---:|---:|
| Gebietsprüfung (innerhalb, außerhalb, Grenze, Ecke, knapp außerhalb, Wirksamkeit nach Zeit/Revision) | 20 | 20 |
| Haltepunktsuche (4 Varianten × 2 Mandanten × 2 Zeitpunkte, Entfernung metrisch ±1 m, Revision, Radius) | 16 | 16 |

Festlegungen des Spikes: Ein Punkt **auf** der Gebietsgrenze oder einer Ecke gehört zum Gebiet (`ST_Covers`, geometry); ein Punkt 0,000001 Grad außerhalb nicht. Wirksam ist eine Revision mit `valid_from <= t` und (`valid_to IS NULL` oder `valid_to > t`): Zum Wechselzeitpunkt gilt die neue Revision.
Die Haltepunktsuche liefert die Haltepunkte im Radius 500 m mit metrischer Entfernung über `geography` (Abweichung zur konstruierten Entfernung unter 1 m) und wählt je Zeitpunkt die wirksame Revision (ein Haltepunkt wird in Revision 2 von 100 m auf 300 m verschoben). Ein Haltepunkt in 600 m Entfernung wird nicht geliefert.

### 6.2 Befund B-04: der GiST-Index wird unter RLS für Bereichsprädikate nicht genutzt

Der Befundbericht vermutet, dass die GiST-Indizes ohne `tenant_id` erst nach dem Indexzugriff nach Mandant filtern. Die Messung zeigt etwas Gravierenderes: Unter RLS nutzt der Planer den GiST-Index **gar nicht**, wenn die Abfrage einen Bereichsoperator verwendet.
Grund: Die RLS-Bedingung `tenant_id = current_tenant()` ist eine Sicherheitsbarriere. Operatoren und Funktionen, die nicht `LEAKPROOF` sind, dürfen vor ihr nicht ausgewertet werden und damit auch nicht als Indexbedingung dienen. Die PostGIS-Operatoren sind nicht leakproof, `uuid_eq` und `int8eq` sind es.

| Funktion/Operator (Argumente) | leakproof |
|---|---|
| geometry_distance_centroid/2 | nein |
| geometry_overlaps/2 | nein |
| int8eq/2 | ja |
| st_covers/2 | nein |
| st_dwithin/3 | nein |
| st_dwithin/4 | nein |
| uuid_eq/2 | ja |

| Rolle | genutzte Indexe für `point && Box` | Ausführung ms |
|---|---|---:|
| Superuser (RLS nicht wirksam) | stop_geo | 1.27 |
| Runtime-Rolle (RLS wirksam) | keiner | 9.95 |

Der Ordnungsoperator `<->` (KNN) ist von dieser Einschränkung nicht betroffen, ebenso wenig Ganzzahlvergleiche. Das `btree_gist`-Gesamtindex `(tenant_id, geom)` ändert für Bereichsabfragen nichts (der `&&`-Teil bleibt nicht leakproof), verringert aber bei KNN die vom Mandantenfilter verworfenen Zeilen.

### 6.3 Pläne und Latenz

Abfragevarianten für die Haltepunktsuche (500 m, 5 Treffer): `naive_geography` (`ST_DWithin` auf dem `geography`-Cast), `bbox_prefilter` (`&&` mit `ST_Expand`, dann metrisch), `knn_index` (`ORDER BY point <-> q LIMIT 50`, dann metrisch), `grid_cell` (generierte Spalte `cell` mit btree `(tenant_id, cell)`, 3×3 Nachbarzellen von 0,01 Grad, dann metrisch).
Die Gebietsprüfung (`area`) nutzt `ST_Covers` auf den Polygonen des Mandanten.

| Index-Variante | Abfrage | genutzte Indexe | Zeilen | durch Filter verworfen | Ausführung ms |
|---|---|---|---:|---:|---:|
| Kernschema-Indexe (+ stop_cell) | area | service_area_pkey | 6 | 116 | 0.39 |
| Kernschema-Indexe (+ stop_cell) | stops_bbox_prefilter | stop_cell | 19 | 23987 | 15.9 |
| Kernschema-Indexe (+ stop_cell) | stops_grid_cell | stop_cell | 19 | 169 | 0.42 |
| Kernschema-Indexe (+ stop_cell) | stops_knn_index | stop_geo | 50 | 70 | 0.43 |
| Kernschema-Indexe (+ stop_cell) | stops_naive_geography | stop_cell | 19 | 23987 | 44.7 |
| zusätzlich btree_gist (tenant_id, geom) | area | service_area_geo_tenant | 6 | 116 | 0.59 |
| zusätzlich btree_gist (tenant_id, geom) | stops_bbox_prefilter | stop_cell | 19 | 23987 | 21.5 |
| zusätzlich btree_gist (tenant_id, geom) | stops_grid_cell | stop_cell | 19 | 169 | 0.35 |
| zusätzlich btree_gist (tenant_id, geom) | stops_knn_index | stop_geo_tenant | 50 | 10 | 0.30 |
| zusätzlich btree_gist (tenant_id, geom) | stops_naive_geography | stop_cell | 19 | 23987 | 65.0 |

Lesehilfe: Bei `naive_geography` und `bbox_prefilter` zeigt der Plan einen Indexzugriff, der nur `tenant_id` nutzt (Präfix von `stop_cell` oder Primärschlüssel); alle Zeilen des Mandanten werden gelesen und danach räumlich gefiltert (Spalte „durch Filter verworfen“). Bei `knn_index` und `grid_cell` wird der Index räumlich bzw. über die Zelle genutzt. Bei `area` mit dem `btree_gist`-Gesamtindex dient der Index ebenfalls nur über das Präfix `tenant_id`; `ST_Covers` steht im Filter. `stop_cell` ist die Messvariante für `grid_cell` und liegt in beiden Index-Varianten vor.

| Index-Variante | Abfrage | n einzeln | p50 ms | p95 ms | p99 ms | n parallel (100) | p50 ms | p95 ms | p99 ms | Ø Treffer | Fehler |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Kernschema-Indexe (+ stop_cell) | area | 1000 | 0.77 | 1.15 | 1.49 | 5000 | 20.3 | 63.2 | 126.2 | 3.14 | 0 |
| Kernschema-Indexe (+ stop_cell) | bbox_prefilter | 1000 | 19.4 | 28.1 | 32.2 | 5000 | 580.6 | 816.4 | 935.8 | 5.0 | 0 |
| Kernschema-Indexe (+ stop_cell) | grid_cell | 1000 | 1.01 | 1.42 | 1.76 | 5000 | 20.0 | 82.2 | 123.4 | 5.0 | 0 |
| Kernschema-Indexe (+ stop_cell) | knn_index | 1000 | 1.02 | 1.41 | 2.97 | 5000 | 21.1 | 79.6 | 143.1 | 5.0 | 0 |
| Kernschema-Indexe (+ stop_cell) | naive_geography | 1000 | 48.2 | 62.0 | 79.5 | 5000 | 1216.4 | 1512.0 | 1873.9 | 5.0 | 0 |
| zusätzlich btree_gist (tenant_id, geom) | area | 1000 | 0.62 | 1.06 | 1.54 | 5000 | 9.99 | 60.5 | 96.1 | 3.14 | 0 |
| zusätzlich btree_gist (tenant_id, geom) | bbox_prefilter | 1000 | 22.5 | 31.7 | 36.2 | 5000 | 519.2 | 735.5 | 824.2 | 5.0 | 0 |
| zusätzlich btree_gist (tenant_id, geom) | grid_cell | 1000 | 1.28 | 1.93 | 2.34 | 5000 | 16.0 | 86.2 | 171.1 | 5.0 | 0 |
| zusätzlich btree_gist (tenant_id, geom) | knn_index | 1000 | 0.97 | 1.58 | 2.42 | 5000 | 15.8 | 74.7 | 129.6 | 5.0 | 0 |
| zusätzlich btree_gist (tenant_id, geom) | naive_geography | 1000 | 46.4 | 65.2 | 73.3 | 5000 | 1260.0 | 1579.8 | 1739.0 | 5.0 | 0 |

Einzeln (eine Verbindung, 1.000 Abfragen an zufälligen Punkten) und unter 100 parallelen Abfragen (50 je Verbindung, Sättigung wie oben). Die beiden indexfähigen Varianten `knn_index` und `grid_cell` und die Gebietsprüfung liegen unter 100 parallelen Abfragen weit unter dem Ziel von 1 s; `bbox_prefilter` liegt knapp darunter
(p95 816.4 ms) und `naive_geography` darüber (p95 1512.0 ms). Beide skalieren mit der Zeilenzahl des Mandanten. Die Zeilenzahl (24.000 Haltepunktzeilen je Mandant) ist nur die Größenordnung eines Regionalnetzes; bei mehr Haltepunkten wachsen diese beiden Varianten linear.

## 7. Mandantentrennung (Erfolgskriterium 8)

Alle Prüfungen laufen unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS) mit transaktionslokalem Kontext.

| Prüfung (alle unter der Runtime-Rolle) | Ergebnis |
|---|---:|
| Hold von A auf eigenem Lauf, während B die Zeilensperre auf seinem Lauf hält: Ergebnis | HELD |
| … Dauer in ms (keine Wartezeit auf B) | 12.66 |
| A: SELECT … FOR UPDATE auf den Lauf von B: sichtbare/gesperrte Zeilen | 0 |
| … Dauer in ms (kein Warten) | 1.13 |
| A: Lauf von B per UUID ohne Mandantenbedingung: Zeilen | 0 |
| A: Hold auf dem Lauf von B | REJECTED:UNKNOWN_RUN |
| A sieht Holds auf dem Lauf von B (Zeilen) | 0 |
| verschiedene tenant_id in den für A sichtbaren Holds | 1 |
| für A sichtbare Buchungen anderer Mandanten (Zeilen) | 0 |
| für A sichtbare Buchungen auf dem Lauf von B (Zeilen) | 0 |
| ohne Mandantenkontext sichtbare Holds | 0 |
| Haltepunktzeilen fremder Mandanten für A sichtbar | 0 |
| verschiedene Mandanten in einer Geoabfrage von A ohne Mandantenbedingung | 1 |

Die Zeilensperre von B blockiert A nicht, und A kann Läufe, Holds, Buchungen und Haltepunkte von B weder sehen noch sperren. Zu beachten für Advisory Locks: Sie liegen in einem globalen Namensraum der Datenbank, RLS wirkt auf sie nicht. Bei einer Schlüsselkollision
zwischen Mandanten käme es zu unnötigem Warten, nicht zu Datenoffenlegung; der Schlüssel muss deshalb `tenant_id` einbeziehen (im Prototyp geschehen).

## 8. Struktur `capacity_hold` (Intervall und `capacity_vector`)

- **Intervall `interval_from/interval_to` (ganzzahlig, halb offen):** trägt. Die Belegung je Abschnitt entsteht durch Aggregation über die Holds des Laufs; mit Index `(tenant_id, run_id)` dauert die Abfrage einzeln ca. 1.26 ms (ohne Index 4.54 ms, Seq Scan) bei 60 Holds je Lauf und 30.000 Holds insgesamt.
  Ohne diesen Index fehlt dem Kernschema ein Pfad für die zentrale Abfrage: **Empfehlung: Index `capacity_hold (tenant_id, run_id)`** (mit `INCLUDE (state, interval_from, interval_to)`) und für den Expiryjob ein Teilindex `(tenant_id, expires_at) WHERE state = 'HELD'`.
- **`capacity_vector jsonb`:** funktioniert, kostet aber bei jeder Belegungsabfrage das Auslesen des jsonb. Dieselbe Abfrage mit generierten Integer-Spalten statt jsonb-Zugriff:

| Zugriff auf den Ressourcenvektor | n | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|
| jsonb | 400 | 1.07 | 1.47 | 3.93 |
| generated_int_columns | 400 | 0.78 | 0.91 | 3.07 |

  Der Unterschied ist messbar, aber klein. Empfehlung: jsonb für die offene Dimensionsmenge beibehalten und die Dimensionen, die jede Prüfung braucht (Sitz, Rollstuhlplatz), als generierte Spalten bereitstellen, falls die Abfrage zum Engpass wird.
- **Keine Datenbankgarantie für die Kapazität:** Weder `CHECK` noch Exclusion Constraint können „Summe der überlappenden Holds ≤ Kapazität“ ausdrücken. Die Korrektheit beruht auf der Sperrdisziplin (Abschnitt 9). Als zusätzliche Sicherung (defense in depth) ist eine Zählertabelle je Abschnitt mit `CHECK (belegt <= kapazitaet)`
  eine Option für M2; **nicht gemessen**.

## 9. Gewählte Sperrstrategie, Alternativen, Isolationsstufen

| Aspekt | `rc_run_row` (Empfehlung) | `rc_advisory` | `rr_touch` | `rr_run_row` / `rc_none` |
|---|---|---|---|---|
| Korrektheit PH-AT-004 | in allen Durchläufen | in allen Durchläufen | in allen Durchläufen | **verletzt** (Überbelegung) |
| Deadlockfrei bei stabiler Reihenfolge | ja | ja | ja (kein Warten auf Sperren) | – |
| Verhalten unter Hotspot | serialisiert je Lauf | disjunkte Abschnitte parallel | Serialisierungsfehler, Wiederholungen, endgültige Fehler | – |
| Disziplin erforderlich | jeder Schreiber des Laufs sperrt die Laufzeile (die Zeile ist zugleich der natürliche Konfliktpunkt für Neuplanung, `revision`) | jeder Schreiber muss dieselben Schlüssel nehmen; der Zusammenhang zu den Daten ist für die Datenbank nicht sichtbar, globaler Namensraum | Wiederholungslogik im Aufrufer | – |
| Eignung für Mehr-Lauf-Anfragen | ja, aufsteigende ID-Reihenfolge | ja, aufsteigende Schlüsselreihenfolge | ja | – |

**Isolationsstufen:** READ COMMITTED mit Zeilensperre ist korrekt und einfach. REPEATABLE READ ist nur mit optimistischem Konfliktpunkt (Schreiben der Laufzeile) korrekt, kostet Wiederholungen und kollabiert bei Hotspots; mit bloßer Sperre ist es falsch.

## 10. Empfehlung für M2

1. **Hold und Confirm als Datenbanktransaktionen mit `FOR UPDATE` auf `vehicle_run`, READ COMMITTED, Läufe in aufsteigender ID-Reihenfolge.** Eine gemeinsame Hilfsfunktion für alle Operationen, die Kapazität ändern (Hold, Confirm, Freigabe, Ablauf, Storno, Neuplanung); ein Architekturtest verbietet Kapazitätsänderungen am Sperrpfad vorbei.
2. **Ablauf nur mit `clock_timestamp()` nach dem Sperrerwerb prüfen**, im Confirm und im Expiryjob mit demselben Lock; abgelaufene Holds im Confirm als `EXPIRED` festschreiben.
3. **Indexe ergänzen** (`capacity_hold (tenant_id, run_id) INCLUDE (...)`, Expiry-Teilindex) in der M2-Migration.
4. **Geo-Abfragen unter RLS nicht auf `&&`/`ST_DWithin`/`ST_Covers` mit GiST stützen.** Für die Haltepunktsuche KNN (`<->`) mit Verfeinerung oder eine Gitterzellenspalte verwenden; Befund B-04 im Befundbericht von „offen (Messung)“ auf „bestätigt, größer als vermutet“ ändern. Für Gebiete (wenige Polygone je Mandant) genügt der Zugriff über den Primärschlüssel; bei vielen Polygonen dieselbe Zellenmethode.
5. **Latenzbudget:** Hold und Confirm liegen in der Messumgebung auch in der Sättigung unter p95 1 s und unterhalb der Sättigung (300 ms Denkzeit, mit Index) bei p95 64.8 ms (Hold) bzw. 62.3 ms (Confirm). Ob das Angebotsbudget von 5 s eingehalten wird, hängt zusätzlich von Solver (Spike b) und Partner-Fanout ab; das ist hier nicht gemessen.
6. **Wiederholung nach D-05:** Peaklast und Mandantenzahl sind offen; der Spike misst mit dem Referenzwert 100 parallele Transaktionen. Mit der Peaklast und einem dedizierten Datenbankserver (PostgreSQL 17) ist die Messung zu wiederholen.

## 11. Grenzen

- Gemessen wurde auf PostgreSQL 16.15 (nicht der Zielversion 17), 4 vCPUs, geteiltem Rechner; Absolutwerte sind nicht übertragbar, die Rangfolge der Strategien und die Korrektheitsaussagen sind es weitgehend.
- Der Lastgenerator ist Python; seine Eigenlast ist in der Basislinie angegeben, nicht herausgerechnet.
- Eine Anfrage entspricht einem Bein auf einem Lauf, Mehr-Lauf-Anfragen nur im Deadlockversuch; Safety- und Qualifikationsprüfung (M3), Hold-Verlängerung und der Planungsdefault 180 s (nur als Testwert) sind nicht Teil des Spikes.
- Die Geodaten sind synthetisch in einer Stellvertreterregion; die Zeilenzahlen sind eine Annahme.
- Nicht gemessen: Abschnittszeilen mit CHECK, Partitionierung, Failover, langlaufende Transaktionen, Vacuum-Verhalten über Tage.

## 12. Reproduktion

```
python tests/performance/spikes/spike_a/cluster.py start          # eigener Cluster, Port 5433, schreibt /tmp/taktovia-spike-pg/env.sh
source /tmp/taktovia-spike-pg/env.sh && export TAKTOVIA_DB_NAME=taktovia_spike_a
python tests/performance/spikes/spike_a/run_spike_a.py --seed 1    # ca. 40 Minuten; schreibt spike-a-data/results.json und die Rohdaten
python tests/performance/spikes/spike_a/build_report.py            # erzeugt diesen Bericht aus results.json
python tests/performance/spikes/spike_a/cluster.py destroy
```

Selbsttest des Prototyps (verkleinert, 30 statt 100 Parallelität, läuft auf dem Standardcluster): `python -m unittest test_spike_a` im Verzeichnis `tests/performance/spikes/spike_a/`.
Der Block E7 (Mandantentrennung) wurde nach einer Korrektur der Prüfabfrage einzeln mit `--only e7 --merge` wiederholt; die Wiederholung steht in `meta.reruns` der `results.json`.
