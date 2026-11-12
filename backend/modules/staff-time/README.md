# staff-time – Personal und Zeit

Paket `de.taktovia.stafftime` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** RosterRevision, Shift, Qualification, TimeEvent; Tabelle staff_shift.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

**Fahrer-App-Basis (WP-M3-24):** `StafftimeApi.driverDevices` (Geräte-/Sitzungsbindung, `driver_device`) und `driverEvents` (Ereigniseingang mit dauerhafter Deduplikation, `driver_event`); Routen `POST /v1/driver/devices`, `DELETE /v1/driver/devices/{device_id}`, `POST /v1/driver/events` (Controller in `app`). Annahme bis WP-M3-05: `staff_shift.driver_ref` ist die IdP-Subject-UUID. Die fachliche Wirkung auf Stopps und Buchungen (WP-M3-29) hängt später am Port `DriverEvents`.

## Abwesenheit und Ist-Arbeitszeit (WP-M4-06, WP-M4-08)

`StafftimeApi.absences` (Verfügbarkeitsrestriktion, nur Personalrolle legt an, Planer sehen „nicht verfügbar“) und `StafftimeApi.timeEvents` (unveränderliche Zeitereignisse, Intervallableitung `TimeIntervals`); Verträge und Annahmen: [m4-cluster-c5.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-c5.md).

## Schichtzuweisung der Leitstelle (WP-M3-07)

`StafftimeApi.shifts` (`/v1/shifts`, `api/shifts.yaml`): Anlegen, Lesen, Auflisten, Ändern und Entziehen einer Zuweisung Fahrer, Fahrzeug, `[starts_at, ends_at)`. Überlappung je Fahrer und je Fahrzeug ist ein Konflikt (409 `CONFLICT`, Ursache `/driver_id` oder `/vehicle_id`, kollidierende Schicht im Detail), Ende gleich Beginn nicht; die Dauer folgt aus absoluten Zeitpunkten. Durchgesetzt von der Anwendung (Sperre je Fahrer und Fahrzeug, `staff_shift_lock`/`staff_shift_clash`) **und** vom Datenbank-Trigger `staff_shift_overlap_guard` (V3070) für jeden Schreibpfad. Dokumentierte Doppelschicht (`overlap_exception`: Genehmiger, Grund, Serverzeit) nach Planungsannahme PA-03/OP-07. Zustände und Übergänge: [staff_shift.md](../../../docs/umsetzungsplan/vertraege/zustaende/staff_shift.md). `roster_revision` bleibt vor M4 der Platzhalter 0. Neue Schreibpfade auf `staff_shift` (auch M4) brauchen nichts weiter: der Trigger gilt; sie dürfen aber nur die Zustände `PLANNED`, `STARTED`, `ENDED`, `CANCELLED` und `PUBLISHED` nutzen.

## Dienstanmeldung und Schicht-/Fahrzeugzuordnung (WP-M3-25)

`StafftimeApi.driverShifts` (`GET /v1/driver/shifts`, `POST /v1/driver/shift-starts`, `POST /v1/driver/shift-ends`, `GET /v1/driver/shifts/{id}/run`, `api/driver.yaml`): nur der Fahrer (Rolle `driver`, Scope `driver:run`, Sitzung); eigene Schicht heißt Fahrerstamm über die Mitgliedschaft oder (Altbestand) Subject als `driver_ref` ([ShiftOwnership](src/main/kotlin/de/taktovia/stafftime/internal/ShiftOwnership.kt)), jede andere Schicht ist 404. Antritt ab `starts_at` minus Toleranz (Planungsdefault 30 Minuten, `taktovia.stafftime.shift-start-tolerance-minutes`), mit dem Fahrzeug der Schicht und einem an die Sitzung gebundenen Gerät; gleichzeitige Geräte desselben Fahrers ergeben genau einen Antritt (Zeilensperre). Der Run liefert Aufträge nur bei angetretener, gültiger und freigegebener Schicht (`resource_release`, kein operativer Block); die Stoppliste kommt über den Port `DriverRunSource` (WP-M3-26), bis dahin leer. Der Antritt ist weder Arbeitszeiterfassung noch Einsatzfreigabe. Zustände: [staff_shift.md](../../../docs/umsetzungsplan/vertraege/zustaende/staff_shift.md).

## Positionserfassung und Standortalter (WP-M3-34)

