# M6b – Kooperation und Integration

- **Meilenstein:** M6 – Föderation, gemeinsamer Checkout, Clearing
- **Phase:** P2 (17 MUSS, 9 SOLL; Originalphase und -priorität unverändert)
- **Anforderungen:** 26 (MUL 16: `MUL-008` bis `MUL-023`; INT 10: `INT-005` bis `INT-012`, `INT-014`, `INT-015`)
- **Arbeitspakete:** 34 (26 mit Anforderungen, 8 Enabler)

**Ziel:** Nach M6b können zwei Mandanten einen Kooperationsvertrag schließen, daraus eng geschnittene technische Grants ableiten, pausieren und widerrufen; ohne aktiven Grant fließt kein operatives Datum über die Mandantengrenze. Gemeinsame Haltestellen und Gebiete, Rollen je Auftrag, Verbundsicht, Störungsadressierung, Kostenverteilung, Datenexport und Datenschutzbericht arbeiten ausschließlich auf freigegebenen, minimierten Projektionen. Die Integrationsschicht liefert abonnierbare signierte Webhooks, verlustfreie Listenabrufe, kündbare Partnerzugänge, konfigurierbare Fahrplan-, Import- und Exportadapter sowie ein Deprecation-Verfahren.

**Hinweis zur Abgrenzung:** Journey-Planung, Saga, Partneradapter und Capability Registry liefert M6a; Checkout, Settlement und Auslaufregeln der Abrechnung liefert M6c. M6b stellt beiden das Grant-Gate, die Projektionsbausteine und die Integrationsbasis bereit. Wo eine Originalabnahme Funktionen aus M6a/M6c braucht, ist das als EXTERN-Abhängigkeit ausgewiesen.

**Querschnittsregeln für alle WPs dieser Datei (Detailvertrag in WP-M6b-01, Sammelabnahme in WP-M6b-34):** (1) Jede neue mutierende Operation folgt Kap. 05 §5.1: `Idempotency-Key`, ETag mit `If-Match` (fehlend 428, veraltet 412), `application/problem+json` mit stabilem `code` und `correlation_id`, 404 für nicht sichtbare Objekte, 403 für fehlenden Funktionsscope. (2) Jede in einem WP genannte Oberfläche liefert die Leer-, Fehler- und Rechtezustände aus Kap. 06 §6.9, ist per Tastatur und Screenreader bedienbar, zeigt Status nie nur als Farbe und bietet zu jeder Karte eine gleichwertige Liste. (3) Jede fachliche Änderung an Vertrag, Grant, Freigabe, Zugang, Konfiguration, Export und Supportzugriff erzeugt einen `audit_event` mit Akteur, Zeitpunkt, Objekt und `correlation_id` ohne Fremdinhalte. (4) Jede neue Tabelle erhält eine Aufbewahrungs-/Löschklasse (`retention_rule`).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M6b-01 | Detailverträge und Zustandsautomaten M6b | 0 | M | WP-M0-31, WP-M1-03 |
| WP-M6b-02 | Migration der M6b-Tabellen mit RLS und Invarianten | 0 | M | WP-M6b-01, WP-M1-01, WP-M1-16, WP-M1-19, WP-M1-33, WP-M2-29, WP-M2-32, WP-M2-33, WP-M3-02, WP-M4-35, WP-M4-45, WP-M4-46, WP-M5-19 |
| WP-M6b-03 | Referenzfixtures Kooperation (A, B, C, Aufgabenträger) | 0 | S | WP-M6b-02, WP-M0-24, WP-M2-02, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-23, WP-M3-03, WP-M3-05, WP-M3-08 |
| WP-M6b-04 | Kooperationsvertrag: Versionen und Lebenszyklus | 0 | M | WP-M6b-02, WP-M1-05, WP-M1-13, WP-M1-17, WP-M1-46, WP-M6a-05 |
| WP-M6b-05 | Cooperation Grants und Föderations-Policy-Gate | 1 | M | WP-M6b-04, WP-M0-03, WP-M1-06, WP-M1-11, WP-M1-12, WP-M1-46, WP-M3-35 |
| WP-M6b-06 | Pause, Leistungsbegrenzung und Auslaufgrant | 1 | M | WP-M6b-05, WP-M1-18, WP-M1-46, WP-M6a-04, WP-M6a-09, WP-M6a-13, WP-M6a-14, WP-M8d-34 |
| WP-M6b-07 | Widerruf: fachlicher Entzug und Sicherheitswiderruf | 0 | M | WP-M6b-05, WP-M6b-06, WP-M1-10, WP-M1-11, WP-M1-46, WP-M3-52, WP-M3-53, WP-M3-54, WP-M6a-13, WP-M6a-14, WP-M6a-15 |
| WP-M6b-08 | UI-18 Kooperation/Rollen: Vertrag, Grants, Pause, Widerruf | 0 | M | WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M1-43, WP-M6a-05 |
| WP-M6b-09 | Minimierte Segmentprojektion und Partner-Fahrgastalias | 1 | M | WP-M6b-05, WP-M1-28, WP-M1-31, WP-M2-32, WP-M6a-02, WP-M6a-12, WP-M6a-13 |
| WP-M6b-10 | Gemeinsame Haltestellen und gemeinsame Bediengebiete | 1 | M | WP-M6b-05, WP-M2-03, WP-M2-04, WP-M2-05 |
| WP-M6b-11 | Gebietsregeln: Überlappung, Priorität, Tarifgrenze, Übergabepunkt, Ausschlusszone | 1 | M | WP-M6b-10, WP-M2-07, WP-M2-12, WP-M6a-08, WP-M6a-09 |
| WP-M6b-12 | Rollenzuordnung je Auftrag und Fahrtenauswertung | 1 | M | WP-M6b-04, WP-M1-05, WP-M1-34, WP-M2-23, WP-M4-44, WP-M4-45, WP-M6a-13, WP-M6a-14 |
| WP-M6b-13 | Verbund-Dashboard: freigegebene Fahrten und Fahrzeugkapazitäten | 1 | M | WP-M6b-05, WP-M6b-09, WP-M3-03, WP-M3-08, WP-M3-36, WP-M6a-18, WP-M6a-19 |
| WP-M6b-14 | Adressierte Störungsmeldungen an betroffene Partner | 1 | M | WP-M6b-05, WP-M6b-09, WP-M6b-23, WP-M1-11, WP-M1-14, WP-M2-32, WP-M3-35, WP-M3-52, WP-M3-53, WP-M3-54 |
| WP-M6b-15 | Kostenverteilung gemeinsam genutzter Ressourcen | 1 | M | WP-M6b-04, WP-M6b-12, WP-M1-34, WP-M4-33, WP-M4-35, WP-M4-36 |
| WP-M6b-16 | Konfigurationshierarchie mit vollständigem Herkunftsnachweis | 1 | M | WP-M6b-02, WP-M1-19, WP-M1-21 |
| WP-M6b-17 | Validierte, versionierte und zeitlich aktivierte Konfiguration | 1 | M | WP-M6b-16, WP-M1-18, WP-M1-20, WP-M1-22, WP-M1-23, WP-M2-21 |
| WP-M6b-18 | Marke, Sprache, Supportkontakt und Rechtstexte je Dienst | 1 | M | WP-M6b-02, WP-M1-11, WP-M1-24, WP-M2-03, WP-M2-33, WP-M2-35, WP-M2-41, WP-M2-42, WP-M2-43 |
| WP-M6b-19 | Delegierte Rollenvergabe und Break-glass-Supportzugriff | 1 | M | WP-M6b-02, WP-M1-06, WP-M1-07, WP-M1-12, WP-M1-46 |
| WP-M6b-20 | Mandantenexport (Datenportabilität) | 1 | M | WP-M6b-02, WP-M6b-25, WP-M1-11, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-23, WP-M5-19 |
| WP-M6b-21 | Verbundauswertung mit Kleingruppenschutz | 1 | M | WP-M6b-05, WP-M4-45, WP-M4-46, WP-M4-59, WP-M6a-14, WP-M6a-18, WP-M6a-19 |
| WP-M6b-22 | Verarbeitungsverzeichnis und Datenschutzbericht | 1 | M | WP-M6b-05, WP-M6b-07, WP-M6b-19, WP-M6b-20, WP-M6b-23, WP-M6b-26, WP-M6b-31, WP-M1-12, WP-M1-32 |
| WP-M6b-23 | Webhook-Ereigniskatalog und Testevent | 1 | M | WP-M6b-01, WP-M1-14, WP-M1-16, WP-M2-23, WP-M2-26, WP-M2-30, WP-M3-08, WP-M4-14, WP-M4-15 |
| WP-M6b-24 | Webhook-Härtung: Signatur, Rotation, Retry, Dead-Letter | 1 | M | WP-M6b-23, WP-M1-14, WP-M1-16, WP-M1-36 |
| WP-M6b-25 | Pagination, Filterung und Änderungssemantik aller Listen | 1 | M | WP-M6b-01, WP-M1-02 |
| WP-M6b-26 | Partnerzugänge: Maschinenidentität, Scopes, Quoten, Schlüsselentzug | 1 | M | WP-M6b-05, WP-M6b-07, WP-M1-02, WP-M1-04, WP-M1-10, WP-M1-36 |
| WP-M6b-27 | GTFS-/GTFS-RT-Adapter mit Feedkonfiguration | 1 | M | WP-M6b-02, WP-M2-05, WP-M6a-19, WP-M6a-26, WP-M6a-27 |
| WP-M6b-28 | CSV-/GeoJSON-Import mit Validierung und Quarantäne | 1 | M | WP-M6b-02, WP-M1-11, WP-M1-38, WP-M2-04, WP-M2-05, WP-M4-21 |
| WP-M6b-29 | Austauschbare Provider-Adapter (Zahlung, SMS, Push, E-Mail, CRM) | 1 | M | WP-M6b-01, WP-M0-23, WP-M2-29, WP-M2-30, WP-M2-32 |
| WP-M6b-30 | API-Versionierung und Deprecation-Verfahren | 1 | S | WP-M6b-01, WP-M1-03 |
| WP-M6b-31 | Mobilitätsdatenexporte je Empfänger | 1 | M | WP-M6b-02, WP-M6b-25, WP-M2-03, WP-M2-04, WP-M2-23, WP-M5-19 |
| WP-M6b-32 | Trace-Korrelation über die Integrationskette | 1 | S | WP-M6b-19, WP-M6b-24, WP-M6b-29, WP-M1-02, WP-M5-03, WP-M5-06 |
| WP-M6b-33 | Fremdfahrzeug und Fremdbeleg: Einsatzfreigabe unter Kooperation | 0 | M | WP-M6b-05, WP-M6b-15, WP-M1-34, WP-M1-35, WP-M3-08, WP-M3-09, WP-M3-19, WP-M4-17, WP-M4-22, WP-M4-29 |
| WP-M6b-34 | M6b-Gate-Abnahmesuite | 0 | M | WP-M6b-05 bis WP-M6b-33, WP-M6a-07, WP-M6a-13, WP-M6a-14, WP-M6c-18 |

## Arbeitspakete

