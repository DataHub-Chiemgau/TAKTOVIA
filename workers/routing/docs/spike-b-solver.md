# Spike (b): Solverlaufzeit der Einfügeheuristik (WP-M0-27)

Stand 11.11.2026. Alle Zahlen stammen aus tatsächlich ausgeführten Läufen von `taktovia_routing.spike.bench` und
`taktovia_routing.spike.ortools_compare`; Rohdaten: [spike-b-messwerte.json](spike-b-messwerte.json) und
[spike-b-ortools.json](spike-b-ortools.json). Das Schema des Prototyps steht in [spike-b-schema.json](spike-b-schema.json).
Dies ist ein **Spike**, kein Produktivcode.

## Ergebnis in Kürze

- **Das 1-s-Budget für die lokale Kandidatensuche (Kap. 01 §1.4) wird auf allen gemessenen Instanzen eingehalten, mit sehr großem Abstand.**
  Referenzinstanz (250 Fahrzeuge, 1000 offene Aufträge): p50 1,23 ms, p95 1,64 ms, max 2,04 ms.
  Schlechtester gemessener Einzelwert über alle Instanzen: **28,6 ms** (2000 Fahrzeuge, 8000 Aufträge); größtes p95: 20,4 ms.
- **Zulässigkeit:** In allen gemessenen Läufen findet der unabhängige Prüfer **null** Verletzungen harter Constraints
  (siehe „Zulässigkeit").
- **Wichtige Einschränkung:** Die Fahrzeitmatrix lag **im Speicher** (synthetisch, 1200 x 1200). Die Latenz eines echten
  Routingdienstes (Valhalla) ist **nicht gemessen**; das misst WP-M0-30. Die Aussage „p95 <= 1 s einschließlich
  Matrixzugriff" gilt daher nur für Zugriff auf eine vorgehaltene Matrix.
- **OR-Tools** ist für die 1-s-Kandidatensuche in dieser Konfiguration **nicht geeignet**: Die Neuplanung der ganzen Flotte fügte die
  neue Anfrage innerhalb von 1 s bzw. 10 s in 0 von 24 Läufen ein und überschreitet das Zeitlimit teils deutlich (bis 6,6 s).
- Die Annahmen sind synthetisch (D-05 und D-09 offen). Belastbar wird das Ergebnis erst mit Pilotdaten und echter Matrix.

## Hardware und Umgebung

| | |
|---|---|
| CPU | Intel(R) Xeon(R) Processor @ 2.10GHz (`/proc/cpuinfo`), `nproc` = 4 |
| Arbeitsspeicher | 16 GB (`free -m`: 16086 MB) |
| System | Linux 6.18.44 (virtuelle Maschine), Python 3.13.18 (CPython), OR-Tools 9.15.6755, NumPy 2.5.4 |
| Ausführung | ein Prozess, ein Thread je Messung (CPython, GIL); Parallelitätstest mit Prozessen |
| Seed | 20261111 (Matrix, Flotte), Zustandsstrom Seed+1, Abfragestrom Seed+2 |

Die Maschine ist eine geteilte virtuelle Umgebung, keine Zielhardware. Die Laufzeiten schwanken von Lauf zu Lauf: Zwei
vollständige Läufe derselben Messung ergaben z. B. für 250 Fahrzeuge/4000 Aufträge (Profil „tag") p95 6,9 ms bzw. 8,4 ms. Die Plan-Ergebnisse
(Anzahl angenommener Aufträge je Instanz) waren in beiden Läufen identisch.

## Messaufbau

**Instanzgenerator** (`spike/generator.py`, nur `random.Random(seed)`): Gebiet 24 x 24 km, 1200 Haltepunkte (60 % in drei Clustern),
Fahrzeit = 1,3 x Luftlinie bei 8 m/s, mindestens 30 s, symmetrisch. Flotte 250 Fahrzeuge: 70 % Typ A (6 Sitze, 0 Rollstuhlplätze),
30 % Typ B (4 Sitze, 2 Rollstuhlplätze); Schicht 8 oder 10 h, Beginn zufällig in den ersten 4 h; je Fahrzeug eine 30-min-Pause im
Fenster 3 h bis 5,5 h nach Schichtbeginn. Anfragen: 8 % Rollstuhl (1 Rollstuhlplatz, 30 % mit Begleitsitz), sonst 1 bis 3 Sitze;
Fahrzeit 5 bis 40 min; Pickup-Fenster je Profil; maximale Fahrzeit an Bord = 1,5 x direkt + 5 min; Servicezeit 60 s je Halt.

| Profil | Pickup-Zeiten | Fensterbreite | Zweck |
|---|---|---|---|
| `tag` | 0:10 bis 9:00 h gleichverteilt | 15 min | Tagesverteilung (Referenz) |
| `spitze` | 2:00 bis 4:00 h | 20 min | Spitzenfenster, dichte Bündelung |
| `weit` | 1:00 bis 5:00 h | 60 min | Stresslast: breite Fenster, viele Kombinationen |

**Referenzinstanz (Annahme, da D-05 offen):** 250 Fahrzeuge, Profil `tag`, 1000 offene Aufträge (4 je Fahrzeug und Tag). Die Aufträge
werden mit derselben Heuristik sequentiell eingefügt (Ausgangszustand); gemessen werden 200 neue Anfragen je Zustand
(zusätzlich 20 Warmlauf-Anfragen), jede **einzeln gegen den unveränderten Zustand**, ohne Übernahme.

**Messgröße:** Wanduhrzeit (`time.perf_counter`) von `insert_request`: Kandidatensuche über alle Fahrzeuge, Matrixzugriff (Speicher),
Aufbau des Plans und Validierung durch den unabhängigen Prüfer. Nicht enthalten: Serialisierung, Transport zum Worker,
Laden des Flottenzustands, Matrixabruf von einem Dienst.

**Heuristik** (`spike/heuristic.py`): Für jedes Fahrzeug werden alle Positionen (Pickup, Dropoff) geprüft, aber mit Vorlauf/Nachlauf-Simulation
und Abbruch bei Verletzung statt Vollauswertung. Harte Constraints: Zeitfenster, Ressourcenvektor (Sitze und Rollstuhlplätze als getrennte
Dimensionen), maximale Fahrzeit an Bord, Pause (im Fenster, nie mit Fahrgästen an Bord), Schichtende. Bestehende Zusagen bleiben in
Reihenfolge, nur Zeiten rutschen innerhalb ihrer Fenster. Ziel: minimaler Zuwachs der Fahrzeit (Leerweg/Fahrzeit). Geliefert wird nur ein
Plan, den der unabhängige Prüfer (`spike/validator.py`, importiert die Heuristik nicht) freigegeben hat. Die beschnittene Suche liefert dasselbe
Optimum wie vollständige Enumeration (Test `test_heuristic_matches_exhaustive_search`, 3 Profile x 25 Anfragen gegen Vollsuche).

## Laufzeit je Instanzgröße (250 Fahrzeuge)

Zeiten in ms je Anfrage; „angen." = angenommene Aufträge im Ausgangszustand (der Rest wurde vom Generator-Zustand abgelehnt);
„Abfr. ok" = von 200 Anfragen mit zulässiger Einfügung. Zulässigkeit: Verletzungen in gelieferten Plänen + im Zustand.

| Profil | Aufträge | angen. | Halte/Route (Ø / max) | Abfr. ok | p50 | p95 | p99 | max | Verletzungen |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|
| tag | 250 | 250 | 3,0 / 11 | 200 | 0,80 | 1,17 | 1,24 | 1,26 | 0 |
| tag | 500 | 500 | 5,0 / 15 | 199 | 1,20 | 1,71 | 1,94 | 2,88 | 0 |
| **tag (Referenz)** | **1000** | 999 | 9,0 / 29 | 190 | **1,23** | **1,64** | 1,73 | 2,04 | 0 |
| tag | 2000 | 1940 | 16,5 / 49 | 167 | 3,38 | 5,91 | 6,72 | 7,12 | 0 |
| tag | 4000 | 3533 | 29,3 / 53 | 132 | 3,77 | 8,37 | 9,55 | 9,86 | 0 |
| tag | 8000 | 4628 | 38,0 / 61 | 24 | 4,35 | 7,39 | 7,88 | 7,98 | 0 |
| spitze | 250 | 250 | 3,0 / 13 | 200 | 0,57 | 0,87 | 1,01 | 1,22 | 0 |
| spitze | 500 | 500 | 5,0 / 17 | 200 | 0,94 | 1,47 | 1,76 | 2,04 | 0 |
| spitze | 1000 | 1000 | 9,0 / 21 | 200 | 1,59 | 2,58 | 2,88 | 4,32 | 0 |
| spitze | 2000 | 1547 | 13,4 / 25 | 52 | 2,04 | 3,13 | 3,91 | 4,25 | 0 |
| spitze | 4000 | 1794 | 15,4 / 27 | 14 | 2,03 | 3,16 | 4,12 | 4,32 | 0 |
| weit | 250 | 250 | 3,0 / 17 | 200 | 0,78 | 0,98 | 1,01 | 1,06 | 0 |
| weit | 500 | 500 | 5,0 / 21 | 200 | 1,12 | 1,99 | 2,42 | 2,53 | 0 |
| weit | 1000 | 1000 | 9,0 / 25 | 200 | 2,06 | 3,26 | 4,43 | 4,62 | 0 |
| weit | 2000 | 2000 | 17,0 / 33 | 200 | 3,44 | 4,86 | 5,06 | 8,96 | 0 |
| weit | 4000 | 3009 | 25,1 / 41 | 47 | 3,99 | 8,08 | 9,36 | 9,71 | 0 |
| weit | 8000 | 3358 | 27,9 / 41 | 8 | 4,28 | 8,01 | 9,29 | 10,36 | 0 |

**Lesehinweis Sättigung:** Ab etwa 2000 bis 4000 Aufträgen ist die Flotte in diesen Profilen ausgelastet; die Zahl zulässiger Einfügungen
fällt (letzte Spalten „Abfr. ok"), und die Suche endet früher, weil Zeitfenster Kandidaten abschneiden. Deshalb steigen p95/max ab dort nicht
weiter. Die für die Laufzeit aussagekräftigen Zeilen sind die, in denen die meisten Anfragen noch Kandidaten finden.

**Skalierung mit der Flottengröße** (Profil `weit`, 4 Aufträge je Fahrzeug, 200 Abfragen, alle Aufträge angenommen, 0 Verletzungen):

| Fahrzeuge | Aufträge | p50 | p95 | max |
|---:|---:|---:|---:|---:|
| 250 | 1000 | 2,06 | 3,26 | 4,62 |
| 500 | 2000 | 3,53 | 5,74 | 7,14 |
| 1000 | 4000 | 8,02 | 12,75 | 16,58 |
| 2000 | 8000 | 13,39 | 20,42 | 28,60 |

Die Laufzeit wächst etwa linear mit der Flottengröße. **Eine Hochrechnung bis 1 s ist nicht gemessen** und wäre für die weit
größere Flotte nur eine Extrapolation.

### AK 4: größte Instanz, die das Budget hält, und erste, die es reißt

Gemessen wurde keine Instanz, die das 1-s-Budget reißt. Die größten gemessenen Instanzen halten es: 250 Fahrzeuge/8000 Aufträge
(angenommen 4628; p95 7,4 ms, max 8,0 ms) und 2000 Fahrzeuge/8000 Aufträge (p95 20,4 ms, max 28,6 ms). Die „erste Instanz, die das Budget
reißt" ist damit **in dieser Messung nicht gefunden**; sie liegt oberhalb der getesteten Größen (Faktor von mindestens ca. 35 zwischen
größtem gemessenem Maximum und Budget).

## Anteil Matrixzugriff vs. Solver

Die Matrix liegt im Speicher (`array('i')` je Zeile, 1200 x 1200 x 4 Byte = 5,76 MB). Gemessen wurden (in einem separaten, nicht gewerteten
Lauf über 40 Anfragen) die Zahl der Matrixzugriffe und der eindeutig benötigten Knotenpaare je Anfrage; die Kosten eines Zugriffs kamen
aus einem Mikro-Benchmark (52 ns je `T[a][b]` in diesem Lauf, 95 ns in einem früheren Lauf, also unsicher).

| Instanz | Zugriffe/Anfrage (Ø) | eindeutige Knotenpaare/Anfrage (Ø / max) | geschätzter Matrixanteil |
|---|---:|---:|---:|
| 250 Fz., tag, 1000 | 5958 | 2324 / 2917 | 27 % |
| 250 Fz., weit, 2000 | 9895 | 3160 / 4091 | 15 % |
| 2000 Fz., weit, 8000 | 42 481 | 5255 / 6540 | 17 % |

Der **Matrixanteil ist eine Schätzung** (Zugriffe x Mikro-Benchmark / mittlere Laufzeit), keine direkte Messung im Lauf, und gilt nur für
den In-Memory-Zugriff. Für WP-M0-30 relevant: Pro Anfrage werden einige tausend verschiedene Fahrzeitpaare gebraucht (Zeilen/Spalten von
Pickup und Dropoff zu allen Haltepunkten in den Fahrzeugplänen); **ein bei jeder Anfrage frisch bei Valhalla geholter Abruf dieser Größe ist
nicht gemessen** und könnte das Budget dominieren.

## Zulässigkeit (AK 2)

- Jeder von der Heuristik gelieferte Plan wird vor der Auslieferung vom unabhängigen Prüfer freigegeben und im Messlauf **ein zweites Mal**
  nachgeprüft (`check_result`: Zeitfenster, Fahrzeit, Kapazität je Dimension, Pickup vor Dropoff, maximale Fahrzeit, Pause ohne Fahrgäste und im
  Fenster, Schichtende, bestehende Stops unverändert enthalten). Zusätzlich wurde jeder Fahrzeugplan des Ausgangszustands geprüft.
- Ergebnis: 17 Instanzen x 200 Anfragen und 3 Flottengrößen x 200 Anfragen, dazu 400 Anfragen im Timeout-Test und jeweils der gesamte
  Ausgangszustand: **0 Verletzungen**.
- Einschränkung: Der Prüfer stammt aus derselben Entwicklung wie die Heuristik und prüft nur die hier modellierten Constraints. Er ist
  durch Negativtests abgesichert (`test_validator_detects_violations`), aber kein unabhängiges Drittprodukt. Nicht modelliert und daher nicht
  geprüft: sichere Fahrzeiten (Lenz-/Ruhezeiten), Lade-/Reichweitenreserven, Umrüstzustände, Fahrzeugwechsel (siehe „Nicht im Umfang").

## Verhalten bei Timeout (AK 3)

`insert_request(budget_s=...)` prüft die Frist je Fahrzeug. Bei Ablauf wird nur ein bereits gefundener, **vollständig validierter** Plan
geliefert, sonst die Ablehnung `TIMEOUT_NO_VALIDATED_PLAN`. Messung auf 100 Anfragen (250 Fahrzeuge, `weit`, 2000 Aufträge, alle angenommen);
„unbegrenzt" = 100 zulässige Einfügungen:

| Budget | angenommen | Ablehnung wegen Timeout | Verletzungen | angenommen, aber schlechter als unbegrenzt | Ø Mehr-Fahrzeit dieser Pläne | größte Überschreitung des Budgets |
|---:|---:|---:|---:|---:|---:|---:|
| 0,02 ms | 56 | 44 | 0 | 53 | 3839 s | 0,06 ms |
| 0,1 ms | 73 | 27 | 0 | 68 | 2924 s | 0,09 ms |
| 0,5 ms | 99 | 1 | 0 | 77 | 1274 s | 0,09 ms |
| 1,0 ms | 100 | 0 | 0 | 57 | 899 s | 0,11 ms |

Es gab nie einen ungeprüften Plan und keine andere Ablehnungsursache. Die Überschreitung ist durch die Prüfgranularität (ein Fahrzeug plus
Validierung) beschränkt. **Qualität bei Abbruch:** Die Fahrzeuge werden in fester Reihenfolge abgesucht; ein früh abgebrochener Lauf liefert
das beste bisher gefundene, nicht das beste Ergebnis. Die Qualität hängt also von der Scanreihenfolge ab; eine Vorsortierung nach Nähe
wäre ein Produktivthema. Bei unbegrenzter Zeit ist die Heuristik innerhalb ihrer Nachbarschaft (genau eine Einfügung) optimal und wird
durch mehr Rechenzeit nicht besser.

## Parallele Last (Test „konkurrierend")

k Prozesse (fork, gemeinsamer Ausgangszustand, gleichzeitiger Start über Barriere) rechnen je dieselben 100 Anfragen (250 Fahrzeuge, `weit`,
2000 Aufträge, 4 vCPU):

| gleichzeitige Prozesse | Anfragen | p50 | p95 | max |
|---:|---:|---:|---:|---:|
| 1 | 100 | 3,11 | 5,24 | 8,04 |
| 2 | 200 | 3,04 | 4,58 | 6,76 |
| 4 | 400 | 3,21 | 5,62 | 6,96 |
| 8 | 800 | 7,33 | 13,41 | 21,25 |

Bis zur Kernzahl (4) bleibt die Latenz unverändert, bei 8 Prozessen auf 4 Kernen (Überbuchung) etwa verdoppelt. Das Budget bleibt in allen Fällen
mit großem Abstand gewahrt. Ein Thread-Test (`test_concurrent_queries_on_same_fleet_are_consistent`) prüft, dass gleichzeitige Abfragen
denselben Zustand nicht verändern und gleiche Ergebnisse wie sequentiell liefern. **Kapazitätskorrektheit bei konkurrierenden Buchungen
ist ausdrücklich nicht Gegenstand** (Hold in PostgreSQL, WP-M0-26); die Heuristik liefert Angebote auf einer Planrevision.

## Qualität: Vergleich mit OR-Tools

Modell (`spike/ortools_compare.py`): `RoutingModel` mit Zeitdimension, Sitz- und Rollstuhldimension, Pickup/Dropoff-Paaren, maximaler Fahrzeit,
Schichtende; bestehende Aufträge Pflicht, neue Anfrage über Disjunktion optional; Startlösung = bestehende Routen. **Pausen sind im OR-Tools-Modell
nicht abgebildet**; der Vergleich läuft deshalb auf Instanzen ohne Pause (Profil `weit`, Ausgangszustand mit derselben Heuristik gebaut).
Metaheuristik Guided Local Search (in einem kurzen Vorversuch lieferten `AUTOMATIC` und `GREEDY_DESCENT` dasselbe Ergebnis). Zeiten Wanduhr, 1 Thread.

| Variante | Aufträge | Anfragen | Modellaufbau p50 | Gesamt p50 / max | OR-Tools fügt neue Anfrage ein | Heuristik nimmt an | Verletzungen (Prüfer) |
|---|---:|---:|---:|---:|---:|---:|---:|
| ganze Flotte, 1 s | 250 | 8 | 0,09 s | 1,13 / 1,15 s | **0 / 8** | 8 / 8 | 0 |
| ganze Flotte, 1 s | 500 | 8 | 0,25 s | 1,36 / 1,50 s | **0 / 8** | 8 / 8 | 0 |
| ganze Flotte, 1 s | 1000 | 5 | 0,74 s | 6,35 / 6,60 s | **0 / 5** | 5 / 5 | 0 |
| ganze Flotte, 10 s | 250 | 3 | 0,09 s | 10,11 / 10,13 s | **0 / 3** | 3 / 3 | 0 |
| 10 nächste Fahrzeuge, 1 s | 250 | 8 | 0,004 s | 1,00 / 1,01 s | 8 / 8 | 8 / 8 | 0 |
| 10 nächste Fahrzeuge, 1 s | 1000 | 8 | 0,007 s | 1,01 / 1,02 s | 8 / 8 | 8 / 8 | 0 |
| 10 nächste Fahrzeuge, 10 s | 1000 | 3 | 0,007 s | 10,01 / 10,01 s | 3 / 3 | 3 / 3 | 0 |
| 10 nächste Fahrzeuge, 1 s | 4000 | 8 | 0,017 s | 1,02 / 1,02 s | 0 / 8 | 3 / 8 | 0 |

Befunde:
- **Ganze Flotte:** In dieser Konfiguration fügt OR-Tools die neue Anfrage auch in 10 s nicht ein, sondern verbessert stattdessen bestehende
  Routen (Gesamtfahrzeit sinkt um 342 bis 16 460 s). Das ist ein Befund dieser Konfiguration (Startlösung, Guided Local Search, ein Thread),
  keine allgemeine Aussage über OR-Tools; andere Strategien (z. B. gezielte Einfügeoperatoren, Teilmodelle) wurden nicht untersucht.
  Bei 1000 Aufträgen überschritt der Lauf das 1-s-Limit auf 5,6 s Lösungszeit (Zeitlimit wird nicht hart eingehalten).
- **Teilmodell mit 10 nächsten Fahrzeugen:** Es braucht immer die volle Zeitgrenze (Suche läuft bis zum Limit), die Heuristik 0,5 bis 4 ms (Ausreißer 12,8 ms bei gesättigter Flotte, 4000 Aufträge).
  In den 19 Läufen, in denen beide Verfahren die Anfrage einfügten (1 s und 10 s): OR-Tools liefert in 17 dieser 19 Fälle einen kleineren Zuwachs der Gesamtfahrzeit
  der 10 Fahrzeuge als die Heuristik (in 2 größer). **Der Vergleich ist nicht deckungsgleich:** OR-Tools optimiert zugleich die bestehenden Routen
  um (Zuwachs kann negativ werden, ohne Zusagefenster zu verletzen), die Heuristik ändert nur durch Einfügen. Die Zahlen stehen in
  `spike-b-ortools.json` (`heuristic_delta_s`, `ortools_delta_total_s`).
- Bei 4000 Aufträgen (Flotte weitgehend voll) fügt keines der beiden Verfahren die meisten Anfragen ein; die Heuristik nahm 3 von 8 an,
  OR-Tools über die 10 nächsten Fahrzeuge 0 von 8.
- Zulässigkeit der OR-Tools-Pläne wurde mit demselben Prüfer kontrolliert: 0 Verletzungen.

**Einordnung:** OR-Tools eignet sich nach diesen Messungen als Baustein für die **periodische Verbesserung** bestehender Pläne (Hintergrund,
nie Verschlechterung bindender Zusagen), nicht für die synchrone Kandidatensuche innerhalb von 1 s. Die Einfügeheuristik leistet die
Kandidatensuche in Millisekunden.

## Empfehlung zur Schnittstelle Kern <-> Worker und zum Matrix-Cache

Dies ist eine Empfehlung auf Grundlage der Messung, **nicht entschieden** (Vertragspunkt für M2).

1. **Aufruf:** Für die Kandidatensuche ist ein synchroner Aufruf (Anfrage/Antwort) mit hartem Zeitbudget naheliegend: Die Rechenzeit beträgt
   Millisekunden, sodass eine Queue keinen Gewinn bringt, aber zusätzliche Latenz und Komplexität (Korrelation, Timeout) verursachte. Eine Queue
   bleibt für die periodische Verbesserung und Neuberechnungen im Hintergrund sinnvoll. Nicht gemessen: Serialisierung und Netzwerk zwischen Kern
   und Worker.
2. **Zustand im Worker:** Nur machbar, wenn der Worker den Flottenzustand (Pläne je Fahrzeug) im Speicher hält und der Kern nur die Anfrage und die
   Planrevision schickt; den Zustand bei jeder Anfrage zu übertragen wurde nicht gemessen. Planrevision muss im Ergebnis stehen (Hold prüft sie).
3. **Matrix-Cache:** Eine im Worker vorgehaltene Haltepunkt-Matrix (hier 1200 Knoten = 5,8 MB) macht den Matrixzugriff zu einem Bruchteil der Laufzeit.
   Ein On-Demand-Abruf bei Valhalla je Anfrage mit einigen tausend Paaren (siehe oben) ist ohne Messung aus WP-M0-30 **nicht** für das 1-s-Budget
   freigegeben. Zu klären: Aktualisierungsintervall des Caches (Verkehr, Netzänderungen), Größe bei realer Haltepunktzahl (D-05).
4. **Timeout:** Fristprüfung je Fahrzeug genügt (Überschreitung <= 0,11 ms gemessen). Ablehnung statt Teilergebnis, wenn kein validierter Plan vorliegt.

## Reproduzierbarkeit (AK 5)

Seed 20261111; `python -m taktovia_routing.spike.bench --out docs/spike-b-messwerte.json` (ca. 6 Minuten, davon 86 s Zustandsaufbau für 2000 Fahrzeuge) und
`python -m taktovia_routing.spike.ortools_compare --out docs/spike-b-ortools.json` (ca. 3 Minuten). Zustände, Anfragen und Plan-Ergebnisse sind bei gleichem
Seed identisch (Test `test_determinism_same_seed_same_result`); die Laufzeiten variieren mit der Maschine (siehe oben).

## Annahmen, Grenzen, offen

- Synthetische Instanz (Annahmen oben), keine Pilotregion und Auftragsdichte (D-05); symmetrische Luftlinien-Fahrzeiten ohne Einbahnstraßen, Verkehr oder
  Barrieren (D-09 Routinganbieter offen).
- Der Zeitplan eines Fahrzeugs ist „frühester Start" (kein Warten absichtlich verlängert). Das kann die maximale Fahrzeit an Bord konservativ
  auslegen (zulässige Pläne mit späterem Pickup werden verworfen), nie großzügig. Ein zulässiger Plan ist daher stets zulässig.
- Nicht im Umfang (WP): sichere Fahrzeiten/Ruhezeiten (nur die einfache Pause), Lade-/Reichweitenreserven, Umrüstzustände, Fahrzeugwechsel, Hold und Kapazitätssperre,
  periodische Verbesserung, Partner-Fanout, Matrixlatenz von Valhalla (WP-M0-30).
- Laufzeit gemessen in CPython; ein Produktivworker könnte schneller oder langsamer sein (Zielhardware, Container-CPU-Limits).
- Nicht ausgeführt: Container-Build (kein Docker-Daemon); die Messung lief ohne Container.
