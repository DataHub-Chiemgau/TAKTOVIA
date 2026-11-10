# M8e – Plattform, Sicherheit und Betrieb

- **Meilenstein:** M8 – Finanzen, Controlling, ETA, Governance, Strom „Plattform“ (setzt M5 voraus; Nachbareinheiten M8a Belege/Finanzen, M8b ETA/Governance, M8c Kennzahlen/Reporting, M8d Konfiguration/Automatisierung)
- **Phase:** P2 (11 MUSS, 20 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 31 (SYS 4, IAM 5, SEC 9, NFR 8, QAT 5)
- **Anzahl WPs:** 36 (31 mit Anforderungen, 5 Enabler)

**Ziel:** Nach M8e sind die P2-Plattformanforderungen geliefert: Verkehrsprodukttypen mit getrennten Buchungsregeln und Nutzerzugang, Feature-/Rollout-Flags, Mehrsprachigkeit und White-Label je Betreiber; föderierte OIDC-Anmeldung für Fahrer und Mitarbeiter, Gastbuchung, dokumentierter Vertretungszugriff, Vertrauensperson und Schülerkonten. Betroffenenrechte (Auskunft, Export, Korrektur, Löschung) laufen als fristüberwachter Workflow mit orchestriertem Löschjob und Legal Hold; Einwilligungen, Providerpayloads und Auditansichten sind zweckgebunden minimiert. Betrieb und Abnahme sind um Barrierefreiheit aller Oberflächen, Gerätematrix, Rollback, API-Versionierung, Release-Gates, Export-/Archivstrategie, Abnahmeprotokolle, Pilot-, Support- und Übergabeprozess ergänzt.

**Gate-Anteil M8e (Umsetzungsplan §4, Gate M8):** Keiner der Gate-Tests des Meilensteins wird von M8e geführt. M8e liefert Beiträge zu E2E-105 (Auskunftsworkflow und Endpunkt `/v1/privacy/requests`; führend M8b), E2E-079 (maskierte Auditansicht für protokollierte Fehlzugriffe; führend M8b), E2E-101 (verborgene personenbezogene Partnerdaten durch Feldfreigabe je Empfänger; führend M8c) und E2E-102 (Sprach-/Zeitformatierung der Oberflächen; führend M7d/M8c). Als Regression bestätigt M8e die P1-Nachweise AT-18, AT-19, AT-23, AT-24, AT-25, AT-49, AT-53, AT-54, AT-59, AT-61, E2E-091, E2E-096 bis E2E-098 sowie PH-AT-001, PH-AT-011, PH-AT-017 und PH-AT-018 mit den P2-Erweiterungen.

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (SYS-0xx = `LH-6.1-SYS-0xx`, IAM-0xx = `LH-6.2-IAM-0xx`, SEC-0xx = `LH-6.18-SEC-0xx`, NFR-0xx = `LH-6.19-NFR-0xx`, QAT-0xx = `LH-6.20-QAT-0xx`).
- `openapi.yaml` enthält für diesen Bereich nur `/v1/config/revisions/{revision_id}/publish`. Kap. 05 §5.7 nennt als Funktionszuschnitt `/v1/me`, `/v1/passengers`, `/v1/delegations`, `/v1/privacy/requests`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/notifications`, `/v1/bookings`, `/v1/ride-offers`, `/v1/services`. Alle weiteren Pfade dieser Datei sind als **Arbeitsname** gekennzeichnet und werden in WP-M8e-01 festgelegt.
- Im Kern-DDL (`kernschema.sql`) vorhanden und hier genutzt: `tenant`, `membership`, `organization`, `legal_entity`, `service`, `config_revision`, `passenger_private`, `booking`, `journey`, `journey_leg`, `audit_event`, `outbox`, `inbox`, `idempotency_record`, `operation`. Nur als Namen in Kap. 04 §4.4: `passenger_preference`, `consent_revision`, `delegated_access`, `support_case`, `policy_schema`, `retention_rule`, `erasure_job`, `export_job`, `adapter_capability`, `webhook_subscription`, `notification`, `notification_attempt`, `channel_template_revision`, `tariff_revision`, `tariff_rule`. Weitere Tabellen dieser Datei sind **Arbeitsnamen**.
- Grundlagen aus früheren Meilensteinen werden erweitert, nicht neu gebaut: Mitgliedschaft/OIDC/Sitzungssperre, Audit, Löschjob-Grundgerüst (`retention_rule`, `erasure_job`), Einwilligungsschalter (`consent_revision`) und Konfigurationsregister aus M1; Buchung für Dritte (`delegated_access`, `/v1/delegations`) aus M7a; Callcenter-Gastanlage aus M7b; Staging, Lasttest, Restore-Übung, Barrierefreiheitsprüfung der Kernreisen, Betriebshandbuch und Pilotbereitschaft aus M5.
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als Vorschlag bezeichnet (z. B. sechs Monate Parallelbetrieb bei Breaking Changes, Kap. 05 §5.1; signierte Downloads höchstens 60 Sekunden, Kap. 02 §4). Wo das Pflichtenheft keine Werte nennt, verwenden Tests gekennzeichnete Fixturewerte.
- Kein Parameter und keine Kennzahl aus `kataloge.csv` ist dieser Einheit fachlich führend zugeordnet. Genutzt werden `notification_quiet_hours`, `customer_notify_before_pickup_min`, `kpi_aggregation_min_n`, `invoice_archive_years`, `min_booking_lead_min`, `max_booking_horizon_days` und `reporting_timezone`; Feature-Flags, Sprachen und Branding sind **nicht** im Katalog der 80 Parameter enthalten (siehe Offene Punkte).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M8e-01 | Detailverträge M8e: Datenschutzanfrage, Flags, Branding, Identität, Betriebsprozesse | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M1-03, WP-M1-17, WP-M1-04, WP-M1-06, WP-M1-20 |
| WP-M8e-02 | Migration der M8e-Tabellen mit RLS | – (Enabler) | M | WP-M8e-01, WP-M1-01, WP-M1-30, WP-M1-33, WP-M1-19, WP-M1-29, WP-M7a-02, WP-M7a-11, WP-M7b-02, WP-M2-32, WP-M2-33 |
| WP-M8e-03 | Referenzfixtures M8e: zwei Betreiber, Produkttypen, Personen- und Löschdatensatz | – (Enabler) | S | WP-M8e-02, WP-M5-02, WP-M0-24, WP-M0-23 |
| WP-M8e-04 | Verkehrsprodukttypen mit getrennten Buchungsregeln und Nutzerzugang | 1 | M | WP-M8e-02, WP-M8e-03, WP-M1-25, WP-M1-21, WP-M1-26, WP-M2-21, WP-M2-22, WP-M2-23, WP-M7a-05 |
| WP-M8e-05 | Zentraler Feature- und Rollout-Flag-Dienst je Mandant und Verkehrsprodukt | 1 | M | WP-M8e-02, WP-M1-20, WP-M1-21, WP-M1-22, WP-M1-23 |
| WP-M8e-06 | Mehrsprachigkeit je Mandant mit benutzerspezifischer Sprachwahl | 1 | M | WP-M8e-02, WP-M1-43, WP-M1-19, WP-M1-20, WP-M1-29, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M2-33, WP-M3-24, WP-M7a-37, WP-M7a-38 |
| WP-M8e-07 | White-Label: Logos, Farben, Domains, App-Namen, rechtliche Pflichttexte | 1 | M | WP-M8e-02, WP-M8e-06, WP-M1-43, WP-M1-11, WP-M4-21, WP-M7a-37 |
| WP-M8e-08 | Zentraler Identitätsanbieter für Fahrer- und Mitarbeiterkonten | 1 | M | WP-M8e-02, WP-M1-04, WP-M1-06, WP-M1-10, WP-M3-24 |
| WP-M8e-09 | Gastbuchung mit verifiziertem Buchungstoken | 1 | M | WP-M8e-02, WP-M8e-05, WP-M1-28, WP-M1-02, WP-M1-13, WP-M1-33, WP-M2-23, WP-M2-26, WP-M2-32, WP-M7a-03 |
| WP-M8e-10 | Dokumentierter Vertretungszugriff: Besteller und beförderte Person getrennt | 1 | S | WP-M8e-02, WP-M7a-11 |
| WP-M8e-11 | Vertrauensperson mit explizit freigegebenen Benachrichtigungen | 1 | M | WP-M8e-02, WP-M8e-12, WP-M2-32, WP-M7b-24, WP-M7b-31 |
| WP-M8e-12 | Einwilligungsregister: Rechtsgrundlage je Zweck und nachvollziehbarer Widerruf | 1 | M | WP-M8e-02, WP-M1-30, WP-M7b-24, WP-M7b-26 |
| WP-M8e-13 | Minderjährigen- und Schülerkonten: Sichtbarkeit und Kontaktregeln | 1 | M | WP-M8e-04, WP-M8e-10, WP-M8e-11, WP-M1-07, WP-M1-08, WP-M1-31, WP-M1-12, WP-M1-46, WP-M3-36, WP-M3-26, WP-M3-30, WP-M3-32, WP-M7b-35, WP-M7b-30, WP-M7b-17 |
| WP-M8e-14 | Legal Hold: Setzen, Wirkung, Aufhebung | – (Enabler) | S | WP-M8e-02, WP-M1-33, WP-M1-46, WP-M1-22 |
| WP-M8e-15 | Orchestrierter Lösch- und Auskunftsjob über DB, Objektspeicher, Suchindex, Partnerprojektion | – (Enabler) | M | WP-M8e-02, WP-M8e-14, WP-M1-33, WP-M1-15, WP-M1-11, Klärung Auftraggeber (Suchindex), WP-M5-14, WP-M5-15, WP-M6a-05, WP-M6a-12, WP-M6b-09 |
| WP-M8e-16 | Betroffenenrechte-Workflow: Auskunft, Export, Korrektur, Löschung mit Fristüberwachung | 1 | M | WP-M8e-14, WP-M8e-15, WP-M1-04, WP-M1-06, WP-M1-46, WP-M1-12, WP-M1-17, WP-M1-43, WP-M1-13, WP-M2-32 |
| WP-M8e-17 | Selbstbedienung: Export und Löschung von Profil- und Buchungsdaten | 1 | M | WP-M8e-09, WP-M8e-16, WP-M2-36, WP-M2-23, WP-M2-45 |
| WP-M8e-18 | Providerpayload-Minimierung gegen freigegebenes Datenflussdiagramm | 1 | M | WP-M8e-02, WP-M1-32, WP-M1-12, WP-M2-29, WP-M2-32, WP-M6a-05, WP-M6a-07, WP-M6b-29, WP-M6b-23 |
| WP-M8e-19 | Rollenabhängig maskierte Auditansicht | 1 | M | WP-M8e-02, WP-M1-46, WP-M1-12, WP-M1-31, WP-M1-07, WP-M1-43, WP-M5-06 |
| WP-M8e-20 | PBefG-Betriebsform als Freigabevoraussetzung je Dienst | 1 | S | WP-M8e-02, WP-M8e-04, WP-M1-22, WP-M1-11, WP-M1-43, WP-M1-12 |
| WP-M8e-21 | Bewertung und Freigabe der Mobilitätsdatenpflichten vor Go-live | 1 | S | WP-M8e-20, WP-M8c-30, WP-M8c-27 |
| WP-M8e-22 | Regelmäßige Sicherheitsprüfungen und Abhängigkeitsupdates mit Release-Sperre | 1 | S | WP-M1-38, WP-M0-14, WP-M5-22 |
| WP-M8e-23 | Responsive Oberflächen und Gerätetestmatrix | 1 | M | WP-M8e-03, WP-M1-43, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M3-36, WP-M3-24, WP-M3-25, WP-M3-26, WP-M3-03, WP-M4-18, WP-M4-19, WP-M4-20, WP-M4-38, WP-M4-44, WP-M4-59, WP-M5-02, WP-M5-23, WP-M6a-16, WP-M6c-30, WP-M7a-37, WP-M7a-38, WP-M7b-35 |
| WP-M8e-24 | Barrierefreiheit aller Nutzeroberflächen nach Prüfplan (Ziel WCAG 2.2 AA) | 1 | L | WP-M8e-06, WP-M8e-07, WP-M8e-23, WP-M1-43, WP-M3-24, WP-M3-25, WP-M3-26, WP-M5-18, WP-M5-30, WP-M7a-37, WP-M7a-38 |
| WP-M8e-25 | Unabhängiger Barrierefreiheitsaudit und dienstbezogene Rechtslageprüfung | 1 | S | WP-M8e-07, WP-M8e-20, WP-M8e-24 |
| WP-M8e-26 | Leitstelle: Tastaturbedienung der Schlüsselaktionen und Schutzdialog | 1 | M | WP-M3-36, WP-M3-40, WP-M7b-04, WP-M7b-08, WP-M7b-13, WP-M7b-32, WP-M6a-16 |
| WP-M8e-27 | Kritische Fehlermeldungen mit Fehler-ID und Handlungshinweis | 1 | S | WP-M8e-06, WP-M1-02, WP-M1-43, WP-M3-24, WP-M7a-37 |
| WP-M8e-28 | Semantische API-Versionierung und Übergangsfristen | 1 | M | WP-M8e-02, WP-M1-03, WP-M6b-04, WP-M6b-23, WP-M6b-30, WP-M6a-05 |
| WP-M8e-29 | Release-Rollback ohne Verlust bestätigter Buchungen | 1 | M | WP-M8e-03, WP-M5-02, WP-M5-14, WP-M5-16, WP-M2-23, WP-M2-30, WP-M0-23 |
| WP-M8e-30 | Lieferkette: Staging, Performance-/Sicherheitstests, CI/CD-Gates, Incident-Management | 1 | M | WP-M8e-22, WP-M8e-29, WP-M5-02, WP-M5-22, WP-M5-10, WP-M5-04, WP-M5-26 |
| WP-M8e-31 | Export-/Archivstrategie und Portabilität der Stammdaten | 1 | M | WP-M8e-02, WP-M8e-03, WP-M1-11, WP-M1-15, WP-M5-25, WP-M5-19 |
| WP-M8e-32 | Vier-Augen-Freigabe für Tarif- und Regelkonfiguration | 1 | M | WP-M8e-02, WP-M1-22, WP-M2-07, WP-M2-09, WP-M8d-03, WP-M8d-05, WP-M8d-21 |
| WP-M8e-33 | Signierbare Abnahmeprotokolle für Last-, Failover-, Datenschutz- und Barrierefreiheitstests | 1 | M | WP-M8e-16, WP-M8e-18, WP-M8e-19, WP-M8e-24, WP-M8e-30, WP-M5-10, WP-M5-15, WP-M5-17, WP-M5-31 |
| WP-M8e-34 | Pilotbetrieb mit Abbruchkriterien, Rückfallprozess und Pilotbericht | 1 | S | WP-M8e-05, WP-M8e-33, WP-M5-29, WP-M5-26, WP-M8c-22, WP-M8c-25, WP-M8c-27 |
| WP-M8e-35 | Sicherheits-/Supportkanäle, Fehlerklassifikation P0–P3 und Musterincident | 1 | S | WP-M8e-30, WP-M1-41, WP-M5-26 |
| WP-M8e-36 | Abschlussübergabe: Dokumentation, Konfigurationen, Wiederherstellungsmittel; M8e-Abnahme | 1 | M | WP-M8e-04 bis WP-M8e-13, WP-M8e-16 bis WP-M8e-35, WP-M5-27, WP-M5-14, WP-M5-31 |

## Arbeitspakete

### WP-M8e-01 – Detailverträge M8e: Datenschutzanfrage, Flags, Branding, Identität, Betriebsprozesse

- **Ziel:** Vor Codebeginn liegen alle Verträge der Einheit vor: OpenAPI der Identitäts-, Datenschutz- und Konfigurationspfade, DDL der Ergänzungstabellen, Zustandsautomaten und die Vorlagen der Betriebs- und Abnahmedokumente.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzung für `/v1/privacy/requests` (anlegen, lesen, Statusübergänge, Legal Hold), Erweiterungen von `/v1/me` (Sprache, wirksame Flags, Branding), `/v1/passengers` (Gast, Vertrauensperson, Schülerkonto), `/v1/delegations` (Dokumentationsfelder), `/v1/bookings` (Zugriff per Buchungstoken), `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact` (Flags, Branding, Sprachen); Festlegung der Arbeitsnamen-Pfade dieser Datei (`/v1/audit/events`, `/v1/privacy/legal-holds`, `/v1/branding`, `/v1/compliance/approvals`, `/v1/exports/master-data`, `/v1/guest/bookings`) oder deren Abbildung auf Katalogpfade; Konventionen nach Kap. 05 §5.1.
  - Datenbank: DDL-Entwurf für Erweiterungen von `consent_revision`, `delegated_access`, `passenger_preference`, `retention_rule`, `erasure_job`, `export_job`, `policy_schema` sowie für die Arbeitsnamen-Tabellen aus WP-M8e-02.
  - Backend: Zustandsautomat Datenschutzanfrage (Arbeitsstand: `RECEIVED → IDENTITY_VERIFIED → IN_PROGRESS → PARTIALLY_BLOCKED/COMPLETED/REJECTED`), Zustandsautomat Legal Hold, Zustandsautomat Compliance-Freigabe, Zustandsautomat Gast-Buchungstoken; JSON-Schema für Flag-Definition, Branding, Datenflussfreigabe je Empfänger und Stammdaten-Exportpaket; Vertrag der internen Ports „Auskunftsinhalt liefern“ und „Aufbewahrung prüfen“ der Datenschutzanfrage (Eingaben, Ergebnis je Datenkategorie, Fehlerfälle) mit Stub, gegen den WP-M8e-16 baut und den die Einheit M8b fachlich implementiert.
  - Adapter/Worker: Vorlagen für Abnahmeprotokoll je Testart, Pilotbericht, Incident-Kommunikationsplan P0–P3, Änderungsdokumentation der API, Übergabeinventar.
- **Detailverträge vorab:** dieses WP ist der Detailvertrag; Reviewrollen gemäß Detailvertragsprozess aus M0; Abstimmung der Zuständigkeit `/v1/privacy/requests` mit M8b (WP-M8b-24 liefert Aufbewahrungsprüfung und Auskunftsinhalt aus dem Verarbeitungsregister, M8e den Workflow und Endpunkt).
- **Akzeptanzkriterien:**
  1. `validate_artifacts.py` läuft mit den ergänzten OpenAPI-Dateien fehlerfrei.
  2. Für jede der 31 Anforderungen der Einheit ist ein Detailvertrag benannt; für jede der 11 MUSS-Anforderungen zusätzlich mindestens ein positiver ausführbarer Testfall (Kap. 08 §8.4).
  3. Jeder Arbeitsname dieser Datei ist bestätigt oder durch den endgültigen Namen ersetzt.
  4. Jeder Punkt aus „Offene Punkte“ ist entschieden oder als gekennzeichnete Annahme mit Verantwortlichem im Vertrag festgehalten.
  5. Die Portverträge „Auskunftsinhalt liefern“ und „Aufbewahrung prüfen“ liegen als versionierte, schemageprüfte Fassung mit Stub vor (Offener Punkt 40).
- **Tests:** positiv: Vertragslinting, Schemavalidierung der Beispielpayloads. negativ: Beispielpayload einer Datenschutzanfrage ohne Anfrageart bzw. eines Flags ohne Scope wird vom Schema abgelehnt.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess und CI-Vertragsprüfung)
  - extern: WP-M1-03, WP-M1-17, WP-M1-04, WP-M1-06, WP-M1-20 (OpenAPI je Modul, Zustandsautomaten-Rahmen, bestehende Identitäts- und Konfigurationsverträge)
- **Entscheidungen:** D-11 (Löschfristen, Legal Holds, Auskunfts-/Exportprozess, Supportzugriff), D-05 (Hosting, SLA, Wartungsregel, Pilot), D-02 (Isolationsstufe, betrifft eigene Domains) – blockieren nur Produktivschaltung; die Verträge werden mit gekennzeichneten Annahmen erstellt.
- **Größe:** M
- **Nicht im Umfang:** Verträge für ETA-/Governance-Objekte (M8b), Berichtsjobs (M8c), Parameterkatalog-Erweiterung und Automatisierungsregeln (M8d).

### WP-M8e-02 – Migration der M8e-Tabellen mit RLS

- **Ziel:** Alle für M8e benötigten Tabellen und Spaltenerweiterungen sind als fortsetzbare expand/contract-Migration vorhanden und unter der Runtime-Rolle mandantengetrennt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Flyway-Migrationen für Erweiterungen von `consent_revision` (Zweck, Rechtsgrundlage, Widerrufszeitpunkt), `delegated_access` (Dokumentationsreferenz, Nachweisart), `passenger_preference` (Sprache), `passenger_private` (Kontoart Gast/Schüler), `retention_rule`, `erasure_job` (Schritte je Zielsystem), `export_job` (Zweck Auskunft/Portabilität), `policy_schema` (Schemas für Flags, Branding, Sprachen), `channel_template_revision` (Zweck und Sprache je Vorlage), `booking` (getrennte Referenz auf Buchungsersteller und beförderte Person samt Berechtigungsrevision, sofern nicht bereits durch M7a ergänzt – im Kern-DDL fehlen diese Felder), `config_revision` (zusätzlicher CHECK „Prüfer ungleich Ersteller“, sofern nicht bereits in M1 ergänzt – das Kern-DDL prüft nur „publiziert nur mit Prüfer“); neue Tabellen als Arbeitsnamen: `privacy_request`, `privacy_request_step`, `legal_hold`, `feature_flag_definition`, `tenant_branding_revision`, `trusted_contact`, `guest_booking_token`, `data_flow_release`, `compliance_approval`, `service_access_group` (geschlossene Nutzergruppe je Dienst, WP-M8e-04), `identity_provider_binding` (zugelassener föderierter Issuer je Mandant, WP-M8e-08; `membership` enthält `issuer`/`subject` bereits), `api_client_usage` (Nutzungsregister je Client und Operation, WP-M8e-28). Jede Tabelle mit `(tenant_id,id)`, zusammengesetzten FKs, RLS-Policy `tenant_isolation`, Revision und Audit (Kap. 04 §4.4); append-only für `consent_revision`, `legal_hold`-Historie und `compliance_approval`.
  - Datenbank (Invarianten Kap. 04 §4.5): „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“, „Gegenereignis statt Änderung“ für die append-only Tabellen, „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ für `tenant_branding_revision` und Flag-Revisionen in `config_revision`.
- **Detailverträge vorab:** DDL aus WP-M8e-01; Lösch-/Aufbewahrungsklasse je neuer Tabelle; Indexplan (`(tenant_id,state,updated_at)` für Arbeitslisten, Kap. 04 §4.7).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und aus dem freigegebenen M5-/M7-Vorstand mit Testdaten zweier Mandanten fehlerfrei.
  2. Jede neue Tabelle hat RLS aktiviert und erzwungen; ohne Mandantenkontext liefert sie keine Zeile.
  3. UPDATE/DELETE auf append-only Tabellen wird unter der Runtime-Rolle abgelehnt.
  4. Die Runtime-Rolle besitzt keine der neuen Tabellen und kein TRUNCATE-/DDL-Recht (Kap. 04 §4.6).
  5. Jede neue Tabelle und jede neue Spalte mit Personenbezug hat eine Lösch-/Aufbewahrungsklasse und ist im Modulport „personenbezogene Daten liefern/löschen“ (WP-M8e-15) registriert; eine Tabelle ohne Klasse lässt die Migrationsprüfung scheitern.
  6. Jede in dieser Datei als Arbeitsname geführte Tabelle wird von genau dieser Migration angelegt; kein Fach-WP legt eigene Tabellen außerhalb der Migration an.
- **Tests:** positiv: Migration frisch und aus Vorstand, Backfill fortsetzbar. negativ: Zugriff auf Zeile des Mandanten B im Kontext A, FK auf fremden Mandanten, UPDATE auf `consent_revision`, manipulierte `tenant_id` im Schreibrequest (Kap. 05 §5.8), Publikation einer `config_revision` mit `approved_by` gleich `created_by`. konkurrierend (Datentrennung): zwei parallele Transaktionen mit unterschiedlichem Tenantkontext auf demselben Pool; überlappende Branding-Revisionen parallel publizieren – genau eine gewinnt. Regression: PH-AT-001.
- **Abhängigkeiten:** intern: WP-M8e-01.
  - extern: WP-M1-01, WP-M1-30, WP-M1-33, WP-M1-19, WP-M1-29 (Tenantkontext, RLS-Laufzeitbasis, Isolations-Testharness; Tabellen `consent_revision`, `retention_rule`, `erasure_job`, `policy_schema`, `passenger_preference`)
  - extern: WP-M7a-02, WP-M7a-11 (Tabelle `delegated_access`, Buchungsbezug `booking_subject`)
  - extern: WP-M7b-02, WP-M2-32, WP-M2-33 (Tabellen `notification`, `notification_attempt`, `channel_template_revision`, `support_case`)
- **Entscheidungen:** D-02 – Bau mit RLS-Default; spätere Datenbank je Mandant wäre Umbau.
- **Größe:** M
- **Nicht im Umfang:** `processing_activity` und ETA-Tabellen (M8b); `export_job`-Nutzung für Berichte (M8c).

### WP-M8e-03 – Referenzfixtures M8e: zwei Betreiber, Produkttypen, Personen- und Löschdatensatz

- **Ziel:** Ein deterministischer Datensatz macht alle M8e-Akzeptanzkriterien wiederholbar prüfbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Seed in `tests/acceptance` mit zwei Betreibermandanten (unterschiedliches Branding, unterschiedliche Sprachen), je einem Dienst der Typen Firmen-Shuttle, AST/ALT, Bürgerbus, Schüler- und Eventverkehr; Personen: registrierter Fahrgast mit Buchungen/Zahlung/Beleg, Gast, Schüler mit Erziehungsberechtigtem, Fahrgast mit Vertrauensperson, Fahrer und Disponent im Test-Realm des OIDC-Anbieters aus M1 (der zweite, föderierte Issuer wird in WP-M8e-08 ergänzt und in den Seed übernommen), eine Person mit Konten in beiden Mandanten; Konfigurations- und Tarifrevisionen in den Zuständen Entwurf, geprüft und publiziert; Auditereignisse mit Kontakt-, Standort- und Unterstützungsfeldern; ein Objekt unter Legal Hold; Löschdatensatz mit Objekten in Objektspeicher, Suchindex und Partnerprojektion; steuerbare Uhr; simulierte Provider (Benachrichtigung, PSP, Routing, Partner) mit Payloadaufzeichnung.
- **Detailverträge vorab:** Fixturekatalog mit Seed und erwarteten Ergebnissen; Kennzeichnung aller Fixturewerte, die das Pflichtenheft nicht vorgibt.
- **Akzeptanzkriterien:**
  1. Der Seed ist aus leerem Schema in einem Lauf einspielbar und liefert bei gleichem Seed identische IDs.
  2. Der Datensatz enthält ausschließlich synthetische Personen (Kap. 01 §1.7: keine Produktionsdaten in Entwicklung).
  3. Jeder Test dieser Einheit referenziert Seed, Migrationsstand und Konfigurationsrevision (Kap. 08 §8.4).
- **Tests:** positiv: Seedlauf, Wiederholung idempotent. negativ: Seed im Kontext eines Mandanten legt keine Zeilen im anderen an.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M5-02, WP-M0-24, WP-M0-23 (Staging-/Performance-Umgebung mit Referenzdatenbestand, Sandbox-Partner und PSP-Simulator)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Lastprofile (M5, WP-M8e-33); ETA-Beobachtungsgenerator (M8b).

### WP-M8e-04 – Verkehrsprodukttypen mit getrennten Buchungsregeln und Nutzerzugang

- **Ziel:** Ein Betreiber konfiguriert Firmen-Shuttle, AST/ALT, Bürgerbus, Schüler- und Eventverkehr als eigene Produkte; Buchungsregeln und Nutzerzugang wirken je Produkt getrennt.
- **Anforderungen:** `LH-6.1-SYS-004`
- **Liefergegenstände:**
  - Backend: Modul `config`/`service-geo`: Produkttyp und Zugangsregel als Teil der Dienstkonfiguration (offen, geschlossene Nutzergruppe, Berechtigungsnachweis); Prüfung im Policy-Port bei Angebot, Hold und Confirm; keine eigene Fachlogik je Typ, nur Konfiguration (Baustein „Tenant-/Service-Konfiguration; Zustandsautomaten“).
  - Datenbank: `service`, `config_revision` (`scope_kind` Dienst/Produkt), `policy_schema` (Schema „Produktkonfiguration“), `membership`/Arbeitsname `service_access_group` für geschlossene Nutzergruppen (Migration in WP-M8e-02), `audit_event`.
  - API: `/v1/services`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/ride-offers`, `/v1/bookings`.
  - Oberfläche: UI-17 (Produkt anlegen/freigeben, Zustand „konkurrierende Version“), UI-01/UI-02 (nur zugängliche Produkte; Ablehnungsgrund bei fehlendem Zugang).
  - Konfigurationsparameter: produktbezogene Wirkung von `min_booking_lead_min`, `max_booking_horizon_days`, `pooling_allowed`, `pickup_window_min`.
- **Detailverträge vorab:** JSON-Schema Produktkonfiguration mit Typ-Enum; Regelkatalog Nutzerzugang; Abgrenzung zu „Buchungsregeln je Verkehrsprodukt“ aus M7a.
- **Akzeptanzkriterien:**
  1. Für jeden der fünf Produkttypen lässt sich ein Produkt allein über Konfiguration anlegen und freigeben (SYS-004).
  2. Zwei Produkte desselben Betreibers mit unterschiedlichen Buchungsregeln werden getrennt geprüft: dieselbe Anfrage wird in Produkt X angenommen und in Produkt Y mit Regelgrund abgelehnt (SYS-004).
  3. Ein Nutzer ohne Zugang zu einem geschlossenen Produkt (z. B. Firmen-Shuttle, Schülerverkehr) erhält dort weder Angebot noch Buchung, in einem offenen Produkt desselben Betreibers schon (SYS-004).
  4. Die Zugangsprüfung erfolgt serverseitig bei Angebot, Hold und Confirm.
  5. Eine Produktrevision mit künftigem Wirksamkeitsdatum wirkt erst ab diesem Zeitpunkt; bereits bestätigte Buchungen behalten die zugesagte Regelrevision (Prüfstrategie „zeitbezogene Konfigurationsfälle“).
  6. Produkte und Nutzergruppen eines Mandanten sind für einen anderen Mandanten weder sichtbar noch buchbar; Anlage, Freigabe und Änderung der Zugangsregel sind auditiert.
  7. UI-17 zeigt für die Produktpflege die Zustände leer (kein Produkt), Validierungsfehler, fehlendes Recht und „konkurrierende Version“; UI-01/UI-02 zeigen bei fehlendem Zugang einen verständlichen Grund ohne Hinweis auf fremde Nutzergruppen.
- **Tests:** positiv: je Typ Buchung durch berechtigten Nutzer. negativ: unberechtigter Nutzer per API direkt auf Produkt-ID; Entzug der Gruppenmitgliedschaft zwischen Angebot und Confirm; Produkt-ID eines fremden Mandanten (404 ohne Existenzhinweis); Revision mit Wirksamkeit in der Zukunft wird vor Stichtag nicht angewendet. konkurrierend (Kapazität/Sicherheit): Zugangsentzug parallel zum Confirm – kein Confirm nach wirksamem Entzug; zwei Produkte auf demselben Fahrzeug überbuchen nicht. Regression: AT-01, AT-15.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-03.
  - extern: WP-M1-25, WP-M1-21, WP-M1-26 (Dienstkonfiguration, Präzedenzkette, Policy-Port)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23 (Angebot, Hold, Confirm im Buchungskern)
  - extern: WP-M7a-05 (Buchungsregeln je Verkehrsprodukt)
- **Entscheidungen:** D-05 (Pilotprodukte) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Genehmigungsnachweis der Betriebsform (WP-M8e-20); Schülerkonten (WP-M8e-13); Tarifmodelle je Produkt (M2/M6c).

### WP-M8e-05 – Zentraler Feature- und Rollout-Flag-Dienst je Mandant und Verkehrsprodukt

- **Ziel:** Merkmale lassen sich je Mandant und Verkehrsprodukt zentral ein- und ausschalten; ein deaktiviertes Merkmal ist weder in der Oberfläche noch über die API nutzbar.
- **Anforderungen:** `LH-6.1-SYS-008`
- **Liefergegenstände:**
  - Backend: Modul `config`: Flag-Register (stabile Flag-ID, Default aus, Scope Mandant/Produkt, Rollout-Stufe), Auswertung über die bestehende Präzedenzkette; serverseitiger Guard an Endpunkten und Fachoperationen; Flag-Änderung als `config_revision` mit Freigabe und Audit.
  - Datenbank: `feature_flag_definition` (Arbeitsname), `config_revision`, `policy_schema`.
  - API: `/v1/config/effective` (wirksame Flags), `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/revisions/{revision_id}/publish`, `/v1/me` (Flags der Sitzung); Fehler `403` mit stabilem Code für deaktiviertes Merkmal (Arbeitsname `FEATURE_DISABLED`).
  - Oberfläche: UI-17/Konfigurationseditor (Kap. 06 §6.8) – Flag-Liste je Mandant/Produkt; alle Clients blenden deaktivierte Merkmale aus.
  - Ereignisse/Outbox: Flag-Änderung als Ereignis zur Cache-Invalidierung.
- **Detailverträge vorab:** Flag-Katalog (welche Merkmale schaltbar sind); Guard-Annotation; Verhalten bereits gebundener Zusagen bei Abschaltung.
- **Akzeptanzkriterien:**
  1. Ein für Mandant A deaktiviertes Merkmal ist in dessen Oberflächen nicht sichtbar und jeder zugehörige API-Aufruf wird serverseitig abgelehnt (SYS-008).
  2. Dasselbe Merkmal bleibt für Mandant B bzw. ein anderes Verkehrsprodukt von A nutzbar (SYS-008).
  3. Eine Flag-Änderung wirkt spätestens mit dem nächsten Request nach Publikation; bestehende Zusagen bleiben gültig.
  4. Jede Flag-Änderung ist mit Ersteller, Prüfer, Grund und Wirksamkeit auditiert.
  5. Ein Rollout-Flag lässt sich stufenweise schalten: zunächst für ein einzelnes Verkehrsprodukt, danach für den ganzen Mandanten; jede Stufe ist eine eigene freigegebene Revision und einzeln rücknehmbar (SYS-008).
  6. Die wirksamen Flags stammen ausschließlich aus dem zentralen Konfigurationsdienst; kein Client und kein Modul führt eine eigene abweichende Flag-Quelle (SYS-008).
  7. Ein Offline-Client (Driver-App) mit veraltetem Flag-Stand kann ein inzwischen deaktiviertes Merkmal nicht wirksam nutzen: der Server lehnt die nachträglich synchronisierte Aktion mit dem Fehlercode für deaktivierte Merkmale ab und der Client zeigt dies an.
  8. Die Flag-Liste in UI-17 hat die Zustände leer, Ladefehler und fehlendes Recht; ein Nutzer ohne Konfigurationsrecht sieht keine Flag-Pflege.
- **Tests:** positiv: Aktivierung je Produkt. negativ: direkter API-Aufruf bei ausgeblendeter Oberfläche; manipulierter Client-Flagwert; veralteter Cache; Flag eines fremden Mandanten lesen oder setzen; Publikation ohne `If-Match` (428) bzw. mit veralteter Revision (412); wiederholte Publikation mit gleichem `Idempotency-Key` wirkt genau einmal, mit abweichendem Request wird sie abgelehnt (PH-AT-011); Offline-Sync nach Abschaltung. konkurrierend (Sicherheit): Abschaltung parallel zu laufendem Request – kein neuer Vorgang nach Wirksamkeit, laufende Operation endet definiert. Regression: E2E-096, E2E-097.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-20, WP-M1-21, WP-M1-22, WP-M1-23 (Konfigurationsrevisionen, Präzedenzkette, Vier-Augen-Publikation, Wirkungsanalyse)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** prozentualer ETA-Modellrollout `eta_model_rollout_percent` und `eta_personalization_enabled` (M8b); Capability Registry für Partner (M6).

### WP-M8e-06 – Mehrsprachigkeit je Mandant mit benutzerspezifischer Sprachwahl

- **Ziel:** Je Mandant sind mehrere Sprachen konfigurierbar; Nutzer wechseln die Sprache, ohne Eingaben oder Buchungen zu verlieren.
- **Anforderungen:** `LH-6.1-SYS-009`
- **Liefergegenstände:**
  - Backend: Sprachliste und Standardsprache je Mandant als Konfiguration; Sprachauflösung Nutzerpräferenz → Mandantenstandard → Deutsch; lokalisierte Fehler-/Benachrichtigungstexte über Schlüssel; Formatierung von Datum/Zeit/Betrag je Locale ohne Änderung der Fachwerte.
  - Datenbank: `passenger_preference` (Sprache), `config_revision`, `channel_template_revision` (Sprachvarianten).
  - API: `/v1/me`, `/v1/passengers`, `/v1/config/effective`; `Accept-Language` nur als Vorschlag.
  - Oberfläche: Sprachumschalter in `web/passenger`, `web/operator`, `mobile/driver`, `mobile/passenger`; UI-05 (Sprache), UI-01 bis UI-04 (Wechsel mitten im Vorgang).
- **Detailverträge vorab:** Textkatalogformat und Übersetzungsprozess; Liste der im Pilot benötigten Sprachen (im Pflichtenheft nicht benannt); Behandlung nicht übersetzter Schlüssel.
- **Akzeptanzkriterien:**
  1. Ein Mandant konfiguriert mindestens zwei Sprachen; ein Nutzer wählt seine Sprache und sie bleibt über Sitzungen erhalten (SYS-009).
  2. Ein Sprachwechsel während Suche, Checkout oder in einer aktiven Reise ändert die Texte; Formulareingaben, Angebot, Hold und Buchung bleiben unverändert, es ist keine erneute Buchung nötig (SYS-009).
  3. Eine nicht konfigurierte Sprache fällt auf den Mandantenstandard zurück; fehlende Schlüssel erscheinen nie als Rohschlüssel.
  4. Rechtlich gebundene Texte der Zusage (akzeptierte Bedingungen) bleiben in der akzeptierten Fassung referenziert.
  5. Datum, Uhrzeit und Beträge werden je Locale nur anders dargestellt; Zeitpunkt, Zeitzone des Dienstes und Betrag bleiben identisch – auch für Fahrten über Mitternacht und über den Sommerzeitwechsel (Beitrag zu E2E-102).
  6. Der Sprachwechsel setzt die Sprachauszeichnung der Oberfläche, sodass Screenreader die gewählte Sprache verwenden; der Umschalter ist per Tastatur bedienbar.
  7. Die Driver-App wechselt die Sprache auch ohne Verbindung; noch nicht synchronisierte Ereignisse der Offline-Queue bleiben erhalten.
  8. Die Sprachwahl eines Nutzers wirkt auch auf Benachrichtigungen an ihn, soweit eine Vorlage in dieser Sprache freigegeben ist; sonst gilt der Mandantenstandard.
- **Tests:** positiv: Wechsel in UI-03 bei laufendem Hold. negativ: nicht freigegebene Sprache per API; Sprache eines anderen Mandanten; Sprachwechsel im Offlinezustand mit gefüllter Queue; Benachrichtigungsvorlage ohne Sprachvariante. Beitrag zu E2E-102 (Darstellung von Kalender- und Zeitangaben über Mitternacht/Sommerzeitwechsel in zwei Sprachen); Regression: AT-19, PH-AT-018.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-43, WP-M1-19, WP-M1-20, WP-M1-29 (Web-Designsystem, Konfigurationsregister, Profil/Präferenzen)
  - extern: WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M2-33 (Passenger-PWA UI-01 bis UI-05 mit Internationalisierungsschlüsseln; Benachrichtigungsvorlagen)
  - extern: WP-M3-24 (Driver-App mit Offline-Queue)
  - extern: WP-M7a-37, WP-M7a-38 (native Passenger-App)
- **Entscheidungen:** D-05 (Pilotregion, Sprachen) – nur Inhalte.
- **Größe:** M
- **Nicht im Umfang:** Übersetzungsinhalte selbst über Deutsch und eine Prüfsprache hinaus; einfache Sprache (M7a).

### WP-M8e-07 – White-Label: Logos, Farben, Domains, App-Namen, rechtliche Pflichttexte

- **Ziel:** Zwei Betreiber erscheinen in ihren Clients unterscheidbar mit eigenem Erscheinungsbild und eigenen Pflichttexten, auf derselben Fachlogik.
- **Anforderungen:** `LH-6.1-SYS-010`
- **Liefergegenstände:**
  - Backend: Branding-Revision je Betreiber (Logo-Referenz, Farbtokens, App-Name, Domainbindung, Impressum/Datenschutz/Beförderungsbedingungen als versionierte Texte je Sprache); Auflösung des Brandings aus der serverseitig verifizierten Domain-/Mandantenbindung, nie als Rechtequelle (Kap. 05 §5.1); Kontrastprüfung der Farbtokens bei Validierung.
  - Datenbank: `tenant_branding_revision` (Arbeitsname), `config_revision`, Logo im Objektspeicher mit Mandantenpräfix.
  - API: `/v1/branding` (Arbeitsname, öffentlich lesbar, nur Branding), `/v1/config/revisions`, `/validate`.
  - Oberfläche: Theme-Schicht im Designsystem; `web/passenger`, `mobile/passenger`, Benachrichtigungsvorlagen; UI-17/Admin für Branding-Pflege mit Vorschau.
- **Detailverträge vorab:** Token-Schema; Domain- und Zertifikatsprozess; App-Namen in nativen Apps (Build-Varianten oder Laufzeit, siehe Offene Punkte); Pflichttextkatalog.
- **Akzeptanzkriterien:**
  1. Zwei Betreiber erscheinen in Web und App mit unterschiedlichem Logo, Farben, App-Namen und Pflichttexten; beide laufen auf demselben Build der Fachlogik ohne betreiberspezifische Codezweige (SYS-010).
  2. Eine Betreiber-Domain führt ausschließlich in den Kontext des gebundenen Betreibers; sie gewährt keine zusätzlichen Rechte.
  3. Ein Farbschema, das den vereinbarten Kontrast unterschreitet, wird bei der Validierung abgelehnt.
  4. Pflichttexte sind versioniert; die bei Buchung akzeptierte Fassung bleibt referenziert.
  5. Eine Branding-Revision wird erst nach Freigabe durch eine zweite Person wirksam; Erstellung, Freigabe und Publikation sind auditiert.
  6. Fehlt ein Pflichttext in einer konfigurierten Sprache, scheitert die Validierung der Branding-Revision; die Branding-Pflege zeigt die Zustände leer, Validierungsfehler und fehlendes Recht.
  7. Ohne Branding-Revision bzw. bei nicht ladbarem Logo erscheint ein neutrales Standard-Erscheinungsbild; die Fachfunktion bleibt nutzbar.
- **Tests:** positiv: zwei Brandings parallel. negativ: Domain von A mit Sitzung von B; Logo-Upload mit unzulässigem Typ; fremdes Logo-Objekt per URL. konkurrierend (Datentrennung): gleichzeitige Publikation zweier Branding-Revisionen – genau eine wirksam. Regression: AT-18.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-06.
  - extern: WP-M1-43, WP-M1-11, WP-M4-21 (Web-Designsystem, Isolation außerhalb der Datenbank (Objektspeicher), Uploadprüfung)
  - extern: WP-M7a-37 (native Passenger-App)
- **Entscheidungen:** D-02 (Isolationsstufe/Domains), D-05 (Hosting) – Produktivschaltung eigener Domains.
- **Größe:** M
- **Nicht im Umfang:** Driver-App-Branding (im Pflichtenheft nicht gefordert); Store-Veröffentlichung getrennter Apps.

### WP-M8e-08 – Zentraler Identitätsanbieter für Fahrer- und Mitarbeiterkonten

- **Ziel:** Fahrer und Mitarbeiter melden sich über OIDC bzw. den zentralen Identitätsanbieter ihres Unternehmens an und ab; dort gesperrte Konten sind in TAKTOVIA wirksam gesperrt.
- **Anforderungen:** `LH-6.2-IAM-003`
- **Liefergegenstände:**
  - Backend: Modul `identity`: Identity-Brokering im verwalteten OIDC-Anbieter (Kap. 01 §1.1) je Mandant; Bindung `membership.issuer/subject` an föderierte Identität; Abmeldung (RP-initiated und Back-Channel-Logout); Sperrübernahme über kurze Tokenlaufzeit, serverseitige Sitzungssperre und Sperrsignal des IdP; MFA-Pflicht für privilegierte Konten bleibt (Kap. 01 §1.7).
  - Datenbank: `membership`, `identity_provider_binding` (Arbeitsname, Migration in WP-M8e-02), Sitzungstabellen aus M1, `audit_event`.
  - API: `/v1/me`; jede Anfrage prüft issuer, audience, expiry, session status und Scopes (Kap. 05 §5.1).
  - Oberfläche: Anmeldung in `web/operator` und `mobile/driver` (UI-09 Dienststart nach Anmeldung); UI-18 (IdP-Bindung, gesperrte Nutzer).
  - Adapter/Worker: IdP-Testinstanz als zweiter Issuer in der lokalen Umgebung.
- **Detailverträge vorab:** unterstützte Protokolle (OIDC; SAML nur über Broker – im Pflichtenheft nicht benannt); Attributabbildung; maximale Wirkdauer einer Sperre; Verhalten der Driver-App offline nach Sperre; Verhalten bei nicht erreichbarem Identitätsanbieter (im Pflichtenheft nicht geregelt, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Fahrer und Mitarbeiter melden sich über die integrierte Identität an und ab; die Abmeldung beendet die TAKTOVIA-Sitzung (IAM-003).
  2. Ein im Identitätsanbieter gesperrtes Konto kann sich nicht anmelden; eine bestehende Sitzung wird innerhalb der vertraglich festgelegten Frist unwirksam und jeder weitere Request abgelehnt (IAM-003).
  3. Eine föderierte Identität ohne aktive `membership` erhält keinen Zugriff; ein Issuer ist genau an seinen Mandanten gebunden.
  4. An- und Abmeldung sowie abgelehnte Zugriffe gesperrter Konten sind auditiert.
  5. Gesperrtes Konto, fehlende Mitgliedschaft und nicht erreichbarer Identitätsanbieter führen in `web/operator` und `mobile/driver` zu einer verständlichen Meldung mit Fehler-ID ohne technische Interna; es gibt keinen lokalen Ersatz-Login, der die Sperre umgeht.
  6. Ein Mitarbeiter mit Mitgliedschaften in zwei Mandanten erhält je Mandantenkontext nur die dort vergebenen Rollen; die IdP-Bindung in UI-18 ist nur für berechtigte Administratoren sichtbar und änderbar.
- **Tests:** positiv: Login/Logout Fahrer und Disponent. negativ: gesperrtes Konto, abgelaufenes Token, Token eines fremden Issuers mit gleichem Subject, manipulierte Audience, entzogene Mitgliedschaft bei gültigem Token, Issuer von Mandant A in der Bindung von Mandant B. konkurrierend (Sicherheit): Sperre während laufendem Schreibrequest und während Offline-Sync der Driver-App – kein Ereignis nach wirksamer Sperre ohne Prüfung angenommen. Regression: PH-AT-017, AT-24.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-04, WP-M1-06, WP-M1-10 (OIDC-Ressourcenserver, IdP-Realm und MFA, Mitgliedschaft, Sitzungssperre)
  - extern: WP-M3-24 (Driver-App-Anmeldung und Offline-Queue)
- **Entscheidungen:** D-05 (Hosting/IdP-Betrieb), D-11 (Supportzugriff) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrgastkonten (M1); Partner-Maschinenidentitäten (M6); Provisionierung per SCIM (im Pflichtenheft nicht gefordert).

### WP-M8e-09 – Gastbuchung mit verifiziertem Buchungstoken

- **Ziel:** Betreiber können Gastbuchung ohne permanentes Konto aktivieren; ein Gast sieht und ändert seine Buchung mit einem verifizierten Buchungstoken.
- **Anforderungen:** `LH-6.2-IAM-007`
- **Liefergegenstände:**
  - Backend: Gastsubjekt mit verifiziertem Kontaktkanal; Buchungstoken (hochentropisch, nur gehasht gespeichert, an genau eine Buchung/Reise gebunden, ablaufend, widerrufbar, Rate-Limit); eingeschränkter Scope: nur eigene Buchung lesen, ändern, stornieren; Aktivierung je Betreiber über Flag.
  - Datenbank: `guest_booking_token` (Arbeitsname), `passenger_private` (Kontoart Gast, `retention_until`), `booking`, `audit_event`.
  - API: `/v1/passengers` (Gastanlage), `/v1/ride-offers`, `/v1/bookings` (Zugriff per Token), Arbeitsname `/v1/guest/bookings`.
  - Oberfläche: UI-01 bis UI-04 als Gastfluss ohne UI-05; Einstieg „Buchung per Link/Code öffnen“; Zustände „Token abgelaufen“, „Verifikation fehlt“.
  - Ereignisse/Outbox: Tokenversand über den Benachrichtigungsdienst.
- **Detailverträge vorab:** Zustandsautomat Token; Verifikationsverfahren des Kontakts; Tokenlaufzeit und Löschfrist für Gastdaten (im Pflichtenheft nicht festgelegt); spätere Kontoübernahme.
- **Akzeptanzkriterien:**
  1. Ist Gastbuchung für den Betreiber aktiviert, bucht eine Person ohne permanentes Konto; ist sie deaktiviert, wird die Gastbuchung in UI und API abgelehnt (IAM-007).
  2. Ein Gast sieht und ändert mit verifiziertem Buchungstoken genau seine Buchung (IAM-007).
  3. Ein abgelaufenes, widerrufenes oder zu einer anderen Buchung gehörendes Token liefert konsistent 404 ohne Existenzhinweis.
  4. Gastdaten erhalten eine eigene Aufbewahrungsregel und werden nach Fristablauf vom Löschjob erfasst.
  5. Für einen Gast werden nur die für Buchung, Kontakt und Abrechnung erforderlichen Felder gespeichert; das Token erscheint weder in Logs, Traces, Auditdaten noch in weitergegebenen Adressen an Dritte.
  6. Aktivierung und Deaktivierung der Gastbuchung je Betreiber sowie Ausgabe, Nutzung und Widerruf eines Tokens sind auditiert; bestehende Gastbuchungen bleiben nach Deaktivierung per Token einsehbar und stornierbar.
- **Tests:** positiv: Gastbuchung, Änderung, Storno per Token. negativ: geratenes Token, Token von Mandant B, Token nach Storno, unverifizierter Kontakt, Brute-Force (429), wiederholter Gast-Buchungsrequest mit gleichem `Idempotency-Key` erzeugt genau eine Buchung und bei abweichendem Request eine Ablehnung (PH-AT-011), Token im Log-/Trace-Scan. konkurrierend (Kapazität/Sicherheit): zwei parallele Änderungen mit demselben Token – genau eine wirksam (ETag/412); Tokenwiderruf parallel zur Änderung. Regression: AT-13, AT-18.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-05.
  - extern: WP-M1-28, WP-M1-02, WP-M1-13, WP-M1-33 (Kontaktverifikation, Rate-Limit, Fehlermodell, Idempotenzregister, Löschjob-Grundgerüst mit `retention_rule`)
  - extern: WP-M2-23, WP-M2-26, WP-M2-32, WP-M7a-03 (Buchung, Änderung, Storno, Betriebsnachrichten)
- **Entscheidungen:** D-11 (Löschfristen Gastdaten) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Callcenter-Gastanlage in UI-06 (M7b nutzt diese Fähigkeit); Zahlungsmittel-Speicherung für Gäste.

### WP-M8e-10 – Dokumentierter Vertretungszugriff: Besteller und beförderte Person getrennt

- **Ziel:** Berechtigte Dritte buchen für einen Fahrgast mit dokumentiertem Vertretungszugriff; Buchungsersteller und beförderte Person sind getrennt nachvollziehbar.
- **Anforderungen:** `LH-6.2-IAM-008`
- **Liefergegenstände:**
  - Backend: Dokumentation der Vertretung (Nachweisart, Zweck, Scope, Ablauf, Erteiler, Widerruf) an `delegated_access`; erneute Prüfung von Zweck, Dienst und Laufzeit bei Planung, Hold und Confirm (Kap. 05 §5.9, `booking_subject` DELEGATED); Auswertung „wer hat für wen gebucht“ in Buchungshistorie und Audit.
  - Datenbank: `delegated_access`, `booking`/`journey` (aufgelöste Person und Berechtigungsrevision; Felder aus M7a bzw. Migration in WP-M8e-02), `audit_event`.
  - API: `/v1/delegations`, `/v1/bookings`, `/v1/passengers`.
  - Oberfläche: UI-05 (Vertreter anzeigen, erteilen, widerrufen; Zustand „Vertretung abgelaufen“), UI-04 (Besteller und Reisender getrennt ausgewiesen), UI-20 (Nachweis im Fall).
- **Detailverträge vorab:** Nachweisarten; Sicht des Vertretenen auf Buchungen des Vertreters; Abgrenzung zu M7a (Buchung für Dritte) und M7b (Callcenter-Legitimation).
- **Akzeptanzkriterien:**
  1. Jede Vertretungsbuchung weist Buchungsersteller und beförderte Person als getrennte, einzeln auswertbare Angaben aus – in Buchung, Historie und Audit (IAM-008).
  2. Der Vertretungszugriff ist mit Zweck, Umfang, Ablauf und Erteiler dokumentiert und vom Vertretenen einsehbar und widerrufbar (IAM-008).
  3. Nach Ablauf oder Widerruf scheitern Planung, Hold und Confirm für den Vertretenen; bestehende Buchungen bleiben mit ihrer damaligen Berechtigungsrevision nachvollziehbar.
  4. Jeder lesende und schreibende Zugriff des Vertreters auf Daten des Vertretenen ist mit Vertreter, Vertretenem und Berechtigungsrevision auditiert; der Vertreter sieht nur die vom Scope erfassten Daten.
  5. UI-05 zeigt die Zustände „keine Vertretung“, „Vertretung abgelaufen“ und „widerrufen“; in einem anderen Mandanten ist die Vertretung nicht wirksam.
- **Tests:** positiv: Vertretungsbuchung mit Nachweis. negativ: abgelaufene Vertretung, Scope ohne den Dienst, Vertreter liest Profil außerhalb des Scopes, Vertretung aus Mandant A im Kontext von Mandant B, Buchung mit `booking_subject` DELEGATED ohne gültige Vertretung. konkurrierend (Sicherheit): Widerruf zwischen Hold und Confirm – Confirm abgelehnt. Regression: AT-17.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M7a-11 (Buchung für Dritte, `/v1/delegations`)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff) – nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Callcenter-Zustimmung am Telefon (M7b); Erziehungsberechtigte für Schüler (WP-M8e-13).

### WP-M8e-11 – Vertrauensperson mit explizit freigegebenen Benachrichtigungen

- **Ziel:** Fahrgäste hinterlegen eine Begleit- oder Kontaktperson; diese erhält ausschließlich die ausdrücklich freigegebenen Benachrichtigungen.
- **Anforderungen:** `LH-6.2-IAM-013`
- **Liefergegenstände:**
  - Backend: Vertrauensperson mit verifiziertem Kontakt und Freigabeliste je Nachrichtenart (z. B. Abholung, Ankunft, Störung); Empfängerauflösung im Benachrichtigungsdienst prüft die Freigabe je Versand; minimierter Nachrichteninhalt; Widerruf wirkt sofort.
  - Datenbank: `trusted_contact` (Arbeitsname), `consent_revision` (Freigabe als nachweisbare Erklärung), `notification`, `notification_attempt`.
  - API: `/v1/passengers` (Unterressource Vertrauensperson), `/v1/notifications`.
  - Oberfläche: UI-05 (Vertrauensperson hinterlegen, Freigaben je Nachrichtenart, Zustand „Verifikation fehlt“).
  - Konfigurationsparameter: `notification_quiet_hours`, `customer_notify_before_pickup_min` gelten auch für diese Empfänger.
- **Detailverträge vorab:** Katalog freigebbarer Nachrichtenarten; Information der Vertrauensperson über ihre Hinterlegung; Löschregel.
- **Akzeptanzkriterien:**
  1. Ein Fahrgast hinterlegt eine Vertrauensperson und gibt einzelne Nachrichtenarten frei (IAM-013).
  2. Die Person erhält genau die freigegebenen Benachrichtigungen und keine andere; nicht freigegebene Ereignisse derselben Reise erzeugen keinen Versand an sie (IAM-013).
  3. Nach Widerruf oder Löschung der Vertrauensperson wird nichts mehr zugestellt; bereits geplante Sendungen werden verworfen.
  4. Die Vertrauensperson erhält keinen Zugriff auf Konto, Buchungsliste oder Zahlungsdaten.
  5. Hinterlegung, Freigabe je Nachrichtenart und Widerruf sind mit Zeitpunkt und Akteur nachvollziehbar; die Kontaktdaten der Vertrauensperson unterliegen einer Löschregel und werden bei Löschung des Fahrgastkontos mit entfernt.
  6. Ein fehlgeschlagener Versand an die Vertrauensperson beeinträchtigt die Betriebsnachricht an den Fahrgast nicht und ist als Zustellversuch sichtbar.
- **Tests:** positiv: freigegebene Abholnachricht. negativ: nicht freigegebene Nachrichtenart, unverifizierter Kontakt, Vertrauensperson versucht Buchungszugriff. konkurrierend (Datentrennung): Widerruf parallel zum Versandjob – kein Versand nach wirksamem Widerruf.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-12.
  - extern: WP-M2-32 (Betriebsnachrichten mit Zustellversuchen)
  - extern: WP-M7b-24, WP-M7b-31 (Kommunikationsauftrag mit Zweck und Empfängerauflösung, Ruhezeiten)
- **Entscheidungen:** D-11 (Löschfristen, Rechtsgrundlage Drittkontakt) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Weitergabe des Abholfensters per Link (M7a); Live-Standort für Dritte.

### WP-M8e-12 – Einwilligungsregister: Rechtsgrundlage je Zweck und nachvollziehbarer Widerruf

- **Ziel:** Einwilligung wird nur dort abgefragt und ausgewertet, wo sie Rechtsgrundlage ist; ein Widerruf ist nachvollziehbar und beendet Werbekanäle, ohne Betriebsnachrichten zu stören.
- **Anforderungen:** `LH-6.18-SEC-015`
- **Liefergegenstände:**
  - Backend: Zweckkatalog mit Rechtsgrundlage je Verarbeitungszweck (Einwilligung, Vertrag, gesetzliche Pflicht – Zuordnung fachlich freizugeben); Einwilligungs-Gate nur für einwilligungsbasierte Zwecke; Widerruf als neue Revision mit Zeitpunkt, Kanal und Akteur; Durchsetzung im Versandpfad vor jedem Versand; Betriebsnachrichten zur Beförderung sind vom Marketingwiderruf unabhängig (Kap. 05 §5.7 „Opt-in unabhängig von Betriebsnachricht“).
  - Datenbank: `consent_revision` (append-only), `notification`, `channel_template_revision` (Zweck je Vorlage), `audit_event`.
  - API: `/v1/passengers` (Einwilligungen lesen/erteilen/widerrufen), `/v1/notifications`.
  - Oberfläche: UI-05 (Einwilligungen mit Verlauf); UI-03 (Pflichtzustimmung nie mit Marketingeinwilligung kombiniert, Kap. 06 §6.3); UI-20 (Nachweis).
  - Ereignisse/Outbox: Widerrufsereignis an Kommunikationsmodul und Zielgruppenprojektionen.
- **Detailverträge vorab:** Zweck-/Rechtsgrundlagenmatrix; Text- und Versionsbindung der Einwilligung; Wirkfrist des Widerrufs auf bereits eingeplante Kampagnen.
- **Akzeptanzkriterien:**
  1. Ein Marketingwiderruf beendet alle Werbekanäle; Betriebsnachrichten zu bestehenden und neuen Buchungen werden weiterhin zugestellt (SEC-015).
  2. Erteilung und Widerruf sind mit Zeitpunkt, Textversion, Kanal und Akteur nachvollziehbar und unveränderlich gespeichert (SEC-015).
  3. Für Zwecke, deren Rechtsgrundlage nicht die Einwilligung ist, wird keine Einwilligung abgefragt oder als Voraussetzung ausgewertet (SEC-015).
  4. Ein Versand mit Marketingzweck ohne gültige Einwilligung wird serverseitig verworfen und protokolliert.
- **Tests:** positiv: Erteilung, Widerruf, erneute Erteilung. negativ: Marketingversand nach Widerruf, Vorlage ohne Zweck, Einwilligung eines anderen Mandanten. konkurrierend (Datentrennung/Sicherheit): Widerruf parallel zu laufendem Massenversand – keine Zustellung an die Person nach wirksamem Widerruf; Buchungsbestätigung parallel bleibt zugestellt.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-30 (Datenschutzeinstellungen Betriebsmitteilung/Marketing, `consent_revision`)
  - extern: WP-M7b-24, WP-M7b-26 (Kommunikationsauftrag mit Einwilligungs-Gate, Massenkommunikation)
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss) – Rechtsgrundlagenmatrix blockiert Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrerbezogene Freigaben der ETA-Personalisierung (M8b); Standortzustimmung im Browser (M2).

