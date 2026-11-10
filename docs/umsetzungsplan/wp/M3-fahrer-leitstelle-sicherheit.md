# M3 – Fahrer, Leitstelle, Fahrzeugsicherheit

- **Meilenstein:** M3 – Fahrer, Leitstelle, Fahrzeugsicherheit
- **Phase:** P1 (alle Anforderungen MUSS, Originalphase und -priorität unverändert)
- **Anforderungen:** 48 (DRV 8, OPS 6, FLT 4, CHK 16, DMG 12, AUT 2)
- **Arbeitspakete:** 56 (44 mit Anforderungen, 12 Enabler)

**Ziel:** Nach M3 meldet sich ein Fahrer in der Driver-App an, führt die versionierte Pflichtprüfung durch und erhält die Einsatzfreigabe ausschließlich nach Serverbestätigung; er arbeitet seine Stoppliste ab und meldet Einstieg, Ausstieg und No-Show auch nach einem Netzverlust genau einmal wirksam. Ein kritischer Mangel oder sicherheitskritischer Schaden sperrt das Fahrzeug synchron im selben Commit, in dem die Disposition ihre Zusage prüft; die Wiederfreigabe verlangt Reparaturnachweis, berechtigte technische Freigabe und Folgeprüfung. Die Leitstelle sieht Fahrzeuge mit Standortalter, Aufträge, Prüfstatus und Schäden, kann sperren, umplanen und für Fahrgäste buchen, und ein Fahrzeugausfall erzeugt genau einen nachvollziehbaren Störungsablauf.

**Hinweis zum Zuschnitt:** M3 braucht aus M2 den Fahrtenplan (`vehicle_run`, `route_stop`, `booking`), den Hold-/Commit-Pfad und die Betriebsnachrichten. Bis diese vorliegen, werden die M3-WPs gegen Fixtures des Akzeptanztestgerüsts (WP-M0-22) entwickelt; die Abnahme am echten Fahrtenplan ist je WP als EXTERN-Abhängigkeit ausgewiesen. Drei Fähigkeiten, die M3 braucht, liefert M2 ausdrücklich nicht, weil ihre Anforderungen P2 sind (M2, Offener Punkt 44): Boarding-Code (`LH-6.4-BKG-019`), Bearbeitung bestätigter Buchungen (`LH-6.4-BKG-006`) und No-Show-Abrechnungsregel (`LH-6.4-BKG-015`); ebenso fehlt die Buchung für Dritte (`booking_subject=DELEGATED`, M7). Die betroffenen WPs (WP-M3-29, WP-M3-30, WP-M3-39) liefern deshalb nur den für ihre P1-Abnahme nötigen Mindestumfang selbst und weisen den Rest als Offenen Punkt aus (OP-18, OP-21, OP-30). Ereignisnamen und Tabellennamen, die nicht in Kap. 04 §4.4 stehen, sind Arbeitsnamen und werden in WP-M3-01 verbindlich festgelegt. Planungsdefaults des Pflichtenhefts sind als solche gekennzeichnet.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M3-01 | Detailverträge Fahrer, Leitstelle, Flotte/Sicherheit | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M1-02, WP-M1-03, WP-M1-07, WP-M2-01 |
| WP-M3-02 | Migration der M3-Tabellen mit RLS und Unveränderlichkeit | – (Enabler) | M | WP-M3-01, WP-M0-15, WP-M0-16, WP-M0-18, WP-M1-01, WP-M2-02, WP-M2-16 |
| WP-M3-03 | Fahrzeugstamm | 1 | M | WP-M3-02, WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-05, WP-M1-43, WP-M1-34 |
| WP-M3-04 | Kapazitäts- und Stellplatzkonfiguration | 1 | M | WP-M3-03, WP-M2-14 |
| WP-M3-05 | Fahrerstamm und Qualifikationsrevisionen | – (Enabler) | M | WP-M3-02, WP-M3-03, WP-M1-06, WP-M1-07, WP-M1-31 |
| WP-M3-06 | Fahrzeugpool-Zuordnung zu Diensten | 1 | M | WP-M3-03, WP-M1-09, WP-M1-25, WP-M2-04, WP-M2-15, WP-M2-02 |
| WP-M3-07 | Fahrer-/Fahrzeugzuweisung mit eindeutigen Schichtgrenzen | 1 | M | WP-M3-03, WP-M3-05, WP-M1-43, WP-M1-18, WP-M4-04, WP-M4-13, WP-M4-14, WP-M4-65 |
| WP-M3-08 | Sperr- und Freigabekern (Safety-Revision, `resource_release`) | – (Enabler) | L | WP-M3-02, WP-M3-03, WP-M3-07, WP-M1-14, WP-M1-12, WP-M1-17, WP-M2-22, WP-M2-23, WP-M2-02 |
| WP-M3-09 | Readiness-Gate vor Bereitstatus und Disposition | 1 | M | WP-M3-08, WP-M3-14, WP-M3-25, WP-M3-36, WP-M1-19, WP-M1-21, WP-M2-15 |
| WP-M3-10 | Checklistenvorlagen und Prüfelemente | 1 | M | WP-M3-02, WP-M3-03, WP-M1-22, WP-M1-11 |
| WP-M3-11 | Pflichtvorlagen Fahrzeugtechnik und Fahrgastraum | 2 | S | WP-M3-10, WP-M3-13, WP-M3-14 |
| WP-M3-12 | Barrierefreiheits-Prüfpunkte und eingeschränkte Freigabe | 1 | M | WP-M3-04, WP-M3-08, WP-M3-10, WP-M3-14, WP-M3-36, WP-M2-14, WP-M2-15 |
| WP-M3-13 | Prüflauf-Start mit exakt freigegebener Vorlage | 1 | M | WP-M3-07, WP-M3-10, WP-M3-25 |
| WP-M3-14 | Prüfabschluss und unveränderlicher Prüfnachweis | 1 | M | WP-M3-08, WP-M3-13, WP-M1-13, WP-M1-14, WP-M1-12 |
| WP-M3-15 | Prüfentwurf und versionierte Korrektureinträge | 1 | S | WP-M3-14 |
| WP-M3-16 | Kritischer Mangel sperrt synchron | 1 | M | WP-M3-08, WP-M3-14, WP-M3-36, WP-M3-52 |
| WP-M3-17 | Schichtstartwerte mit Plausibilitätsprüfung | 1 | S | WP-M3-14 |
| WP-M3-18 | Technikbereitschaft und Technikereignis | 1 | S | WP-M3-14, WP-M3-36, WP-M3-52 |
| WP-M3-19 | Einsatzbereitschaft und Qualifikationsprüfung | 1 | M | WP-M3-05, WP-M3-08, WP-M3-14 |
| WP-M3-20 | Abweichung mit Foto und Schadenvorgang aus Prüfschritt oder Störung | 2 | M | WP-M3-14, WP-M3-43, WP-M3-44, WP-M3-53 |
| WP-M3-21 | Bekannte Altschäden anzeigen und quittieren | 2 | M | WP-M3-13, WP-M3-43, WP-M3-50 |
| WP-M3-22 | Fahrzeugwechsel in laufender Schicht | 1 | M | WP-M3-07, WP-M3-08, WP-M3-09, WP-M3-13, WP-M3-36 |
| WP-M3-23 | Prüfstatus in Fahrer-App und Leitstelle | 1 | M | WP-M3-08, WP-M3-14, WP-M3-35, WP-M3-36 |
| WP-M3-24 | Driver-App-Basis: Anmeldung, verschlüsselte Outbox, Synchronisation | – (Enabler) | L | WP-M3-01, WP-M0-06, WP-M0-28, WP-M1-04, WP-M1-10, WP-M1-13 |
| WP-M3-25 | Dienstanmeldung und Schicht-/Fahrzeugzuordnung | 1 | M | WP-M3-07, WP-M3-24, WP-M1-06, WP-M1-08, WP-M2-16 |
| WP-M3-26 | Stoppliste des Fahrers | 1 | M | WP-M3-25, WP-M1-08, WP-M1-31, WP-M2-14, WP-M2-16, WP-M2-28, WP-M4-61, WP-M4-62 |
| WP-M3-27 | Planänderungen anzeigen und kontrolliert übernehmen | 1 | M | WP-M3-26, WP-M3-35, WP-M2-28, WP-M2-32 |
| WP-M3-28 | Navigation auf Basis der gültigen Stoppliste | 1 | S | WP-M3-26, WP-M0-30, WP-M2-05 |
| WP-M3-29 | Fahrerereignisse: Ankunft, Einstieg, No-Show, Ausstieg | 1 | L | WP-M3-08, WP-M3-24, WP-M3-26, WP-M1-13, WP-M1-08, WP-M2-23, WP-M2-25, WP-M2-16 |
| WP-M3-30 | Check-in mit Codeprüfung und Fahrgastliste | 1 | M | WP-M3-02, WP-M3-29, WP-M2-23, WP-M2-26 |
| WP-M3-31 | Fahrzeug- und Dienststatus | 1 | M | WP-M3-08, WP-M3-25, WP-M2-15, WP-M2-16 |
| WP-M3-32 | Unterstützungshinweise nur für zugeordnetes Personal | 1 | S | WP-M3-26, WP-M1-31, WP-M2-14, WP-M2-23 |
| WP-M3-33 | Offline-Prüfung und Offline-Entwürfe | – (Enabler) | M | WP-M3-14, WP-M3-16, WP-M3-24, WP-M3-44 |
| WP-M3-34 | Positionserfassung und Standortalter | – (Enabler) | M | WP-M3-02, WP-M3-24, WP-M3-25, WP-M1-33, WP-M1-32 |
| WP-M3-35 | Live-Aktualisierungskanal für Leitstelle und Fahrer | – (Enabler) | M | WP-M3-01, WP-M1-14, WP-M1-10, WP-M1-43, WP-M2-28 |
| WP-M3-36 | Leitstelle Livebetrieb: Fahrzeuge, Position, Verfügbarkeit | 1 | M | WP-M3-08, WP-M3-31, WP-M3-34, WP-M3-35, WP-M1-08, WP-M1-43, WP-M2-16, WP-M2-40 |
| WP-M3-37 | Auftragsliste mit Suche | 1 | M | WP-M3-36, WP-M1-31, WP-M1-02, WP-M2-23, WP-M2-14 |
| WP-M3-38 | Fahrzeugbezogene Stoppreihenfolge, Prognose und Besetzung | 1 | M | WP-M3-29, WP-M3-36, WP-M2-16, WP-M2-19, WP-M4-61, WP-M4-62 |
| WP-M3-39 | Buchung durch die Disposition anlegen, ändern, stornieren | 1 | L | WP-M3-02, WP-M3-08, WP-M3-37, WP-M1-07, WP-M1-12, WP-M1-13, WP-M1-28, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-31, WP-M2-25, WP-M2-34 |
| WP-M3-40 | Manuelle Umplanung mit Begründung und Plan-CAS | – (Enabler) | M | WP-M3-02, WP-M3-08, WP-M3-38, WP-M1-21, WP-M1-12, WP-M2-16, WP-M2-19, WP-M2-22, WP-M2-28 |
| WP-M3-41 | Operative Fahrzeugsperre durch die Leitstelle | 1 | S | WP-M3-08, WP-M3-36 |
| WP-M3-42 | Überfälligkeit und ausstehende Informationen | 1 | M | WP-M3-27, WP-M3-29, WP-M3-34, WP-M3-36, WP-M3-37, WP-M3-52, WP-M1-21, WP-M1-15 |
| WP-M3-43 | Schadenakte: Meldung, Pflichtdaten, Fallnummer | 2 | M | WP-M3-02, WP-M3-03, WP-M1-13, WP-M1-08, WP-M1-12 |
| WP-M3-44 | Mobile Schadenerfassung mit Fotoevidenz | 1 | M | WP-M3-24, WP-M3-43, WP-M1-11, WP-M4-21 |
| WP-M3-45 | Schadenklassifikation und Triage | 1 | M | WP-M3-08, WP-M3-43, WP-M3-52, WP-M1-21, WP-M1-07 |
| WP-M3-46 | Sicherheitskritische Meldung: Sofortsperre und priorisierter Alarm | 1 | M | WP-M3-08, WP-M3-45, WP-M3-52 |
| WP-M3-47 | Statusautomat der Schadenakte | 1 | S | WP-M3-43, WP-M1-17 |
| WP-M3-48 | Unveränderliche Historie von Meldungen und Anhängen | 1 | M | WP-M3-43, WP-M3-44, WP-M1-12, WP-M1-33 |
| WP-M3-49 | Schadenliste der Leitstelle mit Filtern | 1 | M | WP-M3-43, WP-M3-45, WP-M3-50, WP-M1-09, WP-M1-02, WP-M1-43 |
| WP-M3-50 | Vertraulichkeit der Schadenakte | 1 | M | WP-M3-43, WP-M3-44, WP-M1-31, WP-M1-08, WP-M1-12 |
| WP-M3-51 | Reparaturauftrag und Wiederfreigabe mit Rollenprüfung | – (Enabler) | M | WP-M3-08, WP-M3-14, WP-M3-45, WP-M3-47, WP-M1-07, WP-M1-12 |
| WP-M3-52 | Alarm-Grundlage: Alarm, Dedupe, Quittierung | – (Enabler) | M | WP-M3-02, WP-M3-35, WP-M1-14, WP-M1-21, WP-M1-15, WP-M1-43 |
| WP-M3-53 | Regelbasierter Workflow: Incident bei Fahrzeugausfall | 1 | M | WP-M3-02, WP-M3-08, WP-M3-31, WP-M3-52, WP-M1-14, WP-M1-15 |
| WP-M3-54 | Störungsablauf: betroffene Fahrten, Ersatzvorschläge, Fahrgastauswirkung | 1 | L | WP-M3-22, WP-M3-40, WP-M3-41, WP-M3-46, WP-M3-53, WP-M2-17, WP-M2-21, WP-M2-26, WP-M2-27, WP-M2-31, WP-M2-34 |
| WP-M3-55 | Ablaufprotokoll automatisch ausgeführter Vorgänge | 1 | S | WP-M3-02, WP-M3-53, WP-M1-12, WP-M1-02 |
| WP-M3-56 | M3-Gate-Abnahmesuite | – (Enabler) | M | alle WPs dieser Datei, WP-M0-22, WP-M0-32, WP-M0-33, WP-M2-16, WP-M2-23, WP-M2-26, WP-M2-34, WP-M2-48, WP-M4-21 |

## Arbeitspakete

### WP-M3-01 – Detailverträge Fahrer, Leitstelle, Flotte/Sicherheit
- **Ziel:** Alle Schnittstellen, Zustandsautomaten und Schemas, die M3 braucht, liegen geprüft vor, bevor Fachcode entsteht (Leitlinie 7 „Vertrag vor Code").
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Dateien unter `api/` für die Endpunkte aus Kap. 05 §5.7: `/v1/operator/vehicles`, `/v1/operator/incidents`, `/v1/runs/{id}/revisions`, `/v1/driver/shifts/{id}/run`, `/v1/inspections`, `/v1/inspections/{id}/submit`, `/v1/defects`, `/v1/defects/{id}/triage`, `/v1/repair-orders`, `/v1/vehicles/{id}/release`; Übernahme von `/v1/driver/events` (`recordDriverEvent`, Schemas `DriverEvent`, `DriverEventResult`) aus `openapi.yaml`; Ergänzungsvorschläge für die in den Offenen Punkten genannten Lücken (OP-03, OP-04, OP-13).
  - Backend: Zustandsautomaten als Tabellen (Zustand, Ereignis, Folgezustand, Rolle, Vorbedingung) für Prüflauf, Schadenakte, Fahrzeug-/Dienststatus, Einsatzfreigabe, Reparaturauftrag, Incident.
  - Ereignisse/Outbox: Ereigniskatalog M3 mit `event_type`, `schema_version`, Payload-JSON-Schema und Minimierungsregel (keine Diagnosen, keine Fahrgast-PII).
  - Datenbank: DDL-Entwurf der Tabellen aus Kap. 04 §4.4 (Zeilen DRV/FLT, CHK, DMG, Alerts) sowie der dort fehlenden, in WP-M3-02 aufgezählten Zusatztabellen als Eingabe für WP-M3-02.
  - Backend: Ergänzung der Rechtematrix um die M3-Rollen und -Aktionen (Fahrer, Disposition/Leitstelle, Flottenverwaltung, Werkstatt, technische Freigabe, Betriebsleitung, Prüfer) je Endpunkt, als Eingabe für das Rollenmodell aus WP-M1-07; Rollenbezeichnungen sind Arbeitsnamen bis zur Lieferung der Betreiberrollen (D-11).
  - API: je mutierendem Endpunkt Festlegung von `Idempotency-Key`, `If-Match`/ETag, Fehlercodes (`application/problem+json`, 404 statt Fremdobjekt, 403 bei fehlendem Scope, 412/428) und Listenform `{items,next_cursor,as_of}` nach Kap. 05 §5.1.
  - Backend: Abstimmung mit M2 – gemeinsames Protokoll des Änderungsstroms (WP-M2-28) und des Live-Kanals (WP-M3-35), Übergabe des Leseports `ResourceAvailability` (WP-M2-02) an die führenden M3-Module, Sperrreihenfolge im Hold-Commit (WP-M2-22).
- **Detailverträge vorab:** Dieses WP erstellt sie; Review durch Fach-, Sicherheits- und Testrolle nach dem Prozess aus WP-M0-31.
- **Akzeptanzkriterien:**
  1. Jede der 48 Anforderungen ist in `arbeitspakete.csv` mit einem Detailvertrag (Datei und Abschnitt) verknüpft.
  2. Alle neuen OpenAPI-Dateien bestehen `validate_artifacts.py` und die Vertragsprüfung der CI (parsbar, `$ref` auflösbar, `operationId` eindeutig, Beispiele gültig).
  3. Jeder Zustandsautomat nennt für jeden Übergang die berechtigte Rolle und mindestens einen unzulässigen Übergang als Negativbeispiel.
  4. Die Offenen Punkte OP-01 bis OP-34 sind je mit Entscheidung, Verantwortlichem oder dokumentierter Annahme versehen.
  5. Für jeden M3-Endpunkt nennt der Vertrag die berechtigten Rollen, das Verhalten bei fremdem Mandanten bzw. fremdem Objekt (404), bei manipuliertem `tenant_id` im Body (keine zusätzlichen Rechte) und bei wiederholtem Idempotenzschlüssel mit anderem Inhalt (`409 IDEMPOTENCY_CONFLICT`).
  6. Der Ereigniskatalog ist mit M2 abgeglichen: kein Ereignis und keine Tabelle wird von zwei Modulen geschrieben; die Zuständigkeit für `booking_change`, `route_stop` und den Plan-Commit ist je Operation festgelegt.
- **Tests:** Positiv: CI-Vertragsprüfung grün. Negativ: bewusst fehlerhafte Beispielnutzlast je Endpunkt wird vom Schema abgewiesen.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess und Vertragsprüfung in CI)
  - extern: WP-M1-02, WP-M1-03, WP-M1-07 (Fehlermodell, ETag-/Idempotenzkonventionen, OpenAPI je Modul, Rollenmodell)
  - extern: WP-M2-01 (Detailverträge und Zustandsautomaten M2 für Fahrtenplan, Buchung und Änderungsstrom)
- **Entscheidungen:** D-11 (berechtigte Freigaberollen, Notbetrieb) und D-09 (Navigation, Telematik) fließen ein; blockieren nicht den Bau, nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Implementierung der Endpunkte; Verträge für P2-Anforderungen von DRV, OPS, CHK, DMG, AUT (M7, M8).

### WP-M3-02 – Migration der M3-Tabellen mit RLS und Unveränderlichkeit
- **Ziel:** Alle M3-Tabellen existieren als Flyway-Migration, sind mandantenisoliert und schützen unveränderliche Nachweise auf Datenbankebene.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: neue Tabellen nach Kap. 04 §4.4: `driver`, `qualification_revision`, `vehicle_lease`, `vehicle_assignment`, `position_sample`, `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer`, `defect_case`, `defect_evidence`, `repair_order`, `vehicle_release_decision`, `alert_policy_revision`, `business_alert`, `alert_acknowledgement`. Jeweils Primärschlüssel `(tenant_id,id)`, zusammengesetzte Fremdschlüssel, `revision`, `created_at`, Urheber, RLS-Policy `tenant_isolation` (USING und WITH CHECK). Erweiterung der Kerntabellen `vehicle`, `staff_shift`, `resource_release`, `vehicle_run` nur additiv (expand/contract).
  - Datenbank: neu zu ergänzende Tabellen, die weder `kernschema.sql` noch Kap. 04 §4.4 nennt (Arbeitsnamen, in WP-M3-01 festzulegen, siehe OP-01, OP-02, OP-32): Sperrgründe je Fahrzeug bzw. Dienst (Grund, Quelle, Aufhebungsrecht, gesetzt/aufgehoben), Statushistorie Fahrzeug-/Dienststatus, Quittierung bekannter Schäden am Prüflauf, Korrektur-/Ergänzungseinträge zur Prüfung, Revisionen und Statushistorie der Schadenakte, Incident mit Verknüpfungen, Workflow-Regelrevision, Ablaufprotokoll, bestätigte Planrevision je Fahrer, Boarding-Code je Buchung (nur Hash). Gleiche Konventionen wie oben (Mandantenschlüssel, zusammengesetzte FKs, RLS, Revision, Urheber).
  - Datenbank: `booking_change` (Kap. 04 §4.4, Zeile BKG) wird hier angelegt, weil M2 sie nicht liefert (M2 Offener Punkt 22/44); Schreibrecht ausschließlich für das Modul `booking-dispatch`.
  - Datenbank: fehlende Fremdschlüssel des Kernschemas additiv ergänzen: `staff_shift.driver_ref` → `driver`, `resource_release.inspection_ref` → `inspection_run`; Umgang mit `staff_shift.roster_revision NOT NULL` vor M4 laut OP-07.
  - Datenbank: Trigger `reject_mutation()` auf `inspection_answer`, auf abgeschlossene `inspection_run`, auf `defect_evidence` und `vehicle_release_decision`; versionierte CHECKs für die Zustandswerte aus WP-M3-01; Index `(tenant_id,vehicle_id,measured_at DESC)` auf `position_sample` und `(tenant_id,state,updated_at)` für Arbeitslisten (Kap. 04 §4.7).
  - Datenbank: Modulzugänge für `fleet-safety` und `staff-time` (ein Modul schreibt nur eigene Tabellen).
- **Detailverträge vorab:** DDL aus WP-M3-01; Entscheidung zu OP-01 (Sperrgründe), OP-02 (Incident-Tabelle), OP-07 (Modulzuordnung), OP-08 (Fahrzeugwechsel im Schema).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf dem Stand nach M1 fehlerfrei; Rollback-/Wiederholungslauf ist idempotent.
  2. Unter der Runtime-Rolle liefert jede neue Tabelle ohne Tenantkontext null Zeilen; ein Insert mit fremder `tenant_id` oder fremdem Fremdschlüssel scheitert.
  3. UPDATE und DELETE auf `inspection_answer` (nach Abschluss), `defect_evidence` und `vehicle_release_decision` sowie auf die append-only Zusatztabellen (Historien, Ablaufprotokoll, Korrektureinträge) scheitern mit SQLSTATE 23514; ein abgeschlossener `inspection_run` lässt sich nicht in einen offenen Zustand zurücksetzen.
  4. Der Architekturtest bestätigt, dass kein Modul fremde Tabellen schreibt.
  5. Jede neue Tabelle hat `ENABLE` und `FORCE ROW LEVEL SECURITY`; die Runtime-Rolle ist nicht Eigentümerin, hat kein TRUNCATE und keine Rechte auf Partitionen (Kap. 04 §4.6).
  6. Die M2-Fixtures für `vehicle`, `staff_shift` und `resource_release` (WP-M2-02) bleiben nach der Migration gültig oder werden durch ein mitgeliefertes Datenmigrationsskript überführt.
- **Tests:** Positiv: Migration und Fixture-Insert für Mandant A und B. Negativ: Cross-Tenant-FK, leerer Tenantkontext, Mutation unveränderlicher Zeilen. Konkurrierend: zwei parallele Transaktionen verschiedener Mandanten nach Pool-Wiederverwendung sehen nur eigene Zeilen.
- **Abhängigkeiten:** intern: WP-M3-01.
  - extern: WP-M0-15, WP-M0-16, WP-M0-18 (Kernschema als Flyway-Migration, DB-Rollen, Tenantkontext-Testharness)
  - extern: WP-M1-01 (RLS-Laufzeitbasis und Isolations-Testharness)
  - extern: WP-M2-02, WP-M2-16 (Referenzfixtures und Tabellen des Fahrtenplans (`route_stop`, `passenger_trip`), auf die M3-Tabellen verweisen)
- **Entscheidungen:** D-02 (Isolationsstufe) – RLS ist Planungsdefault; blockiert den Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** `charging_slot` (E-Mobilität, spätere Phase); Tabellen `route_stop`, `booking_change`, `passenger_trip` (M2); Partitionierung von `position_sample` (erst nach gemessenem Volumen).

