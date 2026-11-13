# Detailvertrag Incident-Workflow, Ablaufprotokoll und Abweichung aus Prüfschritt oder Störung (Cluster wave13-c4)

- **Arbeitspakete:** WP-M3-53 (Workflow, Incident), WP-M3-20 (Abweichung, Schadenakte aus Prüfschritt oder Störung), WP-M3-55 (Ablaufprotokoll).
- **Verträge:** `LH-I.8-AUT-001`, `LH-I.8-AUT-002`, `LH-6.21-CHK-011`, `LH-6.22-DMG-003`; Automat [zustaende/incident.md](zustaende/incident.md).
- **Status:** Vorschläge dieses Plans (OP-02, OP-24, OP-13, OP-31); keine menschliche Freigabe (D-12). Modul `fleet-safety` führt Incident, Workflow und Ablaufprotokoll; die Ersatzfahrzeugsuche ruft einen Port.

## 1. Workflow und Regelformat (WP-M3-53)

- **Ereignisarten** (registriert): `VEHICLE_BLOCKED` (Bus: `fleet.vehicle_blocked`), `DISRUPTION_REPORTED` (Bus: `vehicle.service_status_changed` mit `current = DISRUPTED`), `CRITICAL_DEFECT` (Bus: `defect.triaged` mit `severity = SAFETY_CRITICAL`),
  `DEFECT_REPORTED` (Bus: `defect.reported`), `REJECTION` (Bus, reserviert: `dispatch.request_rejected`), `CRITICAL_DELAY` (Bus, reserviert: `dispatch.critical_delay`). Jede andere Art ist unbekannt:
  Eintrag `REJECT_EVENT/UNKNOWN_EVENT_TYPE` im Ablaufprotokoll, keine Wirkung (Arbeitsannahme des Vertrags). Ein Fahrzeug, das im Empfängermandanten nicht existiert (fremd, gelöscht), ergibt `SKIP_EVENT/UNKNOWN_VEHICLE`.
- **Regelformat** `workflow-regel.v1` ([Schema](schemas/workflow-regel.v1.schema.json)): `{schema, event_kind, actions[]}` mit den Aktionen `OPEN_INCIDENT(incident_kind)` und `START_REPLACEMENT_SEARCH` (setzt einen vorher eröffneten Incident voraus; je Aktion höchstens einmal).
  Eine leere Aktionsliste ist gültig. Die Veröffentlichung (`IncidentWorkflow.publishRule`, Disposition und Betriebsleitung, Audit `fleet.workflow.rule_published`) ist eine neue unveränderliche Zeile in `workflow_rule_revision`;
  die Revisionsnummer je (Mandant, Ereignisart) vergibt ein Datenbanktrigger unter einer Sperre (lückenlos, auch parallel). Einen HTTP-Endpunkt zur Veröffentlichung sieht der Vertrag nicht vor.
- **Standardregeln** (Revision 1 je Mandant, angelegt beim ersten Ereignis der Art, Urheber `system:workflow-default`): `VEHICLE_BLOCKED`, `DISRUPTION_REPORTED`, `CRITICAL_DEFECT` eröffnen einen `VEHICLE_OUTAGE`-Incident und starten die Ersatzsuche.
  Für `DEFECT_REPORTED`, `REJECTION`, `CRITICAL_DELAY` gibt es ohne Veröffentlichung keine Regel: Ablaufprotokoll `EVALUATE_RULE/NO_RULE` und `NO_ACTION/NO_ACTION`, kein Fehler (OP-24).
- **Wirksame Regel** ist die höchste Revision zur Verarbeitungszeit. Ein bereits verarbeitetes Ereignis wird nie neu bewertet (Ereignissperre und Protokoll `(event_id, action)`); jeder Incident nennt die Regelrevision, nach der er entstand.
- **Dedupe-Schlüssel** des Incidents: `<Incident-Art>:<Fahrzeug-ID>` (ohne Fahrzeug: `<Art>:<Bezugskennung>`, höchstens 100 Zeichen aus `[A-Za-z0-9_.:-]`). Sperre, Schadenmeldung und Störungsmeldung desselben Fahrzeugs ergeben denselben Schlüssel, solange ein Incident
  offen ist (`OPEN`, `REPLACEMENT_SEARCH`); nach dem Abschluss ist ein neuer Ausfall ein neuer Incident. Drei Schichten: Ereignissperre, Ausfallsperre je Schlüssel (`pg_advisory_xact_lock`), partieller Unique-Index `incident_one_open_per_key`.
