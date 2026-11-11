# Detailverträge M3 (WP-M3-01)

Eingang für alle folgenden M3-WPs (Fahrer, Leitstelle, Flotte/Sicherheit). Alle Festlegungen, die das Pflichtenheft nicht trifft, sind **Annahmen dieses Plans**; Verantwortlicher ist die Rolle Flotte/Sicherheit (`fleet-safety`) für Prüfung, Schaden, Sperre und Incident, die Rolle Fahrer-App/Betrieb (`staff-time`, `mobile/driver`) für Fahrerereignisse und Synchronisation, die Rolle Buchung/Disposition (`booking-dispatch`) für Plan und Leitstellenbuchung. Die Bestätigung durch Fachanalyse, Sicherheits- und Testrolle und den Auftraggeber steht aus (D-12, Prozess nach [vertragsprozess.md](../vertragsprozess.md)); **kein menschlicher Review ist erfolgt**. Die Verträge je Anforderung (`LH-6.6-DRV-*`, `LH-6.7-OPS-*`, `LH-6.8-FLT-*`, `LH-6.21-CHK-*`, `LH-6.22-DMG-*`, `LH-I.8-AUT-*`) sind in `arbeitspakete.csv` verknüpft; ihre Testfälle bleiben dort maßgeblich. Offene Punkte sind die Nummern aus [wp/M3-fahrer-leitstelle-sicherheit.md](../wp/M3-fahrer-leitstelle-sicherheit.md).

## 1. API-Verträge

| Datei | Modul (schreibend) | Operationen | Zustand |
|---|---|---|---|
| [`api/driver.yaml`](../../../api/driver.yaml) | `staff-time` | `recordDriverEvent` (übernommen aus `openapi.yaml`, erweitert), `recordDriverPosition`, `getDriverRun`, `acknowledgeDriverRunRevision`, `registerDriverDevice`, `revokeDriverDevice` | Vertrag; Gerätebindung und `recordDriverEvent` ab WP-M3-24 gebaut, Rest in `contract-pending.txt` |
| [`api/fleet-safety.yaml`](../../../api/fleet-safety.yaml) | `fleet-safety` | `listOperatorVehicles`, `createVehicleBlock`, `liftVehicleBlock`, `listChecklistTemplates`, `createInspection`, `getInspection`, `saveInspectionDraft`, `submitInspection`, `listDefects`, `createDefect`, `getDefect`, `triageDefect`, `listRepairOrders`, `createRepairOrder`, `createVehicleRelease`, `listOperatorIncidents`, `getOperatorIncident`, `transitionOperatorIncident` | Vertrag, nicht gebaut |
| [`api/dispatch.yaml`](../../../api/dispatch.yaml) | `booking-dispatch` | `listRunRevisions`, `createRunRevision` (`/v1/runs/{run_id}/revisions`) | Vertrag, nicht gebaut |

**Bewusste Abweichungen vom Katalog Kap. 05 §5.7:** (a) `/v1/operator/vehicles` wird um das Teilfeld `/blocks` und `/blocks/{block_id}/lift` ergänzt (operative Sperre, OPS-005; Entscheidung zu OP-01). (b) `/v1/checklist-templates` (nur lesen) ist neu: ohne ihn kennt die App die „exakt freigegebene Vorlage“ nicht (CHK-002, OP-14). (c) `/v1/driver/positions` ist neu (OP-04). (d) `/v1/driver/devices` ist neu (Geräte-/Sitzungsbindung, WP-M3-24). (e) `/v1/operator/incidents/{id}/transitions` ist neu: der Incident-Automat wird über benannte Ereignisse bedient, nie durch Setzen eines Zustands vom Client. `docs/pflichtenheft/api/openapi.yaml` bleibt unverändert; die Erweiterung von `recordDriverEvent` ist rein additiv (neue Enum-Werte, `booking_id`/`route_stop_id` nur für CHECK_IN/CHECK_OUT/NO_SHOW Pflicht, neue optionale Felder) und besteht `bundle.py --check`.

