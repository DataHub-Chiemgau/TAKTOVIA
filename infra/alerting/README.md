# Alerting kritischer Fehlerszenarien (WP-M5-04, LH-6.19-NFR-008)

Für die kritischen Fehlerszenarien entsteht je ein Alarm, der einen definierten Empfänger erreicht und im Betriebshandbuch einen Handlungsschritt hat.

| Datei | Inhalt |
|---|---|
| `katalog.yaml` | Szenariokatalog: Schwelle, Schweregrad, Empfängerrolle, Eskalation, Runbook-Verweis, Dedupe-/Stummschaltregel (17 Szenarien; Backup und Archivierungs-/Replikationsverzug stehen im Katalog und folgen mit WP-M5-14) |
| `../observability/alert-rules.d/kritische-szenarien.yml` | die Alarmregeln als Code (20 Regeln) |
| `alertmanager.yml` | genau ein Alarmkanal `betreiber`, der Herzschlag-Empfänger `totmann`, Gruppierung `[alertname, component]`, Wiederholung kritisch 1 h / Warnung 4 h |
| `deadman.py` | unabhängige Totmannprüfung der Kette (anderer Host, eigener Meldeweg) |
| `alertops.py` | Betriebswerkzeug: Stummschalten (höchstens 8 h, Pflichtkommentar), Quittieren (je Alarm genau einmal), Regeländerung mit Prüfung; nur Betriebsrollen, jeder Vorgang im Audit-Protokoll |
| `promql_mini.py`, `chain.py` | Nachbildung der Regelauswertung (PromQL-Teilmenge) und des Alertmanager-Routings für die Tests |
| `rules_test.yml` | promtool-Testfälle (CI) |
| `../check_alerting.py` | statische Prüfung Katalog ↔ Regeln ↔ Komponenten ↔ Runbook ↔ Routing ↔ Metriken (auch in `infra/check.py`) |
| `../../docs/betrieb/alarm-handlungsschritte.md` | erster Handlungsschritt je Szenario (Vorläufer des Betriebshandbuchs, WP-M5-26) |

## Was geprüft ist und was nicht

| Geprüft (Befehl) | **Nicht ausgeführt** (Grund) |
|---|---|
| `python3 infra/check.py` (enthält `check_alerting.py`); `python3 -m unittest infra.test_check_alerting infra.test_check`; `python3 -m unittest discover -s infra/alerting -p 'test_promql_mini.py'` | Prometheus und Alertmanager selbst (kein Docker); `promtool test rules` (nicht installiert) |
| `cd tests/acceptance && python -m unittest alerting.test_alert_scenarios`: jedes aktive Szenario wird durch Messreihen gezielt ausgelöst, die echten Regeln feuern, die Nachbildung des Routings aus der echten `alertmanager.yml` stellt per echtem HTTPS an den Test-Empfänger zu; Auslöse- und Zustellzeit werden protokolliert; Schwellen knapp darunter, anhaltender Fehler, gleichzeitige Ausfälle, Stummschaltung, Rollen, parallele Quittierung, Totmannprüfung (als echter Prozess) | das Verhalten des echten Alertmanagers (Notification-Log, Inhibition, Cluster); die Zustellung an den echten Rufbereitschaftskanal (Empfänger und Rufbereitschaft offen: D-05, OP-07, OP-32/OP-39); der Alarm aus einem echten Fachkern-Scrape (Dienste laufen hier nicht) |
| Abnahme NFR-008 | **nicht vollständig**: die Backup-/Replikationsalarme aus WP-M5-14 sind noch nicht ausgelöst; der Katalogabgleich weist das ausdrücklich aus |

## Übernahme in die Fachalarmierung

Betrieblich relevante technische Alarme lassen sich als `TECH_<Szenario>`-Alarm in die Arbeitsliste „Offene Störungen“ übernehmen (`platform.alert.BusinessAlertPort`, WP-M3-52; Baustein `app.alerting.TechnicalAlertBridge`: übernimmt nur Szenario, Schweregrad und Komponente, nie Alarmtexte). **Nicht verdrahtet:** ob und für welchen Mandanten technische Alarme dort geführt werden, ist offen (OP-07); der Baustein hat deshalb keinen HTTP-Eingang.

## Betrieb

- `alertmanager.yml`: Adressen nur als Secret-Dateien (`alert_channel_credential`, `deadman_credential`); die Totmannprüfung braucht einen eigenen Meldeweg (`deadman_channel_credential`), der nicht über die Kette läuft.
- Stummschalten und Quittieren nur über `alertops.py` bzw. mit derselben Rolle und Protokollierung; Regeländerungen per Pull Request mit Code-Owner-Review (`.github/CODEOWNERS`).
