# Ereigniskatalog M3 (WP-M3-01)

Outbox-Ereignisse der Module `fleet-safety` und `staff-time` (und die M3-Verbraucher der M2-Ereignisse). Hülle: [platform.eventenvelope.v1](../../../api/events/platform.eventenvelope.v1.schema.json). Payload-Schemas: `api/events/<event_type>.v1.schema.json`; Zustandsübergänge der Automaten nutzen zusätzlich [platform.statechanged.v1](../../../api/events/platform.statechanged.v1.schema.json) mit `machine` aus der Spalte „Automat“. `schema_version` aller Ereignisse: `1.0`, Empfänger: eigener Mandant (Föderation ab M6).

**Minimierungsregel (verbindlich):** Payloads enthalten nur Kennungen, Codes, Zähler und Zeitpunkte. **Nie:** Diagnose- oder Beschreibungstexte von Mängeln und Schäden, Foto- oder Dateiinhalte, Namen, Kontaktdaten, Fahrgastdaten, exakte Positionen. Wer Details braucht, liest sie über die API mit seinen Rechten nach. `tests/contract/test_m3_vertraege.py` prüft: jedes Schema `additionalProperties: false`, kein verbotenes Feld, Beispiele gültig, fehlerhaftes Beispiel abgewiesen.

