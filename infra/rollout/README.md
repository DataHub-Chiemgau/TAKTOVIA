# Produktionsumgebung und gestaffeltes Rollout (WP-M5-28)

Enabler, keine Anforderung. Stand: **vorläufig, D-05 offen** (Hostinganbieter, Region, Betriebsmodell); D-03 blockiert die Produktivschaltung der Zahlung, D-11 die Produktivfreigabe personenbezogener Daten, OP-26 die Verbindlichkeit von Stufen, Rauchprobe und Stoppkriterien.

| Datei | Inhalt |
|---|---|
| `produktion.json` | Definition der Production-Umgebung: Übernahmen aus WP-M5-03/-04/-05/-06/-14/-22 und dem IaC-/Identitätsgerüst als Dateiverweise, Probemandant, Datenbankregeln |
| `rollout-plan.json` | Stufen (5 %, 25 %, 100 %), Rauchproben je Stufe, Stoppkriterien, Regeln (nur signierte Artefakte, expand/contract, Vorwärtskorrektur, Serialisierung) |
| `betriebszugriff.json` | Zugriffskonzept: personengebunden, MFA, nur anfügbares Protokoll, Notfallzugang mit Vier-Augen-Freigabe und Höchstdauer, verbotene Sammelkonten |
| `taktovia_rollout/domaenen.py` | Zugangsdaten einer anderen Umgebung werden abgewiesen (Secret, Datenbankzugang, PSP, Token-Issuer; fail closed) gegen `../umgebungsmatrix.json` |
| `taktovia_rollout/datenbank.py` | Runtime-Rolle (kein Superuser, BYPASSRLS, Eigentum), Wächter „Tabelle ohne Policy“ (zwei begründete Ausnahmen), keine Testdaten außer dem Probemandanten, Migrationsstand |
| `taktovia_rollout/stufen.py` | gestaffeltes Rollout mit Rauchprobe und Stoppkriterien; bei Abbruch wird nur der Verkehrsanteil zurückgezogen, die Datenbank bleibt unberührt |
| `taktovia_rollout/bereitstellung.py` | Ablauf: Sperre, Artefaktprüfung (Release-Gate WP-M5-22), Zugangstrennung, Migration über den Migrationszugang, Stand gleich Staging, Rollout |
| `taktovia_rollout/zugriff.py` | Betriebszugriff prüfen und in einer Hash-Kette protokollieren |
| `../check_rollout.py` | statische Prüfung (auch in `infra/check.py`) |

## Nachweis

- `cd tests/acceptance/rollout && python -m unittest test_m5_28_rollout test_m5_28_rollout_last` (Datenbankumgebung wie `db/db.py`; der Lasttest braucht `./gradlew :app:bootJar`).
- Positiv: signiertes Artefakt, gestaffeltes Rollout mit Rauchprobe (echte Anwendung, zwei Instanzen, Buchungslast); Aufbau der Production-Datenbank aus dem Repository mit demselben Migrationsstand wie Staging.
- Negativ: unsigniertes, manipuliertes, falsch signiertes Artefakt, Artefakt mit roter Stufe, Probelauf-Manifest; Migration mit der Runtime-Rolle; Staging-Secret/-Zugang/-Token in Production und umgekehrt; fehlgeschlagene Rauchprobe stoppt; geteilte Konten, fehlende MFA.
- Konkurrierend: Rollout unter laufender Buchungslast, Abbruch einer Stufe lässt bestätigte Buchungen, Holds und Zahlungszustände unverändert, Invariantenabfrage Kap. 04 §4.5 ohne Befund; zwei gleichzeitige Bereitstellungen werden serialisiert; gleichzeitige Zugriffsprotokollierung bleibt eine lückenlose Kette.

## Was NICHT ausgeführt ist

- Eine Production-Umgebung bei einem Anbieter (kein Konto, D-05); `terraform validate/plan`; signierte Container-Images (das Release-Gate signiert nur mit HMAC, keine Lieferkettensignatur); echte Lastverteilung (im Test eine Weiche).
- Im Lasttest sind „alte“ und „neue“ Version dasselbe Jar; belegt ist die Nebenläufigkeit zweier Instanzen, nicht die Kompatibilität zweier verschiedener Versionen. Es entsteht keine Zahlungslast; Zahlungszustände sind nur für vorhandene Zeilen geprüft.
- Rauchprobe `synthetische_probe` ist im Test ein Ersatz (Angebotssuche, `/v1/me`); die volle Probe (`infra/availability`) braucht Operatorzugang und Probemandanten (OP-28). `alarmkette` läuft gegen die Nachbildung des Alertmanager-Routings, `archivierungsverzug` (< 15 min) ist ohne WAL-Archiv nicht ausgeführt und im lokalen Testplan entfernt.
- MFA und Personenbindung des Betriebszugriffs sind als Regelprüfung belegt, nicht gegen einen Identitätsanbieter. Das Einspielen von Pilotdaten gehört zu WP-M5-29.
- Die Verdrahtung der Prüfungen als CI-Stufe (`.github/`) gehört nicht zu diesem Paket.
