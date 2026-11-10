# M1 – Plattformkern

- **Meilenstein:** M1 – Plattformkern: Mandanten, Identität, Konfiguration, Ereignisse
- **Phase:** P1 (alle Anforderungen MUSS, Originalphase und -priorität unverändert)
- **Anforderungen:** 46 (SYS 7, IAM 8, INT 4, SEC 13, MUL 7, CFG 7)
- **Arbeitspakete:** 46 (39 mit Anforderungen, 7 Enabler)

**Ziel:** Nach M1 kann sich ein Mensch per OIDC anmelden, serverseitig geprüft in genau einem Mandanten arbeiten und nur das sehen und ändern, was Rolle, Dienst und Objekt erlauben. Jede Tabelle ist per RLS und zusammengesetzten Schlüsseln isoliert, jede schreibende API ist idempotent und liefert ein einheitliches Fehlermodell, Ereignisse laufen über Outbox/Inbox mit fachlich genau einmaliger Wirkung. Das Konfigurationsregister liefert versionierte, geprüfte und zeitlich wirksame Werte mit Vier-Augen-Freigabe und Rollback als neue Revision. M2, M3 und M4 bauen ausschließlich auf diesen Bausteinen auf.

**Hinweis zur Abnahme:** Mehrere M1-Anforderungen haben eine Originalabnahme, die fachliche Objekte späterer Meilensteine voraussetzt (Buchung, Fahrzeug, Fahrer, Zahlung). Diese WPs liefern in M1 den Plattformbaustein mit einem Nachweis an einem Testaggregat; der Nachweis am echten Fachobjekt ist als EXTERN-Abhängigkeit ausgewiesen und unter „Offene Punkte" gesammelt (OP-01).

**Hinweis zu Bezeichnern:** Tabellen, Endpunkte, UI-IDs und Parameter tragen die Namen der Artefakte (`kernschema.sql`, Kap. 04 §4.4, `openapi.yaml`, Kap. 05 §5.7, Kap. 06 §6.9, `kataloge.csv`). Nicht aus den Artefakten stammen und daher Vorschläge dieses Plans sind: Ereignistypnamen (z. B. `organization.changed`, `config.revision_published`), Port- und Klassennamen (z. B. `TenantContext`, `PolicyCheck`, `ImpactProvider`), alle als „neue Tabelle" oder „Detailvertrag" gekennzeichneten Tabellen und Endpunkte sowie der Zuschnitt „UI-xx Basis". Sie werden im jeweiligen Detailvertrag verbindlich festgelegt (OP-33). Kennzahlen aus `kataloge.csv` liefert M1 nicht; die 48 KPIs beginnen mit M4.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M1-01 | Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness | 2 | M | WP-M0-15, WP-M0-16, WP-M0-17 |
| WP-M1-02 | API-Grundgerüst: Fehlermodell, Validierung, ETag, Listen, Tracing | 1 | M | WP-M1-01, WP-M0-02, WP-M0-08, WP-M0-12 |
| WP-M1-03 | OpenAPI je Modul und Contract-Test-Pipeline | 1 | S | WP-M1-02, WP-M0-04, WP-M0-13, WP-M2-01, WP-M2-21, WP-M2-23, WP-M3-01, WP-M3-03, WP-M3-53 |
| WP-M1-04 | OIDC-Ressourcenserver, IdP-Realm und MFA | 2 | M | WP-M1-02, WP-M1-46, WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35 |
| WP-M1-05 | Mandant, Rechtsträger, Organisation und vertragliche Rollen | 1 | M | WP-M1-01, WP-M1-02, WP-M1-06, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46 |
| WP-M1-06 | Mitgliedschaft, RequestContext, serverseitiger Tenantwechsel, `/v1/me` | 2 | M | WP-M1-01, WP-M1-04, WP-M1-14, WP-M1-43, WP-M1-46 |
| WP-M1-07 | Rollenmodell und Rechtematrix | 1 | M | WP-M1-06, WP-M1-43 |
| WP-M1-08 | Dienst- und Objektberechtigung | 1 | M | WP-M1-07, WP-M1-09, WP-M2-23 |
| WP-M1-09 | Organisationsstruktur: Regionen, Betriebshöfe, Dienste, Kostenstellen | 1 | M | WP-M1-05, WP-M1-06, WP-M1-07, WP-M1-20, WP-M1-43, WP-M2-04, WP-M2-05 |
| WP-M1-10 | Sitzungsverwaltung und Sitzungssperre | 1 | M | WP-M1-06, WP-M1-43, WP-M1-46, WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35, WP-M2-35, WP-M2-36 |
| WP-M1-11 | Isolation außerhalb der Datenbank und mandantengetrennte Bestände | 2 | M | WP-M1-06, WP-M1-08, WP-M1-12, WP-M1-14, WP-M1-22, WP-M0-08, WP-M2-07, WP-M2-38, WP-M4-21, WP-M4-22, WP-M4-33, WP-M4-37, WP-M4-64 |
| WP-M1-12 | Audit append-only und Sicherheitsereignisse | 1 | M | WP-M1-06, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46, WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35, WP-M3-40 |
| WP-M1-13 | Idempotenzregister und Kollisionskontrolle | 1 | M | WP-M1-02, WP-M1-06, WP-M1-14, WP-M2-24, WP-M2-30 |
| WP-M1-14 | Outbox-Relay und Inbox-Deduplikation | – (Enabler) | M | WP-M1-01, WP-M1-46, WP-M0-08, WP-M0-10, WP-M5-04 |
| WP-M1-15 | `operation`-Ressource mit Lease/Fencing | – (Enabler) | M | WP-M1-07, WP-M1-13, WP-M1-14 |
| WP-M1-16 | Webhook-Grundlage (signierter Eingang, Abonnement, Zustellung) | – (Enabler) | M | WP-M1-02, WP-M1-07, WP-M1-13, WP-M1-14, WP-M1-36, WP-M1-46, WP-M0-10, WP-M5-04 |
| WP-M1-17 | Zustandsautomaten-Rahmen mit serverseitiger Übergangsprüfung | 1 | M | WP-M1-02, WP-M1-14, WP-M2-01, WP-M2-16, WP-M2-23, WP-M2-30, WP-M3-01, WP-M3-31, WP-M3-53 |
| WP-M1-18 | Zeit-, Zeitzonen- und DST-Baustein | 1 | S | WP-M1-02, WP-M1-43, WP-M2-11, WP-M2-23 |
| WP-M1-19 | Parameterkatalog, `policy_schema` und Systemgrenzen | 2 | M | WP-M1-02, WP-M1-06 |
| WP-M1-20 | Konfigurationsrevisionen: Entwurf, Historie, Rekonstruktion | 2 | M | WP-M1-07, WP-M1-14, WP-M1-19, WP-M1-43, WP-M1-46, WP-M2-04, WP-M2-07, WP-M2-21, WP-M2-23 |
| WP-M1-21 | Präzedenzkette und wirksame Konfiguration | 1 | M | WP-M1-07, WP-M1-09, WP-M1-20, WP-M1-43, WP-M3-03 |
| WP-M1-22 | Validierung, Vier-Augen-Freigabe, Publikation und Rollback | 1 | L | WP-M1-07, WP-M1-13, WP-M1-20, WP-M1-21, WP-M1-23, WP-M1-43, WP-M1-46, WP-M2-04, WP-M2-05, WP-M2-09, WP-M2-21, WP-M2-23 |
| WP-M1-23 | Wirkungsanalyse vor Publikation | 1 | M | WP-M1-07, WP-M1-15, WP-M1-20, WP-M1-43, WP-M1-46, WP-M2-04, WP-M2-23, WP-M4-17 |
| WP-M1-24 | Unternehmensprofil des Mandanten | 1 | S | WP-M1-05, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46 |
| WP-M1-25 | Dienstkonfiguration und Angebotsmodelle | 1 | M | WP-M1-09, WP-M1-22, WP-M1-43, WP-M2-04, WP-M2-05, WP-M2-07, WP-M2-21, WP-M2-23 |
| WP-M1-26 | Serverseitige Regelprüfung (Policy-Port) | 1 | S | WP-M1-02, WP-M1-21, WP-M1-46, WP-M2-09, WP-M2-23 |
| WP-M1-27 | Revisionsfeste Zusage und kontrollierte Änderungsverfahren | 1 | M | WP-M1-02, WP-M1-14, WP-M1-17, WP-M1-46, WP-M2-23, WP-M2-32 |
| WP-M1-28 | Fahrgastregistrierung mit Kontaktverifikation | 1 | M | WP-M1-04, WP-M1-06, WP-M1-10, WP-M1-31, WP-M1-43, WP-M1-46, WP-M2-32, WP-M2-35, WP-M2-36 |
| WP-M1-29 | Fahrgastprofil und Präferenzen | 1 | M | WP-M1-07, WP-M1-28, WP-M1-31, WP-M1-43, WP-M2-05, WP-M2-35, WP-M2-36 |
| WP-M1-30 | Datenschutzeinstellungen: Betriebsmitteilung getrennt von Marketing | 1 | S | WP-M1-29, WP-M1-43, WP-M2-32 |
| WP-M1-31 | Feldschutz für Mobilitäts- und Unterstützungsmerkmale | 1 | M | WP-M1-01, WP-M1-07, WP-M1-36, WP-M1-43, WP-M1-46, WP-M2-23, WP-M4-06, WP-M4-08 |
| WP-M1-32 | Dateninventar mit Zweck und Rechtsgrundlage | 1 | S | WP-M1-01, WP-M1-43 |
| WP-M1-33 | Aufbewahrungs-, Archivierungs- und Löschregeln | 1 | M | WP-M1-07, WP-M1-11, WP-M1-15, WP-M1-31, WP-M1-32, WP-M1-43, WP-M1-46 |
| WP-M1-34 | Fahrzeugrollen mit Gültigkeitsintervallen | 1 | M | WP-M1-05, WP-M1-07, WP-M1-14, WP-M1-46, WP-M2-16, WP-M3-03, WP-M4-33 |
| WP-M1-35 | Zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion | 1 | M | WP-M1-05, WP-M1-07, WP-M1-08, WP-M1-14, WP-M1-43, WP-M1-46, WP-M3-05, WP-M6b-04, WP-M6b-05 |
| WP-M1-36 | Transportverschlüsselung und Secret-Verwaltung | 2 | S | WP-M0-08, WP-M0-11, WP-M0-12, WP-M0-14, WP-M0-35 |
| WP-M1-37 | Zahlungsdatentrennung (keine Kartendaten im System) | 1 | S | WP-M1-02, WP-M1-31, WP-M1-32, WP-M1-36, WP-M2-29 |
| WP-M1-38 | Eingabehärtung und Sicherheits-Scans in CI | 1 | M | WP-M1-02, WP-M1-03, WP-M0-11, WP-M0-12, WP-M0-35, WP-M5-17 |
| WP-M1-39 | Verschlüsselte Backups und Restore-Test | 1 | M | WP-M1-01, WP-M1-33, WP-M1-36, WP-M0-11, WP-M0-35, WP-M5-14, WP-M5-15 |
| WP-M1-40 | Trennung von Test- und Produktionsdaten und -zugängen | 1 | S | WP-M1-04, WP-M1-36, WP-M0-11 |
| WP-M1-41 | Sicherheitsvorfallprozess und Übung | 1 | S | WP-M1-05, WP-M1-10, WP-M1-12, WP-M1-14, WP-M1-36, WP-M1-43, WP-M0-10, WP-M5-04 |
| WP-M1-42 | Datenschutz-Folgenabschätzung | 1 | S | WP-M1-31, WP-M1-32, WP-M1-33, WP-M3-34, WP-M4-61, WP-M4-62 |
| WP-M1-43 | Web-Designsystem und Anwendungsrahmen | – (Enabler) | M | WP-M1-02, WP-M1-03, WP-M0-05, WP-M0-12, WP-M5-30 |
| WP-M1-44 | Web-Anmeldung und Mandantenwechsel | – (Enabler) | M | WP-M1-04, WP-M1-06, WP-M1-10, WP-M1-43, WP-M2-35, WP-M2-36 |
| WP-M1-45 | M1-Gate-Abnahmesuite | – (Enabler) | M | alle WPs dieser Datei, WP-M0-22, WP-M0-31, WP-M0-32, WP-M2-07, WP-M2-23, WP-M2-38, WP-M2-48, WP-M3-44, WP-M3-56, WP-M4-06, WP-M4-08, WP-M4-22, WP-M4-64 |
| WP-M1-46 | Audit-Port und append-only Auditspeicher | – (Enabler) | S | WP-M1-01, WP-M0-16 |

## Arbeitspakete

### WP-M1-01 – Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness
- **Ziel:** Jede HTTP- und Workertransaktion läuft unter der nicht privilegierten Runtime-Rolle mit genau einem transaktionslokalen Mandantenkontext; ohne Kontext liefert keine Tabelle eine Zeile. Ein wiederverwendbarer Zwei-Mandanten-Testharness beweist das automatisiert für jede Tabelle.
- **Anforderungen:** `LH-I.4-MUL-001`, `LH-6.18-SEC-001`
- **Liefergegenstände:**
  - Backend: Modul `backend/platform` – `TenantContext`, Transaktionsinterceptor mit parametrisiertem `set_config('app.tenant_id', tenant, true)` innerhalb `BEGIN`; Verbot sessionweiter GUCs; Worker-Variante mit explizitem Tenantkontext, Batchjobs iterieren begrenzt je Mandant (Kap. 02 §4.2, Kap. 04 §4.6). Architekturtest: ein Modul schreibt nur eigene Tabellen.
  - Datenbank: Flyway-Migration auf Basis `kernschema.sql`; Policy `tenant_isolation` (USING und WITH CHECK) und `FORCE ROW LEVEL SECURITY` für alle Tabellen mit `tenant_id`, Policy `own_tenant` auf `tenant`; Funktion `current_tenant()`; Trigger, der eine Änderung von `tenant_id` verweigert (Kap. 02 §4.2); Migrationsprüfung „jede neue Tabelle hat `tenant_id`, PK `(tenant_id,id)`, zusammengesetzte FKs, RLS". Views mit invokergerechten Rechten; Definerfunktionen werden vermieden oder erhalten festen `search_path`, minimale Rechte und eigene Tests; Partitionierung gilt nicht als Sicherheitsgrenze (Kap. 02 §4.2). Invarianten §4.5: „Keine lokale Cross-Tenant-Referenz", „Kein Mandantenkontext → keine Zeile".
  - Adapter/Worker: Testharness in `tests/acceptance` mit Fixture Mandant A/B (je Fahrgast, Fahrzeug, Beleg, identisch benannte Kostenstelle gemäß PH-AT-001) und generischem Tabellen-Scan unter der Runtime-Rolle.
- **Detailverträge vorab:** DDL-Konvention für neue Tabellen (Checkliste aus Kap. 04 §4.1); Rollenmatrix Migrator/Runtime/Modulzugänge mit GRANTs; Festlegung des Plattformmandanten für globale Katalogobjekte (Kap. 02 §1, siehe OP-08).
- **Akzeptanzkriterien:**
  1. Ein automatisierter Mandantentest unter der Runtime-Rolle kann mit Kontext A weder Zeilen aus `booking` noch aus `expense_document`, `journal` oder `settlement_line` des Mandanten B lesen; der Test läuft über alle Tabellen des Schemas `taktovia` und schlägt fehl, wenn eine Tabelle mit `tenant_id` keine erzwungene RLS-Policy hat. (MUL-001)
  2. Jede Entität besitzt `tenant_id` als ersten Bestandteil von Primär-, Unique- und Fremdschlüsseln; `tenant_id IS NULL` existiert nicht. (MUL-001)
  3. INSERT/UPDATE mit fremdem FK oder fremder `tenant_id` wird durch FK bzw. WITH CHECK abgewiesen; ein Rechte-Testlauf über alle Rollen und beide Mandanten meldet null Cross-Tenant-Leaks und ist CI-Pflichtschritt. (SEC-001)
  4. Mit leerem Transaktionskontext und nach Connection-Pool-Wiederverwendung (auch nach Fehler/Rollback) ist kein vorheriger Tenantkontext wirksam. (SEC-001)
  5. Die Runtime-Rolle ist nachweislich NOSUPERUSER, NOBYPASSRLS, kein Tabelleneigentümer und hat weder TRUNCATE noch DDL.
  6. Über keine View und keine Funktion des Schemas liefert ein Lesezugriff mit Kontext A eine Zeile des Mandanten B; ein Batchjob über mehrere Mandanten setzt den Kontext je Mandant neu und hinterlässt nach Abbruch keinen Kontext.
- **Tests:** positiv: A liest und schreibt A. negativ: A liest/ändert B über bekannte UUID; fremder FK; `tenant_id`-Update; leerer Kontext; Zugriff über View und Definerfunktion; DML-Versuch mit TRUNCATE/DDL unter der Runtime-Rolle. konkurrierend: zwei parallele Transaktionen mit Kontext A und B auf demselben Pool, 1.000 Wechsel, keine Kontextvermischung. Ermöglicht PH-AT-001 (Datenbankteil), E2E-091, AT-49.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-16 (Kernschema als lauffähige Flyway-Migration)
  - extern: WP-M0-15, WP-M0-17 (getrennte DB-Rollen Migrator/Runtime und Testcontainers-Umgebung)
- **Entscheidungen:** D-02 – blockiert den Bau nicht (RLS ist Planungsdefault); eine spätere Entscheidung für Datenbank je Mandant wäre Umbau.
- **Größe:** M
- **Nicht im Umfang:** API-Autorisierung (WP-M1-06 bis WP-M1-08); Feld-/Spaltenrechte (WP-M1-31); Cache, Storage, Events (WP-M1-11); Partitionierung.

### WP-M1-02 – API-Grundgerüst: Fehlermodell, Validierung, ETag, Listen, Tracing
- **Ziel:** Alle Endpunkte teilen ein Gerüst nach Kap. 05 §5.1: `application/problem+json` mit stabilem `code`, Feldverletzungen mit JSON-Pointer, ETag/If-Match, Cursor-Listen, Correlation-ID und Rate-Limit.
- **Anforderungen:** `LH-6.17-INT-003`
- **Liefergegenstände:**
  - Backend: `backend/app` – globaler Fehlerabbildner auf Schema `Problem` (`type`, `title`, `status`, `code`, `detail`, `correlation_id`, `violations[pointer,code,message]`); Request-Validierung gegen OpenAPI/JSON Schema; ETag-Filter (`"7"`), `If-Match` → 428 `PRECONDITION_REQUIRED` / 412 `REVISION_MISMATCH`; Listenbaustein `{items,next_cursor,as_of}` mit `limit` 1–100 (Default 50), signiertem opakem `cursor`, Sortierung `(created_at,id)`; W3C `traceparent`, neue Correlation-ID bei ungültigem Header; 429 mit `Retry-After`, Quoten nach Tenant und Akteur (Partnerquoten folgen M6; Redis nur als Rate-Limit-Speicher); keine Personen- oder Ortssuchen als Metriklabels. Geldtypen der Plattform (`Money`, `SignedMoney`): `amount_minor` als Ganzzahl im sicher darstellbaren JSON-Integerbereich, `currency`, `exponent`; serverseitige Prüfung des Exponenten gegen `currency`; kein binary float (Kap. 05 §5.1, Kap. 04 §4.1) – von M2 und M4 als Geldtyp aus M1 vorausgesetzt (OP-37).
  - API: Fehlercode-Katalog als Bestandteil der OpenAPI-Komponenten (`NOT_FOUND`, `REVISION_MISMATCH`, `PRECONDITION_REQUIRED`, `IDEMPOTENCY_CONFLICT`, `OPERATION_IN_PROGRESS` u. a. aus Kap. 05 §5.4); Regel 404 für nicht sichtbare Ressourcen, 403 für fehlenden Funktionsscope.
  - Datenbank: Abbildung von Constraint-Fehlern (z. B. SQLSTATE 23514 aus `reject_mutation()`) auf Problem-Codes; DB-Meldungen gelangen nie roh zur API (Kap. 02 §4.2).
- **Detailverträge vorab:** vollständiger Fehlercode-Katalog mit HTTP-Status; Katalog der Feldverletzungscodes; Cursor-Format und Signaturschlüssel; Rate-Limit-Quoten (im Pflichtenheft nicht beziffert, siehe OP-20).
- **Akzeptanzkriterien:**
  1. Ein Feldfehler in einem schreibenden Request liefert 422 mit `violations[].pointer` und `violations[].code`; ein Client erkennt Feld und Fehlerart ohne Auswertung von `detail`/`message`. (INT-003)
  2. Jede Fehlerantwort aller M1-Endpunkte validiert gegen das Schema `Problem` und enthält weder Stacktrace noch fremde Objektbezeichnungen. (INT-003)
  3. Fehlender `If-Match` ergibt 428, veraltete Revision 412; kein stilles Überschreiben.
  4. Listen liefern stabile Reihenfolge über Seiten; manipulierter Cursor wird mit 400 abgewiesen.
  5. Bei Quotenüberschreitung antwortet die API mit 429 und `Retry-After`; die Quote eines Mandanten oder Akteurs beeinflusst keinen anderen. Ein ungültiger `traceparent` führt zu einer neuen Correlation-ID, die in der Fehlerantwort als `correlation_id` erscheint.
  6. Nicht sichtbare Ressourcen ergeben einheitlich 404 `NOT_FOUND`, fehlender Funktionsscope 403; Datenbank-Constraint-Meldungen erscheinen nie roh in einer Antwort.
  7. `Money` lehnt negative Beträge, Gleitkommawerte, unbekannte Währung und einen vom Währungskatalog abweichenden `exponent` mit Feldverletzung ab; `SignedMoney` lässt negative Werte nur im dafür vorgesehenen Typ zu.
