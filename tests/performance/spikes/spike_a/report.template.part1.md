# Spike (a) WP-M0-26: Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS

Stand 11.11.2026. Alle Zahlen in diesem Bericht stammen aus `spike-a-data/results.json` (Messlauf mit `run_spike_a.py`, Seed 1) und werden von `build_report.py` eingesetzt. Wo etwas nicht gemessen wurde, steht es ausdrücklich so da.
Der Prototyp ist ein Wegwerf-Prototyp und nicht Teil des Produktivcodes.

## 1. Ergebnis gegen die Erfolgskriterien

{{table:criteria}}

## 2. Messaufbau und Messprotokoll

{{table:meta}}

**Abweichungen vom Plan, ausdrücklich:**

- **Eigener PostgreSQL-Cluster statt des geteilten lokalen Clusters.** Der geteilte Cluster hat `max_connections=100` (davon 3 reserviert und weitere durch andere Läufe belegt); 100 wirklich gleichzeitig offene Transaktionen sind dort nicht möglich, und eine Umstellung hätte einen Neustart des geteilten Clusters verlangt. Der Spike lief deshalb auf einem eigenen Cluster (gleiche PostgreSQL-16-Binärdateien, gleiche PostGIS-Version, Standardeinstellungen außer Port und `max_connections=300`), angelegt mit `spike_a/cluster.py` und nach dem Lauf verworfen. Der Messaufbau auf PostgreSQL 17 (Zielversion, Versionsmatrix) steht aus.
- **Lastgenerator in Python.** 100 Threads in einem Python-Prozess verfälschen die Latenz durch die GIL stark (Basislinie `SELECT 1` mit 100 Threads: p50 etwa 48 ms in einem Vorlauf). Der Messlauf verteilt die 100 Verbindungen deshalb auf 4 Prozesse mit je 25 Threads; die verbleibende Eigenlast des Generators zeigt die Basislinie unten.
- **Geteilter Rechner.** Der Rechner hatte nur 4 virtuelle CPUs, die sich Datenbank, Lastgenerator und andere Arbeiten teilten (Load Average in der Tabelle). Alle Latenzen gelten für diese Umgebung und sind obere Schranken gegenüber einem dedizierten Datenbankserver.
- **Geschlossene Last ohne Denkzeit.** Die 100 Arbeiter senden ohne Pause. Das ist ein Sättigungstest: Die Latenz ist dann im Wesentlichen Wartezeit in der Warteschlange (Durchsatz × Parallelität). Ein Szenario mit 300 ms Denkzeit zeigt die Latenz unterhalb der Sättigung.
- **Datenmenge.** Die Basisdaten bilden einen Betriebstag nach der Referenzlast ab (250 Fahrzeuge je Mandant, 60 Buchungen je Fahrzeug, zusammen 30.000 Buchungen); die Geodaten liegen in einer synthetischen Stellvertreterregion (D-05 offen).

Basislinie des Lastgenerators (100 parallele `SELECT 1` mit Commit): p50 {{v:e0_overhead_select1.latency_ms.p50_ms}} ms, p95 {{v:e0_overhead_select1.latency_ms.p95_ms}} ms, p99 {{v:e0_overhead_select1.latency_ms.p99_ms}} ms (n = {{v:e0_overhead_select1.latency_ms.n}}). Diese Werte sind der Mindestanteil, den Generator und Rechner jeder Messung hinzufügen.

### Datenstand

{{table:data}}

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