**Scopes** (neu in `common.yaml`, beide Scope-Listen): `operator:read`, `operator:write`, `dispatch:write`, `inspections:read`, `inspections:write`, `defects:read`, `defects:write`, `defects:triage`, `repair:read`, `repair:write`, `fleet:release`, `driver:run`, `driver:devices`, `driver:positions`.

### 1.1 Regeln je Endpunkt (Akzeptanzkriterium 5)

Gemeinsame Regeln für **jede** Operation der drei Dateien:

- **Fremder Mandant, fremdes Objekt, fremde Schicht eines anderen Fahrers:** 404 `NOT_FOUND`, ohne Hinweis auf die Existenz (Kap. 05 §5.4). Fehlender Scope oder fehlende Rolle: 403 `FORBIDDEN`.
- **`tenant_id` im Body:** unzulässig; die Request-Schemas haben `additionalProperties: false` (422 `VALIDATION_FAILED`). Der Mandant folgt allein aus dem Token (`RequestContext`); es entstehen keine zusätzlichen Rechte.
- **Idempotenz:** jede mutierende Operation trägt `Idempotency-Key`; derselbe Schlüssel mit anderem Inhalt: 409 `IDEMPOTENCY_CONFLICT`; laufende Erstverarbeitung: 409 `OPERATION_IN_PROGRESS` mit `Retry-After`. `recordDriverEvent` zusätzlich dauerhaft über die `event_id` (Fachschlüssel, überlebt die Frist des Registers).
- **Revisionsschutz:** Operationen auf einem Pfad mit Ressourcenvariable verlangen `If-Match` (428 ohne, 412 veraltet); Lesen liefert `ETag`.
- **Listen:** `{items,next_cursor,as_of}` mit `limit`/`cursor` (Kap. 05 §5.1), Cursor an Mandant, Akteur und Filter gebunden.
- **Fehlerformat:** `application/problem+json`; M3 führt **keine neuen Fehlercodes** ein, sondern nutzt `INVALID_STATE_TRANSITION`, `IMMUTABLE_RECORD`, `CONFLICT`, `VALIDATION_FAILED`; fachliche Gründe stehen im Problem-Detail (`violations`, `missing_requirements`).
- **Fahrer-Endpunkte (`driver:*`):** wirken nur auf die eigene, aktuell zugewiesene Schicht und das eigene registrierte Gerät.

