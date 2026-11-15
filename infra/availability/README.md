# infra/availability: Verfügbarkeitsmessung mit synthetischen Proben (WP-M5-05, LH-6.19-NFR-001)

Die Verfügbarkeit von **Kernbuchung** und **Operator** wird von außen an der öffentlichen Schnittstelle gemessen; der Berichtsgenerator berechnet daraus je Kalendermonat und Bereich die
Verfügbarkeit außerhalb vereinbarter Wartung und weist das Ziel (mindestens 99,9 %) als erreicht oder verfehlt aus. **Alle Zielwerte und Messgrenzen sind vorläufig (D-05 offen, Kap. 08 §8.6).**
Vertrag: [LH-6.19-NFR-001](../../docs/umsetzungsplan/vertraege/LH-6.19-NFR-001.md), Berichtsschema: [verfuegbarkeitsbericht.v1.schema.json](../../docs/umsetzungsplan/vertraege/schemas/verfuegbarkeitsbericht.v1.schema.json).

| Baustein | Datei | Stand |
|---|---|---|
| Messpunktvertrag (Probenintervall 60 s, Zeitüberschreitung, Ziel, Messgrenze, Wartungs- und Lückenregel) | `messpunktvertrag.json` | Arbeitswerte; der abgestimmte Vertrag kommt aus WP-M5-01 |
| Wartungskalender (Fenster müssen vor Beginn eingetragen sein) | `wartungskalender.json` | leer, Regel ausgeführt (Test) |
| Proben (nur bestehende Pfade `/v1/ride-offers`, Hold, `/v1/bookings`, Storno; Anmeldung, `/v1/me`, `/v1/operator/vehicles`), Klassifikation `ok`, `fehler_plattform`, `fehler_provider`, `keine_messung`, Probenalarm | `taktovia_availability/proben.py` | ausgeführt gegen Attrappe über HTTP **und gegen die echte Anwendung** (opt-in) |
| Berichtsgenerator (Formel, Messpunkte, Intervall, Wartungsfenster, Ausfallminuten, Rohdatenverweis mit SHA-256), Markdown und JSON | `taktovia_availability/bericht.py`, `cli.py` | ausgeführt |
| Probemandant (eigener Dienst, Probefahrzeug, Umlauf) | `taktovia_availability/probemandant.py`, Migration `V50050__probemandant.sql` | ausgeführt (Datenbanktest) |
| Betrieb auf einem Host außerhalb der Plattform | `systemd/taktovia-probe.*` | **nicht ausgeführt** (keine Zielumgebung, D-05) |

## Aufruf

```bash
# ein Lauf je Bereich (vom Timer im Probenintervall aufgerufen); Zugangsdaten aus der Umgebung (Werte env:NAME in der Konfiguration)
python -m taktovia_availability probe --config probe-config.json --rohdaten rohdaten.jsonl --alarmdatei alarme.jsonl
# Bericht
python -m taktovia_availability bericht --rohdaten rohdaten.jsonl --monat 2026-11 --wartung wartungskalender.json --ausgabe berichte/
```
Tests: `cd tests/performance/availability && python -m unittest discover -s . -p 'test_*.py'` (Datenbanktests brauchen die Umgebung aus `db/db.py`; der Lauf gegen die Anwendung zusätzlich `TAKTOVIA_PERF_RUN=1`).

## Messregeln (Kurzfassung, Einzelheiten im Kopf von `bericht.py` und im Messpunktvertrag)

- **Formel** je Kalendermonat (UTC) und Bereich: `(Messminuten − Ausfallminuten außerhalb Wartung) / (Messminuten − Wartungsminuten)`.
- **Ergebnisse je Lauf:** `ok`; `fehler_plattform` (Verbindungsfehler, Zeitüberschreitung, 5xx, falscher Inhalt, vom Dienst abgelehntes gültiges Token); `fehler_provider` (Problem-Code `PROVIDER_UNAVAILABLE`: getrennt ausgewiesen, **nicht** im Plattformausfall);
  `keine_messung` (Probe selbst ausgefallen: ungültige Zugangsdaten, 403 des Probekontos, Referenzpunkt nicht erreichbar, Ausnahme im Probenprozess). `keine_messung` und fehlende Intervalle zählen weder als verfügbar noch als Ausfall und lösen den **Probenalarm** aus (JSON-Zeile in der Alarmdatei, Metrik `taktovia_probe_result`).
- **Wartung:** nur vorab eingetragene Fenster werden herausgerechnet und im Bericht ausgewiesen; nachträglich eingetragene werden als solche gekennzeichnet und nicht herausgerechnet.
- **Abdeckung:** unter 90 % der Zeit außerhalb Wartung mit Messwert lautet die Aussage `unzureichende_abdeckung` (kein erreicht/verfehlt).
- **Probebuchungen:** laufen im **Probemandanten** (`tenant.synthetic`, OP-03 als Mandantenmerkmal entschieden). Die Probe storniert ihre Buchung im selben Lauf (auch bei Fehlern); Ereignisse und Fakten des Probemandanten werden nie in der Analytics-Faktenbasis gespeichert (Trigger in V50050), sie erscheinen also nicht in `Anfragen`, `Buchungskonversion` und den übrigen Kennzahlen. Der Probemandant hat keinen Zugriff auf Echtdaten und umgekehrt (RLS, mandantengebundene Fremdschlüssel).

## Grenzen (nicht ausgeführt, offen)

- Keine Zielumgebung (Staging/Produktion): Proben liefen gegen eine Attrappe und gegen die lokal gestartete Anwendung mit JWT-Test-Issuer (Ersatz für Keycloak). Die Probe der **Anmeldung** gegen Keycloak ist nicht ausgeführt.
- Ein voller Produktivmonat ist erst im Pilot nachweisbar (OP-02); der Bericht belegt das Messverfahren über Testzeiträume. Der Kalendermonat wird in UTC gezählt (Arbeitswert).
- OP-28 (Probemandant in Produktion zulässig?) offen; Einrichtung in Produktion ist WP-M5-28. Die Probeschicht des Probefahrzeugs ist auf 23 Stunden begrenzt und muss vom Probenbetrieb täglich erneuert werden.
- Die Produktionsverdrahtung von `/v1/ride-offers` bindet Angebote noch nicht an Kandidatensuche und Solver (siehe `tests/performance/README.md`); der Lauf gegen die Anwendung setzt die Planbindung per SQL (`Konfiguration.planbindung`). In Staging mit Solver bleibt der Hook leer.
- Kein Alarmierungsweg an Alertmanager: der Probenalarm ist als Alarmdatei und Metrik ausgeführt; die Regel im Alarmkatalog (`infra/alerting`) gehört zu WP-M5-04.
