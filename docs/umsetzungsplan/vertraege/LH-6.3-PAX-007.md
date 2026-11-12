# LH-6.3-PAX-007 – Fußweg zum Treffpunkt mit Textanleitung

- **Verantwortlicher:** Rolle Passenger-Web (UI-02/UI-04), fachlich Routing/Buchung
- **Arbeitspaket:** WP-M2-44
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein verständlicher Fußweg zum sicheren Treffpunkt ist mit Gehzeit und barrierearmer Textanleitung darstellbar.

**Originalabnahme (unverändert):** Eine Beispielbuchung zeigt Karte und äquivalente Wegbeschreibung.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle; Fußwegergebnis wird als Snapshot mit Angebot/Buchung geführt (Ablageort abhängig von OP-19). Zugänglichkeitsangaben aus `stop_revision.accessibility` (Schema offen, OP-41; P1-Minimalfeld geeignet/ungeeignet/ungeprüft laut WP-M2-05). Das Wegschritte-Schema und die Snapshot-Ablage (an Angebot bzw. `booking.promise_snapshot`) legt WP-M2-01 fest (OP-19).
- **API:** Fußwegblock in `GET /v1/ride-offers/{id}` und `GET /v1/bookings/{id}`: Geometrie, Distanz, Gehzeit, strukturierte Wegschritte (Schema aus WP-M2-01). Berechnung über `RoutingPort` (Fußprofil, Modul `booking-dispatch`, WP-M2-10) vom Startort zum Abholhaltepunkt; Ergebnis trägt Kartendatenstand/Version. Fremde Buchung: 404.
- **Zustandsautomat:** keiner. Rückfall bei Ausfall des Fußwegdienstes: Haltepunktname, Adresse und Luftlinienhinweis, als Rückfall gekennzeichnet. Start = Haltepunkt: kein Weg. Sicherer Treffpunkt = definierter Abholhaltepunkt; weitere Sicherheitseigenschaften sind erst GEO-007 (P2).
- **JSON-Schema / Ereignisse:** Schema der Wegschritte aus WP-M2-01.
- **Parameter und Kennzahlen:** `stop_walk_max_m` (kein angebotener Fußweg überschreitet ihn).
- **Oberfläche:** UI-02 Kurzangabe; UI-04 Karte mit Weg plus nummerierte, ohne Karte vollständig verständliche Textanleitung (Gehzeit, Haltepunktname, Zugänglichkeitsangaben), in sinnvoller Screenreader-Reihenfolge; Karte und Text inhaltlich äquivalent.
- **Entscheidungen:** D-09 (Datenquelle für Fußwege, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP-36 (Gehgeschwindigkeit/Profile, auch für mobilitätseingeschränkte Personen; Datenquelle für „sichere“ Treffpunkte, Eigenschaften erst GEO-007 P2); Formulierungsregeln der Textanleitung vom Bau festzulegen. Arbeitsannahme: ein Standardgehprofil; blockiert den Bau nicht.

## Tests

- **Positiv:** Beispielbuchung zeigt Fußweg auf der Karte und inhaltlich äquivalente Textwegbeschreibung mit Gehzeit; Textanleitung ist ohne Karte vollständig und per Screenreader sinnvoll lesbar.
- **Negativ:** Routingausfall: Rückfall mit Haltepunktname, Adresse und Luftlinienhinweis, gekennzeichnet; Start = Haltepunkt (kein Weg); Fußweg über `stop_walk_max_m` wird nicht angeboten (Grenzfall exakt `stop_walk_max_m` und +1 m); fremde Buchung und fremder Mandant ergeben 404 (Datentrennung).
- **Konkurrierend:** Planrevision ändert den Abholhaltepunkt, während der Fußweg angezeigt wird: Weg und Text werden gemeinsam aktualisiert, nie gemischt (Karte und Text tragen dieselbe Revision/denselben Snapshot).
- **Referenztests:** AT-19 (Teil; Textanleitung ohne Karte vollständig, Screenreader-Reihenfolge).
- Testfälle: AT-19; web/passenger/src/booking/WalkingRoute.test.tsx::karte_und_textanleitung_sind_aequivalent; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::fussweg_ueber_stop_walk_max_m_wird_nicht_angeboten; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::routingausfall_liefert_rueckfall; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::start_gleich_haltepunkt_liefert_keinen_weg; web/passenger/src/booking/WalkingRoute.test.tsx::textanleitung_ohne_karte_vollstaendig_und_screenreader_reihenfolge; web/passenger/src/booking/WalkingRoute.test.tsx::rueckfall_ist_als_rueckfall_gekennzeichnet; web/passenger/src/booking/WalkingRoute.test.tsx::planrevision_aktualisiert_weg_und_text_gemeinsam; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingDetailApiTest.kt::fussweg_fremde_buchung_ergibt_404; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::weg_mit_geometrie_gehzeit_und_nummerierten_schritten; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::service_plant_ueber_routingport_speichert_snapshot_und_liest_ihn_nur_fuer_den_besitzer; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::snapshot_ist_unveraenderlich_und_mandantengetrennt; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingDetailApiTest.kt::fussweg_ist_derselbe_snapshot_in_angebot_und_buchung; web/passenger/src/booking/WalkingRoute.test.tsx::start_gleich_haltepunkt_zeigt_keinen_weg_und_ohne_walk_einen_hinweis

## Umsetzungsentscheidungen WP-M2-44

- **Schema der Wegschritte:** `RideWalkRoute` und `RideWalkStep` in `api/booking.yaml` (Schritt: `seq`, `maneuver` START/TURN_LEFT/TURN_RIGHT/ARRIVE, `distance_m`, `duration_s`, `instruction`); Block `walk` in `GET /v1/ride-offers/{id}`, in der Angebotsantwort der Suche und in `GET /v1/bookings/{id}`.
- **Ablage (OP-19):** Tabelle `ride_offer_walk` (V20440), ein unveränderlicher Snapshot je Angebot, die Buchung liest ihn über ihr Angebot. Karte und Text tragen dieselbe `snapshot_id`.
- **Datenschutz (OP-13):** die Koordinate eines freien Startpunkts wird nie gespeichert; Geometrie gibt es nur zwischen öffentlichen Haltepunkten. Freier Startpunkt = gekennzeichneter Rückfall (`NO_ORIGIN_STOP`) mit auf 10 m aufgerundeter Luftlinie und ohne Richtung.
- **Gehzeit (OP-36, Arbeitsannahme):** Standardprofil 1,25 m/s (4,5 km/h) im Rückfall; mit Routing gilt die Zeit des Workers (Profil `walk`).
- **Formulierungsregeln:** ein Satz je Schritt, Höflichkeitsform, ganze Meter, Himmelsrichtung nur im ersten Schritt, „nach links/rechts abbiegen“ ab 30 Grad Richtungsänderung, letzter Schritt nennt den Haltepunkt (`WalkPlanner.steps`).
- **`stop_walk_max_m`:** aus der wirksamen Dienstkonfiguration (Planungsdefault 400 m); überschreitet der Weg die Grenze (genau die Grenze ist erlaubt, +1 m nicht), entfällt das Angebot (Ablehnung `NO_STOP_IN_RANGE`, wenn kein Angebot bleibt).
- **Nicht ausgeführt / offen:** Der Routing-Worker mit Fußprofil und Haltepunktkatalog ist in der Anwendung nicht angeschlossen (D-09); bis dahin liefert jede Suche den Rückfall, der Weg über den `RoutingPort` ist gegen einen Ersatz (`FakeWalkRouting`) belegt. Ein Haltepunkt trägt keine Adresse (`stop_revision`: Name und Punkt), der Rückfall zeigt deshalb Name und Luftlinie. Die Karte ist ein Linienzug ohne Kartenkacheln (Kartenquelle D-09).

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
