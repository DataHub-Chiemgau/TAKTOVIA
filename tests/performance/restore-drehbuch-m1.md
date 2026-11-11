# Restore-Drehbuch M1 (WP-M1-39, LH-6.18-SEC-010)

Automatisiert, wiederholbar, in einer **isolierten** Datenbank. Die Restore-Übung mit RPO/RTO-Messung und PSP-Abgleich (Vorlage `templates/restore-drehbuch-vorlage.json`) folgt mit WP-M5-14/-15.

| Schritt | Werkzeug | Erwartung |
|---|---|---|
| 1. Backup (Snapshot, verschlüsselt) und Journalausleitung | `python -m taktovia_backup backup --database <db>`, `journal-export` | Manifest mit Prüfsummen je Tabelle, Authentifizierungscode; Journal außerhalb der Datenbank |
| 2. Prüfung des Backups | `python -m taktovia_backup verify <id>` | unverändert und authentisch; manipuliertes Backup, Manifest oder Journal wird abgewiesen |
| 3. Restore in neue Datenbank | `python -m taktovia_backup restore-test <id>` | falscher Schlüssel: Abbruch vor jeder Wirkung; sonst Wiederherstellung |
| 4. Prüfsummenvergleich je Tabelle | im Restore-Job | alle Tabellen beider Testmandanten identisch zu T |
| 5. Strukturprüfung | im Restore-Job | RLS (aktiv, erzwungen, Policies), Trigger, Rollen und Rechte identisch |
| 6. Lösch-/Sperrjournal wieder einspielen | im Restore-Job (`taktovia.replay_erasure_journal`) | zuvor gelöschte Datensätze bleiben gelöscht |
| 7. Isolationstest WP-M1-01 | im Restore-Job (`db/tests/test_03_rls.py`, `test_isolation_alle_tabellen.py`) | besteht gegen die wiederhergestellte Datenbank |
| 8. Protokoll | `restore-protokolle/restore-protokoll-<id>.json` | Ergebnis, Dauer, Schritte, Ergebnis je Tabelle; Fehlschlag wird als FAILED protokolliert |

Ausführung der Tests: `cd tests/performance && python -m unittest test_restore_drehbuch` (braucht die Datenbank-Umgebung aus `db/db.py`, `pg_dump`, `pg_restore`, `tar` und das Paket `cryptography`; ohne Datenbank übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` fehlgeschlagen).
