# Geheimnisverwaltung (Konzept, WP-M0-11 / WP-M0-35)

- Keine Geheimnisse im Repository (CONTRIBUTING). Das Repository enthält nur **Namen** von Geheimnisdomänen und Zugängen.
- Jede Umgebung hat eine **eigene** Geheimnis-, Identitäts- und PSP-Domäne (siehe `umgebungsmatrix.csv`); kein Geheimnis wird umgebungsübergreifend geteilt. `infra/check.py` prüft, dass die Domänennamen je Umgebung eindeutig sind.
- Migrationszugang und Laufzeitzugang sind getrennt (Kap. 01 §1.7): der Migrationszugang existiert nur im kurzlebigen Migrationsschritt; der Laufzeitprozess bekommt ihn nie. Zuordnung je Schritt: `geheimnisse.csv`.
- Lokal: nur Entwicklungswerte aus `compose/.env.example` (gekennzeichnet "nur lokal").
- Konkrete Geheimnisverwaltung (Anbieterdienst) ist bis D-05 offen.

## Namensschema, Zugriff und Rotation (WP-M1-36, Planungsvorschlag; Secret-Manager-Auswahl hängt an D-05)

- **Referenz:** `secret://<geheimnisdomäne>/<name>`; Domäne laut `umgebungsmatrix.csv` (`taktovia-<umgebung>-secrets`), Name laut `geheimnisse.csv` (`[a-z][a-z0-9_]+`). Konfiguration und Datenbank halten nur Referenzen (Spalten `*_ref`, Lint in `db/tests/test_12_keine_secretspalten.py`). Aufgelöst wird zur Laufzeit von `platform.secret.SecretResolver`; eine Referenz einer fremden Domäne wird nie aufgelöst (getrennte Umgebungen).
- **Zugriffsrollen:** Laufzeitprozess liest nur Laufzeitgeheimnisse seiner Umgebung (`db_runtime_credential`, `rabbitmq_credential`, `redis_credential`, `objectstore_credential`, `adapter_client_secrets`); `db_migration_credential` nur der Migrationsschritt; `keycloak_admin_credential` nur die Bereitstellung.
- **Rotation ohne neues Image (Wechselnutzer-Verfahren):** 1. neuen Zugang beim Dienst anlegen, 2. Secret in der Verwaltung atomar ersetzen (Datei tauschen, nicht überschreiben), 3. Anwendung liest beim nächsten Verbindungsaufbau den neuen Wert (`SecretBackedDataSource`, kein Neustart), 4. alten Zugang sperren. Während der Rotation sind nur TLS-Verbindungen zulässig (`TransportGuard`). Nachweis unter Last: `SecretRotationTest`.
- **Rotationsintervall:** im Pflichtenheft nicht festgelegt; Vorschlag 90 Tage für Laufzeitzugänge, sofort bei Verdacht. Festlegung vor Produktivschaltung.
- **Secret-Scan:** `tools/secret_scan.py` (Arbeitsbaum, Historie, Image-Archive/SBOM), CI-Job `secret-scan`; zusätzlich der vorhandene `gitleaks`-Schritt im Job `lieferkette` (nicht ausgeführt).