### WP-M8e-13 – Minderjährigen- und Schülerkonten: Sichtbarkeit und Kontaktregeln

- **Ziel:** Schüler- und Minderjährigenkonten haben alters- und rollenangepasste Sichtbarkeit; nicht autorisierte Dritte sehen keine Schülerfahrten.
- **Anforderungen:** `LH-6.18-SEC-022`
- **Liefergegenstände:**
  - Backend: Kontoart Schüler/Minderjährig mit Bindung an autorisierte Personen (Erziehungsberechtigte, autorisierte Stelle) über Vertretung; Sichtbarkeitsregel: Fahrten nur für Konto selbst, autorisierte Vertreter und zuständige Beförderungsrollen; Kontaktregeln (kein Direktkontakt Fahrer↔Schüler außerhalb des Relays, Benachrichtigungen an autorisierte Person); Ausschluss aus Teilen/Weitergabe- und Vertrauenspersonfunktionen ohne Autorisierung.
  - Datenbank: `passenger_private` (Kontoart), `delegated_access`, `trusted_contact` (Arbeitsname), `audit_event`.
  - API: `/v1/passengers`, `/v1/delegations`, `/v1/bookings`.
  - Oberfläche: UI-05 (Kontoart, autorisierte Personen), UI-04 (eingeschränkte Ansicht), UI-06/UI-07 (minimierte Anzeige in Callcenter/Leitstelle), UI-10 (Fahrer sieht nur Beförderungsaufgabe).
