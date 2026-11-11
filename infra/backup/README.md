# infra/backup: verschlüsselte Backups und Restore-Test (WP-M1-39, LH-6.18-SEC-010)

Datenbank und Objektspeicher werden verschlüsselt und veränderungsgeschützt gesichert; ein wiederholbarer Restore-Test stellt die Referenzdaten beider Testmandanten
in eine **isolierte** Datenbank wieder her und vergleicht Prüfsummen. Drehbuch und Nachweis: [tests/performance/restore-drehbuch-m1.md](../../tests/performance/restore-drehbuch-m1.md),
Tests: `tests/performance/test_restore_drehbuch.py`. Vertrag: [LH-6.18-SEC-010](../../docs/umsetzungsplan/vertraege/LH-6.18-SEC-010.md).

| Baustein | Datei | Stand |
|---|---|---|
| Verschlüsselung AES-256-GCM, chunkweise, Ende- und Zählerbindung (Abschneiden, Umordnen, Bitfehler und falscher Schlüssel werden erkannt) | `taktovia_backup/crypto.py` | ausgeführt (Test) |
| Backupspeicher: einmal schreibbar, bis Aufbewahrungsende unlöschbar, nur Betriebsrollen (`ops-backup`, `ops-restore`, `ops-retention`) | `taktovia_backup/store.py` | ausgeführt (Test); lokaler Ersatz für Object Lock/IAM |
| Konsistentes Backup (`pg_dump --snapshot` auf exportiertem Snapshot, Prüfsummen je Tabelle im selben Snapshot), Manifest mit Authentifizierungscode | `taktovia_backup/dbops.py` | ausgeführt (Test), auch unter Schreiblast |
| Lösch-/Sperrjournal außerhalb der Datenbank (hash-verkettet) und Wiedereinspielung vor Freigabe | `dbops.export_journal`, `taktovia.replay_erasure_journal` (V10330) | ausgeführt (Test) |
| Restore-Job: isolierte Datenbank, Prüfsummen, Struktur (RLS, Trigger, Rollen, Rechte), Isolationstest WP-M1-01, Protokoll mit Ergebnis und Dauer | `dbops.restore`, `python -m taktovia_backup restore-test` | ausgeführt (Test) |
| WAL-Archiv (PITR): `archive_command`/`restore_command` verschlüsseln und authentifizieren Segmente | `dbops.archive_wal/restore_wal`, `postgresql-archive.conf` | Ver- und Entschlüsselung ausgeführt; **Abspielen gegen einen Cluster (PITR) nicht ausgeführt** |
| Geplanter Betrieb (Backup täglich, Restore-Test wöchentlich) | `systemd/*.service`, `*.timer` | **nicht ausgeführt** (keine Zielumgebung, D-05); Planungswerte in `backup-policy.json` |
| Objektspeicher-Backup (Tar, verschlüsselt, Prüfsumme je Objekt) | `create_backup(objects_dir=…)` | ausgeführt gegen ein Verzeichnis als Ersatz für den S3-Bucket; **kein S3/MinIO** |

## Schlüssel und Zugriffstrennung

- Eigener Backup-Schlüssel (32 Byte, `python -m taktovia_backup keygen <pfad>`, Modus 0600), getrennt vom Betriebsschlüssel der Anwendung (`taktovia.identity.context-secret`, Feldschutz-KEK u. a.). Im Betrieb liegt er im Secret-Speicher der **Betriebsdomäne** (nicht der Anwendungsdomäne); die Anwendung kennt ihn nicht. Rotation: neue Schlüsselkennung (`key_id` im Manifest), alte Backups bleiben mit dem alten Schlüssel lesbar, solange sie aufbewahrt werden.
- Runtime- (`taktovia_runtime`) und Migratorrolle (`taktovia_migrator`) haben **keinen** Zugriff auf den Backupspeicher: weder über die Speicherrollen noch über das Dateisystem (eigene Identitäten) noch über die Datenbank (`COPY … TO/FROM PROGRAM`, `pg_read_file`, `lo_export` sind verweigert, die Rollen sind weder Superuser noch Mitglied von `pg_read/write_server_files`). Im Betrieb entspricht das einem Bucket mit Object Lock (Compliance-Modus) und eigener IAM-Rolle (Terraform-Entwurf: `infra/terraform`, nicht ausgeführt).
- Manifest und Journal tragen Authentifizierungscodes bzw. Kettenhashes: ein verändertes Manifest, Journalsegment oder Backup wird nicht als Erfolg gemeldet.

## Grenzen (nicht ausgeführt, nicht Gegenstand von M1)

Kontinuierliches WAL/PITR gegen einen Cluster, S3 Object Lock, verwaltete Dienste, RPO-/RTO-Messung und PSP-Abgleich (**WP-M5-14, WP-M5-15**), Failover. Backupintervall, Aufbewahrung und Testzyklus sind im Pflichtenheft nicht beziffert (OP-31); `backup-policy.json` enthält Planungswerte.