- **Ersatzsuche:** `operation` der Art `REPLACEMENT_SEARCH` ohne Reisebezug (`journey_id` NULL, Entscheidung OP-15/OP-02), Frist 30 min; je Incident genau eine (Unique auf `incident.replacement_operation_id`). Der Handler ruft den Port `ReplacementSearchPort`
  (Umsetzung WP-M3-54, Modul `booking-dispatch`); **ohne Umsetzung meldet der Standard `Failed("REPLACEMENT_SEARCH_NOT_AVAILABLE")`**, der Incident kehrt nach `OPEN` zurück und bleibt sichtbar, ein Ergebnis wird nie vorgetäuscht. `Proposed(n)` lässt ihn in
  `REPLACEMENT_SEARCH`, `NoneAvailable` und `Failed` führen nach `OPEN`.
- **Ereignisse:** `incident.opened` (Schema vorhanden), `incident.replacement_search_started` (neu, [Schema](../../../api/events/incident.replacement_search_started.v1.schema.json)), je Incident höchstens einmal.
- **Ereigniskonsum:** über die Inbox (`consumer = incident-workflow`), jedes Ereignis als eigenes Aggregat (Revision 1). Die Anbindung an den Broker ist wie bei den übrigen Konsumenten nicht ausgeführt (kein Docker/RabbitMQ).
- **API:** `GET /v1/operator/incidents` und `GET /v1/operator/incidents/{id}` (Rollen `callcenter`, `support`, `dispatcher`, `planner`, `workshop`, Scope `operator:read`); Dienstbezug: `incident.service_id` ist der Dienst der laufenden Poolzuordnung des Fahrzeugs, wenn es genau
  einen gibt, sonst mandantenweit sichtbar. `transitionOperatorIncident` bleibt offen (WP-M3-54).

## 2. Ablaufprotokoll (WP-M3-55)

- **Ablage:** `workflow_run_log` (V3020, append-only per Trigger `reject_mutation`; die Runtime-Rolle hat nur SELECT/INSERT), erweitert in `V30550`: `seq` (laufende Ordnungsnummer je Incident, lückenlos und eindeutig), `event_type` (Auslöser),
  `decision`, `result_ref`, `reason_code`, `correlation_id` (Bezug zu `audit_event.correlation_id`); `created_by` ist der Akteur (`system:workflow` oder `user:<Kennung>`), `created_at` die Serverzeit. Bei der Wiederzustellung desselben Ereignisses entstehen keine
  weiteren Zeilen (`(event_id, action)` ist eindeutig).
- **Ordnungsnummer:** ein Trigger vergibt sie beim Einfügen mit einem bedingten UPDATE auf der Incident-Zeile (`incident.log_seq`); die Zeilensperre serialisiert parallele Schreiber desselben Incidents, ein Rollback nimmt die Vergabe zurück (Nachweis
  `db/tests/test_ablaufprotokoll.py`, 80 parallele Einfügungen: 1..80 ohne Lücke). Eine vom Client gesendete `seq` wird überschrieben. Einträge ohne Incident (Ereignis ohne Regel, unbekannte Art, übersprungene Eröffnung) haben keine Nummer.
- **Einträge einer Ausführung** (in dieser Reihenfolge): `EVALUATE_RULE` (Auslöser, Regelversion, Entscheidung `RULE_MATCHED` mit `MATCHED:<n>` bzw. `NO_RULE`), je Folgeaktion `OPEN_INCIDENT` (`CREATED`, `JOINED`, `SKIPPED_NO_SUBJECT`), `START_REPLACEMENT_SEARCH`
  (`STARTED` mit Operation, `ALREADY_STARTED`, `INCIDENT_NOT_OPEN`, `SKIPPED_NO_INCIDENT`), später `SEARCH_RESULT` (`PROPOSALS:<n>`, `NO_RESULT`, `FAILED:<Code>`), `NO_ACTION`; Leitstellenentscheidungen als `DISPATCH_DECISION` (Ergebnis = Entscheidungscode, `reason_code`,
  `decision = MANUAL_DECISION`), geschrieben über `IncidentWorkflow.recordDecision` (nur Disposition und Betriebsleitung; WP-M3-54 ruft sie).
- **Minimierung:** Aktion, Ergebnis, Entscheidung, Grund und Referenz sind Codes (Zeichenklassen als CHECK in der Datenbank), nie Freitext, Beschreibung oder Rohpayload; Personendaten können nicht hinein. Die Prüfungen gelten für neue Zeilen (`NOT VALID`), Altzeilen
  aus Entwicklungsständen bleiben unverändert.
- **Lesen:** `GET /v1/operator/incidents/{id}` liefert `workflow_log` (Schema [ablaufprotokoll.v1](schemas/ablaufprotokoll.v1.schema.json), `WorkflowLogEntry` in `api/fleet-safety.yaml`) in der Reihenfolge der Ordnungsnummer; die Liste enthält es nicht. Rollen wie für den Incident
  (`callcenter`, `support`, `dispatcher`, `planner`, `workshop`; **Betriebsleiter-Zuordnung offen**: das Pflichtenheft legt die Rollenliste nicht fest, `tenant_admin` hat nach der Rechtematrix keinen Zugriff auf Betriebsoperationen), ohne Rolle 403, fremder Mandant oder Dienst 404.
  Schreibende Endpunkte gibt es nicht (PUT, PATCH, DELETE, POST am Incident und am Protokoll: 404/405).
