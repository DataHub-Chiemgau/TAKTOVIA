# LH-6.3-PAX-006 – UI-04 Buchungsdetail mit Status und ETA ohne Neuladen

- **Verantwortlicher:** Rolle Passenger-Web (UI-04), fachlich Buchung/Disposition
- **Arbeitspaket:** WP-M2-43
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrgäste sehen Buchungsnummer, Status, Treffpunkt, aktuelle ETA und gegebenenfalls Fahrzeugidentifikation.

**Originalabnahme (unverändert):** UI aktualisiert eine ETA-Änderung nachvollziehbar ohne Neuladen.

## Vertrag

- **Tabellen / DDL:** Lesen aus `booking` (Buchungsnummer: Spalte fehlt, OP-42), Planrevision/ETA-Projektion je Buchung (Planzeit am Abholstopp, WP-M2-28), `booking.state`, `booking.revision` (ETag), `booking.promise_snapshot` (verbindliche Zusage). `booking` hat keine Fahrgastspalte; Fahrgastbindung über `journey`-Hülle (OP-03). Fahrgast sieht nie fremde Personal- oder Fahrzeugakten; Fahrzeugmerkmale nur nach Festlegung (OP-35).
- **API:** `GET /v1/bookings/{id}` mit ETag (Felder: Buchungsnummer, fachlicher Status, Zahlungsstatus getrennt, Treffpunkt, Zusage-Fenster, aktuelle ETA mit Datenstand und Quelle, Fahrzeugidentifikation sobald zugewiesen); Änderungsstrom aus WP-M2-28 (Protokoll offen, OP-25). Fremde Buchung: 404 (auch fremder Mandant). Wiederverbindung: Snapshotabruf über `GET /v1/bookings/{id}` schließt die Lücke; Ereignisse deduplizieren und prüfen die Revision (kein Zurücksetzen auf ältere Revision).
- **Zustandsautomat:** Buchungszustand siehe OP-04 (nicht definiert); UI-Zustand „Daten veraltet“ bei unterbrochenem Strom, nach Wiederverbindung aktueller Stand.
- **JSON-Schema / Ereignisse:** Änderungsereignis für Planrevision/ETA aus WP-M2-28; Feldliste Buchungsdetail aus WP-M2-01.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-04 Detailansicht nach Kap. 06 §6.4: Zusage getrennt von aktueller ETA beschriftet; „Letzter Stand“ mit Zeit; sichtbarer Hinweis bei ETA-Änderung (alt → neu); zurückhaltende Live-Region ohne Fokusverschiebung; Zustand „Daten veraltet“. Abgrenzung: in M2 Plan-ETA, Ausführungszustände und Fahrzeugidentifikation mit Testereignissen/Fixture; Vollabnahme erst mit WP-M3-25, WP-M3-29, WP-M4-62.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-42 (Buchungsnummer: Format, Eindeutigkeitsbereich, Erratbarkeit), OP-35 (sichtbare Fahrzeugmerkmale), OP-25 (Technik der Aktualisierung: SSE/WebSocket, Authentisierung, Wiederaufnahme), OP-04 (Zustandsautomat `booking.state`). Arbeitsannahmen aus WP-M2-01; blockieren den Bau nicht.

## Tests

- **Positiv:** Detailansicht zeigt Buchungsnummer, Status, Treffpunkt, aktuelle ETA und (sobald vorhanden) Fahrzeugidentifikation; serverseitige ETA-Änderung (neue Planrevision) erscheint im offenen Browser ohne Neuladen mit Änderungshinweis und neuem Datenstand; Zusage bleibt dabei unverändert und getrennt von der ETA beschriftet; Zahlungsstatus wird getrennt vom fachlichen Status angezeigt.
- **Negativ:** Stromabbruch markiert Datenstand als veraltet, nach Wiederverbindung aktueller Stand; veraltete Revision wird nicht über neuere gelegt; fremde Buchung ergibt 404; fremde Personal-/Fahrzeugakten nie sichtbar; Screenreader: Live-Region sagt Änderung an, Fokus bleibt.
- **Konkurrierend:** zwei schnell aufeinanderfolgende ETA-Änderungen kommen in Revisionsreihenfolge an (Revision 8 vor 7, Revision 8 mehrfach: Anzeige bleibt auf 8); Änderung während Wiederverbindung geht nicht verloren; ETA-Änderung für Buchung A erreicht Fahrgast B desselben Umlaufs nie.
- **Referenztests:** AT-01 (Status- und ETA-Anzeige, soweit ohne M3 möglich).
- Testfälle: AT-01; geplant:web/passenger/src/booking/BookingDetail.test.tsx::eta_aenderung_erscheint_ohne_neuladen; geplant:web/passenger/src/booking/BookingDetail.test.tsx::stromabbruch_kennzeichnet_daten_als_veraltet; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingDetailApiTest.kt::fremde_buchung_ergibt_404; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingDetailApiTest.kt::fremder_mandant_ergibt_404_und_keine_fremden_akten; geplant:web/passenger/src/booking/BookingDetail.test.tsx::detail_zeigt_nummer_status_treffpunkt_eta_fahrzeug; geplant:web/passenger/src/booking/BookingDetail.test.tsx::zusage_bleibt_bei_eta_aenderung_unveraendert; geplant:web/passenger/src/booking/BookingDetail.test.tsx::wiederverbindung_laedt_aktuellen_stand; geplant:web/passenger/src/booking/BookingDetail.test.tsx::aeltere_revision_ueberschreibt_neuere_nicht; geplant:web/passenger/src/booking/BookingDetail.test.tsx::live_region_sagt_aenderung_an_ohne_fokusverschiebung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