| operationId | Methode, Pfad | Scope | Rollen (Rechtematrix) | Idempotency-Key | If-Match / ETag |
| `recordDriverEvent` | POST `/v1/driver/events` | `driver:events` | `driver` | ja | – |
| `recordDriverPosition` | POST `/v1/driver/positions` | `driver:positions` | `driver` | ja | – |
| `getDriverRun` | GET `/v1/driver/shifts/{shift_id}/run` | `driver:run` | `driver` | – | ETag in Antwort |
| `acknowledgeDriverRunRevision` | POST `/v1/driver/shifts/{shift_id}/run/acknowledgements` | `driver:run` | `driver` | ja | If-Match |
| `registerDriverDevice` | POST `/v1/driver/devices` | `driver:devices` | `driver` | ja | ETag in Antwort |
| `revokeDriverDevice` | DELETE `/v1/driver/devices/{device_id}` | `driver:devices` | `driver` | ja | If-Match |
| `listRunRevisions` | GET `/v1/runs/{run_id}/revisions` | `operator:read` | `callcenter`, `support`, `dispatcher`, `planner`, `workshop` | – | – |
| `createRunRevision` | POST `/v1/runs/{run_id}/revisions` | `dispatch:write` | `dispatcher` | ja | If-Match |
| `listOperatorVehicles` | GET `/v1/operator/vehicles` | `operator:read` | `callcenter`, `support`, `dispatcher`, `planner`, `workshop` | – | – |
| `createVehicleBlock` | POST `/v1/operator/vehicles/{vehicle_id}/blocks` | `operator:write` | `dispatcher` | ja | If-Match |
| `liftVehicleBlock` | POST `/v1/operator/vehicles/{vehicle_id}/blocks/{block_id}/lift` | `operator:write` | `dispatcher` | ja | If-Match |
| `listChecklistTemplates` | GET `/v1/checklist-templates` | `inspections:read` | `driver`, `dispatcher`, `workshop` | – | – |
| `createInspection` | POST `/v1/inspections` | `inspections:write` | `driver`, `workshop` | ja | ETag in Antwort |
| `getInspection` | GET `/v1/inspections/{inspection_id}` | `inspections:read` | `driver`, `dispatcher`, `workshop` | – | ETag in Antwort |
| `saveInspectionDraft` | PUT `/v1/inspections/{inspection_id}` | `inspections:write` | `driver`, `workshop` | ja | If-Match |
| `submitInspection` | POST `/v1/inspections/{inspection_id}/submit` | `inspections:write` | `driver`, `workshop` | ja | If-Match |
| `listDefects` | GET `/v1/defects` | `defects:read` | `driver`, `dispatcher`, `workshop` | – | – |
| `createDefect` | POST `/v1/defects` | `defects:write` | `driver`, `dispatcher`, `workshop` | ja | ETag in Antwort |
| `getDefect` | GET `/v1/defects/{defect_id}` | `defects:read` | `driver`, `dispatcher`, `workshop` | – | ETag in Antwort |
| `triageDefect` | POST `/v1/defects/{defect_id}/triage` | `defects:triage` | `dispatcher`, `workshop` | ja | If-Match |
| `listRepairOrders` | GET `/v1/repair-orders` | `repair:read` | `dispatcher`, `tenant_admin`, `workshop` | – | – |
| `createRepairOrder` | POST `/v1/repair-orders` | `repair:write` | `workshop` | ja | ETag in Antwort |
| `createVehicleRelease` | POST `/v1/vehicles/{vehicle_id}/release` | `fleet:release` | `tenant_admin`, `workshop` | ja | If-Match |
| `listOperatorIncidents` | GET `/v1/operator/incidents` | `operator:read` | `callcenter`, `support`, `dispatcher`, `planner`, `workshop` | – | – |
| `getOperatorIncident` | GET `/v1/operator/incidents/{incident_id}` | `operator:read` | `callcenter`, `support`, `dispatcher`, `planner`, `workshop` | – | ETag in Antwort |
| `transitionOperatorIncident` | POST `/v1/operator/incidents/{incident_id}/transitions` | `operator:write` | `dispatcher` | ja | If-Match |

Rollenbezeichnungen sind **Arbeitsnamen** (OP-34, D-11) und auf die vorhandenen Rollencodes abgebildet: Fahrer `driver`; Disposition/Leitstelle `dispatcher` (Lesen auch `callcenter`, `support`, `planner`); Flottenverwaltung und Werkstatt/technische Freigabe/Prüfer `workshop`; Betriebsleitung (Zweitfreigabe) `tenant_admin`. Eigene Codes (`fleet_manager`, `technical_release`, `inspector`) entstehen erst nach D-11 per Migration von `role_catalog`; bis dahin gilt die Matrix aus `rechtematrix.csv` (Zeilen mit Meilenstein `M3` ergänzt, Version und Sperrdatei erhöht die Integration). Die Vieraugenpflicht der Wiederfreigabe wird zusätzlich fachlich geprüft (zweite, andere Identität), nicht nur über Rollen.

## 2. Zustandsautomaten

Jede Kante nennt Auslöser, Akteur (Rolle) und Fehlercode; jede Datei enthält Negativbeispiele. Prüfung: [`tests/contract/test_m3_vertraege.py`](../../../tests/contract/test_m3_vertraege.py).

| Automat | Datei | Spalte / Aggregat |
|---|---|---|
| Prüflauf | [inspection_run.md](zustaende/inspection_run.md) (Alias [pruefung.md](zustaende/pruefung.md)) | `inspection_run.status` |
| Schadenakte | [defect_case.md](zustaende/defect_case.md) | `defect_case.status` |
| Fahrzeug-/Dienststatus | [fahrzeug_dienststatus.md](zustaende/fahrzeug_dienststatus.md) | `vehicle_service_status.status` (Historie `vehicle_status_history`) |
| Fahrzeugsperre | [fahrzeug_sperre.md](zustaende/fahrzeug_sperre.md) | `vehicle_block.status` (je Quelle) |
| Einsatzfreigabe | [einsatzfreigabe.md](zustaende/einsatzfreigabe.md) | `resource_release` (abgeleitet aus `revoked_at`, `valid_until`) |
| Reparaturauftrag | [reparaturauftrag.md](zustaende/reparaturauftrag.md) | `repair_order.status` |
| Incident | [incident.md](zustaende/incident.md) | `incident.status` |

