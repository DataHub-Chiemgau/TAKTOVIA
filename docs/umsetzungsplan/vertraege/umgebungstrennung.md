# Umgebungstrennung (WP-M1-40, Detailvertrag)

Ergänzt [LH-6.18-SEC-012](LH-6.18-SEC-012.md). Maschinenlesbare Umgebungsmatrix: [infra/umgebungsmatrix.json](../../../infra/umgebungsmatrix.json) (Zugänge, Rollen, Datenherkunft, Konto, Netz, Identitäts- und Secret-Domäne, Datenbankzugänge je Umgebung).

## Umgebungskennung (Entscheidung zum offenen Ablageort)

- **Ablageort:** Tabelle `taktovia_meta.environment_marker` (Migration `V1400__umgebungskennung.sql`): genau eine Zeile, Werte `development`, `staging`, `performance`, `production`. Im Metaschema, nicht im Fachschema (kein Mandantenbezug, keine RLS-Tabelle).
- **Setzen:** einmalig durch `db.py migrate` aus `TAKTOVIA_ENVIRONMENT` (Vorgabe `development`); eine abweichende Kennung bricht die Migration ab. In der Bereitstellung liefert das Terraform-Modul die Kennung als Ausgabe `environment_marker`.
- **Unveränderlich:** UPDATE, DELETE, TRUNCATE scheitern per Trigger für jede Rolle (auch Migrator und Administrator); eine zweite Zeile verhindert der Primärschlüssel. Die Runtime-Rolle hat nur SELECT.
- **Anwendung:** Konfigurationswert `taktovia.environment` (`TAKTOVIA_ENVIRONMENT`): `local` (= `development`, nur lokale Entwicklung mit gelockerter TLS-Pflicht), `development`, `staging`, `performance`, `production`; ohne Angabe `production`.
- **Startprüfung** (`app.EnvironmentGuard`): Kennung der Anwendung = Kennung der Datenbank, sonst Abbruch beim Start; Datenbank ohne lesbare Kennung ebenfalls. Ein Entwicklungsdienst mit Produktions-DB-URL startet damit nicht.

## Importprüfung für Dumps

`python db/db.py import-dump <datei>` (`db/dumpguard.py`) liest die Kennung aus dem Dump (Custom- und Klartextformat) und die des Ziels und weist ab: Dump ohne lesbare Kennung, Verzeichnisformat, **Dump der Produktion in jede andere Umgebung**, jedes Ziel `production` (Wiederherstellung: WP-M1-39), Ziel ohne Kennung, gleichzeitiger zweiter Import (Advisory-Lock). Geprüft wird vor jeder Schreiboperation und erneut unter dem Lock; zwei parallele Importversuche eines Produktionsdumps werden beide abgewiesen. Eingespielt werden nur Daten aus Custom-Archiven; Kennung, Historie und Währungskatalog des Ziels bleiben unangetastet.

**Kontrollierte, anonymisierte Datenübernahme:** gibt es nicht. Bis zu einem eigenen Beschluss (Pflichtenheft: nur „Produktionsdaten werden nicht in Entwicklung kopiert“) ist jede Übernahme von Produktionsdaten verboten. **Grenze:** Die Kennung schützt vor Versehen, nicht vor absichtlichem Umetikettieren; dagegen wirkt die Trennung der Zugänge (kein Netzpfad, keine geteilten Zugangsdaten, siehe Matrix).

## Synthetische Daten

`infra/seed/seed_dev.py` erzeugt deterministisch die Mandanten A/B (setzt auf `tests/acceptance/taktovia_at/seed.py` auf) und läuft nur gegen eine Datenbank mit Kennung `development`. `infra/seed/synthetic_scan.py` prüft eine Datenbank: Kennung `development`, alle Namen mit „(synthetisch)“, Pseudonyme `synthetic-token-…`, Mitgliedschaften aus dem Entwicklungs-IdP, keine Kontaktdaten.

## Zugänge, Identität, Secrets

Je Umgebung eigene Konto-, Netz-, Secret-, PSP- und Identitätsdomäne (Realm und Issuer, Realm-Export je Umgebung), eigene Datenbankzugänge (Migration ≠ Laufzeit). Die Datenbank ist nur aus dem eigenen Netz erreichbar; kein Netzpfad zwischen Umgebungen. Das Backend vertraut genau einem Issuer (`taktovia.idp.issuer-uri`, WP-M1-04); Token einer anderen Umgebung scheitern an Issuer und Schlüsseln (401). `infra/check_env_separation.py` (Teil von `infra/check.py`) prüft Matrix, Terraform-Parameter, Realms und Compose-Konfiguration.

## Nicht ausgeführt

Reale Konten, Netze, Firewalls, Terraform (`plan/validate`) und ein Keycloak wurden nicht ausgeführt (kein Docker/Terraform); der Nachweis „kein Netzpfad“ ist die statische Prüfung der Konfiguration als Code, nicht eine Messung im Netz. D-05 (Hosting) blockiert nur die Produktivschaltung.