- **Detailverträge vorab:** Altersgrenzen und Rollenmatrix (im Pflichtenheft nicht beziffert); Autorisierungsnachweis; Übergang bei Volljährigkeit.
- **Akzeptanzkriterien:**
  1. Eine nicht autorisierte Drittperson (anderer Fahrgast, nicht berechtigter Mitarbeiter, Vertrauensperson ohne Autorisierung, anderer Mandant) sieht keine Schülerfahrten – weder in Listen, Details, Suche, Export noch Benachrichtigungen (SEC-022).
  2. Autorisierte Personen sehen genau die Fahrten des ihnen zugeordneten Schülers (SEC-022).
  3. Kontaktaufnahme zum Schüler erfolgt ausschließlich über die festgelegten Kanäle; Fahrer erhalten keine privaten Kontaktdaten.
  4. Jeder abgelehnte Zugriff auf Schülerfahrten wird protokolliert.
  5. Die Sichtbarkeitsregel gilt identisch in API, Listen, Suche, Berichten/Exporten und Benachrichtigungen; UI-05 zeigt für autorisierte Personen die Zustände „keine Autorisierung“, „Autorisierung abgelaufen“ und „widerrufen“.
- **Tests:** positiv: Erziehungsberechtigter sieht Fahrt. negativ: fremder Erziehungsberechtigter, Mitarbeiter ohne Dienstscope, Export/Bericht mit Schülerdaten, Supportsuche. konkurrierend (Datentrennung): Entzug der Autorisierung parallel zum Listenabruf – keine Zeile nach wirksamem Entzug. Regression: AT-49.
- **Abhängigkeiten:** intern: WP-M8e-04, WP-M8e-10, WP-M8e-11.
  - extern: WP-M1-07, WP-M1-08, WP-M1-31, WP-M1-12, WP-M1-46 (Rollen-/Objektberechtigung, Feldschutz, Audit abgelehnter Zugriffe)
  - extern: WP-M3-36, WP-M3-26, WP-M3-30, WP-M3-32 (Leitstelle UI-07 und Driver-App UI-10 mit minimierter Fahrgastanzeige)
  - extern: WP-M7b-35, WP-M7b-30, WP-M7b-17 (Callcenter UI-06, anonymisierte bzw. relay-basierte Kommunikation, Supportsuche)
