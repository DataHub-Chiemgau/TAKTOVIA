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

Siehe Abschnitt 4 (wird mit dem Paket ergänzt).

## 3. Abweichung und Schadenakte aus Prüfschritt oder Störung (WP-M3-20)

Siehe Abschnitt 5 (wird mit dem Paket ergänzt).
