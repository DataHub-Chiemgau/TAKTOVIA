# M0 – Fundament und Spikes

- **Meilenstein:** M0 (Voraussetzung für M1–M9)
- **Phase:** – (vor P1; keine Lastenheft-Phase)
- **Anzahl Anforderungen:** 0 (alle Arbeitspakete sind Enabler)
- **Anzahl WPs:** 35

**Ziel:** Nach M0 kann das Team vertragsgetrieben liefern: Das Repository hat die Zielstruktur aus Umsetzungsplan §3, jede CI-Stufe ist Pflichtprüfung, die lokale Umgebung startet mit einem Befehl, und das Kernschema ist erstmals tatsächlich ausgeführt und unter der nicht privilegierten Runtime-Rolle getestet (Kap. 09 weist aus, dass SQL, RLS und Trigger bisher nur geparst wurden). Die vier technischen Hauptrisiken aus Kap. 01 §1.1 und der Routingvergleich aus D-09 sind gemessen statt angenommen. Sandbox-Partner, PSP-Simulator, Detailvertragsprozess und Nachverfolgungs-CI stehen als Infrastruktur für alle Folgemeilensteine bereit.

**Gate M0 (Umsetzungsplan §4):** Kernschema migriert auf leerer Datenbank; Spike-Berichte mit Messwerten; Entscheidung über Routinganbieter und PSP-Kandidat vorbereitet.

**Lesehinweis:** Schwellenwerte, die nicht wörtlich im Pflichtenheft stehen, sind als „Vorschlag dieses Plans“ markiert. Werte, die das Pflichtenheft selbst als vorläufig bezeichnet, sind als „Planungsdefault“ markiert. M0 hat keinen vorgelagerten Meilenstein; externe Abhängigkeiten sind deshalb ausschließlich Beistellungen des Auftraggebers und als „Beistellung Auftraggeber“ ausgewiesen (siehe Offene Punkte 27). WP-M0-35 wurde in der Prüfung ergänzt; die WP-IDs 01–34 bleiben unverändert, weil andere Einheiten sie referenzieren. In der Abarbeitung bleibt WP-M0-34 (Gate-Bericht) das letzte Paket.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M0-01 | Repository-Wurzel, Konventionen und Versionsfestschreibung | 0 | S | – |
| WP-M0-02 | Backend-Gerüst `backend/` (Gradle-Multiprojekt) | 0 | M | WP-M0-01 |
| WP-M0-03 | Architekturtest Modulgrenzen | 0 | S | WP-M0-02 |
| WP-M0-04 | Vertragsverzeichnis `api/` (OpenAPI 3.1 je Modul) | 0 | S | WP-M0-01 |
| WP-M0-05 | Web-Gerüst `web/` | 0 | S | WP-M0-01 |
| WP-M0-06 | Mobile-Gerüst `mobile/driver` und `mobile/passenger` | 0 | S | WP-M0-01 |
| WP-M0-07 | Worker-Gerüst `workers/routing` und `workers/documents` | 0 | S | WP-M0-01 |
| WP-M0-08 | Lokale Umgebung `infra/` per docker-compose | 0 | M | WP-M0-01 |
| WP-M0-09 | Keycloak-Realm und Testidentitäten | 0 | S | WP-M0-08 |
| WP-M0-10 | Observability-Grundgerüst | 0 | S | WP-M0-02, WP-M0-08 |
| WP-M0-11 | IaC- und Umgebungsgerüst | 0 | S | WP-M0-01 |
| WP-M0-12 | CI-Stufe Build, Unit-Test, Lint | 0 | M | WP-M0-02, WP-M0-03, WP-M0-05, WP-M0-06, WP-M0-07 |
| WP-M0-13 | CI-Stufe Vertragsprüfung | 0 | S | WP-M0-04, WP-M0-12 |
| WP-M0-14 | CI-Stufe Lieferkette (Lockfiles, SBOM, signierte Images) | 0 | M | WP-M0-12 |
| WP-M0-15 | Datenbankrollen Migrator, Runtime, Modulzugänge | 0 | S | WP-M0-08 |
| WP-M0-16 | Kernschema als erste Flyway-Migration | 0 | M | WP-M0-15 |
| WP-M0-17 | CI-Stufe Datenbankintegration | 0 | S | WP-M0-12, WP-M0-16 |
| WP-M0-18 | Tenantkontext-Testharness | 0 | M | WP-M0-02, WP-M0-16 |
| WP-M0-19 | RLS-Laufzeittests | 0 | M | WP-M0-18 |
| WP-M0-20 | Trigger- und Constraint-Laufzeittests | 0 | M | WP-M0-18 |
| WP-M0-21 | Befundbericht Kernschema und Rückfluss ins Pflichtenheft | 0 | S | WP-M0-16, WP-M0-19, WP-M0-20 |
| WP-M0-22 | Akzeptanztestgerüst, Zwei-Mandanten-Fixtures, Evidenzformat | 0 | M | WP-M0-09, WP-M0-12, WP-M0-17, WP-M0-18 |
| WP-M0-23 | PSP-Simulator mit steuerbaren Fehlern | 0 | M | WP-M0-08 |
| WP-M0-24 | Deterministische Sandbox-Partner | 0 | M | WP-M0-04, WP-M0-08 |
| WP-M0-25 | Gerüst `tests/performance` | 0 | S | WP-M0-02, WP-M0-08 |
| WP-M0-26 | Spike (a) Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS | 0 | M | WP-M0-16, WP-M0-18, WP-M0-25 |
| WP-M0-27 | Spike (b) Solverlaufzeit der Einfügeheuristik | 0 | M | WP-M0-07, WP-M0-08, WP-M0-25 |
| WP-M0-28 | Spike (c) Driver-Offline-Queue auf Zielgeräten | 0 | M | WP-M0-06 |
| WP-M0-29 | Spike (d) PSP-Sandbox | 0 | M | WP-M0-23 |
| WP-M0-30 | Spike (e) Streckenbezogener Routingvergleich (D-09) | 0 | M | WP-M0-08 |
| WP-M0-31 | Detailvertragsprozess | 0 | S | WP-M0-01, WP-M0-04 |
| WP-M0-32 | Nachverfolgungs-CI für `arbeitspakete.csv` und `e2e.csv` | 0 | M | WP-M0-12, WP-M0-22, WP-M0-31 |
| WP-M0-33 | Bestätigung von Umfang und Testzuordnung mit dem Auftraggeber | 0 | S | WP-M0-22, WP-M0-31, WP-M0-32 |
| WP-M0-34 | M0-Gate-Bericht und Entscheidungsvorlagen | 0 | S | WP-M0-10, WP-M0-13, WP-M0-14, WP-M0-17, WP-M0-21, WP-M0-24, WP-M0-26, WP-M0-27, WP-M0-28, WP-M0-29, WP-M0-30, WP-M0-32, WP-M0-33, WP-M0-35 |
| WP-M0-35 | Bereitstellungsstufe und gemeinsame Integrationsumgebung | 0 | M | WP-M0-09, WP-M0-11, WP-M0-14, WP-M0-16 |

## Arbeitspakete

### WP-M0-01 – Repository-Wurzel, Konventionen und Versionsfestschreibung

- **Ziel:** Das Repository hat die Verzeichnisse der Zielstruktur (Umsetzungsplan §3) mit je einem README zum Zweck und Verantwortlichen; unterstützte Versionen aller Bausteine aus Kap. 01 §1.1 sind festgeschrieben.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Verzeichnisse `backend/`, `db/migrations/`, `api/`, `workers/routing/`, `workers/documents/`, `web/`, `mobile/driver/`, `mobile/passenger/`, `infra/`, `tests/acceptance/`, `tests/performance/` mit README (Zweck, Verantwortliches Team, Build-Befehl).
  - `CODEOWNERS`, Branch- und Review-Regeln, `.editorconfig`, `.gitignore`, Beitragsleitfaden mit den sieben Leitlinien aus Umsetzungsplan §2.
  - Versionsmatrix (`docs/umsetzungsplan/versionen.md` oder gleichwertig): Java 21 LTS, Kotlin, Spring Boot, PostgreSQL 17, PostGIS, RabbitMQ, Redis, Keycloak, MinIO, Valhalla, OR-Tools, Flutter, Node/React mit Patchstand, Lizenz und Supportende (Kap. 01 §1.1 „Patchstände, CVEs, Lizenzen und Kompatibilität in BOM/Lockfiles festzuschreiben“).
  - Aktualisierung von `README.md` der Wurzel (Einstieg für Entwickler).
- **Detailverträge vorab:** Liste der Verzeichnisse und Teamzuständigkeiten; Namenskonvention für Module (`identity`, `config`, `service-geo`, `booking-dispatch`, `journey-federation`, `fare-payment`, `fleet-safety`, `staff-time`, `documents-finance`, `communication-support`, `analytics`).
- **Akzeptanzkriterien:**
  1. Jedes der elf Verzeichnisse aus Umsetzungsplan §3 existiert und enthält ein README mit Zweck und Verantwortlichem.
  2. Die Versionsmatrix nennt für jeden Baustein der Tabelle in Kap. 01 §1.1 eine konkrete Version, Lizenz und Quelle; kein Eintrag lautet „latest“.
  3. `CODEOWNERS` deckt jedes Verzeichnis ab; ein Merge ohne Review ist technisch gesperrt.
  4. `python docs/pflichtenheft/validate_artifacts.py` läuft nach der Umstrukturierung weiterhin mit `result: PASS`.
- **Tests:** positiv: Skript prüft Existenz der Verzeichnisse und README. negativ: Entfernen eines README lässt die Prüfung fehlschlagen.
- **Abhängigkeiten:** intern: keine. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Beschluss D-12 zu Team und Zuständigkeiten für die Belegung von `CODEOWNERS`.
- **Entscheidungen:** D-12 (blockiert nicht den Bau; ohne Beschluss stehen Rollen statt Personen in `CODEOWNERS`).
- **Größe:** S
- **Nicht im Umfang:** Build-Dateien der Teilprojekte (WP-M0-02, -05, -06, -07); CI (WP-M0-12 bis -14, -17).

### WP-M0-02 – Backend-Gerüst `backend/` (Gradle-Multiprojekt)

- **Ziel:** Ein leeres, baubares und startfähiges Kotlin/Spring-Boot-Deployable mit allen Teilprojekten der Zielstruktur; jedes Modul hat eine explizite, noch leere Modul-API.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Gradle-Multiprojekt mit `backend/platform`, `backend/app`, `backend/modules/identity`, `backend/modules/config`, `backend/modules/service-geo`, `backend/modules/booking-dispatch`, `backend/modules/journey-federation`, `backend/modules/fare-payment`, `backend/modules/fleet-safety`, `backend/modules/staff-time`, `backend/modules/documents-finance`, `backend/modules/communication-support`, `backend/modules/analytics`, `backend/adapters/psp`, `backend/adapters/partner-tomp`, `backend/adapters/partner-osdm`, `backend/adapters/timetable-gtfs`, `backend/adapters/erp-export`, `backend/adapters/ocr`, `backend/adapters/notification`.
  - Je Modul Paketkonvention `api` (öffentliche Ports) und `internal`; in `platform` Platzhalterpakete für Tenantkontext, RLS-Anbindung, Outbox/Inbox, Idempotenz, Audit, Geld- und Zeittypen (Inhalt erst in M1).
  - Zentrale BOM/Versionskatalog, Dependency-Locking eingeschaltet, Gradle-Wrapper mit Prüfsumme.
  - `backend/app`: startfähige Anwendung mit Health-Endpunkt, Datenbankverbindung unter der Runtime-Rolle, ohne Fachendpunkte.
  - Testbasis: JUnit, Testcontainers-Abhängigkeit, ein Beispieltest je Teilprojekt.
- **Detailverträge vorab:** Modulliste und erlaubte Abhängigkeitsrichtungen (Modul → `platform`; Adapter → Modul-API; `app` → alle); Zuordnung der führenden Aggregate je Modul gemäß Kap. 01 §1.3 als Kommentar im jeweiligen README.
- **Akzeptanzkriterien:**
  1. `./gradlew build` baut alle 20 Teilprojekte ohne Netzwerkzugriff auf nicht festgeschriebene Versionen.
  2. `backend/app` startet gegen die lokale Umgebung und meldet Health „UP“; die Datenbankverbindung nutzt nachweislich die Runtime-Rolle, nicht den Migrator.
  3. Kein Modul hat eine Kompilierabhängigkeit auf das `internal`-Paket eines anderen Moduls.
  4. Es existiert genau ein Deployable (Leitlinie 1, Kap. 01 §1.1); Python-Worker sind nicht Teil des Gradle-Builds.
- **Tests:** positiv: Build und Kontextstart. negativ: absichtlich eingefügter Import auf fremdes `internal`-Paket bricht den Build (wird in WP-M0-03 dauerhaft abgesichert).
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Implementierung von Tenantkontext, Outbox, Idempotenz, Audit (M1); Security-Konfiguration mit OIDC (M1); Fachlogik jeder Art.

### WP-M0-03 – Architekturtest Modulgrenzen

- **Ziel:** CI erzwingt, dass ein Modul nur über explizite Modul-APIs kommuniziert und ausschließlich eigene Tabellen schreibt (Leitlinie 1, Kap. 01 §1.3).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Architekturtests (z. B. ArchUnit) für Paketabhängigkeiten zwischen den elf Modulen, `platform`, `app` und `adapters`.
  - Datenbank: Zuordnungsdatei „Tabelle → besitzendes Modul“ für alle 38 Tabellen aus `kernschema.sql` (`tenant`, `currency`, `legal_entity`, `organization`, `membership`, `config_revision`, `service`, `service_area`, `stop_revision`, `partner_contract_version`, `cooperation_grant`, `passenger_private`, `travel_attempt`, `fare_quote`, `journey`, `journey_leg`, `operation`, `saga_step`, `vehicle`, `staff_shift`, `resource_release`, `vehicle_run`, `capacity_hold`, `booking`, `payment_intent`, `expense_document`, `cost_object`, `economic_event`, `allocation_set`, `allocation_line`, `fiscal_period`, `journal`, `journal_line`, `settlement_line`, `idempotency_record`, `outbox`, `inbox`, `audit_event`); Test, der Schreibzugriffe eines Moduls auf fremde Tabellen meldet.
  - Regel „kein PSP-/Partneraufruf innerhalb offener SQL-Transaktion“ (Leitlinie 3) als dokumentierte, später automatisierbare Prüfregel.
  - Regel zu Leitlinie 4: Redis-Client-Klassen dürfen nur aus einem benannten Cache-/Rate-Limit-Paket in `platform` verwendet werden; kein Fachmodul hält Kapazitäts-, Preis- oder Zahlungszustand in Redis (Kap. 01 §1.1 „Kein führender Kapazitäts-, Preis- oder Zahlungszustand“).
  - Regel zu Leitlinie 5: In Geldtypen und in allen Feldern mit Namensendung `_minor` sind `Float`/`Double` verboten (Kap. 03 §2.1, Kap. 04 §4.1 „Keine `float`-Beträge“); das datenbankseitige Gegenstück liegt in WP-M0-17.
  - Übersicht „Leitlinie 1–7 → prüfende CI-Regel bzw. Zielmeilenstein“, weil Umsetzungsplan §2 verlangt, dass die Leitlinien ab dem ersten Commit in CI geprüft werden.
- **Detailverträge vorab:** Tabellenbesitz je Modul, abgeleitet aus Kap. 01 §1.3 und Kap. 04 §4.3; Sonderfall der Plattformtabellen `idempotency_record`, `outbox`, `inbox`, `audit_event` (Besitz `platform`).
- **Akzeptanzkriterien:**
  1. Jede der 38 Kerntabellen ist genau einem Besitzer zugeordnet; die Prüfung schlägt fehl, wenn eine Tabelle in einer Migration ohne Zuordnung erscheint.
  2. Ein Testmodul, das eine fremde Tabelle schreibt oder ein fremdes `internal`-Paket importiert, lässt die CI fehlschlagen.
  3. Die Regelmenge ist im Beitragsleitfaden verlinkt.
  4. Ein Probemodul mit direktem Redis-Zugriff außerhalb des Cache-Pakets und ein Geldfeld vom Typ `Double` lassen die CI jeweils fehlschlagen.
  5. Für jede der sieben Leitlinien aus Umsetzungsplan §2 nennt die Übersicht eine aktive Regel oder den Meilenstein, ab dem sie automatisiert geprüft wird; keine Leitlinie bleibt ohne Eintrag.
- **Tests:** positiv: aktueller Stand grün. negativ: vier absichtliche Verstöße (Import, Fremdtabellenzugriff, Redis außerhalb des Cache-Pakets, Gleitkomma-Geldfeld) als „rote“ Selbsttests der Regeln.
- **Abhängigkeiten:** intern: WP-M0-02. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Durchsetzung über getrennte Modul-DB-Zugänge (Rollen in WP-M0-15); Tabellen aus Kap. 04 §4.4 (werden mit ihrer jeweiligen Migration zugeordnet).

### WP-M0-04 – Vertragsverzeichnis `api/` (OpenAPI 3.1 je Modul)