Die bisherigen Platzhalter [fahrzeug.md](zustaende/fahrzeug.md) und [stoerung.md](zustaende/stoerung.md) (M1 OP-21) sind damit spezifiziert; die Registrierung in `StateMachines` erfolgt mit WP-M3-08 (Sperre, Freigabe) und WP-M3-53 (Incident).

## 3. Ereignisse und Schemas

Ereigniskatalog: [m3-ereigniskatalog.md](m3-ereigniskatalog.md); 13 Payload-Schemas unter `api/events/` (`fleet.*`, `inspection.completed`, `defect.*`, `repair.order_created`, `driver.*`, `vehicle.service_status_changed`, `incident.opened`, `alert.raised`). Die Fahrerprojektion `DriverRun` und der Ereignisvertrag `DriverEvent`/`DriverEventResult` sind OpenAPI-Schemas in `api/driver.yaml`.

## 4. Datenbank: DDL-Entwurf als Eingabe für WP-M3-02

Der Entwurf steht in [m3-ddl-entwurf.sql](m3-ddl-entwurf.sql) (**kein** Migrationsskript, nicht ausgeführt; WP-M3-02 übernimmt ihn als `V3020…`). Konvention wie M2: `tenant_id uuid NOT NULL`, Primärschlüssel `(tenant_id,id)`, lokale Fremdschlüssel zusammengesetzt, `ENABLE`+`FORCE ROW LEVEL SECURITY` mit Policy `tenant_isolation`, `revision`, `created_at`, Urheber; unveränderliche Tabellen mit Trigger `reject_mutation()` (SQLSTATE 23514).

| Tabelle | Schreibendes Modul | Unveränderlich | WP |
|---|---|---|---|
| `position_sample` | `staff-time` | append-only | WP-M3-34 |
| `checklist_template_revision`, `checklist_step` | `fleet-safety` | nach Veröffentlichung | WP-M3-10 |
| `inspection_run`, `inspection_answer`, `inspection_amendment` | `fleet-safety` | Antworten ab Abschluss, Ergänzungen append-only | WP-M3-13/-14/-15 |
| `defect_case`, `defect_evidence`, `defect_status_history` | `fleet-safety` | Evidenz und Historie append-only | WP-M3-43/-47/-48 |
| `repair_order` | `fleet-safety` | – | WP-M3-51 |
| `vehicle_release_decision` | `fleet-safety` | append-only | WP-M3-51 |
| `vehicle_block` (OP-01) | `fleet-safety` | Zeilen nur schließen, nie öffnen | WP-M3-08 |
| `vehicle_status_history` | `staff-time` | append-only | WP-M3-31 |
| `driver_device`, `driver_event` (Eingang, Dedupe), `driver_plan_ack` | `staff-time` | `driver_event`, `driver_plan_ack` append-only | WP-M3-24/-27/-29 |
| `boarding_code` (nur Hash) | `staff-time` | – | WP-M3-30 |
| `alert_policy_revision`, `business_alert`, `alert_acknowledgement` | `fleet-safety` | Revision und Quittierung append-only | WP-M3-52 |
| `incident`, `incident_link`, `workflow_rule_revision`, `workflow_run_log` (OP-02) | `fleet-safety` | Regelrevision, Protokoll append-only | WP-M3-53/-55 |
| `booking_change` | `booking-dispatch` | append-only | WP-M3-02/-39 |
| `known_defect_acknowledgement` | `fleet-safety` | append-only | WP-M3-21 |

Additiv an Kerntabellen: `vehicle` (Depot-Bezug, Fahrzeugklasse, Bedienart: OP-14, OP-22), `staff_shift` (FK `driver_ref`, Überlappungsverbot, OP-07/OP-32), `resource_release` (FK `inspection_ref`). `vehicle.active_block` wird abgeleitet (siehe fahrzeug_sperre.md).

## 5. Zuständigkeit und Abstimmung mit M2 (Akzeptanzkriterium 6)

