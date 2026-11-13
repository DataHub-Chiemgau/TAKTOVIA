# Alarme und erste Handlungsschritte (WP-M5-04)

Jedes kritische Fehlerszenario aus [infra/alerting/katalog.yaml](../../infra/alerting/katalog.yaml) hat hier einen ersten Handlungsschritt (Runbook-Verweis). Das vollständige Betriebshandbuch (Monitoring, Eskalation, Notbetrieb) liefert WP-M5-26; dieser Abschnitt ist sein Vorläufer und wird dort übernommen.

**Status:** Vorschlag. Empfänger und Rufbereitschaft sind organisatorisch offen (D-05, OP-07, OP-32/OP-39); die Rollen unten sind Rollen, keine Personen. Die Regeln liegen unter `infra/observability/alert-rules.d/`, die Zustellung über genau einen Kanal (`infra/alerting/alertmanager.yml`).

## Grundregeln

- **Ein Alarm je Fehler:** gleicher Alarmname und gleiche Komponente bilden eine Gruppe; kritisch wiederholt stündlich, Warnung alle 4 Stunden; nach Behebung kommt die Auflösungsmeldung.
- **Alarmtexte** enthalten keine Fahrgast- und keine Personaldaten; Etiketten tragen nur technische Namen. Details stehen in Logs und Traces (maskiert), über die Korrelations-ID erreichbar.
- **Stummschaltung** nur befristet (höchstens 8 Stunden) mit Pflichtkommentar durch eine Betriebsrolle (`infra/alerting/alertops.py silence`); der Alarm bleibt sichtbar und protokolliert, nur der Ruf entfällt.
- **Quittieren** nur Betriebsrollen, je Alarm genau einmal wirksam (`alertops.py acknowledge`); jede Änderung von Regeln, Stummschaltungen und Quittierungen steht im Audit-Protokoll.
- **Totmannprüfung:** Alertmanager sendet minütlich den Herzschlag `Watchdog` an einen unabhängigen Prozess (`infra/alerting/deadman.py`, anderer Host, eigener Meldeweg). Bleibt er 5 Minuten aus, gilt die Alarmkette als ausgefallen (ALARMKETTE_AUSGEFALLEN).
- **Eskalation:** unquittiert nach der im Katalog genannten Zeit an die Betriebsleitung (Stufe 1). Betrieblich relevante technische Alarme lassen sich als `TECH_*`-Alarm in die Arbeitsliste „Offene Störungen“ übernehmen (Port `BusinessAlertPort`, WP-M3-52); die Verdrahtung hängt an OP-07 (welcher Mandant, ob überhaupt).

## Szenarien

<a id="kernbuchung_ausfall"></a>
### KERNBUCHUNG_AUSFALL: Ausfall der Kernbuchung

- **Komponente:** fachkern · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** Fehleranteil booking > 50 % über 5 min (mindestens 0,1 Anfragen/s) oder Fachkern 2 min nicht erreichbar
- **Erster Handlungsschritt:** Prüfen, ob der Fachkern läuft (Verwaltungsport `/actuator/health`) und ob die Datenbank antwortet; Buchungsfehler im Dashboard `taktovia-kern` nach Fehlerklasse (Datenbankpool, Lock-Wartezeit, Fremddienst) aufschlüsseln. Bei Datenbank- oder Messagingausfall dort weitermachen. Neustart des Fachkerns nur, wenn der Pool erschöpft und die Ursache bekannt ist; vorher keine Datenreparatur.
- **Eskalation:** Stufe 1: Betriebsleitung, wenn nach 15 min keine Ursache feststeht.
- **Lieferung:** Regel aktiv

<a id="datenbank_nicht_erreichbar"></a>
### DATENBANK_NICHT_ERREICHBAR: Datenbank nicht erreichbar

- **Komponente:** postgres · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** pg_up = 0 über 1 min
- **Erster Handlungsschritt:** Erreichbarkeit des Primärknotens und der Verbindungsgrenzen prüfen (`pg_up`, `hikaricp_connections_*`); bei Absturz Wiederanlauf nach Betriebshandbuch (WP-M5-14, Wiederanlaufdrehbuch). Keine manuelle Promotion eines Standby ohne Freigabe der Betriebsleitung.
- **Eskalation:** Stufe 1 nach 15 min.
- **Lieferung:** Regel aktiv

<a id="datenbank_replikationsverzug"></a>
### DATENBANK_REPLIKATIONSVERZUG: Replikationsverzug der Datenbank

- **Komponente:** postgres · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** Verzug > 30 s über 5 min
- **Erster Handlungsschritt:** Verzug (`pg_replication_lag_seconds`) und Schreiblast prüfen; lange Transaktionen und Netzwerk zwischen Primär und Standby ansehen. Solange der Verzug wächst, keine Standby-Umschaltung.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv

<a id="outbox_stau"></a>
### OUTBOX_STAU: Stau in der Outbox

