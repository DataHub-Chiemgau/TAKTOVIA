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

## Präzedenzkette und wirksame Konfiguration (WP-M1-21)

`GET /v1/config/effective` (Modul `config`, Port `ConfigApi.effective`; `parameter`, Kontext `region_id`/`service_id`/`vehicle_id`, `at`) löst die Kette **global → Mandant → Region → Dienst → Fahrzeug** auf (die Stufe „Produkt“ folgt mit M2, OP-22): die spezifischste Ebene mit einem **veröffentlichten, zum Zeitpunkt wirksamen und zulässigen** Wert gilt. Die globale Ebene ist der Parameterkatalog (feste Vorgabe der Sicherheitsinvarianten bzw. `default`); sein Bereich ist die **globale Sicherheitsgrenze**: ein Wert, der sie in der zum Zeitpunkt gültigen Schema-Version verletzt, wird nie als wirksam geliefert (die nächste Ebene gilt, die Antwort nennt ihn als `SKIPPED_LIMIT`). Fahrzeug-Overrides sind nur für freigegebene Parameter zulässig (`config_vehicle_override_release`, per Migration gepflegt, in P1 `wheelchair_boarding_sec`; andere werden beim Anlegen mit 422 abgewiesen und von der Auflösung ignoriert). Die Antwort nennt je Parameter Wert, Einheit, Herkunft (Ebene, Revision, Wirksamkeit), Vorrangregel (`MOST_SPECIFIC_LEVEL_WINS`, `SINGLE_LEVEL`, `SAFETY_LIMIT_FALLBACK`, `GLOBAL_DEFAULT`, `NO_VALUE`), alle Ebenen mit ihrem Schicksal und einen vorgeschlagenen (unveröffentlichten) Wert. Ein Lesestand (eine Anweisung) ist konsistent zu genau einer Revision; der Zwischenspeicher trägt die beteiligten Revisionen und die Schema-Version im Schlüssel. Kontextobjekte fremder Mandanten und Dienste außerhalb des Dienstscopes (Rollen ohne `config:write`) sind nicht sichtbar (404); Dienst und Region müssen zusammenpassen (422). Befehle lesen dieselbe Auflösung in ihrer Transaktion über `ConfigApi.effective.resolveInTransaction` (Regelprüfung, WP-M1-26). Oberfläche: Konfigurationseditor `/admin/konfiguration` (UI-17 Basis).

## Serverseitige Regelprüfung (WP-M1-26)

`platform.policy.PolicyCheck` (Umsetzung `ServerPolicyCheck`) prüft vor jeder Bestätigung „Befehl X im Kontext Y gegen die wirksamen Regeln zum Zeitpunkt T“: der Befehl (`PolicyCommand`) trägt Name, Gebiet (`PolicyScope`) und **Fakten**, die die Fachlogik des Servers aus ihren eigenen Daten bildet (gespeichertes Angebot, Serverzeit, Eingabefelder wie der Wunschzeitpunkt), nie Regel-, Preis- oder Revisionswerte aus der Anfrage. Die Regeln löst die `PolicyRuleSource` aus der Präzedenzauflösung (WP-M1-21) in der Transaktion des Befehls auf (Verdrahtung `app.policy.ConfigPolicyRuleSource`; die `PolicyDefinition`-Beans der bestätigenden Befehle liefern die Fachmodule mit M2). Ergebnis (`PolicyResult`) und Auditereignis `policy.command.checked` nennen die serverseitig aufgelösten Regelrevisionen (für den Snapshot des bestätigten Objekts, z. B. `fare_quote.config_revision_id`). Regelverletzung: 422 mit `/facts/<name>`; Regelwechsel zwischen Angebot und Bestätigung bzw. veraltete Revisionsreferenz: 409 `REQUOTE_REQUIRED`. Bestätigende Befehle tragen `@ConfirmingCommand` und müssen die Prüfung aufrufen (Architekturregel `PolicyCheckRules`, roter Selbsttest). Request-Schemas: `api/request-schema-policy.json` verbietet vom Client setzbare Regel-, Preis-, Revisions- und Statusfelder (begründete Ausnahmen dort) und verlangt `additionalProperties: false` (`api/tools/lint.py`, `RequestSchemaLintTest`); unbekannte Felder ergeben 422.

## Aufbewahrungs-, Archivierungs- und Löschregeln (WP-M1-33)

