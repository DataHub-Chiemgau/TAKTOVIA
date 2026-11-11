# LH-6.6-DRV-008 – Unterstützungshinweise nur für zugeordnetes Personal

- **Verantwortlicher:** Rolle Fahrer-App/Betrieb (Modul `staff-time`/`booking-dispatch`, Projektion im BFF)
- **Arbeitspaket:** WP-M3-32
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Relevante Hinweise zu Rollstuhlbedarf und Ein-/Ausstiegsunterstützung werden nur befugtem Personal gezeigt.

**Originalabnahme (unverändert):** Unbeteiligte Fahrer sehen keine entsprechenden Daten.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle in V1/V2. Quelle der Merkmale ist `passenger_trip` (Unterstützungsbedarf, M2: WP-M2-14/WP-M2-23) mit Feldschutz aus WP-M1-31; Zuordnung des Stopps zum Fahrer über `route_stop` → `vehicle_run` (`vehicle_run.shift_id`) → `staff_shift.driver_ref`. Mandantenisolation bleibt RLS (V1, `tenant_id` führend); die Fahrerzuordnung ist eine zweite Schicht in der Anwendung. Abrufprotokoll in `audit_event` (Akteur, Fahrer-Schicht, Stopp, Zweck; ohne Merkmalswerte). Keine Diagnosen: die Feldliste enthält ausschließlich Rollstuhlbedarf und Ein-/Ausstiegshilfe.
- **API:** Felder (Arbeitsname `assistance`: `wheelchair_required`, `boarding_assist`, `alighting_assist`) im Stopp von `GET /v1/driver/shifts/{id}/run`. Sichtbar nur, wenn (a) die Schicht `{id}` zum aufrufenden Fahrer gehört (`staff_shift.driver_ref`), (b) die Schicht aktuell angetreten ist, (c) der Stopp zur aktuellen Runrevision dieser Schicht gehört und (d) das Sichtbarkeitsfenster offen ist. Alle anderen Fälle liefern den Stopp ohne diese Felder bzw. für fremde Schicht/Buchung einheitlich 404. Kein anderer Endpunkt (`/v1/bookings`, `/v1/driver/events`, Push, Logs) trägt die Merkmale; Push-Nutzlasten enthalten nur Referenzen.
- **Zustandsautomat:** keine neuen Zustände; Sichtbarkeitsfenster aus Stoppstatus der Runrevision (Freigabe bei Zuordnung/vor dem Stopp, Entzug nach Fahrtabschluss oder Schichtende, bei Planrevision mit Fahrerwechsel ab Commit).
- **JSON-Schema / Ereignisse:** Antwortschema `driver_run_stop` (Feldgruppe `assistance` optional). Kein Ereignis mit Merkmalen; Outbox-Ereignisse tragen nur Referenzen.
- **Parameter und Kennzahlen:** keine Katalogparameter; Fenster (Vorlauf vor dem Stopp, Nachlauf danach) ist Detailvertrag-Vorgabe und offen (siehe Offene Punkte).
- **Oberfläche:** UI-10 (Driver Fahrt/Schaden) – Zeile „notwendige Unterstützung" am Stopp; Zustände: kein Bedarf (Zeile entfällt), Hinweis sichtbar, Hinweis nicht mehr verfügbar (nach Fahrtende/Schichtende, ohne Platzhalterwerte). Lokaler Cache der App wird mit Fahrtabschluss/Schichtende geleert; Hinweise werden nicht in Logs, Crashreports oder Push-Nutzlasten geschrieben.
- **Entscheidungen:** D-11 (Löschfristen) – blockiert nur die Produktivschaltung. D-10 (Fahrerdaten) berührt das Abrufprotokoll.
- **Offene Punkte:** (1) Endgültige Feldliste und Sichtbarkeitsfenster sind laut WP „Detailverträge vorab" festzulegen; Pflichtenheft nennt nur „Rollstuhlbedarf und Ein-/Ausstiegsunterstützung" – bis zur Festlegung gilt die Arbeitsliste oben (Fenster: von Zuordnung des Stopps zur aktuellen Runrevision bis Abschluss des Stopps bzw. Schichtende; Feinwerte offen). (2) Aufbewahrung des Abrufprotokolls hängt an D-11. (3) Die zweite Schicht (Fahrerzuordnung in der Anwendung neben RLS) ist Festlegung des WP-M3-32 („ausschließlich für Stopps der eigenen, aktuellen Schicht"), kein Pflichtenheft-Text; ob `audit_event` (`actor_ref`, `action`, `object_ref`, `redacted_changes`) die Abrufangaben trägt, ist mit WP-M3-02 zu bestätigen. (4) Fachliche Definition „Diagnose" (was gilt als nicht transportrelevant) ist durch das Pflichtenheft nicht festgelegt; Feldliste ist abschließend. Blockiert den Bau nicht; D-11 blockiert nur die Produktivschaltung.

## Tests

- **Positiv:** Der der Schicht zugeordnete Fahrer sieht Rollstuhlbedarf und Ein-/Ausstiegshilfe am betreffenden Stopp (Akzeptanzkriterium 1); der Abruf erzeugt genau einen `audit_event`.
- **Negativ (Datentrennung):** Unbeteiligter Fahrer desselben Mandanten erhält über `GET /v1/driver/shifts/{id}/run` der fremden Schicht 404 und über keinen anderen Endpunkt die Merkmale, auch nicht bei bekannter Buchungs-ID (Kriterium 2); anderer Fahrer in anderer Schicht; Fahrer eines anderen Mandanten; Abruf nach Fahrtabschluss/Schichtende liefert die Felder nicht mehr (Kriterium 3); Inspektion des lokalen App-Speichers nach Schichtende enthält keine Merkmale; Suche in Logs und Push-Nutzlasten findet keine Merkmale; Antwort enthält nur die Feldliste, keine Diagnosefelder (Kriterium 4); ohne Feldfreigabe (WP-M1-31) keine Felder.
- **Konkurrierend:** Der Stopp wird per Planrevision einem anderen Fahrer zugeordnet, während der bisherige Fahrer abruft – ab dem Commit der Revision erhält nur noch der neue Fahrer die Hinweise; parallele Abrufe zweier Mandanten über wiederverwendete Poolverbindungen bleiben getrennt.
- **Referenztests:** keine Katalog-ID im WP genannt; Baustein für PH-AT-001 (Datentrennung) nicht beansprucht.
- Testfälle: geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::zugeordneter_fahrer_sieht_unterstuetzung_am_stopp; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::unbeteiligter_fahrer_erhaelt_keine_daten_auch_nicht_per_buchungs_id; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::planrevision_wechselt_sichtbarkeit_ab_commit; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::fremder_mandant_und_fremde_schicht_liefern_404; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::nach_fahrtabschluss_und_schichtende_nicht_mehr_abrufbar; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::abruf_erzeugt_audit_event_ohne_merkmalswerte; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/DriverAssistanceVisibilityTest.kt::keine_merkmale_in_logs_push_und_anderen_endpunkten; geplant:mobile/driver/test/assistance_cache_test.dart::lokaler_speicher_nach_schichtende_ohne_merkmale

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