- **Komponente:** outbox-relay · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** Tiefe > 1000 oder ältester unveröffentlichter Eintrag > 300 s, jeweils über 5 min
- **Erster Handlungsschritt:** Dashboard `taktovia-infrastruktur`: Tiefe und Alter der Outbox je Mandant, Dead-Letter. Läuft der Relay (`taktovia_outbox_relay_events_total`)? Ist RabbitMQ erreichbar? Bei Messagingausfall dort beginnen; Einträge nie löschen, Zustellung wiederholt sich mit Backoff.
- **Eskalation:** Stufe 1 nach 30 min.
- **Lieferung:** Regel aktiv

<a id="messaging_nicht_erreichbar"></a>
### MESSAGING_NICHT_ERREICHBAR: Messaging nicht erreichbar

- **Komponente:** rabbitmq · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** nicht erreichbar über 2 min
- **Erster Handlungsschritt:** RabbitMQ-Knoten, Speicher- und Plattenalarm sowie Netz prüfen. Die Outbox hält Ereignisse zurück (keine Geschäftswirkung geht verloren); nach Wiederanlauf den Abbau der Outbox beobachten.
- **Eskalation:** Stufe 1 nach 15 min.
- **Lieferung:** Regel aktiv

<a id="routing_worker_ausfall"></a>
### ROUTING_WORKER_AUSFALL: Routing-Worker ohne Antwort

- **Komponente:** routing-worker · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** nicht erreichbar über 2 min
- **Erster Handlungsschritt:** Prozess und `/metrics` des Routing-Workers prüfen, Speicher und Solver-Queue ansehen. Der Fachkern fällt auf Fallback-Verhalten (Angebote ohne Routing werden abgelehnt oder aus dem Cache bedient); Worker neu starten.
- **Eskalation:** Stufe 1 nach 30 min.
- **Lieferung:** Regel aktiv

<a id="solver_timeouts"></a>
### SOLVER_TIMEOUTS: Solver-Timeouts über Schwelle

- **Komponente:** routing-worker · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** Timeoutanteil > 20 % über 10 min (mindestens 0,05 Anfragen/s)
- **Erster Handlungsschritt:** Anfragelast und Queue-Wartezeit (`taktovia_routing_queue_wait_seconds`) prüfen; Zeitbudget und Instanzgröße beachten. Kein Eingriff in laufende Pläne.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv

<a id="psp_unbestimmt"></a>
### PSP_UNBESTIMMT: PSP nicht erreichbar bzw. Zahlung länger als Schwelle unbestimmt

- **Komponente:** fachkern · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** mindestens eine unbestimmte payment_intent über 15 min (Näherung: die Metrik zählt, sie trägt kein Alter; eine direkte PSP-Probe liefern die synthetischen Proben, WP-M5-05)
- **Erster Handlungsschritt:** Zahlungen im Zustand `CAPTURE_UNKNOWN`/`RECONCILING` im Abgleich ansehen; PSP-Statusseite und Erreichbarkeit der Adapteranbindung prüfen. Unbestimmtes bleibt unbestimmt: nie manuell als Erfolg oder Fehler buchen, sondern den Abgleich laufen lassen oder an die Zahlungsverantwortlichen übergeben.
- **Eskalation:** Stufe 1 nach 30 min.
- **Lieferung:** Regel aktiv

<a id="operation_lease_ueberfaellig"></a>
### OPERATION_LEASE_UEBERFAELLIG: Überfällige operation-Leases

- **Komponente:** fachkern · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** mindestens eine überfällige Lease über 5 min
- **Erster Handlungsschritt:** Läuft der Operation-Runner (`taktovia.operations.worker-enabled`)? Überfällige Leases zeigen hängende Worker; nach Ablauf übernimmt ein anderer Worker mit höherem Fencing-Token. Anhaltende Überfälligkeit: Worker neu starten.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv

<a id="redis_ausfall"></a>
### REDIS_AUSFALL: Redis-Ausfall

- **Komponente:** redis · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** redis_up = 0 über 2 min
- **Erster Handlungsschritt:** Redis ist nur Cache und Rate-Limit: Kapazität und Buchungen bleiben in PostgreSQL korrekt; Sitzungssperren entscheidet die Datenbank. Redis neu starten, danach Rate-Limit-Zähler neu aufbauen lassen.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv

<a id="zertifikat_laeuft_ab"></a>
### ZERTIFIKAT_LAEUFT_AB: Ablaufende Zertifikate

- **Komponente:** gateway · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** Rest < 14 Tage (Warnung), < 3 Tage (kritisch)
- **Erster Handlungsschritt:** Ablaufdatum des Gateway-Zertifikats prüfen (`probe_ssl_earliest_cert_expiry`), Erneuerung über die Secret-Verwaltung anstoßen und Austausch im Gateway nachvollziehen (nie Zertifikate im Repository).
- **Eskalation:** Stufe 1 nach 4 h; unter 3 Tagen kritisch.
- **Lieferung:** Regel aktiv

<a id="fehlerrate_endpunktklasse"></a>
### FEHLERRATE_ENDPUNKTKLASSE: Fehlerratenanstieg je Endpunktklasse

