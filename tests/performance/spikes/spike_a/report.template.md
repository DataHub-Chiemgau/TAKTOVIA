# Spike (a) WP-M0-26: Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS

Stand 11.11.2026. Alle Zahlen in diesem Bericht stammen aus `spike-a-data/results.json` (Messlauf mit `run_spike_a.py`, Seed 1) und werden von `build_report.py` eingesetzt. Wo etwas nicht gemessen wurde, steht es ausdrücklich so da.
Der Prototyp ist ein Wegwerf-Prototyp und nicht Teil des Produktivcodes.

## 1. Ergebnis gegen die Erfolgskriterien

{{table:criteria}}

## 2. Messaufbau und Messprotokoll

{{table:meta}}

**Abweichungen vom Plan, ausdrücklich:**

- **Eigener PostgreSQL-Cluster statt des geteilten lokalen Clusters.** Der geteilte Cluster hat `max_connections=100` (davon 3 reserviert und weitere durch andere Läufe belegt); 100 wirklich gleichzeitig offene Transaktionen sind dort nicht möglich, und eine Umstellung hätte einen Neustart des geteilten Clusters verlangt. Der Spike lief deshalb auf einem eigenen Cluster (gleiche PostgreSQL-16-Binärdateien, gleiche PostGIS-Version, Standardeinstellungen außer Port und `max_connections=300`), angelegt mit `spike_a/cluster.py` und nach dem Lauf verworfen. Der Messaufbau auf PostgreSQL 17 (Zielversion, Versionsmatrix) steht aus.
- **Lastgenerator in Python.** 100 Threads in einem Python-Prozess verfälschen die Latenz durch die GIL stark (Basislinie `SELECT 1` mit 100 Threads: p50 etwa 48 ms in einem Entwicklungs-Vorlauf, nicht in den Rohdaten). Der Messlauf verteilt die 100 Verbindungen deshalb auf 4 Prozesse mit je 25 Threads; die verbleibende Eigenlast des Generators zeigt die Basislinie unten.
- **Geteilter Rechner.** Der Rechner hatte nur 4 virtuelle CPUs, die sich Datenbank, Lastgenerator und andere Arbeiten (Gradle-Builds anderer Aufgaben) teilten (Load Average in der Tabelle). Alle Latenzen gelten für diese Umgebung und sind obere Schranken gegenüber einem dedizierten Datenbankserver.
- **Geschlossene Last ohne Denkzeit.** Die 100 Arbeiter senden ohne Pause. Das ist ein Sättigungstest: Die Latenz ist dann im Wesentlichen Wartezeit in der Warteschlange (Durchsatz × Parallelität). Ein Szenario mit 300 ms Denkzeit zeigt die Latenz unterhalb der Sättigung.
- **Datenmenge.** Die Basisdaten bilden einen Betriebstag nach der Referenzlast ab (250 Fahrzeuge je Mandant, 60 Buchungen je Fahrzeug, zusammen 30.000 Buchungen); die Geodaten liegen in einer synthetischen Stellvertreterregion (D-05 offen).

Basislinie des Lastgenerators (100 parallele `SELECT 1` mit Commit): p50 {{v:e0_overhead_select1.latency_ms.p50_ms}} ms, p95 {{v:e0_overhead_select1.latency_ms.p95_ms}} ms, p99 {{v:e0_overhead_select1.latency_ms.p99_ms}} ms (n = {{v:e0_overhead_select1.latency_ms.n}}). Diese Werte sind der Mindestanteil, den Generator und Rechner jeder Messung hinzufügen.

### Datenstand

{{table:data}}

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

{{table:e1}}

Die empfohlene Strategie `rc_run_row` besteht alle {{v:e1_primary.iterations}} Durchläufe: in jedem genau eine Bestätigung (also auch keine unnötige Abweisung bei vorhandenem freien Platz), keine Überbelegung, keine negative Belegung, 0 Deadlocks, 0 Serialisierungsfehler.
Die Latenz der Spalten p50 bis p99 ist die Gesamtdauer von Hold plus gegebenenfalls Confirm je Anfrage im Worst Case: alle 100 Anfragen konkurrieren um **denselben** Lauf; sie sind durch die Sperre serialisiert, die Zeit ist im Wesentlichen Warteschlange.

Die Negativkontrollen belegen, dass der Test die Race Condition tatsächlich provoziert: **ohne Sperre** (`rc_none`) ist jeder der {{v:e1_variants.rc_none.iterations}} Durchläufe überbucht (im Mittel mehr als 23 Bestätigungen statt einer);
**REPEATABLE READ mit Zeilensperre** (`rr_run_row`) ist ebenso in jedem Durchlauf überbucht: Der Snapshot der Transaktion entsteht mit der ersten Anweisung, also vor dem Warten auf die Sperre, und die spätere Belegungsabfrage sieht
die inzwischen bestätigten Holds nicht. **Merksatz für M2:** Die Sperre allein genügt unter REPEATABLE READ nicht.

