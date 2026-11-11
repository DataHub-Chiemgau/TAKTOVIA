# LH-6.3-PAX-005 – UI-02 Angebotsliste und Angebotsdetails

- **Verantwortlicher:** Rolle Passenger-Web (UI-02), fachlich Buchung/Disposition
- **Arbeitspaket:** WP-M2-41
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Eine Buchungsanfrage zeigt verfügbare Optionen mit Fußweg, Zeitfenster, Fahrtdauer und Preis.

**Originalabnahme (unverändert):** Angezeigte Daten entsprechen beim Bestätigen dem verwendeten Angebotsstand.

## Vertrag

- **Tabellen / DDL:** keine eigene Tabelle in der UI; Anzeige aus dem Angebotsstand (`RideOffer`), Preis aus `fare_quote` (`pay_at_platform_minor`, `external_known_minor`, `external_unknown_leg_ids`, `expires_at`, `snapshot`, `content_hash`; `fare_components` als disjunkte additive Preisbestandteile nach Kap. 05 §5.2 im `snapshot`), bestätigte Werte aus `booking.promise_snapshot`. Persistenz des Angebots ist offen (OP-19).
- **API:** Antwort von `POST /v1/ride-offers`, `GET /v1/ride-offers/{id}` (mit ETag); Hold/Confirm erhalten Angebots-ID und ETag unverändert. Abgelaufenes Angebot: `OFFER_EXPIRED`; geänderter Preis/Terms: `409 REQUOTE_REQUIRED`; Revisionswechsel: `412 REVISION_MISMATCH`; nie stille Bestätigung (Kap. 05 §5.4).
- **Zustandsautomat:** Angebot (UI-Sicht): gültig, abgelaufen (`OFFER_EXPIRED`), geändert (`REQUOTE_REQUIRED`); Zustand „Abgelaufen“ mit erneuter Suche. Offen, ob der Angebotszustand serverseitig geführt wird (OP-19).
- **JSON-Schema / Ereignisse:** Feldliste UI ↔ `RideOffer` aus WP-M2-01.
- **Parameter und Kennzahlen:** Gültigkeitsdauer des Angebots (kein Katalogparameter, OP-20); Hold 180 s ist Planungsdefault.
- **Oberfläche:** UI-02: Ergebniskarte je Angebot mit Abhol- und Zielhaltepunkt, Fußweg (Distanz/Gehzeit, Quelle WP-M2-10), Abhol- und Ankunftsfenster, Fahrtdauer, Preis mit Aufschlüsselung, Ablaufzeit; Details/Bedingungen; Zustand „unbestätigte Barrierefreiheit“; Preis nie clientseitig berechnet oder gerundet (Anzeige des vom Server gelieferten Betrags); externe Preisbestandteile nicht als Gesamtpreis beschriftet (`external_unknown_leg_ids`, Kap. 06 §6.2); Tastatur/Screenreader.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-19 (Persistenz des Angebots), OP-20 (Angebotsgültigkeit/Hold-Dauer ohne Parameter), OP-21 (Unterpfade der Buchungs-API). Arbeitsannahmen aus WP-M2-01; blockieren den Bau nicht, sind aber vor dem Bau von UI-02 festzuschreiben.

## Tests

- **Positiv:** jede Option zeigt Fußweg, Zeitfenster, Fahrtdauer und Preis; E2E vergleicht Anzeige, Angebotsrevision und `promise_snapshot`/`fare_quote` der entstandenen Buchung (identisch).
- **Negativ:** Angebot läuft während der Anzeige ab (keine stille Bestätigung, neue Auswahl verlangt); Preisänderung (`REQUOTE_REQUIRED`); `412` nach Revisionswechsel; fehlender `If-Match` (428); fremdes Angebot ergibt 404; Preis wird clientseitig weder berechnet noch gerundet; Tastatur/Screenreader (AT-19, Teil).
- **Konkurrierend:** Angebot ändert sich zwischen Anzeige und Bestätigung durch parallelen Buchungsvorgang: es wird nur der angezeigte Stand oder gar nichts bestätigt; zwei Tabs bestätigen dasselbe Angebot: höchstens eine Buchung (Verweis WP-M2-42/AT-03).
- **Referenztests:** AT-19 (Teil).
- Testfälle: AT-19; geplant:web/passenger/src/booking/OfferList.test.tsx::angebot_zeigt_fusswegzeitfenster_dauer_preis; geplant:web/passenger/src/booking/OfferList.test.tsx::geaendertes_angebot_wird_nicht_still_bestaetigt; geplant:tests/acceptance/passenger/test_angebotsstand_gleich_buchung.py::test_anzeige_entspricht_promise_snapshot_und_fare_quote; geplant:web/passenger/src/booking/OfferList.test.tsx::abgelaufenes_angebot_verlangt_neue_auswahl; geplant:web/passenger/src/booking/OfferList.test.tsx::requote_required_zeigt_neuen_stand; geplant:web/passenger/src/booking/OfferList.test.tsx::revisionswechsel_412_wird_nicht_still_bestaetigt; geplant:web/passenger/src/booking/OfferList.test.tsx::preis_wird_clientseitig_nicht_berechnet_oder_gerundet; geplant:web/passenger/src/booking/OfferList.test.tsx::angebotsliste_per_tastatur_und_screenreader_bedienbar; geplant:tests/acceptance/passenger/test_angebotsstand_gleich_buchung.py::test_parallele_aenderung_bestaetigt_nur_angezeigten_stand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Geld).