- **Komponente:** fachkern · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** Fehleranteil 5xx > 5 % über 10 min je Klasse (mindestens 0,1 Anfragen/s)
- **Erster Handlungsschritt:** Betroffene Endpunktklasse (`route_class`) im Dashboard `taktovia-kern` ansehen; Fehlerklassen und Korrelations-ID aus dem Log verfolgen. Bei Klasse `booking` gilt KERNBUCHUNG_AUSFALL.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv

<a id="gateway_nicht_erreichbar"></a>
### GATEWAY_NICHT_ERREICHBAR: Gateway nicht erreichbar

- **Komponente:** gateway · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** Probe fehlgeschlagen über 2 min
- **Erster Handlungsschritt:** Probe von außen wiederholen, Gateway-Prozess, Zertifikat und Portfreigabe prüfen; Zugriffslog des Gateways ansehen (ohne Query und Kopfzeilen).
- **Eskalation:** Stufe 1 nach 15 min.
- **Lieferung:** Regel aktiv

<a id="dokumenten_worker_ausfall"></a>
### DOKUMENTEN_WORKER_AUSFALL: Dokumenten-Worker ausgefallen oder Aufgaben scheitern

- **Komponente:** dokumenten-worker · **Schweregrad:** warning · **Empfänger:** betrieb-plattform
- **Schwelle:** nicht erreichbar über 5 min, bzw. Fehleranteil der Aufgaben > 30 % über 15 min
- **Erster Handlungsschritt:** Prozess und `/metrics` prüfen; Fehlerarten der Aufgaben (`taktovia_documents_tasks_total{result}`) ansehen: Malware-Prüfer, OCR-Dienst, Speicher. Quarantäne bleibt fail-closed.
- **Eskalation:** Stufe 1 nach 120 min.
- **Lieferung:** Regel aktiv

<a id="alarmkette_ausgefallen"></a>
### ALARMKETTE_AUSGEFALLEN: Alarmierungskette selbst ausgefallen

- **Komponente:** alarmkette · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** kein Herzschlag der Totmannprüfung binnen 5 min bzw. Zustellfehler > 0 über 5 min
- **Erster Handlungsschritt:** Totmannprüfung meldet: kein Herzschlag der Kette. Prometheus, Alertmanager und den Zustellweg (Empfängerkanal) prüfen; bis zur Wiederherstellung die Dashboards manuell beobachten. Zustellfehler (`alertmanager_notifications_failed_total`) und Regelauswertungsfehler (`prometheus_rule_evaluation_failures_total`) zuerst ansehen.
- **Eskalation:** Stufe 1 nach 15 min.
- **Lieferung:** Regel aktiv

<a id="backup_fehlgeschlagen"></a>
### BACKUP_FEHLGESCHLAGEN: Fehlgeschlagenes Backup

- **Komponente:** postgres · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** letzter Backuplauf fehlgeschlagen über 1 min bzw. letztes erfolgreiches Backup älter als 36 h (Planungswert, OP-31/D-05)
- **Erster Handlungsschritt:** Lauf des Dienstes `taktovia-backup` und das Sicherungsprotokoll prüfen (`journalctl -u taktovia-backup`); Ursache sind meist Schlüsseldatei, Backupspeicher (Platz, Schreibrolle `ops-backup`) oder Datenbankverbindung. Backup nach Behebung von Hand starten (`python -m taktovia_backup backup --database <db>`); der Alarm schließt mit dem nächsten erfolgreichen Lauf. Solange kein gültiges Backup besteht, keine riskanten Änderungen an der Datenbank. Danach `python -m taktovia_backup verify <id>`.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv (WP-M5-14)

<a id="archivierungs_replikationsverzug"></a>
### ARCHIVIERUNGS_REPLIKATIONSVERZUG: Archivierungs-/Replikationsverzug (WAL)

- **Komponente:** postgres · **Schweregrad:** critical · **Empfänger:** betrieb-bereitschaft
- **Schwelle:** Archivierungsverzug > 10 min über 2 min (Vorwarnung vor der RPO-Grenze 15 min); fehlgeschlagene Archivierung; erwarteter Standby nicht verbunden über 5 min
- **Erster Handlungsschritt:** `pg_stat_archiver` ansehen (`last_failed_wal`, `failed_count`) und das Serverprotokoll des `archive_command` lesen (`taktovia_backup.wal`): Backupspeicher voll oder nicht beschreibbar, Schlüsseldatei fehlt, abweichender Inhalt eines Segments (Konflikt, nie überschreiben). Nicht archivierte Segmente bleiben im `pg_wal` und füllen die Platte: Platz überwachen. Beim Standby: Verbindung (`pg_stat_replication`), Netzwerk und Replikationsslot prüfen. Solange der Verzug 15 min überschreitet, ist das RPO verletzt.
- **Eskalation:** Stufe 1 nach 60 min.
- **Lieferung:** Regel aktiv (WP-M5-14)