### 4.2 Varianten aus PH-AT-004

{{table:e2}}

Der freie Sitz ersetzt keinen fehlenden Rollstuhlplatz: In der gemischten Last (je Hälfte Rollstuhl- und Sitzanfragen) wird nie eine Rollstuhlanfrage bestätigt, und genau eine Sitzanfrage gewinnt. Bei verändertem `vehicle_run.revision` weist der Hold
alle Anfragen mit `REVISION_CONFLICT` ab (Neuplanung oder 409); wird die Revision zwischen Hold und Confirm verändert, weist der Confirm ab und die Hold-Zeilen bleiben unverändert (`HELD`, laufen ab oder werden freigegeben).

### 4.3 Wiederholte Bestätigung und Ablauf am Grenzzeitpunkt

{{table:e3}}

Beim Grenzrennen laufen je Durchlauf 20 Holds mit `expires_at` 5 bis 45 ms nach dem Anlegen; Confirm-Aufrufe (zufällig 0 bis 50 ms verzögert) und ein Expiryjob (4 Läufe) kommen gleichzeitig. Es ergeben sich beide Ausgänge (bestätigt und abgelaufen), und in keinem Fall
„abgelaufen und gebucht“, „bestätigt ohne Buchung“ oder „weder noch“. Entscheidend ist, dass Confirm und Expiryjob denselben Lock verwenden und der Confirm die Ablaufzeit **nach** dem Sperrerwerb mit `clock_timestamp()` prüft.
Ein Hold mit `expires_at` gleich dem Einfügezeitpunkt (Grenzzeitpunkt oder bereits überschritten) ist beim Confirm immer abgelaufen (`now >= expires_at`), der Ablauf wird festgeschrieben.

### 4.4 Deadlocks bei gegenläufiger Sperrreihenfolge

Je Durchlauf 100 Anfragen, die je zwei Läufe belegen; die Hälfte nennt die Läufe in umgekehrter Reihenfolge (bei `advisory` die Abschnitte).

{{table:e4}}

Ohne stabile Reihenfolge (`*_naive`) enden fast alle Anfragen im Deadlock (Zähler des Clients; der Server zählt dieselbe Zahl, Tabelle unten), und jeder Deadlock kostet `deadlock_timeout` (1 s), bei `rc_run_row_naive` dauert ein Durchlauf entsprechend viele Sekunden (Spalte „Dauer je Durchlauf“).
Mit stabiler Reihenfolge (aufsteigend nach Lauf-ID bzw. Schlüssel) entsteht kein einziger Deadlock. Die Sperrreihenfolge ist also **keine Optimierung, sondern Voraussetzung** und gehört in einen Architekturtest (jede Fachoperation sperrt über dieselbe Hilfsfunktion).

{{table:deadlocks_server}}

Die Deadlocks stammen vollständig aus der Gegenprobe (Block `e4`); in allen anderen Blöcken, auch bei der empfohlenen Strategie, zählt der Server 0.

## 5. Latenz unter 100 parallelen Transaktionen (Erfolgskriterium 5)

Arbeitslast je Arbeiter: Hold, bei Erfolg Confirm, bei Erfolg Storno (Kapazität wird frei, die Belegung bleibt im Mittel stabil); {{v:e5.with_hold_indexes.spread.cycles_per_worker}} Zyklen je Arbeiter, zwei Mandanten abwechselnd.
Szenarien: `spread` (Läufe zufällig über alle 250 je Mandant, Normalfall), `hot5` (nur 5 Läufe je Mandant, starke Konkurrenz), `single` (ein Lauf je Mandant: Worst Case, 50 Arbeiter je Lauf), `spread_paced300ms` (wie `spread` mit 300 ms Denkzeit, unterhalb der Sättigung).
Zwei Indexvarianten: nur die Indexe des Kernschemas (`capacity_hold` hat keinen Index auf `run_id`) und mit zusätzlichem Index `(tenant_id, run_id) INCLUDE (state, interval_from, interval_to)`.

{{table:e5plan}}

{{table:e5}}

