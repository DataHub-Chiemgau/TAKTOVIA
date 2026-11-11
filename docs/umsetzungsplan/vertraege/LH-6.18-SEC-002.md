# LH-6.18-SEC-002 – Transportverschlüsselung und Secret-Verwaltung

- **Verantwortlicher:** Rolle Betrieb/Infrastruktur (`infra/`)
- **Arbeitspaket:** WP-M1-36
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Verbindungen für Clients, Admin und Schnittstellen sind transportverschlüsselt.

**Originalabnahme (unverändert):** Unsichere Klartextverbindungen werden abgewiesen.

## Vertrag

- **Tabellen / DDL:** keine; keine Secretwerte in Datenbanktabellen (z. B. `webhook_subscription` hält nur Secretreferenz, Kap. 04 §4.4).
- **API / Transport:** TLS-Terminierung am Gateway für API, Web- und Admin-Oberfläche; HSTS; Klartext-HTTP wird abgewiesen bzw. Klartext-Ports geschlossen, es werden keine Nutzdaten im Klartext beantwortet. TLS auch für PostgreSQL, RabbitMQ, Redis, Objektspeicher und IdP; unverschlüsselte Verbindung zu Datenbank und Broker wird abgewiesen. Konfiguration nur über Secret-Referenzen, getrennte Secrets je Umgebung (Kap. 01 §1.8; Bestand: `infra/deploy/geheimnisse.csv` mit `db_migration_credential`, `db_runtime_credential`, `rabbitmq_credential`, `redis_credential`, `objectstore_credential`, `keycloak_admin_credential`, `adapter_client_secrets`; Domänen je Umgebung in `infra/deploy/umgebungsmatrix.csv`, Eindeutigkeit durch `infra/check.py` geprüft); Migrationszugang nur im kurzlebigen Migrationsschritt, nie im Laufzeitprozess; Rotation ohne neues Image; Secret-Scan in CI (Teil SEC-003, gemeinsames WP).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine; betroffen sind alle Oberflächen (Klartextzugriff abgewiesen).
- **Entscheidungen:** D-05 (Hostingregion, verwaltete Dienste) – blockiert nur die Produktivschaltung, nicht den Bau.
- **Offene Punkte:** OP-31 (TLS-Mindestversion und Cipher-Vorgabe nicht beziffert; Planungsdefault im Detailvertrag vorab festlegen, bis dahin nur "keine unsicheren Protokollversionen" prüfbar); Secret-Namensschema, Zugriffsrollen, Rotationsintervall und Secret-Manager-Auswahl hängen an D-05. `umgebungsmatrix.csv` führt `staging` als "Zielplattform, M5" und `development` als vorläufige Integrationsumgebung: In welcher Umgebung der TLS-Scan der "Staging-Endpunkte" in M1 läuft (lokale Compose-Umgebung bzw. vorläufige Integrationsumgebung bis Staging steht), ist im Detailvertrag festzulegen (nicht bauhindernd). Die Secret-Scan-Hälfte (Test-Secret, `.env` im Image) gehört zu LH-6.18-SEC-003 (gemeinsames WP-M1-36).

## Tests

- **Positiv:** Betrieb mit Secrets aus dem Secret-Manager; TLS-Scan der Staging-Endpunkte zeigt keine unsicheren Protokollversionen; HTTPS-Anfrage an API, Web und Admin wird bedient; HSTS-Header ist gesetzt; PostgreSQL-, RabbitMQ-, Redis-, Objektspeicher- und IdP-Verbindungen der Anwendung laufen mit TLS; Konfiguration enthält ausschließlich Secret-Referenzen.
- **Negativ:** unverschlüsselte HTTP-Anfrage an API, Web und Admin wird abgewiesen ohne Nutzdaten im Klartext; Klartextverbindung zu PostgreSQL, RabbitMQ, Redis, Objektspeicher und IdP je Komponente abgewiesen (Anwendung startet nicht mit Klartext-Konfiguration); Klartextport am Gateway geschlossen oder liefert keine Nutzdaten; Test-Secret im Code und Image mit `.env`-Datei lassen den Build scheitern; keine Secretwerte in Datenbanktabellen (`webhook_subscription` und andere Tabellen halten nur Secretreferenzen; Prüfung über Schema-Scan nach Secretspalten bzw. Testdatensatz); Staging-Secrets funktionieren nicht gegen Development und umgekehrt (getrennte Domänen); Laufzeitprozess hat keinen Zugriff auf `db_migration_credential`.
- **Konkurrierend (Sicherheit):** Rotation eines Secrets unter Last ohne Ausfall und ohne neues Image; während der Rotation wird keine Klartextverbindung zugelassen; Rotation ändert nur das Secret, es wird kein neues Image gebaut; nach der Rotation wird das alte Secret abgewiesen.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis über TLS-Scan der Staging-Endpunkte.
- Testfälle: geplant:tests/acceptance/security/test_transport_tls.py::test_klartext_http_und_dienstverbindungen_abgewiesen; geplant:tests/acceptance/security/test_transport_tls.py::test_https_bedient_und_hsts_gesetzt; geplant:tests/acceptance/security/test_transport_tls.py::test_tls_scan_keine_unsicheren_protokollversionen; geplant:tests/acceptance/security/test_secret_rotation.py::test_secret_rotation_unter_last_ohne_ausfall; geplant:tests/acceptance/security/test_secret_handling.py::test_keine_secretwerte_in_tabellen_und_konfiguration; infra/test_check.py::test_shared_secret_domain

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit).
