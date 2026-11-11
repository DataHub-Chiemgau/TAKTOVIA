# backend

Kotlin (Java 21) und Spring Boot, ein Gradle-Multiprojekt mit **einem** Deployable (`app`). Python-Worker liegen unter `workers/` und gehören nicht zu diesem Build.

```bash
cd backend
./gradlew build            # alle 20 Teilprojekte, Tests, Architekturregeln
./gradlew :app:bootRun     # Start gegen die lokale Datenbank (Runtime-Rolle)
./gradlew resolveAndLockAll --write-locks   # Lockfiles nach Abhängigkeitsänderung erneuern
```

Datenbanktests brauchen `TAKTOVIA_DB_HOST/PORT/NAME` und `TAKTOVIA_DB_RUNTIME_PASSWORD` (optional `TAKTOVIA_DB_ADMIN_*`, `TAKTOVIA_DB_MIGRATOR_PASSWORD` für den Rollen-Negativtest). Ohne Zugangsdaten werden sie übersprungen; mit `TAKTOVIA_REQUIRE_DB=true` (CI) schlagen sie stattdessen fehl. Schema und Rollen: [db/README.md](../db/README.md).

## Struktur und erlaubte Abhängigkeiten

| Projekt | Inhalt | darf abhängen von |
|---|---|---|
| `platform` | Tenantkontext, RLS, Outbox/Inbox, Idempotenz, Audit, Geld/Zeit, Cache (Platzhalter bis M1) | nichts Fachliches |
| `modules/*` (11) | `identity`, `config`, `service-geo`, `booking-dispatch`, `journey-federation`, `fare-payment`, `fleet-safety`, `staff-time`, `documents-finance`, `communication-support`, `analytics`; je `api` und `internal` | `platform`; andere Module nur über deren `api` |
| `adapters/*` (7) | `psp`, `partner-tomp`, `partner-osdm`, `timetable-gtfs`, `erp-export`, `ocr`, `notification` | `platform`, `api` von Modulen |
| `app` | Zusammenbau, Health, Rollenprüfung beim Start | alles |

Durchgesetzt von [ArchitectureRules](app/src/test/kotlin/de/taktovia/architecture/ArchitectureRules.kt) (ArchUnit) und [TableOwnership](app/src/test/kotlin/de/taktovia/architecture/TableOwnership.kt). Jede Regel hat einen roten Selbsttest gegen absichtlich fehlerhafte Fixtures in `archfixtures/`. Tabellenbesitz: [db/table_owners.d/](../db/table_owners.d/).

## Versionen

Zentral in `gradle/libs.versions.toml`; Gradle-Wrapper mit Prüfsumme; `gradle.lockfile` je Teilprojekt (Dependency-Locking für alle Konfigurationen). Plugins (Kotlin, Spring Boot) sind über den Katalog festgelegt, aber nicht gelockt. Details: [versionen.md](../docs/umsetzungsplan/versionen.md).

## API-Grundgerüst (WP-M1-02)

Alle `/v1/…`-Anfragen laufen durch die Filterkette `CorrelationFilter` (W3C `traceparent` → Correlation-ID) → `RateLimitFilter` (Quote je Mandant und Akteur, 429 mit `Retry-After`) → `RequestValidationFilter` (strikter Content-Type, Größen-/Tiefenlimit, Body gegen das OpenAPI-/JSON-Schema der Operation, `If-Match`). Fehler jeder Art erscheinen als `application/problem+json` (Schema `Problem`, Katalog `ErrorCode`/`ViolationCode` in `api/common.yaml`) über `GlobalProblemAdvice`; Datenbankmeldungen gelangen nie nach außen (`platform.problem.DbErrorMapper`). Bausteine ohne Spring-Web in `platform.api` (ETag, signierter Cursor, Quote, Traceparent) und `platform.money` (Geldtypen, Währungskatalog).

| Einstellung (`taktovia.api.…`, Planungsdefaults, OP-20) | Standard |
|---|---|
| `cursor-secret` (mind. 32 Byte; ohne Wert zufällig je Instanz, nur für Entwicklung) | – |
| `rate-limit.requests` / `rate-limit.window-seconds` | 600 / 60 |
| `rate-limit.redis-url` (gemeinsamer Zähler mehrerer Instanzen; sonst prozesslokal) | – |
| `max-body-bytes` / `max-json-depth` / `max-string-length` | 1 MiB / 32 / 100000 |
| `extra-specs` (zusätzliche OpenAPI-Dateien, nur Tests) | – |

