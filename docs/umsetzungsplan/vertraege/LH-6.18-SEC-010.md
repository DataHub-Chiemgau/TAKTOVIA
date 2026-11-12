# LH-6.18-SEC-010 – Verschlüsselte Backups und Restore-Test

- **Verantwortlicher:** Rolle Betrieb/Infrastruktur (`infra/`), fachlich Rolle Plattform
- **Arbeitspaket:** WP-M1-39
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Backups werden verschlüsselt aufbewahrt, zyklisch getestet und vor unbefugter Veränderung geschützt.

**Originalabnahme (unverändert):** Regelmäßiger Restore-Test stellt Referenzdaten erfolgreich wieder her.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Referenzdatensatz der Mandanten A/B (je Fahrgast, Fahrzeug, Beleg, Kostenstelle gemäß WP-M1-01) mit Prüfsummen je Tabelle des Schemas `taktovia`. Nachweis nach Restore: RLS (`tenant_isolation`, `own_tenant`, `FORCE ROW LEVEL SECURITY`), Rollen (Runtime NOSUPERUSER/NOBYPASSRLS) und Trigger (`reject_mutation()` auf den unveränderlichen Tabellen `economic_event`, `audit_event`, `partner_contract_version`, `fare_quote`; `tenant_id`-Änderungssperre `reject_tenant_change()` als Trigger `tenant_id_immutable` aus `V3__tenant_id_unveraenderlich.sql`) sind unverändert wirksam; Isolationstest aus WP-M1-01 läuft gegen die wiederhergestellte Datenbank. Das Lösch-/Sperrjournal (WP-M1-33) wird vor Freigabe der wiederhergestellten Umgebung wieder eingespielt (Kap. 01 §1.7); ein zuvor gelöschter Testdatensatz bleibt gelöscht.
- **API:** keine fachliche API. Betriebsfunktionen laufen nur über die Betriebsrolle (Infrastruktur), nicht über `/v1`.
- **Infrastruktur (`infra/`):** verschlüsselte Basis-Backups plus kontinuierliches WAL (PITR) für PostgreSQL; Objektspeicher ebenfalls verschlüsselt gesichert; getrennter Backup-Schlüssel (nicht der Betriebsschlüssel der Anwendung); Schreibschutz/Object Lock bzw. getrennte Berechtigungsdomäne: Runtime- (`taktovia_runtime`) und Migratorrolle (`taktovia_migrator`) können Backups weder verändern noch löschen, Zugriff nur für die Betriebsrolle; geplanter Restore-Job in isolierte Umgebung mit Prüfsummenvergleich gegen den Referenzdatensatz; Ergebnis (Erfolg/Fehlschlag) und Dauer werden protokolliert; Integritätsprüfung erkennt ein manipuliertes Backup. Die Prüfsummen- und Isolationsprüfung nach Restore läuft als Teil des Restore-Jobs (Ergebnis je Tabelle protokolliert). Restore-Drehbuch in `tests/performance/`.
- **Zustandsautomat:** keine. **JSON-Schema / Ereignisse:** keine fachlichen; das Restore-Protokoll ist ein Betriebsartefakt (Felder: Zeitpunkt T, Dauer, Prüfsummenergebnis je Tabelle, Ergebnis).
- **Parameter und Kennzahlen:** keine Katalogparameter. Zielwerte des Lastenhefts RPO <= 15 min, RTO <= 2 h gelten als Zielwerte; ihre Messung gehört nicht in M1 (Abgrenzung, siehe unten).
- **Oberfläche:** keine.
- **Abgrenzung des Nachweises:** M1 weist Restore-Test, Verschlüsselung und Zugriffstrennung nach; Restore-Übung mit RPO/RTO-Messung einschließlich PSP-Abgleich folgt mit WP-M5-14 und WP-M5-15 (Vollabnahme). Failover/Hochverfügbarkeit und Finanzarchive sind nicht im Umfang.
- **Entscheidungen:** D-05 (Hosting, Betrieb) – blockiert nur Produktivschaltung, nicht den Bau.
- **Offene Punkte:** OP-31 – Backup-Intervall, Aufbewahrungsdauer und Zyklus des Restore-Tests sind im Pflichtenheft nur „zyklisch“/„regelmäßig“; Zahlenwerte und Schlüsselverwaltung (Detailvertrag vorab) vor Produktivschaltung festlegen; der Bau mit parametrisiertem Zyklus ist nicht blockiert. Konkrete Werkzeuge hängen an D-05.

## Tests

- **Positiv:** Restore auf Zeitpunkt T in isolierte Umgebung; Prüfsummen der Referenzdaten beider Mandanten identisch; Job ist als regelmäßiger Job eingerichtet und protokolliert Ergebnis und Dauer; Backup (Datenbank und Objektspeicher) im Ruhezustand verschlüsselt und ohne Schlüssel nicht lesbar; Objektspeicher-Referenzobjekte (Belegoriginale, Prüfsumme `original_sha256`) nach Restore identisch; Isolationstest aus WP-M1-01 besteht nach Restore.
- **Negativ:** Restore mit falschem Schlüssel schlägt fehl (Backup ohne Schlüssel nicht lesbar); Löschen/Ändern des Backups mit Anwendungs-, Runtime- und Migratorrolle wird abgewiesen; manipuliertes Backup wird erkannt und nicht als erfolgreich gemeldet; zuvor gelöschter Testdatensatz erscheint nach Restore und Journal-Wiedereinspielung nicht wieder.
- **Konkurrierend:** Backup und Restore auf Zeitpunkt T unter laufender Schreiblast beider Testmandanten – wiederhergestellter Stand ist transaktionskonsistent, keine halben Transaktionen, keine Vermischung der Mandanten.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis der Isolation nach Restore nutzt den Isolationstest aus WP-M1-01 (kein eigener Katalogtest).
- Testfälle: tests/performance/test_restore_drehbuch.py::test_restore_referenzdaten_pruefsummen_identisch; tests/performance/test_restore_drehbuch.py::test_restore_mit_falschem_schluessel_scheitert; tests/performance/test_restore_drehbuch.py::test_backup_unter_schreiblast_transaktionskonsistent; tests/performance/test_restore_drehbuch.py::test_restore_job_protokolliert_ergebnis_und_dauer; tests/performance/test_restore_drehbuch.py::test_backup_im_ruhezustand_verschluesselt_und_objektspeicher_restore_identisch; tests/performance/test_restore_drehbuch.py::test_runtime_und_migrator_koennen_backup_nicht_aendern_oder_loeschen; tests/performance/test_restore_drehbuch.py::test_manipuliertes_backup_wird_erkannt; tests/performance/test_restore_drehbuch.py::test_geloeschter_datensatz_bleibt_nach_restore_und_journal_geloescht; tests/performance/test_restore_drehbuch.py::test_isolation_und_rls_nach_restore_wirksam; tests/performance/test_restore_drehbuch.py::test_wal_segment_verschluesselt_archiviert_und_authentifiziert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
