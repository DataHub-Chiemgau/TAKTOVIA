# Eingabehärtung und Sicherheits-Scans (WP-M1-38, LH-6.18-SEC-009)

Detailvertrag: [LH-6.18-SEC-009](../umsetzungsplan/vertraege/LH-6.18-SEC-009.md). Dieses Dokument legt fest, was der Vertrag „im Bau festzulegen“ nennt: Pflicht-Header, Grenzwerte, Schwellen „kritisch“ je Werkzeug und den Prüfkatalog. Es trennt ausdrücklich **ausgeführt** von **nicht ausgeführt**.

## Abwehrbausteine (Backend, `backend/app`, `backend/platform`)

| Angriff | Baustein | Ort |
|---|---|---|
| SQL-Injektion | ausschließlich Platzhalter; Query-Lint bricht den Build bei String-Verkettung | `tools/lint_sql.py`; Pfad-/Query-Parameter zusätzlich per Schema (UUID, Ganzzahl) |
| XSS | Antworten sind JSON (`application/json`/`problem+json`) mit `nosniff` und `Content-Security-Policy: default-src 'none'`; Freitext wird nie als HTML ausgeliefert; Angriffsdaten werden nicht in Fehler gespiegelt | `SecurityHeadersFilter`, `SchemaViolations` |
| Mass Assignment | `additionalProperties: false` auf allen Request-Schemas, unbekannte Query-Parameter und doppelte Parameter/Schlüssel werden abgewiesen | `RequestValidationFilter` |
| Übergroße/tiefe Bodies | Größen- und Tiefenlimit, Stringlänge, `Content-Type` strikt `application/json` (UTF-8) | `RequestValidationFilter`, `ApiConfiguration` |
| Steuerzeichen (NUL …) | in Werten, Schlüsseln, Pfad, Query und Headern abgewiesen | `RequestValidationFilter`, `PathHardeningFilter` |
| Pfadmanipulation | `;`, `%2e`, `%2f`, `%5c`, `%00`, `%0d`, `%0a`, `..`, `//`, `\` werden vor Spring abgewiesen | `PathHardeningFilter` |
| Header-Injektion | Container weist ab; kein Anfragewert gelangt in einen Antwortheader | `ProblemErrorReportValve` |
| CORS | exakte Whitelist (`taktovia.security.cors.allowed-origins`), nie `*`, `null` oder Credentials; Preflight nur für freigegebene Herkunft, Methoden und Header | `OriginPolicyFilter` |
| CSRF | Bearer im `Authorization`-Header, keine Cookie-Anmeldung; zusätzlich Origin-Whitelist, Fetch-Metadata (`Sec-Fetch-Site`) und JSON-Zwang für zustandsändernde Anfragen | `OriginPolicyFilter`, `RequestValidationFilter` |
| SSRF | https, öffentliche Adressen, freigegebene Ports; Prüfung bei Eingabe (`x-url`) und vor jedem Aufruf mit Pinning der geprüften IPs; Weiterleitungen neu geprüft | `platform.net.SsrfGuard` (gemeinsam mit WP-M1-16) |
| Fehlerinformationen | jede Fehlerantwort (auch des Containers) ist `application/problem+json` ohne Stack, SQL, Pfade, Produktname | `GlobalProblemAdvice`, `ProblemErrorReportValve`, `ProblemErrorController` |
| Rate-Limit | Quote je Mandant und Akteur, 429 mit `Retry-After` | `RateLimitFilter` (WP-M1-02) |

### Pflicht-Header jeder API-Antwort

`X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Strict-Transport-Security: max-age=63072000; includeSubDomains`, `Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()`, `Cross-Origin-Resource-Policy: same-site`. Maßgeblich ist `SecurityHeaders.REQUIRED`; `InputHardeningTest` und der Angriffskatalog prüfen sie an echten Antworten.

### Web-Rahmen

`web/csp.ts` fügt beim Build die Content-Security-Policy als `<meta>` ein (`script-src 'self'`, kein Inline-Skript, `object-src 'none'`; `connect-src` um `VITE_API_ORIGIN`). Am Hosting zusätzlich als HTTP-Header setzen, einschließlich `frame-ancestors 'none'`. Tokens liegen nur im Arbeitsspeicher (WP-M1-43/-44); ein Test verbietet `localStorage`, `sessionStorage`, `document.cookie`, `innerHTML` und `eval` im Quelltext.

## Grenzwerte (Planungsdefaults, OP-20, vor Produktivschaltung festzulegen)

| Grenze | Standard | Einstellung |
|---|---|---|
| Body-Größe | 1 MiB | `taktovia.api.max-body-bytes` |
| JSON-Verschachtelung | 32 | `taktovia.api.max-json-depth` |
| Stringlänge | 100000 Zeichen | `taktovia.api.max-string-length` |
| Query-Länge | 4096 Zeichen | fest |
| Quote | 600 Anfragen/60 s je Mandant und Akteur | `taktovia.api.rate-limit.*` |
| SSRF-Ports | 443 | `taktovia.security.ssrf.allowed-ports` |

## Schwellen „kritisch“ je Werkzeug

Mangelklassen nach Kap. 08 §8.4 (siehe `tests/acceptance/docs/mangelklassen.md`): **A blockiert die Produktion**, B braucht Workaround und Behebungstermin, C einen Maßnahmenplan.

| Werkzeug | Klasse A (kritisch) | Klasse B | Klasse C |
|---|---|---|---|
| CodeQL (SAST), OSV/Trivy (Abhängigkeiten, Images) | `security-severity`/CVSS >= 9.0 | >= 7.0 | übrige |
| OWASP ZAP (DAST) | Risiko High | Medium | Low, Informational |
| eigene Werkzeuge (`lint_sql`, Angriffskatalog) | Schwere KRITISCH (SQL-Injektion, SSRF-Lücke, Härtung umgangen) | HOCH | übrige |

Das Gate `tools/findings_gate.py` verlangt für **jeden** Befund eine Entscheidung in `docs/security/befunde.csv` (Werkzeug; Regel; Ort oder `*`; `FALSCHMELDUNG`/`AKZEPTIERT`/`WIRD_BEHOBEN`; Begründung; Verantwortlicher; Behebungstermin `bis`). Klasse A lässt nur `FALSCHMELDUNG` zu, Klasse B braucht einen nicht abgelaufenen Termin. Offene kritische Befunde gibt es danach nicht.

## Prüfkatalog (Arbeitsgrundlage, vom Pflichtenheft nicht vorgegeben)

OWASP API Security Top 10 (2023) → Abdeckung: API1/API5 Objekt- und Funktionsautorisierung: Mandantenisolation RLS (WP-M1-01) und Rollen (WP-M1-07/-08), hier nur 404/403-Form; API2 Authentisierung: WP-M1-04; API3 Eigenschaftsebene/Mass Assignment: `additionalProperties: false` und Angriffskatalog „mass-assignment“; API4 Ressourcenverbrauch: Größen-/Tiefenlimits, Quote, Listenlimit 100; API6 Geschäftsabläufe: Idempotenz (WP-M1-13); API7 SSRF: `SsrfGuard`; API8 Fehlkonfiguration: Header, CORS, Fehlerform, keine Server-Banner; API9 Bestandsverwaltung: Vertragsabgleich Route ↔ `operationId` (WP-M1-03); API10 unsichere Nutzung von APIs: Partnersignatur (M6).

Der aus den OpenAPI-Dateien generierte Angriffskatalog (`tests/acceptance/security/attack_catalog.py`) erzeugt je Endpunkt: SQL-Injektion (Pfad, Query), Injektion/XSS und Steuerzeichen in Feldern, Mass Assignment, unbekannte Query-Parameter, übergroße und tief verschachtelte Bodies, doppelte Schlüssel, Text nach JSON, falschen Content-Type, Pfadmanipulation, Header-Injektion, fremde CORS-Origin, Cross-Site-Schreibzugriff, SSRF-Ziele.

## Nachweisstand

| Prüfung | Stand |
|---|---|
| Angriffskatalog gegen die gebaute Anwendung (`app.jar`, echte PostgreSQL) | **ausgeführt**, 1225 Angriffe gegen alle 51 dokumentierten Endpunkte, 0 Abweichungen (`tests/acceptance/security/test_angriffskatalog.py`) |
| Rate-Limit unter parallelem Missbrauch (32 Clients, zwei Absenderadressen) | **ausgeführt** (`test_ratelimit.py`) |
| Eingabehärtung im Backend (Kotlin, echte Anwendung) | **ausgeführt** (`InputHardeningTest`, `CorsCsrfTest`, `SsrfApiTest`, `SsrfGuardTest`) |
| Query-Lint | **ausgeführt**, ohne Befund (`tools/lint_sql.py`) |
| Abhängigkeitsscan Web (`npm audit`) | **ausgeführt**: 0 Schwachstellen (Stand 11.11.2026) |
| CSP/Token-Regeln im Web | **ausgeführt** (Vitest, inklusive echtem Vite-Build) |
| Befund-Gate mit absichtlich eingebautem kritischem Befund | **ausgeführt** (`test_ci_gate.py`) |
| SAST (CodeQL), Abhängigkeitsscan Gradle (OSV), Image-Scan, DAST (ZAP) gegen Staging | **nicht ausgeführt**: in dieser Umgebung weder Docker noch die Dienste (GitHub Actions, OSV-API, Staging) erreichbar; als Workflow `.github/workflows/security.yml` eingerichtet, Lauf steht mit der ersten CI-Ausführung bzw. WP-M0-35 (Staging) aus |
| Penetrationstest des Lastenhefts | Umfang von WP-M5-17; M1 liefert Scan-Berichte (`findings_gate.py --report`), den Angriffskatalog und Testzugang (Staging, WP-M0-35) |