- **Entscheidungen:** D-11 (Datenrechte), D-05 (Schülerverkehr im Pilot) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Schülerverkehrs-Tarife und Berechtigungsnachweise (M6a/M6c); Serienbuchung für Schulwege (M7a).

### WP-M8e-14 – Legal Hold: Setzen, Wirkung, Aufhebung

- **Ziel:** Berechtigte Rollen können Objekte oder Personen unter Legal Hold stellen; kein Lösch- oder Fristenlauf entfernt gehaltene Daten.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Legal-Hold-Dienst als Port für alle Löschpfade (Plattformjob, Belegarchiv M8a, Benachrichtigungsprotokolle M7b, Aufbewahrungsklassen M8b); Setzen/Aufheben mit Anlass, Umfang, Verantwortlichem und Vier-Augen-Bestätigung; Hold sperrt Löschung, nicht den regulären Zugriffsschutz.
  - Datenbank: `legal_hold` (Arbeitsname, Historie append-only), `retention_rule`, `erasure_job`, `audit_event`.
  - API: Arbeitsname `/v1/privacy/legal-holds`; Statusfeld in `/v1/privacy/requests`.
  - Oberfläche: UI-20 (Zustand „Legal Hold“, Liste aktiver Holds); UI-05 (Löschstatus „gesperrt“ ohne Offenlegung des Anlasses).
  - Ereignisse/Outbox: Hold gesetzt/aufgehoben.
- **Detailverträge vorab:** Zustandsautomat; Rollen für Setzen/Aufheben; Objektarten, die gehalten werden können.
- **Akzeptanzkriterien:**
  1. Ein Objekt unter Legal Hold wird von keinem Löschpfad entfernt; der Löschschritt endet mit Status „gesperrt“ und Begründungsklasse.
  2. Setzen und Aufheben erfordern berechtigte Rolle, Anlass und zweite Person und sind auditiert.
  3. Nach Aufhebung greift die reguläre Frist wieder.
- **Tests:** positiv: Hold setzen, Löschlauf überspringt. negativ: Hold ohne Anlass, Aufhebung durch denselben Akteur, Hold im fremden Mandanten. konkurrierend (Sicherheit/Datentrennung): Hold parallel zum laufenden Löschschritt desselben Objekts – entweder vollständig gelöscht vor dem Hold oder vollständig erhalten, nie teilgelöscht.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-33, WP-M1-46, WP-M1-22 (Aufbewahrungs- und Löschregeln (`retention_rule`, `erasure_job`), Audit, Vier-Augen-Baustein)
- **Entscheidungen:** D-11 (Legal Holds) – blockiert Produktivschaltung, nicht den Bau.
- **Größe:** S
- **Nicht im Umfang:** Fristenmatrix je Datentyp (M8b); Belegarchivfristen (M8a).

### WP-M8e-15 – Orchestrierter Lösch- und Auskunftsjob über DB, Objektspeicher, Suchindex, Partnerprojektion

- **Ziel:** Löschung und Datenzusammenstellung laufen als wiederaufnehmbarer Job über alle Speicherorte; ein Restore spielt das Lösch-/Sperrjournal vor Freigabe wieder ein.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Orchestrierung über `operation` mit Lease/Fencing; Schritte je Zielsystem (Datenbankmodule über explizite Fachoperationen, Objektspeicher, Suchindex, Cache, Partnerprojektion); Trennung aufbewahrungspflichtiger Finanzbezüge von entbehrlicher PII (Anonymisierung statt Löschung, Kap. 01 §1.7); Sammelschritt für Auskunft/Export in ein mandantengebundenes Paket; Lösch-/Sperrjournal (Arbeitsname `erasure_journal`; außerhalb des wiederherzustellenden Datenbestands geführt, Speicherort im Detailvertrag) und dessen Wiedereinspielung nach Restore.
  - Datenbank: `erasure_job` (Schritte, Status je Ziel), `export_job`, `retention_rule`, `passenger_private`, `passenger_preference`, `consent_revision`, `delegated_access`, `support_case`, `notification`, `audit_event`; kontrollierter Lifecycleprozess für unveränderliche Tabellen (Kap. 04 §4.5).
  - Adapter/Worker: Löschadapter Objektspeicher; Zielport „Suchindex“ ohne produktiven Adapter (Offener Punkt 41); Partner-Löschmitteilung über freigegebenen Adapterkanal; signierter Download des Auskunftspakets (Planungsdefault höchstens 60 Sekunden gültig, Kap. 02 §4).
  - Ereignisse/Outbox: Löschauftrag je Modul, Abschlussmeldung je Ziel.
- **Detailverträge vorab:** Modulport „personenbezogene Daten liefern/löschen“ je Fachmodul; Anonymisierungsverfahren je Tabelle; Verhalten bei nicht erreichbarem Partner; Format des Lösch-/Sperrjournals.
- **Akzeptanzkriterien:**
  1. Ein Löschjob entfernt bzw. anonymisiert die Daten einer Testperson in Datenbank, Objektspeicher und Partnerprojektion und weist je Ziel einen Status aus. Das Löschziel „Suchindex“ ist als Zielport der Orchestrierung vorhanden und wird mit einem Testdouble nachgewiesen (Schritt wird aufgerufen, Status je Ziel, Wiederaufnahme); solange kein Suchindex existiert, weist der Job das Ziel als „nicht vorhanden“ aus und nicht als „gelöscht“ (Offener Punkt 41).
  2. Aufbewahrungspflichtige Finanzbezüge bleiben erhalten und sind von PII getrennt; Journal und Belegreferenzen bleiben konsistent.
  3. Ein abgebrochener Job wird nach Neustart fortgesetzt, ohne einen Schritt doppelt wirksam auszuführen.
  4. Nach einem Restore auf einen Stand vor der Löschung ist die Person vor Freigabe der Umgebung erneut gelöscht bzw. gesperrt.
  5. Ein Job im Kontext von Mandant A berührt keine Daten von Mandant B.
  6. Das Lösch-/Sperrjournal enthält selbst keine wiederherstellbaren Personendaten, sondern nur die zur erneuten Löschung nötigen Referenzen.
  7. Jeder Jobschritt ist mit Auslöser, Ziel und Ergebnis auditiert; ein dauerhaft offener Schritt erzeugt eine sichtbare Aufgabe in UI-20.
- **Tests:** positiv: vollständige Löschung, vollständiges Auskunftspaket. negativ: Partner nicht erreichbar (Schritt bleibt offen und sichtbar), Legal Hold, offener Rechnungsbezug, fremder Mandant. konkurrierend (Geld/Datentrennung): Löschjob parallel zu Belegbuchung/Periodenabschluss und parallel zu neuer Buchung derselben Person; zwei Worker auf demselben Job (Fencing). Regression: AT-53, AT-59.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-14.
  - extern: WP-M1-33, WP-M1-15, WP-M1-11 (Löschjob-Grundgerüst, `operation` mit Lease/Fencing, Isolation in Objektspeicher/Cache)
  - Klärung Auftraggeber (kein WP): Ob ein eigener Suchindex außerhalb der Datenbank gebaut wird. Kap. 01 §1.1 nennt keinen Suchbaustein, Kap. 01 §1.7 nennt den Suchindex nur als Löschziel; kein WP der Meilensteine M0 bis M8 liefert ihn (WP-M1-11 schließt ihn aus). Siehe Offener Punkt 41.
  - extern: WP-M5-14, WP-M5-15 (Wiederanlaufdrehbuch und Restore-Übung)
  - extern: WP-M6a-05, WP-M6a-12, WP-M6b-09 (Partnerprojektionen und Adapterkanal)
- **Entscheidungen:** D-11 (Löschmatrix, Wiederanlauf) – Fristen blockieren Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fachliche Aufbewahrungsprüfung je Datentyp (M8b); Belegarchiv (M8a); Löschung von Benachrichtigungsprotokollen nach Frist (M7b).

### WP-M8e-16 – Betroffenenrechte-Workflow: Auskunft, Export, Korrektur, Löschung mit Fristüberwachung

- **Ziel:** Eine Betroffenenanfrage wird von Eingang über Identitätsprüfung bis Abschluss als Fall geführt und vollständig sowie fristgerecht bearbeitet.
- **Anforderungen:** `LH-6.18-SEC-014`
- **Liefergegenstände:**
  - Backend: Aggregat Datenschutzanfrage mit Arten Auskunft, Export, Korrektur, Löschung; Identitätsprüfung des Anfragenden; Fristberechnung und Eskalation; Aufgaben je Fachmodul; interne Ports „Auskunftsinhalt liefern“ und „Aufbewahrung prüfen“ mit Vertrag aus WP-M8e-01 und Testimplementierung (die fachliche Implementierung liefert WP-M8b-24); Korrektur als nachvollziehbare Änderung mit Verweis auf unveränderliche Nachweise; Abschlussmitteilung mit Ergebnis je Datenkategorie („erledigt / gesperrt wegen Aufbewahrungspflicht / Legal Hold“).
  - Datenbank: `privacy_request`, `privacy_request_step` (Arbeitsnamen), `erasure_job`, `export_job`, `legal_hold` (Arbeitsname), `audit_event`.
  - API: `/v1/privacy/requests` (Kap. 05 §5.7: Auskunft/Löschung und Legal Hold).
  - Oberfläche: UI-20 (Fallbearbeitung „begründeten Fall bearbeiten“; Zustände „fehlender Zweck“, „Legal Hold“, „abgelaufener Zugriff“; Fristampel); UI-05 (Anfrage stellen, Löschstatus).
  - Ereignisse/Outbox: Anfrage eingegangen, Frist gefährdet, abgeschlossen.
- **Detailverträge vorab:** Zustandsautomat aus WP-M8e-01; Fristwerte und Verlängerungsregel (im Pflichtenheft nicht beziffert); Identitätsprüfverfahren; Antwortschema gemeinsam mit M8b.
- **Akzeptanzkriterien:**
  1. Eine Testanfrage jeder Art (Auskunft, Export, Korrektur, Löschung) durchläuft den Workflow vollständig bis zum dokumentierten Abschluss (SEC-014). In diesem WP wird das mit den Daten aus dem Modulport von WP-M8e-15 und der Testimplementierung der Ports „Auskunftsinhalt liefern“/„Aufbewahrung prüfen“ nachgewiesen; die Vollständigkeit der Auskunft nach Kategorien, Empfängern und Zwecken sowie die Aufbewahrungsprüfung gegen `retention_rule` werden erst mit WP-M8b-24 nachgewiesen.
  2. Die Bearbeitung erfolgt innerhalb der konfigurierten Frist; eine gefährdete Frist erzeugt eine Eskalation, eine überschrittene ist ausgewiesen (SEC-014).
  3. Ohne erfolgreiche Identitätsprüfung werden keine Daten herausgegeben oder gelöscht.
  4. Die Auskunft enthält nachweislich keine Daten, Kategorien oder Existenzhinweise aus anderen Mandanten.
  5. Jeder Bearbeitungsschritt ist mit Akteur und Zweck auditiert.
  6. Die anfragende Person erhält Eingangsbestätigung und Abschlussmitteilung über einen verifizierten Kanal; die Mitteilung nennt je Datenkategorie das Ergebnis und bei Zurückbehaltung den Grund.
  7. Eine Korrektur ändert die laufenden Daten nachvollziehbar; unveränderliche Nachweise (Audit, Belege, Zusagen) werden nicht überschrieben, sondern mit Korrekturvermerk verknüpft.
  8. UI-20 zeigt für die Fallliste die Zustände leer, Ladefehler, fehlendes Recht, „fehlender Zweck“, „Legal Hold“ und „abgelaufener Zugriff“; ohne Zweckangabe wird kein Fall geöffnet.
