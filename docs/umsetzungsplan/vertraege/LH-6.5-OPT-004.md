# LH-6.5-OPT-004 – Fahrtenplanmodell und Routeninvarianten

- **Verantwortlicher:** Rolle Buchung/Disposition (Aggregat RunPlanRevision im Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-16
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrtabfolge garantiert Einstieg vor Ausstieg und die zulässigen Kapazitäten entlang der gesamten Route.

**Originalabnahme (unverändert):** Automatisierter Routentest findet keine negative oder überhöhte Belegung.

## Vertrag

- **Tabellen / DDL:** `vehicle_run` (V1: `revision bigint`, `state text`, FK auf `vehicle` und `staff_shift`); neue Migration für `route_stop` (Kap. 04 §4.4): `tenant_id`, `id`, `run_id`, `run_revision`, `seq` (`UNIQUE(tenant_id, run_id, run_revision, seq)`), `stop_revision_id` (Haltepunktrevision), `planned_at`, `boardings`/`alightings` (je Buchung/Ressourcenvektor), `leg_duration_sec` und `leg_distance_m` zum Folgestopp; Abschnittsindex wird auf `capacity_hold.interval_from/interval_to` abgebildet (Stabilität des Index über Revisionen laut Spike-Bericht WP-M0-26). Ein `vehicle_run` je einsetzbarer Schicht/Fahrzeug-Kombination (`UNIQUE(tenant_id, shift_id)` oder gleichwertige Sicherung), Startrevision; Zeitpunkt und Auslöser der Anlage im Bau (OP-48). Frühere Revisionen bleiben lesbar (append-only je Revision). RLS nach Mandant (V1); fremde Runs weder lesbar noch referenzierbar (Composite-FKs mit `tenant_id`).
- **Invarianten (Constraint Validator, gemeinsam von Solver-Ergebnisprüfung, Hold und Confirm genutzt):** je Buchung Einstieg vor Ausstieg; Belegungsvektor je Abschnitt zwischen 0 und Fahrzeugkapazität (`vehicle.capacity_config`) in jeder Dimension; Stoppreihenfolge eindeutig; `vehicle_run.revision` steigt je Planänderung monoton. Verletzendes Solver-Ergebnis wird verworfen und nicht gespeichert.
- **API:** keine öffentliche; lesbarer Fahrtenplan mit Stoppliste je `vehicle_run` als Schnittstelle für M3 (Pfad im Bau, manuelle Umplanung `/v1/runs/{id}/revisions` ist M3).
- **Zustandsautomat:** `vehicle_run.state`: neu in `vertraege/zustaende/vehicle_run.md` zu definieren (Werte im Pflichtenheft nicht festgelegt, OP-4/OP-48).
- **JSON-Schema / Ereignisse:** Outbox-Ereignis „Planrevision erstellt“ (Verteilung WP-M2-28); Schema unter `api/events/` (Name im Bau).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-48 (Erzeugung und Zustände `vehicle_run`), OP-4 (Zustandsautomat), OP-15 (Dimensionen des Ressourcenvektors); Intervallindex-Stabilität laut WP-M0-26. Nicht blockierend (Annahmen im Bau dokumentiert).

## Tests

- **Positiv:** gültige Pläne werden gespeichert; Property-Test über generierte Pläne und alle im Testlauf erzeugten Planrevisionen findet keine negative und keine die Kapazität übersteigende Belegung; frühere Revisionen lesbar.
- **Negativ:** konstruierte Verletzung je Invariante (Ausstieg vor Einstieg, Belegung kleiner 0, Belegung über Kapazität in jeder Dimension, doppelte Sequenznummer) wird verworfen und nicht gespeichert; Run eines fremden Mandanten nicht lesbar und nicht referenzierbar.
- **Konkurrierend (Kapazität):** zwei parallele Planänderungen desselben Runs: eine erhält Revisionskonflikt, `revision` bleibt monoton; zwei parallele Erstanlagen eines Runs für dieselbe Schicht: genau ein Run.
- **Referenztests:** AT-02 (Reihenfolge und Kapazität zweier gepoolter Fahrten).
- Testfälle: AT-02; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::property_keine_negative_oder_ueberhoehte_belegung; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::gueltiger_plan_wird_gespeichert; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::ausstieg_vor_einstieg_wird_verworfen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::belegung_kleiner_null_wird_verworfen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::belegung_ueber_kapazitaet_je_dimension_wird_verworfen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::verletzendes_solverergebnis_wird_nicht_gespeichert; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::revision_steigt_monoton; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::fruehere_revisionen_bleiben_lesbar; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::planrevision_erstellt_ereignis_in_outbox; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::parallele_planaenderung_ein_revisionskonflikt; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/RunPlanValidatorTest.kt::parallele_erstanlage_genau_ein_run; geplant:db/tests/test_route_stop.py::test_sequenz_eindeutig_je_runrevision; geplant:db/tests/test_route_stop.py::test_fremder_mandant_run_nicht_referenzierbar; geplant:db/tests/test_route_stop.py::test_fremder_mandant_run_nicht_lesbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