`/v1/retention-rules` (Entwurf anlegen, ändern, `…/approvals` freigeben mit Vier-Augen), `/v1/erasure-jobs` (Lauf für eine **freigegebene** Regel starten, Läufe lesen) und `/v1/erasure-journal` (Löschprotokoll ohne Personendaten); Scopes `retention:read|write` (nur `tenant_admin`). Der Lauf ist ein fortsetzbarer Job über die `operation` `RETENTION_RUN` (`platform.retention.ErasureEngine`, `ErasureOperationHandler`): je Objekt eine Transaktion mit Advisory-Lock, Journalprüfung, Aktion über das `RetentionTarget` des besitzenden Moduls, Löschung im Objektspeicher (`TenantObjectStore`, falls als Bean vorhanden) und Fortschreibung von Zählern und Fortsetzungsmarke; ein Abbruch lässt den Job `RESUMABLE`, ein hart beendeter Worker `RUNNING` mit abgelaufener Lease. Zustände: [erasure_job.md](../docs/umsetzungsplan/vertraege/zustaende/erasure_job.md).

Fahrgastkonto (`LK_PII`, Modul `identity`, `PassengerRetention.target()`): Fälligkeit `retention_until` plus Nachlauffrist der Regel; `ANONYMIZE`/`DELETE` setzt `passenger_private.erased_at` (der Trigger `passenger_private_erasure` leert atomar alle Personenspalten einschließlich der Ciphertexts und lässt nie wieder Personenangaben zu, auch nicht bei gleichzeitiger Profiländerung), löscht Kanäle, Verifikationsaufgaben und Präferenzen; der Einwilligungsnachweis bleibt ohne Personenbezug. `ARCHIVE` setzt `archived_at`/`archive_until`: die Zeile und die Bestände am Konto sind für operative Abfragen unsichtbar (restriktive RLS-Policy), die Rolle mit `retention:read` ruft sie bis zum Fristende über `PassengerArchive.read` ab (auditiert); danach folgt die Anonymisierung. Nur `platform.retention.ArchiveAccess` setzt `app.archive_access` (Quelltext-Wächter `RetentionGuardsTest`).

| Einstellung | Standard |
|---|---|
| Fristen und Löschmatrix (`db/loeschmatrix.csv`) | **nicht festgelegt** (D-11, OP-25): die Fristen liefert der Mandant je Regel; Testfristen gelten nur für Tests; die Produktivschaltung personenbezogener Daten bleibt bis zum Beschluss blockiert |
| Lease eines Laufs / Objekte je Stapel | 5 min / 50 (`ErasureEngine`) |
| Ausführung | Operation-Runner (`taktovia.operations.worker-enabled=true` mit `TenantSource` des Betriebs) |

Noch nicht angeschlossen (Zielmodule folgen mit ihren Datenklassen): Löschziele für Standort-/Fahrerdaten (WP-M3-34), Benachrichtigungen (communication-support, WP-M2-33; Präferenz-/Kontaktbestände des Kontos sind erfasst, gesendete Nachrichten noch nicht), Finanzarchiv (M4), Suchindex und Partnerprojektion (M6/M8). Das Lösch-/Sperrjournal wird beim Restore wieder eingespielt (`infra/backup`, WP-M1-39).

## Fahrzeugstamm und Kapazitätskonfiguration (WP-M3-03, WP-M3-04)

`/v1/operator/vehicle-master` (Liste, Anlegen, Lesen, Ändern/Deaktivieren) und `/v1/operator/vehicle-master/{vehicle_id}/capacity` (Lesen, Ändern in Teilen) im Modul `fleet-safety` (Ports `Vehicles`, `VehicleCapacities`, Rechte `fleet:read`/`fleet:write`). Kennung, Betreiber (Organisation des Mandanten), Typ, Kapazität (Sitz, Rollstuhlplatz), Ausstattung und Aktivstatus sind Pflicht; ungültige Kapazität ergibt 422 mit Feldverweis, in der Datenbank zusätzlich per CHECK. ETag und `If-Match` sind die Stammdaten-Revision (`vehicle.master_revision`, getrennt von `safety_revision`), die Kennung ist je Mandant eindeutig (409). Ein neues Fahrzeug ist bis zur Einsatzfreigabe gesperrt (`active_block = true`, OP-01); ein deaktiviertes Fahrzeug liefert der Leseport `ResourceAvailability` als `VEHICLE_INACTIVE` (nicht neu disponiert, in Historie lesbar). Die Kapazitätskonfiguration (`vehicle.capacity_layout`, Schema `capacity_config.v1`) trennt Stellplätze (mit n = ersetzte Sitzplätze je Rollstuhlplatz), Ausstattung, Kombinationen (Umrüstzustände mit aktiver Kombination) und Restriktionen; `vehicle.capacity_config` bleibt der daraus abgeleitete wirksame Ressourcenvektor (`resource-vector.v1`), den `booking-dispatch` liest (`VehicleCapacities.resourceVectors` liefert je Konfiguration einen Vektor). Der Hold sperrt die Fahrzeugzeile mit `FOR SHARE`, die Konfigurationsänderung mit `FOR NO KEY UPDATE`: der Hold sieht einen konsistenten Stand, bestehende Holds ändern sich nie. Audit `fleet.vehicle.created|changed|deactivated|capacity_changed`, Ereignisse `vehicle.created|changed|deactivated`.