- **Tests:** positiv: je Anfrageart ein vollständiger Durchlauf mit steuerbarer Uhr. negativ: Anfrage für fremde Person, fehlende Identitätsprüfung, Bearbeiter ohne Zweckangabe, Person mit Konten in zwei Mandanten, wiederholtes Anlegen derselben Anfrage mit gleichem `Idempotency-Key` (genau ein Fall; abweichender Request abgelehnt, PH-AT-011), Statusübergang ohne `If-Match` (428), Fall eines fremden Mandanten (404). konkurrierend (Sicherheit/Datentrennung): zwei Bearbeiter schließen denselben Fall parallel (ETag/412); Löschanfrage parallel zu neuer Buchung. Beitrag zu E2E-105; Regression: AT-49.
- **Abhängigkeiten:** intern: WP-M8e-14, WP-M8e-15.
  - extern: WP-M1-04, WP-M1-06, WP-M1-46, WP-M1-12, WP-M1-17, WP-M1-43, WP-M1-13 (Identität, Audit, Zustandsautomaten-Rahmen, UI-20-Basis, Idempotenzregister)
  - extern: WP-M2-32 (Betriebsnachrichten für Eingangsbestätigung und Abschlussmitteilung)
- **Vollabnahme erst mit:** WP-M8b-22, WP-M8b-24 (der fachliche Auskunftsinhalt aus dem Verarbeitungsregister und die Aufbewahrungsprüfung werden dort in den hier gelieferten Fall eingehängt; erst damit ist die Auskunft inhaltlich vollständig und der führende Nachweis E2E-105 möglich)
- **Entscheidungen:** D-11 (Auskunfts-/Exportprozess, Verantwortliche je Datenfluss) – blockiert Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Inhalt der Auskunft aus dem Verarbeitungsregister und Aufbewahrungsprüfung (M8b); Fahrertransparenz zum ETA-Profil (M8b).

### WP-M8e-17 – Selbstbedienung: Export und Löschung von Profil- und Buchungsdaten

- **Ziel:** Fahrgäste fordern den Export ihrer Profil- und Buchungsdaten oder deren Löschung nach freigegebenen Regeln an; gesetzliche Aufbewahrungen werden erklärt.
- **Anforderungen:** `LH-6.2-IAM-012`
- **Liefergegenstände:**
  - Backend: definierter Exportumfang (Profil, Präferenzen, Einwilligungen, Vertretungen, Buchungen/Reisen mit Status) als maschinenlesbares Paket mit Schema-/Policyversion und Checksumme (Kap. 04 §4.7); Löschvorschau mit Erklärung je zurückbehaltener Kategorie; Kontoschließung als Folge erfolgreicher Löschung.
  - Datenbank: `export_job`, `erasure_job`, `privacy_request` (Arbeitsname), `passenger_private`, `passenger_preference`, `consent_revision`, `delegated_access`, `booking`, `journey`.
  - API: `/v1/privacy/requests`, `/v1/passengers`, `/v1/me`.
  - Oberfläche: UI-05 („Meine Daten exportieren“, „Konto und Daten löschen“, Zustand „Löschstatus“, Erklärtext zu Aufbewahrungen).
- **Detailverträge vorab:** Feldkatalog des Exports („definierte Personendaten“); Exportformat; Erklärtexte je Aufbewahrungsgrund; Verhalten bei offenen Buchungen/Forderungen.
- **Akzeptanzkriterien:**
  1. Der Export enthält genau die im Feldkatalog definierten Personendaten der anfragenden Person und keine Daten Dritter (IAM-012).
  2. Bei Löschung werden gesetzlich aufzubewahrende Daten nicht entfernt; der Nutzer erhält je Kategorie eine verständliche Erklärung der Aufbewahrung (IAM-012).
  3. Eine Löschung mit offener Buchung oder offener Forderung wird nicht still ausgeführt, sondern mit Grund zurückgestellt.
  4. Der Download ist nur für die authentisierte Person und kurzzeitig gültig (Planungsdefault höchstens 60 Sekunden, Kap. 02 §4).
  5. Anforderung, Bereitstellung, Abruf und Löschung sind auditiert; das Exportpaket selbst wird nach einer festgelegten Frist entfernt.
  6. UI-05 zeigt die Zustände „Verifikation fehlt“, „Export in Arbeit“, „Export bereit“, „Löschung zurückgestellt“ mit Grund und „Löschstatus“.
- **Tests:** positiv: Export und Löschung ohne Rückhalt. negativ: Download-Link durch andere Sitzung, Löschung mit aktiver Buchung, Export für Gast ohne gültiges Token, doppelte Anforderung (Doppelklick) erzeugt genau einen Job. konkurrierend (Datentrennung): Export parallel zu Profiländerung – Paket trägt konsistenten As-of-Stand.
- **Abhängigkeiten:** intern: WP-M8e-09, WP-M8e-16.
  - extern: WP-M2-36, WP-M2-23, WP-M2-45 (UI-05 Konto, Buchungsbestand)
- **Entscheidungen:** D-11 (Löschfristen) – blockiert Produktivschaltung der Löschung.
- **Größe:** M
- **Nicht im Umfang:** Export für Fahrer/Mitarbeiter (Workflow in WP-M8e-16, Inhalte M8b); Finanzbelege (M8a).

### WP-M8e-18 – Providerpayload-Minimierung gegen freigegebenes Datenflussdiagramm

- **Ziel:** Externe Anbieter erhalten nur die im freigegebenen Datenflussdiagramm je Empfänger erlaubten Felder; Abweichungen werden technisch verhindert und in CI erkannt.
- **Anforderungen:** `LH-6.18-SEC-016`
- **Liefergegenstände:**
  - Backend: maschinenlesbare Feldfreigabe je Empfänger und Zweck (Benachrichtigungs-, PSP-, Routing-/Geocoding-, OCR-, Partner- und Telemetrieanbieter); Ausgangsfilter (Allowlist) in jedem Adapter – Payload wird aus erlaubten Feldern erzeugt, nicht nachträglich gekürzt (Kap. 02 §4); Verstoß führt zu Abbruch des Aufrufs und Sicherheitsereignis.
  - Datenbank: `data_flow_release` (Arbeitsname, versioniert, freigegeben), `adapter_capability`, `webhook_subscription`, `audit_event`.
  - Adapter/Worker: Payloadaufzeichnung in der Sandbox; Vertragstest je Adapter in `backend/adapters` (psp, partner-tomp, partner-osdm, timetable-gtfs, erp-export, ocr, notification).
  - Oberfläche: UI-20 (lesbare Feldfreigabe je Empfänger als Datenflussübersicht).
- **Detailverträge vorab:** Datenflussdiagramm mit Feldliste je Empfänger (im Pflichtenheft nur als Prinzip, Kap. 02 „Minimaler Betreiberpayload“); Freigabeprozess; Umgang mit Freitextfeldern.
- **Akzeptanzkriterien:**
  1. Für jeden externen Anbieter enthält der aufgezeichnete Payload ausschließlich Felder, die im freigegebenen Datenflussdiagramm für diesen Empfänger stehen (SEC-016).
  2. Ein Adapterbuild, der ein nicht freigegebenes Feld sendet, scheitert im Vertragstest; zur Laufzeit wird der Aufruf blockiert und protokolliert (SEC-016).
  3. Diagnosen, Kartendaten und vollständige Personalprofile erscheinen in keinem Providerpayload (Kap. 05 §5.1).
  4. Eine Änderung der Feldfreigabe ist versioniert und benötigt Freigabe.
- **Tests:** positiv: Payload je Adapter entspricht Freigabe. negativ: zusätzliches Feld im DTO, PII in Freitext/Metadaten/Trace-Headern, Payload an Empfänger B mit Freigabe von A. konkurrierend (Datentrennung): Freigabeentzug parallel zu Outbound-Retry – kein Versand mit alter Feldmenge. Beitrag zu E2E-101; Regression: PH-AT-002, E2E-091.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-32, WP-M1-12 (Dateninventar mit Zweck und Rechtsgrundlage, Sicherheitsereignisse)
  - extern: WP-M2-29, WP-M2-32 (PSP- und Benachrichtigungsadapter)
  - extern: WP-M6a-05, WP-M6a-07, WP-M6b-29, WP-M6b-23 (Partneradapter, Capability Registry, Webhook-Abonnements)
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss), D-08 (Partnerstandards) – Freigabe des Diagramms blockiert Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Minimierte Föderationsprojektionen zwischen Betreibern (M6); Verarbeitungsregister (M8b).

### WP-M8e-19 – Rollenabhängig maskierte Auditansicht

- **Ziel:** Audit-Logs sind auswertbar, ohne unnötige Kontakt- und Bewegungsdaten offenzulegen; die Ansicht maskiert sensible Felder nach Rolle.
- **Anforderungen:** `LH-6.18-SEC-019`
- **Liefergegenstände:**
  - Backend: Klassifikation der Auditfelder (Kontakt, Standort/Bewegung, Unterstützungsbedarf, HR, Finanzen); Schreibpfad speichert nur maskierte Änderungen (`audit_event.redacted_changes`); Lesepfad mit Maskierungsprofil je Rolle und Zweckangabe; Aufdeckung einzelner Felder nur mit begründetem, befristetem Zugriff, der selbst auditiert wird.
  - Datenbank: `audit_event`; Maskierungsprofile in `policy_schema`/`config_revision`.
  - API: Arbeitsname `/v1/audit/events` (Liste nach Kap. 05 §5.1 mit `limit`/`cursor`).
  - Oberfläche: UI-20 (Auditliste und Detail mit maskierten Feldern; Zustände „fehlender Zweck“, „abgelaufener Zugriff“).
- **Detailverträge vorab:** Feldklassifikation; Maskierungsmatrix Rolle × Feldklasse; Exportziel der getrennten Integritäts-/Berechtigungsdomäne (offen seit M1).
- **Akzeptanzkriterien:**
  1. Die Auditansicht zeigt sensible Felder je Rolle maskiert; zwei Rollen sehen für dasselbe Ereignis unterschiedliche, der Matrix entsprechende Feldmengen (SEC-019).
  2. Kontakt- und Bewegungsdaten, die für die Nachvollziehbarkeit nicht erforderlich sind, werden gar nicht in das Audit geschrieben (SEC-019).
  3. Maskierung gilt identisch in Liste, Detail, Suche und Export.
  4. Jede Aufdeckung ist begründet, befristet und selbst protokolliert.
  5. Die Auditansicht zeigt die Zustände leer, Ladefehler, fehlendes Recht, „fehlender Zweck“ und „abgelaufener Zugriff“; Auditereignisse anderer Mandanten sind nicht abrufbar.
- **Tests:** positiv: Datenschutzrolle sieht mehr als Disponent. negativ: Filter/Sortierung/Suche auf maskiertem Feld lässt keinen Rückschluss zu; Export umgeht Maskierung nicht; Audit eines fremden Mandanten. konkurrierend (Sicherheit): Rollenentzug während paginiertem Abruf – Folgeseite maskiert nach neuer Rolle. Beitrag zu E2E-079; Regression: AT-49, PH-AT-018.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-46, WP-M1-12, WP-M1-31, WP-M1-07, WP-M1-43 (Audit append-only, Feldschutz, Rollenmatrix, UI-20-Basis)
  - extern: WP-M5-06 (Maskierung von Diagnosedaten in Logs)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Audit-Trail der Dispositionsentscheidungen (M7b); Zugriffsklassen für Fahrerprofile (M8b).

### WP-M8e-20 – PBefG-Betriebsform als Freigabevoraussetzung je Dienst

- **Ziel:** Betriebsart und Genehmigung nach anwendbarem PBefG sind je Dienst geprüft und in einem Freigabeprotokoll dokumentiert, bevor der Dienst produktiv geschaltet wird.
- **Anforderungen:** `LH-6.18-SEC-017`
- **Liefergegenstände:**
  - Backend: Compliance-Freigabe als Aggregat (Art „Betriebsform/Genehmigung“, genehmigte Betriebsform, zutreffende Regeln, Geltungszeitraum, Nachweisdokument, Prüfer, Freigeber); Produktivschaltung eines Dienstes prüft das Vorliegen einer wirksamen Freigabe; Ablauf erzeugt Warnung.
  - Datenbank: `compliance_approval` (Arbeitsname, append-only), `service`, `config_revision`, Nachweis im Objektspeicher mit Hash.
  - API: Arbeitsname `/v1/compliance/approvals`; Prüfung in `/v1/config/revisions/{revision_id}/publish`.
  - Oberfläche: UI-17 (Dienstfreigabe zeigt fehlende Genehmigung als blockierenden Zustand), UI-20 (Freigabeprotokoll).
- **Detailverträge vorab:** Katalog der Betriebsformen und der daraus folgenden Regeln (fachlich/rechtlich vom Auftraggeber zu liefern; im Pflichtenheft nicht enthalten); Vorlage Freigabeprotokoll.
- **Akzeptanzkriterien:**
  1. Für jeden produktiven Dienst liegt ein Freigabeprotokoll vor, das die genehmigte Betriebsform und die zutreffenden Regeln dokumentiert (SEC-017).
  2. Ein Dienst ohne wirksame Freigabe lässt sich nicht produktiv schalten; der Versuch wird mit Grund abgelehnt und protokolliert.
  3. Das Protokoll ist unveränderlich, datiert und einem Freigeber zugeordnet.
  4. Bereits bestätigte Buchungen eines Dienstes bleiben vom Ablauf einer Freigabe unberührt; der Ablauf erzeugt eine Warnung an die verantwortliche Rolle und verhindert nur weitere Produktivschaltungen (Planungsannahme, siehe Offene Punkte 16).
- **Tests:** positiv: Freigabe und Produktivschaltung. negativ: Produktivschaltung ohne bzw. mit abgelaufener Freigabe; Freigabe durch den Ersteller selbst; Freigabe eines fremden Mandanten. konkurrierend (Sicherheit): Widerruf bzw. Ablauf der Freigabe parallel zur Produktivschaltung – nie ein produktiver Dienst ohne im Publikationszeitpunkt wirksame Freigabe. Regression: AT-25.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-04.
  - extern: WP-M1-22, WP-M1-11, WP-M1-43, WP-M1-12 (Publikation von Konfigurationsrevisionen, Vier-Augen-Baustein, Objektspeicher mit Hash, UI-17-/UI-20-Basis)
- **Entscheidungen:** D-01 (Rollen Verkäufer/Beförderer), D-05 (Pilotbetreiber, Region) – blockieren die inhaltliche Freigabe und damit die Produktivschaltung, nicht den Bau.
- **Größe:** S
- **Nicht im Umfang:** Rechtliche Prüfung selbst (Auftraggeber); Parallelfahrtenverbot und Linienalternativen (M7a).

### WP-M8e-21 – Bewertung und Freigabe der Mobilitätsdatenpflichten vor Go-live

- **Ziel:** Mobilitätsdatenpflichten einschließlich möglicher nationaler Zugangspunkte sind fachlich bewertet; der Datenverantwortliche hat die passenden Export- und Veröffentlichungsprozesse genehmigt.
- **Anforderungen:** `LH-6.18-SEC-018`
- **Liefergegenstände:**
  - Backend: Compliance-Freigabe Art „Mobilitätsdaten“ je Mandant/Dienst (bewertete Pflichten, Datenkategorien, Zielstelle, Veröffentlichungsrhythmus, Genehmiger); Go-live-Checklistenpunkt; Veröffentlichung/Export an externe Stellen ist ohne genehmigten Prozess deaktiviert.
  - Datenbank: `compliance_approval` (Arbeitsname), `export_job`.
  - API: Arbeitsname `/v1/compliance/approvals`; Gate vor `/v1/reports/jobs` für Mobilitätsdatenexporte.
  - Oberfläche: UI-20 (Bewertung und Genehmigung), UI-19 (Exportjob zeigt fehlende Genehmigung).
- **Detailverträge vorab:** Bewertungsvorlage; Zuordnung Datenkategorie → Exportprozess aus M8c; Rolle „Datenverantwortlicher“.
- **Akzeptanzkriterien:**
  1. Vor Go-live liegt je Mandant eine dokumentierte fachliche Bewertung der Mobilitätsdatenpflichten vor (SEC-018).
  2. Der Datenverantwortliche hat die passenden Export-/Veröffentlichungsprozesse genehmigt; die Genehmigung ist datiert und nachweisbar (SEC-018).
  3. Ein Mobilitätsdatenexport an eine externe Stelle ohne Genehmigung wird nicht ausgeführt.
- **Tests:** positiv: Genehmigung, danach Exportlauf. negativ: Export ohne Genehmigung, Genehmigung durch nicht berechtigte Rolle; Genehmigung eines anderen Mandanten wird nicht anerkannt. konkurrierend (Sicherheit/Datentrennung): Widerruf der Genehmigung parallel zum laufenden Exportjob – kein Versand an die externe Stelle nach wirksamem Widerruf. Regression: AT-25.
- **Abhängigkeiten:** intern: WP-M8e-20.
  - extern: WP-M8c-30, WP-M8c-27 (Mobilitätsdatenexport als Berichtsjob)
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss), D-05 (Region) – blockieren Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Exportformate und -inhalte (M8c); GTFS-Bereitstellung (M6).

### WP-M8e-22 – Regelmäßige Sicherheitsprüfungen und Abhängigkeitsupdates mit Release-Sperre

- **Ziel:** Sicherheitsprüfungen und Abhängigkeitsupdates laufen regelmäßig automatisch; die Releasepipeline blockiert bei nicht akzeptierten kritischen Schwachstellen.
- **Anforderungen:** `LH-6.18-SEC-020`
- **Liefergegenstände:**
  - Adapter/Worker: zeitgesteuerte Pipeline-Läufe (Abhängigkeits-, Container-/Image-, Lizenz- und statische Analyse, Secret-Scan) für `backend`, `web`, `mobile`, `workers`, `infra`; automatische Update-Vorschläge mit Testlauf; SBOM je Release; Release-Gate „keine kritische Schwachstelle ohne dokumentierte, befristete Akzeptanz“; Akzeptanzregister mit Verantwortlichem und Ablaufdatum.
- **Detailverträge vorab:** Schweregradschema und Schwelle „kritisch“; Akzeptanzprozess (wer, wie lange); Scanrhythmus.
- **Akzeptanzkriterien:**
  1. Scans und Update-Prüfungen laufen ohne manuellen Anstoß im festgelegten Rhythmus und sind protokolliert (SEC-020).
  2. Ein Release-Kandidat mit einer nicht akzeptierten kritischen Schwachstelle wird von der Pipeline blockiert (SEC-020).
  3. Eine akzeptierte Schwachstelle hat Begründung, Verantwortlichen und Ablaufdatum; nach Ablauf blockiert sie erneut.
- **Tests:** positiv: sauberer Build passiert. negativ: Testabhängigkeit mit bekannter kritischer Schwachstelle blockiert; abgelaufene Akzeptanz blockiert; manipulierte Scan-Konfiguration im Merge-Request wird erkannt.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-38, WP-M0-14 (Sicherheits-Scans in CI, SBOM, Lockfiles)
  - extern: WP-M5-22 (Release-Gate in CI)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Penetrationstest (M5, Wiederholung in WP-M8e-33).

### WP-M8e-23 – Responsive Oberflächen und Gerätetestmatrix

- **Ziel:** Alle Web-Oberflächen sind responsiv; die definierten Mobil- und Desktopbrowser in aktueller Hauptversion erreichen alle Kernreisen ohne schwerwiegenden Fehler.
- **Anforderungen:** `LH-6.19-NFR-010`
- **Liefergegenstände:**
  - Oberfläche: Nachbesserung von UI-01 bis UI-20 in `web/passenger` und `web/operator` auf Reflow bei 320 CSS-Pixeln, 200-%-Textvergrößerung und Touchflächen mindestens 44×44 CSS-Pixel als Designziel (Kap. 06 §6.1); mobile Darstellung „ein primärer Arbeitsschritt pro Seite“.
  - Adapter/Worker: Gerätetestmatrix (Browser × Betriebssystem × Viewport) als automatisierte Suite der Kernreisen in `tests/acceptance`; Protokoll je Lauf mit Build-ID; Geräte-/Browserbereitstellung für die Matrix (eigene Geräte oder Testdienst – im Pflichtenheft nicht festgelegt, siehe Offene Punkte).
