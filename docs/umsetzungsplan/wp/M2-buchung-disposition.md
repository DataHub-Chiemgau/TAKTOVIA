# M2 – Einbetreiber-Buchung und Disposition

- **Meilenstein:** M2 (setzt M0 und M1 voraus; liefert `vehicle_run` und Stoppliste an M3, `travel_attempt` und Ereignisse an M4)
- **Phase:** P1 (alle Anforderungen MUSS, P1; Phase und Priorität unverändert)
- **Anzahl Anforderungen:** 38 (PAX 9, BKG 8, OPT 11, GEO 3, PAY 4, COM §6.12 3)
- **Anzahl WPs:** 48 (38 mit je einer Anforderung, 10 Enabler)

**Ziel:** Nach M2 kann ein Fahrgast bei einem einzelnen Betreiber über die Passenger-PWA (UI-01 bis UI-05) eine Sofort- oder Vorausfahrt suchen, ein bepreistes Angebot mit Zeitfenster erhalten, es über Hold und Confirm verbindlich buchen, bezahlen, verfolgen und stornieren. Die Disposition fügt Buchungen per Einfügeheuristik in versionierte Fahrtenpläne (`vehicle_run`) ein, hält Kapazität als Ressourcenvektor (Sitz, Rollstuhl, Ausstattung) ausschließlich in PostgreSQL und schützt zugesagte Zeitfenster als harte Nebenbedingung. Tarifrevision und unveränderlicher `fare_quote`, ein vom Buchungsstatus unabhängiger Zahlungsautomat und Betriebsnachrichten mit Zustellversuchen sind enthalten.

**Gate M2 (Umsetzungsplan §4):** PH-AT-004; AT-01 bis AT-05, AT-07, AT-13, AT-15, AT-19. Zu AT-01 und AT-13 siehe Offene Punkte 1 und 2.

**Lesehinweise:**
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als vorläufig bezeichnet (z. B. Hold 180 s, Kap. 02 §7.2). „Vorschlag dieses Plans“ kennzeichnet Festlegungen, die im Pflichtenheft fehlen und im jeweiligen Detailvertrag zu bestätigen sind.
- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung; `COM-00x` meint in dieser Datei ausschließlich `LH-6.12-COM-00x` (nicht Anhang I.9).
- Die Endpunkte `/v1/ride-offers`, `/v1/bookings`, `/v1/services`, `/v1/service-areas`, `/v1/stops`, `/v1/payments/provider-events/{provider}` und `/v1/notifications` stehen nur im Endpunktkatalog (Kap. 05 §5.7), nicht in `openapi.yaml`. Unterpfade wie `/v1/ride-offers/{id}/hold` sind Vorschläge dieses Plans und werden in WP-M2-01 festgelegt. Ebenso sind die Bezeichner `ResourceAvailability`, `RoutingPort` und `RideOffer` Arbeitsnamen dieses Plans, keine Namen aus dem Pflichtenheft.
- Für alle WPs gelten die Leitlinien des Umsetzungsplans §2 (Mandantenisolation mit `(tenant_id,id)` und RLS, Outbox in derselben Transaktion, Geld als `bigint`, Tests unter der Runtime-Rolle). Sie werden je WP nur genannt, wo sie eine besondere Ausprägung haben. Jede in dieser Datei als „neu“ bezeichnete Tabelle erhält ohne weitere Nennung `tenant_id`, Primärschlüssel `(tenant_id,id)`, zusammengesetzte lokale FKs, eine RLS-Policy analog `tenant_isolation` (aktiviert und erzwungen), Revision und Auditbezug (Kap. 04 §4.4); die maschinelle Prüfung dafür liegt in WP-M2-48.
- Abgrenzung zu M1: Den Dienstkopf (`service`, `/v1/services` Kopfverwaltung, Angebotsmodell) liefern WP-M1-09 und WP-M1-25, Fahrgastprofil mit `passenger_preference` und UI-05-Basis WP-M1-29. M2 ergänzt diese Objekte und legt sie nicht erneut an (WP-M2-03, WP-M2-36).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M2-01 | Detailverträge und Zustandsautomaten M2 | 0 | M | WP-M0-04, WP-M0-13, WP-M0-31, WP-M1-02, WP-M1-03, WP-M1-13, WP-M1-17, WP-M1-18, WP-M1-19, WP-M1-20, WP-M1-27 |
| WP-M2-02 | Referenzfixtures und Ressourcen-Leseport | 0 | M | WP-M2-01, WP-M0-03, WP-M0-18, WP-M0-22 |
| WP-M2-03 | Dienste und Betriebszeiten | 0 | M | WP-M2-01, WP-M1-05, WP-M1-07, WP-M1-08, WP-M1-09, WP-M1-18, WP-M1-20, WP-M1-21, WP-M1-25, WP-M1-43, WP-M1-46 |
| WP-M2-04 | Bediengebiete als valide Polygone | 1 | M | WP-M2-03, WP-M0-16, WP-M1-07, WP-M1-23, WP-M1-46 |
| WP-M2-05 | Haltepunktrevisionen mit Aktivstatus | 1 | M | WP-M2-03, WP-M1-07, WP-M1-46 |
| WP-M2-06 | Start-/Zielzulässigkeit nach Zonen und Zielpunkten | 1 | M | WP-M2-04, WP-M2-05, WP-M1-07, WP-M1-46 |
| WP-M2-07 | Tarifrevision und Preisregeln mit Gültigkeit | 1 | M | WP-M2-03, WP-M1-02, WP-M1-22, WP-M1-46 |
| WP-M2-08 | Tarifengine: Festpreis, Zonenpreis, Grundpreis/Strecke | 1 | M | WP-M2-06, WP-M2-07 |
| WP-M2-09 | Preisangebot als unveränderlicher `fare_quote` | 1 | M | WP-M2-08, WP-M2-11, WP-M0-20, WP-M1-20, WP-M1-22, WP-M1-26 |
| WP-M2-10 | Routingadapter und Fahrzeitmatrix | 1 | M | WP-M2-02, WP-M0-07, WP-M0-08, WP-M0-27, WP-M0-30 |
| WP-M2-11 | Anfrageerfassung, `travel_attempt` und Zeitwunsch | 1 | M | WP-M2-01, WP-M2-03, WP-M1-04, WP-M1-06, WP-M1-13, WP-M1-18, WP-M1-21, WP-M1-28, WP-M1-31 |
| WP-M2-12 | Zulässigkeitsprüfung Gebiet und Bedienzeit | 1 | S | WP-M2-06, WP-M2-11 |
| WP-M2-13 | Standardisierte Ablehnungsgründe | 1 | S | WP-M2-11 |
| WP-M2-14 | Ressourcenvektor und Mehrpersonenbuchung | 1 | M | WP-M2-01, WP-M2-02, WP-M0-26 |
| WP-M2-15 | Fahrzeugkandidatenfilter | 1 | S | WP-M2-02, WP-M2-10, WP-M2-14, WP-M3-34 |
| WP-M2-16 | Fahrtenplanmodell und Routeninvarianten | 1 | M | WP-M2-14, WP-M0-26 |
| WP-M2-17 | Einfügeheuristik im Routing-Worker | 1 | L | WP-M2-10, WP-M2-15, WP-M2-16, WP-M0-25, WP-M0-27 |
| WP-M2-18 | Harte Grenzen für Wartezeit, Umweg und Verspätung | 1 | M | WP-M2-17, WP-M1-19, WP-M1-21, WP-M1-22 |
| WP-M2-19 | Schutz zugesagter Zeitfenster | 1 | M | WP-M2-17, WP-M2-18 |
| WP-M2-20 | Mehrzielbewertung mit konfigurierbaren Gewichten | 1 | M | WP-M2-17, WP-M2-18, WP-M1-19 |
| WP-M2-21 | Angebotsobjekt mit Ablauf und Revisionsbindung | 1 | M | WP-M2-09, WP-M2-12, WP-M2-13, WP-M2-18 |
| WP-M2-22 | Hold: Kapazitätssperre mit Revisionsabgleich | 1 | L | WP-M2-16, WP-M2-21, WP-M0-26, WP-M1-01, WP-M1-13, WP-M1-14 |
| WP-M2-23 | Confirm: atomare Buchungsbestätigung | 1 | M | WP-M2-22, WP-M1-06, WP-M1-08, WP-M1-14, WP-M1-17, WP-M1-23, WP-M1-26, WP-M1-27, WP-M1-28, WP-M1-31 |
| WP-M2-24 | Idempotenz der Buchungsbefehle | 1 | S | WP-M2-22, WP-M2-23, WP-M1-02, WP-M1-13 |
| WP-M2-25 | Statushistorie der Buchung | 1 | S | WP-M2-23, WP-M1-07, WP-M1-12, WP-M1-46 |
| WP-M2-26 | Storno mit Fristen und Gründen | 1 | M | WP-M2-23, WP-M2-25, WP-M1-19, WP-M1-20, WP-M3-27 |
| WP-M2-27 | Stornoursache Fahrzeugausfall | 1 | S | WP-M2-26, WP-M3-53, WP-M4-46 |
| WP-M2-28 | Planrevisionen verteilen und Clients synchronisieren | 1 | M | WP-M2-16, WP-M2-23, WP-M1-11, WP-M1-14, WP-M3-24 |
| WP-M2-29 | PSP-Adapter und Provider-Ereignisse | 0 | M | WP-M2-01, WP-M0-23, WP-M0-29, WP-M1-14, WP-M1-16, WP-M1-36, WP-M1-46 |
| WP-M2-30 | Zahlungsautomat unabhängig vom Buchungsstatus | 1 | M | WP-M2-09, WP-M2-23, WP-M2-29, WP-M1-14, WP-M1-15 |
| WP-M2-31 | Void und Erstattung nach Storno | 0 | M | WP-M2-26, WP-M2-30 |
| WP-M2-32 | Asynchroner Nachrichtenversand mit Zustellversuchen | 1 | M | WP-M2-01, WP-M0-08, WP-M1-01, WP-M1-14, WP-M1-28, WP-M1-30, WP-M1-36 |
| WP-M2-33 | Nachrichtenvorlagen je Sprache, Verkehrsprodukt, Ereignistyp | 1 | M | WP-M2-32, WP-M1-29 |
| WP-M2-34 | Auslöser transaktionaler Benachrichtigungen | 1 | M | WP-M2-23, WP-M2-26, WP-M2-27, WP-M2-28, WP-M2-33, WP-M1-27, WP-M1-30, WP-M3-29, WP-M3-53, WP-M4-61, WP-M4-62 |
| WP-M2-35 | Passenger-PWA: Grundgerüst | 0 | M | WP-M2-01, WP-M0-05, WP-M0-09, WP-M1-06, WP-M1-10, WP-M1-43, WP-M1-44 |
| WP-M2-36 | UI-05 Konto: Kontakt, Sprache, Favoriten | 0 | S | WP-M2-05, WP-M2-35, WP-M1-28, WP-M1-29, WP-M1-31 |
| WP-M2-37 | UI-01 Startansicht und Buchung in fünf Schritten | 1 | M | WP-M2-21, WP-M2-23, WP-M2-35 |
| WP-M2-38 | Ortssuche mit fünf Eingabearten | 1 | M | WP-M2-05, WP-M2-35, WP-M2-36 |
| WP-M2-39 | Standortfreigabe nur nach Zustimmung | 1 | S | WP-M2-38 |
| WP-M2-40 | Karte mit Bediengebiet, Haltepunkten und Textzugang | 1 | M | WP-M2-04, WP-M2-05, WP-M2-35 |
| WP-M2-41 | UI-02 Angebotsliste und Angebotsdetails | 1 | M | WP-M2-09, WP-M2-21, WP-M2-37 |
| WP-M2-42 | UI-03 Checkout | 0 | M | WP-M2-24, WP-M2-30, WP-M2-41 |
| WP-M2-43 | UI-04 Buchungsdetail mit Status und ETA ohne Neuladen | 1 | M | WP-M2-28, WP-M2-42, WP-M3-25, WP-M3-29, WP-M4-62 |
| WP-M2-44 | Fußweg zum Treffpunkt mit Textanleitung | 1 | M | WP-M2-10, WP-M2-43 |
| WP-M2-45 | UI-04 Buchungshistorie in vier Bereichen | 1 | S | WP-M2-25, WP-M2-43, WP-M3-29 |
| WP-M2-46 | UI-04 Selbststorno | 1 | S | WP-M2-26, WP-M2-31, WP-M2-45 |
| WP-M2-47 | Barrierefreier Buchungsweg | 0 | S | WP-M2-39, WP-M2-40, WP-M2-44, WP-M2-46, WP-M1-43 |
| WP-M2-48 | Gate-Nachweis M2 und fortlaufender Lasttest | 0 | M | WP-M2-19, WP-M2-20, WP-M2-34, WP-M2-47, WP-M0-22, WP-M0-25, WP-M0-32, WP-M0-33, WP-M1-25, WP-M1-45, WP-M3-25, WP-M3-29, WP-M3-30 |

## Arbeitspakete

### WP-M2-01 – Detailverträge und Zustandsautomaten M2

