# Messprotokoll Spike (c) Driver-Offline-Queue – Vorlage

> **Vorlage. Alle Werte sind leer.** Es sind keine Messwerte eingetragen; auf Zielgeräten wurde nichts gemessen. Die Kopffelder entsprechen der Berichtsvorlage aus WP-M0-25 (Hardware, Datenstand, Stichprobe, Rohdatenablage); bei Abweichung gilt jene Vorlage.

## Kopf (je Gerät ein Protokoll)

| Feld | Wert |
|---|---|
| Datum / Durchführende Person | |
| Hersteller, Modell | |
| Betriebssystem und Version | |
| Verwaltet / nicht verwaltet (Geräteverwaltung) | |
| App-Build (Commit, Flutter 3.47.9, Dart 3.13.7) | |
| Bibliothek verschlüsselte SQLite (Version, aktive Chiffre per `PRAGMA cipher`) | |
| Datenstand (synthetischer Datensatz, Erzeugung) | |
| Stichprobe (Anzahl Ereignisse je Lauf; Plan: 1.000) | |
| Netz (WLAN/Mobilfunk, Testserver) | |
| Rohdatenablage (Pfad/Link) | |

## Ergebnisse je Störfall (Nummern laut spike-drehbuch.md)

| Nr. | Ereignisse erfasst | serverseitig `APPLIED` | `DUPLICATE` bei Wiederholung | Verlust | doppelt wirksam | Sync-Dauer (s) | Bemerkung |
|---|---|---|---|---|---|---|---|
| S1 | | | | | | | |
| S2 | | | | | | | |
| S3 | | | | | | | |
| S4 | | | | | | | |
| S5 | | | | | | | |
| S6 | | | | | | | |
| S7 | | | | | | | |
| S8 | | | | | | | |
| S9 | | | | | | | |
| S10 | | | | | | | |
| S11 | | | | | | | |
| S12 | | | | | | | |
| S13 | | | | | | | |

Sync-Dauer als Median und p95 über mindestens 5 Läufe angeben (Stichprobengröße nennen).

## Akzeptanzkriterien (WP-M0-28)

| AK | Erfüllt (ja/nein/nicht geprüft) | Beleg |
|---|---|---|
| 1 Verlust 0, jedes genau einmal `APPLIED` | | |
| 2 nach App-Abbruch und Neustart unverändert | | |
| 3 Datei ohne Schlüssel nicht lesbar | | |
| 4 veraltete Revision → `REVIEW_REQUIRED` | | |
| 5 Erfassungs- und Empfangszeit getrennt (auch bei Uhrverstellung) | | |
| 6 Bericht je Gerät mit Betriebssystemversion | | |
| 7 Dienst und Stoppliste offline mit „Stand von …“ | | |
| 8 nur synthetische Daten | | |

## Geräte, die ein Kriterium nicht erfüllen

(leer)

## Empfehlung für M3

(erst nach Messung auszufüllen)
