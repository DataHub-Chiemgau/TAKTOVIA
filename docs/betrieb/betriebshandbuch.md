---
dokument_version: 0.1
abnahme_build: ENTWURF-W18-9d035b7
sprache: de
stand: 2026-11-15
arbeitspaket: WP-M5-26
---
# Betriebshandbuch TAKTOVIA: Monitoring, Eskalation, Notbetrieb

- **Arbeitspaket:** WP-M5-26 (Enabler, Betriebs-Gate aus Kap. 08 §8.4)
- **Stand:** Entwurf 0.1, erstellt auf dem Stand von Welle 17. Die Endfassung und die Produktivschaltung blockieren **D-11** (Notbetrieb, Kontaktprozess, Freigaberollen) und **D-05** (Supportzeiten, Betrieb, Hosting); bis dahin sind alle Zeiten, Schwellen und Kontakte **vorläufig** und im Text als solche gekennzeichnet.
- **Zielgruppe:** das Betriebspersonal des Betreibers, das die Plattform ohne das Entwicklungsteam betreibt.
- **Grundsatz:** Jeder Schritt nennt die erforderliche **Rolle**. Kein Schritt im Regelbetrieb verlangt Superuser- oder direkten Datenbankzugriff; Zustände werden über die Anwendung (API, Betriebswerkzeuge mit eigener Rolle) geändert, nie per SQL. Bestätigte Buchungen werden nie gelöscht, Fehler werden durch Vorwärtskorrektur behoben (Kap. 01 §1.8).
- **Prüfung:** `python3 tools/check_betriebshandbuch.py` gleicht den Alarmkatalog (`infra/alerting/katalog.yaml`) mit den Runbooks dieses Handbuchs ab (Vollständigkeit, Runbook-Vorlage, Rolle je Runbook, keine Superuser-Schritte).

## Inhalt

