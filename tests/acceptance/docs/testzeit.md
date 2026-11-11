# Steuerbare Testzeit (WP-M0-22, Offene Punkte 26)

Gemeinsame Vereinbarung für PSP-Simulator und Sandbox-Partner, umgesetzt in `tests/lib/taktovia_testkit/testclock.py`.

- Jeder Simulator hat eine eigene Uhr, standardmäßig **eingefroren** (`frozen`) ab einer Startzeit. Zwei Läufe mit gleicher
  Startzeit und gleicher Aufrufreihenfolge erzeugen gleiche Zeitstempel (geprüft in `test_acceptance.py`).
- Steuerung bei jedem Simulator: `GET/POST /control/clock` mit `{"set": ISO}`, `{"advance_seconds": n}`, `{"mode": "frozen|real"}`.
- Szenario-Hilfsfunktion: `Context.set_time(iso)` setzt die eigene Uhr und die aller angemeldeten Simulatoren
  (`register_sim`). Evidenz-Zeitstempel (`executed_at`) kommen aus dieser Uhr, nicht von der Wanduhr.
- Vorstellen geht nur vorwärts; rückwärts muss die Uhr ausdrücklich neu gesetzt werden.

**Grenze (dokumentiert).** Die Datenbankzeit `now()` wird nicht verstellt. Ablauf-Szenarien, die am Datenbank- oder Kernzeitpunkt
hängen (Hold-Ablauf `now >= expires_at`, Zeitfenster ±300 s auf Kernseite, DST in PH-AT-018), laufen über kurze reale
Fristen oder über eine in M1 zu liefernde Zeitquelle des Kerns. Die Simulatoruhr gilt nur für Zeitstempel und Fristen im
Simulator selbst.