- **Ziel:** `api/` ist die bearbeitbare Quelle der API-Verträge; der Ausgangsstand `docs/pflichtenheft/api/openapi.yaml` ist inhaltsgleich übernommen und je Modul aufgeteilt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: Aufteilung der 14 Kernpfade in Moduldateien ohne inhaltliche Änderung: `/v1/federation/journeys/plan`, `/v1/federation/offers/{offer_id}`, `/v1/federation/offers/{offer_id}/hold`, `/v1/federation/journeys/{journey_id}`, `/v1/federation/journeys/{journey_id}/confirm`, `/v1/federation/journeys/{journey_id}/cancellation-quotes`, `/v1/federation/journeys/{journey_id}/cancel`, `/v1/operations/{operation_id}`, `/v1/cost-events/{cost_event_id}/allocations`, `/v1/expenses/{expense_id}/approve`, `/v1/config/revisions/{revision_id}/publish`, `/v1/rosters/{roster_id}/publish`, `/v1/driver/events`, `/v1/federation/partners/{partner_id}/events`.
  - Gemeinsame Komponenten-Datei (Problem-Antwort, `Traceparent`, `IdempotencyKey`, Geldtypen einschließlich `SignedMoney`, Listenhülle `{items,next_cursor,as_of}`), auf die alle Moduldateien verweisen.
  - Bündelungsschritt, der wieder eine Gesamtdatei erzeugt; Lint-Regelsatz für die Konventionen aus Kap. 05 §5.1 (Pfadpräfix `/v1`, eindeutige `operationId`, `application/problem+json`, ETag/`If-Match` bei mutierenden Revisionseingriffen, `Idempotency-Key`).
  - Festlegung, welche Datei führend ist (`api/` oder `docs/pflichtenheft/api/openapi.yaml`) und wie die andere daraus erzeugt wird.
  - Unveränderte Übernahme der beiden Sicherheitsschemata `oidc` (mit Scopes wie `journeys:plan`, `journeys:book`, `driver:events`) und `partnerSignature` sowie der gemeinsamen Parameter `IdempotencyKey`, `IfMatch`, `Traceparent`.
  - Ausnahmeliste des Linters, abgeleitet aus dem Ausgangsstand: `receivePartnerEvent` (`/v1/federation/partners/{partner_id}/events`) trägt keinen `Idempotency-Key`, sondern Signaturheader und Inbox-Deduplikation (Kap. 05 §5.6); `planJourney` und `recordDriverEvent` tragen `Idempotency-Key`, aber kein `If-Match`. Jede Ausnahme ist namentlich begründet, neue Ausnahmen brauchen Review.
  - Ereignisse/Outbox: Ablageort und Namensschema für versionierte Ereignis-JSON-Schemas (die Spalte `outbox.schema_version` verlangt eine Schemaversion; Kap. 01 §1.8 verlangt „kompatible Events“). In M0 nur Verzeichnis, Konvention und Lint auf gültiges JSON Schema; Inhalte entstehen je Meilenstein. Der Ort ist in Umsetzungsplan §3 nicht vorgesehen (siehe Offene Punkte 25).
- **Detailverträge vorab:** Zuordnung der 14 Pfade und 42 Schemas zu Modulen; Regelsatz des Linters einschließlich der Liste, welche Operationen als „mutierender Revisionseingriff“ `If-Match` verlangen (im Ausgangsstand: `holdOffer`, `confirmJourney`, `quoteCancellation`, `cancelJourney`, `allocateCost`, `approveExpense`, `publishConfig`, `publishRoster`).
- **Akzeptanzkriterien:**
  1. Die gebündelte Datei aus `api/` ist semantisch gleich dem Ausgangsstand: 14 Pfade, 42 Schemas, 14 eindeutige `operationId`s; alle 9 Schema-Beispiele validieren.
  2. `validate_artifacts.py` läuft weiterhin grün gegen die Pflichtenheftdatei.
  3. Der Linter meldet einen Verstoß, wenn ein Pfad ohne `/v1` oder eine mutierende Operation ohne `Idempotency-Key`-Parameter ergänzt wird und sie nicht in der begründeten Ausnahmeliste steht; der unveränderte Ausgangsstand mit seinen 14 Operationen läuft ohne Verstoß durch.
  4. Die führende Quelle ist dokumentiert; eine Abweichung zwischen beiden Ständen wird maschinell erkannt.
  5. Jede Operation behält ihr Sicherheitsschema und ihre Scopes; eine Operation ohne `security`-Angabe wird vom Linter abgewiesen.
- **Tests:** positiv: Bündelung und Gleichheitsprüfung. negativ: doppelte `operationId`, nicht auflösbares `$ref`, ungültiges Schema-Beispiel, Operation ohne Sicherheitsschema und ungültiges Ereignis-JSON-Schema führen jeweils zum Abbruch.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Neue Endpunkte aus Kap. 05 §5.7 (entstehen als Detailverträge der jeweiligen Meilensteine); Codegenerierung für Server/Clients (Entscheidung in M1).

### WP-M0-05 – Web-Gerüst `web/`

- **Ziel:** Ein baubares TypeScript/React-Monorepo mit den Paketen `design-system`, `passenger` und `operator`, jeweils mit leerer Startseite.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: Workspace mit `web/design-system`, `web/passenger`, `web/operator` (inkl. künftiger Bereiche Callcenter, Finanzen, Personal, Admin als Routen-Platzhalter); keine UI-IDs aus Kap. 06 §6.9 umgesetzt.
  - Lockfile, strikte TypeScript-Konfiguration, Lint, Unit-Test-Runner, Komponententest-Beispiel.
  - Automatisierte Barrierefreiheitsprüfung (Regelsatz) als Testbaustein im `design-system`, da Kap. 08 §8.4 Tastatur- und Screenreader-Bedienbarkeit als Gate nennt.
  - PWA-Grundkonfiguration (Kap. 01 §1.1 „responsive PWA“).
- **Detailverträge vorab:** Paketgrenzen (Apps hängen vom `design-system` ab, nicht umgekehrt; Apps nicht untereinander).
- **Akzeptanzkriterien:**
  1. Ein Installations- und ein Build-Befehl bauen alle drei Pakete reproduzierbar aus dem Lockfile.
  2. `passenger` und `operator` starten lokal und zeigen eine Platzhalterseite, die eine Komponente aus `design-system` verwendet.
  3. Die Barrierefreiheitsprüfung läuft im Test und schlägt bei einem absichtlich fehlerhaften Beispiel (fehlender zugänglicher Name) fehl.
- **Tests:** positiv: Build, Beispiel-Komponententest. negativ: Typfehler und Barrierefreiheitsverstoß brechen den Lauf ab.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Designsystem-Inhalte, Login, API-Anbindung, alle Bildschirme UI-01 ff. (ab M1/M2).

### WP-M0-06 – Mobile-Gerüst `mobile/driver` und `mobile/passenger`

- **Ziel:** Ein baubares Flutter-Projekt für die Driver-App auf beiden Zielplattformen; `mobile/passenger` ist als Platzhalter angelegt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - `mobile/driver`: Flutter-Projekt, festgeschriebene Flutter/Dart-Version, Lockfile, Lint, Widget-Testbeispiel, Build für Android und iOS (soweit Zielgeräteliste dies verlangt).
  - `mobile/passenger`: README mit Verweis „ab M7“ (Umsetzungsplan §3), kein Code.
  - Auswahl und Festschreibung der Bibliothek für verschlüsselte SQLite (Voraussetzung für WP-M0-28).
- **Detailverträge vorab:** Zielgeräteliste (Hersteller, Modell, Betriebssystemversion, verwaltet/nicht verwaltet) – siehe Offene Punkte.
- **Akzeptanzkriterien:**
  1. `mobile/driver` baut in CI ein installierbares Debug-Artefakt je Zielplattform.
  2. Der Widget-Test läuft grün; ein Lint-Verstoß bricht ab.
  3. `mobile/passenger` enthält ausschließlich das README.
- **Tests:** positiv: Build und Widget-Test. negativ: Lint-Verstoß.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Zielgeräteliste und Geräteverwaltung für die Driver-App (D-05, D-12).
- **Entscheidungen:** D-05 (Pilotbetreiber bestimmt Zielgeräte; blockiert nicht den Bau, ohne Angabe wird mit Referenzgeräten gebaut).
- **Größe:** S
- **Nicht im Umfang:** Offline-Queue (Spike WP-M0-28, Produktivumsetzung M3); Fahrerbildschirme UI-09 ff. (M3); native Passenger-App (M7).

### WP-M0-07 – Worker-Gerüst `workers/routing` und `workers/documents`

- **Ziel:** Zwei baubare Python-Projekte mit festgeschriebenen Abhängigkeiten, Container-Build und leerem Health-Endpunkt bzw. Queue-Consumer-Skelett.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `workers/routing` (Python, OR-Tools als Abhängigkeit, Valhalla-Adapter-Schnittstelle als leeres Interface); `workers/documents` (Schnittstellen-Platzhalter für OCR-Adapter, Malware-Scan, KoSIT-Validator).
  - Je Projekt: Lockfile mit Hashes, Lint, Typprüfung, Unit-Test-Runner, Dockerfile.
  - Beschreibung der Aufrufschnittstelle Kern ↔ Worker als offener Vertragspunkt (Queue über RabbitMQ oder synchroner Aufruf) – wird in M2 (Routing) bzw. M4 (Dokumente) entschieden.
- **Detailverträge vorab:** keine fachlichen; nur Projektlayout und Abhängigkeitsliste.
- **Akzeptanzkriterien:**
  1. Beide Projekte bauen reproduzierbar ein Container-Image aus dem Lockfile.
  2. Ein Beispieltest je Projekt läuft grün; OR-Tools lässt sich im Container importieren und löst ein Trivialproblem.
  3. Kein Worker hat Schreibzugriff auf die Datenbank konfiguriert.
- **Tests:** positiv: Build, Beispieltest. negativ: Lint-/Typfehler brechen ab.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Einfügeheuristik (Spike WP-M0-27, Produktiv M2); OCR, Malware-Scan, KoSIT-Validierung (M4).

### WP-M0-08 – Lokale Umgebung `infra/` per docker-compose

- **Ziel:** Ein Befehl startet auf einem Entwicklerrechner PostgreSQL 17/PostGIS, RabbitMQ, Redis, Keycloak, MinIO und Valhalla in festgeschriebenen Versionen; ein zweiter setzt alles zurück.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - `infra/docker-compose.yml` mit Diensten PostgreSQL 17 + PostGIS, RabbitMQ (Quorum Queues aktivierbar), Redis, Keycloak, MinIO (Versionierung aktivierbar), Valhalla; Images per Digest festgeschrieben.
  - Health-Checks je Dienst, Start- und Reset-Skript, Portübersicht, `.env.example` ohne echte Geheimnisse.
  - Valhalla: dokumentierter Bezug eines kleinen OSM-Ausschnitts als Entwicklungsdatenstand samt Lizenzhinweis.
  - Hinweis im README: Redis ist ausschließlich Cache und Rate-Limit (Leitlinie 4); Produktionsdaten werden nicht in Entwicklung kopiert (Kap. 01 §1.7).
- **Detailverträge vorab:** Dienstliste mit Versionen aus WP-M0-01; Namens- und Portkonvention.
- **Akzeptanzkriterien:**
  1. Auf einem frischen Rechner sind nach dem Startbefehl alle sechs Dienste „healthy“.
  2. `SELECT postgis_version()` liefert eine Version; RabbitMQ nimmt eine Quorum Queue an; MinIO nimmt ein Objekt in einem versionierten Bucket an; Valhalla beantwortet eine Routenanfrage im geladenen Ausschnitt.
  3. Der Reset-Befehl stellt den Ausgangszustand her (leere Datenbank, leere Queues und Buckets).
  4. Im Repository liegen keine Geheimnisse; ein Secret-Scan (WP-M0-14) bleibt grün.
