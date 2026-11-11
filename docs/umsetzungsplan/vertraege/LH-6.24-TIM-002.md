# LH-6.24-TIM-002 – Stempeln in der Fahrer-App

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`, Fahrer-App `mobile/driver`)
- **Arbeitspaket:** WP-M4-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer können Arbeitsbeginn, Arbeitsende und Pausen in der App erfassen, auch vor Fahrzeugfreigabe und vor Fahrtbeginn.

**Originalabnahme (unverändert):** Vor-Schicht-Checkliste kann innerhalb einer begonnenen Arbeitszeit erledigt werden.

## Vertrag

- **Tabellen / DDL:** `time_event` (neue Migration ab V4, Modul `staff-time`; in `db/migrations/V1__kernschema.sql` V1 nicht enthalten, Kap. 04 §4.5 Familie TIM): `tenant_id uuid`, `id uuid`, `person_ref`/`driver_ref uuid` (wie `staff_shift.driver_ref`), `device_event_id uuid` (stabile Ereignis-ID), `kind` (Kommen, Pausenbeginn, Pausenende, Gehen), `occurred_at timestamptz`, `timezone text` (IANA), `origin` (online/offline), `device_time timestamptz`, `received_at timestamptz`, `conflict_flag`, `shift_id` optional; `PRIMARY KEY(tenant_id,id)`, `UNIQUE(tenant_id,device_event_id)`; RLS nach `tenant_id` wie bei den Kerntabellen; Runtime-Rolle nur INSERT/SELECT, keine stille Änderung (Korrekturen sind `time_correction`, TIM-007, M7). Exakte Spaltennamen werden mit dem Migrationsentwurf festgelegt (WP-M4-08). Invariante: Stempeln hängt nicht an Fahrzeugfreigabe (`resource_release`), Check- oder Fahrtzustand; `time_event` hat keine Fremdschlüssel darauf. Rechte: `time_event` wird in `db/migrations/R__runtime_grants.sql` und `db/role_matrix.csv` mit SELECT,INSERT aufgenommen (kein UPDATE/DELETE; Sperrtrigger wie bei `audit_event`, WP-M4-08).
- **API:** `POST /v1/time-events` (Stempel aus der App mit stabiler Ereignis-ID `device_event_id`, Header Idempotency-Key und Traceparent wie `api/common.yaml`); Antwort mit Ergebniscode. Statuscodes 200, 400, 401, 403, 409 (doppeltes Einstempeln als Konflikt gekennzeichnet), 422, 429, 503. Der Pfad ist in `api/*.yaml` noch nicht beschrieben (nur Katalog Kap. 05 §5.1); Ergänzung mit dem Bau. Stempel laufen über `/v1/time-events`, nicht über `/v1/driver/events` (Vorschlag des Plans, siehe Offene Punkte).
- **Zustandsautomat:** Arbeitszeit: nicht gestartet, Arbeit läuft, Pause, beendet; Übergänge Kommen, Pausenbeginn, Pausenende, Gehen. Datei `vertraege/zustaende/time_event.md` wird mit dem Bau angelegt.
- **JSON-Schema / Ereignisse:** Outbox-Ereignis je Stempel (WP-M1-14); Audit über WP-M1-46.
- **Parameter und Kennzahlen:** `clock_in_geofence_enabled` bleibt in P1 aus (TIM-017 ist P2).
- **Oberfläche:** Fahrer-App, Bereich Arbeitszeit im Dienststart UI-09 („Arbeitszeit: gestartet“): Schaltflächen Kommen, Pause, Gehen; Arbeitszeitbeginn und technische Einsatzfreigabe getrennt sichtbar; Status nicht nur als Farbe; Touchflächen mindestens 44×44 CSS-Pixel; Screenreader-bedienbar; während der Fahrbewegung kein Erfassungsdialog (Kap. 06 §6.1/§6.7). Zustände: ohne Sitzung (Anmeldung erforderlich), Fehler (Konflikt), laufende Arbeitszeit/Pause.
- **Entscheidungen:** D-10 (Mitbestimmung bei Fahrerdaten; blockiert nur Produktivschaltung).
- **Offene Punkte:** Endpunktwahl `/v1/time-events` gegenüber `/v1/driver/events` und Ergebniswerte bei Konflikten sind festzulegen (WP-M4 Offener Punkt 36); blockiert den Bau nicht, da der Katalog `/v1/time-events` nennt. UI-ID: Kap. 06 führt UI-09 als „Driver Dienststart“, die Arbeitszeit-Ansicht hat keine eigene UI-ID.

## Tests

- **Positiv:** Fahrer stempelt vor Fahrzeugfreigabe ein, erledigt die Vor-Schicht-Checkliste innerhalb der begonnenen Arbeitszeit (Originalabnahme); Pausenbeginn, Pausenende und Gehen erzeugen je ein `time_event`; Einstempeln erteilt keine Fahrzeug- oder Einsatzfreigabe; AT-45-Ablauf als App-Integrationstest mit erwarteter Soll/Ist-Auswertung (mit WP-M4-09).
- **Negativ:** Stempeln vor Fahrtbeginn (kein `vehicle_run`, keine `resource_release`) wird angenommen und nicht abgewiesen; doppeltes Einstempeln wird als Konflikt gekennzeichnet; Stempeln ohne gültige Sitzung (401); Stempel mit fremder Personen-ID wird serverseitig abgewiesen (403); Erfassungsdialog öffnet sich während der Fahrbewegung nicht; Statusanzeige nicht nur per Farbe.
- **Konkurrierend:** zwei parallele Stempel derselben Person mit derselben Ereignis-ID ergeben genau eine Zeile (Datentrennung/Idempotenz); parallele Stempel zweier Fahrer verschiedener Mandanten bleiben getrennt (RLS).
- **Referenztests:** AT-45.
- Testfälle: AT-45; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventStempelnTest.kt::einstempeln_vor_fahrzeugfreigabe_und_checkliste_innerhalb_arbeitszeit; geplant:mobile/driver/test/arbeitszeit_stempeln_test.dart::at45_stempeln_pause_gehen; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventStempelnTest.kt::stempel_fremde_person_403_ohne_sitzung_401; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/TimeEventStempelnTest.kt::paralleler_stempel_gleiche_ereignis_id_genau_eine_zeile; geplant:mobile/driver/test/arbeitszeit_stempeln_test.dart::kein_erfassungsdialog_waehrend_fahrbewegung_und_status_nicht_nur_farbe

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung nicht gefordert; D-10 betrifft nur Produktivschaltung.