| Operation / Ressource | Schreibendes Modul | M3-Zugriff |
|---|---|---|
| `vehicle_run`, `route_stop`, Plan-Commit, `booking_change`, `booking` | `booking-dispatch` | lesend über `ResourceAvailability` und Run-Lesemodell; Änderungen nur über Port `PlanCommit` (`createRunRevision`, `acknowledge` nicht betroffen) |
| `vehicle`, `vehicle_block`, `resource_release`, Prüf-/Schadentabellen, Incident | `fleet-safety` | `assertDispatchable(vehicle, shift, safety_revision)` als synchrone Modul-API (WP-M3-08), im Hold-Commit aufgerufen |
| `staff_shift`, `driver_event`, `position_sample`, Geräte, Dienststatus | `staff-time` | Fahrerereignisse wirken auf `booking-dispatch` über Port `TripExecution` (CHECK_IN/OUT, NO_SHOW), nie durch Schreiben fremder Tabellen |

- **Leseport `ResourceAvailability` (WP-M2-02):** M3 liefert die Sperr- und Freigabewahrheit als Implementierung (`fleet-safety`); `booking-dispatch` filtert Kandidaten damit und prüft im Hold-Commit erneut.
- **Sperrreihenfolge im Hold-Commit (WP-M2-22):** feste Reihenfolge `vehicle` (Zeile sperren, `safety_revision` lesen) -> `resource_release` -> `vehicle_run` -> `route_stop`/`capacity_hold`. Jede Sperr- oder Freigabeänderung sperrt `vehicle` zuerst und erhöht `safety_revision` im selben Commit; damit entsteht in keiner Serialisierung eine Zusage auf ein gesperrtes Fahrzeug (PH-AT-020).
- **Änderungsstrom (WP-M2-28) und Live-Kanal (WP-M3-35):** gemeinsames Protokoll: Server-Sent Events je Mandant mit Ereignis-ID und `plan_revision`/`aggregate_revision`; der Client holt nach Verbindungsverlust per Listenabfrage nach (`as_of`). Inhaltslose Weckrufe für geschlossene Apps (OP-29) enthalten keine Fahrgast- oder Ortsdaten; Provider offen (D-05).
- **Reihenfolge der Plan-Revisionen:** `dispatch.run_revised` (M2) bleibt das einzige Planereignis; M3 erzeugt keine eigenen Planereignisse, sondern ruft `PlanCommit`.

## 6. Konfiguration (Annahmen, additiv zum Katalog)

| Parameter | Wert (Planungsdefault) | Zweck |
|---|---|---|
| `position_stale_after_sec` | 30 | Veraltet-Kennzeichnung der Position in der Leitstelle (OP-04, Abnahme AT-10) |
| `inspection_release_max_hours` | 16 | Höchstgültigkeit der Einsatzfreigabe (OP-33) |
| `plan_ack_overdue_min` | 5 | Hinweis an die Leitstelle bei ausbleibender Bestätigung (DRV-004) |
| `workshop_auto_block_severity` | `SAFETY_CRITICAL` | ab dieser Klasse sperrt die Triage (OP-10) |
| `driver_sync_batch_max` | 100 | Obergrenze Ereignisse je Synchronisationslauf der App |

Die Aufnahme in `kataloge.csv` und den Parameterkatalog (WP-M1-19) ist **nicht** Teil dieses WP (Katalog eingefroren); die Werte gelten als Planungsdefaults bis dahin.

## 7. Offene Punkte OP-01 bis OP-34: Entscheidung, Annahme, Verantwortlicher

Alle Einträge sind **Annahmen des Plans** (Entscheidung durch den Auftraggeber steht aus, D-12); Verantwortlicher ist die genannte Rolle. „Blockiert“ nennt, was erst nach Klärung produktiv gehen darf.