| Ereignis (`event_type`) | Schreibendes Modul | Aggregat / Revision | Auslöser | Verbraucher | Automat |
|---|---|---|---|---|---|
| `fleet.vehicle_blocked` | `fleet-safety` | `vehicle_block` / 1 | Sperre gesetzt (operativ, Mangel, Schaden) | `booking-dispatch` (Kandidatenfilter), Leitstelle, Incident-Workflow | `fahrzeug_sperre` |
| `fleet.vehicle_block_lifted` | `fleet-safety` | `vehicle_block` / 2 | Sperre aufgehoben | `booking-dispatch`, Leitstelle | `fahrzeug_sperre` |
| `fleet.release_issued` | `fleet-safety` | `resource_release` / 1 | Einsatzfreigabe nach Prüfabschluss | `staff-time` (Dienststatus, Auftragsauslieferung), Leitstelle | `einsatzfreigabe` |
| `fleet.release_revoked` | `fleet-safety` | `resource_release` / 2 | Widerruf | `staff-time`, `booking-dispatch`, Leitstelle | `einsatzfreigabe` |
| `inspection.completed` | `fleet-safety` | `inspection_run` / n | `submitInspection` bestätigt | Leitstelle (Prüfstatus), Schadenakte (Mangel zu Schaden) | `inspection_run` |
| `inspection.template_unavailable` | `fleet-safety` | Hinweis (je Ereignis neue Aggregat-ID) / 1 | Prüflauf-Start ohne eindeutig freigegebene Vorlage (WP-M3-13) | Leitstelle | – |
| `inspection.release_withheld` | `fleet-safety` | Hinweis (je Ereignis neue Aggregat-ID) / 1 | Prüfabschluss ohne gültige Qualifikation, keine Einsatzfreigabe (WP-M3-19, nur Kennungen und Code, ohne HR-Inhalte) | Leitstelle | – |
| `defect.reported` | `fleet-safety` | `defect_case` / 1 | Schaden gemeldet | Leitstelle, Alarm (WP-M3-46) | `defect_case` |
| `defect.triaged` | `fleet-safety` | `defect_case` / n | Klasse gesetzt | Sperrkern, Alarm, Incident | `defect_case` |
| `defect.state_changed` | `fleet-safety` | `defect_case` / n | Status gewechselt (WP-M3-47, auch Wiedereröffnung) | Leitstelle, Alarm | `defect_case` |
| `defect.amended` | `fleet-safety` | `defect_case` / n | Nachtrag zur Meldung (WP-M3-48) | Leitstelle, Prüfer | `defect_case` |
| `repair.order_created` | `fleet-safety` | `repair_order` / 1 | Reparaturauftrag | Leitstelle, M4 (Kostenbezug) | `reparaturauftrag` |
| `repair.proof_recorded` | `fleet-safety` | `repair_order` / n | Reparatur abgeschlossen gemeldet, Nachweis erfasst (WP-M3-51); hebt keine Sperre auf | Leitstelle, M4 (Kostenbezug) | `reparaturauftrag` |
| `fleet.release_decided` | `fleet-safety` | `vehicle_release_decision` / 1 | Technische Freigabe erteilt: erste Entscheidung (ausstehend) oder abgeschlossene Vieraugenentscheidung (WP-M3-51) | Leitstelle | – |
| `fleet.release_attempt_rejected` | `fleet-safety` | Hinweis (je Ereignis neue Aggregat-ID) / 1 | Freigabeversuch abgewiesen (Rolle, fehlende Bedingung, veralteter Sicherheitsstand, WP-M3-51, AT-51); nur Codes | Leitstelle, Sicherheitsüberwachung | – |
| `driver.event_recorded` | `staff-time` | `driver_event` / 1 | Fahrerereignis verarbeitet (`APPLIED` oder `REVIEW_REQUIRED`) | `booking-dispatch` (Fahrtstatus, No-Show), Leitstelle | – |
| `driver.passenger_boarded` | `staff-time` | `driver_event` / 1 | Einstieg wirksam (WP-M3-29) | Analytics (Fakten), Leitstelle | `fahrerereignis` |
| `driver.passenger_alighted` | `staff-time` | `driver_event` / 1 | Ausstieg wirksam (WP-M3-29) | Analytics (Fakten), Leitstelle | `fahrerereignis` |
| `driver.no_show_recorded` | `staff-time` | `driver_event` / 1 | No-Show festgestellt mit Grund, Wartezeit und Regelstand, ohne Gebühr (WP-M3-29) | Tarif/Zahlung (M2/M7), Analytics | `fahrerereignis` |
| `driver.review_opened` | `staff-time` | `driver_event` / 1 | Klärfall: Fahrerereignis nicht wirksam (WP-M3-29) | Leitstelle | `fahrerereignis` |
| `driver.boarding_code_locked` | `staff-time` | `booking` / 1 | Codeeingabe nach 5 Fehlversuchen gesperrt (WP-M3-30), Hinweis an die Leitstelle | Leitstelle | – |
| `driver.run_acknowledged` | `staff-time` | Bestätigung / 1 | Fahrer bestätigt Planrevision | Leitstelle (ausbleibende Bestätigung) | – |
| `vehicle.service_status_changed` | `staff-time` | `vehicle_service_status` / n | Dienststatuswechsel | `booking-dispatch` (nicht neu disponieren), Leitstelle | `fahrzeug_dienststatus` |
| `incident.opened` | `fleet-safety` | `incident` / 1 | Workflowregel (Fahrzeugausfall) | Leitstelle, `booking-dispatch` (Ersatzsuche) | `incident` |
| `alert.raised` | `fleet-safety` | `business_alert` / 1 | Alarmregel (WP-M3-52), technische Betriebsalarme (WP-M5-04) | Leitstelle, Betreiberkanal | – |
| `alert.acknowledged` | `fleet-safety` | `business_alert` / n | Quittierung (WP-M3-52) | Leitstelle (Live-Kanal) | `alarm` |
| `alert.escalated` | `fleet-safety` | `business_alert` / n | Eskalation nach `alert_escalation_delay_min` (WP-M3-52) | Leitstelle (Live-Kanal) | `alarm` |
| `alert.resolved` | `fleet-safety` | `business_alert` / n | Behebung eines technischen Alarms (WP-M5-04) | Leitstelle (Live-Kanal) | `alarm` |
| `dispatch.run_revised` (M2) | `booking-dispatch` | `vehicle_run` / n | Planrevision | `staff-time` (Fahrerprojektion, Hinweis), Leitstelle | `fahrtenplan` |
| `booking.confirmed`, `booking.cancelled` (M2) | `booking-dispatch` | `booking` / n | Buchung, Storno | `staff-time` (Stoppliste) | `buchung-lokal` |

**Keine Ereignisse für Positionen:** `position_sample` entsteht über `recordDriverPosition` (Hochfrequenz) und wird nicht über die Outbox verteilt; die Leitstelle liest den Stand über den Live-Kanal (WP-M3-35) und die Listenabfrage. Das Standortalter berechnet der Server beim Lesen.

**Zuständigkeit (Akzeptanzkriterium 6):** Jedes Ereignis und jede Tabelle hat genau ein schreibendes Modul (Tabelle „Zuständigkeit“ in [m3-detailvertraege.md](m3-detailvertraege.md)). `booking_change`, `route_stop` und der Plan-Commit gehören ausschließlich `booking-dispatch`; M3 ruft sie über Ports auf (Abschnitt „Abstimmung mit M2“). Regeln: Ereignis und Aggregatänderung in derselben Transaktion; mindestens einmal zugestellt, Verbraucher deduplizieren über `event_id` (Inbox); ein Ereignis zu einer älteren Aggregatrevision überschreibt nie einen neueren Zustand.
