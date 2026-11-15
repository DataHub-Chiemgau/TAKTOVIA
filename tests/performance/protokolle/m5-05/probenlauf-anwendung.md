# Verfügbarkeit im Testzeitraum 2026-11-10T00:00:00+00:00 bis 2026-11-10T00:14:00+00:00

Umgebung: lokal (Ersatz für Staging) · erzeugt 2026-11-15T00:21:11+00:00 · Probenintervall 60 s · Status der Zielwerte: **vorläufig, D-05 offen**

Formel: `Verfügbarkeit = (Messminuten − Ausfallminuten außerhalb Wartung) / (Messminuten − Wartungsminuten)`

| Bereich | Verfügbarkeit | Ziel | Aussage | Messminuten | Ausfallminuten | Wartungsminuten | Anbieterfehler-Min. | keine Messung (Min.) | Abdeckung |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|
| Kernbuchung | 75.0000 % | 99.9 % | ziel_verfehlt | 14.0 | 3.0 | 2.0 | 0.0 | 0.0 | 100.0 % |
| Operator | 75.0000 % | 99.9 % | ziel_verfehlt | 14.0 | 3.0 | 2.0 | 0.0 | 0.0 | 100.0 % |

## Messpunkte
- **kernbuchung**: POST /v1/ride-offers, POST /v1/ride-offers/{id}/hold, POST /v1/bookings, POST /v1/bookings/{id}/cancel. Angebot erhalten (200 mit mindestens einem Angebot), Hold 201, Buchung bestätigt (201, Zustand bestätigt), Probebuchung storniert (200); Probemandant, eigener Dienst, eigenes Probefahrzeug.
- **operator**: POST <Token-Endpunkt des Anmeldedienstes>, GET /v1/me, GET /v1/operator/vehicles. Anmeldung liefert ein Zugriffstoken, /v1/me liefert 200 mit Mandantenbezug des Probemandanten, /v1/operator/vehicles liefert 200 und enthält das Probefahrzeug.

## Wartungsfenster
- 2026-11-10T00:09:00+00:00 bis 2026-11-10T00:11:00+00:00 (geplante Wartung (Test)): 2.0 min im Zeitraum, herausgerechnet

## Ausfälle Kernbuchung
- 2026-11-10T00:04:00+00:00 bis 2026-11-10T00:07:00+00:00: 3.0 min (davon Wartung 0.0 min, gezählt 3.0 min)
- 2026-11-10T00:09:00+00:00 bis 2026-11-10T00:11:00+00:00: 2.0 min (davon Wartung 2.0 min, gezählt 0.0 min)

## Ausfälle Operator
- 2026-11-10T00:04:00+00:00 bis 2026-11-10T00:07:00+00:00: 3.0 min (davon Wartung 0.0 min, gezählt 3.0 min)
- 2026-11-10T00:09:00+00:00 bis 2026-11-10T00:11:00+00:00: 2.0 min (davon Wartung 2.0 min, gezählt 0.0 min)

## Rohdaten
probenlauf-anwendung-rohdaten.jsonl (SHA-256 287e99aa81f4880797bfa644ec1acfdfefc25c5cddb22cd89051fa6358374ae7, 28 Sätze)

## Messgrenze
Gemessen wird von außerhalb der Plattform an der öffentlichen Schnittstelle (Gateway) und am Anmeldedienst. Antwortet die Plattform mit einem Fehler der Plattform (Verbindungsfehler, Zeitüberschreitung, 5xx ohne Providerbezug, falscher Inhalt), ist der Lauf `fehler_plattform`. Fehler eines Zahlungs- oder Kartenanbieters (Problem-Code mit Providerbezug) sind `fehler_provider`: sie werden getrennt ausgewiesen und nicht als Plattformausfall gezählt. Fällt die Probe selbst aus (ungültige Zugangsdaten, nicht erreichbarer Referenzpunkt, Ausnahme im Probenprozess), ist der Lauf `keine_messung`.

## Fehlende Proben
Ein Intervall ohne Messwert (Probe ausgefallen, Lücke im Rohdatenbestand) ist `keine_messung`: es zählt weder als verfügbar noch als Ausfall. Unterschreitet die Abdeckung (Minuten mit Messwert / Minuten des Zeitraums außerhalb Wartung) die Mindestabdeckung, weist der Bericht `aussage: unzureichende_abdeckung` aus und kein Erreicht/Verfehlt.

## Wartungsregel
Wartungsfenster stehen vorab im Wartungskalender (wartungskalender.json, Eintrag vor Beginn des Fensters). Minuten innerhalb eines Fensters zählen weder als Ausfall noch im Nenner der Verfügbarkeit; sie werden im Bericht ausgewiesen. Ein nachträglich eingetragenes Fenster wird im Bericht als `nachtraeglich` gekennzeichnet und nicht herausgerechnet.

## Hinweise
- Zielwerte und Messgrenzen sind vorläufig, D-05 offen (Kap. 08 §8.6).
- Ein voller Produktivmonat ist erst im Pilotbetrieb nachweisbar (OP-02); dieser Bericht belegt das Messverfahren über den angegebenen Zeitraum.
- Der Zeitraum ist kein voller Kalendermonat (Testzeitraum).
