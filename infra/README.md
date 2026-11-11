# infra

Lokale Umgebung (docker-compose), Infrastruktur als Code, Observability, Bereitstellungsentwurf.

- **Verantwortlich:** Plattform (Rolle laut D-12, noch keine Personen benannt)
- **Regeln:** [CONTRIBUTING.md](../CONTRIBUTING.md). Keine Geheimnisse im Repository; Passwörter in diesem Verzeichnis sind Entwicklungswerte (`dev-only-…`), **nur lokal**.
- **Statische Prüfung (läuft ohne Docker-Daemon):** `python3 infra/check.py`, Selbsttests: `python3 -m unittest infra.test_check` (aus dem Repository-Wurzelverzeichnis), Realm-Hilfsbibliothek: `python3 -m unittest discover infra/keycloak`.

## Was geprüft ist und was nicht

| Bereich | Geprüft (Befehl) | **Nicht ausgeführt** (Grund) |
|---|---|---|
| compose | `docker compose config` (im `check.py`), `bash -n` der Skripte | Start, Healthchecks, Smoke-Skript, Reset (kein Docker-Daemon) |
| Keycloak-Realm | JSON-Struktur, Rollen, MFA-Kennzeichen, Mandantentrennung (`check.py`); Token-Hilfe offline | Import in Keycloak, Tokenbezug (kein laufender Keycloak) |
| Observability | YAML-Syntax, Pipelinekonsistenz (`check.py`) | Collector/Prometheus gestartet, Scrape, Backend-Metrikendpunkt (Backend-Teil von WP-M0-10 nicht hier) |
| IaC | Dateistruktur, Pflichtparameter, eindeutige Domänen (`check.py`) | `terraform validate/plan` (weder Terraform noch OpenTofu installiert) |
| Bereitstellung | Format der Matrizen | gesamter Ablauf, Pipeline, Signaturprüfung, Rauchtest in einer Umgebung |

## Compose (`compose/`)

Start: `cd infra/compose && cp .env.example .env && ./up.sh [profil …]`, Reset: `./reset.sh` (löscht Volumes, Realm wird neu importiert), Smoke: `./smoke.sh`.
Profile: `core` (PostgreSQL+PostGIS, RabbitMQ, Redis-kompatibel), `auth` (Keycloak), `storage` (MinIO), `routing` (Valhalla), `observability` (OTel-Collector, Prometheus), `all` = alle sechs Fachdienste.

Portkonvention (nur `127.0.0.1`): PostgreSQL 15432, RabbitMQ AMQP 15672 / UI 25672, Redis 16379, Keycloak 18080, MinIO API 19000 / Konsole 19001, Valhalla 18002, OTLP gRPC 14317 / HTTP 14318, Collector-Health 13133, Prometheus 19090. Namenskonvention: Compose-Projekt `taktovia-dev`, Dienstnamen wie oben.

Hinweise: Redis (hier Valkey als Platzhalter) ist ausschließlich Cache und Rate-Limit (Leitlinie 4). Produktionsdaten werden nie in Entwicklung kopiert (Kap. 01 §1.7). Die Datenbankrollen und das Schema legen WP-M0-15/-16 an (`db/db.py`), nicht compose.

### Versionen (Abweichung vom WP: keine Digest-Festschreibung)
Das WP verlangt Images per Digest. Digests und Tags konnten **nicht gegen eine Registry geprüft** werden (Registries sind aus der Entwicklungsumgebung nicht erreichbar). Daher:
- `postgis/postgis:17-3.5` (Tag aus `versionen.md`, ungeprüft), `rabbitmq:4-management`, `valkey/valkey:8`, `quay.io/keycloak/keycloak:26.0`, `otel/opentelemetry-collector-contrib:0.100.0`, `prom/prometheus:v2.53.0`: aus dem Gedächtnis eingetragen, **nicht gegen Registry geprüft**, nur Ausgangsvorschläge.
- MinIO und Valhalla: Tag `latest` ist bewusst **Platzhalter**; Version und Lizenz (MinIO AGPL-3.0, D-05; Valhalla MIT) sind festzulegen.
- Offen: Patchstände, CVE-Prüfung, Digest-Pinning, Lizenzbewertung Redis/Valkey; Eintrag in `docs/umsetzungsplan/versionen.md`.
Alle Werte lassen sich in `compose/.env` überschreiben.

### Valhalla-Kartendaten
Valhalla braucht einen kleinen OSM-Ausschnitt (Arbeitsstand, D-09). Eine `.osm.pbf`-Datei (z. B. ein kleiner Regionalauszug eines OSM-Anbieters) nach `compose/valhalla-data/` legen oder `VALHALLA_TILE_URLS` setzen; die Kacheln werden beim ersten Start gebaut. Lizenz: Open Database License (ODbL), Namensnennung "© OpenStreetMap-Mitwirkende". Ein konkreter Download wurde nicht geprüft (externe Hosts nicht erreichbar). `valhalla-data/` ist ignoriert.

## Keycloak (`keycloak/`)
`realm-taktovia-dev.json` (Realm `taktovia-dev`) wird von `generate_realm.py` erzeugt und per `--import-realm` beim Start importiert. Enthalten: Clients `passenger-web`, `operator-web`, `driver-app` (Authorization Code + PKCE S256; Passwort-Grant zusätzlich nur für lokale Testtokens), Maschinen-Clients `adapter-<typ>` (Client Credentials). Testakteure `a.<rolle>` und `b.<rolle>` für `passenger`, `dispatcher`, `driver`, `finance`, `tenant_admin`, dazu `ab.dispatcher` (beide Mandanten) und `none.passenger` (keine Mitgliedschaft). Mandanten-IDs als Claim `tenant_ids` (A `…-00000000000a`, B `…-00000000000b`); Audience `taktovia-api`.
Alle Passwörter/Secrets sind `dev-only-…` und **nur lokal**; Mailadressen `@example.test`.
**MFA-Kennzeichnung (Vorschlag dieses Plans, Durchsetzung in M1):** privilegiert sind `dispatcher`, `finance`, `tenant_admin`; Rollenattribut und Claim `mfa_required=true`, aber keine Pflichtaktion gesetzt. Rollen- und Scope-Namen sind vorläufig (Rollenkatalog ist Detailvertrag M1). `token_helper.py` bezieht Tokens und prüft Issuer/Audience/Ablauf; der Bezug selbst ist nicht ausgeführt.

## Observability (`observability/`)
OTel-Collector (OTLP rein, Filter für personenbezogene Attribute, Metriken zu Prometheus, Traces/Logs nur in das debug-Log) und Prometheus (Scrape: Collector, Backend `/actuator/prometheus` als Annahme, RabbitMQ). Alarmkanal ist **Platzhalter** (Alarmierung erst M5). Ein Trace-Anzeigewerkzeug ist offen. Backend-Instrumentierung gehört nicht zu diesem Verzeichnis.

## IaC (`terraform/`) und Bereitstellung (`deploy/`)
Siehe [terraform/README.md](terraform/README.md) und [deploy/ablauf.md](deploy/ablauf.md). Die Hostingentscheidung (D-05) ist offen; WP-M0-35 ist nur als Entwurf/Vorabvertrag geliefert.