| OP | Entscheidung / Annahme | Verantwortlich | Blockiert |
|---|---|---|---|
| 01 | Sperrgründe als Tabelle `vehicle_block` (Quelle OPERATIONS/SAFETY, Grundcode, Aufhebungsrecht je Quelle); `active_block` wird abgeleitet; V1-Default `true` bedeutet „nicht freigegeben“ und entfällt per expand/contract | Flotte/Sicherheit | nichts |
| 02 | Incident-Tabellen `incident`, `incident_link`, `workflow_rule_revision`, `workflow_run_log` (Modul `fleet-safety`); Ersatzsuche als `operation` mit nullbarem `journey_id` | Flotte/Sicherheit | nichts |
| 03 | `DriverEvent` erweitert um ARRIVAL, BREAK_START/END, DISRUPTION, SHIFT_END; `booking_id` nur für CHECK_IN/OUT/NO_SHOW Pflicht | Fahrer-App | nichts |
| 04 | Endpunkt `recordDriverPosition`; Veraltet ab 30 s (`position_stale_after_sec`); Aufbewahrung Rohpositionen 30 Tage (Annahme, D-10/D-11 offen), Intervall 10 s im Dienst | Fahrer-App | Produktivschaltung (D-05) |
| 05 | Aktualisierung per SSE mit Nachladen über Liste; Zielintervall 5 s, Statuswechsel sichtbar ≤ 10 s | Leitstelle | nichts |
| 06 | Überfälligkeit = Planzeit + `on_time_pickup_tolerance_min` überschritten und noch kein ARRIVAL; Zählung nach Buchungen, nicht Fahrgästen | Leitstelle | Betroffenenzahl bis Klärung |
| 07 | Schicht/Fahrer in M3 im Modul `staff-time` angelegt (Stammteil M1); `roster_revision` für M3-Schichten mit Sentinel 0 (expand); Doppelschicht nur durch `dispatcher` mit Begründung im Audit | Fahrer-App | Dienstplan (M4) |
| 08 | Fahrzeugwechsel in der Schicht = neue Prüfung (Art `VEHICLE_CHANGE`), Schicht bleibt, `staff_shift.vehicle_id` wird per Revision geändert, alte Freigabe widerrufen; Auslöser Fahrer oder Disposition | Flotte/Sicherheit | nichts |
| 09 | `vehicle_readiness_require_checks=false` schaltet nur nicht sicherheitskritische Schritte ab; Gate und `inspection_ref` bleiben (Validierung bei Publikation) | Flotte/Sicherheit | Parameterwirkung |
| 10 | Klassen `SAFETY_CRITICAL` (= S0), `OPERATIONALLY_RELEVANT`, `COSMETIC`, `OTHER`; `workshop_auto_block_severity` = `SAFETY_CRITICAL` | Flotte/Sicherheit | nichts |
| 11 | Wiederfreigabe über `createVehicleRelease`: Reparaturnachweis, bestandene Folgeprüfung, Rolle `workshop`, Vieraugen mit zweiter Identität (`tenant_admin`/`workshop`); M7-Abgleich offen | Flotte/Sicherheit | Rollen (D-11) |
| 12 | Gate-Anteil laut WP: Klassifikation, Sperre, Zuständiger, Historie, Alarm sicherheitskritisch; Fristen und Dublettenvorschlag erst M7c; M3 zeigt nur „offene Fälle desselben Fahrzeugs“ | Flotte/Sicherheit | AT-34/AT-36 Teilumfang |
| 13 | Evidenzupload über Uploadticket des Bausteins `documents-finance` (WP-M4-21); bis dahin Entwicklungsersatz mit Hash-Prüfung, Größe ≤ 10 MB, JPEG/PNG; Malware-Scan = M4 | Flotte/Sicherheit, Finanz/Belege | AT-33/-36 Medienprüfung |
| 14 | Fahrzeugklasse und Bedienart als Textcodes an `vehicle` (Katalogwerte offen); Vorlagenauswahl nach (Klasse, Bedienart) | Flotte/Sicherheit | Wertekatalog |
| 15 | Plausibilität: Kilometerstand darf nicht fallen und höchstens +2000 km/Tag springen, SOC 0 bis 100; Identität per Fahrzeugcode-Eingabe; Freigabe „mit Begründung“ durch Disposition | Flotte/Sicherheit | Schwellen |
| 16 | Vierte Antwort `NOT_CHECKABLE` -> Ergebnis `CLARIFICATION_REQUIRED` (technisch ungeklärt, keine Freigabe) | Flotte/Sicherheit | nichts |
| 17 | `RESTRICTED` nur für Barrierefreiheitsmängel (Rollstuhlplatz); Disposition erhält Einschränkung als Kapazitätsvektor ohne Rollstuhlplatz | Flotte/Sicherheit | weitere Mängel |
| 18 | Boarding-Code: 6 Ziffern, 24 h gültig, nur Hash (Argon2id) gespeichert, höchstens 5 Fehlversuche je Buchung; Ausgabe an Fahrgast erst WP-M7a-14; Fahrgäste ohne Code: Leitstellenbestätigung | Fahrer-App | Fahrgastanzeige |
| 19 | Übergabe an die Geräte-Navigation (kein eigenes Abbiegen); Ziel = Koordinate und Revision des nächsten Stopps | Fahrer-App | D-09 |
| 20 | Planzeit aus dem Routing gilt als Prognose, Kennzeichnung „Plan“ statt „Prognose“ bis zum ETA-Modell (M4) | Fahrer-App | nichts |
| 21 | No-Show-Ereignis mit Grund und Wartezeit; Gebührenwirkung nicht Teil M3; AT-08 „regelkonform“ ohne Gebühr zur Klärung | Fahrer-App | AT-08 Gebühr |
| 22 | Ereignisort als `location_kind` (COORDINATE, ADDRESS_TEXT, FREE_TEXT, DEPOT) plus Wert; `vehicle.depot_id` additiv (Depot aus M1) | Flotte/Sicherheit | nichts |
| 23 | Unfall-/Gesundheitsangaben nie in `defect_case`; eigener Zugriffsbereich (`defect_personal_note`) erst nach Klärung; M3 liefert nur die Trennung | Flotte/Sicherheit | fachlicher Umfang |
| 24 | Nur Fahrzeugausfall ist aktive Regel; Ablehnung/kritische Verspätung protokollieren „keine Aktion“ ohne hinterlegte Regel; Regelformat `workflow-regel.v1` | Flotte/Sicherheit | weitere Regeln |
| 25 | „Geografisch“ = Bediengebiet (`service_area`) über `vehicle_pool_assignment` (Fixture aus M2) | Buchung/Disposition | nichts |
| 26 | Fahrgastsuche über Blind-Index (exakter Treffer auf normalisierten Kontakt), Rollen `dispatcher`, `callcenter` | Buchung/Disposition | Suchfelder |
| 27 | Fahrgastinformation bei Fahrzeugausfall erst nach Ersatzentscheidung, Vorlage `vehicle_outage`; Kanäle wie M2-Nachrichten | Buchung/Disposition | Vorlagentexte |
| 28 | Nachtrag in `arbeitspakete.csv` nach Freigabe durch den Auftraggeber (außerhalb dieses WP) | Projektleitung | nichts |
| 29 | Weckruf ohne Inhalt über Push zulässig; Provider offen (D-05); ohne Push gilt Abruf alle 30 s im Vordergrund | Fahrer-App | Provider |
| 30 | Disposition bucht als Akteur für einen verifizierten Fahrgast (Änderung = neues Angebot mit atomarem Tausch); Delegierung/Dritte erst M7 | Buchung/Disposition | Legitimation |
| 31 | Vom Gerät gelieferte Bilder werden serverseitig neu kodiert, EXIF inkl. Standort entfernt; Aufnahmezeit wird neben dem Bild angezeigt (nicht eingebrannt) | Flotte/Sicherheit | AT-59 |
| 32 | Zusatztabellen nach Abschnitt 4; FKs `staff_shift.driver_ref`, `resource_release.inspection_ref` additiv nach `NOT VALID`+`VALIDATE` | Flotte/Sicherheit | nichts |
| 33 | `valid_until` = Schichtende, höchstens `inspection_release_max_hours`; nach Pause keine Neuprüfung, solange `safety_revision` gleich | Flotte/Sicherheit | nichts |
| 34 | Arbeitsnamen der Rollen wie in Abschnitt 1.1 auf vorhandene Codes abgebildet; verbindliche Matrix vor Produktivschaltung (D-11) | Flotte/Sicherheit | Produktivschaltung |