Die Verträge aus `api/*.yaml` werden beim Bauen in die Anwendung kopiert (`processResources`). Tests der Gerüstteile laufen gegen die echte Anwendung und die echte Datenbank über die Prüf-Endpunkte unter `app/src/test/…/probe` (`taktovia.probe.enabled=true`, nur im Test).

## Ressourcenserver und Umgebungstrennung (WP-M1-04, WP-M1-40)

Jede Anfrage außer `/actuator/health` braucht ein gültiges Bearer-Token (`security.AuthenticationFilter`, nach Herkunftsprüfung und vor der Quote): Signatur (RS256/PS256/ES256, Schlüssel per `kid` aus dem JWKS des Issuers, Rotation und Fail-closed), `iss`, `aud`, `exp`, Claims-Vertrag [token-claims.v1](../docs/umsetzungsplan/vertraege/schemas/token-claims.v1.schema.json) → sonst 401. Privilegierte Rollen brauchen `acr=loa2` (zweiter Faktor), sonst 403 und Sicherheitsereignis `security.auth.mfa_missing`. Dienstkonten sind auf Adapter und Scopes begrenzt. Das System speichert keine Passwörter. Vertrag und Planungswerte: [idp-realm.md](../docs/umsetzungsplan/vertraege/idp-realm.md).

| Einstellung (`taktovia.idp.…`) | Standard |
|---|---|
| `enabled` (Ausschalten nur mit `taktovia.environment=local`) | `true` |
| `issuer-uri` (Pflicht), `jwks-uri`, `audience` | –, `<issuer>/protocol/openid-connect/certs`, `taktovia-api` |
| `jwks-cache-seconds` / `jwks-min-refresh-seconds` / `max-stale-seconds` | 300 / 10 / 3600 |
| `clock-skew-seconds` / `max-token-lifetime-seconds` | 30 / 900 |
| `privileged-roles`, `human-clients` | `tenant_admin,dispatcher,finance`, `passenger-web,operator-web,driver-app` |
| `service-clients.<client>` (Scope-Obergrenze je Adapter) | – (kein Dienstkonto zugelassen) |

`EnvironmentGuard`: `taktovia.environment` (`local`=`development`, `staging`, `performance`, `production`) muss der unveränderlichen Umgebungskennung der Datenbank entsprechen, sonst kein Start. Die bestehenden Gerüsttests laufen mit `taktovia.idp.enabled=false` (Testkonfiguration); die Sicherheitstests starten die Anwendung gegen einen lokalen JWT-Test-Issuer (`TestIdp`).

## Identität, Anfragekontext und Mandantenwechsel (WP-M1-06)

Nach der Token-Prüfung bildet der `RequestContextFilter` (`app.identity`) je Anfrage den unveränderlichen `platform.context.RequestContext`: der Mandant folgt **nur** aus einer serverseitig geprüften aktiven Mitgliedschaft (`membership`, Modul `identity`), nie aus Header, Pfad, Query oder Body. Abweichende Tenant-Angaben ergeben 404 plus Sicherheitsereignis, fehlende Mitgliedschaft oder fehlender Funktionsscope 403 ohne Details. Fachoperationen laufen über `platform.context.ContextTransactions` (Mitgliedschaft in derselben Transaktion erneut prüfen, schreibend mit `FOR SHARE`), damit ein Widerruf linearisiert wirkt. Endpunkte: `/v1/me`, `/v1/me/tenant-switch`, `/v1/memberships`. Vertrag: [identitaet-kontext.md](../docs/umsetzungsplan/vertraege/identitaet-kontext.md).

| Einstellung (`taktovia.identity.…`, Planungsdefaults) | Standard |
|---|---|
| `context-secret` (mind. 32 Byte; Signatur der Mandantenbindung; ohne Wert zufällig je Instanz, nur für Entwicklung) | – |
| `context-ttl-seconds` (Gültigkeit der Mandantenbindung) | 3600 |

