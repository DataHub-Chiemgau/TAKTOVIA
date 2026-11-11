# Detailverträge M2 (WP-M2-01)

Eingang für alle folgenden M2-WPs. Alle Festlegungen, die das Pflichtenheft nicht trifft, sind **Annahmen dieses Plans**; Verantwortlicher ist die Rolle Buchung/Disposition (`booking-dispatch`), die Bestätigung durch Fachanalyse und Auftraggeber (D-12) steht aus. Offene Punkte sind die Nummern aus [wp/M2-buchung-disposition.md](../wp/M2-buchung-disposition.md).

## 1. API-Verträge

| Datei | Modul | Operationen | Zustand |
|---|---|---|---|
| [`api/booking.yaml`](../../../api/booking.yaml) | `booking-dispatch` | `createRideOffer`, `getRideOffer`, `holdRideOffer`, `releaseHold`, `confirmBooking`, `getBooking`, `listBookings`, `cancelBooking` (`/v1/ride-offers`, `/v1/bookings`) | Vertrag, nicht gebaut (`contract-pending.txt`) |
| [`api/geo.yaml`](../../../api/geo.yaml) | `service-geo` | `listServiceAreas`, `createServiceArea`, `listStops`, `createStop` (`/v1/service-areas`, `/v1/stops`; `/v1/services` steht in `services.yaml`) | Vertrag, nicht gebaut |
| [`api/payments.yaml`](../../../api/payments.yaml) | `fare-payment` | `receiveProviderEvent` (`/v1/payments/provider-events/{provider}`) | Vertrag, nicht gebaut |
| [`api/notifications.yaml`](../../../api/notifications.yaml) | `communication-support` | `listNotifications`, `getNotification` (`/v1/notifications`) | Vertrag, nicht gebaut |

Entscheidungen zu den Unterpfaden (OP-21, OP-24): **Hold ist ein eigener Schritt** (`POST /v1/ride-offers/{id}/hold`, synchron `201`, kein `202`: der Hold ist eine lokale Transaktion), **Confirm** ist `POST /v1/bookings` mit `hold_id` (Antwort `201` mit der Buchung; wiederholte Bestätigung liefert dieselbe Buchung), **Freigabe** des eigenen Holds (Abbruch des Checkouts, idempotent) ist `POST /v1/ride-offers/{offer_id}/hold/release` (`releaseHold`). **Storno** ist einstufig (`POST /v1/bookings/{id}/cancel`, Grund Pflicht, Ergebnis nennt Erstattung); ein zweistufiges Stornoangebot gibt es für lokale Buchungen in P1 nicht (OP-24, Annahme; die Föderation behält `cancellation-quotes`). Fehlerverträge: `OFFER_EXPIRED`, `HOLD_EXPIRED`, `REQUOTE_REQUIRED`, `CAPACITY_UNAVAILABLE`, `OPERATION_IN_PROGRESS`, `REVISION_MISMATCH` (412), `PRECONDITION_REQUIRED` (428), `NOT_FOUND`, `PROVIDER_UNAVAILABLE`, `IDEMPOTENCY_CONFLICT` stehen in `api/common.yaml` und werden in den Operationen referenziert.

Scopes: `rides:read`, `rides:write`, `geo:read`, `geo:write`, `payments:provider-events`, `notifications:read` (neu in `common.yaml`, beide Scope-Listen). Rechtematrix: je Operation eine Zeile (Meilenstein M2).

## 2. Zustandsautomaten

| Automat | Datei | Spalte |
|---|---|---|
| lokale Buchung | [buchung-lokal.md](zustaende/buchung-lokal.md) | `booking.state` |
| Hold | [hold.md](zustaende/hold.md) | `capacity_hold.state` |
| Fahrtenplan | [fahrtenplan.md](zustaende/fahrtenplan.md) | `vehicle_run.state` (Vorschlag, OP-48) |
| Zahlung | [zahlung.md](zustaende/zahlung.md), [zahlung-ablauf.md](zustaende/zahlung-ablauf.md) | `payment_intent.state` |
| Nachricht, Zustellversuch | [nachricht.md](zustaende/nachricht.md) | `notification.state`, `notification_attempt.state` |