`StafftimeApi.positions` (`POST /v1/driver/positions`, Leitstellenliste `GET /v1/operator/vehicles` über die App-Ebene): angenommen wird nur für die eigene, **angetretene** Schicht und ihr Fahrzeug an einem an die Sitzung gebundenen Gerät (sonst 404/403, nicht angetreten oder entzogen 409, nichts gespeichert). Messzeit (Gerät, `measured_at`) und Empfangszeit (Serveruhr, `received_at`) sind getrennt gespeichert; die Messzeit liegt zwischen Antritt und Ende des Dienstes und höchstens die Toleranz in der Zukunft, sonst wird der Punkt einzeln abgewiesen (`FUTURE_TIME`, `OUTSIDE_SERVICE`). Erneute Sendung derselben Messzeit ist idempotent (Eindeutigkeit `(tenant_id, shift_id, measured_at)`, V3340). „Letzte Position“ ist die jüngste **Messzeit**; verspätete ältere Meldungen überschreiben sie nie und nachgelieferte Punkte sind veraltet. `position_stale` gilt ab Überschreiten der Schwelle, die letzte gemessene Position bleibt sichtbar (nie extrapoliert, AT-10).

| Einstellung (`taktovia.stafftime.…`, Planungsdefaults, OP-04) | Standard |
|---|---|
| `position-stale-after-seconds` (Schwelle des Standortalters, kein Katalogparameter) | 30 |
| `position-future-tolerance-seconds` (Zukunftstoleranz der Messzeit) | 5 |
| `shift-start-tolerance-minutes` (Antrittstoleranz, WP-M3-25) | 30 |

**Aufbewahrung (D-10, D-11 offen):** Das Löschziel `LK_STANDORT` (`PositionRetention.target()`, nur Aktion DELETE, Frist ab Empfangszeit) löscht Rohpositionen nach der Frist der **freigegebenen** Regel des Aufbewahrungsregisters; ein Standardwert ist im Code nicht hinterlegt (die „30 Tage“ des Detailvertrags sind nur eine Planungsannahme). Mit `taktovia.environment=production` nimmt der Server Positionen nur an, wenn für `LK_STANDORT` eine freigegebene Regel besteht (409 sonst); in den anderen Umgebungen wird auch ohne Regel erfasst. Geschwindigkeit und Fahrtrichtung der Fahrer-App werden angenommen, aber nicht gespeichert (Minimierung, kein Zweck in M3).

## Dienstplan: Entwurf, offene Schichten, Konflikte, Depotrecht (WP-M4-04, -05, -07, -16)

`StafftimeApi.rosters` (`/v1/rosters`, `api/rosters.yaml`): Dienstplan je Depot und Kalenderwoche mit Revisionen (`roster_revision`, V40040), getrenntem **Entwurf** (nur Planer, Scope `rosters:read|write`, Depotrecht und restriktive RLS-Policy über den transaktionslokalen Schalter `app.roster_draft_access`) und **veröffentlichter Revision** (unveränderlich, verbindliche Sicht `view=published`); `openDraft` kopiert die veröffentlichten Schichten in einen neuen Entwurf. Entwurfsänderungen erzeugen keine Outbox-Ereignisse. Der Stand des Dienstplans (ETag) steigt mit jeder Schichtänderung; Änderer sperren die Entwurfszeile (Reihenfolge Depotrecht, Dienstplan, Schicht). Zustände: [dienstplan.md](../../../docs/umsetzungsplan/vertraege/zustaende/dienstplan.md). Das Überlappungsverbot der Schichtzuweisung (V3070) gilt seit V40040 nur für verbindliche Schichten (OP-38).

**Offene Schichten** (SCH-008): ohne Person und ohne Fahrzeug; Zähler `open_shifts`, `unstaffed_need` und Bedarf je Tag und Funktion im Lesemodell, Filter `open=true`. **Konfliktprüfung** (SCH-006, `RosterConflictChecker`): Doppelbelegung, Qualifikation zum Schichtzeitpunkt, genehmigte Abwesenheit (nie mit Art); Hinweis beim Speichern, Konfliktliste im Lesemodell, Sperre `Rosters.requirePublishable` (409 mit `ViolationCode.ROSTER_*`) – der Veröffentlichungsbefehl selbst ist WP-M4-14. **Depotrecht** (SCH-009): `StafftimeApi.rosterDepotAccess` (`roster_depot_grant`, V40160), fail-closed, `ALL_DEPOTS` als ausdrückliches depotübergreifendes Recht (auditiert); nicht sichtbare Dienstpläne sind 404.

