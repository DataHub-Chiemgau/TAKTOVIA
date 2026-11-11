# Versionsmatrix

Stand 11.11.2026. Grundlage: Kap. 01 §1.1 („Major-Versionen bilden den Implementierungsstartpunkt“; Patchstände, CVEs, Lizenzen und Kompatibilität sind festzuschreiben). Spalte **Status**: `verifiziert` heißt, Version und Lizenz wurden am genannten Stand gegen die genannte Quelle geprüft; `offen` heißt, die Version ist hier **nicht** festgelegt und wird in dem genannten WP bestimmt. Ein Eintrag „offen“ ist kein Versprechen einer Version.

Die tatsächlich gebauten Versionen stehen verbindlich in `backend/gradle/libs.versions.toml` und den `gradle.lockfile`-Dateien, nicht in dieser Tabelle.

| Baustein | Version | Lizenz | Quelle / Prüfung | Status | Supportende / Bemerkung |
|---|---|---|---|---|---|
| Java (OpenJDK) | 21.0.12 (LTS) | GPLv2 mit Classpath Exception | lokal `java -version`; Ubuntu-Paket | verifiziert | LTS laut Plan; Supportende beim Anbieter des Container-Basisimages festhalten |
| Kotlin | 2.4.21 | Apache-2.0 | Maven Central, 11.11.2026 | verifiziert | Gradle-Plugin meldet für Gradle 8.14.3 die Mindestanforderung 8.14.4 ab Kotlin 2.5; Wrapper-Update einplanen |
| Spring Boot | 4.1.2 | Apache-2.0 | Maven Central, 11.11.2026 | verifiziert | Release-Linie 4.1; Supportende prüfen |
| Gradle | 8.14.3 | Apache-2.0 | Wrapper mit SHA-256 `bd7110…3531`; Launcher-JAR deckt sich mit dem lokal installierten 8.14.3 | verifiziert | Neuere Hauptversion 9.x verfügbar |
| PostgreSQL JDBC | 42.7.14 | BSD-2-Clause | Maven Central | verifiziert | |
| ArchUnit | 1.5.1 | Apache-2.0 | Maven Central | verifiziert | |
| Testcontainers | 2.0.5 | MIT | Maven Central | verifiziert | Docker in der Cloud-Entwicklungsumgebung nicht verfügbar; in CI nutzbar |
| PostgreSQL | 17 (Zielversion); getestet 16.15 | PostgreSQL License | Ubuntu-Paket (16.15); 17 nur über CI-Image | offen (17.x-Patch) | Erster Lauf auf 17 steht mit dem CI-Lauf aus |
| PostGIS | 3.5 (CI-Image `postgis/postgis:17-3.5`); lokal 3.4.2 | GPL-2.0-or-later | Ubuntu-Paket 3.4.2; Imagetag nicht geprüft | offen (Imagetag) | Passung PostgreSQL 17 ↔ PostGIS in WP-M0-16 bestätigen |
| React | 19.3.1 | MIT | npm-Registry, 11.11.2026 | verifiziert | Festlegung mit WP-M0-05 |
| TypeScript | 7.0.2 | Apache-2.0 | npm-Registry, 11.11.2026 | verifiziert | Hauptversion neu; Kompatibilität mit Werkzeugkette in WP-M0-05 prüfen |
| Node.js | 24.22.2 (LTS „Krypton“) | MIT | nodejs.org Release-Index, 11.11.2026 | verifiziert | Alternative LTS 26.13.0 „Lithium“; Wahl in WP-M0-05 |
| OR-Tools (Python) | 9.15.6755 | Apache-2.0 | PyPI, 11.11.2026 | verifiziert | Nutzung im Spike (b) WP-M0-27 |
| Python | 3.13 (CI und lokale Werkzeuge) | PSF | `setup-python`, lokal | verifiziert | |
| Keycloak | – | Apache-2.0 | nicht geprüft (kein Zugriff auf Release-Quelle) | offen – WP-M0-08/-09 | |
| RabbitMQ | – | MPL-2.0 | nicht geprüft | offen – WP-M0-08 | Quorum Queues laut Kap. 01 |
| Redis oder kompatibler Ersatz | – | je nach Wahl (Lizenzwechsel bei Redis ab 8.0 beachten) | nicht geprüft | offen – WP-M0-08 | Lizenzbewertung vor Festlegung |
| MinIO oder anderer S3-kompatibler Speicher | – | AGPL-3.0 (Community) bzw. kommerziell | nicht geprüft | offen – WP-M0-08, D-05 | Hosting-Entscheidung maßgeblich |
| Valhalla | – | MIT | nicht geprüft | offen – WP-M0-30 (Routingvergleich D-09) | |
| Flutter | – | BSD-3-Clause | nicht geprüft | offen – WP-M0-06 | |
| KoSIT-Validator (XRechnung) | – | Apache-2.0 | nicht geprüft | offen – WP-M0-07 | |
| OpenTelemetry, Prometheus-Metriken | – | Apache-2.0 | nicht geprüft | offen – WP-M0-10 | |

Die Quellen für „nicht geprüft“ (GitHub-Releases, Container-Registries) waren in der Entwicklungsumgebung nicht erreichbar. Diese Zeilen sind in WP-M0-08/-14 mit Patchstand, CVE-Prüfung und Lizenzbewertung zu schließen.
