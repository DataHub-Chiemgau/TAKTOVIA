# Rohdaten Spike (a)

Erzeugt von `tests/performance/spikes/spike_a/run_spike_a.py` (Seed 1, Standardparameter). Reproduktion: siehe Abschnitt „Reproduktion“ in `../spike-a-hold-confirm.md`.

| Datei | Inhalt |
|---|---|
| `results.json` | alle Kennzahlen, Messprotokoll (Hardware, PostgreSQL-Einstellungen, Datenstand), Zählungen je Experiment, Ausführungspläne |
| `e1_primary_iterations.csv.gz` | je Durchlauf der empfohlenen Strategie (PH-AT-004): Mandant, Gewinner, Buchungen, Kapazitätsverstöße, Ergebnisverteilung |
| `e5_<index>_<szenario>_events.csv.gz` | je Transaktion der Latenzmessung: Operation, Mandant, Dauer, Sperrwartezeit, Ergebnis, Wiederholungen |

Die Dateien sind synthetisch (keine Personen- oder Betriebsdaten). Die Datenbank des Messlaufs wurde nach dem Lauf verworfen.
