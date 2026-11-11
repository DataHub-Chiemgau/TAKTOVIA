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

## Idempotenzregister (WP-M1-13)

Jede schreibende Operation, die `Idempotency-Key` verlangt, läuft durch den `IdempotencyFilter` (`app.web`, nach Authentisierung, Mandantenkontext, Rechtematrix und Eingabeprüfung, vor dem Controller) über `platform.idempotency` (`IdempotencyService`, `IdempotencyRegistry`, `RequestFingerprint`): je `(Akteur, Mandant, operationId, Schlüssel)` genau eine Ausführung, Wiederholung liefert das gespeicherte 2xx-Ergebnis (`Idempotent-Replayed: true`), anderer Fingerprint `409 IDEMPOTENCY_CONFLICT`, laufende Erstverarbeitung `409 OPERATION_IN_PROGRESS`. Fehlerantworten werden nicht gespeichert. Vertrag, Kanonisierungsregel und Grenzen: [idempotenz-register.md](../docs/umsetzungsplan/vertraege/idempotenz-register.md).

| Einstellung (`taktovia.idempotency.…`, Planungsdefaults) | Standard |
|---|---|
| `response-ttl-hours` (Antwortcache) | 72 |
| `lease-seconds` (Erstverarbeitung gilt danach als abgebrochen) | 120 |
| `in-progress-wait-millis` (Wartezeit eines parallelen Retransmits) | 10000 |
| `cleanup-minutes` (nur mit einer `TenantSource`-Bean) | 10 |

Für Controller: nichts tun (der Filter wirkt über den Vertrag); die entstandene `operation` optional über das Anfrageattribut `IdempotencyFilter.OPERATION_ID_ATTRIBUTE` melden. Die Fachwirkung muss zusätzlich durch einen fachlichen Unique-Schlüssel einmalig sein (Antwortcache läuft ab).