Lesehilfe: Die Dauer enthält die Wartezeit auf die Zeilensperre (Spalte Sperrwartezeit) und die Eigenlast des Generators (Basislinie in Abschnitt 2). Ohne Denkzeit gilt Durchsatz ≈ 100 / mittlere Latenz: Die Zahlen beschreiben die **Sättigung** der 4-CPU-Umgebung, nicht die Latenz im Normalbetrieb. Das Szenario mit Denkzeit zeigt die Latenz unterhalb der Sättigung.

Ergebnis: Der höchste p95 von Hold und Confirm über alle Szenarien und beide Indexvarianten liegt unter dem Ziel von 1 s („Standard-API p95 ≤ 1 s“, vorläufiges Lastenheftziel). Einzelne Maxima liegen darüber (z. B. `hot5` mit Index: max {{v:e5.with_hold_indexes.hot5.per_op.hold.total_ms.max_ms}} ms),
das Ziel bezieht sich auf p95. Der fehlende Index auf `capacity_hold.run_id` kostet Durchsatz und Latenz (Szenario `spread`: {{v:e5.baseline_kernschema_indexes.spread.throughput_tx_per_s}} gegen {{v:e5.with_hold_indexes.spread.throughput_tx_per_s}} Transaktionen je Sekunde; p95 Hold {{v:e5.baseline_kernschema_indexes.spread.per_op.hold.total_ms.p95_ms}} ms gegen {{v:e5.with_hold_indexes.spread.per_op.hold.total_ms.p95_ms}} ms).

### Alternative Sperrstrategien unter gleicher Last (mit Index)

{{table:e5alt}}

`rc_advisory` ist unter `hot5` schneller als `rc_run_row`, weil Anfragen auf disjunkten Abschnitten desselben Laufs parallel laufen; unter `spread` liegt es leicht hinter der Zeilensperre. `rr_touch` (REPEATABLE READ mit optimistischem Anfassen der Laufzeile) ist unter Streuung noch brauchbar, **kollabiert** aber bei
Konkurrenz auf wenigen Läufen: Viele Transaktionen scheitern nach allen Wiederholungen endgültig mit Serialisierungsfehler (Spalte „Serialisierungsfehler“), das verletzt Kriterium 4 („jede Transaktion endet mit Erfolg oder fachlichem Grund“).

## 6. PostGIS-Anteil (Erfolgskriterium 7, Befund B-04)

### 6.1 Korrektheit

{{table:geo_correct}}

Festlegungen des Spikes: Ein Punkt **auf** der Gebietsgrenze oder einer Ecke gehört zum Gebiet (`ST_Covers`, geometry); ein Punkt 0,000001 Grad außerhalb nicht. Wirksam ist eine Revision mit `valid_from <= t` und (`valid_to IS NULL` oder `valid_to > t`): Zum Wechselzeitpunkt gilt die neue Revision.
Die Haltepunktsuche liefert die Haltepunkte im Radius 500 m mit metrischer Entfernung über `geography` (Abweichung zur konstruierten Entfernung unter 1 m) und wählt je Zeitpunkt die wirksame Revision (ein Haltepunkt wird in Revision 2 von 100 m auf 300 m verschoben). Ein Haltepunkt in 600 m Entfernung wird nicht geliefert.

### 6.2 Befund B-04: der GiST-Index wird unter RLS für Bereichsprädikate nicht genutzt

Der Befundbericht vermutet, dass die GiST-Indizes ohne `tenant_id` erst nach dem Indexzugriff nach Mandant filtern. Die Messung zeigt etwas Gravierenderes: Unter RLS nutzt der Planer den GiST-Index **gar nicht**, wenn die Abfrage einen Bereichsoperator verwendet.
Grund: Die RLS-Bedingung `tenant_id = current_tenant()` ist eine Sicherheitsbarriere. Operatoren und Funktionen, die nicht `LEAKPROOF` sind, dürfen vor ihr nicht ausgewertet werden und damit auch nicht als Indexbedingung dienen. Die PostGIS-Operatoren sind nicht leakproof, `uuid_eq` und `int8eq` sind es.

{{table:rls}}

Der Ordnungsoperator `<->` (KNN) ist von dieser Einschränkung nicht betroffen, ebenso wenig Ganzzahlvergleiche. Das `btree_gist`-Gesamtindex `(tenant_id, geom)` ändert für Bereichsabfragen nichts (der `&&`-Teil bleibt nicht leakproof), verringert aber bei KNN die vom Mandantenfilter verworfenen Zeilen.

### 6.3 Pläne und Latenz

