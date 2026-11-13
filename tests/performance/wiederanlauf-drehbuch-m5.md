# Wiederanlaufdrehbuch Datenbank (WP-M5-14, Vorlage aus WP-M0-25)

Beschreibt jeden Schritt vom Ausfall der Primärdatenbank bis zur funktionsfähigen End-to-End-Probe. Ausführbare Teile: `infra/backup/taktovia_backup` (`wal.py`, `pitr.py`, `consistency.py`,
`drehbuch.py`), Protokollstruktur nach `templates/restore-drehbuch-vorlage.json`. Die Durchführung der Ausfallübung mit Messung ist **WP-M5-15** (nicht Teil dieses Pakets).
Verträge: [LH-6.19-NFR-007](../../docs/umsetzungsplan/vertraege/LH-6.19-NFR-007.md), [LH-6.18-SEC-010](../../docs/umsetzungsplan/vertraege/LH-6.18-SEC-010.md); Alarme: `docs/betrieb/alarm-handlungsschritte.md`.

## Messdefinitionen (Kap. 08 §8.6, vorläufige Lastenheftziele, D-05)

| Größe | Definition | Ziel |
|---|---|---|
| Ausfallzeitpunkt | Zeitpunkt, zu dem die Primärdatenbank keine Commits mehr annimmt (Schritt 1, Zeitstempel des Alarms `DatenbankNichtErreichbar` als Gegenprobe) | – |
| RPO-Messpunkt | Zeitpunkt des **letzten wiederherstellbaren Commits** im wiederhergestellten Bestand (`max(commit)` bzw. `pg_last_xact_replay_timestamp()`, Schritt 3) | – |
| gemessenes RPO | Ausfallzeitpunkt minus RPO-Messpunkt | ≤ 15 min |
| RTO-Ende | Zeitpunkt der **funktionsfähigen End-to-End-Probe** (Schritt 8: Buchungs- und Operatorprobe erfolgreich) | – |
| gemessenes RTO | RTO-Ende minus Ausfallzeitpunkt | ≤ 2 h |
| Archivierungsverzug | Alter der ältesten noch nicht archivierten WAL-Daten (`taktovia_wal_archive_lag_seconds`); Obergrenze des RPO | < 15 min, Alarm ab 10 min |

## Zuständigkeiten und Freigabeberechtigte

| Rolle (Personen nach D-12) | Aufgabe |
|---|---|
| `betrieb-bereitschaft` (Durchführung) | Schritte 1 bis 8, Protokoll führen, Entscheidung Failover/Restore vorbereiten |
| `betrieb-plattform` (zweite Person) | Vier-Augen bei Schritt 3 (Zielzeitpunkt) und bei jedem Abbruch; hält Schlüssel `ops-restore` bereit |
| `betriebsleitung` (**Freigabeberechtigte**) | Entscheidung Failover/Restore (Schritt 0), Freigabe nach Schritt 6 (Schranke), Abbruch |
| Fachverantwortliche Zahlungen | bestätigt den Abgleich externer Vorgänge (Schritt 6) |

Der Schlüssel `ops-restore` liegt in der Betriebsdomäne; Anwendungs-, Migrator- und Runtimeidentität haben keinen Zugriff (`infra/backup/README.md`).

## Schritt 0: Entscheidung Failover oder Restore

1. Ist ein **Standby** (Streaming, `taktovia_standby_connected = 1` bis zum Ausfall) vorhanden und sein Stand höher als der letzte archivierte Commit? Dann **Failover** (Standby hochstufen, `pg_ctl promote`): kleinster Verlust. Der Standby wird danach wie ein Restore behandelt (ab Schritt 4), denn der letzte Commit des Standbys ist der RPO-Messpunkt.
2. Sonst oder bei Beschädigung (Standby ebenfalls betroffen, logischer Fehler): **Restore auf Zeitpunkt** aus Basissicherung und WAL (Schritt 3). Bei logischem Fehler (falsche Löschung, fehlerhafte Migration) den Zielzeitpunkt **vor** dem Fehler wählen; die Verlustmenge dokumentieren.
3. Beide Wege enden in demselben Prüfpfad ab Schritt 4. Die Entscheidung trifft die Betriebsleitung; Zeitpunkt und Begründung stehen im Protokoll.

## Schritte