`UNKNOWN`/`RECONCILING` sind eigene Zustände (Leitlinie 6): Buchung (`RECONCILING`), Zahlung (`CAPTURE_UNKNOWN`, `RECONCILING`), Nachricht (`UNKNOWN`). Die Datei [`tests/contract/test_m2_vertraege.py`](../../../tests/contract/test_m2_vertraege.py) prüft maschinell: jede Kante nennt Auslöser, Akteur und Fehlercode; alle Zustände sind vom Anfangszustand erreichbar; Endzustände haben keine ausgehende Kante; der Zahlungsautomat deckt sich mit `StateMachines.PAYMENT`.

## 3. JSON-Schemas (mit gespeicherter Schema-Version)

| Schema | Speicherort | Datei |
|---|---|---|
| Ressourcenvektor | `vehicle.capacity_config`, `capacity_hold.capacity_vector` | [resource-vector.v1](schemas/resource-vector.v1.schema.json) (hier in WP-M2-14 final) |
| Zusage-Snapshot | `booking.promise_snapshot` | [promise-snapshot.v1](schemas/promise-snapshot.v1.schema.json) (M1) |
| Tarifangebot | `fare_quote.snapshot` | [fare-quote-snapshot.v1](schemas/fare-quote-snapshot.v1.schema.json) |
| Barrierefreiheit | `stop_revision.accessibility` | [stop-accessibility.v1](schemas/stop-accessibility.v1.schema.json) |

