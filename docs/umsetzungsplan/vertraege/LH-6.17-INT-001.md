# LH-6.17-INT-001 – OpenAPI je Modul und Contract-Test-Pipeline

- **Verantwortlicher:** Rolle Plattform (Modul `platform`, API-Verträge in `api/`)
- **Arbeitspaket:** WP-M1-03
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Plattform stellt versionierte, dokumentierte APIs für Buchungen, Verfügbarkeit, Fahrzeug- und Betriebsobjekte bereit.

**Originalabnahme (unverändert):** OpenAPI-Schema und Contract-Tests decken produktive Endpunkte ab.

## Vertrag

- **Tabellen / DDL:** keine.
- **API:** Struktur `api/` mit einer OpenAPI-3.1-Datei je Modul (bestehend: `common.yaml`, `federation.yaml`, `operations.yaml`, `finance.yaml`, `config.yaml`, `rosters.yaml`, `driver.yaml`; Ausgangspunkt `docs/pflichtenheft/api/openapi.yaml`, Bündelung per `api/tools/bundle.py`). Gemeinsame Komponenten in `common.yaml`: `Problem`, `UUID`, `SignedMoney`, `IdempotencyKey`, `IfMatch`, `Traceparent`, `OperationAccepted` (vorhanden); `Money` liegt heute in `federation.yaml` und ist als Plattformtyp nach `common.yaml` zu verlagern (WP-M1-02, OP-37; die `api/README.md` führt Geldtypen unter `common.yaml`). Jede Vertragsänderung in `api/` verlangt danach `python api/tools/bundle.py --write-docs`, sonst scheitert `bundle.py --check`. Versionspräfix `/v1`; Breaking Change -> neue Major-Version (Lint in `api/tools/lint.py`, Abweichungsprüfung `api/tools/breaking.py`; Linter-Ausnahmen nur namentlich begründet laut `api/README.md`). Zu ergänzen: `/v1/me`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `.../validate`, `.../impact`, `/v1/config/revisions/{revision_id}/publish`, `/v1/operations/{operation_id}`, `/v1/passengers`, `/v1/webhook-subscriptions`, `/v1/services` sowie die Verwaltungsendpunkte der M1-Datei (Organisation, Mitgliedschaft/Tenantwechsel, Region/Betriebshof/Kostenstelle, Sitzungen, Auditabfrage, Unternehmensprofil, Aufbewahrungsregeln, Fahrzeugrollen, Fahrerfreigabe). Scope-Katalog im Sicherheitsschema `oidc` erweitern (bisher `journeys:plan|read|book|cancel`, `operations:read`, `costs:allocate`, `expenses:approve`, `config:publish`, `rosters:publish`, `driver:events`; Namen der neuen M1-Scopes sind Detailvertrag vorab). Neue schreibende Endpunkte tragen `Idempotency-Key`, Revisionseingriffe `If-Match` (Lint).
- **Pipeline (CI-Pflicht):** Contract-Test-Runner prüft Request/Response aller Routen gegen die laufende Anwendung; Vergleich registrierter Routen gegen `operationId`s (genau eine je Route, sonst Build-Abbruch); Kap. 05 §5.8: als 3.1 parsbar, alle `$ref` auflösbar, `operationId` eindeutig, Beispiele schemagültig; `validate_artifacts.py` bleibt Pflichtschritt. Dokumentation (Entwicklerportal-Seite, aus den OpenAPI-Dateien erzeugt) nennt Version, Sicherheits-Scopes und Fehlercodes je Endpunkt; Ort und Werkzeug der Seite sind im Pflichtenheft nicht festgelegt (Detailvertrag vorab, nicht bauhindernd).
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** veröffentlichte API-Dokumentation aus den OpenAPI-Dateien (Entwicklerportal).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-01 – Die Originalabnahme nennt Buchungen, Verfügbarkeit, Fahrzeug- und Betriebsobjekte; diese APIs (`/v1/ride-offers`, `/v1/bookings`, `/v1/operator/vehicles`, `/v1/operator/incidents`) entstehen erst in M2/M3. In M1 nachgewiesen werden Pipeline, Build-Abbruch bei fehlendem Vertrag und Abdeckung aller M1-Endpunkte; Vollabnahme mit WP-M2-01, WP-M2-21, WP-M2-23, WP-M3-01, WP-M3-03, WP-M3-53 über dieselbe Pipeline. Das blockiert den Bau nicht.

## Tests

- **Positiv:** Contract-Lauf aller M1-Endpunkte grün, je Endpunkt mindestens ein positiver Fall; Dokumentation nennt Version, Scopes und Fehlercodes je Endpunkt (maschinell gegen die OpenAPI-Dateien geprüft); `/v1`-Präfix an allen Pfaden; Beispiele schemagültig; `bundle.py --check` grün.
- **Negativ:** absichtlich undokumentierte Testroute lässt den Build scheitern; Antwort mit zusätzlichem Feld gegenüber dem Schema wird erkannt; je Endpunkt mindestens ein Fehlerfall; doppelte `operationId` oder nicht auflösbares `$ref` bricht ab; Pfad ohne `/v1`, Mutation ohne `Idempotency-Key`, Revisionseingriff ohne `If-Match`, Operation ohne Sicherheitsschema, Fehlerantwort ohne `application/problem+json` bricht den Lint; Breaking Change ohne neue Major-Version (entferntes Pflichtfeld, entfernte Operation, neues Pflichtfeld/-parameter) wird von `breaking.py` gemeldet; Dokumentation, die Version, Scope oder Fehlercode eines Endpunkts weglässt, schlägt fehl.
- **Konkurrierend:** nicht einschlägig (reine Vertrags- und Buildprüfung, keine Sicherheits-/Geld-/Kapazitätsfunktion).
- **Referenztests:** keine Katalog-ID im WP genannt; Vollabnahme mit M2/M3.
- Testfälle: tests/acceptance/api/test_contract_pipeline.py::test_undokumentierte_route_bricht_build; backend/app/src/test/kotlin/de/taktovia/app/ContractCoverageTest.kt::jede_registrierte_route_hat_genau_eine_operation_id; backend/app/src/test/kotlin/de/taktovia/app/ContractCoverageTest.kt::alle_m1_endpunkte_positiv_und_fehlerfall; backend/app/src/test/kotlin/de/taktovia/app/ContractCoverageTest.kt::antwort_mit_zusaetzlichem_feld_wird_erkannt; tests/acceptance/api/test_api_documentation.py::test_doku_nennt_version_scopes_fehlercodes_je_endpunkt; api/tools/test_api.py::test_negativ_doppelte_operation_id; api/tools/test_api.py::test_negativ_nicht_aufloesbarer_ref; api/tools/test_api.py::test_entferntes_pflichtfeld_wird_gemeldet; tests/acceptance/api/test_contract_pipeline.py::test_weitere_verstoesse_bricht_build

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