### WP-M6b-01 – Detailverträge und Zustandsautomaten M6b
- **Ziel:** Vor Codebeginn liegen für alle 26 Anforderungen die fehlenden Verträge vor (Leitlinie „Vertrag vor Code", Kap. 08 §8.4); jede MUSS-Anforderung hat Verantwortlichen, Detailvertrag und mindestens einen positiven ausführbaren Test.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen unter `api/` für die in Kap. 05 §5.7 nur als Funktionszuschnitt genannten Pfade `/v1/webhook-subscriptions`, `/v1/transfer-hubs`, `/v1/stops`, `/v1/service-areas`, `/v1/services`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`, `/v1/analytics/metrics`, `/v1/privacy/requests`, `/v1/partner/capabilities` (nur Scope-/Quotenteil); Vertragsentwurf für die im Katalog fehlende Kooperationsverwaltung (Verträge, Grants, Pause, Widerruf – siehe Offene Punkte OP-01).
  - API (Querschnitt): verbindliche Vorlage für jede neue mutierende Operation dieser Einheit nach Kap. 05 §5.1 – `Idempotency-Key` mit Scope `(auth_principal, tenant_context, operation_name, Idempotency-Key)`, ETag/`If-Match` (428/412), `application/problem+json`, 404/403-Regel, Listenkonvention `{items,next_cursor,as_of}`; je Operation mindestens ein Negativbeispiel.
  - Oberfläche: Zustandskatalog je M6b-Oberfläche (UI-02, UI-03, UI-04, UI-07, UI-15, UI-17, UI-18, UI-19, UI-20) mit Leer-, Fehler-, Rechte- und Veraltet-Zustand nach Kap. 06 §6.9 sowie Textkatalog; Festlegung, welcher Meilenstein die Grundmaske von UI-19 liefert (OP-24).
  - Backend: Zustandsautomat Vertrag `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED` (Kap. 02 §5) mit Übergangsbedingungen (beidseitige Genehmigung, technische Capability-Prüfung, Rechtsfreigabe); Automat Grant (gültig, pausiert, auslaufend, widerrufen); Automat Webhook-Zustellung; Automat Import-/Exportlauf.
  - Backend (Rollen): Rechtematrix der in M6b neuen bzw. erweiterten Rollen und Funktionsscopes (Kooperationsverwaltung, Verbund-Dispatcher, Aufgabenträger-Bericht, Partner-Maschinenidentität, Superadministrator/Support) als Erweiterung der Rechtematrix aus M1 (OP-23).
  - Datenbank: DDL-Entwürfe für alle in WP-M6b-02 genannten Tabellen und Spalten.
  - Datenbank (Datenschutz): Aufbewahrungs-/Löschklasse je neuer Tabelle als Eintrag für `retention_rule` (Fristen selbst hängen an D-11, OP-26).
  - Ereignisse/Outbox: JSON-Schema der Ereignishülle (`event_id`, `owner_tenant_id`, `aggregate_id`, `aggregate_version`, `type`, `schema_version`, `occurred_at`, `recorded_at`, `correlation_id`, freigegebene Empfänger; Kap. 02 §8); Ereignistypkatalog Buchung/Fahrzeug/Schicht/Zahlung; JSON-Schema „minimaler Betreiberpayload" (Kap. 02 §3); JSON-Schema `partner_contract_version.terms`.
  - Konfigurationsparameter: Schemas (Typ, Einheit, Min/Max, Ebene) für `sharing_visibility_level`, `sharing_service_ids`, `sharing_vehicle_quota`, `interoperator_bookings`, `partner_transport_modes`, `area_overlapping_priority`, `interchange_min_time_min`, `cost_split_rule`, `partner_retry_policy`, `kpi_aggregation_min_n` in `policy_schema`.
- **Detailverträge vorab:** dieses WP ist die Vertragsarbeit; Reviewrollen und Vorlage aus M0.
- **Akzeptanzkriterien:**
  1. Alle neuen OpenAPI-Dateien bestehen `validate_artifacts.py` und die Contract-Pipeline; jedes Schema-Beispiel ist gültig.
  2. Für jede der 26 Anforderungen ist in `arbeitspakete.csv` ein Detailvertrag und ein Testfall eingetragen.
  3. Jeder Zustandsautomat nennt alle zulässigen Übergänge, Auslöser, Berechtigung und Fehlercode; nicht genannte Übergänge sind verboten.
  4. Das Grant-Vokabular (Aktionen, Felder, Zwecke) ist abschließend aufgezählt; `offer:read` schließt `hold:create` und `booking:confirm` nachweislich nicht ein (Kap. 02 §2).
  5. Jede neue mutierende Operation der Einheit nennt `Idempotency-Key`, ETag-/`If-Match`-Verhalten, Fehlercodes und die 404/403-Regel; ein OpenAPI-Lint lässt die CI scheitern, wenn eines davon fehlt.
  6. Für jede M6b-Oberfläche liegt der Zustandskatalog (leer, Fehler, Rechte, veraltet) vor; für jede neue Tabelle ist die Aufbewahrungsklasse benannt.
  7. Die Schnittlinien zu M6a und M6c (OP-03, OP-04, OP-16, OP-19, OP-22) sind in den Detailverträgen beider Seiten gleichlautend festgehalten; kein Port, keine Tabelle und kein Endpunkt ist doppelt oder gar nicht zugeordnet.
- **Tests:** positiv: Vertragsvalidierung in CI. negativ: Beispiel mit unbekanntem Grant-Scope bzw. unbekanntem Ereignistyp wird vom Schema abgewiesen; mutierende Operation ohne `Idempotency-Key`-Parameter lässt den Lint scheitern.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31 (Detailvertragsprozess, Vorlage und Reviewrollen)
  - extern: WP-M1-03 (OpenAPI je Modul und Contract-Test-Pipeline)
- **Entscheidungen:** D-01, D-08, D-11 – blockieren den Bau nicht; Vertragsinhalte werden als Sandbox-Fixtures versioniert, Produktivwerte folgen dem Beschluss.
- **Größe:** M
- **Nicht im Umfang:** Verträge für `/v1/federation/*`, `/v1/partner/offers`, `/v1/partner/reservations` (M6a) und `/v1/clearing/statements` (M6c).

### WP-M6b-02 – Migration der M6b-Tabellen mit RLS und Invarianten
- **Ziel:** Alle von M6b benötigten Tabellen und Spalten existieren als Flyway-Migration mit `(tenant_id,id)`, zusammengesetzten Fremdschlüsseln, RLS und Negativtests.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank (Kernschema, Erweiterung): `partner_contract_version` (unverändert unveränderlich, Trigger `reject_mutation`); Vertragsstatus und Genehmigungen in einer getrennten Zustandstabelle (Name im Pflichtenheft nicht festgelegt, OP-02); `cooperation_grant` um Grantrevision, Pausenintervall, Begrenzung auf bekannte Journey-/Segment-IDs (Restabwicklungsgrant) und Widerrufsgrund; `journey_leg` und `booking` um Rollenreferenzen (WP-M6b-12); `service_area` um Priorität und Zonentyp (WP-M6b-11); `stop_revision` um globale opake Haltestellenreferenz (WP-M6b-10); `outbox` um Empfängeradressierung, soweit in M1 nicht vorhanden.
  - Datenbank (Kernschema, weitere Lücken): `membership` um Gültigkeitsende und Delegationsherkunft (Kap. 02 §2 „zeitlich begrenzt"; im Kernschema nur `revoked_at`), soweit in M1 nicht ergänzt; `outbox` um `correlation_id` und `recorded_at` aus der Ereignishülle (Kap. 02 §8), soweit in M1 nicht ergänzt (OP-25).
  - Datenbank (ohne Namen im Pflichtenheft, OP-02): Projektionstabellen im Empfängerkontext (Segment-/Anschlussprojektion WP-M6b-09, Verbundprojektion WP-M6b-13, freigegebene Katalogeinträge WP-M6b-10), Aliaszuordnung und Kontaktrelay (WP-M6b-09), Rollensnapshot je Segment (WP-M6b-12), Partnerzugang (WP-M6b-26), Exportprofil (WP-M6b-31), Gebietsregeln für Tarifgrenze und Ausschlusszone (WP-M6b-11, OP-06).
  - Datenbank (Voraussetzungsprüfung): Die Migration prüft vor dem ersten Schritt, dass die von M6b genutzten Ergänzungstabellen aus Vormeilensteinen existieren (`webhook_subscription`, `policy_schema`, `export_job`, `retention_rule`, `notification`, `notification_attempt`, `channel_template_revision`, `business_alert`, `vehicle_lease`, `vehicle_assignment`, `allocation_activation`, `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `payment_event`), und bricht sonst mit benannter Lücke ab (OP-25).
  - Datenbank (Kap. 04 §4.4): `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation` (OPT/GEO/PTI); `webhook_subscription` (Erweiterung Ereignistypen, Schlüsselversionen), `export_job`, `adapter_capability` nur lesend/als Fremdbezug (CFG/SEC/INT); Zustelltabelle für Webhook-Versuche und Dead-Letter, Importlauf-/Importfehlertabelle, Dienstauftritt, Supportzugriff, Verarbeitungsverzeichnis (Namen nicht festgelegt, OP-02).
  - Datenbank (Invarianten Kap. 04 §4.5): „Keine lokale Cross-Tenant-Referenz", „Kein Mandantenkontext → keine Zeile", „Gegenereignis statt Änderung" (Contract), „Grantwiderruf sofort wirksam" (Felder), „Nicht überlappende aktive Tarif-/Policy-/Dienstversion" (Exclusion Constraint auf `config_revision` je Scope).
  - Migration: expand/contract, Testdaten beider Mandanten, Backfill der neuen Pflichtspalten fortsetzbar (Kap. 04 §4.7).
- **Detailverträge vorab:** DDL aus WP-M6b-01; Entscheidung, ob `transfer_hub`/`timetable_feed` in M6b oder M6a migriert werden (OP-03).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und aus dem M5-Stand fehlerfrei; Runtime-Rolle ist weder Owner noch BYPASSRLS.
  2. Jede neue Tabelle mit `tenant_id` hat `ENABLE` und `FORCE ROW LEVEL SECURITY` mit `USING` und `WITH CHECK`.
  3. Ein `UPDATE` auf `partner_contract_version` schlägt fehl; ein Statuswechsel ändert nur die Zustandstabelle.
  4. Zwei zeitlich überlappende publizierte `config_revision` desselben Scopes werden von der Datenbank abgewiesen.
  5. Fehlt eine vorausgesetzte Tabelle aus einem Vormeilenstein, bricht die Migration vor jeder Änderung mit dem Tabellennamen ab; es entsteht kein Teilstand.
  6. Eine Änderung der `tenant_id` einer bestehenden Zeile wird in jeder neuen Tabelle verweigert; keine neue Tabelle wird über eine `SECURITY DEFINER`-Funktion oder eine „alle Kooperationspartner"-Policy gelesen (Kap. 04 §4.6).
  7. Projektionstabellen enthalten keinen Fremdschlüssel in den Eigentümermandanten, nur opake Referenzen; jede neue Tabelle hat eine Aufbewahrungsklasse.
  8. Die Contract-Stufe (Entfernen alter Spalten) läuft erst nach validiertem Backfill; ein abgebrochener Backfill ist fortsetzbar.
- **Tests:** positiv: Migration frisch und aus Vorstand. negativ: Zugriff ohne Tenantkontext liefert keine Zeile; Fremdschlüssel auf Zeile eines anderen Mandanten scheitert. konkurrierend (Datentrennung): zwei parallele Transaktionen publizieren überlappende Revisionen – genau eine gewinnt; Pool-Wiederverwendung überträgt keinen Tenantkontext; Invariante „Grantwiderruf sofort wirksam" mit zwei parallelen Transaktionen (Widerruf gegen Lesen/Schreiben über den Grant) – nach dem Commit des Widerrufs sieht keine neue Transaktion den Grant als gültig; Invariante „Gegenereignis statt Änderung" mit parallelem `UPDATE` und `DELETE` auf `partner_contract_version`.
- **Abhängigkeiten:** intern: WP-M6b-01.
  - extern: WP-M1-01, WP-M1-16, WP-M1-19, WP-M1-33 (Tenantkontext, RLS-Laufzeitbasis und Isolations-Testharness; `webhook_subscription`, `policy_schema`, `retention_rule`)
  - extern: WP-M2-29, WP-M2-32, WP-M2-33 (`notification`, `notification_attempt`, `channel_template_revision`, `payment_event` als vorhandene Tabellen)
  - extern: WP-M3-02 (`business_alert`, `vehicle_lease`, `vehicle_assignment` als vorhandene Tabellen)
  - extern: WP-M4-35, WP-M4-45, WP-M4-46, WP-M5-19 (`allocation_activation`, `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `export_job` als vorhandene Tabellen)
- **Entscheidungen:** D-02 – blockiert den Bau nicht (RLS ist Planungsdefault).
- **Größe:** M
- **Nicht im Umfang:** Tabellen `settlement_statement`, `settlement_reconciliation`, `payout_instruction`, `transfer_reversal` (M6c); Saga-Tabellen `operation`, `saga_step` (M1/M6a).

### WP-M6b-03 – Referenzfixtures Kooperation (A, B, C, Aufgabenträger)
- **Ziel:** Ein deterministischer Datenbestand für alle M6b-Tests steht bereit: Betreiber A und B mit Vertrag für Dienst S und gemeinsamem Hub, unbeteiligter Betreiber C, Aufgabenträger mit Berichtsfreigabe, Fahrzeugeigentümer X.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Seed-Skripte unter `tests/acceptance` für Mandanten A/B/C, Aufgabenträger T, Rechtsträger X/Y; je ein Fahrgast, Fahrzeug, Beleg; gemeinsamer Übergabehub; Vertrag A↔B mit Auslaufregel (Fixture aus PH-AT-003); Kostenbeispiel B aus Kap. 03 §6 (X 400, Y 1.680, K1 1.008,00, K2 672,00).
  - Adapter/Worker: Webhook-Testempfänger mit steuerbaren Fehlern (5xx, 429 mit `Retry-After`, Timeout, verfälschter Body); GTFS-/GTFS-RT-Testfeed mit Ausfall und Verspätung; fehlerhafte CSV-/GeoJSON-Testdateien.
- **Detailverträge vorab:** Fixture-Beschreibung mit Seed, Konfigurations-, Tarif- und Vertragsrevision (Kap. 08 §8.4 Evidenzfelder).
- **Akzeptanzkriterien:**
  1. Der Seed ist wiederholbar und erzeugt identische IDs.
  2. Fixtures enthalten ausschließlich synthetische Personen- und Belegdaten.
  3. Jeder steuerbare Fehler des Testempfängers ist einzeln auslösbar und protokolliert.
- **Tests:** positiv: Seed zweimal, Bestand identisch. negativ: Seed unter Runtime-Rolle ohne Tenantkontext schreibt nichts.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M0-24 (deterministische Sandbox-Partner mit steuerbaren Fehlern)
  - extern: WP-M2-02, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-23 (Dienste, Bediengebiete, Haltepunkte und Buchungen als Seed-Objekte)
  - extern: WP-M3-03, WP-M3-05, WP-M3-08 (Fahrzeug-, Fahrerstamm und Einsatzfreigabe als Seed-Objekte)
- **Entscheidungen:** D-05 (Pilotbetreiber, Region) – blockiert nur produktionsnahe Fixtures.
- **Größe:** S
- **Nicht im Umfang:** Saga- und PSP-Fehlerszenarien (M6a, M6c).

### WP-M6b-04 – Kooperationsvertrag: Versionen und Lebenszyklus
- **Ziel:** Zwei Mandanten können einen Kooperationsvertrag als unveränderliche Version anlegen, prüfen, beidseitig genehmigen und aktivieren; jede Buchung kann ihren Vertragsstand referenzieren.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`, Aggregat `PartnerContractVersion` (Parteien, Leistungen, Gebiet, Laufzeit, Datenzwecke, Haftungs-/Abrechnungsregeln; Kap. 02 §2); Zustandsübergänge `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED`; `ACTIVE` nur mit beidseitig genehmigter Fassung, technischer Capability-Prüfung und Rechtsfreigabe; Daten-, Auskunfts-, Buchungs-, Zahlungs- und Clearingrechte getrennt aktivierbar; Zeitfenster halboffen `[valid_from, valid_until)` gegen Serverzeit.
  - Datenbank: `partner_contract_version` (`partner_ref`, `version`, `terms_hash`, `terms`, `valid_from`, `valid_to`), Zustandstabelle aus WP-M6b-02; Spiegelversion beim Vertragspartner als eigene Zeile in dessen Mandant mit gleichem `terms_hash` (kein Cross-Tenant-FK).
  - API: Kooperationsverwaltung laut Detailvertrag (OP-01); ETag und `If-Match` für Statuswechsel; `Idempotency-Key` für Anlage, Genehmigung und Aktivierung (gleicher Schlüssel mit anderem Inhalt `409 IDEMPOTENCY_CONFLICT`).
  - Backend (Capability-Prüfung): Port „technische Capability-Prüfung bestanden" gegen die Capability Registry aus M6a; bis zu deren Lieferung deterministischer Sandbox-Stub, damit der Lebenszyklus testbar bleibt.
  - Ereignisse/Outbox: Vertragsstatusänderung als adressiertes Ereignis an die Vertragspartei.
  - Konfigurationsparameter: `partner_settlement_model`, `partner_liability_policy` werden als Vertragsbestandteil gespeichert (fachliche Auswertung M6c).
- **Detailverträge vorab:** Zustandsautomat Vertrag; JSON-Schema `terms`; Regel für `terms_hash` (Kanonisierung); Genehmigungsrollen.
- **Akzeptanzkriterien:**
  1. Ein Vertrag ohne Genehmigung beider Parteien, ohne Capability-Prüfung oder ohne Rechtsfreigabe erreicht `ACTIVE` nicht.
  2. Eine Änderung der Bedingungen erzeugt eine neue Version; die alte bleibt unverändert lesbar.
  3. Beide Parteien sehen denselben `terms_hash`; eine abweichende Fassung wird als Konflikt ausgewiesen.
  4. Ab `valid_until` gilt der Vertrag als `EXPIRED`; der Zeitpunkt `valid_until` selbst liegt außerhalb der Gültigkeit.
  5. Der Vertragsstand einer Buchung bleibt nach Ablauf oder Widerruf nachvollziehbar, verleiht aber kein Zugriffsrecht.
  6. Daten-, Auskunfts-, Buchungs-, Zahlungs- und Clearingrechte sind einzeln aktivierbar; ein aktiver Vertrag mit nur aktiviertem Auskunftsrecht erlaubt keine Buchung (Kap. 02 §5).
  7. Anlage, Genehmigung, Aktivierung und jeder Statuswechsel sind mit Akteur, Zeitpunkt und Vertragsversion auditiert; das Audit enthält keine Vertragsinterna des Partners.
  8. Die wiederholte Genehmigung mit demselben `Idempotency-Key` erzeugt genau einen Statuswechsel und genau ein Ereignis an die Vertragspartei.
- **Tests:** positiv: Lebenszyklus bis `ACTIVE`. negativ: einseitige Genehmigung; Statuswechsel mit veraltetem ETag (412) und ohne `If-Match` (428); gleicher `Idempotency-Key` mit anderem Inhalt (409); Aktivierung ohne Capability-Nachweis; Mandant C liest Vertrag A↔B (404); Prüfung exakt zum Zeitpunkt `valid_until` (außerhalb). konkurrierend (Sicherheit): parallele Genehmigung und Rücknahme – genau ein konsistenter Endzustand.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M1-05, WP-M1-13, WP-M1-17, WP-M1-46 (Mandant, Rechtsträger, Organisation mit `federation_ref`; Zustandsautomaten-Rahmen; Audit; Idempotenzregister)
  - extern: WP-M6a-05 (Capability Registry als Quelle der technischen Capability-Prüfung (bis dahin Stub))
- **Entscheidungen:** D-01, D-04 – blockieren nur die Produktivschaltung (reale Vertragsinhalte); Bau mit Testverträgen.
- **Größe:** M
- **Nicht im Umfang:** Grants (WP-M6b-05); Pause und Ablauf mit Auslauf (WP-M6b-06); Widerruf (WP-M6b-07); Abrechnungsregeln und Clearing (M6c).

### WP-M6b-05 – Cooperation Grants und Föderations-Policy-Gate
- **Ziel:** Aus einem aktiven Vertrag entstehen technische Grants; jede mandantenübergreifende Lese- oder Schreiboperation läuft durch ein Gate, das ohne gültigen Grant ablehnt.
- **Anforderungen:** `LH-I.4-MUL-009`
- **Liefergegenstände:**
  - Backend: Aggregat `CooperationGrant` (Subject, Empfänger, Aktionen, Dienste, Felder, Zweck, Gültigkeit); Föderations-Gateway-Port `authorize(grantee, action, service, fields, purpose, at)` mit deny-by-default; Schnittmengenregel „alle Mengen schneiden, niemals vereinigen" (Kap. 04 §4.3); Online-Prüfung des Grantstatus bei jeder geschäftswirksamen Partneroperation; Prüfung der Grantrevision unmittelbar vor dem lokalen Commit (Kap. 02 §5); Cache-Schlüssel mit Policyrevision.
  - Datenbank: `cooperation_grant` (`contract_version_id`, `grantee_ref`, `purpose`, `actions`, `service_refs`, `allowed_fields`, `valid_from`, `valid_to`, `revoked_at`); Invariante „Grantwiderruf sofort wirksam".
  - API: Grantverwaltung laut Detailvertrag (OP-01); Fehlercode `403 COOPERATION_NOT_ALLOWED`; nicht sichtbare Objekte `404 NOT_FOUND`.
  - Ereignisse/Outbox: Empfängeradressierung je Ereignis; ohne Grant kein Empfänger.
  - Konfigurationsparameter: `sharing_visibility_level`, `sharing_service_ids`, `interoperator_bookings`, `partner_transport_modes`.
- **Detailverträge vorab:** Grant-Vokabular; Schnittstelle des Gate-Ports; Zuordnung Vertragsrecht → Grant.
- **Akzeptanzkriterien:**
  1. Ohne aktiven Kooperationsvertrag liefert jede mandantenübergreifende Abfrage, Buchungsoperation, Ereigniszustellung, Cache-Antwort, Suchindex-Abfrage, Websocket-/SSE-Nachricht, Queue-Nachricht, jeder Objektspeicher- und jeder Report-Download keine fremden Daten und keinen Schreibeffekt (Kap. 02 §4.3). (MUL-009)
  2. Mit aktivem Vertrag und Grant `offer:read` ist die Auskunft möglich, `hold:create` und `booking:confirm` werden abgewiesen. (MUL-009)
  3. Ein Grant kann nicht weiter reichen als sein Vertrag (Dienste, Felder, Zeitraum); der Versuch wird beim Anlegen abgewiesen.
  4. Nicht zum Vertrag gehörende Dienste bleiben unbuchbar (PH-AT-002).
  5. Jede Ablehnung erzeugt ein Sicherheitsereignis ohne fremde Inhalte.
  6. Mandant C ohne Vertrag erhält zu keinem Ereignis von A oder B eine Zustellung.
  7. Erteilen, Ändern und Entziehen eines Grants sind mit Akteur, Zeitpunkt, Vertragsversion und Umfang auditiert; die technische Policy (Aktionen, Dienste, Felder, Zweck, Gültigkeit) ist je Grant maschinenlesbar einsehbar. (MUL-009)
  8. Das Gate wird von jedem mandantenübergreifenden Pfad durchlaufen: ein Architekturtest lässt die CI scheitern, wenn ein Modul Partnerdaten ohne Aufruf des Gate-Ports liest oder schreibt.
  9. Fällt die Grantprüfung technisch aus (Datenbank oder Cache nicht erreichbar), wird abgelehnt (fail closed), nicht aus einem alten Cache-Eintrag erlaubt.
- **Tests:** positiv: freigegebene Projektion lesbar. negativ: kein Vertrag, Vertrag `DRAFT`, Grant abgelaufen, falscher Dienst, falsches Feld, falscher Zweck, `tenant_id` in Body, Pfad und Header manipuliert; Grantprüfung nicht erreichbar; Grant weiter als Vertrag. konkurrierend (Sicherheit/Datentrennung): Grant wird zwischen Prüfung und Commit entzogen – Commit wird gestoppt; parallele Anfragen mit altem Cache-Eintrag nach Revisionswechsel. Ermöglicht PH-AT-002, E2E-085; semantische Contract-Fälle „Manipulation tenant_id" und „unzulässige Fremd-ID" (Kap. 05 §5.8).
- **Abhängigkeiten:** intern: WP-M6b-04.
  - extern: WP-M0-03, WP-M1-06, WP-M1-11, WP-M1-12, WP-M1-46, WP-M3-35 (RequestContext mit Zweck und Vertragsrevision; Isolation außerhalb der Datenbank (Cache, Suchindex, Websocket-/SSE-Kanäle, Queue, Objektspeicher, Ereignisse, Exporte); Audit und Sicherheitsereignisse; Architekturtest-Rahmen)
  - extern: UNGEDECKT – Isolation des Suchindex außerhalb der Datenbank (kein WP bis M6 liefert einen Suchindex bzw. dessen Mandantenisolation; WP-M1-11 schließt ihn aus)
- **Entscheidungen:** D-01, D-02 – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Pause/Ablauf (WP-M6b-06); Widerruf (WP-M6b-07); Maschinenzugänge der Partner (WP-M6b-26); Aufruf des Gates in Planung und Saga (M6a).

### WP-M6b-06 – Pause, Leistungsbegrenzung und Auslaufgrant
- **Ziel:** Ein Anbieter kann seine Teilnahme zeitgesteuert pausieren oder auf Leistungen begrenzen; neue Anfragen werden nicht mehr bedient, bestehende Zusagen werden über einen engen Restabwicklungsgrant abgewickelt.
- **Anforderungen:** `LH-I.4-MUL-014`
- **Liefergegenstände:**
  - Backend: geplante Pause mit Beginn/Ende (halboffen, Serverzeit); Begrenzung auf Dienste/Leistungen; Vertragsablauf `EXPIRED`; automatische Ableitung eines scopebegrenzten Restabwicklungsgrants, der nur bekannte Journey-/Segment-IDs, Status, Storno und notwendige Kommunikation umfasst (Kap. 02 §5); Regel „Ein Pausenflag darf nicht still bestätigte Fahrten löschen".
  - Datenbank: `cooperation_grant` (Pausenintervall, ID-Begrenzung), Zustandstabelle Vertrag `PAUSED`.
  - API: Pause anlegen/ändern/aufheben laut Detailvertrag (OP-01); Anbieterfilter-Port für die Planung (Vertrag aktiv, Dienst-/Gebietsrecht, Pause).
  - Oberfläche: Datenteil für UI-18 (Zustände „Vertragsablauf", „offene Reisen").
  - Ereignisse/Outbox: Pausenbeginn/-ende an Vertragspartei; Hinweis an verantwortliche Leitstelle bei betroffenen Reisen.
  - Konfigurationsparameter: `sharing_service_ids`, `sharing_vehicle_quota`.
- **Detailverträge vorab:** Tabelle „Ereignis → Neue Auskunft/Buchung → Bestehende Reisen → Daten-/Finanzfolge" aus Kap. 02 §5 als ausführbare Regel; Umfang des Restabwicklungsgrants.
- **Akzeptanzkriterien:**
  1. Ein Partner mit aktiver Pause wird bei künftigen Fahrtanfragen nicht angeboten und erhält keine neuen Holds. (MUL-014)
  2. Vor Pausenbeginn zugesagte Segmente bleiben bestätigt und werden abgewickelt (Status, Storno, notwendige Kommunikation). (MUL-014)
  3. Eine Begrenzung auf Dienst S1 lässt S1 buchbar und sperrt S2. (MUL-014)
  4. Eine für die Zukunft geplante Pause wirkt exakt ab ihrem Beginn und endet exakt an ihrem Ende, ohne manuellen Eingriff.
  5. Der Restabwicklungsgrant erlaubt keinen Zugriff auf andere Reisen oder nicht freigegebene Felder.
  6. Nach Vertragsablauf ist keine Neuanlage möglich; der Auslaufprozess bleibt gemäß eingefrorener Vertragsrevision möglich (Kap. 02 §10 „Vertragsablauf bei bestehender Zusage").
  7. Pause, Begrenzung, deren Änderung und Aufhebung sind mit Akteur, Zeitpunkt und Begründung auditiert; die Vertragspartei erhält genau ein Ereignis je Änderung.
  8. Eine Pause löscht oder storniert keine bestätigte Fahrt; betroffene offene Reisen werden gezählt und der verantwortlichen Leitstelle angezeigt.
- **Tests:** positiv: Pause mit bestehender Zusage J1. negativ: neue Anfrage J2 während Pause; Zugriff über Restabwicklungsgrant auf fremde Reise. Pausenbeginn in einer DST-Lücke bzw. doppelten Ortszeit (abgewiesen bzw. eindeutiger Offset verlangt); Pause mit Ende vor Beginn. konkurrierend (Kapazität/Sicherheit): Hold-Anfrage exakt am Pausenbeginn – deterministisches Ergebnis nach Serverzeit; Pause wird während laufendem Hold gesetzt; zwei parallele Pausenänderungen (412 für die zweite). Ermöglicht PH-AT-003 (Teil „J1 bleibt, J2 nicht").
- **Abhängigkeiten:** intern: WP-M6b-05.
  - extern: WP-M1-18, WP-M1-46 (Zeit-/DST-Baustein und zeitgesteuerte Ausführung; Audit)
  - extern: WP-M8d-34 (zeitgesteuerte Ausführung) – Reihenfolgekonflikt, siehe OP-28
  - extern: WP-M6a-04, WP-M6a-09, WP-M6a-13, WP-M6a-14 (Anbieterfilter der Angebotsbildung ruft den Pausen-/Grant-Port; bestätigte Segmente (`journey_leg`) als Bezug des Restabwicklungsgrants)
- **Entscheidungen:** D-04, D-11 – blockieren nur die Produktivschaltung der Auslaufregeln.
- **Größe:** M
- **Nicht im Umfang:** Sicherheitswiderruf (WP-M6b-07); finanzielle Auslaufregeln, Erstattung und Abrechnung nach Vertragsende (M6c, `LH-I.9-COM-011`).

### WP-M6b-07 – Widerruf: fachlicher Entzug und Sicherheitswiderruf
- **Ziel:** Einzelne Dienste oder ein ganzer Zugang können entzogen werden; ein Sicherheitswiderruf wirkt sofort auch gegen alte Tokens und Caches und eröffnet den Klärprozess.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Übergang `REVOKED`; fachlicher Entzug einzelner Dienste (betroffene Reisen markieren, Leitstelle informieren); Sicherheitswiderruf (sofort sperren, Tokens/Keys unwirksam, auch Abwicklungsrechte entziehen, Incident eröffnen); Erhöhung der Grantrevision und Cache-Invalidierung; Sperre entzogener Projektionen für operativen Zugriff; In-flight-Fälle werden abgeglichen, nicht rückwirkend ungeschehen gemacht.
  - Datenbank: `cooperation_grant.revoked_at`, Grantrevision, Widerrufsgrund; `audit_event`.
  - API: Widerruf mit Pflichtbegründung laut Detailvertrag (OP-01).
  - Ereignisse/Outbox: Widerrufsereignis; Alarm an Betrieb (`business_alert`).
- **Detailverträge vorab:** Unterscheidung fachlicher Entzug vs. Sicherheitswiderruf; Schnittstelle zum Incident-/Klärprozess; Verhalten für laufende Schreibvorgänge.
- **Akzeptanzkriterien:**
  1. Nach Widerruf wird ein allgemeiner Zugriff auch mit altem, noch nicht abgelaufenem Token oder Cache-Eintrag nicht wiederhergestellt (PH-AT-003).
  2. Ein Widerruf zwischen Hold und Confirm stoppt den neuen Commit nach Grantprüfung; der In-flight-Fall erscheint als Abgleichfall (Kap. 02 §10).
  3. Der Sicherheitswiderruf entzieht auch Abwicklungsrechte und eröffnet den dokumentierten manuellen Klärprozess.
  4. Ein fachlicher Entzug von Dienst S1 lässt andere aktiv erlaubte Rechte unberührt.
  5. Widerruf ohne Begründung wird abgewiesen; Akteur, Zeitpunkt und Grund stehen im Audit.
  6. Entzogene Projektionen sind für den operativen Zugriff gesperrt; gesetzlich zu archivierende Belege werden nicht gelöscht, nur ihr Downloadpfad ist gesperrt (Kap. 02 §4.3, §5).
  7. Beim fachlichen Entzug sind die betroffenen Reisen markiert und die verantwortliche Leitstelle ist informiert; beim Sicherheitswiderruf existiert ein Incident mit Verweis auf alle In-flight-Fälle.
  8. Der wiederholte Widerruf mit demselben `Idempotency-Key` erzeugt genau eine Grantrevision, ein Ereignis und einen Alarm.
- **Tests:** positiv: fachlicher Entzug mit Restrechten. negativ: Zugriff mit altem Token; Download einer entzogenen Projektion. konkurrierend (Sicherheit): Widerruf parallel zu 50 laufenden Partneroperationen – keine Operation committet nach der neuen Grantrevision. Ermöglicht PH-AT-003.
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-06.
  - extern: WP-M1-10, WP-M1-11, WP-M1-46 (Sitzungs- und Tokensperre, Cache-Invalidierung mit Policyrevision, Sperre signierter Downloads; Audit)
  - extern: WP-M3-52, WP-M3-53, WP-M3-54 (Alarm-Grundlage (`business_alert`) und Störungs-/Incidentablauf der Leitstelle)
  - extern: WP-M6a-13, WP-M6a-14, WP-M6a-15 (Saga prüft Grantrevision vor Commit und führt In-flight-Abgleich)
- **Entscheidungen:** D-11 – blockiert nur die Produktivschaltung (Notfallprozess, Zuständige).
- **Größe:** M
- **Nicht im Umfang:** Rebooking, Erstattung und Reconciliation nach Widerruf (M6a, M6c); Schlüsselentzug für Maschinenzugänge (WP-M6b-26).

### WP-M6b-08 – UI-18 Kooperation/Rollen: Vertrag, Grants, Pause, Widerruf
- **Ziel:** Berechtigte Administratoren verwalten Kooperationen vollständig in UI-18, einschließlich Folgenanzeige vor Pause und Widerruf.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-18 (Kap. 06 §6.8): Partner, Rechtsträgerrollen, erlaubte Dienste/Aktionen/Felder, Vertragsversion, Frist und Nachweise; Capability-Teststatus getrennt vom Vertragsstatus; primäre Aktion „Grant erteilen / entziehen"; Zustände „Vertragsablauf", „offene Reisen", „Scopekonflikt"; Widerruf erklärt Folgen für neue Reisen und bestehende Segmente und verlangt Begründung; kein pauschales Kontrollkästchen „alle Mandantendaten teilen".
  - API: Nutzung der Endpunkte aus WP-M6b-04 bis WP-M6b-07; 412-Konflikt ohne stilles Überschreiben.
- **Detailverträge vorab:** Wireframe und Textkatalog; Zustandsliste je Vertrags-/Grantstatus.
- **Akzeptanzkriterien:**
  1. Alle Pflichtaktionen sind per Tastatur und Screenreader bedienbar; Status nie nur als Farbe.
  2. Vor Pause oder Widerruf zeigt die Oberfläche die Zahl betroffener offener Reisen und die Wirkung auf neue Anfragen.
  3. Capability-Teststatus und Vertragsstatus sind getrennt sichtbar; ein aktiver Vertrag ohne Sandbox-Nachweis zeigt die Fähigkeit als deaktiviert.
  4. Ein Nutzer ohne Recht sieht die Kooperationsverwaltung nicht; ein veralteter Stand führt zur 412-Anzeige mit Neuladen.
  5. Die Zustände „Vertragsablauf", „offene Reisen" und „Scopekonflikt" sowie der Leerzustand (keine Kooperation) sind einzeln erreichbar und textlich erklärt; es gibt kein Bedienelement, das alle Mandantendaten pauschal freigibt.
  6. Die Oberfläche zeigt je Grant Aktionen, Dienste, Felder, Zweck und Gültigkeit so an, wie das Gate sie prüft (gleiche Quelle, kein zweites Rechtemodell).
- **Tests:** positiv: Grant erteilen, Pause planen, Widerruf mit Begründung. negativ: fehlendes Recht; Scopekonflikt; zwei Tabs mit unterschiedlichem Mandanten. UI-Abnahme „Mandantenwechsel mit zwei Tabs" und „412-Konflikt" (Kap. 06 §6.9).
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-06, WP-M6b-07.
  - extern: WP-M1-43 (Web-Designsystem, Admin-Grundgerüst und UI-18 Basis)
  - extern: WP-M6a-05 (Capability-Teststatus aus der Capability Registry (lesend))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Capability-Tests selbst (M6a); UI-08 und UI-16 (M6a, M6c).

### WP-M6b-09 – Minimierte Segmentprojektion und Partner-Fahrgastalias
- **Ziel:** Ein Fahrgastkonto kann Angebote mehrerer Unternehmen nutzen; jeder Partner erhält nur den minimalen Datensatz für sein Segment und einen eigenen Alias.
- **Anforderungen:** `LH-I.4-MUL-021`
- **Liefergegenstände:**
  - Backend: Projektionsbaustein „minimaler Betreiberpayload" (Segmentreferenz; Partner-Fahrgastalias; Start-/Zielhaltestelle; vereinbartes Zeitfenster; Personenzahl und Kapazitätsmerkmale; Boarding-Code; erforderliche Unterstützung ohne Diagnose; Kontaktrelay oder freigegebener Notkontakt; vertraglich nötiger Tarif-/Ticketnachweis; Kap. 02 §3); Feldfilter gegen `cooperation_grant.allowed_fields`; paarweise verschiedene Aliasse je Partner; Kontaktrelay je Reise/Segment mit Ablaufzeit; Anschlussprojektion (`transfer_hub_id`, voraussichtliche Ankunft, Übergabefenster, Umsteigezeit, notwendige Assistenz, Aktualitätsindikator).
  - Datenbank: `passenger_private` (Plattformsubjekt-Token, verschlüsselte Kontakt-/Hilfsdaten) als Quelle; Aliaszuordnung und Kontaktrelay im Vertriebsmandanten sowie Projektionstabelle im Empfängerkontext (Tabellen aus WP-M6b-02, Namen nicht festgelegt, OP-02); Projektion wird beim Eigentümer erzeugt und unter Empfängerkontext gespeichert.
  - Ereignisse/Outbox: Payload je Empfänger aus erlaubten Feldern erzeugt.
- **Detailverträge vorab:** JSON-Schema des Payloads; Aliasverfahren; Ablaufregel des Relays; Negativliste (volle Reisekette, komplettes Fahrgastkonto, Kartentoken, Kreditkartendaten, fremde Rechnungen, personenbezogene Vorgeschichte).
- **Akzeptanzkriterien:**
  1. Partner B erhält für sein Segment ausschließlich die Felder des minimalen Betreiberpayloads; jedes zusätzliche Feld lässt den Contract-Test scheitern. (MUL-021)
  2. Die an A und B ausgegebenen Aliasse desselben Fahrgasts sind verschieden und nicht ineinander überführbar. (MUL-021)
  3. Ein Fahrgastkonto bucht Angebote von A und B, ohne dass A oder B das Gesamtprofil oder die Segmente des jeweils anderen sieht. (MUL-021)
  4. Unterstützungsbedarf wird als transportrelevantes Merkmal ohne Diagnose übertragen und nur, wenn Dienst, Vertrag, Zweck und Rechtsgrundlage es erlauben.
  5. Nach Ablauf des Relays ist keine Kontaktaufnahme mehr möglich.
  6. Dieselbe Minimierung gilt für Cache, Event-Subscription und Report-Download (PH-AT-002).
  7. Kein Payload, Log, Trace oder Ereignis enthält ein Feld der Negativliste (volle Reisekette, Fahrgastkonto, Kartentoken, Kreditkartendaten, fremde Rechnungen, Vorgeschichte); ein automatischer Feldscan belegt das je Empfänger. (MUL-021)
  8. Nach Grantentzug ist die gespeicherte Projektion für den Empfänger gesperrt; der Alias wird nicht für einen anderen Partner wiederverwendet.
- **Tests:** positiv: Segmentpayload für B. negativ: B fragt andere Reise, A-Rohdaten oder Kontaktfeld außerhalb des Grants ab; abgelaufenes Relay. konkurrierend (Datentrennung): parallele Projektionserzeugung für A und B – keine Aliasvertauschung. Ermöglicht PH-AT-002, PH-AT-005.
- **Abhängigkeiten:** intern: WP-M6b-05.
  - extern: WP-M1-28, WP-M1-31 (Feldschutz für Mobilitäts- und Unterstützungsmerkmale; `passenger_private`)
  - extern: WP-M2-32 (Nachrichtenkanäle (SMS, Push, E-Mail) als Träger des Kontaktrelays)
  - extern: WP-M6a-02, WP-M6a-12, WP-M6a-13 (Journey/`journey_leg` als Quelle der Segmentdaten; Übergabe des Payloads im Hold-/Confirm-Aufruf)
- **Entscheidungen:** D-01, D-11 – blockieren nur die Produktivschaltung (Verantwortlichkeit je Datenfluss).
- **Größe:** M
- **Nicht im Umfang:** Transport des Payloads über Partneradapter und Saga (M6a, dort FED-013/026/027; Abgrenzung siehe OP-04); Löschung von Partnerprojektionen (M8).

### WP-M6b-10 – Gemeinsame Haltestellen und gemeinsame Bediengebiete
- **Ziel:** Zwei Betreiber können dieselbe Haltestelle und ein gemeinsames Bediengebiet mit definierten Rechten nutzen, ohne Dienst- oder Fahrgastdaten zu teilen.
- **Anforderungen:** `LH-I.4-MUL-008`
- **Liefergegenstände:**
  - Backend: Modul `service-geo` – Veröffentlichung einer Haltestelle bzw. eines Gebiets in den gemeinsamen Angebotskatalog (Plattformkatalog plus Veröffentlichungen: Gebietsgeometrie, zugelassene Hubs, öffentliche Betriebszeiten; Kap. 02 §3); Rechte je Partner (lesen, als Halt nutzen, als Übergabepunkt nutzen) über Grant; globale opake Haltestellenreferenz; Regel „`tenant_id IS NULL` bedeutet niemals für alle sichtbar" – Katalogobjekte gehören dem expliziten Plattformmandanten.
  - Datenbank: `stop_revision` (je Mandant eigene Revision, gemeinsame Referenz), `service_area`, `transfer_hub`; Projektion der freigegebenen Katalogeinträge im Empfängermandanten.
  - API: `/v1/stops`, `/v1/service-areas`, `/v1/transfer-hubs` (GeoJSON/SRID-Konvention, Datengültigkeit, Wirksamkeitsprüfung).
  - Oberfläche: UI-17 – gemeinsame Haltestellen und Gebiete kennzeichnen, Rechte je Partner anzeigen; Zustände „ungültige Geometrie", „konkurrierende Version".
- **Detailverträge vorab:** OpenAPI der drei Pfade; Verfahren der gemeinsamen Haltestellenidentität (OP-05); Rechtevokabular für Katalogobjekte.
- **Akzeptanzkriterien:**
  1. Betreiber A und B nutzen dieselbe Haltestelle in ihren Diensten; jeder sieht ausschließlich seine eigenen Dienste, Buchungen und Fahrgäste an dieser Haltestelle. (MUL-008)
  2. Ein gemeinsames Bediengebiet ist für beide Partner mit den vereinbarten Rechten nutzbar; ein Partner ohne Recht „als Halt nutzen" kann dort keine Fahrt anbieten. (MUL-008)
  3. Die gemeinsame Haltestellenreferenz verschafft keinen Zugriff auf Buchungs-, Personal- oder Finanzdaten des Partners.
  4. Barrierefreiheitsangaben bleiben je Revision beim jeweiligen Eigentümer; eine gemeinsame ID ersetzt keine Fußweg-, Aufzug- oder Stufenprüfung.
  5. Entzug des Rechts entfernt die Haltestelle aus neuen Angeboten des Partners; bestehende Zusagen bleiben.
  6. Einrichten, Ändern und Entziehen einer gemeinsamen Nutzung sind auditiert; die „definierten Rechte" je Partner sind in UI-17 und über die API einsehbar. (MUL-008)
  7. UI-17 zeigt Leerzustand (keine gemeinsamen Objekte), „ungültige Geometrie", „konkurrierende Version" und fehlendes Recht; die Karte hat eine gleichwertige Liste.
- **Tests:** positiv: gemeinsame Haltestelle in Diensten von A und B. negativ: A listet Buchungen von B an der Haltestelle (leer/404); Mandant C sieht die Freigabe nicht. konkurrierend (Datentrennung): A und B ändern gleichzeitig ihre Revision – keine gegenseitige Überschreibung. Ermöglicht E2E-085.
- **Abhängigkeiten:** intern: WP-M6b-05.
  - extern: WP-M2-03, WP-M2-04, WP-M2-05 (Dienste, Bediengebiete als valide Polygone, Haltepunktrevisionen und deren Endpunkte)
- **Entscheidungen:** D-05 (Pilotregion, Übergabehub) – blockiert nur produktive Stammdaten.
- **Größe:** M
- **Nicht im Umfang:** Detaileigenschaften der Zugänglichkeit und temporäre Haltestellen (M7, GEO); Umstiegsberechnung (M6a).

### WP-M6b-11 – Gebietsregeln: Überlappung, Priorität, Tarifgrenze, Übergabepunkt, Ausschlusszone
- **Ziel:** Bediengebiete tragen prüfbare Regeln; jede Route zwischen überlappenden Gebieten wird gegen alle Regeln bewertet, und Regelverletzungen werden sichtbar abgewiesen.
- **Anforderungen:** `LH-I.4-MUL-012`
- **Liefergegenstände:**
  - Backend: Modul `service-geo` – Gebietsregel-Port `evaluate(origin, destination, time, services)` mit Ergebnis je Regel (zulässig, verletzt, Grund); Überlappung mit eindeutiger Auswahlregel; Ausschlusszonen als harter Filter; Tarifgrenzen als Bewertungsmerkmal für die Preisbildung; Übergabepunkte mit Mindestwechselzeit; „Gebietspolygone allein erlauben keine Fahrt" (Kap. 02 §6); überlappende Angebote bleiben getrennte Alternativen.
  - Datenbank: `service_area` (Priorität, Zonentyp, Gültigkeit), `transfer_hub`, `transfer_rule` (Stoppaare, Gehweg, Puffer); Geo-GiST-Index.
  - API: `/v1/service-areas`, `/v1/transfer-hubs`.
  - Oberfläche: UI-17 – Überlappungen, Prioritäten, Ausschlusszonen und Übergabepunkte auf Karte und in gleichwertiger Liste.
  - Konfigurationsparameter: `area_overlapping_priority`, `interchange_min_time_min`, `transfer_min_time_min`, `advance_arrival_buffer_min`.
- **Detailverträge vorab:** Regelmodell und Auswertungsreihenfolge; Datenmodell für Tarifgrenzen und Ausschlusszonen (OP-06); Auswahlregel bei gleicher Priorität.
- **Akzeptanzkriterien:**
  1. Für eine Route durch zwei überlappende Gebiete liefert der Port eine eindeutige, reproduzierbare Auswahl gemäß `area_overlapping_priority`. (MUL-012)
  2. Eine Route, deren Start, Ziel oder Übergabepunkt in einer Ausschlusszone liegt, wird mit normiertem Grund abgewiesen; keine Bedienregel wird stillschweigend umgangen. (MUL-012)
  3. Eine Route über eine Tarifgrenze weist die Grenze und die betroffenen Tarifversionen im Ergebnis aus. (MUL-012)
  4. Ein Umstieg an einem Übergabepunkt ist nur zulässig, wenn die Mindestwechselzeit eingehalten ist. (MUL-012)
  5. Jede Bewertung speichert die verwendeten Gebiets- und Regelrevisionen.
- **Tests:** positiv: Route A-Gebiet → Hub → B-Gebiet. negativ: Ziel in Ausschlusszone; Hub ohne Freigabe; gleiche Priorität ohne Auswahlregel wird bei Publikation abgewiesen. konkurrierend (Datentrennung): Regelrevision wechselt während Bewertung – Ergebnis gehört konsistent zu genau einer Revision. Ermöglicht E2E-085.
- **Abhängigkeiten:** intern: WP-M6b-10.
  - extern: WP-M2-07, WP-M2-12 (Zulässigkeitsprüfung Gebiet und Bedienzeit; Tarifrevisionen)
  - extern: WP-M6a-08, WP-M6a-09 (Journey Planner ruft den Gebietsregel-Port im Reisegraphen auf)
- **Entscheidungen:** D-05, D-08 – blockieren nur produktive Gebietsdaten.
- **Größe:** M
- **Nicht im Umfang:** Straßen- und Gebietsrestriktionen der Disposition (M7, GEO/OPT); Preisberechnung über Tarifgrenzen (M6c); Ranking (M6a).

### WP-M6b-12 – Rollenzuordnung je Auftrag und Fahrtenauswertung
- **Ziel:** Jeder Auftrag führt Vermittler, Fahrtdurchführer, Fahrzeugsteller und Kostenträger als getrennte, versionierte Zuordnungen; die Fahrtenauswertung zeigt jede Rolle mit ihrer wirtschaftlichen Zuordnung.
- **Anforderungen:** `LH-I.4-MUL-010`
- **Liefergegenstände:**
  - Backend: Rollensnapshot je Segment mit `performing_carrier`, `seller`, `payment_recipient`, `invoice_issuer`, `vehicle_owner`, `cost_bearer`, ggf. `subcontractor` (Kap. 03 §4.1); Rollengleichheit wird nicht angenommen; Snapshot wird bei Bestätigung eingefroren und verweist auf Vertrags- und Organisationsrevision; Eigentümerwechsel am Fahrzeug ändert abgerechnete Runs nicht (Kap. 02 §2).
  - Datenbank: `journey_leg` und `booking` (Rollenreferenzen als opake `federation_ref` der `organization`), `vehicle.owner_org_ref`, `vehicle_lease`, `vehicle_assignment`, `cost_object`.
  - API: Rollenfelder in `GET /v1/federation/journeys/{journey_id}` und `/v1/bookings` (lesend); Auswertung über `/v1/analytics/metrics` bzw. `/v1/reports/jobs`.
  - Oberfläche: UI-15 bzw. Fahrtenauswertung im Operator-Web: Spalten je Rolle und wirtschaftliche Zuordnung (Erlös-, Kosten-, Zahlungsempfängerbezug).
- **Detailverträge vorab:** Werteliste der Rollen und Abbildung der vier Rollen aus MUL-010 auf die sieben aus Kap. 03 §4.1 (OP-07); DDL der Rollenspalten; Regel für Pflichtrollen je Dienst.
- **Akzeptanzkriterien:**
  1. Ein Auftrag, bei dem Vermittler P, Fahrtdurchführer Y, Fahrzeugsteller X und Kostenträger T verschieden sind, zeigt in der Fahrtenauswertung jede Rolle mit korrekter wirtschaftlicher Zuordnung. (MUL-010)
  2. Fehlt eine Pflichtrolle, wird die Bestätigung abgewiesen. (MUL-010)
  3. Eine spätere Änderung von Organisation, Vertrag oder Fahrzeugeigentum ändert die Auswertung bereits bestätigter Aufträge nicht.
  4. Jeder Mandant sieht in der Auswertung nur Rollen und Beträge, für die er berechtigt ist; keine gegenseitige Stammdatenfreigabe (E2E-094).
- **Tests:** positiv: vier verschiedene Rollenträger. negativ: Rollenreferenz auf unbekannte Organisation; Auswertung durch unbeteiligten Mandanten. konkurrierend (Geld/Datentrennung): Eigentümerwechsel parallel zur Bestätigung – Snapshot ist konsistent zu genau einem Stand. Ermöglicht E2E-094, E2E-093 (Rollenbezug), COST-06 (Kap. 03 §7).
- **Abhängigkeiten:** intern: WP-M6b-04.
  - extern: WP-M1-05, WP-M1-34 (Organisation mit vertraglichen Rollen; Fahrzeugrollen mit Gültigkeitsintervallen)
  - extern: WP-M2-23 (`booking` und lokale Bestätigung)
  - extern: WP-M4-44, WP-M4-45 (Analytics-Faktenbasis und Kostenübersicht je Kostenobjekt)
  - extern: WP-M6a-13, WP-M6a-14 (`journey_leg` und Segmentbestätigung in der Saga)
- **Entscheidungen:** D-01 – blockiert nur die Produktivschaltung der Rollenbelegung je Dienst.
- **Größe:** M
- **Nicht im Umfang:** Preisbestandteile je Rolle, `settlement_line` und Provision (M6c); Kostenverteilung (WP-M6b-15).

### WP-M6b-13 – Verbund-Dashboard: freigegebene Fahrten und Fahrzeugkapazitäten
- **Ziel:** Ein organisationsübergreifender Dispatcher sieht ausschließlich die für das Kooperationsgebiet freigegebenen Fahrten und Fahrzeugkapazitäten.
- **Anforderungen:** `LH-I.4-MUL-011`
- **Liefergegenstände:**
  - Backend: Verbundprojektion (freigegebenes Fahrzeugprofil, Kapazitäten, Einsatzzeitraum, bestätigte Einsatzfähigkeit; Segment-/Anschlussprojektion) wird beim Eigentümer erzeugt und im Verbund-/Empfängermandanten gespeichert; keine dynamische „alle Kooperationspartner"-RLS-Policy (Kap. 04 §4.6); Kartenpositionen nur bei ausdrücklichem, zweckgebundenem Grant.
  - Datenbank: Projektionstabelle im Empfängermandanten (Detailvertrag); Quelle `vehicle`, `vehicle_run`, `resource_release`, `journey_leg`.
  - API: `/v1/operator/vehicles` (Filter „Verbund", nur Projektion).
  - Oberfläche: UI-07 Föderationsübersicht (Kap. 06 §6.5): fremder Betreiber nur mit erlaubter Segment-/Anschlussprojektion; Datenstand je Zeile; Drag-and-drop ausschließlich für eigene Ressourcen; „Partnerübernahme anfragen" als Verweis auf den vertrags-/capabilitygeprüften Vorgang.
  - Konfigurationsparameter: `sharing_visibility_level`, `sharing_vehicle_quota`, `sharing_service_ids`.
- **Detailverträge vorab:** Feldliste der Verbundprojektion; Aktualisierungs- und Sperrregel bei Grantentzug; OpenAPI-Filter; Rolle und Mandantenkontext des organisationsübergreifenden Dispatchers (Mitgliedschaft im Verbund-/Empfängermandanten, OP-23).
- **Akzeptanzkriterien:**
  1. Im Verbund-Dashboard erscheinen nur Fahrzeuge, die für das Kooperationsgebiet freigegeben sind; ein nicht freigegebenes Fahrzeug desselben Partners erscheint nicht. (MUL-011)
  2. Sichtbar sind ausschließlich freigegebene Fahrten und Kapazitäten; Einkaufspreis, Schadenhistorie, Personal- und Finanzdaten des Partners sind nicht abrufbar. (MUL-011)
  3. Überschreitet die Freigabe `sharing_vehicle_quota`, wird sie abgewiesen.
  4. Nach Grantentzug verschwindet die Projektion aus dem Dashboard; der Datenstand wird angezeigt.
  5. Der Dispatcher kann fremde Fahrzeuge nicht disponieren.
  6. Jede Zeile zeigt Datenstand und Quelle; eine veraltete Projektion ist als veraltet gekennzeichnet, nicht als aktuell. Leerzustand (keine Freigabe) und fehlendes Recht sind unterscheidbar, ohne die Existenz nicht freigegebener Fahrzeuge zu verraten.
  7. Die Föderationsübersicht ist als Tabelle vollständig per Tastatur und Screenreader nutzbar; die Karte ist keine Voraussetzung.
  8. Der Abruf durch den Verbund-Dispatcher ist mit Zweck auditiert; ein Dispatcher ohne Verbundrolle sieht die Föderationsübersicht nicht. (MUL-011)
- **Tests:** positiv: freigegebenes B-Fahrzeug im Dashboard von A. negativ: Detailabruf eines nicht freigegebenen Fahrzeugs (404); Dispositionsversuch auf fremdem Fahrzeug (403); Positionskarte ohne Grant. konkurrierend (Datentrennung): Grantentzug während Listenabruf – keine Zeile nach neuer Revision. Ermöglicht PH-AT-002, E2E-100 (Vorstufe).
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-09.
  - extern: WP-M3-03, WP-M3-08, WP-M3-36 (Leitstelle Livebetrieb (UI-07), Fahrzeugstamm, Einsatzfreigabe)
  - extern: WP-M6a-18, WP-M6a-19 (Segment- und Anschlussstatus der Reisen)
- **Entscheidungen:** D-01, D-02 – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme als Vorgang (M7, E2E-100); UI-08 Klärarbeitsplatz (M6a).

### WP-M6b-14 – Adressierte Störungsmeldungen an betroffene Partner
- **Ziel:** Eine mandantenübergreifende Störung informiert genau die Partner, deren Segmente oder freigegebene Ressourcen betroffen sind, und niemanden sonst.
- **Anforderungen:** `LH-I.4-MUL-013`
- **Liefergegenstände:**
  - Backend: Betroffenheitsermittlung aus Segment-, Anschluss- und Ressourcenprojektion; Empfängeradressierung je Ereignis („der Eventbus ist keine frei abonnierbare Gesamtdatenquelle", Kap. 02 §4.3); minimierter Störungspayload je Empfänger.
  - Datenbank: `outbox` (Empfängeradressierung), `business_alert`, `notification`, `notification_attempt`.
  - API: Störungen lesend über `/v1/operator/incidents` (nur eigene bzw. adressierte).
  - Ereignisse/Outbox: Störungsereignis mit `owner_tenant_id` und expliziter Empfängerliste.
  - Oberfläche: UI-07 – adressierte Partnerstörung mit Ursache und Datenalter.
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`.
- **Detailverträge vorab:** Betroffenheitsregel; Payloadschema; Verhalten bei pausiertem bzw. widerrufenem Partner.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeugausfall bei Partner A erzeugt eine Meldung an Partner B, dessen Anschlusssegment betroffen ist, und keine Meldung an den unbeteiligten Partner C. (MUL-013)
  2. C erhält die Meldung auf keinem Kanal: weder Ereignis, Webhook, Push, Websocket/SSE noch Listenabruf. (MUL-013)
  3. Die Meldung an B enthält nur Felder der Anschlussprojektion, keine Fahrzeug- oder Personalakte von A.
  4. Wiederholte Zustellung desselben Störungsereignisses erzeugt bei B genau eine Meldung.
  5. Ein pausierter Partner mit bestehender Zusage erhält die Meldung über den Restabwicklungsgrant; ein sicherheitswiderrufener Partner erhält keine Meldung, die Leitstelle stattdessen einen Klärhinweis.
  6. Die Betroffenheitsentscheidung (welcher Partner warum adressiert wurde) ist je Störung nachvollziehbar gespeichert, ohne Inhalte unbeteiligter Partner.
- **Tests:** positiv: Ausfall mit einem betroffenen Partner. negativ: C abonniert Störungsereignisse von A (abgewiesen); B ohne betroffenes Segment erhält nichts. konkurrierend (Datentrennung): zwei gleichzeitige Störungen mit unterschiedlichen Betroffenen – keine Empfängervermischung. Besteht Kap. 02 §10 „C erhält kein Ereignis"; ermöglicht AT-14 (Folgeprozess beim Partner), E2E-101.
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-09, WP-M6b-23.
  - extern: WP-M1-11, WP-M1-14, WP-M3-35 (Outbox-Relay und Inbox-Deduplikation; Websocket-/SSE-Kanalisolation)
  - extern: WP-M2-32 (Betriebsnachrichten (`notification`, `notification_attempt`) als Zustellkanal)
  - extern: WP-M3-52, WP-M3-53, WP-M3-54 (Störungsablauf bei Fahrzeugausfall, Alarm-Grundlage)
- **Entscheidungen:** D-04 – blockiert nur die Produktivschaltung der Folgeprozesse.
- **Größe:** M
- **Nicht im Umfang:** Anschlussrisikoberechnung und Ersatzoptionen (M6a); Fahrgastkommunikation (M2/M7).

### WP-M6b-15 – Kostenverteilung gemeinsam genutzter Ressourcen
- **Ziel:** Kosten einer gemeinsam genutzten Ressource werden anhand einer gespeicherten vertraglichen Verteilungsregel centgenau gebucht, ohne in fremde Kostenstellen zu schreiben.
- **Anforderungen:** `LH-I.4-MUL-020`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` – Verteilungsregel als versionierter Vertragsbestandteil (Schlüssel als exakte Dezimalstrings); Anwendung auf die Kostenbasis einer gemeinsamen Schicht; Largest-Remainder-Rundung; je Rechtsträger eigener vertraglich referenzierter Intercompany-Beleg bzw. Leistungsnachweis statt Schreibzugriff auf fremde Kostenstellen (Kap. 05 §5.5); konsolidierte Ressourcensicht nur mit eigener Freigabe, sonst `NOT_AVAILABLE` (Kap. 03 §6).
  - Datenbank: `economic_event`, `allocation_set` (`driver_snapshot`, `algorithm_version`), `allocation_line`, `cost_object`, `allocation_activation`, `partner_contract_version` (Regelbezug); Invariante „Umlage centgenau und final unveränderlich".
  - API: `/v1/cost-events/{cost_event_id}/allocations` (Regelrevision und Begründung Pflicht; Summe = Basis; geschlossene Periode 409).
  - Oberfläche: UI-15 – Umlageschlüssel und Ursprungsereignisse einsehbar; Zustand „fehlende Partnerrechte".
  - Konfigurationsparameter: `cost_split_rule`.
  - Kennzahlen: `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt` lesen die verteilte Basis.
- **Detailverträge vorab:** JSON-Schema der Verteilungsregel; Zuordnung Regel → Vertragsrevision; Intercompany-Belegmodell (Abstimmung M6c); Testfall Beispiel B.
- **Akzeptanzkriterien:**
  1. Die Fahrzeugkosten einer gemeinsamen Schicht werden anhand der gespeicherten Verteilungsregel gebucht; im Beispiel B (60 h / 40 h) ergibt Y 1.680,00 EUR → K1 1.008,00 EUR und K2 672,00 EUR. (MUL-020)
  2. Jede Verteilung referenziert Leistungsnachweis, Regel- und Vertragsrevision. (MUL-020)
  3. Die Summe aller Positionen entspricht exakt der Kostenbasis; ein Rundungsrest wird deterministisch zugeordnet.
  4. Xs Originalaufwand wird nicht auf Y umgebucht; Y sieht keine Lieferantenpreise oder Rohbelege von X.
  5. Ohne freigegebene Daten von X ist die konsolidierte Sicht `NOT_AVAILABLE`; mit Freigabe ergibt sie 1.280,00 EUR.
  6. Eine Korrektur erfolgt durch Gegenereignis und neue Revision; POSTED-Zeilen bleiben unverändert.
  7. Die Verteilung erzeugt weder eine Verbindlichkeit noch eine Stammdatenfreigabe von X; die vertragliche Leistung X→Y hat einen eigenen Forderungs-/Verbindlichkeitsbezug; das Journal ist je Rechtsträger und Währung ausgeglichen, ein nur mandantenübergreifend ausgeglichener Buchungssatz wird abgewiesen (PH-AT-013, Referenzfall X/Y).
  8. Es ist je Kostenbasis genau eine Umlagerevision aktiv (`allocation_activation`); Regelrevision, Begründung und Akteur sind auditiert.
- **Tests:** positiv: Beispiel B. negativ: Summe ≠ Basis; Ziel-Kostenobjekt eines fremden Mandanten; geschlossene Periode. konkurrierend (Geld): zwei parallele Allokationsrevisionen derselben Basis – genau eine aktive Fassung; paralleles Gegenereignis. Ermöglicht E2E-094, E2E-095 (Kostenteil), COST-06, PH-AT-013 (Referenzfall Eigentümer X / Betreiber Y); semantische Contract-Fälle „EUR-Rundungsrest", „Allokationssumme ≠ Basis".
- **Abhängigkeiten:** intern: WP-M6b-04, WP-M6b-12.
  - extern: WP-M1-34 (Fahrzeugrollen mit Gültigkeitsintervallen)
  - extern: WP-M4-33, WP-M4-35, WP-M4-36 (Kostenereignis, Journal, centgenaue Aufteilung und Fiskalperiode)
- **Entscheidungen:** D-07 – blockiert die Produktivschaltung (Kostenarten, Treiber); D-06 – blockiert nur den Export. Bau mit Testannahmen aus Kap. 03.
- **Größe:** M
- **Nicht im Umfang:** Partnerabrechnung und `settlement_line` (M6c); TCO-Vollausbau und Konsolidierungsberichte (M8).

### WP-M6b-16 – Konfigurationshierarchie mit vollständigem Herkunftsnachweis
- **Ziel:** Für jede wirksame Einstellung ist die gesamte Werthierarchie von globaler Vorlage über Mandant, Gebiet, Dienst bis Fahrzeug sichtbar, einschließlich definierter Überschreibungen und der Kooperationsebenen.
- **Anforderungen:** `LH-I.4-MUL-015`
- **Liefergegenstände:**
  - Backend: Modul `config` – globale Vorlage im Plattformmandanten; Erweiterung des Resolvers um die vollständige Kette je Parameter (jede Ebene mit Wert, Revision, Status „überschrieben/wirksam/nicht zulässig"); Regelwerk „definierte Überschreibungen" je Parameter aus `policy_schema`; zusätzliche Scope-Arten für die Ebenen Kooperation, Partner, Vertrag und Übergabepunkt laut `kataloge.csv`.
  - Datenbank: `config_revision` (`scope_kind`, `scope_id`), `policy_schema`.
  - API: `/v1/config/effective` (Sammelabruf aller Parameter eines Kontexts mit Herkunftskette).
  - Oberfläche: UI-17 Konfigurationseditor – Herkunft der wirksamen Regel je Einstellung, aufklappbare Hierarchie.
  - Konfigurationsparameter: Kooperations-/Partnerparameter `sharing_visibility_level`, `sharing_service_ids`, `sharing_vehicle_quota`, `partner_offer_ttl_sec`, `partner_api_timeout_ms`, `partner_retry_policy`, `interchange_min_time_min`, `interoperator_bookings`, `partner_settlement_model`, `partner_liability_policy`, `partner_transport_modes`, `area_overlapping_priority` mit Scope und Schema.
- **Detailverträge vorab:** Einordnung der Ebenen Kooperation/Partner/Vertrag/Übergabepunkt in die Präzedenzkette (OP-08); Antwortschema der Herkunftskette.
- **Akzeptanzkriterien:**
  1. Für jede effektive Einstellung zeigen API und Oberfläche den Ursprung in der Werthierarchie (Ebene, Scope, Revision) und alle überschriebenen Ebenen. (MUL-015)
  2. Eine Überschreibung auf einer Ebene, die für den Parameter nicht definiert ist, wird abgewiesen. (MUL-015)
  3. Ein Wert aus der globalen Vorlage erscheint als Ursprung, wenn keine tiefere Ebene überschreibt. (MUL-015)
  4. Die zwölf Kooperations-/Partnerparameter sind mit ihrer Ebene auflösbar.
  5. Globale Sicherheitsgrenzen bleiben auf jeder Ebene vorrangig.
- **Tests:** positiv: Kette über fünf Ebenen. negativ: nicht definierter Override; Kontext eines fremden Mandanten. konkurrierend (Datentrennung): Publikation während Sammelabruf – Antwort konsistent zu einem Stand. Ermöglicht E2E-096.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M1-19, WP-M1-21 (Parameterkatalog und `policy_schema`; Präzedenzkette und wirksame Konfiguration)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Berechtigungsmatrix, wer welchen Parameter ändern darf, und Konfigurationspakete (M8, CFG-018/019); fachliche Auswertung der Partnerparameter (M6a, M6c).

### WP-M6b-17 – Validierte, versionierte und zeitlich aktivierte Konfiguration
- **Ziel:** Jede Konfigurationsänderung wird vor Veröffentlichung validiert, als Version geführt und exakt zum geplanten Zeitpunkt in der lokalen Betriebszeit wirksam, ohne laufenden Betrieb zu verändern.
- **Anforderungen:** `LH-I.4-MUL-016`
- **Liefergegenstände:**
  - Backend: Modul `config` – Aktivierung zum `effective_from` ohne Deployment (zeitgesteuerte Cache-Invalidierung, Auflösung nach Leistungszeitpunkt); Umrechnung „nächster Montag" in der IANA-Zeitzone des Dienstes; bestehende Zusageversionen bleiben gebunden; Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion"; Validierung auch für Kooperations- und Gebietsregeln aus WP-M6b-11/-16.
  - Datenbank: `config_revision` (`effective_from`, `effective_to`, `approved_by`, `published_at`).
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/revisions/{revision_id}/publish`.
  - Oberfläche: UI-17 – Wirksamkeitsdatum, Validator, Prüfer, Vorschau auf betroffene künftige Angebote; bestätigte Zusagen als unverändert gebundene Version.
  - Konfigurationsparameter: Nachweis an `min_booking_lead_min` (E2E-096) und `interchange_min_time_min`.
- **Detailverträge vorab:** Regel, welcher Zeitpunkt maßgeblich ist (Anfragezeit oder Leistungszeit) je Parameterklasse (OP-09); DST-Verhalten am Aktivierungszeitpunkt.
- **Akzeptanzkriterien:**
  1. Eine am Freitag publizierte Änderung mit Wirksamkeit nächster Montag 00:00 Ortszeit lässt den laufenden Sonntagsbetrieb unverändert; ab Montag nutzen neue Angebote den neuen Wert. (MUL-016)
  2. Eine ungültige Konfiguration (Schema, Bereich, Sicherheitsgrenze) wird vor Veröffentlichung abgewiesen. (MUL-016)
  3. Jede Veröffentlichung erzeugt eine neue Revision; die vorherige bleibt rekonstruierbar. (MUL-016)
  4. Vor Montag bestätigte Buchungen für Termine nach Montag behalten ihre Zusageversion.
  5. Zwei wirksame Revisionen desselben Scopes mit überlappendem Intervall sind nicht publizierbar.
- **Tests:** positiv: zukünftige Aktivierung. negativ: Publikation ohne Prüfer; Aktivierungszeit in DST-Lücke; `If-Match` veraltet (412), fehlend (428). konkurrierend (Kapazität/Datentrennung): Angebotsanfragen während des Aktivierungszeitpunkts – jede Antwort nutzt genau eine Revision; zwei parallele Publikationen mit Überlappung. Besteht E2E-096 am echten Fahrtangebot; ermöglicht PH-AT-018.
- **Abhängigkeiten:** intern: WP-M6b-16.
  - extern: WP-M1-18, WP-M1-20, WP-M1-22, WP-M1-23 (Konfigurationsrevisionen, Validierung, Vier-Augen-Freigabe, Publikation, Wirkungsanalyse; Zeit-/DST-Baustein)
  - extern: WP-M2-21 (Angebotsbildung mit Revisionsbindung als Verbraucher)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Simulation/Vorschau-Stufe, Rücknahme-Workflow, Feiertagskalender und Notfallmodus (M8, CFG-008/021/022).

### WP-M6b-18 – Marke, Sprache, Supportkontakt und Rechtstexte je Dienst
- **Ziel:** Jeder Mandant verwaltet Markenoberfläche, Sprachen, Supportkontakte und rechtliche Texte getrennt je Dienst; Fahrgäste sehen je Angebot die richtige Marke und die richtigen Kontaktdaten.
- **Anforderungen:** `LH-I.4-MUL-017`
- **Liefergegenstände:**
  - Backend: versionierter Dienstauftritt (Marke, Logo-/Farbreferenz, Sprachen, Supportkontakt, Rechtstexte mit Hash und Gültigkeit) je `service`; Auslieferung als öffentliche Projektion je Angebot; in Mehrbetreiberreisen je Segment der Auftritt des jeweiligen Betreibers; Hash der akzeptierten Bedingungen referenziert die Rechtstextrevision.
  - Datenbank: Dienstauftritt-Tabelle (Name nicht festgelegt, OP-02) mit FK auf `service`; `channel_template_revision` (Sprache, Marke in Nachrichten); Objektspeicher für Markenbilder mit Mandantenpräfix.
  - API: `/v1/services` (Auftritt lesen/pflegen); Auftrittsreferenz in Angebots- und Reiseantworten.
  - Oberfläche: UI-17 (Pflege je Dienst); UI-02, UI-03, UI-04 (Anzeige Marke, Supportkontakt, Bedingungen je Abschnitt; „Anbieter und Rechnungsaussteller: A und B", Kap. 06 §6.3).
- **Detailverträge vorab:** DDL Dienstauftritt; erlaubte Medientypen und Größen; Sprachfallback; Zuordnung Rechtstext → Terms-Hash.
- **Akzeptanzkriterien:**
  1. Für zwei Dienste desselben Mandanten mit unterschiedlichem Auftritt sieht der Fahrgast je Angebot die jeweils korrekte Marke und die korrekten Kontaktdaten. (MUL-017)
  2. In einer Reise mit Segmenten von A und B zeigt jeder Abschnitt Marke, Supportkontakt und Rechtstexte seines Betreibers. (MUL-017)
  3. Sprachen sind je Dienst getrennt aktivierbar; fehlt eine Übersetzung, greift der dokumentierte Fallback sichtbar.
  4. Eine Änderung der Rechtstexte erzeugt eine neue Revision; bestehende Buchungen referenzieren die akzeptierte Fassung.
  5. Mandant B kann den Auftritt von A weder ändern noch dessen nicht veröffentlichte Entwürfe lesen.
  6. Betriebsnachrichten (Push, SMS, E-Mail) zu einer Buchung tragen Marke, Sprache und Supportkontakt des Dienstes, zu dem die Buchung gehört. (MUL-017)
  7. Pflege und Veröffentlichung eines Auftritts sind auditiert; Entwurf und veröffentlichte Revision sind in UI-17 getrennt sichtbar; Markenbilder haben Alternativtexte, und Texte bleiben bei jeder Markenfarbe kontrastkonform lesbar.
- **Tests:** positiv: zwei Dienste, zwei Marken. negativ: Upload mit unzulässigem Medientyp; Zugriff auf Entwurf eines fremden Mandanten; Marken-URL eines anderen Mandanten; Rechtstext ohne Gültigkeitsbeginn. konkurrierend (Datentrennung): zwei parallele Veröffentlichungen desselben Dienstauftritts – genau eine wirksame Revision (412 für die zweite); Angebotsabruf während der Veröffentlichung zeigt konsistent genau eine Revision. Barrierefreiheit: Kontrast und Alternativtexte der Markenbilder.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M1-11, WP-M1-24 (Unternehmensprofil des Mandanten; Objektspeicher-Isolation)
  - extern: WP-M2-03, WP-M2-33, WP-M2-35, WP-M2-41, WP-M2-42, WP-M2-43 (Dienste; Passenger-PWA (UI-02, UI-03, UI-04); Nachrichtenvorlagen)
- **Entscheidungen:** D-01 – blockiert nur die Produktivschaltung (Rechnungsaussteller, Supportverantwortung).
- **Größe:** M
- **Nicht im Umfang:** native Passenger-App (M7); zentral verteilte Unternehmensvorlagen (M9, CFG-023).

### WP-M6b-19 – Delegierte Rollenvergabe und Break-glass-Supportzugriff
- **Ziel:** Superadministratoren können Rollen delegieren, ohne Fachdaten zu sehen; Supportzugriff auf Betriebsdaten ist nur zeitlich begrenzt, begründet, freigegeben und vollständig protokolliert möglich.
- **Anforderungen:** `LH-I.4-MUL-018`
- **Liefergegenstände:**
  - Backend: Modul `identity` – Rolle Plattformadministration verwaltet Konfiguration und Rollen ohne automatische Fachdatensicht; Delegation von Rollenvergabe an Mandantenadministratoren; Break-glass-Zugriff mit Anlass, begrenzter Dauer, verantwortlicher Freigabe und nachträglicher Auditkontrolle (Kap. 02 §4.3); automatischer Ablauf; keine `SECURITY DEFINER`-Allzweckfunktion und keine globalen Supportleserechte (Kap. 04 §4.6).
  - Datenbank: `membership` (delegierte Rolle, Ablauf), Supportzugriff-Tabelle (Name nicht festgelegt, OP-02), `audit_event`.
  - API: Antrag, Freigabe, Beendigung des Supportzugriffs (Detailvertrag); `/v1/me` zeigt aktiven Supportkontext.
  - Oberfläche: UI-20 (Datenschutz/Audit/Support): begründeten Fall bearbeiten; Zustände „fehlender Zweck", „abgelaufener Zugriff"; UI-18 (Rollen delegieren).
  - Ereignisse/Outbox: Benachrichtigung des betroffenen Mandanten über Beginn und Ende des Zugriffs.
- **Detailverträge vorab:** Rollenmatrix Superadministrator; Maximaldauer und Freigaberolle (OP-10); Protokollumfang; Zustandsautomat des Supportzugriffs.
- **Akzeptanzkriterien:**
  1. Ein Superadministrator kann einem Mandantenadministrator Rollenvergabe delegieren; dabei sind keine Buchungen, Fahrgast-, Personal- oder Finanzdaten des Mandanten abrufbar. (MUL-018)
  2. Supportzugriff ohne Begründung, ohne Freigabe oder nach Ablauf der Frist wird serverseitig abgewiesen. (MUL-018)
  3. Jeder Zugriff während des Supportfensters ist mit Akteur, Zweck, Objekt und Zeitpunkt im Audit vollständig protokolliert. (MUL-018)
  4. Der Zugriff endet automatisch zum Fristende, auch bei offener Sitzung.
  5. Antragsteller und Freigebender sind verschiedene Personen.
  6. Der betroffene Mandant sieht Beginn, Anlass, Dauer und Ende jedes Supportzugriffs auf seine Daten (Benachrichtigung und Einsicht in UI-20); die nachträgliche Auditkontrolle ist als abzuschließender Vorgang geführt (Umfang OP-10).
  7. Eine delegierte Rolle kann nicht weiter reichen als die Rolle des Delegierenden und endet mit ihrem Ablauf oder dem Entzug der Delegation; Delegation und Entzug sind auditiert. (MUL-018)
- **Tests:** positiv: begründeter, freigegebener Zugriff für 30 Minuten (Testwert). negativ: Selbstfreigabe; Zugriff auf anderen Mandanten als beantragt; Zugriff nach Ablauf; Rohpayload mit PII. konkurrierend (Sicherheit): Ablauf während laufender Anfrage; parallele Anträge desselben Akteurs. Ermöglicht AT-18, E2E-091 (Supportfall).
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M1-06, WP-M1-07, WP-M1-12, WP-M1-46 (Rollenmodell und Rechtematrix; Mitgliedschaft und RequestContext; Audit append-only; UI-20 Basis)
- **Entscheidungen:** D-11 (zulässiger Supportzugriff) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Notfallmodus für Konfigurationsgrenzen (M8, CFG-022); Export des Audits in getrennte Berechtigungsdomäne (M1/M8).

### WP-M6b-20 – Mandantenexport (Datenportabilität)
- **Ziel:** Ein Mandant kann seinen vollständigen Datenbestand in offenen, dokumentierten Formaten mit konsistenten IDs exportieren.
- **Anforderungen:** `LH-I.4-MUL-019`
- **Liefergegenstände:**
  - Backend: asynchroner Exportlauf mit Snapshot/As-of; Exportpaket je Modul (Stammdaten: Organisation, Dienste, Gebiete, Haltestellen, Fahrzeuge, Konfiguration; Buchungsdaten: Buchungen, Reisen, Segmente); Manifest mit Schema-/Policyversion und Prüfsumme; Eigentümerdaten und geteilte Projektionen getrennt; opake Partnerreferenzen bleiben erhalten (Kap. 04 §4.7); Abruf über kurzlebigen signierten Download nach Objekt-/Zweckprüfung.
  - Datenbank: `export_job` (Lösch-/Exportzweck); Lesen aller mandanteneigenen Tabellen unter Runtime-Rolle mit Tenantkontext.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Jobtyp Mandantenexport; Detailvertrag, OP-11).
  - Oberfläche: UI-20 – Export anfordern, Status, Download.
  - Adapter/Worker: Exportworker mit Fortsetzbarkeit und Wiederholung.
- **Detailverträge vorab:** Formatdokumentation (CSV/JSON/GeoJSON je Objekt, Feldliste, ID-Regeln); Mindestumfang „vollständig" (OP-11); Manifestschema.
- **Akzeptanzkriterien:**
  1. Ein entkoppelter Testmandant exportiert Stammdaten und Buchungsdaten; alle Referenzen zwischen den Exportdateien lösen sich über konsistente IDs auf. (MUL-019)
  2. Formate sind offen und dokumentiert; das Manifest nennt Schema-/Policyversion, Datenstand und Prüfsumme je Datei. (MUL-019)
  3. Der Export enthält keine Zeile eines anderen Mandanten; geteilte Projektionen liegen in einem getrennten Teilpaket.
  4. Ein wiederholter Lauf zum selben As-of liefert identische Prüfsummen.
  5. Der Download ist nur für die berechtigte Rolle und nur befristet abrufbar.
  6. Anforderung, Fertigstellung und jeder Download sind auditiert; das Exportpaket wird nach Ablauf seiner Aufbewahrungsklasse gelöscht (Frist nach D-11, OP-26).
  7. Ein abgebrochener Exportlauf ist fortsetzbar und hinterlässt kein abrufbares Teilpaket.
- **Tests:** positiv: Export Testmandant, Referenzprüfung per Skript. negativ: Export für fremden Mandanten; Download mit abgelaufener URL; Rolle ohne Exportrecht. konkurrierend (Datentrennung): zwei Mandanten exportieren parallel – keine Vermischung; Schreiblast während Export verändert den Snapshot nicht. Ermöglicht PH-AT-001 (Exportteil), AT-61.
- **Abhängigkeiten:** intern: WP-M6b-02, WP-M6b-25.
  - extern: WP-M1-11 (Isolation von Objektspeicher und Exporten; signierte Downloads)
  - extern: WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-23 (Buchungs- und Stammdaten als Exportquelle)
  - extern: WP-M5-19 (Exporte als asynchrone Jobs mit `export_job` und Empfängerrechten)
- **Entscheidungen:** D-11 (Export-/Auskunftsprozess) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Import in einen Zielmandanten bzw. Datenbankwechsel je Mandant (D-02); Finanzarchiv- und ERP-Export (M4/M8); Betroffenenauskunft je Person (`/v1/privacy/requests`, M8).

### WP-M6b-21 – Verbundauswertung mit Kleingruppenschutz
- **Ziel:** Ein Aufgabenträger sieht mandantenübergreifende Verbundkennzahlen für genehmigte Gebiete und Zeiträume, ohne Zugriff auf einzelne fremde Fahrgastprofile.
- **Anforderungen:** `LH-I.4-MUL-022`
- **Liefergegenstände:**
  - Backend: Modul `analytics` – freigegebene Reportingprojektion je Mandant (aggregiert), Zusammenführung im Kontext des Aufgabenträgers; Mindestgruppengröße und Unterdrückung kleiner Zellen; Deduplikation über Journey-/Leg-Referenzen; Grant je Gebiet/Zeitraum; „ein Reportingauftrag hebt nicht die operativen Schutzregeln auf" (Kap. 02 §4.2).
  - Datenbank: `aggregate_snapshot`, `metric_definition_revision`, `dataset_watermark`; Grant in `cooperation_grant` (Zweck Reporting).
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (keine identifizierbaren Föderationskleingruppen).
  - Oberfläche: UI-19 – Verbundansicht mit Datenstand, Vollständigkeit, unterdrückten Zellen.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
  - Kennzahlen: `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`, `Nicht erreichte Anschlüsse`, `Technische Partnerablehnungen`.
- **Detailverträge vorab:** Definition der fünf Verbundkennzahlen (Zähler, Nenner, Ausschlüsse); Unterdrückungsregel inkl. Differenzangriff über Filterkombination; Berichtsgrant.
- **Akzeptanzkriterien:**
  1. Der Aufgabenträger sieht Verbundkennzahlen über die freigegebenen Mandanten und Gebiete und kann keine personenbezogenen Daten fremder Fahrgäste abrufen, weder in Anzeige, Drilldown noch Export. (MUL-022)
  2. Zellen unter `kpi_aggregation_min_n` werden unterdrückt; durch Filterkombination lässt sich kein Einzelwert rekonstruieren. (MUL-022)
  3. Eine Reise mit zwei Segmenten zählt in Journey-Kennzahlen einmal.
  4. Ohne Berichtsgrant eines Mandanten fehlt dessen Anteil und die Kennzahl ist als unvollständig gekennzeichnet.
  5. Null-Nenner ergibt „nicht berechenbar".
  6. Jeder Abruf und Export durch den Aufgabenträger ist mit Zweck, Gebiet und Zeitraum auditiert; die verwendete Kennzahldefinition und ihr Datenstand sind je Wert sichtbar.
- **Tests:** positiv: drei Firmen, zwei Gebiete (Fixture E2E-101). negativ: Drilldown auf Einzelreise; Gebiet außerhalb des Grants; Export mit Kleingruppe. konkurrierend (Datentrennung): Grantentzug während Berichtslauf – Ergebnis ohne den entzogenen Anteil oder Abbruch. Ermöglicht E2E-101, PH-AT-021 (Aggregationsschutz im Verbund).
- **Abhängigkeiten:** intern: WP-M6b-05.
  - extern: WP-M4-45, WP-M4-46, WP-M4-59 (Kennzahlregister mit Definitionsversion, Nenner und Datenstand; Analytics-Faktenbasis; Kennzahlenteil von UI-19 als Grundmaske (Zuständigkeit OP-24))
  - extern: WP-M6a-14, WP-M6a-18, WP-M6a-19 (Segment-, Übergabe- und Partnerannahmeereignisse als Fakten)
- **Entscheidungen:** D-01, D-07 – blockieren nur die Produktivschaltung (wer welche konsolidierten Werte sehen darf).
- **Größe:** M
- **Nicht im Umfang:** vollständiger KPI-Katalog und Berichtsjobs (M8); Konzernübersichten für Untermandanten (M9, MUL-024).

### WP-M6b-22 – Verarbeitungsverzeichnis und Datenschutzbericht
- **Ziel:** Jede Organisation dokumentiert Verarbeitungsrollen, Vereinbarungen und Datenempfängerkategorien; ein Datenschutzbericht zeigt aktive Freigaben, Empfänger und Ablaufdatum.
- **Anforderungen:** `LH-I.4-MUL-023`
- **Liefergegenstände:**
  - Backend: Verzeichnis je Organisation (Verarbeitungsrolle je Datenfluss, zugrunde liegende Vereinbarung/Vertragsversion, Empfängerkategorie, Zweck); Bericht aus Live-Daten: Grants (`grantee_ref`, `purpose`, `allowed_fields`, `valid_to`, `revoked_at`), Webhook-Abonnements, Partnerzugänge, aktive Supportzugriffe, Exportempfänger.
  - Datenbank: Verzeichnistabelle (Name nicht festgelegt, OP-02); Lesen `cooperation_grant`, `partner_contract_version`, `webhook_subscription`, `export_job`.
  - API: Bericht über `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Jobtyp Datenschutzbericht; Detailvertrag).
  - Oberfläche: UI-20 – Verzeichnis pflegen, Bericht anzeigen und exportieren.
- **Detailverträge vorab:** Werteliste Verarbeitungsrollen und Empfängerkategorien (OP-12); Berichtsschema; Abgleich mit Dateninventar.
- **Akzeptanzkriterien:**
  1. Der Datenschutzbericht eines Mandanten listet alle aktiven Freigaben mit Empfänger und Ablaufdatum; eine widerrufene oder abgelaufene Freigabe erscheint nicht als aktiv. (MUL-023)
  2. Je Organisation sind Verarbeitungsrolle, Vereinbarung und Empfängerkategorie erfassbar und versioniert. (MUL-023)
  3. Eine aktive Freigabe ohne dokumentierte Vereinbarung wird im Bericht als Lücke markiert.
  4. Der Bericht enthält nur Daten des eigenen Mandanten und keine Vertragsinterna des Partners.
  5. Der Bericht führt neben Grants auch Webhook-Abonnements, Partnerzugänge, aktive Supportzugriffe und Exportempfänger als Empfänger mit Zweck und Ablaufdatum; fehlt ein Ablaufdatum, ist das ausgewiesen. (MUL-023)
  6. Erstellung und Export des Berichts sind auditiert; der Bericht nennt seine Stichzeit (As-of).
- **Tests:** positiv: Bericht mit zwei Grants und einem Webhook-Abonnement. negativ: Bericht für fremden Mandanten; Grant läuft während Berichtserstellung ab – Stichzeit wird ausgewiesen. konkurrierend (Datentrennung): Widerruf parallel zum Bericht – Ergebnis konsistent zum As-of.
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-07, WP-M6b-19, WP-M6b-20, WP-M6b-23, WP-M6b-26, WP-M6b-31.
  - extern: WP-M1-12, WP-M1-32 (Dateninventar mit Zweck und Rechtsgrundlage; UI-20 Basis)
- **Entscheidungen:** D-01, D-11 – blockieren nur die Produktivschaltung (Verantwortliche je Datenfluss).
- **Größe:** M
- **Nicht im Umfang:** Datenschutz-Folgenabschätzung (M1); Lösch- und Auskunftsjobs (M8).

### WP-M6b-23 – Webhook-Ereigniskatalog und Testevent
- **Ziel:** Registrierte Empfänger erhalten Webhooks zu Buchungs-, Fahrzeug-, Schicht- und Zahlungsereignissen; ein dokumentierter Testevent ist jederzeit auslösbar.
- **Anforderungen:** `LH-6.17-INT-005`
- **Liefergegenstände:**
  - Backend: Ereignistypkatalog mit Schema und `schema_version` für die vier Klassen (Buchung: bestätigt, geändert, storniert, abgeschlossen; Fahrzeug: gesperrt, freigegeben, Status; Schicht: veröffentlicht, geändert; Zahlung: autorisiert, eingezogen, erstattet, fehlgeschlagen – endgültige Liste im Detailvertrag); Abonnement je Ereignistyp; Empfängerbindung und minimierter Payload; Testevent-Auslösung.
  - Datenbank: `webhook_subscription` (Ereignistypen, Ziel, Secretreferenz), `outbox`.
  - API: `/v1/webhook-subscriptions` (Anlegen, Lesen, Ändern, Deaktivieren, Testevent senden); Ziel-URL-Prüfung gegen SSRF.
  - Ereignisse/Outbox: Zuordnung fachlicher Outbox-Ereignisse der Module `booking-dispatch`, `fleet-safety`, `staff-time`, `fare-payment` zu öffentlichen Webhook-Typen.
  - Oberfläche: UI-18 bzw. Admin-Bereich – Abonnements, letzter Zustellstatus, Testevent-Schaltfläche.
- **Detailverträge vorab:** veröffentlichter Ereigniskatalog mit JSON-Schema und Beispielen; welche Felder je Typ enthalten sind (keine Diagnosen, Kartendaten, Personalprofile; Kap. 05 §5.1); Dokumentation des Testevents.
- **Akzeptanzkriterien:**
  1. Ein registrierter Empfänger erhält nach Auslösung den dokumentierten Testevent mit gültiger Signatur und dem im Katalog beschriebenen Schema. (INT-005)
  2. Je ein reales Buchungs-, Fahrzeug-, Schicht- und Zahlungsereignis wird an einen dafür abonnierten Empfänger zugestellt. (INT-005)
  3. Ein Empfänger erhält nur abonnierte Typen und nur Ereignisse seines Mandanten.
  4. Payloads enthalten ausschließlich die im Katalog freigegebenen Felder.
  5. Ein Abonnement für einen unbekannten Typ wird mit 422 abgewiesen.
  6. Anlegen, Ändern, Deaktivieren und Testevent sind rollengebunden und auditiert; ein Testevent ist als solcher gekennzeichnet und löst beim Empfänger keine Fachwirkung aus.
  7. Jeder Webhook trägt die Ereignishülle aus Kap. 02 §8 einschließlich `event_id`, `aggregate_version` und `schema_version`, sodass der Empfänger deduplizieren und ordnen kann.
  8. Ein deaktiviertes Abonnement erhält ab dem Deaktivierungszeitpunkt keine Zustellung mehr, auch keine wartenden Wiederholungen.
- **Tests:** positiv: Testevent; je Klasse ein Ereignis. negativ: Abonnement auf fremden Mandanten; Ziel in privatem Netz; nicht abonnierter Typ. konkurrierend (Datentrennung): Ereignisse zweier Mandanten parallel – keine Fehlzustellung. Ermöglicht AT-24.
- **Abhängigkeiten:** intern: WP-M6b-01.
  - extern: WP-M1-14, WP-M1-16 (Webhook-Grundlage (Abonnement, Zustellworker), Outbox-Relay)
  - extern: WP-M2-23, WP-M2-26, WP-M2-30 (Buchungs- und Zahlungsereignisse in der Outbox)
  - extern: WP-M3-08 (Fahrzeugereignisse (Sperre, Freigabe) in der Outbox)
  - extern: WP-M4-14, WP-M4-15 (Schichtereignisse (Veröffentlichung, Änderung) in der Outbox)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Signatur-/Retry-Härtung (WP-M6b-24); eingehende Partnerereignisse `/v1/federation/partners/{partner_id}/events` (M6a); PSP-Eingang `/v1/payments/provider-events/{provider}` (M2).

### WP-M6b-24 – Webhook-Härtung: Signatur, Rotation, Retry, Dead-Letter
- **Ziel:** Ausgehende und eingehende Webhooks sind signiert und replaygeschützt; vorübergehende Fehler führen zu erneuter Zustellung, erschöpfte Versuche zu Dead-Letter mit Warnung und Nachbearbeitung.
- **Anforderungen:** `LH-6.17-INT-006`
- **Liefergegenstände:**
  - Backend: Signatur `v1=<hex(HMAC-SHA256(secret, timestamp + "." + raw_body))>` mit Key-ID, Zeitfenster ±300 s, Replay-Prüfung über den Rohbody; Schlüsselrotation mit begrenztem Parallelbetrieb; unbekannte Schema-/Signaturversionen werden isoliert (Kap. 02 §8); Outbound-Retry als Planungsdefault 1 s, 5 s, 30 s, 2 min, 10 min, danach exponentiell mit Jitter bis 24 h; 429 respektiert `Retry-After`; Dead-Letter mit Betreiberwarnung; erneute Verarbeitung mit Originalschlüssel.
  - Datenbank: Zustellversuchs-/Dead-Letter-Tabelle (OP-02), `webhook_subscription` (Schlüsselversionen als Secretreferenz), `inbox` (`payload_sha256`).
  - API: `/v1/webhook-subscriptions` (Schlüssel rotieren, Dead-Letter einsehen und erneut zustellen); Eingangsantworten 202, 200 `ALREADY_RECEIVED`, 409 mit Quarantäne.
  - Ereignisse/Outbox: Sicherheitsalarm bei gleicher Event-ID mit verändertem Payload (`business_alert`).
  - Konfigurationsparameter: `partner_retry_policy`.
- **Detailverträge vorab:** veröffentlichter Signaturvertrag (Header, Algorithmus, Zeitfenster, Rotationsablauf); Klassifikation vorübergehend vs. endgültig je HTTP-Status; Dead-Letter-Bearbeitungsregel.
- **Akzeptanzkriterien:**
  1. Ein manipulierter Event (veränderter Body, falsche Signatur, Zeitstempel außerhalb ±300 s, unbekannte Key-ID) wird zurückgewiesen und erzeugt keinen Inbox-Eintrag und keine Fachwirkung. (INT-006)
  2. Antwortet der Empfänger vorübergehend mit 503 oder Timeout, wird derselbe Event mit derselben `event_id` gemäß Staffel erneut geliefert und kommt nach Erholung genau einmal fachlich an. (INT-006)
  3. Nach Erschöpfung der Versuche liegt der Event im Dead-Letter, eine Betreiberwarnung existiert, und eine manuelle Neuzustellung verwendet den Originalschlüssel. (INT-006)
  4. Während der Rotation sind alter und neuer Schlüssel verifizierbar; nach Ende nur der neue.
  5. Gleiche Event-ID mit anderem Payload ergibt 409, Sicherheitsalarm und Quarantäne.
  6. 100 identische Zustellungen erzeugen genau eine Wirkung.
  7. Ein verspätet eintreffender Event mit älterer Aggregatversion überschreibt keinen neueren Zustand; eine Versionslücke löst Nachlesen aus (PH-AT-017).
  8. Zustellversuche und Dead-Letter-Einträge speichern nur den minimierten Payload, sind mandantengetrennt, nur für berechtigte Rollen einsehbar und werden nach ihrer Aufbewahrungsklasse gelöscht.
  9. Manuelle Neuzustellung und Schlüsselrotation sind auditiert; Secretwerte erscheinen weder in Datenbank, Log noch Oberfläche.
- **Tests:** positiv: Zustellung nach zwei 503. negativ: Manipulation an Body, Zeitstempel, Signatur, Key-ID; Replay nach Fensterablauf; übergroßer Body. konkurrierend (Sicherheit): 100 parallele identische Events; Rotation während laufender Zustellung; paralleler Retry und manuelle Neuzustellung. Besteht AT-24 (Webhook-Teil), semantischer Contract-Fall „100 identische Webhooks"; ermöglicht E2E-090, E2E-104, PH-AT-017.
- **Abhängigkeiten:** intern: WP-M6b-23.
  - extern: WP-M1-14, WP-M1-16, WP-M1-36 (Webhook-Grundlage (HMAC-Eingangsfilter, Zustellworker); Secret-Verwaltung; Inbox-Deduplikation)
- **Entscheidungen:** D-08 – blockiert nur partnerindividuelle Retry-Vereinbarungen.
- **Größe:** M
- **Nicht im Umfang:** fachliche Verarbeitung von Partner- und PSP-Ereignissen (M6a, M6c); PSP-spezifische Signatur (M2).

### WP-M6b-25 – Pagination, Filterung und Änderungssemantik aller Listen
- **Ziel:** Alle Listenressourcen liefern große Datenmengen ohne Auslassungen oder Duplikate, mit dokumentierten Filtern und dokumentierter Änderungssemantik.
- **Anforderungen:** `LH-6.17-INT-007`
- **Liefergegenstände:**
  - Backend: durchgängige Anwendung der Listenkonvention (Kap. 05 §5.1): `limit` 1–100 (Default 50), opaker signierter `cursor`, stabiler Sortierschlüssel `(created_at,id)`, Antwort `{items,next_cursor,as_of}`, Snapshot/As-of bei Exporten; Filter dürfen Autorisierung nur weiter einschränken; Änderungsabruf (geändert seit Cursor/Revision) je Ressource; Konformitätsprüfung als Architekturtest über alle Module.
  - API: alle Listenpfade aus Kap. 05 §5.7, u. a. `/v1/bookings`, `/v1/operator/vehicles`, `/v1/operator/incidents`, `/v1/stops`, `/v1/service-areas`, `/v1/services`, `/v1/rosters`, `/v1/cost-events`, `/v1/notifications`, `/v1/webhook-subscriptions`, `/v1/config/revisions`; dokumentierte Filterschemata und Negativbeispiele.
  - Datenbank: Indizes `(tenant_id,state,updated_at)` und `(tenant_id,created_at,id)` je Listenressource (Kap. 04 §4.7).
- **Detailverträge vorab:** Dokument „Änderungssemantik" (ETag/`If-Match`, Revision, Löschkennzeichen, Verhalten bei Änderung während Blättern); Filterkatalog je Ressource; Cursor-Gültigkeit.
- **Akzeptanzkriterien:**
  1. Der vollständige Abruf von 10.000 Datensätzen (Testwert) über Folgeseiten liefert jeden Datensatz genau einmal, auch wenn währenddessen Datensätze eingefügt, geändert und gelöscht werden. (INT-007)
  2. Jeder dokumentierte Filter liefert dieselbe Menge wie eine Referenzabfrage; kein Filter erweitert die Sicht über die Autorisierung hinaus. (INT-007)
  3. Die Änderungssemantik ist je Ressource dokumentiert und durch Contract-Test belegt (ETag, 412, 428, Änderungsabruf). (INT-007)
  4. Ein manipulierter oder mandantenfremder Cursor wird abgewiesen.
  5. Jede Listenressource besteht denselben generischen Konformitätstest.
- **Tests:** positiv: Vollabruf mit `limit` 1, 50, 100. negativ: `limit` 0 und 101; manipulierter Cursor; Cursor von Mandant A unter Mandant B; unbekannter Filter (422). konkurrierend (Datentrennung): Schreiblast während Blättern; gleiche `created_at` vieler Zeilen.
- **Abhängigkeiten:** intern: WP-M6b-01.
  - extern: WP-M1-02 (API-Grundgerüst mit Listen-/Cursor-Baustein und ETag)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** asynchrone Vollexporte (WP-M6b-20, WP-M6b-31); Listen der Module aus M7/M8 (übernehmen den Konformitätstest).

### WP-M6b-26 – Partnerzugänge: Maschinenidentität, Scopes, Quoten, Schlüsselentzug
- **Ziel:** Jeder Partner besitzt eigene Maschinenidentitäten mit schmalen Scopes, eigenen Limits und kündbaren Zugangsschlüsseln; ein entzogener Zugang ist unmittelbar unbrauchbar.
- **Anforderungen:** `LH-6.17-INT-011`
- **Liefergegenstände:**
  - Backend: OAuth Client Credentials je Partner und Zweck; Scope-Prüfung je Anfrage (issuer, audience, expiry, session status, Scopes); Bindung des Zugangs an Vertrag/Grant; Quoten nach Tenant, Partner und Akteur mit 429 und `Retry-After`; sofortiger Entzug über serverseitige Sperre und Online-Grantprüfung unabhängig von der Tokenlaufzeit; HMAC-Partnerschlüssel im Secret Manager, rotierbar.
  - Datenbank: Partnerzugang als Dienstprinzipal in `membership` (Rolle Partner, `revoked_at`) bzw. eigener Zugangstabelle (Detailvertrag); `audit_event`.
  - API: Zugang anlegen, Schlüssel rotieren, entziehen (Detailvertrag); `/v1/partner/capabilities` zeigt Scopes und Quoten des aufrufenden Partners.
  - Oberfläche: UI-18 – Zugänge, Scopes, Limits, letzter Gebrauch, Entzug.
  - Konfigurationsparameter: `partner_api_timeout_ms` (Anzeige), Quotenwerte je Partner (Schema im Detailvertrag).
- **Detailverträge vorab:** Scope-Katalog der Partner-API; Quotenmodell; Verfahren „unmittelbar" (maximale Wirkverzögerung, OP-13).
- **Akzeptanzkriterien:**
  1. Nach Entzug eines Partnerzugangs wird die nächste Anfrage mit einem zuvor ausgestellten, formal noch gültigen Token abgewiesen. (INT-011)
  2. Jeder Partner hat eigene Scopes; ein Aufruf außerhalb des Scopes ergibt 403, ein Aufruf über dem Limit 429 mit `Retry-After`. (INT-011)
  3. Schlüssel sind einzeln kündbar; der Entzug eines Schlüssels beeinträchtigt andere Partner nicht. (INT-011)
  4. Limits eines Partners wirken nicht auf andere Partner oder eigene Nutzer.
  5. Anlage, Rotation und Entzug sind auditiert; Secretwerte erscheinen weder in Datenbank noch Logs.
- **Tests:** positiv: Aufruf im Scope. negativ: fremder Scope; entzogener Schlüssel; Token eines anderen Partners; Limitüberschreitung. konkurrierend (Sicherheit): Entzug während 100 paralleler Anfragen – keine Annahme nach dem Entzugszeitpunkt; Rotation unter Last. Ermöglicht PH-AT-003 (altes Token).
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-07.
  - extern: WP-M1-02, WP-M1-04, WP-M1-10, WP-M1-36 (OIDC-Ressourcenserver und IdP-Realm; Sitzungssperre; Secret-Verwaltung; Rate-Limit-Baustein)
- **Entscheidungen:** D-08 – blockiert nur die Ausgabe produktiver Partnerzugänge.
- **Größe:** M
- **Nicht im Umfang:** fachliche Partnerendpunkte `/v1/partner/offers`, `/v1/partner/reservations`, `/v1/partner/reservations/{id}/confirm`, `/cancel`, `/status` (M6a).

### WP-M6b-27 – GTFS-/GTFS-RT-Adapter mit Feedkonfiguration
- **Ziel:** Fahrplan- und Echtzeitfeeds lassen sich je Mandant konfigurierbar anbinden; ein Feed mit Ausfall oder Verspätung aktualisiert die Reiseauskunft.
- **Anforderungen:** `LH-6.17-INT-008`
- **Liefergegenstände:**
  - Adapter/Worker: `backend/adapters/timetable-gtfs` – Abruf, Validierung und Import von GTFS (Fahrplan, Haltestelle, Kalender) und GTFS-RT (Ausfall, Verspätung); Standardversion je Adapter im Contract-Test fixiert, kein `latest`; Abrufintervall, Quelle, Lizenz und Staleness-Grenze konfigurierbar; fehlerhafter Feed wird isoliert, letzter gültiger Stand bleibt mit Datenalter.
  - Datenbank: `timetable_feed` (Quelle, Lizenz, Version), `realtime_observation` (Quellenzeit, Staleness).
  - API: Feedverwaltung (Detailvertrag, OP-14); Aktualitätsdatum im Adaptermodell.
  - Ereignisse/Outbox: Ereignis „Fahrplanabweichung" (Ausfall/Verspätung) für Reiseauskunft und Anschlussüberwachung.
  - Oberfläche: UI-17 – Feedliste mit letztem Abruf, Status, Datenalter.
  - Konfigurationsparameter: `partner_transport_modes`, `partner_api_timeout_ms`, `partner_retry_policy`.
- **Detailverträge vorab:** gepinnte GTFS-/GTFS-RT-Version; Abbildung Feed-Haltestelle → `stop_revision`/`transfer_hub`; Regel „Fahrplan-/Realtimefeed ist keine Buchungs- oder Zahlungserlaubnis" (Kap. 02 §9).
- **Akzeptanzkriterien:**
  1. Ein Testfeed mit einem Ausfall und einer Verspätung aktualisiert die Reiseauskunft: die betroffene Verbindung zeigt Ausfall bzw. neue Zeit mit Datenalter und Ursache. (INT-008)
  2. Ein zweiter Feed lässt sich ohne Codeänderung durch Konfiguration anbinden und wieder deaktivieren. (INT-008)
  3. Ein syntaktisch oder inhaltlich fehlerhafter Feed überschreibt den letzten gültigen Stand nicht; der Fehler ist sichtbar.
  4. Aus Feeddaten entstehen ausschließlich `INFORMATION_ONLY`- oder `EXTERNAL_BOOKING`-Abschnitte, nie `PLATFORM_BOOKABLE`.
  5. Überschreitet das Datenalter die Staleness-Grenze, wird die Unsicherheit ausgewiesen.
  6. Anlegen, Ändern und Deaktivieren eines Feeds sind auditiert; ohne hinterlegte Lizenzangabe wird ein Feed nicht aktiviert; Zugangsdaten eines Feeds liegen nur als Secretreferenz vor.
  7. Ein Feed eines Mandanten ist für andere Mandanten weder sichtbar noch wirksam, solange keine Freigabe über einen Grant besteht.
- **Tests:** positiv: Testfeed mit Ausfall/Verspätung. negativ: defektes Archiv; Feed ohne Lizenzangabe; Zeitstempel in der Zukunft; Feed-URL in privatem Netz. konkurrierend (Datentrennung): zwei Mandanten mit eigenem Feed parallel – keine Vermischung; Import während Abfrage liefert konsistenten Stand. Ermöglicht E2E-087, AT-14, PH-AT-006.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M2-05 (Haltepunktrevisionen als Zuordnungsziel)
  - extern: WP-M6a-19, WP-M6a-26, WP-M6a-27 (Reiseauskunft und Informationssegmente konsumieren Feeddaten; Anschlussüberwachung konsumiert Abweichungsereignisse)
- **Entscheidungen:** D-08 (Datenlizenzen, Feeds der Pilotpartner) – blockiert nur die Produktivschaltung realer Feeds.
- **Größe:** M
- **Nicht im Umfang:** fachliche Verarbeitung der Feeddaten für die Reiseauskunft (kanonisches Fahrplanmodell, Herkunftskennzeichnung, Versions-/Reihenfolgeregel, Darstellung – M6a, dort WP-M6a-26/WP-M6a-27; Akzeptanzkriterium 1 wird gemeinsam mit M6a nachgewiesen); NeTEx/SIRI/VDV-Adapter (nach D-08); Telematik-/Ladesystemadapter (M9, INT-013); Reiseplanung und Anschlussrisiko (M6a).

### WP-M6b-28 – CSV-/GeoJSON-Import mit Validierung und Quarantäne
- **Ziel:** CSV- und GeoJSON-Importe prüfen Feldschema, Einheiten, Koordinaten und Datumswerte; fehlerhafte Daten werden isoliert und verständlich beanstandet.
- **Anforderungen:** `LH-6.17-INT-009`
- **Liefergegenstände:**
  - Backend: Importrahmen mit Uploadticket (Größe, MIME, Hash), Zeichensatz- und Trennzeichenprüfung, Schema je Importtyp, Einheitenprüfung, Koordinatenprüfung (WGS84/SRID 4326, Wertebereich, gültige Geometrie), Datumsprüfung (RFC 3339 bzw. ISO-Datum, Zeitzone, DST-Lücke); Trockenlauf; zeilenweise Fehlerliste mit Zeile, Feld, Regel, Klartext; Isolation fehlerhafter Zeilen bzw. Dateien; idempotente Wiederholung.
  - Datenbank: Importlauf- und Importfehlertabelle (OP-02); Ziel `stop_revision`, `service_area` als neue Entwurfsrevision.
  - API: Import über Uploadticket und Jobressource (Detailvertrag, OP-14); Ergebnisabruf.
  - Oberfläche: UI-17 – Import starten, Fehlerbericht, Entwurf übernehmen; Zustände „ungültige Geometrie", „konkurrierende Version".
- **Detailverträge vorab:** Importtypen und Spaltenschemata (mindestens Haltestellen CSV, Bediengebiete GeoJSON; weitere siehe OP-14); Regel „alles oder nichts" vs. Teilübernahme je Typ; Fehlermeldungskatalog.
- **Akzeptanzkriterien:**
  1. Eine Datei mit fehlerhaften Zeilen (fehlendes Pflichtfeld, falsche Einheit, Koordinate außerhalb des Wertebereichs, ungültiges Datum) führt zu keiner Übernahme der fehlerhaften Daten; diese sind isoliert einsehbar. (INT-009)
  2. Jede Beanstandung nennt Zeile, Feld, verletzte Regel und eine verständliche Meldung in der Sprache des Nutzers. (INT-009)
  3. Eine ungültige oder selbstüberschneidende GeoJSON-Geometrie wird abgewiesen.
  4. Ein korrekter Import erzeugt Entwurfsrevisionen, keine sofort wirksamen Daten.
  5. Die Wiederholung derselben Datei erzeugt keine Duplikate.
  6. Importlauf, Übernahme und Verwerfen sind auditiert; isolierte Fehlerdaten sind nur im eigenen Mandanten einsehbar und werden nach ihrer Aufbewahrungsklasse gelöscht.
  7. Der Fehlerbericht ist in UI-17 per Tastatur und Screenreader lesbar und als Datei exportierbar; Zellinhalte werden beim Export gegen Formelausführung neutralisiert.
- **Tests:** positiv: gültige CSV und GeoJSON. negativ: je Regel eine Fehlerdatei; Datei mit Formel-/Skriptinhalt; übergroße Datei; falscher Zeichensatz; Koordinaten vertauscht (Lat/Lon). konkurrierend (Datentrennung): zwei parallele Importe desselben Scopes – genau eine Entwurfsrevision je Lauf, kein Überschreiben.
- **Abhängigkeiten:** intern: WP-M6b-02.
  - extern: WP-M1-11, WP-M1-38, WP-M4-21 (Eingabehärtung; Objektspeicher mit Uploadticket)
  - extern: WP-M2-04, WP-M2-05 (Haltepunkt- und Gebietsrevisionen mit Geo-Validierung)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Stammdateneinspielung vor Betriebsstart (M5); Tankkarten- und Rechnungsimporte (M8).

### WP-M6b-29 – Austauschbare Provider-Adapter (Zahlung, SMS, Push, E-Mail, CRM)
- **Ziel:** Zahlungs-, SMS-, Push-, E-Mail- und CRM-Provider hängen hinter stabilen Ports; ein Providerwechsel erfordert keine Änderung der zentralen Buchungslogik.
- **Anforderungen:** `LH-6.17-INT-010`
- **Liefergegenstände:**
  - Backend: Port-Schnittstellen je Providerklasse (Zahlung: Authorize/Capture/Void/Refund/Status; Nachricht: Senden/Zustellstatus je Kanal; CRM: Kontakt-/Fallübergabe laut Detailvertrag); Architekturtest: Module `booking-dispatch` und `journey-federation` importieren keine Providerklassen; Providerwahl je Mandant per Konfiguration; normalisierte Fehler- und Statuscodes.
  - Adapter/Worker: `backend/adapters/psp`, `backend/adapters/notification` jeweils mit zweitem Referenzadapter (Simulator) als Wechselnachweis; CRM-Adapter als Port mit Referenzimplementierung.
  - Datenbank: `adapter_capability` (Provider, Fähigkeit, Sandboxnachweis) lesend; `notification_attempt`, `payment_event` unverändert.
  - API: keine neuen öffentlichen Pfade; `/v1/payments/provider-events/{provider}` bleibt je Provider getrennt.
- **Detailverträge vorab:** Port-Verträge je Klasse mit Konformitätstest; Umfang der CRM-Integration (OP-15); Konfigurationsschema der Providerwahl.
- **Akzeptanzkriterien:**
  1. Der Wechsel des Zahlungs-, SMS-, Push- oder E-Mail-Providers auf den zweiten Referenzadapter erfolgt ausschließlich durch Konfiguration und Adaptermodul; der Diff der Module `booking-dispatch` und `journey-federation` ist leer. (INT-010)
  2. Dieselbe Buchungs-Regressionssuite besteht mit beiden Adaptern. (INT-010)
  3. Jeder Adapter besteht den Konformitätstest seines Ports (Idempotenz, Statusabfrage, Fehlerklassen).
  4. Ein nicht nachgewiesener Adapter bleibt deaktiviert.
  5. Der CRM-Port besteht denselben Wechselnachweis mit zwei Referenzadaptern; an ein CRM gehen nur die im Detailvertrag freigegebenen Felder (Umfang OP-15). (INT-010)
  6. Providerwahl und -wechsel sind je Mandant auditiert; Providerzugangsdaten liegen nur als Secretreferenz vor.
- **Tests:** positiv: Buchung mit Adapter 1 und Adapter 2. negativ: Adapter ohne Statusfähigkeit wird nicht für Zahlung freigeschaltet; unbekannter Providerstatus fail closed. konkurrierend (Geld): Providerwechsel bei offenen Zahlungen – offene Vorgänge bleiben beim ursprünglichen Provider, keine Doppelbelastung. Ermöglicht AT-15 (Adapterebene).
- **Abhängigkeiten:** intern: WP-M6b-01.
  - extern: WP-M0-23 (PSP-Simulator mit steuerbaren Fehlern)
  - extern: WP-M2-29, WP-M2-30, WP-M2-32 (PSP-Adapter und Zahlungsautomat; asynchroner Nachrichtenversand mit Zustellversuchen)
- **Entscheidungen:** D-03 (PSP-Auswahl) – blockiert nur die Produktivschaltung eines realen zweiten Providers.
- **Größe:** M
- **Nicht im Umfang:** Mehrparteien-Zahlungsmodell und Settlement (M6c); Partneradapter TOMP/OSDM (M6a).

### WP-M6b-30 – API-Versionierung und Deprecation-Verfahren
- **Ziel:** API-Versionen werden nach einem veröffentlichten Verfahren gepflegt; ein Client auf einer unterstützten Vorgängerversion bleibt innerhalb der Übergangsfrist funktionsfähig.
- **Anforderungen:** `LH-6.17-INT-012`
- **Liefergegenstände:**
  - Backend: Parallelbetrieb zweier Major-Versionen (`/v1` und Folgeversion) im Gateway; Kennzeichnung abgekündigter Operationen in OpenAPI (`deprecated`) und per Antwortheader; Nutzungsmessung je Client und Version; CI-Prüfung auf Breaking Changes gegen die veröffentlichte OpenAPI.
  - API: veröffentlichtes Deprecation-Dokument (Ankündigung, Übergangsfrist, Abschaltung); Planungsdefault mindestens sechs Monate Parallelbetrieb (Kap. 05 §5.1), konkrete Partnerfristen vertraglich.
  - Ereignisse/Outbox: Versionierung der Ereignisschemata (`schema_version`) nach demselben Verfahren.
- **Detailverträge vorab:** Definition „Breaking Change"; Headerkonvention; Benachrichtigungsweg an Partner.
- **Akzeptanzkriterien:**
  1. Ein Testclient, der gegen die Vorgängerversion gebaut ist, besteht seine Contract-Suite unverändert, während die Folgeversion produktiv ist. (INT-012)
  2. Das Deprecation-Verfahren ist veröffentlicht; jede abgekündigte Operation trägt Ankündigungs- und Abschaltdatum. (INT-012)
  3. Ein Breaking Change innerhalb einer Major-Version lässt die CI scheitern.
  4. Die Nutzung abgekündigter Operationen ist je Partner auswertbar.
  5. Betroffene Partner und Webhook-Empfänger werden über jede Abkündigung auf dem im Detailvertrag festgelegten Weg benachrichtigt; die Benachrichtigung ist nachweisbar.
- **Tests:** positiv: Vorgängerclient gegen Parallelbetrieb. negativ: Entfernen eines Pflichtfelds ohne neue Major-Version; Aufruf nach Abschaltdatum liefert dokumentierten Fehler.
- **Abhängigkeiten:** intern: WP-M6b-01.
  - extern: WP-M1-03 (OpenAPI je Modul und Contract-Test-Pipeline)
- **Entscheidungen:** D-08 – blockiert nur die vertragliche Festlegung der Partnerfristen.
- **Größe:** S
- **Nicht im Umfang:** tatsächliche Einführung einer `/v2`; mobile App-Versionierung.

### WP-M6b-31 – Mobilitätsdatenexporte je Empfänger
- **Ziel:** Datenexporte für Mobilitätsdatenplattformen und regionale Sammelstellen sind je Empfänger getrennt konfigurierbar; jeder Lauf erzeugt ein validiertes Protokoll und ist wiederholbar.
- **Anforderungen:** `LH-6.17-INT-014`
- **Liefergegenstände:**
  - Backend: Exportprofil je Empfänger (Inhalt, Format, Gebiet/Dienst, Rhythmus, Ziel, Rechtsgrundlage); asynchroner Lauf mit Snapshot/As-of; Validierung der Ausgabe gegen Zielschema; Laufprotokoll (Umfang, Prüfsumme, Validierungsergebnis, Empfangsstatus); Wiederholung liefert denselben Datenstand und dieselbe Lauf-ID; Empfängerrechte beim Export.
  - Datenbank: `export_job` (Exportzweck, Profil, Protokoll), Exportprofil-Tabelle (OP-02).
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Jobtyp Mobilitätsdatenexport); Profilverwaltung (Detailvertrag).
  - Adapter/Worker: Exportworker; Zustellung an Empfängerziel mit Retry.
  - Oberfläche: UI-19 bzw. UI-20 – Profile, Läufe, Protokolle, Wiederholung.
- **Detailverträge vorab:** Zielformate und Pflichtinhalte je Empfängerklasse (OP-16); Protokollschema; Wiederholungssemantik.
- **Akzeptanzkriterien:**
  1. Ein Exportlauf erzeugt ein Protokoll mit Validierungsergebnis gegen das Zielschema, Umfang und Prüfsumme. (INT-014)
  2. Der Lauf ist wiederholbar und liefert zum selben As-of identische Inhalte. (INT-014)
  3. Zwei Empfänger haben getrennte Profile; die Änderung eines Profils verändert den Export des anderen nicht. (INT-014)
  4. Ein Export mit Validierungsfehler wird nicht zugestellt und als fehlgeschlagen protokolliert.
  5. Der Export enthält nur Daten des Mandanten und des freigegebenen Gebiets; personenbezogene Daten nur laut Profil.
  6. Anlage und Änderung eines Profils, jeder Lauf und jede Wiederholung sind auditiert; ein Profil ohne hinterlegte Rechtsgrundlage und Empfänger wird nicht aktiviert.
  7. Das Empfängerziel wird gegen SSRF geprüft; Zugangsdaten liegen nur als Secretreferenz vor.
- **Tests:** positiv: zwei Profile, je ein Lauf. negativ: Profil für fremden Mandanten; ungültiges Zielschema; Ziel nicht erreichbar (Retry, dann Fehlerprotokoll). konkurrierend (Datentrennung): Wiederholung parallel zum Erstlauf – ein Ergebnis; Schreiblast verändert den Snapshot nicht. Ermöglicht AT-25.
- **Abhängigkeiten:** intern: WP-M6b-02, WP-M6b-25.
  - extern: WP-M2-03, WP-M2-04, WP-M2-23 (Buchungs-, Dienst- und Gebietsdaten als Exportquelle)
  - extern: WP-M5-19 (Exporte als asynchrone Jobs mit `export_job` und Empfängerrechten)
- **Entscheidungen:** D-11, D-05 – blockieren nur die Produktivschaltung (Empfänger, Rechtsgrundlage).
- **Größe:** M
- **Nicht im Umfang:** Datenkategorien, Freigabe und Fristnachweis der gesetzlichen Mobilitätsdatenpflichten (M6a, WP-M6a-33, baut auf dieser Profil- und Laufmechanik auf); Berichtsjobs und Mobilitätsdatenexporte des Reportingstroms (M8, ANA); Mandantenexport (WP-M6b-20); ERP-Export (M4).

### WP-M6b-32 – Trace-Korrelation über die Integrationskette
- **Ziel:** Jede externe Provider- und Partneranfrage ist über Trace-IDs mit dem auslösenden Geschäftsvorgang korreliert; der Support kann einen Fehler über die gesamte Kette verfolgen.
- **Anforderungen:** `LH-6.17-INT-015`
- **Liefergegenstände:**
  - Backend: Weitergabe von W3C `traceparent` und `correlation_id` an alle Adapteraufrufe (PSP, Nachricht, CRM, Fahrplanfeed, Webhook-Zustellung, Partner); Speicherung von `correlation_id`, `saga_id`, `leg_id`, `partner_operation_id` je Aufruf (Kap. 02 §7.1); Verknüpfung asynchroner Schritte über Outbox/Inbox; keine Personen-/Ortssuchen als Metriklabels.
  - Datenbank: `audit_event.correlation_id`, `saga_step.partner_command_ref` / `partner_result_ref` als Suchschlüssel; Zustellversuche mit Trace-ID.
  - API: `correlation_id` in jeder `application/problem+json`-Antwort; Suche nach Correlation-ID im Supportkontext.
  - Oberfläche: UI-20 – Kettenansicht eines Vorgangs (Schritte, Provider, Status, Zeit) ohne Rohpayload mit PII.
- **Detailverträge vorab:** Feldliste je Span; Maskierungsregel; Aufbewahrung der Traces.
- **Akzeptanzkriterien:**
  1. Für einen provozierten Fehler beim Provider findet der Support anhand der dem Kunden gezeigten `correlation_id` alle Schritte der Kette vom API-Eingang über Worker bis zur externen Anfrage und deren Antwort. (INT-015)
  2. Jede ausgehende Provideranfrage trägt eine Trace-ID, die im eigenen Trace auffindbar ist. (INT-015)
  3. Ein ungültiger eingehender `traceparent` wird durch eine neue interne Correlation-ID ersetzt.
  4. Traces und Logs enthalten keine Kontakt-, Zahlungs- oder Unterstützungsdaten.
  5. Die Kette bleibt über einen Prozessneustart und Retry hinweg zusammenhängend.
  6. Die Kettenansicht ist nur für die berechtigte Supportrolle im eigenen Mandanten bzw. im freigegebenen Supportkontext (WP-M6b-19) abrufbar; jeder Abruf ist auditiert.
- **Tests:** positiv: Fehlerfall PSP-Timeout Ende-zu-Ende verfolgbar. negativ: Suche nach Correlation-ID eines fremden Mandanten (leer); PII-Scan der Traces; Suche ohne Supportrolle bzw. ohne aktiven Supportkontext (403). konkurrierend (Datentrennung): parallele Vorgänge zweier Mandanten mit demselben eingehenden `traceparent` – getrennte Ketten, keine Vermischung. Ermöglicht PH-AT-007, PH-AT-009 (Diagnoseteil).
- **Abhängigkeiten:** intern: WP-M6b-19, WP-M6b-24, WP-M6b-29.
  - extern: WP-M1-02 (Tracing-Grundlage im API-Grundgerüst)
  - extern: WP-M5-03, WP-M5-06 (Telemetrie-Vollausbau (Metriken, Traces, Logs je Komponente) und Maskierung von Diagnosedaten)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** UI-08 Klärarbeitsplatz mit Saga-Schritten (M6a); Alerting (M5/M8).

### WP-M6b-33 – Fremdfahrzeug und Fremdbeleg: Einsatzfreigabe unter Kooperation
- **Ziel:** Die in M1/M3/M4 gebauten Bausteine für Fremdressourcen werden an Vertrag und Grant gebunden: Einsatz nur mit aktiver Freigabe, Qualifikation, Schicht und Check; Belege bleiben im richtigen Mandanten.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Kopplung von `ResourceAssignment` (Eigentum, Einsatzbetreiber, Personal-Arbeitgeber, Abrechnungspartner als unabhängige Attribute; Kap. 02 §2) an `cooperation_grant`; freigegebenes Fahrzeugprofil enthält nur Kapazitäten, Einsatzzeitraum, bestätigte Einsatzfähigkeit; Belegzuordnung zu fremdem Fahrzeug nur bei aktiver Einsatzfreigabe, sonst Ablehnung mit sicherem Entwurf.
  - Datenbank: `vehicle_lease`, `vehicle_assignment`, `resource_release`, `staff_shift`, `expense_document`, `cooperation_grant`.
  - API: bestehende Pfade `/v1/vehicles/{id}/release`, `/v1/expenses/receipts`; Fehlercodes 404/403 laut Kap. 05 §5.4.
- **Detailverträge vorab:** Regel, welche Prüfungen trotz Partnervertrag zwingend bleiben (Qualifikation, Schicht, bestätigter Check); Verhalten bei Grantentzug während Einsatz.
- **Akzeptanzkriterien:**
  1. Fahrer A kann einen Beleg nicht einem B-Fahrzeug zuordnen; das Original bleibt im Entwurf von Mandant A (E2E-092, PH-AT-016).
  2. Mit aktiver Einsatzfreigabe für ein B-Fahrzeug in Dienst A ist die Nutzung möglich; Eigentümer, Betriebsträger und Kostenpflichtiger bleiben getrennt (E2E-094).
  3. Einsatz ohne passende Qualifikation, Schicht oder bestätigten Check wird trotz Partnervertrag abgewiesen (E2E-095).
  4. Der Einsatzbetreiber sieht weder Einkaufspreis noch vollständige Schadenhistorie des Eigentümers.
  5. Wird die Freigabe während eines laufenden Einsatzes entzogen, greift die im Detailvertrag festgelegte Regel (laufende Fahrt abschließen bzw. Störungsablauf); es entsteht keine neue Zuweisung auf das Fahrzeug.
  6. Jede Freigabe, Nutzung und Ablehnung ist auditiert; die Ablehnung der Belegzuordnung erzeugt ein Sicherheitsereignis ohne B-Inhalte.
- **Tests:** positiv: freigegebenes Drittfahrzeug im Dienst. negativ: Beleg an fremdes Fahrzeug; Einsatz ohne Check; abgelaufene Freigabe. konkurrierend (Sicherheit/Kapazität): Grantentzug parallel zur Einsatzfreigabe – genau ein konsistentes Ergebnis. Besteht E2E-092, E2E-094, E2E-095, PH-AT-016.
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-15.
  - extern: WP-M1-34, WP-M1-35 (Fahrzeugrollen mit Gültigkeitsintervallen; zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion)
  - extern: WP-M3-08, WP-M3-09, WP-M3-19 (Sperr- und Freigabekern (`resource_release`), Readiness-Gate, Qualifikationsprüfung)
  - extern: WP-M4-17, WP-M4-22, WP-M4-29 (Belegaggregat mit Kostenzuordnung; Schicht- und Fahrzeugbereitstellung)
- **Entscheidungen:** D-01, D-07 – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme bei Werkstattausfall (M7, E2E-100); Abrechnung der Fahrzeuggestellung (M6c).

### WP-M6b-34 – M6b-Gate-Abnahmesuite
- **Ziel:** Die M6b-Anteile der M6-Gate-Tests laufen automatisiert in `tests/acceptance` und sind mit Build, Evidenz und Entscheidung in `e2e.csv` eintragbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: ausführbare Szenarien PH-AT-002, PH-AT-003, PH-AT-016, E2E-085, E2E-092, E2E-094, E2E-095 sowie die Zusatzfälle aus Kap. 02 §10 „Mandant A/B, Drittpartner C", „Falscher Tenant in Body/URL/Cache", „Widerruf während Buchung", „Vertragsablauf bei bestehender Zusage"; Beitrag (Führung M6a/M6c) zu E2E-086, E2E-087, E2E-088, E2E-089, E2E-090, E2E-093, E2E-104, PH-AT-005, PH-AT-006, PH-AT-007, PH-AT-008, PH-AT-009, PH-AT-010, PH-AT-014, AT-14, AT-16.
  - Adapter/Worker (Zuordnung Referenztest → liefernde WPs dieser Einheit): PH-AT-002 → WP-M6b-05, WP-M6b-09, WP-M6b-13; PH-AT-003 → WP-M6b-06, WP-M6b-07, WP-M6b-26; PH-AT-005 → WP-M6b-09, WP-M6b-12; PH-AT-006, E2E-087, AT-14 → WP-M6b-27, WP-M6b-14; PH-AT-007, PH-AT-009 → WP-M6b-24, WP-M6b-32; PH-AT-016, E2E-092, E2E-094, E2E-095 → WP-M6b-33, WP-M6b-12, WP-M6b-15; E2E-085 → WP-M6b-10, WP-M6b-11; E2E-086 → WP-M6b-10, WP-M6b-11, WP-M6b-09; E2E-088 → WP-M6b-06 (Anbieterfilter), WP-M6b-32; E2E-090, E2E-104 → WP-M6b-24; E2E-093 → WP-M6b-12. Ohne fachlichen M6b-Anteil außer Grant-Gate, Fixtures und Trace: PH-AT-008, PH-AT-010, PH-AT-014, E2E-089, AT-16 (M6a/M6c).
  - Oberfläche: Sammelabnahme der M6b-Oberflächen in UI-02, UI-03, UI-04, UI-07, UI-15, UI-17, UI-18, UI-19, UI-20 nach Kap. 06 §6.9.
  - Backend: Evidenzerfassung je Lauf (`source_key[]`, Test-ID/Version, Build, Migrationsstand, Konfigurations-/Tarif-/Vertragsrevision, Seed, Rollen und Mandanten, Soll/Ist, Trace-IDs; Kap. 08 §8.4).
  - Kennzahlen: Nachweislauf für `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss` am Fixture.
- **Detailverträge vorab:** Testspezifikation je Szenario mit Fixture und erwarteten Zuständen; Zuordnung Szenario → WP.
- **Akzeptanzkriterien:**
  1. PH-AT-002 besteht einschließlich der Wiederholung für Cache, Event-Subscription und Report-Download.
  2. PH-AT-003 besteht einschließlich des gesonderten Falls „Sicherheitsnotfall entzieht auch Abwicklungsrechte".
  3. PH-AT-016, E2E-092, E2E-094 und E2E-095 bestehen unter der nicht privilegierten Runtime-Rolle.
  4. E2E-085: Auswahlregel bei Gebietsüberlappung ist eindeutig; Buchung entsteht nur beim bestätigenden Betreiber.
  5. Alle 17 MUSS-Anforderungen der Einheit haben einen grünen Test; SOLL-Abweichungen sind mit Begründung dokumentiert.
  6. Evidenz enthält keine realen Fahrgast- oder Personaldaten.
  7. Die semantischen Contract-Fälle aus Kap. 05 §5.8 mit M6b-Bezug laufen grün: „100 identische Webhooks", „Manipulation tenant_id", „unzulässige Fremd-ID", „EUR-Rundungsrest", „Allokationssumme ≠ Basis", „geschlossene Periode".
  8. Jede M6b-Oberfläche besteht die UI-Abnahme aus Kap. 06 §6.9: Tastatur und Screenreader, Leer-/Fehler-/Rechtezustände, „Mandantenwechsel mit zwei Tabs", „412-Konflikt ohne stilles Überschreiben".
  9. Jede Querschnittsregel des Dateikopfs ist je WP nachgewiesen: mutierende Operationen mit `Idempotency-Key` und ETag, Audit je Fachänderung, Aufbewahrungsklasse je neuer Tabelle.
  10. Die Zusatzfälle aus Kap. 02 §10 „Direktzugriff mit Runtime-DB-Rolle" und „Falscher Tenant in Body/URL/Cache" bestehen für alle in M6b neuen Tabellen und Endpunkte, auch nach Connection-Pool-Wiederverwendung.
- **Tests:** positiv/negativ/konkurrierend wie in den Szenarien; zusätzlich Invarianten aus Kap. 04 §4.5 „Grantwiderruf sofort wirksam" und „Nicht überlappende aktive Tarif-/Policy-/Dienstversion" mit je zwei parallelen Transaktionen. Besteht PH-AT-002, PH-AT-003, PH-AT-016, E2E-085, E2E-092, E2E-094, E2E-095.
- **Abhängigkeiten:** intern: WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M6b-08, WP-M6b-09, WP-M6b-10, WP-M6b-11, WP-M6b-12, WP-M6b-13, WP-M6b-14, WP-M6b-15, WP-M6b-16, WP-M6b-17, WP-M6b-18, WP-M6b-19, WP-M6b-20, WP-M6b-21, WP-M6b-22, WP-M6b-23, WP-M6b-24, WP-M6b-25, WP-M6b-26, WP-M6b-27, WP-M6b-28, WP-M6b-29, WP-M6b-30, WP-M6b-31, WP-M6b-32, WP-M6b-33.
  - extern: WP-M6a-07, WP-M6a-13, WP-M6a-14 (Zweibetreiberreise über Saga und internen Zweitbetreiber-Adapter für PH-AT-002/003 und E2E-085)
  - extern: WP-M6c-18 (Auslauf- und Abrechnungsregel der eingefrorenen Vertragsrevision für PH-AT-003)
- **Entscheidungen:** D-01, D-03, D-04, D-08 – blockieren nur die Produktivschaltung; die Abnahme erfolgt in der Sandbox.
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests mit Führung in M6a (Saga, Adapter) und M6c (Checkout, Clearing); Last-, Fault-Injection- und Restore-Tests der Föderation (kein WP dieser Einheit, siehe OP-27).

## Offene Punkte

- **OP-01 – Endpunkte der Kooperationsverwaltung fehlen.** Weder `openapi.yaml` noch Kap. 05 §5.7 nennen Pfade für Verträge, Grants, Pause, Widerruf und Partnerzugänge; nur UI-18 und die Tabellen `partner_contract_version`, `cooperation_grant` existieren. Betrifft `LH-I.4-MUL-009`, `LH-I.4-MUL-014`, `LH-6.17-INT-011`. Die Pfade werden in WP-M6b-01 vorgeschlagen und sind freizugeben.
- **OP-02 – Tabellen ohne Namen im Pflichtenheft.** Für Vertragsstatus/Genehmigung (der Vertrag selbst ist per Trigger unveränderlich, Kap. 04 §4.5), Webhook-Zustellversuche/Dead-Letter, Importlauf/Importfehler, Dienstauftritt (`LH-I.4-MUL-017`), Supportzugriff (`LH-I.4-MUL-018`), Verarbeitungsverzeichnis (`LH-I.4-MUL-023`) und Exportprofil (`LH-6.17-INT-014`) nennt weder `kernschema.sql` noch Kap. 04 §4.4 Tabellen. `cooperation_grant` hat keine Grantrevision, kein Pausenintervall und keine Begrenzung auf Journey-/Segment-IDs, obwohl Kap. 02 §5 dies voraussetzt.
- **OP-03 – Zuständigkeit für `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation`, `adapter_capability`.** Die Tabellen stehen in Kap. 04 §4.4 unter OPT/GEO/PTI bzw. CFG/SEC/INT; PTI und Capability Registry liegen in M6a. Dieser Plan migriert die vier Geo-/Feedtabellen in WP-M6b-02 und liest `adapter_capability` nur; die Aufteilung ist mit M6a abzustimmen, damit keine Tabelle doppelt oder gar nicht entsteht.
- **OP-04 – Überschneidung `LH-I.4-MUL-021` mit FED-013/026/027.** Kap. 02 §3 belegt den minimalen Betreiberpayload mit allen vier Anforderungen. Dieser Plan legt Projektion, Alias und Relay in WP-M6b-09 und den Transport in M6a; die Schnittlinie ist zu bestätigen.
- **OP-05 – Identität gemeinsamer Haltestellen.** `stop_revision` ist mandantenlokal mit `stop_id`; wie zwei Mandanten „dieselbe" Haltestelle führen (Plattformkatalog, globale opake Referenz, wer die Barrierefreiheitsangaben verantwortet) und welche „definierten Rechte" `LH-I.4-MUL-008` meint, ist nicht festgelegt.
- **OP-06 – Tarifgrenzen und Ausschlusszonen ohne Datenmodell.** `LH-I.4-MUL-012` nennt Überlappungen, Prioritäten, Tarifgrenzen, Übergabepunkte und Ausschlusszonen; `service_area` enthält nur Polygon und Gültigkeit. `area_overlapping_priority` hat die Ebene „Region", eine Tabelle für Regionen fehlt (vgl. M1 OP-07). Die Auswahlregel bei gleicher Priorität und die Bedeutung von „korrekt bewertet" (Preis, Zulässigkeit, Ranking) sind nicht definiert.
- **OP-07 – Rollenbegriffe weichen ab.** `LH-I.4-MUL-010` nennt Vermittler, Fahrtdurchführer, Fahrzeugsteller, Kostenträger; Kap. 03 §4.1 nennt `performing_carrier`, `seller`, `payment_recipient`, `invoice_issuer`, `vehicle_owner`, `cost_bearer`, `subcontractor`; `journey_leg` kennt nur `operator_ref`, `booking` keine Rolle. Abbildung, Pflichtrollen und der Begriff „Auftrag" (Buchung, Segment oder Reise) sind festzulegen; „wirtschaftliche Zuordnung" ist nicht definiert.
- **OP-08 – Ebenen der Konfigurationshierarchie.** `LH-I.4-MUL-015` nennt globale Vorlage, Mandant, Gebiet, Dienst, Fahrzeug; Kap. 01 §1.6 zusätzlich „konkretes Produkt"; `kataloge.csv` führt für die Föderationsparameter die Ebenen Kooperation, Partner, Vertrag, Übergabepunkt, Region/Transfer. Deren Platz in der Präzedenzkette ist offen (vgl. M1 OP-22). Abgrenzung zu `LH-I.7-CFG-005` (M1) ist nur über die „definierten Überschreibungen" und die vollständige Herkunftskette möglich.
- **OP-09 – Maßgeblicher Zeitpunkt bei zeitlicher Aktivierung.** Für `LH-I.4-MUL-016` ist nicht festgelegt, ob eine am Sonntag gestellte Anfrage für eine Fahrt am Montag den alten oder neuen Wert nutzt (Anfragezeit oder Leistungszeit). E2E-096 sagt nur „Sonntagsfahrten unverändert; künftige Angebote nutzen neuen Wert". Abgrenzung zu CFG-008/021 (M8) ist zu bestätigen.
- **OP-10 – Break-glass-Parameter.** Für `LH-I.4-MUL-018` fehlen Maximaldauer, freigebende Rolle, Umfang (lesen/schreiben), Benachrichtigung des betroffenen Mandanten und die Form der „nachträglichen Auditkontrolle". Was „Rollen delegieren" konkret umfasst (welche Rollen, über Mandantengrenzen?), ist nicht beschrieben.
- **OP-11 – Umfang und Format des Mandantenexports.** `LH-I.4-MUL-019` verlangt „vollständig" und „offene, dokumentierte Formate"; die Abnahme nennt nur Stammdaten und Buchungsdaten. Ob Finanz-, Personal-, Beleg- und Auditdaten sowie Objektspeicherinhalte dazugehören, welches Format gilt und über welchen Endpunkt der Export läuft, ist offen. „Entkoppelter Testmandant" ist nicht definiert.
- **OP-12 – Wertelisten für den Datenschutzbericht.** `LH-I.4-MUL-023` nennt Verarbeitungsrollen, Vereinbarungen und Datenempfängerkategorien ohne Werteliste; das Verhältnis zum Dateninventar aus M1 (SEC-004) ist nicht beschrieben.
- **OP-13 – „Unmittelbar unbrauchbar".** `LH-6.17-INT-011` nennt keine maximale Wirkverzögerung nach Entzug; ebenso fehlen Quotenwerte und der Scope-Katalog für Partner. Kap. 02 §5 verlangt Online-Prüfung nur für geschäftswirksame Operationen; ob reine Leseabrufe ebenfalls online geprüft werden, ist festzulegen.
- **OP-14 – Importtypen und Verwaltungsendpunkte.** `LH-6.17-INT-009` nennt keine Importobjekte; `LH-6.17-INT-008` keine Endpunkte für die Feedkonfiguration. „Geeignete Echtzeitdatenadapter" ist über GTFS-RT hinaus nicht bestimmt (NeTEx/SIRI/VDV hängen an D-08).
- **OP-15 – CRM-Integration unbestimmt.** `LH-6.17-INT-010` nennt CRM-Provider; das Pflichtenheft beschreibt weder CRM-Datenfluss noch Zweck oder Zielsystem. Geplant ist nur der Port mit Referenzadapter.
- **OP-16 – Zielformate der Mobilitätsdatenexporte.** `LH-6.17-INT-014` nennt Mobilitätsdatenplattformen und regionale Sammelstellen ohne Empfänger, Format oder Pflichtinhalt; Abgrenzung zu den Exporten des Reportingstroms in M8 und zu AT-25 (PBefG-Datenexport, Gate M5) ist zu klären.
- **OP-17 – Ereignistypen der Webhooks.** `LH-6.17-INT-005` nennt vier Ereignisklassen ohne Typenliste, Payload oder Empfängerkreis (eigene Systeme des Mandanten, Partner oder beide). Der „dokumentierte Testevent" ist nicht spezifiziert.
- **OP-18 – Retry-Staffel und Hold-Fristen sind Planungsdefaults.** Die Outbound-Retry-Staffel (1 s, 5 s, 30 s, 2 min, 10 min, bis 24 h), das Signaturfenster ±300 s, die Parallelbetriebsfrist von sechs Monaten und die Download-Gültigkeit von 60 s sind Vorschläge des Pflichtenhefts und vor Produktivschaltung je Partnervertrag zu bestätigen.
- **OP-19 – Verteilungsregel für gemeinsame Ressourcen.** `LH-I.4-MUL-020` verlangt „vertragliche Schlüssel"; zulässige Treiber, Kostenarten und die Behandlung nicht abzugsfähiger Vorsteuer hängen an D-07. `cost_split_rule` hat die Ebene „Vertrag", aber kein Schema. Das Intercompany-Belegmodell ist mit M6c abzustimmen.
- **OP-20 – Verbundkennzahlen und Aufgabenträgerkontext.** Für `LH-I.4-MUL-022` ist nicht festgelegt, welche Kennzahlen „Verbundkennzahlen" sind, wie der Aufgabenträger seinen Berichtsgrant erhält (eigener Vertrag je Betreiber?) und ob `kpi_aggregation_min_n` (Ebene Mandant) im Verbund vom strengsten beteiligten Mandanten bestimmt wird.
- **OP-21 – Markenumfang.** `LH-I.4-MUL-017` nennt „Markenoberflächen" ohne Umfang (Logo, Farben, Domain, App-Name, E-Mail-Absender). Ob eine eigene Domain je Dienst verlangt ist, bleibt offen; dieser Plan liefert Marke innerhalb der gemeinsamen Oberfläche.
- **OP-22 – Widerruf ohne eigene Anforderung in M6b.** Kap. 02 §5 und PH-AT-003 verlangen Widerruf mit Auslaufregeln und verweisen auf I.9/COM-011 (M6c). WP-M6b-07 liefert den technischen Mechanismus als Enabler; die fachliche Abnahme der Auslaufregel liegt bei M6c.
- **OP-23 – Rolle und Kontext des organisationsübergreifenden Dispatchers.** `LH-I.4-MUL-011` nennt einen „organisationsübergreifenden Dispatcher" und ein „Verbund-Dashboard"; Kap. 04 §4.6 verlangt, dass Föderationsprojektionen im Kontext eines eigenen Verkaufs-/Verbundmandanten verarbeitet werden. Wer diesen Verbundmandanten führt, wie die Rolle vergeben wird und ob sie in der Rechtematrix aus M1 vorgesehen ist, steht nicht im Pflichtenheft. Gleiches gilt für die Rolle des Aufgabenträgers (`LH-I.4-MUL-022`).
- **OP-24 – Grundmasken der genutzten Oberflächen.** Der Meilensteinplan nennt für M6 nur UI-08, UI-16, UI-18 sowie UI-02/03; dieser Plan nutzt zusätzlich UI-07 (M3), UI-15 (M4), UI-17 und UI-20 (Basis M1) und UI-19. UI-19 ist im Meilensteinplan keinem der Meilensteine M1–M6 zugeordnet; `LH-I.4-MUL-022` und `LH-6.17-INT-014` brauchen dort eine Grundmaske. Die WP-Planung von M4 sieht den Kennzahlenteil von UI-19 als Grundausbau vor; dieser Plan setzt darauf auf (EXTERN[M4]). Das ist im Meilensteinplan zu bestätigen.
- **OP-25 – Abweichungen zwischen Kernschema und Text.** (a) `membership` hat kein Gültigkeitsende, obwohl Kap. 02 §2 die Zuordnung „zeitlich begrenzt" nennt (`LH-I.4-MUL-018`). (b) `outbox` führt `aggregate_revision`, Kap. 02 §8 nennt `aggregate_version`; `correlation_id`, `recorded_at` und freigegebene Empfänger fehlen im Kernschema (`LH-I.4-MUL-013`, `LH-6.17-INT-005`). (c) `partner_contract_version` und `cooperation_grant` führen `valid_to`, Kap. 02 §5 nennt `valid_until`. (d) Die Ergänzungstabellen aus Kap. 04 §4.4, die M6b nur nutzt (u. a. `webhook_subscription`, `export_job`, `business_alert`, `vehicle_lease`, `vehicle_assignment`, `allocation_activation`, `aggregate_snapshot`), sind dort keinem Meilenstein zugeordnet; WP-M6b-02 prüft ihr Vorhandensein, die liefernde Einheit ist je Tabelle zu bestätigen.
- **OP-26 – Aufbewahrung der neuen Datenbestände.** Für Zustellversuche/Dead-Letter (`LH-6.17-INT-006`), isolierte Importfehler (`LH-6.17-INT-009`), Exportpakete (`LH-I.4-MUL-019`, `LH-6.17-INT-014`), Supportzugriffsprotokolle (`LH-I.4-MUL-018`), Traces (`LH-6.17-INT-015`) und gesperrte Partnerprojektionen nach Grantentzug (`LH-I.4-MUL-021`) nennt das Pflichtenheft keine Fristen; sie hängen an D-11. Gebaut wird mit konfigurierbarer Aufbewahrungsklasse.
- **OP-27 – Last-, Fault-Injection- und Restore-Nachweis.** M5 liefert diese Nachweise nur für den P1-Stand. Für Grant-Gate (Online-Prüfung je Partneroperation, `LH-I.4-MUL-009`), Webhook-Zustellung (`LH-6.17-INT-006`) und Listenabruf (`LH-6.17-INT-007`) gibt es in M6 kein Arbeitspaket mit Lastziel; Kap. 08 §8.6 nennt nur vorläufige Ziele. Zu entscheiden ist, ob M6 ein eigenes Lastpaket erhält oder M8 (NFR) dies übernimmt.
- **OP-28 – Sprachen je Dienst.** `LH-I.4-MUL-017` verlangt Sprachen je Dienst; das Pflichtenheft nennt weder die Pflichtsprachen noch die Fallbackreihenfolge noch, ob Rechtstexte in jeder aktivierten Sprache vorliegen müssen, bevor ein Dienst veröffentlicht werden darf.
- **OP-28 – Reihenfolgekonflikt: zeitgesteuerte Ausführung für WP-M6b-06.** WP-M6b-06 setzt für Beginn und Ende der Partnerpause eine „zeitgesteuerte Ausführung" aus M1 voraus. Kein WP aus M0–M6 liefert einen allgemeinen zeitgesteuerten Ausführungskern; das einzige liefernde WP ist WP-M8d-34 (Meilenstein M8, später als M6). Zu entscheiden: Ausführungskern nach M1 vorziehen, in WP-M6b-06 einen eigenen Minimalmechanismus bauen (z. B. auf `operation` mit Lease/Fencing aus WP-M1-15) oder die Pausengrenzen rein lesend über das Gültigkeitsintervall prüfen.
- **OP-29 – Reihenfolgekonflikt: Zyklus WP-M6b-06 ↔ M6a.** WP-M6b-06 nennt den Anbieterfilter (WP-M6a-04, WP-M6a-09) und bestätigte Segmente (WP-M6a-13, WP-M6a-14) als Abhängigkeit; WP-M6a-04 und WP-M6a-09 hängen ihrerseits von WP-M6b-06 ab (Anbieterfilter-Port), WP-M6a-13/14 mittelbar über WP-M6a-09. Die M6a-Seite ist Verbraucher des Ports; aufzulösen durch Bau gegen Port-Vertrag/Stub (WP-M6b-01, WP-M6a-01) und Verlagerung des Integrationsnachweises nach WP-M6b-34.
- **OP-30 – Reihenfolgekonflikt: Zyklus WP-M6b-07 ↔ M6a.** WP-M6b-07 nennt die Grantrevisionsprüfung der Saga und den In-flight-Abgleich (WP-M6a-13, WP-M6a-14, WP-M6a-15) als Abhängigkeit; WP-M6a-13 hängt über WP-M6a-04 von WP-M6b-07 ab (WP-M6a-14/15 mittelbar). Auflösung wie OP-29.
- **OP-31 – Reihenfolgekonflikt: Zyklus WP-M6b-09 ↔ M6a.** WP-M6b-09 nennt Segmentdaten und Payload-Übergabe im Hold-/Confirm-Aufruf (WP-M6a-12, WP-M6a-13) als Abhängigkeit; WP-M6a-12 hängt direkt von WP-M6b-09 ab (Projektionsbaustein), WP-M6a-13 über WP-M6a-12. Ohne Zyklus bleibt nur der Tabellenbezug `journey_leg` (WP-M6a-02). Auflösung wie OP-29.
- **OP-32 – Reihenfolgekonflikt: Zyklus WP-M6b-11 ↔ M6a.** WP-M6b-11 nennt den Aufruf des Gebietsregel-Ports im Reisegraphen (WP-M6a-08, WP-M6a-09) als Abhängigkeit; beide hängen direkt von WP-M6b-11 ab. Auflösung wie OP-29.
- **OP-33 – Reihenfolgekonflikt: Zyklus WP-M6b-27 ↔ M6a.** WP-M6b-27 nennt die Verbraucher der Feeddaten und Abweichungsereignisse (WP-M6a-19, WP-M6a-26, WP-M6a-27) als Abhängigkeit; alle drei hängen direkt von WP-M6b-27 ab. Auflösung wie OP-29.