Parameter-Schemas (Typ, Einheit, Min/Max, Ebene) der zwölf M2-Katalogparameter liegen vollständig unter `schemas/<parameter>.v1.schema.json` (`max_wait_min`, `max_detour_pct`, `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `arrival_grace_min`, `stop_walk_max_m`, `pooling_allowed`, `min_capacity_reserve`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `customer_notify_before_pickup_min`); WP-M1-19 hat sie in `policy_schema` registriert. Der Test prüft, dass alle zwölf vorhanden und gültige JSON-Schemas mit `x-unit` und `x-levels` sind.

Der Ereigniskatalog steht in [m2-ereigniskatalog.md](m2-ereigniskatalog.md).

## 4. DDL-Konvention und Tabellenliste

Jede neue M2-Tabelle: `tenant_id uuid NOT NULL`, Primärschlüssel `(tenant_id,id)`, lokale Fremdschlüssel nur zusammengesetzt `(tenant_id,<x>_id)`, `ENABLE`+`FORCE ROW LEVEL SECURITY` mit Policy `tenant_isolation` (`USING`/`WITH CHECK (tenant_id = current_tenant())`), `revision bigint NOT NULL DEFAULT 1` und `created_at`/`updated_at`, Auditbezug über `AuditPort` in derselben Transaktion (Kap. 04 §4.4, §4.6), Rechte nur über `R__runtime_grants_<wp>.sql`, Besitz in `db/table_owners.d/<wp>.csv`, Rollenmatrix `db/role_matrix.d/<wp>.csv`, Dateninventar `db/dateninventar.d/<wp>.csv`. Migrationsnummern `V2<nn>0` je WP.

| Tabelle | Modul | WP |
|---|---|---|
| `route_matrix_cache` | `booking-dispatch` | WP-M2-10 |
| `passenger_trip` | `booking-dispatch` | WP-M2-14 |
| `ride_offer` (Angebot, OP-19) | `booking-dispatch` | WP-M2-21 |
| `route_stop` | `booking-dispatch` | WP-M2-16 |
| `booking_status_history` (OP-22) | `booking-dispatch` | WP-M2-25 |
| `notification`, `notification_attempt` | `communication-support` | WP-M2-32 |
| `tariff`/Tarifregeln (OP-11) | `fare-payment` | WP-M2-18 |
| `vehicle_pool_assignment` (OP-43, Fixture bis M3) | `fleet-safety` (M3) | WP-M2-02 Fixture, WP-M3-06 |

Bestandstabellen aus V1 (`travel_attempt`, `vehicle_run`, `capacity_hold`, `booking`, `fare_quote`, `payment_intent`, `journey`, `journey_leg`, `service`, `service_area`, `stop_revision`) behalten ihr Schema; Änderungen erfolgen per expand/contract. Diese Liste ist Eingang der RLS-Abdeckungsprüfung (WP-M2-48).

## 5. Entscheidungsvorlage `journey`/`journey_leg` (OP-3)

**Annahme/Entscheidung:** Eine Einbetreiberbuchung legt bei der Bestätigung eine `journey` mit genau einem `journey_leg` an (`booking.federation_ref` = Leg-Referenz). Begründung: `payment_intent.journey_id` und `operation.journey_id` sind im Kern-DDL Pflicht-FKs; Lockern bräuchte eine Migration der Bestandstabellen und trennte den Zahlungs- und Operationsweg vom föderierten Fall (M6), der denselben Kern verwendet (Kap. 05 §5.7: „Ein Segment verwendet denselben Tarif-/Kapazitätskern“). Besitz: `journey` gehört `journey-federation`; `booking-dispatch` legt sie über einen Port an (WP-M2-23, WP-M2-30). Alternative (FK lockern) ist verworfen, solange D-02/M6 den föderierten Fall nicht ausschließen. Bestätigung durch Architektur und Fachanalyse offen.

## 6. Weitere Annahmen zu offenen Punkten (Kurzfassung)

| OP | Annahme in diesem Vertrag |
|---|---|
| 4 | `booking.state` und `vehicle_run.state` wie in den Zustandsdateien (Vorschlag) |
| 5 | Zahlung `AUTHORIZED` (oder kostenfrei) ist Voraussetzung für `CONFIRMED`; Capture-Zeitpunkt laut Zahlungsplan (D-03) |
| 15 | Höchstwerte je Buchung als Dienstparameter; Testwerte 8 Personen, 2 Rollstuhlplätze; Umrüstzustände in P1 nicht modelliert |
| 19 | Angebot in eigener Tabelle `ride_offer` (WP-M2-21) |
| 20 | Angebotsgültigkeit 300 s, Hold 180 s (Planungsdefaults, kein Katalogparameter) |
| 21, 24 | Pfade und einstufiges Storno wie in Abschnitt 1 |
| 38 | Aufrufart Kern ↔ Routing-Worker: synchroner Aufruf mit Zeitbudget, siehe [LH-6.5-OPT-007](LH-6.5-OPT-007.md) (WP-M2-10) |
| 47 | keine Hold-Verlängerung in P1 |
| 48 | `vehicle_run` entsteht bei Schichtfreigabe (M3/M4); bis dahin durch Fixture (WP-M2-02) |

AT-01 und AT-13 sind in M2 Teilnachweise (OP-1, OP-2, OP-49); die Gate-Zuordnung bleibt beim Auftraggeber.

## 7. Routing (WP-M2-10): Festlegungen

Aufrufart Kern ↔ Worker: **synchron** (HTTP/JSON, `workers/routing/.../server.py`, Client `booking-dispatch` `HttpRoutingClient`) mit hartem Zeitbudget; Cache zweistufig und mandantengebunden: flüchtig (Redis-Schlüssel `taktovia:route:v1:<mandant>:<profil>:<kartenversion>:<start>:<ziel>`, **Redis-Anbindung nicht gebaut**, nur Schlüssel- und Wertvertrag) und PostgreSQL `route_matrix_cache` (RLS, UNIQUE über Mandant/Start/Ziel/Profil/Kartenversion). Invalidierung: der Port fragt vor jedem Cachezugriff die Kartenversion beim Worker ab; bei einem neuen Stand werden Einträge anderer Versionen des Mandanten gelöscht, Einträge anderer Versionen werden nie gelesen. `route_matrix_cache` ist eine technische, ersetzbare Tabelle ohne Revision und Audit (Ausnahme von der DDL-Konvention in Abschnitt 4, Löschklasse `LK_TECHNISCH`). Haltepunktkatalog des Workers: in M2 die Fixture der Referenzregion; die Pflege aus `stop_revision` ist offen.

## 8. Ressourcenvektor und Mehrpersonenbuchung (WP-M2-14): Festlegungen

- **Vektor:** `{seats, wheelchair, equipment}` nach [resource-vector.v1](schemas/resource-vector.v1.schema.json) (final); Wertobjekt `ResourceVector` (komponentenweise Addition, Vergleich je Dimension, kein Ersatz einer Dimension). Unbekannte Dimension oder Merkmal: 422 (`UNKNOWN_FIELD`, `NOT_IN_ENUM`); die Datenbank erzwingt die Form zusätzlich (`capacity_hold_vector_shape`).
- **Personen:** `passengers` schließt Rollstuhlplätze ein (`wheelchair_spaces <= passengers`, wie `Party` in der Föderation); Sitzbedarf = Personen minus Rollstuhlplätze. **Höchstwerte (OP-15, Testwerte bis zur Nennung durch den Auftraggeber, als Dienstparameter gedacht):** 8 Personen, 2 Rollstuhlplätze je Buchung. Umrüstzustände sind in P1 nicht modelliert.
- **Unterstützungsbedarf:** Feld `assistance_codes` (Katalog des Feldschutzes WP-M1-31: `BOARDING_HELP`, `AUDIO_GUIDANCE`, `LOW_FLOOR`, `WHEELCHAIR_SPACE`, `ASSISTANCE_ANIMAL`, `ACCOMPANYING_PERSON`), kein Freitext, keine Diagnose; `ASSISTANCE_ANIMAL` verlangt zusätzlich `ASSISTANCE_DOG_SPACE`. Der Name `support_needs` ist dem geschützten Schema `PassengerSupportNeeds` vorbehalten.
- **`min_capacity_reserve` je Dimension:** der Katalogparameter ist eine Platzzahl der Dimension Sitz; für den Rollstuhlplatz gilt dieselbe Regel als eigener Wert (`ReserveRule(seats, wheelchair)`); Ausstattung hat keine Reserve. Die Reserve darf die tatsächliche Kapazität der Dimension nicht überschreiten (422 `/min_capacity_reserve/<dimension>`).
- **Ablehnungsgrund Ausstattung:** fehlendes Merkmal oder ein Fahrzeug ohne Rollstuhlplatz ergibt `EQUIPMENT_MISSING` (Kategorie Ausstattung, Primärgrund des Ereignisses `booking.attempt_recorded`); ein belegter Rollstuhlplatz ergibt `NO_CAPACITY` (`NO_CAPACITY_WHEELCHAIR`). Die Kennzahl `Ausstattungsablehnungen` entsteht aus diesen Ereignissen (M2-11/M4); WP-M2-14 liefert den normierten Grund.
- **Zusage:** `promise-snapshot.v1` bekommt das optionale Feld `resource_vector` (fahrgastrelevant); `booking.promise_snapshot` ist per Trigger nach dem Anlegen unveränderlich, eine Änderung ist eine neue Zusage-Revision (M2-23/-26).
- **Kernel Hold/Confirm:** `CapacityHolds` (Zeilenlock auf `vehicle_run`, Spike (a) `rc_run_row`) prüft den Vektor je Abschnitt und Dimension unter dem Lock; WP-M2-22/-23 vervollständigen Zustandsautomat, Ablauf-Job, Journey-Hülle (Abschnitt 5) und API. Die Zeile `booking` entsteht in `CONFIRMING`.