Für andere Module: `RequestContext` aus `platform.context` verwenden und Datenbankzugriffe über `ContextTransactions.run(ctx) { jdbc -> … }`; den Mandanten nie aus Anfrage-DTOs lesen (Architekturregel `RequestContextRules`: kein `tenant_id` in `*Request`-DTOs oder `@RequestBody`-Typen, Kontexte entstehen nur im Filter).

## Rollenmodell und Rechtematrix (WP-M1-07)

Rollenkatalog (`passenger`, `driver`, `callcenter`, `support`, `dispatcher`, `planner`, `tenant_admin` plus Platzhalter `finance`, `staff_planning`, `workshop`) und die versionierte **Rechtematrix** `modules/identity/src/main/resources/rechtematrix/rechtematrix.csv` sind die einzige Rechtequelle: Funktionsscopes folgen aus der Matrix, die zentrale Autorisierung (`identity.api.Authorizer`) entscheidet im `RequestContextFilter`. Eine Operation der API-Verträge ohne Matrixzeile lässt den Build scheitern; Änderungen der Matrix brauchen Versionsanhebung und `rechtematrix.lock` und werden beim Start in `rights_matrix_rollout` protokolliert. Der Administrator hat keinen Zugriff auf PII-/HR-/Finanz-Tätigkeiten ohne gesonderte Fachrolle. Details und offene Punkte (OP-10): [rechtematrix.md](../docs/umsetzungsplan/vertraege/rechtematrix.md).

**Neue Route bauen:** Operation in `api/<modul>.yaml` mit `security: oidc: [<scope>]` deklarieren, Zeile in `rechtematrix.csv` ergänzen (Scope muss zu den übrigen Zeilen desselben Scopes passen), `# matrix_version` erhöhen und `rechtematrix.lock` erneuern (Test nennt den `sha256`-Wert).

## Mandant, Rechtsträger, Organisation und vertragliche Rollen (WP-M1-05)

`/v1/legal-entities` und `/v1/organizations` (Modul `identity`, Port `OrganizationAdmin`) verwalten Rechtsträger und Organisationen des aktiven Mandanten; die sechs vertraglichen Rollen (`PLATFORM_OPERATOR`, `TRANSPORT_COMPANY`, `TASK_AUTHORITY`, `CLIENT`, `BROKER`, `ACTUAL_CARRIER`) sind getrennte Werte (DB-CHECK, OpenAPI-Enum, `OrganizationRole`), keine wird abgeleitet. Jede Änderung: Zeile, genau ein Auditeintrag (`identity.organization.changed`) und genau ein Outbox-Ereignis (`organization.changed`) in einer Transaktion; veraltete Revision 412, doppelte `federation_ref` 409, fremder Mandant 404.

**Plattformverwaltung (OP-36, Planungsannahme):** `POST /v1/platform/tenants` und `POST /v1/platform/tenant-status-changes` tragen keinen Mandantenkontext. Erlaubt sind nur Konten aus `platform_operator_account` (Pflege durch die Administration, z. B. `INSERT INTO taktovia.platform_operator_account(issuer, subject) VALUES (...)`) mit zweitem Faktor; sie haben keine Mitgliedschaft und sehen keine Fachdaten. Die Anlage legt Mandant, Rechtsträger und erste Administrator-Mitgliedschaft in einer Transaktion an. In `SUSPENDED` und `CLOSED` verweigert jeder Fachzugriff des Mandanten (Mitgliedschaftsprüfung verlangt `ACTIVE`).

## Unternehmensprofil des Mandanten (WP-M1-24)

`GET`/`PUT /v1/company-profile` (Modul `config`, Port `ConfigApi.companyProfile`): Kontakt, Anschrift, Abrechnungsangaben, Betriebsart, Rechtsinformationen und Standardwährung als versionierte Konfiguration (`config_revision`, `scope_kind = TENANT_PROFILE`, Planungsannahme PA-11/OP-27, Schema `docs/umsetzungsplan/vertraege/schemas/tenant-profile.v1.schema.json`). Nur `tenant_admin` ändert (Rechtematrix `company:write`); die Revision ist der ETag (`If-Match`, 412 bei veralteter oder gleichzeitiger Änderung, genau eine Gewinnerin). Die Währung muss im Katalog `currency` stehen (422 an `/default_currency`). Je Änderung ein Audit (`config.tenant_profile.changed`, ohne Personendaten) und ein Ereignis `tenant.profile_changed`; die Mandantenanlage der Plattformverwaltung legt Revision 1 in derselben Transaktion an.