- **Tests:** positiv: gültiger Request; Folgeseite per Cursor. negativ: unbekanntes Feld (`additionalProperties:false`), Typfehler, zu langer String, manipulierter Cursor, fehlender/veralteter ETag, 429, ungültiger `traceparent`, Betrag als Gleitkommazahl, falscher `exponent`, Betrag außerhalb des sicheren Integerbereichs. konkurrierend (Sicherheit/Datentrennung): zwei parallele Updates derselben Revision – genau eines gewinnt, das andere erhält 412. Semantischer Contract-Fall „412-Konflikt ohne stilles Überschreiben" (Kap. 06 §6.9).
- **Abhängigkeiten:** intern: WP-M1-01.
  - extern: WP-M0-02, WP-M0-08, WP-M0-12 (Repository-Gerüst, CI, Redis in lokaler Umgebung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Rechen- und Rundungsregeln für Geld (Largest Remainder, Steuer – M2/M4); Idempotenz (WP-M1-13); Authentisierung (WP-M1-04); modulspezifische Fehlercodes späterer Meilensteine.

### WP-M1-03 – OpenAPI je Modul und Contract-Test-Pipeline
- **Ziel:** Jeder produktive Endpunkt ist in einer versionierten OpenAPI-3.1-Datei beschrieben und wird in CI per Contract-Test gegen die laufende Anwendung geprüft; ein Endpunkt ohne Vertrag bricht den Build.
- **Anforderungen:** `LH-6.17-INT-001`
- **Liefergegenstände:**
  - API: Struktur `api/` mit einer OpenAPI-3.1-Datei je Modul, Ausgangspunkt `docs/pflichtenheft/api/openapi.yaml`; gemeinsame Komponenten (`Problem`, `UUID`, `Money`, `SignedMoney`, `IdempotencyKey`, `IfMatch`, `Traceparent`, `OperationAccepted`); Versionspräfix `/v1`; Regel „Breaking Change → neue Major-Version" als Lint.
  - Backend: Contract-Test-Runner (Request/Response-Validierung aller Routen, Vergleich registrierter Routen gegen `operationId`s); Prüfungen aus Kap. 05 §5.8: parsbar als 3.1, alle `$ref` auflösbar, `operationId` eindeutig, Beispiele schemagültig; `validate_artifacts.py` bleibt Pflichtschritt.
  - Oberfläche: veröffentlichte API-Dokumentation (Entwicklerportal-Seite aus den OpenAPI-Dateien).
- **Detailverträge vorab:** OpenAPI-Ergänzungen für alle M1-Endpunkte: `/v1/me`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/v1/config/revisions/{id}/validate`, `/v1/config/revisions/{id}/impact`, `/v1/config/revisions/{revision_id}/publish`, `/v1/operations/{operation_id}`, `/v1/passengers`, `/v1/webhook-subscriptions`, `/v1/services` (Dienstkopf) sowie die in Kap. 05 §5.7 nicht gelisteten Verwaltungsendpunkte dieser Datei (Organisation/Rechtsträger, Mitgliedschaft und Tenantwechsel, Region/Betriebshof/Kostenstelle, Sitzungen, Auditabfrage, Unternehmensprofil, Aufbewahrungsregeln, Fahrzeugrollen, Fahrerfreigabe); Scope-Katalog (heute nur `config:publish`, `operations:read` u. a. vorhanden).
- **Akzeptanzkriterien:**
  1. Für jeden in der Anwendung registrierten Endpunkt existiert genau eine `operationId` in `api/`; der Vergleich ist CI-Pflichtschritt und schlägt bei Abweichung fehl. (INT-001)
  2. Contract-Tests decken jeden produktiven M1-Endpunkt mit mindestens einem positiven und einem Fehlerfall ab und laufen grün. (INT-001)
  3. Die Dokumentation nennt Version, Sicherheits-Scopes und Fehlercodes je Endpunkt. (INT-001)
- **Tests:** positiv: Contract-Lauf aller M1-Endpunkte. negativ: absichtlich undokumentierte Testroute lässt den Build scheitern; Antwort mit zusätzlichem Feld wird erkannt. konkurrierend: nicht einschlägig (reine Vertrags- und Buildprüfung).
- **Abhängigkeiten:** intern: WP-M1-02.
  - extern: WP-M0-04, WP-M0-13 (CI mit Vertragsprüfung (`validate_artifacts.py`))
  - extern: WP-M2-01, WP-M2-21, WP-M2-23 (APIs für Buchungen und Verfügbarkeit (`/v1/ride-offers`, `/v1/bookings`) als Gegenstand der Originalabnahme)
  - extern: WP-M3-01, WP-M3-03, WP-M3-53 (APIs für Fahrzeug- und Betriebsobjekte (`/v1/operator/vehicles`, `/v1/operator/incidents`))
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** APIs für Buchungen, Verfügbarkeit, Fahrzeug- und Betriebsobjekte selbst (M2, M3) – diese nutzen die hier gelieferte Pipeline; die zwölf semantischen Contract-Fälle aus Kap. 05 §5.8 werden mit dem Meilenstein Pflicht, der die Funktion liefert.

### WP-M1-04 – OIDC-Ressourcenserver, IdP-Realm und MFA
- **Ziel:** Menschen melden sich per OIDC Authorization Code + PKCE am IdP an; das Backend prüft Signatur, Issuer, Audience, Ablauf und Authentisierungsstärke. Privilegierte Rollen kommen ohne zweiten Faktor nicht an geschützte APIs. Das System speichert selbst keine Passwörter.
- **Anforderungen:** `LH-6.2-IAM-002`, `LH-6.2-IAM-010`
- **Liefergegenstände:**
  - Backend: `backend/app` Security-Konfiguration (Resource Server, JWKS-Rotation, Prüfung `iss`, `aud`, `exp`); MFA-Durchsetzung über Authentisierungsnachweis im Token für privilegierte Rollen; Service-Accounts per OAuth Client Credentials, pro Adapter und Zweck begrenzt (Kap. 01 §1.7).
  - Adapter/Worker: Keycloak-Realm als Code (`infra/`): Clients Passenger-Web, Operator-Web (public, PKCE), Passwort-Policy mit adaptivem Hash, MFA-Pflichtfluss für privilegierte Rollen, passwortloser Anmeldeweg vorgesehen; getrennte Identitätsdomänen je Umgebung (Kap. 01 §1.8).
  - Datenbank: keine eigene Passwort- oder Tokenspalte; Schema-Lint verbietet Spaltennamen wie `password*`.
- **Detailverträge vorab:** Token-Claims-Vertrag (Subject, Authentisierungsstärke, Session-ID); Liste der „privilegierten" Rollen (siehe OP-11); gewählter Hash-Algorithmus und Parameter des IdP; passwortloses Verfahren (siehe OP-12); Tokenlaufzeiten (siehe OP-13).
- **Akzeptanzkriterien:**
  1. Eine Sicherheitsprüfung (Schema-Scan, IdP-Konfigurationsexport, Log-Stichprobe) findet keine Klartextpasswörter und keinen unsicheren Standard-Hash; der IdP verwendet einen adaptiven Hash, ein passwortloser Anmeldeweg ist konfiguriert. (IAM-002)
  2. Ein Administratorzugriff mit gültigem Token ohne zweiten Faktor wird an jeder geschützten API mit 403 abgewiesen und über den Sicherheitsereignis-Port (WP-M1-46) protokolliert; mit zweitem Faktor gelingt er. Abfrage und Alarmierung dieses Ereignisses weist WP-M1-12 nach. (IAM-010)
  3. Token mit falscher Signatur, fremdem Issuer, falscher Audience oder abgelaufen ergibt 401.
  4. Ein Service-Account-Token (Client Credentials) ist auf Adapter und Zweck begrenzt: Aufrufe außerhalb seiner Scopes ergeben 403; ein Service-Account erhält keine Menschen-Sitzung und keinen Mandantenwechsel.
- **Tests:** positiv: Login mit PKCE, Admin mit MFA. negativ: Token ohne MFA für Adminrolle; manipulierte Signatur; `alg=none`; abgelaufenes Token; Token eines Issuers der Staging-Domäne gegen Produktion; Service-Account außerhalb seines Scopes. konkurrierend (Sicherheit): JWKS-Schlüsselrotation während laufender Anfragen ohne Annahme alter, zurückgezogener Schlüssel.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-46.
  - extern: WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35 (Keycloak in lokaler Umgebung und Staging)
- **Entscheidungen:** D-05 (Hostingregion/verwalteter IdP) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Mitgliedschaft und Mandantenwahl (WP-M1-06); Sitzungssperre (WP-M1-10); Registrierung (WP-M1-28); Web-Login-Oberfläche (WP-M1-44).

### WP-M1-05 – Mandant, Rechtsträger, Organisation und vertragliche Rollen
- **Ziel:** Mandanten, Rechtsträger und Organisationen lassen sich anlegen und pflegen; die Rollen Plattformbetreiber, Verkehrsunternehmen, Aufgabenträger, Auftraggeber, Vermittler und tatsächlicher Beförderer werden als eigenständige, getrennt gespeicherte Rollen geführt.
- **Anforderungen:** `LH-I.4-MUL-003`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Aggregate `Tenant` (Status `ACTIVE`, `SUSPENDED`, `CLOSED`), `LegalEntity`, `Organization` mit Rollenmenge und `federation_ref`; Regel „gleicher Rechtsträger begründet keinen Zugriff auf andere Mandanten" (Kap. 04 §4.1). Mandantenanlage durch die Plattformbetreiberrolle einschließlich erster Administrator-Mitgliedschaft; Plattformadministration erhält dadurch keine Fachdatensicht (Kap. 02 §4.3).
  - Datenbank: `tenant`, `legal_entity`, `organization`, `currency` aus `kernschema.sql`; Migration: CHECK auf zulässige Werte in `organization.roles`, `created_at`/Urheber/Revision gemäß Kap. 04 §4.1; Erweiterung um zeitliche Gültigkeit der Rollen (Kap. 02 §2, siehe OP-05).
  - API: Verwaltungsendpunkte für Organisation/Rechtsträger (nicht in Kap. 05 §5.7 enthalten, als Detailvertrag zu ergänzen).
  - Oberfläche: UI-18 Basis – Liste und Formular Organisationen mit Rechtsträgerrollen; Zustände „Scopekonflikt", fehlendes Recht.
  - Ereignisse/Outbox: `organization.changed` über `outbox`.
- **Detailverträge vorab:** OpenAPI für Organisations-/Rechtsträgerverwaltung; Rollen-Enum und Abbildung der sechs Lastenheftrollen auf die Begriffe aus Kap. 02 §2 (OP-05); DDL-Migration. Ablauf der Mandantenanlage und Erstausstattung (im Pflichtenheft nicht beschrieben, OP-36).
- **Akzeptanzkriterien:**
  1. Ein Testfall mit vier unterschiedlichen Organisationen (z. B. Plattformbetreiber, Aufgabenträger, Vermittler, tatsächlicher Beförderer) speichert die vertraglichen Rollen getrennt und liest sie getrennt wieder aus; keine Rolle wird aus einer anderen abgeleitet. (MUL-003)
  2. Eine Organisation kann mehrere Rollen tragen; unbekannte Rollenwerte werden mit 422 abgewiesen. (MUL-003)
  3. Organisationen und Rechtsträger des Mandanten B sind aus A weder les- noch referenzierbar (404).
  4. Die Plattformbetreiberrolle legt einen Mandanten samt Rechtsträger und erster Administrator-Mitgliedschaft an; danach kann sie ohne eigene Mitgliedschaft keine Fachdaten dieses Mandanten lesen. Statuswechsel des Mandanten (`ACTIVE`, `SUSPENDED`, `CLOSED`) sind auditiert; in `SUSPENDED` schlägt jeder Fachzugriff fehl.
  5. Jede Änderung an Organisation, Rechtsträger und Rollen erzeugt einen Auditeintrag und genau ein Outbox-Ereignis.
- **Tests:** positiv: vier Organisationen mit getrennten Rollen. negativ: unbekannte Rolle; Organisation mit Rechtsträger eines fremden Mandanten (FK-Verletzung → 404/422); doppelte `federation_ref`; Zugriff auf einen Mandanten im Status `SUSPENDED`; Mandantenanlage durch einen Mandantenadministrator. konkurrierend (Datentrennung): paralleles Anlegen derselben `federation_ref` – genau ein Erfolg.
- **Abhängigkeiten:** intern: WP-M1-01, WP-M1-02, WP-M1-06, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46.
- **Entscheidungen:** D-01 (Rollen je Dienst, Rechnungsaussteller) – blockiert nur Produktivschaltung der Rollenbelegung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Kooperationsvertrag und Grants (`partner_contract_version`, `cooperation_grant`, M6); Rollen am Fahrzeug (WP-M1-34); Unternehmensprofil (WP-M1-24).

### WP-M1-06 – Mitgliedschaft, RequestContext, serverseitiger Tenantwechsel, `/v1/me`
- **Ziel:** Der aktive Mandant ergibt sich ausschließlich aus einer serverseitig geprüften aktiven Mitgliedschaft. Header, Pfad oder JSON-Feld erzeugen keine Rechte; ein Tenantwechsel ist ein geprüfter serverseitiger Kontextwechsel.
- **Anforderungen:** `LH-I.4-MUL-006`, `LH-6.17-INT-002`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Aggregat `Membership` (Issuer/Subject, `role_code`, `service_scope`, `revoked_at`); unveränderlicher `RequestContext` aus Akteur, aktivem Tenant, Scope, zulässigen Diensten, Zweck und Vertragsrevision (in P1 leer, von M6 befüllt; Kap. 02 §4.1); personenbezogene Kontexte werden nicht in Logs kopiert; Tenantauswahl mit Membership-Prüfung; deny-by-default ohne Kontext; Scope-Prüfung je Endpunkt.
  - Datenbank: `membership` aus `kernschema.sql`; Migration: zeitliche Begrenzung (Kap. 02 §2, OP-04); kontrollierter Lookup „Mandanten eines Subjects" vor gesetztem Tenantkontext (OP-06).
  - API: `/v1/me` (Akteur, aktiver Mandant, Rollen, Dienste, wählbare Mandanten); Tenantwechsel-Operation (als Detailvertrag zu ergänzen); Verwaltungsendpunkte Mitgliedschaft erteilen/entziehen.
  - Oberfläche: UI-18 Basis – Mitgliedschaften und Rollen eines Mandanten verwalten.
  - Ereignisse/Outbox: `membership.granted`, `membership.revoked`; Audit über WP-M1-46.
- **Detailverträge vorab:** OpenAPI `/v1/me` und Tenantwechsel; Bindung Tenant↔Token/Sitzung (Kap. 05 §5.1 „Mitgliedschaft/Tokenbindung"); DDL-Ergänzung `membership`; Mechanismus für mandantenübergreifenden Membership-Lookup ohne Allzweck-`SECURITY DEFINER` (Kap. 04 §4.6).
- **Akzeptanzkriterien:**
  1. Ein Request mit manipuliertem `tenant_id` in Body, Pfad oder Header gibt keine fremde Ressource preis; die Antwort ist identisch zu „nicht vorhanden" (404) und es entsteht ein Sicherheitsereignis über den Port aus WP-M1-46. (MUL-006)
  2. Alle M1-Endpunkte bestimmen den Mandanten aus dem `RequestContext`; ein Architekturtest verbietet das Lesen von `tenant_id` aus Request-DTOs. (MUL-006)
  3. Ohne Token 401; mit Token ohne aktive Mitgliedschaft im gewählten Mandanten 403; mit Mitgliedschaft in A liefert kein Endpunkt Daten aus B. (INT-002)
  4. Fehlender Funktionsscope ergibt 403, ohne vertrauliche Daten im Fehlertext. (INT-002)
  5. Nach `revoked_at` ist der Zugriff spätestens mit der nächsten Anfrage beendet.
  6. Erteilen und Entziehen einer Mitgliedschaft sowie jeder Tenantwechsel sind auditiert; Anwendungslogs enthalten weder Token noch personenbezogene Kontextdaten (automatisierter Log-Scan der Testläufe).
  7. Der Tenantwechsel verlangt eine aktuelle Mitgliedschaft und einen freigegebenen Dienst; der Lookup der wählbaren Mandanten liefert ausschließlich Mandanten des angemeldeten Subjects (kein Aufzählen fremder Mandanten).
- **Tests:** positiv: Nutzer mit Mitgliedschaften in A und B wechselt und sieht jeweils nur den aktiven Mandanten. negativ: Wechsel in Mandant ohne Mitgliedschaft; manipulierter Mandantenheader; fremde UUID; widerrufene Mitgliedschaft; Lookup der Mandantenliste mit fremdem Subject. konkurrierend: Widerruf während laufender Anfragen – keine Anfrage nach Commit des Widerrufs erfolgreich; zwei Tabs mit unterschiedlichem Kontext unabhängig. Semantischer Contract-Fall „Manipulation tenant_id" und „unzulässige Fremd-ID" (Kap. 05 §5.8). Besteht AT-18 (Teil Mandant), ermöglicht PH-AT-001 (API-Teil), E2E-091.
- **Abhängigkeiten:** intern: WP-M1-01, WP-M1-04, WP-M1-14, WP-M1-43, WP-M1-46.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Rechtematrix je Rolle (WP-M1-07); Dienst-/Objektprüfung (WP-M1-08); Delegation/Vertretung (`delegated_access`, `/v1/delegations`, M7); Partner-Maschinenidentitäten (M6). Anlage von Mandanten (WP-M1-05; Testmandanten stammen bis dahin aus dem Fixture von WP-M1-01).

### WP-M1-07 – Rollenmodell und Rechtematrix
- **Ziel:** Die Rollen Fahrgast, Fahrer, Callcenter, Support, Disponent, Verkehrsplaner und Administrator existieren mit einer versionierten Rechtematrix; jede erlaubte und verweigerte Tätigkeit ist als ausführbarer Test belegt.
- **Anforderungen:** `LH-6.2-IAM-004`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Rollenkatalog (`role_code`), Abbildung Rolle → Funktionsscopes (z. B. `config:publish`, `operations:read`; Export als eigener Funktionsscope), zentrale Autorisierungskomponente; Regel „Tenantadmin bekommt nicht automatisch HR-, Gesundheits- oder Finanzzugriff" (Kap. 01 §1.7).
  - Datenbank: CHECK/Referenz für `membership.role_code`; Rechtematrix als versioniertes Artefakt im Repository.
  - API: Scope-Deklaration (`security`) an jedem M1-Endpunkt in OpenAPI.
  - Oberfläche: rollenbezogene Navigation im Operator-Web (nur erlaubte Bereiche sichtbar; Anzeige ist keine Rechtequelle, Kap. 06 §6.1).
- **Detailverträge vorab:** Rechtematrix Rolle × Tätigkeit für alle M1-Funktionen (das Pflichtenheft enthält keine Matrix, OP-10); Rollen-Enum einschließlich weiterer in Kap. 06 genannter Fachrollen (Finanzen, Personalplanung, Werkstatt) als Platzhalter für M3/M4; Scope-Katalog.
- **Akzeptanzkriterien:**
  1. Ein Rechtematrixtest führt für jede der sieben Rollen jede in der Matrix als erlaubt markierte M1-Tätigkeit erfolgreich und jede als verweigert markierte mit 403/404 aus; der Test ist aus der Matrixdatei generiert. (IAM-004)
  2. Eine neue Route ohne Matrixeintrag lässt den Build scheitern. (IAM-004)
  3. Die Administratorrolle hat ohne gesonderte Zuweisung keinen Zugriff auf Felder der Klassen PII/HR/Finanzen.
  4. Die Rechtematrix ist versioniert; eine Änderung der Matrix ist im Repository nachvollziehbar und wird beim Ausrollen auditiert.
- **Tests:** positiv/negativ: vollständiger generierter Matrixlauf (7 Rollen × M1-Tätigkeiten). konkurrierend (Sicherheit): Rollenentzug während aktiver Sitzung – Folgeanfrage verweigert. Besteht AT-18 (Teil Rolle).
- **Abhängigkeiten:** intern: WP-M1-06, WP-M1-43.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Matrixzeilen für Fachfunktionen aus M2–M4 (werden dort ergänzt, das Testgerüst stammt von hier); Break-glass-Zugriff (MUL-018, M6).

### WP-M1-08 – Dienst- und Objektberechtigung
- **Ziel:** Zusätzlich zur Rolle begrenzt jede Objektoperation den Zugriff auf Mandant, Dienst (`service_scope`) und konkretes Objekt; ein Disponent einer Region sieht keine Objekte einer anderen Region.
- **Anforderungen:** `LH-6.2-IAM-005`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Objektberechtigungs-Port (`canRead/canWrite(actor, objectRef, purpose)`), Dienstfilter für Listen („Filter dürfen Autorisierung nur weiter einschränken", Kap. 05 §5.1), Pflichtaufruf per Architekturtest für jedes Repository mit Dienstbezug.
  - Datenbank: Nutzung `membership.service_scope uuid[]` und `service.id`; Index `(tenant_id, …)` für dienstgefilterte Listen.
  - API: einheitliches Verhalten 404 für nicht sichtbare Objekte, auch innerhalb desselben Mandanten bei fremdem Dienst.
- **Detailverträge vorab:** Semantik von `service_scope` (NULL = alle Dienste oder keiner? im Pflichtenheft nicht festgelegt, OP-04); Zuordnung Region ↔ Dienst (WP-M1-09); Objektberechtigungsregeln je Aggregattyp.
- **Akzeptanzkriterien:**
  1. Ein Disponent mit `service_scope` = Dienste der Region 1 erhält für ein Testobjekt mit Dienstbezug zur Region 2 desselben Mandanten 404 in Detail-, Listen- und Änderungszugriff; der Nachweis an der echten Buchung erfolgt mit M2 über denselben Port. (IAM-005)
  2. Listenfilter können den Dienstscope nicht erweitern (Filter auf fremden Dienst liefert leere Liste). (IAM-005)
  3. Zugriff auf Objekte fremder Mandanten bleibt unabhängig vom Dienstscope 404.
- **Tests:** positiv: Disponent bearbeitet Objekt eigener Region. negativ: fremde Region per UUID, per Listenfilter, per Sortierung/Cursor einer anderen Sicht. konkurrierend (Datentrennung): Scope-Entzug parallel zu einer Änderung – Änderung nach Commit des Entzugs abgewiesen. Besteht AT-18; Baustein für PH-AT-001.
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-09.
  - extern: WP-M2-23 (Buchungsaggregat für den Nachweis „fremde regionale Buchungen")
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Feldrechte auf sensible Merkmale (WP-M1-31); Kooperationsgrants zwischen Mandanten (M6).

### WP-M1-09 – Organisationsstruktur: Regionen, Betriebshöfe, Dienste, Kostenstellen
- **Ziel:** Ein Mandant verwaltet mehrere Regionen, Betriebshöfe, Betriebsdienste und Kostenstellen; ein Nutzer arbeitet innerhalb eines Logins in zwei geografisch getrennten Gebieten mit jeweils eigenständigen Regeln.
- **Anforderungen:** `LH-I.4-MUL-002`
- **Liefergegenstände:**
  - Backend: Module `identity`/`service-geo` – Stammobjekte Region und Betriebshof, Dienstkopf (`service` mit `operator_id`, `timezone`, `config_revision_id`; die Anlage eines Dienstes erzeugt dessen erste Konfigurationsrevision über WP-M1-20, weil `service.config_revision_id` Pflichtfeld ist – OP-35), Kostenstelle als `cost_object` mit `kind='COST_CENTER'`.
  - Datenbank: `service`, `cost_object` aus `kernschema.sql`; neue Tabellen für Region und Betriebshof (weder in `kernschema.sql` noch in Kap. 04 §4.4 benannt, OP-07) mit `(tenant_id,id)`, RLS, Revision; Zuordnung Dienst → Region.
  - API: `/v1/services` (Kopf-Verwaltung; Geo-Inhalte folgen in M2), Verwaltungsendpunkte Region/Betriebshof/Kostenstelle als Detailvertrag.
  - Oberfläche: UI-17 Basis – Strukturbaum Mandant → Region → Dienst; Kontextzeile „Mandant/Region/Dienst" (Kap. 06 §6.1).
  - Konfigurationsparameter: Scope-Arten Region und Dienst werden von WP-M1-21 genutzt.
- **Detailverträge vorab:** DDL Region/Betriebshof; OpenAPI; Entscheidung, ob Kostenstellen in M1 nur als Stammobjekt (`cost_object`) oder bereits mit Rechtsträgerlogik geführt werden (Finanzlogik ist M4).
- **Akzeptanzkriterien:**
  1. Ein Mandant besitzt zwei geografisch getrennte Regionen mit je einem Dienst; jede Region und jeder Dienst trägt eine eigene Konfigurationsrevision mit unterschiedlichen Werten (z. B. `max_wait_min`), und ein Nutzer mit Mitgliedschaft für beide Dienste arbeitet ohne erneute Anmeldung in beiden. Dass diese Werte als wirksame Regeln aufgelöst werden, weist WP-M1-21 (dort Kriterium 6) am selben Fixture nach. (MUL-002)
  2. Betriebshöfe und Kostenstellen lassen sich je Mandant mehrfach anlegen und einem Gebiet/Dienst zuordnen. (MUL-002)
  3. Gleichnamige Kostenstellen in Mandant A und B sind getrennte Objekte.
  4. Anlegen und Ändern von Region, Betriebshof, Dienst und Kostenstelle ist auf berechtigte Rollen beschränkt, auditiert und über `If-Match` gegen stilles Überschreiben geschützt.
- **Tests:** positiv: zwei Regionen, eigene Regeln, ein Login. negativ: Dienst einer Region eines fremden Mandanten zuordnen; doppelte `business_ref` je `kind`; Anlage durch Rolle ohne Recht; ungültige IANA-Zone in `service.timezone`. konkurrierend (Datentrennung): paralleles Anlegen identischer Kostenstelle – genau ein Erfolg (`UNIQUE(tenant_id,kind,business_ref)`).
- **Abhängigkeiten:** intern: WP-M1-05, WP-M1-06, WP-M1-07, WP-M1-20, WP-M1-43.
  - extern: WP-M2-04, WP-M2-05 (Bediengebiet-Geometrie (`service_area`) und Haltepunkte (`stop_revision`))
- **Entscheidungen:** D-05 (Pilotbetreiber/Regionen für Fixtures) – blockiert den Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** `service_area`, `stop_revision`, Geo-Validierung (M2); Dienstplan (M4); Kostenrechnung (M4); Betriebshof-Inventar (M7).

### WP-M1-10 – Sitzungsverwaltung und Sitzungssperre
- **Ziel:** Nutzer und Administratoren können Sitzungen beenden, alle Geräte abmelden und Konten sperren; eine gesperrte Sitzung kann trotz noch gültigem Token keine geschützte API mehr aufrufen.
- **Anforderungen:** `LH-6.2-IAM-009`
- **Liefergegenstände:**
  - Backend: Modul `identity` – serverseitiges Sitzungsregister mit Sperrstatus, Prüfung „session status" in jeder Anfrage (Kap. 05 §5.1); Abmeldung aller Geräte (IdP-Sitzungen beenden und lokale Sperre); Kontosperre; Wiederherstellungsfluss über IdP mit verifiziertem Kontakt.
  - Datenbank: neue Tabelle für Sitzungssperren (nicht in `kernschema.sql`/§4.4, OP-13) mit RLS; Redis nur als Cache der Sperrliste, PostgreSQL führt.
  - API: Erweiterung `/v1/me` um Sitzungsliste, „alle Geräte abmelden"; Admin-Operation Konto/Sitzung sperren (Detailvertrag).
  - Oberfläche: UI-05 Basis (eigene Sitzungen, alle Geräte abmelden); UI-18 Basis (Nutzer sperren).
  - Ereignisse/Outbox: Sicherheitsereignisse Logout, Sperre, Entsperrung, Wiederherstellung über den Port aus WP-M1-46 (Login-Ereignisse des IdP übernimmt WP-M1-12).
  - Adapter/Worker: IdP-Admin-Anbindung (Sitzungen des Kontos beenden, Konto sperren/entsperren, Wiederherstellung anstoßen) mit eigenem, eng berechtigtem Service-Account.
- **Detailverträge vorab:** OpenAPI der Sitzungsoperationen; DDL; maximale Tokenlaufzeit und Cache-TTL der Sperrliste (im Pflichtenheft nur „kurze Tokenlaufzeit", OP-13); Wiederherstellungsverfahren.
- **Akzeptanzkriterien:**
  1. Nach Sperrung einer Sitzung liefert jeder Aufruf einer geschützten API mit dem zuvor gültigen Access-Token 401; ein Refresh ist nicht mehr möglich. (IAM-009)
  2. „Alle Geräte abmelden" beendet alle Sitzungen des Kontos; ein zweites Gerät erhält bei der nächsten Anfrage 401. (IAM-009)
  3. Die Wiederherstellung des Zugangs ist nur über einen im IdP als verifiziert geführten Kontakt möglich und beendet alle bestehenden Sitzungen des Kontos; die Kontaktverifikation für Fahrgäste liefert WP-M1-28. (IAM-009)
  4. Sperren und Entsperren eines Kontos ist auf berechtigte Rollen beschränkt; Sperre, Entsperrung, „alle Geräte abmelden" und Wiederherstellung erzeugen je einen Auditeintrag mit Akteur und Zeitpunkt.
  5. Fällt der Cache der Sperrliste aus, entscheidet die Datenbank; im Zweifel wird der Zugriff verweigert (fail-closed).
- **Tests:** positiv: Abmelden, erneutes Anmelden. negativ: gesperrte Sitzung ruft `/v1/me` und einen schreibenden Endpunkt auf; Sperrversuch durch unberechtigte Rolle; Wiederherstellung über unverifizierten Kontakt; Sperre bei ausgefallenem Redis (fail-closed über Datenbank). konkurrierend (Sicherheit): Sperre während 50 paralleler Anfragen – keine nach Commit begonnene Anfrage erfolgreich.
- **Abhängigkeiten:** intern: WP-M1-06, WP-M1-43, WP-M1-46.
  - extern: WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35 (Keycloak mit Admin-Schnittstelle in lokaler Umgebung und Staging)
  - extern: WP-M2-35, WP-M2-36 (Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Web-Login (WP-M1-44); erweiterte IAM-Anforderungen der Phase 2 (M8).

### WP-M1-11 – Isolation außerhalb der Datenbank und mandantengetrennte Bestände
- **Ziel:** Mandantengrenzen gelten auch in Cache, Objektspeicher, Ereignissen, Exporten und Suche; die je Mandant getrennten Bestände (Rollen, Tarif-/Konfigurationsdaten, Marken, Benachrichtigungsregeln) beeinflussen sich nachweislich nicht.
- **Anforderungen:** `LH-6.1-SYS-001`, `LH-I.4-MUL-007`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Cache-Key-Baustein (Tenant, Dienst, Ressourcen-ID, Policyrevision; Kap. 02 §4.3); Objektspeicher-Baustein mit Mandantenpräfix und signierten URLs erst nach Objekt-/Zweckprüfung (Default höchstens 60 s, Planungsdefault Kap. 02 §4.3); Ereignishülle mit Eigentums-`tenant_id` und expliziter Empfängeradressierung; Listen-/Export-Baustein mit erzwungenem Tenantfilter.
  - Datenbank: Nachweis, dass `config_revision`, `membership` und alle Stammdaten aus WP-M1-05/09 mandantenlokal sind; mandantenbezogene Marken- und Benachrichtigungsregel-Einstellungen als Konfigurationswerte im Register (Tabelle `config_revision`).
  - Konfigurationsparameter: `notification_quiet_hours`, `customer_notify_before_pickup_min`, `reporting_timezone` als Beispiele mandantengetrennter Werte.
  - Adapter/Worker: Architekturtests „kein Cache-/Storage-Zugriff ohne Tenant im Schlüssel".
- **Detailverträge vorab:** Schlüsselschema Cache/Objektspeicher; Ereignishülle (JSON-Schema); Liste der in M1 vorhandenen Lese-, Listen- und Exportwege für den Cross-Tenant-Scan; Datenmodell für Marke/Branding (im Pflichtenheft nicht spezifiziert, OP-09).
- **Akzeptanzkriterien:**
  1. Ein Testmandant A kann über keinen M1-Endpunkt (Detail, Liste, Änderung, Löschung) und keinen technischen Weg (Cache, Objektspeicher-URL, Ereigniskanal) ein Objekt des Mandanten B lesen oder verändern; Antworten für fremde und nicht vorhandene Objekte sind gleichartig. (SYS-001)
  2. Zwei Mandanten verwalten unabhängig Dienste, Regionen, Ressourcen-Stammdaten und Regeln; Anlegen/Ändern in A erzeugt keine Zeile, kein Ereignis und keinen Cacheeintrag in B. (SYS-001)
  3. Eine publizierte Änderung der Tarif-/Konfigurationsrevision in Mandant A ändert die wirksame Konfiguration von B nicht (Vorher/Nachher-Vergleich von `/v1/config/effective` für B identisch); der Nachweis an `tariff_revision` folgt mit M2. (MUL-007)
  4. Rollenzuweisungen, Marken- und Benachrichtigungsregel-Einstellungen sind je Mandant getrennt gespeichert und abrufbar. (MUL-007)
  5. Das Sicherheitsprotokoll enthält bei Fremdzugriff ein Ereignis ohne B-Personen- oder Inhaltsdaten.
- **Tests:** positiv: A und B arbeiten parallel. negativ: bekannte B-UUID über jeden M1-Endpunkt; signierte URL von B mit Sitzung A; Cache-Poisoning-Versuch mit identischer Ressourcen-ID. konkurrierend (Datentrennung): gleichzeitige Publikation in A und B mit identischer `scope_id` – keine gegenseitige Wirkung. Besteht PH-AT-001 (soweit M1-Endpunkte), E2E-091, AT-18.
- **Abhängigkeiten:** intern: WP-M1-06, WP-M1-08, WP-M1-12, WP-M1-14, WP-M1-22.
  - extern: WP-M2-07, WP-M2-38, WP-M4-21 (`tariff_revision` für den Tarifnachweis; Such- und Medienendpunkte)
  - extern: WP-M4-22, WP-M4-33, WP-M4-37, WP-M4-64 (Beleg-, Export- und Finanzendpunkte für den vollständigen PH-AT-001)
  - extern: WP-M0-08 (MinIO/Redis in lokaler Umgebung)
- **Entscheidungen:** D-02 – blockiert den Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** WebSocket/SSE-Kanäle (M3 Livekarte); Suchindex (späterer Meilenstein); Partnerprojektionen (M6).

### WP-M1-12 – Audit append-only und Sicherheitsereignisse
- **Ziel:** Sicherheitsrelevante Änderungen, Logins und Eingriffe werden unveränderlich mit Akteur, Zeitpunkt und Aktivität protokolliert und sind abfragbar; Sicherheitsereignisse enthalten keine Fremdinhalte.
- **Anforderungen:** `LH-6.18-SEC-006`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Nutzung des Audit- und Sicherheitsereignis-Ports aus WP-M1-46; Katalog und Erzeugungsregeln der Sicherheitsereignisse (401/403/404-auf-fremd, manipulierte Mandantenangabe, MFA-Fehlschlag, Sperre, veränderter Payload bei gleicher Ereignis-ID); Unterscheidung „fehlend" vs. „verweigert" nur intern (Kap. 02 §4.1); Auditabfrage-Dienst mit Rollen- und Zweckprüfung.
  - Datenbank: Lesezugriff auf `audit_event` (Tabelle, Trigger `audit_immutable` und Rechte liefert WP-M1-46); Index `(tenant_id, occurred_at)` für die Abfrage; der Auditzugriff selbst wird auditiert.
  - API: Auditabfrage (Filter Akteur, Objekt, Zeitraum, Aktion; Cursor-Liste) – nicht in Kap. 05 §5.7 gelistet, als Detailvertrag zu ergänzen.
  - Oberfläche: UI-20 Basis – Auditliste mit Zweckangabe; Zustände „fehlender Zweck", „abgelaufener Zugriff".
  - Adapter/Worker: Export in getrennte Integritäts-/Berechtigungsdomäne (Kap. 04 §4.3, Kap. 01 §1.7) mit Hashkette oder gleichwertiger Integritätsprüfung; Übernahme der Anmeldeereignisse des IdP (erfolgreicher und fehlgeschlagener Login, Logout, MFA-Fehlschlag) in `audit_event`, da Logins nicht im Backend stattfinden (OP-14).
- **Detailverträge vorab:** Katalog auditpflichtiger Aktionen in M1; JSON-Schema `redacted_changes`; OpenAPI Auditabfrage; Zielsystem und Integritätsverfahren des Exports (OP-14); Behandlung von Ereignissen ohne Mandantenkontext (fehlgeschlagener Login, OP-14).
- **Akzeptanzkriterien:**
  1. Eine Auditabfrage nach einer Rollenänderung, einem Login und einer Konfigurationspublikation weist jeweils Akteur (`actor_ref`), Zeitpunkt (`occurred_at`) und Aktivität (`action`, `object_ref`) aus. (SEC-006)
  2. Die Protokolle sind manipulationserschwert: UPDATE und DELETE auf `audit_event` schlagen fehl (Speichernachweis aus WP-M1-46, im Abnahmelauf wiederholt), und der Export erkennt eine nachträglich manipulierte Kopie. (SEC-006)
  3. Sicherheitsereignisse zu Fremdzugriffen enthalten keine Personen-, Beleg- oder Objektinhalte des anderen Mandanten.
  4. Auditzugriff ist rollenbeschränkt; A liest keine Auditzeilen von B.
  5. Ein erfolgreicher und ein fehlgeschlagener Login am IdP erscheinen als Auditeinträge mit Akteur bzw. Anmeldekennung, Zeitpunkt und Ergebnis; das Passwort oder der Code erscheinen nie. (SEC-006)
  6. Die von WP-M1-04 (MFA-Fehlschlag), WP-M1-06 (manipulierte Mandantenangabe), WP-M1-10 (Sperre) und WP-M1-14 (veränderter Payload) gemeldeten Ereignisse sind über die Auditabfrage auffindbar. (SEC-006)
  7. Dispositionseingriffe werden über denselben Port protokolliert; der Nachweis am echten Eingriff folgt mit M3. (SEC-006)
- **Tests:** positiv: Abfrage mit Filtern. negativ: UPDATE/DELETE-Versuch; Auditabfrage ohne Berechtigung oder ohne Zweck; Fremdmandant; fehlgeschlagener Login ohne Mandantenkontext; doppelt geliefertes IdP-Ereignis erzeugt keinen zweiten Eintrag. konkurrierend (Sicherheit): Export läuft parallel zu 100 schreibenden Änderungen – die Integritätsprüfung des Exports bleibt gültig und kein Eintrag fehlt; paralleler Zugriff zweier Mandanten auf die Auditabfrage ohne Vermischung. Besteht AT-49 („protokolliert"), E2E-091 („Sicherheitsprotokoll").
- **Abhängigkeiten:** intern: WP-M1-06, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46.
  - extern: WP-M0-08, WP-M0-09, WP-M0-11, WP-M0-35 (Keycloak-Ereignisschnittstelle in lokaler Umgebung und Staging)
  - extern: WP-M3-40 (Dispositionseingriffe als auditpflichtige Fachaktion (Originalabnahme „Dispositionseingriffe"))
- **Entscheidungen:** D-11 (Aufbewahrung, zulässiger Supportzugriff) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Audit-Port, Tabelle und Unveränderlichkeit (WP-M1-46); Alarmregeln und Vorfallprozess (WP-M1-41); Dispositionseingriffe selbst (M3 nutzt den Audit-Port); Datenschutzanfragen `/v1/privacy/requests` und Legal Hold (M8); Supportfall-Bearbeitung in UI-20 (M7).

### WP-M1-13 – Idempotenzregister und Kollisionskontrolle
- **Ziel:** Jeder schreibende Befehl mit `Idempotency-Key` wird je Mandant, Akteur und Operationstyp genau einmal wirksam; Wiederholungen liefern dasselbe Ergebnis, abweichende Requests mit gleichem Schlüssel 409.
- **Anforderungen:** `LH-6.17-INT-004`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Idempotenzfilter: Scope `(auth_principal, tenant_context, operation_name, Idempotency-Key)`, kanonischer Request-Fingerprint inkl. Ressourcen-ID und fachlich relevanter Header, Speicherung von Operations-ID und HTTP-Ergebnis; Prüfung nach Authentisierung, vor erneutem ETag-/Ablauftest (Kap. 05 §5.1); `409 IDEMPOTENCY_CONFLICT`, `409 OPERATION_IN_PROGRESS`.
  - Datenbank: `idempotency_record` (PK `(tenant_id,principal_ref,command_scope,key)`, `request_sha256`, `operation_id`, `response_json`, `expires_at`); Bereinigungsjob nach Ablauf; Invariante §4.5 „Bestätigung/PSP-Retry exakt gleiche Operation" (Registerteil).
  - API: Header-Parameter `IdempotencyKey` (16–128 Zeichen, Muster aus `openapi.yaml`) an allen schreibenden Endpunkten.
- **Detailverträge vorab:** Kanonisierungsregel des Fingerprints; Liste „fachlich relevanter Header"; Response-Cachehaltung 72 h (Entwurfswert des Pflichtenhefts, als Planungsdefault gekennzeichnet); Verhalten bei laufender Erstverarbeitung.
- **Akzeptanzkriterien:**
  1. Zwei gleichzeitige identische Requests mit gleichem Schlüssel erzeugen genau ein Ergebnisobjekt und genau einen Outbox-Eintrag; beide Antworten tragen dieselbe Ressourcen-/Operations-ID. (INT-004)
  2. Gleicher Schlüssel mit anderem Fingerprint ergibt `409 IDEMPOTENCY_CONFLICT`, keine zweite Ausführung und keine Wiederverwendung des fremden Ergebnisses. (INT-004)
  3. Schlüssel kollidieren nicht zwischen Mandanten, Akteuren und Operationstypen.
  4. Ein bereits angenommenes Kommando erscheint bei Wiederholung nicht nachträglich als abgelaufen; Authentisierung und Leseberechtigung werden erneut geprüft.
  5. Abgelaufene Einträge (`expires_at`) werden bereinigt, ohne dass ein noch laufender Befehl seinen Eintrag verliert; der Ablauf des Antwortcaches erlaubt keine zweite fachliche Wirkung, soweit ein fachlicher Unique-Schlüssel existiert.
- **Tests:** positiv: Wiederholung nach Timeout liefert gespeichertes Ergebnis. negativ: anderer Body, anderer Pfad, anderer Akteur mit gleichem Schlüssel; zu kurzer Schlüssel. konkurrierend (Geld/Kapazität): 100 parallele identische Requests → eine Wirkung; paralleler Retransmit während laufender Erstverarbeitung. Besteht PH-AT-011 an einem Testbefehl (am `publishConfig`-Befehl mit WP-M1-22; Betragsvariante mit Angebot Q1/Q2 mit M2), ermöglicht AT-24.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-06, WP-M1-14.
  - extern: WP-M2-24, WP-M2-30 (Buchungs- und Zahlungsbefehle als fachliche Nutzer (Abnahmebezug „Buchungs- und Zahlungsvorgänge"))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** fachliche Unique-Constraints für Buchung/Zahlung/Kostenbuchung (M2, M4); PSP-Idempotenz (M2).

### WP-M1-14 – Outbox-Relay und Inbox-Deduplikation
- **Ziel:** Fachtransaktionen schreiben Aggregat, Revision und Outbox gemeinsam; ein Relay stellt mindestens einmal zu, Consumer deduplizieren und lassen ältere Revisionen keinen neueren Zustand überschreiben.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: `backend/platform` – Outbox-API für Module, Inbox-Baustein mit Dedupe `(consumer, event_id)` und Aggregatrevisionsprüfung, Lückenerkennung mit Nachlesen per autorisiertem Snapshotabruf (Kap. 01 §1.5); Trennung Ereigniszeit, Empfangszeit, Aggregatversion.
  - Datenbank: `outbox` (`UNIQUE(tenant_id,aggregate_id,aggregate_revision,event_type)`, Teilindex `outbox_unpublished`), `inbox` (PK `(tenant_id,consumer,event_id)`, `payload_sha256`); Ergänzung von Zustellversuchszähler/Dead-Letter-Kennzeichen (im Kern-DDL nicht enthalten, OP-16).
  - Ereignisse/Outbox: Ereignishülle mit `event_id`, `event_type`, `schema_version`, `aggregate_revision`, `occurred_at`, Eigentums-`tenant_id`, Empfängeradressierung.
  - Adapter/Worker: Relay-Worker PostgreSQL → RabbitMQ Quorum Queues, Wiederaufholen nach Relay-Verlust, Dead-Letter mit Betreiberwarnung.
- **Detailverträge vorab:** JSON-Schema der Ereignishülle und Schema-Versionierungsregel; Queue-/Exchange-Topologie; Retry-Staffel (Vorschlag Kap. 05 §5.6: 1 s, 5 s, 30 s, 2 min, 10 min, dann exponentiell mit Jitter bis 24 h – Planungsdefault).
- **Akzeptanzkriterien:**
  1. Rollback der Fachtransaktion hinterlässt keinen Outbox-Eintrag; Commit hinterlässt genau einen je Aggregatrevision und Ereignistyp.
  2. Dreifach zugestelltes Ereignis mit Revision 8 wirkt genau einmal; ein danach eintreffendes Ereignis mit Revision 7 setzt den Zustand nicht zurück und löst Nachlesen aus.
  3. Nach Abbruch des Relays werden unveröffentlichte Einträge ohne Verlust nachgeliefert.
  4. Gleiche `event_id` mit anderem Payloadhash wird nicht verarbeitet und über den Sicherheitsereignis-Port (WP-M1-46) gemeldet.
  5. Ein Consumer verarbeitet ein Ereignis nur im Kontext des adressierten Empfängermandanten; ein Ereignis ohne passende Empfängeradressierung wird nicht zugestellt.
  6. Geschäftliche Ereigniszeit, Empfangszeit und Aggregatversion sind getrennt gespeichert und abfragbar.
- **Tests:** positiv: Ende-zu-Ende Zustellung. negativ: Duplikat, veraltete Revision, Lücke, veränderter Payload, Ereignis für fremden Empfängermandanten. konkurrierend: zwei Relay-Instanzen gleichzeitig; zwei Consumer-Instanzen verarbeiten dasselbe Ereignis parallel → eine Wirkung. Besteht PH-AT-017, AT-24 (Ereignisteil); semantischer Contract-Fall „100 identische Webhooks" (Dedupe-Baustein).
- **Abhängigkeiten:** intern: WP-M1-01, WP-M1-46.
  - extern: WP-M0-08 (RabbitMQ in lokaler Umgebung)
  - extern: WP-M0-10, WP-M5-04 (Observability/Alarmkanal für die Betreiberwarnung bei Dead-Letter)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** fachliche Consumer (M2–M4); Partner-Inbox mit `(partner_id,event_id)` und Signaturprüfung (M6; Grundlage in WP-M1-16).

### WP-M1-15 – `operation`-Ressource mit Lease/Fencing
- **Ziel:** Lang laufende Befehle werden als persistierte `operation` geführt, nach Prozessneustart über Lease/Fencing wieder aufgenommen und sind über `/v1/operations/{operation_id}` abfragbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: `backend/platform` – Operation-Runner: Zustände `QUEUED`, `RUNNING`, `RECONCILING`, `SUCCEEDED`, `FAILED`, `MANUAL_REVIEW` (Kap. 02 §7.3); Lease-Übernahme mit `lease_until`, monoton steigendem `fencing_token`, `next_attempt_at`, `deadline_at`; Regel „UNKNOWN wird abgeglichen, nie zu Erfolg/Fehler verkürzt".
  - Datenbank: `operation` (und Anbindung `saga_step` als Struktur, ohne Partnerlogik); Teilindex für offene Operationen (Kap. 04 §4.7); Anpassung `journey_id NOT NULL` für nicht reisebezogene Operationen (OP-15).
  - API: `/v1/operations/{operation_id}` (`getOperation`, Scope `operations:read`), Antwortbaustein `OperationAccepted` (202, `Location`, `Retry-After`).
- **Detailverträge vorab:** Zustandsautomat der Operation mit erlaubten Übergängen; Erweiterung des `kind`-Enums über `HOLD`, `CONFIRM`, `CANCEL`, `RECONCILE` hinaus für M1-Befehle (OP-15); Lease-Dauer und Backoff.
- **Akzeptanzkriterien:**
  1. Ein Worker, der seine Lease verloren hat, kann mit altem `fencing_token` keinen Zustand mehr schreiben.
  2. Nach erzwungenem Prozessabbruch nimmt ein anderer Worker die Operation nach Leaseablauf auf; die Geschäftswirkung tritt genau einmal ein.
  3. `GET /v1/operations/{operation_id}` liefert den persistierten Stand mit `retry_after_seconds`; fremde Operation ergibt 404.
  4. `FAILED` wird nur nach determiniertem Ergebnis gesetzt.
  5. Der Zugriff auf `/v1/operations/{operation_id}` verlangt den Scope `operations:read`; ohne Scope 403.
- **Tests:** positiv: Operation läuft durch. negativ: fremder Mandant, unerlaubter Zustandsübergang, abgelaufene Deadline. konkurrierend: zwei Worker konkurrieren um dieselbe Operation; Absturz zwischen Nebenwirkung und Speicherung (mit Testadapter). Vorbereitung PH-AT-009 (M6).
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-13, WP-M1-14.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Journey-Saga und Partnerabgleich (M6); Hold/Confirm im lokalen Betreiberkern (M2).

### WP-M1-16 – Webhook-Grundlage (signierter Eingang, Abonnement, Zustellung)
- **Ziel:** Es gibt einen wiederverwendbaren Baustein für eingehende signierte Ereignisse (Prüfung, Inbox-Commit, Quittung) und für ausgehende Webhook-Zustellung mit Abonnement, Signatur, Retry und Dead-Letter.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: `backend/platform` – Eingangsfilter: konstantzeitliche HMAC-Prüfung über den exakten Rohbody, Zeitfenster ±300 s, maximale Körpergröße, Schlüssel-ID mit Rotation (alter und neuer Schlüssel kurzzeitig gültig), Quittungen 202 nach Inbox-Commit, 200 `ALREADY_RECEIVED`, 409 bei gleicher ID mit anderem Payload (Kap. 05 §5.6). Ausgang: Zustellworker mit empfängergebundenem, minimiertem Payload.
  - Datenbank: `webhook_subscription` (Kap. 04 §4.4 CFG/SEC/INT: Callbackzielprüfung, Signatur/Secretreferenz, keine Secretwerte); `inbox` für Eingangsdeduplikation.
  - API: `/v1/webhook-subscriptions` (Anlegen, Lesen, Deaktivieren) mit Ziel-URL-Prüfung gegen SSRF (Kap. 05 §5.7).
  - Adapter/Worker: Retry-Staffel und Dead-Letter mit Betreiberwarnung; Respektieren von 429 `Retry-After`.
- **Detailverträge vorab:** OpenAPI `/v1/webhook-subscriptions`; DDL `webhook_subscription`; Signaturvertrag ausgehend (Header, Algorithmus); SSRF-Regeln (erlaubte Schemata, gesperrte Netze); Ereignistypen, die in P1 abonnierbar sind (OP-17).
- **Akzeptanzkriterien:**
  1. 100 identische eingehende Testereignisse führen zu genau einem Inbox-Eintrag und einer Wirkung; 99 Antworten sind `ALREADY_RECEIVED`.
  2. Falsche Signatur, Zeitabweichung über 300 s oder zu großer Body werden abgewiesen, ohne Inbox-Eintrag; gleiche Ereignis-ID mit verändertem Payload ergibt 409, ein Sicherheitsereignis und Quarantäne des Ereignisses (Kap. 05 §5.6).
  3. Ein Abonnement mit Ziel in privatem Netz, `localhost` oder mit Nicht-HTTPS-Schema wird mit 422 abgelehnt.
  4. Nach Erschöpfung der Zustellversuche liegt das Ereignis in Dead-Letter und eine Betreiberwarnung existiert; kein stiller Verlust.
  5. Abonnements sind mandantengetrennt und nur für berechtigte Rollen anleg-, les- und deaktivierbar; die Tabelle `webhook_subscription` enthält nur eine Secretreferenz, nie den Secretwert; Anlegen und Deaktivieren sind auditiert.
- **Tests:** positiv: Zustellung an Test-Empfänger mit gültiger Signatur. negativ: Signatur, Replay, SSRF-Ziele (inkl. DNS-Rebinding und Redirect auf internes Ziel), fremdes Abonnement. konkurrierend (Sicherheit): 100 parallele identische Eingänge; Schlüsselrotation während Zustellung. Besteht AT-24 (Webhook-Teil), semantischer Contract-Fall „100 identische Webhooks".
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-07, WP-M1-13, WP-M1-14, WP-M1-36, WP-M1-46.
  - extern: WP-M0-10, WP-M5-04 (Observability/Alarmkanal für die Betreiberwarnung bei Dead-Letter)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** `/v1/federation/partners/{partner_id}/events` mit Partnerbindung (M6); `/v1/payments/provider-events/{provider}` mit PSP-Signatur (M2); `/v1/notifications` (M2).

### WP-M1-17 – Zustandsautomaten-Rahmen mit serverseitiger Übergangsprüfung
- **Ziel:** Fahrt-, Buchungs-, Fahrzeug-, Zahlungs- und Störungsstatus sind getrennte, versionierte Automaten; jeder Übergang wird serverseitig gegen die Übergangstabelle geprüft, unerlaubte Übergänge werden abgewiesen.
- **Anforderungen:** `LH-6.1-SYS-007`
- **Liefergegenstände:**
  - Backend: `backend/platform` – deklarativer Automatenbaustein (Zustände, erlaubte Übergänge, Wächter, Revision/CAS, Outbox-Ereignis je Übergang); Registrierung der fünf Automaten als eigenständige Definitionen; Regel „keine gegenseitige Ableitung" (Zahlungsstatus kein impliziter Gleichlauf, Kap. 02 §7.3).
  - Datenbank: Härtung der `state`-Textspalten als versionierte CHECKs/Transitionsregeln (Kap. 04 §4.1) – in M1 für `operation.status` und `tenant.status`; Muster-Migration für `booking.state`, `vehicle_run.state`, `payment_intent.state`, `capacity_hold.state`, die von M2/M3 angewendet wird.
  - API: einheitlicher Fehler für unerlaubten Übergang (409 mit stabilem `code`; der Codename ist in Kap. 05 nicht vergeben und im Detailvertrag festzulegen), aktueller Zustand im ETag-geschützten Lesen.
  - Ereignisse/Outbox: `<aggregat>.state_changed` mit alter/neuer Zustandsangabe und Revision.
- **Detailverträge vorab:** Übergangstabellen: Buchung/Segment und Ausführung aus Kap. 02 §7.3, Zahlung aus Kap. 03 (`CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED` samt Nebenpfaden); Fahrzeugstatus und Störungsstatus sind im Pflichtenheft nicht als Wertelisten definiert und müssen vor Codebeginn spezifiziert werden (OP-21).
- **Akzeptanzkriterien:**
  1. Für jeden der fünf Automaten existiert eine eigene Definition mit eigener Zustandsspalte; kein Automat schreibt den Zustand eines anderen. (SYS-007)
  2. Ein per API angeforderter, nicht in der Übergangstabelle enthaltener Übergang wird serverseitig mit 409 abgewiesen, der Zustand bleibt unverändert, es entsteht kein Outbox-Ereignis. (SYS-007)
  3. Ein direkter Schreibversuch eines unzulässigen Zustandswerts scheitert zusätzlich am Datenbank-CHECK. (SYS-007)
  4. Ein verspätetes Ereignis mit älterer Revision bewirkt keinen Zustandsrücksprung.
- **Tests:** positiv: jeder definierte Übergang (generiert aus der Tabelle). negativ: jeder nicht definierte Übergang (generiert); unbekannter Zustand. konkurrierend: zwei widersprüchliche Übergänge gleichzeitig auf derselben Revision – genau einer gewinnt. Ermöglicht PH-AT-017.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-14.
  - extern: WP-M2-01, WP-M2-16, WP-M2-23, WP-M2-30 (Buchungs-, Fahrt- und Zahlungsaggregat wenden den Rahmen an)
  - extern: WP-M3-01, WP-M3-31, WP-M3-53 (Fahrzeug- und Störungsaggregat wenden den Rahmen an)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fachlogik der Übergänge (Wächter mit Kapazität, Safety, PSP) – M2/M3; Journey-Statusdimensionen der Föderation (M6).

### WP-M1-18 – Zeit-, Zeitzonen- und DST-Baustein
- **Ziel:** Alle Zeitpunkte werden als `timestamptz` in UTC mit zusätzlicher IANA-Zone geführt, in der API als RFC 3339 mit Offset übertragen und lokal korrekt dargestellt; DST-Lücken werden abgelehnt, doppelte Ortszeiten verlangen einen eindeutigen Offset.
- **Anforderungen:** `LH-6.1-SYS-006`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Zeittypen (Zeitpunkt, lokales Datum/Zeit + Zone, halboffenes Gültigkeitsintervall „links geschlossen, rechts offen"); Validator „`desired_at` und `time_zone` beschreiben denselben lokalen Zeitpunkt"; injizierbare Serveruhr; lokaler Betriebstag.
  - Datenbank: Lint „keine `timestamp without time zone`-Spalte"; `service.timezone`, `staff_shift.timezone` als IANA-Zone geprüft.
  - API: gemeinsame OpenAPI-Komponenten für Zeitpunkt und Zone; Feldfehlercodes für DST-Lücke und mehrdeutige Ortszeit.
  - Oberfläche: Designsystem-Komponente Datum/Zeit mit Zonenanzeige und Auswahl des UTC-Offsets bei mehrdeutiger Ortszeit (Kap. 06 §6.2).
  - Konfigurationsparameter: `reporting_timezone` (IANA TZ, Ebene Mandant, DST-sicher).
- **Detailverträge vorab:** JSON-Schema der Zeittypen; Fehlercodes; Regel zur Speicherung der Zone je Aggregat.
- **Akzeptanzkriterien:**
  1. Zwei Testanfragen für 25.10.2026 02:30 `Europe/Berlin` mit Offset +02:00 und +01:00 werden als zwei verschiedene, eindeutige Zeitpunkte gespeichert und unterscheidbar zurückgegeben; ohne Offset wird die Anfrage mit Feldfehler abgewiesen. (SYS-006)
  2. Eine nicht existente Ortszeit (Frühjahrsumstellung 02:30) wird mit Feldfehler abgewiesen. (SYS-006)
  3. Eine Dauer über die Zeitumstellung wird aus absoluten Zeitpunkten berechnet (z. B. 01:00–04:00 Ortszeit am 25.10.2026 = 4 h). (SYS-006)
  4. Die Oberfläche zeigt Zeitpunkte in der Zone des Dienstes mit Offset an.
  5. Der lokale Betriebstag eines Zeitpunkts wird in der Zone des Dienstes bestimmt und zusammen mit dem Zeitpunkt angezeigt (PH-AT-018: „aktuelle Ansicht zeigt … den lokalen Betriebstag"). (SYS-006)
- **Tests:** positiv: Sommer-/Winterzeitpunkte, Zonenwechsel. negativ: DST-Lücke, fehlender Offset, `time_zone` widerspricht Offset, unbekannte Zone. Besteht PH-AT-018 (DST-Teil); der Nachweis an der Buchung folgt mit M2. konkurrierend: nicht einschlägig (zustandsloser Baustein).
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-43.
  - extern: WP-M2-11, WP-M2-23 (Buchung über Sommer-/Winterzeitgrenze als fachlicher Nachweis)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Schichtdauer- und Arbeitszeitrechnung (M4); KPI-Periodenbasis (M4/M8).

### WP-M1-19 – Parameterkatalog, `policy_schema` und Systemgrenzen
- **Ziel:** Jeder betriebsrelevante Einstellwert hat technischen Namen, Datentyp, gültigen Bereich, Einheit und Beschreibung; Werte außerhalb des Schemas oder über dem systemischen Sicherheitsmaximum werden vor dem Speichern abgewiesen.
- **Anforderungen:** `LH-I.7-CFG-002`, `LH-I.7-CFG-004`
- **Liefergegenstände:**
  - Backend: Modul `config` – Parameterregister (Laden der Schemas, Typ-/Bereichs-/Einheitenprüfung), Policy-Validator für globale Sicherheits- und Gesetzesgrenzen, die keine Mandantenrevision überschreiben kann; Kennzeichnung nicht abschaltbarer Sicherheitsparameter.
  - Datenbank: `policy_schema` (Kap. 04 §4.4 CFG/SEC/INT) mit Schema-Version; `config_revision.schema_version` referenziert sie; globale Grenzen im Plattformmandanten (OP-08).
  - API: Lesen des Parameterkatalogs (Name, Typ, Einheit, Min/Max, Beschreibung, erlaubte Ebenen) als Teil von `/v1/config/effective` bzw. eigener Katalogressource (Detailvertrag).
  - Konfigurationsparameter: JSON-Schemas für die P1-relevanten der 80 Parameter aus `kataloge.csv`; Startmenge als Vorschlag dieses Plans (Auswahl im Pflichtenheft nicht festgelegt, OP-03): `max_wait_min`, `max_detour_pct`, `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `arrival_grace_min`, `no_show_grace_min`, `stop_walk_max_m`, `advance_arrival_buffer_min`, `pooling_allowed`, `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`, `transfer_min_time_min`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `vehicle_readiness_require_checks`, `customer_notify_before_pickup_min`, `reporting_timezone`, `notification_quiet_hours`.
- **Detailverträge vorab:** JSON-Schema je Parameter (Typ, Einheit, Min/Max, Default, erlaubte Ebene, Sicherheitsklasse); DDL `policy_schema`; Werte der systemischen Sicherheitsmaxima (im Pflichtenheft nicht beziffert, OP-03); Regel für Katalogeinträge mit unscharfem Bereich (z. B. `pickup_window_min` „Positiv").
- **Akzeptanzkriterien:**
  1. Jeder Parameter der Startmenge ist im Register mit technischem Namen, Datentyp, Bereich, Einheit und Beschreibung abrufbar; der Name stimmt exakt mit `kataloge.csv` überein (CI-Abgleich). (CFG-002)
  2. Ein Revisionsentwurf mit `max_wait_min` = −5 wird vor der Speicherung mit 422 und Feldverletzung abgewiesen; es entsteht keine Zeile in `config_revision`. (CFG-002)
  3. Ein Wert oberhalb des systemischen Sicherheitsmaximums (z. B. `max_wait_min` > 240 laut Katalogbereich 0..240) wird abgelehnt, auch wenn ein Mandantenadministrator ihn setzt. (CFG-004)
  4. `vehicle_readiness_require_checks` = false wird als Verletzung einer Sicherheitsinvariante abgelehnt („Sicherheitsprüfungen dürfen nicht abgeschaltet werden"). (CFG-004)
  5. Ein unbekannter Parametername oder falscher Typ wird abgewiesen.
  6. Der Parameterkatalog ist für jeden Mandanten lesbar, aber nur über Migration/Plattformfreigabe änderbar; ein Mandant kann weder Schema noch Systemgrenze eines Parameters verändern. (CFG-004)
- **Tests:** positiv: gültige Werte aller Startparameter an Bereichsgrenzen. negativ: negativ, über Maximum, falscher Typ, falsche Einheit, unbekannter Name, Sicherheitsparameter deaktivieren. konkurrierend (Sicherheit): Schemaversion wird parallel zu einem Entwurf angehoben – Entwurf wird gegen die bei Publikation gültige Version erneut validiert. Besteht E2E-097.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-06.
- **Entscheidungen:** D-05 (Zielwerte/Pilotprofil) – blockiert nur die Freigabe von Produktionswerten, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Schemas der übrigen Parameter (J.2 ETA, J.3 Flotte, J.4 Personal, J.5 Finanzen, J.6 Föderation, restliche J.7) – mit M3, M4, M6, M8; fachliche Verwendung der Werte (M2 ff.).

### WP-M1-20 – Konfigurationsrevisionen: Entwurf, Historie, Rekonstruktion
- **Ziel:** Konfigurationen werden ausschließlich als Revisionen je Scope geführt; jede Änderung trägt Benutzer, Zeitpunkt, Altwert, Neuwert, Grund und Gültigkeitsbeginn, und der zu einem historischen Zeitpunkt wirksame Stand ist rekonstruierbar.
- **Anforderungen:** `LH-I.7-CFG-003`, `LH-6.1-SYS-002`
- **Liefergegenstände:**
  - Backend: Modul `config` – Aggregat `ConfigRevision` mit Lebenszyklus Entwurf → geprüft → publiziert (Publikation in WP-M1-22); Diff-Berechnung Altwert/Neuwert gegen Vorgängerrevision; As-of-Abfrage; publizierte Revision unveränderlich. Grundschema „Dienstkonfiguration" für `scope_kind` Dienst mit den Bestandteilen Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten und Tarifverknüpfung (Angebotsmodell und Modellregeln ergänzt WP-M1-25).
  - Datenbank: `config_revision` (`scope_kind`, `scope_id`, `revision`, `schema_version`, `values_json`, `effective_from`, `effective_to`, `created_by`, `approved_by`, `published_at`); Migration: Statusspalte, Änderungsgrund, `created_at`, Unveränderlichkeits-Trigger für publizierte Zeilen (analog `reject_mutation()`), CHECK der zulässigen `scope_kind`-Werte (OP-18).
  - API: `/v1/config/revisions` (Liste, Entwurf anlegen), `/v1/config/revisions/{id}` (Lesen mit ETag, Entwurf ändern mit `If-Match`); As-of-Parameter für historische Rekonstruktion.
  - Oberfläche: UI-17 Basis – Revisionsliste je Scope mit Ersteller, Zeitpunkt, Wirksamkeit, Diff Alt/Neu, Grund.
  - Ereignisse/Outbox: `config.revision_created`; Audit über WP-M1-46.
- **Detailverträge vorab:** OpenAPI der beiden Pfade inkl. Schemas `ConfigRevision`, Diff, As-of; DDL-Migration; Zustandsautomat der Revision; Abbildung der Dienstkonfiguration (Bediengebiet, Verkehrstyp, Betreiber, Betriebszeiten, Tarifverknüpfung) auf `service.config_revision_id`.
- **Akzeptanzkriterien:**
  1. Jede Konfigurationsänderung eines Dienstes ist eine neue Revision mit Ersteller (`created_by`), Zeitstempel und dokumentierter Wirksamkeit (`effective_from`/`effective_to`); eine Änderung ohne diese Angaben wird abgewiesen. (SYS-002)
  2. Die Dienstkonfiguration umfasst versioniert Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten und Tarifverknüpfung als Bestandteile derselben Revision. (SYS-002)
  3. Das Änderungsprotokoll zeigt zu jeder Revision Benutzer, Zeitpunkt, Altwert, Neuwert, Grund und Gültigkeitsbeginn. (CFG-003)
  4. Jede Ansicht eines Konfigurationswerts zeigt die verwendete Revision (PH-AT-018). Für einen historischen Zeitpunkt T (z. B. Dispositionsfall vor zwei Revisionen) liefert die As-of-Abfrage exakt die damals wirksame Einstellung samt Revisions-ID. (CFG-003)
  5. Eine publizierte Revision lässt sich weder per API noch per SQL unter der Runtime-Rolle ändern oder löschen.
  6. Entwürfe und Revisionen sind nur für berechtigte Rollen des eigenen Mandanten les- und änderbar; Anlegen und Ändern eines Entwurfs erzeugt einen Auditeintrag.
- **Tests:** positiv: drei Revisionen, As-of auf jede. negativ: Änderung ohne Grund; fehlendes `effective_from`; `effective_to` vor `effective_from`; Rolle ohne Recht; Update einer publizierten Revision; fremder Scope; veralteter ETag. konkurrierend: zwei parallele Entwürfe mit derselben Revisionsnummer – `UNIQUE(tenant_id,scope_kind,scope_id,revision)` lässt genau einen zu. Ermöglicht E2E-096, E2E-098, PH-AT-018 (Konfigurationsteil).
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-14, WP-M1-19, WP-M1-43, WP-M1-46.
  - extern: WP-M2-21, WP-M2-23 (Dispositionsfall, der seine `config_revision_id` referenziert (Nachweis „historischer Dispositionsfall"))
  - extern: WP-M2-04, WP-M2-07 (`service_area` und `tariff_revision` als Ziel von Bediengebietsbezug und Tarifverknüpfung der Dienstkonfiguration)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Präzedenzauflösung (WP-M1-21); Publikation/Freigabe (WP-M1-22); Tarifrevision `tariff_revision` (M2).

### WP-M1-21 – Präzedenzkette und wirksame Konfiguration
- **Ziel:** Für jeden Parameter und Kontext wird der wirksame Wert aus der Kette globale Sicherheitsgrenze → Mandantenrahmen → Region → Dienst → Produkt → Fahrzeug berechnet; API und Oberfläche zeigen Wert, Herkunft und Vorrangregel.
- **Anforderungen:** `LH-I.7-CFG-005`
- **Liefergegenstände:**
  - Backend: Modul `config` – Resolver (Kontext + Zeitpunkt → wirksamer Wert, Herkunftsrevision, angewandte Vorrangregel); Regel „Fahrzeug-/Profiloverrides nur für dafür freigegebene Parameter", „engere fachliche Limits werden validiert, nicht blind überschrieben" (Kap. 01 §1.6); interner Port für andere Module; Cache mit Policyrevision im Schlüssel.
  - Datenbank: Lesezugriff `config_revision` über Scope-Arten global, Mandant, Region, Dienst, Fahrzeug; Index `(tenant_id, scope_kind, scope_id, effective_from)`.
  - API: `/v1/config/effective` (Parameter, Kontext, optional Zeitpunkt → Wert, Einheit, Herkunft/Präzedenz, Wirksamkeit).
  - Oberfläche: UI-17 Basis – Konfigurationseditor mit Name/Einheit, aktuellem und vorgeschlagenem Wert, Herkunft der wirksamen Regel und Wirksamkeitsdatum (Kap. 06 §6.8).
- **Detailverträge vorab:** OpenAPI `/v1/config/effective`; formale Präzedenzregel je Parameterklasse; Zuordnung der fünf Ebenen aus CFG-005 zu den sechs Stufen aus Kap. 01 §1.6 (OP-22); Liste der je Ebene überschreibbaren Parameter (Spalte `ebene_original` in `kataloge.csv`).
- **Akzeptanzkriterien:**
  1. Bei widersprüchlichen Werten für `max_wait_min` auf Mandanten-, Regions- und Dienstebene zeigt die Oberfläche den wirksamen Wert und benennt die Vorrangregel sowie die Herkunftsrevision. (CFG-005)
  2. `/v1/config/effective` unterscheidet globale, mandantenbezogene, regionale, dienstbezogene und fahrzeugspezifische Vorgaben und liefert die Ebene der Herkunft. (CFG-005)
  3. Ein Fahrzeug-Override für einen dafür nicht freigegebenen Parameter wird abgewiesen. (CFG-005)
  4. Ein Dienstwert, der die globale Sicherheitsgrenze verletzt, wird nie als wirksam geliefert.
  5. Die Auflösung zum Zeitpunkt T berücksichtigt nur Revisionen, deren Intervall T enthält.
  6. Am Fixture aus WP-M1-09 (ein Mandant, zwei Regionen mit je einem Dienst) liefert die Auflösung für beide Gebiete unterschiedliche wirksame Werte derselben Parameter; ein Nutzer wechselt innerhalb eines Logins zwischen beiden und sieht jeweils die Regeln des gewählten Gebiets. (MUL-002, Abschlussnachweis)
- **Tests:** positiv: Auflösung auf jeder Ebene. negativ: nicht freigegebener Override; Kontext eines fremden Mandanten; Parameter unbekannt; Zeitpunkt ohne wirksame Revision; Dienst außerhalb des eigenen `service_scope`. konkurrierend (Datentrennung): Publikation während laufender Auflösungen – jede Antwort ist konsistent zu genau einer Revision. Ermöglicht E2E-096.
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-09, WP-M1-20, WP-M1-43.
  - extern: WP-M3-03 (Fahrzeugstamm als Scope-Ziel für fahrzeugspezifische Vorgaben)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Schreiben/Publizieren (WP-M1-22); Produkt-Ebene, solange kein Produktobjekt existiert (M2).

### WP-M1-22 – Validierung, Vier-Augen-Freigabe, Publikation und Rollback
- **Ziel:** Eine Konfigurationsänderung wirkt erst nach erfolgreicher Validierung, Freigabe durch eine zweite Person und Publikation zum Wirksamkeitsbeginn; widersprüchliche wirksame Versionen sind ausgeschlossen, und ein Rollback erzeugt eine neue Revision, ohne bestehende Zusagen zu verändern.
- **Anforderungen:** `LH-I.7-CFG-006`
- **Liefergegenstände:**
  - Backend: Modul `config` – Befehle validieren, freigeben, publizieren, zurücknehmen; Prüfung „Prüfer ≠ Ersteller"; Sperre des Scopes und Zeitintervallprüfung bei Publikation; Schließen des Vorgängerintervalls (`effective_to`); Rollback als neue Revision mit den Werten einer früheren; bestehende Zusageversionen bleiben gültig (Kap. 05 §5.5). Die Vier-Augen-Prüfung „Prüfer ≠ Ersteller" entsteht als wiederverwendbarer Plattformbaustein, den M3 und M4 für eigene Freigaben nutzen.
  - Datenbank: `config_revision`; Invariante §4.5 „Nicht überlappende aktive Tarif-/Policy-/Dienstversion" (Scope sperren, Intervallprüfung, ggf. Exclusion Constraint – laut §4.5 „noch umzusetzen"); CHECK `published_at IS NULL OR approved_by IS NOT NULL` ergänzt um `approved_by <> created_by`.
  - API: `/v1/config/revisions/{id}/validate`, `/v1/config/revisions/{revision_id}/publish` (`publishConfig`, Scope `config:publish`, `Idempotency-Key`, `If-Match`, `PublishRequest{effective_from,reason,impact_report_id}` → `PublishedRevision`); Freigabeoperation und Rücknahme als neue Revision (Detailvertrag, OP-19).
  - Oberfläche: UI-17 Basis – „Revision freigeben": Validator-Ergebnis, Prüfer, Wirksamkeitsdatum; Zustände „konkurrierende Version", unzulässiger Sicherheitswert blockiert Veröffentlichung, 412-Konflikt.
  - Ereignisse/Outbox: `config.revision_published`, `config.revision_rolled_back`; `audit_event_id` in der Antwort.
  - Konfigurationsparameter: für Bediengebiet, Betriebszeiten, `max_wait_min` und Haltestellenschema-Einstellungen des Dienstes.
- **Detailverträge vorab:** Zustandsautomat der Revision (Entwurf, validiert, freigegeben, publiziert, abgelöst); OpenAPI `/validate`, Freigabe, Rollback; Exclusion-Constraint-DDL; Rolle „qualifizierter Betreiber" in der Rechtematrix; Regel, ob `effective_from` in der Vergangenheit zulässig ist (im Pflichtenheft nicht geregelt, OP-19).
- **Akzeptanzkriterien:**
  1. Eine Teständerung an Betriebszeiten und `max_wait_min` eines Dienstes ist vor erfolgreicher Validierung nicht publizierbar und in `/v1/config/effective` nicht wirksam; nach Validierung, Freigabe und Publikation wirkt sie ab `effective_from`. (CFG-006)
  2. Nur Rollen mit `config:publish` (qualifizierte Betreiber) können publizieren; der Ersteller kann seine eigene Revision nicht freigeben. (CFG-006)
  3. Eine am Sonntag publizierte Änderung von `min_booking_lead_min` mit Wirksamkeit ab Montag lässt die für Sonntag aufgelösten Werte unverändert; Auflösungen ab Montag liefern den neuen Wert. (CFG-006)
  4. Zwei Revisionen desselben Scopes mit überlappendem Wirksamkeitsintervall können nicht gleichzeitig publiziert sein.
  5. Rollback einer fehlerhaften publizierten Revision erzeugt eine neue Revision mit den früheren Werten; die fehlerhafte Revision bleibt lesbar, ein Testobjekt mit gebundener Revisionsreferenz zeigt weiter seine ursprüngliche Version; das Audit enthält beide Schritte.
  6. Publikation ohne `If-Match` ergibt 428, mit veralteter Revision 412, Wiederholung mit gleichem `Idempotency-Key` dasselbe Ergebnis.
  7. Validierung, Freigabe, Publikation und Rollback erzeugen je einen Auditeintrag mit Akteur, Zeitpunkt und Grund; die Antwort der Publikation nennt `audit_event_id`. (CFG-006)
  8. Ein unzulässiger Sicherheitswert blockiert die Veröffentlichung in API und Oberfläche; die Oberfläche zeigt Validator-Ergebnis, Prüfer und Wirksamkeitsdatum auch per Tastatur und Screenreader bedienbar an.
- **Tests:** positiv: Entwurf → validieren → freigeben → publizieren → wirksam; Rollback. negativ: Publikation ohne Validierung, ohne Freigabe, Selbstfreigabe, ungültiger Wert, fehlender Wirkungsbericht, fehlender Scope. konkurrierend (Sicherheit/Datentrennung): zwei Publikationen desselben Scopes mit überlappendem Intervall in parallelen Transaktionen – genau eine erfolgreich (Pflicht-Negativtest zu §4.5); paralleler Publish und Rollback; Auflösung genau zum Zeitpunkt `effective_from` liefert trotz Cache nie den abgelösten Wert. Besteht E2E-096, E2E-097 (Publikationsteil), E2E-098 (mit Konfigurationsrevision; Tarifversion folgt M2), PH-AT-018 (Konfigurationsteil).
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-13, WP-M1-20, WP-M1-21, WP-M1-23, WP-M1-43, WP-M1-46.
  - extern: WP-M2-09, WP-M2-21, WP-M2-23 (Angebot/Buchung mit gebundener `config_revision_id` (`fare_quote.config_revision_id`) für den Nachweis „Sonntagsfahrten unverändert" und „Buchungszusage bleibt korrekt")
  - extern: WP-M2-04, WP-M2-05 (`service_area`, `stop_revision` als administrierbare Gebiets-/Haltestellenobjekte)
- **Entscheidungen:** keine
- **Größe:** L
- **Nicht im Umfang:** Geo-Editor für Bediengebiete und Haltepunkte (M2, UI-17 Vollausbau); Tariffreigabe (M2); Wirkungsbericht-Inhalt (WP-M1-23).

### WP-M1-23 – Wirkungsanalyse vor Publikation
- **Ziel:** Vor dem Speichern/Publizieren wichtiger Konfigurationen wird geprüft, welche bestehenden Fahrten und Schichten betroffen wären; das Ergebnis ist ein gespeicherter Wirkungsbericht, auf den die Publikation verweist.
- **Anforderungen:** `LH-I.7-CFG-007`
- **Liefergegenstände:**
  - Backend: Modul `config` – Wirkungsanalyse-Dienst mit Provider-Port je Fachmodul (`ImpactProvider`: Revision → betroffene Objekte, Schweregrad); persistierter Wirkungsbericht mit ID, Revisionsbezug und Hash der geprüften Werte; Regel „Bericht muss zu unveränderter Revision passen" (`openapi.yaml`, `publishConfig`).
  - Datenbank: neue Tabelle für Wirkungsberichte (im Pflichtenheft nur als `impact_report_id` referenziert, OP-19) mit `(tenant_id,id)`, RLS, Revisionsbezug.
  - API: `/v1/config/revisions/{id}/impact` (Analyse auslösen/lesen; bei großem Umfang asynchron über `operation`).
  - Oberfläche: UI-17 Basis – Vorschau „betroffene zukünftige Angebote", bestätigte Zusagen als „unverändert gebundene Version" (Kap. 06 §6.8); Konfliktliste.
  - Konfigurationsparameter: Kennzeichen „wichtig/impact-pflichtig" im Parameterschema (z. B. `pickup_window_min`: „bei Änderung bereits bestätigte Buchungen prüfen").
- **Detailverträge vorab:** OpenAPI `/impact` und Schema des Wirkungsberichts; Provider-Schnittstelle; DDL; Liste der impact-pflichtigen Konfigurationen (OP-19).
- **Akzeptanzkriterien:**
  1. Eine Revision, die ein Bediengebiet verkleinert, löst eine Konfliktprüfung aus; der Wirkungsbericht listet die vom Provider gemeldeten betroffenen Buchungen und Schichten; in M1 mit Test-Provider, mit M2/M4 mit echten Buchungen/Schichten. (CFG-007)
  2. Ohne Wirkungsbericht oder mit einem Bericht zu einem anderen Revisionsstand wird die Publikation abgewiesen. (CFG-007)
  3. Der Bericht ist nachträglich über seine ID abrufbar und im Audit der Publikation referenziert.
  4. Wirkungsberichte sind mandantengetrennt; die Analyse ist nur für Rollen mit Konfigurationsrecht auslösbar und enthält keine Personendaten betroffener Fahrgäste oder Beschäftigter, nur Objektverweise und Anzahl.
- **Tests:** positiv: Bericht ohne Konflikte, Bericht mit Konflikten. negativ: Publikation mit fremdem/veraltetem Bericht; Bericht eines anderen Mandanten. konkurrierend (Kapazität/Sicherheit): Revision wird nach Berichtserstellung geändert – Publikation scheitert; neue betroffene Objekte zwischen Bericht und Publikation werden über die Revisionsbindung erkannt (Regel im Detailvertrag).
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-15, WP-M1-20, WP-M1-43, WP-M1-46.
  - extern: WP-M2-04, WP-M2-23 (ImpactProvider für Buchungen und Bediengebiete)
  - extern: WP-M4-17 (ImpactProvider für Schichten)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** vollständiger Wirkungsbericht über alle Parameter (M8: „restliche Parameter und Wirkungsbericht"); automatische Umplanung betroffener Fahrten.

### WP-M1-24 – Unternehmensprofil des Mandanten
- **Ziel:** Ein Mandantenadministrator pflegt selbstständig das Unternehmensprofil mit Kontakt, Abrechnungsangaben, Betriebsart und Rechtsinformationen einschließlich Standardwährung.
- **Anforderungen:** `LH-I.7-CFG-001`
- **Liefergegenstände:**
  - Backend: Modul `config`/`identity` – Aggregat Unternehmensprofil je Mandant mit Revision; Validierung der Währung gegen `currency`.
  - Datenbank: `tenant`, `legal_entity`, `currency`; Profilfelder als versionierte Konfiguration mit `scope_kind` Mandant in `config_revision` oder als eigene Profiltabelle (im Pflichtenheft nicht festgelegt, OP-27).
  - API: Profil lesen/ändern (Detailvertrag; kein Pfad in Kap. 05 §5.7).
  - Oberfläche: UI-18 Basis – Formular Unternehmensprofil; Zustände fehlendes Recht, 412-Konflikt.
  - Ereignisse/Outbox: `tenant.profile_changed`; Audit über WP-M1-46.
- **Detailverträge vorab:** Feldliste des Profils (Kontakt, Abrechnung, Betriebsart, Rechtsinformationen, Standardwährung) mit Pflichtfeldern; OpenAPI; DDL.
- **Akzeptanzkriterien:**
  1. Ein Administrator des Mandanten ändert Kontakt und Standardwährung über die Oberfläche; die Änderung ist ohne Mitwirkung einer Plattformbetreiber-Rolle wirksam und nach erneutem Laden sichtbar. (CFG-001)
  2. Eine nicht im Währungskatalog enthaltene Währung wird mit Feldfehler abgewiesen. (CFG-001)
  3. Die Änderung erscheint im Audit mit Akteur und Zeitpunkt; Mandant B bleibt unverändert.
- **Tests:** positiv: Kontakt und Währung ändern. negativ: Nicht-Administrator; ungültige Währung; Profil eines fremden Mandanten; veralteter ETag. konkurrierend (Datentrennung): zwei parallele Änderungen – eine erhält 412.
- **Abhängigkeiten:** intern: WP-M1-05, WP-M1-07, WP-M1-14, WP-M1-43, WP-M1-46.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Rechnungsstellung und Kontenrahmen (M4, D-06); Marken-/Designanpassung der Fahrgastoberfläche.

### WP-M1-25 – Dienstkonfiguration und Angebotsmodelle
- **Ziel:** Für einen Dienst lässt sich eines der Angebotsmodelle Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr oder Bahnhofszubringer mit seinen Modellregeln konfigurieren und als Revision publizieren.
- **Anforderungen:** `LH-6.1-SYS-003`
- **Liefergegenstände:**
  - Backend: Modul `service-geo` – Dienstkonfiguration mit Angebotsmodell als Bestandteil der Dienstrevision; Modellregel-Validator (welche Parameter je Modell Pflicht/unzulässig sind); Bereitstellung der wirksamen Modellregeln über den Konfigurations-Port.
  - Datenbank: `service` (`config_revision_id`), `config_revision` mit `scope_kind` Dienst; JSON-Schema „Dienstkonfiguration" in `policy_schema`.
  - API: `/v1/services` (Dienst mit Modell und Konfigurationsrevision lesen/anlegen).
  - Oberfläche: UI-17 Basis – Dienst anlegen, Angebotsmodell wählen, modellabhängige Felder.
  - Konfigurationsparameter: `pooling_allowed`, `max_detour_pct`, `stop_walk_max_m`, `transfer_min_time_min`, `advance_arrival_buffer_min` als modellabhängige Werte.
- **Detailverträge vorab:** JSON-Schema der Dienstkonfiguration mit Enum der drei Modelle; Modellregeln je Angebotsmodell (das Pflichtenheft nennt die Modelle, beschreibt ihre Regeln aber nicht, OP-28); OpenAPI `/v1/services`.
- **Akzeptanzkriterien:**
  1. Für jedes der drei Angebotsmodelle lässt sich ein Dienst konfigurieren, validieren und publizieren; die wirksame Konfiguration liefert Modell und Modellregeln. (SYS-003)
  2. Eine Konfiguration, die gegen die Modellregeln verstößt (Pflichtangabe fehlt oder modellfremder Parameter gesetzt), wird bei der Validierung abgewiesen. (SYS-003)
  3. Mit M2 wird je Modell eine Testfahrt gemäß Modellregeln erfolgreich gebucht; M1 liefert dafür je Modell eine publizierte Fixture-Konfiguration. (SYS-003)
- **Tests:** positiv: drei Dienste, je ein Modell. negativ: unbekanntes Modell; modellwidrige Parameterkombination; Dienst mit Betreiber eines fremden Mandanten. konkurrierend (Datentrennung): zwei parallele Publikationen der Dienstkonfiguration desselben Dienstes – genau eine wirksam (Mechanismus aus WP-M1-22).
- **Abhängigkeiten:** intern: WP-M1-09, WP-M1-22, WP-M1-43.
  - extern: WP-M2-21, WP-M2-23 (Angebotsbildung und Buchung je Angebotsmodell (Testfahrt))
  - extern: WP-M2-04, WP-M2-05, WP-M2-07 (`service_area`, `stop_revision`, Tarifverknüpfung)
- **Entscheidungen:** D-05 (Pilotdienste und Genehmigungsmodell) – blockiert nur die Produktivkonfiguration.
- **Größe:** M
- **Nicht im Umfang:** Disposition, Routing und Buchung (M2); Tarifregeln (M2).

### WP-M1-26 – Serverseitige Regelprüfung (Policy-Port)
- **Ziel:** Jeder bestätigende Befehl löst die geltenden Angebots- und Betriebsregeln serverseitig aus der wirksamen Konfiguration auf; vom Client gelieferte Regel-, Preis- oder Grenzwerte werden nie als Entscheidungsgrundlage verwendet.
- **Anforderungen:** `LH-6.1-SYS-011`
- **Liefergegenstände:**
  - Backend: `backend/platform` – `PolicyCheck`-Port („prüfe Befehl X im Kontext Y gegen wirksame Regeln zum Zeitpunkt T"), Pflichtaufruf vor Commit per Architekturtest für alle als „bestätigend" markierten Befehle; Ergebnis mit Regelrevision für Audit und Snapshot.
  - API: Konvention und Lint für Request-Schemas: `additionalProperties:false`, keine clientseitig setzbaren Regel-/Preis-/Statusfelder; unbekannte Felder → 422.
  - Datenbank: keine neuen Tabellen; Regelrevision wird im bestätigten Objekt referenziert (z. B. `fare_quote.config_revision_id`).
- **Detailverträge vorab:** Liste der regelrelevanten Felder, die in keinem Request-DTO vorkommen dürfen; Port-Signatur; Fehlercodes für Regelverletzung.
- **Akzeptanzkriterien:**
  1. Ein Testbefehl mit manipulierten Client-Parametern (zusätzliches Feld mit eigener Wartezeit/eigenem Preis, überschriebene Revisions-ID, verändertes Zeitfenster außerhalb der Regel) wird abgewiesen oder die Parameter bleiben wirkungslos; die Entscheidung beruht nachweislich auf der serverseitig aufgelösten Revision. (SYS-011)
  2. Ein bestätigender Befehl ohne `PolicyCheck`-Aufruf lässt den Architekturtest scheitern. (SYS-011)
  3. Die verwendete Regelrevision ist im Ergebnis und im Audit referenziert.
- **Tests:** positiv: regelkonformer Befehl. negativ: zusätzliche Felder, manipulierte Regelwerte, veraltete Revisionsreferenz, Regelwechsel zwischen Angebot und Bestätigung. konkurrierend (Geld/Kapazität): Publikation einer strengeren Regel parallel zur Bestätigung – Entscheidung konsistent zu genau einer Revision.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-21, WP-M1-46.
  - extern: WP-M2-09, WP-M2-23 (Buchungsbestätigung und Tarifprüfung als Anwender des Ports (Nachweis „operative oder tarifliche Regel"))
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** die fachlichen Regeln selbst (Gebiet, Zeit, Kapazität, Tarif – M2).

### WP-M1-27 – Revisionsfeste Zusage und kontrollierte Änderungsverfahren
- **Ziel:** Es gibt einen Plattformbaustein für verbindliche Zusagen: unveränderlicher Kernkonditionen-Snapshot mit Hash und Revision, Änderungen nur über ein kontrolliertes Verfahren, das Audit-Ereignis und – soweit nötig – einen Fahrgastinformationsauftrag erzeugt.
- **Anforderungen:** `LH-6.1-SYS-005`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Typ „gebundener Snapshot" (Inhalt, JSON-Schema-Version, Hash, Revision, referenzierte Konfigurations-/Tarifrevision); Änderungsverfahren als Befehl mit Grund, Akteur, alter/neuer Revision; Entscheidung „Fahrgastinformation nötig" als Regel; Übergabe an den Benachrichtigungs-Port.
  - Datenbank: Muster für `booking.promise_snapshot`/`booking.revision` und `journey_leg.promise_revision`; Unveränderlichkeit analog Trigger `quote_immutable` auf `fare_quote`; Invariante §4.5 „Gegenereignis statt Änderung".
  - Ereignisse/Outbox: `promise.changed` mit alter/neuer Revision und Kennzeichen „Fahrgastinformation erforderlich"; Auditeintrag über WP-M1-12.
- **Detailverträge vorab:** JSON-Schema des Zusage-Snapshots (welche Felder sind „Kernkonditionen" – im Pflichtenheft nicht abschließend aufgezählt, OP-01/OP-29); Katalog zulässiger Änderungsverfahren; Regel, wann eine Fahrgastinformation nötig ist; Ereignisschema.
- **Akzeptanzkriterien:**
  1. Ein gebundener Snapshot kann nach Bestätigung nicht per UPDATE verändert werden; eine Änderung erzeugt eine neue Revision und lässt die alte lesbar. (SYS-005)
  2. Jede Änderung einer Zusage über das Änderungsverfahren erzeugt genau ein Audit-Ereignis mit Akteur, Grund, alter und neuer Revision. (SYS-005)
  3. Ist die Änderung fahrgastrelevant, entsteht genau ein Outbox-Ereignis mit Informationsauftrag; bei nicht relevanter Änderung keiner. (SYS-005)
  4. Eine Konfigurationsänderung verändert bestehende Snapshots nicht.
- **Tests:** positiv: Änderung mit und ohne Informationspflicht. negativ: direktes Update; Änderung ohne Grund; Änderung durch unberechtigte Rolle. konkurrierend (Geld/Kapazität): zwei gleichzeitige Änderungen derselben Zusage – eine erhält 412, genau ein Audit- und ein Outbox-Eintrag.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-14, WP-M1-17, WP-M1-46.
  - extern: WP-M2-23 (Buchung mit `promise_snapshot` als fachlicher Träger der Zusage)
  - extern: WP-M2-32 (Betriebsnachrichten (Push/SMS/E-Mail) für die Fahrgastinformation)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalt und Berechnung der Zusage (Zeitfenster, Preis – M2); Zustellung der Nachricht (M2); Umplanung durch Leitstelle (M3).

### WP-M1-28 – Fahrgastregistrierung mit Kontaktverifikation
- **Ziel:** Fahrgäste registrieren sich per E-Mail oder Telefon; Kontaktdaten gelten erst nach erfolgreicher Verifikation als verifiziert, und nur verifizierte Kontakte sind für sicherheitsrelevante Kontowiederherstellung nutzbar.
- **Anforderungen:** `LH-6.2-IAM-001`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Registrierungsfluss über den IdP (Self-Registration, Verifikationsaufgabe), Anlage des Fahrgastkontos im Mandanten-/Vertriebskontext, Verifikationsstatus je Kontaktkanal; Wiederherstellungsregel „nur verifizierter Kanal".
  - Datenbank: `passenger_private` (`subject_token`, `contact_ciphertext`, `retention_until`); Migration: Verifikationsstatus und -zeitpunkt je Kanal (im Kern-DDL nicht enthalten, OP-23).
  - API: `/v1/passengers` (Konto anlegen/lesen), `/v1/me` (Verifikationsstatus).
  - Oberfläche: UI-05 Basis – Registrierung, Kontakt verifizieren; Zustand „Verifikation fehlt".
  - Adapter/Worker: Versand des Verifikationscodes per E-Mail über den IdP; Telefon-/SMS-Kanal über Adapter `notification` (OP-23).
- **Detailverträge vorab:** OpenAPI `/v1/passengers`; Verifikationsverfahren je Kanal (Codegültigkeit, Versuchslimit – im Pflichtenheft nicht beziffert); DDL-Ergänzung; Verschlüsselungs-/Schlüsselvertrag für `contact_ciphertext`.
- **Akzeptanzkriterien:**
  1. Registrierung per E-Mail und per Telefonnummer ist möglich; nach Abschluss der Verifikation ist der Kanal als verifiziert markiert. Der Telefonkanal wird in M1 gegen einen Test-SMS-Adapter nachgewiesen, der produktive SMS-Versand folgt mit M2. (IAM-001)
  2. Eine Kontowiederherstellung über einen nicht verifizierten Kontakt wird abgelehnt; die Antwort verrät nicht, ob ein Konto existiert. (IAM-001)
  3. Ein geänderter Kontakt ist bis zur erneuten Verifikation unverifiziert und nicht für Wiederherstellung nutzbar. (IAM-001)
  4. Verifikationsversuche sind rate-limitiert und werden als Sicherheitsereignis protokolliert.
  5. Verifikationscodes sind einmalig, zeitlich begrenzt und werden weder im Klartext gespeichert noch geloggt; Kontaktdaten liegen ausschließlich verschlüsselt in `passenger_private.contact_ciphertext`.
- **Tests:** positiv: Registrierung beider Kanäle. negativ: falscher/abgelaufener Code; Wiederherstellung über unverifizierten Kanal; Konto-Enumeration; Brute-Force auf Code. konkurrierend (Sicherheit): zwei parallele Registrierungen mit demselben Kontakt – genau ein Konto (`UNIQUE(tenant_id,subject_token)`).
- **Abhängigkeiten:** intern: WP-M1-04, WP-M1-06, WP-M1-10, WP-M1-31, WP-M1-43, WP-M1-46.
  - extern: WP-M2-32 (SMS-Zustellkanal (Betriebsnachrichten/Adapter `notification`) für Telefonverifikation)
  - extern: WP-M2-35, WP-M2-36 (Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34))
- **Entscheidungen:** D-11 (Löschfristen für Kontaktdaten) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Vertretung/Delegation (`/v1/delegations`, M7); Callcenter-Anlage von Fahrgästen (UI-06, M7); Passenger-Suche und Buchung (M2).

### WP-M1-29 – Fahrgastprofil und Präferenzen
- **Ziel:** Fahrgäste verwalten Kontaktdaten, Sprache, Favoriten, Benachrichtigungskanäle und bevorzugte Haltestellen; Änderungen sind nach erneuter Anmeldung konsistent abrufbar.
- **Anforderungen:** `LH-6.2-IAM-006`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Aggregat Fahrgastprofil mit Revision/ETag; Trennung Kontakt (verschlüsselt) von Präferenzen.
  - Datenbank: `passenger_private`, `passenger_preference` (Kap. 04 §4.4 IAM/PAX/SUP: Passenger 1:n Präferenz) mit `(tenant_id,id)`, RLS, Revision.
  - API: `/v1/me` (eigenes Profil lesen/ändern), `/v1/passengers` (berechtigte Betreibersicht, minimiert).
  - Oberfläche: UI-05 Basis – Konto: Kontakt, Sprache, Benachrichtigungskanäle, Favoriten, bevorzugte Haltestellen; Zustände „Verifikation fehlt", „Löschstatus"; Einbettung der Auswahl des Unterstützungsbedarfs aus WP-M1-31.
- **Detailverträge vorab:** OpenAPI Profil-Schema; DDL `passenger_preference`; Darstellung von Favoriten/bevorzugten Haltestellen vor Vorhandensein des Haltestellenstamms (OP-30).
- **Akzeptanzkriterien:**
  1. Änderungen an Kontaktdaten, Sprache, Favoriten, Benachrichtigungskanälen und bevorzugten Haltestellen sind nach Abmeldung und erneuter Anmeldung unverändert abrufbar, auch von einem zweiten Gerät. (IAM-006)
  2. Ein Fahrgast liest und ändert ausschließlich sein eigenes Profil; fremde Profil-ID ergibt 404. (IAM-006)
  3. Gleichzeitige Änderungen von zwei Geräten führen nicht zu stillem Überschreiben (412).
  4. Die berechtigte Betreibersicht auf `/v1/passengers` liefert nur die minimierten Felder; Kontakt und Unterstützungsmerkmale erscheinen dort nicht ohne Feldrecht und Zweck (WP-M1-31).
- **Tests:** positiv: alle fünf Profilbereiche ändern und neu laden. negativ: fremdes Profil; ungültige Sprache; nicht existierende Haltestellenreferenz; veralteter ETag. konkurrierend (Datentrennung): parallele Änderung von zwei Sitzungen.
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-28, WP-M1-31, WP-M1-43.
  - extern: WP-M2-05 (Haltepunktstamm (`stop_revision`) für bevorzugte Haltestellen und Ortsfavoriten)
  - extern: WP-M2-35, WP-M2-36 (Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Vertreter in UI-05 (M7); Unterstützungsmerkmale (WP-M1-31); Marketingeinwilligung (WP-M1-30).

### WP-M1-30 – Datenschutzeinstellungen: Betriebsmitteilung getrennt von Marketing
- **Ziel:** Die Einwilligung in freiwillige Marketingkommunikation wird versioniert und getrennt von betrieblichen Mitteilungen geführt; eine Ablehnung von Marketing blockiert keine sicherheitsrelevante Fahrtinformation.
- **Anforderungen:** `LH-6.2-IAM-011`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Einwilligungsrevisionen je Zweck; Port `isDeliveryAllowed(passenger, purpose)` mit festen Zweckklassen (betrieblich/sicherheitsrelevant vs. Marketing), den der Benachrichtigungsdienst aufruft.
  - Datenbank: `consent_revision` (Kap. 04 §4.4 IAM/PAX/SUP) mit `(tenant_id,id)`, RLS, append-only.
  - API: `/v1/me` (Datenschutzeinstellungen lesen/ändern).
  - Oberfläche: UI-05 Basis – getrennte Schalter; Pflichtzustimmung wird nie mit Marketingeinwilligung kombiniert (Kap. 06 §6.3).
  - Konfigurationsparameter: `notification_quiet_hours` (Sicherheitsalarme ausgenommen) als angrenzende Regel.
- **Detailverträge vorab:** Zweck-Enum der Nachrichtenklassen; DDL `consent_revision`; OpenAPI; Port-Vertrag für den Benachrichtigungsdienst.
- **Akzeptanzkriterien:**
  1. Bei abgelehntem Marketing liefert der Zustellport für eine sicherheitsrelevante Fahrtinformation „erlaubt" und für eine Marketingnachricht „nicht erlaubt"; der Nachweis mit echter Zustellung folgt mit M2. (IAM-011)
  2. Einwilligung und Widerruf sind mit Zeitpunkt und Textversion als eigene Revision gespeichert; frühere Revisionen bleiben lesbar. (IAM-011)
  3. Marketing ist standardmäßig nicht eingewilligt.
- **Tests:** positiv: Einwilligen, Widerrufen. negativ: Marketingversand ohne Einwilligung; Änderung fremder Einwilligung; nachträgliches Ändern einer Revision. konkurrierend (Datentrennung): Widerruf parallel zur Zustellprüfung – nach Commit keine Marketingfreigabe.
- **Abhängigkeiten:** intern: WP-M1-29, WP-M1-43.
  - extern: WP-M2-32 (Betriebsnachrichten mit Zustellversuchen (`notification`, `notification_attempt`) als Anwender des Ports)
- **Entscheidungen:** D-11 (Datenrechte) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Nachrichtenversand, Vorlagen, Massenkommunikation (M2, M7); Auskunfts-/Löschanfragen (`/v1/privacy/requests`, M8).

### WP-M1-31 – Feldschutz für Mobilitäts- und Unterstützungsmerkmale
- **Ziel:** Sensible Profilmerkmale (Mobilitäts-/Unterstützungsbedarf, Kontakt) liegen verschlüsselt in eigenen Tabellen mit eigenen Modulzugängen; nur an der Beförderung beteiligte, berechtigte Rollen erhalten sie zweckgebunden und minimiert.
- **Anforderungen:** `LH-6.18-SEC-005`
- **Liefergegenstände:**
  - Backend: `backend/platform` – Feldschutzbaustein (Klassifizierung PII/HR/Finanzen/Unterstützung, DTO-Trennung, Zweckprüfung, feldbezogene Rechte); Envelope-Verschlüsselung mit Schlüsselreferenz aus der Secret-Verwaltung; Regel „keine Diagnosen", „erforderliche Unterstützung ohne Diagnose" (Kap. 02 §3).
  - Datenbank: `passenger_private.support_ciphertext`, `passenger_private.contact_ciphertext`; eigener Modulzugang und explizite Spalten-/Viewrechte (Kap. 04 §4.6); keine sensiblen Spalten in allgemeinen Aggregattabellen (Schema-Lint).
  - API: getrennte Schemas für sensible Felder; Standardantworten enthalten sie nicht; Zugriff nur mit Zweck und Feldscope.
  - Oberfläche: UI-05 Basis – Komponente „Unterstützungsbedarf" als transportrelevante Auswahl ohne Diagnoseabfrage (Einbettung in die Kontoseite durch WP-M1-29); UI-20 Basis – Zugriff mit Zweckangabe.
  - Ereignisse/Outbox: Lesezugriffe auf sensible Felder als Auditeintrag (WP-M1-46); keine sensiblen Felder in Ereignis-Payloads oder Logs.
- **Detailverträge vorab:** Feldklassifikation je Tabelle/Spalte (gemeinsam mit WP-M1-32); Rollen×Feld-Matrix; Schlüsselverwaltung und Rotation; Katalog der transportrelevanten Unterstützungsmerkmale (im Pflichtenheft nicht aufgezählt, OP-24).
- **Akzeptanzkriterien:**
  1. Eine unbeteiligte Person (anderer Fahrgast, Mitarbeiter ohne Feldrecht, Tenantadmin ohne gesonderte Zuweisung, Fremdmandant) erhält über keinen M1-Endpunkt das Unterstützungsmerkmal eines Fahrgasts; die Antwort enthält das Feld nicht bzw. ist 404. (SEC-005)
  2. Eine berechtigte Rolle erhält das Merkmal nur mit Zweck; der Zugriff ist im Audit mit Akteur und Zweck nachweisbar. (SEC-005)
  3. Unter der allgemeinen Runtime-Rolle ist `support_ciphertext` nicht selektierbar; nur der Modulzugang liest die Spalte, und sie ist im Ruhezustand verschlüsselt.
  4. Logs, Metriken und Outbox-Payloads enthalten das Merkmal nicht (automatisierter Scan der Testläufe).
- **Tests:** positiv: berechtigter Zugriff mit Zweck. negativ: jede Rolle der Rechtematrix ohne Feldrecht; fehlender Zweck; Fremdmandant; direkter SQL-Zugriff mit allgemeiner Rolle. konkurrierend (Sicherheit): Entzug des Feldrechts parallel zum Lesen – nach Commit kein Zugriff. Besteht AT-49 (Merkmalsteil).
- **Abhängigkeiten:** intern: WP-M1-01, WP-M1-07, WP-M1-36, WP-M1-43, WP-M1-46.
  - extern: WP-M2-23 (minimierter Betreiberpayload der Buchung als fachlicher Anwender)
  - extern: WP-M4-06, WP-M4-08 (Arbeitszeit- und Abwesenheitsdaten für den vollständigen AT-49)
- **Entscheidungen:** D-11 (Zweck, Rechtsgrundlage, Löschfrist) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** HR-/Gesundheitsdaten des Personals (M4); Schadensfotos (M3); paarweise Partner-Aliasse (M6).

### WP-M1-32 – Dateninventar mit Zweck und Rechtsgrundlage
- **Ziel:** Jedes personen- oder standortbezogene Feld des Schemas ist in einem maschinenlesbaren Dateninventar mit Zweck, Rechtsgrundlage, Schutzklasse und Löschklasse verzeichnet; ein Feld ohne Eintrag bricht den Build.
- **Anforderungen:** `LH-6.18-SEC-004`
- **Liefergegenstände:**
  - Backend: CI-Prüfung „Schema ↔ Inventar": jede Spalte jeder Tabelle ist klassifiziert (personenbezogen ja/nein; wenn ja: Zweck, Rechtsgrundlage, Schutzklasse, Löschklasse).
  - Datenbank: Inventardatei im Repository je Migration gepflegt; Löschklasse je Aggregattabelle gemäß Kap. 04 §4.1.
  - Oberfläche: UI-20 Basis – lesbarer Export des Inventars für Datenschutz.
- **Detailverträge vorab:** Format des Inventars; Katalog zulässiger Zwecke und Rechtsgrundlagen (fachlich/rechtlich vom Auftraggeber freizugeben, OP-24); Schutzklassen gemäß Kap. 01 §1.7 (Kontakt/Unterstützung, Standort/Driver-ETA, Belege/Journal, Security-Audit, aggregierte Statistik).
- **Akzeptanzkriterien:**
  1. Das Dateninventar ordnet jedem Feld aller in M1 migrierten Tabellen einen Zweck und – bei Personen-/Standortbezug – eine Rechtsgrundlage zu; die CI-Prüfung meldet null unklassifizierte Felder. (SEC-004)
  2. Eine Migration, die ein neues Feld ohne Inventareintrag einführt, scheitert in CI. (SEC-004)
  3. Felder ohne nachgewiesenen Zweck (z. B. `travel_attempt.coarse_origin` in feinerer als grober Auflösung) werden nicht angelegt; Abweichungen sind als Befund dokumentiert.
- **Tests:** positiv: vollständiger Inventarabgleich. negativ: Testmigration mit unklassifizierter Spalte; Eintrag mit unbekanntem Zweck. konkurrierend: nicht einschlägig (Build-Prüfung).
- **Abhängigkeiten:** intern: WP-M1-01, WP-M1-43.
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss, Rechtsgrundlagen) – blockiert die Produktivfreigabe, nicht den Bau; D-10 für Fahrer-/Standortfelder (ab M4).
- **Größe:** S
- **Nicht im Umfang:** Inventareinträge für Tabellen späterer Meilensteine (dort Pflicht über dieselbe CI-Prüfung); Verarbeitungsverzeichnis als Rechtsdokument.

### WP-M1-33 – Aufbewahrungs-, Archivierungs- und Löschregeln
- **Ziel:** Für personenbezogene Daten gibt es freigebbare Aufbewahrungsregeln je Datenklasse und einen Löschlauf, der einen Testdatensatz nach freigegebener Regel entfernt oder anonymisiert und das nachweist.
- **Anforderungen:** `LH-6.18-SEC-007`
- **Liefergegenstände:**
  - Backend: Modul `identity`/`platform` – Regelverwaltung (Datenklasse, Frist, Aktion löschen/anonymisieren/archivieren, Freigabestatus); Löschlauf als fortsetzbarer Job über `operation`; Trennung aufbewahrungspflichtiger Bezüge von entbehrlicher PII (Kap. 01 §1.7); Lösch-/Sperrjournal für Restore.
  - Datenbank: `retention_rule`, `erasure_job` (Kap. 04 §4.4 CFG/SEC/INT); Nutzung `passenger_private.retention_until`; kontrollierter Lifecycleprozess für unveränderliche Tabellen (§4.5).
  - API: Regelverwaltung und Jobstatus (Detailvertrag; kein Pfad in Kap. 05 §5.7).
  - Oberfläche: UI-20 Basis – Regelübersicht und Löschprotokoll.
  - Konfigurationsparameter: `invoice_archive_years` als angrenzender Parameter (Schema folgt M4).
  - Adapter/Worker: Löschworker für DB und Objektspeicher.
- **Detailverträge vorab:** DDL `retention_rule`, `erasure_job`; Löschmatrix Datenklasse × Frist × Aktion (Fristen im Pflichtenheft nicht festgelegt, D-11, OP-25); Anonymisierungsverfahren je Tabelle; Abgrenzung zu M8.
- **Akzeptanzkriterien:**
  1. Ein Testdatensatz (Fahrgastkonto mit Kontakt- und Unterstützungsdaten) wird nach Ablauf der freigegebenen Test-Regel durch den Löschlauf entfernt oder anonymisiert; danach liefert keine API und keine SQL-Abfrage die Personendaten. (SEC-007)
  2. Für jede im Dateninventar als personenbezogen markierte Datenklasse existiert eine Aufbewahrungs-, Archivierungs- oder Löschregel; eine fehlende Regel wird in CI gemeldet. (SEC-007)
  3. Eine nicht freigegebene Regel wird vom Löschlauf nicht angewendet; der Lauf ist nach Abbruch fortsetzbar und protokolliert jedes betroffene Objekt ohne Personendaten.
  4. Datensätze des Mandanten B werden von einem Lauf im Kontext A nicht berührt.
  5. Eine Archivierungsregel überführt den Testdatensatz in den Archivzustand: er ist für operative Rollen nicht mehr lesbar, bleibt aber bis Fristende für die berechtigte Rolle abrufbar. (SEC-007)
  6. Das Lösch-/Sperrjournal wird fortgeschrieben, sodass ein Restore (WP-M1-39) gelöschte Datensätze nicht wiederbelebt; Regeländerungen und Löschläufe sind auditiert.
- **Tests:** positiv: Löschung, Anonymisierung und Archivierung nach Regel; Löschung des zugehörigen Objekts im Objektspeicher. negativ: Regel nicht freigegeben; Datensatz mit noch laufender Frist; Lauf für fremden Mandanten. konkurrierend (Datentrennung): Löschlauf parallel zu Profiländerung desselben Datensatzes – konsistentes Ergebnis ohne wiederauflebende PII.
- **Abhängigkeiten:** intern: WP-M1-07, WP-M1-11, WP-M1-15, WP-M1-31, WP-M1-32, WP-M1-43, WP-M1-46.
- **Entscheidungen:** D-11 – Bau mit Testfristen möglich; Produktivschaltung personenbezogener Daten blockiert, bis die Löschmatrix beschlossen ist.
- **Größe:** M
- **Nicht im Umfang:** Auskunfts-/Löschanfragen Betroffener und Legal Hold (`/v1/privacy/requests`, M8); Löschung in Suchindex und Partnerprojektion (M6/M8); Finanzarchiv (M4).

### WP-M1-34 – Fahrzeugrollen mit Gültigkeitsintervallen
- **Ziel:** Einem Fahrzeug lassen sich Eigentümer, Betreiber, Einsatzleister und Abrechnungspartner als unabhängige, zeitlich gültige Rollen zuordnen; ein Eigentümerwechsel wirkt ab Wirksamkeitsdatum und verändert keine historischen Zuordnungen.
- **Anforderungen:** `LH-I.4-MUL-004`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` (Stammteil) – Rollenzuordnung Fahrzeug ↔ Organisation mit halboffenem Gültigkeitsintervall; As-of-Auflösung; Snapshot-Funktion „Rollen zum Leistungszeitpunkt" für abrechnende Module.
  - Datenbank: `vehicle` (`owner_org_ref`), `vehicle_lease`, `vehicle_assignment` (Kap. 04 §4.4 DRV/FLT: Eigentums-/Nutzungsintervalle) mit `(tenant_id,id)`, RLS; Überlappungsverbot je Rolle und Fahrzeug.
  - API: Rollenzuordnung lesen/ändern unter `/v1/operator/vehicles` (Detailvertrag).
  - Ereignisse/Outbox: `vehicle.role_assignment_changed`.
- **Detailverträge vorab:** DDL `vehicle_lease`, `vehicle_assignment`; Abbildung der vier Rollen aus MUL-004 auf die Begriffe „Eigentum, Einsatzbetreiber, Abrechnungspartner" aus Kap. 02 §2 (OP-26); OpenAPI; Snapshot-Vertrag für `vehicle_run`.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug trägt gleichzeitig vier verschiedene Organisationen als Eigentümer, Betreiber, Einsatzleister und Abrechnungspartner; jede Rolle ist einzeln änderbar. (MUL-004)
  2. Nach einem Eigentümerwechsel zum Stichtag liefert die As-of-Abfrage für einen Zeitpunkt vor dem Stichtag weiterhin den alten Eigentümer; ein vorher erzeugter Rollensnapshot bleibt unverändert. (MUL-004)
  3. Der Nachweis an abgerechneten Fahrzeugfahrten (`vehicle_run` mit Kostenereignis) erfolgt mit M3/M4 über die hier gelieferte Snapshot-Funktion. (MUL-004)
  4. Überlappende Intervalle derselben Rolle am selben Fahrzeug werden abgewiesen.
  5. Rollenzuordnungen sind nur für berechtigte Rollen änderbar; jede Änderung ist auditiert und erzeugt genau ein Outbox-Ereignis.
- **Tests:** positiv: vier Rollen, Wechsel mit Stichtag. negativ: Organisation eines fremden Mandanten; überlappendes Intervall; rückwirkende Änderung in abgeschlossenem Intervall. konkurrierend (Geld): zwei parallele Eigentümerwechsel zum selben Stichtag – genau einer erfolgreich.
- **Abhängigkeiten:** intern: WP-M1-05, WP-M1-07, WP-M1-14, WP-M1-46.
  - extern: WP-M2-16, WP-M3-03 (Fahrzeugstamm und `vehicle_run`)
  - extern: WP-M4-33 (Kostenereignis/Abrechnung der Fahrzeugfahrten)
- **Entscheidungen:** D-01 (Rollen je Dienst) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrzeugstammdaten, Kapazität, Sicherheitsstatus (M3); Fremdfahrzeug-Freigabe zwischen Mandanten (M6, PH-AT-016).

### WP-M1-35 – Zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion
- **Ziel:** Ein Fahrer kann befristet für zulässige Dienste eines weiteren Mandanten freigegeben werden; der empfangende Mandant sieht ausschließlich die vertraglich nötigen Daten (Qualifikationen, Einsatzzeitraum, Kontaktkanal).
- **Anforderungen:** `LH-I.4-MUL-005`
- **Liefergegenstände:**
  - Backend: Modul `staff-time` (Stammteil)/`identity` – Freigabe-Aggregat (Fahrerreferenz, Empfängermandant, Dienste, Gültigkeit, Widerruf); Erzeugung einer minimierten Projektion beim Eigentümer, Speicherung unter Empfängerkontext (Kap. 02 §4.2); Prüfung von Ablauf und Widerruf bei jedem Zugriff; Cache-Invalidierung und Prüfung der Berechtigungsrevision bei Widerruf (Kap. 04 §4.5).
  - Datenbank: `driver`, `qualification_revision` (Kap. 04 §4.4 DRV/FLT, Stammteil); Tabelle für die Freigabe/Projektion (im Pflichtenheft als `ResourceAssignment` beschrieben, aber ohne Tabellennamen, OP-26); Invariante §4.5 „Grantwiderruf sofort wirksam".
  - API: Freigabe erteilen/entziehen und Projektion lesen (Detailvertrag).
  - Oberfläche: UI-18 Basis – Freigabe mit Diensten, Frist und sichtbarer Feldliste; Zustände „Vertragsablauf", „Scopekonflikt".
  - Ereignisse/Outbox: `driver.release_granted`, `driver.release_revoked` mit Empfängeradressierung.
- **Detailverträge vorab:** DDL Freigabe/Projektion; feste Feldliste der Projektion; Verhältnis zu `cooperation_grant`/`partner_contract_version` (in P1 ohne Kooperationsvertrag? OP-26); OpenAPI.
- **Akzeptanzkriterien:**
  1. Ein Fahrer aus Mandant A wird für einen Dienst in Mandant B von T1 bis T2 freigegeben; B sieht innerhalb des Zeitraums ausschließlich Qualifikationen, zeitliche Einsatzberechtigung und Kontaktkanal. (MUL-005)
  2. Personalakte, Arbeitszeiten, Abwesenheiten und Gehaltsdaten des Fahrers sind in B über keinen Endpunkt und keine SQL-Abfrage sichtbar. (MUL-005)
  3. Vor T1, nach T2 und nach Widerruf liefert B keinen Zugriff mehr; der Widerruf wirkt mit der nächsten Anfrage.
  4. Die Freigabe gilt nur für die benannten Dienste.
  5. Erteilen, Ändern und Widerrufen einer Freigabe ist auf berechtigte Rollen des Eigentümermandanten beschränkt und auditiert; Pflichtprüfung und gesetzliche Einsatzgrenzen des Fahrers bleiben von der Freigabe unberührt (Nachweis mit M3).
- **Tests:** positiv: Freigabe im Zeitraum. negativ: Zugriff außerhalb des Zeitraums, auf nicht freigegebenen Dienst, auf nicht freigegebenes Feld; Freigabe durch unberechtigte Rolle. konkurrierend (Datentrennung): Widerruf parallel zu Lesezugriffen in B – nach Commit kein Zugriff.
- **Abhängigkeiten:** intern: WP-M1-05, WP-M1-07, WP-M1-08, WP-M1-14, WP-M1-43, WP-M1-46.
  - extern: WP-M3-05 (Fahrerstamm und Qualifikationen (`driver`, `qualification_revision`) im Vollausbau)
  - extern: WP-M6b-04, WP-M6b-05 (Kooperationsvertrag und Grants als vertragliche Grundlage der Freigabe)
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung) und D-01 – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Einsatzprüfung (Schicht, Qualifikation, Check) beim Dienstantritt (M3); Fremdfahrzeuge (M6); Abrechnung des Fremdeinsatzes (M6).

### WP-M1-36 – Transportverschlüsselung und Secret-Verwaltung
- **Ziel:** Alle Verbindungen von Clients, Admin-Oberflächen und Schnittstellen sind TLS-verschlüsselt und Klartext wird abgewiesen; Geheimnisse liegen ausschließlich in der Secret-Verwaltung, und kein Auslieferungsartefakt enthält Zugangsschlüssel.
- **Anforderungen:** `LH-6.18-SEC-002`, `LH-6.18-SEC-003`
- **Liefergegenstände:**
  - Backend: Konfiguration ausschließlich über Secret-Referenzen; keine Secretwerte in Datenbanktabellen (z. B. `webhook_subscription` hält nur Secretreferenz, Kap. 04 §4.4).
  - Adapter/Worker: `infra/` – TLS-Terminierung am Gateway, HSTS, Abweisung/Schließen von Klartext-Ports, TLS für PostgreSQL, RabbitMQ, Redis, Objektspeicher und IdP; Secret-Manager-Anbindung je Umgebung mit getrennten Secrets (Kap. 01 §1.8); Secret-Scan in CI über Quellcode, Git-Historie und gebaute Images; Rotationsverfahren.
- **Detailverträge vorab:** TLS-Mindestversion und Cipher-Vorgabe (im Pflichtenheft nicht beziffert, OP-31); Secret-Namensschema und Zugriffsrollen; Rotationsintervall; Auswahl des Secret-Managers (abhängig vom Hosting).
- **Akzeptanzkriterien:**
  1. Eine unverschlüsselte HTTP-Anfrage an API, Web- und Admin-Oberfläche sowie eine unverschlüsselte Verbindung zu Datenbank und Broker wird abgewiesen; es werden keine Nutzdaten im Klartext beantwortet. (SEC-002)
  2. Ein TLS-Scan der Staging-Endpunkte zeigt keine unsicheren Protokollversionen. (SEC-002)
  3. Der Secret-Scan des Auslieferungsartefakts (Images, Konfigurationsdateien, SBOM-Inhalt) und des Repositorys findet keine Zugangsschlüssel; der Scan ist CI-Pflichtschritt. (SEC-003)
  4. Ein absichtlich eingechecktes Test-Secret lässt den Build scheitern. (SEC-003)
  5. Ein Secret lässt sich rotieren, ohne ein neues Image zu bauen.
- **Tests:** positiv: Betrieb mit Secrets aus dem Manager. negativ: Klartextverbindung je Komponente; Test-Secret im Code; Image mit `.env`-Datei. konkurrierend (Sicherheit): Rotation eines Secrets unter Last ohne Ausfall.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-08, WP-M0-11, WP-M0-12, WP-M0-14, WP-M0-35 (CI, signierte Images, SBOM, lokale und Staging-Umgebung)
- **Entscheidungen:** D-05 (Hostingregion, verwaltete Dienste) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Partnerschlüssel und HMAC-Rotation (WP-M1-16, M6); PSP-Zugänge (M2); Penetrationstest (M5).

### WP-M1-37 – Zahlungsdatentrennung (keine Kartendaten im System)
- **Ziel:** Architektur, Schema und API verhindern, dass rohe Kartendaten das System erreichen oder gespeichert werden; PSP-Referenzen und Token werden als geschützte Felder behandelt.
- **Anforderungen:** `LH-6.18-SEC-008`
- **Liefergegenstände:**
  - Backend: Eingangs- und Log-Filter, der kartennummernähnliche Muster in Requests, Logs, Audit und Ereignissen erkennt und abweist bzw. maskiert; Feldschutzklasse „Payment-Token" im Feldschutzbaustein (WP-M1-31).
  - Datenbank: Schema-Lint „keine Spalte für PAN/CVC/Ablaufdatum"; `payment_intent.provider_ref` als einzige PSP-Referenz, klassifiziert im Dateninventar.
  - API: OpenAPI-Lint – kein Schema mit Kartendatenfeldern; „PSP-Token bleibt ein PSP-Referenzwert; rohe Zahlungsdaten erreichen diese API nie" (Kap. 05 §5.3).
  - Oberfläche: Vorgabe für das Designsystem – Zahlungsdateneingabe ausschließlich in PSP-gehosteten Komponenten.
- **Detailverträge vorab:** Erkennungsmuster und Maskierungsregel; Nachweisdokument „PSP-konforme Trennung" (Datenfluss, Systemgrenzen); Schutzklasse der Token.
- **Akzeptanzkriterien:**
  1. Die Sicherheitsprüfung (Schema-Lint, OpenAPI-Lint, Log-/Audit-Scan der Testläufe, Datenflussdokument) bestätigt, dass kein Systemteil rohe Kartendaten annimmt, verarbeitet oder speichert. (SEC-008)
  2. Ein Request, der eine Kartennummer in einem Freitextfeld enthält, wird abgewiesen oder maskiert; die Nummer erscheint weder in Logs noch in `audit_event.redacted_changes`. (SEC-008)
  3. PSP-Referenzen sind nur für Rollen mit Zahlungsbezug lesbar. (SEC-008)
  4. Die abschließende Bestätigung am echten PSP-Adapter erfolgt mit M2.
- **Tests:** positiv: Request mit PSP-Referenz. negativ: Testkartennummer in Body, Query und Header; Migration mit Kartendatenspalte; OpenAPI-Schema mit Kartendatenfeld. konkurrierend (Sicherheit): 100 parallele Requests mit Testkartennummer unter Last – jeder wird abgewiesen oder maskiert, kein Treffer gelangt über asynchrones Logging in Logs oder Audit.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-31, WP-M1-32, WP-M1-36.
  - extern: WP-M2-29 (PSP-Adapter und `/v1/payments/provider-events/{provider}` für die Prüfung am Zahlungsfluss)
- **Entscheidungen:** D-03 (Teil: PSP-Auswahl) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Zahlungsautomat, Authorize/Capture/Refund (M2); PCI-Nachweis des PSP.

### WP-M1-38 – Eingabehärtung und Sicherheits-Scans in CI
- **Ziel:** Alle Eingaben werden serverseitig validiert, typische API-/Webangriffe sind durch Bausteine abgewehrt, und SAST, Abhängigkeits- und DAST-Scans laufen als Pflichtschritte ohne offene kritische Befunde.
- **Anforderungen:** `LH-6.18-SEC-009`
- **Liefergegenstände:**
  - Backend: ausschließlich parametrisierte Queries (Lint), Größen- und Tiefenlimits für JSON, strikte Content-Types, Ausgabe-Encoding, Security-Header, CORS-Whitelist, CSRF-Schutz für Browserflüsse, Schutz vor Mass Assignment (`additionalProperties:false`), SSRF-Baustein (gemeinsam mit WP-M1-16).
  - Oberfläche: Content-Security-Policy, keine Inline-Skripte, sichere Token-Aufbewahrung im Web-Rahmen (WP-M1-43).
  - Adapter/Worker: CI-Pipeline mit SAST, Abhängigkeits-/Image-Scan, DAST gegen Staging anhand der OpenAPI-Dateien; Befundverwaltung mit Mangelklassen (Klasse A blockiert).
- **Detailverträge vorab:** Schwellen „kritisch" je Werkzeug; Liste der Pflicht-Header; Limits (Body-Größe, Verschachtelung – im Pflichtenheft nicht beziffert, OP-20); Prüfkatalog (z. B. OWASP API Top 10 als Arbeitsgrundlage, vom Pflichtenheft nicht vorgegeben).
- **Akzeptanzkriterien:**
  1. SAST und DAST laufen in CI bzw. gegen Staging über alle M1-Endpunkte; es gibt keinen offenen kritischen Befund, jeder Befund ist mit Entscheidung dokumentiert. (SEC-009)
  2. Standardangriffe (SQL-Injection, XSS, übergroße/tief verschachtelte Bodies, Mass Assignment, Pfadmanipulation, Header-Injection) werden in automatisierten Negativtests abgewiesen. (SEC-009)
  3. Der Penetrationstest des Lastenhefts wird in M5 durchgeführt; M1 liefert dafür Scan-Berichte und Testzugang. (SEC-009)
- **Tests:** positiv: Regressionslauf ohne Fehlalarme auf gültigen Requests. negativ: Angriffskatalog je Endpunkt (generiert aus OpenAPI). konkurrierend (Sicherheit): Rate-Limit unter parallelem Missbrauch.
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-03.
  - extern: WP-M0-11, WP-M0-12, WP-M0-35 (CI, Staging-Umgebung)
  - extern: WP-M5-17 (Penetrationstest und Behandlung der Befunde)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Upload-/Malware-Prüfung (M4); Penetrationstest selbst (M5).

### WP-M1-39 – Verschlüsselte Backups und Restore-Test
- **Ziel:** Datenbank und Objektspeicher werden verschlüsselt und veränderungsgeschützt gesichert; ein wiederholbarer Restore-Test stellt Referenzdaten beider Testmandanten nachweislich wieder her.
- **Anforderungen:** `LH-6.18-SEC-010`
- **Liefergegenstände:**
  - Adapter/Worker: `infra/` – verschlüsselte Basis-Backups plus kontinuierliches WAL (PITR), getrennter Schlüssel, Schreibschutz/Object Lock bzw. getrennte Berechtigungsdomäne für Backups, Zugriff nur für Betriebsrolle; geplanter Restore-Job in isolierte Umgebung mit Prüfsummenvergleich; Drehbuch in `tests/performance/` (Restore-Drehbuch).
  - Datenbank: Referenzdatensatz (Mandanten A/B) mit Prüfsummen; Nachweis, dass RLS, Rollen und Trigger nach Restore unverändert wirksam sind; Wiedereinspielen des Lösch-/Sperrjournals vor Freigabe (Kap. 01 §1.7).
- **Detailverträge vorab:** Backup-Intervall, Aufbewahrung und Testzyklus (im Pflichtenheft nur „zyklisch"/„regelmäßig"; RPO ≤ 15 min, RTO ≤ 2 h als Zielwerte des Lastenhefts, OP-31); Schlüsselverwaltung; Restore-Drehbuch.
- **Akzeptanzkriterien:**
  1. Ein automatisiert ausgelöster Restore-Test stellt die Referenzdaten vollständig wieder her (Prüfsummen identisch) und ist als regelmäßiger Job eingerichtet; Ergebnis und Dauer werden protokolliert. (SEC-010)
  2. Backups sind im Ruhezustand verschlüsselt; ohne Schlüssel nicht lesbar. (SEC-010)
  3. Die Runtime- und die Migratorrolle können Backups weder verändern noch löschen. (SEC-010)
  4. Nach Restore schlägt der Isolationstest aus WP-M1-01 nicht fehl, und ein zuvor gelöschter Testdatensatz bleibt gelöscht.
- **Tests:** positiv: Restore auf Zeitpunkt T. negativ: Restore mit falschem Schlüssel; Löschversuch am Backup mit Anwendungsrolle; manipuliertes Backup wird erkannt. konkurrierend (Sicherheit): Backup und Restore auf Zeitpunkt T unter laufender Schreiblast beider Testmandanten – der wiederhergestellte Stand ist transaktionskonsistent und enthält keine halben Transaktionen.
- **Abhängigkeiten:** intern: WP-M1-36, WP-M1-01, WP-M1-33.
  - extern: WP-M0-11, WP-M0-35 (Infrastruktur als Code, verwaltete PostgreSQL-/Objektspeicher-Dienste)
  - extern: WP-M5-14, WP-M5-15 (Restore-Übung mit RPO/RTO-Messung einschließlich PSP-Abgleich)
- **Entscheidungen:** D-05 (Hosting, Betrieb) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Failover/Hochverfügbarkeit und RPO/RTO-Abnahme (M5); Sicherung der Finanzarchive (M4).

### WP-M1-40 – Trennung von Test- und Produktionsdaten und -zugängen
- **Ziel:** Entwicklungs-, Staging-, Performance- und Produktionsumgebung haben getrennte Daten, Secrets, Identitätsdomänen und Zugänge; echte Fahrgastdaten gelangen nicht unkontrolliert in Nicht-Produktionssysteme.
- **Anforderungen:** `LH-6.18-SEC-012`
- **Liefergegenstände:**
  - Adapter/Worker: `infra/` – getrennte Konten/Netze je Umgebung, getrennte IdP-Realms und Secrets (Kap. 01 §1.8); kein Netzpfad von Entwicklung zur Produktionsdatenbank; synthetischer Seed-Datengenerator für Mandanten A/B; technische Sperre für Produktions-Dumps in Nicht-Produktionsumgebungen (Umgebungskennung in der Datenbank, Importprüfung).
  - Backend: Startprüfung „Umgebungskennung der Anwendung = Umgebungskennung der Datenbank"; Token eines fremden Issuers werden abgelehnt (WP-M1-04).
- **Detailverträge vorab:** Umgebungsmatrix (Zugänge, Rollen, Datenherkunft); Verfahren für kontrollierte, anonymisierte Datenübernahme (falls gewünscht – im Pflichtenheft nur „Produktionsdaten werden nicht in Entwicklung kopiert").
- **Akzeptanzkriterien:**
  1. Eine Prüfung des Entwicklungssystems findet ausschließlich synthetische Daten; ein Import eines als Produktion gekennzeichneten Dumps wird technisch abgewiesen. (SEC-012)
  2. Mit Entwicklungs-/Testzugängen ist kein Zugriff auf Produktions-API, -Datenbank oder -Secrets möglich und umgekehrt. (SEC-012)
  3. Eine Anwendung mit Nicht-Produktionskonfiguration startet nicht gegen die Produktionsdatenbank. (SEC-012)
- **Tests:** positiv: Seed-Lauf in Entwicklung. negativ: Produktions-Dump-Import; Test-Token gegen Produktion; Entwicklungsdienst mit Produktions-DB-URL. konkurrierend: nicht einschlägig (statische Umgebungstrennung).
- **Abhängigkeiten:** intern: WP-M1-04, WP-M1-36.
  - extern: WP-M0-11 (Umgebungen und Infrastruktur als Code)
- **Entscheidungen:** D-05 (Hosting) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Lasttestdaten und Performance-Umgebung im Vollausbau (M5).

### WP-M1-41 – Sicherheitsvorfallprozess und Übung
- **Ziel:** Für Sicherheitsvorfälle existiert ein beschriebener Eskalations-, Untersuchungs- und Meldeprozess mit technischen Auslösern; eine Übung belegt Identifikation, Eindämmung und Meldeentscheidung.
- **Anforderungen:** `LH-6.18-SEC-011`
- **Liefergegenstände:**
  - Backend: Alarmregeln auf Sicherheitsereignisse aus WP-M1-12 (gehäufte Fremdzugriffe, MFA-Fehlschläge, veränderter Payload bei gleicher Ereignis-ID); Eindämmungsfunktionen: Sitzung/Konto sperren (WP-M1-10), Mandant auf `SUSPENDED` setzen, Secret rotieren (WP-M1-36).
  - Oberfläche: UI-20 Basis – Liste der Sicherheitsereignisse als Einstieg in die Untersuchung.
  - Adapter/Worker: Betriebsdokument „Sicherheitsvorfall" (Rollen, Eskalationsstufen, Untersuchungsschritte, Entscheidungsvorlage Meldung); Übungsdrehbuch und Protokollvorlage.
- **Detailverträge vorab:** Rollen und Erreichbarkeiten (Auftraggeber/Betreiber), Meldewege und Fristen (rechtlich vom Auftraggeber zu bestätigen, OP-32); Schweregradschema; Alarmkanäle.
- **Akzeptanzkriterien:**
  1. Eine durchgeführte Übung (simulierter Cross-Tenant-Zugriffsversuch mit kompromittiertem Konto) ist protokolliert mit Zeitpunkt der Identifikation, Eindämmungsmaßnahmen und dokumentierter Entscheidung über Meldungen. (SEC-011)
  2. Der Prozess benennt Eskalationsstufen, Verantwortliche und Untersuchungsschritte und ist freigegeben. (SEC-011)
  3. Der auslösende Alarm wird aus den Sicherheitsereignissen automatisch erzeugt und erreicht den definierten Kanal.
- **Tests:** positiv: Übung nach Drehbuch. negativ: Alarm bei Schwellwertunterschreitung bleibt aus; Eindämmung durch unberechtigte Rolle abgewiesen. konkurrierend (Sicherheit): 200 gleichartige Sicherheitsereignisse in kurzer Folge erzeugen genau einen Vorfallalarm (Deduplikation), kein Ereignis geht verloren.
- **Abhängigkeiten:** intern: WP-M1-05, WP-M1-10, WP-M1-12, WP-M1-14, WP-M1-36, WP-M1-43.
  - extern: WP-M0-10, WP-M5-04 (Observability/Alarmkanal)
- **Entscheidungen:** D-11 (Verantwortliche, Supportzugriff) und D-05 (Supportzeiten) – blockieren die Freigabe des Prozesses für den Produktivbetrieb.
- **Größe:** S
- **Nicht im Umfang:** Betriebshandbuch, Monitoring und Eskalation des Gesamtbetriebs (M5); regelbasierte Fachalarme (`alert_policy_revision`, M3/M8).

### WP-M1-42 – Datenschutz-Folgenabschätzung
- **Ziel:** Für die relevanten Verarbeitungen (insbesondere Bewegungs- und Mobilitätsdaten) liegt vor Produktion eine geprüfte und dokumentierte Datenschutz-Folgenabschätzung mit Maßnahmen vor; die Freigabe ist technisch an das Release-Gate gekoppelt.
- **Anforderungen:** `LH-6.18-SEC-013`
- **Liefergegenstände:**
  - Adapter/Worker: DSFA-Dokument im Repository (Verarbeitungen, Risiken, Maßnahmen, Restrisiko, Freigabevermerk), gespeist aus Dateninventar (WP-M1-32), Löschregeln (WP-M1-33), Feldschutz (WP-M1-31) und Isolationsnachweisen (WP-M1-01, WP-M1-11); Release-Gate-Prüfung „DSFA-Version freigegeben und deckt alle im Inventar als Bewegungs-/Mobilitätsdaten markierten Felder ab".
- **Detailverträge vorab:** DSFA-Vorlage und Verantwortliche (Datenschutzbeauftragte des Auftraggebers/der Betreiber, OP-32); Kennzeichnung „Bewegungs-/Mobilitätsdatum" im Dateninventar.
- **Akzeptanzkriterien:**
  1. Die Freigabeunterlage enthält eine Bewertung der Verarbeitung von Bewegungs- und Mobilitätsdaten und die zugeordneten technischen und organisatorischen Maßnahmen mit Verweis auf die umsetzenden WPs. (SEC-013)
  2. Die DSFA ist vor der ersten Produktivschaltung personenbezogener Daten geprüft, datiert und von der verantwortlichen Stelle freigegeben; ohne Freigabevermerk schlägt die Gate-Prüfung fehl. (SEC-013)
  3. Jedes im Inventar als Bewegungs-/Mobilitätsdatum markierte Feld ist in der DSFA referenziert.
- **Tests:** positiv: Gate-Prüfung mit freigegebener DSFA. negativ: neues Mobilitätsdatenfeld ohne DSFA-Referenz lässt die Prüfung scheitern; fehlender Freigabevermerk. konkurrierend: nicht einschlägig (Dokument- und Gate-Prüfung).
- **Abhängigkeiten:** intern: WP-M1-32, WP-M1-33, WP-M1-31.
  - extern: WP-M3-34 (Standortdaten (`position_sample`) als nachzutragende Verarbeitung)
  - extern: WP-M4-61, WP-M4-62 (Fahrer-/ETA-Daten als nachzutragende Verarbeitung)
- **Entscheidungen:** D-11 und D-10 – blockieren die Produktivschaltung, nicht die Erstellung; D-05 (Hostingregion).
- **Größe:** S
- **Nicht im Umfang:** rechtliche Bewertung selbst (Auftraggeber/Datenschutz); Fortschreibung für P2-Verarbeitungen (M6–M8).

### WP-M1-43 – Web-Designsystem und Anwendungsrahmen
- **Ziel:** Ein gemeinsames Designsystem und der Anwendungsrahmen für Operator- und Passenger-Web stehen bereit, sodass alle Fach-WPs barrierearme Oberflächen aus denselben Bausteinen bauen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: `web/design-system` – Komponenten nach Kap. 06 §6.1: Layout Desktop (Navigation links, Arbeitskontext oben, Hauptbereich, Detailpanel) und Mobil (ein Arbeitsschritt je Seite), Kontextzeile Mandant/Dienst/Status/Datenstand, Formulare mit Feldfehlern aus `violations[].pointer`, Tabellen mit Cursor-Paging, Statusanzeigen nie nur als Farbe, Live-Region für asynchrone Meldungen, Erweiterungspunkt für die Datum/Zeit-Komponente mit Zone (liefert WP-M1-18), 412-Konfliktdialog ohne stilles Überschreiben, gesperrte Buttons bei laufender Operation; Internationalisierung mit Deutsch als erster Sprache.
  - Oberfläche: `web/operator` und `web/passenger` als Rahmenanwendungen (Routing, rollenbezogene Navigation, Fehlerseiten, API-Client aus OpenAPI generiert); Admin-Grundgerüst mit leeren Bereichen UI-17, UI-18, UI-20.
  - Adapter/Worker: automatisierte Barrierefreiheitsprüfung in CI (WCAG 2.2 AA als Abnahmeziel, Kontrast, 200 % Text, Reflow 320 CSS-Pixel, Touchflächen 44×44, reduzierte Bewegung).
- **Detailverträge vorab:** Komponentenliste und Design-Tokens; Navigationsstruktur je Rolle; Fehler- und Leerzustands-Konvention; Browser-/Gerätematrix (im Pflichtenheft nicht festgelegt, OP-20).
- **Akzeptanzkriterien:**
  1. Alle Komponenten sind per Tastatur bedienbar, haben sichtbaren Fokus und bestehen die automatisierte Barrierefreiheitsprüfung.
  2. Ein Feldfehler der API wird am richtigen Feld angezeigt, ohne den freien Fehlertext auszuwerten.
  3. Der Admin-Rahmen zeigt nur Navigationspunkte, für die `/v1/me` Rechte liefert.
  4. Layout bleibt bei 320 CSS-Pixeln und 200 % Text ohne horizontales Scrollen benutzbar.
  5. Für UI-17, UI-18 und UI-20 sind die Leer-, Fehler- und Rechtezustände aus Kap. 06 §6.9 als wiederverwendbare Zustandskomponenten vorhanden; jeder Bildschirmrahmen benennt Mandant/Dienst, fachlichen Status und Datenstand.
- **Tests:** positiv: Komponententests, visuelle Regression. negativ: API-Fehler 401/403/404/412/428/429 erzeugen die definierten Zustände. konkurrierend: nicht einschlägig (Oberflächenbausteine ohne eigenen Zustand; Doppelklick-/Mehrfachabsendeschutz wird über gesperrte Buttons positiv geprüft). Vorbereitung des UX-Gates (Kap. 08 §8.4).
- **Abhängigkeiten:** intern: WP-M1-02, WP-M1-03.
  - extern: WP-M0-05, WP-M0-12 (Repository-Gerüst `web/`, CI)
  - extern: WP-M5-30 (Barrierefreiheitsprüfung der Kernreisen mit Tastatur und Screenreader (UX-Gate))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fachbildschirme UI-01 bis UI-16, UI-19 (M2–M8); Kartenkomponente (M2); Flutter-Apps (M3, M7).

### WP-M1-44 – Web-Anmeldung und Mandantenwechsel
- **Ziel:** Nutzer melden sich im Web per OIDC/PKCE an, sehen ihren aktiven Mandanten und wechseln ihn bewusst; fremde offene Formulare und Listen werden beim Wechsel zurückgesetzt, mehrere Tabs führen ihren Kontext unabhängig.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: Login-/Logout-Fluss, MFA-Aufforderung, Sitzungsablauf- und Sperrzustand; Mandantenwähler auf Basis von `/v1/me`; Kontextanzeige als Anzeige der autorisierten Sitzung, nicht als Rechtequelle (Kap. 06 §6.1); Behandlung ungespeicherter eigener Eingaben mit Rückkehrmöglichkeit; UI-05 Basis (Konto) und UI-18 Basis (Einstieg) eingebunden.
  - API: Nutzung `/v1/me` und Tenantwechsel aus WP-M1-06, Sitzungsoperationen aus WP-M1-10.
- **Detailverträge vorab:** Sitzungs-/Tokenhaltung im Browser; Verhalten je Tab (Kontextbindung); Texte der Zustände (gesperrt, abgelaufen, keine Mitgliedschaft).
- **Akzeptanzkriterien:**
  1. Anmeldung mit PKCE; Administratorrollen werden zur MFA geführt; ohne Mitgliedschaft erscheint ein verständlicher Zustand ohne Datenzugriff.
  2. Mandantenwechsel mit zwei Tabs: Tab 1 bleibt in A, Tab 2 wechselt nach B; keine Anfrage aus Tab 1 liefert B-Daten und umgekehrt (UI-Abnahmefall aus Kap. 06 §6.9).
  3. Nach Wechsel sind Listen und Formulare des vorherigen Mandanten zurückgesetzt; ungespeicherte Eingaben werden nicht still in den neuen Mandanten übernommen.
  4. Eine serverseitig gesperrte Sitzung führt bei der nächsten Aktion zur Abmeldung.
- **Tests:** positiv: Login, Wechsel, Logout (Ende-zu-Ende im Browser). negativ: manipulierter Mandant im lokalen Speicher erzeugt keine Rechte; gesperrte Sitzung; abgelaufenes Token. konkurrierend (Datentrennung): zwei Tabs mit unterschiedlichen Mandanten und gleichzeitigen Schreibaktionen. Unterstützt AT-18 und PH-AT-001 auf UI-Ebene.
- **Abhängigkeiten:** intern: WP-M1-04, WP-M1-06, WP-M1-10, WP-M1-43.
  - extern: WP-M2-35, WP-M2-36 (Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fahrgast-Registrierungsseiten (WP-M1-28); Callcenter-Kontext (UI-06, M7).

### WP-M1-45 – M1-Gate-Abnahmesuite
- **Ziel:** Die Gate-Tests des Meilensteins laufen als automatisierte Szenarien unter der Runtime-Rolle mit Build-ID und Evidenz; alle 46 Anforderungen sind in `arbeitspakete.csv` mit Detailvertrag und Testfall verknüpft.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `tests/acceptance` – Szenarien PH-AT-001, PH-AT-011, PH-AT-017, PH-AT-018, AT-18, AT-24, AT-49, E2E-091, E2E-096, E2E-097, E2E-098 mit Fixture Mandanten A/B; Evidenzerfassung gemäß Kap. 08 §8.4 (`source_key[]`, Test-ID/Version, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen/Mandanten, Soll/Ist, Trace-IDs, Ergebnis, Mangelklasse); Befüllung von `e2e.csv` ausschließlich aus Testläufen; CI-Prüfung „keine als umgesetzt markierte MUSS-Zeile ohne Testfall"; Evidenz ohne unnötige Fahrgast- oder Personaldaten. Semantische Contract-Fälle aus Kap. 05 §5.8, die M1 betreffen, als CI-Pflicht: „100 identische Webhooks", „Manipulation tenant_id", „unzulässige Fremd-ID". UI-Abnahmefälle aus Kap. 06 §6.9: „Mandantenwechsel mit zwei Tabs", „412-Konflikt ohne stilles Überschreiben".
  - Datenbank: Pflicht-Negativtests mit zwei parallelen Transaktionen für die in M1 wirksamen Invarianten aus Kap. 04 §4.5 (Cross-Tenant-Referenz, kein Kontext → keine Zeile, Gegenereignis statt Änderung für `audit_event`, nicht überlappende Policyversion, Idempotenz).
- **Detailverträge vorab:** Testfallbeschreibungen je Gate-Test mit Abgrenzung des in M1 prüfbaren Umfangs (OP-02); Evidenzformat; Zuordnungstabelle Anforderung → Testfall.
- **Akzeptanzkriterien:**
  1. Alle elf Gate-Tests laufen in CI grün im in M1 prüfbaren Umfang; nicht prüfbare Teilschritte sind je Test benannt und einem späteren Meilenstein zugeordnet.
  2. Für jede der 46 Anforderungen sind in `arbeitspakete.csv` Detailvertrag und mindestens ein positiver ausführbarer Test eingetragen; Sicherheits- und Datentrennungsregeln haben zusätzlich negative und konkurrierende Tests.
  3. `e2e.csv` enthält für die Gate-Tests Build, Nachweis und Entscheidung aus einem Testlauf.
  4. Keine offenen Mängel der Klasse A.
  5. Die drei M1-relevanten semantischen Contract-Fälle und die zwei UI-Abnahmefälle laufen in CI grün.
  6. Für jede in M1 wirksame Invariante aus Kap. 04 §4.5 existiert ein Negativtest mit mindestens zwei parallelen Transaktionen unter der Runtime-Rolle.
- **Tests:** PH-AT-001, PH-AT-011, PH-AT-017, PH-AT-018, AT-18, AT-24, AT-49, E2E-091, E2E-096, E2E-097, E2E-098; positiv und negativ je Gate-Test gemäß Soll der Referenz (negativ u. a. Fremdzugriff, abweichender Request bei gleichem Schlüssel, unzulässige Konfiguration); Invarianten-Negativtests konkurrierend mit zwei parallelen Transaktionen.
- **Abhängigkeiten:** intern: alle WPs dieser Datei.
  - extern: WP-M0-22, WP-M0-31, WP-M0-32 (Testinfrastruktur, Detailvertragsprozess, Pflege von `arbeitspakete.csv`)
  - extern: WP-M2-07, WP-M2-23, WP-M2-38, WP-M2-48 (Buchung, Tarifversion, Such-/Medienendpunkte für die vollständigen Fassungen von PH-AT-001, PH-AT-011, E2E-096, E2E-098)
  - extern: WP-M3-44, WP-M3-56 (Fotos/Schadensevidenz für AT-49)
  - extern: WP-M4-06, WP-M4-08, WP-M4-22, WP-M4-64 (Belege, Rechnungen, Arbeitszeit, Abwesenheit, Personalprofil für AT-49, E2E-091, PH-AT-001)
- **Entscheidungen:** D-12 (Bestätigung der Testzuordnung zu Meilensteinen in M0) – blockiert das Gate, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Lasttest, Restore-Übung, Penetrationstest (M5); Gates anderer Meilensteine.

### WP-M1-46 – Audit-Port und append-only Auditspeicher
- **Ziel:** Jedes Modul kann Audit- und Sicherheitsereignisse in derselben Transaktion wie die Fachänderung schreiben; der Speicher ist append-only. Der Baustein steht vor den Identitäts-, Ereignis- und Konfigurations-WPs bereit, damit diese nicht zyklisch von der Auditabfrage (WP-M1-12) abhängen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: `backend/platform` – Audit-Port (Schreiben in derselben Transaktion wie die Fachänderung) und Sicherheitsereignis-Port; Maskierung in `redacted_changes` (keine Geheimnisse, keine Inhalte fremder Mandanten, keine unnötigen Personendaten); Pflichtangaben Akteur, Aktion, Objekt, Zeitpunkt, `correlation_id`; Architekturtest „als auditpflichtig markierter Befehl ohne Audit-Aufruf scheitert".
  - Datenbank: `audit_event` aus `kernschema.sql` mit Trigger `audit_immutable` (`reject_mutation()`); Runtime-Rolle nur INSERT/SELECT; RLS wie alle Tabellen; Invariante §4.5 „Gegenereignis statt Änderung".
- **Detailverträge vorab:** JSON-Schema `redacted_changes`; Format von `actor_ref` und `object_ref`; Startkatalog auditpflichtiger Aktionen (fortgeschrieben in WP-M1-12); Behandlung von Ereignissen ohne Mandantenkontext, da `audit_event.tenant_id` Pflichtfeld ist (OP-14).
- **Akzeptanzkriterien:**
  1. Fachänderung und Auditeintrag sind atomar: schlägt die Fachänderung fehl, existiert kein Auditeintrag; gelingt sie, existiert genau einer mit Akteur, Aktion, Objekt und Zeitpunkt.
  2. UPDATE und DELETE auf `audit_event` schlagen unter der Runtime-Rolle fehl; die Runtime-Rolle besitzt auf der Tabelle nur INSERT und SELECT.
  3. `redacted_changes` enthält in keinem Testlauf Token, Passwörter, Kartendaten oder Inhalte eines anderen Mandanten.
  4. Mit Kontext A ist keine Auditzeile des Mandanten B lesbar.
- **Tests:** positiv: Auditeintrag zu einer Teständerung. negativ: UPDATE/DELETE-Versuch; Insert mit fremder `tenant_id`; Befehl ohne Audit-Aufruf (Architekturtest). konkurrierend (Sicherheit): Pflicht-Negativtest zu §4.5 mit zwei parallelen Transaktionen (Änderungsversuch an einem Eintrag parallel zu neuen Einträgen – Änderung scheitert, Einträge vollständig); 100 parallele Änderungen → 100 Einträge, keine Lücke; fehlgeschlagene Fachänderung → kein verwaister Auditeintrag. Ermöglicht AT-49 („protokolliert") und E2E-091 („Sicherheitsprotokoll").
- **Abhängigkeiten:** intern: WP-M1-01.
  - extern: WP-M0-16 (Kernschema als lauffähige Flyway-Migration)
- **Entscheidungen:** D-11 (Aufbewahrung der Auditdaten) – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Auditabfrage, UI-20, Export mit Integritätsprüfung, Übernahme der IdP-Anmeldeereignisse (WP-M1-12); Alarmregeln (WP-M1-41).

## Offene Punkte

- **OP-01 – Abnahme setzt Objekte späterer Meilensteine voraus.** Die Originalabnahme folgender M1-Anforderungen ist in M1 nur am Plattformbaustein bzw. Testaggregat prüfbar: `LH-6.1-SYS-003` (Testfahrt je Angebotsmodell → M2), `LH-6.1-SYS-005` (Fahrtzusage, Fahrgastinformation → M2), `LH-6.1-SYS-006` (Buchung über DST-Grenze → M2), `LH-6.1-SYS-007` (Fahrt-, Buchungs-, Zahlungsstatus → M2; Fahrzeug-, Störungsstatus → M3), `LH-6.1-SYS-011` (tarifliche Regel → M2), `LH-6.2-IAM-005` (regionale Buchungen → M2), `LH-6.2-IAM-011` (Fahrtinformation → M2), `LH-6.17-INT-001` und `LH-6.17-INT-004` (Buchungs-/Zahlungs-APIs → M2), `LH-I.4-MUL-001` (Buchungen/Rechnungen → M2/M4), `LH-I.4-MUL-004` (abgerechnete Fahrzeugfahrten → M3/M4), `LH-I.4-MUL-005` (Fahrerstamm → M3), `LH-I.4-MUL-007` (Tarif → M2), `LH-I.7-CFG-003` (Dispositionsfall → M2), `LH-I.7-CFG-007` (betroffene Buchungen/Schichten → M2/M4), `LH-6.18-SEC-008` (PSP → M2), `LH-6.18-SEC-009` (Penetrationstest → M5), `LH-6.1-SYS-002` (Bediengebiet und Tarifverknüpfung als Bestandteil der Dienstkonfiguration → M2), `LH-I.7-CFG-006` (Bediengebiete und Haltestellenschema → M2), `LH-6.2-IAM-001` (produktiver SMS-Kanal → M2), `LH-6.2-IAM-006` (bevorzugte Haltestellen → M2), `LH-6.18-SEC-006` (Dispositionseingriffe → M3), `LH-I.4-MUL-002` (Betriebshöfe im Vollausbau laut Meilensteinplan → M7). Zu klären: Gilt die Anforderung am M1-Gate mit Bausteinnachweis als erfüllt, oder wird die Endabnahme formal am Gate des liefernden Meilensteins nachgezogen? Phase und Priorität bleiben unverändert.
- **OP-02 – Gate-Tests sind in M1 nur teilweise ausführbar.** PH-AT-001 verlangt Fahrzeug, Beleg, Such-, Export- und Medienendpunkt; AT-49 Fotos, Arbeitszeit und Krankheitsdetails; E2E-091 Rechnung/Personalprofil; PH-AT-011 Angebot und Betrag; E2E-096 Fahrten/Angebote; E2E-098 eine Tarifversion; PH-AT-018 bezieht sich zusätzlich auf CFG-011/021 und KPI-006/020 (M8). Der in M1 prüfbare Umfang je Test muss mit dem Auftraggeber festgelegt werden (WP-M1-45).
- **OP-03 – P1-relevante Parameter und Sicherheitsmaxima nicht festgelegt.** Der Meilensteinplan nennt „Schemas für die P1-relevanten der 80 Parameter", ohne sie zu benennen; `kataloge.csv` vermerkt für alle Parameter „konkrete Schemas und freigegebene Werte vor Implementierung ergänzen". Die Startmenge in WP-M1-19 ist ein Vorschlag. Für `LH-I.7-CFG-004` fehlen die systemischen Sicherheitsmaxima als Zahlenwerte; mehrere Katalogbereiche sind unscharf (z. B. `pickup_window_min` „Positiv").
- **OP-04 – `membership` ohne Gültigkeitszeitraum und Statusfeld.** Kap. 02 §2 beschreibt Membership als „zeitlich begrenzt"; `kernschema.sql` enthält nur `revoked_at`. Die Bedeutung von `service_scope` = NULL (alle Dienste oder keiner) ist nicht festgelegt. Betrifft `LH-I.4-MUL-006`, `LH-6.2-IAM-005`.
- **OP-05 – Organisationsrollen: Werteliste und Gültigkeit.** `LH-I.4-MUL-003` nennt Plattformbetreiber, Verkehrsunternehmen, Aufgabenträger, Auftraggeber, Vermittler, tatsächlicher Beförderer; Kap. 02 §2 nennt Plattformanbieter, Beförderer, Vermittler, Fahrzeugsteller, Kostenträger und „zeitlich gültige Rollen". `organization.roles` ist ein `text[]` ohne Werteprüfung und ohne Gültigkeitsintervall. Abbildung und Enum fehlen.
- **OP-06 – Mandantenliste vor gesetztem Tenantkontext.** RLS auf `membership` und `tenant` liefert ohne Kontext keine Zeile; Kap. 04 §4.6 verbietet Allzweck-`SECURITY DEFINER`. Wie der Server für einen Nutzer die wählbaren Mandanten ermittelt (`/v1/me`, Tenantwechsel), ist nicht spezifiziert. Betrifft `LH-I.4-MUL-006`, `LH-6.17-INT-002`.
- **OP-07 – Tabellen für Region und Betriebshof fehlen.** `LH-I.4-MUL-002` verlangt Betriebshöfe, Regionen, Betriebsdienste und Kostenstellen; weder `kernschema.sql` noch Kap. 04 §4.4 benennen Tabellen für Region und Betriebshof (nur `service` und `cost_object`).
- **OP-08 – Plattformmandant und globale Konfigurationsebene.** Kap. 02 §1 verlangt einen expliziten Plattformmandanten statt `tenant_id IS NULL`. Wie Mandanten-Transaktionen globale Sicherheitsgrenzen und Katalogobjekte dieses Plattformmandanten unter RLS lesen, ist nicht spezifiziert. Betrifft `LH-I.7-CFG-004`, `LH-I.7-CFG-005`.
- **OP-09 – Marken und Benachrichtigungsregeln je Mandant.** `LH-I.4-MUL-007` nennt getrennte Marken und Benachrichtigungsregeln; das Pflichtenheft enthält dafür weder Tabellen noch Parameter außer `notification_quiet_hours` und `customer_notify_before_pickup_min`. Dienstpläne, Fahrzeuge, Tarife und Finanzdaten derselben Anforderung entstehen erst in M2–M4.
- **OP-10 – Rechtematrix fehlt.** `LH-6.2-IAM-004` verlangt einen Rechtematrixtest; das Pflichtenheft enthält keine Matrix Rolle × Tätigkeit und keine Werteliste für `membership.role_code`. Kap. 06 setzt weitere Fachrollen voraus (Finanzen, Personalplanung, Werkstatt/Freigabe), die in IAM-004 nicht genannt sind.
- **OP-11 – „Privilegierte Konten" nicht definiert.** `LH-6.2-IAM-010` verlangt MFA für privilegierte Konten; Kap. 02 §4.1 verlangt OIDC und MFA für Mitarbeiter allgemein. Welche Rollen als privilegiert gelten, ist festzulegen.
- **OP-12 – Passwortlose Anmeldung.** `LH-6.2-IAM-002` sieht eine passwortlose Alternative vor; Verfahren und Zielgruppe sind nicht benannt. Da Passwörter ausschließlich im IdP liegen, besteht die Abnahme aus einem Konfigurationsnachweis des IdP; das ist mit dem Auftraggeber abzustimmen.
- **OP-13 – Sitzungssperre: Datenmodell, Tokenlaufzeit, Wiederherstellung.** `LH-6.2-IAM-009`: Es gibt keine Sitzungstabelle in `kernschema.sql` oder Kap. 04 §4.4; „kurze Tokenlaufzeit" (Kap. 01 §1.7) ist nicht beziffert; das Wiederherstellungsverfahren ist nicht beschrieben. `/v1/me` ist in Kap. 05 §5.7 nur mit Stichworten („gesperrte Sitzung") umrissen.
- **OP-14 – Audit: Abfrage-API, Exportziel, Ereignisse ohne Mandant.** `LH-6.18-SEC-006`: Kap. 05 §5.7 enthält keinen Audit-Endpunkt; das Ziel des „Exports in getrennte Integritäts-/Berechtigungsdomäne" und das Integritätsverfahren sind offen; `audit_event.tenant_id` ist NOT NULL, fehlgeschlagene Logins vor Mandantenwahl haben aber keinen Mandanten. Der Umfang von „UI-20 Basis" ist nicht beschrieben. Logins finden am IdP statt; wie dessen Anmeldeereignisse in `audit_event` übernommen werden, ist nicht spezifiziert.
- **OP-15 – `operation` ist im Kern-DDL an `journey` gebunden.** `operation.journey_id` ist NOT NULL mit FK auf `journey`, während das API-Schema `Operation.journey_id` null erlaubt und M1 die Ressource ohne Reisen braucht. Das `kind`-Enum kennt nur `HOLD`, `CONFIRM`, `CANCEL`, `RECONCILE`. DDL und OpenAPI müssen für generische Operationen angepasst werden.
- **OP-16 – `outbox`/`inbox` ohne Zustell- und Adressierungsfelder.** Empfängeradressierung (Kap. 02 §4.3), Zustellversuche und Dead-Letter (Kap. 05 §5.6) haben keine Spalten im Kern-DDL. Der Inbox-Schlüssel ist dort `(tenant_id,consumer,event_id)`, in Kap. 05 §5.6 `(partner_id,event_id)` plus Payloadhash.
- **OP-17 – Umfang der „Webhook-Grundlage".** Der Meilensteinplan nennt sie ohne Abgrenzung. `/v1/webhook-subscriptions` steht in Kap. 05 §5.7 unter Kommunikation/Support, `webhook_subscription` in Kap. 04 §4.4 ohne Feldliste. Welche Ereignistypen in P1 abonnierbar sind und auf welchen Webhook sich AT-24 in P1 bezieht (PSP-Ereignisse kommen erst in M2), ist offen. Betrifft `LH-6.17-INT-004`.
- **OP-18 – `config_revision`: fehlende Felder und Regeln.** Für `LH-I.7-CFG-003` fehlen Änderungsgrund, Statusfeld und `created_at`; Altwert/Neuwert sind nur als Diff zur Vorgängerrevision ableitbar. Die zulässigen `scope_kind`-Werte sind nicht aufgezählt. Der CHECK erzwingt `approved_by` bei Publikation, aber nicht `approved_by <> created_by`. Das Überlappungsverbot ist laut Kap. 04 §4.5 „noch umzusetzen".
- **OP-19 – Konfigurations-API unvollständig.** Kap. 05 §5.7 nennt `/validate` und `/impact` nur verkürzt; es gibt keinen Endpunkt für die Freigabe durch die zweite Person und keinen für das Rollback. `PublishRequest` verlangt `impact_report_id`, ein Schema oder eine Tabelle für den Wirkungsbericht existiert nicht. Offen ist, welche Konfigurationen als „wichtig" gelten (`LH-I.7-CFG-007`), ob für jede Publikation ein Bericht Pflicht ist und ob `effective_from` in der Vergangenheit zulässig ist (`LH-I.7-CFG-006`). Der Pfadparameter heißt in `openapi.yaml` `{revision_id}`, in Kap. 05 §5.5/§5.7 `{id}`.
- **OP-20 – Nicht bezifferte technische Grenzwerte.** Rate-Limit-Quoten, maximale Body-Größe und Verschachtelungstiefe (`LH-6.17-INT-003`, `LH-6.18-SEC-009`) sowie die Browser-/Gerätematrix des Webs sind im Pflichtenheft nicht festgelegt.
- **OP-21 – Fahrzeugstatus und Störungsstatus ohne Werteliste.** `LH-6.1-SYS-007`: Kap. 02 §7.3 definiert Journey-, Segment-, Operations- und Zahlungszustände, Kap. 03 den Zahlungsautomaten; für Fahrzeugstatus und Störungsstatus gibt es keine Zustands- und Übergangslisten. „Fahrtstatus" ist dort nur als Journey-/Segment-Ausführung der Föderation beschrieben, nicht für den lokalen `vehicle_run`.
- **OP-22 – Ebenen der Präzedenzkette weichen ab.** `LH-I.7-CFG-005` nennt fünf Ebenen (global, Mandant, Region, Dienst, Fahrzeug); Kap. 01 §1.6 nennt sechs (zusätzlich „konkretes Produkt"). `kataloge.csv` verwendet weitere Ebenenbezeichnungen (z. B. „Land/Belegart"). Ein Produktobjekt existiert im Datenmodell nicht.
- **OP-23 – Registrierung und Verifikation.** `LH-6.2-IAM-001`: Ob die Registrierung vollständig im IdP oder in einer eigenen Oberfläche erfolgt, ist offen. `passenger_private` hat keine Felder für Verifikationsstatus je Kanal. Für Telefonverifikation wird ein SMS-Kanal benötigt, der laut Meilensteinplan erst in M2 (Betriebsnachrichten) entsteht. Codegültigkeit und Versuchslimits sind nicht beziffert. `/v1/passengers` hat keinen OpenAPI-Vertrag.
- **OP-24 – Kataloge für Unterstützungsmerkmale, Zwecke und Rechtsgrundlagen.** `LH-6.18-SEC-005` und `LH-6.18-SEC-004`: Die Liste transportrelevanter Unterstützungsmerkmale und der Katalog zulässiger Zwecke/Rechtsgrundlagen fehlen; „beteiligte" gegenüber „unbeteiligten" Personen ist nicht rollenscharf definiert. Laut Kap. 01 §1.7 „vor Produktion festzulegen".
- **OP-25 – Lösch- und Aufbewahrungsfristen; Abgrenzung zu M8.** `LH-6.18-SEC-007`: Fristen und Löschmatrix stehen unter D-11; `retention_rule` und `erasure_job` haben keine Feldliste. Der Meilensteinplan ordnet „Lösch- und Auskunftsjobs, Legal Hold" M8 zu; die Grenze zwischen dem M1-Löschlauf und dem M8-Umfang ist festzulegen.
- **OP-26 – Fahrzeugrollen und Fahrerfreigabe in P1.** `LH-I.4-MUL-004`: Die vier Rollen (Eigentümer, Betreiber, Einsatzleister, Abrechnungspartner) sind im DDL nur als `vehicle.owner_org_ref` vorhanden; `vehicle_lease`/`vehicle_assignment` haben keine Feldliste. `LH-I.4-MUL-005`: Die mandantenübergreifende Fahrerfreigabe ist P1, Kooperationsvertrag und Grants sind P2 (M6); für `ResourceAssignment` (Kap. 02 §2) gibt es keinen Tabellennamen. Zu klären ist, auf welcher vertraglichen Grundlage die Freigabe in P1 erfolgt und ob sie im Einbetreiber-Pilot überhaupt produktiv genutzt wird.
- **OP-27 – Unternehmensprofil ohne Datenmodell.** `LH-I.7-CFG-001`: Es gibt weder Tabelle noch Feldliste für Kontakt, Abrechnung, Betriebsart und Rechtsinformationen; `tenant` und `legal_entity` haben keine Standardwährung. Kein Endpunkt in Kap. 05 §5.7.
- **OP-28 – Modellregeln der Angebotsmodelle.** `LH-6.1-SYS-003`: Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr und Bahnhofszubringer sind benannt, ihre unterscheidenden Regeln und Pflichtparameter aber nirgends beschrieben. `service` hat kein Feld für das Angebotsmodell.
- **OP-29 – „Kernkonditionen" und „soweit nötig".** `LH-6.1-SYS-005`: Welche Felder die unveränderlichen Kernkonditionen bilden, welche Änderungsverfahren zulässig sind und wann eine Fahrgastinformation nötig ist, ist nicht abschließend festgelegt.
- **OP-30 – Favoriten und bevorzugte Haltestellen vor dem Haltestellenstamm.** `LH-6.2-IAM-006`: Bevorzugte Haltestellen referenzieren `stop_revision` (M2); `passenger_preference` hat keine Feldliste. Der Unterschied zwischen „Favoriten" und „bevorzugten Haltestellen" ist nicht beschrieben.
- **OP-31 – Betriebsvorgaben ohne Zahlenwerte.** `LH-6.18-SEC-002`: TLS-Mindestversion nicht festgelegt. `LH-6.18-SEC-010`: Backup-Intervall, Aufbewahrungsdauer und Zyklus des Restore-Tests („zyklisch", „regelmäßig") nicht beziffert; Hosting und Werkzeuge hängen an D-05.
- **OP-32 – Organisatorische Abnahmen brauchen Verantwortliche des Auftraggebers.** `LH-6.18-SEC-011` (Übung, Meldeentscheidung) und `LH-6.18-SEC-013` (DSFA-Freigabe) sind nicht allein durch Software erfüllbar; Rollen, Meldewege, Fristen und freigebende Stelle sind nicht benannt (D-11, D-10).
- **OP-33 – Bezeichner, die in keinem Artefakt stehen.** Ereignistypnamen (z. B. `organization.changed`, `membership.granted`, `config.revision_published`, `promise.changed`, `vehicle.role_assignment_changed`, `driver.release_granted`), Port- und Klassennamen, der Fehlercode für unerlaubte Zustandsübergänge (`LH-6.1-SYS-007`) sowie alle als „neue Tabelle" gekennzeichneten Tabellen (Region, Betriebshof, Sitzungssperre, Wirkungsbericht, Fahrerfreigabe) sind Vorschläge dieses Plans. Das Pflichtenheft enthält keinen Ereigniskatalog; er ist vor Codebeginn als Detailvertrag festzulegen.
- **OP-34 – UI-05 in M1 oder M2.** Der Meilensteinplan nennt für M1 nur „UI-17/18 Basis, UI-20 Basis" und ordnet das Passenger-Web mit UI-01 bis UI-05 M2 zu. `LH-6.2-IAM-001`, `LH-6.2-IAM-006`, `LH-6.2-IAM-009`, `LH-6.2-IAM-011` und `LH-6.18-SEC-005` sind aber M1-Anforderungen mit Fahrgastbezug. Diese Datei sieht deshalb eine „UI-05 Basis" in M1 vor (WP-M1-10, WP-M1-28 bis WP-M1-31, WP-M1-44). Zu entscheiden: UI-05 Basis in M1 vorziehen oder die fünf Anforderungen in M1 nur an der API abnehmen und die Oberfläche mit M2 liefern.
- **OP-35 – `service.config_revision_id` ist Pflichtfeld.** Ein Dienst kann laut `kernschema.sql` nur mit vorhandener Konfigurationsrevision angelegt werden; ob diese Revision ein Entwurf sein darf oder publiziert sein muss und wie `service.config_revision_id` bei späteren Publikationen fortgeschrieben wird, ist nicht geregelt. Daraus folgt die Reihenfolge WP-M1-20 vor WP-M1-09. Betrifft `LH-6.1-SYS-002`, `LH-I.4-MUL-002`.
- **OP-36 – Mandantenanlage und Erstausstattung.** Wer einen Mandanten, dessen Rechtsträger und die erste Administrator-Mitgliedschaft anlegt (Plattformbetreiberrolle im Plattformmandanten?) und mit welcher Freigabe, ist im Pflichtenheft nicht beschrieben; Kap. 02 §4.3 verlangt nur, dass Plattformadministration keine automatische Fachdatensicht erhält. Betrifft `LH-I.4-MUL-003`, `LH-6.2-IAM-004`.
- **OP-37 – Geldtyp der Plattform.** M2 und M4 setzen einen Geldtyp aus M1 voraus; der Meilensteinplan nennt ihn für M1 nicht ausdrücklich (nur „einheitliches Fehlermodell (Kap. 05 §5.1)" und in der Zielstruktur „Geld-/Zeittypen" unter `backend/platform`). Er ist hier WP-M1-02 zugeordnet; die Zuordnung ist zu bestätigen.
- **Reihenfolgekonflikt: WP-M1-03 → WP-M2-01, WP-M2-21, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: APIs für Buchungen und Verfügbarkeit (`/v1/ride-offers`, `/v1/bookings`) als Gegenstand der Originalabnahme. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-03 → WP-M3-01, WP-M3-03, WP-M3-53.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: APIs für Fahrzeug- und Betriebsobjekte (`/v1/operator/vehicles`, `/v1/operator/incidents`). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-08 → WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchungsaggregat für den Nachweis „fremde regionale Buchungen". Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-09 → WP-M2-04, WP-M2-05.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Bediengebiet-Geometrie (`service_area`) und Haltepunkte (`stop_revision`). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-10 → WP-M2-35, WP-M2-36.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-11 → WP-M2-07, WP-M2-38, WP-M4-21.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: `tariff_revision` für den Tarifnachweis; Such- und Medienendpunkte. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-11 → WP-M4-22, WP-M4-33, WP-M4-37, WP-M4-64.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Beleg-, Export- und Finanzendpunkte für den vollständigen PH-AT-001. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-12 → WP-M3-40.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Dispositionseingriffe als auditpflichtige Fachaktion (Originalabnahme „Dispositionseingriffe"). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-13 → WP-M2-24, WP-M2-30.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchungs- und Zahlungsbefehle als fachliche Nutzer (Abnahmebezug „Buchungs- und Zahlungsvorgänge"). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-14 → WP-M5-04.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Observability/Alarmkanal für die Betreiberwarnung bei Dead-Letter. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-16 → WP-M5-04.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Observability/Alarmkanal für die Betreiberwarnung bei Dead-Letter. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-17 → WP-M2-01, WP-M2-16, WP-M2-23, WP-M2-30.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchungs-, Fahrt- und Zahlungsaggregat wenden den Rahmen an. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-17 → WP-M3-01, WP-M3-31, WP-M3-53.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fahrzeug- und Störungsaggregat wenden den Rahmen an. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-18 → WP-M2-11, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchung über Sommer-/Winterzeitgrenze als fachlicher Nachweis. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-20 → WP-M2-21, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Dispositionsfall, der seine `config_revision_id` referenziert (Nachweis „historischer Dispositionsfall"). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-20 → WP-M2-04, WP-M2-07.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: `service_area` und `tariff_revision` als Ziel von Bediengebietsbezug und Tarifverknüpfung der Dienstkonfiguration. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-21 → WP-M3-03.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fahrzeugstamm als Scope-Ziel für fahrzeugspezifische Vorgaben. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-22 → WP-M2-09, WP-M2-21, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Angebot/Buchung mit gebundener `config_revision_id` (`fare_quote.config_revision_id`) für den Nachweis „Sonntagsfahrten unverändert" und „Buchungszusage bleibt korrekt". Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-22 → WP-M2-04, WP-M2-05.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: `service_area`, `stop_revision` als administrierbare Gebiets-/Haltestellenobjekte. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-23 → WP-M2-04, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: ImpactProvider für Buchungen und Bediengebiete. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-23 → WP-M4-17.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: ImpactProvider für Schichten. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-25 → WP-M2-21, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Angebotsbildung und Buchung je Angebotsmodell (Testfahrt). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-25 → WP-M2-04, WP-M2-05, WP-M2-07.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: `service_area`, `stop_revision`, Tarifverknüpfung. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-26 → WP-M2-09, WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchungsbestätigung und Tarifprüfung als Anwender des Ports (Nachweis „operative oder tarifliche Regel"). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-27 → WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchung mit `promise_snapshot` als fachlicher Träger der Zusage. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-27 → WP-M2-32.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Betriebsnachrichten (Push/SMS/E-Mail) für die Fahrgastinformation. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-28 → WP-M2-32.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: SMS-Zustellkanal (Betriebsnachrichten/Adapter `notification`) für Telefonverifikation. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-28 → WP-M2-35, WP-M2-36.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-29 → WP-M2-05.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Haltepunktstamm (`stop_revision`) für bevorzugte Haltestellen und Ortsfavoriten. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-29 → WP-M2-35, WP-M2-36.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-30 → WP-M2-32.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Betriebsnachrichten mit Zustellversuchen (`notification`, `notification_attempt`) als Anwender des Ports. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-31 → WP-M2-23.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: minimierter Betreiberpayload der Buchung als fachlicher Anwender. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-31 → WP-M4-06, WP-M4-08.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Arbeitszeit- und Abwesenheitsdaten für den vollständigen AT-49. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-34 → WP-M2-16, WP-M3-03.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fahrzeugstamm und `vehicle_run`. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-34 → WP-M4-33.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Kostenereignis/Abrechnung der Fahrzeugfahrten. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-35 → WP-M3-05.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fahrerstamm und Qualifikationen (`driver`, `qualification_revision`) im Vollausbau. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-35 → WP-M6b-04, WP-M6b-05.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Kooperationsvertrag und Grants als vertragliche Grundlage der Freigabe. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-37 → WP-M2-29.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: PSP-Adapter und `/v1/payments/provider-events/{provider}` für die Prüfung am Zahlungsfluss. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-38 → WP-M5-17.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Penetrationstest und Behandlung der Befunde. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-39 → WP-M5-14, WP-M5-15.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Restore-Übung mit RPO/RTO-Messung einschließlich PSP-Abgleich. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-41 → WP-M5-04.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Observability/Alarmkanal. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-42 → WP-M3-34.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Standortdaten (`position_sample`) als nachzutragende Verarbeitung. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-42 → WP-M4-61, WP-M4-62.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fahrer-/ETA-Daten als nachzutragende Verarbeitung. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-43 → WP-M5-30.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Barrierefreiheitsprüfung der Kernreisen mit Tastatur und Screenreader (UX-Gate). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-44 → WP-M2-35, WP-M2-36.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Passenger-Web (UI-05 im Vollausbau laut Meilensteinplan, OP-34). Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-45 → WP-M2-07, WP-M2-23, WP-M2-38, WP-M2-48.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Buchung, Tarifversion, Such-/Medienendpunkte für die vollständigen Fassungen von PH-AT-001, PH-AT-011, E2E-096, E2E-098. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-45 → WP-M3-44, WP-M3-56.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Fotos/Schadensevidenz für AT-49. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt: WP-M1-45 → WP-M4-06, WP-M4-08, WP-M4-22, WP-M4-64.** M1 darf nur von M0/M1 abhängen; benötigte Fähigkeit aus späterem Meilenstein: Belege, Rechnungen, Arbeitszeit, Abwesenheit, Personalprofil für AT-49, E2E-091, PH-AT-001. Nicht aufgelöst (vgl. OP-01/OP-02).
- **Reihenfolgekonflikt-Hinweis Staging/Alarmkanal.** „Staging“ ist in M0 nur als Gerüst (WP-M0-11) bzw. gemeinsame Integrationsumgebung (WP-M0-35) gedeckt, die endgültige Staging-Umgebung liefert WP-M5-02; den Alarmkanal schließt WP-M0-10 ausdrücklich aus (Alarmierung erst WP-M5-04). Betrifft WP-M1-04, -10, -12, -14, -16, -36, -38, -41.
