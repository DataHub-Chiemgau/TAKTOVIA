# LH-6.7-OPS-002 – Auftragsliste mit Suche

- **Verantwortlicher:** Rolle Leitstelle/Dispatch (Modul `booking-dispatch`, Web `web/operator`)
- **Arbeitspaket:** WP-M3-37
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Auftragsliste unterstützt Suche nach Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug und Status.

**Originalabnahme (unverändert):** Eine bestehende Buchung ist über alle definierten Suchfelder auffindbar.

## Vertrag

- **Tabellen / DDL:** Suchabfrage über `booking` (`state`, `service_id`, `promise_snapshot`), `passenger_trip` (Fahrgastbezug, M2), `capacity_hold` → `vehicle_run.vehicle_id` (Fahrzeug) und `passenger_private` (Fahrgast; Kontakt als `contact_ciphertext`, daher keine Klartextsuche). Neue Suchindizes mit führender `tenant_id` (Migration ab V3, Name im Bau; Arbeitsname `V3__booking_search_indexes.sql`). Buchungsnummer: Format ist M2 Offener Punkt 42. RLS (V1) plus Dienstscope in der Anwendung.
- **API:** Listenabfrage `GET /v1/bookings` im Leitstellenkontext (Scope Disposition) bzw. Operator-Variante laut OpenAPI-Entwurf, mit Filtern `booking_number`, Zeitraum (`from`,`to`), `passenger`, `vehicle_id`, `state`; Kombination = Schnittmenge. Antwort `{items,next_cursor,as_of}`, Cursor-Paginierung mit stabilem Sortierschlüssel, `limit` > 100 und manipulierter Cursor werden abgewiesen (`application/problem+json`, Statuscode laut OpenAPI-Entwurf). Nicht berechtigter Dienst oder fremder Mandant: Buchung erscheint in keinem Ergebnis, Einzelzugriff 404. Keine Treffer: Status 200 mit leerer Liste und `as_of`, kein Fehler. Trefferzeilen enthalten nur disponierende Felder; Unterstützungsmerkmale nur nach Feldfreigabe der Rolle (WP-M1-31).
- **Zustandsautomat:** keiner neu (Statuswerte aus Buchungszustandsautomat M2).
- **JSON-Schema / Ereignisse:** Antwortschema `booking_search_result` (Arbeitsname). Suchbegriffe zu Personen stehen weder in Logs noch in Metriklabels.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-07 – Auftragsliste mit Suchleiste (fünf Suchfelder), Sortierung, ausdrücklicher Leerzustand, Datenstand.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-26 (Suchverfahren für den Fahrgast bei verschlüsselten Kontaktdaten: Blind-Index, exakte Suche, Suchfeldumfang, berechtigte Rollen) – der Fahrgast-Suchpfad ist bis zur Klärung nicht festgelegt; Suche nach Buchungsnummer, Zeitraum, Fahrzeug und Status ist davon unabhängig. Buchungsnummernformat (M2 Offener Punkt 42; `booking` hat dafür bisher keine Spalte). Worauf sich „Zeitraum" bezieht (Zusagefenster aus `promise_snapshot`, Abholzeit oder Anlagezeit), legt das Pflichtenheft nicht fest; Arbeitsannahme Zusagefenster, im OpenAPI-Entwurf zu bestätigen. Da die Originalabnahme „alle definierten Suchfelder" verlangt, muss OP-26 vor Abnahme des Fahrgastfeldes entschieden sein; der Bau der übrigen vier Felder wird dadurch nicht blockiert.

## Tests

- **Positiv:** Eine bestehende Testbuchung ist über jedes der fünf Suchfelder einzeln auffindbar (Buchungsnummer, Zeitraum, Fahrgast, Fahrzeug, Status); kombinierte Filter liefern die Schnittmenge; keine Treffer ergibt Leerzustand mit `as_of`.
- **Negativ:** Buchung eines fremden Dienstes und eines fremden Mandanten erscheint in keinem Ergebnis; `limit` > 100; manipulierter Cursor; Fahrgastsuche durch eine Rolle ohne Recht auf Fahrgastdaten; Suchbegriffe zu Personen fehlen in Logs und Metriklabels; Unterstützungsmerkmale ohne Feldfreigabe nicht in der Trefferliste.
- **Konkurrierend:** Parallele Suchen zweier Mandanten über wiederverwendete Poolverbindungen liefern nur eigene Buchungen; Rechteentzug am Dienst während einer paginierten Suche – die nächste Seite liefert keine Treffer dieses Dienstes.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::jede_der_fuenf_suchfelder_findet_bestehende_buchung; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::fremder_dienst_und_fremder_mandant_ohne_treffer; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::rechteentzug_waehrend_paginierter_suche; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::kombinierte_filter_liefern_schnittmenge_und_leere_antwort_hat_as_of; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::limit_ueber_100_und_manipulierter_cursor_abgewiesen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::fahrgastsuche_ohne_recht_auf_fahrgastdaten_abgewiesen; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::suchbegriffe_nicht_in_logs_und_metriklabels; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/operator/BookingSearchTest.kt::parallele_suchen_zweier_mandanten_bleiben_getrennt; geplant:web/operator/src/bookings/BookingList.test.tsx::leerzustand_und_fuenf_suchfelder_sichtbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