- **Oberfläche UI-07:** `web/operator/src/alarm/Vorfaelle.tsx`, Route `/leitstelle/vorfaelle`: Incidents mit Fahrzeug, Ursache, Zustand und Verknüpfungen; Zeitleiste des Vorfalls (Auslöser, Regelversion, Entscheidung, Folgeaktionen samt Ergebnis, auch fehlgeschlagene und
  übersprungene), Leerzustand, Ladefehler mit Datenstand, Rechtezustand.
- **Nicht im Umfang:** Auswertungen über viele Vorfälle (M8).

## 3. Abweichung und Schadenakte aus Prüfschritt oder Störung (WP-M3-20)

- **Abweichung = Schadenakte mit Herkunft.** Text, Kategorie und Fotos einer Abweichung sind Beschreibung, Kategorie und Fotoevidenz der Akte (`defect_case`, `defect_evidence`); die Fotos laufen über denselben Upload- und Prüfpfad wie Schadensfotos
  (Uploadticket mit Pflicht-`captured_at`, Quarantäne, Medienprüfung, WP-M3-44): ein Foto ohne bestandene Medienprüfung ist nicht an der Akte sichtbar (`evidence_pending`). Das Aufnahmedatum ist die Gerätezeit (nicht vertrauenswürdig), die Serverempfangszeit steht daneben (OP-31).
- **Herkunft Prüfschritt:** `POST /v1/defects` mit `source_inspection_id` und `source_step_code` (beide oder keines, sonst 422). Der Prüflauf muss im Mandanten liegen (sonst 404), ein Fahrer nur einen Lauf einer eigenen Schicht (sonst 404 ohne Hinweis), das Fahrzeug ist das des Laufs (422).
  Der Schritt muss **negativ beantwortet** sein (Antwort `DEFECT`, auch im Entwurf, oder ein Nachtrag auf `DEFECT`), gleich ob er sicherheitskritisch ist (Kriterium 6); sonst 422 am Feld `/source_step_code`. Je Schritt höchstens eine Akte
  (`defect_case_origin_step_unique`): wiederholtes Auslösen, nach Zeitüberschreitung oder von einem zweiten Gerät auch gleichzeitig, liefert dieselbe Akte (200, `created = false`); gleichzeitige Anlage trifft den Unique-Index, die unterlegene Transaktion rollt zurück und liest die Akte.
- **Herkunft Störung:** `source_incident_id` (schließt die Herkunft Prüfschritt aus, 422). Der Incident muss im Mandanten und im Dienstscope liegen (sonst 404), ein Fahrer kennt keine Incidents (404), das Fahrzeug ist das des Incidents (422). Die Akte wird am Incident verknüpft
  (`incident_link`, Art `DEFECT`); sie ist über `defect_ids` des Incidents erreichbar. Zu einem Incident dürfen mehrere Akten gehören (Wiederholung über `device_event_id`).
- **Gegenreferenz ohne Mutation (Invariante b):** `inspection_answer` bekommt **keine** Spalte `defect_case_id`; die Gegenreferenz Prüfschritt -> Akte ergibt sich aus der Herkunft der Akte (`GET /v1/inspections/{id}` -> `defect_refs`, Akte -> Prüfschritt: `source_*` an `GET /v1/defects/{id}`).
  Damit wird die Antwort eines abgeschlossenen Laufs nie verändert (Nachweis `test_defect_origin.py::test_verknuepfung_veraendert_abgeschlossene_antwort_nicht`). Ein Fahrer sieht in `defect_refs` nur Akten, die er selbst gemeldet hat (DMG-011). Die Herkunft ist unveränderlich (Trigger).
- **Offen (nicht festgelegt):** das Verhalten, wenn eine Entwurfsantwort mit Akte später von „Nein“ auf „Ja“ geändert wird: die Akte bleibt bestehen und wird nicht automatisch verworfen (die Disposition entscheidet); die Antwortoptionen „Nein – Mangel melden“ gegenüber „Mangel“ (OP-16); das Aufnahmedatum im Bild
  (OP-31, hier: nur neben dem Bild); der Fahrerbezug der Schadenmeldung über den Fahrerstamm (die Standardumsetzung `ShiftDriverRelation` vergleicht `driver_ref` mit der IdP-Kennung, Annahme OP-34).
- **Oberfläche:** UI-09 (Fahrer-App) `mobile/driver/lib/pruefung/step_defect_origin.dart` mit Test, **nicht ausgeführt** (kein Flutter); UI-11 (Sprung Prüfung <-> Akte) und die Sicht des Incidents in UI-07 verwenden die Felder `defect_refs`, `source_*` und `defect_ids`.