## Fahrzeugrollen mit Gültigkeitsintervallen (WP-M1-34)

`GET`/`POST /v1/vehicle-roles` (Modul `fleet-safety`, Port `FleetsafetyApi.vehicleRoles`): Eigentümer, Betreiber, Einsatzleister und Abrechnungspartner (`OWNER`, `OPERATOR`, `DISPATCH_PROVIDER`, `BILLING_PARTNER`) sind unabhängige Zuordnungen Fahrzeug-Organisation mit halboffenem Intervall `[valid_from, valid_to)` in `vehicle_assignment` (RLS, zusammengesetzte Schlüssel, Exclusion Constraint je Fahrzeug und Rolle, abgeschlossene Intervalle unveränderlich). Ein Wechsel zum Stichtag schließt das laufende Intervall und legt ein neues an (ein Audit `fleet.vehicle_role.changed`, ein Ereignis `vehicle.role_assignment_changed`); gleichzeitige Wechsel desselben Fahrzeugs werden über die Fahrzeugzeile serialisiert (genau einer gewinnt, der andere 409). **Abrechnende Module** nutzen `rolesAt(jdbc, vehicleId, instant)` für den Snapshot „Rollen zum Leistungszeitpunkt“, legen ihn ab und lesen nie live. Voraussetzung der Datenbank: Erweiterung `btree_gist` (in der Migration `V1340` angelegt, vertrauenswürdig; das Datenbankabbild braucht die contrib-Erweiterungen).
## Sitzungsverwaltung und Sitzungssperre (WP-M1-10)

Jede Anfrage eines Menschen prüft nach der Mandantenermittlung den Sitzungsstatus (`identity.api.SessionGate`, im `RequestContextFilter`): gesperrte Sitzung oder gesperrtes Konto ergibt 401 trotz gültigem Token, Nichtprüfbarkeit 503 (fail-closed). PostgreSQL führt (`session_register`, `account_access`); Redis ist nur Cache der **Sperrliste** (kein positiver Cache). Operationen: eigene Sitzungen, „alle Geräte abmelden“, Sitzungen und Konten sperren/entsperren, Wiederherstellung nur über im IdP verifizierten Kontakt (`api/sessions.yaml`, `identity.yaml`). IdP-Schritte laufen über den Port `IdpAdmin` (Keycloak-Admin-REST, Dienstkonto `taktovia-idp-admin`) nach dem Commit. Vertrag: [LH-6.2-IAM-009](../docs/umsetzungsplan/vertraege/LH-6.2-IAM-009.md).

| Einstellung (Planungswerte, OP-13) | Standard |
|---|---|
| `taktovia.identity.session.redis-url` (Cache der Sperrliste; ohne Wert entscheidet immer die Datenbank) | – |
| `taktovia.identity.session.max-age-seconds` / `block-ttl-seconds` / `cache-ttl-seconds` (Kontosperren, höchstens 60) | 36000 / 86400 / 60 |
| `taktovia.idp.admin.base-url`, `realm` (Standard: letztes Issuer-Segment), `client-id` | –, –, `taktovia-idp-admin` |
| `taktovia.idp.admin.client-secret-file` (oder `client-secret` aus der Umgebung; nie im Repository) | – |
## Idempotenzregister (WP-M1-13)

Jede schreibende Operation, die `Idempotency-Key` verlangt, läuft durch den `IdempotencyFilter` (`app.web`, nach Authentisierung, Mandantenkontext, Rechtematrix und Eingabeprüfung, vor dem Controller) über `platform.idempotency` (`IdempotencyService`, `IdempotencyRegistry`, `RequestFingerprint`): je `(Akteur, Mandant, operationId, Schlüssel)` genau eine Ausführung, Wiederholung liefert das gespeicherte 2xx-Ergebnis (`Idempotent-Replayed: true`), anderer Fingerprint `409 IDEMPOTENCY_CONFLICT`, laufende Erstverarbeitung `409 OPERATION_IN_PROGRESS`. Fehlerantworten werden nicht gespeichert. Vertrag, Kanonisierungsregel und Grenzen: [idempotenz-register.md](../docs/umsetzungsplan/vertraege/idempotenz-register.md).