- **Detailverträge vorab:** Liste der „definierten“ Browser und Geräte (im Pflichtenheft nicht benannt); Definition „Kernreise“ je Rolle (Kap. 06 §6.9 UI-Abnahme); Definition „schwerwiegender Fehler“.
- **Akzeptanzkriterien:**
  1. Die Gerätetestmatrix erreicht alle Kernreisen auf jeder definierten Browser-/Gerätekombination ohne schwerwiegenden Fehler (NFR-010).
  2. Jede Oberfläche ist bei 320 CSS-Pixeln ohne horizontales Scrollen bedienbar.
  3. Eine neue Browser-Hauptversion wird innerhalb der vereinbarten Frist in die Matrix aufgenommen.
  4. Jede Oberfläche bleibt bei 200-%-Textvergrößerung ohne Inhalts- oder Funktionsverlust bedienbar.
  5. Das Matrixprotokoll weist je Kombination Kernreise, Ergebnis, Build und Mangelklasse aus; ein schwerwiegender Fehler verhindert die Freigabe.
- **Tests:** positiv: Matrixlauf Kernreisen (Buchung zweier Betreiber mit Teilstorno, Callcenter, Mandantenwechsel mit zwei Tabs, 412-Konflikt). negativ: nicht unterstützter Browser erhält verständlichen Hinweis statt defekter Seite. Regression: AT-19, AT-54.
- **Abhängigkeiten:** intern: WP-M8e-03.
  - extern: WP-M1-43 (Web-Designsystem, Admin-Oberflächen UI-17, UI-18, UI-20)
  - extern: WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 (Passenger-PWA UI-01 bis UI-05)
  - extern: WP-M3-36, WP-M3-24, WP-M3-25, WP-M3-26, WP-M3-03 (UI-07, UI-09, UI-10, UI-11)
  - extern: WP-M4-18, WP-M4-19, WP-M4-20, WP-M4-38, WP-M4-44, WP-M4-59 (UI-12 bis UI-15, UI-19)
  - extern: WP-M5-02, WP-M5-23 (Staging-Umgebung, durchgängiger E2E-Referenzfall)
  - extern: WP-M6a-16, WP-M6c-30 (UI-08, UI-16 (Klärarbeitsplatz, Clearing))
  - extern: WP-M7a-37, WP-M7a-38 (native Passenger-App (Gerätematrix Mobil))
  - extern: WP-M7b-35 (Callcenter UI-06)
- **Entscheidungen:** D-05 (Zielgeräte Pilot) – Inhalt der Matrix.
- **Größe:** M
- **Nicht im Umfang:** Driver-App-Zielgeräte (Spike M0, M3); Barrierefreiheitskriterien (WP-M8e-24).

### WP-M8e-24 – Barrierefreiheit aller Nutzeroberflächen nach Prüfplan (Ziel WCAG 2.2 AA)

- **Ziel:** Alle Nutzeroberflächen erreichen das vereinbarte Barrierefreiheitsniveau; Tastatur-, Screenreader- und Kontrasttests bestehen nach Prüfplan.
- **Anforderungen:** `LH-6.19-NFR-011`
- **Liefergegenstände:**
  - Oberfläche: Prüfung und Nachbesserung von UI-01 bis UI-20 sowie `mobile/driver` und `mobile/passenger`: vollständige Tastaturbedienung mit sichtbarem Fokus, Alternativen zu Karte und Drag-and-drop (gleichwertige Listen), Status nie nur als Farbe, Live-Regionen für asynchrone Meldungen, reduzierte Bewegung, Kontrast, Screenreader-Beschriftungen (Kap. 06 §6.1).
  - Adapter/Worker: Prüfplan mit Kriterienkatalog; automatisierte Regeln in CI; manuelle Screenreader-Protokolle je Oberfläche; Komponentenprüfung im Designsystem.
- **Detailverträge vorab:** Prüfplan und „vereinbartes Niveau“ (Ziel WCAG 2.2 AA, Kap. 06 §6.1); Screenreader-/Browserkombinationen; Verfahren für genehmigte Abweichungen.
- **Akzeptanzkriterien:**
  1. Tastatur-, Screenreader- und Kontrasttests bestehen für jede Oberfläche nach Prüfplan; Abweichungen sind einzeln genehmigt und dokumentiert (NFR-011).
  2. Die UI-Abnahmefälle aus Kap. 06 §6.9 sind vollständig per Tastatur und Screenreader ausführbar.
  3. Die automatisierte Prüfung ist Release-Gate; ein neuer Verstoß der vereinbarten Stufe blockiert.
  4. Betreiber-Branding und alle konfigurierten Sprachen bestehen dieselben Prüfungen.
  5. Fehler-, Leer-, Lade- und Rechtezustände jeder Oberfläche sowie zeitkritische Meldungen (Hold-Ablauf, Offline-Hinweis) sind für Screenreader wahrnehmbar und per Tastatur erreichbar.
- **Tests:** positiv: Prüfplanlauf je Oberfläche. negativ: Komponente mit fehlendem Namen/Fokus/Kontrast scheitert in CI; Pflichtaktion nur per Karte oder Drag-and-drop gilt als Fehler. Regression: AT-19, AT-54.
- **Abhängigkeiten:** intern: WP-M8e-06, WP-M8e-07, WP-M8e-23.
  - extern: WP-M1-43 (Web-Designsystem mit Komponentenprüfung)
  - extern: WP-M3-24, WP-M3-25, WP-M3-26 (Driver-App (UI-09, UI-10))
  - extern: WP-M5-18, WP-M5-30 (Barrierefreiheitsprüfung der Kernreisen (UI-01 bis UI-05, UI-07, UI-09, UI-10) als Ausgangsstand)
  - extern: WP-M7a-37, WP-M7a-38 (native Passenger-App)
- **Entscheidungen:** keine für den Bau; genehmigte Abweichungen brauchen AG-Freigabe.
- **Größe:** L
- **Nicht im Umfang:** Unabhängiger Audit (WP-M8e-25); Leitstellen-Schutzdialog (WP-M8e-26).

### WP-M8e-25 – Unabhängiger Barrierefreiheitsaudit und dienstbezogene Rechtslageprüfung

- **Ziel:** Ein unabhängiger Audit bestätigt die vereinbarten Prüfkriterien für App und Web; die Rechtslage ist je Dienst geprüft und dokumentiert.
- **Anforderungen:** `LH-6.18-SEC-021`
- **Liefergegenstände:**
  - Adapter/Worker: Auditvorbereitung (Prüfumfang, Testzugänge, synthetische Daten), Begleitung, Befundliste mit Zuordnung zu Oberflächen; Behebung oder genehmigte Abweichung je Befund; Vermerk der dienstbezogenen Rechtslageprüfung als Compliance-Freigabe; Erklärung zur Barrierefreiheit als Pflichttext je Betreiber.
  - Datenbank: `compliance_approval` (Arbeitsname, Art „Barrierefreiheit“), `tenant_branding_revision` (Arbeitsname, Pflichttext).
  - Oberfläche: Pflichttext „Barrierefreiheit“ in `web/passenger`/`mobile/passenger`.
- **Detailverträge vorab:** vereinbarte Prüfkriterien; Auswahl der unabhängigen Stelle (Auftraggeber); Abweichungsverfahren.
- **Akzeptanzkriterien:**
  1. Ein unabhängiger Audit bestätigt die vereinbarten Prüfkriterien für App und Web bis auf genehmigte Abweichungen; der Bericht liegt vor (SEC-021).
  2. Für jeden Dienst ist dokumentiert, welche rechtlichen Barrierefreiheitsanforderungen geprüft wurden und mit welchem Ergebnis (SEC-021).
  3. Jeder Auditbefund ist behoben oder trägt eine genehmigte Abweichung mit Frist.
- **Tests:** positiv: Nachprüfung der behobenen Befunde. negativ: offener Befund ohne Genehmigung verhindert den Abnahmevermerk.
- **Abhängigkeiten:** intern: WP-M8e-07, WP-M8e-20, WP-M8e-24.
- **Entscheidungen:** D-12 (Budget für externen Audit), D-05 (betroffene Dienste) – blockieren die Durchführung, nicht die Vorbereitung.
- **Größe:** S
- **Nicht im Umfang:** Umsetzung der Barrierefreiheit (WP-M8e-24).

### WP-M8e-26 – Leitstelle: Tastaturbedienung der Schlüsselaktionen und Schutzdialog

- **Ziel:** Schlüsselaktionen der Leitstelle sind über Tastatur nutzbar; irreversible Eingriffe verlangen eine Rückfrage.
- **Anforderungen:** `LH-6.19-NFR-014`
- **Liefergegenstände:**
  - Oberfläche: UI-07 und UI-08 – Tastaturkürzel und Fokusführung für die Schlüsselaktionen (Arbeitsliste, Buchung öffnen, Umdisposition, Fahrgast informieren, Fall eröffnen, Statusabgleich, Kompensation, Sperre); Kürzelübersicht; Schutzdialog mit Folgenbeschreibung, Pflichtbegründung und sicherem Standardfokus auf „Abbrechen“; Tastaturalternative zu Drag-and-drop.
  - Backend: Kennzeichnung irreversibler Kommandos im Vertrag (Bestätigungsfeld serverseitig Pflicht).
  - API: `/v1/operator/vehicles`, `/v1/operator/incidents`, `/v1/runs/{id}/revisions` (Bestätigungs-/Begründungsfeld).
- **Detailverträge vorab:** Liste der „Schlüsselaktionen“ und der „irreversiblen Eingriffe“ (im Pflichtenheft nicht aufgezählt); Kürzelbelegung ohne Konflikt mit Screenreadern.
- **Akzeptanzkriterien:**
  1. Ein UI-Test führt jede Schlüsselaktion der Leitstelle ausschließlich per Tastatur aus (NFR-014).
  2. Jeder irreversible Eingriff zeigt einen Schutzdialog; ohne ausdrückliche Bestätigung wird nichts ausgeführt (NFR-014).
  3. Der Server lehnt ein irreversibles Kommando ohne Bestätigungs-/Begründungsfeld ab.
  4. Doppelte Betätigung der Bestätigung löst die Aktion genau einmal aus.
  5. Der Schutzdialog ist per Tastatur vollständig bedienbar und für Screenreader als Dialog mit Folgenbeschreibung angekündigt; Kürzel wirken nicht in Eingabefeldern.
  6. Jeder bestätigte irreversible Eingriff ist mit Akteur, Begründung und betroffenem Objekt auditiert; ein Nutzer ohne Recht erhält die Aktion weder als Kürzel noch als Schaltfläche.
- **Tests:** positiv: Tastaturpfad je Schlüsselaktion. negativ: Enter-Dauerdruck bestätigt nicht versehentlich; API-Aufruf ohne Bestätigung. konkurrierend (Sicherheit): zwei Disponenten bestätigen denselben Eingriff parallel – Plan-CAS, genau eine Wirkung. Regression: AT-51, AT-54.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M3-36, WP-M3-40 (Leitstelle UI-07, manuelle Umplanung mit Plan-CAS)
  - extern: WP-M7b-04, WP-M7b-08, WP-M7b-13, WP-M7b-32, WP-M6a-16 (erweiterte Leitstellenfunktionen, Klärarbeitsplatz UI-08)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Auswirkungsvorschau manueller Eingriffe (M7a/M7b).

### WP-M8e-27 – Kritische Fehlermeldungen mit Fehler-ID und Handlungshinweis

- **Ziel:** Kritische Fehlermeldungen liefern in allen Clients eine Fehler-ID und einen sinnvollen Handlungshinweis statt technischer Interna.
- **Anforderungen:** `LH-6.19-NFR-016`
- **Liefergegenstände:**
  - Backend: Fehlerkatalog (stabiler `code` → lokalisierter Nutzertext → Handlungshinweis → Supportklasse) über `application/problem+json` mit `correlation_id` (Kap. 05 §5.1); globaler Fänger für unerwartete Fehler ohne Stacktrace, SQL- oder Hostangaben; gleiche Regel für Gateway/BFF- und Worker-Fehler.
  - Oberfläche: einheitliche Fehlerkomponente im Designsystem und in den Flutter-Apps (Fehler-ID kopierbar, nächster Schritt, Kontaktweg); UI-01 bis UI-10 Fehlerzustände aus Kap. 06 §6.9.
- **Detailverträge vorab:** Fehlerkatalog mit Texten je Sprache; Klassifikation „kritisch“; Zuordnung Fehler-ID ↔ Trace für den Support.
- **Akzeptanzkriterien:**
  1. Ein provozierter Serverfehler zeigt dem Fahrgast eine Fehler-ID und einen Handlungshinweis; Antwort und Oberfläche enthalten keine Stacktrace-Informationen oder technischen Interna (NFR-016).
  2. Der Support findet über die Fehler-ID den zugehörigen Vorgang in Logs/Traces.
  3. Jeder Fehlercode des Katalogs hat in jeder konfigurierten Sprache Text und Handlungshinweis.
  4. Ohne Verbindung zeigt der Client eine eigene Meldung mit Handlungshinweis und ohne technische Interna; die Fehleranzeige ist für Screenreader wahrnehmbar.
  5. Logs und Traces zur Fehler-ID enthalten keine unnötigen Kontakt- oder Bewegungsdaten.
- **Tests:** positiv: Katalogfehler je Client. negativ: unbehandelte Ausnahme, Datenbankfehler, Adapter-Timeout, fehlerhafte Gateway-Antwort – jeweils ohne Interna; nicht sichtbare Ressource liefert 404 ohne Fremdbezeichnung; Verbindungsabbruch in `mobile/driver` und `web/passenger`. Regression: AT-23.
- **Abhängigkeiten:** intern: WP-M8e-06.
  - extern: WP-M1-02, WP-M1-43 (einheitliches Fehlermodell, Tracing, Web-Designsystem)
  - extern: WP-M3-24 (Driver-App)
  - extern: WP-M7a-37 (native Passenger-App)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Kontextsensitive Hilfetexte in einfacher Sprache (M7a).

### WP-M8e-28 – Semantische API-Versionierung und Übergangsfristen

- **Ziel:** Dokumentierte APIs tragen semantische Versionen; Breaking Changes haben eine Übergangsfrist und die Änderungsdokumentation weist betroffene Clients aus.
- **Anforderungen:** `LH-6.19-NFR-017`
- **Liefergegenstände:**
  - Backend: Versionsfeld je OpenAPI-Datei nach SemVer; automatischer Diff gegen den letzten freigegebenen Stand in CI (Breaking Change ohne neue Major-Version blockiert); Parallelbetrieb alter und neuer Major-Version; Ankündigungs-Header für abgekündigte Operationen; Nutzungsregister, welcher Client (Service-Account, Partner, App-Version) welche Operation aufruft.
  - Adapter/Worker: Änderungsdokumentation je Release mit Liste betroffener Clients; Benachrichtigung betroffener Partner.
  - Datenbank: `webhook_subscription`, `adapter_capability` (gebundene Vertragsversion); Nutzungsregister (Arbeitsname `api_client_usage`, Migration in WP-M8e-02; ohne Personenbezug über Client-/Service-Account-Kennung).
- **Detailverträge vorab:** Versionsrichtlinie; Übergangsfrist (Planungsdefault mindestens sechs Monate Parallelbetrieb, Kap. 05 §5.1 – konkrete Partnerfristen vertraglich zu bestätigen); Versionsregel für Ereignis-/Webhook-Schemata.
- **Akzeptanzkriterien:**
  1. Jede dokumentierte API trägt eine semantische Version; ein Breaking Change ohne Major-Erhöhung scheitert in CI (NFR-017).
  2. Die Änderungsdokumentation eines Release weist für jede Änderung die betroffenen Clients aus (NFR-017).
  3. Während der Übergangsfrist bedient die Plattform alte und neue Major-Version; Aufrufe abgekündigter Operationen sind mit Client auswertbar.
- **Tests:** positiv: kompatible Erweiterung als Minor. negativ: entferntes Feld/geänderter Typ ohne Major; Abschaltung vor Fristende wird vom Gate verhindert; Nutzungsregister eines Mandanten zeigt keine Clients anderer Mandanten. Regression: Contract-Tests aus Kap. 05 §5.8.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-03 (OpenAPI je Modul und Contract-Test-Pipeline)
  - extern: WP-M6b-04, WP-M6b-23, WP-M6b-30, WP-M6a-05 (Partnerverträge, Webhook-Abonnements, Capability Registry)
- **Entscheidungen:** D-08 (Partnerstandards, vertragliche Fristen) – bestätigt die Frist; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Versionierung fachlicher Revisionen (Tarif, Konfiguration, Vertrag).

### WP-M8e-29 – Release-Rollback ohne Verlust bestätigter Buchungen

- **Ziel:** Eine Releasebereitstellung kann zurückgenommen werden, ohne bestätigte Buchungen oder deren Zahlungsstatus zu verlieren.
- **Anforderungen:** `LH-6.19-NFR-013`
- **Liefergegenstände:**
  - Adapter/Worker: Rollback-Verfahren in `infra` (gestaffeltes Rollout, Rückschaltung auf vorheriges signiertes Image); expand/contract-Migrationen mit Kompatibilitätsprüfung Vorversion ↔ neues Schema; kompatible Ereignisschemata; Rollback-Drehbuch und automatisierter Test-Rollback in Staging; Regel „Vorwärtskorrektur statt Löschen fachlicher Daten“ (Kap. 01 §1.8).
  - Backend: Kompatibilitätstest, dass die Vorversion mit dem migrierten Schema Buchungen und Zahlungen liest und fortführt; Outbox-/Inbox-Verträglichkeit bei gemischten Versionen.
- **Detailverträge vorab:** Migrationsrichtlinie (kein destruktiver Schritt im selben Release); Rollback-Entscheidungskriterien; Umgang mit während des neuen Release entstandenen Daten.
- **Akzeptanzkriterien:**
  1. Ein Test-Rollback in Staging erhält alle bestätigten Buchungen mit ihrem Buchungs- und Zahlungsstatus; Vorher-/Nachher-Abgleich ist ohne Differenz (NFR-013).
  2. Während des neuen Release erstellte Buchungen und Zahlungen sind nach dem Rollback vorhanden und bearbeitbar.
  3. Offene Holds, laufende Operationen und UNKNOWN-Zahlungen werden nach dem Rollback abgeglichen, nicht verworfen oder doppelt ausgeführt.
  4. Eine Migration mit destruktivem Schritt im selben Release wird vom Gate abgelehnt.
- **Tests:** positiv: Rollback unter Last mit laufenden Buchungen. negativ: Release mit inkompatibler Migration; Ereignis im neuen Schema an alten Consumer. konkurrierend (Geld/Kapazität): Rollback während Confirm/Capture – genau eine Buchung, keine zweite Belastung, kein überbuchter Platz. Regression: AT-23, PH-AT-004.
- **Abhängigkeiten:** intern: WP-M8e-03.
  - extern: WP-M5-02, WP-M5-14, WP-M5-16 (Staging-Umgebung, Wiederanlaufdrehbuch, Fault-Injection)
  - extern: WP-M2-23, WP-M2-30, WP-M0-23 (Buchungs- und Zahlungsautomat, PSP-Simulator)