| Nr | Schritt | Werkzeug / Erwartung | Abbruch |
|---|---|---|---|
| 1 | Ausfall feststellen, Zeitpunkt festhalten | Alarm, `pg_isready`; `Drehbuch.ausfall()` | – |
| 2 | Schreibzugriffe stoppen; Worker (`operation`-Runner, Outbox-Relay) und Ereignisstrom anhalten | Anwendung auf Wartungsmodus, Worker aus. Laufende Worker würden gegen den alten Stand schreiben | – |
| 3 | Wiederherstellen: Zielumgebung = **Quellumgebung** (sonst technisch abgewiesen); `pitr.restore_pitr(store, key, backup_id, ziel, <umgebung>, target_time=…)` bzw. `python -m taktovia_backup restore-test` für logische Backups | Reihenfolge im Werkzeug: Manifest (MAC) → Umgebung → WAL-Kette (beschädigt/Lücke blockiert) → entschlüsseln → Wiedergabe bis Zielzeitpunkt → Hochstufen. Danach letzten wiederherstellbaren Commit bestimmen (RPO-Messpunkt) | beschädigtes oder fehlendes Segment, falscher Schlüssel, Umgebungsabweichung: **kein** Restore, Eskalation Betriebsleitung |
| 4 | Migrationsstand, Rollen, RLS prüfen | `python -m taktovia_backup consistency` (Migrationsstand), Strukturabdruck aus dem Restore-Job (RLS, Trigger, Rollen, Rechte), Isolationstest WP-M1-01 | Abweichung: Freigabe gesperrt |
| 5 | **Lösch-/Sperrjournal wieder einspielen** (`taktovia.replay_erasure_journal`, im Restore-Job) und Konsistenzprüfungen | `consistency.run_all`: `journal_wirksam` (kein gelöschter Datensatz mit Personendaten, keine aufgehobene Archivsperre), `outbox_*`, `inbox_*`, `idempotenz_*`, `kapazitaet_nicht_ueberbucht`, `keine_buchung_ohne_kapazitaet`, `audit_*`, `migrationsstand`; Protokoll als JSON | **Jeder blockierende Befund und fehlende Journalwirkung sperren die Freigabe** (`ReleaseBlocked`); ohne übergebenes Journal ebenfalls |
| 6 | **Abgleich externer Zahlungen und Operationen** vor jedem Wiederholen | Backend: `Payments.reconcileAfterRestoreIn` je Mandant (stellt Statusabgleiche über Händler-/Providerreferenz ein), danach laufen die `operation`-Worker mit Lease/Fencing; PSP-Aufrufe wiederholen nur mit derselben Kommandoreferenz. Bestätigung durch die fachverantwortliche Person | Abgleich offen: Freigabe gesperrt. Verdacht auf zweite Belastung: Abbruch, manuelle Klärung |
| 7 | **Freigabe**: Worker und Ereignisstrom wieder aktivieren (Outbox wird mindestens einmal erneut veröffentlicht, Empfänger deduplizieren über die Inbox) | `Drehbuch.freigeben()` verweigert, solange Schritt 5 oder 6 nicht bestanden sind | – |
| 8 | End-to-End-Probe: synthetische Buchung (WP-M5-05) und Operatorprobe | Zeitpunkt = RTO-Ende; danach `Drehbuch.save()` | Probe fehlgeschlagen: Ursache klären, RTO läuft weiter |

Objektspeicher: wird aus der versionierten Replik bzw. dem Objektbackup (`create_backup(objects_dir=…)`, Prüfsumme je Objekt) wiederhergestellt; Objekte, die im Löschjournal stehen, bleiben gelöscht (Löschlauf nach Restore wiederholen). Vorgaben dazu: `infra/terraform/modules/datensicherung`.

## Abgleich der Zahlungen (Schritt 6, Verhalten des Backends)

Nach einem Restore kann der Zahlungsdienstleister weiter sein als die Datenbank. `reconcileAfterRestoreIn` betrachtet jede Zahlung in `CREATED`, `REQUIRES_ACTION`, `CAPTURE_PENDING`, `CAPTURE_UNKNOWN`, `RECONCILING`, `VOID_PENDING`, `REFUND_PENDING` und stellt höchstens einen Abgleich ein. Der Abgleich fragt den Status über die Providerreferenz, ohne Providerreferenz über die **eigene Händlerreferenz** (`merchant_ref`), ab und übernimmt nur den verifizierten Stand. Er löst nie eine neue Autorisierung oder einen neuen Einzug aus; die ursprünglichen Operationen wiederholen nur mit derselben Kommandoreferenz. Grenze: eine Zahlung, deren `payment_intent`-Zeile im Verlustfenster angelegt wurde und deshalb lokal **fehlt**, wird nicht gefunden (der PSP-Adapter hat keine Auflistung nach Zeitraum); sie erscheint im Abgleichbericht der Ausfallübung (WP-M5-15) über Providerseite.

## Nachweise und Grenzen

| Nachweis | Befehl | Stand |
|---|---|---|
| PITR gegen echten Cluster (initdb, pg_basebackup, WAL-Archiv verschlüsselt, Wiedergabe auf Zeitpunkt), beschädigtes/fehlendes Segment, andere Umgebung, Archivierungsverzug-Metriken, echte Metriken lösen die echten Alarmregeln aus | `cd tests/performance && python -m unittest test_pitr` | ausgeführt |
| Trockenlauf aller Schritte, Konsistenzprüfungen positiv/negativ, Freigabeschranke, Umgebungsabweisung | `cd tests/performance && python -m unittest test_wiederanlauf_drehbuch` | ausgeführt; Protokoll: `protokolle/wiederanlauf-trockenlauf-m5-14.json` (**simulierte Uhr**, Platzhalter für Abgleich und Probe) |
| Abgleichlauf nach Restore gegen die Datenbank und den PSP-Ersatz (verlorene Zahlung, keine zweite Belastung, parallel zu Webhook) | `cd backend && ./gradlew :modules:fare-payment:test --tests '*PaymentRestoreReconcileTest*'` | ausgeführt |
| Alarme „fehlgeschlagenes Backup“ und „Archivierungs-/Replikationsverzug“ ausgelöst und zugestellt | `cd tests/acceptance && python -m unittest alerting.test_alert_scenarios` | ausgeführt (Nachbildung von Prometheus/Alertmanager) |
| Ausführung des Drehbuchs durch eine Person, die es nicht geschrieben hat (Abnahme 2) | – | **nicht ausgeführt**: der Trockenlauf ist automatisiert; die Durchführung durch Dritte steht aus |
| Standby über Hosts, S3 Object Lock, Zielumgebung, Betriebsmodell | – | **nicht ausgeführt** (kein Docker, D-05); `infra/backup/postgresql-standby.conf` und `infra/terraform/modules/datensicherung` sind Entwürfe |