## Fahrerstamm und Qualifikationsrevisionen (WP-M3-05)

`/v1/drivers` (Liste, Anlegen, Lesen, Ändern) und `/v1/drivers/{driver_id}/qualifications` (Revisionen lesen, neue Revision anlegen) im Modul `staff-time` (Port `Drivers`, Rechte `drivers:read`/`drivers:write`, Datenklasse HR). Ein Fahrer gehört genau einer aktiven Mitgliedschaft der Rolle `driver` des Mandanten (endgültig, eindeutig). Qualifikationen sind append-only Revisionen je (Fahrer, Qualifikation) mit Entscheidung `APPROVED`, `PENDING` oder `REVOKED`; wirksam ist die höchste nicht wartende Revision, sie gilt nur freigegeben und im Intervall `[valid_from, valid_to)` (`Drivers.validQualifications(jdbc, driverId, t)`). `driver.qualification_seq` ist die fortlaufende Qualifikationsnummer (Bezug von `resource_release.qualification_revision`), vergeben unter der Fahrerzeilensperre. Name, Kontakt und Nachweisverweis liefert der Server nur der Rolle `staff_planning`; Dispositionssichten sehen `null`. Die Projektion der Fahrerfreigabe (WP-M1-35) zeigt nur freigegebene, nicht entzogene Qualifikationen.

### Fahrzeugpool: Dienstzuordnung (WP-M3-06, LH-6.8-FLT-002)

`/v1/operator/vehicles/{vehicle_id}/service-assignments` (Liste mit `as_of`, Anlegen, `…/{assignment_id}/end`; Modul `fleet-safety`, Port `FleetsafetyApi.vehiclePool`, Rechte `fleet:read`/`fleet:write`). Die Zuordnung Fahrzeug zu Dienst mit Intervall `[valid_from, valid_to)` liegt in `vehicle_pool_assignment` (die Rollentabelle `vehicle_assignment` aus WP-M1-34 bleibt getrennt); ein Fahrzeug kann gleichzeitig mehreren Diensten zugeordnet sein, im selben Dienst überschneiden sich Zuordnungen nicht (409, Exclusion Constraint je Dienst und Fahrzeug). **Geografisch** (Planungsannahme PA-02, OP-25 offen): optionaler Gebietsbezug `service_area_id`; wirksam ist die zum Prüfzeitpunkt gültige Revision der Gebietskette, alle übergebenen Orte der Fahrt müssen darin liegen (`ST_Covers`); ohne Ortsangabe ist eine gebietsbezogene Zuordnung nicht zulässig (fail closed). Welche Orte maßgeblich sind (Abholung, Ziel oder beides) legt der Aufrufer über `locations` fest (`PlaceHold`, `CandidateRequest`, `ResourceAvailability`); die „Eignung“ eines Dienstes für das Fahrzeug (OP-14) wird nicht geprüft. Zeitliche und geografische Prüfung wirken unabhängig; geprüft wird zum Fensterbeginn. Der Leseport `ResourceAvailability` (Hold, Kandidatenfilter) liest die Zuordnung (`NOT_IN_POOL`, `OUTSIDE_SERVICE_AREA`); der Zusage-Commit (`confirm`) sperrt die Fahrzeugzeile gemeinsam (nach dem Lauf) und prüft die Zuordnung zum Abholbeginn erneut. Anlegen und Beenden sperren die Fahrzeugzeile exklusiv (`FOR NO KEY UPDATE`) und serialisieren damit mit dem Hold; ein Ende liegt nie vor dem Beginn und nie in der Vergangenheit. Beim Beenden bleiben zugesagte Fahrten unverändert; sie stehen als `committed_rides_after_end` im Ergebnis und als Hinweis im Ereignis `vehicle.pool_assignment_ended` (Audit `fleet.pool_assignment.created|ended`, Ereignis `vehicle.pool_assignment_created`). Zugesagt = Buchung mit verbrauchtem Hold, Abholung (spätestens) ab dem Ende. Nicht umgesetzt: Widerruf einer noch nicht begonnenen Zuordnung (nur laufende Intervalle werden beendet), Fremdfahrzeuge über `cooperation_grant` (M6), automatische Poolbildung.
