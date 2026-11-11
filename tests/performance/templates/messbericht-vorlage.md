# Messbericht <Titel> (Vorlage, WP-M0-25)

Verwendung: Pflicht für die Spikes WP-M0-26 und -27; die Spikes WP-M0-28 bis -30 verwenden dieselben Kopffelder.
Format der Messwerte: `schema/report.schema.json`; Perzentile Nearest-Rank über die tatsächlich gemessenen Werte.

## Kopf (Pflicht)

| Feld | Inhalt |
|---|---|
| Datum, Ausführender | |
| Software-Stand (Commit) | |
| Hardware (CPU-Modell, logische CPUs, RAM, Betriebssystem, Virtualisierung) | |
| Datenstand (Beschreibung, Zeilenzahlen je Tabelle, Seed) | |
| Stichprobe (Anzahl Messwerte, Auswahlregel, ausgeschlossene Aufwärmphase) | |
| Parallelität / Poolgröße / Isolationsstufe (soweit zutreffend) | |
| Rohdatenablage (Pfad und SHA-256) | |
| Reproduktion (Befehl, Seed) | |

## Ergebnis

Tabelle mit n, p50, p95, p99, max je Operation; Fehler-/Abweisungszahlen; Abweichungen von den Erfolgskriterien.

## Bewertung gegen die vorläufigen Lastenheftziele

Je Kriterium: Ziel, gemessen, Urteil (erfüllt / verfehlt / nicht bewertet). Bei Verfehlung Ursache und Alternativvorschlag.

## Offene Pflichtparameter und Grenzen

Peak-Requests/s und Anzahl aktiver Mandanten (D-05) nennen; Grenzen der Messung (Umgebung, Datenstand, nicht Gemessenes).