- **Entscheidungen:** D-05 (Hosting, Wartungsregel) – Produktivverfahren.
- **Größe:** M
- **Nicht im Umfang:** Konfigurations-Rollback als neue Revision (M1, E2E-098); Restore aus Backup (M5).

### WP-M8e-30 – Lieferkette: Staging, Performance-/Sicherheitstests, CI/CD-Gates, Incident-Management

- **Ziel:** Ein Release durchläuft automatisierte Gates in Staging einschließlich Performance- und Sicherheitstests und endet mit einem Freigabeprotokoll; Incidents folgen einem standardisierten Prozess.
- **Anforderungen:** `LH-6.19-NFR-018`
- **Liefergegenstände:**
  - Adapter/Worker: durchgängige Pipeline Build → Vertragstests → Integrationstests → Staging-Deployment → automatisierte Performance-Stichprobe und Sicherheitstests → fachliche E2E-Suite → Freigabeprotokoll (Build-ID, Migrationsstand, Gate-Ergebnisse, Freigeber) → Produktion; getrennte Secrets, PSP-Konten und Identitätsdomänen je Umgebung (Kap. 01 §1.8); standardisierter Incident-Prozess (Erfassung, Klassifikation, Rollen, Kommunikation, Nachbereitung) mit Werkzeuganbindung an das Alerting.
- **Detailverträge vorab:** Gate-Katalog mit Schwellen (Release-Gates Kap. 01 §1.8); Format des Freigabeprotokolls; Incident-Prozessbeschreibung; Abgrenzung Notfall-Release.
- **Akzeptanzkriterien:**
  1. Ein Release läuft von Commit bis Produktionsfreigabe mit automatisierten Gates durch und erzeugt ein Freigabeprotokoll (NFR-018).
  2. Ein fehlgeschlagenes Gate (Vertrag, Performance-Schwelle, Sicherheitstest, E2E) verhindert die Freigabe (NFR-018).
  3. Staging ist von Produktion getrennt (Daten, Secrets, Identität); Produktionsdaten gelangen nicht nach Staging.
  4. Ein Test-Incident durchläuft den standardisierten Prozess mit dokumentierter Nachbereitung.
- **Tests:** positiv: vollständiger Release-Durchlauf. negativ: künstliche Performance-Regression, fehlgeschlagener RLS-Test, fehlende Signatur des Images, manuelle Freigabe ohne Protokoll. Regression: PH-AT-001, AT-23.
- **Abhängigkeiten:** intern: WP-M8e-22, WP-M8e-29.
  - extern: WP-M5-02, WP-M5-22, WP-M5-10, WP-M5-04, WP-M5-26 (Staging-/Performance-Umgebung, Release-Gate in CI, Referenzlastprofil, Alerting, Betriebshandbuch)
- **Entscheidungen:** D-05 (Hosting, SLA, Betrieb) – Produktivverfahren.
- **Größe:** M
- **Nicht im Umfang:** Vertragliche Supportkanäle und P0–P3 (WP-M8e-35); vollständiger Last- und Failovernachweis (WP-M8e-33).

### WP-M8e-31 – Export-/Archivstrategie und Portabilität der Stammdaten

- **Ziel:** Eine konfigurierbare Export- und Archivstrategie ist umgesetzt; alle betriebsrelevanten Stammdaten lassen sich über ein dokumentiertes Exportschema in eine unabhängige Testinstanz migrieren.
- **Anforderungen:** `LH-6.19-NFR-020`
- **Liefergegenstände:**
  - Backend: Stammdaten-Exportpaket je Mandant (Organisation, Rechtsträger, Dienste, Bediengebiete, Haltepunkte, Tarife, Konfigurationsrevisionen, Fahrzeuge, Personal-/Qualifikationsstamm, Checklistenvorlagen, Kontierung) mit Schema-/Policyversion und Checksumme; Eigentümerdaten und geteilte Projektionen getrennt; opake Partnerreferenzen bleiben erhalten (Kap. 04 §4.7); Import in leere Instanz mit Migrationsprotokoll; konfigurierbare Archivstrategie (Rhythmus, Ziel, Aufbewahrung, Objektsperre).
  - Datenbank: `export_job`, `retention_rule`; gelesen `tenant`, `legal_entity`, `organization`, `service`, `service_area`, `stop_revision`, `config_revision`, `vehicle`, `tariff_revision`, `tariff_rule`.
  - API: Arbeitsname `/v1/exports/master-data` (asynchron nach Kap. 05 §5.1).
  - Oberfläche: UI-20/Admin (Export anstoßen, Archivplan, Protokoll).
  - Konfigurationsparameter: `invoice_archive_years` wird von der Archivstrategie berücksichtigt (führend M8a).
- **Detailverträge vorab:** Exportschema (Objektliste „betriebsrelevante Stammdaten“ – im Pflichtenheft nicht abschließend benannt); Archivziele; Importreihenfolge und Referenzauflösung.
- **Akzeptanzkriterien:**
  1. Ein Stammdatenexport von Mandant A lässt sich allein anhand des dokumentierten Exportschemas in eine unabhängige leere Testinstanz importieren; dort ist eine Buchung im migrierten Dienst möglich (NFR-020).
  2. Export- und Archivstrategie sind je Mandant konfigurierbar und ihre Läufe protokolliert (NFR-020).
  3. Das Paket enthält Schema-/Policyversion und Checksumme; ein verändertes Paket wird beim Import abgelehnt.
  4. Der Export enthält keine Daten anderer Mandanten und nur mit gesonderter Berechtigung personenbezogene Stammdaten.
  5. Das Paket enthält keine Secretwerte (nur Secretreferenzen); Anstoß, Download und Import sind auditiert, der Download ist kurzzeitig gültig (Planungsdefault höchstens 60 Sekunden, Kap. 02 §4).
- **Tests:** positiv: Export → Import → Vergleich Objektanzahl und Stichproben. negativ: Export durch Rolle ohne Berechtigung, manipuliertes Paket, Import in nicht leere Instanz, Paket mit Secretwert, wiederholter Exportanstoß mit gleichem `Idempotency-Key` erzeugt genau einen Job. konkurrierend (Datentrennung): Export parallel zu Stammdatenänderung – konsistenter As-of-Stand. Regression: AT-53, AT-61.
- **Abhängigkeiten:** intern: WP-M8e-02, WP-M8e-03.
  - extern: WP-M1-11, WP-M1-15 (Isolation in Exporten, Objektspeicher, `operation`)
  - extern: WP-M5-25, WP-M5-19 (Stammdateneinspielung mit Migrationsprotokoll, Export-/Archivnachweise)
- **Entscheidungen:** D-11 (Aufbewahrung), D-06 (Archivmodell), D-02 (Isolationsstufe/Datenbankwechsel je Mandant) – Produktivstrategie.
- **Größe:** M
- **Nicht im Umfang:** Bewegungsdatenexporte und Berichte (M8c); ERP-/Archivexport der Belege (M4/M8a); Betroffenenexport (WP-M8e-17).

### WP-M8e-32 – Vier-Augen-Freigabe für Tarif- und Regelkonfiguration