- **Ziel:** Vor Codebeginn liegen die modulübergreifenden Verträge für Buchung, Disposition, Tarif/Zahlung und Kommunikation vor, sodass alle folgenden WPs gegen feste Schnittstellen arbeiten (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Dateien unter `api/` für die Module `service-geo` (`/v1/services`, `/v1/service-areas`, `/v1/stops`), `booking-dispatch` (`/v1/ride-offers`, `/v1/bookings`), `fare-payment` (`/v1/payments/provider-events/{provider}`) und `communication-support` (`/v1/notifications`) mit Konventionen aus Kap. 05 §5.1 (ETag/`If-Match`, `Idempotency-Key`, `application/problem+json`, Cursor-Listen) und den Fehlercodes aus Kap. 05 §5.4 (`OFFER_EXPIRED`, `HOLD_EXPIRED`, `REQUOTE_REQUIRED`, `CAPACITY_UNAVAILABLE`, `OPERATION_IN_PROGRESS`, `REVISION_MISMATCH`, `PRECONDITION_REQUIRED`, `NOT_FOUND`, `PROVIDER_UNAVAILABLE`, `IDEMPOTENCY_CONFLICT`).
  - Zustandsautomaten (Dokument + Transitionstabelle): lokale Buchung (`booking.state`), Hold (`capacity_hold.state`: `HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`), Fahrtenplan (`vehicle_run.state`), Zahlung nach Kap. 03 §4.2 (`CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED` mit Nebenpfaden), Nachricht/Zustellversuch.
  - JSON-Schemas mit gespeicherter Schema-Version (Kap. 04 §4.1): Ressourcenvektor (`vehicle.capacity_config`, `capacity_hold.capacity_vector`), `booking.promise_snapshot`, `fare_quote.snapshot`, `stop_revision.accessibility`.
  - Ereigniskatalog M2 (Outbox-`event_type`, `schema_version`, Payload) für Anfrageversuch (erfasst/abgelehnt mit Primärgrund), Angebot, Hold, Buchung, Storno mit Grund, Planrevision, Zahlung, Erstattung, Nachricht – die Ereignisse zu Versuch, Angebot, Hold, Buchung, Storno und Zahlung sind zugleich die Datenbasis, die M4 (Nachfragetrichter, Kennzahlen) erwartet; keine exakten Adressen oder Kontaktdaten im Payload (Kap. 05 §5.1 Minimierung).
  - DDL-Konvention für alle neuen M2-Tabellen (Mandantenschlüssel, zusammengesetzte FKs, RLS-Policy, Revision, Audit; Kap. 04 §4.4/§4.6) und Liste der Tabellen mit zuständigem WP.
  - Entscheidungsvorlage zur Hülle `journey`/`journey_leg` für Einbetreiberbuchungen (siehe Offener Punkt 3).
  - Parameter-Schemas (Typ, Einheit, Min/Max, Ebene) für die in M2 verwendeten Katalogparameter: `max_wait_min`, `max_detour_pct`, `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `arrival_grace_min`, `stop_walk_max_m`, `pooling_allowed`, `min_capacity_reserve`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `customer_notify_before_pickup_min`.
- **Detailverträge vorab:** Dieses WP ist die Vorabspezifikation; Eingang sind Kap. 01 §1.4, Kap. 02 §7, Kap. 03 §4.2, Kap. 04 §4.3–4.5, Kap. 05 §5.1/§5.4/§5.7.
- **Akzeptanzkriterien:**
  1. Alle neuen OpenAPI-Dateien bestehen die CI-Vertragsprüfung (parsbar, `$ref` auflösbar, `operationId` eindeutig, Beispiele schemagültig; Kap. 05 §5.8).
  2. Jeder Zustandsautomat nennt Zustände, erlaubte Übergänge, auslösenden Befehl/Ereignis, Akteur und Fehlercode bei unzulässigem Übergang; unbestimmte Ergebnisse (`UNKNOWN`/`RECONCILING`) sind eigene Zustände (Leitlinie 6).
  3. Für jede der 38 Anforderungen ist in `arbeitspakete.csv` ein Detailvertrag (Datei/Abschnitt) eingetragen.
  4. Die Entscheidung zur `journey`-Hülle ist getroffen und im Datenmodell (WP-M2-23, WP-M2-30) nachvollzogen.
  5. Offene Punkte dieser Datei, die einen Vertrag betreffen, sind entweder entschieden oder als Annahme mit Verantwortlichem dokumentiert.
  6. Der Ereigniskatalog ist mit den Verbrauchern in M3 (Fahrtenplan, Planrevision) und M4 (Versuch, Angebot, Hold, Buchung, Storno, Zahlung) abgestimmt; jedes Ereignis nennt Aggregat, Revision und Empfängerkreis.
  7. Die Liste der neuen M2-Tabellen mit DDL-Konvention liegt vor und ist Eingang der RLS-Abdeckungsprüfung in WP-M2-48.
- **Tests:** positiv: Vertragsprüfung in CI grün; Beispielpayloads validieren gegen die JSON-Schemas. negativ: ein absichtlich fehlerhaftes Beispiel lässt die Prüfung fehlschlagen.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-04, WP-M0-13, WP-M0-31 (Vertragsverzeichnis `api/` und CI-Stufe Vertragsprüfung (WP-M0-04, WP-M0-13); Detailvertragsprozess (WP-M0-31).)
  - extern: WP-M1-02, WP-M1-03, WP-M1-13 (Einheitliches Fehlermodell, Idempotenz- und ETag-Konventionen als wiederverwendbare OpenAPI-Komponenten.)
  - extern: WP-M1-19, WP-M1-20 (Konfigurationsregister mit Schemaregistrierung für Parameter.)
  - extern: WP-M1-17, WP-M1-18, WP-M1-27 (Zustandsautomaten-Rahmen mit serverseitiger Übergangsprüfung (WP-M1-17); Zeit-/Zeitzonen-/DST-Baustein (WP-M1-18); Baustein „gebundener Snapshot“ für Zusagen (WP-M1-27).)
- **Entscheidungen:** D-05 (Routingbudget, Pilotregion – blockiert nur Produktivwerte), D-03 (PSP-Modell – blockiert nur Produktivschaltung der Zahlung), D-09 (Routing/Karten – Bau mit Valhalla/OSM als Arbeitsstand).
- **Größe:** M
- **Nicht im Umfang:** Föderationsendpunkte `/v1/federation/*` (M6); `/v1/booking-series` (M7); Implementierung der Verträge (folgende WPs).

### WP-M2-02 – Referenzfixtures und Ressourcen-Leseport

- **Ziel:** Buchung und Disposition können gegen eine deterministische Referenzregion mit Fahrzeugen, Schichten und Freigaben entwickelt und getestet werden, ohne Tabellen fremder Module zu beschreiben.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Leseport `ResourceAvailability` im Modul `booking-dispatch` auf die Daten von `fleet-safety`/`staff-time` (Fahrzeug, `capacity_config`, `active_block`, `safety_revision`, Schicht mit Zeitraum, gültige `resource_release`, Zuordnung Fahrzeug ↔ Dienst – im Kern-DDL nicht vorhanden, siehe Offener Punkt 43). Bis M3/M4 die schreibenden Module liefern, liest der Port die Fixture-Daten.
  - Datenbank: Seed-Skripte (kein Modulcode) für `vehicle`, `staff_shift`, `resource_release` in zwei Mandanten; Fahrzeuge mit und ohne Rollstuhlplatz, ein gesperrtes Fahrzeug (`active_block=true`), eine abgelaufene Freigabe.
  - Testfixtures unter `tests/acceptance`: Referenzregion (Bediengebiet, Haltepunkte, Straßengraph-Ausschnitt mit einem gesperrten Abschnitt und einer Einbahnstraße), Tarif-Testrelationen, synthetische Fahrgäste; fester Seed und Uhr (steuerbare Serverzeit).
- **Detailverträge vorab:** Schnittstelle des Leseports (Felder, Konsistenzzusage, Verhalten bei fehlender Freigabe); JSON-Schema Ressourcenvektor aus WP-M2-01; Fixturebeschreibung mit erwarteten Fahrzeiten.
- **Akzeptanzkriterien:**
  1. Fixtures laden reproduzierbar auf leerer, migrierter Datenbank unter Migrator-Rolle; Tests lesen sie unter der Runtime-Rolle mit Tenantkontext.
  2. Der Architekturtest bestätigt: `booking-dispatch` schreibt weder `vehicle` noch `staff_shift` noch `resource_release`.
  3. Der Port liefert für ein Fahrzeug mit `active_block=true` oder ohne gültige `resource_release` „nicht einsetzbar“.
  4. Mandant A sieht keine Ressource von Mandant B.
  5. Die Fixtures enthalten je Dienst einen definierten Fahrzeugpool; ein Fahrzeug außerhalb des Pools wird für diesen Dienst nicht geliefert.
- **Tests:** positiv: Port liefert erwartete Fahrzeuge je Zeitraum. negativ: gesperrtes/abgelaufenes Fahrzeug fehlt. konkurrierend (Datentrennung): paralleler Zugriff zweier Mandanten über denselben Connection-Pool liefert nie fremde Zeilen.
- **Abhängigkeiten:** intern: WP-M2-01.
  - extern: WP-M0-03, WP-M0-18, WP-M0-22 (Akzeptanztestgerüst und Zwei-Mandanten-Fixtures (WP-M0-22); Tenantkontext-Testharness (WP-M0-18); Architekturtest Modulgrenzen (WP-M0-03).)
  - extern: WP-M3-03, WP-M3-06, WP-M3-08 (Fachlich führende Pflege von `vehicle`, `resource_release` und der Fahrzeugpool-Zuordnung zu Diensten (WP-M3-06) löst die Fixtures später ab.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
  - extern: WP-M4-03, WP-M4-65 (Fachlich führende Pflege von `staff_shift` (Dienstplan) löst die Fixtures später ab.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
- **Entscheidungen:** D-05 (Pilotbetreiber und Region – ohne Beschluss synthetische Referenzregion; blockiert nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Fahrzeug- und Fahrerstamm, Pflichtcheck, Einsatzfreigabe (M3); Dienstplan und Qualifikationen (M4).

### WP-M2-03 – Dienste und Betriebszeiten

- **Ziel:** Der in M1 angelegte Dienstkopf wird um Betriebszeiten/Kalender und Verkehrsproduktkennung ergänzt; ein Betreiber kann sie pflegen und lesen, und alle folgenden Prüfungen beziehen sich auf die zum Leistungszeitpunkt wirksame Dienstversion.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `service-geo`, Aggregat ServiceRevision (Kap. 01 §1.3); nur freigegebene, zum Leistungszeitpunkt wirksame Versionen sind lesbar für Angebotsbildung.
  - Datenbank: Tabelle `service` (`operator_id`, `timezone`, `config_revision_id`) besteht aus M1 (WP-M1-09) und wird hier nicht neu angelegt; Migration für Betriebszeiten/Kalender, Verkehrsproduktkennung und Versionierung/Wirksamkeit der Dienstversion (im Kern-DDL nicht vorhanden, siehe Offener Punkt 6) mit RLS; Invariante „nicht überlappende aktive Dienstversion“ (Kap. 04 §4.5) per Scope-Sperre und Intervallprüfung.
  - API: Erweiterung von `/v1/services` (Kopfverwaltung aus M1) um Betriebszeiten, Verkehrsprodukt und Revision; Detail mit ETag, Änderung als neue Revision; Lesesicht für Fahrgäste (Betriebsstatus, nächste Betriebszeit) ohne interne Felder.
  - Oberfläche: UI-17 – Erweiterung des Dienstformulars aus M1 um Betriebszeiten/Ausnahmetage und Verkehrsprodukt; Zustände leer („keine Betriebszeit hinterlegt“), Validierungsfehler, fehlendes Recht (403), „konkurrierende Version“ (412).
  - Ereignisse/Outbox: Dienstrevision veröffentlicht.
- **Detailverträge vorab:** OpenAPI `/v1/services`; DDL-Ergänzung Betriebszeiten (Wochenschema, Ausnahmetage, IANA-Zeitzone, halboffene Intervalle); Regel für DST-Tage.
- **Akzeptanzkriterien:**
  1. Ein Dienst mit Betriebszeit in `Europe/Berlin` liefert für einen Zeitpunkt eindeutig „in Betrieb“ oder „außer Betrieb“, auch am 25.10.2026 02:30 mit beiden Offsets.
  2. Zwei überlappende aktive Versionen desselben Dienstes werden abgelehnt.
  3. Änderung erzeugt eine neue Revision; bestehende Buchungen referenzieren weiter ihre Version.
  4. Fremder Mandant erhält 404 auf Dienst-ID.
  5. Ein Dienst ohne hinterlegte Betriebszeit gilt als „außer Betrieb“ und erzeugt keine Angebote (kein stiller 24-h-Betrieb).
  6. Nur Rollen mit Stammdatenrecht für diesen Dienst ändern Betriebszeiten; jede Änderung erzeugt einen Auditeintrag mit Akteur und Grund.
- **Tests:** positiv: Pflege, Lesen, Revision. negativ: ungültige Zeitzone, überlappende Version, fehlender `If-Match` (428), veraltet (412). konkurrierend: zwei parallele Aktivierungen desselben Zeitraums – genau eine gewinnt.
- **Abhängigkeiten:** intern: WP-M2-01.
  - extern: WP-M1-05, WP-M1-07, WP-M1-08, WP-M1-46 (Mandant, Organisation (Betreiber), Rollen- und Objektberechtigung, Audit.)
  - extern: WP-M1-09, WP-M1-25 (Dienstkopf `service` mit `/v1/services` (WP-M1-09) und Dienstkonfiguration mit Angebotsmodell (WP-M1-25).)
  - extern: WP-M1-18, WP-M1-20, WP-M1-21 (Konfigurationsregister (`config_revision`) und `/v1/config/effective`; Zeit-/DST-Baustein (WP-M1-18).)
  - extern: WP-M1-43 (Admin-Grundgerüst UI-17 Basis und Designsystem.)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Anlage des Dienstkopfs, Regionen, Angebotsmodell (M1); Mehrere Buchungslösungen je Verkehrsprodukt (BKG-011, P2); Betriebsmodelle der Plattform (SYS, M1).

### WP-M2-04 – Bediengebiete als valide Polygone

- **Ziel:** Bediengebiete lassen sich als gültige Polygone mit Aktivierungszeitraum erstellen und bearbeiten; die Angebotsprüfung verwendet das zum Fahrtzeitpunkt wirksame Gebiet.
- **Anforderungen:** `LH-6.9-GEO-001`
- **Liefergegenstände:**
  - Backend: Aggregat ServiceArea im Modul `service-geo`; Geometrievalidierung vor Persistenz (Gültigkeit, SRID 4326, geschlossene Ringe, keine Selbstüberschneidung); Abfrage „Punkt im wirksamen Gebiet zum Zeitpunkt t“.
  - Datenbank: `service_area` (`area geometry(MultiPolygon,4326)`, `revision`, `valid_from`, `valid_to`, `CHECK(ST_IsValid(area))`), Index `service_area_geo`; Invariante „nicht überlappende aktive Version je Dienst“ (Kap. 04 §4.5).
  - API: `/v1/service-areas` (GeoJSON nach SRID-Konvention, Liste je Dienst, Detail mit ETag, neue Revision).
  - Oberfläche: UI-17 – Gebiet zeichnen/importieren, Zustände „Ungültige Geometrie“ und „konkurrierende Version“; gleichwertige Koordinatenliste neben der Karte.
  - Ereignisse/Outbox: Gebietsrevision wirksam.
  - Backend: ImpactProvider „Bediengebiet“ für die Wirkungsanalyse vor Publikation (von WP-M1-23 als M2-Beitrag erwartet): Anzahl betroffener künftiger Angebote/Buchungen einer Gebietsänderung.
- **Detailverträge vorab:** Schnittstelle ImpactProvider (aus WP-M1-23); OpenAPI `/v1/service-areas` mit GeoJSON-Schema und Fehlerpointern; Regel für Punkte exakt auf der Grenze; Freigabeablauf der Revision (siehe Offener Punkt 9).
- **Akzeptanzkriterien:**
  1. Ein selbstüberschneidendes oder offenes Polygon wird mit 422 und Feldverweis abgelehnt und nicht gespeichert (GEO-001).
  2. Ein gültiges Polygon mit Aktivierungszeitraum wird gespeichert und von der Angebotsprüfung (WP-M2-12) berücksichtigt: Punkt innerhalb → zulässig, außerhalb → abgelehnt (GEO-001).
  3. Vor `valid_from` und ab `valid_to` wird das Gebiet nicht berücksichtigt (links geschlossen, rechts offen).
  4. Bearbeitung erzeugt eine neue Revision; die alte bleibt für bestehende Zusagen lesbar.
  5. Fremder Mandant erhält 404 auf Gebiets-ID; Rolle ohne Stammdatenrecht erhält 403; jede Änderung ist auditiert.
  6. Der ImpactProvider nennt für eine Gebietsverkleinerung die bestätigten Buchungen mit Start oder Ziel außerhalb des neuen Gebiets (Behandlung siehe Offener Punkt 8).
- **Tests:** positiv: gültiges MultiPolygon, Punkt-in-Gebiet. negativ: ungültige Geometrie, falsches SRID, `valid_to <= valid_from`, direkter SQL-Insert verletzt `ST_IsValid`. konkurrierend (Datentrennung/Version): zwei parallele Revisionen desselben Gebiets – eine erhält 412.
- **Abhängigkeiten:** intern: WP-M2-03.
  - extern: WP-M0-16 (Kernschema als Flyway-Migration mit PostGIS (WP-M0-16).)
  - extern: WP-M1-07, WP-M1-23, WP-M1-46 (Rollenprüfung für Stammdatenpflege; Audit; Wirkungsanalyse-Rahmen (WP-M1-23).)
- **Entscheidungen:** D-05 (reales Pilotgebiet – blockiert nur Produktivdaten).
- **Größe:** M
- **Nicht im Umfang:** Sperrbereiche (GEO-004, P2); geplante Aktivierung mit Versionsworkflow (GEO-006, P2); Geodatenimport mit Dublettenprüfung (GEO-013, P2); Priorität bei Gebietsüberlappung (`area_overlapping_priority`, M6).

### WP-M2-05 – Haltepunktrevisionen mit Aktivstatus

- **Ziel:** Haltestellen besitzen Koordinate, Identifikator, Aktivstatus und zulässige Verkehrsprodukte; nur aktive, wirksame Haltepunkte werden als buchbar angeboten.
- **Anforderungen:** `LH-6.9-GEO-002`
- **Liefergegenstände:**
  - Backend: Aggregat StopRevision im Modul `service-geo`; Abfrage „buchbare Haltepunkte für Dienst, Zeitpunkt, Umkreis“.
  - Datenbank: `stop_revision` (`stop_id`, `revision`, `point`, `name`, `accessibility`, `approved_by`, `valid_from`, `valid_to`), Index `stop_geo`; Migration für Aktivstatus und zulässige Verkehrsprodukte (Zuordnung Haltepunkt ↔ `service`; im Kern-DDL nicht vorhanden, siehe Offener Punkt 7).
  - API: `/v1/stops` (Liste mit Filtern Dienst/Umkreis/aktiv, Detail mit ETag, neue Revision, Deaktivierung als Revision).
  - Oberfläche: UI-17 – Haltepunktliste und -formular; Karte mit gleichwertiger Tabelle.
  - Ereignisse/Outbox: Haltepunktrevision wirksam/deaktiviert.
- **Detailverträge vorab:** OpenAPI `/v1/stops`; DDL-Ergänzung Aktivstatus/Verkehrsprodukte; JSON-Schema `accessibility` (nur Felder, die P1 braucht).
- **Akzeptanzkriterien:**
  1. Jede Haltestelle hat Koordinate (WGS84), stabilen Identifikator (`stop_id`), Aktivstatus und mindestens ein zulässiges Verkehrsprodukt (GEO-002).
  2. Eine inaktive Haltestelle erscheint weder in der Haltepunktsuche für Fahrgäste noch als Abhol-/Zielpunkt in einem Angebot (GEO-002).
  3. Eine Haltestelle wird nur für Dienste angeboten, deren Verkehrsprodukt zugelassen ist.
  4. Deaktivierung wirkt auf neue Angebote; bestehende Zusagen behalten ihren Haltepunktsnapshot (Folgebehandlung siehe Offener Punkt 8).
  5. `accessibility` enthält für P1 mindestens die Angabe, ob der Haltepunkt für Rollstuhl-Ein-/Ausstieg geeignet, ungeeignet oder ungeprüft ist (Minimalfeld für AT-04, Offener Punkt 41); Schema-Version ist gespeichert.
  6. Fremder Mandant erhält 404 auf Haltepunkt-ID; Rolle ohne Stammdatenrecht 403; Änderungen sind auditiert; `approved_by` ist gesetzt (Offener Punkt 9).
- **Tests:** positiv: aktive Haltestelle wird angeboten. negativ: inaktiv, außerhalb Gültigkeit, falsches Verkehrsprodukt, Koordinate außerhalb Wertebereich, `accessibility` verletzt das JSON-Schema, fremder Mandant. konkurrierend: Deaktivierung parallel zur Angebotsbildung – Angebot enthält entweder die alte wirksame Revision oder den Haltepunkt nicht, nie eine halbe Revision.
- **Abhängigkeiten:** intern: WP-M2-03.
  - extern: WP-M1-07, WP-M1-46 (Rollenprüfung für Stammdatenpflege; Audit.)
- **Entscheidungen:** D-05 (reale Haltepunktdaten des Piloten – blockiert nur Produktivdaten).
- **Größe:** M
- **Nicht im Umfang:** Detaileigenschaften Rollstuhlzugang, Querung, Steigung (GEO-007, P2); redaktioneller Freigabeprozess virtueller Haltepunkte (GEO-008, P2); `/v1/transfer-hubs` (M6); temporäre Haltestellen (GEO-011, P2).

### WP-M2-06 – Start-/Zielzulässigkeit nach Zonen und Zielpunkten

- **Ziel:** Ob eine Relation gebucht werden darf, lässt sich abhängig von getrennten Zonen für Start und Ziel sowie von ausdrücklich zugelassenen Zielpunkten prüfen.
- **Anforderungen:** `LH-6.9-GEO-003`
- **Liefergegenstände:**
  - Backend: Relationsregel im Modul `service-geo` (Fachoperation „Relation zulässig?“ mit Dienst, Startpunkt, Zielpunkt, Zeitpunkt; Ergebnis zulässig oder normierter Grund).
  - Datenbank: Migration für Zonenrolle (Start/Ziel/beides) an `service_area` bzw. eigene Zonentabelle und für die Menge erlaubter Zielpunkte (Bezug auf `stop_revision.stop_id`); im Pflichtenheft nicht als Tabelle benannt (Offener Punkt 10). Versioniert mit `valid_from`/`valid_to`.
  - API: Erweiterung `/v1/service-areas` um Zonenrolle und Relationsregeln.
  - Oberfläche: UI-17 – Zonenrolle und erlaubte Zielpunkte pflegen.
- **Detailverträge vorab:** DDL und OpenAPI-Ergänzung; Wahrheitstabelle Start-/Zielzone/Zielpunkt → zulässig/Grund.
- **Akzeptanzkriterien:**
  1. Eine Testrelation mit nicht erlaubtem Ziel (Ziel außerhalb der Zielzone bzw. nicht in der Zielpunktmenge) wird abgelehnt (GEO-003).
  2. Dieselbe Relation in umgekehrter Richtung wird unabhängig nach ihren eigenen Zonenregeln bewertet.
  3. Relation mit Start in Startzone und Ziel an erlaubtem Zielpunkt ist zulässig.
  4. Die Ablehnung trägt einen Grund aus dem Katalog von WP-M2-13 (außerhalb Gebiet bzw. Regelverbot).
- **Tests:** positiv: zulässige Relation. negativ: Ziel nicht erlaubt, Start nicht erlaubt, Regel abgelaufen, Zielpunkt verweist auf inaktiven Haltepunkt, Regel eines fremden Mandanten nicht sichtbar. Grenzfall: Punkt auf Zonengrenze gemäß Detailvertrag. konkurrierend: Regeländerung parallel zur Angebotsprüfung – die Prüfung verwendet vollständig die alte oder die neue wirksame Version.
- **Abhängigkeiten:** intern: WP-M2-04, WP-M2-05.
  - extern: WP-M1-07, WP-M1-46 (Rollenprüfung für Stammdatenpflege; Audit.)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Sperrbereiche nach Fahrzeugklasse (GEO-004, P2); Parallelfahrtenverbot (OPT-012, P2; siehe Offener Punkt 2).

### WP-M2-07 – Tarifrevision und Preisregeln mit Gültigkeit

- **Ziel:** Preisregeln sind einem Dienst und einer Tarifklasse zugeordnet und haben einen Gültigkeitszeitraum; abgelaufene Regeln werden nicht mehr angewendet.
- **Anforderungen:** `LH-6.10-PAY-003`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`, Aggregat Tarifrevision mit Regeln; Auswahl der wirksamen Revision zum Preisstichtag; Freigabe mit Ersteller/Prüfer.
  - Datenbank: neue Tabellen `tariff_revision` und `tariff_rule` (Kap. 04 §4.4: Tarif-/Steuerstand) mit `(tenant_id,id)`, FK auf `service`, Tarifklasse, `valid_from`/`valid_to`, RLS; Invariante „nicht überlappende aktive Tarifversion“ (Kap. 04 §4.5) per Scope-Sperre oder Exclusion Constraint; `fare_quote.tariff_revision_id` erhält FK.
  - API: Tarifpflege-Endpunkte (im Endpunktkatalog nicht benannt; Pfadvorschlag in WP-M2-01, Offener Punkt 11).
  - Oberfläche: UI-17 – Tarifrevision anlegen, prüfen, freigeben; Zustand „konkurrierende Version“.
  - Ereignisse/Outbox: Tarifrevision veröffentlicht.
- **Detailverträge vorab:** DDL `tariff_revision`/`tariff_rule`; OpenAPI Tarifpflege; Definition „Preisstichtag“ (Anfragezeitpunkt oder Fahrtzeitpunkt, Offener Punkt 12); Steuerregelrevision je Regel.
- **Akzeptanzkriterien:**
  1. Jede Preisregel hat Gültigkeitszeitraum, Dienst und Tarifklasse; ohne diese Angaben ist keine Freigabe möglich (PAY-003).
  2. Eine abgelaufene Regel wird bei der Preisberechnung nicht mehr angewendet; die Anfrage erhält den Preis der wirksamen Nachfolgeregel oder eine begründete Ablehnung „kein Tarif“ (PAY-003).
  3. Zwei zeitlich überlappende aktive Revisionen für denselben Dienst und dieselbe Tarifklasse werden abgewiesen.
  4. Eine veröffentlichte Revision ist unveränderlich; Korrektur nur als neue Revision.
- **Tests:** positiv: Regel innerhalb Gültigkeit wird gefunden. negativ: abgelaufen, noch nicht gültig, fremder Dienst, Update einer veröffentlichten Revision. konkurrierend (Geld): zwei parallele Veröffentlichungen mit überlappendem Zeitraum – genau eine erfolgreich.
- **Abhängigkeiten:** intern: WP-M2-03.
  - extern: WP-M1-02, WP-M1-22, WP-M1-46 (Vier-Augen-Freigabe und Audit; Geldtyp (`bigint` minor, Währungskatalog `currency`).)
- **Entscheidungen:** D-05 (realer Pilottarif – blockiert nur Produktivdaten).
- **Größe:** M
- **Nicht im Umfang:** Gutscheine, Guthaben, Pässe, Tarifberechtigungen (PAY-008 bis PAY-010, P2); Tarif-Sandbox (PAY-017, P3).

### WP-M2-08 – Tarifengine: Festpreis, Zonenpreis, Grundpreis/Strecke

- **Ziel:** Die Tarifengine berechnet Preise für die drei Tarifmodelle deterministisch und ohne Gleitkomma.
- **Anforderungen:** `LH-6.10-PAY-002`
- **Liefergegenstände:**
  - Backend: reine Berechnungskomponente im Modul `fare-payment` mit den Regeltypen Festpreis, Zonenpreis und Grundpreis plus Streckenanteil; Eingang: wirksame `tariff_revision`, Tarifklasse, Personenzahl, Start-/Zielzone, tarifliche Strecke; Ausgang: disjunkte additive Komponenten (Kap. 05 §5.2) in `bigint` minor mit dokumentierter Rundungsregel.
  - Datenbank: neue Tabelle `fare_component` (Komponente je Segment, Kap. 04 §4.4); Regelparameter in `tariff_rule` (Sätze als `numeric(20,8)`).
- **Detailverträge vorab:** Formeln und Rundungsregel je Regeltyp; Quelle der tariflichen Strecke (Routingdistanz aus WP-M2-10 mit Version); Tabelle der Testrelationen mit erwarteten Preisen (Fixture, vom Auftraggeber zu bestätigen); Überlaufprüfung vor Multiplikation (Kap. 04 §4.1).
- **Akzeptanzkriterien:**
  1. Die vordefinierten Testrelationen liefern für Festpreis, Zonenpreis und Grundpreis/Streckenanteil exakt die erwarteten Preise (PAY-002).
  2. Gleiche Eingaben ergeben immer dasselbe Ergebnis; keine `float`-Arithmetik im Codepfad (statisch geprüft).
  3. Summe der Komponenten entspricht dem Bruttobetrag; Steuer wird nicht doppelt addiert.
  4. Mehrere Personen einer Buchung werden nach Regel bepreist; Ergebnis ist im Detailvertrag belegt.
- **Tests:** positiv: Tabelle der Testrelationen als parametrisierter Test. negativ: fehlende Zone, Strecke 0, Überlaufgrenze, unbekannter Regeltyp. Property-Test: Komponentensumme = Brutto für zufällige Eingaben. konkurrierend: entfällt für die zustandslose Berechnung; Nebenläufigkeit von Tarifwechsel und Preisbindung wird in WP-M2-07 und WP-M2-09 geprüft.
- **Abhängigkeiten:** intern: WP-M2-06, WP-M2-07.
- **Entscheidungen:** D-05 (Steuersätze und Preise sind Testannahmen bis zur Freigabe des Pilottarifs – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Rabatte, Zuschüsse, Trinkgeld (PAY-008, PAY-013, P2); Mehrparteien-Preisbestandteile (M6).

### WP-M2-09 – Preisangebot als unveränderlicher `fare_quote`

- **Ziel:** Vor der verbindlichen Buchung liegt ein Preisangebot mit Steuer- und Gebührenbestandteilen vor; der bestätigte Betrag entspricht exakt diesem eingefrorenen Preisstand.
- **Anforderungen:** `LH-6.10-PAY-001`
- **Liefergegenstände:**
  - Backend: Erzeugung des QuoteSnapshot im Modul `fare-payment` je Angebot; Vergleich „akzeptierter Betrag = Quote“ beim Confirm; Abweichung → `409 REQUOTE_REQUIRED`.
  - Datenbank: `fare_quote` (`attempt_id`, `currency`, `pay_at_platform_minor`, `external_known_minor` = 0 und `external_unknown_leg_ids` leer im Einbetreiberfall, `expires_at`, `tariff_revision_id`, `config_revision_id`, `snapshot`, `content_hash`), Trigger `quote_immutable`; `fare_component`-Zeilen je Quote.
  - API: Preisblock in den Antworten von `/v1/ride-offers` (Money-Objekt `{amount_minor,currency,exponent}`, `fare_components` mit Netto, Steuer, Gebühren).
  - Oberfläche: Preisaufschlüsselung in UI-02 und UI-03 (Darstellung in WP-M2-41, WP-M2-42).
- **Detailverträge vorab:** JSON-Schema `fare_quote.snapshot`; Kanonisierung für `content_hash` (SHA-256); OpenAPI-Schema Preisblock.
- **Akzeptanzkriterien:**
  1. Jedes Angebot enthält den Endpreis mit ausgewiesenen Steuer- und Gebührenbestandteilen, bevor ein Hold oder Confirm möglich ist (PAY-001).
  2. Der beim Confirm gebuchte und zur Zahlung autorisierte Betrag ist identisch mit `fare_quote.pay_at_platform_minor`; eine zwischenzeitlich veröffentlichte Tarifrevision ändert ihn nicht (PAY-001).
  3. UPDATE und DELETE auf `fare_quote` schlagen unter der Runtime-Rolle fehl (Invariante „Gegenereignis statt Änderung“, Kap. 04 §4.5).
  4. Ein Confirm mit abweichendem akzeptiertem Betrag liefert `409 REQUOTE_REQUIRED` ohne Buchung und ohne Zahlung.
- **Tests:** positiv: Quote erzeugt, Hash reproduzierbar. negativ: Mutation abgelehnt; abweichender Betrag; abgelaufene Quote. konkurrierend (Geld): Tarifwechsel parallel zum Confirm – Betrag bleibt der eingefrorene.
- **Abhängigkeiten:** intern: WP-M2-08, WP-M2-11.
  - extern: WP-M0-20 (Trigger- und Constraint-Laufzeittests für `quote_immutable` (WP-M0-20).)
  - extern: WP-M1-20, WP-M1-22, WP-M1-26 (Policy-Port für die serverseitige Tarifprüfung (WP-M1-26); gebundene `config_revision_id` (WP-M1-20/22, Nachweis E2E-096/E2E-098 im M2-Anteil).)
- **Entscheidungen:** keine für den Bau.
- **Größe:** M
- **Nicht im Umfang:** Quittungen (PAY-007, P2); externe Preisanteile gemischter Reisen (M6).

### WP-M2-10 – Routingadapter und Fahrzeitmatrix

- **Ziel:** Die Disposition erhält Fahrzeiten, Distanzen und Wege, die Straßengraph, Fahrtrichtungen, Fahrzeugrestriktionen und definierte Stopps beachten.
- **Anforderungen:** `LH-6.5-OPT-007`
- **Liefergegenstände:**
  - Adapter/Worker: Routingadapter in `workers/routing/` hinter einer anbieterneutralen Schnittstelle (Arbeitsstand Valhalla/OSM, Kap. 01 §1.1): Matrix (n×m Fahrzeiten/Distanzen), Einzelroute mit Geometrie, Fußweg; Fahrzeugprofil (Abmessungen/Restriktionen) als Parameter; Stopps werden auf die definierten Haltepunktkoordinaten gebunden.
  - Datenbank: neue Tabelle `route_matrix_cache` (Kap. 04 §4.4: Start/Ziel/Profil/Version im Cache-Key) mit Mandantenschlüssel und RLS; Redis nur als flüchtiger Cache (Leitlinie 4).
  - Backend: Port `RoutingPort` im Modul `booking-dispatch` mit Timeout, Kartendatenstand/Version im Ergebnis.
  - Testfixture: Kartenausschnitt mit gesperrtem Abschnitt, Einbahnstraße und einer Fahrzeugrestriktion.
- **Detailverträge vorab:** Schnittstelle `RoutingPort` (Anfrage, Antwort, Fehler, Zeitbudget innerhalb „1 s lokale Kandidatensuche“ – Planungsdefault Kap. 01 §1.4); Cache-Key und Invalidierung bei neuem Kartenstand; Aufrufart Kern ↔ Worker (synchron oder Queue; Empfehlung aus Spike WP-M0-27).
- **Akzeptanzkriterien:**
  1. Auf der Teststrecke mit gesperrtem Abschnitt führt keine gelieferte Route über diesen Abschnitt; die Fahrzeit entspricht der Umfahrung (OPT-007).
  2. Eine Einbahnstraße wird nicht entgegen der Fahrtrichtung geplant; ein Fahrzeugprofil mit Restriktion erhält eine andere Route als ein Profil ohne.
  3. Start und Ziel jeder Route sind definierte Stopps (Haltepunktkoordinaten), keine frei gesnappten Punkte.
  4. Jedes Ergebnis trägt Profil und Kartendatenversion; ein Cache-Treffer mit anderer Version wird nicht verwendet.
  5. Bei Timeout oder Ausfall der Kartenquelle liefert der Port einen definierten Fehler, kein geschätztes Ergebnis.
  6. Cache-Einträge (`route_matrix_cache` und Redis) sind mandantengebunden; ein Mandant erhält nie einen Cache-Treffer eines anderen Mandanten (Kap. 01 §1.7: Isolation in Cache-Keys).
- **Tests:** positiv: Referenzrouten mit erwarteten Zeiten. negativ: gesperrter Abschnitt, Gegenrichtung, unerreichbares Ziel, Timeout. Fault-Injection: Kartenquelle nicht erreichbar. Datentrennung: zwei Mandanten mit identischer Relation und unterschiedlichem Profil/Kartenstand erhalten getrennte Cache-Einträge.
- **Abhängigkeiten:** intern: WP-M2-02.
  - extern: WP-M0-07, WP-M0-08, WP-M0-27, WP-M0-30 (Worker-Gerüst `workers/routing` (WP-M0-07), Valhalla in der lokalen Umgebung (WP-M0-08), Spike-Berichte (b) und (e) (WP-M0-27, WP-M0-30).)
- **Entscheidungen:** D-09 (Routing, Karten, Telematik – Bau mit Valhalla/OSM; Produktivschaltung erst nach Beschluss und Lizenzprüfung).
- **Größe:** M
- **Nicht im Umfang:** Pflege verbotener Strecken und Sperrbereiche durch den Betreiber (GEO-004, GEO-005, P2); fahrzeugabhängige Erreichbarkeit, Pausen, Depot (OPT-017, P2); Verkehrslage/Echtzeit.

### WP-M2-11 – Anfrageerfassung, `travel_attempt` und Zeitwunsch

- **Ziel:** Sofort- und Vorausbuchungen können mit Abfahrts- oder Ankunftswunsch angefragt werden; jede fachlich neue Anfrage wird als `travel_attempt` erfasst und führt zu einem korrekten Zeitfenster oder einer begründeten Ablehnung.
- **Anforderungen:** `LH-6.4-BKG-001`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`, Anfrageannahme für `/v1/ride-offers`: Validierung von Start, Ziel, Personen/Unterstützungsbedarf, Zeitwunsch (Art Abfahrt/Ankunft, `desired_at`, `time_zone`), Ableitung des angebotenen Abhol- bzw. Ankunftsfensters aus `pickup_window_min` und `arrival_grace_min`; Prüfung von `min_booking_lead_min` und `max_booking_horizon_days`; DST-Regeln nach Kap. 05 §5.1 (Lücke ablehnen, doppelte Ortszeit braucht Offset).
  - Datenbank: `travel_attempt` (`created_at`, `channel`, `primary_rejection`, `secondary_rejections`, `coarse_origin`, `coarse_destination`); Regel „eine `attempt_id` je fachlich neuer Suche; technische Retries zählen nicht neu“ (Kap. 04 §4.3).
  - API: `POST /v1/ride-offers` (Anfrage; Antwort 200 mit `offers` oder leerer Liste plus normiertem Ablehnungsgrund; 422 bei fehlerhafter Eingabe – analog Kap. 05 §5.3 Schritt 1).
  - Konfigurationsparameter: `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `arrival_grace_min`.
  - Ereignisse/Outbox: Anfrageversuch erfasst bzw. abgelehnt (mit `attempt_id`, Kanal, Primärgrund; ohne exakte Orte) als Datenbasis für M4.
  - Kennzahlen (Datenbasis, Berechnung in M4): `Anfragen`, `Technische Anfragefehler`, `Zeitfensterablehnungen`.
- **Detailverträge vorab:** OpenAPI-Schema der Anfrage (`TimeWindow {earliest_at,latest_at}` nach Kap. 05 §5.9); Regel zur Erkennung „technischer Retry“ gegenüber „neuer Versuch“; Vergröberung für `coarse_origin`/`coarse_destination` (Offener Punkt 13); Abgrenzung Sofort-/Vorausbuchung.
- **Akzeptanzkriterien:**
  1. Eine Sofortanfrage und eine Vorausanfrage, jeweils mit Abfahrts- und mit Ankunftswunsch, ergeben ein Angebot mit nichtleerem Abhol- und Ankunftsfenster, das zum Wunsch passt (BKG-001).
  2. Eine Anfrage unter dem Mindestvorlauf oder jenseits des Vorausbuchungshorizonts wird mit dem Grund „Zeitfenster/Frist“ abgelehnt; ein 422 wird nur bei formal fehlerhafter Eingabe geliefert (BKG-001).
  3. `desired_at` 25.10.2026 02:30 `Europe/Berlin` ohne Offset wird zur Klärung zurückgewiesen; eine nicht existente Ortszeit wird abgelehnt.
  4. Ein wiederholter identischer Request (gleicher Idempotenzschlüssel) erzeugt keinen zweiten `travel_attempt`; eine bewusst neue Suche erzeugt einen neuen.
  5. `travel_attempt` enthält keine exakten Adressen oder Personenbezüge.
- **Tests:** positiv: vier Kombinationen Sofort/Voraus × Abfahrt/Ankunft; AT-05 (Vorausbuchung für den nächsten Betriebszeitraum, eindeutige Zeit-/Regelprüfung). negativ: Vorlauf, Horizont, DST-Lücke, DST-Doppelstunde ohne Offset, Personenzahl 0. Datentrennung: Versuche zweier Mandanten sind getrennt zählbar.
- **Abhängigkeiten:** intern: WP-M2-01, WP-M2-03.
  - extern: WP-M1-04, WP-M1-06, WP-M1-13, WP-M1-18, WP-M1-21, WP-M1-28, WP-M1-31 (Authentisierter Fahrgast-Principal und Tenantkontext; Idempotenzregister; `/v1/config/effective` für Dienstparameter; Zeit-/DST-Baustein (WP-M1-18); Feldschutz für Unterstützungsmerkmale (WP-M1-31).)
- **Entscheidungen:** D-11 (Aufbewahrung der Anfragedaten – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Buchung für Dritte/Callcenter (`booking_subject=DELEGATED`, BKG-010, UI-06, M7); Serienbuchung (BKG-007/008, M7); Berechnung des Nachfragetrichters (M4).

### WP-M2-12 – Zulässigkeitsprüfung Gebiet und Bedienzeit

- **Ziel:** Jede Anfrage wird vor der Fahrzeugsuche geografisch und zeitlich geprüft; unzulässige Anfragen werden ohne Fahrzeugzuweisung abgelehnt.
- **Anforderungen:** `LH-6.5-OPT-001`
- **Liefergegenstände:**
  - Backend: Vorprüfung im Modul `booking-dispatch` (Schritt 1 aus Kap. 01 §1.4) über Fachoperationen von `service-geo`: Start und Ziel im wirksamen `service_area`, Relationsregel (WP-M2-06), Betriebszeit des Dienstes (WP-M2-03), buchbare Haltepunkte in Reichweite `stop_walk_max_m`; bei Rollstuhlbedarf nur Haltepunkte, deren `stop_revision.accessibility` die Eignung ausweist (Kap. 01 §1.4 Schritt 2 „freigegebene Haltepunkte“; Minimalfeld aus WP-M2-05).
  - Konfigurationsparameter: `stop_walk_max_m`.
  - Kennzahlen (Datenbasis): `Gebietsablehnungen`, `Zeitfensterablehnungen`.
- **Detailverträge vorab:** Prüfreihenfolge und Zuordnung Prüfung → Ablehnungsgrund; Nachweis „keine Fahrzeugzuweisung“ (kein Aufruf des Routing-Workers, kein Hold, keine Planrevision).
- **Akzeptanzkriterien:**
  1. Eine Anfrage außerhalb der Bedienzeiten wird abgelehnt; es wird weder ein Fahrzeugkandidat ermittelt noch ein Hold oder eine Planrevision erzeugt (OPT-001).
  2. Eine Anfrage mit Start oder Ziel außerhalb des Gebiets wird ebenso ohne Fahrzeugzuweisung abgelehnt (OPT-001).
  3. Der Versuch ist als `travel_attempt` mit passendem Primärgrund gespeichert.
  4. Eine zulässige Anfrage erreicht die Kandidatensuche.
  5. Eine Anfrage mit Rollstuhlbedarf erhält nur Abhol- und Zielhaltepunkte, die als geeignet ausgewiesen sind; ist in Reichweite keiner vorhanden, wird mit normiertem Grund abgelehnt statt einen ungeeigneten oder ungeprüften Haltepunkt anzubieten (AT-04, Teil „Stopp zulässig“).
- **Tests:** positiv: zulässige Anfrage. negativ: außerhalb Betriebszeit, außerhalb Gebiet, kein Haltepunkt in Reichweite, Gebiet zum Fahrtzeitpunkt nicht wirksam, Rollstuhlbedarf an ungeeignetem Haltepunkt. AT-04 (Stoppteil). Nachweis über Zähler/Trace, dass der Solver nicht aufgerufen wurde.
- **Abhängigkeiten:** intern: WP-M2-06, WP-M2-11.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Parallelfahrtenverbot und ÖPNV-Alternativen (OPT-012, PTI-004, P2); Berechtigungsprüfung für Tarifansprüche (PAY-010, P2).

### WP-M2-13 – Standardisierte Ablehnungsgründe

- **Ziel:** Nicht erfüllbare Anfragen erhalten einen standardisierten Ablehnungsgrund, der fehlende Kapazität, Regelverbot und „außerhalb Gebiet“ unterscheidet und die Ablehnungsstatistik speist.
- **Anforderungen:** `LH-6.5-OPT-011`
- **Liefergegenstände:**
  - Backend: Aufzählung der Ablehnungsgründe im Modul `booking-dispatch` mit deterministischer Wahl des Primärgrunds und Liste der Sekundärgründe (Kap. 01 §1.6: Primärgrund zählt einen Versuch nur einmal).
  - Datenbank: neue Katalogtabelle `rejection_reason` (Kap. 04 §4.4, ANA/KPI); Befüllung von `travel_attempt.primary_rejection` und `secondary_rejections`.
  - API: Feld für den normierten Ablehnungsgrund in der Antwort von `POST /v1/ride-offers` und in `problem+json`-Codes.
  - Oberfläche: Textbausteine je Grund mit nächster Handlung für UI-01 (Kap. 06 §6.2; Darstellung in WP-M2-37).
  - Kennzahlen (Datenbasis): `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Angebotsquote`.
- **Detailverträge vorab:** Codeliste mit Definition und Priorität für die Primärgrundwahl. Vorschlag dieses Plans, abgeleitet aus den KPI-Definitionen K.1: Kapazität, Personal, Ausstattung, Gebiet, Zeitfenster/Frist (getrennt „Betrieb geschlossen“ und „Frist“), Regelverbot, technischer Fehler (Offener Punkt 14).
- **Akzeptanzkriterien:**
  1. Drei Testanfragen – kein freier Platz, Relation per Regel verboten, Ziel außerhalb Gebiet – liefern drei verschiedene standardisierte Gründe (OPT-011).
  2. Jede Ablehnung hat genau einen Primärgrund; weitere Ursachen stehen nur in `secondary_rejections`.
  3. Dieselbe Anfrage auf demselben Datenstand ergibt denselben Primärgrund.
  4. Ein technischer Fehler (z. B. Routing-Timeout) wird nicht als fachliche Ablehnung gezählt.
- **Tests:** positiv: je Grund ein Szenario. negativ: unbekannter Code wird beim Schreiben abgewiesen. Vorbereitung PH-AT-021 (zehn Versuche, drei Ablehnungen; Auswertung in M4).
- **Abhängigkeiten:** intern: WP-M2-11.
  - extern: WP-M4-46, WP-M4-51 (Kennzahlregister und Nachfragetrichter lesen `travel_attempt` und `rejection_reason`.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Entscheidungsprotokoll der Zuweisung (OPT-022, P2); Alarm `capacity_reject_alert_count` (AUT, M3/M8); Kennzahlberechnung (M4).

### WP-M2-14 – Ressourcenvektor und Mehrpersonenbuchung

- **Ziel:** Je Buchung werden mehrere Personen und getrennte Kapazitätsarten (Sitz, Rollstuhlplatz, Ausstattung) erfasst und als Ressourcenvektor gegen die Fahrzeugkapazität geprüft.
- **Anforderungen:** `LH-6.4-BKG-004`
- **Liefergegenstände:**
  - Backend: Wertobjekt Ressourcenvektor im Modul `booking-dispatch` (Komponentenweise Addition, Vergleich, kein Ersatz einer Dimension durch eine andere; Umrüstzustände nach Kap. 01 §1.4 Schritt 2 laut Detailvertrag); Bedarfsermittlung aus der Anfrage (Personenzahl, Rollstuhlplätze, benötigte Ausstattung).
  - Datenbank: `vehicle.capacity_config` (lesen), `capacity_hold.capacity_vector`; neue Tabelle `passenger_trip` (n:1 zu `booking`, Kap. 04 §4.4) für die Personen/Plätze einer Buchung ohne Diagnosen.
  - API: Felder Personenzahl und Unterstützungsbedarf in `POST /v1/ride-offers` und in der Buchungsressource `/v1/bookings/{id}`.
  - Konfigurationsparameter: `min_capacity_reserve`, `wheelchair_boarding_sec`.
  - Kennzahlen (Datenbasis): `Ausstattungsablehnungen`.
- **Detailverträge vorab:** JSON-Schema Ressourcenvektor (aus WP-M2-01, hier final); Regel für `min_capacity_reserve` je Dimension; Höchstwerte Personen je Buchung in P1 (Offener Punkt 15).
- **Akzeptanzkriterien:**
  1. Eine Buchung mit zwei Sitzplätzen und einem Rollstuhlplatz speichert beide Kapazitätsarten getrennt (BKG-004).
  2. Ist der Rollstuhlplatz auf einem Abschnitt belegt, wird eine weitere Rollstuhlbuchung nicht bestätigt, auch wenn Sitze frei sind (BKG-004; Variante aus PH-AT-004).
  3. Der Ressourcenvektor einer Buchung ist nach Bestätigung unveränderlich Teil von `promise_snapshot`.
  4. Unterstützungsbedarf enthält nur transportrelevante Merkmale, keine Diagnose (Kap. 06 §6.2).
- **Tests:** positiv: Mehrpersonenbuchung. negativ: Rollstuhlplatz belegt, Ausstattung fehlt, Vektor mit unbekannter Dimension. konkurrierend (Kapazität): zwei parallele Rollstuhlbuchungen auf einen Platz – höchstens eine (Ausführung in WP-M2-23). AT-04 (Kapazitätsteil).
- **Abhängigkeiten:** intern: WP-M2-01, WP-M2-02.
  - extern: WP-M0-26 (Befund des Spikes (a) zur Eignung von `capacity_vector jsonb` (WP-M0-26).)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Gruppenbuchung mit individuellen Anforderungen (BKG-009, P2); Buchung für Dritte (BKG-010, P2).

### WP-M2-15 – Fahrzeugkandidatenfilter

- **Ziel:** Für eine zulässige Anfrage werden nur Fahrzeuge betrachtet, die nach Dienstzeit, Standort, Kapazität und Ausstattung geeignet sind.
- **Anforderungen:** `LH-6.5-OPT-002`
- **Liefergegenstände:**
  - Backend: Kandidatenfilter im Modul `booking-dispatch` über den Leseport aus WP-M2-02: Fahrzeug gehört zum Pool des angefragten Dienstes (Offener Punkt 43), Schicht deckt das Zeitfenster, Fahrzeug nicht gesperrt und freigegeben, Ressourcenvektor deckt den Bedarf, Erreichbarkeit des Abholpunkts im Zeitfenster (Fahrzeit aus WP-M2-10 vom geplanten Standort laut Fahrtenplan).
  - Kennzahlen (Datenbasis): `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Kapazitätsablehnungen`.
- **Detailverträge vorab:** Definition „Standort“ in M2 (geplante Position aus `vehicle_run`, da Livepositionen erst mit M3 vorliegen; Offener Punkt 16); Filterreihenfolge und Zuordnung zu Ablehnungsgründen.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug ohne erforderlichen Rollstuhlplatz ist für eine Rollstuhlanfrage kein Kandidat (OPT-002).
  2. Ein Fahrzeug außerhalb seiner Dienstzeit oder ohne gültige Freigabe ist kein Kandidat.
  3. Ein Fahrzeug, das den Abholpunkt im Fenster nicht erreichen kann, ist kein Kandidat.
  4. Bleibt kein Kandidat, nennt die Ablehnung den zutreffenden Grund (Ausstattung, Personal, Kapazität).
- **Tests:** positiv: geeignetes Fahrzeug wird gefunden. negativ: je Filterkriterium ein Ausschlussfall (einschließlich Fahrzeug eines anderen Dienstpools und Fahrzeug eines fremden Mandanten). AT-04 (Fahrzeug mit/ohne geeignete Ausstattung). Vorbereitung E2E-080 (alle Fahrzeuge ausgelastet → Normgrund Kapazität) und E2E-081 (freies Fahrzeug, niemand einsatzbereit → Primärgrund Personalmangel); Auswertung in M4. konkurrierend: entfällt für den lesenden Filter; die verbindliche Prüfung unter Sperre erfolgt in WP-M2-22.
- **Abhängigkeiten:** intern: WP-M2-02, WP-M2-10, WP-M2-14.
  - extern: WP-M3-06, WP-M3-08, WP-M3-34 (Livepositionen (`position_sample`) und echte Einsatzfreigabe ersetzen die Planposition bzw. Fixture; Fahrzeugpool-Zuordnung zu Diensten (WP-M3-06).) – Reihenfolgekonflikt (Zyklus über WP-M3-06, WP-M3-08), siehe Offener Punkt 50
  - extern: WP-M4-17 (Qualifikations-/Schichtport des Dienstplans ersetzt die Schicht-Fixture.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fahrerqualifikation je Fahrzeugwechsel (M3/M4); Lade-/Reichweitenreserven (OPT-021, P3).

### WP-M2-16 – Fahrtenplanmodell und Routeninvarianten

- **Ziel:** Fahrtenpläne sind als versionierte Stoppfolgen gespeichert; jede Planrevision garantiert Einstieg vor Ausstieg und zulässige Belegung auf jedem Abschnitt.
- **Anforderungen:** `LH-6.5-OPT-004`
- **Liefergegenstände:**
  - Backend: Aggregat RunPlanRevision im Modul `booking-dispatch` mit Constraint Validator: je Buchung Einstieg vor Ausstieg, Belegungsvektor je Abschnitt zwischen 0 und Fahrzeugkapazität in jeder Dimension, Stoppreihenfolge eindeutig; Validator wird von Solver-Ergebnisprüfung, Hold und Confirm gemeinsam benutzt.
  - Datenbank: `vehicle_run` (`revision`, `state`); neue Tabelle `route_stop` (Stoppsequenz eindeutig pro Runrevision, Kap. 04 §4.4) mit Haltepunktrevision, Planzeit, Ein-/Ausstiegen sowie Fahrzeit und Distanz des Abschnitts zum Folgestopp (von M4 für Leistungs-/Kostenbezug erwartet); Abbildung der Abschnitte auf `capacity_hold.interval_from/interval_to`. Anlage eines `vehicle_run` je einsetzbarer Schicht/Fahrzeug-Kombination (FK auf `vehicle` und `staff_shift`) mit Startrevision; Zeitpunkt und Auslöser der Anlage laut Detailvertrag (Offener Punkt 48).
  - Ereignisse/Outbox: Planrevision erstellt (Verteilung in WP-M2-28).
  - Schnittstelle für M3: lesbarer Fahrtenplan mit Stoppliste je `vehicle_run`.
- **Detailverträge vorab:** DDL `route_stop`; Definition Abschnitt/Intervallindex und dessen Stabilität über Revisionen (Befund WP-M0-26); Zustandsautomat `vehicle_run.state`; Property-Spezifikation der Invarianten.
- **Akzeptanzkriterien:**
  1. Der automatisierte Routentest (Property-Test über generierte Pläne und alle im Testlauf erzeugten Planrevisionen) findet keine negative und keine über der Kapazität liegende Belegung (OPT-004).
  2. Keine Planrevision enthält einen Ausstieg vor dem zugehörigen Einstieg (OPT-004).
  3. Ein vom Solver geliefertes Ergebnis, das eine Invariante verletzt, wird verworfen und nicht gespeichert.
  4. Jede Planänderung erhöht `vehicle_run.revision` monoton; `route_stop`-Sequenz ist je Revision eindeutig.
  5. Für dieselbe Schicht entsteht auch bei parallelen Erstanfragen genau ein `vehicle_run`; ein Run eines fremden Mandanten ist weder lesbar noch referenzierbar.
  6. Frühere Planrevisionen bleiben lesbar (Nachvollzug für Prüfer und für den Planvergleich in M3).
- **Tests:** positiv: gültige Pläne. negativ: konstruierte Verletzungen je Invariante. Property-Test (Kapazität). konkurrierend (Kapazität): zwei parallele Planänderungen desselben Runs – eine erhält Revisionskonflikt; zwei parallele Erstanlagen eines Runs für dieselbe Schicht – genau ein Run. AT-02 (Reihenfolge und Kapazität zweier gepoolter Fahrten).
- **Abhängigkeiten:** intern: WP-M2-14.
  - extern: WP-M0-26 (Spike-Bericht (a) mit Empfehlung zur Sperr- und Intervallstruktur (WP-M0-26).)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Manuelle Umplanung mit Plan-CAS `/v1/runs/{id}/revisions` (M3); Fahrerereignisse `/v1/driver/events` (M3).

### WP-M2-17 – Einfügeheuristik im Routing-Worker

- **Ziel:** Für eine Anfrage werden mögliche Einfügepositionen für Ein- und Ausstieg in bestehende Umläufe geprüft; eine neue Buchung kann zwischen vorhandene Stopps eingefügt werden.
- **Anforderungen:** `LH-6.5-OPT-003`
- **Liefergegenstände:**
  - Adapter/Worker: Einfügeheuristik in `workers/routing/` (OR-Tools als Solverbaustein, Kap. 01 §1.1): Aufzählung der Positionspaare (Einstieg, Ausstieg) je Kandidatenfahrzeug, Zeitpropagation mit `stop_dwell_sec` und `wheelchair_boarding_sec`, Rückgabe vollständig validierter zulässiger Varianten mit Planrevision, auf der sie berechnet wurden; Leerfahrzeug als Sonderfall (neuer Umlauf).
  - Backend: Aufruf im Modul `booking-dispatch` mit Zeitbudget; bei Solver-Timeout nur ein bereits vollständig validierter Plan, sonst begründete Ablehnung (Kap. 01 §1.4).
  - Konfigurationsparameter: `pooling_allowed`, `stop_dwell_sec`, `wheelchair_boarding_sec`.
- **Detailverträge vorab:** Schnittstelle Kern ↔ Worker (Eingang: Planrevisionen, Bedarf, Fenster, Matrix; Ausgang: Varianten, Grundcodes); Zeitbudget 1 s lokale Kandidatensuche (Planungsdefault, unter D-05); Verhalten bei `pooling_allowed=false` (keine fremden Buchungen gemeinsam disponieren).
- **Akzeptanzkriterien:**
  1. In einen Umlauf mit vorhandenen Stopps A–B wird eine neue Buchung so eingefügt, dass ihr Einstieg und/oder Ausstieg zwischen A und B liegt, sofern alle Bedingungen erfüllt sind (OPT-003).
  2. Jede zurückgegebene Variante besteht den Validator aus WP-M2-16.
  3. Bei `pooling_allowed=false` entsteht keine Variante mit überlappender Mitfahrt verschiedener Buchungen.
  4. Die Laufzeit der Kandidatensuche liegt im Referenzszenario innerhalb des vereinbarten Budgets; Überschreitung führt zu validiertem Teilergebnis oder Ablehnung, nie zu einem ungeprüften Plan.
  5. Jede Variante nennt die Planrevision, gegen die sie gerechnet wurde.
- **Tests:** positiv: Einfügen am Anfang, in der Mitte, am Ende; leeres Fahrzeug. negativ: keine zulässige Position; Solver-Timeout. Referenzrouten und Property-Tests gegen den Validator. AT-02 (gemeinsame Fahrzeugroute zweier überlappender Relationen). Laufzeitmessung im Lasttestgerüst.
- **Abhängigkeiten:** intern: WP-M2-10, WP-M2-15, WP-M2-16.
  - extern: WP-M0-25, WP-M0-27 (Spike-Bericht (b) Solverlaufzeit (WP-M0-27); Gerüst `tests/performance` (WP-M0-25).)
- **Entscheidungen:** D-05 (exakte Routingbudgets und Peaklast – Bau mit Planungsdefault; Produktivwerte nach Beschluss).
- **Größe:** L
- **Nicht im Umfang:** Periodische Reoptimierung über den Einfügefall hinaus; prognostizierte Fahrzeugposition und Fahrzeitunsicherheit (OPT-014, P2); Pausen/Depot (OPT-017, P2); dynamische Haltestellenwahl (OPT-019, P3).

### WP-M2-18 – Harte Grenzen für Wartezeit, Umweg und Verspätung

- **Ziel:** Konfigurierbare Höchstwerte für Wartezeit, Umweg und Verspätung werden als harte Nebenbedingungen geprüft; keine bestätigte Fahrt verletzt sie.
- **Anforderungen:** `LH-6.5-OPT-005`
- **Liefergegenstände:**
  - Backend/Worker: Nebenbedingungen im Constraint Validator und im Solver: Wartezeit ≤ `max_wait_min`, Umweg gegenüber Direktfahrt ≤ `max_detour_pct`, Ankunft innerhalb Zusage plus `arrival_grace_min`; Werte aus der zum Angebotszeitpunkt wirksamen `config_revision` des Dienstes, im Angebot referenziert.
  - Konfigurationsparameter: `max_wait_min`, `max_detour_pct`, `arrival_grace_min`, `pickup_window_min`.
- **Detailverträge vorab:** Messdefinitionen (Bezugspunkt der Wartezeit, Basis der Umwegberechnung, Definition „Verspätung“ gegenüber welchem Fensterrand; Offener Punkt 17); Verhalten bei Parameteränderung (bestehende Zusagen behalten ihre Version, Kap. 01 §1.6).
- **Akzeptanzkriterien:**
  1. In jeder im Testlauf bestätigten Fahrt sind Wartezeit, Umweg und Verspätungsgrenze nachweislich eingehalten (automatischer Prüfer über alle bestätigten Buchungen) (OPT-005).
  2. Eine Variante, die einen Höchstwert überschreitet, wird nicht angeboten.
  3. Eine Änderung eines Höchstwerts wirkt nur auf neue Angebote; bestätigte Buchungen referenzieren weiter ihre Konfigurationsrevision.
  4. Werte außerhalb der Katalogvalidierung (`max_wait_min` 0..240, `max_detour_pct` 0..200) werden vom Konfigurationsregister abgelehnt.
- **Tests:** positiv: Variante knapp innerhalb der Grenze. negativ: knapp außerhalb je Grenze. Property-Test über alle bestätigten Testfahrten. AT-02 (zugesagte Zeitgrenzen gültig).
- **Abhängigkeiten:** intern: WP-M2-17.
  - extern: WP-M1-19, WP-M1-21, WP-M1-22 (Konfigurationsregister mit Präzedenzkette und Wirksamkeitsintervallen für die genannten Parameter.)
- **Entscheidungen:** D-05 (freigegebene Produktionswerte – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Stabilitätsschutz/Nahhorizont (`protected_stop_horizon_min`, OPT-016, P2); Umbuchungsgrenze `max_reassignments` (P2).

### WP-M2-19 – Schutz zugesagter Zeitfenster

- **Ziel:** Bereits zugesagte Abhol- und Ankunftsfenster bleiben bei jeder Neuplanung harte Nebenbedingungen; eine neue Buchung verletzt keines davon.
- **Anforderungen:** `LH-6.5-OPT-006`
- **Liefergegenstände:**
  - Backend/Worker: Zusagefenster jeder bestätigten oder gehaltenen Buchung (`booking.promise_snapshot`) als unveränderliche harte Zeitfenster in Solver-Eingang und Validator; Prüfung erneut beim Hold und beim Confirm gegen die dann aktuelle Planrevision.
  - Datenbank: `booking.promise_snapshot` mit Abhol-/Ankunftsfenster und Konfigurationsrevision; Planzeiten in `route_stop`.
- **Detailverträge vorab:** JSON-Schema `promise_snapshot` (aus WP-M2-01); Regel, welche Buchungszustände geschützt sind (bestätigt und aktiv gehalten).
- **Akzeptanzkriterien:**
  1. Nach Einfügen einer neuen Buchung liegen die Planzeiten aller zuvor bestätigten Buchungen desselben Umlaufs weiterhin in ihren zugesagten Fenstern (OPT-006).
  2. Eine Einfügung, die nur durch Verletzung eines zugesagten Fensters möglich wäre, wird nicht angeboten bzw. beim Hold abgewiesen.
  3. Eine spätere Parameteränderung (`pickup_window_min`) verändert kein gespeichertes Zusagefenster.
  4. Der Prüfer aus WP-M2-18 Kriterium 1 deckt alle Planrevisionen des Testlaufs ab.
- **Tests:** positiv: Einfügen ohne Fensterverletzung. negativ: Einfügung würde bestehendes Fenster reißen. konkurrierend (Kapazität/Zusage): zwei parallele Einfügungen in denselben Umlauf, die einzeln zulässig, zusammen aber fensterverletzend sind – höchstens eine wird gehalten. AT-02.
- **Abhängigkeiten:** intern: WP-M2-17, WP-M2-18.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Autorisierter Ausnahmeprozess der Leitstelle (M3; OPT-015, P2); periodische Verbesserung bestehender Pläne.

### WP-M2-20 – Mehrzielbewertung mit konfigurierbaren Gewichten

- **Ziel:** Zulässige Varianten werden nach Wartezeit, Umwegen, Leerstrecken und Betriebsqualität mit konfigurierbaren Gewichten bewertet; die Rangfolge ist nachvollziehbar.
- **Anforderungen:** `LH-6.5-OPT-008`
- **Liefergegenstände:**
  - Worker/Backend: Bewertungsfunktion nach Kap. 01 §1.4 Schritt 3 (erst Unzulässigkeit = 0, dann weiche Ziele, danach Leerweg/Fahrzeit/Kosten); je Variante gespeicherte Teilwerte und verwendete Gewichtsrevision.
  - Konfigurationsparameter: Gewichte der vier Ziele – im Katalog `kataloge.csv` nicht enthalten; als neue Dienstparameter im Konfigurationsregister anzulegen (Offener Punkt 18).
  - Oberfläche: keine Fahrgastwirkung außer der Reihenfolge der Angebote; Diagnoseausgabe der Teilwerte für Tests.
- **Detailverträge vorab:** Formel, Normierung und Wertebereiche der Gewichte; Messdefinition „Betriebsqualität“ (Offener Punkt 18); deterministische Auflösung bei Gleichstand.
- **Akzeptanzkriterien:**
  1. In einem Referenzszenario mit zwei zulässigen Varianten ändert die Erhöhung eines Gewichts die Rangfolge in die erwartete Richtung; Teilwerte und Gewichte sind je Variante ausgegeben (OPT-008).
  2. Kein Gewicht kann eine unzulässige Variante zulässig machen.
  3. Gleiche Eingaben und gleiche Gewichtsrevision ergeben dieselbe Rangfolge.
- **Tests:** positiv: Rangfolge vor/nach Gewichtsänderung. negativ: Gewicht außerhalb Wertebereich wird abgelehnt; unzulässige Variante bleibt ausgeschlossen.
- **Abhängigkeiten:** intern: WP-M2-17, WP-M2-18.
  - extern: WP-M1-19 (Konfigurationsregister zur Aufnahme zusätzlicher Parameter mit Schema.)
- **Entscheidungen:** D-05 (Produktionsgewichte – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Entscheidungsprotokoll (OPT-022, P2); Repositionierung (OPT-023, P3).

### WP-M2-21 – Angebotsobjekt mit Ablauf und Revisionsbindung

- **Ziel:** Angebote besitzen ID, Ablaufdatum, Preisstand, Abholpunkt und Dienstkennung; ein abgelaufenes oder verändertes Angebot kann nicht blind bestätigt werden.
- **Anforderungen:** `LH-6.4-BKG-002`
- **Liefergegenstände:**
  - Backend: Angebotsbildung im Modul `booking-dispatch` (Kap. 01 §1.4 Schritt 4): Angebot bündelt beste Varianten mit Planrevision, `fare_quote`, Tarif- und Konfigurationsrevision, `expires_at`, Abhol-/Zielhaltepunkt (Revision), Zeitfenster, Dienst; Angebot verspricht keine Kapazität.
  - Datenbank: Persistenz des Angebots (eigene Angebotstabelle oder ausschließlich `fare_quote.snapshot`; im Pflichtenheft nicht festgelegt, Offener Punkt 19).
  - API: Antwort von `POST /v1/ride-offers`; `GET /v1/ride-offers/{id}` liest genau den angebotenen Stand mit ETag, ohne Neuberechnung unter derselben ID (analog Kap. 05 §5.3 Schritt 2).
  - Konfigurationsparameter: Angebotsgültigkeit – kein Parameter im Katalog für eigene Angebote (`partner_offer_ttl_sec` gilt für Partner); Offener Punkt 20.
  - Ereignisse/Outbox: Angebot erstellt (mit `attempt_id`, Dienst, Ablauf) als Datenbasis für M4.
  - Kennzahlen (Datenbasis): `Angebotsquote`, `Buchungskonversion` (Nenner „gültige Angebote“).
- **Detailverträge vorab:** OpenAPI-Schema `RideOffer`; Regel für `expires_at` einschließlich Uhrenreserve; Definition „verändert“ (Planrevision, Tarif, Haltepunkt, Bedingungen) und Reaktion je Fall (neu planen oder `409`).
- **Akzeptanzkriterien:**
  1. Jedes Angebot enthält ID, `expires_at`, Preisstand (`fare_quote`), Abholpunkt und Dienstkennung (BKG-002).
  2. Ein Hold/Confirm auf ein abgelaufenes Angebot liefert `409 OFFER_EXPIRED`; es entsteht keine Buchung (BKG-002).
  3. Ein Hold/Confirm mit veralteter Angebotsrevision (`If-Match`) liefert `412 REVISION_MISMATCH`, ohne `If-Match` `428`; geänderter Preis/Bedingungen liefern `409 REQUOTE_REQUIRED` (BKG-002).
  4. `GET` desselben Angebots liefert unverändert denselben Inhalt und ETag.
  5. Grenzfall: `now >= expires_at` (Serverzeit) gilt als abgelaufen.
- **Tests:** positiv: Angebot lesen, innerhalb Frist halten. negativ: abgelaufen, falscher ETag, fehlender `If-Match`, fremdes Angebot (404), Preisänderung. Zeitgrenze exakt bei `expires_at` mit steuerbarer Uhr. konkurrierend (Kapazität): zwei parallele Holds auf dasselbe Angebot – höchstens einer erfolgreich (Ausführung in WP-M2-22). PH-AT-010 (Einbetreiberanteil: kein Bestätigen gegen abgelaufene Frist).
- **Abhängigkeiten:** intern: WP-M2-09, WP-M2-12, WP-M2-13, WP-M2-18.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Kombinierte Angebote mehrerer Betreiber `/v1/federation/offers/{offer_id}` (M6); Bestätigungsfristen zeitkritischer Angebote (BKG-018, P2); Wartelisten/Alternativen (BKG-017, P3).

### WP-M2-22 – Hold: Kapazitätssperre mit Revisionsabgleich

- **Ziel:** Ein Hold gleicht die Zuweisung atomar mit der aktuellen Kapazitäts- und Routenrevision ab und reserviert die betroffenen Abschnitte befristet; Konkurrenz führt nie zu Doppelbelegung.
- **Anforderungen:** `LH-6.5-OPT-009`
- **Liefergegenstände:**
  - Backend: Hold-Befehl im Modul `booking-dispatch` (Kap. 01 §1.4 Schritt 5): betroffene Kapazitätsabschnitte in stabiler Reihenfolge sperren, `run_revision` vergleichen, Ressourcenvektor je Abschnitt und Dimension prüfen, Einsatzfähigkeit über Leseport erneut prüfen, Planrevision fortschreiben, Hold und Outbox in einer Transaktion schreiben; bei konkurrierender Revision neu planen oder `409 CAPACITY_UNAVAILABLE`. Ablauf: Prüfung bei jedem Zugriff mit Serverzeit plus Expiry-Job, der denselben Kapazitätslock benutzt (Kap. 02 §7.1). Kein Redis-Lock.
  - Datenbank: `capacity_hold` (`run_id`, `run_revision`, `federation_ref`, `capacity_vector`, `interval_from`, `interval_to`, `expires_at`, `state`), `vehicle_run`, `route_stop`; Invariante „Kapazität: Run-Zeilen in stabiler Reihenfolge sperren, Revisionen vergleichen“ (Kap. 04 §4.5).
  - API: Hold-Befehl auf dem Angebot (Pfadvorschlag `POST /v1/ride-offers/{id}/hold`, Offener Punkt 21) mit `Idempotency-Key` und `If-Match`; Antwort mit `hold_id`, `expires_at`. Ausdrückliche Freigabe eines eigenen Holds durch den Client (Abbruch des Checkouts; Kap. 05 §5.4: „vorhandene Holds idempotent freigeben“) – Pfad im Detailvertrag (Offener Punkt 21).
  - Ereignisse/Outbox: Hold gesetzt, freigegeben, abgelaufen.
  - Adapter/Worker: Expiry-Job (fortsetzbar, je Mandant mit Tenantkontext).
  - Konfigurationsparameter: Hold-Dauer 180 s als Planungsdefault (Kap. 02 §7.2); kein Katalogparameter vorhanden (Offener Punkt 20); `min_capacity_reserve`.
- **Detailverträge vorab:** Zustandsautomat `capacity_hold.state` (`HELD → CONSUMED | RELEASED | EXPIRED`); Sperrstrategie und Lock-Reihenfolge laut Spike-Empfehlung; Verhalten „neu planen oder 409“; Bedeutung von `federation_ref` im Einbetreiberfall.
- **Akzeptanzkriterien:**
  1. Konkurrenztest: N parallele Holds auf denselben letzten Platz eines überlappenden Abschnitts führen zu höchstens einem `HELD`; kein Abschnitt ist in irgendeiner Dimension über Kapazität (OPT-009).
  2. Ein Hold gegen eine inzwischen veränderte Routenrevision wird neu bewertet oder mit `409` abgewiesen, nie ungeprüft übernommen (OPT-009).
  3. Ein abgelaufener Hold gibt seine Kapazität frei; Ablauf und gleichzeitiger Zugriff führen nie zu „abgelaufen und belegt“.
  4. Hold und Outbox-Ereignis entstehen gemeinsam oder gar nicht.
  5. Jede der parallelen Transaktionen endet mit Erfolg oder einem definierten Fachgrund; kein unbehandelter Deadlock.
  6. Ein zwischen Angebot und Hold gesperrtes Fahrzeug (Leseport) führt zur Abweisung.
  7. Die Freigabe eines Holds ist idempotent und nur dem Urheber möglich; fremder Hold → 404; ein bereits `CONSUMED` Hold wird durch eine Freigabe nicht verändert.
  8. Die Hold-Dauer wird nur nach erneuter Kapazitätsprüfung verlängert (Kap. 02 §7.2); ohne festgelegte Verlängerungsregel gibt es in P1 keine Verlängerung.
- **Tests:** positiv: Hold, Freigabe. negativ: Kapazität erschöpft, Revision veraltet, Angebot abgelaufen, Fahrzeug gesperrt, Freigabe eines fremden oder verbrauchten Holds. konkurrierend (Kapazität): Freigabe parallel zum Confirm desselben Holds – genau ein Ausgang; 100 parallele Holds (letzter Sitz; belegter Rollstuhlplatz; gegenläufige Abschnittsreihenfolge als Deadlock-Provokation); Hold-Ablauf parallel zum Confirm. Contract-Fall „abgelaufene Holds“ (Kap. 05 §5.8). Grundlage für AT-03 und PH-AT-004.
- **Abhängigkeiten:** intern: WP-M2-16, WP-M2-21.
  - extern: WP-M0-26 (Spike-Bericht (a) Hold/Confirm unter 100 parallelen Transaktionen (WP-M0-26).)
  - extern: WP-M1-01, WP-M1-13, WP-M1-14 (Outbox-Relay; Idempotenzregister; Worker-Tenantkontext.)
  - extern: WP-M3-08, WP-M3-09 (Synchrone Safety-/Readiness-Prüfung im selben Lock (PH-AT-020) ersetzt die Leseport-Prüfung.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
- **Entscheidungen:** D-05 (Peaklast – Lastprofil für die Konkurrenztests; blockiert nicht den Bau).
- **Größe:** L
- **Nicht im Umfang:** Hold-Verlängerung über Partnerregeln und Saga-Koordination mehrerer Holds (M6); Hold über `/v1/partner/reservations` (M6).

### WP-M2-23 – Confirm: atomare Buchungsbestätigung

- **Ziel:** Die Bestätigung konvertiert einen gültigen Hold genau einmal in eine Buchung und reserviert alle erforderlichen Kapazitäten atomar.
- **Anforderungen:** `LH-6.4-BKG-003`
- **Liefergegenstände:**
  - Backend: Confirm-Befehl im Modul `booking-dispatch` (Kap. 01 §1.4 Schritt 6): Hold sperren, Ablauf mit Serverzeit erneut prüfen, `capacity_hold.state` `HELD → CONSUMED`, `booking` mit `promise_snapshot` anlegen, Planrevision festschreiben, Outbox – alles in einer lokalen Transaktion; wiederholte Bestätigung findet dieselbe Buchung.
  - Datenbank: `booking` (`hold_id` UNIQUE, `service_id`, `federation_ref`, `state`, `promise_snapshot`, `revision`), `passenger_trip`; Migration für Fahrgastbezug (`passenger_private`), `created_at`, Urheber und eine je Mandant eindeutige, für Fahrgäste lesbare Buchungsnummer an `booking` (Kap. 04 §4.1; im Kern-DDL nicht vorhanden; Format siehe Offener Punkt 42); CHECK der zulässigen `booking.state`-Werte; je nach Entscheidung aus WP-M2-01 Hülle `journey`/`journey_leg`.
  - API: `POST /v1/bookings` (Confirm mit Hold-Referenz, akzeptiertem Betrag, `Idempotency-Key`, `If-Match`); `GET /v1/bookings/{id}` mit ETag; `GET /v1/bookings` (eigene Buchungen, Cursor-Liste); Betreibersicht derselben Ressourcen mit Dienst-Scope und minimiertem Payload (Unterstützungsbedarf nur für zuständige Rollen; Kap. 05 §5.1 Minimierung).
  - Backend: ImpactProvider „Buchungen“ für die Wirkungsanalyse (von WP-M1-23 erwartet): betroffene bestätigte Buchungen je Dienst/Zeitraum.
  - Ereignisse/Outbox: Buchung bestätigt.
  - Kennzahlen (Datenbasis): `Buchungskonversion`.
- **Detailverträge vorab:** Zustandsautomat lokale Buchung (Offener Punkt 4); OpenAPI `/v1/bookings`; Reihenfolge Zahlung ↔ Confirm im Einbetreiberfall (Offener Punkt 5; Schnittstelle zu WP-M2-30); Objektberechtigung „eigene Buchung“.
- **Akzeptanzkriterien:**
  1. Zwei parallele Zusagen auf den letzten Platz überschreiten nie die physische Kapazität: höchstens eine Buchung wird bestätigt (BKG-003; AT-03).
  2. PH-AT-004: 100 parallele Bestätigungen unterschiedlicher fachlicher Anfragen bei genau einem freien Sitz auf dem überlappenden Abschnitt → höchstens eine neue bestätigte Sitzreservierung, kein Abschnitt über Kapazität, keine negative Belegung, alle Abweisungen mit eindeutigem Grund; Wiederholung mit freiem Sitz bei belegtem Rollstuhlplatz und mit veränderter Routenrevision (BKG-003).
  3. Confirm auf abgelaufenen Hold liefert `409 HOLD_EXPIRED`; keine Buchung, Kapazität frei.
  4. Wiederholtes Confirm desselben Holds liefert dieselbe Buchung; `UNIQUE(tenant_id,hold_id)` verhindert eine zweite.
  5. Buchung, Hold-Verbrauch, Planrevision und Outbox sind nach einem erzwungenen Abbruch entweder vollständig vorhanden oder vollständig nicht.
  6. Ein Fahrgast liest nur eigene Buchungen; fremde ID → 404.
  7. Betriebspersonal liest nur Buchungen der Dienste seines Scopes; eine Buchung eines anderen Dienstes oder Mandanten ergibt 404 (Nachweis „fremde regionale Buchungen“ für WP-M1-08); der Betreiberpayload enthält keine Kontaktdaten über das Erforderliche hinaus.
  8. Jede Buchung trägt eine Buchungsnummer, die je Mandant eindeutig ist und in Bestätigung, UI-04 und Nachrichten identisch erscheint.
  9. `promise_snapshot` ist nach der Bestätigung unveränderlich (Baustein aus WP-M1-27); eine nachträgliche Konfigurations- oder Tarifänderung verändert ihn nicht (PH-AT-018, Teil Zusage).
- **Tests:** positiv: Hold → Confirm → Buchung lesbar. negativ: abgelaufen, freigegeben, abweichender Betrag, fremder Hold, Betriebspersonal ohne Dienst-Scope, direktes UPDATE auf `promise_snapshot`. konkurrierend (Kapazität): AT-03, PH-AT-004 mit 100 parallelen Transaktionen unter der Runtime-Rolle; Confirm parallel zum Expiry-Job; Abbruch mitten in der Transaktion. Contract-Fall „letzter Platz“ (Kap. 05 §5.8, Einbetreiberanteil).
- **Abhängigkeiten:** intern: WP-M2-22.
  - extern: WP-M1-06, WP-M1-08, WP-M1-14, WP-M1-28 (Fahrgastidentität (`passenger_private`, `/v1/me`), Dienst- und Objektberechtigung (WP-M1-08), Outbox-Relay.)
  - extern: WP-M1-17, WP-M1-23, WP-M1-26, WP-M1-27, WP-M1-31 (Zustandsautomaten-Rahmen (WP-M1-17), Policy-Port für die Regelprüfung bei der Bestätigung (WP-M1-26), Baustein „gebundener Snapshot“ (WP-M1-27), Feldschutz für Unterstützungsmerkmale (WP-M1-31), Wirkungsanalyse-Rahmen (WP-M1-23).)
- **Entscheidungen:** D-11 (Aufbewahrung personenbezogener Buchungsdaten – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Bearbeitung bestätigter Buchungen (`booking_change`, BKG-006, P2); Boarding-Code/QR (BKG-019, P2; siehe Offener Punkt 44); Buchung durch die Disposition in UI-07 (M3); Fahrerzuweisung im Sinne der Driver-App, Check-in, Fahrtende (M3); Journey-Saga (M6).

### WP-M2-24 – Idempotenz der Buchungsbefehle

- **Ziel:** Hold, Confirm und Storno sind durch Idempotenzschlüssel gegen Doppelausführung geschützt; eine Wiederholung erzeugt nie mehrere aktive Buchungen.
- **Anforderungen:** `LH-6.4-BKG-012`
- **Liefergegenstände:**
  - Backend: Anbindung der Buchungsbefehle an das Idempotenzregister mit Scope `(auth_principal, tenant_context, operation_name, Idempotency-Key)` und kanonischem Request-Fingerprint (Kap. 05 §5.1); Prüfung des vorhandenen Eintrags nach Authentisierung, aber vor erneutem ETag-/Ablauftest; fachliche Unique-Constraints als zweite Sicherung (`booking.hold_id`, `capacity_hold.federation_ref`).
  - Datenbank: `idempotency_record` (`principal_ref`, `command_scope`, `key`, `request_sha256`, `response_json`, `expires_at`); Entwurfswert Response-Cachehaltung 72 Stunden (Kap. 05 §5.1).
  - API: Pflichtheader `Idempotency-Key` an `POST /v1/ride-offers/{id}/hold`, `POST /v1/bookings`, Storno; `409 IDEMPOTENCY_CONFLICT`, `409 OPERATION_IN_PROGRESS`.
- **Detailverträge vorab:** Fingerprint-Definition je Befehl (Ressourcen-ID, relevante Header, Body-Kanonisierung); `command_scope`-Namen; Verhalten nach Cacheablauf (fachliche Constraints bleiben).
- **Akzeptanzkriterien:**
  1. Die Wiederholung desselben Confirm-Requests (gleicher Schlüssel, gleicher Body) liefert dieselbe Buchungs-ID und erzeugt keine zweite aktive Buchung, auch nach Ablauf des Angebots (BKG-012).
  2. Gleicher Schlüssel mit anderem Angebot oder anderem Betrag liefert `409 IDEMPOTENCY_CONFLICT` ohne Ausführung (PH-AT-011).
  3. Schlüssel kollidieren nicht zwischen Mandanten, Akteuren und Operationstypen (PH-AT-011).
  4. Zwei verschiedene Schlüssel auf demselben Angebot/Hold erzeugen höchstens eine Buchung (ETag und Unique-Constraint).
  5. Ein Request, der während einer laufenden identischen Ausführung eintrifft, erhält dasselbe Ergebnis oder `409 OPERATION_IN_PROGRESS`, nie eine zweite Ausführung.
- **Tests:** positiv: identische Wiederholung. negativ: Fingerprint-Abweichung, fehlender Schlüssel. konkurrierend (Kapazität/Geld): 100 identische Requests parallel → eine Buchung; zwei verschiedene Schlüssel parallel → eine Buchung. PH-AT-011 (Buchungsanteil).
- **Abhängigkeiten:** intern: WP-M2-22, WP-M2-23.
  - extern: WP-M1-02, WP-M1-13 (Idempotenzregister und einheitliches Fehlermodell (PH-AT-011 Grundfunktion).)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Idempotenz der PSP-Aufrufe (WP-M2-29, WP-M2-30); Idempotenz der Partneroperationen (M6).

### WP-M2-25 – Statushistorie der Buchung

- **Ziel:** Jede Buchung besitzt eine vollständige, unveränderliche Statushistorie mit Änderungsgrund, aus der ein Prüfer den Ablauf von der Anfrage bis zum Abschluss rekonstruieren kann.
- **Anforderungen:** `LH-6.4-BKG-013`
- **Liefergegenstände:**
  - Backend: jeder Zustandsübergang von `booking` schreibt in derselben Transaktion einen Historieneintrag (alter/neuer Zustand, Grundcode, Akteur, Zeitpunkt, `booking.revision`, Korrelations-ID); Verknüpfung zu `travel_attempt`, Angebot, Hold und Zahlung.
  - Datenbank: neue append-only Historientabelle für Buchungszustände mit UPDATE/DELETE-Verbot analog `reject_mutation()`; Tabellenname im Pflichtenheft nicht festgelegt (`booking_change` ist in Kap. 04 §4.4 für die Änderungsannahme vorgesehen; Offener Punkt 22).
  - API: `GET /v1/bookings/{id}/history` (Pfadvorschlag) für berechtigte Rollen; Fahrgast sieht eine reduzierte Sicht.
  - Oberfläche: Verlauf in UI-04 (WP-M2-43) und für Prüfer im Operator-Web (Listenansicht).
- **Detailverträge vorab:** DDL der Historientabelle; Katalog der Änderungsgründe (gemeinsam mit Stornogründen aus WP-M2-26/27); Sichtbarkeitsregel Fahrgast/Betreiber.
- **Akzeptanzkriterien:**
  1. Für eine Testbuchung lässt sich allein aus Historie und verknüpften Objekten die Kette Anfrage → Angebot → Hold → Bestätigung → (Storno oder Abschluss) mit Zeitpunkten, Akteuren und Gründen lückenlos rekonstruieren (BKG-013).
  2. Kein Zustandsübergang ohne Historieneintrag: die Anzahl der Einträge entspricht `booking.revision`.
  3. Historieneinträge können unter der Runtime-Rolle weder geändert noch gelöscht werden.
  4. Jeder Eintrag hat einen Änderungsgrund aus dem Katalog.
- **Tests:** positiv: vollständiger Lebenslauf. negativ: UPDATE/DELETE abgewiesen; Übergang ohne Grund abgewiesen; unzulässiger Übergang laut Automat abgewiesen; Fahrgast ruft die Prüfersicht ab (403). konkurrierend: zwei parallele Zustandsübergänge derselben Buchung – genau einer gewinnt, Historie und `booking.revision` bleiben deckungsgleich. Datentrennung: Historie fremder Mandanten nicht lesbar.
- **Abhängigkeiten:** intern: WP-M2-23.
  - extern: WP-M1-07, WP-M1-12, WP-M1-46 (Audit (`audit_event`) für Akteurbezug; Rollenprüfung „Prüfer“.)
  - extern: WP-M3-29, WP-M3-30 (Ausführungszustände (Check-in, Fahrtende, No-Show) schreiben in dieselbe Historie.) – Reihenfolgekonflikt (Zyklus), siehe Offener Punkt 50
- **Entscheidungen:** D-11 (Aufbewahrungsfrist der Historie – blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Entscheidungsprotokoll der Disposition (OPT-022, P2); Auskunfts-/Löschjobs (M8).

### WP-M2-26 – Storno mit Fristen und Gründen

- **Ziel:** Buchungen können kontrolliert nach konfigurierbaren Fristen und mit Grund storniert werden; Kapazität und Fahrtenplan sind danach konsistent.
- **Anforderungen:** `LH-6.4-BKG-005`
- **Liefergegenstände:**
  - Backend: Storno-Befehl im Modul `booking-dispatch`: Regelprüfung (Frist, Zustand, Akteur), Zustandsübergang der Buchung, Freigabe der Kapazitätsabschnitte unter demselben Kapazitätslock, neue Planrevision ohne die Stopps der Buchung (Fahrerauftrag), Historieneintrag mit Grund, Outbox – eine Transaktion.
  - Datenbank: `booking`, `capacity_hold` (Freigabe), `vehicle_run`, `route_stop`, Historientabelle.
  - API: `POST /v1/bookings/{id}/cancel` (Pfadvorschlag) mit `Idempotency-Key`, `If-Match` und Grundcode; Antwort mit Erstattungshinweis (aus WP-M2-31).
  - Konfigurationsparameter: Stornofristen und zulässige Gründe je Dienst – im Katalog `kataloge.csv` nicht enthalten; als neue Dienstparameter anzulegen (Offener Punkt 23).
  - Ereignisse/Outbox: Buchung storniert (mit Grund), Planrevision geändert.
  - Kennzahlen (Datenbasis): `Buchungsstornoquote Fahrgast`.
- **Detailverträge vorab:** Stornoregel (Fristen relativ zu welchem Zeitpunkt, Verhalten nach Fristablauf, Gebührenfolge – soweit P1); Grundkatalog; ein- oder zweistufiger Ablauf im Einbetreiberfall (Kap. 06 §6.4 beschreibt zweistufig; Offener Punkt 24).
- **Akzeptanzkriterien:**
  1. Nach einem Storno ist die Kapazität auf allen betroffenen Abschnitten in allen Dimensionen wieder verfügbar und eine neue Buchung auf denselben Platz möglich (BKG-005).
  2. Der Fahrtenplan (Fahrerauftrag) enthält nach dem Storno keine Stopps der stornierten Buchung mehr und trägt eine neue Revision; verbleibende Zusagen bleiben eingehalten (BKG-005).
  3. Ein Storno außerhalb der konfigurierten Frist oder ohne zulässigen Grund wird mit definiertem Code abgewiesen.
  4. Wiederholtes Storno derselben Buchung ist wirkungsgleich (keine doppelte Kapazitätsfreigabe).
  5. Storno und parallele Neubuchung desselben Platzes ergeben nie eine Überbelegung.
- **Tests:** positiv: Storno innerhalb Frist. negativ: außerhalb Frist, unzulässiger Zustand, fehlender Grund, fremde Buchung. konkurrierend (Kapazität): Storno parallel zu Hold/Confirm auf denselben Abschnitt; doppeltes Storno parallel. AT-07 (Storno nach Disposition: Route, Info, ggf. Erstattung konsistent – Routenteil).
- **Abhängigkeiten:** intern: WP-M2-23, WP-M2-25.
  - extern: WP-M1-19, WP-M1-20 (Konfigurationsregister zur Aufnahme der Stornoparameter.)
  - extern: WP-M3-27 (Zustellung der geänderten Planrevision an die Driver-App.)
- **Entscheidungen:** D-05 (freigegebene Stornoregeln des Pilotbetreibers – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Stornierungs- und Erstattungsregeln nach Zeitpunkt, Grund und Zahlungslage (PAY-006, P2); Teilstorno und `/v1/federation/journeys/{journey_id}/cancellation-quotes` (M6); No-Show-Regeln (`no_show_grace_min`, BKG-015, P2).

### WP-M2-27 – Stornoursache Fahrzeugausfall

- **Ziel:** Ein Storno wegen Fahrzeugausfall ist von einem normalen Kundenstorno unterscheidbar; Auswertung und Abrechnungslogik verwenden den korrekten Grund.
- **Anforderungen:** `LH-6.4-BKG-014`
- **Liefergegenstände:**
  - Backend: Stornogründe mit Verursacherklasse (Fahrgast, Betreiber, Fahrzeugausfall) im Modul `booking-dispatch`; betreiberseitiger Storno-Befehl mit Rollenprüfung; Grund wird in Historie, Outbox-Ereignis und Erstattungsentscheidung (WP-M2-31) übernommen und ist nachträglich nicht änderbar.
  - API: Grundcode im Storno-Befehl und in `GET /v1/bookings/{id}`; betreiberseitiger Aufruf über denselben Endpunkt mit Betreiberrolle.
  - Ereignisse/Outbox: „Buchung storniert“ trägt Grund und Verursacherklasse als typisierte Felder.
  - Kennzahlen (Datenbasis): `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber` (Ausschluss laut Katalog: „Fahrzeugausfall getrennt“).
- **Detailverträge vorab:** Grundkatalog mit Verursacherklasse; Ereignisschema; Regel, wer welchen Grund setzen darf.
- **Akzeptanzkriterien:**
  1. Zwei Testbuchungen – eine vom Fahrgast, eine wegen Fahrzeugausfall storniert – sind in Buchungsressource, Historie und Ereignis mit unterschiedlichem Grund ausgewiesen (BKG-014).
  2. Die für Reports bereitgestellte Datenbasis zählt die Fahrgaststornierung nur unter `Buchungsstornoquote Fahrgast` und den Fahrzeugausfall getrennt; ein Abfragetest belegt dies (BKG-014).
  3. Die Erstattungslogik (WP-M2-31) wählt bei Fahrzeugausfall die betreiberverursachte Regel, nicht die Kundenstornoregel (BKG-014).
  4. Ein Fahrgast kann den Grund „Fahrzeugausfall“ nicht selbst setzen.
- **Tests:** positiv: beide Stornoarten. negativ: Fahrgast setzt Betreibergrund (403); Grund nachträglich ändern (abgewiesen); Betriebspersonal ohne Dienst-Scope storniert (404). konkurrierend (Geld): Kundenstorno und Betreiberstorno derselben Buchung parallel – genau ein Storno mit genau einem Grund und genau einer Erstattungsentscheidung.
- **Abhängigkeiten:** intern: WP-M2-26.
  - extern: WP-M3-53, WP-M3-54 (Störungsablauf bei Fahrzeugausfall löst den Betreiberstorno fachlich aus (Leitstelle).) – Reihenfolgekonflikt (Zyklus über WP-M3-54), siehe Offener Punkt 50
  - extern: WP-M4-46 (Kennzahlregister berechnet die Stornoquoten aus der Datenbasis.)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Automatische Ersatzdisposition bei Fahrzeugausfall (M3); Erstattungsregeln im Detail (PAY-006, P2).

### WP-M2-28 – Planrevisionen verteilen und Clients synchronisieren

- **Ziel:** Routenänderungen sind versioniert und werden an betroffene Fahrer und Fahrgäste verteilt; ein Client mit veralteter Revisionsnummer wird erkannt und synchronisiert.
- **Anforderungen:** `LH-6.5-OPT-010`
- **Liefergegenstände:**
  - Backend: je Planänderung Outbox-Ereignis mit `aggregate_id` = Run, `aggregate_revision`, Liste betroffener Buchungen; adressierte Projektion je Empfänger (Fahrgast nur eigene Buchung, Fahrer nur zugeordneter Run; Kap. 02 §4.3); Revisionsprüfung bei jedem schreibenden Clientzugriff (`If-Match`, `412 REVISION_MISMATCH` mit aktuellem Stand).
  - API: ETag = Revision auf `GET /v1/bookings/{id}` und auf der Lesesicht des Fahrtenplans; Änderungsstrom für Clients (SSE oder WebSocket – im Pflichtenheft nicht festgelegt, Offener Punkt 25) mit Mandanten- und Empfängerbindung; Nachladen bei Lücke.
  - Ereignisse/Outbox: Planrevision veröffentlicht; Consumer deduplizieren `(consumer, event_id)` und prüfen Aggregatrevision (`inbox`).
  - Schnittstelle für M3: Fahrer-Projektion des Fahrtenplans mit Revision (Verbraucher ist die Driver-App).
- **Detailverträge vorab:** Ereignisschema Planrevision; Protokoll des Änderungsstroms (Authentisierung, Wiederaufnahme, Reihenfolge je Aggregat); Regel „relevante Änderung für Fahrgast“.
- **Akzeptanzkriterien:**
  1. Jede Routenänderung erzeugt eine neue Revision und je betroffenem Fahrgast und Fahrer-Empfängerkanal genau eine fachlich wirksame Benachrichtigung der Änderung (OPT-010).
  2. Ein Client, der mit veralteter Revisionsnummer schreibt oder liest, wird erkannt (`412` bzw. Revisionsvergleich im Strom) und erhält den aktuellen Stand; nach Synchronisation zeigt er die aktuelle Revision (OPT-010).
  3. Doppelt oder ungeordnet zugestellte Ereignisse setzen den Clientstand nie auf eine ältere Revision zurück.
  4. Ein Fahrgast erhält keine Daten anderer Buchungen desselben Umlaufs.
  5. Nach Verbindungsabbruch schließt der Client die Lücke über einen Snapshotabruf.
- **Tests:** positiv: Änderung erreicht verbundenen Client. negativ: veralteter ETag, fremder Kanal (kein Zugriff). Reihenfolge: Revision 8 vor 7, Revision 8 dreifach (analog PH-AT-017). Datentrennung: zwei Mandanten am selben Strom-Endpunkt. Simulierter Fahrer-Client als Testdouble bis M3.
- **Abhängigkeiten:** intern: WP-M2-16, WP-M2-23.
  - extern: WP-M1-11, WP-M1-14 (Outbox-Relay, Inbox-Deduplikation, Ereignisadressierung (PH-AT-017).)
  - extern: WP-M3-24, WP-M3-27, WP-M3-29 (Driver-App als realer Empfänger; Offline-Check-in auf alter Planrevision (Contract-Fall Kap. 05 §5.8).) – Reihenfolgekonflikt (Zyklus über WP-M3-27, WP-M3-29), siehe Offener Punkt 50
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fahrerplan-Latenzziel p95 ≤ 10 s bis Clientdarstellung (Nachweis M5); Ereigniszustellung von Fahrgaständerungen an Fahrer und Leitstelle (BKG-016, P2); Push/SMS-Benachrichtigung (WP-M2-34).

### WP-M2-29 – PSP-Adapter und Provider-Ereignisse

- **Ziel:** Das Zahlungsmodul kann Authorize, Capture, Void, Refund und Status über einen austauschbaren PSP-Adapter ausführen und signierte Provider-Ereignisse genau einmal fachlich wirksam verarbeiten.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `backend/adapters/psp` mit Schnittstelle Authorize/Capture/Void/Refund/Status, stabiler eigener Kommandoreferenz je Aufruf (kein neuer Charge-Key bei Retry), Timeout → Ergebnis „unbestimmt“; Implementierung gegen den PSP-Simulator, optional gegen die PSP-Sandbox.
  - API: `POST /v1/payments/provider-events/{provider}` mit PSP-spezifischer Signaturprüfung (nicht generisches Partner-HMAC), Größen- und Zeitfensterprüfung, 202 erst nach dauerhaftem Commit.
  - Datenbank: neue Tabelle `payment_event` (PSP-Ereignisse dedupliziert, Kap. 04 §4.4) mit Unique auf Provider und Ereignis-ID plus Payloadhash; `inbox`.
  - Regel: PSP-Aufrufe nie innerhalb offener SQL-Transaktionen (Leitlinie 3); rohe Zahlungsdaten erreichen die API nie.
- **Detailverträge vorab:** Adapterschnittstelle mit Fehlerklassen; OpenAPI `/v1/payments/provider-events/{provider}`; Abbildung PSP-Ereignistypen → interne Ereignisse; Secretreferenzen für Signaturschlüssel.
- **Akzeptanzkriterien:**
  1. Ein identisches Provider-Ereignis, 100-fach zugestellt, wird einmal fachlich wirksam; Wiederholungen werden als Duplikat quittiert (Contract-Fall „100 identische Webhooks“, Kap. 05 §5.8).
  2. Gleiche Ereignis-ID mit verändertem Payload wird abgewiesen und als Sicherheitsereignis gemeldet.
  3. Ungültige Signatur oder abgelaufenes Zeitfenster → Ablehnung ohne Verarbeitung.
  4. Ein Timeout des Adapters liefert „unbestimmt“; eine Wiederholung verwendet dieselbe Kommandoreferenz.
  5. Ein Architektur-/Integrationstest belegt, dass während eines PSP-Aufrufs keine Datenbanktransaktion offen ist.
- **Tests:** positiv: alle fünf Operationen gegen den Simulator. negativ: Signaturfehler, Replay außerhalb Zeitfenster, manipulierte `tenant_id`. konkurrierend (Geld): 100 identische Webhooks parallel; Webhook parallel zur synchronen Antwort. Fault-Injection: Timeout, verlorene Antwort, Duplikat.
- **Abhängigkeiten:** intern: WP-M2-01.
  - extern: WP-M0-23, WP-M0-29 (PSP-Simulator mit steuerbaren Fehlern (WP-M0-23); Spike-Bericht (d) PSP-Sandbox (WP-M0-29).)
  - extern: WP-M1-14, WP-M1-16, WP-M1-36, WP-M1-46 (Webhook-Grundlage, Inbox, Secret-Verwaltung, Sicherheitsereignisse.)
- **Entscheidungen:** D-03 (Teil: PSP-Auswahl für Einbetreiberzahlung – ohne Beschluss nur PSP-Simulator; blockiert Produktivschaltung, nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Tokenisierung und Onlinezahlung als abnahmepflichtige Anforderung (PAY-005, P2; siehe Offener Punkt 26); Settlement-Dateiabgleich und Reconciliation (PAY-014, P2/M6); Mehrparteienzahlung (M6).

### WP-M2-30 – Zahlungsautomat unabhängig vom Buchungsstatus

- **Ziel:** Der Zahlstatus wird in einem eigenen Automaten getrennt vom Buchungsstatus geführt; eine fehlgeschlagene oder unklare Zahlung verändert eine vereinbarte Fahrt nie stillschweigend.
- **Anforderungen:** `LH-6.10-PAY-004`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`, Aggregat PaymentIntent mit Automat nach Kap. 03 §4.2 (`CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED`; `AUTHORIZATION_FAILED`, `VOID_PENDING → VOIDED`, `CAPTURE_UNKNOWN → RECONCILING`); Kopplung an Hold/Confirm über explizite Fachoperationen und Ereignisse, nicht über gemeinsame Zustandsfelder; Abgleich unbestimmter Ergebnisse per Statusabfrage mit Lease/Fencing; Autorisierung exakt über `fare_quote.pay_at_platform_minor`.
  - Datenbank: `payment_intent` (`provider`, `provider_ref`, `state`, `authorized_minor`, `captured_minor`, `refunded_minor`, `revision`; CHECKs erstattet ≤ erfasst ≤ autorisiert); CHECK der zulässigen `state`-Werte; `operation` für die Zahlungsoperation. `payment_intent.journey_id` ist im Kern-DDL Pflicht-FK auf `journey` (Offener Punkt 3).
  - API: Zahlungsblock (UI-Projektion des Zahlstatus) in `GET /v1/bookings/{id}`; Statusabfrage der laufenden Operation über `GET /v1/operations/{operation_id}`; Zahlungsauswahl/SCA-Rückkehr für den Checkout.
  - Ereignisse/Outbox: Zahlung autorisiert, fehlgeschlagen, eingezogen, unbestimmt.
- **Detailverträge vorab:** Transitionstabelle Zahlung; Ablaufdiagramm Einbetreiber-Checkout (Hold → Authorize → Confirm → Capture) mit Fehlerpfaden aus Kap. 02 §7.4 (Autorisierung abgelehnt, PSP-Timeout, Capture fehlgeschlagen, SCA länger als Hold); Capture-Zeitpunkt als Testannahme bis D-03 (Offener Punkt 5); Zahlungsplan für Vorausbuchungen (Kap. 02 §7.2, Offener Punkt 27).
- **Akzeptanzkriterien:**
  1. Zahl- und Buchungsstatus sind getrennt gespeichert und getrennt abrufbar; jede Kombination aus dem Detailvertrag ist darstellbar (PAY-004).
  2. Schlägt ein Capture nach bestätigter Buchung fehl, bleibt die Buchung bestätigt, der Zahlstatus zeigt den Fehler bzw. Klärfall; die Fahrt wird weder storniert noch als abgeschlossen oder bezahlt markiert (PAY-004).
  3. AT-15: PSP-Timeout bei der Autorisierung → Zahlstatus unbestimmt, Abgleich über die PSP-Referenz; ein später eintreffendes identisches Ereignis führt zu genau einer Belastung und einem klaren Endstatus.
  4. Autorisierung abgelehnt → Hold freigegeben, keine Buchung bestätigt, anderer Zahlungsweg möglich.
  5. SCA dauert länger als der Hold → kein abgelaufener Platz wird bestätigt; eine nachträglich erfolgreiche Autorisierung wird aufgehoben (Contract-Fall „SCA länger als Hold“).
  6. Die CHECKs erstattet ≤ erfasst ≤ autorisiert halten unter parallelen Schreibversuchen.
- **Tests:** positiv: Authorize → Confirm → Capture. negativ: Ablehnung, Capture-Fehler, abweichender Betrag. konkurrierend (Geld): doppelte SCA-Rückkehr; Webhook und Statusabfrage parallel; zwei Worker auf derselben Operation (Fencing); Prozessabbruch zwischen PSP-Erfolg und lokalem Commit → Abgleich ohne zweite Belastung. AT-15.
- **Abhängigkeiten:** intern: WP-M2-09, WP-M2-23, WP-M2-29.
  - extern: WP-M1-14, WP-M1-15 (`operation`-Ressource mit Lease/Fencing und `/v1/operations/{id}`; Outbox-Relay.)
- **Entscheidungen:** D-03 (Teil: PSP und Capture-Zeitpunkt – Bau gegen Simulator mit Testannahme; blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Guthaben, Gutscheine, Barzahlung, Rechnungszahlung (PAY-008, PAY-009, PAY-012, PAY-016, P2); Journal-/Erlösbuchung der Zahlung (M4/M6); Streitfälle (`DISPUTED`, M6).

### WP-M2-31 – Void und Erstattung nach Storno

- **Ziel:** Nach einem Storno wird die Zahlung passend zurückgeführt (Autorisierung aufheben oder Erstattung beauftragen) und der Fahrgast erhält einen zutreffenden Erstattungshinweis.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Reaktion des Moduls `fare-payment` auf „Buchung storniert“: bei `AUTHORIZED` Void, bei `CAPTURED` Erstattung in der durch Stornoregel und Grund bestimmten Höhe; Zustände `REFUND_PENDING → REFUNDED` bzw. `PARTIALLY_REFUNDED`; „erstattet“ erst nach PSP-Bestätigung, davor „Erstattung beauftragt“ (Kap. 06 §6.4).
  - Datenbank: neue Tabelle `refund_case` (Kap. 04 §4.4) mit eindeutiger PSP-Kommandoreferenz; `payment_intent.refunded_minor`.
  - API: Erstattungshinweis (Betrag, Status) in der Storno-Antwort und in `GET /v1/bookings/{id}`.
  - Ereignisse/Outbox: Erstattung beauftragt, bestätigt, fehlgeschlagen.
- **Detailverträge vorab:** Minimalregel P1 für Erstattungshöhe je Grund (Kundenstorno in Frist, Fahrzeugausfall) als konfigurierte Testregel; Zustandsautomat `refund_case`; Verhalten bei fehlgeschlagener Erstattung (Buchung bleibt storniert, Refund offen, Kap. 02 §7.4).
- **Akzeptanzkriterien:**
  1. Storno einer autorisierten, nicht eingezogenen Buchung führt zu genau einem Void; kein Einzug.
  2. Storno einer eingezogenen Buchung führt zu genau einer Erstattung; ein doppelt zugestelltes PSP-Ereignis oder ein wiederholtes Storno erhöht `refunded_minor` nicht.
  3. Parallele Erstattungsaufträge überschreiten nie den erfassten Betrag.
  4. Bei Fahrzeugausfall wird die betreiberverursachte Regel angewendet (WP-M2-27).
  5. Scheitert die Erstattung, bleibt die Buchung storniert und der offene Betrag sichtbar.
- **Tests:** positiv: Void, Vollerstattung. negativ: Erstattung > erfasst abgewiesen. konkurrierend (Geld): zwei parallele Erstattungen; Webhook-Duplikat; Prozessabbruch nach PSP-Erfolg (analog PH-AT-009, Zahlungsanteil). AT-07 (Erstattungsteil).
- **Abhängigkeiten:** intern: WP-M2-26, WP-M2-30.
- **Entscheidungen:** D-03 (Teil), D-05 (freigegebene Erstattungsregel des Piloten) – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Vollständige Stornierungs- und Erstattungsregeln nach Zeitpunkt, Grund und Zahlungslage (PAY-006, P2); Teilstorno, Provisionskorrektur, Transferreversal (M6); Kulanzgutschriften (PAY-015, P2).

### WP-M2-32 – Asynchroner Nachrichtenversand mit Zustellversuchen

- **Ziel:** Nachrichten werden asynchron mit eindeutiger Nachrichten-ID, Wiederholung und Fehlerbehandlung versendet; ein Provider-Timeout führt nicht zu unkontrollierten Mehrfachbenachrichtigungen.
- **Anforderungen:** `LH-6.12-COM-003`
- **Liefergegenstände:**
  - Backend: Modul `communication-support`, Aggregate Notification (fachlicher Auftrag) und NotificationAttempt (Zustellversuch); Auftrag entsteht aus einem Outbox-Ereignis über die Inbox (einmal je Ereignis und Empfänger); Versand-Worker mit begrenztem Backoff, Höchstzahl Versuche, Dead-Letter und Betreiberwarnung; Retry und Zustellstatus ändern keine Buchungszusage (Kap. 01 §1.3).
  - Datenbank: neue Tabellen `notification` und `notification_attempt` (Kap. 04 §4.4: ein fachlicher Auftrag, n Zustellversuche) mit Unique auf fachlichem Schlüssel (Ereignis, Empfänger, Kanal), RLS; Kontaktdaten nur als Referenz auf `passenger_private`.
  - Adapter/Worker: `backend/adapters/notification` mit Kanalschnittstelle Push, SMS, E-Mail; je Versuch wird die Nachrichten-ID als Idempotenzreferenz an den Provider übergeben; Simulator-Provider mit steuerbarem Timeout/Fehler. Interne Fachoperation „Betriebsnachricht senden“ für andere Module (u. a. SMS-Zustellkanal für die Kontaktverifikation aus WP-M1-28).
  - API: `/v1/notifications` (Lesen von Auftrag und Zustellstatus für berechtigte Rollen).
  - Ereignisse/Outbox: Nachricht versendet, endgültig fehlgeschlagen.
- **Detailverträge vorab:** Zustandsautomat Auftrag/Versuch (inklusive „unbekannt“ nach Timeout); Retry-Regel (Intervalle, Höchstzahl – im Pflichtenheft für Nachrichten nicht festgelegt; Offener Punkt 28); Kanalschnittstelle; OpenAPI `/v1/notifications`.
- **Akzeptanzkriterien:**
  1. Der Versand läuft außerhalb der auslösenden Fachtransaktion; ein Ausfall des Providers verzögert oder verhindert keine Buchung (COM-003).
  2. Jede Nachricht hat eine eindeutige Nachrichten-ID, die in allen Zustellversuchen und beim Provider identisch ist (COM-003).
  3. Bei Provider-Timeout wird kontrolliert wiederholt: dieselbe Nachrichten-ID, begrenzte Versuchszahl, höchstens eine fachliche Nachricht je Ereignis, Empfänger und Kanal (COM-003).
  4. Ein mehrfach zugestelltes auslösendes Ereignis erzeugt keinen zweiten Auftrag.
  5. Nach Erschöpfung der Versuche ist der Kanalfehler dokumentiert und für den Betreiber sichtbar; kein stiller Verlust.
  6. Nachrichteninhalt und Kontaktdaten erscheinen weder in Logs noch in Metriklabels; `/v1/notifications` liefert nur berechtigten Rollen des eigenen Mandanten Auftrag und Zustellstatus (fremde ID → 404).
- **Tests:** positiv: Versand je Kanal über Simulator. negativ: dauerhafter Providerfehler → dokumentierter Fehlschlag; ungültiger Kontakt. konkurrierend (Sicherheit/Einmaligkeit): dasselbe Ereignis parallel an zwei Worker → ein Auftrag; Provider-Timeout mit spätem Erfolg → keine zweite Nachricht. Datentrennung: Nachrichten fremder Mandanten nicht lesbar.
- **Abhängigkeiten:** intern: WP-M2-01.
  - extern: WP-M1-01, WP-M1-14, WP-M1-28, WP-M1-30, WP-M1-36 (Outbox-Relay und Inbox; verifizierte Kontaktkanäle des Fahrgasts in `passenger_private` (WP-M1-28); Benachrichtigungs-Port mit Trennung Betriebsmitteilung/Marketing (WP-M1-30); Worker-Tenantkontext; Secret-Verwaltung für Providerzugänge (WP-M1-36).)
  - extern: WP-M0-08 (RabbitMQ in der lokalen Umgebung (WP-M0-08).)
- **Entscheidungen:** D-05 (Betrieb/Hosting bestimmt die Provider für Push, SMS, E-Mail – Bau gegen Simulator; blockiert Produktivschaltung), D-11 (Protokollierungs- und Löschfrist der Nachrichten – blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Kommunikationsstatus mit „zugestellt/gelesen“ als Fachfunktion (COM-009, P2); Massen-Push (COM-004, P2); SMS-Kundenkontakt für verifizierte Nummern als eigene Anforderung (COM-006, P2); Fahrer-Push (COM-007, P2); Ruhezeiten (`notification_quiet_hours`, COM-010, P2); `/v1/webhook-subscriptions`, `/v1/support/cases` (M7/M8).

### WP-M2-33 – Nachrichtenvorlagen je Sprache, Verkehrsprodukt, Ereignistyp

- **Ziel:** Nachrichten werden aus versionierten Vorlagen je Sprache, Verkehrsprodukt und Ereignistyp erzeugt; ein Sprachwechsel verwendet die richtige Vorlage, ohne die Buchungsreferenz zu verlieren.
- **Anforderungen:** `LH-6.12-COM-002`
- **Liefergegenstände:**
  - Backend: Vorlagenauflösung im Modul `communication-support` nach (Ereignistyp, Verkehrsprodukt/Dienst, Sprache, Kanal) mit definierter Rückfallkette; typisierte Platzhalter (Buchungsnummer, Treffpunkt, Zeitfenster, ETA); Pflichtplatzhalter „Buchungsreferenz“ je buchungsbezogener Vorlage; verwendete Vorlagenrevision wird am Auftrag gespeichert.
  - Datenbank: neue Tabelle `channel_template_revision` (Kap. 04 §4.4) mit Revision, Gültigkeit, RLS.
  - API: Vorlagenpflege unter `/v1/notifications` (Unterpfad im Detailvertrag) oder Seed-basierte Pflege in P1 (Offener Punkt 29).
  - Oberfläche: Sprachwahl des Fahrgasts in UI-05 (WP-M2-36).
- **Detailverträge vorab:** DDL `channel_template_revision`; Platzhalterkatalog je Ereignistyp; Rückfallregel bei fehlender Sprache/fehlendem Verkehrsprodukt; Liste der P1-Sprachen (Kap. 06 §6.1: mindestens Deutsch und vorbereitete Internationalisierung; Offener Punkt 29).
- **Akzeptanzkriterien:**
  1. Für dieselbe Buchung und denselben Ereignistyp wird nach Wechsel der Fahrgastsprache die Vorlage der neuen Sprache verwendet; die Buchungsreferenz ist in beiden Nachrichten identisch enthalten (COM-002).
  2. Vorlagen sind je Sprache, Verkehrsprodukt und Ereignistyp getrennt hinterlegt; zwei Dienste mit unterschiedlichem Verkehrsprodukt erhalten unterschiedliche Texte (COM-002).
  3. Eine Vorlage ohne Pflichtplatzhalter „Buchungsreferenz“ kann für buchungsbezogene Ereignisse nicht aktiviert werden.
  4. Fehlt eine Vorlage, greift die dokumentierte Rückfallkette; fehlt auch diese, wird ein Kanalfehler dokumentiert statt einer leeren Nachricht.
  5. Bereits versendete Nachrichten referenzieren unverändert ihre Vorlagenrevision.
- **Tests:** positiv: zwei Sprachen × zwei Verkehrsprodukte × Ereignistypen. negativ: fehlender Platzhalterwert, fehlende Vorlage, Aktivierung ohne Buchungsreferenz. Sicherheit: Platzhalterwerte werden kanalgerecht maskiert (kein Einschleusen von Markup).
- **Abhängigkeiten:** intern: WP-M2-32.
  - extern: WP-M1-29 (Sprachpräferenz am Fahrgastkonto.)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Redaktionsworkflow für Vorlagen (COM-013, P3); In-App-Banner (COM-005, P2); einfache Sprache/Hilfetexte (PAX-016, P2).

### WP-M2-34 – Auslöser transaktionaler Benachrichtigungen

- **Ziel:** Bei Bestätigung, relevanter ETA-Änderung, Ankunft, Storno und Ausfall wird je betroffenem Fahrgast eine passende Nachricht erzeugt oder ein Kanalfehler dokumentiert.
- **Anforderungen:** `LH-6.12-COM-001`
- **Liefergegenstände:**
  - Backend: Ereignisverbraucher im Modul `communication-support` für die fünf Szenarien: Buchung bestätigt (WP-M2-23), Planrevision mit relevanter ETA-Änderung (WP-M2-28), Fahrzeug am Treffpunkt angekommen, Buchung storniert (WP-M2-26), Ausfall/Betreiberstorno (WP-M2-27); zusätzlich Verbrauch des Informationsauftrags aus `promise.changed` (WP-M1-27) über denselben Weg; Kanalwahl nach verfügbaren verifizierten Kontakten; Betriebsnachricht unabhängig von Marketing-Opt-in (Kap. 05 §5.7).
  - Konfigurationsparameter: `customer_notify_before_pickup_min` (Erinnerung vor Abholung, Katalog J.7: „reale ETA-Änderungen priorisiert“); Schwelle „relevante ETA-Änderung“ – kein Katalogparameter (Offener Punkt 30).
  - Ereignisse/Outbox: Verbrauch der M2-Ereignisse; Vertrag für die erst mit M3 entstehenden Ereignisse „Ankunft“ und „Fahrzeugausfall“ (bis dahin per Testereignis ausgelöst).
- **Detailverträge vorab:** Zuordnungstabelle Ereignis → Ereignistyp der Nachricht → Empfänger → Kanäle; Definition „relevante ETA-Änderung“; Ereignisschemas „Ankunft“ und „Ausfall“ gemeinsam mit M3.
- **Akzeptanzkriterien:**
  1. Jedes der fünf Szenarien (Bestätigung, relevante ETA-Änderung, Ankunft, Storno, Ausfall) erzeugt für den betroffenen Fahrgast eine passende Nachricht mit der richtigen Vorlage oder einen dokumentierten Kanalfehler (COM-001).
  2. Eine ETA-Änderung unterhalb der Relevanzschwelle erzeugt keine Nachricht.
  3. Storno durch den Fahrgast und Ausfall führen zu unterschiedlichen Nachrichtentypen.
  4. Hat der Fahrgast keinen nutzbaren Kanal, wird dies als Kanalfehler dokumentiert; die Buchung bleibt unberührt.
  5. Nur der Fahrgast der betroffenen Buchung erhält die Nachricht; andere Mitfahrende desselben Umlaufs nur bei eigener Betroffenheit.
  6. Betriebsnachrichten werden unabhängig von einer fehlenden Marketingeinwilligung zugestellt; es wird kein Marketinginhalt über diesen Weg versendet.
- **Tests:** positiv: je Szenario ein Ende-zu-Ende-Test bis zum Simulator-Provider. negativ: kein Kanal, Provider-Ausfall, Ereignis für stornierte Buchung. Einmaligkeit: doppeltes Ereignis → eine Nachricht. AT-07 (Infoteil), AT-01 (Benachrichtigungsteil).
- **Abhängigkeiten:** intern: WP-M2-23, WP-M2-26, WP-M2-27, WP-M2-28, WP-M2-33.
  - extern: WP-M1-27, WP-M1-30 (Ereignis `promise.changed` mit Informationsauftrag (WP-M1-27); Einwilligungs-/Zweckprüfung Betriebsmitteilung (WP-M1-30).)
  - extern: WP-M3-29, WP-M3-53 (Ereignisse „Ankunft am Stopp“ und „Fahrzeugausfall“ aus Driver-App bzw. Leitstelle.)
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell als Quelle laufender ETA-Werte (bis dahin Plan-ETA aus der Planrevision).)
- **Entscheidungen:** D-11 (Rechtsgrundlage/Löschfrist Kontaktdaten – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Weitergabe des Abholfensters durch den Fahrgast (PAX-015, P2); Incident-Massenmitteilungen (COM-011, P2); Ruhezeiten (COM-010, P2).

### WP-M2-35 – Passenger-PWA: Grundgerüst

- **Ziel:** Die Passenger-Webanwendung ist als installierbare PWA mit Anmeldung, Mandanten-/Dienstkontext, API-Client und barrierefreiem Seitenrahmen lauffähig.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: `web/passenger` auf dem Designsystem: App-Shell mit Navigation „Meine Reisen | Hilfe“ (Kap. 06 §6.2), Routing für UI-01 bis UI-05, Anmeldung per OIDC Authorization Code + PKCE, Fehler-/Leer-/Offlinezustände, Live-Region für asynchrone Statusmeldungen, Anzeige von Dienst, Status und Datenstand (Kap. 06 §6.1); Service Worker nur für App-Shell-Assets, keine Offline-Buchung (Kap. 01 §1.5).
  - API: generierter typisierter Client aus den OpenAPI-Dateien von WP-M2-01; zentrale Behandlung von `problem+json`, 401/403/404/409/412/428/429, `Idempotency-Key`-Erzeugung und Wiederverwendung nach Verbindungsabbruch.
  - Tests: Komponententest- und E2E-Gerüst (Browser), automatische Barrierefreiheitsprüfung in CI.
- **Detailverträge vorab:** Routen- und Zustandskatalog UI-01 bis UI-05; Textkatalog Deutsch mit Internationalisierungsschlüsseln; Regel, welche Daten der Service Worker cachen darf (keine personenbezogenen Antworten).
- **Akzeptanzkriterien:**
  1. Anmeldung, Abmeldung und gesperrte Sitzung funktionieren gegen den Test-IdP; ohne Anmeldung sind nur öffentliche Seiten erreichbar.
  2. Seitenrahmen erfüllt die Vorgaben aus Kap. 06 §6.1: Tastaturbedienung, sichtbarer Fokus, Reflow bei 320 CSS-Pixeln, 200 % Textvergrößerung, Status nie nur als Farbe.
  3. Die PWA ist installierbar; bei fehlender Verbindung erscheint ein klarer Offlinezustand ohne Buchungsmöglichkeit.
  4. Ein wiederholter Befehl nach Verbindungsabbruch verwendet denselben Idempotenzschlüssel.
- **Tests:** positiv: Login-Flow, Navigation. negativ: abgelaufenes Token, 403/404-Darstellung, Offline. Sicherheit: kein Token im lokalen persistenten Speicher außerhalb der festgelegten Ablage; Cache enthält keine personenbezogenen API-Antworten.
- **Abhängigkeiten:** intern: WP-M2-01.
  - extern: WP-M0-05, WP-M0-09 (Web-Gerüst `web/` (WP-M0-05); Keycloak-Realm und Testidentitäten (WP-M0-09).)
  - extern: WP-M1-06, WP-M1-10, WP-M1-43, WP-M1-44 (Designsystem und Anmeldung; `/v1/me`; Sitzungssperre.)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Native Passenger-App (`mobile/passenger`, M7); Callcenter UI-06 (M7); Mandantenwechsel für Mitarbeiter (M1).

### WP-M2-36 – UI-05 Konto: Kontakt, Sprache, Favoriten

- **Ziel:** Die in M1 gelieferte UI-05-Basis (Kontakt, Sprache, Favoriten) ist in die Passenger-PWA eingebunden; Favoritenorte tragen validierte Koordinaten und optional einen Haltepunktbezug – die Voraussetzungen für Ortssuche und Benachrichtigung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-05 in `web/passenger` mit Abschnitten Kontakt (Verifikationsstatus sichtbar), Sprache, Favoriten (Name, bestätigter Ort über die Ortssuche bzw. Haltestellenliste); Zustände „Verifikation fehlt“, leer („noch keine Favoriten“), Fehler, veralteter Stand (412).
  - Backend/Datenbank: `passenger_preference` (Kap. 04 §4.4, IAM/PAX) besteht aus WP-M1-29 und wird hier nicht neu angelegt; Ergänzung der Favoriten um validierte Koordinate und optionalen Bezug auf `stop_revision.stop_id` (in M1 ausdrücklich auf den Haltepunktstamm aus M2 verschoben); Prüfung der Haltepunktreferenz gegen den Haltepunktstamm (WP-M2-05).
  - API: eigenes Profil über `/v1/me` (WP-M1-29); Erweiterung des Favoritenschemas laut Detailvertrag; `/v1/passengers` bleibt Betreibersicht aus M1.
- **Detailverträge vorab:** Erweiterung des Profil-Schemas und der DDL `passenger_preference` aus WP-M1-29 (Abstimmung mit M1, Offener Punkt 31); Höchstzahl und Felder der Favoriten; Verhalten eines Favoriten, dessen Haltepunkt deaktiviert wurde.
- **Akzeptanzkriterien:**
  1. Ein Fahrgast kann einen Favoriten anlegen, umbenennen und löschen; der Favorit enthält validierte Geokoordinaten.
  2. Die gewählte Sprache wird gespeichert und von Oberfläche und Nachrichten (WP-M2-33) verwendet.
  3. Ein Fahrgast sieht und ändert nur eigene Daten; fremde ID → 404.
  4. Nicht verifizierte Kontaktkanäle sind als solche gekennzeichnet.
  5. Ein Favorit mit Bezug auf einen inzwischen inaktiven Haltepunkt bleibt als Ort nutzbar, wird aber nicht als buchbarer Haltepunkt vorgeschlagen.
- **Tests:** positiv: Favorit-CRUD, Sprachwechsel. negativ: ungültige Koordinate, nicht existierende oder fremde Haltepunktreferenz, fremdes Konto, veralteter ETag (412). Datentrennung: zwei Mandanten, gleiche Subject-ID.
- **Abhängigkeiten:** intern: WP-M2-05, WP-M2-35.
  - extern: WP-M1-28, WP-M1-29, WP-M1-31 (Fahrgastkonto mit verifizierten Kontakten (`passenger_private`, WP-M1-28), Fahrgastprofil mit `passenger_preference`, `/v1/me` und UI-05-Basis (WP-M1-29), Feldschutz für PII.)
- **Entscheidungen:** D-11 (Löschfristen für Präferenzen – blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Vertreter/Delegation (`delegated_access`, `/v1/delegations`, M7); Auskunft/Löschung (`/v1/privacy/requests`, M8); Einwilligungsverwaltung über die Standortzustimmung hinaus (`consent_revision`, M1/M8); wiederkehrende Ziele und zuletzt gewählte Routen (PAX-011, P2).

### WP-M2-37 – UI-01 Startansicht und Buchung in fünf Schritten

- **Ziel:** Die Startansicht zeigt Start, Ziel, Reisezeit, Verfügbarkeit und eigene anstehende Buchungen; eine Standardfahrt ist in höchstens fünf Hauptschritten buchbar.
- **Anforderungen:** `LH-6.3-PAX-001`
- **Liefergegenstände:**
  - Oberfläche: UI-01 nach Kap. 06 §6.2: Felder Von/Nach, Zeit (Abfahrt/Ankunft, Datum, Uhrzeit, Zeitzone), Reisende, Unterstützung (Rollstuhlplatz); Verfügbarkeitshinweis des Dienstes (in Betrieb/außer Betrieb, nächste Betriebszeit); Liste eigener anstehender Buchungen; Zustände „Keine Abdeckung“, „ungültige Zeit“ mit primärem Ablehnungsgrund und nächster Handlung; DST-Auswahl mit UTC-Offset; Suche abbrechbar.
  - API: `POST /v1/ride-offers`, `GET /v1/bookings` (Filter „anstehend“), `/v1/services` (Betriebszeiten).
  - Nachweisdokument: Schrittzählung des Standardbuchungswegs.
- **Detailverträge vorab:** Definition „Hauptschritt“ und „Standardfahrt“ (Offener Punkt 32); Definition „Verfügbarkeit“ auf der Startansicht (Offener Punkt 32); Wireframe-Abgleich mit Kap. 06 §6.2.
- **Akzeptanzkriterien:**
  1. Die Startansicht zeigt Start, Ziel, Reisezeit, Verfügbarkeit und die eigenen anstehenden Buchungen (PAX-001).
  2. Ein E2E-Test bucht eine Standardfahrt (ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel) von der Startansicht bis zur Bestätigung in höchstens fünf Hauptschritten ohne Umweg über andere Bereiche (PAX-001).
  3. Eine leere Angebotsliste nennt den primären Ablehnungsgrund und eine nächste Handlung.
  4. Eine uneindeutige Ortszeit (DST) verlangt die Auswahl des Offsets; eine nicht existente Ortszeit verlangt Korrektur.
  5. Alle Eingaben sind per Tastatur und Screenreader bedienbar.
- **Tests:** positiv: Fünf-Schritte-E2E; anstehende Buchung sichtbar. negativ: außerhalb Gebiet/Betriebszeit, ungültige Zeit, Abbruch der Suche (bestätigt keine Buchung). AT-01 (Fahrgastanteil Anfrage → Angebot → bestätigte Buchung), AT-19 (Teil).
- **Abhängigkeiten:** intern: WP-M2-21, WP-M2-23, WP-M2-35.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Mehrere Verkehrsmittel und Umstiege in der Suche (PAX-010, P2); Partnerausfall-Zustand (M6); Schnellbuchung wiederkehrender Ziele (PAX-011, P2).

### WP-M2-38 – Ortssuche mit fünf Eingabearten

- **Ziel:** Die Ortssuche akzeptiert Adresse, POI, Haltestellenname, Favorit und Kartenpunkt und liefert für jede Eingabeart validierte Geokoordinaten.
- **Anforderungen:** `LH-6.3-PAX-002`
- **Liefergegenstände:**
  - Backend: Ortssuch-Fachoperation (BFF) mit Geocoding-Adapter für Adresse und POI (anbieterneutral, Arbeitsstand OSM-basiert), Haltestellensuche über `/v1/stops`, Favoriten aus `passenger_preference`, Rückwärtsauflösung eines Kartenpunkts; Validierung: Koordinate im Wertebereich, Zuordnung zum nächsten buchbaren Haltepunkt bzw. Hinweis „außerhalb Bediengebiet“.
  - API: Ortssuch-Endpunkt (im Endpunktkatalog nicht benannt; Pfadvorschlag in WP-M2-01, Offener Punkt 33); Rate-Limit; keine Personen-/Ortssuchen als Metriklabels (Kap. 05 §5.1).
  - Oberfläche: Eingabefeld mit Vorschlagsliste (Typ gekennzeichnet), Favoritenauswahl, „Punkt auf Karte wählen“ als Option neben der Texteingabe; klar bestätigter Treffpunkt (Kap. 06 §6.2).
- **Detailverträge vorab:** Antwortschema Ortstreffer (Typ, Bezeichnung, Koordinate, optional `stop_id`, Quelle/Datenstand); Geocoder-Schnittstelle; Regel für Mehrdeutigkeit.
- **Akzeptanzkriterien:**
  1. Je ein Test für Adresse, POI, Haltestellenname, Favorit und Kartenpunkt liefert validierte Geokoordinaten, die als Start oder Ziel in `POST /v1/ride-offers` akzeptiert werden (PAX-002).
  2. Ein nicht auflösbarer Text liefert eine verständliche Meldung, keine Koordinate 0/0.
  3. Inaktive Haltestellen erscheinen nicht in den Vorschlägen.
  4. Fällt der Geocoder aus, bleiben Haltestellenname, Favorit und Kartenpunkt nutzbar.
  5. Suchtexte erscheinen weder in Metriklabels noch in Logs oberhalb der festgelegten Stufe.
- **Tests:** positiv: fünf Eingabearten. negativ: leere/zu kurze Eingabe, Treffer außerhalb Gebiet, Geocoder-Timeout. Sicherheit: Rate-Limit; Eingaben werden nicht als Markup interpretiert.
- **Abhängigkeiten:** intern: WP-M2-05, WP-M2-35, WP-M2-36.
- **Entscheidungen:** D-09 (Karten-/Geocodingquelle und Lizenz – Bau mit OSM-Arbeitsstand; blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Dynamische Haltestellenwahl mit Fußwegvergleich (PAX-017, P3); zuletzt gewählte Ziele (PAX-011, P2).

### WP-M2-39 – Standortfreigabe nur nach Zustimmung

- **Ziel:** Der Gerätestandort wird nur nach aktiver Zustimmung verwendet; ohne Zustimmung bleibt die manuelle Ortswahl vollständig nutzbar.
- **Anforderungen:** `LH-6.3-PAX-003`
- **Liefergegenstände:**
  - Oberfläche: Schaltfläche „Meinen Standort verwenden“ in UI-01; die Standortabfrage des Browsers wird ausschließlich durch diese Nutzeraktion ausgelöst, nie beim Seitenaufruf; verständlicher Zustand bei Ablehnung, Zeitüberschreitung oder ungenauer Position; manuelle Eingabe bleibt jederzeit sichtbar und fokussierbar.
  - Backend: Standort wird nur als Suchkoordinate der aktuellen Anfrage verarbeitet und nicht dauerhaft gespeichert (Speicherung der Zustimmung selbst siehe Offener Punkt 34).
- **Detailverträge vorab:** Zustimmungstext; Verhalten je Browserstatus (erlaubt, abgelehnt, dauerhaft blockiert, nicht verfügbar).
- **Akzeptanzkriterien:**
  1. Ohne Nutzeraktion wird keine Standortabfrage ausgelöst (automatisierter Browsertest).
  2. Bei abgelehnter Standortfreigabe lässt sich eine Buchung mit manuell gewähltem Start und Ziel vollständig abschließen (PAX-003).
  3. Bei erteilter Freigabe wird der Standort als Startvorschlag angeboten und muss bestätigt werden.
  4. Der Standort wird nicht an Dritte außer dem Geocoding-Adapter in der dokumentierten Form übermittelt und nicht persistiert.
- **Tests:** positiv: Freigabe erteilt. negativ: abgelehnt, blockiert, Timeout – jeweils Buchung per manueller Eingabe. Tastatur-/Screenreaderprüfung des Dialogs.
- **Abhängigkeiten:** intern: WP-M2-38.
- **Entscheidungen:** D-11 (Datenrechte – nur falls Zustimmung persistiert werden soll).
- **Größe:** S
- **Nicht im Umfang:** Hintergrundortung und Live-Fahrzeugposition (PAX-013, P2); native Berechtigungsdialoge (M7).

### WP-M2-40 – Karte mit Bediengebiet, Haltepunkten und Textzugang

- **Ziel:** Die Karte zeigt Bediengebiet und zulässige Ein- und Ausstiegspunkte; derselbe Inhalt ist als Text zugänglich, sodass der Abholpunkt ohne Karteninteraktion gewählt werden kann.
- **Anforderungen:** `LH-6.3-PAX-004`
- **Liefergegenstände:**
  - Oberfläche: Kartenkomponente in UI-01/UI-02 mit wirksamem Bediengebiet und buchbaren Haltepunkten; gleichwertige Haltestellenliste (Name, Entfernung/Gehzeit, Barrierefreiheitsangabe) mit Auswahlfunktion (Kap. 06 §6.1: „Karte immer mit gleichwertiger Haltestellenliste“); Textbeschreibung des Bediengebiets.
  - API: `/v1/service-areas` (Lesesicht für Fahrgäste, generalisierte Geometrie), `/v1/stops` (Umkreissuche, nur aktive und für den Dienst zulässige).
  - Adapter: Kartenkacheln über konfigurierbare Quelle.
- **Detailverträge vorab:** Lesesicht-Schemas für Fahrgäste (keine internen Felder wie `approved_by`); Sortierung und Umfang der Liste; Verhalten bei Ausfall der Kartenquelle.
- **Akzeptanzkriterien:**
  1. Die Karte zeigt das zum gewählten Zeitpunkt wirksame Bediengebiet und nur zulässige Ein-/Ausstiegspunkte (PAX-004).
  2. Ein Fahrgast wählt den Abholpunkt ausschließlich über die Textliste per Tastatur und Screenreader und schließt damit die Buchung ab (PAX-004).
  3. Fällt die Kartenquelle aus, bleibt die Textliste vollständig funktionsfähig.
  4. Karte und Liste zeigen dieselbe Menge an Haltepunkten.
- **Tests:** positiv: Auswahl über Karte und über Liste. negativ: Kartenquelle nicht erreichbar; kein Haltepunkt in Reichweite. AT-19 (ohne Kartenpflicht vollständig ausführbar – Teil).
- **Abhängigkeiten:** intern: WP-M2-04, WP-M2-05, WP-M2-35.
- **Entscheidungen:** D-09 (Kartenquelle/Lizenz – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Live-Fahrzeugposition (PAX-013, P2); Sperrbereiche (GEO-004, P2).

### WP-M2-41 – UI-02 Angebotsliste und Angebotsdetails

- **Ziel:** Eine Buchungsanfrage zeigt die verfügbaren Optionen mit Fußweg, Zeitfenster, Fahrtdauer und Preis; die angezeigten Daten sind genau der Angebotsstand, der beim Bestätigen verwendet wird.
- **Anforderungen:** `LH-6.3-PAX-005`
- **Liefergegenstände:**
  - Oberfläche: UI-02 mit Ergebniskarten je Angebot: Abhol- und Zielhaltepunkt, Fußweg (Distanz/Gehzeit), Abhol- und Ankunftsfenster, Fahrtdauer, Preis mit Aufschlüsselung, Ablaufzeit; Details und Bedingungen; Zustand „Abgelaufen“ mit erneuter Suche; Zustand „unbestätigte Barrierefreiheit“ soweit Daten vorliegen. Die Oberfläche hält Angebots-ID und ETag und übergibt beide unverändert an Hold/Confirm.
  - API: Antwort von `POST /v1/ride-offers`, `GET /v1/ride-offers/{id}`.
- **Detailverträge vorab:** Feldliste UI ↔ `RideOffer`; Darstellung des Preises aus `fare_components`; Quelle der Fußwegangabe (WP-M2-10).
- **Akzeptanzkriterien:**
  1. Jede Option zeigt Fußweg, Zeitfenster, Fahrtdauer und Preis (PAX-005).
  2. Die im Checkout bestätigten Werte (Haltepunkte, Fenster, Preis) sind identisch mit dem in UI-02 angezeigten Angebotsstand; ein E2E-Test vergleicht Anzeige, Angebotsrevision und `promise_snapshot`/`fare_quote` der entstandenen Buchung (PAX-005).
  3. Hat sich das Angebot geändert oder ist es abgelaufen, wird nicht still bestätigt; die Oberfläche zeigt den neuen Stand und verlangt erneute Auswahl.
  4. Der Preis wird nie clientseitig berechnet oder gerundet.
- **Tests:** positiv: Anzeige = Buchung. negativ: Angebot läuft während der Anzeige ab; Preis ändert sich (`REQUOTE_REQUIRED`); `412` nach Revisionswechsel. Tastatur/Screenreader. AT-19 (Teil).
- **Abhängigkeiten:** intern: WP-M2-09, WP-M2-21, WP-M2-37.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Kombinierte Angebote mit Betreiberausweis und externen Tickets (M6); ÖPNV-Alternativen (PTI-002, P2).

### WP-M2-42 – UI-03 Checkout

- **Ziel:** Der Fahrgast prüft Reise, Preis und Bedingungen, löst Hold, Zahlung und Bestätigung aus und erhält ein eindeutiges Ergebnis – auch bei Holdverlust, SCA, Preisänderung und Doppelklick.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-03 nach Kap. 06 §6.3 im Einbetreiberzuschnitt: Zusammenfassung, Preisaufschlüsselung mit Steuer-/Gebührenbestandteilen, Stornoregel, PSP-Zahlungsauswahl (gehostete PSP-Komponente, keine Kartendaten in der Anwendung), Pflichtzustimmung zu Beförderungs- und Stornobedingungen getrennt von Marketing, Schaltfläche „Zahlungspflichtig buchen – Betrag“; „Plätze werden reserviert“; Countdown der bestätigten Restfrist des Holds; Schaltflächensperre während laufender Operation; nach Verbindungsabbruch Wiederaufnahme derselben Operation; Timeout zeigt „Bestätigung wird geprüft“ mit Betrag und Kontaktmöglichkeit.
  - API: Hold (`POST /v1/ride-offers/{id}/hold`), `POST /v1/bookings`, Zahlungsstart/SCA-Rückkehr, `GET /v1/operations/{operation_id}`.
- **Detailverträge vorab:** Zustandsdiagramm des Checkouts mit allen UI-03-Fehlerzuständen (Holdverlust, SCA, Preisänderung, Doppelklick); Texte für unbestimmte Zustände; Regel zur Wiederverwendung des Idempotenzschlüssels.
- **Akzeptanzkriterien:**
  1. Erfolgsfall: Auswahl → Hold → Zahlung → Bestätigung zeigt Buchungsnummer und Zahlungsstatus getrennt.
  2. Läuft der Hold während der Zahlung ab, zeigt die Oberfläche keinen Erfolg, sondern prüft Zahlung/Reservierung und bietet ein neues Angebot an.
  3. Doppelklick und Neuladen erzeugen keine zweite Buchung und keine zweite Belastung.
  4. Bei unbestimmtem Zahlungs- oder Buchungsergebnis behauptet die Anzeige weder Erfolg noch Ablehnung.
  5. Der angezeigte Betrag ist der Betrag des `fare_quote`; bei `REQUOTE_REQUIRED` wird der neue Preis zur ausdrücklichen Zustimmung angezeigt.
  6. Der gesamte Checkout ist per Tastatur und Screenreader bedienbar.
- **Tests:** positiv: Erfolgsfall. negativ: Holdablauf, SCA-Abbruch, Zahlungsablehnung, Preisänderung, Verbindungsabbruch nach Absenden. konkurrierend (Geld/Kapazität): zwei Tabs bestätigen dasselbe Angebot. AT-03 (über die Oberfläche mit zwei Clients), AT-15 (Anzeige), AT-19 (Teil).
- **Abhängigkeiten:** intern: WP-M2-24, WP-M2-30, WP-M2-41.
- **Entscheidungen:** D-03 (Teil: PSP-Komponente – Bau gegen Simulator; blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Mehrbetreiber-Checkout mit externen Ticketabschnitten und Teilkauf (M6); Gutscheine/Guthaben (P2); Quittung (PAY-007, P2).

### WP-M2-43 – UI-04 Buchungsdetail mit Status und ETA ohne Neuladen

- **Ziel:** Fahrgäste sehen Buchungsnummer, Status, Treffpunkt, aktuelle ETA und gegebenenfalls die Fahrzeugidentifikation; eine ETA-Änderung erscheint nachvollziehbar ohne Neuladen.
- **Anforderungen:** `LH-6.3-PAX-006`
- **Liefergegenstände:**
  - Oberfläche: UI-04 Detailansicht nach Kap. 06 §6.4: Buchungsnummer, fachlicher Status, Zahlungsstatus getrennt, Treffpunkt, verbindliche Zusage (Fenster) getrennt von aktueller ETA, Fahrzeugidentifikation sobald zugewiesen, „Letzter Stand“ mit Zeit; Aktualisierung über den Änderungsstrom aus WP-M2-28 mit zurückhaltender Live-Region; sichtbarer Hinweis, wenn sich die ETA geändert hat (alt → neu); Zustand „Daten veraltet“ bei unterbrochenem Strom.
  - API: `GET /v1/bookings/{id}` mit ETag; Änderungsstrom; Felder ETA mit Datenstand und Quelle.
  - Backend: ETA-Projektion je Buchung aus der Planrevision (Planzeit am Abholstopp).
- **Detailverträge vorab:** Feldliste Buchungsdetail; welche Fahrzeugmerkmale der Fahrgast sehen darf (Offener Punkt 35); Darstellung der ETA-Änderung; Abgrenzung Plan-ETA (M2) und Prognose-ETA (M4).
- **Akzeptanzkriterien:**
  1. Die Detailansicht zeigt Buchungsnummer (aus WP-M2-23), Status, Treffpunkt, aktuelle ETA und – sobald vorhanden – die Fahrzeugidentifikation (PAX-006).
  2. Eine serverseitige ETA-Änderung (neue Planrevision) erscheint in der geöffneten Ansicht ohne Neuladen, mit erkennbarem Hinweis auf die Änderung und neuem Datenstand (PAX-006).
  3. Zusage und ETA sind getrennt beschriftet; die Zusage ändert sich durch eine ETA-Änderung nicht.
  4. Bei unterbrochener Verbindung wird der Datenstand als veraltet gekennzeichnet; nach Wiederverbindung wird der aktuelle Stand geladen.
  5. Fremde Personal- oder Fahrzeugakten sind nie sichtbar.
- **Tests:** positiv: ETA-Änderung im offenen Browser. negativ: Stromabbruch, veraltete Revision, fremde Buchung (404). Screenreader: Änderung wird über Live-Region angesagt, ohne Fokus zu verschieben. AT-01 (Status- und ETA-Anzeige, soweit ohne M3 möglich).
- **Abhängigkeiten:** intern: WP-M2-28, WP-M2-42.
  - extern: WP-M3-25, WP-M3-29 (Ausführungszustände (Anfahrt, Einstieg, Fahrtende) und reale Fahrzeugzuordnung/-identifikation.)
  - extern: WP-M4-62 (ETA-Grundmodell als Quelle der laufenden ETA.)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Live-Fahrzeugposition auf der Karte (PAX-013, P2); Boarding-Code/QR (BKG-019, P2); Kontakt über Relay (COM-008, P2).

### WP-M2-44 – Fußweg zum Treffpunkt mit Textanleitung

- **Ziel:** Der Fußweg zum sicheren Treffpunkt wird mit Gehzeit auf der Karte und als gleichwertige, barrierearme Textanleitung dargestellt.
- **Anforderungen:** `LH-6.3-PAX-007`
- **Liefergegenstände:**
  - Backend: Fußwegberechnung über `RoutingPort` (Fußprofil) vom Startort des Fahrgasts zum Abholhaltepunkt: Geometrie, Distanz, Gehzeit, Abbiegeanweisungen als strukturierte Schritte; Ergebnis wird mit dem Angebot/der Buchung als Snapshot geführt.
  - API: Fußwegblock in `GET /v1/ride-offers/{id}` und `GET /v1/bookings/{id}`.
  - Oberfläche: Darstellung in UI-02 (Kurzangabe) und UI-04 (Karte mit Weg plus nummerierte Textanleitung, Haltepunktname und vorhandene Zugänglichkeitsangaben aus `stop_revision.accessibility`).
  - Konfigurationsparameter: `stop_walk_max_m`.
- **Detailverträge vorab:** Schema der Wegschritte; Gehgeschwindigkeit/Profil für die Gehzeit (Offener Punkt 36); Formulierungsregeln der Textanleitung.
- **Akzeptanzkriterien:**
  1. Eine Beispielbuchung zeigt den Fußweg auf der Karte und eine inhaltlich äquivalente Textwegbeschreibung mit Gehzeit (PAX-007).
  2. Die Textanleitung ist ohne Karte vollständig verständlich und per Screenreader in sinnvoller Reihenfolge lesbar.
  3. Kein angebotener Fußweg überschreitet `stop_walk_max_m`.
  4. Fällt der Fußwegdienst aus, werden Haltepunktname, Adresse und Luftlinienhinweis als Rückfall gezeigt, klar als solcher gekennzeichnet.
- **Tests:** positiv: Beispielbuchung mit Karte und Text. negativ: Routingausfall, Start = Haltepunkt (kein Weg). AT-19 (Teil).
- **Abhängigkeiten:** intern: WP-M2-10, WP-M2-43.
- **Entscheidungen:** D-09 (Datenquelle für Fußwege – blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Haltestelleneigenschaften Querung/Steigung/Fußwegbeschreibung (GEO-007, P2); Fußwegvergleich mehrerer Abholpunkte (PAX-017, P3).

### WP-M2-45 – UI-04 Buchungshistorie in vier Bereichen

- **Ziel:** Die Buchungsübersicht trennt bevorstehende, laufende, vergangene und stornierte Fahrten; ein Statuswechsel verschiebt die Buchung in den richtigen Bereich.
- **Anforderungen:** `LH-6.3-PAX-008`
- **Liefergegenstände:**
  - Oberfläche: UI-04 Übersicht mit vier Bereichen (Reiter oder Abschnitte mit Anzahl), Sortierung je Bereich, Leerzustände; Aktualisierung bei Statuswechsel über den Änderungsstrom.
  - API: `GET /v1/bookings` mit Filter nach Bereich, Cursor-Paginierung, stabiler Sortierschlüssel `(created_at,id)`.
  - Backend: eindeutige, serverseitige Zuordnung Buchungs-/Ausführungszustand → Bereich.
- **Detailverträge vorab:** Zuordnungstabelle Zustand → Bereich (Offener Punkt 37: „laufend“ setzt Ausführungszustände aus M3 voraus); Filterparameter.
- **Akzeptanzkriterien:**
  1. Jede Buchung erscheint in genau einem der vier Bereiche bevorstehend, laufend, vergangen, storniert (PAX-008).
  2. Ein Statuswechsel (z. B. bestätigt → storniert; bestätigt → laufend → abgeschlossen) verschiebt die Buchung in den korrekten Bereich, in der geöffneten Ansicht ohne manuelles Neuladen (PAX-008).
  3. Die Zuordnung erfolgt serverseitig; Oberfläche und API liefern dieselbe Einteilung.
  4. Die Liste enthält ausschließlich eigene Buchungen.
- **Tests:** positiv: je Bereich mindestens eine Buchung; Wechsel zwischen den Bereichen (Ausführungszustände per Testereignis bis M3). negativ: fremde Buchungen nicht enthalten; ungültiger Filter 422.
- **Abhängigkeiten:** intern: WP-M2-25, WP-M2-43.
  - extern: WP-M3-29 (Ausführungszustände „laufend“/„abgeschlossen“ aus Check-in und Fahrtende.)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Bewertungen nach der Fahrt (PAX-012, P2); gespeicherte Reisepläne (PAX-018, P3).

### WP-M2-46 – UI-04 Selbststorno

- **Ziel:** Nutzer können eigene Buchungen nach den konfigurierten Regeln selbst stornieren; danach sind Status, Erstattungshinweis und freie Kapazität konsistent.
- **Anforderungen:** `LH-6.3-PAX-009`
- **Liefergegenstände:**
  - Oberfläche: Stornoaktion in UI-04 mit Anzeige der geltenden Regel (Frist, erwartete Erstattung), ausdrücklicher Bestätigung, Grundauswahl; Ergebnis mit Status „storniert“ und Erstattungshinweis („Erstattung beauftragt“ bis zur PSP-Bestätigung, danach „erstattet“, Kap. 06 §6.4); Zustand „Storno nicht mehr möglich“ mit Kontaktmöglichkeit.
  - API: `POST /v1/bookings/{id}/cancel` mit `Idempotency-Key` und `If-Match`; `GET /v1/bookings/{id}`.
- **Detailverträge vorab:** Texte je Regelfall; Ablauf ein- oder zweistufig (Offener Punkt 24); Darstellung offener Erstattung.
- **Akzeptanzkriterien:**
  1. Ein Fahrgast storniert eine eigene Buchung innerhalb der Frist; danach zeigt die Oberfläche Status „storniert“ und einen Erstattungshinweis, der dem Zahlungsstand entspricht (PAX-009).
  2. Nach dem Storno ist der Platz wieder buchbar: eine zweite Testbuchung erhält dieselbe Kapazität (PAX-009).
  3. Status in UI, `booking.state`, Historie, Kapazität und `payment_intent`/`refund_case` widersprechen sich nicht (automatischer Konsistenzvergleich) (PAX-009).
  4. Außerhalb der Regel wird die Aktion nicht angeboten bzw. serverseitig abgewiesen.
  5. Doppelklick oder Wiederholung nach Verbindungsabbruch führt zu genau einem Storno und einer Erstattung.
- **Tests:** positiv: Storno mit Void, Storno mit Erstattung. negativ: Frist überschritten, bereits storniert, veralteter ETag. konkurrierend (Geld/Kapazität): Storno in zwei Tabs gleichzeitig; Storno parallel zur Neubuchung desselben Platzes. AT-07 (Ende-zu-Ende über die Oberfläche).
- **Abhängigkeiten:** intern: WP-M2-26, WP-M2-31, WP-M2-45.
- **Entscheidungen:** keine für den Bau.
- **Größe:** S
- **Nicht im Umfang:** Teilstorno und Klärfall-Darstellung `UNKNOWN` (M6); Bearbeiten statt Stornieren (BKG-006, P2).

### WP-M2-47 – Barrierefreier Buchungsweg

- **Ziel:** Der vollständige Buchungsweg der Passenger-PWA ist mit Screenreader, Tastatur und Schriftvergrößerung ohne Kartenpflicht ausführbar und als Regressionstest abgesichert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: Prüfprotokoll und automatisierte Regressionssuite für die Kernreise Suche → Angebot → Checkout → Detail → Storno über UI-01 bis UI-05: reine Tastaturbedienung, Screenreader (mindestens zwei Kombinationen aus Browser und Screenreader, Auswahl im Detailvertrag), 200 % Textvergrößerung, Reflow bei 320 CSS-Pixeln, Kontrast, reduzierte Bewegung, Touchflächen 44×44 CSS-Pixel (Designziel, Kap. 06 §6.1).
  - Oberfläche: Behebung der dabei gefundenen Mängel in den WPs WP-M2-35 bis WP-M2-46.
- **Detailverträge vorab:** Prüfkatalog nach WCAG 2.2 AA (Abnahmeziel laut Kap. 06 §6.1) für die Kernreise; Liste der Prüfkombinationen; Mängelklassifikation nach Lastenheft §11.3.
- **Akzeptanzkriterien:**
  1. AT-19: Die Kernreise ist mit Screenreader, nur mit Tastatur und bei Schriftvergrößerung vollständig ausführbar, ohne dass an irgendeiner Stelle eine Karte bedient werden muss.
  2. Die automatisierte Prüfung meldet keine Verstöße der vereinbarten Stufe auf UI-01 bis UI-05.
  3. Statusänderungen werden über Live-Regionen angesagt, ohne den Fokus zu verschieben.
  4. Kein offener Mangel der Klasse A oder B ohne freigegebenen Workaround.
- **Tests:** AT-19 als manuelles Prüfprotokoll mit Evidenz; automatisierte Suite in CI.
- **Abhängigkeiten:** intern: WP-M2-39, WP-M2-40, WP-M2-44, WP-M2-46.
  - extern: WP-M1-43 (Barrierefreie Komponenten des Designsystems.)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Barrierefreiheitsprüfung aller Rollen und formale Abnahme (M5); einfache Sprache (PAX-016, P2).

### WP-M2-48 – Gate-Nachweis M2 und fortlaufender Lasttest

- **Ziel:** Alle Gate-Tests von M2 sind mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen; Buchung und Disposition werden ab M2 fortlaufend unter Last gemessen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: ausführbare Szenarien in `tests/acceptance` für PH-AT-004, AT-01, AT-02, AT-03, AT-04, AT-05, AT-07, AT-13, AT-15, AT-19 mit Evidenzformat nach Kap. 08 §8.4; Zuordnung `source_key[]` je Test.
  - Tests: Lastprofil in `tests/performance` für Angebotsbildung, Hold und Confirm mit mindestens zwei Mandanten; regelmäßiger Lauf (Risiko „Solverlaufzeit und Kapazitätssperren unter Last“, Umsetzungsplan §7); Messung gegen „Angebote p95 ≤ 5 s“ und „Standard-API p95 ≤ 1 s“ (vorläufige Lastenheftziele).
  - Tests: RLS-Abdeckungsprüfung über alle in M2 neu angelegten Tabellen (Liste aus WP-M2-01): `tenant_id` vorhanden, RLS aktiviert und erzwungen, Policy mit USING und WITH CHECK, zusammengesetzte FKs; Isolationslauf über alle M2-Endpunkte mit zwei Mandanten (M2-Anteil von PH-AT-001: fremde ID → 404, manipulierte `tenant_id` wirkungslos, kein Mandantenkontext → keine Zeile).
  - Tests: M2-Anteile der von M1 übergebenen Referenztests: PH-AT-011 (Buchungs-/Zahlungsbefehle), PH-AT-018 (DST-Anfrage, Zusage und Tarif nach Konfigurationswechsel unverändert), E2E-096 (geänderte Vorlaufzeit wirkt erst ab Wirksamkeit; frühere Buchungen unverändert), E2E-098 (Rollback einer fehlerhaften Tarifversion als neue Revision); PH-AT-010 im Einbetreiberzuschnitt.
  - Tests: je Angebotsmodell aus WP-M1-25 (Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr, Bahnhofszubringer) eine Testfahrt mit der von M1 gelieferten Fixture-Konfiguration, soweit die Modellregeln festgelegt sind (Offener Punkt 45).
  - Nachweis: Pflege von `arbeitspakete.csv` (Spalten `detailvertrag`, `testfall`) für alle 38 Anforderungen; Gate-Bericht mit offenen Mängeln nach Klasse.
- **Detailverträge vorab:** Testspezifikation je Referenztest mit Fixture, Schritten, Soll; Abgrenzungsvermerk für AT-01 und AT-13 (Offene Punkte 1 und 2); Lastprofil (Requestmix, Flottengröße, Parallelität) als Referenzannahme bis D-05.
- **Akzeptanzkriterien:**
  1. PH-AT-004 und AT-03 sind unter der Runtime-Rolle grün, einschließlich der Varianten Rollstuhlplatz und veränderte Routenrevision.
  2. AT-02, AT-04, AT-05, AT-07, AT-15, AT-19 sind grün und mit Build-ID in `e2e.csv` eingetragen.
  3. AT-01 ist für den in M2 lieferbaren Teil (Anfrage, Angebot, bestätigte Buchung, Planzuweisung, Status/ETA aus dem Plan) grün; die Schritte Fahrerzuweisung in der Driver-App, Check-in und Fahrtende sind als offen für M3 vermerkt.
  4. AT-13 ist gemäß der mit dem Auftraggeber getroffenen Klärung behandelt (Offener Punkt 2) und der Status in `e2e.csv` begründet.
  5. Jede der 38 Anforderungen hat mindestens einen grünen positiven Test; Geld-, Kapazitäts-, Sicherheits- und Datentrennungsregeln zusätzlich negative und konkurrierende Tests (Kap. 08 §8.4).
  6. Ein Lasttestbericht mit Messwerten liegt vor; Abweichungen von den vorläufigen Zielen sind mit Ursache dokumentiert.
  7. Kein offener Mangel der Klasse A.
  8. Die RLS-Abdeckungsprüfung ist für alle neuen M2-Tabellen grün und läuft in CI unter der Runtime-Rolle; jede Invariante aus Kap. 04 §4.5 mit M2-Bezug (Kapazität, nicht überlappende Tarif-/Dienstversion, Gegenereignis statt Änderung, Bestätigung/PSP-Retry) hat einen Negativtest mit mindestens zwei parallelen Transaktionen.
  9. Die M2-Anteile von PH-AT-001, PH-AT-010, PH-AT-011, PH-AT-018, E2E-096 und E2E-098 sind ausgeführt und an die Gate-Suite von M1 (WP-M1-45) zurückgemeldet.
  10. Die Evidenz enthält keine unnötigen Fahrgastdaten (Kap. 08 §8.4).
- **Tests:** PH-AT-004, AT-01, AT-02, AT-03, AT-04, AT-05, AT-07, AT-13, AT-15, AT-19; Contract-Fälle aus Kap. 05 §5.8 mit M2-Bezug (letzter Platz – Einbetreiberanteil, abgelaufene Holds, SCA länger als Hold, 100 identische Webhooks, Manipulation tenant_id, unzulässige Fremd-ID); M2-Anteile von PH-AT-001, PH-AT-010, PH-AT-011, PH-AT-018, E2E-096, E2E-098; Vorbereitung von PH-AT-021, E2E-080 und E2E-081 (Auswertung in M4).
- **Abhängigkeiten:** intern: WP-M2-19, WP-M2-20, WP-M2-34, WP-M2-47.
  - extern: WP-M0-22, WP-M0-25, WP-M0-32, WP-M0-33 (Akzeptanztestgerüst und Evidenzformat (WP-M0-22); Gerüst `tests/performance` (WP-M0-25); Nachverfolgungs-CI (WP-M0-32); bestätigte Testzuordnung (WP-M0-33).)
  - extern: WP-M1-25, WP-M1-45 (Gate-Suite M1 mit den Grundfassungen von PH-AT-001, PH-AT-011, E2E-096, E2E-098 (WP-M1-45); Fixture-Konfiguration je Angebotsmodell (WP-M1-25).)
  - extern: WP-M3-25, WP-M3-29, WP-M3-30 (Fahrerzuweisung, Check-in, Fahrtende für den vollständigen AT-01.)
  - extern: WP-M6a-26, WP-M6a-29 (ÖPNV-Fahrplandaten (GTFS) als Grundlage einer Parallelfahrtenregel für AT-13.) – Reihenfolgekonflikt (späterer Meilenstein), siehe Offener Punkt 49
- **Entscheidungen:** D-05 (Peaklast und Lastprofil – Messung gegen Referenzannahme; belastbares Profil erst nach Beschluss), D-12 (Vorziehung von OPT-012 für AT-13 nur per Beschluss).
- **Größe:** M
- **Nicht im Umfang:** Referenzlasttest und Restore-Übung der Pilotabnahme (M5); Penetrationstest (M5).

## Offene Punkte

1. **AT-01 ist in M2 nur teilweise erfüllbar.** Der Ablauf verlangt „Fahrerzuweisung, Check-in, Fahrtende“; Driver-App, Check-in und Ausführungszustände liefert laut Umsetzungsplan erst M3. Das Gate M2 nennt AT-01 dennoch ohne Einschränkung. Zu klären: Teilabnahme in M2 (Annahme in WP-M2-48) oder Verschiebung des Tests ins Gate M3.
2. **AT-13 (Parallelfahrtenverbot) hat in M2 keine zugeordnete Anforderung.** Die Regel steht in `LH-6.5-OPT-012` und `LH-6.11-PTI-004` (beide P2) und setzt ÖPNV-Verbindungsdaten voraus (PTI, M6). Keine der 38 M2-Anforderungen deckt sie. Entweder AT-13 aus dem Gate M2 nehmen oder OPT-012 per D-12-Beschluss vorziehen; ohne Beschluss wird in M2 keine Parallelfahrtenregel gebaut.
3. **`journey`-Hülle für Einbetreiberbuchungen ungeklärt.** `payment_intent.journey_id` und `operation.journey_id` sind im Kern-DDL Pflicht-FKs auf `journey`; `fare_quote` ist nur über `journey.quote_id` mit einer Reise verknüpft. `booking` hat dagegen weder Fahrgast- noch Quote- noch Zahlungsbezug. Kap. 05 §5.7 sagt nur „Ein Segment verwendet denselben Tarif-/Kapazitätskern“. Offen ist, ob `/v1/bookings` in M2 eine `journey` mit genau einem `journey_leg` anlegt oder ob die FKs gelockert werden (betrifft `LH-6.4-BKG-003`, `LH-6.10-PAY-004`; WP-M2-01, WP-M2-23, WP-M2-30).
4. **Zustandsautomat der lokalen Buchung nicht definiert.** Kap. 02 §7.3 definiert Zustände für Journey, Segment-Buchung und Segment-Ausführung, aber nicht ausdrücklich für `booking.state` und `vehicle_run.state`; beide Spalten sind freier Text (`LH-6.4-BKG-013`, `LH-6.4-BKG-003`, `LH-6.5-OPT-010`).
5. **Reihenfolge Zahlung/Confirm und Capture-Zeitpunkt im Einbetreiberfall.** Kap. 03 §4.2 und Kap. 05 §5.3 beschreiben den Mehrbetreiberablauf und verweisen auf den AG-Entscheid (D-03). Für P1 fehlt die Festlegung, ob eine Buchung ohne erfolgreiche Autorisierung bestätigt werden darf und wann eingezogen wird (`LH-6.10-PAY-004`, `LH-6.10-PAY-001`).
6. **Betriebszeiten und Verkehrsprodukt fehlen im Kern-DDL.** `service` enthält nur Betreiber, Name, Zeitzone und `config_revision_id`. `LH-6.5-OPT-001` verlangt die Prüfung von Bedienzeiten, `LH-6.9-GEO-002` und `LH-6.12-COM-002` beziehen sich auf „Verkehrsprodukte“. Offen: eigene Tabellen oder Werte in `config_revision.values_json`; Verhältnis Dienst ↔ Verkehrsprodukt. `service` hat außerdem weder Revision noch Wirksamkeitsintervall, obwohl Kap. 04 §4.5 „nicht überlappende aktive Dienstversion“ verlangt; die Versionierung muss zwischen WP-M1-09/WP-M1-25 (Dienstkopf, Dienstkonfiguration) und WP-M2-03 abgestimmt werden.
7. **`stop_revision` ohne Aktivstatus und ohne zulässige Verkehrsprodukte.** Beides verlangt `LH-6.9-GEO-002`; das Kern-DDL kennt nur Gültigkeitsintervall und `accessibility`. Es fehlt auch jede Zuordnung Haltepunkt ↔ Dienst.
8. **Deaktivierung von Haltepunkten oder Gebieten mit bestehenden Zusagen.** Das Pflichtenheft regelt nicht, was mit bestätigten Buchungen geschieht, deren Haltepunkt deaktiviert oder deren Gebiet verkleinert wird (`LH-6.9-GEO-001`, `LH-6.9-GEO-002`).
9. **Freigabeablauf der Geo-Revisionen in P1.** `stop_revision.approved_by` ist Pflichtfeld, `service_area` hat kein Freigabefeld; Kap. 01 §1.3 verlangt „nur freigegebene Versionen“. Der redaktionelle Freigabeprozess ist erst `LH-6.9-GEO-008` (P2). Offen, welche Freigabe in P1 gilt (`LH-6.9-GEO-001`, `LH-6.9-GEO-002`).
10. **Datenmodell für getrennte Zonen und Zielpunkte fehlt.** `LH-6.9-GEO-003` verlangt getrennte Zonen und Zielpunkte; weder Kern-DDL noch Kap. 04 §4.4 nennen dafür eine Tabelle.
11. **Tarifpflege ohne Endpunkt.** Kap. 05 §5.7 nennt keinen Pfad für Tarife; UI-17 „Gebiete/Stops/Tarife“ setzt einen voraus (`LH-6.10-PAY-003`).
12. **Preisstichtag und Tarifklasse.** Nicht festgelegt, ob die Gültigkeit einer Preisregel zum Anfrage-, Buchungs- oder Fahrtzeitpunkt geprüft wird, und welche Tarifklassen in P1 existieren (`LH-6.10-PAY-003`, `LH-6.10-PAY-002`). Ebenso fehlen Steuersätze und die Testrelationen mit erwarteten Preisen; sie müssen vom Auftraggeber geliefert werden.
13. **Vergröberung in `travel_attempt`.** `coarse_origin`/`coarse_destination` sind Textfelder ohne definierte Auflösung (Zone, Raster, Gemeinde) (`LH-6.4-BKG-001`).
14. **Kein verbindlicher Katalog der Ablehnungsgründe.** `LH-6.5-OPT-011` fordert standardisierte Gründe; das Pflichtenheft nennt nur Beispiele und die KPI-Kategorien aus Anhang K.1. Codes, Prioritätsregel für den Primärgrund und die Abgrenzung „Regelverbot“ fehlen.
15. **Obergrenzen je Buchung.** Maximale Personenzahl, Anzahl Rollstuhlplätze und die Liste der Ausstattungsmerkmale im Ressourcenvektor sind nicht festgelegt (`LH-6.4-BKG-004`, `LH-6.5-OPT-002`); „Umrüstzustände“ (Kap. 01 §1.4) sind nicht beschrieben.
16. **„Standort“ im Kandidatenfilter.** `LH-6.5-OPT-002` nennt den Standort als Filterkriterium; Livepositionen (`position_sample`) entstehen erst in M3. Annahme dieses Plans: geplante Position aus dem Fahrtenplan.
17. **Messdefinitionen der harten Grenzen.** Für `max_wait_min`, `max_detour_pct` und „Verspätung“ fehlen Bezugspunkte (Wartezeit ab Anfrage oder ab Wunschzeit; Umweg gegenüber Direktfahrzeit oder -distanz; Verspätung gegenüber welchem Fensterrand). Ein eigener Katalogparameter für die maximale Verspätung existiert nicht; `arrival_grace_min` ist als „Ankunftstoleranz“ definiert (`LH-6.5-OPT-005`).
18. **Gewichte der Mehrzieloptimierung nicht im Parameterkatalog.** `LH-6.5-OPT-008` verlangt konfigurierbare Gewichte für Wartezeit, Umwege, Leerstrecken und Betriebsqualität; `kataloge.csv` enthält dafür keinen Parameter, und „Betriebsqualität“ ist nicht definiert.
19. **Persistenz des Angebots.** Für das lokale Fahrtangebot gibt es keine Tabelle; `fare_quote` speichert nur den Preisstand. Offen, wo Angebots-ID, Planrevision, Haltepunkte und Zeitfenster bis zum Hold gehalten werden (`LH-6.4-BKG-002`).
20. **Angebotsgültigkeit und Hold-Dauer ohne Parameter.** Hold 180 s ist Planungsdefault (Kap. 02 §7.2); eine Gültigkeitsdauer eigener Angebote ist nirgends beziffert, `partner_offer_ttl_sec` gilt für Partner. Beide Werte fehlen im Katalog der 80 Parameter (`LH-6.4-BKG-002`, `LH-6.5-OPT-009`).
21. **Unterpfade der Buchungs-API.** Kap. 05 §5.7 nennt nur `/v1/ride-offers` und `/v1/bookings`. Ob Hold ein eigener Schritt der öffentlichen API ist (wie bei `/v1/federation/offers/{offer_id}/hold`) oder im Confirm aufgeht, sowie die Pfade für Hold-Freigabe, Storno, Historie und Änderungsstrom sind offen. Ebenso, ob Hold/Confirm synchron antworten oder als `operation` mit 202 laufen.
22. **Tabelle für die Statushistorie.** Kap. 04 §4.4 nennt `booking_change` nur als „Änderungsannahme“ (passt zu `LH-6.4-BKG-006`, P2). Für `LH-6.4-BKG-013` ist keine Tabelle benannt.
23. **Stornofristen und -gründe ohne Parameter.** `LH-6.4-BKG-005` und `LH-6.3-PAX-009` verlangen konfigurierbare Fristen und Gründe; der Katalog enthält nur `partner_liability_policy` (Partner) und `no_show_grace_min`. Auch die P1-Folge eines Stornos nach Fristablauf (Ablehnung oder Gebühr) fehlt; die ausführlichen Regeln sind `LH-6.10-PAY-006` (P2).
24. **Storno ein- oder zweistufig.** Kap. 06 §6.4 beschreibt ein zweistufiges Verfahren mit Stornoangebot; die OpenAPI kennt es nur für `/v1/federation/journeys/{journey_id}/cancellation-quotes`. Für `/v1/bookings` ist nichts festgelegt.
25. **Technik der Aktualisierung „ohne Neuladen“.** `LH-6.3-PAX-006` und `LH-6.5-OPT-010` verlangen Push zum Client; Kap. 02 §4.3 erwähnt WebSocket-/SSE-Kanäle nur als Isolationsthema. Protokoll, Authentisierung und Wiederaufnahme sind nicht spezifiziert.
26. **Zahlungsumfang M2 gegenüber `LH-6.10-PAY-005` (P2).** Der Umsetzungsplan legt „Zahlung über PSP-Adapter mit eigenem Zahlungsautomaten“ und AT-15 in M2; die Anforderung „Onlinezahlung über PSP mit Tokenisierung“ ist jedoch P2/MUSS und gehört nicht zu den 38. Diese Datei behandelt den PSP-Adapter als Enabler (WP-M2-29, WP-M2-31) und ändert keine Phase; der Auftraggeber sollte bestätigen, dass P1 eine Onlinezahlung enthält, oder das Zahlungsmittel des Piloten benennen.
27. **Zahlungsplan bei Vorausbuchungen.** Kap. 02 §7.2 hält fest, dass eine Autorisierung nicht bis zum Reisetag als garantiert gilt, und überlässt Modell und Capture-Zeitpunkt dem Auftraggeber. Für AT-05 in M2 fehlt damit die Regel (`LH-6.4-BKG-001`, `LH-6.10-PAY-004`).
28. **Kanäle, Provider und Retry-Regel für Nachrichten.** Der Umsetzungsplan nennt Push/SMS/E-Mail; `LH-6.12-COM-003` nennt keine Kanäle, „SMS-Kundenkontakt“ ist `LH-6.12-COM-006` (P2). Provider, Web-Push-Fähigkeit der PWA als P1-Kanal, Wiederholintervalle und Höchstzahl der Versuche sind nicht festgelegt (Kap. 05 §5.6 regelt nur Partnerereignisse).
29. **Sprachen und Vorlagenpflege in P1.** Kap. 06 §6.1 verlangt „mindestens Deutsch und vorbereitete Internationalisierung“; die Abnahme von `LH-6.12-COM-002` setzt mindestens zwei Sprachen voraus. Welche zweite Sprache und ob Vorlagen in P1 über eine Oberfläche oder nur per Auslieferung gepflegt werden, ist offen.
30. **„Relevante ETA-Änderung“ und Auslöser aus M3.** Für `LH-6.12-COM-001` fehlt eine Schwelle für Relevanz; die Auslöser „Ankunft“ und „Ausfall“ entstehen fachlich erst in M3, die laufende ETA erst mit dem ETA-Grundmodell in M4. Unklar ist außerdem, ob die Erinnerung nach `customer_notify_before_pickup_min` zu den P1-Auslösern zählt.
31. **Zuständigkeit für Fahrgastkonto und `/v1/passengers`.** Der Umsetzungsplan legt UI-05 in M2, die IAM-Anforderungen (Konto, verifizierte Kontakte) in M1. Laut M1-Planung liefern WP-M1-28 (Registrierung, Kontaktverifikation) und WP-M1-29 (`passenger_preference`, Favoriten, Sprache, UI-05-Basis über `/v1/me`) das Konto; WP-M2-36 bindet dies nur in die PWA ein und ergänzt den Haltepunkt-/Koordinatenbezug der Favoriten. Die genaue Schnittlinie (wer besitzt das Favoritenschema) ist vor Baubeginn zwischen M1 und M2 zu bestätigen.
32. **„Fünf Hauptschritte“, „Standardfahrt“ und „Verfügbarkeit“.** `LH-6.3-PAX-001` definiert weder die Zählweise (zählen Anmeldung und Zahlungsmittelauswahl?) noch, was die Startansicht als „Verfügbarkeit“ zeigt (Betriebsstatus des Dienstes oder freie Kapazität).
33. **Ortssuche ohne Endpunkt und ohne Datenquelle.** Für Adress- und POI-Suche (`LH-6.3-PAX-002`) nennt Kap. 05 §5.7 keinen Pfad; Geocoder und POI-Datenbestand hängen an D-09.
34. **Nachweis der Standortzustimmung.** `LH-6.3-PAX-003` verlangt aktive Zustimmung; ob sie über die Browserberechtigung hinaus als `consent_revision` zu protokollieren ist, ist nicht festgelegt.
35. **Umfang der „Fahrzeugidentifikation“ für Fahrgäste.** `LH-6.3-PAX-006` nennt sie „gegebenenfalls“; welche Merkmale (Kennzeichen, Fahrzeugnummer, Farbe) gezeigt werden dürfen und ab welchem Zeitpunkt, ist offen. `vehicle` enthält im Kern-DDL kein solches Feld.
36. **Gehzeitmodell.** Für `LH-6.3-PAX-007` fehlen Gehgeschwindigkeit bzw. Profile (auch für mobilitätseingeschränkte Personen) und die Datenquelle für „sichere“ Treffpunkte; Haltepunkteigenschaften dazu sind erst `LH-6.9-GEO-007` (P2).
37. **Bereich „laufend“ der Buchungshistorie.** `LH-6.3-PAX-008` braucht Ausführungszustände, die erst M3 liefert; in M2 kann der Wechsel nur mit Testereignissen nachgewiesen werden.
38. **Schnittstelle Kern ↔ Routing-Worker.** Nicht festgelegt, ob die Angebotsberechnung synchron oder über RabbitMQ läuft (bereits als offener Punkt in M0 vermerkt); betrifft das 1-s-Budget in `LH-6.5-OPT-003` und die Fehlerbehandlung in `LH-6.5-OPT-011`.
39. **Lastprofil für die Konkurrenz- und Lasttests.** Peak-Anfragen/s und gleichzeitige Buchungen sind laut D-05 offen; PH-AT-004 gibt nur „100 parallele Bestätigungen“ vor. Die Messungen in WP-M2-22, WP-M2-23 und WP-M2-48 laufen bis zum Beschluss gegen Referenzannahmen.
40. **Fixtures für Fahrzeuge und Schichten statt führender Module.** `vehicle`, `staff_shift` und `resource_release` gehören fachlich zu M3/M4, werden aber von `vehicle_run` per FK benötigt. M2 arbeitet mit Seed-Daten (WP-M2-02); die Übergabe an die führenden Module und die synchrone Safety-Prüfung im Hold (PH-AT-020) müssen mit M3 abgestimmt werden.
41. **AT-04 „Stopp zulässig“ ohne Datengrundlage in P1.** AT-04 verlangt, dass nur ein geeigneter Stopp zulässig ist; die Haltestelleneigenschaften zum Rollstuhlzugang sind erst `LH-6.9-GEO-007` (P2), und `stop_revision.accessibility` hat kein festgelegtes Schema. Annahme dieses Plans: ein Minimalfeld „geeignet/ungeeignet/ungeprüft“ in P1 (WP-M2-05, WP-M2-12); zu bestätigen ist, ob „ungeprüft“ für Rollstuhlanfragen ausgeschlossen wird (`LH-6.9-GEO-002`, `LH-6.4-BKG-004`).
42. **Buchungsnummer.** `LH-6.3-PAX-006` verlangt eine Buchungsnummer; `booking` hat dafür keine Spalte, und Format, Eindeutigkeitsbereich (je Mandant oder plattformweit) sowie Erratbarkeit sind nicht festgelegt (Beispiel „TV-2048“ in Kap. 06 §6.4 ist eine Reisenummer).
43. **Zuordnung Fahrzeug ↔ Dienst fehlt.** Weder `vehicle` noch `vehicle_run` noch `staff_shift` haben im Kern-DDL einen Bezug zu `service`; `LH-6.5-OPT-002` filtert „nach Dienstzeit“. Die Fahrzeugpool-Zuordnung liefert erst M3 (WP-M3-06). M2 arbeitet mit einer Fixture-Zuordnung über den Leseport (WP-M2-02, WP-M2-15); Tabelle und Besitzer sind mit M3 abzustimmen.
44. **Erwartungen aus M3 an M2, die außerhalb der 38 Anforderungen liegen.** Die M3-Planung nennt als EXTERN[M2] einen „Boarding-Code je Buchung“ (`LH-6.4-BKG-019`, P2), eine „No-Show-Abrechnungsregel des Tarifs“ (`LH-6.4-BKG-015`, P2) und die „Änderung“ von Buchungen (`LH-6.4-BKG-006`, P2). Keine davon gehört zu M2; ohne D-12-Beschluss baut M2 sie nicht. Die M3-Pakete müssen ihre Abhängigkeit anpassen oder die Anforderungen werden vorgezogen.
45. **Testfahrt je Angebotsmodell.** WP-M1-25 erwartet von M2 je Angebotsmodell (Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr, Bahnhofszubringer) eine erfolgreiche Testfahrt. Das Pflichtenheft beschreibt die Modellregeln nicht, und keine der 38 M2-Anforderungen unterscheidet die Modelle (z. B. feste Stoppfolge beim linienbasierten Bedarfsverkehr). Ohne Festlegung kann M2 nur das Modell nachweisen, das die Einfügeheuristik mit freien Haltepunkten abbildet.
46. **Wirkungsanalyse-Beiträge.** WP-M1-23 erwartet von M2 ImpactProvider für Buchungen und Bediengebiete; das Pflichtenheft legt weder Schnittstelle noch Zählregel („betroffene künftige Angebote“) fest. Hier in WP-M2-04 und WP-M2-23 aufgenommen; Umfang ist mit M1 zu bestätigen.
47. **Hold-Verlängerung.** Kap. 02 §7.2 erlaubt eine Verlängerung „nur bei erneuter Kapazitätsprüfung“, nennt aber weder Auslöser noch Höchstdauer. Annahme: in P1 keine Verlängerung (WP-M2-22) (`LH-6.5-OPT-009`).
48. **Erzeugung von `vehicle_run`.** Das Pflichtenheft sagt nicht, wann und durch wen ein Umlauf je Schicht/Fahrzeug entsteht (bei Schichtveröffentlichung, bei Einsatzfreigabe oder bei der ersten Buchung) und welche Werte `vehicle_run.state` annimmt; M4 (Dienstplan) und M3 (Einsatzfreigabe) sind betroffen (`LH-6.5-OPT-003`, `LH-6.5-OPT-004`).
49. **Reihenfolgekonflikt: WP-M2-48 → WP-M6a-26, WP-M6a-29 (späterer Meilenstein).** Die für AT-13 benötigten ÖPNV-Fahrplandaten (GTFS, WP-M6a-26) und die Parallelfahrtenregel (WP-M6a-29) liegen in M6 und damit nach M2. Die Abhängigkeit ist in der Übersichtstabelle nicht eingetragen; sie ist nur über die Klärung aus Offenem Punkt 2 auflösbar (AT-13 aus dem Gate M2 nehmen oder Vorziehung per D-12-Beschluss).
50. **Reihenfolgekonflikt: zyklische Verweise auf die Parallelmeilensteine M3/M4.** Folgende externe Verweise zeigen auf WPs, die ihrerseits (direkt oder über ihre Vorgänger) auf das verweisende M2-WP angewiesen sind: WP-M2-02 → WP-M3-03, WP-M3-06, WP-M3-08, WP-M4-03, WP-M4-65; WP-M2-13 → WP-M4-46, WP-M4-51; WP-M2-15 → WP-M3-06, WP-M3-08, WP-M4-17; WP-M2-22 → WP-M3-08, WP-M3-09; WP-M2-25 → WP-M3-29, WP-M3-30; WP-M2-27 → WP-M3-54; WP-M2-28 → WP-M3-27, WP-M3-29. Fachlich handelt es sich um spätere Ablösungen der M2-Fixtures bzw. um nachgelagerte Verbraucher/Erweiterungen, nicht um Startvoraussetzungen; M2 baut gegen Fixtures, Leseport und Testereignisse. Diese Verweise sind in der Übersichtstabelle („hängt ab von“) nicht eingetragen. Zu klären: Umwandlung in Integrationsschritte auf Seiten von M3/M4 oder ein eigenes Integrations-WP nach Vorliegen beider Seiten. Die übrigen Verweise auf M3/M4 (WP-M2-15 → WP-M3-34; WP-M2-26 → WP-M3-27; WP-M2-27 → WP-M3-53, WP-M4-46; WP-M2-28 → WP-M3-24; WP-M2-34 → WP-M3-29, WP-M3-53, WP-M4-61, WP-M4-62; WP-M2-43 → WP-M3-25, WP-M3-29, WP-M4-62; WP-M2-45 → WP-M3-29; WP-M2-48 → WP-M3-25, WP-M3-29, WP-M3-30) sind zyklusfrei und eingetragen; sie bedeuten, dass der jeweilige Vollausbau bzw. Gate-Nachweis erst mit dem genannten M3-/M4-WP abschließbar ist.