Abfragevarianten für die Haltepunktsuche (500 m, 5 Treffer): `naive_geography` (`ST_DWithin` auf dem `geography`-Cast), `bbox_prefilter` (`&&` mit `ST_Expand`, dann metrisch), `knn_index` (`ORDER BY point <-> q LIMIT 50`, dann metrisch), `grid_cell` (generierte Spalte `cell` mit btree `(tenant_id, cell)`, 3×3 Nachbarzellen von 0,01 Grad, dann metrisch).
Die Gebietsprüfung (`area`) nutzt `ST_Covers` auf den Polygonen des Mandanten.

{{table:geo_plans}}

Lesehilfe: Bei `naive_geography` und `bbox_prefilter` zeigt der Plan einen Indexzugriff, der nur `tenant_id` nutzt (Präfix von `stop_cell` oder Primärschlüssel); alle Zeilen des Mandanten werden gelesen und danach räumlich gefiltert (Spalte „durch Filter verworfen“). Bei `knn_index` und `grid_cell` wird der Index räumlich bzw. über die Zelle genutzt. Bei `area` mit dem `btree_gist`-Gesamtindex dient der Index ebenfalls nur über das Präfix `tenant_id`; `ST_Covers` steht im Filter. `stop_cell` ist die Messvariante für `grid_cell` und liegt in beiden Index-Varianten vor.

{{table:geo_lat}}

Einzeln (eine Verbindung, 1.000 Abfragen an zufälligen Punkten) und unter 100 parallelen Abfragen (50 je Verbindung, Sättigung wie oben). Die beiden indexfähigen Varianten `knn_index` und `grid_cell` und die Gebietsprüfung liegen unter 100 parallelen Abfragen weit unter dem Ziel von 1 s; `bbox_prefilter` liegt knapp darunter
(p95 {{v:e6_postgis.latency_baseline.bbox_prefilter.parallel.p95_ms}} ms) und `naive_geography` darüber (p95 {{v:e6_postgis.latency_baseline.naive_geography.parallel.p95_ms}} ms). Beide skalieren mit der Zeilenzahl des Mandanten. Die Zeilenzahl (24.000 Haltepunktzeilen je Mandant) ist nur die Größenordnung eines Regionalnetzes; bei mehr Haltepunkten wachsen diese beiden Varianten linear.

## 7. Mandantentrennung (Erfolgskriterium 8)

Alle Prüfungen laufen unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS) mit transaktionslokalem Kontext.

{{table:iso}}

Die Zeilensperre von B blockiert A nicht, und A kann Läufe, Holds, Buchungen und Haltepunkte von B weder sehen noch sperren. Zu beachten für Advisory Locks: Sie liegen in einem globalen Namensraum der Datenbank, RLS wirkt auf sie nicht. Bei einer Schlüsselkollision
zwischen Mandanten käme es zu unnötigem Warten, nicht zu Datenoffenlegung; der Schlüssel muss deshalb `tenant_id` einbeziehen (im Prototyp geschehen).

## 8. Struktur `capacity_hold` (Intervall und `capacity_vector`)

- **Intervall `interval_from/interval_to` (ganzzahlig, halb offen):** trägt. Die Belegung je Abschnitt entsteht durch Aggregation über die Holds des Laufs; mit Index `(tenant_id, run_id)` dauert die Abfrage einzeln ca. {{v:e5.with_hold_indexes.occupancy_plan_run_A.execution_ms}} ms (ohne Index {{v:e5.baseline_kernschema_indexes.occupancy_plan_run_A.execution_ms}} ms, Seq Scan) bei 60 Holds je Lauf und 30.000 Holds insgesamt.
  Ohne diesen Index fehlt dem Kernschema ein Pfad für die zentrale Abfrage: **Empfehlung: Index `capacity_hold (tenant_id, run_id)`** (mit `INCLUDE (state, interval_from, interval_to)`) und für den Expiryjob ein Teilindex `(tenant_id, expires_at) WHERE state = 'HELD'`.
- **`capacity_vector jsonb`:** funktioniert, kostet aber bei jeder Belegungsabfrage das Auslesen des jsonb. Dieselbe Abfrage mit generierten Integer-Spalten statt jsonb-Zugriff:

{{table:jsonb}}

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
5. **Latenzbudget:** Hold und Confirm liegen in der Messumgebung auch in der Sättigung unter p95 1 s und unterhalb der Sättigung (300 ms Denkzeit, mit Index) bei p95 {{v:e5.with_hold_indexes.spread_paced300ms.per_op.hold.total_ms.p95_ms}} ms (Hold) bzw. {{v:e5.with_hold_indexes.spread_paced300ms.per_op.confirm.total_ms.p95_ms}} ms (Confirm). Ob das Angebotsbudget von 5 s eingehalten wird, hängt zusätzlich von Solver (Spike b) und Partner-Fanout ab; das ist hier nicht gemessen.
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