- **Tests:** positiv: Smoke-Skript prüft die sechs Dienste. negativ: Smoke-Skript meldet verständlich, welcher Dienst fehlt, wenn einer gestoppt ist.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine.
- **Entscheidungen:** D-09 (Valhalla/OSM ist Arbeitsstand; blockiert nur die Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Rollen und Schema in der Datenbank (WP-M0-15, -16); Keycloak-Inhalte (WP-M0-09); Staging/Produktion (WP-M0-11).

### WP-M0-09 – Keycloak-Realm und Testidentitäten

- **Ziel:** Die lokale Umgebung enthält einen importierbaren Realm mit Testakteuren zweier Mandanten, damit Integrations- und Akzeptanztests echte OIDC-Tokens verwenden können.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Versionierter Realm-Export: Clients für Passenger Web, Operator, Driver (Authorization Code + PKCE) und ein Maschinen-Client je Adaptertyp (Client Credentials) gemäß Kap. 05 §5.1.
  - Testakteure: je Mandant A und B mindestens ein Fahrgast, Disponent, Fahrer, Finanzrolle, Tenantadmin; ein Akteur mit Mitgliedschaft in beiden Mandanten (für Tenantwechsel); ein Akteur ohne Mitgliedschaft.
  - Hilfsbibliothek für Tests zum Beziehen von Tokens.
  - Kennzeichnung, welche Rollen als „privilegiert“ MFA benötigen (Kap. 01 §1.7) – als Konfiguration vorbereitet, Durchsetzung in M1.
- **Detailverträge vorab:** vorläufige Rollen- und Scope-Namen (z. B. `driver:events` aus `openapi.yaml`); endgültiger Rollenkatalog ist Detailvertrag von M1 (IAM).
- **Akzeptanzkriterien:**
  1. Der Realm lässt sich ohne manuelle Schritte importieren; der Reset aus WP-M0-08 stellt ihn wieder her.
  2. Für jeden Testakteur lässt sich ein Token beziehen, dessen Issuer, Audience und Ablauf prüfbar sind.
  3. Der Realm enthält keine echten Personen- oder Zugangsdaten.
- **Tests:** positiv: Tokenbezug je Akteur. negativ: falsches Passwort bzw. falsches Client-Secret liefert kein Token.
- **Abhängigkeiten:** intern: WP-M0-08. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Tokenprüfung im Gateway, Membership-Prüfung, Sitzungssperre, MFA-Durchsetzung (M1); `membership`-Datensätze (Fixtures in WP-M0-22).

### WP-M0-10 – Observability-Grundgerüst

- **Ziel:** Das Backend-Gerüst erzeugt strukturierte Logs mit Korrelations-ID, Traces und Prometheus-kompatible Metriken; lokal sind sie einsehbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: OpenTelemetry-Einbindung, W3C `traceparent`-Übernahme, neue interne Correlation-ID bei ungültigem Header (Kap. 05 §5.1), strukturiertes Logformat.
  - `infra/`: lokaler Collector und Anzeige für Traces und Metriken als optionales Compose-Profil.
  - Regel und automatischer Test: keine Fahrgastkontakte, Personen- oder Ortssuchen in Metriklabels oder Logs (Kap. 01 §1.1, Kap. 05 §5.1).
- **Detailverträge vorab:** Logfeld-Schema (Pflichtfelder: Zeit, Level, `correlation_id`, Trace-ID, Modul; Tenant nur als opake ID); Namenskonvention für Metriken.
- **Akzeptanzkriterien:**
  1. Ein Aufruf des Health-Endpunkts mit gültigem `traceparent` erzeugt einen Trace mit derselben Trace-ID und eine Logzeile mit `correlation_id`.
  2. Ein ungültiger `traceparent` führt zu einer neuen Correlation-ID, nicht zu einem Fehler.
  3. Der Metrik-Endpunkt ist erreichbar; der Label-Test schlägt fehl, wenn eine Metrik ein als personenbezogen markiertes Label erhält.
- **Tests:** positiv: Trace- und Logkorrelation. negativ: ungültiger Header; verbotenes Label.
- **Abhängigkeiten:** intern: WP-M0-02, WP-M0-08. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fachliche Kennzahlen aus `kataloge.csv` (M4); Alarmierung, synthetische Proben, SLO-Messung (M5).

### WP-M0-11 – IaC- und Umgebungsgerüst

- **Ziel:** Die vier Umgebungen Development, Staging, Performance, Production (Kap. 01 §1.8) sind als deklaratives Gerüst mit getrennten Geheimnis-, PSP- und Identitätsdomänen beschrieben; eine gemeinsame Integrationsumgebung für M1 ist bereitstellbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - `infra/`: IaC-Modulstruktur je Umgebung, Parameterdateien, Konzept zur Geheimnisverwaltung (keine Geheimnisse im Repository), Namenskonvention.
  - Bereitstellungsbeschreibung für OCI-Container auf verwalteter Containerplattform, verwaltete PostgreSQL- und Objektspeicherdienste, EU-Hosting (Kap. 01 §1.1) – anbieterneutral, bis D-05 die Hostingregion festlegt.
  - Trennung des Migrationszugangs vom Laufzeitzugang auch in der Bereitstellung („separater kurzlebiger Betriebszugang“, Kap. 01 §1.7).
- **Detailverträge vorab:** Umgebungsmatrix (Zweck, Datenklasse, Zugriffsberechtigte).
- **Akzeptanzkriterien:**
  1. Die IaC-Definition lässt sich für die Development-/Integrationsumgebung validieren („plan“ ohne Fehler) und – sofern ein Hostingkonto vorliegt – anwenden.
  2. Jede Umgebung hat eigene Geheimnis- und Identitätsdomänen; ein Prüfskript findet keine umgebungsübergreifend geteilten Zugangsdaten.
  3. Die offene Hostingentscheidung ist als Parameter gekapselt, nicht im Code verteilt.
- **Tests:** positiv: Validierung der IaC-Definition in CI. negativ: fehlender Pflichtparameter bricht die Validierung ab.
- **Abhängigkeiten:** intern: WP-M0-01. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Hostingregion, Hostinganbieter und Hostingkonto (D-05).
- **Entscheidungen:** D-05 (Hosting; blockiert die Anwendung auf eine reale Umgebung, nicht das Gerüst); D-02 (Isolationsstufe; Gerüst geht vom RLS-Default aus).
- **Größe:** S
- **Nicht im Umfang:** Automatisches Ausrollen und Betrieb der gemeinsamen Integrationsumgebung (WP-M0-35); PITR, Backup, Restore-Nachweis, Failover (M5); Produktionsbereitstellung.

### WP-M0-12 – CI-Stufe Build, Unit-Test, Lint

- **Ziel:** Jeder Merge-Request baut und testet alle Teilprojekte; ein Fehler in einem Teilprojekt verhindert den Merge.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Pipeline-Definition mit Jobs für `backend/` (inkl. Architekturtest aus WP-M0-03), `web/`, `mobile/driver`, `workers/routing`, `workers/documents`.
  - Zwischenspeicherung von Abhängigkeiten, Testberichte als Artefakte, eindeutige Build-ID je Lauf (wird in `e2e.csv`-Evidenz verwendet, WP-M0-32).
  - Pflichtstatus im Merge-Schutz.
  - Ausführungsumgebung je Job festgelegt; für den iOS-Build von `mobile/driver` ist eine macOS-Ausführungsumgebung nötig, sofern die Zielgeräteliste iOS enthält (siehe Offene Punkte 1 und 18).
- **Detailverträge vorab:** Format der Build-ID; Liste der Pflichtjobs.
- **Akzeptanzkriterien:**
  1. Ein Merge-Request mit fehlschlagendem Unit-Test in einem beliebigen Teilprojekt kann nicht gemergt werden.
  2. Jeder Lauf veröffentlicht eine Build-ID und Testberichte.
  3. Der Architekturtest ist Pflichtjob.
- **Tests:** positiv: grüner Lauf auf dem Hauptzweig. negativ: je Teilprojekt ein absichtlich roter Probe-Merge-Request.
- **Abhängigkeiten:** intern: WP-M0-02, WP-M0-03, WP-M0-05, WP-M0-06, WP-M0-07. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Vertragsprüfung (WP-M0-13), Lieferkette (WP-M0-14), Datenbankstufe (WP-M0-17), Nachverfolgung (WP-M0-32).

### WP-M0-13 – CI-Stufe Vertragsprüfung

- **Ziel:** `validate_artifacts.py` und die OpenAPI-Prüfungen sind Pflichtschritt jeder Pipeline; Vertragsbrüche werden vor dem Merge sichtbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Pipeline-Job mit reproduzierbarer Python-Umgebung (`PyYAML`, `jsonschema`, `openapi-spec-validator`, `pglast` mit festgeschriebenen Versionen) für `python docs/pflichtenheft/validate_artifacts.py`.
  - Job für Lint und Bündelung von `api/` (WP-M0-04) sowie Erkennung inkompatibler Änderungen gegenüber dem Hauptzweig (Kap. 05 §5.1: Breaking Changes benötigen neue Major-Version).
  - Platzhalter-Job „semantische Contract-Tests“ mit der Liste der zwölf Fälle aus Kap. 05 §5.8 (zwei Betreiber/letzter Platz; abgelaufene Holds; SCA länger als Hold; Confirm-Timeout mit spät positivem Status; Storno unbekannt; 100 identische Webhooks; Manipulation tenant_id; unzulässige Fremd-ID; EUR-Rundungsrest; geschlossene Periode; Allokationssumme ≠ Basis; Offline-Check-in auf alter Planrevision), je Fall mit dem Meilenstein, ab dem er Pflicht wird.
- **Detailverträge vorab:** Definition „inkompatible Änderung“; Zuordnung der zwölf Fälle zu Meilensteinen.
- **Akzeptanzkriterien:**
  1. Die Pipeline schlägt fehl, wenn `validate_artifacts.py` nicht `result: PASS` liefert.
  2. Entfernen eines Pflichtfelds aus einem Antwortschema ohne Versionswechsel wird als inkompatibel gemeldet.
  3. Die zwölf Contract-Fälle sind als benannte, noch übersprungene Tests mit Zielmeilenstein vorhanden; ein Fall kann nicht stillschweigend gelöscht werden (Zählprüfung = 12).
- **Tests:** positiv: grüner Lauf. negativ: manipulierte CSV-Zeile, ungültiges Schema-Beispiel und inkompatible API-Änderung brechen je einzeln ab.
- **Abhängigkeiten:** intern: WP-M0-04, WP-M0-12. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Implementierung der zwölf Contract-Tests (jeweiliger Meilenstein); Laufzeit-Contract-Tests gegen laufende API (ab M1).

### WP-M0-14 – CI-Stufe Lieferkette (Lockfiles, SBOM, signierte Images)

- **Ziel:** Jedes gebaute Artefakt ist reproduzierbar, hat eine SBOM und eine prüfbare Signatur; bekannte kritische Schwachstellen und unzulässige Lizenzen blockieren.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Lockfile-Pflichtprüfung für Gradle, Node, Python und Flutter.
  - SBOM-Erzeugung je Image und je Build; CVE- und Lizenzprüfung mit dokumentierter Ausnahmeliste.
  - Container-Build für `backend/app`, `workers/routing`, `workers/documents`; Signatur der Images und Verifikationsschritt; Basis-Images per Digest.
  - Secret-Scan über Repository und Merge-Requests.
  - Zeitgesteuerter Lauf der CVE- und Lizenzprüfung auf dem Hauptzweig, damit neu bekannt gewordene Schwachstellen in bereits festgeschriebenen Versionen auffallen (Kap. 01 §1.1 „unterstützte Patchstände, CVEs“); Ergebnis fließt in die Versionsmatrix aus WP-M0-01.
- **Detailverträge vorab:** Schwellenwert für blockierende Schwachstellen und erlaubte Lizenzliste (Vorschlag dieses Plans; fachlich vom Auftraggeber zu bestätigen); Verfahren für Ausnahmen mit Ablaufdatum.
- **Akzeptanzkriterien:**
  1. Ein Build mit verändertem, nicht festgeschriebenem Abhängigkeitsstand schlägt fehl.
  2. Zu jedem Image existieren SBOM und Signatur; die Verifikation eines unsignierten oder manipulierten Images schlägt fehl.
  3. Eine Testabhängigkeit mit bekannter kritischer Schwachstelle blockiert die Pipeline.
  4. Ein eingechecktes Testgeheimnis wird erkannt.
  5. Der zeitgesteuerte Lauf meldet eine nachträglich als verwundbar markierte Testabhängigkeit, ohne dass ein Merge-Request vorliegt.
- **Tests:** positiv: signiertes Image wird verifiziert. negativ: unsigniertes Image, veraltetes Lockfile, Testgeheimnis, verwundbare Testabhängigkeit.
- **Abhängigkeiten:** intern: WP-M0-12. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Pentest und Behandlung von Befunden (M5); gestaffeltes Rollout (M5).

### WP-M0-15 – Datenbankrollen Migrator, Runtime, Modulzugänge

- **Ziel:** Die Datenbank hat getrennte Rollen gemäß Kap. 04 §4.6; die Runtime-Rolle besitzt weder Superuser noch BYPASSRLS noch Tabelleneigentum.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Bootstrap-Skript (außerhalb der Fachmigrationen) für die Rollen Migrator/Tabelleneigentümer, Runtime (NOSUPERUSER, NOBYPASSRLS, kein Eigentum, kein TRUNCATE, kein DDL) und vorbereitete Modulzugänge für PII, HR und Finanzen (Kap. 04 §4.6 „eigene Modulzugänge und explizite Spalten-/Viewrechte“).
  - Geschlossene Default-Rechte (`REVOKE` auf Schema `public` und Schema `taktovia`); wiederholbare Grant-Migration, die Runtime-DML-Rechte je Tabelle ausdrücklich vergibt.
  - Prüfabfrage, die Rollenattribute und Tabelleneigentum ausliest und als Test verwendet wird.
  - Klärung, unter welcher Rolle `CREATE EXTENSION postgis` läuft (siehe Offene Punkte).
  - Ausdrückliche Rechte neben DML: `USAGE` auf Schema `taktovia`, `EXECUTE` auf `current_tenant()`; für die Triggerfunktionen `protect_journal_line()` und `guard_journal()` die Rechte, die deren `SELECT … FOR UPDATE` auf `journal` und `fiscal_period` unter Aufruferrechten verlangt (siehe Offene Punkte 8).
  - Festlegung für die globale Tabelle `currency`: Runtime nur lesend; Pflege ausschließlich über Migration (Vorschlag dieses Plans, siehe Offene Punkte 9).
- **Detailverträge vorab:** Rollenmatrix „Rolle × Tabelle × Recht“ für die 38 Kerntabellen; Liste der Tabellen, auf die die Runtime-Rolle kein UPDATE/DELETE erhält (`economic_event`, `audit_event`, `partner_contract_version`, `fare_quote` sind zusätzlich per Trigger geschützt).
- **Akzeptanzkriterien:**
  1. Die Prüfabfrage belegt für die Runtime-Rolle: `rolsuper = false`, `rolbypassrls = false`, kein Eigentum an Schema oder Tabellen in `taktovia`.
  2. Unter der Runtime-Rolle schlagen `CREATE TABLE`, `ALTER TABLE`, `TRUNCATE` und `SET ROLE` auf den Migrator fehl.
  3. Der Migrator-Zugang ist in der Laufzeitkonfiguration von `backend/app` nicht vorhanden.
  4. Ein neuer Tabellenname ohne Eintrag in der Rollenmatrix lässt die Prüfung fehlschlagen (keine impliziten Rechte).
  5. Unter der Runtime-Rolle schlagen INSERT, UPDATE und DELETE auf `currency` fehl; `SELECT` gelingt auch ohne Tenantkontext.
  6. Die Rolle `PUBLIC` hat weder im Schema `public` noch im Schema `taktovia` Anlage- oder Ausführungsrechte, die über die Rollenmatrix hinausgehen.
- **Tests:** positiv: Runtime-Rolle kann mit gesetztem Tenantkontext erlaubte DML ausführen. negativ: DDL, TRUNCATE, Rollenwechsel, Zugriff auf Tabelle ohne Grant. konkurrierend (Sicherheit): zwei gleichzeitige Verbindungen der Runtime-Rolle mit unterschiedlichem Tenantkontext beeinflussen sich nicht (Grundfall; vertieft in WP-M0-18).
- **Abhängigkeiten:** intern: WP-M0-08. extern: keine.
- **Entscheidungen:** D-02 (Isolationsstufe; Bau folgt dem RLS-Favoriten, eine spätere Datenbank je Mandant wäre Umbau – blockiert nicht).
- **Größe:** S
- **Nicht im Umfang:** Spalten- und Viewrechte für konkrete PII-/HR-/Finanztabellen (mit den jeweiligen Tabellen ab M1/M4); zeitlich begrenzter Betriebszugriff/Break-glass (M8, MUL-018).

### WP-M0-16 – Kernschema als erste Flyway-Migration

- **Ziel:** `docs/pflichtenheft/schema/kernschema.sql` läuft als versionierte Flyway-Migration unter der Migrator-Rolle fehlerfrei auf einer leeren PostgreSQL-17/PostGIS-Datenbank.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: `db/migrations/V1__kernschema.sql` (oder gleichwertige Aufteilung) mit allen 61 Top-Level-Anweisungen, darunter 38 Tabellen: `tenant`, `currency`, `legal_entity`, `organization`, `membership`, `config_revision`, `service`, `service_area`, `stop_revision`, `partner_contract_version`, `cooperation_grant`, `passenger_private`, `travel_attempt`, `fare_quote`, `journey`, `journey_leg`, `operation`, `saga_step`, `vehicle`, `staff_shift`, `resource_release`, `vehicle_run`, `capacity_hold`, `booking`, `payment_intent`, `expense_document`, `cost_object`, `economic_event`, `allocation_set`, `allocation_line`, `fiscal_period`, `journal`, `journal_line`, `settlement_line`, `idempotency_record`, `outbox`, `inbox`, `audit_event`.
  - Funktionen `current_tenant()`, `reject_mutation()`, `protect_journal_line()`, `guard_journal()`; Trigger `economic_immutable`, `audit_immutable`, `contract_immutable`, `quote_immutable`, `journal_line_guard`, `journal_guard`; Policies `tenant_isolation` (36 Tabellen mit `tenant_id`) und `own_tenant` (Tabelle `tenant`); Indizes `service_area_geo`, `stop_geo`, `expense_hash`, `outbox_unpublished`.
  - Flyway-Konfiguration (Schema `taktovia`, Historientabelle, Ausführung nur unter Migrator), Migrationsbefehl lokal und in CI.
  - Anpassungsprotokoll: jede Abweichung vom Entwurfs-DDL (z. B. Entfernen von `BEGIN;`/`COMMIT;`, Umgang mit `SET search_path`, Trennung von `CREATE EXTENSION`) mit Begründung; Eingang für WP-M0-21.
  - Strukturvergleich: automatischer Abgleich der migrierten Datenbank gegen die Tabellenliste des Entwurfs.
- **Detailverträge vorab:** Migrationskonvention (Benennung, eine fachliche Änderung je Migration, expand/contract gemäß Kap. 04 §4.7, keine Änderung bereits ausgelieferter Migrationen, Rücknahme fachlicher Daten als Vorwärtskorrektur statt Löschen gemäß Kap. 01 §1.8); festgeschriebene PostGIS-Version passend zu PostgreSQL 17 aus der Versionsmatrix (WP-M0-01); Entscheidung, ob `docs/pflichtenheft/schema/kernschema.sql` oder `db/migrations/` künftig führend ist.
- **Akzeptanzkriterien:**
  1. Auf einer leeren Datenbank läuft die Migration ohne Fehler durch; ein zweiter Lauf ist ein No-op (Gate M0 „Kernschema migriert auf leerer Datenbank“).
  2. Nach der Migration existieren genau 38 Tabellen im Schema `taktovia`; für jede der 36 Tabellen mit `tenant_id` sowie für `tenant` gilt `relrowsecurity = true` und `relforcerowsecurity = true`; `currency` ist als bewusst globale Tabelle dokumentiert.
  3. Alle sechs Trigger und vier Funktionen sind vorhanden; die Trigger-Funktionen haben den festen `search_path` aus dem Entwurf.
  4. Eigentümer aller Objekte ist die Migrator-Rolle; die Runtime-Rolle hat nur die Rechte aus WP-M0-15.
  5. Jede Abweichung vom Entwurfs-DDL steht im Anpassungsprotokoll.
  6. Die Geometriespalten `service_area.area` (MultiPolygon, SRID 4326) und `stop_revision.point` (Point, SRID 4326) sowie die GiST-Indizes `service_area_geo` und `stop_geo` existieren; der DO-Block hat `tenant_isolation` auf genau den 36 Tabellen mit `tenant_id` angelegt und keine Tabelle ausgelassen.
- **Tests:** positiv: Migration auf leerer Datenbank; Strukturvergleich. negativ: Migration unter der Runtime-Rolle schlägt fehl; nachträglich geänderte ausgelieferte Migration wird durch Prüfsummenfehler abgewiesen.
- **Abhängigkeiten:** intern: WP-M0-15. extern: keine.
- **Entscheidungen:** D-02 (nur Hinweis; blockiert nicht).
- **Größe:** M
- **Nicht im Umfang:** Tabellen aus Kap. 04 §4.4; Härtung der `state`-Textspalten zu versionierten CHECKs (Kap. 04 §4.1, je Meilenstein); in Kap. 04 §4.5 als „noch umzusetzen“ markierte Invarianten (Umlage centgenau, nicht überlappende Versionen, Ledgerkonto-/Periodenüberlappung); Ergänzung von `created_at`, Urheber, Löschklasse; Partitionierung.

### WP-M0-17 – CI-Stufe Datenbankintegration

- **Ziel:** Jede Pipeline migriert eine frische Datenbank und führt alle Datenbank-Integrationstests unter der Runtime-Rolle aus (Leitlinie 2, Umsetzungsplan §6).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Pipeline-Job mit Testcontainers (PostgreSQL 17 + PostGIS im gleichen Image-Digest wie lokal): Rollen-Bootstrap, Migration frisch, Tests.
  - Zweiter Pfad „Migration aus freigegebenem Vorstand“ (Kap. 08 §8.4, Gate CI): Wiederherstellen des zuletzt freigegebenen Schemastands, dann Migration; in M0 mangels Vorstand als ausführbarer, leerer Pfad angelegt.
  - Wächter: Ein Integrationstest, der sich als Superuser, Tabelleneigentümer oder mit BYPASSRLS verbindet, lässt die Stufe fehlschlagen.
  - Schemakonventions-Wächter über den Katalog der migrierten Datenbank (Kap. 04 §4.1): jede Tabelle mit `tenant_id` hat einen Primärschlüssel, der mit `tenant_id` beginnt; keine Spalte vom Typ `real`, `double precision` oder `money`; Betragsspalten mit Endung `_minor` sind `bigint`; Zeitpunkte sind `timestamptz`; Geometrien tragen SRID 4326. Bewusste Ausnahmen (`tenant`, `currency`) stehen in einer begründeten Liste.
- **Detailverträge vorab:** Definition „freigegebener Vorstand“ (Tag bzw. Migrationsstand des letzten Gates).
- **Akzeptanzkriterien:**
  1. Die Stufe ist Pflichtstatus; ein Fehler in Migration oder Datenbanktest verhindert den Merge.
  2. Der Wächter erkennt einen Test, der nicht unter der Runtime-Rolle läuft.
  3. Migrationsstand und Build-ID werden im Testbericht ausgegeben (Vorgabe aus Kap. 08 §8.4 für Evidenz).
  4. Der Schemakonventions-Wächter ist auf dem Kernschema grün und schlägt bei einer Probemigration mit `double precision`-Betrag bzw. mit Primärschlüssel ohne `tenant_id` fehl.
- **Tests:** positiv: grüner Lauf mit frischer Migration. negativ: fehlerhafte Probe-Migration; Probetest mit Migrator-Verbindung; Probemigrationen mit Gleitkomma-Betrag und mit Primärschlüssel ohne `tenant_id`.
- **Abhängigkeiten:** intern: WP-M0-12, WP-M0-16. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Migration mit Testdaten beider Mandanten und Backfill-Prüfung (ab der zweiten Migration, M1); Restore-Tests (M5).

### WP-M0-18 – Tenantkontext-Testharness

- **Ziel:** Eine wiederverwendbare Testbibliothek führt Datenbankoperationen so aus, wie es die spätere Laufzeit tut (Kontext nur transaktionslokal), und beweist, dass nach Pool-Wiederverwendung kein Tenantkontext fortwirkt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend (Testcode in `backend/platform`): Hilfsfunktionen „in Transaktion als Mandant X“ mit parameterisiertem `set_config('app.tenant_id', <tenant>, true)`, „ohne Kontext“, „N parallele Transaktionen“ (Startbarriere, deterministische Auswertung), Fehler- und Abbruchinjektion.
  - Verbindungspool-Konfiguration wie für die Laufzeit vorgesehen (kleiner Pool, erzwungene Wiederverwendung derselben physischen Verbindung).
  - Statische Prüfung: sessionweites Setzen von `app.tenant_id` (`set_config(..., false)` bzw. `SET app.tenant_id`) im Produktiv- und Testcode ist verboten (Kap. 04 §4.6).
  - Minimaldaten-Erzeuger für zwei Mandanten auf SQL-Ebene (nur was die Tests aus WP-M0-19/-20 brauchen).
- **Detailverträge vorab:** Schnittstelle der Harness; Festlegung des GUC-Namens `app.tenant_id` (aus `kernschema.sql`); Verhalten bei leerem String (wird von `current_tenant()` als NULL behandelt).
- **Akzeptanzkriterien:**
  1. Nach COMMIT, nach ROLLBACK, nach Fehler mitten in der Transaktion und nach Abbruch der Clientverbindung liefert die nächste Transaktion auf derselben physischen Verbindung ohne eigenes `set_config` für jede Tabelle null Zeilen (PH-AT-001, letzter Satz).
  2. Die Harness kann mindestens 100 Transaktionen gleichzeitig starten und Ergebnis je Transaktion (Erfolg, SQLSTATE) auswerten.
  3. Die statische Prüfung schlägt bei einem sessionweiten Tenant-GUC fehl.
  4. Alle Harness-Verbindungen nutzen die Runtime-Rolle.
  5. Ein nicht als UUID lesbarer Wert in `app.tenant_id` führt zu einem Fehler und nie zu sichtbaren Zeilen (fail-closed); ein leerer String wirkt wie fehlender Kontext.
- **Tests:** positiv: Operation mit Kontext sieht eigene Zeilen. negativ: ohne Kontext keine Zeile, kein INSERT; ungültiger GUC-Wert; Wert mit SQL-Sonderzeichen wird nur parameterisiert übergeben und nie in SQL-Text eingesetzt (Kap. 04 §4.6). konkurrierend: zwei Mandanten abwechselnd auf einem Pool der Größe 1; 100 parallele Transaktionen wechselnder Mandanten auf kleinem Pool – nie eine fremde Zeile. Ermöglicht: PH-AT-001 (Datenbank- und Poolanteil).
- **Abhängigkeiten:** intern: WP-M0-02, WP-M0-16. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Produktive Tenantkontext-Komponente mit Principal-Prüfung und RequestContext (M1); API-seitige Isolation (M1).

### WP-M0-19 – RLS-Laufzeittests

- **Ziel:** Für jede Tabelle des Kernschemas ist unter der Runtime-Rolle nachgewiesen, dass RLS fail-closed wirkt und lokale Cross-Tenant-Referenzen unmöglich sind.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: generierter Testsatz über alle 36 Tabellen mit `tenant_id` und über `tenant`: (a) ohne Kontext keine Zeile lesbar und kein INSERT möglich; (b) mit Kontext A keine B-Zeile lesbar, änderbar oder löschbar; (c) INSERT mit `tenant_id` = B unter Kontext A wird durch `WITH CHECK` abgewiesen; (d) UPDATE von `tenant_id` auf B wird abgewiesen (Kap. 02 §4.2 „Änderung der Eigentums-Tenant-ID … wird verweigert“).
  - Testsatz zusammengesetzte Fremdschlüssel: je lokaler FK-Beziehung ein Versuch, auf eine B-Zeile zu verweisen (z. B. `expense_document` → `vehicle`, `booking` → `capacity_hold`, `journey_leg` → `journey`).
  - Vollständigkeitswächter: Der Test liest den Katalog aus und schlägt fehl, wenn eine Tabelle mit `tenant_id` ohne RLS/FORCE/Policy existiert oder wenn eine lokale FK ohne `tenant_id` angelegt wurde.
  - Befundliste für WP-M0-21 (u. a. Verhalten von `currency`, Sichtbarkeit von `tenant`).
  - Sonderfall `tenant`: Die Policy `own_tenant` prüft `id` statt `tenant_id`; die Fälle (c) und (d) werden dort mit der Spalte `id` ausgeführt. Der konkurrierende Fall „gleiche `id` in zwei Mandanten“ gilt für `tenant` nicht (Primärschlüssel ist `id` allein).
- **Detailverträge vorab:** Liste der lokalen FK-Beziehungen aus `kernschema.sql`; erwartete SQLSTATEs je Negativfall.
- **Akzeptanzkriterien:**
  1. Die Fälle (a) bis (d) sind für alle 37 RLS-Tabellen grün; die Tests laufen ausschließlich unter der Runtime-Rolle.
  2. Jede lokale FK-Beziehung hat einen Negativtest mit tenantfremdem Ziel; keiner gelingt (Invariante „Keine lokale Cross-Tenant-Referenz“, Kap. 04 §4.5).
  3. Invariante „Kein Mandantenkontext → keine Zeile“ (Kap. 04 §4.5) ist für jede Tabelle belegt.
  4. Der Vollständigkeitswächter schlägt bei einer Probetabelle ohne Policy fehl.
  5. Fehlermeldungen der Negativfälle enthalten keine Inhalte fremder Zeilen; wo PostgreSQL bei Schlüsselverletzungen Werte in der Fehlermeldung nennt, ist das als Befund für die M1-Regel „DB-Constraint-Fehlermeldungen gelangen nicht roh zur API“ (Kap. 02 §4.2) dokumentiert.
- **Tests:** positiv: Mandant A liest und schreibt eigene Zeilen. negativ: Fälle (a)–(d), tenantfremde FK. konkurrierend (Datentrennung, mindestens zwei parallele Transaktionen gemäß Kap. 04 §4.5): Transaktion A und B schreiben gleichzeitig in dieselbe Tabelle mit gleicher `id` – beide erfolgreich, jede sieht nur ihre Zeile; Transaktion A versucht während einer offenen B-Transaktion deren noch nicht committete Zeile zu referenzieren – abgewiesen. Ermöglicht bzw. besteht in Teilen: PH-AT-001 (Datenbankanteil), Vorarbeit für AT-18, AT-49, E2E-091.
- **Abhängigkeiten:** intern: WP-M0-18. extern: keine.
- **Entscheidungen:** D-02 (nur Hinweis).
- **Größe:** M
- **Nicht im Umfang:** API-, Such-, Export-, Medien-, Cache- und Event-Isolation (PH-AT-001 vollständig erst M1/M5); Grant-basierte Partnersichten (M6); RLS über Partitionen (erst bei Einführung von Partitionierung).

### WP-M0-20 – Trigger- und Constraint-Laufzeittests

- **Ziel:** Die im Kern-DDL als „Enthalten“ markierten Invarianten aus Kap. 04 §4.5 sind ausgeführt und mit mindestens zwei parallelen Transaktionen negativ getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank, Unveränderlichkeit: UPDATE und DELETE auf `economic_event`, `audit_event`, `partner_contract_version`, `fare_quote` werden durch `reject_mutation()` abgewiesen (SQLSTATE 23514).
  - Datenbank, Journal: `journal` kann nicht direkt als POSTED eingefügt werden; Buchen nur bei ≥ 2 Zeilen, Summe Soll = Haben, gesetztem `posted_by`, offener `fiscal_period` und Buchungsdatum innerhalb der Periode; ein gebuchtes Journal und seine `journal_line`-Zeilen sind unveränderlich; Umhängen einer Zeile auf ein anderes Journal ist verboten.
  - Datenbank, Geld-CHECKs: `payment_intent` mit `captured_minor <= authorized_minor` und `refunded_minor <= captured_minor`, keine negativen Beträge; `economic_event` mit `net_minor + tax_minor = gross_minor` und begrenzter, vorzeichengleicher `recoverable_tax_minor`; `journal_line.amount_minor > 0` mit `side` in `D`/`C`; `fare_quote` ohne negative Preisanteile; `journey_leg` mit `gross_minor >= 0` und Betrag nur zusammen mit Währung; `currency.exponent` zwischen 0 und 4.
  - Datenbank, Rechtsträger- und Währungsbindung: `journal` und `allocation_set` verweisen über den zusammengesetzten Fremdschlüssel `(tenant_id, event_id, legal_entity_id, currency)` auf `economic_event`; `journal` zusätzlich über `(tenant_id, period_id, legal_entity_id)` auf `fiscal_period`. Ein Journal mit anderem Rechtsträger oder anderer Währung als sein Ereignis bzw. mit Periode eines anderen Rechtsträgers wird abgewiesen (Kap. 04 §4.3 „Je Rechtsträger und Währung ausgeglichen“).
  - Datenbank, Kapazitäts- und Gültigkeits-CHECKs: `capacity_hold` mit `interval_to > interval_from` und `state` nur `HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`; `service_area` mit `ST_IsValid(area)`; Gültigkeitsintervalle (`valid_to > valid_from`, `effective_to > effective_from`) auf `service_area`, `stop_revision`, `partner_contract_version`, `cooperation_grant`, `config_revision`; `config_revision` nur mit `approved_by` publizierbar; `journey_leg` mit den Zeitfenster-CHECKs und der Regel, dass ein bestätigtes plattformbuchbares Segment Zusagefenster trägt.
  - Datenbank, Einmaligkeit: Unique-Schlüssel von `idempotency_record` `(tenant_id, principal_ref, command_scope, key)`, `inbox` `(tenant_id, consumer, event_id)`, `outbox` `(tenant_id, aggregate_id, aggregate_revision, event_type)`, `booking` `UNIQUE(tenant_id, hold_id)` und `UNIQUE(tenant_id, federation_ref)`, `capacity_hold` `UNIQUE(tenant_id, federation_ref)`, `payment_intent` `UNIQUE(tenant_id, provider, provider_ref)`, Quell-Unique von `economic_event` `(tenant_id, legal_entity_id, source_system, source_ref, event_type, scenario)`, `journal` `UNIQUE(tenant_id, event_id)` (ein Journal je Ereignis), `allocation_set` `UNIQUE(tenant_id, event_id, revision)`, `saga_step` `UNIQUE(tenant_id, partner_command_ref)`, `journey` `UNIQUE(tenant_id, quote_id)`, `journey_leg` `UNIQUE(tenant_id, federation_ref)`, `settlement_line` `UNIQUE(tenant_id, event_id, leg_id, creditor_ref, revision)`, `config_revision` `UNIQUE(tenant_id, scope_kind, scope_id, revision)`.
  - Prüfung, dass die Trigger unter der Runtime-Rolle funktionieren (die Funktionen sperren `journal` und `fiscal_period` mit `FOR UPDATE`; die dafür nötigen Rechte müssen in der Rollenmatrix aus WP-M0-15 stehen).
  - Zuordnungstabelle „Invariante aus Kap. 04 §4.5 → Test bzw. Zielmeilenstein“ für alle zehn Invarianten.
- **Detailverträge vorab:** erwartete Fehlercodes je Trigger; Liste der in M0 nicht prüfbaren Invarianten mit Zielmeilenstein (Umlage centgenau → M4; nicht überlappende Tarif-/Policy-/Dienstversion → M1/M2; Grantwiderruf → M6; Kapazität/Qualifikation/Fahrzeugfreigabe → M2/M3, Vorarbeit in WP-M0-26; Bestätigung/PSP-Retry → M2/M6).
- **Akzeptanzkriterien:**
  1. Jeder der sechs Trigger hat mindestens einen positiven und einen negativen Test unter der Runtime-Rolle.
  2. Invariante „Gegenereignis statt Änderung“ ist für alle vier geschützten Tabellen belegt.
  3. Invariante „Journal ausgeglichen, ≥ 2 Zeilen, offene Periode“ ist belegt, einschließlich der konkurrierenden Fälle unter „Tests“.
  4. Jeder genannte Unique-Schlüssel hat einen Test mit zwei gleichzeitigen Einfügungen: genau eine gewinnt.
  5. Die Zuordnungstabelle nennt für jede der zehn Invarianten entweder einen grünen Test oder einen Zielmeilenstein.
  6. Jeder genannte CHECK und jeder zusammengesetzte Fremdschlüssel zur Rechtsträger-/Währungsbindung hat einen Negativtest unter der Runtime-Rolle.
  7. Kein Triggerpfad lässt eine Buchung still durch, wenn die gesperrte Bezugszeile nicht gefunden wird: Ist `fiscal_period` bzw. `journal` für die Runtime-Rolle nicht lesbar oder nicht sperrbar, endet der Versuch mit Fehler und nie mit einem POSTED-Journal (die Vergleiche in `guard_journal()` und `protect_journal_line()` werten bei fehlender Zeile zu NULL aus; das Verhalten wird gemessen und als Befund an WP-M0-21 gegeben).
  8. Die konkurrierenden Journalfälle sind unter READ COMMITTED und unter REPEATABLE READ ausgeführt; je Isolationsstufe ist dokumentiert, ob das Ergebnis Abweisung, Wartezeit oder Serialisierungsfehler ist.
- **Tests:** positiv: Entwurf anlegen, Zeilen ergänzen, buchen. negativ: unausgeglichenes Journal, eine Zeile, fehlendes `posted_by`, direktes INSERT als POSTED, geschlossene Periode, Datum außerhalb der Periode (einschließlich `accounting_date = ends_on`, da die Periode rechts offen ist), UPDATE und DELETE auf gebuchtes Journal und dessen Zeilen, Umhängen einer Zeile, UPDATE/DELETE auf unveränderliche Tabellen, Verletzung der Geld-, Kapazitäts- und Gültigkeits-CHECKs, Journal mit fremdem Rechtsträger oder fremder Währung, ungültige Geometrie. konkurrierend (Geld): (1) Transaktion 1 bucht das Journal, Transaktion 2 fügt gleichzeitig eine Zeile ein – Ergebnis nie „POSTED und unausgeglichen“; (2) Transaktion 1 schließt die Periode, Transaktion 2 bucht gleichzeitig – nie ein POSTED-Journal in geschlossener Periode; (3) zwei gleichzeitige Buchungsversuche desselben Journals; (4) zwei gleichzeitige `booking`-Einfügungen auf denselben `hold_id` – genau eine Buchung; (5) zwei gleichzeitige `idempotency_record`-Einfügungen mit gleichem Schlüssel; (6) zwei gleichzeitige `inbox`-Einfügungen mit gleichem `(tenant_id, consumer, event_id)`; (7) zwei gleichzeitige `economic_event`-Einfügungen mit gleichem Quellschlüssel – genau ein Ereignis; (8) zwei gleichzeitige `journal`-Einfügungen zum selben `event_id` – genau ein Journal. Vorarbeit für PH-AT-011, PH-AT-015 und den Contract-Fall „geschlossene Periode“ (Kap. 05 §5.8).
- **Abhängigkeiten:** intern: WP-M0-18. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik für Idempotenz (Fingerprint-Vergleich, 409 `IDEMPOTENCY_CONFLICT`) – M1; Ledgerkonto- und Periodenüberlappungsregeln (Kap. 04 §4.5 „ergänzen“) – M4; Kapazitätslogik (Spike WP-M0-26, Produktiv M2).

### WP-M0-21 – Befundbericht Kernschema und Rückfluss ins Pflichtenheft

- **Ziel:** Alle bei der ersten echten Ausführung gefundenen Abweichungen sind dokumentiert, bewertet und als Korrektur in Pflichtenheft und Prüfbericht zurückgeführt (Umsetzungsplan §7, Risiko „Kernschema und Trigger sind nie ausgeführt worden“).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Befundbericht: je Befund Fundstelle in `kernschema.sql`, beobachtetes Verhalten, Bewertung (Fehler / Lücke / bewusste Grenze), Korrektur oder Zielmeilenstein.
  - Korrekturvorschläge für `docs/pflichtenheft/schema/kernschema.sql`, Kap. 04 und Kap. 09 (Zeile „Nicht ausgeführt: … SQL-/RLS-/Trigger-Laufzeittests“ wird um den tatsächlich ausgeführten Umfang aktualisiert).
  - Anpassung von `validate_artifacts.py`, falls sich Zählwerte (61 Anweisungen, 38 Tabellen) ändern.
- **Detailverträge vorab:** Änderungsverfahren für das Pflichtenheft (wer gibt Korrekturen frei; Versionsführung V0.1 → V0.2).
- **Akzeptanzkriterien:**
  1. Jede Zeile des Anpassungsprotokolls aus WP-M0-16 und jeder Befund aus WP-M0-19/-20 ist im Bericht mit Entscheidung enthalten.
  2. Pflichtenheft-DDL und ausgeführte Migration sind nach dem Rückfluss inhaltsgleich oder die Differenz ist begründet dokumentiert.
  3. `validate_artifacts.py` läuft nach den Korrekturen grün.
  4. Kap. 09 unterscheidet weiterhin klar zwischen ausgeführten und nicht ausgeführten Prüfungen.
- **Tests:** positiv: automatischer Abgleich Pflichtenheft-DDL ↔ Migration. negativ: künstliche Abweichung wird gemeldet.
- **Abhängigkeiten:** intern: WP-M0-16, WP-M0-19, WP-M0-20. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Freigabe von Korrekturen am Pflichtenheft.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fachliche Erweiterung des Schemas; Korrekturen an Anforderungen oder Phasen.

### WP-M0-22 – Akzeptanztestgerüst, Zwei-Mandanten-Fixtures, Evidenzformat

- **Ziel:** `tests/acceptance` kann Szenarien mit AT-/E2E-/PH-AT-Kennung gegen eine laufende Umgebung ausführen und erzeugt je Lauf einen Evidenzdatensatz mit allen Pflichtfeldern aus Kap. 08 §8.4.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Testgerüst mit Szenario-Kennzeichnung (Test-ID, Version, `source_key[]`), Start gegen lokale Umgebung und CI.
  - Deterministischer Seed „Mandanten A/B“ gemäß Fixture von PH-AT-001: je Mandant ein Fahrgast (`passenger_private`), ein Fahrzeug (`vehicle`), ein Beleg (`expense_document`) und eine identisch benannte Kostenstelle (`cost_object`), dazu `tenant`, `legal_entity`, `organization`, `membership` passend zu den Testidentitäten aus WP-M0-09. Ausschließlich synthetische Daten.
  - Evidenzschema (JSON-Schema): `source_key[]`, Test-ID/Version, Software-Build, Migrationsstand, Konfigurations-/Tarif-/Vertragsrevision, Seed und Datenstand, Rollen und Mandanten, Soll/Ist, Ereignis-/Trace-IDs, Ergebnis, Mangelklasse, Verantwortlicher, Abnahmeentscheidung.
  - Katalog aller 106 Referenztests aus `e2e.csv` und der 22 PH-AT-Tests (PH-AT-001 bis PH-AT-022) als benannte, noch nicht implementierte Szenarien mit Zielmeilenstein.
  - Ein durchgängig laufendes Beispielszenario (technischer Rauchtest ohne Fachbezug), das Evidenz erzeugt.
  - Steuerbare Testzeit: gemeinsame Vereinbarung und Hilfsbibliothek, mit der Szenarien die Uhr des PSP-Simulators (WP-M0-23) und der Sandbox-Partner (WP-M0-24) setzen; dokumentierte Grenze, dass die Datenbankzeit `now()` nicht verstellt wird und Ablauf-Szenarien deshalb über kurze reale Fristen oder eine in M1 zu liefernde Zeitquelle des Kerns laufen (siehe Offene Punkte 26).
  - Seed-Verfahren für `tenant` unter RLS: dokumentiert, unter welcher Rolle und mit welchem Kontext Mandanten angelegt werden, ohne BYPASSRLS und ohne Superuser in Tests (siehe Offene Punkte 10).
- **Detailverträge vorab:** JSON-Schema der Evidenz; Regel zur Vergabe der Mangelklassen A/B/C (Lastenheft §11.3, Kap. 08 §8.4); Prüfregel „Evidenz enthält keine Fahrgast- oder Personaldaten“.
- **Akzeptanzkriterien:**
  1. Der Seed ist wiederholbar: zwei Läufe erzeugen identische IDs und Inhalte.
  2. Das Beispielszenario erzeugt einen Evidenzdatensatz, der gegen das Schema validiert und Build-ID sowie Migrationsstand enthält.
  3. Der Katalog enthält genau 106 + 22 Szenarien; jedes hat einen Zielmeilenstein (vorläufig, Bestätigung in WP-M0-33).
  4. Ein Evidenzdatensatz mit einem als personenbezogen markierten Feld wird abgewiesen.
  5. Der Seed läuft vollständig unter der Runtime-Rolle bzw. dem dokumentierten Seed-Verfahren; die beiden Kostenstellen sind `cost_object`-Zeilen der Art `COST_CENTER` mit identischem `business_ref` in A und B.
  6. Ein Szenario kann die Uhr von Simulator und Sandbox setzen; zwei Läufe mit gleicher Startzeit liefern identische Zeitstempel in der Evidenz.
- **Tests:** positiv: Seed, Beispielszenario, Schema-Validierung. negativ: unvollständige Evidenz, personenbezogenes Feld, Szenario ohne Test-ID. Ermöglicht: alle AT-/E2E-/PH-AT-Szenarien der Folgemeilensteine; stellt die Fixture für PH-AT-001 bereit.
- **Abhängigkeiten:** intern: WP-M0-09, WP-M0-12, WP-M0-17, WP-M0-18. extern: keine.
- **Entscheidungen:** D-05 (Pilotbetreiber/Region für realitätsnahe Fixtures wird erst zu M2 benötigt; blockiert nicht).
- **Größe:** M
- **Nicht im Umfang:** Implementierung fachlicher Szenarien; Fixtures für Dienste, Gebiete, Tarife (M2); Übertragung der Evidenz in `e2e.csv` (WP-M0-32).

### WP-M0-23 – PSP-Simulator mit steuerbaren Fehlern

- **Ziel:** Ein deterministischer Zahlungsanbieter-Simulator steht als Testinfrastruktur bereit; Tests können je Aufruf Timeout, verlorene Antwort und Duplikat auslösen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: eigenständiger Simulator-Dienst unter `tests/acceptance` (Compose-Profil) mit den Operationen Authorize, Capture, Void, Refund (auch Teilbetrag) und Statusabfrage nach Anbieter- bzw. Händlerreferenz.
  - Zustandsführung je Zahlung nach Kap. 03 §4.2: `CREATED`, `REQUIRES_ACTION`, `AUTHORIZED`, `CAPTURE_PENDING`, `CAPTURED`, `AUTHORIZATION_FAILED`, `VOID_PENDING`, `VOIDED`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`, `DISPUTED` als getrennter Streitstatus; die Zustände `CAPTURE_UNKNOWN`/`RECONCILING` entstehen auf der Aufruferseite und werden vom Simulator provozierbar gemacht.
  - Steuerschnittstelle (nur Testnetz): je Operation und Referenz einstellbar – Timeout, „Wirkung persistiert, Antwort verworfen“, doppelte bzw. verspätete asynchrone Benachrichtigung, definitive Ablehnung, verzögerte SCA-Rückkehr (`REQUIRES_ACTION` länger als ein Hold), Statusabfrage nicht verfügbar.
  - Durchsetzung der Geldinvarianten im Simulator: erstattet ≤ erfasst ≤ autorisiert; Beträge als Ganzzahl in kleinster Einheit; Wiederholung mit gleichem Idempotenzschlüssel erzeugt keine zweite Belastung.
  - Protokoll aller Aufrufe mit Referenzen zur Auswertung in Tests.
  - Asynchrone Benachrichtigungen im Format des künftigen Eingangs `/v1/payments/provider-events/{provider}` (Kap. 05 §5.7) mit eigener PSP-Signatur statt des Partner-HMAC; Fehlermodus „ungültige Signatur“.
  - Auslösbarer Streitfall (`DISPUTED`) nach Capture sowie einstellbare Simulatoruhr (gemeinsame Vereinbarung aus WP-M0-22).
- **Detailverträge vorab:** OpenAPI der Simulator- und der Steuerschnittstelle; Zustandsautomat mit erlaubten Übergängen; JSON-Schema der asynchronen Benachrichtigung; Festlegung, dass diese Schnittstelle zugleich der Entwurf des internen PSP-Ports für `backend/adapters/psp` ist (Bestätigung in WP-M0-29).
- **Akzeptanzkriterien:**
  1. Alle fünf Operationen funktionieren im Normalfall; die Statusabfrage findet jede Zahlung über die ursprüngliche Referenz.
  2. Jeder der Fehlermodi Timeout, verlorene Antwort und Duplikat ist je Operation gezielt auslösbar und im Protokoll nachvollziehbar.
  3. Bei „Antwort verworfen“ ist die Wirkung beim Simulator vorhanden und per Statusabfrage auffindbar; eine Wiederholung mit gleichem Schlüssel erzeugt keine zweite Wirkung.
  4. Identischer Lauf mit identischem Seed liefert identische Referenzen und Ergebnisse.
  5. Capture über Autorisierung und Refund über Capture werden abgewiesen.
  6. Eine Benachrichtigung lässt sich bis zu 100-mal identisch, verspätet und mit ungültiger Signatur zustellen; jede Zustellung steht im Protokoll.
  7. Der Simulator ist nur im Testnetz erreichbar und in keiner Produktionskonfiguration referenziert (Kap. 01 §1.8 „separate … PSP-Konten“).
- **Tests:** positiv: Authorize → Capture → Teil-Refund → Status. negativ: Capture ohne Autorisierung, Übererstattung, unbekannte Referenz. konkurrierend (Geld): 100 gleichzeitige Capture-Aufrufe mit gleichem Schlüssel → genau eine Erfassung; zwei gleichzeitige Refunds, deren Summe das Capture übersteigt → höchstens einer erfolgreich. Ermöglicht: AT-15, PH-AT-008, PH-AT-009, PH-AT-010, PH-AT-011, Contract-Fall „SCA länger als Hold“; für PH-AT-014 nur den Erstattungsanteil (zweiter identischer PSP-Webhook, parallele Erstattungen) – Transfer, Payout und Transferreversal ergänzt M6.
- **Abhängigkeiten:** intern: WP-M0-08. extern: keine.
- **Entscheidungen:** D-03 (PSP-Auswahl und Geldfluss; der Simulator ist anbieterneutral, blockiert nicht). Bei Verzug von D-03 bleibt laut Umsetzungsplan §5 „Nur PSP-Simulator“.
- **Größe:** M
- **Nicht im Umfang:** Produktiver PSP-Adapter und `payment_intent`-Logik (M2); Transfer, Payout, Split-Zahlung, Clearing (M6); echte PSP-Sandbox (WP-M0-29).

### WP-M0-24 – Deterministische Sandbox-Partner

- **Ziel:** Mindestens zwei deterministische Partner-Sandboxen (Betreiber A und B) und ein nur auskunftsfähiger Partner stehen als Testinfrastruktur bereit; Timeout, verlorene Antwort und Duplikat sind je Aufruf steuerbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Sandbox-Dienst unter `tests/acceptance` mit konfigurierbarer Capability-Matrix je Instanz mit allen Merkmalen aus Kap. 02 §9: Standard und Version, Discovery, Auskunft, bindende Preise, Hold, Holdverlängerung, Confirm, deduplizierte Requests, Statuslookup, Storno, Refund, Ticketausgabe, Echtzeit, Signaturverfahren, bekannte Quoten, Sandboxnachweis. Capability ist je Dienst einstellbar, nicht nur je Partner.
  - API: betreiberseitige Operationen nach dem Zuschnitt „Partner intern“ aus Kap. 05 §5.7: `/v1/partner/capabilities`, `/v1/partner/offers`, `/v1/partner/reservations` (Hold mit `expires_at` und Hold-TTL), `/v1/partner/reservations/{id}/confirm`, `/cancel`, `/status`; stabile Command-ID je Aufruf, Status nach ursprünglicher fachlicher Referenz (`federation_ref` bzw. `operation_id`), Holdverlängerung und Refund soweit die Capability eingeschaltet ist. Die Payloads sind Entwurf und werden als OpenAPI-Ergänzung unter `api/` abgelegt.
  - Ausgehende Partnerereignisse an `POST /v1/federation/partners/{partner_id}/events` mit den Headern `X-Partner-Key-Id`, `X-Partner-Timestamp`, `X-Partner-Signature` (HMAC-SHA256 über `timestamp + "." + raw_body`, Kap. 05 §5.6).
  - Steuerschnittstelle: Timeout, „Confirm persistiert, Antwort verworfen“, Duplikat (bis 100 identische Ereignisse), gleiche Ereignis-ID mit verändertem Payload, vertauschte Reihenfolge/Versionslücke, definitive Ablehnung, Zeitstempel außerhalb ±300 s, falsche Signatur, unbekannte `X-Partner-Key-Id`, zweiter gültiger Schlüssel während Rotation, Body über der Maximalgröße, `tenant_id` im Ereignis abweichend von der Partnerbindung, nicht zugelassener Ereignistyp, abgeschaltete Statusfähigkeit, abgeschaltete Deduplikation, einstellbare Latenz, einstellbare Sandboxuhr (gemeinsame Vereinbarung aus WP-M0-22).
  - Konfiguration „nur Auskunft“ (kein Hold, kein Ticket) für PH-AT-006.
- **Detailverträge vorab:** OpenAPI des internen Adaptervertrags (Entwurf des Ports für `backend/adapters/partner-tomp` und `backend/adapters/partner-osdm`); JSON-Schema der Partnerereignisse; Capability-Matrix als Schema; Zustandsautomat des Sandbox-Holds (`HELD`, `CONSUMED`, `RELEASED`, `EXPIRED` analog `capacity_hold`).
- **Akzeptanzkriterien:**
  1. Zwei Sandbox-Instanzen mit unterschiedlicher Capability-Matrix laufen parallel; eine dritte ist nur auskunftsfähig und lehnt Hold ab.
  2. Jeder Fehlermodus ist je Aufruf gezielt auslösbar; bei „Antwort verworfen“ findet die Statusabfrage mit der ursprünglichen Referenz genau eine Reservierung.
  3. Wiederholter Confirm mit gleicher Referenz erzeugt keine zweite Reservierung.
  4. Ereignisse tragen eine gültige Signatur; die Modi „falsche Signatur“ und „Zeitfenster überschritten“ erzeugen entsprechend ungültige Ereignisse.
  5. Gleicher Seed → gleiche Referenzen, Zeiten und Ergebnisse.
  6. Jedes Merkmal der Capability-Matrix aus Kap. 02 §9 ist je Instanz und Dienst gesetzt oder ausdrücklich „nicht unterstützt“; `/v1/partner/capabilities` liefert genau diese Matrix.
  7. Jeder Signatur- und Bindungsfehlermodus (falsche Signatur, unbekannte Key-ID, Zeitfenster, Übergröße, abweichende `tenant_id`, unzulässiger Ereignistyp) erzeugt ein Ereignis, das ein korrekter Empfänger nach Kap. 05 §5.6 ablehnen muss; die Rotation erzeugt zwei gleichzeitig gültige Signaturen.
  8. Die Sandbox hält ausschließlich synthetische Daten und Testschlüssel; Schlüssel liegen nicht im Repository.
- **Tests:** positiv: Angebot → Hold → Confirm → Status → Cancel. negativ: Confirm auf abgelaufenen Hold, unbekannte Referenz, Hold bei nur auskunftsfähigem Partner, Status bei abgeschalteter Statusfähigkeit, Aufruf mit Maschinenidentität eines anderen Partners. konkurrierend (Kapazität): 100 gleichzeitige Holds auf den letzten Platz der Sandbox → höchstens einer erfolgreich. Ermöglicht: AT-24, E2E-088, E2E-090, E2E-104, PH-AT-002, PH-AT-005 bis PH-AT-010, PH-AT-017 sowie die Contract-Fälle „Confirm-Timeout mit spät positivem Status“, „Storno unbekannt“, „100 identische Webhooks“ und „Manipulation tenant_id“ (Partnerereignis-Anteil).
- **Abhängigkeiten:** intern: WP-M0-04, WP-M0-08. extern: keine.
- **Entscheidungen:** D-08 (Partnerstandards; Sandbox bildet den internen kanonischen Vertrag ab und blockiert nicht; reale TOMP-/OSDM-Profile erst nach D-08). D-01, D-04 nur für Produktivschaltung der Föderation.
- **Größe:** M
- **Nicht im Umfang:** Journey-Orchestrator, Saga, Inbox-Verarbeitung im Kern (M6); TOMP-/OSDM-Protokollabbildung (M6); GTFS-Fahrplandaten (M6/PTI).

### WP-M0-25 – Gerüst `tests/performance`

- **Ziel:** Lasttests sind ab M2 fortlaufend ausführbar (Umsetzungsplan §7); das Werkzeug, ein parametrierbares Lastprofil und die Vorlage für das Restore-Drehbuch liegen vor.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Ausgewähltes und festgeschriebenes Lastwerkzeug mit einem Beispielprofil gegen den Health-Endpunkt.
  - Profilschema mit den zu protokollierenden Größen aus Kap. 08 §8.6 (Endpointmix, Fehlerrate, Datenvolumen, Hardware, Parallelität, Kandidaten-/Flottengröße, Partneranteile).
  - Referenzziele als Parameter, gekennzeichnet als vorläufige Lastenheftziele: Standard-API p95 ≤ 1 s, Angebot p95 ≤ 5 s, Fahrerplan p95 ≤ 10 s, 250 Fahrzeuge je Mandant, 30.000 Buchungen/Tag, 5.000 Sessions, RPO ≤ 15 min, RTO ≤ 2 h.
  - Vorlage „Restore-Drehbuch“ mit den Protokollfeldern aus Kap. 08 §8.6 (Ausfallzeitpunkt, letzter wiederherstellbarer Commit, Zeitpunkt der funktionsfähigen End-to-End-Probe, Abgleich externer Buchungen/Zahlungen).
  - Berichtsvorlage für Messläufe (Pflicht für die Spikes WP-M0-26 und -27; die Spikes WP-M0-28 bis -30 verwenden dieselben Kopffelder für Hardware, Datenstand, Stichprobe und Rohdatenablage).
- **Detailverträge vorab:** Berichtsformat für Messwerte (Perzentile, Stichprobengröße, Hardware, Datenstand).
- **Akzeptanzkriterien:**
  1. Das Beispielprofil läuft lokal und in CI (manuell auslösbar) und erzeugt einen Bericht im festgelegten Format.
  2. Peak-Requests/s und Anzahl aktiver Mandanten sind als offene Pflichtparameter ausgewiesen (Kap. 08 §8.6 „Tagesdurchschnitt allein genügt nicht“).
  3. Die Restore-Vorlage enthält alle Protokollfelder aus Kap. 08 §8.6.
- **Tests:** positiv: Beispiellauf erzeugt Bericht. negativ: Lauf ohne Pflichtparameter wird abgewiesen.
- **Abhängigkeiten:** intern: WP-M0-02, WP-M0-08. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Peaklastprofil und Mandantenzahl (D-05) – erst für M5 zwingend.
- **Entscheidungen:** D-05 (blockiert nur ein belastbares Lastprofil in M5).
- **Größe:** S
- **Nicht im Umfang:** Fachliche Lastszenarien (ab M2); Restore-/Failover-Durchführung und Fault-Injection (M5).

### WP-M0-26 – Spike (a) Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS

- **Ziel:** Gemessen ist, ob Hold und Confirm mit Sperren der Kapazitätsabschnitte in stabiler Reihenfolge allein in PostgreSQL/PostGIS (Leitlinie 4) unter 100 parallelen Transaktionen korrekt und ausreichend schnell sind, und ob die Geo-Prüfungen des Buchungspfads auf PostGIS dabei tragen (Kap. 01 §1.1 nennt „PostGIS“ ausdrücklich als Spike-Beleg).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Wegwerf-Prototyp (nicht Teil des Produktivcodes) für Hold und Confirm nach Kap. 01 §1.4 Schritte 5–6 auf `vehicle_run`, `capacity_hold`, `booking`: Sperren der betroffenen Intervalle in stabiler Reihenfolge, Prüfung von `run_revision`, Ressourcenvektor (`capacity_vector` mit Sitz und Rollstuhlplatz), Ablauf nach Serverzeit `now >= expires_at`; Hold und zugehörige `outbox`-Zeile werden in derselben Transaktion geschrieben (Kap. 01 §1.4 Schritt 5), damit die Messung den späteren Schreibpfad abbildet.
  - Datenbank, PostGIS-Anteil: Gebietsprüfung „Punkt liegt in wirksamem Bediengebiet“ auf `service_area` und Suche der nächstgelegenen freigegebenen Haltepunkte auf `stop_revision` (Kap. 01 §1.4 Schritte 1–2) mit den GiST-Indizes `service_area_geo` und `stop_geo`, SRID 4326 und metrischer Entfernung über `geography` (Kap. 04 §4.1); gemessen einzeln und als Vorstufe des Holds unter derselben Parallelität.
  - Datenbank: Messaufbau auf dem migrierten Kernschema unter der Runtime-Rolle; zwei Mandanten; Variante mit realistischer Zeilenzahl (Vorschlag dieses Plans: Größenordnung eines Betriebstags mit 250 Fahrzeugen).
  - Spike-Bericht: Messwerte (Durchsatz, p50/p95/p99 je Operation, Anzahl Deadlocks und Serialisierungsfehler, Sperrwartezeiten), gewählte Sperrstrategie mit Alternativen (Zeilensperre auf Run, Abschnittszeilen, Advisory Locks), Befund zur Eignung der Struktur `capacity_hold.interval_from/interval_to` und `capacity_vector jsonb`, Befund zu Indexnutzung und Latenz der Geo-Abfragen unter RLS (die Indizes `service_area_geo` und `stop_geo` beginnen nicht mit `tenant_id`), Vergleich der Isolationsstufen READ COMMITTED und REPEATABLE READ, Empfehlung für M2.
- **Detailverträge vorab:** Messprotokoll (Hardware, Poolgröße, Isolationsstufe, Datenmenge); vorläufige Definition des Ressourcenvektors als JSON-Schema (nur für den Spike); Erfolgskriterien unten vor Beginn mit dem Team M2 bestätigt.
- **Akzeptanzkriterien:** (messbares Erfolgskriterium des Spikes)
  1. Korrektheit (aus PH-AT-004): Bei genau einem freien Sitz auf dem überlappenden Abschnitt und 100 parallelen Bestätigungen unterschiedlicher fachlicher Anfragen entsteht höchstens eine bestätigte Reservierung; kein Abschnitt über Kapazität, keine negative Belegung; jede Abweisung hat einen eindeutigen Grund. Wiederholt in mindestens 1.000 Durchläufen ohne einen einzigen Verstoß (Durchlaufzahl: Vorschlag dieses Plans).
  2. Varianten aus PH-AT-004: freier Sitz bei belegtem Rollstuhlplatz (kein Ersatz); veränderte Routenrevision (Neuplanung oder 409).
  3. Wiederholte Bestätigung desselben Holds findet dieselbe Buchung; Ablauf und Bestätigung genau zum Grenzzeitpunkt führen nie zu „abgelaufen und gebucht“.
  4. Kein unbehandelter Deadlock: Jede der 100 Transaktionen endet mit Erfolg oder einem definierten fachlichen Abweisungsgrund.
  5. Latenz: p95 je Hold bzw. Confirm unter 100 parallelen Transaktionen liegt innerhalb des Ziels „Standard-API p95 ≤ 1 s“ (vorläufiges Lastenheftziel, Kap. 01 §1.8). Wird das Ziel verfehlt, enthält der Bericht Ursache und Alternativvorschlag.
  6. Der Bericht liegt mit Rohdaten und reproduzierbarem Skript vor.
  7. PostGIS: Gebietsprüfung und Haltepunktsuche liefern für einen Testdatensatz mit Punkten innerhalb, außerhalb und auf der Gebietsgrenze die erwarteten Ergebnisse; ihre p95-Latenz unter 100 parallelen Anfragen ist gemessen und dem Ziel „Standard-API p95 ≤ 1 s“ gegenübergestellt; der Ausführungsplan belegt die Indexnutzung bei zwei Mandanten mit realistischer Zeilenzahl.
  8. Mandantentrennung im Spike: Kein Hold, keine Buchung und keine Geo-Abfrage von Mandant A sieht oder sperrt Zeilen von Mandant B; alle Messungen laufen unter der Runtime-Rolle mit transaktionslokalem Kontext.
- **Tests:** positiv: einzelner Hold/Confirm; Gebietsprüfung und Haltepunktsuche. negativ: Hold über Kapazität, Confirm auf abgelaufenen oder freigegebenen Hold. konkurrierend (Kapazität): die Fälle 1–4 mit 100 parallelen Transaktionen; zusätzlich gleichzeitig Hold-Ablauf und Confirm; überlappende Abschnitte in gegenläufiger Reihenfolge angefragt (Deadlock-Provokation). Vorarbeit für AT-03, PH-AT-004 und die Contract-Fälle „zwei Betreiber/letzter Platz“ und „abgelaufene Holds“.
- **Abhängigkeiten:** intern: WP-M0-16, WP-M0-18, WP-M0-25. extern: keine.
- **Entscheidungen:** D-05 (Peaklast unbekannt; der Spike misst mit dem Referenzwert 100 parallele Transaktionen aus dem Umsetzungsplan und blockiert nicht).
- **Größe:** M
- **Nicht im Umfang:** Produktive Buchungs- und Dispositionslogik (M2); Hold-Dauer und -Verlängerung (Planungsdefault 180 s aus Kap. 02 §7.2 wird nur als Testwert verwendet); Safety- und Qualifikationsprüfung im selben Lock (M3).

### WP-M0-27 – Spike (b) Solverlaufzeit der Einfügeheuristik

- **Ziel:** Gemessen ist, ob eine Einfügeheuristik mit harten Zusage-Constraints innerhalb des lokalen Budgets von 1 s (Kap. 01 §1.4, „zunächst zu testender Vorschlag“) einen zulässigen Plan liefert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Prototyp in `workers/routing` (als Spike gekennzeichnet): Einfügen einer neuen Anfrage in bestehende Fahrzeugpläne unter Einhaltung bestehender Zeitfenster als harte Constraints, Ressourcenvektor (Sitz/Rollstuhl), maximaler Umweg und Pausen; OR-Tools als Solverbaustein; Fahrzeitmatrix aus Valhalla bzw. aus zwischengespeicherter Matrix.
  - Synthetischer Instanzgenerator mit festen Seeds für mehrere Flotten- und Auftragsgrößen bis zum Referenzwert 250 Fahrzeuge je Mandant.
  - Spike-Bericht: Laufzeitverteilung je Instanzgröße, Anteil Matrixabruf vs. Solver, Verhalten bei Timeout, Qualität (Leerweg/Fahrzeit gegenüber längerer Rechenzeit), Empfehlung zur Schnittstelle Kern ↔ Worker (synchron oder Queue) und zum Matrix-Cache.
- **Detailverträge vorab:** Eingabe-/Ausgabeschema des Prototyps (JSON-Schema, nur Spike); Definition der Testinstanzen (gemeinsam mit WP-M0-30 festgelegt, damit dort die Matrixlatenz für dieselben Größen gemessen wird); Messprotokoll.
- **Akzeptanzkriterien:** (messbares Erfolgskriterium des Spikes)
  1. Für die Referenzinstanz (250 Fahrzeuge; Auftragsdichte laut dokumentierter Annahme, da D-05 offen ist) liegt die Laufzeit der lokalen Kandidatensuche einschließlich Matrixzugriff bei p95 ≤ 1 s.
  2. Jeder gelieferte Plan ist zulässig: Ein unabhängiger Prüfer findet in keinem Lauf eine Verletzung bestehender Zusagen, Ressourcen oder Zeitfenster.
  3. Bei erzwungenem Timeout wird entweder ein bereits vollständig validierter zulässiger Plan oder eine begründete Ablehnung geliefert, nie ein ungeprüfter Plan (Kap. 01 §1.4).
  4. Der Bericht nennt die größte Instanz, die das 1-s-Budget noch hält, und die erste, die es reißt.
  5. Ergebnisse sind mit Seed reproduzierbar.
- **Tests:** positiv: lösbare Instanz. negativ: unlösbare Instanz → begründete Ablehnung; Instanz mit Constraint-Konflikt. konkurrierend: mehrere gleichzeitige Solveranfragen auf derselben Flotte (Laufzeit unter paralleler Last messen; Kapazitätskorrektheit bleibt Aufgabe des Holds, WP-M0-26). Vorarbeit für OPT-Anforderungen in M2 und den Nachweis „Fahrtangebot p95 ≤ 5 s“ (Kap. 08 §8.6).
- **Abhängigkeiten:** intern: WP-M0-07, WP-M0-08, WP-M0-25. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Pilotregion, Flottengröße und Auftragsdichte (D-05) für realistische Instanzen; ohne Angabe synthetische Annahmen.
- **Entscheidungen:** D-05 und D-09 (blockieren nicht; der Bericht kennzeichnet Annahmen).
- **Größe:** M
- **Nicht im Umfang:** Produktive Dispositionslogik, periodische Verbesserung, Lade-/Reichweitenreserven (M2/M7); Partner-Fanout (M6); Routinganbietervergleich (WP-M0-30).

### WP-M0-28 – Spike (c) Driver-Offline-Queue auf Zielgeräten

- **Ziel:** Auf realen Zielgeräten ist belegt, dass eine verschlüsselte lokale SQLite-Queue Ereignisse offline sicher speichert und nach Wiederverbindung jedes Ereignis genau einmal wirksam wird.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche/Mobil: Prototyp in `mobile/driver` (Spike-Zweig) mit verschlüsselter SQLite-Outbox; je Ereignis `device_event_id`, letzte Serverrevision, Erfassungszeit, Empfangszeit (Kap. 01 §1.5).
  - Oberfläche/Mobil, Offline-Lesestand: zuletzt bestätigter Dienst und Stoppliste mit Stand werden verschlüsselt lokal gehalten (Kap. 01 §1.5 „Offline sichtbar“); im Spike mit synthetischen Daten, ohne Bildschirmgestaltung.
  - API: Test-Gegenstelle, die den Vertrag von `POST /v1/driver/events` (`recordDriverEvent`) nachbildet und `APPLIED`, `DUPLICATE` oder `REVIEW_REQUIRED` zurückgibt.
  - Spike-Bericht je Gerät: Verhalten bei Flugmodus, App-Beendigung durch das Betriebssystem, Geräteneustart, vollem Speicher, Uhrverstellung, Hintergrundbeschränkungen (Akkuoptimierung), Schlüsselhaltung im Gerätespeicher; Messwerte zu Sync-Dauer und Verlust-/Duplikatzahl; Empfehlung für M3.
- **Detailverträge vorab:** Zielgeräteliste (siehe Offene Punkte); Schema der lokalen Queue; Testdrehbuch je Störfall; Anforderungen an Geräteverwaltung („verwaltete Geräte“, Kap. 01 §1.1).
- **Akzeptanzkriterien:** (messbares Erfolgskriterium des Spikes)
  1. Auf jedem Zielgerät: 1.000 offline erfasste Ereignisse (Anzahl: Vorschlag dieses Plans) werden nach Wiederverbindung vollständig übertragen; serverseitig ist jedes genau einmal `APPLIED`, Wiederholungen ergeben `DUPLICATE`; Verlust = 0.
  2. Nach erzwungenem App-Abbruch und nach Geräteneustart während des Syncs gilt Kriterium 1 unverändert.
  3. Die Datenbankdatei ist ohne Geräteschlüssel nicht lesbar (Nachweis durch Auslesen der Datei).
  4. Ein Ereignis auf veralteter Planrevision wird als `REVIEW_REQUIRED` beantwortet und lokal nicht als „synchronisiert“ fehlgedeutet.
  5. Erfassungszeit und Serverempfangszeit bleiben getrennt erhalten, auch bei verstellter Geräteuhr.
  6. Der Bericht liegt je Gerät mit Betriebssystemversion vor und benennt Geräte, die ein Kriterium nicht erfüllen.
  7. Nach Netzverlust und App-Neustart sind der zuletzt bestätigte Dienst und die Stoppliste mit ihrem Stand weiterhin lesbar und als „Stand von …“ gekennzeichnet (Teilaspekt von AT-11 „Letzte gültige Route sichtbar“).
  8. Im Spike werden ausschließlich synthetische Ereignisse und Testkonten verwendet; es werden keine Standort- oder Personendaten realer Fahrer erhoben (D-10 ist nicht berührt).
- **Tests:** positiv: Online-Erfassung; Offline-Erfassung mit späterem Sync. negativ: Sync mit abgelaufenem Token (Ereignisse bleiben in der Queue); manipulierte Queue-Datei. konkurrierend (Sicherheit/Einmaligkeit): gleichzeitiger Sync derselben Queue aus zwei App-Prozessen bzw. Wiederholung während laufender Übertragung → keine doppelte Wirkung. Vorarbeit für AT-11 und den Contract-Fall „Offline-Check-in auf alter Planrevision“.
- **Abhängigkeiten:** intern: WP-M0-06. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Zielgeräteliste und Bereitstellung von Testgeräten (D-05, D-12).
- **Entscheidungen:** D-05 (Zielgeräte folgen dem Pilotbetreiber; ohne Angabe misst der Spike auf Referenzgeräten, das Ergebnis ist dann nur eingeschränkt übertragbar); D-11 (Notbetrieb; nur Hinweis, blockiert nicht).
- **Größe:** M
- **Nicht im Umfang:** Produktive Driver-App, Bildschirm UI-09, Offline-Erfassung von Beleg/Schaden/Zeitereignis (M3/M4); Hintergrundortung; Klärfall-Workflow bei widersprüchlichen Ereignissen (M3).

### WP-M0-29 – Spike (d) PSP-Sandbox

- **Ziel:** Gegen die Sandbox mindestens eines realen PSP-Kandidaten sind Authorize, Capture, Void, Refund und Statusabfrage ausgeführt; die Abweichungen zum PSP-Simulator und zum Zahlungsautomaten aus Kap. 03 §4.2 sind dokumentiert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Wegwerf-Adapter zur Kandidaten-Sandbox hinter demselben Port wie der PSP-Simulator (WP-M0-23).
  - Fähigkeitsmatrix je Kandidat: Idempotenzschlüssel und deren Gültigkeitsdauer, Statusabfrage nach eigener Referenz, Teil-Capture, Teil-Refund, Void nach Teil-Capture, asynchrone Benachrichtigungen (Signatur, Wiederholung, Reihenfolge), SCA/3DS-Rückkehr, Split-/Marktplatzfähigkeit (nur als Ausblick auf D-03), Sandbox-Grenzen.
  - Abgleich Zustände des Kandidaten ↔ `CREATED`, `REQUIRES_ACTION`, `AUTHORIZED`, `CAPTURE_PENDING`, `CAPTURED`, `AUTHORIZATION_FAILED`, `VOID_PENDING`, `VOIDED`, `CAPTURE_UNKNOWN`, `RECONCILING`, `REFUND_PENDING`, `PARTIALLY_REFUNDED`, `REFUNDED`, `DISPUTED`.
  - Spike-Bericht mit Messwerten (Latenz je Operation, Verhalten bei Timeout und Wiederholung) und Entscheidungsvorlage „PSP-Kandidat“ für WP-M0-34.
- **Detailverträge vorab:** Auswahlliste der Kandidaten und Bewertungskriterien; Port-Schnittstelle aus WP-M0-23.
- **Akzeptanzkriterien:** (messbares Erfolgskriterium des Spikes)
  1. Alle fünf Operationen sind gegen mindestens eine reale Sandbox erfolgreich ausgeführt und mit Anbieterreferenzen protokolliert.
  2. Nachgewiesen ist für jede Operation: Ein clientseitig abgebrochener Aufruf (Timeout) wird über die Statusabfrage mit der ursprünglichen Referenz eindeutig aufgelöst; die Wiederholung mit gleichem Idempotenzschlüssel erzeugt keine zweite Belastung bzw. Erstattung (Kap. 03 §4.2 „Kein zweiter Charge auf Verdacht“).
  3. Die Fähigkeitsmatrix ist für jeden geprüften Kandidaten vollständig; jede nicht unterstützte Fähigkeit ist mit Auswirkung auf den Zahlungsautomaten benannt.
  4. Jede Abweichung zwischen Sandbox und Simulator ist entweder im Simulator nachgezogen oder als bewusste Differenz dokumentiert.
  5. Es wurden ausschließlich Testkonten und Testzahlungsmittel verwendet; keine Zugangsdaten im Repository.
- **Tests:** positiv: Authorize → Capture → Teil-Refund → Status; Authorize → Void. negativ: Capture über Autorisierung, Refund über Capture, abgelehntes Testzahlungsmittel. konkurrierend (Geld): zwei gleichzeitige Capture-Aufrufe mit gleichem Schlüssel; doppelt zugestellte Benachrichtigung → eine Wirkung. Vorarbeit für AT-15, PH-AT-011.
- **Abhängigkeiten:** intern: WP-M0-23. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Benennung der PSP-Kandidaten und Sandbox-Zugänge (D-03 Teil Einbetreiberzahlung).
- **Entscheidungen:** D-03 (Teil PSP-Auswahl; der Spike bereitet die Entscheidung vor, sie blockiert nur die Produktivschaltung der Zahlung in M2).
- **Größe:** M
- **Nicht im Umfang:** Produktiver Adapter `backend/adapters/psp` (M2); gemeinsamer Checkout, Transfer, Payout (M6); Vertragsverhandlung mit dem PSP.

### WP-M0-30 – Spike (e) Streckenbezogener Routingvergleich (D-09)

- **Ziel:** Zwei verfügbare Routingvarianten sind auf definierten Teststrecken verglichen, sodass die Entscheidung D-09 mit Messwerten vorbereitet ist.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: providerunabhängige Adapter-Schnittstelle (Entwurf) mit zwei Anbindungen: Valhalla/OSM (Favorit aus D-09) und eine zweite verfügbare Variante.
  - Teststreckenkatalog nach den Kriterien aus D-09: Fahrstrecken, Fuß- und Rollstuhlwege, Restriktionen, Matrixlatenz, zulässige Datenspeicherung.
  - Spike-Bericht: je Variante Abweichung zu Referenzfahrzeiten, Matrixlatenz für die in WP-M0-27 benötigten Größen, Abdeckung barrierefreier Wege, Lizenz- und Datennutzungsbedingungen, Betriebsaufwand; Entscheidungsvorlage „Routinganbieter“ für WP-M0-34.
- **Detailverträge vorab:** Adapter-Schnittstelle (Route, Matrix, Profil, Datenstand/Version – der Cache-Key aus Kap. 04 §4.4 `route_matrix_cache` verlangt Start/Ziel/Profil/Version); Bewertungsschema mit Gewichtung, vor der Messung festgelegt.
- **Akzeptanzkriterien:** (messbares Erfolgskriterium des Spikes)
  1. Beide Varianten sind auf demselben Teststreckenkatalog gemessen; je Strecke liegen Fahrzeit, Distanz und Antwortzeit vor.
  2. Die Matrixlatenz ist für mindestens die Referenzgröße aus WP-M0-27 gemessen und dem 1-s-Budget der lokalen Kandidatensuche gegenübergestellt.
  3. Fuß-/Rollstuhlwege und Restriktionen sind an benannten Beispielstrecken geprüft; Fehlrouten sind dokumentiert.
  4. Lizenz- und Datenspeicherungsbedingungen beider Varianten sind schriftlich bewertet.
  5. Die Entscheidungsvorlage nennt eine begründete Empfehlung und die verbleibenden Unsicherheiten.
- **Tests:** positiv: Route und Matrix je Variante. negativ: Strecke außerhalb des Datenbestands, nicht erreichbarer Anbieter (Timeout-Verhalten des Adapters).
- **Abhängigkeiten:** intern: WP-M0-08. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Pilotregion, reale Haltepunkte, barrierefreie Zugänge, Referenzfahrzeiten und Lizenzrahmen (D-05, D-09).
  - Beistellung Auftraggeber (kein WP): Zugang zur zweiten Routingvariante (Testkonto bzw. Budgetfreigabe).
- **Entscheidungen:** D-09 (wird durch den Spike vorbereitet; blockiert nur die Produktivschaltung, Arbeitsstand bleibt Valhalla/OSM); D-05 (Pilotregion; ohne Angabe wird eine Stellvertreterregion verwendet und so gekennzeichnet).
- **Größe:** M
- **Nicht im Umfang:** Produktiver Routingadapter und Matrix-Cache (M2); Telematik- und Echtzeitdaten (M7); Ladeinfrastruktur.

### WP-M0-31 – Detailvertragsprozess

- **Ziel:** Für jede Anforderung ist geregelt, wie ihr Detailvertrag entsteht, wer ihn prüft und wann mit Code begonnen werden darf (Leitlinie 7, Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Vorlage „Detailvertrag je Anforderung“ mit Feldern: `source_key`, Verantwortlicher, Originaltext und Originalabnahme (unverändert), betroffene Tabellen/DDL, OpenAPI-Pfade, Zustandsautomat, JSON-Schema, Ereignisse, Parameter und Kennzahlen aus `kataloge.csv`, UI-IDs, positive/negative/konkurrierende Tests, betroffene Entscheidungen D-01 bis D-12, offene Punkte.
  - Review-Rollen (Fachanalyse, Architektur, QA, bei Sicherheit/Geld/Datentrennung zusätzlich benannte Zweitprüfung) und „Definition of Ready“: Verantwortlicher + Detailvertrag + mindestens ein positiver ausführbarer Test verknüpft; bei Sicherheits-, Geld-, Kapazitäts- und Datentrennungsregeln zusätzlich negative und konkurrierende Tests.
  - Pflegeregeln für `docs/umsetzungsplan/arbeitspakete.csv`: Bedeutung und erlaubte Werte der Spalten `detailvertrag`, `testfall`, `status`; Abgleich mit den beiden vorhandenen Erzeugern `build_zuordnung.py` (Zuordnung Anforderung → Meilenstein) und `check_wp.py --write` (Zuordnung Anforderung → WP mit den Spalten `wp_titel`, `wp_groesse`, `wp_datei`), damit manuell gepflegte Spalten bei Neuerzeugung nicht verloren gehen; Festlegung, welcher Erzeuger künftig führend ist; Verknüpfung der Spalte `arbeitspaket` mit den WP-IDs der Dateien unter `docs/umsetzungsplan/wp/`.
  - Ablageorte der Vertragsartefakte: OpenAPI unter `api/`, DDL unter `db/migrations/`, Zustandsautomaten und JSON-Schemas (einschließlich Ereignisschemas, WP-M0-04) an einem festgelegten Ort mit Namensschema, damit die Spalte `detailvertrag` auf stabile Pfade zeigt.
  - Verfahren für Abweichungen: SOLL-Abweichung mit Begründung und AG-Freigabe, KANN nur mit ausdrücklicher Aufnahme (Kap. 08 §8.4); Phasen und Prioritäten werden nicht geändert.
  - Vorlage für das Beschlussprotokoll nach Kap. 07 (`decision_id`, Variante, Begründung, Entscheider, Datum, betroffene Anforderungs-Keys, Vertrags-/Policyversion, Bedingungen).
- **Detailverträge vorab:** erlaubte Statuswerte in `arbeitspakete.csv` (bisher nur „Geplant“ belegt); Ablageort und Namensschema der Detailverträge.
- **Akzeptanzkriterien:**
  1. Vorlage, Rollen und Definition of Ready sind freigegeben und im Beitragsleitfaden verlinkt.
  2. Ein Probedurchlauf mit drei M1-Anforderungen (je eine aus SYS, IAM, CFG) erzeugt vollständige Detailverträge und Einträge in `arbeitspakete.csv`.
  3. Eine Neuerzeugung mit `build_zuordnung.py` bzw. `check_wp.py --write` erhält die gepflegten Spalten `detailvertrag`, `testfall` und `status` für alle 672 Zeilen; beide Skripte erzeugen dasselbe Spaltenlayout oder eines von beiden ist stillgelegt.
  4. Die Statuswerte sind abschließend definiert und maschinell prüfbar.
- **Tests:** positiv: Probedurchlauf. negativ: Detailvertrag ohne Testverknüpfung erfüllt die Definition of Ready nicht (Prüfung in WP-M0-32 automatisiert); Neuerzeugung der CSV nach manuell gepflegter Zeile darf diese nicht zurücksetzen; Detailvertrag mit geändertem Originaltext, geänderter Phase oder Priorität wird abgewiesen.
- **Abhängigkeiten:** intern: WP-M0-01, WP-M0-04. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Benennung der fachlichen Ansprechpartner für Vertragsreviews und Abweichungsfreigaben.
- **Entscheidungen:** D-12 (Teambesetzung der Review-Rollen; blockiert nicht).
- **Größe:** S
- **Nicht im Umfang:** Erstellung der Detailverträge für die 46 M1-Anforderungen (Aufgabe der M1-Einheiten, läuft einen Sprint voraus); Automatisierung der Prüfungen (WP-M0-32).

### WP-M0-32 – Nachverfolgungs-CI für `arbeitspakete.csv` und `e2e.csv`

- **Ziel:** CI bricht ab, wenn eine als umgesetzt markierte MUSS-Zeile keinen Testfall referenziert; `e2e.csv` wird ausschließlich aus Testläufen mit Build-ID befüllt (Umsetzungsplan §6).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Prüfskript über `docs/umsetzungsplan/arbeitspakete.csv`: 672 Zeilen, eindeutige `source_key`, Abgleich mit `docs/pflichtenheft/anforderungen.csv` (Phase und Priorität unverändert), gültige Statuswerte, MUSS-Zeile mit Status „umgesetzt“ nur mit nicht leerem `testfall` und `detailvertrag`, referenzierte Testfälle existieren im Testkatalog.
  - Prüfung, dass jeder Meilenstein und jedes `arbeitspaket` der CSV auf ein dokumentiertes WP unter `docs/umsetzungsplan/wp/` verweist und jede `source_key` dort in genau einem WP steht. Grundlage ist das vorhandene `docs/umsetzungsplan/check_wp.py` (672 Anforderungen genau einmal, Abhängigkeiten ohne Zyklus, Katalogabdeckung von Tests, Parametern, Kennzahlen, Tabellen, Endpunkten und UI-IDs); es wird als Pflichtjob eingebunden.
  - Übertragungsschritt Evidenz → `e2e.csv`: schreibt die Spalten `status`, `build`, `nachweis`, `freigabe` aus den Evidenzdatensätzen (WP-M0-22); manuelle Änderungen dieser Spalten werden in Merge-Requests abgewiesen.
  - Gate-Bericht je Meilenstein: Liste der MUSS-Anforderungen ohne grünen Test, Liste der Referenztests ohne Evidenz.
- **Detailverträge vorab:** Statuswerte aus WP-M0-31; Evidenzschema aus WP-M0-22; Regel für die Spalte `freigabe` (wer darf eine Abnahmeentscheidung eintragen).
- **Akzeptanzkriterien:**
  1. Eine Probezeile „MUSS, umgesetzt, ohne Testfall“ lässt die Pipeline fehlschlagen.
  2. Eine von Hand geänderte Zeile in `e2e.csv` (Status ohne Build-ID) lässt die Pipeline fehlschlagen.
  3. Der Übertragungsschritt trägt das Ergebnis des Beispielszenarios aus WP-M0-22 mit Build-ID ein; `validate_artifacts.py` bleibt grün (weiterhin 106 Zeilen).
  4. Eine geänderte Phase oder Priorität gegenüber `anforderungen.csv` wird gemeldet.
  5. Der Gate-Bericht für M0 lässt sich erzeugen.
  6. `check_wp.py` läuft als Pflichtjob mit Exitcode 0; eine doppelt zugeordnete, fehlende oder unbekannte `source_key`, eine zyklische WP-Abhängigkeit oder ein Verweis auf ein nicht existierendes WP lässt die Pipeline fehlschlagen.
  7. Der Übertragungsschritt lehnt Evidenz ab, deren Build-ID nicht zu einem Pipeline-Lauf gehört oder die nicht gegen das Evidenzschema validiert.
- **Tests:** positiv: aktueller Stand grün. negativ: die Fälle aus Kriterien 1, 2 und 4; doppelte bzw. fehlende `source_key`; Verweis auf nicht existierenden Testfall; zyklische WP-Abhängigkeit; Evidenz mit erfundener Build-ID. konkurrierend: zwei gleichzeitige Pipeline-Läufe übertragen Evidenz für denselben Test – der Übertragungsschritt serialisiert und verliert keinen Eintrag.
- **Abhängigkeiten:** intern: WP-M0-12, WP-M0-22, WP-M0-31. extern: keine.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Inhaltliche Prüfung, ob ein Test die Anforderung fachlich abdeckt (Review nach WP-M0-31); Abnahmeentscheidung selbst (Auftraggeber).

### WP-M0-33 – Bestätigung von Umfang und Testzuordnung mit dem Auftraggeber

- **Ziel:** Der Auftraggeber hat den P1-Umfang (202 MUSS), die Meilensteinzuordnung der 672 Anforderungen und die vorgeschlagene Zuordnung der 106 Originaltests zu Meilensteinen bestätigt oder Änderungen beschlossen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Abstimmungsunterlage: Meilensteintabelle, Zuordnung der 106 Tests aus `e2e.csv` und der 22 PH-AT-Tests zu Meilensteinen, Liste der Entscheidungen D-01 bis D-12 mit „benötigt bis Beginn“ (Umsetzungsplan §5).
  - Protokollierte Beschlüsse im Format des Beschlussprotokolls (Kap. 07); terminierte Entscheidungstermine für D-12 und D-05 (Umsetzungsplan §9, Schritt 1).
  - Aktualisierter Testkatalog (Zielmeilenstein je Test) und – nur bei Beschluss – aktualisierte Zuordnung; Phasen und Prioritäten bleiben unverändert.
  - Hinweis auf die doppelten IDs COM-001 bis COM-012 (Kap. 08 §8.1; durchgängig `source_key`, Suffix `-CLR` für I.9).
  - Liste der Beistellungen des Auftraggebers für M0 mit Termin: Zielgeräte und Testgeräte, PSP-Kandidaten und Sandbox-Zugänge, zweite Routingvariante, Pilotregion bzw. Stellvertreterregion, Hostingkonto, fachliche Ansprechpartner.
  - Bestätigung der Erfolgsschwellen der Spikes, die dieser Plan vorschlägt (Offene Punkte 2), vor Spike-Beginn.
- **Detailverträge vorab:** keine.
- **Akzeptanzkriterien:**
  1. Für jeden der 106 Tests ist ein bestätigter Zielmeilenstein im Testkatalog eingetragen.
  2. Es liegt ein protokollierter Beschluss oder eine dokumentierte offene Frage zu D-12 und D-05 mit Termin vor.
  3. Etwaige Vorziehungen oder Streichungen sind ausschließlich als D-12-Beschluss dokumentiert (Umsetzungsplan §7).
  4. Die Prüfungen aus WP-M0-32 laufen nach der Aktualisierung grün.
  5. Für jede Beistellung liegt ein Termin oder eine dokumentierte Ersatzannahme (Referenzgerät, Stellvertreterregion, nur Simulator) vor; die betroffenen Spike-Berichte kennzeichnen die Ersatzannahme.
  6. Auch die 22 PH-AT-Tests haben einen bestätigten Zielmeilenstein; PH-AT-Tests sind laut Kap. 08 §8.5 neu vorgeschlagen und werden nicht als Originaltests des Lastenhefts ausgegeben.
- **Tests:** positiv: Zählprüfung 106 Tests mit Zielmeilenstein. negativ: Test ohne Zielmeilenstein wird gemeldet.
- **Abhängigkeiten:** intern: WP-M0-22, WP-M0-31, WP-M0-32. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Teilnahme und Beschluss zu Umfang, Testzuordnung, D-12 und D-05.
- **Entscheidungen:** D-12 (Budget, Team, Termin, Vorziehungen – benötigt zu Beginn M0; bei Verzug bleibt der Plan ohne Termine, der Bau ist nicht blockiert); D-05 (Terminierung).
- **Größe:** S
- **Nicht im Umfang:** Inhaltliche Klärung der übrigen Entscheidungen D-01 bis D-11 (jeweiliger Meilenstein).

### WP-M0-34 – M0-Gate-Bericht und Entscheidungsvorlagen

- **Ziel:** Das Gate M0 ist nachweisbar bestanden; die Entscheidungen über Routinganbieter und PSP-Kandidat sind vorbereitet und die Schätzung aus Umsetzungsplan §8 ist anhand der Spike-Ergebnisse neu bewertet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Gate-Bericht: Nachweis „Kernschema migriert auf leerer Datenbank“ (Build-ID, Migrationsstand), Zusammenfassung der fünf Spike-Berichte mit Messwerten und Erfüllungsgrad der Erfolgskriterien, Stand aller CI-Stufen, offene Befunde mit Mangelklasse.
  - Entscheidungsvorlagen „Routinganbieter“ (D-09) und „PSP-Kandidat“ (D-03 Teil) im Format des Beschlussprotokolls.
  - Aktualisierung von `docs/umsetzungsplan.md` §7 (Risiken) und §8 (Schätzung) sowie Folgeaufgaben für M1/M2 aus den Spike-Empfehlungen.
- **Detailverträge vorab:** keine.
- **Akzeptanzkriterien:**
  1. Der Gate-Bericht verweist für jedes Gate-Kriterium auf reproduzierbare Evidenz (Pipeline-Lauf, Bericht, Rohdaten).
  2. Jeder der fünf Spikes hat einen Bericht; für jedes nicht erfüllte Erfolgskriterium ist eine Konsequenz (Architekturänderung, Folgespike, akzeptiertes Risiko) benannt.
  3. Beide Entscheidungsvorlagen sind dem Auftraggeber übergeben.
  4. Kein Befund der Klasse A ist offen (Umsetzungsplan §6).
  5. Jedes WP dieser Datei ist abgenommen oder mit dokumentierter Abweichung und Folgetermin geführt; die Testinfrastruktur (PSP-Simulator, Sandbox-Partner, Akzeptanztestgerüst) und alle CI-Stufen sind als „bereit für M1“ bestätigt.
  6. Der Bericht enthält keine Zugangsdaten und keine personenbezogenen Daten aus Spikes.
- **Tests:** positiv: Gate-Bericht aus WP-M0-32 erzeugbar. negativ: fehlender Spike-Bericht verhindert die Gate-Freigabe.
- **Abhängigkeiten:** intern: WP-M0-10, WP-M0-13, WP-M0-14, WP-M0-17, WP-M0-21, WP-M0-24, WP-M0-26, WP-M0-27, WP-M0-28, WP-M0-29, WP-M0-30, WP-M0-32, WP-M0-33, WP-M0-35. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Gate-Abnahme M0 und Entgegennahme der Entscheidungsvorlagen.
- **Entscheidungen:** D-09 und D-03 (Teil PSP) werden vorbereitet; beide blockieren nur die Produktivschaltung, nicht den Bau von M1/M2. D-12 für die Terminaussage.
- **Größe:** S
- **Nicht im Umfang:** Die Entscheidungen selbst; Detailplanung von M1.

### WP-M0-35 – Bereitstellungsstufe und gemeinsame Integrationsumgebung

- **Ziel:** Jeder Stand des Hauptzweigs wird als signiertes Image automatisch in eine gemeinsame Integrationsumgebung ausgerollt; die Migration läuft dort über den getrennten Migrationszugang, die Anwendung ausschließlich unter der Runtime-Rolle. Damit haben M1 und die Folgeeinheiten die laufende Umgebung, die sie als „Staging“ voraussetzen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Pipeline-Stufe „Bereitstellung“ nach erfolgreichem Lauf aller Pflichtstufen: Verifikation der Image-Signatur (WP-M0-14) vor dem Ausrollen, Flyway-Migration als eigener kurzlebiger Schritt mit Migrationszugang (Kap. 01 §1.7 „separater kurzlebiger Betriebszugang“), danach Start von `backend/app` mit Runtime-Zugang.
  - Integrationsumgebung auf Basis des IaC-Gerüsts (WP-M0-11) mit PostgreSQL 17/PostGIS, RabbitMQ, Redis, Keycloak (Realm aus WP-M0-09), MinIO und Valhalla; eigene Geheimnis- und Identitätsdomäne, keine Produktionsdaten (Kap. 01 §1.7, §1.8).
  - Übergangslösung, solange D-05 kein Hostingkonto liefert: Integrationshost auf Basis der Compose-Definition aus WP-M0-08, ausdrücklich als vorläufig gekennzeichnet.
  - PSP-Simulator (WP-M0-23) und Sandbox-Partner (WP-M0-24) als nur im Testnetz erreichbare Dienste der Integrationsumgebung, sobald verfügbar.
  - Rauchtest nach jedem Ausrollen: Health, Migrationsstand, Rollenattribute der Laufzeitverbindung (kein Superuser, kein BYPASSRLS, kein Tabelleneigentum), Erreichbarkeit von Keycloak; Ergebnis mit Build-ID.
  - Rücknahmeverfahren: vorheriges Image erneut ausrollen; Datenbank nur vorwärts korrigieren (Kap. 01 §1.8).
- **Detailverträge vorab:** Ablaufbeschreibung der Bereitstellung (Reihenfolge Signaturprüfung → Migration → Start → Rauchtest, Abbruchregeln); Liste der Geheimnisse je Schritt mit Zuordnung „nur Migrationsschritt“ bzw. „Laufzeit“; Umgebungsmatrix aus WP-M0-11.
- **Akzeptanzkriterien:**
  1. Ein Merge auf den Hauptzweig führt ohne manuellen Schritt zu einer laufenden Version in der Integrationsumgebung; Build-ID und Migrationsstand sind dort abfragbar.
  2. Ein unsigniertes oder manipuliertes Image wird nicht ausgerollt.
  3. Schlägt die Migration fehl, startet die neue Anwendungsversion nicht; die zuvor laufende Version bleibt erreichbar.
  4. Der Laufzeitprozess verfügt nachweislich nicht über den Migrationszugang; der Rauchtest belegt die Rollenattribute der Runtime-Rolle in der Umgebung.
  5. In der Umgebung liegen ausschließlich synthetische Daten und Testidentitäten; Geheimnisse stammen aus der Geheimnisverwaltung, nicht aus dem Repository.
  6. Ist die Übergangslösung im Einsatz, steht das im Gate-Bericht (WP-M0-34) mit der Bedingung für den Wechsel auf die Zielplattform.
- **Tests:** positiv: Merge → Ausrollen → Rauchtest grün. negativ: unsigniertes Image; fehlerhafte Probemigration; Laufzeitkonfiguration mit Migrationszugang wird vom Rauchtest abgewiesen; Rauchtest schlägt bei gestopptem Keycloak verständlich fehl. konkurrierend (Sicherheit/Datenintegrität): zwei gleichzeitig gestartete Bereitstellungen – die Migration läuft genau einmal, die Bereitstellungen werden serialisiert, am Ende läuft der jüngere Stand.
- **Abhängigkeiten:** intern: WP-M0-09, WP-M0-11, WP-M0-14, WP-M0-16. extern: keine (kein vorgelagerter Meilenstein).
  - Beistellung Auftraggeber (kein WP): Hostingkonto, Hostinganbieter und Hostingregion (D-05); ohne Beistellung gilt die Übergangslösung.
- **Entscheidungen:** D-05 (Hosting; blockiert nur die Zielplattform, nicht die Übergangslösung und nicht den Bau von M1). D-02 (Isolationsstufe; die Umgebung folgt dem RLS-Default).
- **Größe:** M
- **Nicht im Umfang:** Endgültige Umgebungen Staging, Performance und Production mit gestaffeltem Rollout, PITR, Backup, Restore und Failover (M5); Alarmierung und synthetische Proben (M5); Bereitstellung der Mobile-App an Geräte (Spike WP-M0-28, Produktiv M3).

## Offene Punkte

M0 hat keine Lastenheft-Anforderungen; die folgenden Punkte beziehen sich deshalb auf Fundstellen im Pflichtenheft und im Umsetzungsplan statt auf `source_key`s.

1. **Zielgeräte der Driver-App nicht benannt.** Der Umsetzungsplan verlangt den Spike „auf Zielgeräten“; Kap. 01 §1.1 nennt nur „verwaltete Geräte“. Hersteller, Modelle, Betriebssystemversionen und Geräteverwaltung fehlen (betrifft WP-M0-06, WP-M0-28; abhängig von D-05/D-12).
2. **Erfolgsschwellen der Spikes nur teilweise vorgegeben.** Vorgegeben sind „100 parallele Transaktionen“ und „innerhalb 1 s“ (dies in Kap. 01 §1.4 ausdrücklich als „zunächst zu testender Vorschlag“). Nicht vorgegeben sind Latenzziel für Hold/Confirm, Anzahl der Wiederholungsläufe, Ereigniszahl im Offline-Spike, Instanzgröße und Auftragsdichte im Solver-Spike. Diese Werte sind oben als Vorschlag dieses Plans markiert und vor Spike-Beginn zu bestätigen.
3. **Lastprofil für die Spikes fehlt.** Peak-Anfragen/s, gleichzeitige Buchungen und Flottengröße je Betreiber sind laut D-05 offen; „30.000/Tag ist kein Peakprofil“. Die Spikes (a) und (b) messen daher gegen Referenzwerte und sind nach D-05 gegebenenfalls zu wiederholen.
4. **PSP-Kandidaten und zweite Routingvariante nicht benannt.** Der Umsetzungsplan fordert eine „PSP-Sandbox“ und D-09 einen Vergleich „mit zwei verfügbaren Varianten“, ohne Anbieter zu nennen. Auswahl und Zugänge müssen vor WP-M0-29 bzw. WP-M0-30 vorliegen.
5. **`kernschema.sql` enthält eigene Transaktionssteuerung.** Die Datei beginnt mit `BEGIN;` und endet mit `COMMIT;`, setzt `SET search_path` und legt mit `CREATE SCHEMA taktovia` das Schema selbst an. Das kollidiert mit der Transaktions- und Schemaverwaltung von Flyway und muss in WP-M0-16 angepasst und in WP-M0-21 zurückgeführt werden.
6. **`CREATE EXTENSION postgis` und Rollentrennung.** Die Erweiterung benötigt je nach Installation höhere Rechte als eine reine Tabelleneigentümer-Rolle. Kap. 04 §4.6 regelt nicht, ob der Migrator diese Rechte haben darf oder ob ein getrennter Bootstrap-Schritt nötig ist (WP-M0-15).
7. **Kern-DDL legt keine Rollen und Rechte an.** Kopfkommentar von `kernschema.sql`: „Keine Runtime-Rollen/Secrets angelegt“. Die Rollenmatrix „Rolle × Tabelle × Recht“ ist im Pflichtenheft nicht ausformuliert; Kap. 04 §4.6 nennt nur Grundsätze. Insbesondere fehlt die Festlegung der „Modulzugänge“ (Anzahl, Zuschnitt, ob eigene Datenbankrollen je Modul oder nur für PII/HR/Finanzen).
8. **Trigger laufen mit Aufruferrechten.** `protect_journal_line()` und `guard_journal()` sind nicht `SECURITY DEFINER` und sperren `journal` bzw. `fiscal_period` mit `FOR UPDATE`. Die Runtime-Rolle benötigt dafür Rechte auf diesen Tabellen, die über reines Lesen hinausgehen; das Pflichtenheft sagt dazu nichts. Zu klären in WP-M0-15/-20, weil es dem Ziel „nur ausdrücklich benötigte DML-Rechte“ entgegenstehen kann.
9. **Globale Tabelle `currency` ohne RLS.** `currency` hat keine `tenant_id` und damit keine Policy. Das ist vermutlich beabsichtigt (Währungskatalog), in Kap. 04 aber nicht ausdrücklich als Ausnahme von der Mandantenregel genannt. Schreibrechte der Runtime-Rolle auf `currency` sind festzulegen.
10. **Sichtbarkeit der Tabelle `tenant`.** Die Policy `own_tenant` erlaubt nur die eigene Zeile. Wie Plattformadministration Mandanten anlegt oder auflistet (ohne BYPASSRLS und ohne `SECURITY DEFINER`-Allzweckfunktion, Kap. 04 §4.6), ist nicht beschrieben; relevant für Seeds in WP-M0-22 und für M1.
11. **Idempotenzscope unterschiedlich benannt.** Kap. 05 §5.1 definiert den Scope als `(auth_principal, tenant_context, operation_name, Idempotency-Key)`; `idempotency_record` verwendet `principal_ref`, `command_scope`, `key`. Die Zuordnung `operation_name` ↔ `command_scope` ist plausibel, aber nicht ausdrücklich festgelegt.
12. **Inbox-Schlüssel unterschiedlich beschrieben.** Kap. 05 §5.6 nennt als Inbox-Unique-Key `(partner_id,event_id)` plus Payloadhash; die Tabelle `inbox` hat den Schlüssel `(tenant_id, consumer, event_id)` und `payload_sha256` ohne `partner_id`. Zu klären, ob `consumer` den Partner trägt (betrifft Sandbox-Partner WP-M0-24 und M6).
13. **`state`-Spalten sind freier Text.** Kap. 04 §4.1 verlangt, dass produktive Migrationen Zustände als versionierte CHECKs/Transitionsregeln härten. Für M0 ist nicht festgelegt, ob V1 das Entwurfs-DDL unverändert übernimmt (Annahme dieses Plans) oder bereits härtet.
14. **Pflichtspalten fehlen im Kern-DDL.** Kap. 04 §4.1: `created_at`, Urheber, Änderungshistorie und Löschklasse „werden für alle produktiven Aggregattabellen ergänzt“. Zeitpunkt und Verantwortung dieser Ergänzung sind nicht geregelt (Annahme: je Modul ab M1).
15. **Führende Quelle für Schema und API.** Umsetzungsplan §3 nennt `docs/pflichtenheft/schema/kernschema.sql` und `docs/pflichtenheft/api/openapi.yaml` als „Ausgangspunkt“ für `db/migrations/` und `api/`. Ob die Pflichtenheftdateien danach eingefroren, erzeugt oder parallel gepflegt werden, ist offen; `validate_artifacts.py` prüft fest die Pflichtenheftpfade.
16. **`validate_artifacts.py` hängt am Lastenheft-DOCX.** Das Skript erwartet `docs/lastenheft/Lastenheft_On_Demand_Mobilitaetsplattform_V1_2.docx` mit festem SHA-256. Für die CI muss diese Datei im Repository verfügbar bleiben; eine Lastenheftrevision (z. B. Bereinigung der COM-IDs, D-12) bricht die Prüfung, bis der Hash angepasst wird.
17. **Statuswerte in `arbeitspakete.csv` nicht definiert.** Bisher ist nur „Geplant“ belegt; die Nachverfolgungsregel spricht von „als umgesetzt markiert“. Die Werteliste und die Bedeutung der Spalten `detailvertrag` und `testfall` (Pfad, ID, mehrere Werte) fehlen. `build_zuordnung.py` überschreibt die Datei derzeit vollständig und würde gepflegte Spalten löschen.
18. **CI-Plattform und Signaturverfahren nicht festgelegt.** Umsetzungsplan und Kap. 01 §1.8 fordern „SBOM, Lockfiles, signierte Images“, nennen aber weder CI-System noch Registry, Signaturverfahren oder Schwellen für blockierende Schwachstellen und Lizenzen.
19. **Hosting offen.** Kap. 01 §1.1 fordert EU-Hosting auf verwalteter Containerplattform; Anbieter und Region stehen unter D-05. Das IaC-Gerüst (WP-M0-11) bleibt bis dahin anbieterneutral und nicht anwendbar.
20. **Schnittstelle Kern ↔ Python-Worker nicht spezifiziert.** Kap. 01 nennt getrennte Worker und RabbitMQ, legt aber nicht fest, ob die Angebotsberechnung innerhalb des 1-s-Budgets synchron oder über die Queue aufgerufen wird. Der Spike (b) soll dazu eine Empfehlung liefern.
21. **Entscheidung D-12 wird „bis Beginn M0“ benötigt.** Ohne Beschluss zu Budget, Team und Termin können `CODEOWNERS`, Review-Rollen und Terminaussagen nur mit Rollen statt Personen belegt werden; laut Umsetzungsplan §5 bleibt der Plan dann ohne Termine.
22. **Detailverträge M1 liegen zeitlich in M0, gehören aber nicht zur Einheit M0.** Umsetzungsplan §9 Schritt 3 verlangt die Detailverträge der 46 M1-Anforderungen vor M1-Beginn. Diese Arbeit ist hier bewusst nicht als WP geführt, damit keine Anforderung doppelt zugeordnet wird; sie muss in den M1-Einheiten mit Start während M0 eingeplant werden.
23. **Spike-Beleg „PostGIS“ ist nicht näher bestimmt.** Kap. 01 §1.1 verlangt, dass ein Technologie-Spike „Driver-Offlinemodus, PostGIS, Solverzeit und PSP-Sandbox“ belegt; der Umsetzungsplan formuliert „Hold/Confirm unter 100 parallelen Transaktionen auf PostGIS“. Die Kapazitätstabellen `vehicle_run`, `capacity_hold` und `booking` enthalten keine Geometrie. WP-M0-26 legt das als Gebiets- und Haltepunktprüfung auf `service_area` und `stop_revision` im Buchungspfad aus (Annahme dieses Plans); Datenmenge und Zielwerte dafür fehlen im Pflichtenheft.
24. **Linter-Regeln passen nicht ausnahmslos auf den Ausgangsstand.** Kap. 05 §5.1 beschreibt `Idempotency-Key` und `If-Match` als Konvention; in `openapi.yaml` hat `receivePartnerEvent` keinen `Idempotency-Key`, und `planJourney` sowie `recordDriverEvent` haben kein `If-Match`. Das ist fachlich plausibel (Signatur und Inbox bzw. keine Revision), aber nirgends als Ausnahme festgehalten. WP-M0-04 führt eine begründete Ausnahmeliste; sie ist zu bestätigen.
25. **Ablage und Kompatibilitätsprüfung von Ereignisschemas fehlen.** `outbox.schema_version` und Kap. 01 §1.8 („kompatible Events“) setzen versionierte Ereignisschemas voraus; Umsetzungsplan §3 sieht dafür kein Verzeichnis vor, und Kap. 05 enthält keinen Ereigniskatalog. WP-M0-04 legt nur Ort und Konvention an (Annahme dieses Plans).
26. **Steuerbare Zeit in Tests ist nicht geregelt.** Hold-Ablauf nach Serverzeit `now >= expires_at` (Kap. 01 §1.4), das Zeitfenster ±300 s (Kap. 05 §5.6) und DST-Fälle (PH-AT-018) verlangen reproduzierbare Zeit. Das Pflichtenheft nennt keine Zeitquelle des Kerns; die Datenbankzeit lässt sich in Tests nicht verstellen. M0 liefert steuerbare Uhren nur für Simulator und Sandbox (WP-M0-22 bis -24); die Zeitquelle des Kerns muss M1 in `platform` („Geld- und Zeittypen“) festlegen.
27. **Beistellungen des Auftraggebers und die Prüfregel für externe Abhängigkeiten.** `docs/umsetzungsplan/check_wp.py` wertet jede Zeile mit dem Marker für externe Abhängigkeiten als harten Fehler („unaufgelöst“), weil der Marker für Abhängigkeiten zu anderen Meilensteinen gedacht ist. M0 hat keinen vorgelagerten Meilenstein; die 13 Abhängigkeiten zum Auftraggeber (Zielgeräte, PSP-Kandidaten, Routingvariante, Hosting, Ansprechpartner, Freigaben) sind deshalb als „Beistellung Auftraggeber (kein WP)“ geführt. Sie haben keinen Termin; WP-M0-33 soll ihn herbeiführen.
28. **Folgeeinheiten erwarten eine Staging-Umgebung aus M0.** Mehrere Einheiten nennen „Staging-Umgebung“ bzw. „Keycloak in lokaler Umgebung und Staging“ als Vorleistung von M0. Der Umsetzungsplan nennt für M0 nur die lokale Umgebung; Hosting steht unter D-05. WP-M0-35 liefert eine gemeinsame Integrationsumgebung, notfalls als Übergangslösung. Ob das die Erwartung „Staging“ im Sinn von Kap. 01 §1.8 erfüllt, ist mit den Einheiten und dem Auftraggeber zu klären.
29. **Beobachtungen am Kern-DDL aus der Lektüre (nicht ausgeführt, in WP-M0-20/-21 zu prüfen).** (a) `guard_journal()` vergleicht mit einer per `SELECT … FOR UPDATE` gelesenen Periode; wird keine Zeile gefunden, ergeben die Vergleiche NULL und lösen keine Ausnahme aus. (b) Für `allocation_line` gibt es keine datenbankseitige Summenprüfung gegen `allocation_set.basis_minor` (Kap. 04 §4.5 weist das als „noch umzusetzen“ aus). (c) `fare_quote.tariff_revision_id` hat keinen Fremdschlüssel, weil `tariff_revision` erst in Kap. 04 §4.4 vorgesehen ist. (d) `journal_line` trägt keine Währung; die Währungsbindung besteht nur über den Kopf. (e) `booking.hold_id` ist eindeutig, die Datenbank prüft aber nicht, dass der Hold im Zustand `CONSUMED` ist. (f) Die Geo-Indizes `service_area_geo` und `stop_geo` enthalten kein `tenant_id`, obwohl Kap. 04 §4.1 verlangt, dass Indizes mit dem Mandanten beginnen. Keiner dieser Punkte ist als Fehler belegt; sie sind Prüfaufträge.
30. **PSP-Simulator deckt PH-AT-014 nur teilweise.** PH-AT-014 setzt Auszahlung, Rückforderung und Provisionskorrektur voraus; Transfer und Payout sind laut Kap. 03 §4.2 eigene Objekte und gehören zu M6. Der Simulator aus WP-M0-23 liefert in M0 nur Authorize, Capture, Void, Refund, Status und Streitfall; die Erweiterung muss in der Einheit M6c eingeplant sein.
31. **Zwei Erzeuger für `arbeitspakete.csv` mit unterschiedlichem Spaltenlayout.** `build_zuordnung.py` schreibt zehn Spalten, `check_wp.py --write` dreizehn (zusätzlich `wp_titel`, `wp_groesse`, `wp_datei`); beide überschreiben `detailvertrag`, `testfall` und `status`. Welches Skript führend ist, ist nicht festgelegt (WP-M0-31, WP-M0-32).
