# Detailvertrag Live-Kanal, Planbestätigung und Alarm-Grundlage (Cluster wave12-c5)

- **Arbeitspakete:** WP-M3-35 (Live-Aktualisierungskanal), WP-M3-27 (Planänderungen, Verweis in `LH-6.6-DRV-004`), WP-M3-52 (Alarm-Grundlage), WP-M5-04 (Alerting, Verweis in `LH-6.19-NFR-008`).
- **Status:** Vorschläge dieses Plans (OP-05, OP-29, OP-07); keine menschliche Freigabe (D-12).

## 1. Live-Kanal (WP-M3-35, OP-05)

**Protokoll.** Server-Sent Events über `fetch` (das Token geht im Header, nie in der Adresse; deshalb kein `EventSource`):

| Operation | Pfad | Scope | Rollen |
|---|---|---|---|
| `streamOperatorLiveEvents` | `GET /v1/operator/live/events` | `operator:read` | `callcenter`, `support`, `dispatcher`, `planner`, `workshop` |
| `listOperatorLiveEvents` (Rückfall Polling) | `GET /v1/operator/live/feed` | `operator:read` | wie oben |
| `streamDriverLiveEvents` | `GET /v1/driver/live/events` | `driver:run` | `driver` (Rückfall: `listDriverPlanChanges`, WP-M2-28) |

Rahmen des Stroms: `retry: 3000`, `event: ready` (`cursor`, `as_of`), je Änderung `id: <cursor>` und `event: live` mit der Hülle `LiveEvent` (`event_id`, `cursor`, `type`, `topic`, `aggregate_id`, `aggregate_revision`, `service_id`, `shift_id`, `status`, `occurred_at`), Kommentar-Herzschlag alle 15 s, am Ende `event: end` mit festem Grund (`max_duration`, `session_revoked`, `membership_revoked`, `unavailable`). Eine Verbindung dauert höchstens `wait_seconds` (Standard 25, höchstens 55); der Client verbindet mit `Last-Event-ID` bzw. `after` neu.

**Cursor.** `<tx>.<seq>` aus `live_event` (`V3350`). `seq` ist nicht in Commit-Reihenfolge; ausgeliefert werden nur Zeilen unterhalb des Snapshot-Horizonts (`pg_snapshot_xmin`), sortiert nach `(tx, seq)`. Damit liegt ein später sichtbar werdender Eintrag nie hinter einem ausgelieferten Cursor (Nachweis `db/tests/test_m3_35_live.py::test_snapshot_horizont_haelt_neuere_eintraege_hinter_einer_offenen_aelteren_transaktion_zurueck`). Ohne Cursor beginnt der Strom am aktuellen Stand: Der Zustand kommt immer über die gewöhnlichen Listen, **jedes** Ereignis ist nur Hinweis mit Referenz und Revision. Der Client lädt nach jedem (Wieder-)Aufbau seinen Zustand neu (`onResync`), bevor er Ereignisse anwendet; die Revisionsprüfung je Aggregat bleibt Sache der Ansicht (`RevisionTracker`, WP-M2-28). So bleibt keine Anzeige dauerhaft falsch.

**Quelle.** Trigger `outbox_live` (AFTER INSERT auf `outbox`) schreibt in derselben Transaktion eine Zeile `live_event`, aber nur für die Ereignisfamilien `dispatch`, `alert`, `incident`, `fleet`, `vehicle`, `inspection`, `defect`, `repair`; Statusfelder nur aus der Positivliste (`state`, `status`, `priority`, `alert_kind`, `revision`, `previous_revision`, `vehicle_id`, `run_id`, `shift_id`, `cause`, `severity`, `alert_id`, `escalation_level`), skalar und nur in Kennungsform (`^[A-Za-z0-9_.:-]{1,64}$`). Rohpayloads, Namen, Adressen und Koordinaten gelangen nie in den Kanal. Schreiben ist nur aus dem Trigger möglich (Wächter `live_event_insert_guard`, Triggertiefe ≥ 2; die Konvention verbietet `SECURITY DEFINER`).

**Berechtigung je Abruf.** Bei jedem Datenbankabruf (Standard 1 s, `taktovia.live.poll-interval-ms`) werden neu bestimmt: Sitzung (`SessionGate`: gesperrt beendet den Strom mit `session_revoked`), Mitgliedschaft und Rolle (`membership_revoked`), Dienstscope (Schnitt aus gewählter Dienstbegrenzung und heutigem Scope; Ereignisse ohne Dienstbezug gelten mandantenweit), beim Fahrer die eigenen, nicht beendeten Schichten (`DriverShifts.liveShiftIds`). Ein Entzug wirkt damit innerhalb eines Abrufintervalls.

**Rückfall und Veraltet-Kennzeichnung (Web).** `web/operator/src/live/LiveChannel.tsx`: nach drei Fehlschlägen in Folge Abruf (`listOperatorLiveEvents`); ohne Lebenszeichen (Nachricht, Herzschlag, Ende) länger als 40 s `stale`; 401/403 beenden den Kanal ohne stillen Wiederaufbau. Datenstand-Anzeige und zurückhaltende Live-Region (nur Zustandswechsel).

**Grenzen.** Ein Abonnement belegt einen Anfrage-Thread; `taktovia.live.max-connections` (200) begrenzt sie je Instanz (darüber 503 mit `Retry-After`). Der Lastnachweis liegt in M5. Mehrere Instanzen: jede liest dieselbe Tabelle, es gibt keinen Zustand im Prozess. Aufbewahrung: `live_event` ist ein Puffer; die Bereinigung (`LiveFeed.prune`) löscht nach Stunden (kein Betriebsauftrag verdrahtet, Mandantenliste des Betriebs, OP-06/OP-08).

## 2. Planbestätigung (WP-M3-27, OP-29)

Siehe `zustaende/plan_revision_fahrer.md`. API: `acknowledgeDriverRunRevision` (`POST /v1/driver/shifts/{id}/run/acknowledgements`, `If-Match` = bestätigte Planrevision; veraltet 412 mit aktuellem ETag, fehlend 428, fremde Schicht 404, `If-Match` ≠ Body 422). Tabelle `driver_plan_ack` (V3020, unveränderlich). Weckruf: Nutzlast nur `{"type":"plan_revision","revision":"N"}` (`PlanRevisionWakeup`); Anbieter und Gerätetoken offen (OP-29, D-05), nicht verdrahtet.

## 3. Alarm-Grundlage (WP-M3-52)

Siehe Abschnitt 3 im Anschluss (wird mit dem Paket ergänzt).