| Einstellung (`taktovia.idempotency.…`, Planungsdefaults) | Standard |
|---|---|
| `response-ttl-hours` (Antwortcache) | 72 |
| `lease-seconds` (Erstverarbeitung gilt danach als abgebrochen) | 120 |
| `in-progress-wait-millis` (Wartezeit eines parallelen Retransmits) | 10000 |
| `cleanup-minutes` (nur mit einer `TenantSource`-Bean) | 10 |

Für Controller: nichts tun (der Filter wirkt über den Vertrag); die entstandene `operation` optional über das Anfrageattribut `IdempotencyFilter.OPERATION_ID_ATTRIBUTE` melden. Die Fachwirkung muss zusätzlich durch einen fachlichen Unique-Schlüssel einmalig sein (Antwortcache läuft ab).

## Operation mit Lease und Fencing (WP-M1-15)

Lang laufende Befehle werden als `operation` persistiert (`OperationService.enqueue` in der Fachtransaktion, Antwort `202 OperationAccepted` über `OperationResponses.accepted`) und vom `OperationRunner` über `OperationHandler` je Art bearbeitet: Aufnahme per Lease (`lease_until`) mit monoton steigendem `fencing_token`, jede Schreibwirkung gefenced (`LeaseLostException`), Unbestimmtes wird abgeglichen (`RECONCILING`), nie zu Erfolg/Fehler verkürzt. Abfrage: `GET /v1/operations/{operation_id}`. Vertrag, Zustände, Lease/Backoff: [operation-ressource.md](../docs/umsetzungsplan/vertraege/operation-ressource.md).

| Einstellung (`taktovia.operations.…`, Planungsdefaults) | Standard |
|---|---|
| `lease-seconds` / `default-retry-after-seconds` / `reconcile-delay-seconds` | 60 / 3 / 5 |
| `worker-enabled` (Zeitgeber nur mit `TenantSource`-Bean des Betriebs) / `poll-seconds` | `false` / 2 |

## Webhook-Grundlage (WP-M1-16)

`platform.webhook`: **Eingang** (`InboundWebhookReceiver`, HTTP-Anbindung `app.webhook.InboundWebhookResponder`): konstantzeitliche HMAC-Prüfung über den exakten Rohbody, Zeitfenster ±300 s, Größenlimit, Schlüssel-ID mit Rotation, Quittung 202 nach Inbox-Commit / 200 `ALREADY_RECEIVED` / 409 bei gleicher ID mit anderem Payload (Sicherheitsereignis, Quarantäne). **Abonnement** (`/v1/webhook-subscriptions`, Administrator, SSRF-Prüfung, Secret nur als Referenz, auditiert). **Ausgang** (`WebhookFanout`, `WebhookDeliveryWorker`): empfängergebundener minimierter Payload, Signatur, Ziel vor jedem Aufruf erneut geprüft, Retry-Staffel, `Retry-After`, Dead-Letter mit Betreiberwarnung. Vertrag: [webhooks.md](../docs/umsetzungsplan/vertraege/webhooks.md).

| Einstellung (`taktovia.webhooks.…`, Planungsdefaults) | Standard |
|---|---|
| `max-attempts` / `timeout-seconds` | 12 / 10 |
| `worker-enabled` (Zeitgeber nur mit `taktovia.secrets.dir` und `TenantSource`-Bean) / `poll-seconds` | `false` / 5 |
| `taktovia.secrets.domain` (Geheimnisdomäne für `secret_ref`; sonst `taktovia-<umgebung>-secrets`) | – |
## Konfigurationskatalog und Revisionen (WP-M1-19, WP-M1-20)

