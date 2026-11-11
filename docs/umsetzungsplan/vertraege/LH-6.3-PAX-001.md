# LH-6.3-PAX-001 – UI-01 Startansicht und Buchung in fünf Schritten

- **Verantwortlicher:** Rolle Passenger-Web (UI-01), fachlich Buchung/Disposition
- **Arbeitspaket:** WP-M2-37
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Startansicht zeigt Start, Ziel, Reisezeit, Verfügbarkeit und eigene anstehende Buchungen.

**Originalabnahme (unverändert):** Standardfahrt ist ohne Umwege in höchstens fünf Hauptschritten buchbar.

## Vertrag

- **Tabellen / DDL:** keine eigene Tabelle. Lesen aus `booking` (`state`, `promise_snapshot`, `service_id`), `service` (`name`, `timezone`), Betriebszeiten (im Kern-DDL nicht vorhanden, Ablageort offen, OP-06) und `travel_attempt` (`primary_rejection`) für die Ablehnungsgründe. `booking` hat im Kern-DDL keine Fahrgastspalte (Zuordnung zum Fahrgast über die `journey`-Hülle, `journey.passenger_id`, Entscheidung in WP-M2-01, OP-03); die Liste „anstehend“ filtert serverseitig auf den eigenen Fahrgast. Mandantenisolation per RLS (V1); Fahrgast sieht nur eigene Buchungen.
- **API:** `POST /v1/ride-offers` (Angebot ermitteln, Start/Ziel/Zeit/Reisende/Unterstützung), `GET /v1/bookings` (Filter „anstehend“, nur eigene), `GET /v1/services` (Betriebszeiten, Betriebsstatus). Fremde/nicht sichtbare Buchung: 404. Fehlerantwort mit primärem Ablehnungsgrund und nächster Handlung (Kap. 06 §6.2).
- **Zustandsautomat:** keiner eigener; UI-Zustände: Eingabe, Suche (abbrechbar), Keine Abdeckung, ungültige Zeit, DST-Mehrdeutigkeit (Offsetauswahl mit UTC-Offset), nicht existente Ortszeit (Korrektur verlangt).
- **JSON-Schema / Ereignisse:** keine; Request-Schema `RideOfferRequest` aus WP-M2-01.
- **Parameter und Kennzahlen:** keine neuen. Zeitzone aus `service.timezone`; Anzeige der Zeitzone (z. B. `Europe/Berlin`) in UI-01 nach Kap. 06 §6.2.
- **Oberfläche:** UI-01 nach Kap. 06 §6.2: Felder Von/Nach, Zeit (Abfahrt/Ankunft, Datum, Uhrzeit, Zeitzone), Reisende, Unterstützung (Rollstuhlplatz); Verfügbarkeitshinweis (in Betrieb/außer Betrieb, nächste Betriebszeit); Liste eigener anstehender Buchungen (leer: Leerzustand); Fehlerzustände mit Text und nächster Handlung; alles per Tastatur und Screenreader bedienbar. Nachweisdokument: Schrittzählung des Standardbuchungswegs.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-32 (Definition „Hauptschritt“, „Standardfahrt“, „Verfügbarkeit“ auf der Startansicht: Betriebsstatus oder freie Kapazität). Arbeitsannahme für den Bau: Standardfahrt = ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel; Verfügbarkeit = Betriebsstatus des Dienstes; zu bestätigen durch den Auftraggeber, blockiert den Bau nicht. OP-06 (Betriebszeiten fehlen im Kern-DDL; Datenquelle für den Hinweis bis zur Klärung über Leseport/Fixture). Zählweise Anmeldung/Zahlungsmittelauswahl ungeklärt (OP-32).

## Tests

- **Positiv:** Standardfahrt (ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel) von UI-01 bis Bestätigung in höchstens fünf Hauptschritten ohne Umweg über andere Bereiche (E2E, Schrittzählung im Nachweisdokument); anstehende eigene Buchung erscheint in der Liste (leer: Leerzustand); Start, Ziel, Reisezeit und Verfügbarkeit (in Betrieb/außer Betrieb, nächste Betriebszeit) sind sichtbar; alle Eingaben und die Liste per Tastatur und Screenreader bedienbar (AT-19, Teil).
- **Negativ:** Start/Ziel außerhalb Gebiet bzw. Betriebszeit zeigt „Keine Abdeckung“ mit Grund und nächster Handlung; eine leere Angebotsliste nennt den primären Ablehnungsgrund und eine nächste Handlung; Dienst „außer Betrieb“ wird mit nächster Betriebszeit angezeigt; ungültige oder nicht existente Ortszeit verlangt Korrektur; DST-mehrdeutige Zeit verlangt Offsetauswahl; Abbruch der Suche erzeugt keine Buchung; fremde Buchungen (anderer Fahrgast desselben Mandanten, anderer Mandant) erscheinen nie in der Liste (Datentrennung, 404 bei Direktaufruf).
- **Konkurrierend:** zwei Tabs mit derselben Anfrage erzeugen höchstens eine Buchung; Statuswechsel einer Buchung während der Anzeige verschiebt sie aus „anstehend“; Doppelklick auf „Verbindungen suchen“/Bestätigen mit demselben `Idempotency-Key` erzeugt keine zweite Buchung.
- **Referenztests:** AT-01 (Fahrgastanteil), AT-19 (Teil).
- Testfälle: AT-01; AT-19; geplant:web/passenger/src/booking/StartView.test.tsx::standardfahrt_in_hoechstens_fuenf_schritten; geplant:web/passenger/src/booking/StartView.test.tsx::start_zeigt_anstehende_eigene_buchungen; geplant:tests/acceptance/passenger/test_standardfahrt_fuenf_schritte.py::test_standardfahrt_fuenf_hauptschritte; geplant:web/passenger/src/booking/StartView.test.tsx::leere_angebotsliste_nennt_ablehnungsgrund_und_naechste_handlung; geplant:web/passenger/src/booking/StartView.test.tsx::dst_mehrdeutige_zeit_verlangt_offsetauswahl; geplant:web/passenger/src/booking/StartView.test.tsx::nicht_existente_ortszeit_verlangt_korrektur; geplant:web/passenger/src/booking/StartView.test.tsx::startansicht_per_tastatur_und_screenreader_bedienbar; geplant:backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingListApiTest.kt::fremde_buchungen_nie_in_liste_und_404; geplant:tests/acceptance/passenger/test_standardfahrt_fuenf_schritte.py::test_zwei_tabs_hoechstens_eine_buchung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
