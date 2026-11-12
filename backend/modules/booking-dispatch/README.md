# booking-dispatch – Buchung, Kapazität, Disposition

Paket `de.taktovia.bookingdispatch` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Booking, CapacityHold, RunPlanRevision; Tabellen travel_attempt, vehicle_run, capacity_hold, booking.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Ressourcen-Leseport (WP-M2-02)

`BookingdispatchApi.resources` (`ResourceAvailability`) liest Fahrzeug, `capacity_config`, `active_block`, `safety_revision`, Schicht, Einsatzfreigabe und Fahrzeugpool je Dienst in **einem** SQL-Statement (konsistenter Datenstand je Abfrage, nur lesend, nur der Mandant des Kontexts). Ein Fahrzeug ist nicht einsetzbar bei: Sperre (`VEHICLE_BLOCKED`), fehlender deckender Schicht (`NO_SHIFT`), fehlender, widerrufener, abgelaufener oder gegen eine veraltete `safety_revision` ausgestellter Freigabe (`NO_VALID_RELEASE`) oder Pool-Fremdheit (`NOT_IN_POOL`). Bis M3/M4 stammen die Zeilen aus dem Seed [`tests/acceptance/fixtures/m2_referenzregion/`](../../../tests/acceptance/fixtures/m2_referenzregion/README.md); die Schnittstelle bleibt bei der Ablösung unverändert (Vollabnahme mit WP-M3-03/-06/-08, WP-M4-03/-65). Die Tabelle `vehicle_pool_assignment` (Besitzer `fleet-safety`) ist Fixture-Stand bis WP-M3-06.

## Routing (WP-M2-10)

`BookingdispatchApi.routing` (`RoutingPort`) ruft den Worker `workers/routing` synchron mit hartem Zeitbudget (`HttpRoutingClient`, Standard 0,9 s) und cached mandantengebunden: flüchtig (Schlüssel `RoutingCacheKey`, Redis-Anbindung **nicht gebaut**) und PostgreSQL `route_matrix_cache` (RLS). Die Kartendatenversion fragt der Port vor jedem Cachezugriff ab; Einträge anderer Versionen werden nie gelesen und beim Stand-Wechsel gelöscht. Fehler sind definiert (`RoutingException`: `NOT_A_STOP`, `NO_ROUTE`, `UNKNOWN_PROFILE`, `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`), nie ein Schätzwert. Tests gegen einen Stub des Workers; die Antwortform steht in `workers/routing/contract/route-response.json`.

## Ressourcenvektor und Kapazitätshold (WP-M2-14)

`ResourceVector` (Sitz, Rollstuhlplatz, Ausstattung getrennt), `BookingDemand` (Personen, Rollstuhlplätze, Ausstattung, `assistance_codes`), `ReserveRule` (`min_capacity_reserve` je Dimension) und der Kernel `BookingdispatchApi.holds` (`CapacityHolds`: Hold und Bestätigung in je einer Transaktion unter dem Lock auf `vehicle_run`). Festlegungen: [m2-detailvertraege.md §8](../../../docs/umsetzungsplan/vertraege/m2-detailvertraege.md). Die Tabelle `passenger_trip` hält Personen und Plätze je Buchung; `booking.promise_snapshot` ist unveränderlich und enthält den Vektor.

## Fahrtenplan und Routeninvarianten (WP-M2-16)

`BookingdispatchApi.runs` (`RunPlans`): Umlauf je Schicht (`ensureRun`, genau einer auch bei parallelen Aufrufen), Planrevisionen als append-only Stoppfolgen in `route_stop`, `appendRevision` mit Revisionsvergleich unter dem Zeilenlock von `vehicle_run`. `RunPlanValidator` ist der gemeinsame Constraint Validator (Einstieg vor Ausstieg, Belegung je Abschnitt 0..Kapazität je Dimension, eindeutige Stoppreihenfolge); Hold und Confirm nutzen dessen Kapazitätsprüfung. Festlegungen (Abschnittsindex, Zustandsautomat, Annahmen OP-4/OP-48): [m2-detailvertraege.md §9](../../../docs/umsetzungsplan/vertraege/m2-detailvertraege.md).

## Einfügeheuristik, harte Grenzen, Schutz zugesagter Fenster (WP-M2-17 bis -19)

`BookingdispatchApi.insertion` (`InsertionService`, nur mit Routing-Worker) baut den Eingang für `POST /v1/insert` des Workers (Fahrzeitmatrix über `RoutingPort`, Pläne der Kandidatenfahrzeuge mit Planrevision, Zusagefenster, Parameter), ruft ihn mit hartem Zeitbudget (`HttpInsertionClient`, Planungsdefault 1 s, D-05) und **prüft jede Variante im Kern erneut** (`RunPlanValidator`, `PromiseWindowGuard`, Fahrzeug und Planrevision); eine verletzende Variante wird verworfen. `PromiseWindow` (Zusagefenster samt `HardLimits` der Konfigurationsrevision) und `PromiseWindowGuard` sind die reine Prüfregel für Abhol-/Ankunftsfenster, Wartezeit, Umweg und Verspätung. Planbasierter Hold: `PlaceHold.proposal` (`PlanProposal`) prüft unter dem Lock des Umlaufs Revision, Routeninvarianten und alle Zusagefenster, speichert dann die Planrevision und legt den Hold an (Fenster unveränderlich in `capacity_hold.promise_window`, Migration `V20190`); `confirm` prüft erneut gegen die dann aktuelle Planrevision (409 `CAPACITY_UNAVAILABLE`). `BookingdispatchApi.audit` (`ConfirmedRideAudit`) prüft alle bestätigten und gehaltenen Buchungen eines Umlaufs gegen jede gespeicherte Planrevision. Festlegungen und Annahmen: [m2-detailvertraege.md §10](../../../docs/umsetzungsplan/vertraege/m2-detailvertraege.md). Die Tests starten den echten Python-Worker als Prozess (`python3` oder `TAKTOVIA_PYTHON`; ohne Interpreter übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` Fehler).