### WP-M3-03 – Fahrzeugstamm
- **Ziel:** Fahrzeuge lassen sich mit Kennung, Betreiber, Typ, Kapazitäten, Ausstattung und Aktivstatus anlegen, ändern und stilllegen; ungültige Kapazitäten werden abgewiesen.
- **Anforderungen:** `LH-6.8-FLT-001`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`, Aggregat Vehicle mit Revision; Anlegen, Ändern, Aktivieren/Deaktivieren.
  - Datenbank: `vehicle` (Erweiterung um Kennung, Typ, Ausstattung, Aktivstatus; `owner_org_ref`, `capacity_config`, `safety_revision` bestehen), eindeutige Kennung je Mandant; `vehicle_lease` für Eigentums-/Nutzungsintervalle.
  - API: `/v1/operator/vehicles` (Liste, Detail mit ETag, Anlegen, Ändern mit `If-Match`).
  - Oberfläche: UI-11 Flotte/Werkstatt – Bereichsrahmen mit Navigation (im Web-Anwendungsrahmen WP-M1-43), Kopf mit Mandant und Datenstand, Fahrzeugliste und Stammdatenformular mit Feldfehlern; Leerzustand, Fehlerzustand und Rechtezustand (Bereich für Unberechtigte nicht vorhanden).
  - Ereignisse/Outbox: Fahrzeug angelegt/geändert/deaktiviert.
- **Detailverträge vorab:** OpenAPI `/v1/operator/vehicles`; JSON-Schema `capacity_config` (Grundform, Details in WP-M3-04); Wertekatalog Fahrzeugtyp/Ausstattung (OP-14).
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug mit negativer, fehlender oder nicht ganzzahliger Kapazität wird mit 422 und Feldverweis abgewiesen und nicht gespeichert; die Datenbank weist denselben Wert per CHECK ab (FLT-001).
  2. Kennung, Betreiber, Typ, Kapazitäten, Ausstattung und Aktivstatus sind Pflichtangaben und nach dem Speichern unverändert lesbar (FLT-001).
  3. Änderung ohne `If-Match` ergibt 428, mit veralteter Revision 412.
  4. Ein Fahrzeug eines anderen Mandanten ergibt 404.
  5. Anlegen, Ändern und Deaktivieren stehen mit Akteur und Zeitpunkt im `audit_event`; ein deaktiviertes Fahrzeug wird nicht neu disponiert, bleibt aber in Historie und Nachweisen lesbar (FLT-001).
  6. Ein neu angelegtes Fahrzeug ist nicht disponierbar, solange keine Einsatzfreigabe vorliegt (Schema-Default `active_block = true`; Bedeutung laut OP-01 zu bestätigen).
- **Tests:** Positiv: Anlegen und Ändern. Negativ: ungültige Kapazität, doppelte Kennung, fremder Mandant. Konkurrierend: zwei gleichzeitige Änderungen derselben Revision – genau eine gewinnt.
- **Abhängigkeiten:** intern: WP-M3-02.
  - extern: WP-M1-07, WP-M1-08, WP-M1-12, WP-M1-05, WP-M1-43 (Rollen- und Objektberechtigung, Audit, Organisation als Betreiber, Web-Anwendungsrahmen)
  - extern: WP-M1-34 (Fahrzeugrollen mit Gültigkeitsintervallen)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Wartungsfristen, HU-Vorwarnung (`inspection_warning_days`), Energie-/Ladeplanung, Fremdfahrzeugfreigabe zwischen Mandanten (M6/M7).

### WP-M3-04 – Kapazitäts- und Stellplatzkonfiguration
- **Ziel:** Sitzplätze, barrierefrei nutzbare Stellplätze, Platzkombinationen und Restriktionen sind je Fahrzeug getrennt konfigurierbar und stehen der Disposition als Ressourcenvektor zur Verfügung.
- **Anforderungen:** `LH-6.8-FLT-004`
- **Liefergegenstände:**
  - Backend: Validierung und Auflösung von `vehicle.capacity_config` in zulässige Platzkombinationen (Umrüstzustände); Lesefunktion „verfügbarer Ressourcenvektor je Konfiguration" als Modul-API für `booking-dispatch`.
  - Datenbank: `vehicle.capacity_config` (jsonb) mit gespeicherter JSON-Schema-Version.
  - API: `/v1/operator/vehicles` (Teilressource Kapazitätskonfiguration).
  - Oberfläche: UI-11 – Editor für Stellplätze, Kombinationen und Restriktionen mit Tabellenalternative.
- **Detailverträge vorab:** JSON-Schema `capacity_config` (Dimensionen Sitz, Rollstuhlplatz, Ausstattung; Kombinationen; Restriktionen); Abstimmung des Ressourcenvektors mit dem M2-Kapazitätskern (`capacity_hold.capacity_vector`).
- **Akzeptanzkriterien:**
  1. Bei einer Konfiguration „ein Rollstuhlplatz ersetzt n Sitzplätze" reduziert die Belegung des Rollstuhlplatzes die verfügbare Sitzkapazität um genau n; die Disposition erhält diesen Vektor (FLT-004).
  2. Stellplätze, Kombinationen und Restriktionen sind unabhängig voneinander änderbar; eine widersprüchliche Kombination (mehr belegte Fläche als vorhanden) wird abgewiesen (FLT-004).
  3. Eine Konfigurationsänderung wirkt nicht rückwirkend auf bestehende Holds und Buchungen.
- **Tests:** Positiv: Musterfahrzeug mit zwei Kombinationen. Negativ: ungültige Kombination, unbekannte Dimension. Konkurrierend: Konfigurationsänderung parallel zu einem Hold – der Hold prüft gegen eine konsistente Revision, keine Überbuchung.
- **Abhängigkeiten:** intern: WP-M3-03.
  - extern: WP-M2-14 (Ressourcenvektor im Hold-/Angebotskern (Sitz, Rollstuhl, Ausstattung))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Einfügeheuristik und Kapazitätssperre selbst (M2); Parameter `wheelchair_boarding_sec` (M2).

### WP-M3-05 – Fahrerstamm und Qualifikationsrevisionen
- **Ziel:** Fahrer sind mit ihrer Identität verknüpft, und ihre Qualifikationen liegen als freigegebene, zeitlich gültige Revisionen vor, gegen die Einsatzfreigabe und Prüfung prüfen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Aggregat Driver (Verknüpfung zu `membership`), Qualifikationsrevision mit Gültigkeit von/bis und Freigabe; Lesefunktion „gültige Qualifikationen zum Zeitpunkt t" und fortlaufende `qualification_revision`-Nummer je Fahrer.
  - Datenbank: `driver`, `qualification_revision`; Bezug `staff_shift.driver_ref`.
  - API: Verwaltungsendpunkte für Fahrer und Qualifikationen (im Detailvertrag zu benennen, Kap. 05 §5.7 nennt keinen Pfad).
  - Oberfläche: UI-11 – Fahrerliste und Qualifikationspflege (Grundform).
- **Detailverträge vorab:** DDL; Katalog der Qualifikationsarten und deren Bezug zu Fahrzeugtyp/Dienst; Modulzuordnung (OP-07).
- **Akzeptanzkriterien:**
  1. Eine Qualifikation ohne Freigabe oder außerhalb ihres Gültigkeitsintervalls gilt als nicht vorhanden.
  2. Jede Änderung erzeugt eine neue Revision; die alte bleibt lesbar.
  3. HR-nahe Felder sind nur für berechtigte Rollen sichtbar; Dispositionssichten enthalten keine Diagnosen.
  4. Ein Fahrer ist genau einer Mitgliedschaft des Mandanten zugeordnet; ein Fahrer eines anderen Mandanten ergibt 404, und jede Änderung an Fahrer oder Qualifikation steht im `audit_event`.
- **Tests:** Positiv: gültige Qualifikation wird gefunden. Negativ: abgelaufene, nicht freigegebene, fremder Mandant. Konkurrierend: Qualifikationsentzug parallel zur Einsatzfreigabe (Nachweis in WP-M3-08).
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-03 (Rahmen UI-11).
  - extern: WP-M1-06, WP-M1-07, WP-M1-31 (Mitgliedschaft, Rollenmodell, Feldschutz)
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung) – blockiert nur die Produktivschaltung personenbezogener Fahrerdaten.
- **Größe:** M
- **Nicht im Umfang:** Dienstplan, Abwesenheiten, Ruhezeitprüfung (M4, SCH/TIM); zeitlich begrenzte Fahrerfreigabe an Partner (WP-M1-35, M6).

### WP-M3-06 – Fahrzeugpool-Zuordnung zu Diensten
- **Ziel:** Fahrzeuge sind zeitlich und geografisch einem oder mehreren Diensten zugeordnet, und die Disposition vergibt Fahrten nur aus aktuell zugeordneten Diensten.
- **Anforderungen:** `LH-6.8-FLT-002`
- **Liefergegenstände:**
  - Backend: Zuordnung Fahrzeug ↔ Dienst mit Gültigkeitsintervall (links geschlossen, rechts offen) und Gebietsbezug; Modul-API „für Dienst d zum Zeitpunkt t zulässige Fahrzeuge".
  - Datenbank: `vehicle_assignment` mit Fremdschlüsseln auf `vehicle`, `service`, optional `service_area`.
  - API: `/v1/operator/vehicles` (Teilressource Dienstzuordnungen).
  - Oberfläche: UI-11 – Zuordnungstabelle je Fahrzeug.
- **Detailverträge vorab:** DDL `vehicle_assignment`; Festlegung, wie „geografisch" abgebildet wird (OP-25); Schnittstelle zur Kandidatenauswahl in `booking-dispatch`.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug, das Dienst A zugeordnet ist, erhält keine Fahrt aus Dienst B; nach Ablauf der Zuordnung erhält es auch aus Dienst A keine neue Fahrt (FLT-002).
  2. Ein Fahrzeug kann gleichzeitig mehreren geeigneten Diensten zugeordnet sein und erhält dann Fahrten aus beiden (FLT-002).
  3. Eine Zuordnung zu einem Dienst eines anderen Mandanten ist nicht speicherbar.
  4. Die zeitliche und die geografische Einschränkung wirken unabhängig: außerhalb des Zuordnungsintervalls oder außerhalb des zugeordneten Gebiets erhält das Fahrzeug keine Fahrt (FLT-002; Abbildung „geografisch" laut OP-25).
  5. Bereits zugesagte Fahrten bleiben beim Ende einer Zuordnung sichtbar und gehen als Hinweis an die Leitstelle; sie werden nicht still entfernt.
- **Tests:** Positiv: Zuordnung und Zuweisung. Negativ: abgelaufene Zuordnung, falscher Dienst, falsches Gebiet. Konkurrierend: Zuordnung endet parallel zu einem Hold – der Commit prüft die Zuordnung erneut.
- **Abhängigkeiten:** intern: WP-M3-03.
  - extern: WP-M1-09, WP-M1-25 (Dienste und Organisationsstruktur)
  - extern: WP-M2-04, WP-M2-15, WP-M2-02 (Bediengebiete (`service_area`, WP-M2-04) und Fahrzeugkandidatenfilter der Angebotsbildung (WP-M2-15), der die Zuordnung über den Leseport `ResourceAvailability` (WP-M2-02) statt der Fixture liest)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fremdfahrzeuge über `cooperation_grant` (M6); automatische Poolbildung.

### WP-M3-07 – Fahrer-/Fahrzeugzuweisung mit eindeutigen Schichtgrenzen
- **Ziel:** Jede Zuweisung von Fahrer und Fahrzeug hat eindeutige Schichtgrenzen; überlappende Schichten eines Fahrers oder Fahrzeugs erzeugen einen Konflikt.
- **Anforderungen:** `LH-6.8-FLT-003`
- **Liefergegenstände:**
  - Backend: Anlegen/Ändern einer Schichtzuweisung mit Konfliktprüfung für Fahrer und Fahrzeug; Konfliktantwort mit Ursache.
  - Datenbank: `staff_shift` (`driver_ref`, `vehicle_id`, `starts_at`, `ends_at`, `timezone`, `state`); Überlappungsverbot je Fahrer und je Fahrzeug (Exclusion Constraint oder gesperrte Intervallprüfung).
  - API: Schichtzuweisung für die Leitstelle (Pfad im Detailvertrag; `/v1/rosters` gehört zu M4).
  - Oberfläche: Zuweisungsdialog mit Konfliktanzeige im Web-Anwendungsrahmen (WP-M1-43), erreichbar aus UI-11; die Einbindung in die Navigation von UI-07 erfolgt mit WP-M3-36.
- **Detailverträge vorab:** Zustandswerte `staff_shift.state`; Umgang mit `roster_revision` vor M4 (OP-07); Definition der „Ausnahme" (OP-07).
- **Akzeptanzkriterien:**
  1. Eine zweite, zeitlich überlappende Schicht für denselben Fahrer wird ohne dokumentierte Ausnahme mit 409 und Konfliktursache abgewiesen (FLT-003).
  2. Schichtbeginn und -ende sind Pflicht, `ends_at` liegt nach `starts_at`, die Dauer wird aus absoluten Zeitpunkten berechnet (FLT-003).
  3. Direkt aneinandergrenzende Schichten (Ende = Beginn) gelten nicht als Überlappung.
  4. Eine überlappende Fahrzeugbelegung (zwei Schichten auf demselben Fahrzeug) erzeugt denselben Konflikt (FLT-003).
  5. Wird eine Doppelschicht über die dokumentierte Ausnahme zugelassen (Verfahren laut OP-07), sind Genehmiger, Grund und Zeitpunkt gespeichert; ohne diese Angaben bleibt es beim Konflikt (FLT-003).
  6. Anlegen und Ändern einer Zuweisung stehen im `audit_event`; eine Schicht eines anderen Mandanten ergibt 404.
- **Tests:** Positiv: zwei aufeinanderfolgende Schichten. Negativ: Überlappung Fahrer, Überlappung Fahrzeug, DST-Wechsel am 25.10.2026. Konkurrierend: zwei parallele Transaktionen legen überlappende Schichten desselben Fahrers an – höchstens eine wird gespeichert.
- **Abhängigkeiten:** intern: WP-M3-03, WP-M3-05.
  - extern: WP-M1-43, WP-M1-18 (Web-Anwendungsrahmen, Zeit-/Zeitzonen-Baustein)
  - extern: WP-M4-04, WP-M4-13, WP-M4-14, WP-M4-65 (Dienstplan mit Entwurf/Publikation und Ruhezeitprüfung (übernimmt später die Schichterzeugung))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Dienstplan-Publikation, Schichttausch, Ruhezeit, Abwesenheit (M4); AT-39 (M4).

### WP-M3-08 – Sperr- und Freigabekern (Safety-Revision, `resource_release`)
- **Ziel:** Es gibt genau eine serverseitige Stelle, die über die Einsatzfähigkeit eines Fahrzeugs entscheidet; Sperren und Freigaben wirken synchron und sind gegen konkurrierende Disposition abgesichert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`, Domänendienst Readiness: Sperre setzen (mit Grund und Quelle), Sperre aufheben, Einsatzfreigabe erteilen/widerrufen. Jede sicherheitsrelevante Änderung erhöht `vehicle.safety_revision`. Synchrone Modul-API `assertDispatchable(vehicle, shift, safety_revision)` (Arbeitsname, Signatur in WP-M3-01 festzulegen) für `booking-dispatch`; Zeilensperren in stabiler Reihenfolge (Run vor Fahrzeug) nach Kap. 04 §4.5.
  - Backend: Anbindung an den Leseport `ResourceAvailability` aus WP-M2-02: die führenden M3-Daten lösen die M2-Fixtures ab; der Hold-Commit (WP-M2-22) ruft `assertDispatchable` im selben Lock auf.
  - Datenbank: `vehicle.active_block`, `vehicle.safety_revision`, `resource_release` (`safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); Abbildung mehrerer gleichzeitiger Sperrgründe gemäß OP-01.
  - Ereignisse/Outbox: Fahrzeug gesperrt, Sperre aufgehoben, Einsatzfreigabe erteilt/widerrufen (Projektionen werden informiert; die Sperre selbst gilt synchron).
  - Kennzahlen: Datengrundlage (Sperr-/Freigabezeitpunkte mit Grund) für `Fahrzeugverfügbarkeit` und `Ausfallminuten kritisch`.
- **Detailverträge vorab:** Zustandsautomat Einsatzfreigabe; Sperrgrundkatalog; Sperrreihenfolge mit dem M2-Hold-Commit; DDL-Entscheidung OP-01.
- **Akzeptanzkriterien:**
  1. Eine Einsatzfreigabe entsteht nur, wenn im selben Commit gültige Schicht, gültige Qualifikationsrevision, bestätigte Prüfung und keine aktive Sperre vorliegen.
  2. Eine Sperre widerruft im selben Commit jede gültige `resource_release` des Fahrzeugs und erhöht `safety_revision`.
  3. Ein Dispositions-Commit mit veralteter `safety_revision` scheitert; es entsteht keine Zusage auf ein gesperrtes Fahrzeug.
  4. Mehrere Sperrgründe sind unabhängig; das Aufheben eines Grundes hebt die anderen nicht auf.
  5. Jede Sperre und Freigabe steht mit Akteur, Grund und Zeitpunkt im `audit_event`.
  6. Eine `resource_release` gilt nur bis `valid_until` und nur für ihre Schicht; nach Ablauf, nach Schichtende oder nach Widerruf scheitert `assertDispatchable` (Regel für `valid_until` laut OP-33).
  7. Sperr- und Freigabekommandos sind idempotent: dieselbe Sperre aus derselben Quelle erzeugt keinen zweiten Sperrgrund und keinen zweiten Revisionssprung.
  8. Nach Umstellung des M2-Leseports auf die M3-Daten bleibt PH-AT-004 (letzter Platz unter Konkurrenz) grün.
- **Tests:** Positiv: Freigabe bei erfüllten Bedingungen. Negativ: je fehlende Bedingung ein Fall; abgelaufene Freigabe; Freigabe für fremde Schicht; Sperre eines Fahrzeugs eines anderen Mandanten (404). Konkurrierend: Sperre und neue Zuweisung in zwei parallelen Transaktionen (Kern von PH-AT-020) – in keiner Serialisierung entsteht eine Zusage auf das gesperrte Fahrzeug; Qualifikationsentzug parallel zur Freigabe.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-03, WP-M3-07.
  - extern: WP-M1-14, WP-M1-12, WP-M1-17 (Outbox, Audit, Zustandsautomaten-Rahmen)
  - extern: WP-M2-22, WP-M2-23, WP-M2-02 (Hold-/Commit-Pfad ruft die Safety-Prüfung im selben Commit auf (Kap. 01 §1.4 Schritt 5; WP-M2-22, WP-M2-23), Leseport `ResourceAvailability` (WP-M2-02))
- **Entscheidungen:** keine
- **Größe:** L
- **Nicht im Umfang:** fachliche Auslöser der Sperre (WP-M3-16, WP-M3-41, WP-M3-46); Wiederfreigabe nach Reparatur (WP-M3-51).

### WP-M3-09 – Readiness-Gate vor Bereitstatus und Disposition
- **Ziel:** Ohne gültige, abgeschlossene und serverbestätigte Vor-Schicht-Prüfung für die Kombination aus Fahrer, Fahrzeug und Schicht ist weder der erste Bereitstatus noch eine neue Disposition möglich.
- **Anforderungen:** `LH-6.21-CHK-001`
- **Liefergegenstände:**
  - Backend: Regel „erste Bereitmeldung je (Fahrer, Fahrzeug, Schicht) verlangt `resource_release` mit `inspection_ref`"; Abfrage „fehlende Voraussetzungen" (Liste der offenen Prüfschritte und Bedingungen).
  - API: `/v1/operator/vehicles` und `/v1/driver/shifts/{id}/run` liefern Freigabestatus und fehlende Voraussetzungen.
  - Oberfläche: UI-09 – „Fahrzeugfreigabe: Prüfung erforderlich" mit Liste fehlender Schritte; UI-07 – Fahrzeug als nicht einsatzbereit mit Grund.
  - Konfigurationsparameter: `vehicle_readiness_require_checks` (Validierung „Sicherheitsprüfungen dürfen nicht abgeschaltet werden", siehe OP-09).
- **Detailverträge vorab:** Antwortschema „fehlende Voraussetzungen"; Wirkung von `vehicle_readiness_require_checks`.
- **Akzeptanzkriterien:**
  1. Für eine angelegte Schicht mit angemeldetem Fahrer und ungeprüftem Fahrzeug scheitert „verfügbar schalten" serverseitig; das Fahrzeug erscheint nicht als Kandidat neuer Fahrten (CHK-001).
  2. Die Antwort nennt die fehlenden Prüfschritte; Fahrer-App und Leitstelle zeigen sie an (CHK-001, AT-26).
  3. Eine Prüfung derselben Schicht für ein anderes Fahrzeug oder eines anderen Fahrers für dasselbe Fahrzeug erfüllt die Bedingung nicht (CHK-001).
  4. Der Parameter kann die Pflichtprüfung sicherheitskritischer Schritte nicht abschalten; ein entsprechender Konfigurationsentwurf wird bei der Publikation abgewiesen.
  5. Aufträge der Schicht werden in `/v1/driver/shifts/{id}/run` erst nach Einsatzfreigabe als ausführbar ausgeliefert; vorher zeigt die App nur Dienst, Fahrzeug und fehlende Voraussetzungen (CHK-001).
  6. Ein abgewiesener Bereitversuch steht mit Akteur und fehlender Voraussetzung im `audit_event`.
- **Tests:** Positiv: nach bestätigter Prüfung ist Bereitmeldung möglich (AT-27 gemeinsam mit WP-M3-14). Negativ: AT-26; manipulierter Client meldet „bereit" ohne Prüfung. Konkurrierend: Bereitmeldung parallel zum Widerruf der Freigabe.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-14, WP-M3-25, WP-M3-36 (nur UI-07-Anteil).
  - extern: WP-M1-19, WP-M1-21 (wirksame Konfiguration und Systemgrenzen)
  - extern: WP-M2-15 (Fahrzeugkandidatenfilter der Disposition berücksichtigt nur freigegebene Fahrzeuge)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalt und Ablauf der Prüfung (WP-M3-10 bis WP-M3-19); Fahrzeugwechsel (WP-M3-22).

### WP-M3-10 – Checklistenvorlagen und Prüfelemente
- **Ziel:** Checklistenvorlagen lassen sich als unveränderliche Revisionen mit vollständig beschriebenen Prüfelementen pflegen und freigeben.
- **Anforderungen:** `LH-6.21-CHK-003`
- **Liefergegenstände:**
  - Backend: Aggregat ChecklistTemplateRevision (Entwurf → freigegeben → abgelöst); Prüfelement mit Titel, Hilfetext, Eingabetyp, Pflichtstatus, optionalem Beispielbild, Kennzeichen „sicherheitskritisch" und „Begründung bei Nicht anwendbar erforderlich"; Antwortwerte OK, Mangel, Nicht anwendbar; Validierungsfunktion für Antworten.
  - Datenbank: `checklist_template_revision`, `checklist_step`; freigegebene Revision unveränderlich.
  - API: Vorlagenverwaltung (Pfad im Detailvertrag unterhalb `/v1/inspections` bzw. Konfiguration).
  - Oberfläche: UI-11 – Vorlageneditor (Grundform) mit Freigabe.
- **Detailverträge vorab:** DDL; JSON-Schema Eingabetypen; Zustandsautomat Vorlagenrevision; Klärung „Nicht anwendbar" gegenüber „Nicht prüfbar" (OP-16).
- **Akzeptanzkriterien:**
  1. Ein Prüfelement ohne Titel, Eingabetyp oder Pflichtstatus ist nicht speicherbar (CHK-003).
  2. Eine fehlende Antwort auf ein Pflichtelement oder ein Wert außerhalb des Eingabetyps blockiert den Abschluss mit Verweis auf das Element (CHK-003).
  3. „Nicht anwendbar" ohne Begründung wird bei entsprechend markierten Elementen abgewiesen (CHK-003).
  4. Eine freigegebene Revision ist nicht änderbar; Änderung erzeugt eine neue Revision.
  5. Jedes Prüfelement lässt sich mit Hilfetext und optionalem Beispielbild speichern; das Beispielbild wird nur über den mandantengetrennten Objektspeicher ausgeliefert (CHK-003).
  6. Die Antwortwerte OK, Mangel und Nicht anwendbar sind die einzigen zulässigen Statuswerte je Prüfelement; die Blockade des Abschlusses wird am Submit in WP-M3-14 nachgewiesen (CHK-003; vierte Antwort „Nicht prüfbar" laut OP-16).
- **Tests:** Positiv: Vorlage mit allen Eingabetypen. Negativ: fehlende Pflichtantwort, ungültiger Wert, „Nicht anwendbar" ohne Begründung, Änderung einer freigegebenen Revision. Konkurrierend: zwei gleichzeitige Freigaben derselben Vorlage – genau eine wirksame Revision.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-03 (Rahmen UI-11).
  - extern: WP-M1-22, WP-M1-11 (Vier-Augen-Freigabe und Revisionsmuster der Konfiguration (WP-M1-22), Objektspeicher für Beispielbilder (WP-M1-11))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** fachliche Pflichtinhalte (WP-M3-11, WP-M3-12); Durchführung (WP-M3-13 ff.).

### WP-M3-11 – Pflichtvorlagen Fahrzeugtechnik und Fahrgastraum
- **Ziel:** Es gibt freigegebene Standardvorlagen, die alle im Lastenheft geforderten Pflichtpunkte der technischen Fahrzeugprüfung und der Prüfung vor Fahrgastaufnahme enthalten.
- **Anforderungen:** `LH-6.21-CHK-005`, `LH-6.21-CHK-006`
- **Liefergegenstände:**
  - Backend: Vorlagenvalidator „Pflichtpunkte vollständig" je Vorlagentyp; Seed der Standardvorlagen.
  - Datenbank: Seed-Daten in `checklist_template_revision`, `checklist_step`.
  - Oberfläche: UI-09 – Darstellung je Prüfpunkt mit Einzelstatus und Mangelerfassung.
- **Detailverträge vorab:** Liste der Pflichtpunkte mit stabilen Schlüsseln: Beleuchtung, Reifen-Sichtprüfung, Scheiben, Spiegel, Türen, Sicherheitsgurte, sichtbare Leckagen, Warnanzeigen; Sauberkeit, Fahrgastraum, Sitzplätze, Einstiegsbereich, Rückhaltesysteme, Sicherheitsausrüstung. Einstufung „sicherheitskritisch" je Punkt durch die Fachseite.
- **Akzeptanzkriterien:**
  1. Die Fahrzeugvorlage enthält alle acht technischen Pflichtpunkte; eine Vorlage, der einer fehlt, lässt sich nicht freigeben (CHK-005).
  2. Zu jedem technischen Pflichtpunkt kann ein Mangelbefund erfasst werden (CHK-005).
  3. Die Fahrgastraumprüfung lässt sich mit Einzelstatus je Punkt abschließen; bei Abweichung ist ein dokumentierter Mangel Pflicht (CHK-006).
  4. Die Vorlage für die Prüfung vor Fahrgastaufnahme enthält alle sechs Pflichtpunkte (Sauberkeit, Fahrgastraum, Sitzplätze, Einstiegsbereich, Rückhaltesysteme, Sicherheitsausrüstung); fehlt einer, lässt sie sich nicht freigeben (CHK-006).
- **Tests:** Positiv: Abschluss mit allen Punkten OK; Abschluss mit einem dokumentierten Mangel. Negativ: Freigabe einer unvollständigen Vorlage; Abweichung ohne Mangeldokumentation.
- **Abhängigkeiten:** intern: WP-M3-10, WP-M3-13, WP-M3-14 (Durchführung und Abschluss für die Abnahme der Vorlagen). extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** betreiberspezifische Zusatzpunkte; Prüfpunkte für Rampe/Lift (WP-M3-12).

### WP-M3-12 – Barrierefreiheits-Prüfpunkte und eingeschränkte Freigabe
- **Ziel:** Entsprechend ausgestattete Fahrzeuge haben eigene Prüfpunkte für Rampe/Lift, Rollstuhlverankerung, zugängliche Einstiegsstelle und Bedienelemente; ein Ausfall schließt das Fahrzeug von passenden Fahrten aus.
- **Anforderungen:** `LH-6.21-CHK-007`
- **Liefergegenstände:**
  - Backend: ausstattungsabhängige Prüfpunkte (aus `capacity_config`/Ausstattung abgeleitet); Prüfergebnis „eingeschränkt" mit Angabe der nicht verfügbaren Ressourcendimension; Readiness liefert der Disposition den reduzierten Ressourcenvektor.
  - Datenbank: `inspection_run` (Ergebnis und Einschränkung), `checklist_step` (Ausstattungsbedingung).
  - Oberfläche: UI-09 – Frage „Rollstuhlrampe funktionsfähig?" mit Ja / Nein – Mangel melden / Nicht prüfbar (Kap. 06 §6.7); UI-07 – Fahrzeug „eingeschränkt" mit Grund.
- **Detailverträge vorab:** Abbildung Ausstattung → Prüfpunkte; Semantik „eingeschränkt" im Ressourcenvektor (OP-17).
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug mit Rampe/Lift erhält die vier Prüfpunkte, ein Fahrzeug ohne diese Ausstattung nicht (CHK-007).
  2. Bei „Rampe nicht funktionsfähig" erhält das Fahrzeug keine Fahrt mit Rollstuhlbedarf; je nach Einstufung des Punkts bleibt es für andere Fahrten nutzbar oder ist gesperrt (CHK-007).
  3. „Nicht prüfbar" gilt nicht als Ja und führt nicht zur Freigabe für passende Fahrten (CHK-007).
- **Tests:** Positiv: funktionsfähige Ausstattung → volle Freigabe. Negativ: defekte Rampe → Anfrage mit Rollstuhlbedarf wird diesem Fahrzeug nicht zugewiesen. Konkurrierend: Einschränkung parallel zu einem Hold mit Rollstuhlplatz – kein Commit auf die entfallene Dimension.
- **Abhängigkeiten:** intern: WP-M3-04, WP-M3-08, WP-M3-10, WP-M3-14, WP-M3-36 (nur UI-07-Anteil).
  - extern: WP-M2-14, WP-M2-15 (Disposition berücksichtigt den Ressourcenvektor je Dimension)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Umgang mit bereits zugesagten Rollstuhlfahrten bei Ausfall (WP-M3-54).

### WP-M3-13 – Prüflauf-Start mit exakt freigegebener Vorlage
- **Ziel:** Nach der Anmeldung startet die Fahrer-App eine geführte Prüfung, deren Vorlage der Server anhand von Schicht, Fahrzeugklasse, Bedienart und freigegebener Checklistenversion bestimmt.
- **Anforderungen:** `LH-6.21-CHK-002`
- **Liefergegenstände:**
  - Backend: Vorlagenauswahl (Schicht, Fahrzeugklasse, Bedienart → genau eine freigegebene `checklist_template_revision`); Anlage `inspection_run` im Zustand „in Bearbeitung" mit fester Vorlagenrevision.
  - Datenbank: `inspection_run` (Fahrer, Fahrzeug, Schicht, Vorlagenrevision, Zustand).
  - API: `POST /v1/inspections` (idempotent), `GET /v1/inspections/{id}`.
  - Oberfläche: UI-09 – „Pflichtprüfung starten", geführte Schrittfolge „Prüfung 3 / 8".
- **Detailverträge vorab:** OpenAPI `/v1/inspections`; Auswahlregel und Verhalten bei keiner oder mehreren passenden Vorlagen; Attribute Fahrzeugklasse und Bedienart (OP-14).
- **Akzeptanzkriterien:**
  1. Der Fahrer erhält exakt die für sein Fahrzeug und seinen Dienst freigegebene Vorlagenrevision; ein Client kann keine andere Vorlage wählen (CHK-002).
  2. Gibt es keine eindeutig passende freigegebene Vorlage, entsteht kein Prüflauf, die Freigabe bleibt aus und die Leitstelle erhält einen Hinweis (CHK-002).
  3. Wiederholter Start mit demselben Idempotenzschlüssel liefert denselben Prüflauf.
  4. Ein Fahrer kann keinen Prüflauf für eine Schicht starten, die ihm nicht zugewiesen ist (404).
  5. Wird nach dem Start eine neue Vorlagenrevision freigegeben, behält der laufende Prüflauf seine Revision; ein neuer Prüflauf erhält die neue (CHK-002).
- **Tests:** Positiv: zwei Fahrzeugklassen erhalten unterschiedliche Vorlagen. Negativ: fremde Schicht, keine passende Vorlage, Vorlage im Entwurf. Konkurrierend: Doppelstart von zwei Geräten – genau ein offener Prüflauf je (Fahrer, Fahrzeug, Schicht).
- **Abhängigkeiten:** intern: WP-M3-07, WP-M3-10, WP-M3-25. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Antworten und Abschluss (WP-M3-14); Offline-Start (WP-M3-33).

### WP-M3-14 – Prüfabschluss und unveränderlicher Prüfnachweis
- **Ziel:** Ein Prüflauf wird serverseitig abgeschlossen; der Abschluss enthält alle Pflichtangaben und unveränderliche Einzelantworten, und erst die Serverbestätigung führt zur Einsatzfreigabe.
- **Anforderungen:** `LH-6.21-CHK-013`
- **Liefergegenstände:**
  - Backend: Submit validiert alle Antworten gegen die feste Vorlagenrevision, berechnet das Ergebnis (bestanden, eingeschränkt, gesperrt, technisch ungeklärt), schreibt Antworten, Ergebnis, `resource_release` (über WP-M3-08) und Outbox in einer Transaktion.
  - Datenbank: `inspection_run` (Prüfer-ID, Fahrzeug-ID, Schicht-ID, Vorlagenrevision, Zeitstempel Gerät/Server, Ergebnis), `inspection_answer` (verweist auf `checklist_step`-Revision; unveränderlich).
  - API: `POST /v1/inspections/{id}/submit` (Idempotency-Key, `If-Match`), `GET /v1/inspections/{id}` mit Vorlage und Antworten im Originalstand.
  - Oberfläche: UI-09 – Abschlussseite mit serverbestätigtem Ergebnis; UI-11 – Prüfhistorie je Fahrzeug.
  - Ereignisse/Outbox: Prüfung abgeschlossen (mit Ergebnis, ohne Freitext).
- **Detailverträge vorab:** OpenAPI `/v1/inspections/{id}/submit`; Zustandsautomat Prüflauf; Ergebnisregel.
- **Akzeptanzkriterien:**
  1. Der gespeicherte Abschluss enthält Prüfer-ID, Fahrzeug-ID, Schicht-ID, Vorlagenversion, Zeitstempel, Ergebnis und alle Einzelantworten (CHK-013).
  2. Nach einer späteren Vorlagenänderung zeigt die Historie die ursprünglichen Fragen und Antworten (CHK-013, AT-32).
  3. UPDATE/DELETE auf Antworten eines abgeschlossenen Prüflaufs scheitern auf API- und Datenbankebene (CHK-013).
  4. Wiederholtes Submit mit demselben Schlüssel liefert dasselbe Ergebnis und erzeugt keine zweite Freigabe; anderer Inhalt unter demselben Schlüssel ergibt `409 IDEMPOTENCY_CONFLICT`.
  5. Die App zeigt „freigegeben" erst nach Serverantwort.
  6. Der Server setzt Prüfer-ID aus der Sitzung und den Serverzeitstempel selbst; vom Client gelieferte Prüfer-, Mandanten- oder Ergebnisangaben werden ignoriert bzw. abgewiesen (CHK-013).
  7. Der Abschluss steht im `audit_event`; das Outbox-Ereignis enthält weder Freitext noch Personendetails.
- **Tests:** Positiv: AT-27 (vollständige Prüfung, Fahrzeug darf starten). Negativ: Submit mit fehlender Pflichtantwort, Submit fremder Prüfung, Mutation nach Abschluss. Konkurrierend: zwei gleichzeitige Submits – genau ein Abschluss; Submit parallel zu einer neuen Sperre – keine Freigabe.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-13.
  - extern: WP-M1-13, WP-M1-14, WP-M1-12 (Idempotenzregister, Outbox, Audit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Korrektureinträge (WP-M3-15); Export und Archivfristen der Prüfnachweise (AT-53, M5).

### WP-M3-15 – Prüfentwurf und versionierte Korrektureinträge
- **Ziel:** Antworten sind bis zum Abschluss bearbeitbar; danach sind nur versionierte Korrektur- oder Ergänzungseinträge möglich, die das Original nie überschreiben.
- **Anforderungen:** `LH-6.21-CHK-014`
- **Liefergegenstände:**
  - Backend: Zwischenspeichern von Antworten im Zustand „in Bearbeitung"; Korrektur-/Ergänzungseintrag nach Abschluss mit Autor, Zeit, Grund, Version und Bezug auf die Originalantwort.
  - Datenbank: `inspection_answer` (Entwurfsantworten bis Abschluss änderbar; danach append-only Nachträge mit Bezug).
  - API: Antworten speichern und Nachtrag anlegen unterhalb `/v1/inspections/{id}` (Pfade im Detailvertrag).
  - Oberfläche: UI-09 – Fortsetzen einer begonnenen Prüfung; UI-11 – Nachtrag mit Pflichtgrund, Original und Nachträge nebeneinander.
- **Detailverträge vorab:** OpenAPI der Teilressourcen; Regel, ob ein Nachtrag das Ergebnis oder die Freigabe verändern darf (Planungsannahme: nein, nur über neue Prüfung bzw. WP-M3-51).
- **Akzeptanzkriterien:**
  1. Vor dem Abschluss lassen sich gespeicherte Antworten ändern; nach App-Neustart ist der Entwurf vorhanden (CHK-014).
  2. Nach dem Abschluss erzeugt eine Bearbeitung einen neuen versionierten Eintrag; die ursprüngliche Antwort bleibt unverändert lesbar (CHK-014).
  3. Ein Nachtrag ohne Grund wird abgewiesen.
  4. Ein Nachtrag hebt keine Sperre auf und erteilt keine Freigabe.
- **Tests:** Positiv: Entwurf ändern, Nachtrag anlegen. Negativ: direkte Änderung nach Abschluss, Nachtrag ohne Grund, Nachtrag durch unberechtigte Rolle. Konkurrierend: zwei gleichzeitige Nachträge – beide mit fortlaufender Version erhalten.
- **Abhängigkeiten:** intern: WP-M3-14. extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Offline-Entwurf auf dem Gerät (WP-M3-33).

### WP-M3-16 – Kritischer Mangel sperrt synchron
- **Ziel:** Eine negative Antwort auf ein sicherheitskritisches Prüfelement löst im selben Commit eine Fahrzeug- oder Dienstsperre aus und verhindert die automatische Einsatzfreigabe.
- **Anforderungen:** `LH-6.21-CHK-004`
- **Liefergegenstände:**
  - Backend: Submit-Regel „kritisches Nein → Ergebnis gesperrt, Sperre über WP-M3-08, keine `resource_release`"; Unterscheidung Fahrzeugsperre und Dienstsperre je Prüfelement.
  - Datenbank: `checklist_step` (Kennzeichen sicherheitskritisch, Sperrart), `inspection_run` (Ergebnis).
  - Oberfläche: UI-09 – gesonderte Kennzeichnung kritischer Elemente (nicht nur Farbe), Sperranzeige mit Vorgangsnummer und Kontakt zur Leitstelle (Kap. 06 §6.7); UI-07 – „gesperrt · kritischer Mangel".
  - Ereignisse/Outbox: Fahrzeug gesperrt (Quelle Prüfung); Alarm an die Leitstelle über WP-M3-52.
- **Detailverträge vorab:** Sperrart je Element (Fahrzeug oder Dienst); Zusammenspiel mit `workshop_auto_block_severity`.
- **Akzeptanzkriterien:**
  1. Ein als kritisch markierter Mangel verhindert die automatische Einsatzfreigabe; nach dem Submit existiert keine gültige `resource_release` (CHK-004).
  2. Sperre und Prüfergebnis werden in derselben Transaktion gespeichert; es gibt keinen Zeitpunkt mit „Mangel gespeichert, Fahrzeug disponierbar" (CHK-004).
  3. Kritische Elemente sind in der App für Screenreader und ohne Farbe erkennbar gekennzeichnet (CHK-004).
  4. Die Leitstelle erhält genau einen Alarm je Sperre.
  5. Eine Dienstsperre (statt Fahrzeugsperre) verhindert die Einsatzfreigabe der betroffenen Schicht, ohne das Fahrzeug für andere Schichten zu sperren; welche Art greift, bestimmt allein das Prüfelement (CHK-004).
  6. Die App zeigt die Sperre mit Vorgangsnummer und Kontakt zur Leitstelle; der Fahrer kann sie nicht selbst aufheben.
- **Tests:** Positiv: unkritischer Mangel sperrt nicht. Negativ: kritisches Nein → keine Freigabe, Bereitmeldung scheitert. Konkurrierend: Submit mit kritischem Nein parallel zu einer neuen Zuweisung (PH-AT-020) – keine Zusage auf das Fahrzeug. UI-Abnahmefall „Driverprüfpfad mit kritischem Nein" (Kap. 06 §6.9).
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-14, WP-M3-52, WP-M3-36 (nur UI-07-Anteil). extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Schadenakte zum Mangel (WP-M3-20); Aufheben der Sperre (WP-M3-51).

### WP-M3-17 – Schichtstartwerte mit Plausibilitätsprüfung
- **Ziel:** Kilometerstand, Energie- bzw. Tankstatus und Fahrzeugidentität werden beim Schichtstart erfasst, und unplausible Werte müssen überprüft oder begründet freigegeben werden.
- **Anforderungen:** `LH-6.21-CHK-008`
- **Liefergegenstände:**
  - Backend: Eingabetypen Kilometerstand, Energie/Tank, Fahrzeugidentität; Plausibilitätsregeln (Kilometerstand kleiner als letzter bestätigter Wert; Identität weicht vom zugewiesenen Fahrzeug ab); Zustand „Überprüfung erforderlich" mit begründeter Freigabe.
  - Datenbank: `inspection_answer` (typisierte Werte), letzter bestätigter Kilometerstand am `vehicle`.
  - Oberfläche: UI-09 – Erfassung, Warnung mit Vorwert, Pflichtbegründung.
- **Detailverträge vorab:** Regelkatalog und Schwellen der Plausibilität; wer begründet freigeben darf (OP-15); möglicher Bezug zu `min_battery_soc_pct` (OP-15).
- **Akzeptanzkriterien:**
  1. Ein Kilometerstand unter dem letzten bestätigten Wert kann nicht stillschweigend gespeichert werden; er verlangt Korrektur oder Begründung (CHK-008).
  2. Eine abweichende Fahrzeugidentität (Verwechslung) verhindert den Abschluss für das zugewiesene Fahrzeug, bis sie geklärt ist (CHK-008).
  3. Die begründete Freigabe speichert Akteur, Zeit und Begründung im Prüfnachweis (CHK-008).
  4. Kilometerstand, Energie- bzw. Tankstatus und Fahrzeugidentität sind als Schichtstartwerte je Prüflauf gespeichert und später unverändert lesbar (CHK-008).
- **Tests:** Positiv: plausible Werte. Negativ: Unterschreitung ohne Begründung, falsches Fahrzeug, Energie außerhalb 0–100 %, begründete Freigabe durch eine nicht berechtigte Rolle. Konkurrierend: zwei Prüfabschlüsse desselben Fahrzeugs mit unterschiedlichem Kilometerstand – der letzte bestätigte Wert am Fahrzeug bleibt konsistent und fällt nicht zurück.
- **Abhängigkeiten:** intern: WP-M3-14. extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Telematikabgleich, Lade-/Reichweitenplanung, Tankbelege (M4).

### WP-M3-18 – Technikbereitschaft und Technikereignis
- **Ziel:** Der Fahrer bestätigt vor Schichtbeginn die Funktion von Fahrer-App, Mobilfunk, Navigation und dienstlichem Zubehör; Fehler werden als Technikereignis sichtbar.
- **Anforderungen:** `LH-6.21-CHK-009`
- **Liefergegenstände:**
  - Backend: Prüfelemente der Gruppe Technik; negatives Ergebnis erzeugt ein Technikereignis und einen Alarm niedriger Priorität; Ergebnis „technisch ungeklärt", sofern die Fachseite das Element so einstuft.
  - Datenbank: `inspection_answer`, `business_alert`.
  - Oberfläche: UI-09 – Technikschritte; UI-07 – Hinweis am Fahrzeug und in der Arbeitsliste.
  - Ereignisse/Outbox: Technikereignis gemeldet.
- **Detailverträge vorab:** Katalog der Technikpunkte und ihre Wirkung auf die Freigabe.
- **Akzeptanzkriterien:**
  1. Ein gemeldeter Technikfehler erzeugt genau ein Technikereignis mit Bezug auf Prüflauf, Fahrzeug und Schicht (CHK-009).
  2. Die Leitstelle sieht den Hinweis ohne manuelle Suche am betroffenen Fahrzeug (CHK-009).
  3. Wiederholte Übermittlung desselben Prüfschritts erzeugt kein zweites Ereignis.
  4. Die Vorlage enthält je einen Prüfpunkt für Fahrer-App, Mobilfunkverbindung, Navigation und dienstliches Zubehör (CHK-009).
- **Tests:** Positiv: alles funktionsfähig → kein Ereignis. Negativ: Navigationsfehler → Ereignis und Hinweis; Duplikat → kein zweites; Disponent eines nicht berechtigten Dienstes sieht den Hinweis nicht. Konkurrierend: derselbe Prüfschritt wird parallel zweimal übermittelt – genau ein Technikereignis und ein Hinweis.
- **Abhängigkeiten:** intern: WP-M3-14, WP-M3-52, WP-M3-36 (nur UI-07-Anteil). extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Geräteverwaltung (MDM), automatische Gerätediagnose.

### WP-M3-19 – Einsatzbereitschaft und Qualifikationsprüfung
- **Ziel:** Der Fahrer bestätigt seine grundsätzliche Einsatzbereitschaft ohne Gesundheitsangaben; der Server prüft erforderliche Qualifikationen gegen freigegebene Stammdaten und sperrt bei Fehlen.
- **Anforderungen:** `LH-6.21-CHK-010`
- **Liefergegenstände:**
  - Backend: Prüfelement „Einsatzbereitschaft" als reine Ja/Nein-Bestätigung ohne Freitext; Qualifikationsabgleich (Fahrzeugtyp/Dienst ↔ `qualification_revision`) beim Submit und in der Einsatzfreigabe.
  - Datenbank: `inspection_answer` (kein Textfeld für dieses Element, per Schema erzwungen), `resource_release.qualification_revision`.
  - Oberfläche: UI-09 – Bestätigung mit neutralem Wortlaut; verständliche Meldung bei fehlender Qualifikation ohne Offenlegung von Personaldetails.
- **Detailverträge vorab:** Anforderungsmatrix Qualifikation je Fahrzeugtyp/Dienst; Wortlaut der Selbstauskunft; Verhalten bei „nicht einsatzbereit".
- **Akzeptanzkriterien:**
  1. Das Schema der Selbstauskunft enthält kein Freitextfeld; ein Request mit Zusatztext wird abgewiesen, in Datenbank und Logs steht kein Gesundheitsfreitext (CHK-010).
  2. Fehlt eine erforderliche Qualifikation oder ist sie abgelaufen, entsteht keine Einsatzfreigabe und der Dienst ist gesperrt (CHK-010).
  3. Die Prüfung nutzt ausschließlich freigegebene Qualifikationsrevisionen; die verwendete Revision steht in `resource_release` (CHK-010).
  4. Die Meldung an den Fahrer bei fehlender Qualifikation nennt keine Personaldetails; die Leitstelle erhält einen Hinweis ohne HR-Inhalte.
  5. Bestätigt der Fahrer die Einsatzbereitschaft nicht, entsteht keine Einsatzfreigabe; gespeichert wird nur Ja/Nein, kein Grund (CHK-010).
- **Tests:** Positiv: gültige Qualifikation → Freigabe. Negativ: fehlende/abgelaufene Qualifikation, Zusatzfeld im Request. Konkurrierend: Qualifikationsentzug parallel zum Submit – keine Freigabe auf alter Revision.
- **Abhängigkeiten:** intern: WP-M3-05, WP-M3-08, WP-M3-14. extern: keine.
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ruhezeit und Höchstarbeitszeit (M4); AT-39 (M4).

### WP-M3-20 – Abweichung mit Foto und Schadenvorgang aus Prüfschritt oder Störung
- **Ziel:** Zu jeder Abweichung erfasst der Fahrer Text, Kategorie und Fotos; aus einem negativen Prüfschritt oder einer Betriebsstörung entsteht eine Schadenakte, die in beide Richtungen verknüpft ist.
- **Anforderungen:** `LH-6.21-CHK-011`, `LH-6.22-DMG-003`
- **Liefergegenstände:**
  - Backend: Abweichungserfassung am Prüfschritt (Text, Kategorie, Fotos mit Aufnahmedatum); Kommando „Schadenakte aus Prüfschritt" und „Schadenakte aus Störung" mit Übernahme der Referenzen (Prüflauf, Prüfschritt, Fahrzeug, Schicht bzw. Incident).
  - Datenbank: `defect_case` (Herkunftsreferenzen), `inspection_answer` (Verweis auf `defect_case`), `defect_evidence`.
  - API: `POST /v1/defects` mit Herkunftsangabe; `GET /v1/inspections/{id}` und `GET /v1/defects/{id}` liefern die Gegenreferenz.
  - Oberfläche: UI-09 – „Nein – Mangel melden" öffnet die Erfassung mit vorbelegten Referenzen; Foto zeigt das Aufnahmedatum sichtbar; UI-11 – Sprung Prüfung ↔ Schadenakte.
- **Detailverträge vorab:** Herkunftsmodell der Schadenakte; Kategorienkatalog; Quelle des Aufnahmedatums (Gerätezeit, als nicht vertrauenswürdig gekennzeichnet, plus Serverempfangszeit).
- **Akzeptanzkriterien:**
  1. Ein negativer Prüfschritt kann einen Schadenvorgang mit Fallnummer erzeugen, der nachverfolgbar bleibt (CHK-011).
  2. Text, Kategorie und Fotos mit sichtbarem Aufnahmedatum sind an der Abweichung gespeichert (CHK-011).
  3. Aus der Prüfung führt ein Verweis zur Schadenakte und aus der Schadenakte zurück zum Prüfschritt (DMG-003).
  4. Eine aus einer Betriebsstörung erzeugte Schadenakte trägt die Incident-Referenz und ist vom Incident aus erreichbar (DMG-003).
  5. Wiederholtes Auslösen für denselben Prüfschritt erzeugt keine zweite Akte.
  6. Die Abweichungserfassung ist an jedem negativ beantworteten Prüfschritt verfügbar, nicht nur an kritischen (CHK-011).
  7. Fotos durchlaufen denselben Upload- und Prüfpfad wie Schadensfotos (WP-M3-44); ein Foto ohne bestandene Medienprüfung ist nicht an der Abweichung sichtbar.
- **Tests:** Positiv: Mangel mit zwei Fotos → Akte, beide Richtungen navigierbar. Negativ: Akte zu fremdem Prüflauf, Foto ohne Aufnahmedatum, Abruf der Gegenreferenz durch einen Fahrer ohne Bezug zum Fahrzeug (404). Konkurrierend: doppelte Übermittlung nach Timeout – genau eine Akte.
- **Abhängigkeiten:** intern: WP-M3-14, WP-M3-43, WP-M3-44, WP-M3-53. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Klassifikation und Sperre der Akte (WP-M3-45, WP-M3-46).

### WP-M3-21 – Bekannte Altschäden anzeigen und quittieren
- **Ziel:** Vor der Vor-Schicht-Prüfung sieht der Fahrer die nutzungsrelevanten offenen Altschäden des gewählten Fahrzeugs und kann sie als gesehen/bestehend markieren, ohne einen neuen Schadensfall auszulösen.
- **Anforderungen:** `LH-6.21-CHK-012`, `LH-6.22-DMG-012`
- **Liefergegenstände:**
  - Backend: minimierte Fahrerprojektion offener Schadenakten je Fahrzeug (Bereich, Art, Foto, Fallnummer; keine vertraulichen Dokumente); Quittierung mit Bezug auf Prüflauf und Schadenakte; Dublettenschutz „bekannter Schaden → Verweis statt Neuanlage".
  - Datenbank: `defect_case`, Quittierung als Eintrag am `inspection_run` mit Verweis auf `defect_case`.
  - API: Altschäden zum Prüflauf und Quittierung unterhalb `/v1/inspections/{id}` (Pfade im Detailvertrag).
  - Oberfläche: UI-09 – „Vorhandene Schäden ansehen" vor „Pflichtprüfung starten"; Quittierung „gesehen/bestehend" ohne Verursacherangabe.
- **Detailverträge vorab:** Feldliste der Fahrerprojektion; Regel „nutzungsrelevant"; Verhalten, wenn der Fahrer einen Altschaden als verändert meldet (Nachtrag über WP-M3-48).
- **Akzeptanzkriterien:**
  1. Offene Altschäden werden vor dem Abschluss angezeigt; der Abschluss ist erst nach Quittierung möglich (CHK-012, AT-29).
  2. Die Quittierung erzeugt keine neue Schadenakte; der alte Fall bleibt referenziert (CHK-012).
  3. Die Quittierung speichert keine Verursacher- oder Schuldangabe zum quittierenden Fahrer (DMG-012).
  4. Ein neuer Fahrer sieht die Altschäden des gewählten Fahrzeugs, nicht die anderer Fahrzeuge (DMG-012).
  5. Meldet der Fahrer bei der Schadenerfassung einen Schaden an einem Fahrzeug mit offenen Fällen, zeigt die App diese vor dem Anlegen; die Neuanlage bleibt mit Bestätigung möglich (CHK-012).
  6. Hat das Fahrzeug keine offenen Altschäden, zeigt die App das ausdrücklich an (Leerzustand) und verlangt keine Quittierung.
- **Tests:** Positiv: AT-29. Negativ: Abschluss ohne Quittierung; Zugriff auf Altschäden eines nicht zugewiesenen Fahrzeugs; Neuanlage eines identischen Schadens zeigt den bestehenden Fall (P1-Anteil des Dublettenhinweises aus AT-36: Hinweis auf offene Fälle desselben Fahrzeugs vor der Neuanlage; der Ähnlichkeitsvorschlag folgt mit WP-M7c-39, siehe OP-12). Konkurrierend: doppelte Quittierung desselben Altschadens von zwei Geräten – genau ein Quittierungseintrag; Altschaden wird parallel zur Quittierung abgeschlossen – der Prüfabschluss bleibt möglich und referenziert den Stand zum Quittierungszeitpunkt.
- **Abhängigkeiten:** intern: WP-M3-13, WP-M3-43, WP-M3-50. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** automatische Dublettenerkennung über Bild-/Textähnlichkeit (siehe OP-12).

### WP-M3-22 – Fahrzeugwechsel in laufender Schicht
- **Ziel:** Bei einem Fahrzeugwechsel während der Schicht bleibt das Ersatzfahrzeug gesperrt, bis seine eigene fahrzeugspezifische Prüfung serverseitig bestätigt ist.
- **Anforderungen:** `LH-6.21-CHK-015`
- **Liefergegenstände:**
  - Backend: Kommando Fahrzeugwechsel (Leitstelle oder Fahrer nach Regel): beendet die Freigabe des alten Fahrzeugs für diese Schicht, ordnet das Ersatzfahrzeug zu, verlangt neuen Prüflauf; Qualifikationsprüfung für den neuen Fahrzeugtyp.
  - Datenbank: `staff_shift`/`vehicle_assignment` gemäß OP-08, `resource_release` (alte widerrufen, neue erst nach Prüfung), `vehicle_run`.
  - API: Fahrzeugwechsel an der Schicht (Pfad im Detailvertrag); `/v1/driver/shifts/{id}/run` liefert das neue Fahrzeug und „Prüfung erforderlich".
  - Oberfläche: UI-09 – Zustand „andere Fahrzeugrevision", neue Pflichtprüfung; UI-07 – Wechseldialog mit Begründung.
- **Detailverträge vorab:** Schemaentscheidung OP-08; Übergang der offenen Stopps auf das Ersatzfahrzeug (mit WP-M3-40).
- **Akzeptanzkriterien:**
  1. Nach dem Wechsel hat das Ersatzfahrzeug keine Einsatzfreigabe; es ist nicht disponierbar, bis seine Prüfung bestätigt ist (CHK-015, AT-30).
  2. Die Prüfung des alten Fahrzeugs gilt nicht für das Ersatzfahrzeug; es gibt keine Vorabfreigabe (CHK-015).
  3. Fehlt die Qualifikation für den Typ des Ersatzfahrzeugs, wird der Wechsel abgewiesen.
  4. Der Wechsel ist mit Akteur, Begründung, altem und neuem Fahrzeug im `audit_event` gespeichert; die Prüfhistorie beider Fahrzeuge bleibt unverändert.
  5. Die offenen Altschäden des Ersatzfahrzeugs werden vor dessen Prüfung angezeigt (WP-M3-21).
- **Tests:** Positiv: Wechsel, neue Prüfung, Freigabe. Negativ: Bereitmeldung ohne neue Prüfung; Wechsel auf gesperrtes Fahrzeug. Konkurrierend: Wechsel parallel zu einer Zuweisung auf das Ersatzfahrzeug – keine Zusage vor der Prüfung.
- **Abhängigkeiten:** intern: WP-M3-07, WP-M3-08, WP-M3-09, WP-M3-13, WP-M3-36 (nur UI-07-Anteil). extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Umplanung der betroffenen Fahrten (WP-M3-40, WP-M3-54).

### WP-M3-23 – Prüfstatus in Fahrer-App und Leitstelle
- **Ziel:** Fahrer-App und Leitstelle zeigen denselben eindeutigen Prüfstatus, und Statuswechsel erscheinen in der Leitstelle ohne erneutes Laden.
- **Anforderungen:** `LH-6.21-CHK-016`
- **Liefergegenstände:**
  - Backend: Statusprojektion je (Fahrzeug, Schicht) mit den Werten ausstehend, in Bearbeitung, bestanden, eingeschränkt, gesperrt, technisch ungeklärt; Ableitung aus `inspection_run`, Sperren und `resource_release`.
  - API: Statusfeld in `/v1/operator/vehicles` und `/v1/driver/shifts/{id}/run`; Zustellung über den Kanal aus WP-M3-35.
  - Oberfläche: UI-07 und UI-09 – Statusanzeige mit Text und Symbol, Live-Region für Änderungen, Datenstand.
- **Detailverträge vorab:** Ableitungstabelle Status ← Zustände; Aktualisierungsintervall (OP-05).
- **Akzeptanzkriterien:**
  1. Jeder der sechs Statuswerte ist in beiden Oberflächen darstellbar und eindeutig aus dem Serverzustand abgeleitet (CHK-016).
  2. Ein Statuswechsel erscheint in der geöffneten Leitstellenansicht ohne Neuladen innerhalb des festgelegten Aktualisierungsintervalls (CHK-016).
  3. Bei unterbrochenem Kanal zeigt die Ansicht den Datenstand und kennzeichnet den Status als möglicherweise veraltet.
  4. Der Status ist nie nur über Farbe erkennbar.
- **Tests:** Positiv: Durchlauf aller sechs Zustände mit Zeitmessung bis zur Darstellung. Negativ: Kanalabbruch → Veraltet-Kennzeichnung; Fahrer eines anderen Fahrzeugs erhält keinen Status; Disponent eines nicht berechtigten Dienstes erhält keinen Status. Konkurrierend: zwei Statuswechsel kurz hintereinander (bestanden → gesperrt) – beide Oberflächen enden auf dem letzten Serverstand, nie auf dem älteren.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-14, WP-M3-35, WP-M3-36. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Auswertungen und Kennzahlen zum Prüfstatus (M4/M8).

### WP-M3-24 – Driver-App-Basis: Anmeldung, verschlüsselte Outbox, Synchronisation
- **Ziel:** Die Flutter-App authentifiziert Fahrer, hält den letzten bestätigten Serverstand lokal verschlüsselt und überträgt offline erfasste Ereignisse nach Wiederverbindung genau einmal wirksam.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: `mobile/driver` – App-Rahmen, Anmeldung per OIDC Authorization Code + PKCE, Navigation, Offlinebanner mit Zeit des letzten Plans, Barrierefreiheitsgrundlagen (Touchflächen, Screenreader).
  - Adapter/Worker: verschlüsselte SQLite-Outbox mit `device_event_id`, letzter Serverrevision, Erfassungszeit und Empfangszeit (Kap. 01 §1.5); Sync-Engine mit Reihenfolgewahrung, Backoff, Behandlung von `APPLIED`, `DUPLICATE`, `REVIEW_REQUIRED`; lokaler Cache für Dienst und Stoppliste mit Stand.
  - Adapter/Worker: CI-Build und Verteilung der App auf Testgeräte (Aufsatz auf WP-M0-06, WP-M0-12).
  - Backend: BFF-Zuschnitt für die App; Geräte-/Sitzungsbindung; Sitzungssperre beendet die Nutzung nach Wiederverbindung.
- **Detailverträge vorab:** Sync-Protokoll (Reihenfolge, Wiederholung, Konfliktantworten); lokales Datenmodell und Verschlüsselungskonzept; Liste offline zulässiger Aktionen (Beleg, Schaden, Zeitereignis; keine Freigabe, keine Sperraufhebung).
- **Akzeptanzkriterien:**
  1. Ohne gültige Sitzung zeigt die App keine Auftragsdaten; nach Abmeldung sind lokale Daten nicht lesbar.
  2. Ein offline erfasstes Ereignis wird nach Wiederverbindung genau einmal wirksam, auch wenn die App die Übertragung mehrfach wiederholt.
  3. Offline bleibt der zuletzt bestätigte Dienst mit Stoppliste und Stand sichtbar.
  4. Die lokale Datenbank ist auf dem Gerät nicht im Klartext auslesbar.
  5. Jedes Outbox-Ereignis trägt `device_event_id`, letzte Serverrevision, Erfassungszeit und – nach Sync – die Serverempfangszeit; die Gerätezeit wird nie als Serverzeit übernommen.
  6. Offline bietet die App keine Aktion an, die eine Einsatzfreigabe, eine Sperraufhebung oder eine verbindliche Buchung auslöst (Kap. 01 §1.5).
  7. Bedienelemente erfüllen die Grundregeln aus Kap. 06 §6.1 (Touchflächen mindestens 44×44, Status nicht nur über Farbe, Screenreader-Beschriftung, Deutsch mit vorbereiteter Internationalisierung).
  8. Ein signierter Build lässt sich reproduzierbar aus der CI auf die Testgeräte verteilen.
- **Tests:** Positiv: Online-Sync. Negativ: abgelaufene Sitzung, gesperrte Sitzung, manipulierte `device_event_id`. Konkurrierend: 100 identische Wiederholungen eines Ereignisses → eine Wirkung. Legt die Grundlage für AT-11.
- **Abhängigkeiten:** intern: WP-M3-01.
  - extern: WP-M0-06, WP-M0-28 (Mobile-Gerüst und Spike Driver-Offline-Queue auf Zielgeräten)
  - extern: WP-M1-04, WP-M1-10, WP-M1-13 (OIDC, Sitzungssperre, Idempotenzregister)
- **Entscheidungen:** D-11 (Notbetrieb, Offline-Umfang) – blockiert nur die Produktivschaltung; D-05 (Zielgeräte des Pilotbetreibers) – beeinflusst die Testgeräte.
- **Größe:** L
- **Nicht im Umfang:** Beleg- und Zeiterfassung in der App (M4); Passenger-App (M7); Hintergrundortung (WP-M3-34).

### WP-M3-25 – Dienstanmeldung und Schicht-/Fahrzeugzuordnung
- **Ziel:** Der Fahrer meldet sich an und ordnet sich seinem gültigen Dienst und Fahrzeug zu; ohne gültige Schicht erhält er keine ausführbaren Aufträge.
- **Anforderungen:** `LH-6.6-DRV-001`
- **Liefergegenstände:**
  - Backend: Ermittlung der gültigen Schicht des angemeldeten Fahrers; Dienstantritt (Zuordnung Fahrer ↔ Schicht ↔ Fahrzeug bestätigen); Objektberechtigung „nur eigene, aktuell gültige Schicht".
  - Datenbank: `staff_shift` (Zustandsübergang geplant → angetreten), `vehicle_run`.
  - API: `/v1/driver/shifts/{id}/run` (liefert nur bei gültiger, angetretener Schicht Aufträge); `/v1/me` für Rolle und Mandant.
  - Oberfläche: UI-09 Driver Dienststart – „Nächster Dienst 06:00–14:00 · Fahrzeug A-12", Fahrzeugfreigabe getrennt von der Arbeitszeit.
- **Detailverträge vorab:** OpenAPI `/v1/driver/shifts/{id}/run`; Zustandsautomat Schicht (Antritt, Ende); Toleranz für Antritt vor Schichtbeginn.
- **Akzeptanzkriterien:**
  1. Ohne gültige Schicht liefert der Server keine ausführbaren Aufträge; die App zeigt den Leerzustand (DRV-001).
  2. Ein Fahrer kann sich nur einer ihm zugewiesenen Schicht und deren Fahrzeug zuordnen; eine fremde Schicht-ID ergibt 404 (DRV-001).
  3. Nach Schichtende oder Entzug der Zuweisung werden keine Aufträge mehr ausgeliefert (DRV-001).
  4. Der Dienstantritt allein erteilt keine Einsatzfreigabe; die Freischaltung der Aufträge nach bestätigter Prüfung weist WP-M3-09 nach.
  5. Die Authentifizierung nutzt ausschließlich das Fahrerkonto (OIDC, WP-M3-24); ein Konto ohne Fahrerrolle oder mit gesperrter Sitzung kann keinen Dienst antreten (DRV-001).
  6. Dienstantritt und Dienstende stehen mit Akteur, Gerät und Zeit im `audit_event`; die Arbeitszeiterfassung bleibt davon getrennt (Kap. 06 §6.7).
- **Tests:** Positiv: Anmeldung und Antritt. Negativ: keine Schicht, abgelaufene Schicht, fremde Schicht, fremder Mandant. Konkurrierend: zwei Geräte desselben Fahrers treten gleichzeitig an – ein konsistenter Zustand.
- **Abhängigkeiten:** intern: WP-M3-07, WP-M3-24.
  - extern: WP-M1-06, WP-M1-08 (Mitgliedschaft und `/v1/me`, Objektberechtigung)
  - extern: WP-M2-16 (`vehicle_run` der Schicht aus dem Fahrtenplan)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Arbeitszeitstempel (M4, TIM); Dienstplananzeige (M4).

### WP-M3-26 – Stoppliste des Fahrers
- **Ziel:** Der Fahrer sieht die geordnete Liste der kommenden Stopps mit Soll- und Prognosezeit sowie Fahrgastanzahl in der Reihenfolge, die die Disposition festgelegt hat.
- **Anforderungen:** `LH-6.6-DRV-002`
- **Liefergegenstände:**
  - Backend: Fahrerprojektion des aktuellen `vehicle_run` (Stoppfolge, Sollzeit, Prognosezeit, Ein-/Aussteiger je Stopp, Planrevision) mit Feldminimierung.
  - Datenbank: Lesezugriff auf `vehicle_run`, `route_stop`, `passenger_trip` (M2) über Modul-API.
  - API: `GET /v1/driver/shifts/{id}/run` mit ETag = Planrevision.
  - Oberfläche: UI-10 Driver Fahrt – Liste mit nächstem Handlungsschritt, Stopp, Personenzahl; Stand des Plans sichtbar.
- **Detailverträge vorab:** Antwortschema der Fahrerprojektion; Quelle der Prognosezeit vor dem ETA-Grundmodell (OP-20).
- **Akzeptanzkriterien:**
  1. Ein von der Leitstelle bzw. Disposition zugewiesener Auftrag erscheint in der App an der korrekten Position der Reihenfolge (DRV-002).
  2. Jeder Stopp zeigt Sollzeit, Prognosezeit und Fahrgastanzahl; fehlt eine Prognose, wird das ausgewiesen statt eine Zeit zu erfinden (DRV-002).
  3. Die Liste enthält ausschließlich Stopps der eigenen Schicht.
  4. Die Projektion enthält je Stopp nur Haltepunkt, Zeiten, Personenzahl und Ressourcenbedarf; keine Kontakt-, Zahlungs- oder Diagnosedaten (Kap. 05 §5.1 Minimierung).
  5. Leerzustand (kein Auftrag), Fehlerzustand (Abruf gescheitert, letzter Stand mit Zeit) und Offline-Zustand sind unterscheidbar dargestellt.
- **Tests:** Positiv: Run mit drei Stopps, Reihenfolge gleich `route_stop`-Sequenz. Negativ: fremde Schicht, Run ohne Stopps (Leerzustand). Konkurrierend: Abruf während eines Plan-Commits liefert eine konsistente Revision.
- **Abhängigkeiten:** intern: WP-M3-25.
  - extern: WP-M1-08, WP-M1-31 (Objektberechtigung und Feldminimierung)
  - extern: WP-M2-14, WP-M2-16, WP-M2-28 (Fahrtenplan (`vehicle_run`, `route_stop`, `passenger_trip`) mit Planrevision und Fahrer-Projektion)
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell für die Prognosezeit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Planänderungen (WP-M3-27); Unterstützungshinweise (WP-M3-32).

### WP-M3-27 – Planänderungen anzeigen und kontrolliert übernehmen
- **Ziel:** Dynamische Planänderungen erreichen die App zeitnah, werden deutlich hervorgehoben und vom Fahrer bestätigt bzw. kontrolliert übernommen.
- **Anforderungen:** `LH-6.6-DRV-004`
- **Liefergegenstände:**
  - Backend: Benachrichtigung der App bei neuer Planrevision (Push als Weckruf plus Abruf); Speicherung der vom Fahrer bestätigten Revision; Hinweis an die Leitstelle bei ausbleibender Bestätigung.
  - API: `GET /v1/driver/shifts/{id}/run` (bedingter Abruf); Bestätigung der Revision (Pfad im Detailvertrag).
  - Oberfläche: UI-10 – Änderungsanzeige (neu, entfallen, verschoben) mit Bestätigung; keine Erfassungsdialoge während der Fahrbewegung; Zustand „vergangene Revision".
  - Adapter/Worker: Zustellung bei geöffneter App über den Änderungsstrom aus WP-M2-28 bzw. den Live-Kanal aus WP-M3-35; bei geschlossener App technischer Weckruf über den Push-Kanal des Notification-Adapters (WP-M2-32). Ein fachlicher Fahrer-Push mit Inhalt ist `LH-6.12-COM-007` (P2) und nicht Teil dieses WPs (OP-29).
- **Detailverträge vorab:** Diff-Darstellung zwischen Revisionen; Regel, welche Änderungen bestätigt werden müssen und welche automatisch übernommen werden; Messpunkt „Commit bis dargestellte Clientrevision".
- **Akzeptanzkriterien:**
  1. Eine neu eingespielte Route ist bei bestehender Verbindung innerhalb von p95 ≤ 10 s nach Commit in der App dargestellt (Zielwert aus Kap. 08; DRV-004).
  2. Die Änderung ist gegenüber der vorherigen Revision deutlich gekennzeichnet und wird bestätigt oder nach Regel kontrolliert übernommen (DRV-004).
  3. Offline zeigt die App den letzten Plan mit Stand und keine vermeintlich aktuelle Route.
  4. Die bestätigte Revision ist serverseitig je Fahrer nachvollziehbar.
  5. Die Push-Nutzlast enthält keine Fahrgast- oder Ortsdaten, nur den Hinweis auf eine neue Revision.
  6. Während der Fahrbewegung erscheint kein Bestätigungsdialog; die Änderung wird angesagt bzw. angezeigt und die Bestätigung beim nächsten Halt verlangt (Kap. 06 §6.7; Erkennung der Fahrbewegung laut Detailvertrag).
  7. Ein Fahrerereignis auf einer nicht bestätigten, veralteten Revision wird nicht still angewendet (`REVIEW_REQUIRED`, siehe WP-M3-29).
- **Tests:** Positiv: Zeitmessung Commit → Darstellung über 100 Planänderungen. Negativ: Push geht verloren → Abruf beim nächsten Sync; Bestätigung einer veralteten Revision. Konkurrierend: zwei Planrevisionen kurz hintereinander – die App endet auf der neuesten.
- **Abhängigkeiten:** intern: WP-M3-26, WP-M3-35.
  - extern: WP-M2-28, WP-M2-32 (Planrevisionen, Änderungsstrom und Fahrer-Projektion (WP-M2-28), Push-Kanal des Notification-Adapters (WP-M2-32))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Lasttest der p95-Ziele unter Referenzlast (M5).

### WP-M3-28 – Navigation auf Basis der gültigen Stoppliste
- **Ziel:** Abbiegenavigation bzw. Übergabe an eine geeignete Navigations-App verwendet dieselbe gültige Stoppliste; das Navigationsziel ist immer der nächste disponierte Stopp.
- **Anforderungen:** `LH-6.6-DRV-003`
- **Liefergegenstände:**
  - Oberfläche: UI-10 – Aktion „Navigation starten" mit Übergabe der Koordinate des nächsten Stopps an die Geräte-Navigation; Anzeige des Ziels mit Haltepunktname.
  - Backend: Koordinate und Name des nächsten Stopps aus `stop_revision` in der Fahrerprojektion.
  - Adapter/Worker: Navigationsübergabe hinter einer Schnittstelle, damit ein Anbieterwechsel möglich bleibt.
- **Detailverträge vorab:** Entscheidung eingebettete Abbiegenavigation oder Übergabe (OP-19); Verhalten bei Planänderung während laufender Navigation.
- **Akzeptanzkriterien:**
  1. Das übergebene Navigationsziel entspricht Koordinate und Revision des nächsten disponierten Stopps der gültigen Stoppliste (DRV-003).
  2. Nach einer Planänderung des nächsten Stopps weist die App auf das geänderte Ziel hin und übergibt das neue Ziel (DRV-003).
  3. Ohne gültige Stoppliste ist die Navigationsaktion nicht verfügbar.
- **Tests:** Positiv: Ziel = nächster Stopp. Negativ: Plan geändert → altes Ziel wird nicht weiterverwendet; kein Stopp → keine Navigation.
- **Abhängigkeiten:** intern: WP-M3-26.
  - extern: WP-M0-30 (Ergebnis des Routingvergleichs)
  - extern: WP-M2-05 (Haltepunktrevisionen (`stop_revision`))
- **Entscheidungen:** D-09 (Routing, Karten, Telematik) – blockiert nur die Produktivschaltung; gebaut wird gegen die Geräte-Navigation.
- **Größe:** S
- **Nicht im Umfang:** eigene Kartendarstellung mit Verkehrslage; Routingdienst (M2).

### WP-M3-29 – Fahrerereignisse: Ankunft, Einstieg, No-Show, Ausstieg
- **Ziel:** Der Fahrer meldet Ankunft, Einstieg, Nicht-Erscheinen und Ausstieg; der Server prüft Zuordnung und Übergang und schreibt konsistente Fahrt- und Buchungsstatus, auch bei verspäteten Offline-Ereignissen.
- **Anforderungen:** `LH-6.6-DRV-005`
- **Liefergegenstände:**
  - Backend: Verarbeitung von `DriverEvent` mit Prüfung von Schicht, Fahrzeug, Buchung, Stopp und `plan_revision`; Zustandsübergänge an Buchung und Fahrt über die Modul-API von `booking-dispatch`; Ergebnis `APPLIED`, `DUPLICATE`, `REVIEW_REQUIRED` mit `reason_code` (`STALE_PLAN`, `OUTSIDE_STOP`, `CLOCK_SKEW`, `INVALID_ORDER`); Klärfall bei Widerspruch zu einem serverseitigen Storno (Ereignis bleibt als historische Beobachtung erhalten).
  - Datenbank: dauerhafte Deduplikation über `event_id`; `booking.state`, `journey_leg.execution_state` (über M2-Modul-API).
  - API: `POST /v1/driver/events` (`recordDriverEvent`).
  - Oberfläche: UI-10 Driver Fahrt/Schaden – große Check-in/out-Flächen, No-Show erst nach Ablauf der Wartefrist mit Grund; Fehlerzustände „falscher Stopp", „vergangene Revision".
  - Konfigurationsparameter: `no_show_grace_min`.
  - Kennzahlen: Datengrundlage (Ist-Zeitpunkte je Stopp und Buchung) für `No-Show-Rate`, `Abholung pünktlich` und `Ankunft pünktlich`.
  - Ereignisse/Outbox: Fahrgast eingestiegen/ausgestiegen, No-Show festgestellt, Klärfall eröffnet.
- **Detailverträge vorab:** Erweiterung von `DriverEvent.event_type` um die Ankunft (OP-03); Übergangstabelle Ereignis → Buchungs-/Fahrtstatus; Regel für `REVIEW_REQUIRED`.
- **Akzeptanzkriterien:**
  1. Einstieg, Ausstieg und No-Show schreiben konsistente Fahrt- und Buchungsstatus; ein Ausstieg ohne vorherigen Einstieg ergibt `REVIEW_REQUIRED` mit `INVALID_ORDER` (DRV-005).
  2. Die Ankunft am Stopp ist meldbar und startet die Wartefrist (DRV-005).
  3. No-Show vor Ablauf von `no_show_grace_min` wird abgewiesen; der No-Show speichert Grund und Wartezeit (DRV-005, AT-08).
  4. Dasselbe `event_id` wird dauerhaft dedupliziert (`DUPLICATE`), ohne zweite Statuswirkung.
  5. Ein Offline-Check-in auf alter Planrevision ergibt `REVIEW_REQUIRED` mit `STALE_PLAN` und keine ausgeführte Beförderungsbuchung.
  6. Kein verspätetes Ereignis hebt eine aktuelle Schadenssperre auf.
  7. Ereignisse mit Schicht, Fahrzeug, Buchung oder Stopp außerhalb der eigenen angetretenen Schicht ergeben 404; ein `tenant_id` im Request erzeugt keine zusätzlichen Rechte.
  8. Ereignis-, Geräte- und Serverempfangszeit sind getrennt gespeichert; die Wartefrist wird gegen die serverseitig plausibilisierte Ankunftszeit geprüft, bei Uhrabweichung entsteht `REVIEW_REQUIRED` mit `CLOCK_SKEW` (DRV-005).
  9. Der No-Show steht mit Grund, Wartezeit und Regelstand als Ereignis für Tarif/Zahlung bereit; eine Gebühr bucht dieses WP nicht (Abrechnungsteil von AT-08 siehe OP-21).
  10. Während der Fahrbewegung bietet UI-10 keine Erfassungsdialoge an (Kap. 06 §6.7).
- **Tests:** Positiv: AT-08; vollständige Fahrt Einstieg → Ausstieg. Negativ: fremde Schicht (404), falscher Stopp, Uhrabweichung, Ereignis ohne Einsatzfreigabe des Fahrzeugs, Ereignis nach Schichtende, gleicher `event_id` mit anderem Inhalt. Konkurrierend: Check-in parallel zum Storno derselben Buchung → Klärfall, kein Zustandsrücksprung; 100 identische Ereignisse → eine Wirkung. Besteht AT-11 gemeinsam mit WP-M3-24; liefert den Fahreranteil (Check-in, Fahrtende) von AT-01 (Gate M2); deckt den Contract-Fall „Offline-Check-in auf alter Planrevision" (Kap. 05 §5.8).
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-24, WP-M3-26.
  - extern: WP-M1-13, WP-M1-08 (Idempotenzregister, Objektberechtigung)
  - extern: WP-M2-23, WP-M2-25, WP-M2-16 (Buchungs- und Fahrtstatusautomat mit Statushistorie (WP-M2-23, WP-M2-25), Fahrtenplan (WP-M2-16). Die No-Show-Abrechnungsregel (`LH-6.4-BKG-015`, P2) liefert M2 nicht (OP-21).)
- **Entscheidungen:** keine
- **Größe:** L
- **Nicht im Umfang:** No-Show-Gebühr und Zahlung (M2); Kennzahlberechnung (M4).

### WP-M3-30 – Check-in mit Codeprüfung und Fahrgastliste
- **Ziel:** Der Check-in unterstützt eine datensparsame Codeprüfung und die Liste der am Stopp erwarteten Fahrgäste; falsche oder bereits verwendete Codes werden abgewiesen.
- **Anforderungen:** `LH-6.6-DRV-006`
- **Liefergegenstände:**
  - Backend: Mindestumfang Boarding-Code, weil M2 ihn nicht liefert (`LH-6.4-BKG-019` ist P2): Erzeugung je bestätigter Buchung als Verbraucher des Bestätigungsereignisses, Speicherung nur als Hash, Entwertung beim Check-in. Serverseitige Prüfung des Codes gegen die Buchung am aktuellen Stopp; Einmalverwendung; Fehlversuchsbegrenzung. Der Weg, auf dem der Fahrgast den Code erhält (Buchungsdetail UI-04 oder Bestätigungsnachricht), ist mit M2 abzustimmen (OP-18); für die Abnahme genügt die Bereitstellung über Testfixture und Leitstelle.
  - Datenbank: Boarding-Code je Buchung (Zusatztabelle aus WP-M3-02; Hash, Zustand, Verwendungszeitpunkt).
  - API: Erweiterung von `POST /v1/driver/events` (Check-in mit Code) bzw. eigener Prüfaufruf laut Detailvertrag.
  - Oberfläche: UI-10 – Liste erwarteter Fahrgäste (Anzeigename bzw. Buchungskürzel, Personenzahl), Codeeingabe/-scan, Fehlerzustand „falscher Code".
- **Detailverträge vorab:** Codeformat, Gültigkeit und Erzeugung (OP-18); Feldliste der Fahrgastliste (Minimierung nach Kap. 05 §5.1); Verhalten offline.
- **Akzeptanzkriterien:**
  1. Ein falscher Code wird abgewiesen; es entsteht kein Check-in (DRV-006).
  2. Ein bereits verwendeter Code wird abgewiesen (DRV-006).
  3. Die Fahrgastliste enthält nur Fahrgäste des aktuellen bzw. nächsten Stopps der eigenen Schicht und keine Kontakt- oder Zahlungsdaten (DRV-006).
  4. Ein Code einer Buchung an einem anderen Stopp oder Fahrzeug wird abgewiesen.
  5. Der Code steht weder im Klartext in der Datenbank noch in Logs, Ereignissen oder Metriklabels; die Fahrerprojektion enthält ihn nicht (DRV-006, datensparsam).
  6. Offline erfasste Codes gelten erst nach Serverprüfung als Check-in; ein offline doppelt verwendeter Code führt beim Sync zu genau einem Check-in und einem Klärfall.
  7. Nach Überschreiten der Fehlversuchsgrenze ist die Codeeingabe für diese Buchung gesperrt und die Leitstelle erhält einen Hinweis (Grenzwert laut Detailvertrag, kein Katalogparameter – OP-18).
  8. Ein stornierter oder abgelaufener Buchungszustand macht den Code ungültig.
- **Tests:** Positiv: gültiger Code → Check-in. Negativ: falscher Code, verwendeter Code, Code fremder Buchung, zu viele Fehlversuche. Konkurrierend: derselbe Code gleichzeitig von zwei Geräten – genau ein Check-in.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-29.
  - extern: WP-M2-23, WP-M2-26 (Bestätigungs- und Stornoereignis der Buchung)
  - extern: UNGEDECKT – Anzeige des Codes beim Fahrgast (nicht in M2 geplant, OP-18)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Ticketkontrolle externer Abschnitte; Check-in ohne Code als Regelprozess (fachlich zu entscheiden, OP-18).

### WP-M3-31 – Fahrzeug- und Dienststatus
- **Ziel:** Fahrzeug- und Dienststatus unterscheiden Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung und Ende; ein nicht verfügbares Fahrzeug wird nicht neu disponiert.
- **Anforderungen:** `LH-6.6-DRV-007`
- **Liefergegenstände:**
  - Backend: Zustandsautomat Fahrzeug-/Dienststatus mit den sechs Werten; Übergänge durch Fahrer (Pause, Störung, Ende) und abgeleitet aus Fahrerereignissen (Anfahrt, Wartezeit); Modul-API „disponierbar?" kombiniert Status und Readiness.
  - Datenbank: `vehicle_run.state` bzw. Statusfeld an der Schicht laut Detailvertrag; Statushistorie.
  - API: Statusmeldung des Fahrers (Erweiterung `/v1/driver/events` oder eigener Pfad, OP-03); Statusfeld in `/v1/operator/vehicles`.
  - Oberfläche: UI-10 – Statusumschaltung; UI-07 – Status je Fahrzeug.
  - Kennzahlen: Datengrundlage für `Fahrzeugverfügbarkeit`.
- **Detailverträge vorab:** Zustandsautomat mit zulässigen Übergängen und Auslösern; Wirkung von „Pause" auf bereits zugesagte Stopps.
- **Akzeptanzkriterien:**
  1. Alle sechs Status sind unterscheidbar gespeichert und in beiden Oberflächen sichtbar (DRV-007).
  2. Ein Fahrzeug im Status Pause, Störung oder Ende erhält keine neue Zuweisung (DRV-007).
  3. Ein unzulässiger Übergang (z. B. von Ende nach Anfahrt ohne neuen Dienstantritt) wird serverseitig abgewiesen.
  4. Die Statusmeldung „Störung" erzeugt das Outbox-Ereignis, das den Störungsablauf (WP-M3-53) eröffnet.
  5. Jeder Statuswechsel ist mit Auslöser (Fahrer, System, Leitstelle), Zeit und Vorstatus in der Statushistorie gespeichert; ein Fahrer kann nur den Status seiner eigenen angetretenen Schicht melden.
  6. Eine offline erfasste Statusmeldung wird nach Sync genau einmal wirksam; „Störung" und „Ende" wirken auf die Disponierbarkeit erst ab Serverempfang, die App weist darauf hin.
- **Tests:** Positiv: Durchlauf Verfügbarkeit → Anfahrt → Wartezeit → Verfügbarkeit → Pause → Ende. Negativ: unzulässiger Übergang; Zuweisung an pausierendes Fahrzeug. Konkurrierend: Statuswechsel auf Störung parallel zu einer neuen Zuweisung – keine Zusage.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-25.
  - extern: WP-M2-15, WP-M2-16 (Fahrzeugkandidatenfilter der Disposition fragt die Disponierbarkeit ab (WP-M2-15); Zustandsautomat `vehicle_run.state` (WP-M2-16))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Pausenplanung und Arbeitszeitrecht (M4).

### WP-M3-32 – Unterstützungshinweise nur für zugeordnetes Personal
- **Ziel:** Hinweise zu Rollstuhlbedarf und Ein-/Ausstiegsunterstützung sieht nur der Fahrer, dem die Beförderung aktuell zugeordnet ist.
- **Anforderungen:** `LH-6.6-DRV-008`
- **Liefergegenstände:**
  - Backend: Feldfreigabe der Unterstützungsmerkmale in der Fahrerprojektion ausschließlich für Stopps der eigenen, aktuellen Schicht; Zugriff wird protokolliert; keine Diagnosen.
  - API: Felder in `GET /v1/driver/shifts/{id}/run`.
  - Oberfläche: UI-10 – „notwendige Unterstützung" am Stopp.
- **Detailverträge vorab:** Feldliste der transportrelevanten Unterstützungsmerkmale; Sichtbarkeitsfenster (ab wann vor dem Stopp, bis wann danach).
- **Akzeptanzkriterien:**
  1. Der zugeordnete Fahrer sieht Rollstuhlbedarf und Unterstützungshinweis am betreffenden Stopp (DRV-008).
  2. Ein unbeteiligter Fahrer desselben Mandanten erhält diese Daten über keinen Endpunkt, auch nicht bei bekannter Buchungs-ID (DRV-008).
  3. Nach Abschluss der Fahrt bzw. Schichtende sind die Hinweise für den Fahrer nicht mehr abrufbar und nicht mehr im lokalen Cache.
  4. Die Hinweise enthalten nur transportrelevante Merkmale (Rollstuhl, Ein-/Ausstiegshilfe), keine Diagnosen; jeder Abruf ist protokolliert (DRV-008).
- **Tests:** Positiv: zugeordneter Fahrer. Negativ: anderer Fahrer, andere Schicht, anderer Mandant, Abruf nach Fahrtende, Inspektion des lokalen Speichers nach Schichtende, Suche in Logs und Push-Nutzlasten nach Unterstützungsmerkmalen. Konkurrierend: der Stopp wird per Planrevision einem anderen Fahrer zugeordnet, während der bisherige Fahrer abruft – ab dem Commit erhält nur noch der neue Fahrer die Hinweise.
- **Abhängigkeiten:** intern: WP-M3-26.
  - extern: WP-M1-31 (Feldschutz für Mobilitäts- und Unterstützungsmerkmale)
  - extern: WP-M2-14, WP-M2-23 (Unterstützungsbedarf an Buchung/`passenger_trip`)
- **Entscheidungen:** D-11 (Löschfristen) – blockiert nur die Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Leitstellensicht auf Unterstützungsmerkmale (M1-Feldschutz, WP-M3-37).

### WP-M3-33 – Offline-Prüfung und Offline-Entwürfe
- **Ziel:** Prüfung und Schadenmeldung lassen sich ohne Verbindung erfassen; eine Einsatzfreigabe oder Sperraufhebung entsteht dabei nie ohne Serverbestätigung, und der Abschluss wird genau einmal wirksam.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-09 – Offline-Zustand der Prüfung mit getrennten Anzeigen „lokal abgeschlossen", „synchronisiert", „freigegeben"; UI-10 – Schadenentwurf mit lokalem Status.
  - Adapter/Worker: Einreihung von Prüfabschluss, Fotos und Schadenmeldung in die Outbox aus WP-M3-24; Fortsetzen unterbrochener Uploads.
  - Backend: Submit akzeptiert Gerätezeit getrennt von Serverempfangszeit; Neubewertung beim Empfang (Sperren, Vorlagenrevision, Schicht noch gültig).
- **Detailverträge vorab:** Verhalten, wenn zwischen Offline-Abschluss und Sync eine Sperre, ein Fahrzeugwechsel oder eine neue Vorlagenrevision eingetreten ist.
- **Akzeptanzkriterien:**
  1. Eine offline abgeschlossene Prüfung zeigt „Freigabe ausstehend"; das Fahrzeug bleibt serverseitig ohne Freigabe (AT-31).
  2. Nach Wiederverbindung wird der Abschluss genau einmal verarbeitet, auch bei mehrfacher Übertragung (AT-31).
  3. Ist zwischenzeitlich eine Sperre entstanden, führt der verspätete Abschluss nicht zur Freigabe.
  4. Ein offline erfasster Schaden entsteht nach Sync genau einmal mit Fallnummer.
  5. Die für die Offline-Prüfung nötige Vorlagenrevision und die Liste der Altschäden liegen nach dem letzten Online-Kontakt lokal vor; fehlt die Vorlage, lässt sich offline keine Prüfung beginnen und die App sagt das.
  6. Ein offline erfasstes kritisches Nein weist den Fahrer sofort an, nicht zu fahren und die Leitstelle zu kontaktieren; die serverseitige Sperre entsteht beim Sync (PH-AT-020: Offline-Prüfung erzeugt keine operative Serverfreigabe).
- **Tests:** Positiv: AT-31. Negativ: Offline-Abschluss, danach Sperre, dann Sync → keine Freigabe; Sync nach Schichtende. Konkurrierend: Sync derselben Prüfung von zwei App-Instanzen. UI-Abnahmefall „Driverprüfpfad mit kritischem Nein und Offline-Sync" (Kap. 06 §6.9).
- **Abhängigkeiten:** intern: WP-M3-14, WP-M3-16, WP-M3-24, WP-M3-44. extern: keine.
- **Entscheidungen:** D-11 (Notbetrieb: neue technische Freigaben bleiben serverpflichtig) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Offline-Belegerfassung (M4, E2E-069).

### WP-M3-34 – Positionserfassung und Standortalter
- **Ziel:** Die Driver-App liefert Positionen mit Mess- und Empfangszeit; der Server kennzeichnet veraltete Standorte, statt eine Liveposition vorzutäuschen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: `mobile/driver` – Positionserfassung während des angetretenen Dienstes mit Hinweis für den Fahrer.
  - Backend: Annahme von Positionsmeldungen nur für die eigene, angetretene Schicht; Speicherung von Messzeit, Empfangszeit, Genauigkeit/Qualität; Ableitung „letzte Position" und Standortalter; Veraltet-Kennzeichen.
  - Datenbank: `position_sample` mit Index `(tenant_id,vehicle_id,measured_at DESC)`.
  - API: Positionsannahme (Pfad im Detailvertrag, OP-04); `/v1/operator/vehicles` liefert letzte Position, Messzeit und Veraltet-Kennzeichen.
  - Konfigurationsparameter: Schwelle des Standortalters (Planungsdefault 30 s im Normalbetrieb aus Kap. 01 §1.8 und Kap. 08; Zuordnung zu einem Katalogparameter siehe OP-04).
- **Detailverträge vorab:** OpenAPI der Positionsannahme; Sendeintervall und Bündelung; Aufbewahrung der Rohpositionen; Minimierung (Erfassung nur im Dienst).
- **Akzeptanzkriterien:**
  1. Bei unterbrochenem Positionsstrom zeigt die Leitstelle nach Überschreiten der Schwelle „veraltet" mit letztem Stand; es wird keine extrapolierte Position als live dargestellt (AT-10).
  2. Die Gerätezeit wird nicht als vertrauenswürdige Serverzeit behandelt; Mess- und Empfangszeit sind getrennt gespeichert.
  3. Außerhalb eines angetretenen Dienstes werden keine Positionen angenommen oder gespeichert.
  4. Eine Position für ein fremdes Fahrzeug wird abgewiesen.
  5. Rohpositionen werden nach der im Aufbewahrungsregister (WP-M1-33) hinterlegten Frist gelöscht; ohne hinterlegte Frist ist die Erfassung in Produktion nicht aktivierbar (D-10, D-11).
  6. Nach Wiederverbindung nachgelieferte Positionen werden mit ihrer Messzeit gespeichert und nicht als aktuelle Position dargestellt.
  7. Im Normalbetrieb liegt das Standortalter bei höchstens 30 s (Planungsdefault aus Kap. 08 §8.6; Messung aus Erfassungs- und Empfangszeit).
- **Tests:** Positiv: laufender Strom, Standortalter unter der Schwelle. Negativ: AT-10; Position ohne Dienst; Position mit Zukunftszeit. Konkurrierend: verspätete ältere Position überschreibt die neuere „letzte Position" nicht.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-24, WP-M3-25.
  - extern: WP-M1-33, WP-M1-32 (Aufbewahrungs- und Löschregeln, Dateninventar)
- **Entscheidungen:** D-09 (Telematik) und D-10 (Fahrerdaten, Mitbestimmung, DSFA) – blockieren nur die Produktivschaltung; D-05 (GPS-Frequenz) – beeinflusst das Sendeintervall; D-11 (Aufbewahrung der Standortdaten).
- **Größe:** M
- **Nicht im Umfang:** Fahrzeugtelematik-Adapter; Fahrbeobachtungen für ETA (`driving_observation`, M8); Partitionierung.

### WP-M3-35 – Live-Aktualisierungskanal für Leitstelle und Fahrer
- **Ziel:** Geöffnete Leitstellen- und Fahreransichten erhalten Zustandsänderungen ohne Neuladen, mandanten- und dienstgenau gefiltert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: serverseitiger Push-Kanal im BFF (Technik laut Detailvertrag), gespeist aus Outbox-Ereignissen; Autorisierung je Abonnement (Mandant, Dienst, Rolle); Wiederaufsetzen nach Verbindungsabbruch mit letzter gesehener Revision.
  - Oberfläche: `web/operator` – gemeinsamer Client-Baustein mit Datenstand-Anzeige, zurückhaltender Live-Region und Veraltet-Kennzeichnung.
- **Detailverträge vorab:** Kanalprotokoll, Nachrichtenhülle, Aktualisierungsintervall und Rückfall auf Polling (OP-05).
- **Akzeptanzkriterien:**
  1. Ein Abonnent erhält ausschließlich Ereignisse seines Mandanten und seiner berechtigten Dienste.
  2. Nach Verbindungsabbruch holt der Client den aktuellen Stand nach; kein Ereignis führt zu einem dauerhaft falschen Anzeigezustand.
  3. Die Ansicht zeigt den Datenstand und kennzeichnet sich bei fehlendem Kanal als veraltet.
  4. Nach Sitzungssperre endet das Abonnement.
  5. Nachrichten enthalten nur Referenzen und Statusfelder der berechtigten Projektion, keine Rohpayloads mit Personendaten.
  6. Nach Entzug eines Dienstrechts erhält das laufende Abonnement keine Ereignisse dieses Dienstes mehr.
- **Tests:** Positiv: Zustandsänderung erscheint ohne Neuladen. Negativ: Abonnement auf fremden Mandanten/Dienst, gesperrte Sitzung, Kanalabbruch. Konkurrierend: zwei Tabs mit unterschiedlichem Mandantenkontext bleiben getrennt (UI-Abnahmefall „Mandantenwechsel mit zwei Tabs", Kap. 06 §6.9); Rechteentzug während offener Verbindung.
- **Abhängigkeiten:** intern: WP-M3-01.
  - extern: WP-M1-14, WP-M1-10, WP-M1-43 (Outbox-Relay, Sitzungssperre, Web-Designsystem)
  - extern: WP-M2-28 (Änderungsstrom für Clients – dasselbe Protokoll wird wiederverwendet, kein zweiter Kanaltyp)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fahrgast-Livebegleitung (M2/M7); Lastnachweis (M5).

### WP-M3-36 – Leitstelle Livebetrieb: Fahrzeuge, Position, Verfügbarkeit
- **Ziel:** Die Leitstelle sieht aktive Fahrzeuge ihres berechtigten Dienstes mit letzter Position, Standortalter, Verfügbarkeit und Aufträgen, als Karte und als gleichwertige Liste.
- **Anforderungen:** `LH-6.7-OPS-001`
- **Liefergegenstände:**
  - Backend: Leitstellenprojektion je Fahrzeug (Status, Freigabe/Sperre mit Grund, letzte Position mit Messzeit, laufende und nächste Aufträge); Filter nach Dienst, Status, offenen Störungen.
  - API: `GET /v1/operator/vehicles` (Liste mit Filtern, `{items,next_cursor,as_of}`), Detail.
  - Oberfläche: Bereichsrahmen UI-07 mit Navigation (im Web-Anwendungsrahmen WP-M1-43), in den WP-M3-07, WP-M3-09, WP-M3-12, WP-M3-16, WP-M3-18, WP-M3-22 ihre UI-07-Anteile einhängen.
  - Oberfläche: UI-07 Operator Livebetrieb – Kopf „Mandant / Region · Datenstand", Fahrzeugliste und Karte, Detailpanel; Zustände Sperre, veralteter Standort, keine Fremdrechte.
- **Detailverträge vorab:** OpenAPI `/v1/operator/vehicles`; Filterschema; Kartenquelle.
- **Akzeptanzkriterien:**
  1. Im Testbetrieb mit zwei Diensten sieht ein nur für Dienst A berechtigter Disponent ausschließlich Fahrzeuge im Dienst A; ein Filter kann die Berechtigung nur weiter einschränken (OPS-001).
  2. Je Fahrzeug werden letzte Position mit Alter, Verfügbarkeit und Aufträge angezeigt (OPS-001).
  3. Die Liste ist ohne Karte vollständig bedienbar (Tastatur, Screenreader).
  4. Fahrzeuge eines anderen Mandanten erscheinen nie.
  5. Die drei Zustände des Katalogs sind prüfbar dargestellt: Sperre (mit Grund), veralteter Standort (mit letztem Stand), keine Fremdrechte (Fremdfahrzeuge nicht vorhanden statt ausgegraut); zusätzlich Leerzustand und Ladefehler mit Datenstand.
  6. Die Liste liefert `{items,next_cursor,as_of}`; `limit` über 100 und manipulierte Cursor werden abgewiesen.
- **Tests:** Positiv: Filter nach Dienst und Status. Negativ: Zugriff auf Fahrzeug eines nicht berechtigten Dienstes (404), fremder Mandant, manipulierter Filter. Konkurrierend: dem Disponenten wird das Recht am Dienst entzogen, während Liste und Live-Kanal offen sind – ab dem Entzug erscheinen keine Fahrzeuge dieses Dienstes mehr; parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen bleiben getrennt. Besteht AT-10 gemeinsam mit WP-M3-34.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-31, WP-M3-34, WP-M3-35.
  - extern: WP-M1-08, WP-M1-43 (Dienst- und Objektberechtigung, Web-Anwendungsrahmen)
  - extern: WP-M2-16, WP-M2-40 (Aufträge je Fahrzeug aus dem Fahrtenplan (WP-M2-16); Kartenbaustein und Kartenquelle (WP-M2-40, D-09))
- **Entscheidungen:** D-09 (Karten) – blockiert nur die Produktivschaltung der Kartenquelle.
- **Größe:** M
- **Nicht im Umfang:** Föderationsübersicht und Partnerpositionen (M6); Klärarbeitsplatz UI-08 (M6).

### WP-M3-37 – Auftragsliste mit Suche
- **Ziel:** Die Leitstelle findet jede Buchung über Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug und Status.
- **Anforderungen:** `LH-6.7-OPS-002`
- **Liefergegenstände:**
  - Backend: Suchabfrage über Buchungen des berechtigten Dienstes mit den fünf Suchfeldern; Fahrgastsuche ohne Klartext-PII in Logs und Metriklabels.
  - API: Leitstellen-Listenabfrage auf `/v1/bookings` mit Filtern (bzw. Operator-Variante laut Detailvertrag), Cursor-Paginierung, stabiler Sortierschlüssel.
  - Oberfläche: UI-07 – Auftragsliste mit Suchleiste, Sortierung, Leerzustand.
  - Datenbank: Suchindizes mit führender `tenant_id`.
- **Detailverträge vorab:** Filterschema; Suchverfahren für den Fahrgast bei verschlüsselten Kontaktdaten (OP-26).
- **Akzeptanzkriterien:**
  1. Eine bestehende Testbuchung ist über jedes der fünf Suchfelder einzeln auffindbar: Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug, Status (OPS-002).
  2. Kombinierte Filter liefern die Schnittmenge.
  3. Buchungen nicht berechtigter Dienste oder fremder Mandanten erscheinen in keinem Suchergebnis.
  4. Suchbegriffe zu Personen stehen weder in Logs noch in Metriklabels.
  5. Die Trefferliste zeigt je Buchung nur die für die Disposition nötigen Felder; Unterstützungsmerkmale erscheinen nur nach Feldfreigabe der Rolle (WP-M1-31).
  6. Keine Treffer ergibt einen ausdrücklichen Leerzustand, kein Fehlerbild; die Antwort nennt `as_of`.
- **Tests:** Positiv: je Suchfeld ein Treffer. Negativ: fremder Dienst, fremder Mandant, `limit` > 100, manipulierter Cursor, Fahrgastsuche durch eine Rolle ohne Recht auf Fahrgastdaten. Konkurrierend: parallele Suchen zweier Mandanten über wiederverwendete Poolverbindungen liefern nur eigene Buchungen; Rechteentzug am Dienst während einer paginierten Suche – die nächste Seite liefert keine Treffer dieses Dienstes.
- **Abhängigkeiten:** intern: WP-M3-36.
  - extern: WP-M1-31, WP-M1-02 (Feldschutz und Listenkonventionen)
  - extern: WP-M2-23, WP-M2-14 (Buchungen (`booking`, `passenger_trip`) und Buchungsnummer; Format der Buchungsnummer ist M2 Offener Punkt 42)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Callcenter-Arbeitsplatz UI-06 (M7); Serienbuchungen (M7).

### WP-M3-38 – Fahrzeugbezogene Stoppreihenfolge, Prognose und Besetzung
- **Ziel:** Die Leitstelle sieht je Fahrzeug Stoppreihenfolge, prognostizierte Zeiten und aktuelle Besetzung; das Umschalten zwischen Fahrzeugen verändert die Anzeige konsistent.
- **Anforderungen:** `LH-6.7-OPS-003`
- **Liefergegenstände:**
  - Backend: Projektion des Fahrzeugplans (Stopps, Sollzeit, Prognose, Zusagefenster) und der Besetzung je Ressourcendimension aus Fahrerereignissen.
  - API: Fahrzeugplan im Detail von `/v1/operator/vehicles` bzw. `GET /v1/runs/{id}/revisions` (aktuelle Revision).
  - Oberfläche: UI-07 – Detailpanel Fahrzeugplan mit Planrevision und Datenstand.
- **Detailverträge vorab:** Antwortschema Fahrzeugplan; Definition „aktuelle Besetzung" (aus `APPLIED`-Ereignissen; offene `REVIEW_REQUIRED`-Fälle werden ausgewiesen).
- **Akzeptanzkriterien:**
  1. Für zwei Fahrzeuge mit unterschiedlichen Plänen zeigt die Ansicht nach dem Umschalten jeweils nur Stopps, Zeiten und Besetzung des gewählten Fahrzeugs; es bleiben keine Daten des vorherigen stehen (OPS-003).
  2. Die Besetzung entspricht der Differenz bestätigter Ein- und Ausstiege je Dimension (OPS-003).
  3. Prognosezeiten sind von Zusagefenster und Sollzeit unterscheidbar; fehlende Prognose wird ausgewiesen.
- **Tests:** Positiv: Umschalten A ↔ B während einer Planänderung an A. Negativ: Fahrzeug ohne Run (Leerzustand); Fahrzeug fremden Dienstes. Konkurrierend: schnelles Umschalten während eintreffender Live-Updates – keine vermischte Anzeige.
- **Abhängigkeiten:** intern: WP-M3-29 (Besetzung aus Fahrerereignissen), WP-M3-36.
  - extern: WP-M2-16, WP-M2-19 (Fahrtenplan und Zusagefenster)
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Planvergleich zweier Revisionen (WP-M3-40).

### WP-M3-39 – Buchung durch die Disposition anlegen, ändern, stornieren
- **Ziel:** Ein berechtigter Disponent legt für einen Fahrgast eine Buchung an, ändert oder storniert sie; jede Änderung ist mit Akteur und Grund protokolliert.
- **Anforderungen:** `LH-6.7-OPS-004`
- **Liefergegenstände:**
  - Backend: Leitstellenkommandos auf demselben Tarif- und Kapazitätskern wie die Fahrgastbuchung (Angebot → Hold → Bestätigung, Storno); Pflichtgrund; Akteur getrennt vom Fahrgast im Audit. Buchung für einen Fahrgast mit `booking_subject` nach Kap. 05 §5.9: der Server bindet den Fahrgast und prüft die Legitimation des Disponenten bei Planung, Hold und Confirm; ein Subjektwechsel verlangt ein neues Angebot. Mindestumfang, weil M2 `DELEGATED` nicht liefert (OP-30).
  - Backend: Änderungskommando der Leitstelle als Mindestumfang, weil M2 die Bearbeitung bestätigter Buchungen nicht liefert (`LH-6.4-BKG-006`, P2): neues Angebot und Hold für den geänderten Wunsch, danach atomarer Tausch (alte Kapazität frei, neue belegt, ein `booking_change`-Eintrag); scheitert der neue Hold, bleibt die alte Buchung unverändert.
  - Datenbank: `booking_change` (angelegt in WP-M3-02; Akteur, Grund, alte und neue Zusage), Statushistorie der Buchung (WP-M2-25), `audit_event`.
  - API: `/v1/ride-offers`, `/v1/bookings` im Leitstellenkontext (Scope Disposition), Idempotency-Key und `If-Match`.
  - Oberfläche: UI-07 – „Eigene Buchung bearbeiten": Anlegen, Ändern, Stornieren mit Grundauswahl und Folgenanzeige.
- **Detailverträge vorab:** Rollen-/Scope-Zuschnitt für Leitstellenbuchungen; Grundkatalog; Regel zur Zustimmung des Fahrgasts bei Preis- oder Zeitänderung.
- **Akzeptanzkriterien:**
  1. Anlegen, Ändern und Stornieren durch den Disponenten sind möglich und hinterlassen je einen Eintrag mit Akteur, Zeitpunkt und Grund (OPS-004).
  2. Ohne Grund wird die Änderung abgewiesen (OPS-004).
  3. Ein Benutzer ohne Dispositionsrolle oder ohne Recht am Dienst erhält 403 bzw. 404 (OPS-004).
  4. Die Leitstellenbuchung kann die Kapazität nicht überbuchen und nutzt dieselbe Safety-Prüfung.
  5. Die Buchung ist an den Fahrgast gebunden, für den sie angelegt wurde; der Disponent erscheint als eigener Akteur, nicht als Fahrgast (OPS-004; Kap. 05 §5.9).
  6. Eine Änderung, deren neuer Wunsch nicht bedienbar ist, lässt die bestehende Buchung mit ihrer Zusage unverändert; es gibt keinen Zwischenzustand ohne Buchung (OPS-004).
  7. Änderung und Storno durch die Leitstelle lösen die Betriebsnachricht an den Fahrgast aus (WP-M2-34); Preis- oder Zeitänderungen, die eine Zustimmung brauchen, werden nicht ohne diese wirksam (Kap. 06 §6.5; Regel laut Detailvertrag, OP-30).
  8. Wiederholung mit demselben Idempotenzschlüssel erzeugt keine zweite Buchung, Änderung oder Stornierung.
- **Tests:** Positiv: Anlegen, Ändern, Stornieren. Negativ: fehlender Grund, fehlende Rolle, veraltete Revision (412). Konkurrierend: Disponent und Fahrgast buchen gleichzeitig den letzten Platz – genau eine Buchung; Disponent ändert, während der Fahrgast dieselbe Buchung storniert – genau ein Ergebnis, keine doppelt belegte oder verwaiste Kapazität; zwei Disponenten ändern dieselbe Buchung – eine Änderung gewinnt, die andere erhält 412.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-08, WP-M3-37.
  - extern: WP-M1-07, WP-M1-12, WP-M1-13 (Rollenmodell, Audit, Idempotenz)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-31, WP-M2-25, WP-M2-34 (Buchungskern (Angebot WP-M2-21, Hold WP-M2-22, Confirm WP-M2-23, Storno WP-M2-26 mit Erstattung WP-M2-31), Statushistorie (WP-M2-25), Auslöser der Benachrichtigungen (WP-M2-34). Änderung bestätigter Buchungen und `booking_subject=DELEGATED` liefert M2 nicht (OP-30).)
  - extern: WP-M1-28 (verifizierter Fahrgast)
- **Entscheidungen:** D-12 (Vorziehen von P2-Umfang: Buchungsänderung, Buchung für Dritte) – blockiert den Bau des Mindestumfangs nicht, entscheidet aber, ob er genügt.
- **Größe:** L
- **Nicht im Umfang:** Vertretungsprüfung und Callcenter-Zustimmung (UI-06, M7); Zahlungsabwicklung (M2).

### WP-M3-40 – Manuelle Umplanung mit Begründung und Plan-CAS
- **Ziel:** Die Leitstelle kann Stopps und Buchungen eigener freigegebener Ressourcen manuell umplanen; jede Änderung ist begründet, gegen die Planrevision abgesichert und sicherheitsgeprüft.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Kommando „neue Planrevision" mit erwarteter Revision (Compare-and-Set), Pflichtbegründung, Sicherheitsprüfung im Commit (WP-M3-08), Prüfung harter Zusagegrenzen; Konfliktantwort mit aktueller Revision; Planvergleich alt/neu.
  - Datenbank: `vehicle_run.revision`, `route_stop` (M2-Tabelle; Stoppfolge eindeutig je Runrevision; geschrieben nur über die Modul-API von `booking-dispatch`), `booking_change` (aus WP-M3-02).
  - API: `POST /v1/runs/{id}/revisions` (`If-Match`), `GET /v1/runs/{id}/revisions`.
  - Oberfläche: UI-07 – Umplanung per Dialog und Tastatur (Alternative zu Drag-and-drop), Folgenanzeige, 412-Konflikt ohne stilles Überschreiben.
  - Konfigurationsparameter: `protected_stop_horizon_min`, `max_reassignments`.
- **Detailverträge vorab:** OpenAPI `/v1/runs/{id}/revisions`; Ausnahmeprozess für den geschützten Stopphorizont; Zuständigkeit M2/M3 für den Plan-Commit.
- **Akzeptanzkriterien:**
  1. Eine Umplanung mit veralteter Revision ergibt `412 REVISION_MISMATCH`; ohne `If-Match` 428.
  2. Eine Umplanung ohne Begründung wird abgewiesen; Akteur und Begründung stehen im Audit.
  3. Eine Umplanung auf ein gesperrtes oder nicht freigegebenes Fahrzeug scheitert im Commit.
  4. Zusagefenster bestätigter Buchungen werden nicht still verletzt; eine Verletzung verlangt den dokumentierten Ausnahmeweg.
  5. Umgeplant werden können nur eigene, freigegebene Ressourcen des berechtigten Dienstes; ein fremder Run ergibt 404 (Kap. 06 §6.5).
  6. Die Kapazität des Zielfahrzeugs wird je Ressourcendimension im selben Commit geprüft; eine Umplanung, die überbucht, scheitert.
  7. Vor dem Bestätigen zeigt der Dialog den Planvergleich alt/neu mit den Folgen für betroffene Fahrgäste; die neue Revision erreicht Fahrer und Fahrgäste über WP-M2-28.
  8. `max_reassignments` und `protected_stop_horizon_min` werden mit dem zum Commit wirksamen Wert geprüft; Überschreitung ohne Ausnahme ergibt eine fachliche Ablehnung mit stabilem Fehlercode.
- **Tests:** Positiv: Buchung von Fahrzeug A auf B verschieben. Negativ: veraltete Revision, fehlende Begründung, gesperrtes Ziel, Stopp im geschützten Horizont. Konkurrierend: zwei Disponenten ändern denselben Run – genau eine Revision gewinnt; Umplanung parallel zur automatischen Disposition. UI-Abnahmefall „412-Konflikt ohne stilles Überschreiben" (Kap. 06 §6.9).
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-08, WP-M3-38.
  - extern: WP-M1-21, WP-M1-12 (wirksame Konfiguration, Audit)
  - extern: WP-M2-16, WP-M2-19, WP-M2-22, WP-M2-28 (Fahrtenplanmodell und Routeninvarianten (WP-M2-16), Schutz zugesagter Zeitfenster (WP-M2-19), Kapazitätssperre (WP-M2-22), Verteilung der Planrevision (WP-M2-28))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** automatische Neuoptimierung (M2/M7); Partnerübernahme (M6).

### WP-M3-41 – Operative Fahrzeugsperre durch die Leitstelle
- **Ziel:** Die Leitstelle kann ein gestörtes Fahrzeug sperren und damit aus zukünftigen Zuweisungen nehmen.
- **Anforderungen:** `LH-6.7-OPS-005`
- **Liefergegenstände:**
  - Backend: Kommando „operative Sperre setzen/aufheben" mit Grund über WP-M3-08; operative Sperre ist von sicherheitskritischen Sperren getrennt (unterschiedliche Aufhebungsrechte).
  - API: Sperraktion an `/v1/operator/vehicles/{id}` (Pfad im Detailvertrag) mit `If-Match`.
  - Oberfläche: UI-07 – Aktion „Fahrzeug sperren" mit Pflichtgrund; Anzeige „gesperrt" mit Grund und Urheber.
  - Ereignisse/Outbox: Fahrzeug gesperrt (Quelle Leitstelle) – Auslöser für WP-M3-53.
- **Detailverträge vorab:** Sperrgrundkatalog der Leitstelle; wer eine operative Sperre aufheben darf.
- **Akzeptanzkriterien:**
  1. Nach der Sperre wird dem Fahrzeug keine neue Anfrage zugewiesen (OPS-005).
  2. Die Sperre ist mit Akteur, Grund und Zeitpunkt protokolliert und in der Fahrzeugliste sichtbar (OPS-005).
  3. Das Aufheben einer operativen Sperre hebt eine gleichzeitig bestehende sicherheitskritische Sperre nicht auf.
  4. Die Sperre wirkt nur auf Fahrzeuge des berechtigten Dienstes; ein fremdes Fahrzeug ergibt 404. Wiederholtes Sperren mit demselben Idempotenzschlüssel erzeugt keinen zweiten Sperrgrund.
  5. Die Sperre erzeugt das Ereignis, aus dem der Störungsablauf die bereits zugesagten Fahrten ermittelt; sie bleiben bis dahin sichtbar dem Fahrzeug zugeordnet (OPS-005).
- **Tests:** Positiv: sperren, Anfrage geht an ein anderes Fahrzeug. Negativ: Sperre ohne Grund, ohne Rolle; Aufhebungsversuch der kritischen Sperre über diesen Weg. Konkurrierend: Sperre parallel zu einem Hold-Commit – keine Zusage auf das gesperrte Fahrzeug.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-36. extern: keine.
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Behandlung bereits zugesagter Fahrten (WP-M3-54).

### WP-M3-42 – Überfälligkeit und ausstehende Informationen
- **Ziel:** Auftrags- und Fahrzeugansichten kennzeichnen Überfälligkeit und ausstehende Informationen; das Überschreiten einer definierten Schwelle löst einen sichtbaren Hinweis mit Betroffenenzahl aus.
- **Anforderungen:** `LH-6.7-OPS-006`
- **Liefergegenstände:**
  - Backend: zeitgesteuerte Auswertung gegen Schwellen (überfällige Abholung, ausbleibende Fahrerbestätigung einer Planrevision, veralteter Standort, offener `REVIEW_REQUIRED`-Fall); Berechnung der Zahl betroffener Fahrgäste; Hinweis über WP-M3-52.
  - API: Kennzeichen in `/v1/operator/vehicles` und der Auftragsliste; Arbeitsliste offener Hinweise.
  - Oberfläche: UI-07 – Kennzeichnung in Liste und Detail, Arbeitsliste mit Betroffenenzahl.
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`, `alert_escalation_delay_min`; weitere Schwellen laut OP-06.
  - Adapter/Worker: periodischer Auswertungsjob mit Lease.
- **Detailverträge vorab:** Liste der Überfälligkeitsarten mit Schwelle und Parameter (OP-06); Definition „Betroffenenzahl".
- **Akzeptanzkriterien:**
  1. Wird die konfigurierte Schwelle überschritten, erscheint ein Hinweis mit der Zahl der Betroffenen in Auftrags- und Fahrzeugansicht (OPS-006).
  2. Unterhalb der Schwelle erscheint kein Hinweis; nach Wegfall der Ursache verschwindet die Kennzeichnung (OPS-006).
  3. Ausstehende Informationen (z. B. fehlende Fahrerbestätigung) sind als eigener Zustand gekennzeichnet, nicht als Verspätung (OPS-006).
  4. Derselbe Sachverhalt erzeugt genau einen offenen Hinweis.
  5. Die Schwelle wird aus der wirksamen Konfiguration des Dienstes gelesen; eine Änderung wirkt auf neue Auswertungen, nicht rückwirkend auf bereits ausgelöste Hinweise.
  6. Die Betroffenenzahl zählt nur Fahrgäste bzw. Buchungen des berechtigten Dienstes; der Hinweis enthält keine Namen.
- **Tests:** Positiv: Schwelle überschritten → Hinweis mit korrekter Zahl. Negativ: Schwelle nicht erreicht; Ursache behoben. Konkurrierend: zwei Auswertungsläufe gleichzeitig → ein Hinweis.
- **Abhängigkeiten:** intern: WP-M3-27, WP-M3-29, WP-M3-34, WP-M3-36, WP-M3-37, WP-M3-52.
  - extern: WP-M1-21, WP-M1-15 (wirksame Konfiguration, Lease für periodische Jobs)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Eskalationsketten und Empfehlungen (AUT P2, M8); Anschlussrisiken (M6).

### WP-M3-43 – Schadenakte: Meldung, Pflichtdaten, Fallnummer
- **Ziel:** Fahrer und berechtigte Mitarbeiter melden Schäden unabhängig von Checkliste, Fahrt oder Schicht; jede Akte hat validierte Pflichtdaten und eine eindeutige Fallnummer.
- **Anforderungen:** `LH-6.22-DMG-001`, `LH-6.22-DMG-002`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`, Aggregat DefectCase; Anlage mit Referenz (Fallnummer), Mandant, Fahrzeug, Meldendem, Meldezeitpunkt, Ereigniszeit, Ereignisort, Kategorie, Beschreibung, Status „neu"; Fallnummernvergabe je Mandant.
  - Datenbank: `defect_case` mit eindeutiger Fallnummer je Mandant und Index über Mandant, Fahrzeug und Meldezeitpunkt.
  - API: `POST /v1/defects` (idempotent), `GET /v1/defects`, `GET /v1/defects/{id}`.
  - Oberfläche: UI-11 – Erfassung durch Mitarbeiter, Aktenansicht; UI-10 – Einstieg „Mangel melden" ohne aktive Fahrt (die mobile Erfassungsmaske liefert WP-M3-44).
  - Ereignisse/Outbox: Schaden gemeldet.
- **Detailverträge vorab:** OpenAPI `/v1/defects`; Fallnummernformat; Kategorienkatalog; Abbildung des Ereignisorts (OP-22).
- **Akzeptanzkriterien:**
  1. Ein Schaden lässt sich ohne aktive Buchung, Schicht oder Prüfung erfassen und erhält eine eindeutige Fallnummer (DMG-001).
  2. Fahrer und berechtigte Mitarbeiter können melden; ein Benutzer ohne Bezug zum Mandanten nicht (DMG-001).
  3. Fehlende Pflichtdaten (Fahrzeug, Kategorie, Beschreibung, Ereigniszeit) führen zu 422 mit Feldverweis (DMG-002).
  4. Die Akte ist über Fahrzeug und Datum eindeutig auffindbar (DMG-002).
  5. Wiederholte Übermittlung mit demselben Idempotenzschlüssel erzeugt keine zweite Akte.
  6. Mandant, Meldender, Meldezeitpunkt, Referenz und Anfangsstatus setzt der Server; entsprechende Angaben des Clients werden nicht übernommen (DMG-002).
  7. Ein Fahrer kann nur für Fahrzeuge melden, zu denen er einen Bezug hat (aktuelle oder laut Detailvertrag zulässige Schicht); die Meldung selbst verlangt aber weder Buchung noch laufende Prüfung (DMG-001).
  8. Die Fallnummer ist nicht aus Personendaten abgeleitet und über Mandanten hinweg nicht erratbar zuordenbar.
- **Tests:** Positiv: Meldung ohne Buchung. Negativ: fehlende Pflichtfelder, fremdes Fahrzeug, Ereigniszeit in der Zukunft. Konkurrierend: parallele Meldungen erhalten unterschiedliche, lückenlos eindeutige Fallnummern.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-03.
  - extern: WP-M1-13, WP-M1-08, WP-M1-12 (Idempotenz, Objektberechtigung, Audit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Fotos (WP-M3-44); Klassifikation (WP-M3-45); Kostenbezug und Schadenskosten (M4/M7, AT-57).

### WP-M3-44 – Mobile Schadenerfassung mit Fotoevidenz
- **Ziel:** Der Fahrer erfasst einen vollständigen Schaden mit Art, Fahrzeugbereich, beobachteter Auswirkung und Fotos in wenigen Schritten; die Fotos sind integritätsgesichert.
- **Anforderungen:** `LH-6.22-DMG-004`
- **Liefergegenstände:**
  - Oberfläche: UI-10 Driver Fahrt/Schaden – geführte Erfassung mit Auswahlfeldern (Art, Fahrzeugbereich, Auswirkung), Kamera, begrenztem Freitext; Entwurf mit lokalem Status.
  - Backend: Uploadticket mit Größe/MIME/Hash, Quarantäne bis zur Prüfung, SHA-256 je Datei, Metadaten in der Datenbank; signierter Download erst nach Objekt- und Zweckprüfung (Default höchstens 60 s, Kap. 02 §4).
  - Datenbank: `defect_evidence` (Objektschlüssel, SHA-256, MIME, Aufnahmedatum, Urheber; unveränderlich).
  - API: Evidenz-Upload unterhalb `/v1/defects/{id}` (Pfad im Detailvertrag, OP-13).
  - Adapter/Worker: Malware-/Medienprüfung; periodische Integritätsprüfung Objekt ↔ gespeicherter Hash.
- **Detailverträge vorab:** Uploadvertrag für Schadensbilder; Kataloge Art/Fahrzeugbereich/Auswirkung; Obergrenze der Schritte und der Freitextlänge.
- **Akzeptanzkriterien:**
  1. Ein vollständiger Musterfall (Art, Bereich, Auswirkung, ein Foto) lässt sich auf dem Fahrergerät in der im Detailvertrag festgelegten Höchstzahl an Schritten erfassen (DMG-004).
  2. Freie Felder sind auf die notwendigen Angaben begrenzt; Auswahlfelder sind Pflicht (DMG-004).
  3. Eine Datei mit abweichendem Hash oder unzulässigem MIME-Typ wird abgewiesen und nicht an die Akte gehängt.
  4. Eine nachträglich im Objektspeicher veränderte Datei wird von der Integritätsprüfung erkannt und gemeldet (AT-36, Teil Medienintegrität).
  5. Die Meldung enthält Art, Fahrzeugbereich, beobachtete Auswirkung und mindestens ein Foto als Pflichtangaben laut Detailvertrag; fehlt eine, wird sie mit Feldverweis abgewiesen (DMG-004).
  6. Ein Foto ist erst nach bestandener Medienprüfung an der Akte sichtbar und herunterladbar; bis dahin zeigt die Akte „in Prüfung".
  7. Fotos liegen unter dem Mandantenpräfix; ein signierter Link eines anderen Mandanten oder ein abgelaufener Link funktioniert nicht.
  8. Die Erfassung ist mit Screenreader und ohne Farbe bedienbar; Entwurf, „synchronisiert" und „angenommen" sind getrennte Anzeigen (Kap. 06 §6.7).
- **Tests:** Positiv: AT-33 (Schaden mit Foto und Fahrzeugbereich, Fallnummer, Medienprüfung). Negativ: falscher Hash, übergroße Datei, ausführbare Datei, Download ohne Berechtigung. Konkurrierend: doppelter Upload derselben Datei nach Timeout – ein Evidenzeintrag.
- **Abhängigkeiten:** intern: WP-M3-24, WP-M3-43.
  - extern: WP-M1-11 (mandantengetrennter Objektspeicher)
  - extern: WP-M4-21 (Uploadticket, Quarantäne und Malware-Scan des Dokumentenworkers, gemeinsamer Baustein im Modul `documents-finance`. Liegt WP-M4-21 beim M3-Gate nicht vor, ist der Teil „Medienprüfung" von AT-33 nicht abnehmbar (OP-13).)
- **Entscheidungen:** D-11 (Aufbewahrung der Schadensbilder) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Bildanalyse; Dublettenhinweis (OP-12).

### WP-M3-45 – Schadenklassifikation und Triage
- **Ziel:** Schäden werden regelbasiert als sicherheitskritisch, einsatzrelevant, kosmetisch oder sonstig eingestuft; die Einstufung ist korrigierbar, und sicherheitskritisch sperrt die betroffene Einsatzressource unmittelbar.
- **Anforderungen:** `LH-6.22-DMG-005`
- **Liefergegenstände:**
  - Backend: Regelwerk (Kategorie/Bereich/Auswirkung → Klasse) mit Regelversion; Triage-Kommando zur Bestätigung oder Korrektur mit Grund und Zuständigem; Sperre über WP-M3-08 bei sicherheitskritisch im selben Commit.
  - Datenbank: `defect_case` (Klasse, Regelversion, Klassifikationshistorie, Zuständiger).
  - API: `POST /v1/defects/{id}/triage` (`If-Match`).
  - Oberfläche: UI-11 – Triageansicht mit vorgeschlagener Klasse, Korrektur und Begründung.
  - Konfigurationsparameter: `workshop_auto_block_severity` (Validierung „Kritischer Sicherheitsschaden immer sperrend").
- **Detailverträge vorab:** Regelmatrix; Enum-Werte von `workshop_auto_block_severity` und Abbildung auf die vier Klassen sowie auf „S0" aus PH-AT-020 (OP-10); Rolle für die Herabstufung.
- **Akzeptanzkriterien:**
  1. Jede Akte trägt genau eine der vier Klassen und die verwendete Regelversion (DMG-005).
  2. Eine als sicherheitskritisch eingestufte Akte sperrt das Fahrzeug im selben Commit (DMG-005).
  3. Die Einstufung ist durch eine berechtigte Rolle mit Grund korrigierbar; die vorherige Einstufung bleibt in der Historie (DMG-005).
  4. Eine Herabstufung von sicherheitskritisch hebt die Sperre nicht automatisch auf; die Wiederfreigabe läuft über WP-M3-51.
  5. Der Parameter lässt sich nicht so setzen, dass sicherheitskritische Schäden nicht sperren.
  6. Die Triage speichert den Zuständigen; die Statushistorie zeigt Klassifikation, Sperre und Zuweisung mit Akteur und Zeit (AT-34, P1-Anteil).
  7. Eine Hochstufung auf sicherheitskritisch erzeugt den Alarm nach der wirksamen `alert_policy_revision` über WP-M3-52 (AT-34, „Alarm nach Regelmatrix" im P1-Umfang).
- **Tests:** Positiv: je Klasse ein Fall; Teil von AT-34 (klassifizieren, sperren, zuweisen, Statushistorie). Negativ: Triage ohne Rolle, ohne Grund, mit veralteter Revision. Konkurrierend: Hochstufung parallel zu einer Zuweisung – keine Zusage.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-43, WP-M3-52.
  - extern: WP-M1-21, WP-M1-07 (wirksame Konfiguration, Rollenmodell)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Bearbeitungsfristen, Wiedervorlage und Eskalation je Schadenskategorie aus AT-34 („Frist auslösen") – getragen von P2-Anforderungen in WP-M7c-33 und WP-M7c-45 (OP-12); Werkstattzuweisung (M7).

### WP-M3-46 – Sicherheitskritische Meldung: Sofortsperre und priorisierter Alarm
- **Ziel:** Eine neue sicherheitskritische Schadenmeldung macht das Fahrzeug sofort nicht disponierbar und informiert die Leitstelle priorisiert.
- **Anforderungen:** `LH-6.22-DMG-006`
- **Liefergegenstände:**
  - Backend: Annahme der Meldung, Regelklassifikation und Sperre in einer Transaktion; Alarm höchster Priorität ohne Eskalationsverzögerung; Auslösen des Störungsablaufs.
  - Datenbank: `defect_case`, `vehicle.safety_revision`, `resource_release.revoked_at`, `business_alert`.
  - Oberfläche: UI-10 – Sperrmeldung mit Vorgangsnummer und Kontakt zur Leitstelle; UI-07 – priorisierter Alarm, Fahrzeug „gesperrt · kritischer Mangel".
  - Konfigurationsparameter: `alert_escalation_delay_min` (Validierung „Kritische Ereignisse ohne Verzögerung").
  - Kennzahlen: Datengrundlage für `Ausfallminuten kritisch`.
  - Ereignisse/Outbox: Fahrzeug gesperrt (Quelle Schaden).
- **Detailverträge vorab:** Alarmpriorität und Empfängerkreis; Verhalten bei offline erfasster kritischer Meldung (Sperre wirkt ab Serverempfang; App weist den Fahrer sofort an, die Leitstelle zu kontaktieren).
- **Akzeptanzkriterien:**
  1. Nach der Meldung werden keine neuen Buchungen an das betroffene Fahrzeug vergeben (DMG-006).
  2. Die Leitstelle erhält den Alarm priorisiert und ohne Verzögerung; er nennt Fahrzeug, Fallnummer und Zahl der betroffenen Fahrten (DMG-006, AT-28).
  3. Wird die Meldung gleichzeitig mit einer neuen Zuweisung verarbeitet, entsteht keine Fahrtzusage mit dem unsicheren Fahrzeug (PH-AT-020).
  4. Bestehende Zuweisungen gehen in den Störungsablauf über, statt still bestehen zu bleiben.
  5. Sperre und Widerruf der `resource_release` stehen mit Fallnummer als Grund im `audit_event`; der Alarm enthält keine Fahrgastdaten.
  6. Eine offline erfasste kritische Meldung sperrt ab Serverempfang; ein späteres, älteres Fahrerereignis hebt die Sperre nicht auf (Kap. 05 §5.5).
- **Tests:** Positiv: AT-28. Negativ: Meldung zu fremdem Fahrzeug; Doppelmeldung erzeugt keinen zweiten Alarm. Konkurrierend: PH-AT-020 (Schaden und Zuweisung in zwei parallelen Transaktionen, beide Reihenfolgen).
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-45, WP-M3-52. extern: keine.
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Ersatzdisposition (WP-M3-54).

### WP-M3-47 – Statusautomat der Schadenakte
- **Ziel:** Die Schadenakte durchläuft eindeutige Statusübergänge von neu über geprüft, in Bearbeitung und Reparatur bis abgeschlossen oder verworfen; unzulässige Sprünge verhindert der Server.
- **Anforderungen:** `LH-6.22-DMG-007`
- **Liefergegenstände:**
  - Backend: Zustandsautomat DefectCase (neu, geprüft, in Bearbeitung, Reparatur, abgeschlossen, verworfen) im Rahmen aus WP-M1-17; Rollen je Übergang; Statushistorie.
  - Datenbank: `defect_case.state` mit versioniertem CHECK; Statushistorie append-only.
  - API: Statusübergänge unterhalb `/v1/defects/{id}` (Pfade im Detailvertrag) mit `If-Match`.
  - Oberfläche: UI-11 – nur zulässige Folgeaktionen werden angeboten.
- **Detailverträge vorab:** Übergangstabelle mit Rollen und Vorbedingungen (z. B. „verworfen" nur mit Begründung; „abgeschlossen" bei sicherheitskritisch nur mit Freigabeentscheidung).
- **Akzeptanzkriterien:**
  1. Jeder zulässige Übergang ist ausführbar und in der Historie mit Akteur und Zeit gespeichert (DMG-007).
  2. Ein unzulässiger Sprung (z. B. neu → abgeschlossen) wird serverseitig mit 409 abgewiesen, auch bei direktem API-Aufruf (DMG-007).
  3. Aus einem Endzustand gibt es keinen Übergang ohne dokumentierte Wiedereröffnung laut Detailvertrag.
  4. „Abgeschlossen" oder „verworfen" hebt eine bestehende Sperre nicht auf; die Entsperrung läuft ausschließlich über WP-M3-51.
  5. Ein Übergang an einer Akte eines anderen Mandanten oder eines nicht berechtigten Dienstes ergibt 404; ohne `If-Match` 428, mit veralteter Revision 412.
- **Tests:** Positiv: vollständiger Durchlauf beider Endzustände. Negativ: jeder nicht in der Tabelle enthaltene Übergang (tabellengetrieben); Übergang ohne Rolle. Konkurrierend: zwei gleichzeitige Übergänge – genau einer.
- **Abhängigkeiten:** intern: WP-M3-43.
  - extern: WP-M1-17 (Zustandsautomaten-Rahmen)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Fristüberwachung je Status (M7).

### WP-M3-48 – Unveränderliche Historie von Meldungen und Anhängen
- **Ziel:** Meldungen und Anhänge werden nie überschrieben; Nachträge tragen Autor, Zeit, Änderungsgrund und Version, und frühere Inhalte bleiben für berechtigte Prüfer lesbar.
- **Anforderungen:** `LH-6.22-DMG-008`
- **Liefergegenstände:**
  - Backend: Nachtragsmodell für Schadenakte und Evidenz (append-only Revisionen); Leseansicht „Stand zum Zeitpunkt t"; Prüferrolle.
  - Datenbank: Revisionstabelle zur `defect_case` (Autor, Zeit, Grund, Version), `defect_evidence` unveränderlich; Trigger gegen UPDATE/DELETE.
  - API: Nachtrag und Historie unterhalb `/v1/defects/{id}` (Pfade im Detailvertrag).
  - Oberfläche: UI-11 – Versionsverlauf mit Vergleich.
- **Detailverträge vorab:** DDL der Revisionen; Abgrenzung fachliche Historie ↔ `audit_event`; Verhältnis zu Löschfristen (kontrollierter Lifecycleprozess statt UPDATE/DELETE).
- **Akzeptanzkriterien:**
  1. Ein Nachtrag erzeugt eine neue Version mit Autor, Zeit und Änderungsgrund; ohne Grund wird er abgewiesen (DMG-008).
  2. Vorherige Inhalte und Anhänge bleiben für berechtigte Prüfer lesbar; für nicht berechtigte Rollen nicht (DMG-008).
  3. UPDATE/DELETE auf Meldungs- und Evidenzzeilen scheitern auf Datenbankebene.
  4. Ein „ersetzter" Anhang bleibt als frühere Version erhalten.
- **Tests:** Positiv: zwei Nachträge, Version 1 bleibt lesbar. Negativ: direkte Änderung, Nachtrag ohne Grund, Lesen der Historie ohne Prüferrolle. Konkurrierend: zwei gleichzeitige Nachträge – fortlaufende Versionen ohne Verlust.
- **Abhängigkeiten:** intern: WP-M3-43, WP-M3-44.
  - extern: WP-M1-12, WP-M1-33 (Audit append-only, Aufbewahrungsregeln)
- **Entscheidungen:** D-11 (Aufbewahrung) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Export der Schadenakte (AT-53, M5).

### WP-M3-49 – Schadenliste der Leitstelle mit Filtern
- **Ziel:** Die Operator-Oberfläche bietet eine Schadenliste, die nach Fahrzeug, Priorität, Status, Zeitraum, Depot und Verantwortlichem filter- und sortierbar ist.
- **Anforderungen:** `LH-6.22-DMG-009`
- **Liefergegenstände:**
  - Backend: Listenabfrage mit den sechs Filtern, stabiler Sortierung und Cursor.
  - Datenbank: Index `(tenant_id,state,updated_at)` und Filterindizes auf `defect_case`.
  - API: `GET /v1/defects` mit Filterschema, Antwort `{items,next_cursor,as_of}`.
  - Oberfläche: UI-11 Flotte/Werkstatt – Schadenliste mit Filterleiste, Sortierung, Leerzustand.
- **Detailverträge vorab:** Filterschema; Herkunft von „Depot" (Betriebshof aus WP-M1-09, Zuordnung zum Fahrzeug, OP-22) und „Priorität" (Ableitung aus der Klasse oder eigenes Feld).
- **Akzeptanzkriterien:**
  1. Testfälle sind über jeden der sechs Filter eindeutig auffindbar: Fahrzeug, Priorität, Status, Zeitraum, Depot, Verantwortlicher (DMG-009).
  2. Die Liste ist nach den angezeigten Spalten sortierbar; die Sortierung ist bei gleichen Werten stabil (DMG-009).
  3. Die Liste enthält nur Akten, die der Benutzer nach Rolle und Dienst sehen darf; vertrauliche Inhalte erscheinen nicht in der Listenprojektion.
- **Tests:** Positiv: je Filter ein Fall, Filterkombination. Negativ: fremder Mandant, nicht berechtigtes Depot, ungültiger Filterwert (422), `limit` > 100, manipulierter Cursor. Konkurrierend: parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen liefern nur eigene Akten; eine Akte wechselt während der Paginierung den Status – keine Akte erscheint doppelt oder fehlt wegen instabiler Sortierung.
- **Abhängigkeiten:** intern: WP-M3-43, WP-M3-45, WP-M3-50 (Listenprojektion ohne vertrauliche Inhalte).
  - extern: WP-M1-09, WP-M1-02, WP-M1-43 (Betriebshöfe, Listenkonventionen, Web-Anwendungsrahmen)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Werkstattauslastung, Kennzahl `Werkstattquote` (M4/M7).

### WP-M3-50 – Vertraulichkeit der Schadenakte
- **Ziel:** Schadenereignisse sind von personenbezogenen Unfall- und Gesundheitsangaben logisch getrennt; vertrauliche Inhalte sind nur berechtigten Rollen zugänglich.
- **Anforderungen:** `LH-6.22-DMG-011`
- **Liefergegenstände:**
  - Backend: Vertraulichkeitsstufe je Dokument/Feld der Schadenakte; Rollen- und Objektprüfung für jeden Lese- und Downloadzugriff; getrennte Ablage für personenbezogene Unfallangaben (in M3 als abgegrenzter, standardmäßig leerer Bereich mit eigenem Zugriffsrecht).
  - Datenbank: Kennzeichen an `defect_evidence`/Revision; eigener Modulzugang bzw. Spalten-/Viewrechte nach Kap. 04 §4.6.
  - API: Projektionen je Rolle für `GET /v1/defects/{id}`; Download nur über kurzlebige signierte Links nach Prüfung.
  - Oberfläche: UI-10/UI-11 – vertrauliche Teile sind für Unberechtigte nicht vorhanden statt ausgegraut.
- **Detailverträge vorab:** Rollenmatrix Schadenakte (Fahrer des Fahrzeugs, anderer Fahrer, Leitstelle, Werkstatt, Prüfer); Datenmodell der getrennten Unfallangaben (OP-23).
- **Akzeptanzkriterien:**
  1. Ein Fahrer eines anderen Fahrzeugs kann vertrauliche Dokumente des Falls über keinen Endpunkt lesen oder herunterladen, auch nicht mit bekannter Dokument-ID (DMG-011).
  2. Die Schadenakte enthält keine Felder für Gesundheitsangaben; personenbezogene Unfallangaben liegen getrennt und sind nur der dafür berechtigten Rolle zugänglich (DMG-011).
  3. Jeder Zugriff auf vertrauliche Dokumente wird protokolliert; ein abgewiesener Zugriff erzeugt ein Sicherheitsereignis ohne Fallinhalt.
  4. Ein abgelaufener oder weitergegebener Downloadlink funktioniert nicht.
- **Tests:** Positiv: berechtigte Rolle liest. Negativ: anderer Fahrer, andere Schicht, anderer Mandant, abgelaufener Link, Listen-/Such-/Medienendpunkt mit fremder ID. Konkurrierend: Rechteentzug während eines offenen Downloadlinks.
- **Abhängigkeiten:** intern: WP-M3-43, WP-M3-44.
  - extern: WP-M1-31, WP-M1-08, WP-M1-12 (Feldschutz, Objektberechtigung, Sicherheitsereignisse)
- **Entscheidungen:** D-11 (Datenrechte, Supportzugriff) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Unfallmeldeprozess mit Personenschaden (kein P1-Umfang dieser Einheit).

### WP-M3-51 – Reparaturauftrag und Wiederfreigabe mit Rollenprüfung
- **Ziel:** Ein kritisch gesperrtes Fahrzeug wird erst wieder disponierbar, wenn Reparaturnachweis, berechtigte technische Freigabe und neue Prüfung gemeinsam vorliegen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Reparaturauftrag zur Schadenakte (angelegt, in Arbeit, abgeschlossen gemeldet); Freigabeentscheidung durch berechtigte Fachrolle mit Nachweisbezug; Regel „Sperre aufheben nur mit `vehicle_release_decision`"; nach Freigabe ist eine neue Pflichtprüfung Voraussetzung der Einsatzfreigabe.
  - Datenbank: `repair_order`, `vehicle_release_decision` (unveränderlich; Rolle, Nachweis, Bezug zur Schadenakte).
  - API: `/v1/repair-orders`, `POST /v1/vehicles/{id}/release`.
  - Oberfläche: UI-11 Flotte/Werkstatt – Reparatur melden, Freigabe erteilen; Zustand „Freigaberolle fehlt" (Zustand „Frist überschritten" siehe Kriterium 7).
  - Ereignisse/Outbox: Reparatur abgeschlossen gemeldet, technische Freigabe erteilt, Freigabeversuch abgewiesen.
- **Detailverträge vorab:** OpenAPI beider Endpunkte; Zustandsautomat Reparaturauftrag; Freigaberollen und Vieraugenpflicht (OP-11); Nachweisarten.
- **Akzeptanzkriterien:**
  1. Ein gemeldeter Werkstattabschluss ohne technische Freigabe lässt das Fahrzeug gesperrt (AT-35).
  2. Der Versuch der Leitstelle, eine kritische Sperre ohne Werkstattnachweis zu entfernen, wird serverseitig verhindert und im Auditlog als Fehlversuch dokumentiert (AT-51).
  3. Die Disposition wird erst möglich, wenn Reparatur, technische Freigabe und neue Checkliste vorliegen; jede fehlende Bedingung hält die Sperre (AT-60).
  4. Eine Freigabe durch eine nicht berechtigte Rolle ergibt 403; derselbe Benutzer kann bei Vieraugenpflicht nicht beide Schritte ausführen.
  5. Die Freigabeentscheidung ist unveränderlich und nennt Akteur, Rolle, Nachweis und Schadenakte; sie erhöht `safety_revision`, erteilt aber selbst keine `resource_release` – diese entsteht erst mit der neuen Prüfung und gültiger Schicht und Qualifikation (PH-AT-020).
  6. Wiederholung der Freigabe mit demselben Idempotenzschlüssel erzeugt keine zweite Entscheidung; bestehen weitere Sperrgründe, bleibt das Fahrzeug gesperrt.
  7. UI-11 zeigt „Freigaberolle fehlt" als prüfbaren Rechtezustand; der Zustand „Frist überschritten" des Katalogs setzt Fristen voraus, die erst WP-M7c-33 liefert (OP-12).
- **Tests:** Positiv: AT-60. Negativ: AT-35, AT-51; Freigabe für fremdes Fahrzeug; Freigabe ohne Nachweis. Konkurrierend: Freigabe parallel zu einer neuen kritischen Meldung – das Fahrzeug bleibt gesperrt; zwei gleichzeitige Freigaben – eine Entscheidung.
- **Abhängigkeiten:** intern: WP-M3-08, WP-M3-14, WP-M3-45, WP-M3-47.
  - extern: WP-M1-07, WP-M1-12 (Rollenmodell, Audit und Sicherheitsereignisse)
- **Entscheidungen:** D-11 (berechtigte Freigaberollen müssen vom Betreiber geliefert werden) – blockiert nur die Produktivschaltung; gebaut wird mit einer Testrollenmatrix.
- **Größe:** M
- **Nicht im Umfang:** Werkstattplanung, Kostenbezug, Ersatzteile, Wartungsfristen (DMG P2 in M7; AT-57).

### WP-M3-52 – Alarm-Grundlage: Alarm, Dedupe, Quittierung
- **Ziel:** Fachmodule können Alarme und Hinweise auslösen, die dedupliziert in der Arbeitsliste der Leitstelle erscheinen und quittiert werden können.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Alarmdienst mit Policy-Revision, Priorität, Empfängerrolle; Dedupe über `(policy,event,recipient)`; Quittierung mit Akteur, Zeit und Ursache; Eskalation nach Verzögerung.
  - Datenbank: `alert_policy_revision`, `business_alert`, `alert_acknowledgement`.
  - API: Arbeitsliste und Quittierung (Pfade im Detailvertrag unter `/v1/operator/incidents` bzw. eigener Alarmpfad).
  - Oberfläche: UI-07 – Arbeitsliste „Offene Störungen" mit Priorität, Alter, Quittierung.
  - Konfigurationsparameter: `alert_escalation_delay_min`, `vehicle_unavailability_alert_min`.
- **Detailverträge vorab:** DDL; Alarmtypen-Katalog für P1 (kritische Sperre, Technikereignis, Überfälligkeit, Fahrzeugausfall); Zustandsautomat Alarm.
- **Akzeptanzkriterien:**
  1. Dasselbe Quellereignis erzeugt je Policy und Empfänger genau einen Alarm, auch bei mehrfacher Zustellung.
  2. Ein Alarm enthält keine Fahrgast-PII und keine Diagnosen.
  3. Die Quittierung speichert Akteur, Zeit und Ursache; ein quittierter Alarm bleibt nachvollziehbar.
  4. Kritische Alarme umgehen die Eskalationsverzögerung.
  5. Ein nicht quittierter Alarm eskaliert nach `alert_escalation_delay_min` genau einmal je Stufe; die Eskalation ist in der Arbeitsliste sichtbar.
  6. Alarmregeln sind revisioniert: eine neue `alert_policy_revision` wirkt auf neue Ereignisse; jeder Alarm nennt die Revision, nach der er entstand.
  7. Die Arbeitsliste zeigt Leerzustand, Ladefehler mit Datenstand und nur Alarme der berechtigten Dienste.
- **Tests:** Positiv: Alarm erscheint, wird quittiert. Negativ: Alarm für fremden Dienst nicht sichtbar; Quittierung ohne Rolle. Konkurrierend: 100 identische Ereignisse → ein Alarm; zwei gleichzeitige Quittierungen → eine.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-35.
  - extern: WP-M1-14, WP-M1-21, WP-M1-15, WP-M1-43 (Inbox-Deduplikation, wirksame Konfiguration, Lease für den Eskalationsjob, Web-Anwendungsrahmen)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** externe Benachrichtigungskanäle für Alarme, Empfehlungen und weitere Regeln (AUT P2, M8); `notification_quiet_hours`.

### WP-M3-53 – Regelbasierter Workflow: Incident bei Fahrzeugausfall
- **Ziel:** Ein regelbasierter Workflow verarbeitet Ereignisse wie Fahrzeugausfall und Schaden; ein Fahrzeugausfall erzeugt genau einen Incident und stößt genau eine Ersatzfahrzeugsuche an.
- **Anforderungen:** `LH-I.8-AUT-001`
- **Liefergegenstände:**
  - Backend: Workflow-Konsument auf Ereignisse „Fahrzeug gesperrt", „Störung gemeldet", „sicherheitskritischer Schaden" sowie – als registrierte Ereignistypen – „Ablehnung" und „kritische Verspätung"; Regelrevision bestimmt die Folgeaktionen; Incident-Aggregat mit Dedupe je (Fahrzeug, Ausfallursache, offener Zeitraum); Anstoß der Ersatzfahrzeugsuche als `operation`.
  - Datenbank: Incident-Ablage gemäß OP-02; `inbox` für die Deduplikation; Regelrevision.
  - API: `/v1/operator/incidents` (Liste, Detail).
  - Oberfläche: UI-07 – Incident in der Arbeitsliste mit Fahrzeug, Ursache und Verknüpfungen.
  - Ereignisse/Outbox: Incident eröffnet, Ersatzfahrzeugsuche gestartet.
- **Detailverträge vorab:** Regelformat und -revision; Dedupe-Schlüssel des Incidents; Incident-Zustandsautomat; Umfang der Ereignisse „Ablehnung" und „kritische Verspätung" in P1 (OP-24).
- **Akzeptanzkriterien:**
  1. Ein Fahrzeugausfall löst genau einen Incident und genau eine Ersatzfahrzeugsuche aus, auch wenn Sperre, Schadenmeldung und Statusmeldung „Störung" für dasselbe Fahrzeug nahezu gleichzeitig eintreffen (AUT-001).
  2. Die Verarbeitung ist regelbasiert: eine geänderte Regelrevision ändert die Folgeaktionen neuer Ereignisse, nicht die bereits verarbeiteter (AUT-001).
  3. Ein erneut zugestelltes Ereignis erzeugt keinen zweiten Incident.
  4. Schlägt die Ersatzfahrzeugsuche fehl, bleibt der Incident offen und sichtbar.
  5. Die Ereignistypen „Schaden", „Ablehnung" und „kritische Verspätung" sind im Workflow registriert und werden nach der wirksamen Regelrevision verarbeitet; ohne hinterlegte Regel entsteht ein protokollierter Eintrag „keine Aktion", kein Fehler (AUT-001; Regelumfang in P1 laut OP-24).
  6. Regelrevisionen sind unveränderlich; ein Incident ist nur für Rollen des berechtigten Dienstes sichtbar.
- **Tests:** Positiv: Sperre → ein Incident, eine Suche. Negativ: Ereignis für fremden Mandanten, unbekannter Ereignistyp. Konkurrierend: drei auslösende Ereignisse parallel und 100-fache Wiederzustellung → ein Incident, eine Suche.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-08, WP-M3-31 (Ereignis „Störung gemeldet"), WP-M3-52.
  - extern: WP-M1-14, WP-M1-15 (Outbox/Inbox, `operation`-Ressource)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalt der Ersatzsuche und Umbuchung (WP-M3-54); weitere Automationsregeln und Empfehlungen (AUT P2, M8).

### WP-M3-54 – Störungsablauf: betroffene Fahrten, Ersatzvorschläge, Fahrgastauswirkung
- **Ziel:** Bei Fahrzeugausfall verknüpft die Disposition offene Fahrten, Ersatzfahrzeugvorschläge und die Auswirkungen auf Fahrgäste mit dem Schadensfall; die Leitstelle arbeitet den Ausfall bis zur Umbuchung oder zum Storno ab.
- **Anforderungen:** `LH-6.22-DMG-010`
- **Liefergegenstände:**
  - Backend: Ermittlung der offenen Fahrten des gesperrten Fahrzeugs; Aufruf der vorhandenen Ersatzdispositionslogik (Einfügeheuristik aus M2) je betroffener Buchung; Vorschlagsliste mit Wirkung auf Zusagefenster; Übernahme per Plan-Revision (WP-M3-40) oder betreiberseitiges Storno; Fahrgastbenachrichtigung über die Betriebsnachrichten; Verknüpfung Incident ↔ Schadenakte ↔ betroffene Buchungen.
  - Datenbank: Verknüpfungen am Incident; `booking_change` für Umbuchungen.
  - API: `/v1/operator/incidents/{id}` mit betroffenen Fahrten, Vorschlägen und Aktionen.
  - Oberfläche: UI-07 – Störungsansicht: betroffene Fahrgäste, „Ersatzoptionen prüfen", „Fahrgast informieren", Sprung zur Schadenakte.
  - Kennzahlen: Datengrundlage für `Buchungsstornoquote Betreiber` (Fahrzeugausfall getrennt ausgewiesen).
  - Konfigurationsparameter: `max_reassignments`.
- **Detailverträge vorab:** Ablauf und Zustände des Störungsfalls; Regel für Buchungen ohne Ersatz (Storno und Erstattung nach M2-Regeln); Nachrichtenvorlagen (OP-27); Behandlung bereits eingestiegener Fahrgäste.
- **Akzeptanzkriterien:**
  1. Ein gesperrtes Fahrzeug löst sichtbar die vorhandene Ersatzdispositionslogik aus; die Leitstelle sieht Vorschläge je betroffener Fahrt (DMG-010).
  2. Offene Fahrten, Ersatzvorschläge und betroffene Fahrgäste sind vom Schadensfall aus erreichbar und umgekehrt (DMG-010).
  3. Jede betroffene Buchung endet nachvollziehbar in „umgebucht" oder „storniert" oder bleibt als offener Punkt im Incident; keine bleibt still auf dem gesperrten Fahrzeug (AT-09).
  4. Eine Ersatzfahrt gilt erst nach bestätigter Umbuchung als zugesagt; Fahrgäste erhalten genau eine Nachricht je Änderung.
  5. Ersatzvorschläge enthalten nur freigegebene, geeignete Fahrzeuge (Qualifikation, Prüfung, Ressourcenvektor).
  6. Fahrgäste, die bereits eingestiegen sind, werden gesondert ausgewiesen und nicht automatisch umgebucht oder storniert (Verfahren laut Detailvertrag).
  7. Ein Betreiberstorno wegen Fahrzeugausfall trägt die Stornoursache aus WP-M2-27 und löst Erstattung und Benachrichtigung über die M2-Wege aus; es entsteht keine doppelte Erstattung bei Wiederholung.
  8. Jede Entscheidung der Leitstelle im Incident ist mit Akteur, Grund und Zeit protokolliert und im Ablaufprotokoll (WP-M3-55) sichtbar.
- **Tests:** Positiv: AT-09; AT-50 (kritischer Schaden im Dienst, Ersatzfahrer/-fahrzeug, neuer Check, Rebooking, Fahrgastkommunikation). Negativ: kein Ersatz verfügbar → Storno-/Klärpfad; Ersatzfahrzeug ohne Rollstuhlplatz für Rollstuhlbuchung wird nicht vorgeschlagen. Konkurrierend: zwei Disponenten bearbeiten denselben Incident; Umbuchung parallel zu einem Fahrgaststorno.
- **Abhängigkeiten:** intern: WP-M3-22 (Ersatzfahrzeug mit neuer Prüfung für AT-50), WP-M3-40, WP-M3-41, WP-M3-46, WP-M3-53.
  - extern: WP-M2-17, WP-M2-21, WP-M2-26, WP-M2-27, WP-M2-31, WP-M2-34 (Einfügeheuristik/Angebotsbildung (WP-M2-17, WP-M2-21), Storno mit Ursache Fahrzeugausfall und Erstattung (WP-M2-26, WP-M2-27, WP-M2-31), Auslöser der Betriebsnachrichten (WP-M2-34))
- **Entscheidungen:** keine
- **Größe:** L
- **Nicht im Umfang:** Partnerübernahme (E2E-100, M6); automatische Umbuchung ohne Leitstellenentscheidung (M7/M8).

### WP-M3-55 – Ablaufprotokoll automatisch ausgeführter Vorgänge
- **Ziel:** Jeder automatisch ausgeführte Vorgang zeigt Auslöser, Regelversion, Entscheidung und Folgeaktionen, sodass der Betriebsleiter einen Vorfall vollständig rekonstruieren kann.
- **Anforderungen:** `LH-I.8-AUT-002`
- **Liefergegenstände:**
  - Backend: Ablaufprotokoll je Workflow-Ausführung (Auslöserereignis, Regelrevision, Entscheidung, Folgeaktionen mit Ergebnis und Korrelation) als append-only Aufzeichnung.
  - Datenbank: Ablaufprotokoll am Incident bzw. eigene Tabelle laut Detailvertrag; Bezug zu `audit_event.correlation_id`.
  - API: Ablauf in `GET /v1/operator/incidents/{id}`.
  - Oberfläche: UI-07 – Zeitleiste des Vorfalls für den Betriebsleiter.
- **Detailverträge vorab:** Protokollschema; Rollen mit Leserecht; Minimierung (Referenzen statt Rohpayloads mit PII).
- **Akzeptanzkriterien:**
  1. Für einen Testvorfall zeigt die Zeitleiste Auslöser, Regelversion, getroffene Entscheidung und jede Folgeaktion mit Ergebnis in zeitlicher Reihenfolge (AUT-002).
  2. Der Betriebsleiter kann den Ablauf allein aus dieser Ansicht vollständig rekonstruieren, einschließlich fehlgeschlagener oder übersprungener Aktionen (AUT-002).
  3. Das Protokoll ist nicht änderbar und enthält keine Rohpayloads mit Personendaten.
- **Tests:** Positiv: Testvorfall aus WP-M3-53/54 vollständig rekonstruiert. Negativ: Zugriff ohne Rolle; Änderungsversuch am Protokoll (API und Datenbank); Protokoll eines Incidents eines anderen Mandanten (404). Konkurrierend: mehrere Folgeaktionen desselben Vorfalls schreiben gleichzeitig – alle Einträge bleiben erhalten und sind eindeutig geordnet.
- **Abhängigkeiten:** intern: WP-M3-02, WP-M3-53.
  - extern: WP-M1-12, WP-M1-02 (Audit und Korrelations-ID)
- **Entscheidungen:** keine
- **Größe:** S
- **Nicht im Umfang:** Auswertungen über viele Vorfälle (M8).

### WP-M3-56 – M3-Gate-Abnahmesuite
- **Ziel:** Alle Referenztests des M3-Gates laufen automatisiert gegen einen Build und sind mit Evidenz in `e2e.csv` eingetragen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Szenarien in `tests/acceptance` für PH-AT-020, AT-08, AT-09, AT-10, AT-11, AT-26 bis AT-36, AT-50, AT-51, AT-60; Zwei-Mandanten-Fixture mit Fahrzeugen, Fahrern, Schichten, Vorlagen und Fahrtenplan; Gerätesimulation für Offline-/Sync-Fälle; Parallelitätsharness für PH-AT-020.
  - Oberfläche: UI-Abnahmefälle aus Kap. 06 §6.9 (Driverprüfpfad mit kritischem Nein und Offline-Sync; 412-Konflikt ohne stilles Überschreiben) für UI-07, UI-09, UI-10, UI-11.
  - Kennzahlen: Messprotokoll „Commit bis dargestellte Clientrevision" (Fahrerplan) und Standortalter.
- **Detailverträge vorab:** Testzuordnung laut Bestätigung aus WP-M0-33; Evidenzformat aus WP-M0-22.
- **Akzeptanzkriterien:**
  1. Jeder der 19 Referenztests ist als ausführbares Szenario vorhanden und grün. Für AT-34 („Frist auslösen"), AT-36 („Dublettenhinweis" über den Hinweis auf offene Fälle hinaus), AT-08 („Abrechnung regelkonform") und AT-33 („Medienprüfung", abhängig von WP-M4-21) ist der in M3 abnehmbare Teil ausdrücklich abgegrenzt und mit dem Auftraggeber dokumentiert (OP-12, OP-13, OP-21).
  2. PH-AT-020 läuft mit mindestens zwei parallelen Transaktionen in beiden Reihenfolgen und wiederholt (mindestens 100 Durchläufe ohne Fehlzusage).
  3. Jede der 48 Anforderungen hat in `arbeitspakete.csv` mindestens einen grünen positiven Test.
  4. Alle Szenarien laufen unter der nicht privilegierten Runtime-Rolle und mit beiden Mandanten.
  5. `e2e.csv` enthält je Test Build-ID, Evidenz und Entscheidung.
  6. Für jeden M3-Endpunkt laufen die Vertragsprüfungen aus Kap. 05 §5.8: Manipulation `tenant_id`, unzulässige Fremd-ID (404), fehlendes `If-Match` (428), veraltete Revision (412), gleicher Idempotenzschlüssel mit anderem Inhalt (`409 IDEMPOTENCY_CONFLICT`), 100 identische Wiederholungen, Offline-Check-in auf alter Planrevision.
  7. Die Invariante „Kapazität/Qualifikation/Fahrzeugfreigabe" aus Kap. 04 §4.5 ist durch Negativtests mit mindestens zwei parallelen Transaktionen belegt (Sperre, Qualifikationsentzug, Fahrzeugwechsel, Schichtüberlappung, Umplanung).
  8. Die Zustände des Katalogs Kap. 06 §6.9 für UI-07, UI-09, UI-10 und UI-11 sind je als prüfbarer Fachzustand nachgewiesen, nicht nur als Screenshot; Abweichung „Frist überschritten" siehe OP-12.
  9. Die M2-Gate-Regression (PH-AT-004, AT-01 bis AT-05) bleibt nach Ablösung der M2-Fixtures durch die M3-Module grün.
- **Tests:** Die Suite selbst; zusätzlich Negativkontrolle: ein absichtlich deaktivierter Safety-Check lässt PH-AT-020 rot werden.
- **Abhängigkeiten:** intern: alle WPs dieser Datei.
  - extern: WP-M0-22, WP-M0-32, WP-M0-33 (Akzeptanztestgerüst, Fixtures, Nachverfolgungs-CI)
  - extern: WP-M2-16, WP-M2-23, WP-M2-26, WP-M2-34, WP-M2-48 (echter Fahrtenplan, Buchung, Storno und Betriebsnachrichten für AT-08, AT-09, AT-50 (WP-M2-16, WP-M2-23, WP-M2-26, WP-M2-34); M2-Gate-Suite für die Regression (WP-M2-48))
  - extern: WP-M4-21 (Uploadticket und Malware-Prüfung für AT-33)
- **Entscheidungen:** D-11 und D-09 müssen für die Produktivschaltung entschieden sein; das Gate selbst läuft mit den Favoriten in der Testumgebung.
- **Größe:** M
- **Nicht im Umfang:** Last-, Restore- und Barrierefreiheitsabnahme (M5: AT-53, AT-54); AT-57 (Schadenskosten).

## Offene Punkte

- **OP-01 Sperrgründe im Schema** (`LH-6.21-CHK-004`, `LH-6.22-DMG-005`, `LH-6.22-DMG-006`, `LH-6.7-OPS-005`): `kernschema.sql` kennt nur `vehicle.active_block boolean DEFAULT true` und `safety_revision`. Mehrere gleichzeitige Sperrgründe mit unterschiedlichen Aufhebungsrechten sind damit nicht abbildbar. Die Bedeutung des Defaults `true` (neues Fahrzeug zunächst gesperrt?) ist nicht beschrieben. Kap. 04 §4.4 nennt keine Sperrtabelle.
- **OP-02 Incident ohne Tabelle** (`LH-I.8-AUT-001`, `LH-I.8-AUT-002`, `LH-6.22-DMG-010`): Kap. 05 §5.7 nennt `/v1/operator/incidents`, Kap. 04 §4.4 enthält aber keine Incident-Tabelle (nur `business_alert`). Ablage von Incident, Regelrevision des Workflows und Ablaufprotokoll ist zu spezifizieren.
- **OP-03 `DriverEvent` unvollständig** (`LH-6.6-DRV-005`, `LH-6.6-DRV-007`): `event_type` in `openapi.yaml` kennt nur `CHECK_IN`, `CHECK_OUT`, `NO_SHOW`. Die geforderte Ankunftsmeldung fehlt, ebenso Statusmeldungen (Pause, Störung, Ende). `booking_id` ist Pflichtfeld, eine Ankunft am Stopp betrifft aber ggf. mehrere oder keine Buchung.
- **OP-04 Positionsannahme und Standortalter** (`LH-6.7-OPS-001`, AT-10): Kap. 05 §5.7 nennt keinen Endpunkt für Positionsmeldungen. Die Schwelle „Standortalter ≤ 30 s" steht nur als Qualitätsziel in Kap. 01/08; `kataloge.csv` enthält `eta_max_data_age_sec` auf Ebene „Modell", aber keinen Parameter für die Veraltet-Kennzeichnung in der Leitstelle. Sendeintervall und Aufbewahrung der Rohpositionen hängen an D-05, D-10, D-11.
- **OP-05 Aktualisierungsintervall** (`LH-6.21-CHK-016`): Die Abnahme verlangt Statuswechsel „im vorgesehenen Aktualisierungsintervall"; ein Wert und die Technik (Push oder Polling) sind im Pflichtenheft nicht festgelegt.
- **OP-06 Schwelle der Überfälligkeit** (`LH-6.7-OPS-006`): „Definierte Schwelle" ist keinem Parameter eindeutig zugeordnet. Kandidaten in `kataloge.csv`: `vehicle_unavailability_alert_min`, `alert_escalation_delay_min`, `on_time_pickup_tolerance_min`. Welche Überfälligkeitsarten in P1 gelten und wie „Betroffenenzahl" zählt (Fahrgäste oder Buchungen), ist offen.
- **OP-07 Modul- und Meilensteinzuschnitt Schicht/Fahrer** (`LH-6.8-FLT-003`, `LH-6.6-DRV-001`): Kap. 01 §1.3 ordnet Shift und Qualification dem Modul Staff/Time zu, Kap. 04 §4.4 führt `driver` und `qualification_revision` unter DRV/FLT, der Dienstplan folgt erst in M4. `staff_shift.roster_revision` ist NOT NULL, obwohl in M3 noch keine `roster_revision` existiert. Zusätzlich ist die „Ausnahme" aus der Abnahme von FLT-003 (wer genehmigt eine Doppelschicht, wie wird sie dokumentiert) nicht beschrieben.
- **OP-08 Fahrzeugwechsel im Schema** (`LH-6.21-CHK-015`, AT-30, AT-50): `staff_shift.vehicle_id` ist genau ein Fahrzeug je Schicht. Ob ein Wechsel die Schicht ändert, eine neue Schicht erzeugt (AT-50 spricht von „Neuer Dienst/Check") oder über `vehicle_assignment` läuft, ist nicht festgelegt. Wer den Wechsel auslösen darf (Fahrer, Leitstelle), ebenfalls nicht.
- **OP-09 Wirkung von `vehicle_readiness_require_checks`** (`LH-6.21-CHK-001`): Der Katalog erlaubt einen Bool je Dienst, die Validierung sagt „Sicherheitsprüfungen dürfen nicht abgeschaltet werden", und `resource_release.inspection_ref` ist NOT NULL. Was der Wert `false` zulässig abschaltet, ist unklar.
- **OP-10 Schweregrade** (`LH-6.22-DMG-005`, PH-AT-020): PH-AT-020 spricht von einem „S0-Schaden", DMG-005 kennt vier Klassen (sicherheitskritisch, einsatzrelevant, kosmetisch, sonstig). Die Enum-Werte von `workshop_auto_block_severity` und ihre Abbildung auf beide Skalen fehlen.
- **OP-11 Wiederfreigabe ohne P1-Anforderung in dieser Einheit** (AT-35, AT-51, AT-60): Die zwölf DMG-MUSS dieser Einheit fordern keine Wiederfreigabe; das Gate und Kap. 05 §5.7 (`/v1/vehicles/{id}/release`: „Vieraugenpflicht, qualifizierte Folgeprüfung, Kostenbezug") verlangen sie. WP-M3-51 plant den Mindestumfang; der Abgleich mit den DMG-P2-Anforderungen in M7 und die berechtigten Freigaberollen (D-11) sind zu klären.
- **OP-12 Gate-Tests ohne tragende P1-Anforderung** (AT-34, AT-36): „Frist auslösen" und „Alarm nach Regelmatrix" (AT-34) sowie der „Dublettenhinweis" bei doppelter Schadenmeldung (AT-36) sind durch keine der 48 Anforderungen gefordert. Die tragenden Anforderungen sind P2 und in M7c geplant (Fristen/Wiedervorlage: WP-M7c-33, WP-M7c-45; Dublettenvorschlag: WP-M7c-39). M3 liefert nur den P1-Anteil: Klassifikation, Sperre, Zuständiger, Statushistorie und Alarm bei sicherheitskritisch (WP-M3-45) sowie den Hinweis auf offene Fälle desselben Fahrzeugs (WP-M3-21). Davon betroffen ist auch der UI-11-Zustand „Frist überschritten" aus Kap. 06 §6.9. Entweder werden die Tests auf den P1-Umfang zugeschnitten oder die Zuordnung zum M3-Gate wird in WP-M0-33 korrigiert oder der Auftraggeber zieht die P2-Anforderungen per D-12 vor.
- **OP-13 Evidenz-Upload** (`LH-6.22-DMG-004`, `LH-6.21-CHK-011`): Ein Uploadvertrag (Uploadticket, Quarantäne, Hash) ist nur für Belege beschrieben (`/v1/expenses/receipts`). Für Schadens- und Prüffotos fehlen Endpunkt, Größen-/Formatgrenzen und die Zuständigkeit für den Malware-Scan, der laut Plan erst mit M4 entsteht. Die Reihenfolge M3/M4 für diesen gemeinsamen Baustein (WP-M4-21, Modul `documents-finance`) ist abzustimmen; M3 und M4 laufen parallel, AT-33 und AT-36 (Medienintegrität) gehören aber zum M3-Gate.
- **OP-14 Fahrzeugklasse und Bedienart** (`LH-6.21-CHK-002`, `LH-6.8-FLT-001`): Beide Begriffe steuern die Vorlagenauswahl, sind aber weder im Schema noch in einem Katalog definiert. Ebenso fehlt der Wertekatalog für Fahrzeugtyp und Ausstattung.
- **OP-15 Plausibilitätsregeln** (`LH-6.21-CHK-008`): Schwellen („offensichtlich unplausibel"), die Prüfung der Fahrzeugidentität (Eingabe, Scan?) und die Rolle, die „mit Begründung freigeben" darf (Fahrer selbst oder Leitstelle), sind nicht festgelegt. Ob `min_battery_soc_pct` beim Schichtstart greift, ist offen.
- **OP-16 Antwortoptionen** (`LH-6.21-CHK-003`, `LH-6.21-CHK-007`): Die Anforderung nennt OK, Mangel, Nicht anwendbar; das Bildschirmkonzept Kap. 06 §6.7 zeigt Ja, Nein – Mangel melden, Nicht prüfbar. Ob „Nicht prüfbar" eine vierte Antwort ist und zum Status „technisch ungeklärt" führt, ist zu entscheiden.
- **OP-17 Status „eingeschränkt"** (`LH-6.21-CHK-016`, `LH-6.21-CHK-007`): Die Wirkung ist nur für Rollstuhlausstattung beschrieben. Welche weiteren Mängel zu „eingeschränkt" statt „gesperrt" führen und wie die Disposition die Einschränkung erhält, fehlt.
- **OP-18 Boarding-Code** (`LH-6.6-DRV-006`): Format, Gültigkeit, Erzeugung, Offline-Prüfbarkeit und der Regelprozess für Fahrgäste ohne Code sind nicht beschrieben; „datensparsam" ist nicht konkretisiert. Der Boarding-Code selbst ist `LH-6.4-BKG-019` (P2) und wird von M2 nicht gebaut (M2 Offener Punkt 44); WP-M3-30 plant deshalb nur Erzeugung, Hash-Ablage und Prüfung. Wie der Fahrgast den Code in P1 erhält (UI-04 oder Bestätigungsnachricht) und der Grenzwert für Fehlversuche (kein Parameter in `kataloge.csv`) sind zu entscheiden.
- **OP-19 Navigation** (`LH-6.6-DRV-003`): „Abbiegenavigation beziehungsweise Übergabe" lässt beide Varianten zu. Die Wahl hängt an D-09; geplant ist die Übergabe an die Geräte-Navigation.
- **OP-20 Prognosezeit vor dem ETA-Grundmodell** (`LH-6.6-DRV-002`, `LH-6.7-OPS-003`): Das ETA-Grundmodell ist M4 zugeordnet. Bis dahin steht nur die Planzeit aus dem Routing zur Verfügung; ob das für die M3-Abnahme als Prognose gilt, ist zu bestätigen.
- **OP-21 No-Show-Abrechnung** (`LH-6.6-DRV-005`, AT-08): AT-08 verlangt „Abrechnung regelkonform"; die Gebührenregel gehört zu Tarif/Zahlung in M2 und ist dort nicht als Anforderung dieser Einheit greifbar. Die Validierung von `no_show_grace_min` lautet nur „positiv und fahrgastgerecht". Die Abrechnungsregel ist `LH-6.4-BKG-015` (P2) und wird von M2 nicht gebaut; WP-M3-29 stellt nur das No-Show-Ereignis mit Grund und Wartezeit bereit. Ob AT-08 im M3-Gate ohne Gebührenwirkung als bestanden gilt, ist mit dem Auftraggeber zu klären.
- **OP-22 Ereignisort und Depot** (`LH-6.22-DMG-002`, `LH-6.22-DMG-009`): Die Form des Ereignisorts (Koordinate, Adresse, Freitext) ist offen. Für den Depotfilter fehlt am Fahrzeug die Zuordnung zu einem Betriebshof.
- **OP-23 Trennung personenbezogener Unfallangaben** (`LH-6.22-DMG-011`): Das Pflichtenheft nennt keine Tabelle und keinen Prozess für Unfall-/Gesundheitsangaben. Geplant ist in M3 nur die Trennung (keine solchen Felder in `defect_case`, eigener Zugriffsbereich); der fachliche Umfang ist zu bestätigen.
- **OP-24 Umfang des Workflows** (`LH-I.8-AUT-001`): Die Beschreibung nennt auch „Ablehnung" und „kritische Verspätung", die Abnahme prüft nur den Fahrzeugausfall. Welche Regeln für die beiden anderen Ereignisse in P1 gelten, ist nicht beschrieben; `alert_policy_revision` deckt Alarme ab, nicht Workflowregeln.
- **OP-25 Geografische Poolzuordnung** (`LH-6.8-FLT-002`): Ob „geografisch" ein Bediengebiet (`service_area`), einen Betriebshof oder eine eigene Geometrie meint, ist nicht festgelegt; die Felder von `vehicle_assignment` sind in Kap. 04 §4.4 nur umrissen.
- **OP-26 Fahrgastsuche** (`LH-6.7-OPS-002`): Kontaktdaten liegen verschlüsselt in `passenger_private.contact_ciphertext`. Das Suchverfahren (Blind-Index, exakte Suche, Suchfeldumfang) und die dafür berechtigten Rollen fehlen.
- **OP-27 Fahrgastkommunikation im Störungsfall** (`LH-6.22-DMG-010`, AT-50): Nachrichtenvorlagen, Kanäle und der Zeitpunkt der Information (bei Sperre oder erst nach Ersatzentscheidung) sind nicht beschrieben.
- **OP-28 Abgleich mit `arbeitspakete.csv`**: Die dortige Zuordnung führt M3 als ein Arbeitspaket je Modul. Nach Freigabe dieser Datei sind die WP-IDs, Detailverträge und Testfälle je Anforderung dort nachzutragen (außerhalb dieses Auftrags).
- **OP-29 Zustellweg von Planänderungen an den Fahrer** (`LH-6.6-DRV-004`): Der fachliche Fahrer-Push ist `LH-6.12-COM-007` (P2); WP-M2-28 lässt die Technik des Änderungsstroms offen (SSE oder WebSocket, M2 Offener Punkt 25). Für das Ziel p95 ≤ 10 s bei geschlossener oder im Hintergrund laufender App ist festzulegen, ob ein inhaltsloser Weckruf über den Push-Kanal zulässig ist und welcher Provider genutzt wird (D-05).
- **OP-30 Leitstellenbuchung ohne P1-Grundlage in M2** (`LH-6.7-OPS-004`): Die Anforderung verlangt Anlegen, Ändern und Stornieren „für einen Fahrgast". Die Bearbeitung bestätigter Buchungen (`LH-6.4-BKG-006`, `booking_change`) und die Buchung für Dritte (`booking_subject=DELEGATED`, `LH-6.4-BKG-010`, `delegated_access`, UI-06) sind P2 und in M7 geplant. WP-M3-39 plant einen Mindestumfang (Änderung als neues Angebot mit atomarem Tausch; Bindung an einen verifizierten Fahrgast mit Disponent als Akteur). Offen sind: welche Legitimation ein Disponent in P1 braucht, ob eine Buchung für nicht registrierte Fahrgäste zulässig ist, welche Änderungen die Zustimmung des Fahrgasts verlangen und wie Preisdifferenzen bei Änderung abgerechnet werden.
- **OP-31 Aufnahmedatum und Bildmetadaten** (`LH-6.21-CHK-011`, `LH-6.22-DMG-004`): „Sichtbares Aufnahmedatum" setzt eine Zeitquelle voraus (Gerätezeit oder EXIF); AT-59 (nicht im M3-Gate) verlangt EXIF-Minimierung. Festzulegen ist, welche Metadaten beim Upload entfernt werden (insbesondere Standort) und ob das Aufnahmedatum ins Bild eingeblendet oder nur neben dem Bild angezeigt wird.
- **OP-32 Lücken im Kernschema für M3** (`LH-6.21-CHK-001`, `LH-6.21-CHK-013`, `LH-6.8-FLT-003`): `staff_shift.driver_ref` hat keinen Fremdschlüssel (die Tabelle `driver` fehlt im Kern-DDL), `resource_release.inspection_ref` ebenfalls nicht; `resource_release` trägt keinen Fahrerbezug außer über die Schicht. `kernschema.sql` enthält kein Überlappungsverbot für `staff_shift`. Für Korrektureinträge, Quittierungen, Statushistorien, Boarding-Code und bestätigte Planrevision nennt Kap. 04 §4.4 keine Tabellen; WP-M3-02 führt sie als Arbeitsnamen.
- **OP-33 Gültigkeit der Einsatzfreigabe** (`LH-6.21-CHK-001`): `resource_release.valid_until` ist Pflichtfeld, das Pflichtenheft nennt aber keine Regel (Schichtende, feste Dauer, Tageswechsel). Ebenso offen ist, ob nach einer Pause oder Unterbrechung innerhalb derselben Schicht eine neue Prüfung nötig ist; die Anforderung spricht nur vom „ersten Bereitstatus".
- **OP-34 Rollen für M3** (`LH-6.7-OPS-004`, `LH-6.22-DMG-008`, `LH-6.22-DMG-011`, `LH-I.8-AUT-002`): Die Anforderungen nennen „berechtigter Disponent", „berechtigte Prüfer", „berechtigte Rollen" und „Betriebsleiter", ohne sie abzugrenzen; die Freigaberollen liefert der Betreiber (D-11). WP-M3-01 arbeitet mit Arbeitsnamen; die verbindliche Rechtematrix muss vor der Produktivschaltung vorliegen.
- **Reihenfolgekonflikt: Zyklusrisiko zwischen parallelen Einheiten (M2/M3/M4)**: Kein WP dieser Datei hängt von einem späteren Meilenstein ab. Mehrere extern-Abhängigkeiten auf M2/M4 zeigen aber auf WPs, die ihrerseits eine Fähigkeit aus M3 als EXTERN ausweisen; als harte Vorgängerbeziehung gelesen entsteht ein Zyklus. Betroffen: WP-M3-06 ↔ WP-M2-15 (Poolzuordnung/Kandidatenfilter), WP-M3-08 ↔ WP-M2-22 (Safety-Prüfung im Hold-/Commit-Pfad), WP-M3-09, WP-M3-12, WP-M3-31 ↔ WP-M2-15 (Einsatzfreigabe/Disponierbarkeit im Kandidatenfilter), WP-M3-27 ↔ WP-M2-28 (Driver-App als Empfänger der Planrevision), WP-M3-29 ↔ WP-M2-25 (Ausführungszustände in der Statushistorie), WP-M3-54 ↔ WP-M2-27 (Störungsablauf/Betreiberstorno), WP-M3-56 ↔ WP-M2-48 (Gate-Suiten), WP-M3-07 ↔ WP-M4-65 und WP-M3-07 → WP-M4-14 → WP-M3-25 → WP-M3-07 (Übernahme der Schichtzuweisung in den Dienstplan). Aufzulösen ist je Paar, welche Seite zuerst gegen Fixture/Leseport (WP-M0-22, WP-M2-02) fertig wird und welche Seite nur die spätere Integration bzw. Ablösung ist (Integrationsschritt statt Vorgänger).
- **Ungedeckte Fähigkeit: Anzeige des Boarding-Codes beim Fahrgast** (WP-M3-30, siehe OP-18): Kein WP in M0–M4 liefert die Anzeige; die tragende Anforderung `LH-6.4-BKG-019` ist erst in WP-M7a-14 (späterer Meilenstein) geplant.