1. [Systemübersicht](#1-systemübersicht)
2. [Rollen im Betrieb](#2-rollen-im-betrieb)
3. [Umgebungen](#3-umgebungen)
4. [Deployment und Migration](#4-deployment-und-migration)
5. [Monitoring und Dashboards](#5-monitoring-und-dashboards)
6. [Alarmierung und Eskalation](#6-alarmierung-und-eskalation)
7. [Runbooks je Alarm](#7-runbooks-je-alarm)
8. [Klärfälle](#8-klärfälle)
9. [Backup, Restore und Wiederanlauf](#9-backup-restore-und-wiederanlauf)
10. [Wiederkehrende Übungen und Wartungsfenster](#10-wiederkehrende-übungen-und-wartungsfenster)
11. [Notbetrieb](#11-notbetrieb)
12. [Betriebsrelevante Parameter](#12-betriebsrelevante-parameter)
13. [Erkenntnisse aus WP-M5-15 und WP-M5-16](#13-erkenntnisse-aus-wp-m5-15-und-wp-m5-16)
14. [Nachweisstand und offene Punkte](#14-nachweisstand-und-offene-punkte)

## 1. Systemübersicht

| Komponente | Aufgabe | Zustand und Wahrheit | Bei Ausfall |
|---|---|---|---|
| **Fachkern** (Spring Boot, `backend/`) | alle fachlichen Schnittstellen (`/v1/...`), Zeitgeber (Operation-Runner, Zahlungskopplung, Hold-Ablauf, Outbox-Relay) | zustandslos; Wahrheit in PostgreSQL | Buchung nicht möglich (`503`), nichts geht verloren |
| **PostgreSQL + PostGIS** | einzige Wahrheit: Buchungen, Kapazität (Holds), Zahlungen, Outbox/Inbox, Audit; Mandantentrennung per Row-Level-Security | Primär plus Standby, WAL-Archiv, Basisbackups | Fachkern antwortet fail-closed, Wiederanlauf nach Drehbuch (Abschnitt 9) |
| **RabbitMQ** | Zustellung der Ereignisse aus der Outbox an Verbraucher (Kennzahlen, Benachrichtigung, Störungsablauf) | nur Transport; Outbox hält zurück | Folgeprozesse verzögert, Buchung läuft weiter |
| **Redis** | flüchtiger Cache (Routing-Cache, Rate-Limit) | nie Wahrheit | Kapazität, Preis, Zahlungszustand unverändert; Sitzungssperre entscheidet die Datenbank |
| **Routing-Worker** (`workers/routing`) | Fahrzeiten, Matrix, Fußweg, Einfügeheuristik (Solver) | Kartenversion | Angebote begründet abgelehnt oder aus validiertem Cache; Fahrtenpläne bleiben abrufbar |
| **Dokumenten-Worker** (`workers/documents`) | Prüfung und Aufbereitung von Uploads (Quarantäne) | Warteschlange | Uploads bleiben in Quarantäne, nichts Ungeprüftes wird ausgeliefert |
| **PSP-Adapter** (`workers/psp`) | Autorisierung, Einzug, Void, Erstattung beim Zahlungsdienstleister | PSP ist Wahrheit der Zahlung; Abgleich über Statusabfrage | „Ergebnis wird geprüft“, Abgleich, nie Neubuchung |
| **Gateway** | TLS, Eingangsprüfung, Rate-Limit | zustandslos | Plattform von außen nicht erreichbar |
| **Observability** (`infra/observability`) | Metriken, Traces, maskierte Logs, Alertmanager, Totmannprüfung | Prometheus/Loki/Tempo | Überwachung blind (Alarm `ALARMKETTE_AUSGEFALLEN`) |
| **Backup** (`infra/backup`) | Basisbackup, WAL-Archivierung, Verifikation | Archivspeicher mit Object Lock | kein PITR, Alarm `BACKUP_FEHLGESCHLAGEN` |

**Zustandsautomaten, die der Betrieb kennen muss:** Buchung (`CONFIRMING`, `CONFIRMED`, `RECONCILING`, `COMPENSATING`, `CANCEL_PENDING`, `CANCELED`, `COMPLETED`, `FAILED`), Hold (`HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`), Zahlung (`AUTHORIZED`, `CAPTURED`, `CAPTURE_UNKNOWN`, `RECONCILING`, `REFUND_PENDING`, `REFUNDED`, ...), Operation (`QUEUED`, `RUNNING`, `RECONCILING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW`). Unbestimmte Zustände bleiben unbestimmt, bis der Abgleich sie klärt (Kap. 02 §7.4); sie werden weder als Erfolg noch als Fehler verkürzt.

## 2. Rollen im Betrieb

| Rolle | Aufgabe | Zugriff |
|---|---|---|
| **Bereitschaft Betrieb** (`betrieb-bereitschaft`) | erste Reaktion auf kritische Alarme, Wiederanlauf, Quittieren | Betriebswerkzeuge (`alertops.py`, Backup-Werkzeug `ops-backup`), Dashboards, Verwaltungsport; kein Superuser |
| **Betrieb Plattform** (`betrieb-plattform`) | Warnungen, Kapazität, Deployments, Zertifikate | wie oben, Deployment-Rolle |
| **Betriebsleitung** | Freigabe von Promotion eines Standby, Vorwärtskorrektur, Notbetrieb-Entscheidung (D-11) | Freigabe, keine Datenänderung |
| **Finanzen** (`finance`) | Klärung unbestimmter Zahlungen und Erstattungen | Anwendung (Rolle `finance`) |
| **Leitstelle** (`dispatcher`) | Fahrbetrieb, Störungsablauf, Notbetrieb im Fahrbetrieb | Leitstellenoberfläche |
| **Plattformbetrieb** (`platform_operator`) | Mandanten anlegen und sperren | Plattform-API |
| Datenbankadministration | nur für Wiederherstellung (Abschnitt 9) und Migrationen mit kurzlebigem Zugang; **nie im Regelbetrieb** | getrennter Betriebszugang |

Die Anwendung läuft unter der Runtime-Rolle ohne Superuser, ohne BYPASSRLS und ohne Tabelleneigentum. Eine Änderung von Zuständen per SQL ist verboten; der Wächter in `tests/acceptance/e2e/waechter.py` zeigt, dass der Referenzfall ohne sie auskommt.

## 3. Umgebungen

Die Umgebungen (lokal, Test, Staging, Produktion) mit Geheimnisdomänen, Issuern und Netzen stehen in `infra/deploy/umgebungsmatrix.csv`; Geheimnisse liegen ausschließlich in der Secret-Verwaltung der Umgebung (`secret://<domäne>/<name>`), nie im Repository oder in der Konfiguration. Staging ist die Abnahmeumgebung (Referenzfall `tests/acceptance/e2e`, negative Fälle `tests/acceptance/test_negative_e2e.py`); sie läuft mit PSP-Sandbox (D-03 offen: bis dahin PSP-Simulator). **Das Hosting der Produktion ist offen (D-05).**

## 4. Deployment und Migration

1. **Migration vor Anwendung, expand/contract:** Schemaänderungen erst erweiternd (neue Spalten und Tabellen, abwärtskompatibel), dann die Anwendung, danach das Aufräumen (`contract`) in einem späteren Release. Migrationen laufen mit dem kurzlebigen Betriebszugang (`db/db.py migrate`), nie mit der Runtime-Rolle.
2. **Vorwärtskorrektur statt Löschen:** Eine fehlerhafte Auswirkung auf bestätigte Buchungen wird durch eine neue Revision oder einen Folgezustand korrigiert, niemals durch Löschen oder Rückdrehen bestätigter Buchungen (Kap. 01 §1.8). Das gilt auch für Konfiguration (Rollback = neue Revision).
3. **Freigaben:** Deployment durch die Rolle Betrieb Plattform nach Freigabe der Betriebsleitung; Vier-Augen-Prinzip für Konfigurations- und Tarifveröffentlichung (Anwendung erzwingt es).
4. **Prüfpunkte vor und nach dem Deployment:** Gesundheit (`/actuator/health` auf dem Verwaltungsport), Referenzfall (`tests/acceptance/e2e`), Fehlerrate je Endpunktklasse, Warteschlangenstände (Outbox, Operationen).
5. **Abgleich mit der fertigen Produktionsumgebung** erfolgt in WP-M5-29 (nach WP-M5-28) und ist hier **nicht** geprüft.

## 5. Monitoring und Dashboards

- **Telemetrie** (WP-M5-03, [telemetrie.md](telemetrie.md)): Metriken (Latenz je Endpunktklasse, Fehlerrate, Pool, Outbox-Alter, Operationen, Warteschlangen), Traces mit Korrelations-ID, maskierte JSON-Logs ([maskierung.md](maskierung.md)). Die Prometheus-Ansicht liegt auf dem Verwaltungsport, nie auf dem Mandanten-API-Port.
- **Dashboards:** `taktovia-kern` (Fehlerklassen, Pool, Latenz), Warteschlangen (Outbox, Operationen, Inbox), Datenbank (Replikation, WAL, Backups), Verfügbarkeit (synthetische Proben, WP-M5-05, Monatsbericht).
- **Verfügbarkeit:** Synthetische Proben messen die Erreichbarkeit der Kernpfade; der Monatsbericht weist Ausfallzeiten und Wartungsfenster getrennt aus (Zielwert 99,9 % ist ein vorläufiges Lastenheftziel, D-05).
- **Alarmregeln:** [infra/observability/alert-rules.d/kritische-szenarien.yml](../../infra/observability/alert-rules.d/kritische-szenarien.yml); der Katalog [infra/alerting/katalog.yaml](../../infra/alerting/katalog.yaml) ist maßgeblich für Schwellen, Empfänger und Eskalation.

## 6. Alarmierung und Eskalation

- **Ein Alarm je Fehler:** gleicher Alarmname und gleiche Komponente bilden eine Gruppe; kritische Alarme wiederholen stündlich, Warnungen alle 4 Stunden; nach Behebung folgt die Auflösungsmeldung.
- **Empfänger:** `betrieb-bereitschaft` (kritisch), `betrieb-plattform` (Warnung); Totmannprüfung `Watchdog` minütlich an einen unabhängigen Prozess (Ausfall nach 5 Minuten: `ALARMKETTE_AUSGEFALLEN`).
- **Eskalationsstufen:** Stufe 0 Bereitschaft (sofort), **Stufe 1 Betriebsleitung nach der im Katalog genannten Zeit** (kritisch 15 bis 60 Minuten, Warnung 60 bis 240 Minuten, je Alarm im Abschnitt 7), Stufe 2 Auftraggeber/Entwicklungsteam nur auf Entscheidung der Betriebsleitung. Kritische Ereignisse umgehen jede Eskalationsverzögerung (`alert_escalation_delay_min` gilt nur für nicht-kritische fachliche Alarme).
- **Erreichbarkeiten:** Namen, Rufnummern und Bereitschaftsplan **liefert der Betreiber** (D-11); Platzhalter: `<Bereitschaft Betrieb>`, `<Betriebsleitung>`, `<Finanzen>`, `<Entwicklungsteam, nur Stufe 2>`. Dieses Handbuch enthält keine personenbezogenen Kontaktdaten.
- **Quittieren und Stummschalten:** nur Betriebsrollen, je Alarm genau einmal wirksam (`python infra/alerting/alertops.py acknowledge`); Stummschaltung höchstens 8 Stunden mit Pflichtkommentar (`alertops.py silence`); jede Änderung steht im Audit-Protokoll.
- **Supportzeiten:** Zeiten und Reaktionszeiten stehen unter D-05 aus; bis dahin gilt „bestmöglich im Pilotbetrieb“.

## 7. Runbooks je Alarm

**Vorlage** (jeder Alarm des Katalogs hat genau einen Abschnitt mit diesen Feldern): *Auslöser* (aus dem Katalog), *Rolle*, *Diagnose*, *Sofortmaßnahme*, *Eskalation*, *Nacharbeit*. Der Vollständigkeitsabgleich (`tools/check_betriebshandbuch.py`) schlägt an, wenn ein Alarm des Katalogs hier fehlt oder ein Feld fehlt. Die kurzen ersten Handlungsschritte stehen zusätzlich in [alarm-handlungsschritte.md](alarm-handlungsschritte.md).


<a id="rb-kernbuchung_ausfall"></a>
### KERNBUCHUNG_AUSFALL: Ausfall der Kernbuchung

- **Auslöser:** Fehleranteil booking > 50 % über 5 min (mindestens 0,1 Anfragen/s) oder Fachkern 2 min nicht erreichbar (Signal: Fehleranteil 5xx der Endpunktklasse booking, bzw. Scrape des Fachkerns nicht erreichbar; Komponente `fachkern`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `KernbuchungAusfall`, `FachkernNichtErreichbar`)
- **Rolle:** Bereitschaft Betrieb (betrieb-bereitschaft)
- **Diagnose:** Verwaltungsport `/actuator/health` des Fachkerns und Datenbankverbindung prüfen (`pg_up`, `hikaricp_connections_active/pending`); Fehlerklasse im Dashboard `taktovia-kern` (Datenbankpool, Lock-Wartezeit, Fremddienst) bestimmen; Korrelations-ID eines fehlgeschlagenen Aufrufs im Trace nachverfolgen (maskierte Logs).
- **Sofortmaßnahme:** Ursache an der Abhängigkeit beheben (Datenbank, Messaging, PSP: siehe jeweilige Runbooks). Neustart des Fachkerns nur bei erschöpftem Pool und bekannter Ursache. Keine Datenreparatur. Nutzerwirkung: Buchungen liefern `503` mit `Retry-After`; es entsteht nie eine Zusage ohne Commit.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 15 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Nach Wiederanlauf Klärfälle prüfen (`PSP_UNBESTIMMT`, überfällige Operationen), Ausfallzeit im Monatsbericht der Verfügbarkeit vermerken, Ursache im Vorfallsprotokoll festhalten.

<a id="rb-datenbank_nicht_erreichbar"></a>
### DATENBANK_NICHT_ERREICHBAR: Datenbank nicht erreichbar

- **Auslöser:** pg_up = 0 über 1 min (Signal: postgres_exporter: pg_up; Komponente `postgres`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `DatenbankNichtErreichbar`)
- **Rolle:** Bereitschaft Betrieb; Promotion eines Standby nur mit Freigabe der Betriebsleitung
- **Diagnose:** Erreichbarkeit des Primärknotens und Verbindungsgrenzen prüfen (`pg_up`, `hikaricp_connections_*`); Plattenplatz, Speicher, `pg_stat_activity`; Replikationsstand des Standby (`DATENBANK_REPLIKATIONSVERZUG`).
- **Sofortmaßnahme:** Absturz: Wiederanlauf nach Wiederanlaufdrehbuch ([tests/performance/wiederanlauf-drehbuch-m5.md](../../tests/performance/wiederanlauf-drehbuch-m5.md)). Der Fachkern antwortet während des Ausfalls fail-closed mit `503`, nie mit Daten ohne Mandantenkontext; nach Rückkehr bauen sich die Verbindungen selbst auf (kein Neustart nötig, Nachweis Fault-Injection WP-M5-16).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 15 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** RPO/RTO der Störung festhalten, Abgleichbericht der Zahlungen im Verlustfenster auswerten, Übung bei Anpassungen wiederholen.

<a id="rb-datenbank_replikationsverzug"></a>
### DATENBANK_REPLIKATIONSVERZUG: Replikationsverzug der Datenbank

- **Auslöser:** Verzug > 30 s über 5 min (Signal: postgres_exporter: pg_replication_lag_seconds (Standby gegenüber Primär); Komponente `postgres`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `DatenbankReplikationsverzug`)
- **Rolle:** Betrieb Plattform (betrieb-plattform)
- **Diagnose:** Replikationsverzug und WAL-Erzeugung am Primärknoten (`pg_stat_replication`), Netzwerk zum Standby, Last auf dem Standby.
- **Sofortmaßnahme:** Ursache beseitigen (Netz, Platte, Last); kein Eingriff in laufende Buchungen. Bleibt der Verzug über die RPO-Grenze (15 min, vorläufig D-05), ist das ein kritischer Befund für die Betriebsleitung.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Verlauf des Verzugs im Dashboard dokumentieren; bei wiederholtem Auftreten Kapazität der Replikation prüfen.

<a id="rb-outbox_stau"></a>
### OUTBOX_STAU: Stau in der Outbox

- **Auslöser:** Tiefe > 1000 oder ältester unveröffentlichter Eintrag > 300 s, jeweils über 5 min (Signal: taktovia_outbox_depth, taktovia_outbox_oldest_age_seconds; Komponente `outbox-relay`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `OutboxStau`)
- **Rolle:** Bereitschaft Betrieb
- **Diagnose:** Alter des ältesten unveröffentlichten Eintrags (`outbox_oldest_unpublished_age_seconds`), Fehler des Relay, Zustand von RabbitMQ (`MESSAGING_NICHT_ERREICHBAR`), Dead-Letter-Zähler.
- **Sofortmaßnahme:** Messaging bzw. Relay wiederherstellen; die Outbox hält alle Ereignisse zurück, es geht keine Geschäftswirkung verloren. Nach Wiederanlauf den Abbau beobachten: jedes Ereignis wird mindestens einmal zugestellt, Verbraucher deduplizieren über die Inbox.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 30 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Dead-Letter-Einträge prüfen (je Eintrag Ursache klären, danach gezielt erneut einreihen durch Betriebsrolle); Staudauer notieren.

<a id="rb-messaging_nicht_erreichbar"></a>
### MESSAGING_NICHT_ERREICHBAR: Messaging nicht erreichbar

- **Auslöser:** nicht erreichbar über 2 min (Signal: Scrape von RabbitMQ (up); Komponente `rabbitmq`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `MessagingNichtErreichbar`)
- **Rolle:** Bereitschaft Betrieb
- **Diagnose:** RabbitMQ-Knoten, Speicher- und Plattenalarm, Netz prüfen; Publisher-Confirms und Verbindungsfehler im Relay-Log.
- **Sofortmaßnahme:** Broker wiederherstellen. Der Fachkern bucht weiter (Transaktions-Outbox); Folgeprozesse (Benachrichtigung, Kennzahlen) laufen verzögert nach. Nach Rückkehr gleicht sich die Outbox selbst ab (Nachweis WP-M5-16).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 15 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Reihenfolge und Doppelzustellung sind durch die Inbox gedeckt; Stichprobe der Kennzahlen-Datenstände prüfen.

<a id="rb-routing_worker_ausfall"></a>
### ROUTING_WORKER_AUSFALL: Routing-Worker ohne Antwort

- **Auslöser:** nicht erreichbar über 2 min (Signal: Scrape des Routing-Workers (up); Komponente `routing-worker`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `RoutingWorkerNichtErreichbar`)
- **Rolle:** Bereitschaft Betrieb
- **Diagnose:** Gesundheit und Antwortzeit des Routing-Workers, Version der Kartendaten, Fehlercodes `ROUTING_UNAVAILABLE`/`ROUTING_TIMEOUT` in den Angebotsanfragen.
- **Sofortmaßnahme:** Worker neu starten bzw. auf die letzte validierte Kartenversion zurückstellen. Nutzerwirkung: neue Angebote werden begründet abgelehnt oder aus validiertem Cache erzeugt; bestehende Fahrtenpläne bleiben abrufbar.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 30 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Anteil abgelehnter Anfragen im Zeitraum auswerten (`Kapazitätsablehnungen`/technische Ablehnungen), Kartenstand dokumentieren.

<a id="rb-solver_timeouts"></a>
### SOLVER_TIMEOUTS: Solver-Timeouts über Schwelle

- **Auslöser:** Timeoutanteil > 20 % über 10 min (mindestens 0,05 Anfragen/s) (Signal: taktovia_routing_timeouts_total gegen taktovia_routing_requests_total; Komponente `routing-worker`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `SolverTimeoutsHoch`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** Anteil der Solver-Timeouts (`TIMEOUT_NO_VALIDATED_PLAN`), Last des Routing-Workers, Größe der Flotte und der Pools.
- **Sofortmaßnahme:** Lastspitze abwarten oder Worker skalieren; ein Timeout liefert nie einen ungeprüften Plan, sondern eine begründete Ablehnung.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Budgetparameter und Poolgrößen mit der fachlichen Verantwortung abstimmen.

<a id="rb-psp_unbestimmt"></a>
### PSP_UNBESTIMMT: PSP nicht erreichbar bzw. Zahlung länger als Schwelle unbestimmt

- **Auslöser:** mindestens eine unbestimmte payment_intent über 15 min (Näherung: die Metrik zählt, sie trägt kein Alter; eine direkte PSP-Probe liefern die synthetischen Proben, WP-M5-05) (Signal: taktovia_payment_intent_indeterminate (CAPTURE_UNKNOWN, RECONCILING); Komponente `fachkern`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `ZahlungUnbestimmt`)
- **Rolle:** Bereitschaft Betrieb; Klärung offener Zahlungen: Rolle Finanzen (finance)
- **Diagnose:** Anzahl und Alter unbestimmter Zahlungen (`CAPTURE_UNKNOWN`, `RECONCILING`), Erreichbarkeit des PSP, Abgleichsoperationen (`GET /v1/operations/{id}`).
- **Sofortmaßnahme:** Nichts doppelt belasten: der Abgleich läuft über die Statusabfrage mit derselben Händlerreferenz, nie über eine Neubuchung. Zahlungsstatus nie von Hand ändern. Nutzerwirkung: „Ergebnis wird geprüft“.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 30 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Klärfälle der Zahlungen (siehe Abschnitt Klärfälle) vollständig abarbeiten; Abgleichbericht ablegen.

<a id="rb-operation_lease_ueberfaellig"></a>
### OPERATION_LEASE_UEBERFAELLIG: Überfällige operation-Leases

- **Auslöser:** mindestens eine überfällige Lease über 5 min (Signal: taktovia_operation_lease_overdue; Komponente `fachkern`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `OperationLeaseUeberfaellig`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** Operationen mit abgelaufener Lease (`operation.status = RUNNING`, `lease_until` in der Vergangenheit), Zustand der Worker-Instanzen.
- **Sofortmaßnahme:** Ausgefallene Worker neu starten: die Operation wird über Lease und Fencing von einer Instanz wieder aufgenommen; Wiederholung ist wirkungslos, wenn die Wirkung schon eintrat.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Operationen in `MANUAL_REVIEW` über das Klärfallverfahren bearbeiten.

<a id="rb-redis_ausfall"></a>
### REDIS_AUSFALL: Redis-Ausfall

- **Auslöser:** redis_up = 0 über 2 min (Signal: redis_exporter: redis_up (Redis ist nur Cache und Rate-Limit, Leitlinie 4); Komponente `redis`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `RedisAusfall`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** `redis_up`, Speicher, Verbindungen. Redis ist nur Cache und Rate-Limit; Wahrheit liegt in PostgreSQL.
- **Sofortmaßnahme:** Redis neu starten; Rate-Limit-Zähler bauen sich neu auf. Kapazität, Preis und Zahlungszustand bleiben unverändert; Sitzungssperren entscheidet die Datenbank (fail-closed, nie fail-open).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Datenverlust im Cache ist unkritisch; Ursache und Dauer im Vorfallsprotokoll vermerken.

<a id="rb-zertifikat_laeuft_ab"></a>
### ZERTIFIKAT_LAEUFT_AB: Ablaufende Zertifikate

- **Auslöser:** Rest < 14 Tage (Warnung), < 3 Tage (kritisch) (Signal: blackbox_exporter: probe_ssl_earliest_cert_expiry (Gateway-Endpunkt); Komponente `gateway`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `ZertifikatLaeuftAb`, `ZertifikatLaeuftBaldAb`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** Restlaufzeit der Zertifikate (Gateway, Dienste, Datenbank, Broker).
- **Sofortmaßnahme:** Erneuerung in der Secret-Verwaltung der Umgebung auslösen; Rotation ohne Neustart (Referenz bleibt, Wert wechselt).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 240 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Ablaufkalender aktualisieren.

<a id="rb-fehlerrate_endpunktklasse"></a>
### FEHLERRATE_ENDPUNKTKLASSE: Fehlerratenanstieg je Endpunktklasse

- **Auslöser:** Fehleranteil 5xx > 5 % über 10 min je Klasse (mindestens 0,1 Anfragen/s) (Signal: taktovia_http_server_request_seconds_count je route_class und status_class; Komponente `fachkern`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `FehlerrateEndpunktklasse`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** Dashboard `taktovia-kern`: Fehlerrate je Endpunktklasse, Fehlercodes, Release-Stand, zeitlicher Zusammenhang mit Deployments.
- **Sofortmaßnahme:** Ursache an der betroffenen Klasse suchen (Abhängigkeit, Deployment); bei einem Deployment als Ursache Vorwärtskorrektur nach Freigabe der Betriebsleitung (kein Löschen bestätigter Buchungen).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Ergebnis in der Release-Nachbetrachtung festhalten.

<a id="rb-gateway_nicht_erreichbar"></a>
### GATEWAY_NICHT_ERREICHBAR: Gateway nicht erreichbar

- **Auslöser:** Probe fehlgeschlagen über 2 min (Signal: blackbox_exporter: probe_success (Gateway-Endpunkt); Komponente `gateway`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `GatewayNichtErreichbar`)
- **Rolle:** Bereitschaft Betrieb
- **Diagnose:** Gateway-Prozess, Netzwerk, Zertifikat, Gesundheitsprobe von außen (synthetische Probe).
- **Sofortmaßnahme:** Gateway neu starten bzw. auf die zweite Instanz umschalten; Ursache an Netz oder Zertifikat beheben.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 15 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Ausfallzeit im Monatsbericht der Verfügbarkeit ausweisen.

<a id="rb-dokumenten_worker_ausfall"></a>
### DOKUMENTEN_WORKER_AUSFALL: Dokumenten-Worker ausgefallen oder Aufgaben scheitern

- **Auslöser:** nicht erreichbar über 5 min, bzw. Fehleranteil der Aufgaben > 30 % über 15 min (Signal: Scrape (up), taktovia_documents_tasks_total{outcome}; Komponente `dokumenten-worker`, Schweregrad **warning**, Empfänger `betrieb-plattform`, Regeln: `DokumentenWorkerNichtErreichbar`, `DokumentenAufgabenScheitern`)
- **Rolle:** Betrieb Plattform
- **Diagnose:** Gesundheit des Dokumenten-Workers, Warteschlange der Uploads, Fehlerzähler der Aufgaben.
- **Sofortmaßnahme:** Worker neu starten. Solange der Worker steht, bleiben Uploads in Quarantäne: es wird kein ungeprüfter Upload ausgeliefert.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 120 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Aufgaben in Fehlerstatus erneut einreihen, wenn die Ursache behoben ist.

<a id="rb-alarmkette_ausgefallen"></a>
### ALARMKETTE_AUSGEFALLEN: Alarmierungskette selbst ausgefallen

- **Auslöser:** kein Herzschlag der Totmannprüfung binnen 5 min bzw. Zustellfehler > 0 über 5 min (Signal: Watchdog (immer feuernd) als Herzschlag an die unabhängige Totmannprüfung; Fehlerzähler von Alertmanager und Prometheus; Komponente `alarmkette`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `Watchdog`, `AlertmanagerZustellungFehlgeschlagen`, `PrometheusRegelauswertungFehlgeschlagen`)
- **Rolle:** Betriebsleitung (unabhängiger Meldeweg)
- **Diagnose:** Herzschlag `Watchdog` des Alertmanagers beim unabhängigen Prozess (`infra/alerting/deadman.py`) und dessen Meldeweg.
- **Sofortmaßnahme:** Alertmanager und Meldekanäle wiederherstellen; bis dahin die Überwachung manuell über das Dashboard führen (Wache alle 15 Minuten).
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 15 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Lücke in der Alarmierung im Vorfallsprotokoll benennen; Übung der Alarmkette wiederholen.

<a id="rb-backup_fehlgeschlagen"></a>
### BACKUP_FEHLGESCHLAGEN: Fehlgeschlagenes Backup

- **Auslöser:** letzter Backuplauf fehlgeschlagen über 1 min bzw. letztes erfolgreiches Backup älter als 36 h (Planungswert, OP-31/D-05) (Signal: taktovia_backup_last_status (0 = letzter Lauf fehlgeschlagen) und taktovia_backup_last_success_timestamp_seconds (infra/backup/taktovia_backup/metrics.py); Komponente `postgres`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `BackupFehlgeschlagen`, `BackupZuAlt`)
- **Rolle:** Bereitschaft Betrieb (Schreibrolle `ops-backup`)
- **Diagnose:** Lauf des Dienstes `taktovia-backup` und das Sicherungsprotokoll prüfen; Ursache sind meist Schlüsseldatei, Backupspeicher (Platz) oder Datenbankverbindung.
- **Sofortmaßnahme:** Backup nach Behebung von Hand starten (`python -m taktovia_backup backup --database <db>`), danach `verify`. Solange kein gültiges Backup besteht, keine riskanten Änderungen an der Datenbank.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Wiederherstellbarkeit im nächsten Restore-Test bestätigen.

<a id="rb-archivierungs_replikationsverzug"></a>
### ARCHIVIERUNGS_REPLIKATIONSVERZUG: Archivierungs-/Replikationsverzug (WAL)

- **Auslöser:** Archivierungsverzug > 10 min über 2 min (Vorwarnung vor der RPO-Grenze 15 min); fehlgeschlagene Archivierung (Zuwachs in 10 min); erwarteter Standby nicht verbunden über 5 min (Signal: taktovia_wal_archive_lag_seconds, taktovia_wal_archive_failed_total, taktovia_standby_connected (infra/backup/taktovia_backup/metrics.py aus pg_stat_archiver und pg_stat_replication); Komponente `postgres`, Schweregrad **critical**, Empfänger `betrieb-bereitschaft`, Regeln: `WalArchivierungsverzug`, `WalArchivierungFehlgeschlagen`, `StandbyGetrennt`)
- **Rolle:** Bereitschaft Betrieb
- **Diagnose:** WAL-Archivierung (`archive_command`, Archivspeicher), Archivierungsverzug und Replikationsverzug.
- **Sofortmaßnahme:** Archivspeicher und Netz wiederherstellen, damit PITR (RPO ≤ 15 min, vorläufig) erhalten bleibt; WAL nicht löschen, bevor es archiviert ist.
- **Eskalation:** Stufe 1 an die Betriebsleitung, wenn nach 60 Minuten keine Ursache feststeht oder die Sofortmaßnahme wirkungslos bleibt.
- **Nacharbeit:** Lücken der WAL-Kette prüfen (`verify`), bei Lücke neues Basisbackup auslösen.

## 8. Klärfälle

Klärfälle sind Zustände, die die Plattform bewusst nicht entscheidet. Sie sind sichtbar und werden nie als Erfolg oder Fehler verkürzt. Der Betrieb arbeitet sie **über die Anwendung** ab, nie per SQL.

| Klärfall | Erkennung | Vorgehen (Rolle) | Frist (vorläufig) |
|---|---|---|---|
| **Unbestimmte Zahlung** (`CAPTURE_UNKNOWN`, `RECONCILING`) | Alarm `PSP_UNBESTIMMT`; Aufräumprüfung `tests/acceptance/e2e/aufraeumen.py` (Klärfälle werden getrennt ausgewiesen) | Abgleich läuft über die Statusabfrage beim PSP (Operation `RECONCILE_PAYMENT`); bleibt er ergebnislos, Klärung durch Finanzen anhand des PSP-Berichts; **keine zweite Belastung, keine Neubuchung mit neuer Referenz** | Abgleich automatisch, Eskalation nach 30 Minuten |
| **Überfällige Operation** (Lease abgelaufen) | Alarm `OPERATION_LEASE_UEBERFAELLIG` | Worker neu starten; Wiederaufnahme über Lease und Fencing (Betrieb Plattform) | 60 Minuten |
| **Operation in `MANUAL_REVIEW`** | Betriebsansicht der Operationen (`GET /v1/operations/{id}`) | fachliche Prüfung des Falls durch Leitstelle bzw. Finanzen, Entscheidung über die Anwendung dokumentieren | Folgetag |
| **Fahrerereignis `REVIEW_REQUIRED`** (veralteter Plan, falscher Stopp, Uhrabweichung, Widerspruch zu Storno) | Arbeitsliste der Leitstelle („Offene Störungen“) | Leitstelle klärt mit dem Fahrer, das Ereignis bleibt als Beobachtung erhalten (kein Zustandsrücksprung) | Schichtende |
| **Buchung im Störungsablauf** (`OPEN_ISSUE`) | Leitstellenansicht Störungen (UI-07) | Ersatzoptionen prüfen, Umbuchung oder Betreiberstorno, Fahrgast informieren (Leitstelle) | je Fahrt vor Abholung |

## 9. Backup, Restore und Wiederanlauf

- **Backup:** Basisbackup und laufende WAL-Archivierung (`infra/backup`), Verifikation nach jedem Lauf, Aufbewahrung mit Object Lock; RPO ≤ 15 min und RTO ≤ 2 h sind **vorläufige** Zielwerte (D-05).
- **Wiederanlauf:** [wiederanlauf-drehbuch-m5.md](../../tests/performance/wiederanlauf-drehbuch-m5.md) (Schritte, Rollen, Zeitstempel); Restore-Test: [restore-drehbuch-m1.md](../../tests/performance/restore-drehbuch-m1.md). Nach der Wiederherstellung: Konsistenzprüfungen, Lösch- und Sperrjournal, Abgleich der Zahlungen im Verlustfenster, Referenzfall als End-to-End-Probe.
- **Ausfallübung:** `tests/acceptance/m5_at23` (AT-23) führt die Übung mit RPO-/RTO-Messung aus; das Protokoll liegt unter `tests/performance/protokolle/`.

## 10. Wiederkehrende Übungen und Wartungsfenster

| Übung | Rhythmus (vorläufig) | Rolle | Nachweis |
|---|---|---|---|
| Restore aus Backup bis zu einem Zeitpunkt | vierteljährlich | Bereitschaft Betrieb | Übungsprotokoll ([uebungsprotokoll-vorlage.md](uebungsprotokoll-vorlage.md)) |
| Ausfallübung Datenbank mit RPO/RTO | halbjährlich und nach Änderung der Datenbankarchitektur | Betrieb mit Betriebsleitung | AT-23-Evidenz |
| Alarmübung je Schweregrad (kritisch, Warnung) | halbjährlich | Bereitschaft Betrieb, Betrieb Plattform | Übungsprotokoll |
| Fault-Injection-Kampagne (Abhängigkeiten) | vor jedem Release mit Änderung an Zahlung, Messaging, Routing | Betrieb Plattform | `tests/acceptance/test_fault_injection_m5.py` |
| Sicherheitsvorfall-Übung | jährlich | Betriebsleitung | [uebungsdrehbuch-sicherheitsvorfall.md](uebungsdrehbuch-sicherheitsvorfall.md) |
| Notbetrieb-Tischübung | jährlich | Betriebsleitung, Leitstelle | Protokoll (Abschnitt 11) |
| Zertifikats- und Schlüsselrotation | laut Ablaufkalender | Betrieb Plattform | Secret-Verwaltung |

**Wartungsfenster:** werden vom Betreiber festgelegt (D-05) und vorab angekündigt; Wartung ist im Monatsbericht der Verfügbarkeit getrennt ausgewiesen. Datenbankmigrationen im Fenster nur mit expand/contract (Abschnitt 4).

## 11. Notbetrieb

**Entscheidungsrecht:** Die Betriebsleitung des Betreibers entscheidet über den Übergang in den Notbetrieb und zurück (Freigaberollen und Kontaktprozess **liefert der Betreiber**, D-11, blockiert die Produktivschaltung). Jede Entscheidung wird mit Zeit, Entscheider und Grund protokolliert.

**Was die Driver-App offline darf und was serverpflichtig bleibt** (Kap. 01 §1.5):

| Offline erlaubt | Immer serverpflichtig |
|---|---|
| zuletzt bestätigter Dienst und Stoppliste mit Stand ansehen | neue verbindliche Buchung |
| Beleg, Schaden, Zeitereignis erfassen (lokale Queue, jedes Ereignis wirkt nach der Synchronisation genau einmal) | Fahrzeugfreigabe |
| Check-in, Ausstieg, No-Show als Ereignis erfassen (Gerätezeit ist nie Serverzeit, ein Widerspruch zum Server-Storno wird Klärfall) | Aufhebung einer Sperre |

### 11.1 Ausfall der Kernbuchung

- **Auslöser:** `KERNBUCHUNG_AUSFALL`, Buchungen liefern `503`.
- **Vorgehen (Rolle):** Betriebsleitung erklärt den Notbetrieb; Leitstelle nimmt Fahrtwünsche telefonisch auf und notiert sie auf dem Notbetriebsblatt (Zeit, Personenzahl, Start, Ziel, Rückrufweg); **es werden keine verbindlichen Zusagen gegeben**, solange der Fachkern nicht bestätigt hat. Bereits bestätigte Fahrten der Fahrer laufen nach der zuletzt bestätigten Stoppliste weiter.
- **Rückkehr:** Notiz-Buchungen werden nach Wiederanlauf regulär nachgebucht (Leitstelle, Buchung anlegen mit Begründung); Doppelungen verhindert der Idempotenzschlüssel; Fahrgäste werden informiert.

### 11.2 Ausfall der Driver-App-Verbindung

- **Auslöser:** Fahrer-App ohne Verbindung (Netz, Server) oder Alarm der Fahrerereignisse.
- **Vorgehen:** Fahrer arbeiten mit der zuletzt bestätigten Stoppliste weiter und erfassen Ereignisse offline; die Leitstelle fragt Positionen und Fortschritt per Telefon ab. Keine Fahrzeugfreigabe und keine Sperraufhebung ohne Serververbindung.
- **Rückkehr:** Synchronisation: jedes Ereignis wirkt einmal; Widersprüche (z. B. Check-in nach Server-Storno) landen als `REVIEW_REQUIRED` bei der Leitstelle.

### 11.3 Ausfall der Leitstellenoberfläche

- **Auslöser:** Leitstellenoberfläche (UI-07) nicht erreichbar, Fachkern aber im Betrieb.
- **Vorgehen:** Der Fahrbetrieb läuft über die Driver-App weiter (Pläne sind serverseitig). Die Leitstelle nutzt die Rückfallverbindung über die Fahrer-Telefone und das Notbetriebsblatt; Dispositionsentscheidungen (Umbuchung, Storno) werden **erst nach Wiederkehr** über die Anwendung gebucht. Kritische Störungen (Fahrzeugausfall) werden telefonisch an betroffene Fahrer und Fahrgäste gemeldet.
- **Rückkehr:** Notizen werden mit Begründung nachgetragen; der Störungsablauf wird für offene Ausfälle neu geprüft.

### 11.4 Ausfall des PSP

- **Auslöser:** `PSP_UNBESTIMMT`, Autorisierungen laufen in Timeout.
- **Vorgehen:** Fahrgäste sehen „Ergebnis wird geprüft“. Die Betriebsleitung entscheidet (D-11/D-03), ob Buchungen mit Zahlung vorübergehend gesperrt werden; **es wird nie von Hand belastet oder ein Zahlstatus gesetzt**. Fahrten bestätigter Buchungen werden durchgeführt; der Einzug folgt nach Wiederkehr.
- **Rückkehr:** Abgleich (Statusabfrage mit derselben Händlerreferenz) klärt jede unbestimmte Zahlung; Finanzen prüft den Abgleichbericht (Abschnitt 8).

**Tischübung:** mit dem Betreiber **noch nicht durchgeführt**; das Protokoll ist nach der Vorlage [uebungsprotokoll-vorlage.md](uebungsprotokoll-vorlage.md) zu führen (Abschnitt 14).

## 12. Betriebsrelevante Parameter

Die Werte sind im Parameterkatalog (Kap. 01 §1.6, `docs/pflichtenheft/kataloge.csv` J.7) benannt; **freigegebene Produktionswerte stehen aus** (D-05, fachliche Freigabe). Änderungen laufen über Konfigurationsrevisionen (Entwurf, Prüfung, Freigabe, Veröffentlichung, Vier-Augen) in der Anwendung.

| Parameter | Ebene | Wirkung im Betrieb |
|---|---|---|
| `alert_escalation_delay_min` | Alerttyp | Minuten, nach denen ein **nicht-kritischer** fachlicher Alarm zur Eskalation weitergereicht wird. Kritische Ereignisse (Fahrzeugausfall, sicherheitskritische Schäden) umgehen die Verzögerung immer; ein hoher Wert verlängert die Zeit bis zur Eskalation nur für Warnungen. Technische Alarme dieses Handbuchs eskalieren nach den Zeiten des Alarmkatalogs, nicht nach diesem Parameter. |
| `vehicle_unavailability_alert_min` | Dienst | Minuten, nach denen ein Fahrzeug mit offener Sperre als „langer Ausfall“ gemeldet wird (Live-Dashboard, Störungsablauf). Ein kleiner Wert meldet früher, erzeugt aber mehr Meldungen. |
| `notification_quiet_hours` | Mandant | Ruhezeiten (`from`/`to`, Ortszeit) für Nachrichten an Fahrgäste und Personal; **Sicherheitsalarme sind ausgenommen** und gehen immer sofort hinaus. |
| `daily_report_time` | Mandant | Uhrzeit (Zeitzone des Mandanten) des täglichen Berichts; die Zeitzone des Mandanten bestimmt auch den Betriebstag der Kennzahlen. |

## 13. Erkenntnisse aus WP-M5-15 und WP-M5-16

| Quelle | Erkenntnis | Wirkung im Betrieb |
|---|---|---|
| WP-M5-15 (Ausfallübung, AT-23) | Wiederherstellung nur aus Basisbackup und archiviertem WAL; Verlustfenster nicht leer, deshalb RPO aussagekräftig; Zahlungsabgleich im Verlustfenster klärt jeden Vorgang ohne zweite Belastung | Abschnitt 9, Klärfälle (Abschnitt 8) |
| WP-M5-16 (Fault-Injection) | Datenbank-Abriss und -Ausfall: fail-closed (`503`), Wiederkehr ohne Neustart, kein Mandantenkontext einer früheren Verbindung | Runbooks `DATENBANK_NICHT_ERREICHBAR`, `KERNBUCHUNG_AUSFALL` |
| WP-M5-16 | Absturz des Fachkerns zwischen PSP-Aufruf und lokalem Commit: nach Neustart findet der Abgleich die Autorisierung, genau eine Belastung | Klärfälle, Runbook `PSP_UNBESTIMMT` |
| WP-M5-16 | PSP-Timeout und verlorene Antwort: derselbe Schlüssel und dieselbe Händlerreferenz, Statusabfrage, keine zweite Belastung | `PSP_UNBESTIMMT`, Notbetrieb PSP |
| WP-M5-16 | Messaging-Unterbrechung: Buchung läuft weiter, Outbox hält zurück, nach Wiederkehr jedes Ereignis genau einmal wirksam | `OUTBOX_STAU`, `MESSAGING_NICHT_ERREICHBAR` |
| WP-M5-16 | Redis ist in dieser Version nicht angebunden (Dauerzustand ohne Redis): Hold, Bestätigung und Zahlung korrekt | `REDIS_AUSFALL` |
| WP-M5-24 | Das Nicht-Erscheinen gibt jetzt Hold und Plan frei (zuvor blieb der Hold bestehen) | Aufräumprüfung nach jedem Szenario |
| WP-M5-24 | Erstattungen nach Storno hatten keinen Operationshandler: sie endeten als `MANUAL_REVIEW` (`NO_HANDLER`), die Zahlung blieb `REFUND_PENDING`; der Handler ist jetzt verdrahtet, Erstattung endet in `REFUNDED` | Klärfälle (`MANUAL_REVIEW`), Runbook `PSP_UNBESTIMMT` |
| WP-M5-16 | Verbindungsfehler in Filtern (Datenbank weg, Pool erschöpft) lieferten `500` ohne Problem-Body; jetzt `503` mit `Retry-After` und Problem-Body, nie eine Antwort mit Daten | Runbooks `DATENBANK_NICHT_ERREICHBAR`, `KERNBUCHUNG_AUSFALL`; Clients wiederholen mit demselben Idempotenzschlüssel |
| WP-M5-23 | `booking.confirmed` trug keine `offer_id`: die Kennzahl `Buchungskonversion` blieb vorläufig; das Feld wird jetzt gesetzt | Kennzahlen im Monatsbericht |

## 14. Nachweisstand und offene Punkte

- **Nicht ausgeführt, nicht belegt:** (1) Übung je Schweregrad durch Betriebspersonal **ohne** Entwicklerhilfe (Akzeptanzkriterium 1): es gibt hier kein Betriebspersonal; die Übung ist nach [uebungsprotokoll-vorlage.md](uebungsprotokoll-vorlage.md) mit dem Betreiber durchzuführen und zu protokollieren. (2) Tischübung des Notbetriebs mit dem Betreiber (Akzeptanzkriterium 2). (3) Abgleich des Kapitels Deployment gegen die Produktionsumgebung (WP-M5-29).
- **Belegt:** Alarmkatalog und Runbooks sind vollständig abgeglichen (`tools/check_betriebshandbuch.py`, Test `tests/acceptance/test_betriebshandbuch.py`); die Szenarien der Erkenntnisse aus Abschnitt 13 laufen in `tests/acceptance` gegen die echte Anwendung in einer Ersatzumgebung (kein Staging).
- **Vom Betreiber zu liefern (D-11, D-05):** Kontaktprozess und Erreichbarkeiten, Freigaberollen für den Notbetrieb, Supportzeiten, Wartungsfenster, Hosting der Produktion.
