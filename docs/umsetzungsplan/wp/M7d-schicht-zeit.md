# M7d – Schicht- und Personalplanung, Zeiterfassung und Abwesenheiten (Betriebsausbau)

- **Meilenstein:** M7 – Betriebsausbau, Strom „Fahrer, Flotte, Personal“ (setzt M5 voraus; Nachbareinheiten M7a Fahrgast/Buchung/Geo, M7b Leitstelle/Support/Kommunikation, M7c Fahrer/Flotte/Checks/Schäden/Inventar)
- **Phase:** P2 (30 MUSS, 9 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 39 (SCH 26, TIM 13)
- **Anzahl WPs:** 43 (39 mit Anforderungen, 4 Enabler)

**Ziel:** Nach M7d wird der P1-Dienstplan aus M4 zur vollständigen Personalplanung: Vorlagen, Tablet- und Tastaturbedienung, Bereiche und Pools, konfigurierbare Arbeitszeit- und Ruhezeitregeln mit Warnungsklassen, Feiertage, Publikationsvorschau mit gezielten Mitteilungen und Erinnerungen. Offene Dienste werden über Schichtangebote, Bewerbung und Tausch atomar besetzt; Ausfälle von Personal und Fahrzeugen erzeugen Ersatzbedarf, ohne veröffentlichte Historie zu überschreiben. Die Zeitwirtschaft erhält Abwesenheitsanträge, Zeitkorrekturen, Zeit- und Urlaubskonten, Periodenabschluss, Lohnexport, Berichte sowie ein Lösch- und Nachweiskonzept.

**Gate-Anteil M7d (Umsetzungsplan §4, Gate M7):** AT-38, AT-42, AT-43, AT-44, AT-52, AT-56, AT-63 sowie die Regression von AT-46 (Offline-Zeiterfassung, fachlich aus M4). Die übrigen M7-Gate-Tests gehören zu M7a (AT-06, AT-12), M7b (AT-17) und M7c (AT-57, AT-62, E2E-069, E2E-071, E2E-100). Zuarbeit zu Gate-Tests anderer Meilensteine, die erst mit M7d-Funktion vollständig prüfbar sind: AT-49, AT-53, AT-54, AT-59, AT-61 (Gate M5, dort mit P1-Umfang bestanden – für die M7d-Erweiterungen als Regression zu wiederholen) und AT-55 (Gate M9). Technische Schwerpunktprüfungen aus Kap. 08 §8.5 mit M7d-Bezug: PH-AT-001 (Isolation), PH-AT-011 (Idempotenzschlüssel), PH-AT-017 (Ereignisreihenfolge), PH-AT-018 (DST), PH-AT-021 (Aggregationsschutz).

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (SCH-0xx = `LH-6.23-SCH-0xx`, TIM-0xx = `LH-6.24-TIM-0xx`); AT-/E2E-Kennungen verweisen auf `e2e.csv`.
- In `openapi.yaml` ausformuliert ist für diesen Bereich nur `/v1/rosters/{roster_id}/publish`. Dessen `PublishRequest` verlangt bereits heute `effective_from`, `reason` und `impact_report_id` als Pflichtfelder; die Publikationsvorschau (WP-M7d-20) ist deshalb als der Wirkungsbericht zu bauen, auf den `impact_report_id` verweist. Als Scope existiert nur `rosters:publish`; alle weiteren Scopes sind neu und entstehen in WP-M7d-01. Die Pfade `/v1/rosters`, `/v1/shift-offers`, `/v1/shift-swaps`, `/v1/time-events`, `/v1/time-corrections`, `/v1/absences` nennt Kap. 05 §5.7 nur als Ressourcengruppe; ihre Operationen entstehen in WP-M7d-01.
- Im Kern-DDL (`kernschema.sql`) vorhanden: `staff_shift`, `resource_release`, `vehicle_run`, `vehicle`, `outbox`. Die Tabellen `roster_revision`, `shift_template`, `shift_offer`, `shift_application`, `shift_swap`, `time_event`, `time_correction`, `absence_request`, `time_account_entry` stehen nur als Namen in Kap. 04 §4.4. Weitere benötigte Tabellen sind ausdrücklich als **Arbeitsname** gekennzeichnet und werden in WP-M7d-01 festgelegt.
- Ereignis- und Zustandsnamen sind Arbeitsnamen bis zur Freigabe von WP-M7d-01.
- Das Pflichtenheft enthält für diesen Bereich keine bezifferten Planungsdefaults; die „15 Minuten“ in `LH-6.23-SCH-026` sind ein Beispiel der Anforderung, kein Default.
- Module: `staff-time` (führend), `communication-support` (Zustellung), `analytics` (Berichte), `fleet-safety` und `booking-dispatch` (nur über Ereignisse und Leseports). Oberflächen: `web/operator`, `mobile/driver`.
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M7d-01 | Detailverträge und Zustandsautomaten M7d | – (Enabler) | M | WP-M4-01, WP-M4-03, WP-M4-04, WP-M4-06, WP-M4-08, WP-M1-02, WP-M1-13, WP-M1-19, WP-M1-21, WP-M7c-01 |
| WP-M7d-02 | Migration der M7d-Tabellen mit RLS | – (Enabler) | M | WP-M7d-01, WP-M1-01, WP-M0-15, WP-M4-03, WP-M4-06, WP-M4-08 |
| WP-M7d-03 | Referenzfixtures und Testbausteine M7d | – (Enabler) | S | WP-M7d-02, WP-M4-02, WP-M0-17, WP-M0-18, WP-M0-22 |
| WP-M7d-04 | Schichtvorlagen für Wochen-, Wochenend- und Saisonmodelle | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-04, WP-M4-07 |
| WP-M7d-05 | Zuordnung per Drag-and-drop zu Mitarbeitern und Bedarfslinien, Tablet-Bedienung | 1 | M | WP-M7d-04, WP-M4-18, WP-M4-19 |
| WP-M7d-06 | Tastatur, Screenreader und Listenansicht für Schichtaktionen | 1 | M | WP-M7d-05, WP-M1-43, WP-M7c-31 |
| WP-M7d-07 | Standorte, Teams, Fahrzeugtypen, Arbeitsplätze und gemeinsamer Pool | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-16, WP-M1-09 |
| WP-M7d-08 | Qualifikationsvoraussetzungen für Schichten | 1 | S | WP-M7d-02, WP-M7d-03, WP-M4-03, WP-M4-07, WP-M7c-17 |
| WP-M7d-09 | Arbeitszeit- und Ruhezeitregeln je Beschäftigungsmodell | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-13, WP-M4-14, WP-M1-19, WP-M1-20, WP-M1-21 |
| WP-M7d-10 | Warnungsklassen: harte Sperre, prüfpflichtiger Hinweis, Empfehlung; Ausnahmen nach Regelwerk | 1 | M | WP-M7d-09, WP-M1-07, WP-M1-46, WP-M4-14 |
| WP-M7d-11 | Feiertage und betriebliche Sonderregelungen nach Ort und Datum | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-10 |
| WP-M7d-12 | Geplante Pausen in der operativen Fahrzeugverfügbarkeit | 1 | M | WP-M7d-02, WP-M7d-03, WP-M2-16, WP-M2-17, WP-M4-03, WP-M7c-09, WP-M7c-13 |
| WP-M7d-13 | Vorwarnung der Disposition bei Pause oder Arbeitszeitüberschreitung | 1 | M | WP-M7d-09, WP-M7d-12, WP-M2-15, WP-M2-16, WP-M3-52, WP-M3-36, WP-M4-08, WP-M7b-07 |
| WP-M7d-14 | Abwesenheitsanträge mit Art, Zeitraum, Status, Genehmiger, Kommentar | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-06, WP-M4-20, WP-M2-32 |
| WP-M7d-15 | Krankheitsabwesenheit mit beschränkter Einsicht | 1 | S | WP-M7d-14, WP-M1-31, WP-M1-46 |
| WP-M7d-16 | Abwesenheiten als Verfügbarkeitsrestriktion im Planer | 1 | S | WP-M7d-14, WP-M7d-15, WP-M4-07, WP-M4-18 |
| WP-M7d-17 | Krankmeldung und ungeplanter Ausfall: sofortiger Ersatzbedarf | 1 | M | WP-M7d-14, WP-M7d-15, WP-M3-36, WP-M3-52, WP-M3-08, WP-M4-17, WP-M4-05, WP-M7c-14, WP-M7b-04 |
| WP-M7d-18 | Planreaktion auf Fahrzeugausfall, Reparatursperre und Personalwechsel ohne Historienverlust | 1 | M | WP-M7d-02, WP-M7d-03, WP-M3-08, WP-M3-46, WP-M4-15, WP-M7c-37, WP-M1-14 |
| WP-M7d-19 | Stellvertretungs-, Springer- und Bereitschaftsdienste | 1 | M | WP-M7d-09, WP-M7d-17, WP-M4-17 |
| WP-M7d-20 | Publikationsvorschau: Betroffene, Differenz zur Vorversion, getrennte Wochenfreigabe | 1 | M | WP-M7d-09, WP-M7d-10, WP-M4-14, WP-M4-15 |
| WP-M7d-21 | Gezielte Push-/E-Mail-Mitteilungen bei Veröffentlichung und Änderung | 1 | M | WP-M7d-20, WP-M2-32, WP-M2-33, WP-M1-14, WP-M7b-31 |
| WP-M7d-22 | Erinnerungen vor Schichtbeginn und zum Ein-/Ausstempeln | 1 | M | WP-M7d-21, WP-M2-32, WP-M4-11, WP-M1-19, WP-M1-21 |
| WP-M7d-23 | Schichtangebote mit Frist, Qualifikation und Auswahlmodus; Zulässigkeitsprüfung der Bewerbung | 1 | M | WP-M7d-07, WP-M7d-08, WP-M7d-09, WP-M7d-16, WP-M4-05, WP-M2-32 |
| WP-M7d-24 | Bewerbungen im Planer genehmigen oder ablehnen – atomare Besetzung | 1 | M | WP-M7d-21, WP-M7d-23, WP-M4-15, WP-M1-13, WP-M1-07 |
| WP-M7d-25 | First-come-first-served-Modus für offene Schichten | 1 | S | WP-M7d-24 |
| WP-M7d-26 | Schichttausch und Schichtübernahme mit Zustimmungen und Regelprüfung | 1 | M | WP-M7d-09, WP-M7d-10, WP-M7d-21, WP-M4-15, WP-M4-07, WP-M1-13 |
| WP-M7d-27 | Schichtbereitstellung setzt Check-in, Fahrzeugzuordnung und Vor-Schicht-Checkliste voraus | 1 | S | WP-M7d-24, WP-M7d-26, WP-M3-08, WP-M3-09, WP-M3-13, WP-M3-14, WP-M4-17, WP-M7c-21 |
| WP-M7d-28 | Eigene veröffentlichte Schichten, Einsatzorte, Kontakte und Diensthinweise in der App | 1 | S | WP-M7d-02, WP-M7d-03, WP-M4-14, WP-M4-20, WP-M3-24 |
| WP-M7d-29 | Schreibgeschützter Offline-Cache für Schichtpläne und Aufgaben in der Fahrer-App | 1 | M | WP-M7d-28, WP-M3-24, WP-M7c-07, WP-M7c-08 |
| WP-M7d-30 | Export von Schichtplänen als PDF/CSV und persönlicher Kalenderfeed | 1 | M | WP-M7d-07, WP-M7d-28, WP-M5-19, WP-M1-11, WP-M1-46 |
| WP-M7d-31 | Mitarbeiterübersicht: Soll, zugewiesen, Fehlzeiten, Über-/Unterdeckung, offene Bedarfe | 1 | M | WP-M7d-07, WP-M7d-09, WP-M7d-14, WP-M7d-19, WP-M4-46 |
| WP-M7d-32 | Unverbindlicher Personalbedarfsvorschlag aus Nachfrage, Umlaufplanung und Servicefenstern | 1 | M | WP-M7d-04, WP-M7d-07, WP-M4-45, WP-M4-47, WP-M4-51, WP-M2-03, WP-M2-16, WP-M9a-06 |
| WP-M7d-33 | Zeitkorrekturanträge mit Prüfung und Alt-/Neuwert | 1 | M | WP-M7d-02, WP-M7d-03, WP-M4-08, WP-M4-12, WP-M4-20, WP-M1-46 |
| WP-M7d-34 | Getrennte Rollenrechte und Audit für Korrektur, Freigabe und Zugriff | 1 | M | WP-M7d-33, WP-M1-07, WP-M1-46, WP-M1-12 |
| WP-M7d-35 | Zeitkonten: Soll/Ist, Überstunden, Minusstunden mit offengelegter Rundungs- und Pausenlogik | 1 | M | WP-M7d-09, WP-M7d-11, WP-M7d-19, WP-M7d-33, WP-M4-09, WP-M4-10 |
| WP-M7d-36 | Urlaubsansprüche, genommene und verfügbare Tage nach Arbeitszeitmodell | 1 | M | WP-M7d-14, WP-M7d-35 |
| WP-M7d-37 | Periodenabschluss der Zeitdaten und Bereitstellung für die Lohnabrechnung | 1 | M | WP-M7d-33, WP-M7d-34, WP-M7d-35, WP-M4-12 |
| WP-M7d-38 | CSV- und Lohn-/DATEV-Exportadapter | 1 | M | WP-M7d-11, WP-M7d-37, WP-M4-37, WP-M1-09, WP-M4-29 |
| WP-M7d-39 | Personal- und Arbeitszeitberichte über Standorte und Perioden | 1 | M | WP-M7d-09, WP-M7d-31, WP-M7d-35, WP-M4-46, WP-M4-58 |
| WP-M7d-40 | Standortgestütztes Stempeln nur mit Freigabe | 1 | S | WP-M7d-02, WP-M7d-03, WP-M4-11, WP-M1-19, WP-M1-22, WP-M7c-27 |
| WP-M7d-41 | Aufbewahrung, Löschung, Auskunft und Export personenbezogener Arbeitszeitdaten | 1 | M | WP-M7d-33, WP-M7d-35, WP-M7d-37, WP-M7d-40, WP-M1-33, WP-M1-11, WP-M1-12, WP-M5-19, WP-M8b-23 |
| WP-M7d-42 | Persönlicher Mitarbeiterbereich: Schichten, Zeiten, Urlaubssaldo, Korrekturanfragen | 1 | M | WP-M7d-28, WP-M7d-33, WP-M7d-35, WP-M7d-36, WP-M4-20 |
| WP-M7d-43 | Gate-Abnahme M7d | – (Enabler) | S | WP-M7d-05, WP-M7d-06, WP-M7d-15, WP-M7d-17, WP-M7d-18, WP-M7d-21, WP-M7d-22, WP-M7d-23, WP-M7d-24, WP-M7d-26, WP-M7d-33, WP-M7d-37, WP-M7d-38, WP-M7d-40, WP-M7d-41, WP-M4-12, WP-M4-63, WP-M5-02, WP-M5-22, WP-M7c-54 |

## Arbeitspakete

### WP-M7d-01 – Detailverträge und Zustandsautomaten M7d

- **Ziel:** Alle Verträge, die M7d vor Codebeginn braucht, liegen geprüft vor: OpenAPI-Ergänzungen, DDL-Entwurf, Zustandsautomaten, Ereignisschemas und Regelmatrix-Format.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzung im Modul `staff-time` für die in Kap. 05 §5.7 nur als Ressourcengruppe genannten Pfade `/v1/rosters`, `/v1/shift-offers`, `/v1/shift-swaps`, `/v1/time-events`, `/v1/time-corrections`, `/v1/absences`; Vorschau-Unterressource (Arbeitsname `/v1/rosters/{roster_id}/publish-preview`), die den Wirkungsbericht mit Differenzliste, Betroffenenliste und Konfliktliste nach Klasse liefert und dessen Kennung als `impact_report_id` im bestehenden `PublishRequest` verwendet wird (`PublishedRevision` selbst bleibt unverändert: `resource_id`, `revision`, `state`, `effective_from`, `audit_event_id`). Weitere Pfade ohne Entsprechung in Kap. 05 §5.7 (Vorlagen, Periodenabschluss, Lohnexport, Kalenderfeed, Bedarfsvorschlag, Ausfallmeldung) werden als Arbeitsnamen festgelegt und in Kap. 05 nachgetragen. Alle Mutationen mit `Idempotency-Key` und `If-Match` wie in `openapi.yaml`.
  - Datenbank: DDL-Entwurf für `roster_revision`, `shift_template`, `shift_offer`, `shift_application`, `shift_swap`, `time_event`, `time_correction`, `absence_request`, `time_account_entry` (Kap. 04 §4.4, Zeilen SCH und TIM) sowie Erweiterung von `staff_shift` (heute `driver_ref` und `vehicle_id` NOT NULL, kein Standort, keine Schichtart, kein Pausenplan).
  - Backend: Zustandsautomaten für Schichtangebot, Bewerbung, Tausch/Übernahme, Abwesenheitsantrag, Zeitkorrektur und Abrechnungsperiode (Zustandsnamen sind Arbeitsnamen bis zur Freigabe dieses WP).
  - Ereignisse/Outbox: JSON-Schemas der M7d-Ereignisse in `outbox` (Arbeitsnamen, z. B. `roster.published`, `shift.offer.assigned`, `shift.swap.applied`, `absence.approved`, `shift.replacement_required`, `time.correction.applied`, `time.period.closed`).
  - Konfigurationsparameter: Schema, Geltungsebene und Validator für `shift_publish_notice_hours`, `shift_swap_approval`, `clock_in_geofence_enabled`, `max_daily_work_minutes`, `min_rest_hours`, `absence_substitution_mode`, `notification_quiet_hours`, `reporting_timezone` (kataloge.csv: „konkrete Schemas und freigegebene Werte vor Implementierung ergänzen“).
  - Backend: Rollen- und Scope-Matrix für Personalplanung und Zeitwirtschaft (Mitarbeiter, Personalplaner/Disponent je Bereich, Vorgesetzter/Genehmiger, Personalrolle, Lohnrolle, Auditor) mit neuen Scopes neben dem vorhandenen `rosters:publish`; getrennte Rechte für Ausnahme (WP-M7d-10), Vergabe (WP-M7d-24), Ausfallmeldung (WP-M7d-17), Korrekturfreigabe (WP-M7d-34), Periodenabschluss (WP-M7d-37) und Lohnexport (WP-M7d-38).
  - Ereignisse/Outbox: Katalog der M7d-Mitteilungsarten mit Vorlagenschlüssel für `channel_template_revision` und Dedupe-Schlüssel (Veröffentlichung/Änderung/Entfall, Angebot, Bewerbungsergebnis, Tauschstatus, Abwesenheitsentscheid, Korrekturentscheid, Aktivierung Bereitschaft, Schichtstart- und Stempelerinnerung, Ersatzbedarf).
  - Backend: Leseport- und Ereignisverträge zwischen `staff-time`, `fleet-safety` und `booking-dispatch` (Pausen-/Restriktionsintervalle, Flottensperre, Ersatzbedarf), gemeinsam mit WP-M7c-01 festgelegt; jedes Ereignis trägt Ereignis-ID und Aggregatrevision (Deduplikation je Abnehmer, keine Rücksetzung durch verspätete Ereignisse – Muster PH-AT-017).
  - Konfigurationsparameter (neu, in kataloge.csv zu ergänzen – Namen, Einheit, Geltungsebene und Grenzen werden hier vorgeschlagen und fachlich freigegeben, bis dahin keine Implementierung mit geratenen Werten): Erinnerungsvorlauf Schichtbeginn und Stempelerinnerung (`LH-6.23-SCH-026`), Auswahlmodus First-come-first-served (`LH-6.23-SCH-023`), Vorwarnhorizont (`LH-6.24-TIM-016`), Rundungs- und Pausenabzugsregel (`LH-6.24-TIM-011`), Periodenlänge/Abschlusstag der Zeitwirtschaft (`LH-6.24-TIM-012`).
- **Detailverträge vorab:** Dieses WP ist der Detailvertrag. Eingang: Stand der M4-Verträge (WP-M4-01) für Schicht, Zeitereignis und Abwesenheits-Minimalmodell.
- **Akzeptanzkriterien:**
  1. Für jeden der sechs Pfade aus Kap. 05 §5.7 existiert eine geprüfte OpenAPI-Beschreibung mit Scopes, Fehlerfällen (`Problem`) und Sichttrennung „eigene“ gegen „Personalplaner“.
  2. Jeder Zustandsautomat nennt Zustände, erlaubte Übergänge, auslösende Rolle und Ereignis; verbotene Übergänge sind als Negativfälle gelistet.
  3. Das Format der Regelmatrix (harte Sperre / prüfpflichtiger Hinweis / Empfehlung) ist festgelegt und von der Fachseite gegengelesen.
  4. Arbeitsnamen für Tabellen, die Kap. 04 §4.4 nicht nennt (Feiertagskalender, Beschäftigungsmodell, Abrechnungsperiode, Erinnerungseinstellung), sind entschieden und in Kap. 04 nachgetragen oder als offener Punkt eskaliert.
  5. Die Rollen- und Scope-Matrix ist freigegeben; für jede Operation ist festgelegt, welche Rolle sie in welchem Bereich ausführen darf und welche Antwort ein fremdes bzw. nicht vorhandenes Objekt liefert (gleichartig, keine Existenzauskunft – PH-AT-001).
  6. Jeder neue Konfigurationsparameter ist mit Name, Einheit, Geltungsebene, Grenzen und Validator beschrieben und zur Aufnahme in kataloge.csv eingereicht; kein Fach-WP verwendet einen nicht freigegebenen Wert als Default.
  7. Die Verträge der Leseports und Ereignisse zu `fleet-safety` und `booking-dispatch` sind mit WP-M7c-01 abgeglichen; keine Regel (Pause, Verfügbarkeit, Qualifikation) ist in zwei Modulen führend.
  8. Die YAML bleibt als OpenAPI 3.1 parsbar, alle `$ref` sind auflösbar, `operationId`s eindeutig und Beispiele schemagültig (Kap. 05 §5.8).
- **Tests:** Vertragstests (Schema-Lint, Beispielnutzlasten) für alle neuen Pfade und Ereignisse; semantische Vertragsfälle nach Kap. 05 §5.8 als Testspezifikation für die Fach-WPs: Manipulation `tenant_id`, unzulässige Fremd-ID, gleicher `Idempotency-Key` mit anderem Body (PH-AT-011), veralteter `If-Match`, Offline-Aktion auf alter Planrevision. Keine Fachtests.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M4-01, WP-M4-03, WP-M4-04, WP-M4-06, WP-M4-08 (Verträge und Aggregate für Schicht, Dienstplan, Zeitereignis, Abwesenheits-Minimalmodell)
  - extern: WP-M1-02, WP-M1-13, WP-M1-19, WP-M1-21 (API-Konventionen, Problem-Format, Idempotenz, Konfigurationsdienst)
  - extern: WP-M7c-01 (gemeinsame Ereignis- und Leseportverträge Flotte/Verfügbarkeit)
- **Entscheidungen:** D-10 und D-11 blockieren die Festlegung von Standort- und Löschfeldern nicht, müssen aber als offene Annahmen im Vertrag markiert sein; blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Implementierung; Verträge der Einheiten M7a, M7b, M7c.

### WP-M7d-02 – Migration der M7d-Tabellen mit RLS

- **Ziel:** Die Tabellen für Vorlagen, Angebote, Bewerbungen, Tausch, Korrekturen, Abwesenheitsanträge und Zeitkonten sind mandantengetrennt angelegt und unter der Laufzeitrolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Flyway-Migration für `shift_template`, `shift_offer`, `shift_application`, `shift_swap`, `time_correction`, `time_account_entry`; Ausbau von `absence_request`, `roster_revision` und `staff_shift` gemäß WP-M7d-01; zusätzliche Tabellen mit Arbeitsnamen aus WP-M7d-01. Primärschlüssel `(tenant_id,id)`, zusammengesetzte Fremdschlüssel, RLS USING/WITH CHECK über `current_tenant()` (Kap. 04 §4.5, §4.6).
  - Datenbank: eigener Modulzugang für HR-Daten mit expliziten Spalten-/Viewrechten (Kap. 04 §4.6 „Für PII/HR/Finanzen eigene Modulzugänge“); Unique-Constraint „höchstens eine verbindliche Besetzung je Angebot“; Änderungsverbot für angewendete `time_correction` und gebuchte `time_account_entry` (Gegenbuchung statt Update).
  - Backend: Repository-Grundgerüst im Modul `staff-time`.
  - Datenbank: ausdrücklich enthaltene Tabellen mit Arbeitsnamen aus WP-M7d-01 (in Kap. 04 §4.4 nicht genannt, dort nachzutragen): Feiertags-/Sondertagskalender, Beschäftigungsmodell mit Regelrevision und Mitarbeiterzuordnung, Bereichs-/Poolberechtigung, Ausnahmeeintrag zur Planrevision, Erinnerungseinstellung und geplante Erinnerung, Bedarfsvorschlag, Kalenderfeed-Token (nur als Hash gespeichert), Abrechnungsperiode der Zeitwirtschaft, Exportabbildung Stundenart/Kostenstelle, Freigabenachweis Stempel-Geozone.
  - Datenbank: Ausbau von `time_event` um Korrekturbezug und optionale Standortfelder (nur bei aktiver Freigabe befüllt, WP-M7d-40); `staff_shift` erhält nullbare Personen-/Fahrzeugzuordnung für offene Dienste und Bereitschaft, Schichtart, Standort/Team/Arbeitsplatz, Pausenintervalle, Qualifikationsbedarf und Herkunftsverweis – vorbehaltlich des in M4 gewählten Modells (siehe Offene Punkte 2). Keine Tabelle erhält ein Diagnose- oder Nachweisfeld (Kap. 04 §4.4 TIM).
  - Datenbank: `roster_revision` nur anfügend (veröffentlichte Revision ohne UPDATE/DELETE); Indizes `(tenant_id,state,updated_at)` für Arbeitslisten und Zeitbereichsindex je Person für Überschneidungsprüfungen.
- **Detailverträge vorab:** DDL aus WP-M7d-01; Indexliste nach Kap. 04 (`(tenant_id,state,updated_at)` für Arbeitslisten).
- **Akzeptanzkriterien:**
  1. Ohne Mandantenkontext liefert jede neue Tabelle keine Zeile; Schreiben mit fremder `tenant_id` scheitert.
  2. Eine Fremdschlüsselreferenz auf eine Schicht oder Person eines anderen Mandanten ist nicht anlegbar.
  3. Die Laufzeitrolle ist weder Eigentümer noch BYPASSRLS; Migration läuft unter getrennter Migratorrolle.
  4. Migration ist auf einem M5-Datenbestand vorwärts lauffähig; bestehende `staff_shift`-Zeilen bleiben gültig.
  5. Mit leerem Transaktionskontext und nach Wiederverwendung einer Pool-Verbindung wirkt kein vorheriger Mandantenkontext fort (PH-AT-001); sessionweite Tenant-GUCs werden nicht verwendet (Kap. 04 §4.6).
  6. Der HR-Modulzugang kann Spalten mit Abwesenheitsart nur über die dafür vorgesehenen Views lesen; die Laufzeitrolle der Disposition hat kein Leserecht auf `absence_request`, `time_correction` und `time_account_entry`.
  7. UPDATE/DELETE auf veröffentlichte `roster_revision`, angewendete `time_correction` und gebuchte `time_account_entry` scheitern auf Datenbankebene; Löschung ist nur dem kontrollierten Lifecycleprozess (WP-M7d-41) möglich.
- **Tests:** positiv: Migration auf leerem und auf M5-Bestand. negativ: Cross-Tenant-Lesen/Schreiben je Tabelle. konkurrierend: zwei parallele Transaktionen versuchen dieselbe Besetzung (Constraint greift); zwei parallele Transaktionen unterschiedlicher Mandanten auf derselben Pool-Verbindung sehen jeweils nur eigene Zeilen. Alle Tests unter der nicht privilegierten Laufzeitrolle. Zuarbeit zu PH-AT-001, AT-49, AT-61 und E2E-091.
- **Abhängigkeiten:** intern: WP-M7d-01.
  - extern: WP-M1-01, WP-M0-15 (RLS-Rahmen, Migrator-/Laufzeitrollen, Tenantkontext)
  - extern: WP-M4-03, WP-M4-06, WP-M4-08 (Basistabellen `staff_shift`, `time_event`, Abwesenheits-Minimalmodell)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik; Tabellen der Einheit M7c.

### WP-M7d-03 – Referenzfixtures und Testbausteine M7d

- **Ziel:** Wiederverwendbare Testdaten und Hilfen für alle M7d-WPs stehen bereit: zwei Mandanten, zwei Standorte, Beschäftigungsmodelle, Qualifikationen, Feiertage, Uhrsteuerung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Fixtures mit zwei Mandanten, je zwei Standorten/Depots, Vollzeit- und Teilzeitmodell, Fahrern mit und ohne Rollstuhl-Lift-Schulung, veröffentlichtem Wochenplan und offenem Dienst; steuerbare Testuhr; Helfer für parallele Befehle (zwei Transaktionen).
  - Adapter/Worker: Aufzeichnender Test-Doppelgänger des Adapters `notification` (zählt Zustellaufträge je Empfänger).
  - Oberfläche: Testhelfer für Tastatur-/Screenreader-Prüfung und Touch-Bedienung in `web/operator`.
  - Adapter/Worker: Test-Doppelgänger für Flottenereignisse (`fleet-safety`: Sperre gesetzt/aufgehoben, Fahrzeugwechsel) mit steuerbarer Reihenfolge und Mehrfachzustellung sowie für den Leseport zu `booking-dispatch` (Buchungsanfrage gegen Schicht/Pause), damit WP-M7d-12, WP-M7d-13, WP-M7d-17 und WP-M7d-18 ohne M7c-Lieferstand testbar sind.
  - Backend: Sollwerttabellen als versionierte Fixtures – Ruhezeit-/Pausenfälle der Regelmatrix (AT-40), DST-Nacht 25.10.2026 in `Europe/Berlin` mit doppelt vorkommender lokaler Zeit 02:30 (PH-AT-018), Wochenabrechnung Voll-/Teilzeit, Urlaubsprüfrechnung, Zwei-Standorte-Monat. Fachlich freizugebende Werte sind als Platzhalter gekennzeichnet, bis die Freigabe vorliegt.
- **Detailverträge vorab:** Fixturekatalog mit Sollwerten (Wochenstunden, Ruhezeitfälle, DST-Nacht) abgestimmt mit WP-M7d-01.
- **Akzeptanzkriterien:**
  1. Jedes Fach-WP kann seine Tests ohne eigene Stammdatenanlage starten.
  2. Parallelhelfer erzeugt nachweislich überlappende Transaktionen (Negativkontrolle ohne Constraint schlägt fehl).
  3. Fixtures enthalten keine echten Personendaten.
  4. Die Fixtures enthalten je Mandant mindestens einen Planer mit und einen ohne Bereichs-/Ausnahmerecht sowie eine Personal- und eine Lohnrolle, sodass jeder Rechte-Negativtest ohne Zusatzanlage läuft.
  5. Der Ereignis-Doppelgänger kann dasselbe Ereignis mehrfach und in vertauschter Reihenfolge zustellen (PH-AT-017).
- **Tests:** Selbsttests der Fixtures und des Parallelhelfers.
- **Abhängigkeiten:** intern: WP-M7d-02.
  - extern: WP-M4-02 (Referenzfixtures M4)
  - extern: WP-M0-17, WP-M0-18, WP-M0-22 (Testinfrastruktur unter Laufzeitrolle)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fachtests selbst.

### WP-M7d-04 – Schichtvorlagen für Wochen-, Wochenend- und Saisonmodelle

- **Ziel:** Planer speichern wiederkehrende Modelle als Vorlage, kopieren sie und wenden sie auf Zeiträume an; es entstehen Entwurfsschichten mit sichtbaren Überschneidungen.
- **Anforderungen:** `LH-6.23-SCH-011`
- **Liefergegenstände:**
  - Backend: Aggregat Schichtvorlage im Modul `staff-time` (speichern, kopieren, an Zeitraum anpassen, anwenden); Anwendung erzeugt Schichten ausschließlich im Entwurf der `roster_revision`.
  - Datenbank: `shift_template`; erzeugte `staff_shift`-Zeilen mit Herkunftsverweis auf Vorlage.
  - API: `/v1/rosters` (Unterressourcen für Vorlagen und „Vorlage anwenden“ gemäß WP-M7d-01).
  - Oberfläche: UI-12 Dienstplan – Vorlagenliste, Dialog „auf Zeitraum anwenden“, Markierung von Überschneidungen im Wochenraster.
  - Ereignisse/Outbox: keine Fahrerbenachrichtigung, da nur Entwurf.
- **Detailverträge vorab:** Vorlagenschema (Wochentage, relative Zeiten, Zeitzone, Standort, Qualifikationsbedarf, Schichtart); Regel für Mitternachts- und DST-Schichten beim Anwenden.
- **Akzeptanzkriterien:**
  1. Eine gespeicherte Wochenvorlage erzeugt beim Anwenden auf eine Zielwoche planbare Entwurfsschichten; nichts davon ist für Fahrer sichtbar (SCH-011).
  2. Überschneidet eine erzeugte Schicht eine bestehende Schicht derselben Person oder desselben Fahrzeugs, ist die Überschneidung im Entwurf erkennbar markiert (SCH-011).
  3. Eine Vorlage lässt sich kopieren und für einen anderen Zeitraum (Wochenende, Saison) anpassen, ohne die Ursprungsvorlage zu ändern (SCH-011).
  4. Wiederholtes Anwenden mit demselben `Idempotency-Key` erzeugt keine doppelten Schichten (SCH-011).
  5. Anlegen, Kopieren, Ändern und Anwenden einer Vorlage sind nur Planern mit Recht für den Bereich möglich und hinterlassen Urheber und Zeitpunkt in der Entwurfshistorie (AT-38: dokumentierte Historie).
  6. UI-12 zeigt für Vorlagen den Leerzustand (keine Vorlage), den Fehlerzustand (Anwenden gescheitert, nichts teilweise erzeugt) und den Zustand fehlender Rechte (SCH-011).
- **Tests:** positiv: Wochenvorlage kopieren und anwenden. negativ: Anwenden auf veröffentlichte Revision ohne neuen Entwurf wird abgelehnt; Vorlage eines fremden Mandanten nicht sichtbar; gleicher `Idempotency-Key` mit anderem Zielzeitraum liefert Konflikt (PH-AT-011); Abbruch mitten im Anwenden hinterlässt keine Teilmenge. konkurrierend: zwei Planer wenden dieselbe Vorlage parallel auf dieselbe Woche an – keine doppelten Schichten. Ermöglicht AT-38 (mit WP-M7d-05).
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-04, WP-M4-07 (Dienstplan-Entwurf, Konfliktprüfung Doppelbelegung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Drag-and-drop-Zuordnung (WP-M7d-05); Feiertagskennzeichnung (WP-M7d-11); Bedarfsvorschlag (WP-M7d-32).

### WP-M7d-05 – Zuordnung per Drag-and-drop zu Mitarbeitern und Bedarfslinien, Tablet-Bedienung

- **Ziel:** Schichten lassen sich im Wochenraster per Ziehen Mitarbeitern oder offenen Bedarfslinien zuordnen, auch per Touch am Tablet, mit serverseitiger Validierung.
- **Anforderungen:** `LH-6.23-SCH-012`
- **Liefergegenstände:**
  - Oberfläche: UI-12 Dienstplan – Touch-fähiges Ziehen/Ablegen, Ablegeziel „offene Bedarfslinie“, Sofortanzeige der Serverkonflikte, Rücksprung bei Ablehnung.
  - Backend: Befehl „Schicht zuordnen/verschieben“ im Entwurf mit `If-Match`; Validierung über die Konfliktprüfung.
  - API: `/v1/rosters` (Zuordnungsbefehl gemäß WP-M7d-01).
- **Detailverträge vorab:** Unterstützte Tablet-Zielgeräte und Mindestbreite; Verhalten bei veraltetem Entwurf (412).
- **Akzeptanzkriterien:**
  1. Am Tablet wird eine Schicht ohne Maus einem Mitarbeiter und einer offenen Bedarfslinie zugeordnet; die Serverprüfung läuft bei jedem Ablegen (SCH-012).
  2. Ein unzulässiges Ablegen (z. B. Doppelbelegung) wird mit Konfliktursache angezeigt und nicht gespeichert (SCH-012).
  3. Das Verschieben eines offenen Dienstes hinterlässt einen Historieneintrag im Entwurf (AT-38).
  4. Nach dem Verschieben eines offenen Dienstes ist der Entwurf konfliktfrei oder verbleibende Konflikte sind einzeln benannt; nichts davon ist veröffentlicht (AT-38).
  5. Ein Planer ohne Recht für den Zielbereich kann dort nicht ablegen; UI-12 zeigt Lade-, Fehler- und Rechtezustand, bei Verbindungsverlust wird keine Zuordnung als gespeichert dargestellt (SCH-012).
- **Tests:** positiv: Touch-Ablauf auf Tablet-Viewport. negativ: Ablegen mit veraltetem ETag liefert 412 und lädt neu. konkurrierend: zwei Planer verschieben dieselbe Schicht – genau eine Änderung gewinnt. Besteht AT-38 (mit WP-M7d-04).
- **Abhängigkeiten:** intern: WP-M7d-04.
  - extern: WP-M4-18, WP-M4-19 (UI-12 Grundausbau mit Klick/Kopie/Drag-and-drop)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Tastatur- und Screenreader-Alternative (WP-M7d-06).

### WP-M7d-06 – Tastatur, Screenreader und Listenansicht für Schichtaktionen

- **Ziel:** Alle wesentlichen Schichtaktionen sind ohne Maus und ohne Drag-and-drop möglich; eine Tabellen-/Listenansicht ersetzt das Raster.
- **Anforderungen:** `LH-6.23-SCH-036`
- **Liefergegenstände:**
  - Oberfläche: UI-12 Dienstplan – barrierefreie Tabellenalternative (Kap. 06 §6.8), Tastaturbefehle für Anlegen, Zuordnen, Verschieben, Tauschen, Veröffentlichen; Screenreader-Ansagen für Konflikte und Revisionsstatus.
  - Oberfläche: Bausteine im `web/design-system` für fokussierbare Rasterzellen und Aktionsmenüs.
- **Detailverträge vorab:** Liste der „wesentlichen Schichtaktionen“; Prüfstandard für Barrierefreiheit (im Pflichtenheft nicht benannt, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Ein vollständiger Schichtwechsel (Schicht von Person A auf Person B, einschließlich Konfliktanzeige und Speichern) gelingt ausschließlich per Tastatur (SCH-036).
  2. Dieselbe Aktion ist in der Listenansicht ohne Drag-and-drop ausführbar (SCH-036).
  3. Screenreader liest Schichtzeit, Person, Status Entwurf/veröffentlicht und Konflikt vor (SCH-036).
- **Tests:** positiv: Tastaturdurchlauf, automatisierte Barrierefreiheitsprüfung, manueller Screenreader-Test. negativ: keine Aktion ist nur per Zeiger erreichbar. Besteht den Planeranteil von AT-54.
- **Abhängigkeiten:** intern: WP-M7d-05.
  - extern: WP-M1-43 (Design-System-Grundlagen)
  - extern: WP-M7c-31 (Barrierefreie Checklisten für den Fahreranteil von AT-54)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Barrierefreiheit der Fahrer-App-Checklisten (M7c).

### WP-M7d-07 – Standorte, Teams, Fahrzeugtypen, Arbeitsplätze und gemeinsamer Pool

- **Ziel:** Die Einsatzplanung trennt nach Standort, Team, Fahrzeugtyp und Arbeitsplatz und erlaubt einen gemeinsamen Pool über berechtigte Bereiche.
- **Anforderungen:** `LH-6.23-SCH-029`
- **Liefergegenstände:**
  - Backend: Planungsdimensionen an Schicht und Bedarf; Poolfreigabe je Bereich; Berechtigungsprüfung „Depotberechtigung“ für Planer und für einplanbare Mitarbeiter.
  - Datenbank: Erweiterung `staff_shift` um Standort/Team/Arbeitsplatz; Zuordnungstabelle Mitarbeiter↔berechtigte Bereiche (Arbeitsname aus WP-M7d-01).
  - API: `/v1/rosters` (Filter nach Standort/Team/Fahrzeugtyp/Arbeitsplatz).
  - Oberfläche: UI-12 Dienstplan – Bereichsfilter, Poolansicht.
- **Detailverträge vorab:** Begriffsmodell Standort/Depot/Team/Arbeitsplatz und Bezug zu den Betriebshöfen aus M7c; Regel, wer einen Pool freigibt.
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter mit Depotberechtigung kann im freigegebenen Pool eingeplant werden (SCH-029).
  2. Ein Mitarbeiter ohne Berechtigung für einen Bereich wird dort weder vorgeschlagen noch zuweisbar (SCH-029).
  3. Ein Planer sieht und bearbeitet nur Bereiche, für die er berechtigt ist (SCH-029).
  4. Standorte, Teams, Fahrzeugtypen und Arbeitsplätze sind getrennt planbar und filterbar; eine Schicht gehört genau einem Standort an und der gemeinsame Pool umfasst nur ausdrücklich freigegebene Bereiche (SCH-029).
  5. Entzug einer Bereichs- oder Poolberechtigung wirkt sofort auf neue Zuweisungen und ist auditiert; bestehende veröffentlichte Schichten werden als Konflikt gemeldet, nicht still entfernt (SCH-029).
- **Tests:** positiv: Einplanung im Pool. negativ: Zuweisung außerhalb der Berechtigung, Zugriff auf fremden Mandanten, Filter mit fremder Bereichs-ID liefert keine Existenzauskunft. konkurrierend: Berechtigungsentzug parallel zur Zuweisung – keine Zuweisung nach wirksamem Entzug. Zuarbeit zu E2E-091 und PH-AT-001.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-16 (Mandanten- und Depotberechtigung im Dienstplan)
  - extern: WP-M1-09 (Betriebshöfe/Standorte als Stammdaten)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Partnerfahrzeuge im Dienstplan (E2E-095, M6b).

### WP-M7d-08 – Qualifikationsvoraussetzungen für Schichten

- **Ziel:** Schichten tragen erforderliche Qualifikationen; automatische Zuteilung berücksichtigt nur Mitarbeiter mit gültigem Nachweis.
- **Anforderungen:** `LH-6.23-SCH-016`
- **Liefergegenstände:**
  - Backend: Qualifikationsbedarf je Schicht/Vorlage/Angebot (Fahrererlaubnis, Fahrzeugklassen-Berechtigung, Unterweisung, Rollstuhl-Lift-Schulung); Abgleich gegen `qualification_revision` mit Gültigkeitsintervall zum Schichtzeitraum.
  - Datenbank: Qualifikationsbedarf an `staff_shift` und `shift_template`; Lesezugriff auf `qualification_revision`.
  - Oberfläche: UI-12 Dienstplan – Bedarf an der Schicht, Konfliktmarke „Qualifikation fehlt/abgelaufen“.
  - API: `/v1/rosters` (Qualifikationsbedarf an Schicht und Vorlage pflegen; Konfliktliste nennt fehlende Qualifikation).
- **Detailverträge vorab:** Katalog der Qualifikationsarten und ihre Gültigkeitslogik (Ablauf während der Schicht).
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter ohne erforderlichen Qualifikationsnachweis wird bei automatischer Zuteilung (Vorlage, Vorschlag, FCFS) nicht zugeteilt (SCH-016).
  2. Läuft die Qualifikation vor Schichtende ab, gilt sie als nicht erfüllt (SCH-016).
  3. Manuelle Zuweisung ohne Qualifikation erzeugt vor Veröffentlichung eine Sperre mit Ursache (AT-39).
  4. An einer Schicht lassen sich Fahrererlaubnis, Fahrzeugklassen-Berechtigung, Unterweisung und Rollstuhl-Lift-Schulung als Voraussetzung hinterlegen; die Hinterlegung ist auditiert (SCH-016).
  5. Wird eine Qualifikation zwischen Prüfung und Zuteilung entzogen oder läuft ab, scheitert die Zuteilung am Revisionsvergleich (Kap. 04 §4.5 Kapazität/Qualifikation/Fahrzeugfreigabe) (SCH-016).
- **Tests:** positiv: qualifizierter Fahrer wird zugeteilt. negativ: fehlende und abgelaufene Qualifikation. konkurrierend: Qualifikationsentzug parallel zur automatischen Zuteilung – keine Zuteilung auf veralteter `qualification_revision`. Regression AT-39.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-03, WP-M4-07 (Schichtmodell mit Qualifikation, Konfliktprüfung)
  - extern: WP-M7c-17 (Qualifikationen und Fahrzeugberechtigungen in der Schichtzuweisung)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Pflege der Qualifikationsnachweise und Fristen (M7c).

### WP-M7d-09 – Arbeitszeit- und Ruhezeitregeln je Beschäftigungsmodell

- **Ziel:** Regeln zu Arbeitszeit und Ruhezeit sind je Beschäftigungsmodell konfigurierbar; Verstöße erscheinen vor Veröffentlichung mit Regelhinweis und können nicht stillschweigend publiziert werden.
- **Anforderungen:** `LH-6.23-SCH-017`
- **Liefergegenstände:**
  - Backend: Regelprüfer im Modul `staff-time` mit versionierter Regelmatrix je Beschäftigungsmodell; Ergebnis enthält Regel-ID, Rechts-/Regelhinweis und betroffene Schichten; Einbindung in `/v1/rosters/{roster_id}/publish`.
  - Datenbank: Beschäftigungsmodell und Regelrevision (Arbeitsnamen aus WP-M7d-01), Zuordnung Mitarbeiter↔Modell mit Gültigkeit.
  - Konfigurationsparameter: `min_rest_hours`, `max_daily_work_minutes` (Geltungsebene Rechtsraum; „Rechtliche Vorgaben nicht überschreibbar“), `shift_publish_notice_hours`.
  - Oberfläche: UI-12 Dienstplan – Fehlerzustand „Ruhezeit“ mit Regeltext.
  - Oberfläche: Pflege von Beschäftigungsmodellen, Regelwerten und Mitarbeiterzuordnung im Konfigurationseditor (Kap. 06 §6.8: Herkunft der wirksamen Regel, Wirksamkeitsdatum, Validator, Prüfer); unzulässige Werte blockieren die Freigabe.
  - API: Pflege von Beschäftigungsmodell und Zuordnung (Pfad als Arbeitsname in WP-M7d-01; Kap. 05 §5.7 nennt keinen).
- **Detailverträge vorab:** Regelmatrix mit freigegebenen Werten und Testfällen (AT-40 verlangt „freigegebene Regelmatrix“); Berechnung der Ruhezeit über Mitternacht, DST und Standortwechsel.
- **Akzeptanzkriterien:**
  1. Ein Testfall mit unzulässiger Ruhezeit wird beanstandet; `/v1/rosters/{roster_id}/publish` liefert einen Konflikt und veröffentlicht nicht (SCH-017).
  2. Der Hinweis nennt Regel, Grenzwert, Istwert und betroffene Schichten (SCH-017).
  3. Zwei Beschäftigungsmodelle mit unterschiedlichen Regeln liefern für dieselbe Schichtfolge unterschiedliche, jeweils korrekte Ergebnisse (SCH-017).
  4. Ein Mandantenwert unterhalb der Rechtsraum-Untergrenze `min_rest_hours` ist nicht speicherbar (SCH-017).
  5. Eine Regeländerung wird als neue Regelrevision mit Wirksamkeitsdatum gespeichert, ist auditiert und verändert weder bereits veröffentlichte Revisionen noch deren gespeicherte Prüfergebnisse (SCH-017).
  6. Die Prüfung läuft serverseitig bei jeder Veröffentlichung; ein Verstoß kann weder durch die Oberfläche noch durch direkten API-Aufruf ohne den Ausnahmepfad aus WP-M7d-10 umgangen werden (SCH-017).
- **Tests:** positiv: regelkonformer Plan veröffentlicht. negativ: Ruhezeitverstoß, Tageshöchstarbeitszeit, Verstoß über Monatsgrenze und DST-Nacht, Wert unterhalb der Rechtsraumgrenze (Muster E2E-097). konkurrierend: zwei Planer weisen derselben Person parallel zwei Schichten zu, die nur gemeinsam die Ruhezeit verletzen – die Veröffentlichung erkennt den Verstoß. Regression AT-40; Grundlage für AT-56; DST-Fälle nach PH-AT-018.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-13, WP-M4-14 (Hinweise zu Pausen, Ruhezeit und Überschreitungen; Veröffentlichung)
  - extern: WP-M1-19, WP-M1-20, WP-M1-21 (Konfigurationsdienst mit Geltungsebenen und Validatoren)
- **Entscheidungen:** keine D-Entscheidung; die Regelwerte selbst brauchen eine fachliche/rechtliche Freigabe (blockiert Produktivschaltung, nicht Bau).
- **Größe:** M
- **Nicht im Umfang:** Klassifizierung der Warnungen und Ausnahmen (WP-M7d-10); Prüfung bei Bewerbung (WP-M7d-23).

### WP-M7d-10 – Warnungsklassen: harte Sperre, prüfpflichtiger Hinweis, Empfehlung; Ausnahmen nach Regelwerk

- **Ziel:** Planungswarnungen sind in drei Klassen getrennt; harte Konflikte lassen sich nicht übergehen, prüfpflichtige Hinweise nur mit Berechtigung und Begründung.
- **Anforderungen:** `LH-6.23-SCH-035`
- **Liefergegenstände:**
  - Backend: Klassifikation jedes Prüfergebnisses (hart / prüfpflichtig / Empfehlung) aus der Regelmatrix; Ausnahmebefehl mit Rolle, Pflichtbegründung und Auditeintrag; harte Regeln ohne Ausnahmepfad.
  - Datenbank: Ausnahmeeintrag an der `roster_revision` (wer, wann, Regel, Grund); Audit.
  - API: `/v1/rosters/{roster_id}/publish` – `PublishRequest.reason` ist im Vertrag bereits immer Pflicht und ersetzt daher nicht die Ausnahmebegründung; je übergangenem prüfpflichtigem Hinweis ist eine eigene Ausnahme mit Regelbezug und Begründung nötig (Ausnahmebefehl als Arbeitsname in WP-M7d-01). Konfliktliste nach Klasse im Wirkungsbericht (`impact_report_id`).
  - Oberfläche: UI-12 Dienstplan – getrennte Darstellung der drei Klassen, Ausnahmedialog.
- **Detailverträge vorab:** Zuordnung jeder Regel zu einer Klasse und zur ausnahmeberechtigten Rolle (fachlich freizugeben).
- **Akzeptanzkriterien:**
  1. Ein als hart festgelegter Konflikt ist weder ohne noch mit Begründung veröffentlichbar (SCH-035).
  2. Ein prüfpflichtiger Hinweis ist nur durch eine berechtigte Rolle mit Begründung übergehbar; die Ausnahme ist im Audit sichtbar (SCH-035).
  3. Eine Empfehlung blockiert nie und verlangt keine Begründung (SCH-035).
  4. Ein Planer ohne Ausnahmerecht erhält 403 beim Ausnahmeversuch (SCH-035).
  5. Eine erteilte Ausnahme gilt nur für die benannte Regel, Schicht und Entwurfsrevision; ändert sich die Schicht danach, ist die Ausnahme verfallen und muss neu erteilt werden (SCH-035).
- **Tests:** positiv: Ausnahme mit Rolle und Grund. negativ: harte Regel, fehlende Begründung, fehlende Rolle, Ausnahme eines fremden Mandanten, Wiederverwendung einer Ausnahme nach Schichtänderung. konkurrierend: Ausnahme wird erteilt, während ein zweiter Planer dieselbe Schicht ändert – Veröffentlichung nur mit zur Revision passender Ausnahme. Liefert Planungswarnungen an WP-M7b-07.
- **Abhängigkeiten:** intern: WP-M7d-09.
  - extern: WP-M1-07, WP-M1-46 (Rollen/Rechte, Audit)
  - extern: WP-M4-14 (Veröffentlichung des Dienstplans)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Selbstverwaltung der Regelklassen durch Mandanten-Admins (CFG, M8).

### WP-M7d-11 – Feiertage und betriebliche Sonderregelungen nach Ort und Datum

- **Ziel:** Schichten werden nach Ort und Datum als Feiertags-, Sonder- oder Nachtschicht gekennzeichnet, auch bei Wechsel über Mitternacht.
- **Anforderungen:** `LH-6.23-SCH-019`
- **Liefergegenstände:**
  - Backend: Kalenderdienst im Modul `staff-time` (gesetzliche Feiertage je Ort, betriebliche Sondertage); anteilige Kennzeichnung einer Schicht, die über Mitternacht in oder aus einem Feiertag läuft; Nachtkennzeichnung.
  - Datenbank: Feiertags-/Sondertagskalender mit Ortsbezug und Gültigkeit (Arbeitsname aus WP-M7d-01).
  - Oberfläche: UI-12 Dienstplan – Kennzeichen im Wochenraster; Pflegemaske des Kalenders.
  - Konfigurationsparameter: `reporting_timezone` für die Tageszuordnung.
- **Detailverträge vorab:** Quelle und Pflegeverantwortung des Feiertagskalenders; Definition „Nacht“ (im Pflichtenheft nicht beziffert); Ortsbezug (Standort der Schicht).
- **Akzeptanzkriterien:**
  1. Eine Schicht an einem Feiertag des Schichtorts ist gekennzeichnet; dieselbe Schicht an einem Ort ohne diesen Feiertag nicht (SCH-019).
  2. Eine Nachtschicht vom Vortag in den Feiertag ist anteilig korrekt gekennzeichnet (SCH-019).
  3. Ein betrieblicher Sondertag wirkt nur für den Mandanten, der ihn angelegt hat (SCH-019).
  4. Kalenderpflege ist nur einer berechtigten Rolle möglich, versioniert und auditiert; eine nachträgliche Kalenderänderung verändert bereits abgeschlossene Zeitperioden nicht still (SCH-019).
- **Tests:** positiv: Feiertag, Nachtschicht über Mitternacht, DST-Nacht. negativ: fremder Mandantenkalender wirkt nicht. Regression AT-47; Zuarbeit zu E2E-102.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-10 (Zeitrechnung über Mitternacht, Zeitzone und DST)
  - extern: UNGEDECKT – gemeinsamer Feiertags-/Schließtagskalender für Serienbuchung (AT-06) – Abstimmung, ob ein Kalender geteilt wird (kein lieferndes WP in M7a; WP-M7a-07 ist selbst Abnehmer von WP-M7d-11, siehe Offene Punkte 34)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Zuschlagsberechnung für Lohn (nur Kennzeichnung und Stundenart, WP-M7d-35/38).

### WP-M7d-12 – Geplante Pausen in der operativen Fahrzeugverfügbarkeit

- **Ziel:** Geplante Pausen sind Unterbrechungen der Schicht und werden von der Disposition als harte Nichtverfügbarkeit berücksichtigt.
- **Anforderungen:** `LH-6.23-SCH-018`
- **Liefergegenstände:**
  - Backend: Pausenplan je Schicht als zeitliche Intervalle; Leseport/Ereignis an `booking-dispatch`, das Pausenintervalle als Constraint liefert (Kap. 01 §1.6: Einfügeheuristik hält Pausen ein).
  - Datenbank: Pausenintervalle an `staff_shift` (Struktur aus WP-M7d-01).
  - Ereignisse/Outbox: Änderung des Pausenplans (Arbeitsname `shift.break_plan.changed`) mit Planrevision.
  - Oberfläche: UI-12 Dienstplan – Pausen in der Schicht; UI-07 Operator Livebetrieb – Pause als Sperrbereich am Umlauf.
- **Detailverträge vorab:** Vertrag des Leseports staff-time→booking-dispatch; Verhalten, wenn eine bestätigte Fahrt bereits in einer nachträglich geplanten Pause liegt.
- **Akzeptanzkriterien:**
  1. Zur geplanten Pause wird kein neuer, damit unvereinbarer Fahrauftrag zugesagt (SCH-018).
  2. Eine Pausenänderung im veröffentlichten Plan wirkt in der Disposition ab der neuen Revision; bestehende Zusagen bleiben unverändert und werden als Konflikt gemeldet (SCH-018).
  3. Eine veröffentlichte Pause ändert keine Ist-Zeit (SCH-018).
  4. Eine Pause ist als Intervall mit Beginn und Ende innerhalb der Schicht planbar; Pausen außerhalb der Schichtzeit oder überlappende Pausen werden abgelehnt (SCH-018).
- **Tests:** positiv: Buchungsanfrage außerhalb der Pause wird zugesagt. negativ: Anfrage in der Pause wird nicht diesem Fahrzeug zugesagt. konkurrierend: Pausenänderung und Buchungsbestätigung parallel – keine Zusage in die Pause (Sperren der Run-Zeile und Revisionsvergleich nach Kap. 04 §4.5). Ereignis doppelt und verspätet zugestellt – genau eine Wirkung, keine Rücksetzung (PH-AT-017).
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M2-16, WP-M2-17 (Kapazitäts-/Einfügeprüfung mit Run-Revision)
  - extern: WP-M4-03 (Schichtmodell mit Pausenplan)
  - extern: WP-M7c-09, WP-M7c-13 (Pausen starten/beenden und Dienstplanzeiten in der Buchungsprüfung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ist-Pausen des Fahrers (M7c); Vorwarnung bei drohender Überschreitung (WP-M7d-13).

### WP-M7d-13 – Vorwarnung der Disposition bei Pause oder Arbeitszeitüberschreitung

- **Ziel:** Für belegte Fahrzeugschichten meldet das System der Disposition frühzeitig, wenn eine geplante Pause oder eine Arbeitszeitgrenze erreicht wird.
- **Anforderungen:** `LH-6.24-TIM-016`
- **Liefergegenstände:**
  - Backend: Vorschaurechnung aus Schichtplan, Ist-Zeit (`time_event`) und gebuchten Fahrten; Erzeugung einer Kapazitätsrestriktion mit Wirksamkeitszeitpunkt.
  - Ereignisse/Outbox: Vorwarnereignis (Arbeitsname `shift.capacity_restriction.forecast`) an `booking-dispatch` und Alarmwesen (`business_alert`, Dedupe `(policy,event,recipient)`).
  - Oberfläche: UI-07 Operator Livebetrieb – Vorwarnung an der Fahrzeugschicht.
  - Konfigurationsparameter: `max_daily_work_minutes`; Vorwarnzeit (kein Katalogparameter vorhanden, siehe Offene Punkte).
- **Detailverträge vorab:** Vorwarnhorizont; Vertrag der Restriktion gegenüber `booking-dispatch`.
- **Akzeptanzkriterien:**
  1. Der Dispatcher erhält vor Erreichen der Pause bzw. Arbeitszeitgrenze eine Vorwarnung zur betroffenen Fahrzeugschicht (TIM-016).
  2. Neue Fahrten, die in den Restriktionszeitraum fallen, werden einem anderen Fahrzeug zugewiesen oder abgelehnt, nicht der betroffenen Schicht (TIM-016).
  3. Dieselbe Vorwarnung wird je Empfänger nur einmal erzeugt (TIM-016).
  4. Die Vorwarnung enthält keine Personaldaten über Schicht, Zeitpunkt und Art der Restriktion hinaus; eine entfallene Restriktion (Schicht geändert, Pause verlegt) wird zurückgenommen (TIM-016).
- **Tests:** positiv: Vorwarnung und Umlenkung neuer Fahrten. negativ: keine Warnung bei unkritischer Schicht. konkurrierend: Buchung während Entstehen der Restriktion – keine Zusage in den gesperrten Zeitraum.
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-12.
  - extern: WP-M2-15, WP-M2-16 (Disposition mit Kapazitätsprüfung)
  - extern: WP-M3-52, WP-M3-36 (Alarm-Grundlage und Livebetrieb UI-07)
  - extern: WP-M4-08 (Ist-Arbeitszeit als Zeitereignisse)
  - extern: WP-M7b-07 (Schicht- und Dienstplanübersicht der Leitstelle (WP-M7b-07) als Abnehmer)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Automatische Umplanung bereits bestätigter Fahrten (Disposition, M7b).

### WP-M7d-14 – Abwesenheitsanträge mit Art, Zeitraum, Status, Genehmiger, Kommentar

- **Ziel:** Mitarbeiter stellen Abwesenheitsanträge, berechtigte Rollen genehmigen oder lehnen ab; genehmigte Abwesenheit wird automatisch Planungsrestriktion.
- **Anforderungen:** `LH-6.24-TIM-008`
- **Liefergegenstände:**
  - Backend: Aggregat Abwesenheitsantrag mit Zustandsautomat (beantragt, genehmigt, abgelehnt, zurückgezogen/storniert – Namen aus WP-M7d-01); bei Genehmigung Konfliktprüfung gegen belegte Schichten.
  - Datenbank: `absence_request` (Art, Zeitraum, Status, Genehmiger, Kommentar; kein Diagnosefeld – Kap. 04 §4.4 „Abwesenheitsdiagnosen ausgeschlossen“).
  - API: `/v1/absences`.
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – Antrag, Zustände „Konflikt“, „offene Genehmigung“; Genehmigungsliste für Vorgesetzte.
  - Ereignisse/Outbox: Genehmigung/Ablehnung (Arbeitsname `absence.approved`, `absence.rejected`); Benachrichtigung des Antragstellers.
- **Detailverträge vorab:** Katalog der Abwesenheitsarten; Genehmigerermittlung; Freitext-Kommentar mit Hinweis „keine Gesundheitsangaben“.
- **Akzeptanzkriterien:**
  1. Ein genehmigter Urlaub erscheint ohne weiteren Schritt als Planungsrestriktion im Dienstplan (TIM-008).
  2. Der Antrag enthält Art, Zeitraum, Status, Genehmiger und Kommentar; es gibt kein Feld für Diagnosen (TIM-008).
  3. Wird Urlaub über einer bereits belegten Schicht genehmigt, entstehen Verfügbarkeitskonflikt und offene Ersatzschicht (AT-41).
  4. Ein abgelehnter oder offener Antrag blockiert keine Zuweisung (TIM-008).
  5. Antragsteller und Genehmiger werden über Entscheidung bzw. neuen Antrag benachrichtigt; jede Statusänderung ist mit Person und Zeitpunkt auditiert (TIM-008).
  6. Rücknahme oder Stornierung einer genehmigten Abwesenheit hebt die Planungsrestriktion wieder auf (TIM-008).
- **Tests:** positiv: Antrag, Genehmigung, Restriktion. negativ: Genehmigung durch Unberechtigten, Antrag für fremde Person. konkurrierend: Genehmigung und Schichtzuweisung parallel – Ergebnis konsistent (Konflikt sichtbar). Besteht AT-41.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-06, WP-M4-20 (Abwesenheit als Verfügbarkeitsrestriktion (Minimalmodell), UI-13 Grundausbau)
  - extern: WP-M2-32 (Nachrichtenversand)
- **Entscheidungen:** D-11: Aufbewahrung der Anträge – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Urlaubssaldo (WP-M7d-36); Krankmeldung mit beschränkter Einsicht (WP-M7d-15).

### WP-M7d-15 – Krankheitsabwesenheit mit beschränkter Einsicht

- **Ziel:** Krankheitsabwesenheit ist erfassbar, ohne dass Diagnosen oder Nachweise im Schichtkalender oder für Planer sichtbar werden.
- **Anforderungen:** `LH-6.24-TIM-010`
- **Liefergegenstände:**
  - Backend: Abwesenheitsart „Krankheit“ mit eigener Leseberechtigung; Planer- und Dispositionssicht liefern nur „nicht verfügbar“ mit Zeitraum (Kap. 01 §1.5: keine Diagnosen in Dispositionssichten).
  - Datenbank: Spalten-/Viewrechte auf `absence_request` (Kap. 04 §4.6); keine medizinischen Inhalte im Kernschema.
  - API: `/v1/absences` – getrennte Projektionen für eigene Sicht, Personalrolle, Planer.
- **Detailverträge vorab:** Rollenmatrix: wer sieht Art, wer nur Nichtverfügbarkeit; Umgang mit medizinischen Nachweisen (Pflichtenheft nennt keinen Speicherort, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Der Planer sieht die Abwesenheit mit Zeitraum, aber keine medizinische Detailinformation (TIM-010).
  2. API-Antwort der Planersicht enthält weder Diagnose- noch Nachweisfelder, auch nicht bei direktem Aufruf (TIM-010).
  3. Zugriffe der Personalrolle auf Krankheitsdatensätze werden protokolliert (TIM-010).
  4. Der Zugriff eines fremden Mandanten auf Krankheitsdetails wird serverseitig abgelehnt und protokolliert, ohne Existenzauskunft (AT-49).
  5. Krankheitsabwesenheit ist durch die dafür berechtigte Rolle erfassbar; Suche, Export, Berichte, Protokolle und Ereignisse außerhalb der HR-Sicht enthalten nur „nicht verfügbar“ (TIM-010).
- **Tests:** positiv: Krankmeldung erfassen, Planer sieht „nicht verfügbar“. negativ: Planer ruft Detail ab → 403; Ereignisnutzlast enthält keine Art „Krankheit“ für Dispositionsabnehmer; Fremdmandant über Detail-, Listen-, Such- und Exportweg (PH-AT-001); Log- und Fehlermeldungsprüfung auf Art/Freitext. Zuarbeit zu AT-49 und E2E-091.
- **Abhängigkeiten:** intern: WP-M7d-14.
  - extern: WP-M1-31, WP-M1-46 (Feldbezogene Zugriffskontrolle, Audit)
- **Entscheidungen:** D-11: Zugriffs- und Löschklasse – blockiert nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Sofortreaktion auf den Ausfall (WP-M7d-17).

### WP-M7d-16 – Abwesenheiten als Verfügbarkeitsrestriktion im Planer

- **Ziel:** Urlaub, Krankheit, Fortbildung und andere Abwesenheiten erscheinen im Wochenkalender als Nichtverfügbarkeit und blockieren Zuweisungen.
- **Anforderungen:** `LH-6.23-SCH-013`
- **Liefergegenstände:**
  - Oberfläche: UI-12 Dienstplan – Abwesenheitsbalken „nicht verfügbar“ ohne Diagnose im Wochenraster und in der Listenansicht.
  - Backend: Zuweisungsbefehle und Vorlagenanwendung prüfen genehmigte Abwesenheit; Sperre mit Ursache „Abwesenheit“.
- **Detailverträge vorab:** Darstellungsregel, welche Abwesenheitsarten der Planer als Art sieht und welche nur als „nicht verfügbar“.
- **Akzeptanzkriterien:**
  1. Eine genehmigte Abwesenheit blockiert die Zuweisung im betroffenen Zeitraum und erscheint im Wochenkalender (SCH-013).
  2. Urlaub, Krankheit, Fortbildung und sonstige Abwesenheit werden als Restriktion angezeigt; bei Krankheit ist keine Diagnose sichtbar (SCH-013).
  3. Teilweise Überdeckung einer Schicht blockiert ebenfalls (SCH-013).
- **Tests:** positiv: Kalenderanzeige. negativ: Zuweisung in Abwesenheit per Raster, Liste und API wird abgelehnt; Vorlagenanwendung und automatische Zuteilung überspringen abwesende Personen. konkurrierend: Genehmigung der Abwesenheit parallel zur Zuweisung – entweder Ablehnung der Zuweisung oder sichtbarer Konflikt, nie eine stille Doppelbelegung.
- **Abhängigkeiten:** intern: WP-M7d-14, WP-M7d-15.
  - extern: WP-M4-07, WP-M4-18 (Konfliktprüfung Abwesenheit, UI-12 Dashboard)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Antragsworkflow (WP-M7d-14).

### WP-M7d-17 – Krankmeldung und ungeplanter Ausfall: sofortiger Ersatzbedarf

- **Ziel:** Ein ungeplanter Ausfall markiert die betroffenen Dienste sofort als neu zu besetzen, nimmt sie aus der Disponierbarkeit und zeigt Ersatzbedarf und betroffene Fahrten.
- **Anforderungen:** `LH-6.23-SCH-020`
- **Liefergegenstände:**
  - Backend: Befehl „Ausfall melden“; betroffene Schichten erhalten Status „neu zu besetzen“ in einer neuen Revision; Entzug der Disponierbarkeit (Widerruf `resource_release`, falls vorhanden); Ermittlung betroffener Fahrten.
  - Ereignisse/Outbox: Ersatzbedarf (Arbeitsname `shift.replacement_required`) an Leitstelle und Planer; Alarm über `business_alert`.
  - Oberfläche: UI-12 Dienstplan – Zustand „unbesetzter Dienst“; UI-07 Operator Livebetrieb – Ersatzbedarf und betroffene Fahrten.
  - Konfigurationsparameter: `absence_substitution_mode` („Qualifikation/Freigabe zwingend“).
  - Kennzahlen: `Schicht-Abdeckungsquote` (kurzfristige Ausfälle einbeziehen), `Personalmangelablehnungen`.
  - API: Befehl „Ausfall melden“ im Modul `staff-time` (Pfad als Arbeitsname in WP-M7d-01 unter `/v1/absences` bzw. `/v1/rosters`; Kap. 05 §5.7 nennt keine Operation) mit `Idempotency-Key`.
- **Detailverträge vorab:** Enum-Werte von `absence_substitution_mode` (im Katalog nicht aufgezählt, siehe Offene Punkte); Definition „kurz vor Dienstbeginn“.
- **Akzeptanzkriterien:**
  1. Ein Ausfall markiert alle verbundenen Dienste der Person im Ausfallzeitraum als neu zu besetzen (SCH-020).
  2. Planer und Leitstelle erhalten sofort einen Hinweis mit Ersatzbedarf; betroffene Fahrten sind sichtbar (SCH-020; AT-44).
  3. Der betroffene Dienst ist nicht mehr disponierbar; neue Fahrten werden ihm nicht zugewiesen (AT-44).
  4. Die Meldung an Disposition und Leitstelle enthält keinen Ausfallgrund „Krankheit“ (SCH-020).
  5. Die bisher veröffentlichte Planrevision bleibt unverändert abrufbar; der Ausfall ist mit Melder und Zeitpunkt auditiert (SCH-020).
  6. Ersatz wird gemäß `absence_substitution_mode` nur mit erfüllter Qualifikation und Freigabe vorgeschlagen oder besetzt; ohne einsatzbereiten Ersatz entsteht keine Kapazitätszusage (SCH-020; E2E-081).
- **Tests:** positiv: Ausfall kurz vor Dienstbeginn. negativ: Ausfallmeldung durch Unberechtigten; doppelte Meldung erzeugt keinen zweiten Bedarf. konkurrierend: Ausfall und Buchungsbestätigung auf dieselbe Schicht parallel – keine Zusage auf nicht disponierbaren Dienst. Besteht AT-44; Zuarbeit zu E2E-081.
- **Abhängigkeiten:** intern: WP-M7d-14, WP-M7d-15.
  - extern: WP-M3-36, WP-M3-52, WP-M3-08 (Livebetrieb UI-07, Alarm-Grundlage, Einsatzfreigabe `resource_release`)
  - extern: WP-M4-17, WP-M4-05 (Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit; offene Schichten)
  - extern: WP-M7c-14 (Zusammengeführte Verfügbarkeit)
  - extern: WP-M7b-04 (Umplanung betroffener Fahrten in der Leitstelle)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ersatzbesetzung über Angebot (WP-M7d-23/24) oder Springer (WP-M7d-19); Umplanung der Fahrten (M7b).

### WP-M7d-18 – Planreaktion auf Fahrzeugausfall, Reparatursperre und Personalwechsel ohne Historienverlust

- **Ziel:** Fahrzeugausfall, Reparatursperre oder ungeplanter Personalwechsel erzeugen offenen Ersatzbedarf in einer neuen Planrevision; die alte veröffentlichte Version bleibt erhalten.
- **Anforderungen:** `LH-6.23-SCH-038`
- **Liefergegenstände:**
  - Backend: Abnehmer der Flottenereignisse (Sperre, Ausfall) im Modul `staff-time` über `inbox`; erzeugt neue `roster_revision` mit offenem Fahrzeug- oder Personalbedarf; alte Revision unveränderlich.
  - Datenbank: `roster_revision` als fortlaufende, nicht überschreibbare Folge; `staff_shift.roster_revision`.
  - Oberfläche: UI-12 Dienstplan – Revisionshistorie, Markierung „Fahrzeug gesperrt – Ersatz nötig“.
- **Detailverträge vorab:** Ereignisvertrag Flotte→staff-time (Sperre gesetzt/aufgehoben, Fahrzeugwechsel) mit M7c; Regel, ob automatisch erzeugte Revisionen sofort veröffentlicht sind oder Planerfreigabe brauchen.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeugausfall erzeugt offenen Ersatzbedarf an allen betroffenen Schichten; die vorherige veröffentlichte Planversion ist unverändert abrufbar (SCH-038).
  2. Eine Reparatursperre vor Schichtbeginn erzeugt denselben Bedarf; ein verspätetes Ereignis hebt keine aktuelle Sperre auf (SCH-038).
  3. Ein ungeplanter Personalwechsel ist als neue Revision mit Urheber und Zeitpunkt nachvollziehbar (SCH-038).
  4. Doppelt zugestellte Flottenereignisse erzeugen genau eine neue Revision (SCH-038).
  5. Betroffene Mitarbeiter und Planer werden über den entstandenen Ersatzbedarf informiert; die Mitteilung folgt der Empfängerlogik aus WP-M7d-21, sobald diese vorliegt (SCH-038).
- **Tests:** positiv: Sperre → Ersatzbedarf. negativ: Duplikat-Ereignis, Ereignis in falscher Reihenfolge (PH-AT-017), Versuch, eine veröffentlichte Revision zu überschreiben, Lesen alter Revisionen aus fremdem Mandanten (AT-61). konkurrierend: Flottensperre und Planeränderung derselben Schicht parallel – genau eine fortlaufende Revisionsfolge ohne Lücke oder verlorene Änderung. Zuarbeit zu E2E-100, E2E-071 (Planseite), AT-50 und AT-61.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M3-08, WP-M3-46 (Schadenssperre und Safety-Revision)
  - extern: WP-M4-15 (Änderungs- und Veröffentlichungsrevisionen der Schicht)
  - extern: WP-M7c-37 (Ereignisse Fahrzeugdomäne/Schadenakte, Fahrzeugwechsel, Ersatzfahrzeug)
  - extern: WP-M1-14 (Outbox/Inbox)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme (M6b); Fahrzeugwechsel in laufender Schicht mit Neuprüfung (M7c, AT-30).

### WP-M7d-19 – Stellvertretungs-, Springer- und Bereitschaftsdienste

- **Ziel:** Planer kennzeichnen Dienste als Stellvertretung, Springer oder Bereitschaft; diese gelten erst nach Aktivierung als Arbeitszeit und Fahrzeugumlauf.
- **Anforderungen:** `LH-6.23-SCH-032`
- **Liefergegenstände:**
  - Backend: Schichtart mit eigenen Aktivierungsregeln; Aktivierungsbefehl wandelt Bereitschaft in aktiven Dienst (neue Revision); Zeitkonten- und Dispositionsprojektion ignorieren nicht aktivierte Bereitschaft.
  - Datenbank: Schichtart und Aktivierungszeitpunkt an `staff_shift`.
  - Oberfläche: UI-12 Dienstplan – Kennzeichnung, Aktion „aktivieren“.
  - Ereignisse/Outbox: Aktivierung mit Benachrichtigung des Mitarbeiters.
- **Detailverträge vorab:** Aktivierungsregeln je Dienstart und deren Bewertung (im Pflichtenheft nicht ausgeführt, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Ein Bereitschaftsdienst wird nicht automatisch als aktive Arbeitszeit gebucht (SCH-032).
  2. Ein Bereitschaftsdienst erzeugt keinen Fahrzeugumlauf und keine disponierbare Kapazität, solange er nicht aktiviert ist (SCH-032).
  3. Nach Aktivierung durch eine berechtigte Rolle gelten Regelprüfung, Benachrichtigung und Disponierbarkeit wie bei einer regulären Schicht (SCH-032).
  4. Stellvertretungs-, Springer- und Bereitschaftsdienste sind je eigens gekennzeichnet und im Planer mit ihrer Verfügbarkeit erkennbar (SCH-032).
  5. Eine doppelt ausgelöste Aktivierung wirkt genau einmal (SCH-032).
- **Tests:** positiv: Bereitschaft aktivieren. negativ: nicht aktivierte Bereitschaft in Zeitkonto und Disposition unsichtbar; Aktivierung mit Ruhezeitverstoß wird beanstandet; Aktivierung durch Unberechtigten. konkurrierend: zwei parallele Aktivierungen bzw. Aktivierung parallel zur Buchungszusage – keine Zusage vor wirksamer Aktivierung und Einsatzfreigabe.
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-17.
  - extern: WP-M4-17 (Fahrerschicht und Disponierbarkeit)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Vergütung von Bereitschaftszeit (keine Fachregel im Pflichtenheft).

### WP-M7d-20 – Publikationsvorschau: Betroffene, Differenz zur Vorversion, getrennte Wochenfreigabe

- **Ziel:** Vor der Veröffentlichung zeigt das System betroffene Personen und die Unterschiede zur Vorversion; mehrere Wochen lassen sich getrennt freigeben.
- **Anforderungen:** `LH-6.23-SCH-025`
- **Liefergegenstände:**
  - Backend: Differenzrechnung zwischen Entwurf und letzter veröffentlichter `roster_revision` (hinzugefügt, verschoben, entfernt) je Person; Veröffentlichung je Woche.
  - API: Vorschau-Unterressource zu `/v1/rosters` (Arbeitsname `/v1/rosters/{roster_id}/publish-preview`); `/v1/rosters/{roster_id}/publish` je Woche mit `If-Match` und `Idempotency-Key`; der `PublishRequest` trägt die Kennung der Vorschau als `impact_report_id` (Pflichtfeld des bestehenden Vertrags) sowie `effective_from` und `reason`.
  - Oberfläche: UI-12 Dienstplan – Differenzliste, Liste betroffener Personen, blockierende Konflikte (Kap. 06 §6.8), Wochenauswahl.
  - Konfigurationsparameter: `shift_publish_notice_hours`.
- **Detailverträge vorab:** Definition „verschoben“ gegenüber „entfernt + hinzugefügt“; Wirkung von `shift_publish_notice_hours` (Hinweis oder Sperre – nicht spezifiziert, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Die Änderungsansicht zeigt hinzugefügte, verschobene und entfernte Schichten gegenüber der Vorversion (SCH-025).
  2. Die Vorschau listet genau die Personen, deren eigene Schichten sich ändern (SCH-025).
  3. Von zwei bearbeiteten Wochen lässt sich eine veröffentlichen, während die andere Entwurf bleibt (SCH-025).
  4. Vorschau und anschließende Veröffentlichung beziehen sich auf dieselbe Entwurfsrevision; bei zwischenzeitlicher Änderung liefert die Veröffentlichung 412 (SCH-025).
  5. Eine Veröffentlichung ohne oder mit nicht zur Entwurfsrevision passender `impact_report_id` wird abgelehnt (SCH-025).
  6. Die Vorschau zeigt einem Planer nur Personen seiner berechtigten Bereiche; die Wirkung von `shift_publish_notice_hours` wird in der Vorschau ausgewiesen (SCH-025; Sperre oder Hinweis siehe Offene Punkte 8).
- **Tests:** positiv: Differenz für hinzugefügt/verschoben/entfernt. negativ: veralteter ETag, blockierender Konflikt. konkurrierend: zwei Planer veröffentlichen parallel – genau eine neue Revision. Ermöglicht AT-52 (mit WP-M7d-21).
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-10.
  - extern: WP-M4-14, WP-M4-15 (Veröffentlichung des Dienstplans, Revisionen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Versand der Mitteilungen (WP-M7d-21).

### WP-M7d-21 – Gezielte Push-/E-Mail-Mitteilungen bei Veröffentlichung und Änderung

- **Ziel:** Nur Mitarbeiter, deren eigene Schichten sich geändert haben, erhalten eine Mitteilung mit der neuen Planrevision – genau einmal.
- **Anforderungen:** `LH-6.23-SCH-014`
- **Liefergegenstände:**
  - Backend: Empfängerermittlung aus der Differenz (WP-M7d-20); ein fachlicher Mitteilungsauftrag je Person und Revision.
  - Ereignisse/Outbox: Veröffentlichungsereignis in `outbox` (Kap. 05: „Fahrerbenachrichtigung über Outbox“); Abnehmer in `communication-support` erzeugt `notification` mit `notification_attempt` über `channel_template_revision`.
  - Adapter/Worker: Adapter `notification` (Push, E-Mail).
  - Konfigurationsparameter: `notification_quiet_hours`.
  - Oberfläche: Fahrer-App – Mitteilung führt zur eigenen neuen Planrevision.
  - Ereignisse/Outbox: Vorlagen in `channel_template_revision` für Veröffentlichung, Änderung und Entfall je Kanal (Push, E-Mail) gemäß Katalog aus WP-M7d-01; der Abnehmer reagiert auf jede veröffentlichte Revision unabhängig vom Auslöser (Planer, Vergabe, Tausch, Ausfall, Aktivierung).
- **Detailverträge vorab:** Dedupe-Schlüssel (Person, Planrevision, Zweck); Vorlagen für Veröffentlichung/Änderung/Entfall; Verhalten in `notification_quiet_hours`.
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter ohne Änderung an eigenen Schichten erhält keine Änderungsmitteilung (SCH-014).
  2. Jeder betroffene Mitarbeiter erhält genau eine Mitteilung je Revision mit Verweis auf die neue Planrevision, auch bei Wiederholung der Zustellung oder des Ereignisses (SCH-014; AT-52).
  3. Bei teilweiser Neuveröffentlichung stimmen Empfängerliste und Änderungsinhalt mit der Vorschau überein (AT-52).
  4. Die Mitteilung enthält keine Daten anderer Mitarbeiter (SCH-014).
  5. Die Mitteilung wird über Push und/oder E-Mail zugestellt; ein endgültig gescheiterter Zustellversuch ist als `notification_attempt` sichtbar und führt nicht zu einem zweiten fachlichen Mitteilungsauftrag (SCH-014).
  6. Auch Revisionen aus Vergabe, Tausch, Ausfall und Aktivierung erzeugen Mitteilungen ausschließlich an die jeweils Betroffenen (SCH-014).
- **Tests:** positiv: Teilveröffentlichung mit drei Betroffenen. negativ: unveränderter Mitarbeiter, doppelte Ereigniszustellung, Wiederholung nach Adapterfehler. konkurrierend: zwei Veröffentlichungen kurz nacheinander – keine doppelten Pushes. Besteht AT-52; Regression AT-37.
- **Abhängigkeiten:** intern: WP-M7d-20.
  - extern: WP-M2-32, WP-M2-33 (Nachrichtenversand, Vorlagen, `notification`/`notification_attempt`, Adapter `notification`)
  - extern: WP-M1-14 (Outbox/Inbox)
  - extern: WP-M7b-31 (Ruhezeiten für Nachrichten (`notification_quiet_hours`))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Erinnerungen vor Schichtbeginn (WP-M7d-22); Massenkommunikation an Zielgruppen (M7b, AT-17).

### WP-M7d-22 – Erinnerungen vor Schichtbeginn und zum Ein-/Ausstempeln

- **Ziel:** Mitarbeiter erhalten konfigurierbare Erinnerungen vor Schichtbeginn und zum Stempeln – nur für die eigene Schicht, nach Schichtänderung neu berechnet und nie doppelt.
- **Anforderungen:** `LH-6.23-SCH-026`
- **Liefergegenstände:**
  - Backend: Erinnerungsplaner im Modul `staff-time`; Einstellung je Mandant mit Überschreibung je Mitarbeiter; bei Schichtänderung oder -entfall werden offene Erinnerungen verworfen und neu geplant.
  - Datenbank: Erinnerungseinstellung und geplante Erinnerungen (Arbeitsnamen aus WP-M7d-01) mit Unique-Schlüssel (Person, Schicht, Revision, Art).
  - Adapter/Worker: zeitgesteuerter Versand über `notification`.
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – eigene Erinnerungseinstellung; Mandanteneinstellung im Konfigurationseditor.
  - Konfigurationsparameter: für den Erinnerungsvorlauf existiert kein Eintrag in kataloge.csv (nur `customer_notify_before_pickup_min` für Fahrgäste); 15 Minuten ist laut Anforderung ein Beispiel, kein Default (siehe Offene Punkte).
- **Detailverträge vorab:** Parametername und Geltungsebene des Erinnerungsvorlaufs; Auslöser der Stempelerinnerung (fehlender Stempel zu Schichtbeginn/-ende).
- **Akzeptanzkriterien:**
  1. Der Fahrer erhält höchstens die konfigurierten Erinnerungen der eigenen Schicht (SCH-026).
  2. Nach Änderung der Schichtzeit wird nur die Erinnerung zur neuen Zeit versandt; die alte entfällt (AT-63).
  3. Eine Erinnerung wird auch bei Worker-Neustart oder Wiederholung nicht doppelt versandt (AT-63).
  4. Die Ein-/Ausstempel-Erinnerung entfällt, wenn bereits gestempelt wurde (SCH-026).
  5. Die Einstellung je Mitarbeiter übersteuert die des Mandanten im zulässigen Rahmen (SCH-026).
  6. Erinnerungen gehen nur an den Mitarbeiter der Schicht, nie an Dritte; Erinnerungseinstellungen sind nur für die eigene Person bzw. durch die berechtigte Mandantenrolle änderbar (SCH-026; AT-63).
- **Tests:** positiv: Erinnerung 15 min (Beispielwert) vor Beginn. negativ: entfallene Schicht, fremde Schicht, bereits gestempelt. konkurrierend: Schichtänderung während Versandlauf – genau eine gültige Erinnerung. Besteht AT-63.
- **Abhängigkeiten:** intern: WP-M7d-21.
  - extern: WP-M2-32 (Nachrichtenversand und Adapter `notification`)
  - extern: WP-M4-11 (Stempeln in der Fahrer-App)
  - extern: WP-M1-19, WP-M1-21 (Konfigurationsdienst)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Prüfpflicht-Erinnerungen (WP-M7c-23); Fahrgast-Erinnerungen.

### WP-M7d-23 – Schichtangebote mit Frist, Qualifikation und Auswahlmodus; Zulässigkeitsprüfung der Bewerbung

- **Ziel:** Offene Schichten werden berechtigten Mitarbeitern als Angebot mit Bewerbungsfrist, Qualifikationsbedarf und Auswahlmodus zugänglich; nur geeignete Mitarbeiter können reagieren.
- **Anforderungen:** `LH-6.23-SCH-021`
- **Liefergegenstände:**
  - Backend: Aggregat Schichtangebot (entworfen, offen, vergeben, abgelaufen, zurückgezogen – Namen aus WP-M7d-01); Eignungsprüfung je Mitarbeiter (Bereichsberechtigung, Qualifikation, Abwesenheit, Doppelbelegung, Ruhezeit/Arbeitszeit) bei Anzeige und bei Bewerbung.
  - Datenbank: `shift_offer`, `shift_application`.
  - API: `/v1/shift-offers` (Planer: anlegen/zurückziehen; Mitarbeiter: eigene zulässige Angebote, bewerben, zurückziehen).
  - Oberfläche: UI-12 Dienstplan – Angebot aus offenem Dienst erstellen; Fahrer-App/UI-13 – Angebotsliste mit Kennzeichnung „nicht zulässig“ samt Grund.
  - Ereignisse/Outbox: Angebot veröffentlicht → Mitteilung an berechtigte Mitarbeiter.
- **Detailverträge vorab:** Zustandsautomat Angebot/Bewerbung; Auswahlmodi (Auswahl durch Disponent, First-come-first-served); Fristablauf.
- **Akzeptanzkriterien:**
  1. Nur Mitarbeiter, die alle Voraussetzungen erfüllen, können auf ein Angebot reagieren; andere erhalten bei direktem API-Aufruf eine Ablehnung (SCH-021).
  2. Eine Bewerbung trotz gesperrter Ruhezeit wird als nicht zulässig angezeigt und nicht vergeben (AT-56).
  3. Nach Ablauf der Bewerbungsfrist sind keine Bewerbungen mehr möglich (SCH-021).
  4. Das Angebot zeigt Bewerbungsfrist, erforderliche Qualifikationen und Auswahlmodus (SCH-021).
  5. Mitarbeiter sehen keine Namen oder Daten anderer Bewerber (SCH-021).
  6. Anlegen und Zurückziehen eines Angebots sind nur befugten Planern des Bereichs möglich und auditiert; ein zurückgezogenes oder abgelaufenes Angebot informiert die Bewerber (SCH-021).
- **Tests:** positiv: geeigneter Fahrer bewirbt sich. negativ: fehlende Qualifikation, Ruhezeitsperre, Abwesenheit, Frist abgelaufen, fremder Mandant, Angebot außerhalb des berechtigten Bereichs, gleicher `Idempotency-Key` mit anderem Angebot (PH-AT-011). konkurrierend: Bewerbung parallel zu Fristablauf bzw. Zurückziehen des Angebots – keine Bewerbung auf geschlossenem Angebot; doppelt gesendete Bewerbung derselben Person zählt einmal. Besteht AT-56.
- **Abhängigkeiten:** intern: WP-M7d-07, WP-M7d-08, WP-M7d-09, WP-M7d-16.
  - extern: WP-M4-05 (Offene Schichten und unbesetzter Bedarf)
  - extern: WP-M2-32 (Nachrichtenversand)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Vergabe (WP-M7d-24, WP-M7d-25).

### WP-M7d-24 – Bewerbungen im Planer genehmigen oder ablehnen – atomare Besetzung

- **Ziel:** Disponenten sehen Bewerbungen und vergeben den Dienst an genau einen Fahrer; alle Bewerber werden informiert.
- **Anforderungen:** `LH-6.23-SCH-022`
- **Liefergegenstände:**
  - Backend: Vergabebefehl in einer Transaktion: Angebot sperren, Zulässigkeit erneut prüfen, Bewerbung annehmen, übrige ablehnen, Schicht in neuer `roster_revision` besetzen (Kap. 04 §4.4 SCH: „atomare Vergabe“).
  - Datenbank: Unique-Constraint „eine angenommene `shift_application` je `shift_offer`“; Sperrreihenfolge nach Kap. 04 §4.5.
  - API: `/v1/shift-offers` (Bewerbungen lesen, genehmigen, ablehnen) mit `Idempotency-Key` und `If-Match`.
  - Oberfläche: UI-12 Dienstplan – Bewerberliste am offenen Dienst mit Eignungsstatus, Aktionen genehmigen/ablehnen.
  - Ereignisse/Outbox: Vergabe und Ablehnung → Mitteilung an alle Bewerber (Arbeitsname `shift.offer.assigned`).
- **Detailverträge vorab:** Vergabetransaktion und Sperrreihenfolge; Verhalten, wenn der Bewerber zwischen Bewerbung und Genehmigung unzulässig wurde.
- **Akzeptanzkriterien:**
  1. Nach Genehmigung ist genau ein Fahrer verbindlich zugeordnet und benachrichtigt (SCH-022).
  2. Bewerben sich zwei Fahrer parallel und werden beide Genehmigungen parallel ausgelöst, entsteht höchstens eine verbindliche Besetzung; beide Fahrer werden informiert (AT-42).
  3. Eine Genehmigung, deren erneute Regelprüfung scheitert, vergibt nicht und nennt den Grund (SCH-022).
  4. Nur befugte Disponenten des Bereichs können genehmigen oder ablehnen (SCH-022).
- **Tests:** positiv: Genehmigung mit Benachrichtigung. negativ: Unbefugter, inzwischen unzulässiger Bewerber, bereits vergebenes Angebot. konkurrierend: zwei parallele Genehmigungen unterschiedlicher Bewerber; Genehmigung parallel zu direkter Planerzuweisung derselben Schicht. Besteht AT-42.
- **Abhängigkeiten:** intern: WP-M7d-21, WP-M7d-23.
  - extern: WP-M4-15 (Veröffentlichungsrevisionen)
  - extern: WP-M1-13, WP-M1-07 (Idempotenz, Rollen/Rechte)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Automatische Vergabe ohne Disponent (WP-M7d-25).

### WP-M7d-25 – First-come-first-served-Modus für offene Schichten

- **Ziel:** Im konfigurierten Modus erhält die zuerst eingehende zulässige Bewerbung die Schicht automatisch nach Konfliktprüfung.
- **Anforderungen:** `LH-6.23-SCH-023`
- **Liefergegenstände:**
  - Backend: Auswahlmodus FCFS am `shift_offer`; Bewerbung löst die Vergabetransaktion aus WP-M7d-24 unmittelbar aus.
  - Oberfläche: Fahrer-App/UI-13 – sofortige Rückmeldung „vergeben an Sie“ bzw. „bereits vergeben“.
  - Konfigurationsparameter: Aktivierung je Mandant/Angebot – kein Eintrag in kataloge.csv (siehe Offene Punkte).
- **Detailverträge vorab:** Definition „zuerst“ (Server-Commit-Reihenfolge); Wechselwirkung mit Offline-Bewerbungen (nicht zulässig, da Serverbestätigung nötig – zu bestätigen).
- **Akzeptanzkriterien:**
  1. Zwei gleichzeitige Bewerbungen erzeugen nur eine Besetzung (SCH-023).
  2. Die unterlegene Bewerbung erhält eine eindeutige Absage; das Angebot ist danach geschlossen (SCH-023).
  3. Eine unzulässige erste Bewerbung wird übersprungen; die nächste zulässige erhält die Schicht (SCH-023).
  4. Ohne aktivierten Modus bleibt die Vergabe beim Disponenten (SCH-023).
- **Tests:** positiv: erste zulässige Bewerbung gewinnt. negativ: unzulässige Bewerbung, Modus aus. konkurrierend: N parallele Bewerbungen – genau eine Besetzung, keine Deadlocks. Regression AT-42.
- **Abhängigkeiten:** intern: WP-M7d-24.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Ranglisten oder Prioritätsregeln (nicht im Pflichtenheft).

### WP-M7d-26 – Schichttausch und Schichtübernahme mit Zustimmungen und Regelprüfung

- **Ziel:** Mitarbeiter beantragen Tausch oder Übernahme; verbindlich wird er erst nach den nötigen Zustimmungen und bestandener Regelprüfung – dann atomar als neue Revision.
- **Anforderungen:** `LH-6.23-SCH-024`
- **Liefergegenstände:**
  - Backend: Aggregat Tausch/Übernahme (beantragt, vom Partner bestätigt, genehmigt, angewendet, abgelehnt, zurückgezogen, verfallen – Namen aus WP-M7d-01); Anwendung beider Dienste in einer Transaktion (Kap. 04 §4.4: „Tausch beide Dienste in einer Transaktion“) mit Regelprüfung für beide Personen; veröffentlicht neue `roster_revision`.
  - Datenbank: `shift_swap`; Sperre beider `staff_shift`-Zeilen in stabiler Reihenfolge.
  - API: `/v1/shift-swaps`.
  - Oberfläche: Fahrer-App/UI-13 – Tausch beantragen, annehmen, Status; UI-12 Dienstplan – Genehmigungsliste.
  - Ereignisse/Outbox: Statuswechsel an Beteiligte; angewendeter Tausch (Arbeitsname `shift.swap.applied`) speist WP-M7d-21.
  - Konfigurationsparameter: `shift_swap_approval` („Gesetzliche Kontrollen bleiben aktiv“).
- **Detailverträge vorab:** Zustandsautomat; welche Zustimmungen bei `shift_swap_approval=false` genügen; Verfall bei Schichtbeginn oder Planänderung.
- **Akzeptanzkriterien:**
  1. Ein abgelehnter Tausch ändert den veröffentlichten Plan nicht (SCH-024).
  2. Nur ein genehmigter, regelkonformer Tausch veröffentlicht eine neue Revision; beide Dienste wechseln gemeinsam oder gar nicht (AT-43).
  3. Ein Tausch, der für eine der beiden Personen Ruhezeit, Qualifikation, Abwesenheit oder Doppelbelegung verletzt, wird abgelehnt und nennt den Grund (AT-43).
  4. Bei `shift_swap_approval=false` entfällt die Planergenehmigung, die Regelprüfung nicht (SCH-024).
  5. Ändert sich eine der Schichten nach Antragstellung, ist der Antrag ungültig und muss neu gestellt werden (SCH-024).
  6. Die einseitige Übernahme einer Schicht folgt demselben Ablauf mit Regelprüfung für die übernehmende Person (SCH-024).
  7. Antragsteller, Tauschpartner und Planer werden über jeden Statuswechsel informiert; Antrag und Entscheidungen sind auditiert (SCH-024; AT-43).
- **Tests:** positiv: Tausch und einseitige Übernahme. negativ: Ablehnung durch Partner oder Planer, Regelverstoß, Antrag auf fremde Schicht. konkurrierend: zwei Tauschanträge auf dieselbe Schicht parallel genehmigt; Tausch parallel zu Planeränderung – genau ein konsistenter Endzustand. Besteht AT-43.
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-10, WP-M7d-21.
  - extern: WP-M4-15, WP-M4-07 (Veröffentlichungsrevisionen, Konfliktprüfung)
  - extern: WP-M1-13 (Idempotenz)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Tausch über Mandantengrenzen (nicht vorgesehen).

### WP-M7d-27 – Schichtbereitstellung setzt Check-in, Fahrzeugzuordnung und Vor-Schicht-Checkliste voraus

- **Ziel:** Eine veröffentlichte Schicht allein macht kein Fahrzeug verfügbar; Bereitstellung entsteht erst aus Check-in, gültiger Fahrzeugzuordnung und abgeschlossener Checkliste.
- **Anforderungen:** `LH-6.23-SCH-031`
- **Liefergegenstände:**
  - Backend: Bereitstellungsregel im Zusammenspiel `staff-time`↔`fleet-safety`: Disponierbarkeit ausschließlich über serverbestätigte `resource_release` (bindet `shift_id`, `inspection_ref`, `qualification_revision`, `safety_revision`); Veröffentlichung erzeugt keine `resource_release` (Kap. 05: „Eine Veröffentlichung erteilt noch keine fehlende technische Fahrzeugfreigabe“).
  - Datenbank: `resource_release`, `staff_shift`, `vehicle_run` (lesend/prüfend).
  - Oberfläche: UI-09 Driver Dienststart – fehlende Schritte; UI-12 Dienstplan – Status „veröffentlicht, nicht bereitgestellt“.
  - Konfigurationsparameter: `vehicle_readiness_require_checks`.
- **Detailverträge vorab:** Abgrenzung zu den M3/M4/M7c-Verträgen der Einsatzfreigabe; Verhalten der neuen Schichtarten (Bereitschaft, getauschte/übernommene Schicht) – Freigabe ist personen- und fahrzeugbezogen neu zu erwerben.
- **Akzeptanzkriterien:**
  1. Eine veröffentlichte Schicht ohne Check-in, Fahrzeugzuordnung oder Checkliste stellt das Fahrzeug nicht auf verfügbar (SCH-031).
  2. Erst wenn alle drei Voraussetzungen serverseitig erfüllt sind, wird die Schicht disponierbar (SCH-031).
  3. Nach Tausch, Übernahme oder Ersatzbesetzung gilt die Freigabe der vorherigen Person nicht weiter (SCH-031).
- **Tests:** positiv: vollständiger Dienststart. negativ: je eine fehlende Voraussetzung; Tausch nach erteilter Freigabe. konkurrierend: Check-Abschluss und Buchungszusage parallel – keine Zusage vor Freigabe. Regression AT-26.
- **Abhängigkeiten:** intern: WP-M7d-24, WP-M7d-26.
  - extern: WP-M3-08, WP-M3-09, WP-M3-13, WP-M3-14 (Pflichtprüfung, technische Einsatzfreigabe, UI-09)
  - extern: WP-M4-17 (Fahrerschicht, Fahrzeugbereitstellung und Disponierbarkeit)
  - extern: WP-M7c-21 (Vor-Schicht-Checkliste mit Freigabeworkflow)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Inhalt der Checklisten und Freigabeworkflow (M7c).

### WP-M7d-28 – Eigene veröffentlichte Schichten, Einsatzorte, Kontakte und Diensthinweise in der App

- **Ziel:** Mitarbeiter sehen in ihrer App ausschließlich die eigenen veröffentlichten Schichten samt Einsatzort, Kontakten und Diensthinweisen.
- **Anforderungen:** `LH-6.23-SCH-015`
- **Liefergegenstände:**
  - Backend: Leseprojektion „eigene Schichten“ nur aus veröffentlichter Revision, gefiltert auf die angemeldete Person; Diensthinweise und Kontakt je Schicht.
  - API: `/v1/rosters` (eigene Sicht, Kap. 05 §5.7 „Eigene vs Personalplaner-Sicht“).
  - Oberfläche: Fahrer-App (`mobile/driver`) – Liste und Detail der eigenen Schichten; UI-13 im Web.
- **Detailverträge vorab:** Feldliste der eigenen Sicht; welche „Kontakte“ gezeigt werden (dienstliche Kontaktstelle, keine privaten Daten von Kollegen – zu bestätigen).
- **Akzeptanzkriterien:**
  1. Fremde Personaldaten sind in der Fahreransicht nicht sichtbar – weder in der Oberfläche noch in der API-Antwort (SCH-015).
  2. Entwurfsschichten und Schichten anderer Personen sind nicht abrufbar, auch nicht über geratene IDs (SCH-015).
  3. Einsatzort, Kontakt und besondere Diensthinweise der eigenen veröffentlichten Schicht werden angezeigt (SCH-015).
- **Tests:** positiv: eigene Schichten. negativ: fremde Schicht-ID → 404 (gleichartig für fremd und nicht vorhanden, PH-AT-001), Entwurf unsichtbar, fremder Mandant, manipulierte Personen-ID im Aufruf, alte Revision eines fremden Mandanten (AT-61). konkurrierend: Abruf während Veröffentlichung – Antwort stammt vollständig aus genau einer Revision. Regression AT-37; Zuarbeit zu AT-61.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-14, WP-M4-20 (Veröffentlichung, UI-13 Grundausbau)
  - extern: WP-M3-24 (Fahrer-App-Grundgerüst und Anmeldung)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Offline-Cache (WP-M7d-29); vereinheitlichter Mitarbeiterbereich (WP-M7d-42).

### WP-M7d-29 – Schreibgeschützter Offline-Cache für Schichtpläne und Aufgaben in der Fahrer-App

- **Ziel:** Die Fahrer-App hält eigene Schichtpläne und Aufgaben schreibgeschützt offline vor, zeigt Revisionsalter und Offline-Kennzeichnung und aktualisiert nach Verbindungswiederkehr.
- **Anforderungen:** `LH-6.23-SCH-037`
- **Liefergegenstände:**
  - Oberfläche: Fahrer-App (`mobile/driver`) – verschlüsselter lokaler Cache der eigenen Sicht; Banner mit Zeit des letzten Plans (Kap. 06 §6.7) und Planrevision; keine Bearbeitung offline.
  - API: `/v1/rosters` eigene Sicht mit Revisionsnummer/ETag für bedingtes Nachladen.
  - Backend: keine neue Fachlogik.
- **Detailverträge vorab:** Cache-Umfang (Zeitraum), Verschlüsselung, Löschung bei Abmeldung/Gerätewechsel.
- **Akzeptanzkriterien:**
  1. Ohne Verbindung zeigt die App die zuletzt geladenen eigenen Schichten mit Revisionsalter und Offline-Kennzeichnung (SCH-037).
  2. Nach Verbindungswiederkehr wird auf die aktuelle Revision aktualisiert; eine zwischenzeitlich geänderte Schicht wird als geändert kenntlich (SCH-037).
  3. Offline sind keine Planänderungen, Bewerbungen oder Tauschbestätigungen als verbindlich darstellbar (SCH-037).
  4. Nach Abmeldung liegen keine Plandaten mehr lesbar auf dem Gerät (SCH-037).
  5. Der Cache enthält neben den eigenen Schichten die zugehörigen Aufgaben schreibgeschützt; meldet sich eine andere Person am selben Gerät an, sind die Daten der vorherigen Person nicht lesbar (SCH-037; Umfang von „Aufgaben“ siehe Offene Punkte).
- **Tests:** positiv: Flugmodus, Wiederverbindung. negativ: veraltete Revision wird nicht als aktuell dargestellt; Cache enthält keine fremden Daten; Benutzerwechsel am Gerät; Offline-Aktion auf alter Planrevision wird nach Wiederverbindung nicht als verbindlich übernommen (Kap. 05 §5.8).
- **Abhängigkeiten:** intern: WP-M7d-28.
  - extern: WP-M3-24 (Fahrer-App mit verschlüsselter SQLite-Outbox)
  - extern: WP-M7c-07, WP-M7c-08 (Offline-Vorhaltung und konfliktbewusste Synchronisation)
- **Entscheidungen:** D-11: Offline dürfen Fahrer letzte bestätigte Aufträge sehen – deckt den Lesecache; blockiert nicht.
- **Größe:** M
- **Nicht im Umfang:** Offline-Stempeln (M4, WP-M4-12); Offline-Prüfungen (M7c).

### WP-M7d-30 – Export von Schichtplänen als PDF/CSV und persönlicher Kalenderfeed

- **Ziel:** Planer exportieren Schichtpläne als PDF/CSV; Mitarbeiter abonnieren einen persönlichen Kalenderfeed mit eingeschränkten Informationen.
- **Anforderungen:** `LH-6.23-SCH-030`
- **Liefergegenstände:**
  - Backend: Exportdienst über `export_job`; Kalenderfeed (iCalendar) je Person mit widerrufbarem, nicht erratbarem Zugriffstoken; Feed enthält nur eigene veröffentlichte Schichten mit reduzierten Feldern.
  - API: Exportauftrag und Feed-Verwaltung unter `/v1/rosters` (gemäß WP-M7d-01); Download über signierten, kurzlebigen Link.
  - Oberfläche: UI-12 Dienstplan – Export; UI-13 Eigene Zeit/Abwesenheit – Feed erzeugen/widerrufen.
- **Detailverträge vorab:** Feldliste je Exportart; Zeitdarstellung (lokale Zeit mit Zone, UTC im Feed); Token-Lebensdauer und Widerruf.
- **Akzeptanzkriterien:**
  1. PDF-, CSV- und Feed-Export enthalten korrekte Uhrzeiten einschließlich Nachtschicht und DST-Tag (SCH-030).
  2. Der persönliche Feed enthält keine fremden Personaldaten; der Planerexport nur Bereiche, für die der Planer berechtigt ist (SCH-030).
  3. Ein widerrufenes Feed-Token liefert keine Daten mehr (SCH-030).
  4. Exporte beruhen auf der veröffentlichten Revision und nennen diese (SCH-030).
  5. Jeder Export und jede Feed-Erzeugung bzw. jeder Widerruf ist mit Person, Zeitpunkt und Umfang auditiert; das Feed-Token steht nicht im Klartext in Datenbank oder Protokollen (SCH-030).
- **Tests:** positiv: drei Exportarten mit Sollwertvergleich. negativ: fremdes Token, widerrufenes Token, Export ohne Bereichsrecht. Regression AT-47 (Zeitdarstellung).
- **Abhängigkeiten:** intern: WP-M7d-07, WP-M7d-28.
  - extern: WP-M5-19, WP-M1-11 (Exportgrundlagen (`export_job`), signierte Downloads)
  - extern: WP-M1-46 (Audit)
- **Entscheidungen:** D-11: Exportprozess personenbezogener Daten – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Lohnexport (WP-M7d-38).

### WP-M7d-31 – Mitarbeiterübersicht: Soll, zugewiesen, Fehlzeiten, Über-/Unterdeckung, offene Bedarfe

- **Ziel:** Eine Übersicht zeigt je Mitarbeiter und Zeitraum Sollstunden, zugewiesene Stunden, Fehlzeiten, Über-/Unterdeckung sowie offene Bedarfe.
- **Anforderungen:** `LH-6.23-SCH-027`
- **Liefergegenstände:**
  - Backend: Leseprojektion aus veröffentlichter `roster_revision`, Beschäftigungsmodell (Sollstunden) und genehmigten `absence_request`.
  - API: `/v1/rosters` (Auswertungssicht nach Zeitraum und Bereich).
  - Oberfläche: UI-12 Dienstplan – Mitarbeiterübersicht mit Zeitraumwahl.
  - Kennzahlen: `Schicht-Abdeckungsquote` (besetzte veröffentlichte Schichtstunden / geplante Schichtstunden).
- **Detailverträge vorab:** Berechnungsvorschrift Sollstunden je Modell und Zeitraum; Behandlung von Bereitschaft und Pausen in „zugewiesenen Stunden“.
- **Akzeptanzkriterien:**
  1. Die Wochenwerte stimmen mit der veröffentlichten Planung überein (Nachrechnung aus Einzelschichten) (SCH-027).
  2. Entwurfsänderungen verändern die Werte erst nach Veröffentlichung bzw. sind getrennt als Entwurf ausgewiesen (SCH-027).
  3. Fehlzeiten erscheinen als Stunden ohne Diagnose; offene Bedarfe werden je Zeitraum summiert (SCH-027).
- **Tests:** positiv: Sollwertvergleich mit Fixturewoche, Teilzeit/Vollzeit. negativ: Planer sieht keine Mitarbeiter fremder Bereiche.
- **Abhängigkeiten:** intern: WP-M7d-07, WP-M7d-09, WP-M7d-14, WP-M7d-19.
  - extern: WP-M4-46 (Kennzahlenrahmen (`metric_definition_revision`, `aggregate_snapshot`))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ist-Stunden und Zeitkonten (WP-M7d-35); Berichte über Standorte (WP-M7d-39).

### WP-M7d-32 – Unverbindlicher Personalbedarfsvorschlag aus Nachfrage, Umlaufplanung und Servicefenstern

- **Ziel:** Das System schlägt Personalbedarf vor und weist Quelle, Zeitraum und Unsicherheit aus; nichts wird ohne Planerfreigabe verbindlich.
- **Anforderungen:** `LH-6.23-SCH-028`
- **Liefergegenstände:**
  - Backend: Vorschlagsdienst, der Bedarfslinien aus Nachfragedaten, Fahrzeugumlaufplanung und Servicefenstern ableitet; Vorschläge als eigener, als unverbindlich markierter Objekttyp, nicht als Schicht.
  - API: `/v1/rosters` (Vorschläge lesen, übernehmen, verwerfen).
  - Oberfläche: UI-12 Dienstplan – Vorschlagsebene mit Quelle/Zeitraum/Unsicherheit, Aktion „in Entwurf übernehmen“.
- **Detailverträge vorab:** Eingangsdaten und deren Vertrag (Nachfrageprognose stammt aus M9 – bis dahin historische Nachfrage und Servicefenster); Darstellungsform der Unsicherheit.
- **Akzeptanzkriterien:**
  1. Jeder Bedarfsvorschlag weist Quelle, Zeitraum und Unsicherheit aus (SCH-028).
  2. Ein Vorschlag erzeugt ohne Planeraktion weder Entwurfsschicht noch Angebot noch Mitteilung (SCH-028).
  3. Ein übernommener Vorschlag wird erst nach regulärer Veröffentlichung verbindlich (AT-55).
- **Tests:** positiv: Vorschlag übernehmen. negativ: kein automatisches Verbindlichwerden; fehlende Eingangsdaten führen zu „kein Vorschlag“ statt zu Scheinwerten. Zuarbeit zu AT-55.
- **Abhängigkeiten:** intern: WP-M7d-04, WP-M7d-07.
  - extern: WP-M4-45, WP-M4-47, WP-M4-51 (Kennzahlen-/Nachfragedaten)
  - extern: WP-M2-03, WP-M2-16 (Servicefenster und Umlaufdaten (Leseport))
  - extern: WP-M9a-06 (Nachfrageprognose für vollständiges AT-55 (Gate M9)) – Reihenfolgekonflikt, siehe Offene Punkte 35
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Prognosemodell selbst (ANA/AI, spätere Meilensteine).

### WP-M7d-33 – Zeitkorrekturanträge mit Prüfung und Alt-/Neuwert

- **Ziel:** Mitarbeiter beantragen Zeitkorrekturen; erst die Genehmigung einer berechtigten Rolle ändert die wirksame Ist-Zeit, mit dokumentiertem altem und neuem Wert.
- **Anforderungen:** `LH-6.24-TIM-007`
- **Liefergegenstände:**
  - Backend: Aggregat Zeitkorrektur (beantragt, genehmigt, abgelehnt, zurückgezogen); Genehmigung erzeugt korrigierendes Zeitereignis mit Bezug auf das Original; Original bleibt unverändert erhalten.
  - Datenbank: `time_correction` (Korrekturbezug, Genehmigung, alter/neuer Wert, Begründung), `time_event`.
  - API: `/v1/time-corrections`.
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – Korrekturantrag, Zustand „fehlende Buchung“, „offene Genehmigung“; Prüfliste für Vorgesetzte.
  - Ereignisse/Outbox: Arbeitsname `time.correction.applied`; Mitteilung an Antragsteller.
- **Detailverträge vorab:** Zustandsautomat; Darstellung der wirksamen Ist-Zeit als Projektion aus Original + genehmigten Korrekturen.
- **Akzeptanzkriterien:**
  1. Eine beantragte, aber nicht genehmigte Korrektur verändert die Ist-Zeit nicht (TIM-007).
  2. Nach Genehmigung sind alter Wert, neuer Wert, Antragsteller, Prüfer, Zeitpunkt und Begründung abrufbar (TIM-007).
  3. Abwechselndes Ablehnen und Genehmigen mehrerer Anträge hinterlässt eine vollständige Historie; Ist-Zeiten ändern sich nur bei Genehmigung (AT-48).
  4. Ein Antrag für einen fehlenden Stempel (nachträgliche Buchung) folgt demselben Ablauf (TIM-007).
- **Tests:** positiv: Antrag und Genehmigung. negativ: Ablehnung, Antrag auf fremde Zeiten, doppelte Genehmigung (idempotent), gleicher `Idempotency-Key` mit anderem Neuwert (PH-AT-011), Direktänderung an `time_event` ohne Antrag. konkurrierend: zwei Prüfer entscheiden parallel – genau eine Entscheidung wirkt. Besteht AT-48; Zuarbeit zu AT-46 (Zeitkonflikt nach Offline-Synchronisation führt in den Korrekturpfad).
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-08, WP-M4-12, WP-M4-20 (Ist-Arbeitszeit als Zeitereignisse, Offline-Stempel, UI-13)
  - extern: WP-M1-46 (Audit)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Rollentrennung und Selbstfreigabeverbot (WP-M7d-34); Korrektur in gesperrter Periode (WP-M7d-37).

### WP-M7d-34 – Getrennte Rollenrechte und Audit für Korrektur, Freigabe und Zugriff

- **Ziel:** Beantragen, Freigeben und Einsehen von Zeitdaten sind getrennte Rechte; jeder Vorgang ist auditierbar und niemand gibt unbefugt fremde Zeitkonten frei.
- **Anforderungen:** `LH-6.24-TIM-014`
- **Liefergegenstände:**
  - Backend: Scopes/Rechte getrennt für Antrag, Freigabe und Lesezugriff; Bereichsbindung der Freigaberolle; Regel gegen Selbstfreigabe; Auditeinträge auch für lesende Zugriffe auf fremde Zeitkonten.
  - Datenbank: Audit (unveränderlich, Kap. 04 §4.5); Spalten-/Viewrechte des HR-Modulzugangs.
  - API: `/v1/time-corrections`, `/v1/time-events`, `/v1/absences` – 403 bei fehlendem Recht.
  - Oberfläche: UI-20 Datenschutz/Audit/Support – Auditsicht auf Zeitvorgänge; UI-13 – Aktionen nur gemäß Recht.
- **Detailverträge vorab:** Rollen-/Rechtematrix Zeitwirtschaft (Mitarbeiter, Vorgesetzter, Personalrolle, Lohnrolle, Auditor).
- **Akzeptanzkriterien:**
  1. Ein Mitarbeiter kann seinen eigenen Antrag stellen, aber kein fremdes Zeitkonto freigeben (TIM-014).
  2. Ein Mitarbeiter mit Freigaberecht kann den eigenen Antrag nicht selbst freigeben (TIM-014; Auslegung, siehe Offene Punkte).
  3. Jeder Korrektur-, Freigabe- und Zugriffsvorgang ist mit Person, Zeit, Objekt und Ergebnis im Audit auffindbar (TIM-014).
  4. Eine Freigaberolle außerhalb ihres Bereichs oder Mandanten erhält eine Ablehnung, die protokolliert wird (TIM-014).
- **Tests:** positiv: Freigabe durch zuständige Rolle. negativ: Fremdfreigabe, Selbstfreigabe, bereichsfremde Freigabe, Cross-Tenant (Arbeitszeit eines Fremdmandanten lesen, AT-49), Versuch, einen Auditeintrag zu ändern oder zu löschen. konkurrierend: Rechteentzug parallel zur Freigabe – keine Freigabe nach wirksamem Entzug. Zuarbeit zu AT-49, E2E-079, E2E-091 und PH-AT-001.
- **Abhängigkeiten:** intern: WP-M7d-33.
  - extern: WP-M1-07, WP-M1-46, WP-M1-12 (Rollen/Rechte, Audit, UI-20-Grundlagen)
- **Entscheidungen:** D-11: zulässiger Supportzugriff – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Allgemeine Auditoberfläche (M1/M8).

### WP-M7d-35 – Zeitkonten: Soll/Ist, Überstunden, Minusstunden mit offengelegter Rundungs- und Pausenlogik

- **Ziel:** Zeitkonten werden aus Einzelstempeln nachvollziehbar berechnet; Rundungs- und Pausenregeln sind sichtbar und versioniert.
- **Anforderungen:** `LH-6.24-TIM-011`
- **Liefergegenstände:**
  - Backend: Zeitkontenrechner (Tages-/Wochenabschluss) aus `time_event`, genehmigten `time_correction`, `staff_shift` (Soll) und `absence_request`; Buchung als `time_account_entry` mit Verweis auf Regelversion und Quellereignisse.
  - Datenbank: `time_account_entry` (nur Anfügen, Gegenbuchung bei Neuberechnung).
  - API: `/v1/time-events` (Soll/Ist- und Kontosicht mit Rechenweg).
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – Saldo mit aufklappbarem Rechenweg; Personalsicht.
  - Konfigurationsparameter: `max_daily_work_minutes`, `reporting_timezone`; Rundungs- und Pausenregel sind nicht in kataloge.csv (siehe Offene Punkte).
- **Detailverträge vorab:** Rundungsregel, Pausenabzugsregel, Tageszuordnung bei Nachtschicht – fachlich freizugeben; Pflichtenheft nennt keine Werte.
- **Akzeptanzkriterien:**
  1. Die Wochenabrechnung ist anhand der Einzelstempel nachrechenbar; die Oberfläche zeigt je Tag Stempel, angewandte Rundung und Pausenabzug (TIM-011).
  2. Überstunden und Minusstunden ergeben sich aus Ist minus Soll gemäß Beschäftigungsmodell (TIM-011).
  3. Eine nachträglich genehmigte Korrektur führt zu einer nachvollziehbaren Gegen-/Neubuchung, nicht zu einer stillen Änderung (TIM-011).
  4. DST-Nacht und Schicht über Mitternacht liefern die Sollwerte der Fixturetabelle (TIM-011).
- **Tests:** positiv: Wochenabrechnung mit Sollwerttabelle. negativ: fehlender Ausstempel führt zu Klärfall statt Schätzwert. konkurrierend: Korrekturgenehmigung während Wochenabschluss – Konto konsistent. Regression AT-45, AT-47.
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-11, WP-M7d-19, WP-M7d-33.
  - extern: WP-M4-09, WP-M4-10 (Trennung Soll/Ist, Zeitrechnung DST)
- **Entscheidungen:** keine D-Entscheidung; Rundungs-/Pausenregel braucht fachliche Freigabe (blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Urlaubskonto (WP-M7d-36); Lohnbewertung.

### WP-M7d-36 – Urlaubsansprüche, genommene und verfügbare Tage nach Arbeitszeitmodell

- **Ziel:** Urlaubsanspruch, genommene und verfügbare Tage werden je Mitarbeiter nach konfiguriertem Arbeitszeitmodell reproduzierbar berechnet.
- **Anforderungen:** `LH-6.24-TIM-009`
- **Liefergegenstände:**
  - Backend: Urlaubsrechner (Anspruch je Modell und Zeitraum, anteilig bei Ein-/Austritt und Modellwechsel); Verbrauch aus genehmigten `absence_request` der Art Urlaub.
  - Datenbank: `time_account_entry` (Kontoart Urlaub) mit Regelversion; Beschäftigungsmodell.
  - API: `/v1/absences` (Saldoabfrage).
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – Urlaubssaldo; Antragsdialog zeigt verbleibende Tage.
- **Detailverträge vorab:** Berechnungsformel je Modell (Tage/Stunden, Teilzeitverteilung, Übertrag, Verfall) – im Pflichtenheft nicht festgelegt.
- **Akzeptanzkriterien:**
  1. Die Prüfrechnung für ein Teilzeit- und ein Vollzeitmodell ist reproduzierbar: gleiche Eingaben und Regelversion liefern gleiche Werte, der Rechenweg ist einsehbar (TIM-009).
  2. Genehmigter Urlaub mindert die verfügbaren Tage; Ablehnung oder Stornierung stellt sie wieder her (TIM-009).
  3. Ein Antrag über den verfügbaren Anspruch hinaus wird gekennzeichnet (TIM-009; Sperre oder Hinweis siehe Offene Punkte).
  4. Der Urlaubssaldo ist nur für die eigene Person und die berechtigte Personalrolle lesbar; jede Buchung auf dem Urlaubskonto trägt Regelversion und Quellantrag (TIM-009).
- **Tests:** positiv: Vollzeit/Teilzeit-Sollwerte. negativ: Stornierung, Modellwechsel im Jahr. konkurrierend: zwei parallele Genehmigungen gegen denselben Restanspruch – Saldo wird nicht doppelt belastet.
- **Abhängigkeiten:** intern: WP-M7d-14, WP-M7d-35.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Tarifliche Sonderurlaube und Verfallsfristen ohne Fachvorgabe.

### WP-M7d-37 – Periodenabschluss der Zeitdaten und Bereitstellung für die Lohnabrechnung

- **Ziel:** Zeitdaten eines Zeitraums werden nach autorisierter Prüfung abgeschlossen und bereitgestellt; danach sind Änderungen nur über einen dokumentierten Korrekturprozess möglich.
- **Anforderungen:** `LH-6.24-TIM-012`
- **Liefergegenstände:**
  - Backend: Abrechnungsperiode (offen, in Prüfung, abgeschlossen, bereitgestellt – Namen aus WP-M7d-01); Schreibsperre für `time_event`-Korrekturen und `time_account_entry` der Periode; Korrekturprozess erzeugt Nachtragsbuchung mit Verweis und Begründung.
  - Datenbank: Periodentabelle (Arbeitsname aus WP-M7d-01); Trigger/Prüfung „keine Buchung in gesperrter Periode ohne Korrekturvorgang“.
  - API: Periodenbefehle im Modul `staff-time` (Pfad in WP-M7d-01 festzulegen; Kap. 05 §5.7 nennt keinen).
  - Oberfläche: Personalsicht – Prüfliste offener Klärfälle, Abschluss, Korrekturvorgang.
- **Detailverträge vorab:** Periodenlänge und Abschlussrolle; Verhältnis zu `finance_close_day` (Finanzperiode, M4) – eigener Parameter fehlt in kataloge.csv.
- **Akzeptanzkriterien:**
  1. Ein gesperrter Abrechnungsmonat kann nur über den dokumentierten Korrekturprozess geändert werden; direkte Änderungen werden abgelehnt (TIM-012).
  2. Der Abschluss ist nur einer berechtigten Rolle möglich und setzt voraus, dass keine offenen Korrekturanträge der Periode bestehen (TIM-012; Auslegung, siehe Offene Punkte).
  3. Jede Korrektur nach Abschluss nennt Anlass, Urheber, Freigeber und ursprünglichen Wert (TIM-012).
  4. Nach Abschluss stehen die Daten unverändert für den Export bereit (TIM-012).
  5. Abschluss, Bereitstellung und jeder Korrekturvorgang sind auditiert; ein doppelt ausgelöster Abschluss wirkt genau einmal (TIM-012).
- **Tests:** positiv: Abschluss und Nachtragskorrektur. negativ: Direktänderung, Abschluss durch Unbefugten, verspäteter Offline-Stempel in gesperrter Periode wird Klärfall (Vertragsfall „geschlossene Periode“, Kap. 05 §5.8); genehmigte Zeitkorrektur aus WP-M7d-33 für einen Tag der gesperrten Periode ändert diese nicht direkt. konkurrierend: Abschluss und Korrekturgenehmigung parallel – Korrektur liegt eindeutig vor oder nach dem Abschluss.
- **Abhängigkeiten:** intern: WP-M7d-33, WP-M7d-34, WP-M7d-35.
  - extern: WP-M4-12 (Offline-Stempel mit einmaliger Übernahme)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Exportformate (WP-M7d-38); Finanzperiodenabschluss (M4).

### WP-M7d-38 – CSV- und Lohn-/DATEV-Exportadapter

- **Ziel:** Abgeschlossene Zeitdaten werden als CSV und optional über einen Lohn-/DATEV-konformen Adapter exportiert; Stundenarten, Kostenstellen und Mitarbeiteridentifikatoren sind transparent abgebildet.
- **Anforderungen:** `LH-6.24-TIM-013`
- **Liefergegenstände:**
  - Backend: Exportauftrag über `export_job` aus abgeschlossener Periode; Abbildungstabelle Stundenart/Kostenstelle/Personalnummer; Exportprotokoll mit Summen und Zeilenanzahl.
  - Adapter/Worker: CSV-Exporter im Modul; optionaler Lohnadapter nach dem Muster des Adapters `erp-export`.
  - Datenbank: `export_job`; Abbildungsstammdaten (Arbeitsname aus WP-M7d-01).
  - Oberfläche: Personalsicht – Export starten, Abbildung pflegen, Protokoll einsehen.
- **Detailverträge vorab:** CSV-Spaltenvertrag; Ziel-Lohnformat (konkrete DATEV-Schnittstelle im Pflichtenheft nicht benannt, siehe Offene Punkte); Katalog der Stundenarten.
- **Akzeptanzkriterien:**
  1. Ein Export-Import-Test mit repräsentativen Mitarbeitern (Voll-/Teilzeit, Nacht-/Feiertagsstunden, Abwesenheit, Korrektur) zeigt identische Summen und keine fehlenden Zeilen (TIM-013).
  2. Nicht abbildbare Stundenarten oder fehlende Kostenstellen brechen den Export mit Fehlerliste ab, statt Daten wegzulassen (TIM-013).
  3. Der Export ist nur aus abgeschlossener Periode und nur für die Lohnrolle möglich; jeder Export ist auditiert (TIM-013).
  4. Wiederholter Export derselben Periode liefert denselben Inhalt oder weist Nachtragskorrekturen getrennt aus (TIM-013).
- **Tests:** positiv: Export-Import-Rundlauf. negativ: fehlende Abbildung, offene Periode, unbefugte Rolle, fremder Mandant, Download über abgelaufenen Link. konkurrierend: Export parallel zu einer Nachtragskorrektur – Export enthält einen eindeutigen Stand und nennt ihn; doppelt gestarteter Export erzeugt einen Auftrag. Zuarbeit zu AT-53 (Stundenabrechnung exportieren).
- **Abhängigkeiten:** intern: WP-M7d-11, WP-M7d-37.
  - extern: WP-M4-37 (Exportgrundlagen und Adapter `erp-export`)
  - extern: WP-M1-09, WP-M4-29 (Kostenstellen-/ERP-Stammdaten, falls nicht aus M4 vorhanden)
- **Entscheidungen:** D-06 (Finanzumfang und ERP-Anbindung): Wahl des Lohnzielformats – blockiert den optionalen Lohnadapter, nicht den CSV-Export. D-11: Exportprozess – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Lohnberechnung selbst; bidirektionale Schnittstelle.

### WP-M7d-39 – Personal- und Arbeitszeitberichte über Standorte und Perioden

- **Ziel:** Berichte weisen geplante Stunden, erfasste Stunden, Fehlzeiten, Überstunden und Compliance-Warnungen je Standort und Periode konsistent aus.
- **Anforderungen:** `LH-6.24-TIM-015`
- **Liefergegenstände:**
  - Backend: Berichtsprojektion im Modul `analytics` aus `staff-time`-Ereignissen; Definition über `metric_definition_revision`, Datenstand über `dataset_watermark`.
  - Oberfläche: UI-19 Kennzahlen/Forecast/Simulation bzw. Personalsicht – Bericht mit Filter Standort/Periode; Export.
  - Kennzahlen: `Schicht-Abdeckungsquote`.
  - Konfigurationsparameter: `reporting_timezone`, `kpi_aggregation_min_n` (Mindestgruppengröße).
- **Detailverträge vorab:** Berichtsdefinitionen (Zähler/Nenner, Zeitbasis); Geltung von `kpi_aggregation_min_n` für Personalberichte.
- **Akzeptanzkriterien:**
  1. Ein Bericht über zwei Standorte und eine Monatsperiode ist konsistent: Summen der Standorte ergeben den Gesamtwert und stimmen mit Dienstplan (WP-M7d-31) und Zeitkonten (WP-M7d-35) überein (TIM-015).
  2. Der Bericht weist geplante Stunden, erfasste Stunden, Fehlzeiten, Überstunden und Compliance-Warnungen getrennt aus (TIM-015).
  3. Ein Nutzer sieht nur Standorte seines Berechtigungsbereichs; Fehlzeiten enthalten keine Diagnosen (TIM-015).
  4. Gruppen unterhalb von `kpi_aggregation_min_n` werden nicht ausgewiesen, auch nicht durch Differenzbildung benachbarter Filter; der Bericht nennt Datenstand und Definition (TIM-015; Muster PH-AT-021).
- **Tests:** positiv: Zwei-Standorte-Monat gegen Fixture-Sollwerte. negativ: fehlendes Standortrecht, unvollständiger Datenstand wird ausgewiesen.
- **Abhängigkeiten:** intern: WP-M7d-09, WP-M7d-31, WP-M7d-35.
  - extern: WP-M4-46, WP-M4-58 (Kennzahlenrahmen und Berichtsfilter ohne Mandantenüberschreitung)
- **Entscheidungen:** D-10: keine Leistungsranglisten einzelner Fahrer – begrenzt den Berichtszuschnitt; blockiert nur Produktivschaltung personenbezogener Auswertungen.
- **Größe:** M
- **Nicht im Umfang:** Leistungsvergleich einzelner Mitarbeiter (ausgeschlossen, D-10).

### WP-M7d-40 – Standortgestütztes Stempeln nur mit Freigabe

- **Ziel:** Standortdaten beim Stempeln werden nur verarbeitet, wenn die rechtliche und organisatorische Freigabe hinterlegt ist; Standard ist aus, ohne Dauerüberwachung.
- **Anforderungen:** `LH-6.24-TIM-017`
- **Liefergegenstände:**
  - Backend: Freigabegate für die Geozonenprüfung beim Stempeln; ohne Freigabe werden Standortfelder serverseitig verworfen und nicht gespeichert; mit Freigabe nur punktuelle Prüfung zum Stempelzeitpunkt.
  - Datenbank: Standortfelder an `time_event` nur bei aktiver Freigabe befüllt; Freigabenachweis (wer, wann, Grundlage).
  - Konfigurationsparameter: `clock_in_geofence_enabled` (Geltungsebene Standort; „Nur mit legitimer Zweckbindung“), Default aus.
  - Oberfläche: Fahrer-App – Hinweis, ob Standort beim Stempeln verwendet wird; Konfigurationseditor zeigt fehlende Freigabe und bleibt deaktiviert.
- **Detailverträge vorab:** Form des Freigabenachweises; gespeicherter Umfang bei aktiver Freigabe (Ergebnis „in Zone“ oder Koordinate – nicht festgelegt, siehe Offene Punkte).
- **Akzeptanzkriterien:**
  1. Ohne Freigabe werden durch das Stempeln keine GPS-Spuren gespeichert – auch nicht, wenn die App Koordinaten mitsendet (TIM-017).
  2. `clock_in_geofence_enabled` lässt sich ohne hinterlegte Freigabe nicht aktivieren (TIM-017).
  3. Auch mit Freigabe entsteht keine kontinuierliche Standortaufzeichnung aus der Zeiterfassung (TIM-017).
  4. Der Standardwert neuer Mandanten und Standorte ist „aus“ (TIM-017).
  5. Erteilung und Entzug der Freigabe sowie jede Aktivierung von `clock_in_geofence_enabled` sind auditiert; die Fahrer-App fordert ohne Freigabe keine Standortberechtigung für das Stempeln an (TIM-017).
- **Tests:** positiv: Stempeln mit Freigabe in Zone. negativ: Koordinaten im Aufruf ohne Freigabe werden nicht persistiert (Datenbank- und Logprüfung); Aktivierung ohne Freigabe. Offline gestempelte Ereignisse mit Koordinaten werden bei der Synchronisation gleich behandelt (kein Speichern ohne Freigabe); Entzug der Freigabe stoppt die Verarbeitung sofort. Besteht den Stempelanteil von AT-59.
- **Abhängigkeiten:** intern: WP-M7d-02, WP-M7d-03.
  - extern: WP-M4-11 (Stempeln in der Fahrer-App)
  - extern: WP-M1-19, WP-M1-22 (Konfigurationsdienst mit Freigabegate)
  - extern: WP-M7c-27 (EXIF-Minimierung für den Medienanteil von AT-59)
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung): Beteiligung Betriebsrat/Datenschutzprüfung – blockiert Produktivschaltung der Geozone, nicht den Bau.
- **Größe:** S
- **Nicht im Umfang:** Fahrzeugortung im Livebetrieb (M3).

### WP-M7d-41 – Aufbewahrung, Löschung, Auskunft und Export personenbezogener Arbeitszeitdaten

- **Ziel:** Arbeitszeitdaten folgen einem dokumentierten Lösch- und Nachweiskonzept: Pflichtaufzeichnungen bleiben erhalten, verfallene Datensätze werden regelkonform entfernt, Auskunft und Export sind möglich.
- **Anforderungen:** `LH-6.24-TIM-019`
- **Liefergegenstände:**
  - Backend: Löschklassen für `time_event`, `time_correction`, `absence_request`, `time_account_entry`, Erinnerungen und Standortfelder; Anbindung an `retention_rule` und `erasure_job`; Legal Hold; Auskunfts-/Exportpaket je Mitarbeiter über `export_job`.
  - Datenbank: `retention_rule`, `erasure_job`, `export_job`; Löschung nur über kontrollierten Lifecycleprozess (Kap. 04 §4.5).
  - Oberfläche: UI-20 Datenschutz/Audit/Support – Auskunftsfall, Löschlauf-Protokoll, Zustand „Legal Hold“.
  - Adapter/Worker: zeitgesteuerter Löschlauf.
  - Liefergegenstand Dokument: Lösch- und Nachweiskonzept Arbeitszeitdaten.
- **Detailverträge vorab:** Löschmatrix mit Fristen je Datenart (Fristen sind Beschlussgegenstand von D-11; im Pflichtenheft nicht beziffert).
- **Akzeptanzkriterien:**
  1. Rechtlich erforderliche Aufzeichnungen bleiben über ihre Frist erhalten und sind vor Löschläufen geschützt (TIM-019).
  2. Verfallene Datensätze werden vom Löschlauf entfernt; der Lauf ist protokolliert und wiederholbar ohne Nebenwirkung (TIM-019).
  3. Ein Datensatz unter Legal Hold wird nicht gelöscht (TIM-019).
  4. Eine Auskunft liefert einem Mitarbeiter seine Arbeitszeitdaten vollständig und ohne Daten Dritter (TIM-019).
  5. Das Lösch- und Nachweiskonzept liegt dokumentiert vor und stimmt mit den konfigurierten Regeln überein (TIM-019).
  6. Auskunft und Export sind nur für die betroffene Person bzw. die berechtigte Datenschutzrolle auslösbar, auditiert und über einen kurzlebigen signierten Link abrufbar; gelöschte Datensätze verschwinden auch aus Offline-Cache-Nachladungen, Berichtsprojektionen und Exportabbildern (TIM-019).
- **Tests:** positiv: Löschlauf mit Testuhr, Auskunftsexport. negativ: Löschung in abgeschlossener, aufbewahrungspflichtiger Periode; Legal Hold; fremder Mandant. konkurrierend: Löschlauf während Auskunftsexport – Export konsistent; zwei parallele Löschläufe – jeder Datensatz genau einmal behandelt. Zuarbeit zu AT-53 (Archiv- und Löschregeln der Stundenabrechnung).
- **Abhängigkeiten:** intern: WP-M7d-33, WP-M7d-35, WP-M7d-37, WP-M7d-40.
  - extern: WP-M1-33, WP-M1-11, WP-M1-12, WP-M5-19 (Lösch-/Exportrahmen (`retention_rule`, `erasure_job`, `export_job`), UI-20)
  - extern: WP-M8b-23 (Selbstverwaltung der Löschregeln (CFG), falls dort verortet)
- **Entscheidungen:** D-11 (Aufbewahrung, Datenrechte): Löschfristen, Legal Holds, Auskunftsprozess – blockiert Produktivschaltung; Bau mit parametrierten Platzhalterfristen möglich.
- **Größe:** M
- **Nicht im Umfang:** Löschung anderer Datenklassen (Fahrgast, Finanzen).

### WP-M7d-42 – Persönlicher Mitarbeiterbereich: Schichten, Zeiten, Urlaubssaldo, Korrekturanfragen

- **Ziel:** Mitarbeiter sehen in einer vereinheitlichten Ansicht ihre veröffentlichten Schichten, Zeiten, den Urlaubssaldo und ihre Korrekturanfragen – und nichts von anderen.
- **Anforderungen:** `LH-6.24-TIM-020`
- **Liefergegenstände:**
  - Oberfläche: UI-13 Eigene Zeit/Abwesenheit – Übersichtsseite im Web und in der Fahrer-App mit Kacheln Schichten, Zeiten/Saldo, Urlaub, Anträge (Kap. 06 §6.8: „Mitarbeiter sehen eigenen Plan, Zeitstempel, Korrekturantrag und Saldo“).
  - Backend: zusammengeführte Leseprojektion „mein Bereich“ ausschließlich für die angemeldete Person.
  - API: eigene Sichten von `/v1/rosters`, `/v1/time-events`, `/v1/time-corrections`, `/v1/absences`.
- **Detailverträge vorab:** Seitenaufbau und Leer-/Fehlerzustände (Konflikt, offene Genehmigung, fehlende Buchung).
- **Akzeptanzkriterien:**
  1. Der Fahrer kann alle eigenen Daten (veröffentlichte Schichten, Zeiten, Urlaubssaldo, Korrekturanfragen) in einer Ansicht lesen (TIM-020).
  2. Fremde Personalakten, Zeiten oder Anträge sind weder in der Ansicht noch per API abrufbar (TIM-020).
  3. Planzeit überschreibt in der Ansicht keine Ist-Zeit; beide sind getrennt ausgewiesen (TIM-020).
  4. Die Ansicht zeigt Leer-, Fehler- und Offline-Zustand je Kachel; ein Teilausfall einer Quelle blendet nicht die übrigen eigenen Daten aus und zeigt keine veralteten Werte ohne Kennzeichnung (TIM-020).
- **Tests:** positiv: Gesamtansicht mit Fixture-Fahrer. negativ: Zugriff mit fremder Personen-ID, fremder Mandant. Zuarbeit zu E2E-079.
- **Abhängigkeiten:** intern: WP-M7d-28, WP-M7d-33, WP-M7d-35, WP-M7d-36.
  - extern: WP-M4-20 (UI-13 Grundausbau)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Offline-Cache (WP-M7d-29); Personalakte.

### WP-M7d-43 – Gate-Abnahme M7d

- **Ziel:** Die Gate-Tests des Meilensteins M7, die M7d verantwortet, laufen automatisiert und reproduzierbar in der Abnahmeumgebung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: automatisierte Abnahmesuite für AT-38, AT-42, AT-43, AT-44, AT-46, AT-52, AT-56, AT-63 sowie Regression AT-37, AT-39, AT-40, AT-41, AT-45, AT-47, AT-48; Wiederholung der M5-Gate-Tests AT-49, AT-53, AT-54, AT-59 und AT-61 für die M7d-Erweiterungen; Schwerpunktprüfungen PH-AT-001 (neue Tabellen und Pfade), PH-AT-011, PH-AT-017 (M7d-Ereignisse) und PH-AT-018 (Schichten in der DST-Nacht).
  - Adapter/Worker: Abnahmelauf gegen Sandbox des Adapters `notification`.
  - Liefergegenstand Dokument: Abnahmeprotokoll mit Nachweis je Test in `e2e.csv` (Spalte Status).
- **Detailverträge vorab:** Zuordnung Test→WP; Testdaten aus WP-M7d-03.
- **Akzeptanzkriterien:**
  1. AT-38, AT-42, AT-43, AT-44, AT-52, AT-56 und AT-63 sind bestanden und protokolliert.
  2. AT-46 (Offline-Zeiterfassung: genau einmalige Übernahme mit Zeitkonflikterkennung) ist als Regression des M4-Stands nach den M7d-Änderungen (Korrekturpfad, Periodensperre) bestanden.
  3. Alle konkurrierenden Tests laufen mit mindestens zwei parallelen Transaktionen unter der Laufzeitrolle (Kap. 04 §4.5).
  4. Kein P1-Test des Personal-/Zeitbereichs ist durch M7d rückfällig.
  5. AT-49, AT-53, AT-54, AT-59 und AT-61 sind mit den M7d-Erweiterungen (Krankheitsdetails, Stundenabrechnung/Löschregeln, Planer-Barrierefreiheit, Stempeln ohne Freigabe, alte Planrevisionen) erneut bestanden; die Anteile anderer Einheiten sind als solche ausgewiesen.
  6. PH-AT-001 ist für jede neue M7d-Tabelle und jeden neuen Pfad unter der Laufzeitrolle bestanden; PH-AT-017 für jedes M7d-Ereignis mit Abnehmer.
  7. Für jede der 39 Anforderungen ist im Abnahmeprotokoll das Kriterium genannt, das ihre Originalabnahme nachweist.
- **Tests:** Vollständiger Lauf der genannten Referenztests; Wiederholungslauf zum Nachweis der Stabilität.
- **Abhängigkeiten:** intern: WP-M7d-05, WP-M7d-06, WP-M7d-15, WP-M7d-17, WP-M7d-18, WP-M7d-21, WP-M7d-22, WP-M7d-23, WP-M7d-24, WP-M7d-26, WP-M7d-33, WP-M7d-37, WP-M7d-38, WP-M7d-40, WP-M7d-41.
  - extern: WP-M4-12, WP-M4-63 (Offline-Stempel (AT-46) und P1-Abnahmesuite Personal/Zeit)
  - extern: WP-M5-02, WP-M5-22 (Abnahmeumgebung und Testautomatisierung)
  - extern: WP-M7c-54 (Gate-Tests AT-57, AT-62, E2E-069, E2E-071, E2E-100)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Gate-Tests der Einheiten M7a, M7b, M7c; Pilot- oder Produktivfreigabe.

## Offene Punkte

1. **Fehlende Tabellen in Kap. 04 §4.4:** Für Feiertags-/Sondertagskalender (`LH-6.23-SCH-019`), Beschäftigungs-/Arbeitszeitmodell und Regelrevision (`LH-6.23-SCH-017`, `LH-6.24-TIM-009`), Abrechnungsperiode (`LH-6.24-TIM-012`), Erinnerungseinstellungen (`LH-6.23-SCH-026`), Bereichs-/Poolberechtigung (`LH-6.23-SCH-029`) und Exportabbildung (`LH-6.24-TIM-013`) nennt das Datenmodell keine Tabellen. WP-M7d-01 muss Namen festlegen und Kap. 04 nachziehen.
2. **`staff_shift` im Kern-DDL zu eng:** `driver_ref` und `vehicle_id` sind NOT NULL; offene Dienste und Bedarfslinien (`LH-6.23-SCH-012`, `LH-6.23-SCH-021`), Bereitschaft ohne Fahrzeug (`LH-6.23-SCH-032`), Standort/Team (`LH-6.23-SCH-029`), Pausenplan (`LH-6.23-SCH-018`) und Qualifikationsbedarf (`LH-6.23-SCH-016`) sind damit nicht abbildbar. Zu klären, wie M4 offene Schichten bereits gelöst hat, und ob M7d darauf aufbaut oder migriert.
3. **API-Pfade nur als Gruppe:** Kap. 05 §5.7 nennt keine Operationen, Scopes oder Schemas für `/v1/shift-offers`, `/v1/shift-swaps`, `/v1/time-corrections`, `/v1/absences`; für Vorlagen, Publikationsvorschau (`LH-6.23-SCH-025`), Periodenabschluss (`LH-6.24-TIM-012`), Lohnexport (`LH-6.24-TIM-013`) und Kalenderfeed (`LH-6.23-SCH-030`) gibt es gar keinen Pfad. `PublishedRevision` enthält keine Differenz- oder Empfängerliste.
4. **Erinnerungsvorlauf ohne Parameter (`LH-6.23-SCH-026`):** kataloge.csv enthält keinen Parameter für Schichtstart- oder Stempelerinnerung; „15 Minuten“ ist nur Beispiel. Name, Default, Geltungsebene (Mandant/Mitarbeiter) und zulässige Anzahl von Erinnerungen fehlen. Unklar ist auch, ob `notification_quiet_hours` Schichterinnerungen unterdrücken darf.
5. **First-come-first-served ohne Parameter (`LH-6.23-SCH-023`):** Der Modus ist „konfiguriert“, aber kein Katalogparameter existiert. Offen: Ebene (Mandant, Bereich, Angebot) und Definition von „zuerst“ bei gleichzeitigen Eingängen.
6. **Enum-Werte von `absence_substitution_mode` (`LH-6.23-SCH-020`):** Der Katalog nennt nur Typ Enum und „Qualifikation/Freigabe zwingend“, keine Werte. Ebenso ist „kurz vor Dienstbeginn“ (AT-44) nicht beziffert.
7. **Regelmatrix Arbeitszeit/Ruhezeit (`LH-6.23-SCH-017`, `LH-6.23-SCH-035`):** AT-40 verweist auf eine „freigegebene Regelmatrix“, die im Pflichtenheft nicht vorliegt. Werte für `min_rest_hours` und `max_daily_work_minutes`, die Beschäftigungsmodelle, die Zuordnung der Regeln zu den drei Warnungsklassen und die ausnahmeberechtigten Rollen brauchen eine fachlich-rechtliche Freigabe.
8. **Wirkung von `shift_publish_notice_hours` (`LH-6.23-SCH-025`):** Unklar, ob eine Unterschreitung des Veröffentlichungsvorlaufs sperrt, einen prüfpflichtigen Hinweis erzeugt oder nur informiert.
9. **Rundungs- und Pausenlogik (`LH-6.24-TIM-011`):** Die Anforderung verlangt offengelegte Regeln, das Pflichtenheft nennt weder Regel noch Parameter. Ebenso offen: Bezugstag bei Nachtschicht für das Zeitkonto.
10. **Urlaubsberechnung (`LH-6.24-TIM-009`):** Formel, Übertrag, Verfall, Einheit (Tage/Stunden) und Verhalten bei Überziehung (Sperre oder Hinweis) sind nicht festgelegt.
11. **Periodenabschluss (`LH-6.24-TIM-012`):** Periodenlänge („festgelegter Zeitraum“), abschlussberechtigte Rolle und Verhältnis zur Finanzperiode (`finance_close_day`) fehlen; ob offene Korrekturanträge den Abschluss blockieren, ist eine Planungsannahme dieses Dokuments.
12. **Lohn-/DATEV-Format (`LH-6.24-TIM-013`):** „optional“ und „DATEV-konform“ ohne konkrete Schnittstelle oder Stundenartenkatalog; hängt mit D-06 zusammen. Herkunft der Kostenstellen für Mitarbeiter ist nicht beschrieben.
13. **Selbstfreigabe (`LH-6.24-TIM-014`):** Die Abnahme verbietet die unbefugte Freigabe fremder Zeitkonten; ob eine Person mit Freigaberecht den eigenen Antrag freigeben darf, sagt das Pflichtenheft nicht. Dieses Dokument nimmt ein Verbot an (WP-M7d-34) – zu bestätigen.
14. **Standortstempeln (`LH-6.24-TIM-017`):** Form der „rechtlichen und organisatorischen Freigabe“ und der bei aktiver Geozone gespeicherte Umfang (Ja/Nein-Ergebnis oder Koordinate) sind offen; abhängig von D-10.
15. **Löschfristen (`LH-6.24-TIM-019`):** Das „dokumentierte Lösch- und Nachweiskonzept“ samt Fristen ist Beschlussgegenstand von D-11 und liegt nicht vor. Ohne Beschluss nur Bau mit Platzhalterfristen, keine Produktivschaltung.
16. **Medizinische Nachweise (`LH-6.24-TIM-010`):** Die Anforderung erwähnt Nachweise, Kap. 04 schließt Diagnosen aus. Unklar, ob Nachweise überhaupt im System gespeichert werden; dieses Dokument sieht keine Speicherung vor.
17. **Kontakte in der Fahreransicht (`LH-6.23-SCH-015`):** „Kontakte“ steht neben „fremde Personaldaten nicht sichtbar“. Zu klären, welche Kontakte gemeint sind (Leitstelle/Standort oder Kollegen).
18. **Springer/Bereitschaft (`LH-6.23-SCH-032`):** „gesonderte Aktivierungsregeln“ sind nicht ausgeführt; ebenso fehlt die Bewertung der Bereitschaftszeit im Zeitkonto.
19. **Automatische Revision bei Ausfall (`LH-6.23-SCH-038`, `LH-6.23-SCH-020`):** Offen, ob die durch Ausfall erzeugte Planrevision sofort als veröffentlicht gilt (Fahrer sieht Entfall sofort) oder Planerfreigabe braucht; betrifft auch die Mitteilungslogik aus `LH-6.23-SCH-014`.
20. **Bedarfsvorschlag (`LH-6.23-SCH-028`):** AT-55 setzt eine Nachfrageprognose voraus, die erst in M9 entsteht (AT-55 ist Gate M9). In M7d ist nur ein Vorschlag aus historischen Daten und Servicefenstern lieferbar; Form der „Unsicherheit“ ist nicht definiert.
21. **Vorwarnhorizont (`LH-6.24-TIM-016`):** „frühzeitig“ ist nicht beziffert, kein Katalogparameter vorhanden.
22. **Feiertagskalender doppelt benötigt (`LH-6.23-SCH-019`):** M7a benötigt für Serienbuchung (AT-06) ebenfalls Feiertage/Schließtage. Zu entscheiden, ob ein gemeinsamer Kalender in `config`/`service-geo` entsteht oder zwei getrennte; Quelle der gesetzlichen Feiertage ist nicht benannt.
23. **AT-46 im M7-Gate:** Offline-Zeiterfassung ist fachlich `LH-6.24-TIM-006` (P1, M4) und keiner M7d-Anforderung zugeordnet. M7d behandelt den Test als Regression (WP-M7d-43); falls das Gate neue Funktion erwartet, fehlt eine Anforderung dazu.
24. **Barrierefreiheitsstandard (`LH-6.23-SCH-036`) und Tablet-Zielgeräte (`LH-6.23-SCH-012`):** Weder Prüfstandard noch unterstützte Geräte/Browser sind im Pflichtenheft für den Planer benannt.
25. **Überschneidung mit M7c:** `WP-M7c-09` (Pausenmeldung), `WP-M7c-13` (Dienstplanzeiten in der Buchungsprüfung), `WP-M7c-14` (zusammengeführte Verfügbarkeit) und `WP-M7c-17` (Qualifikation in der Schichtzuweisung) berühren `LH-6.23-SCH-018`, `LH-6.23-SCH-020` und `LH-6.23-SCH-016`. Die Leseports und Ereignisverträge zwischen `staff-time`, `fleet-safety` und `booking-dispatch` müssen in WP-M7d-01 und WP-M7c-01 gemeinsam festgelegt werden, damit keine Regel doppelt implementiert wird.
26. **`PublishRequest` verlangt `impact_report_id` und `reason` immer (`LH-6.23-SCH-025`, `LH-6.23-SCH-035`):** `openapi.yaml` macht `effective_from`, `reason` und `impact_report_id` zu Pflichtfeldern, beschreibt aber weder die Ressource hinter `impact_report_id` noch deren Erzeugung. Dieses Dokument nimmt an, dass die Publikationsvorschau diesen Wirkungsbericht liefert, und führt die Ausnahmebegründung für prüfpflichtige Hinweise getrennt von `reason` – zu bestätigen. Offen ist auch, wie „getrennte Freigabe mehrerer Wochen“ auf `roster_id` abgebildet wird (ein Dienstplan je Woche oder Wochenauswahl im Request).
27. **Rollen und Scopes der Personalplanung:** `openapi.yaml` kennt nur den Scope `rosters:publish`. Die Anforderungen nennen „befugte Disponenten“ (`LH-6.23-SCH-022`), „berechtigte Rolle“ (`LH-6.24-TIM-007`, `LH-6.24-TIM-012`), „Genehmiger“ (`LH-6.24-TIM-008`) und „Berechtigung“ für Ausnahmen (`LH-6.23-SCH-035`), ohne die Rollen zu benennen. Die Matrix entsteht in WP-M7d-01 und braucht fachliche Freigabe.
28. **Kanalwahl und Einwilligung bei Schichtmitteilungen (`LH-6.23-SCH-014`, `LH-6.23-SCH-026`):** Offen, ob Push und E-Mail beide verpflichtend oder je Mitarbeiter wählbar sind, was bei fehlendem Push-Token oder fehlender E-Mail-Adresse gilt und ob dienstliche Planmitteilungen einer Einwilligung nach Kap. 04 §4.4 (Kommunikation: „Einwilligung nur für entsprechende Nachrichtenzwecke“) bedürfen. Ebenso offen: ob `notification_quiet_hours` eine kurzfristige Planänderung vor Schichtbeginn verzögern darf.
29. **Wer meldet einen Ausfall (`LH-6.23-SCH-020`):** Nicht festgelegt, ob der Fahrer selbst in der App, nur Planer/Leitstelle oder beide einen ungeplanten Ausfall melden dürfen, und ob die Meldung ohne Bestätigung sofort wirkt. Betrifft Rechte und Missbrauchsschutz in WP-M7d-17.
30. **„Aufgaben“ im Offline-Cache (`LH-6.23-SCH-037`):** Die Anforderung nennt „Schichtpläne und Aufgaben“; welche Aufgaben gemeint sind (Diensthinweise, Checklistenpflichten, Fahraufträge) ist nicht bestimmt. Fahraufträge offline liefert WP-M7c-07; dieses Dokument beschränkt sich auf schichtbezogene Aufgaben und Hinweise – zu bestätigen.
31. **Automatische Zuteilung (`LH-6.23-SCH-016`):** Die Abnahme spricht von „automatisch zugeteilt“, das Pflichtenheft beschreibt aber keine eigenständige automatische Schichtzuteilung. Dieses Dokument versteht darunter Vorlagenanwendung, Vorschlagsübernahme und First-come-first-served – zu bestätigen.
32. **Gate-Tests außerhalb des M7-Gates:** AT-49, AT-53, AT-54, AT-59 und AT-61 sind dem Gate M5 zugeordnet, obwohl Teile (Krankheitsdetails, Stundenabrechnung/Löschregeln, Planer-Barrierefreiheit `LH-6.23-SCH-036`, Stempeln ohne Freigabe `LH-6.24-TIM-017`) erst mit P2-Anforderungen aus M7d vollständig prüfbar sind. WP-M7d-43 wiederholt sie als Regression; zu entscheiden, ob sie zusätzlich in das Gate M7 aufgenommen werden.
33. **Kennzahl ohne Katalogeintrag für Zeitwirtschaft:** kataloge.csv enthält für diesen Bereich nur `Schicht-Abdeckungsquote` (und mittelbar `Personalmangelablehnungen`). Für die in `LH-6.24-TIM-015` und `LH-6.23-SCH-027` verlangten Größen (geplante/erfasste Stunden, Fehlzeiten, Überstunden, Compliance-Warnungen, Über-/Unterdeckung) gibt es keine Kennzahldefinitionen; sie werden als Berichtsgrößen in WP-M7d-31 und WP-M7d-39 definiert und sind bei Bedarf im Katalog nachzutragen.
34. **Reihenfolgekonflikt: möglicher Zyklus WP-M7d-11 ↔ WP-M7a-07 (Feiertags-/Schließtagskalender):** Die externe Abhängigkeit von WP-M7d-11 auf M7a („gemeinsamer Feiertags-/Schließtagskalender für Serienbuchung“) hat in M7a kein lieferndes WP; WP-M7a-07 hängt umgekehrt bereits von WP-M7d-11 ab (siehe M7a, Offene Punkte 40). Ein Rückverweis auf WP-M7a-07 ergäbe einen Zyklus und wurde deshalb nicht eingetragen (Zeile als UNGEDECKT geführt). Zu entscheiden: WP-M7d-11 ist alleiniger Lieferant des Kalenders und die Abstimmung erfolgt im Detailvertrag (WP-M7d-01/WP-M7a-01), oder der Kalender wird in ein gemeinsames WP ausgelagert; zusätzlich ist die Doppelung mit dem Kalenderdienst aus WP-M8d-08 zu klären (vgl. Offene Punkte 22).
35. **Reihenfolgekonflikt: WP-M7d-32 → WP-M9a-06 (Nachfrageprognose für vollständiges AT-55):** WP-M7d-32 (M7) nennt die Nachfrageprognose aus M9 als Abhängigkeit; liefernd ist WP-M9a-06 (Prognose-Leseport), also ein SPÄTERER Meilenstein. Zugleich hängt WP-M9a-12 von WP-M7d-32 ab (Übernahme der Vorschläge), sodass eine harte Abhängigkeit einen Zyklus ergäbe. Vorschlag: WP-M7d-32 liefert in M7 nur den Vorschlag aus historischen Daten und Servicefenstern gegen den Leseport-Vertrag (Stub) und ohne Abhängigkeit auf M9; der Prognoseanteil und das vollständige AT-55 werden in M9 (WP-M9a-12, WP-M9b-48, WP-M9a-24/WP-M9b-57) abgenommen (vgl. Offene Punkte 20). Bis zur Entscheidung bleibt die Abhängigkeit unverändert dokumentiert.
