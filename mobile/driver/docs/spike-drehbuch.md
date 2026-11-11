# Spike (c) Driver-Offline-Queue – Drehbuch (WP-M0-28)

Status: **Vorlage, auf keinem Gerät ausgeführt.** Es werden ausschließlich synthetische Ereignisse und Testkonten verwendet; keine Standort- oder Personendaten realer Fahrer (AK 8, D-10).

## Vorbereitung je Gerät

1. Prototyp aus dem Spike-Zweig installieren (verschlüsselte `SqliteOutboxStore`, Test-Gegenstelle erreichbar oder lokaler Nachbau von `FakeDriverEventServer`).
2. Kopffelder des Messprotokolls ausfüllen (Gerät, Betriebssystemversion, verwaltet/nicht verwaltet).
3. Schlüssel im Gerätespeicher erzeugen; Schlüsselablage notieren.

## Störfälle (je Gerät, je mit 1.000 synthetischen Ereignissen, soweit nicht anders angegeben)

| Nr. | Störfall | Durchführung | Erwartung |
|---|---|---|---|
| S1 | Online-Erfassung | Ereignis erfassen, Sync | `APPLIED` |
| S2 | Flugmodus | 1.000 Ereignisse im Flugmodus erfassen, danach Netz an, Sync | alle genau einmal `APPLIED`, Verlust 0 |
| S3 | App-Beendigung durch das System | Sync starten, Prozess hart beenden, App starten, Sync | wie S2, keine Duplikatwirkung |
| S4 | Geräteneustart während Sync | Neustart mitten im Sync | wie S2 |
| S5 | Voller Speicher | Speicher füllen, Erfassen versuchen | definierter Fehler, bereits gespeicherte Ereignisse unversehrt |
| S6 | Uhrverstellung | Uhr zurück/vor stellen, erfassen, Sync | Erfassungszeit (Geräteuhr) und Serverempfangszeit getrennt erhalten |
| S7 | Hintergrundbeschränkung | Akkuoptimierung aktiv, App im Hintergrund | Sync-Verhalten notieren, Verlust 0 |
| S8 | Schlüssel/Datei | Datei vom Gerät lesen ohne Schlüssel | nicht lesbar (AK 3) |
| S9 | Veraltete Planrevision | Server auf höhere Revision setzen, Ereignis senden | `REVIEW_REQUIRED`, lokal nicht „synchronisiert“ (AK 4) |
| S10 | Abgelaufenes Token | Sync mit ungültigem Token | Ereignisse bleiben in der Queue |
| S11 | Manipulierte Queue-Datei | Datei verändern | App erkennt es, kein Versand |
| S12 | Zwei Prozesse | Sync gleichzeitig aus zwei Prozessen | keine doppelte Wirkung |
| S13 | Offline-Lesestand | Netzverlust und App-Neustart | Dienst und Stoppliste lesbar mit „Stand von …“ (AK 7) |

Die Störfälle S2, S3 (logisch), S9 bis S12 und S13 (logisch) sind in `test/offline/outbox_sync_test.dart` auf Logikebene abgedeckt (nicht ausgeführt); die Geräteverifikation steht aus.
