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

## Festlegung im Bau (WP-M2-37)

- **Hauptschritte:** vier (Start/Ziel, Zeit/Reisende/Suche, Angebot wählen, Buchung bestätigen); Zählung und Grenzen in `docs/umsetzungsplan/nachweise/WP-M2-37-schrittzaehlung.md`. Die Bestätigung ist die Minimalfassung von UI-03 (Hold, Lesen der Revision nach dem Hold, Bestätigung); PSP, SCA und Countdown folgen mit WP-M2-42.
- **„Anstehend“:** `GET /v1/bookings` hat keinen Zustandsfilter; die Oberfläche liest bis zu fünf Seiten (je 100) und filtert Zustand `CONFIRMING`/`CONFIRMED`/`RECONCILING` mit spätestem Ankunftsfenster in der Zukunft, sortiert nach frühester Abholung. Neu gelesen wird beim Aktualisieren und beim Zurückkehren in den Tab.
- **Dienstwahl:** Dienstbezug aus `/v1/me`; ohne Einschränkung wird `GET /v1/services` versucht (für Fahrgäste nicht erlaubt: dann Zustand „kein Dienst“). Mehrere Dienste ergeben eine Auswahl (ohne Namen `Dienst n`).
- **Suche:** Idempotency-Key je Inhalt der Anfrage (Wiederholung nach Verbindungsabbruch gleicher Schlüssel); Abbruch bricht die Anfrage ab, bucht nie. Offsetauswahl und Lücke über `DateTimeField`.
- **Testgrenze:** Die Oberflächentests laufen in jsdom gegen einen Serverersatz (`buchungsWelt.tsx`); ein Browserlauf gegen das echte Backend ist nicht ausgeführt.

## Tests

- **Positiv:** Standardfahrt (ein Fahrgast, Sofortfahrt, hinterlegtes Zahlungsmittel) von UI-01 bis Bestätigung in höchstens fünf Hauptschritten ohne Umweg über andere Bereiche (E2E, Schrittzählung im Nachweisdokument); anstehende eigene Buchung erscheint in der Liste (leer: Leerzustand); Start, Ziel, Reisezeit und Verfügbarkeit (in Betrieb/außer Betrieb, nächste Betriebszeit) sind sichtbar; alle Eingaben und die Liste per Tastatur und Screenreader bedienbar (AT-19, Teil).
- **Negativ:** Start/Ziel außerhalb Gebiet bzw. Betriebszeit zeigt „Keine Abdeckung“ mit Grund und nächster Handlung; eine leere Angebotsliste nennt den primären Ablehnungsgrund und eine nächste Handlung; Dienst „außer Betrieb“ wird mit nächster Betriebszeit angezeigt; ungültige oder nicht existente Ortszeit verlangt Korrektur; DST-mehrdeutige Zeit verlangt Offsetauswahl; Abbruch der Suche erzeugt keine Buchung; fremde Buchungen (anderer Fahrgast desselben Mandanten, anderer Mandant) erscheinen nie in der Liste (Datentrennung, 404 bei Direktaufruf).
- **Konkurrierend:** zwei Tabs mit derselben Anfrage erzeugen höchstens eine Buchung; Statuswechsel einer Buchung während der Anzeige verschiebt sie aus „anstehend“; Doppelklick auf „Verbindungen suchen“/Bestätigen mit demselben `Idempotency-Key` erzeugt keine zweite Buchung.
- **Referenztests:** AT-01 (Fahrgastanteil), AT-19 (Teil).
- Testfälle: AT-01; AT-19; web/passenger/src/booking/StartView.test.tsx::standardfahrt_in_hoechstens_fuenf_schritten; web/passenger/src/booking/StartView.test.tsx::start_zeigt_anstehende_eigene_buchungen; web/passenger/src/booking/StartView.test.tsx::leere_angebotsliste_nennt_ablehnungsgrund_und_naechste_handlung; web/passenger/src/booking/StartView.test.tsx::dst_mehrdeutige_zeit_verlangt_offsetauswahl; web/passenger/src/booking/StartView.test.tsx::nicht_existente_ortszeit_verlangt_korrektur; web/passenger/src/booking/StartView.test.tsx::abbruch_der_suche_bestaetigt_keine_buchung; web/passenger/src/booking/StartView.test.tsx::startansicht_per_tastatur_und_screenreader_bedienbar; tests/acceptance/passenger/test_standardfahrt_fuenf_schritte.py::test_schrittzaehlung_dokument_entspricht_code_und_obergrenze; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingListApiTest.kt::fremde_buchungen_nie_in_liste_und_404; backend/app/src/test/kotlin/de/taktovia/app/booking/StandardfahrtHttpApiTest.kt::standardweg_angebot_hold_bestaetigung_liste_in_vier_aufrufen; backend/app/src/test/kotlin/de/taktovia/app/booking/StandardfahrtHttpApiTest.kt::zwei_tabs_hoechstens_eine_buchung_je_angebot

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung).