Modul `config`: Parameterkatalog mit Systemgrenzen (`policy_schema`, `GET /v1/config/parameters`) und Konfigurationsrevisionen (`/v1/config/revisions`: anlegen, lesen, auflisten, Entwurf ändern, `as_of`-Rekonstruktion).
Werte außerhalb von Name, Typ, Einheit, Bereich oder Sicherheitsinvariante werden vor dem Speichern mit 422 abgewiesen; publizierte Revisionen sind in der Datenbank unveränderlich. Andere Module lesen
Konfiguration nur über `de.taktovia.config.api.ConfigApi`. Neue Katalogversionen sind Migrationen (`db/migrations/`), nie Laufzeitänderungen. Entscheidungen und Grenzen:
[konfigurationsrevisionen.md](../docs/umsetzungsplan/vertraege/konfigurationsrevisionen.md).

## Organisationsstruktur: Regionen, Betriebshöfe, Dienste, Kostenstellen (WP-M1-09)

`/v1/regions`, `/v1/depots`, `/v1/cost-centers`, `/v1/services` (Module `service-geo` für Region, Betriebshof und Dienstkopf, `documents-finance` für Kostenstellen als Stammobjekt `cost_object` mit `kind = 'COST_CENTER'`; Ports `ServicegeoApi.structure`, `DocumentsfinanceApi.costCenters`). Ein Mandant hat mehrere Regionen, Betriebshöfe (je genau einer Region), Dienste (optional einer Region zugeordnet, `service.region_id`) und Kostenstellen (optional Region und/oder Dienst); jede Region und jeder Dienst trägt eigene Konfigurationsrevisionen (`scope_kind` `REGION` bzw. `SERVICE`), die WP-M1-21 als wirksame Regeln auflöst. Die Anlage eines Dienstes legt in **derselben** Transaktion seine erste Konfigurationsrevision an (leerer Entwurf, `ConfigRevisions.seedServiceHead`, OP-35); die Vollständigkeit der Dienstkonfiguration verlangt erst die Publikation. Änderungen verlangen `If-Match` (428/412), sind auditiert (`identity.region|depot|service.changed`, `finance.cost_center.changed`) und mandantenweit: sie hängen an der Rolle (`organization:write`, `services:write`), nicht am Dienstscope. Die Liste der Dienste sieht für lesende Rollen nur die Dienste ihres `service_scope`. Region und Betriebshof sind in `kernschema.sql` nicht benannt (OP-07); der Entwurf steht in `db/migrations/V1090__organisationsstruktur.sql`. Die geografische Abgrenzung (`service_area`, Haltepunkte) folgt mit M2.

## Dienst- und Objektberechtigung (WP-M1-08)

Zusätzlich zu Rolle und Funktionsscope begrenzt der Port `platform.authz.ObjectAuthorizer` (Umsetzung `ServiceScopeAuthorizer`) jeden Objektzugriff auf **Mandant, Dienst und konkretes Objekt**: `canRead/canWrite(ctx, ScopedObject(type, id, tenant, serviceId), purpose)`, mit `jdbc` in der Fachtransaktion (liest den **aktuellen** `service_scope` der Mitgliedschaften des Kontexts, schreibend `FOR SHARE`: ein Scope-Entzug wirkt ab seinem Commit, gleichzeitige Entzüge warten) und `requireRead/requireWrite` mit 404 für nicht sichtbare Objekte (auch im selben Mandanten bei fremdem Dienst, nie 403). Regeln (OP-04, fail-closed): fremder Mandant nie; Objekt mit Dienstbezug nur bei Dienst im Scope; `service_scope = NULL` und die leere Liste bedeuten **keinen** Dienstzugriff; Objekt ohne Dienstbezug nur Mandantengrenze. Listen holen sich die erlaubten Dienste über `effectiveServices(ctx, requested, tenantWide)` (Filter schränken nur ein: ein Filter auf fremde Dienste ergibt eine leere Liste) und binden Cursor mit `ServiceViews.fingerprint` an ihre Sicht. Jede Klasse mit Dienstbezug trägt `@ServiceScoped` und muss den Port aufrufen (Architekturregel `ServiceScopeRules`, roter Selbsttest gegen Fixtures); echtes Anwendungsbeispiel: `JdbcOrganizationStructure.listServices`. Den Nachweis an der Buchung liefert WP-M2-23 über denselben Port.
