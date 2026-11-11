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
- Testfälle: AT-19; geplant:web/passenger/src/booking/WalkingRoute.test.tsx::karte_und_textanleitung_sind_aequivalent; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::fussweg_ueber_stop_walk_max_m_wird_nicht_angeboten; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::routingausfall_liefert_rueckfall; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/WalkingRouteTest.kt::start_gleich_haltepunkt_liefert_keinen_weg; geplant:web/passenger/src/booking/WalkingRoute.test.tsx::textanleitung_ohne_karte_vollstaendig_und_screenreader_reihenfolge; geplant:web/passenger/src/booking/WalkingRoute.test.tsx::rueckfall_ist_als_rueckfall_gekennzeichnet; geplant:web/passenger/src/booking/WalkingRoute.test.tsx::planrevision_aktualisiert_weg_und_text_gemeinsam; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingDetailApiTest.kt::fussweg_fremde_buchung_ergibt_404

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