- **Ziel:** Änderungen an Tarif- und Regelkonfiguration werden durch Vier-Augen-Freigabe oder einen definierten Prüfworkflow kontrolliert; nicht Freigegebenes wirkt produktiv nicht.
- **Anforderungen:** `LH-6.20-QAT-010`
- **Liefergegenstände:**
  - Backend: einheitlicher Prüfworkflow für alle produktiv wirksamen Regelartefakte (Konfigurationsrevisionen, Tarifrevisionen, Flags, Branding, Automatisierungsregeln); Ersteller ≠ Prüfer serverseitig erzwungen; konfigurierbare Workflowvariante (Vier-Augen oder definierter Prüfworkflow mit Rollenfolge); Wirksamkeitsprüfung liest ausschließlich freigegebene, publizierte Revisionen.
  - Datenbank: `config_revision` (`created_by`, `approved_by`, `published_at` – CHECK „publiziert nur mit Prüfer“ im Kern-DDL; CHECK „Prüfer ungleich Ersteller“ aus WP-M8e-02), `tariff_revision`, `tariff_rule`, `audit_event`.
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/revisions/{revision_id}/publish`.
  - Oberfläche: UI-17 (Revision freigeben, Zustand „konkurrierende Version“), Konfigurationseditor (Kap. 06 §6.8: Prüfer, Wirksamkeitsdatum, Vorschau).
- **Detailverträge vorab:** Liste der kontrollierten Artefakte; Workflowvarianten und Rollen; Notfalländerung (im Pflichtenheft nicht geregelt).
- **Akzeptanzkriterien:**
  1. Eine nicht freigegebene Änderung an Tarif- oder Regelkonfiguration wird produktiv nicht wirksam: Angebote, Preise und Regelprüfungen verwenden weiterhin die letzte freigegebene Revision (QAT-010).
  2. Der Ersteller kann seine eigene Änderung nicht freigeben; der Versuch wird abgelehnt und protokolliert (QAT-010).
  3. Für jedes kontrollierte Artefakt ist die Workflowvariante festgelegt und ihre Einhaltung im Audit nachweisbar.
  4. Publikation ohne Prüfer scheitert auch bei direktem Datenbankzugriff der Runtime-Rolle am Constraint.
  5. Eine während der Prüfung bereits bestätigte Buchung behält Preis und Regelrevision ihrer Zusage; die neue Revision wirkt erst ab Wirksamkeitsdatum (E2E-098, PH-AT-018).
- **Tests:** positiv: Erstellen → Prüfen → Publizieren → wirksam. negativ: Selbstfreigabe, Publikation eines Entwurfs, Freigabe durch Rolle ohne Recht, nachträgliche Änderung einer freigegebenen Revision, Publikation ohne `If-Match` (428) oder mit veralteter Revision (412), Revision eines fremden Mandanten, wiederholte Publikation mit gleichem `Idempotency-Key` und abweichendem Request (PH-AT-011). konkurrierend (Geld/Sicherheit): zwei Prüfer geben konkurrierende Revisionen desselben Scopes parallel frei – genau eine wirksame Version (Invariante „nicht überlappende aktive Tarif-/Policy-/Dienstversion“). Regression: E2E-096, E2E-097, E2E-098, PH-AT-011.
- **Abhängigkeiten:** intern: WP-M8e-02.
  - extern: WP-M1-22 (Validierung, Vier-Augen-Freigabe, Publikation und Rollback der Konfiguration)
  - extern: WP-M2-07, WP-M2-09 (Tarifrevision und unveränderlicher `fare_quote`)
  - extern: WP-M8d-03, WP-M8d-05, WP-M8d-21 (Parameterkatalog-Vervollständigung und Automatisierungsregeln als kontrollierte Artefakte)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Belegfreigabe mit Schwellen (`approval_stage_thresholds`, M4/M8a); Modellfreigaben (M8b).

### WP-M8e-33 – Signierbare Abnahmeprotokolle für Last-, Failover-, Datenschutz- und Barrierefreiheitstests

- **Ziel:** Last-, Failover-, Datenschutz- und Barrierefreiheitstests sind fester Teil jeder Teil- und der Gesamtabnahme; je Testart liegt ein datiertes, signierbares Protokoll vor.
- **Anforderungen:** `LH-6.20-QAT-007`
- **Liefergegenstände:**
  - Adapter/Worker: Wiederholung der vier Testarten auf dem P2-Stand: Lasttest gegen das Referenzlastprofil mit mindestens zwei Mandanten (`tests/performance`), Failover-/Restore-Übung mit RPO/RTO-Messung, Datenschutztest (Mandanten-/Feldzugriff, Auskunft/Löschung, Providerpayload, Auditmaskierung), Barrierefreiheitstest nach Prüfplan; Protokollgenerator mit Pflichtfeldern aus Kap. 08 §8.4 (`source_key[]`, Test-ID/Version, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen/Mandanten, Soll/Ist, Trace-IDs, Ergebnis, Mangelklasse, Verantwortlicher, Abnahmeentscheidung); Signaturfeld und unveränderliche Ablage mit Hash.
  - Kennzahlen: Protokolle weisen die Zielgrößen aus Kap. 01 §1.8 aus (Standard-API p95 ≤ 1 s, Angebote p95 ≤ 5 s, Planupdate p95 ≤ 10 s, RPO ≤ 15 min, RTO ≤ 2 h).
- **Detailverträge vorab:** Protokollvorlage je Testart; Signaturverfahren (im Pflichtenheft nicht festgelegt); Zuordnung Testart ↔ Teilabnahme.
- **Akzeptanzkriterien:**
  1. Für jede der vier Testarten liegt je Teil- und Gesamtabnahme ein datiertes, signierbares Protokoll vor (QAT-007).
  2. Jedes Protokoll enthält die Pflichtfelder aus Kap. 08 §8.4 und keine unnötigen Fahrgast- oder Personaldaten.
  3. Ein fehlendes oder nicht bestandenes Protokoll verhindert den Abnahmevermerk; Mängel sind nach Klasse A/B/C eingestuft.
  4. Ein nachträglich verändertes Protokoll ist über den Hash erkennbar.
- **Tests:** positiv: je Testart ein vollständiger Lauf mit Protokoll. negativ: Lauf ohne Build-ID, Protokoll mit Klarnamen, Abnahme mit fehlender Testart. Regression: AT-23, AT-49, AT-54, PH-AT-001; die Gate-Evidenzen des Meilensteins (PH-AT-022, E2E-070, E2E-074 bis E2E-079, E2E-099, E2E-101 bis E2E-103, E2E-105, E2E-106) werden in demselben Protokollformat abgelegt.
- **Abhängigkeiten:** intern: WP-M8e-16, WP-M8e-18, WP-M8e-19, WP-M8e-24, WP-M8e-30.
  - extern: WP-M5-10, WP-M5-15, WP-M5-17, WP-M5-31 (Referenzlastprofil, Ausfallübung mit RPO-/RTO-Messung, Sicherheitsabnahme, Nachweisführung)
- **Entscheidungen:** D-05 (Peaklast, SLA) – belastbares Lastprofil; D-12 (Abnahmetermine).
- **Größe:** M
- **Nicht im Umfang:** Erstmalige P1-Nachweise (M5); fachliche E2E-Szenarien der Nachbareinheiten.

### WP-M8e-34 – Pilotbetrieb mit Abbruchkriterien, Rückfallprozess und Pilotbericht

- **Ziel:** Der Pilotbetrieb der P2-Funktionen ist zeitlich begrenzt, hat vorab definierte Abbruchkriterien und einen Rückfallprozess; der Pilotbericht dokumentiert KPI-Ziele und offene Mängel.
- **Anforderungen:** `LH-6.20-QAT-008`
- **Liefergegenstände:**
  - Adapter/Worker: Pilotplan (Zeitraum, Gebiet, Dienste, aktivierte Flags, Verantwortliche); Abbruchkriterien als messbare Schwellen mit Überwachung; Rückfallprozess (Flags aus, Rückkehr zum P1-Betrieb, Kommunikation); Pilotberichtsvorlage und -erstellung aus Kennzahlen- und Mängeldaten.
  - Kennzahlen: Pilotziele auf Basis des Kennzahlkatalogs, mindestens `Angebotsquote`, `Buchungskonversion`, `Abholung pünktlich`, `Ankunft pünktlich`, `No-Show-Rate`, `Fahrzeugverfügbarkeit`, `Kundenkontakt-Auflösung` (Auswahl und Zielwerte legt der Auftraggeber fest).
  - Konfigurationsparameter: `kpi_aggregation_min_n`, `reporting_timezone` gelten für die Pilotauswertung.
- **Detailverträge vorab:** KPI-Ziele und Schwellen (im Pflichtenheft nicht beziffert); Entscheidungsgremium für Abbruch; Mängelklassifikation A/B/C.
- **Akzeptanzkriterien:**
  1. Vor Pilotbeginn sind Zeitraum, Abbruchkriterien und Rückfallprozess dokumentiert und freigegeben (QAT-008).
  2. Der Pilotbericht dokumentiert je KPI Ziel, Istwert und Datenstand sowie alle offenen Mängel mit Klasse und Maßnahme (QAT-008).
  3. Der Rückfallprozess ist vor Pilotbeginn einmal geübt: nach Auslösung sind die Pilotfunktionen deaktiviert und bestätigte Buchungen bleiben erhalten.
- **Tests:** positiv: Rückfallübung in Staging. negativ: Pilotstart ohne freigegebene Abbruchkriterien wird durch die Checkliste verhindert; Kennzahl mit Null-Nenner erscheint als „nicht berechenbar“.
- **Abhängigkeiten:** intern: WP-M8e-05, WP-M8e-33.
  - extern: WP-M5-29, WP-M5-26 (Pilotbereitschaft P1 (Abbruchkriterien, Rückfallbetrieb), Betriebshandbuch)
  - extern: WP-M8c-22, WP-M8c-25, WP-M8c-27 (Kennzahlkatalog, Zielwerte und Berichtsjobs)
- **Entscheidungen:** D-05 (Pilotbetreiber, Region, Zeitraum), D-12 (Termin) – blockieren den Pilotbeginn.
- **Größe:** S
- **Nicht im Umfang:** Pilot der Föderation (M6); ETA-Rollout-Stopp (M8b).

### WP-M8e-35 – Sicherheits-/Supportkanäle, Fehlerklassifikation P0–P3 und Musterincident

- **Ziel:** Sicherheits- und Supportkanäle sowie die Fehlerklassifikation P0 bis P3 sind vertraglich festgelegt und im Betrieb abgebildet; ein Musterincident durchläuft den vollständigen Kommunikationsprozess.
- **Anforderungen:** `LH-6.20-QAT-009`
- **Liefergegenstände:**
  - Adapter/Worker: Vertragsanlage „Support und Sicherheit“ (Kanäle, Erreichbarkeit, Klassen P0–P3 mit Definition, Reaktions- und Kommunikationspflichten); Abbildung der Klassen im Incident-Werkzeug und im Alerting; Sicherheitsmeldekanal; Kommunikationsvorlagen je Klasse und Empfängerkreis (Betreiber, Partner, Fahrgäste); Durchführung und Protokoll des Musterincidents.
  - Datenbank: keine neue Tabelle; Incidents werden im Incident-Werkzeug des Betriebs geführt. Ob die Klasse P0–P3 zusätzlich am fachlichen `support_case` geführt wird, entscheidet der Detailvertrag (siehe Offene Punkte); Kommunikationsschritte sind in `audit_event` nachvollziehbar.
- **Detailverträge vorab:** Definition und Fristen je Klasse (im Pflichtenheft nicht beziffert); Zuordnung P0–P3 zu den Mangelklassen A/B/C aus Lastenheft §11.3; Kontaktmatrix.
- **Akzeptanzkriterien:**
  1. Kanäle und Klassifikation P0–P3 sind in der Vertragsanlage festgelegt und im Betriebshandbuch und Werkzeug identisch abgebildet (QAT-009).
  2. Ein Musterincident durchläuft den vollständigen Kommunikationsprozess von Meldung über Klassifikation, Eskalation, Statusmeldungen bis Abschlussbericht; das Protokoll liegt vor (QAT-009).
  3. Eine Sicherheitsmeldung über den Sicherheitskanal erreicht die verantwortliche Rolle nachweislich.
  4. Incident-Protokoll und Kommunikation enthalten keine unnötigen Fahrgast- oder Personaldaten; Mitteilungen an Partner und Fahrgäste nennen keine Daten anderer Mandanten.
- **Tests:** positiv: Musterincident je Klasse P0 und P2. negativ: Incident ohne Klassifikation kann nicht geschlossen werden; Kommunikation an nicht berechtigten Empfängerkreis wird verhindert.
- **Abhängigkeiten:** intern: WP-M8e-30.
  - extern: WP-M1-41 (Sicherheitsvorfallprozess und Übung)
  - extern: WP-M5-26 (Betriebshandbuch mit Monitoring, Eskalation, Notbetrieb)
- **Entscheidungen:** D-05 (SLA, Betrieb), D-11 (Notbetriebs-/Kontaktprozess) – vertragliche Festlegung blockiert die Abnahme, nicht die Vorbereitung.
- **Größe:** S
- **Nicht im Umfang:** Fachlicher Supportfall-Workflow und Incident-Posteingang der Leitstelle (M7b).

### WP-M8e-36 – Abschlussübergabe: Dokumentation, Konfigurationen, Wiederherstellungsmittel; M8e-Abnahme

- **Ziel:** Der Abschluss erfolgt mit vollständiger Dokumentation, Konfigurationen und Wiederherstellungsmitteln; der Betreiber kann die dokumentierte Wiederherstellung in isolierter Umgebung selbst prüfen. Zugleich wird der M8e-Anteil am M8-Gate nachgewiesen.
- **Anforderungen:** `LH-6.20-QAT-012`
- **Liefergegenstände:**
  - Adapter/Worker: Übergabeinventar (Architektur-, Betriebs-, Administrator-, Schnittstellen- und Datenschutzdokumentation; OpenAPI-Stände; Konfigurations- und Flag-Export; IaC; Migrationsstände; Backup-/Restore-Drehbuch; Stammdaten-Exportpaket; Schlüssel-/Secret-Übergabeverfahren ohne Secretwerte in Dokumenten); angeleitete Wiederherstellung in isolierter Umgebung durch den Betreiber mit Protokoll; M8e-Abschlussbericht mit Zuordnung aller 31 Anforderungen zu Test und Evidenz.
  - Adapter/Worker: Regressionssuite M8e in `tests/acceptance`.
- **Detailverträge vorab:** Übergabeinventar als Checkliste; Abnahmekriterien der Wiederherstellungsprüfung; Verantwortliche auf Betreiberseite.
- **Akzeptanzkriterien:**
  1. Das Übergabeinventar ist vollständig; Dokumentation, Konfigurationen und Wiederherstellungsmittel sind übergeben und quittiert (QAT-012).
  2. Der Betreiber stellt allein anhand der übergebenen Unterlagen und Mittel das System in einer isolierten Umgebung wieder her; eine Buchung und ein Beleg aus dem Sicherungsstand sind danach lesbar (QAT-012).
  3. Nach der Wiederherstellung sind zuvor gelöschte/gesperrte Personen weiterhin gelöscht bzw. gesperrt (Lösch-/Sperrjournal).
  4. Alle 31 Anforderungen der Einheit sind mit Testfall und Evidenz verknüpft; alle 11 MUSS-Anforderungen haben einen grünen Test.
  5. Die Regressionen AT-18, AT-19, AT-23, AT-24, AT-25, AT-49, AT-53, AT-54, AT-59, AT-61, E2E-091, E2E-096 bis E2E-098, PH-AT-001, PH-AT-011, PH-AT-017 und PH-AT-018 sind auf dem M8-Stand grün; die Beiträge zu E2E-079, E2E-101, E2E-102 und E2E-105 sind an die führenden Einheiten übergeben.
- **Tests:** positiv: Wiederherstellung durch Betreiber. negativ: Wiederherstellung mit unvollständigem Inventar scheitert nachvollziehbar an der Checkliste; Dokument mit Secretwert wird vom Scan abgelehnt. konkurrierend (Datentrennung): Restore zweier Mandanten in dieselbe isolierte Umgebung – Isolation bleibt erhalten (PH-AT-001).
- **Abhängigkeiten:** intern: WP-M8e-04, WP-M8e-05, WP-M8e-06, WP-M8e-07, WP-M8e-08, WP-M8e-09, WP-M8e-10, WP-M8e-11, WP-M8e-12, WP-M8e-13, WP-M8e-16, WP-M8e-17, WP-M8e-18, WP-M8e-19, WP-M8e-20, WP-M8e-21, WP-M8e-22, WP-M8e-23, WP-M8e-24, WP-M8e-25, WP-M8e-26, WP-M8e-27, WP-M8e-28, WP-M8e-29, WP-M8e-30, WP-M8e-31, WP-M8e-32, WP-M8e-33, WP-M8e-34, WP-M8e-35 (alle Anforderungs-WPs, da der Abschlussbericht deren Evidenz verknüpft).
  - extern: WP-M5-27, WP-M5-14, WP-M5-31 (Dokumentationspaket P1, Wiederanlaufdrehbuch, Nachweisführung)
- **Entscheidungen:** D-05 (Hosting, Betriebsverantwortung), D-12 (Abschlusstermin) – blockieren den formalen Abschluss.
- **Größe:** M
- **Nicht im Umfang:** Gate-Abnahme der führenden M8-Tests (PH-AT-022 und ETA-Szenarien in M8b, E2E-101/103 in M8c, E2E-099 in M8d, E2E-070 in M8a).

## Offene Punkte

1. **Zuständigkeit `/v1/privacy/requests` zwischen M8e und M8b.** `LH-6.18-SEC-014`, `LH-6.2-IAM-012` (M8e) und `LH-I.11-GOV-006` (M8b) beschreiben denselben Prozess aus drei Blickwinkeln. Dieser Plan legt Workflow, Endpunkt, Legal Hold und Löschorchestrierung in M8e, Auskunftsinhalt aus dem Verarbeitungsregister und Aufbewahrungsprüfung in M8b. Das Pflichtenheft trennt das nicht.
2. **Fristen der Betroffenenanfrage.** `LH-6.18-SEC-014` verlangt „fristgerecht“, nennt aber weder Frist noch Verlängerungsregel noch das Verfahren der Identitätsprüfung; D-11 ist offen.
3. **Lösch- und Aufbewahrungsfristen, Legal Hold.** `LH-6.2-IAM-012`: „freigegebene Löschregeln“ und die zu erklärenden „gesetzlichen Aufbewahrungen“ sind nicht benannt; `retention_rule`, `erasure_job` haben in Kap. 04 §4.4 keine Feldliste; eine Tabelle für Legal Hold fehlt dort ganz (hier Arbeitsname `legal_hold`). Rollen für Setzen/Aufheben sind nicht festgelegt.
4. **Exportumfang „definierte Personendaten“.** `LH-6.2-IAM-012` nennt keinen Feldkatalog und kein Format.
5. **Feature-Flags nicht im Parameterkatalog.** `LH-6.1-SYS-008`: `kataloge.csv` enthält keine Flag-Parameter; offen ist, welche Merkmale schaltbar sein müssen, ob Flags als `config_revision` oder eigene Tabelle geführt werden und wie „Rollout“ (stufenweise Einführung) gemessen wird.
6. **Produkttypen.** `LH-6.1-SYS-004`: Unterschiede der fünf Typen in Buchungsregeln und Nutzerzugang sind nicht spezifiziert; Abgrenzung zu den produktbezogenen Buchungsregeln aus M7a ist nur planerisch gezogen. `service` im Kernschema hat kein Typ- oder Zugangsfeld.
7. **Sprachen.** `LH-6.1-SYS-009`: Kap. 06 §6.1 nennt „mindestens Deutsch und vorbereitete Internationalisierung“; welche weiteren Sprachen zu liefern sind und wer übersetzt, ist offen. Sprache rechtlich gebundener Texte bei Sprachwechsel ist nicht geregelt.
8. **White-Label in nativen Apps und Domains.** `LH-6.1-SYS-010`: unklar, ob „App-Namen“ getrennte Store-Apps je Betreiber bedeuten oder Laufzeit-Branding; Domain-/Zertifikatsbetrieb hängt an D-02/D-05. Kap. 04 §4.4 enthält keine Branding-Tabelle.
9. **Zentraler Identitätsanbieter.** `LH-6.2-IAM-003`: unterstützte Protokolle neben OIDC, zulässige Verzögerung bis eine IdP-Sperre wirkt, und das Verhalten der Driver-App im Offlinebetrieb nach Sperre sind nicht festgelegt. M1 liefert OIDC bereits für alle Rollen; der P2-Zusatz wird hier als Föderation mit Unternehmens-IdP verstanden – diese Auslegung ist zu bestätigen.
10. **Gastbuchung.** `LH-6.2-IAM-007`: Verifikationsverfahren, Tokenlaufzeit, zulässige Änderungen und Löschfrist für Gastdaten fehlen. M7b (`LH-6.13-SUP-001`) nutzt Gastbuchung bereits im Callcenter, obwohl sie hier erst in M8 liegt – Reihenfolge ist mit dem Auftraggeber zu klären (siehe auch M7b OP-10).
11. **Vertretungszugriff.** `LH-6.2-IAM-008` überschneidet sich mit der Buchung für Dritte aus M7a und Kap. 05 §5.9; welche Nachweisarten als „dokumentiert“ gelten, ist offen.
12. **Vertrauensperson.** `LH-6.2-IAM-013`: Katalog freigebbarer Benachrichtigungen, Informationspflicht gegenüber der hinterlegten Person und Rechtsgrundlage fehlen; Kap. 04 §4.4 enthält keine Tabelle dafür.
13. **Schüler-/Minderjährigenkonten.** `LH-6.18-SEC-022`: Altersgrenzen, Rollenmatrix, Autorisierungsnachweis und Kontaktregeln sind nicht beziffert.
14. **Rechtsgrundlagenmatrix.** `LH-6.18-SEC-015`: welche Zwecke auf Einwilligung beruhen, ist fachlich/rechtlich freizugeben; das Pflichtenheft nennt nur Marketing ausdrücklich.
15. **Datenflussdiagramm.** `LH-6.18-SEC-016` setzt ein freigegebenes Datenflussdiagramm mit Feldliste je Anbieter voraus; es existiert im Pflichtenheft nicht (nur „Minimaler Betreiberpayload“ in Kap. 02 für Partner).
16. **PBefG und Mobilitätsdaten.** `LH-6.18-SEC-017`, `LH-6.18-SEC-018` sind Projektvoraussetzungen ohne Softwarevertrag; Katalog der Betriebsformen, zutreffende Regeln, zuständige Stelle und die Rolle „Datenverantwortlicher“ sind vom Auftraggeber zu liefern. Die hier geplante technische Sperre (Dienst nicht produktiv ohne Freigabe) ist eine Planungsannahme, keine Forderung des Pflichtenhefts.
17. **Auditansicht.** `LH-6.18-SEC-019`: Kap. 05 §5.7 enthält keinen Audit-Endpunkt (bereits M1 OP-14); Maskierungsmatrix Rolle × Feld fehlt.
18. **Schwachstellenakzeptanz.** `LH-6.18-SEC-020`: Schwelle „kritisch“, Scanrhythmus und Akzeptanzberechtigte sind nicht festgelegt.
19. **Browser- und Gerätematrix.** `LH-6.19-NFR-010`: die „definierten“ Mobil- und Desktopbrowser, „Kernreisen“ und „schwerwiegender Fehler“ sind nicht definiert.
20. **Barrierefreiheit.** `LH-6.19-NFR-011`, `LH-6.18-SEC-021`: Prüfplan, „vereinbarte Prüfkriterien“, auditierende Stelle und das Verfahren für „genehmigte Abweichungen“ fehlen; ob der Audit die Driver-App einschließt, ist offen.
21. **Schlüsselaktionen der Leitstelle.** `LH-6.19-NFR-014` zählt weder die Schlüsselaktionen noch die irreversiblen Eingriffe auf.
22. **Übergangsfristen.** `LH-6.19-NFR-017`: Kap. 05 §5.1 nennt sechs Monate Parallelbetrieb nur als Vorschlag; Partnerfristen sind vertraglich offen. Ob Ereignis-/Webhook-Schemata derselben Regel folgen, ist nicht gesagt.
23. **Rollback-Umfang.** `LH-6.19-NFR-013`: Kap. 01 §1.8 beschreibt DB-Rollback als Vorwärtskorrektur; ob „Rollback“ auch Schemaänderungen zurücknehmen muss, ist nicht festgelegt.
24. **Portabilität.** `LH-6.19-NFR-020`: „alle betriebsrelevanten Stammdaten“ sind nicht aufgezählt; Exportformat und Archivziele fehlen; Abgrenzung zu `export_job` der Berichte (M8c) ist nur planerisch.
25. **Signatur der Protokolle.** `LH-6.20-QAT-007`: Signaturverfahren und Unterzeichner sind nicht bestimmt.
26. **Pilot P2.** `LH-6.20-QAT-008`: Zeitraum, KPI-Ziele und Abbruchkriterien fehlen (D-05); Verhältnis zum P1-Pilot aus M5 ist nicht beschrieben.
27. **P0–P3 und Mangelklassen.** `LH-6.20-QAT-009`: Definitionen und Fristen der Klassen fehlen; das Verhältnis zu den Mangelklassen A/B/C (Kap. 08 §8.4) ist ungeklärt.
28. **Vier-Augen-Umfang.** `LH-6.20-QAT-010` überschneidet sich mit der Vier-Augen-Freigabe der Konfiguration aus M1 und den CFG-Anforderungen in M8d; welche Artefakte als „Tarif-/Regelkonfiguration“ gelten und ob eine Notfalländerung zulässig ist, ist offen.
29. **Wiederherstellungsprüfung.** `LH-6.20-QAT-012`: Umfang der „Wiederherstellungsmittel“ und die Verantwortung für die isolierte Umgebung des Betreibers sind nicht festgelegt (D-05 Hosting).
30. **Gate-Tests.** Keiner der M8-Gate-Tests wird von einer Anforderung dieser Einheit getragen; die 31 Anforderungen haben außer den Regressionen keine eigenen Referenztests in `e2e.csv`. Eigene Abnahmefälle entstehen in WP-M8e-01 und werden nicht in `e2e.csv` eingetragen, solange der Auftraggeber die Liste nicht erweitert.
31. **Bindung des Identitätsanbieters und IdP-Ausfall.** `LH-6.2-IAM-003`: `membership` enthält `issuer`/`subject`, aber weder Kern-DDL noch Kap. 04 §4.4 enthalten eine Tabelle, die einen föderierten Issuer einem Mandanten zuordnet (hier Arbeitsname `identity_provider_binding`). Das Verhalten bei nicht erreichbarem Identitätsanbieter (laufende Sitzungen, Dienststart in UI-09) ist nicht geregelt.
32. **Betroffenenanfragen ohne Konto.** `LH-6.18-SEC-014`: nicht geregelt ist, wie Gäste, hinterlegte Vertrauenspersonen, Erziehungsberechtigte und ehemalige Nutzer ohne Konto eine Anfrage stellen und wie ihre Identität geprüft wird; ebenso offen ist die Zusammenführung bei Konten derselben Person in mehreren Mandanten (je Mandant getrennte Fälle ist die Planungsannahme).
33. **Besteller und beförderte Person im Datenmodell.** `LH-6.2-IAM-008`: `booking` im Kern-DDL hat keine Felder für Buchungsersteller, beförderte Person oder Berechtigungsrevision; Kap. 05 §5.9 beschreibt nur `booking_subject`. Ob M7a (Buchung für Dritte) diese Felder liefert oder WP-M8e-02 sie ergänzt, ist mit M7a abzustimmen.
34. **Ersteller ungleich Prüfer im Schema.** `LH-6.20-QAT-010`: `config_revision` erzwingt nur „publiziert nur mit Prüfer“, nicht „Prüfer ungleich Ersteller“; `tariff_revision` hat keine Feldliste. Der zusätzliche CHECK ist hier als Ergänzung geplant und mit M1/M8d abzustimmen.
35. **Lösch-/Sperrjournal.** Kap. 01 §1.7 verlangt, dass ein Restore das Lösch-/Sperrjournal vor Freigabe wieder einspielt, nennt aber weder Tabelle noch Speicherort noch Aufbewahrungsdauer des Journals (hier Arbeitsname `erasure_journal`); betrifft `LH-6.18-SEC-014`, `LH-6.2-IAM-012` und `LH-6.20-QAT-012`.
36. **Geräte- und Browserbereitstellung.** `LH-6.19-NFR-010`: die Testmittel für die Gerätematrix (eigene Geräte, Emulatoren oder externer Testdienst) und deren Kostenträger sind nicht festgelegt (D-12).
37. **Incident-Klasse und Supportfall.** `LH-6.20-QAT-009`: ob die Klassifikation P0–P3 nur im Incident-Werkzeug des Betriebs oder auch am fachlichen `support_case` (M7b) geführt wird, ist nicht festgelegt; Kap. 04 §4.4 nennt für `support_case` keine Klassifikationsfelder.
38. **Rollout-Begriff.** `LH-6.1-SYS-008`: „Rollout-Flags“ werden hier als stufenweise Freischaltung je Verkehrsprodukt und Mandant verstanden; ein prozentualer Rollout auf Nutzeranteile ist nicht gefordert und nicht geplant (der Parameter `eta_model_rollout_percent` gehört zu M8b).
39. **Nachgelagerte Auflösung der EXTERN-Abhängigkeiten.** Alle EXTERN-Zeilen dieser Datei nennen Meilenstein bzw. Einheit und Fähigkeit, aber noch keine konkrete WP-ID der liefernden Einheit; die Auflösung erfolgt in der einheitenübergreifenden Konsolidierung.
40. **Aufgelöst – Reihenfolge WP-M8e-16 → WP-M8b-24 (Betroffenenrechte).** Baureihenfolge: WP-M8e-16 liefert Fall, Workflow und Endpunkt `/v1/privacy/requests` mit den internen Ports „Auskunftsinhalt liefern“ und „Aufbewahrung prüfen“ (Vertrag in WP-M8e-01, Testimplementierung); WP-M8b-24 liefert danach die fachliche Implementierung beider Ports aus Verarbeitungsregister und `retention_rule`. WP-M8e-16 führt WP-M8b-22 und WP-M8b-24 nur noch unter „Vollabnahme erst mit“; WP-M8b-24 behält die Bauabhängigkeit auf WP-M8e-16 (M8b, Offener Punkt 27). Vgl. Offener Punkt 1.
41. **Klärung Auftraggeber – Suchindex (WP-M8e-15).** Kap. 01 §1.7 nennt den Suchindex als Ziel des orchestrierten Löschjobs, Kap. 01 §1.1 enthält aber keinen Suchbaustein, und keine Anforderung fordert einen Suchindex außerhalb der Datenbank; kein WP der Meilensteine M0 bis M8 liefert ihn (WP-M1-11 schließt ihn aus; in WP-M6b-05 ebenfalls als Klärung geführt, dort OP-34). WP-M8e-15 liefert deshalb nur den Zielport „Suchindex“ in der Orchestrierung und weist ihn mit einem Testdouble nach. Zu klären mit dem Auftraggeber: (a) wird ein eigener Suchindex gebaut – dann sind ein lieferndes WP einschließlich Mandantenisolation (PH-AT-001) und ein Löschadapter zu ergänzen –, oder (b) entfällt das Löschziel, weil die Suche ausschließlich über die Datenbank unter RLS läuft.