## Hinweise, Veröffentlichung, Historie, Eigensicht (WP-M4-13, -14, -15, -20)

**Regelhinweise** (TIM-006, `WorkRuleEngine`, `RosterHints`): Ruhezeit (`min_rest_hours`, Standard 11 h), Tagesarbeitszeit (`max_daily_work_minutes`, Standard 600 min) und Pflichtpause werden aus absoluten Zeitpunkten berechnet (DST-fest, Grenzwert exakt = kein Hinweis) und als Warnung mit Regel, Sollwert, Istwert und Bezug geliefert: im Lesemodell von `/v1/rosters` (Arbeitsrevision, nur mit Depotrecht) und in `listTimeTargetActual` (nur Eigensicht). Hinweise werden nie gespeichert und sperren nie. Die Parameter stehen mit rechtlichen Systemgrenzen im Register (V40130, Planungsannahme: Ruhezeit mindestens 11 h, Tagesarbeitszeit höchstens 600 min); `WorkRuleSource` liefert die wirksamen Werte (App: `ConfigWorkRuleSource`). **Planungsannahme, nicht freigegeben:** die Pausenschwellen (mehr als 6 h: 30 min, mehr als 9 h: 45 min) sind kein Katalogparameter, und ob eine Ruhezeitverletzung die Veröffentlichung sperrt, ist offen (Regelmatrix AT-40, Offener Punkt 3); gebaut ist die Warnung.

**Veröffentlichung** (SCH-005, `StafftimeApi.publishedRosters`, `POST /v1/rosters/{roster_id}/publish`): eine Transaktion mit Absperrung gegen Abwesenheitsgenehmigungen, Depotrecht, Revisionszeilen (`FOR UPDATE`), erneuter Konfliktprüfung (409 ohne Wirkung), Regelhinweisen als Warnung, Beförderung des Entwurfs zur veröffentlichten Revision (die bisherige wird `SUPERSEDED`, bleibt unverändert), Auditereignis und Outbox-Ereignis `staff.roster_published` (nur Kennungen, nur Personen mit geänderten Schichten, Schema `api/events/staff.roster_published.v1.schema.json`). `If-Match` (428/412), `Idempotency-Key` (Wiederholung liefert dieselbe Revision, anderer Inhalt `IDEMPOTENCY_CONFLICT`, Abdruck an der Revision). Keine Fahrzeugfreigabe. `shift_publish_notice_hours` ist nur registriert (Wirkung offen, OP 9). Die Zustellung der Fahrermeldung übernimmt die Outbox-Verarbeitung; ein Zustellfehler macht die Veröffentlichung nicht rückgängig. Eigensicht: `GET /v1/rosters/own-shifts` (nur eigene Schichten der aktuellen veröffentlichten Revision), `GET /v1/absences/own` (neutral).

**Historie** (SCH-010, `StafftimeApi.rosterHistory`, Scope `rosters:audit` plus Depotrecht): Revisionsliste, historische veröffentlichte Fassung (Snapshot je Revision = die Schichtzeilen der Revision) und Änderungsprotokoll je Schicht (`roster_shift_change`, V40150, nur anhängen, stabile `lineage_id` über Revisionen). Veröffentlichte Revisionen sind per Datenbankregel unveränderlich und nicht löschbar.

## Schichtbesetzung, Übernahme der M3-Zuweisungen (WP-M4-17, WP-M4-65)

`StafftimeApi.shiftStaffing` (Leseport `ShiftStaffingReadPort` aus `platform.readports`): je Fahrzeug und Zeitraum besetzt oder unbesetzt mit Schicht-, Roster- und Qualifikationsrevision; besetzt nur mit veröffentlichter (oder angetretener) Schicht mit Fahrer, Entwürfe und offene Fahrerschichten machen kein Fahrzeug besetzt, der Port erteilt keine Freigabe. `StafftimeApi.impactProvider()` nennt veröffentlichte Schichten für die Wirkungsanalyse. `V40170` ergänzt `staff_shift.service_id` (Dienstbezug), `V40650` übernimmt M3-Altzeilen in veröffentlichte Revisionen (`adopt_legacy_staff_shifts`). Vertrag und offene Punkte: [m4-cluster-wave12-c7.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave12-c7.md).

