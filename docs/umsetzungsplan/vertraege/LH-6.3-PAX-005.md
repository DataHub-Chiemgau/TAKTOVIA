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

## Festlegung im Bau (WP-M2-41)

- **Feldliste UI ↔ `RideOffer`:** Abhol- und Zielhaltepunkt (Name über `GET /v1/stops/{id}`), Abhol- und Ankunftsfenster (`pickup_window`, `arrival_window`, in der Zone des Dienstes mit UTC-Offset), Fahrtdauer (`direct_seconds` aus der Suchantwort, sonst abgeleitet und mit „ca.“ gekennzeichnet), Preis `gross` und `fare_components` (Details), Ablauf `expires_at`, Bedingungen (`cancellation_policy_ref`, Barrierefreiheit des Haltepunkts; `UNVERIFIED` wird als „unbestätigt“ gezeigt).
- **Preis:** nur Zerlegung des Serverwerts in Text (`formatMoney`), nie Rechnen, Summieren oder Runden; die Komponenten werden nicht zum Gesamtpreis addiert.
- **Fußweg (Abweichung):** das Angebot trägt keine Fußwegangabe aus dem Routing (WP-M2-10 liefert nur Fahrzeiten). Die Oberfläche zeigt Luftlinie zwischen dem gewählten Ort und dem Haltepunkt und die Gehzeit `ceil(m / 80 m/min)`, gekennzeichnet „Luftlinie, ca.“. Eine serverseitige Fußwegangabe im `RideOffer` ist offen. Ohne den gewählten Ort (Neuladen der Seite) ist der Fußweg „nicht verfügbar“.
- **Stand und Revision:** die Oberfläche hält Angebots-ID und Revision (ETag), sendet sie unverändert als `If-Match` an den Hold, liest nach dem Hold das Angebot neu (die Revision entspricht dem ETag der Hold-Antwort, belegt im Backendtest) und verwendet diese für die Bestätigung. Weicht der gelesene Stand ab, wird der Hold freigegeben und nicht bestätigt.
- **Änderung und Ablauf:** `409 REQUOTE_REQUIRED`, `412`, `OFFER_EXPIRED`/`HOLD_EXPIRED` führen zum neuen Stand bzw. „Abgelaufen“ mit Neusuche; die Auswahl ist zurückgenommen, bestätigt wird nur nach erneuter Auswahl. Unklarer Ausgang (Netz, 5xx, `OPERATION_IN_PROGRESS`) behauptet weder Erfolg noch Ablehnung; „Stand prüfen“ wiederholt mit demselben Idempotency-Key.
- **Testgrenze:** wie WP-M2-37 (jsdom gegen Serverersatz; Serververhalten durch `StandardfahrtHttpApiTest`). Das E2E-Muster „Anzeige = `promise_snapshot`/`fare_quote`“ ist serverseitig belegt, im Browser nicht ausgeführt.

## Tests

- **Positiv:** jede Option zeigt Fußweg, Zeitfenster, Fahrtdauer und Preis; E2E vergleicht Anzeige, Angebotsrevision und `promise_snapshot`/`fare_quote` der entstandenen Buchung (identisch).
- **Negativ:** Angebot läuft während der Anzeige ab (keine stille Bestätigung, neue Auswahl verlangt); Preisänderung (`REQUOTE_REQUIRED`); `412` nach Revisionswechsel; fehlender `If-Match` (428); fremdes Angebot ergibt 404; Preis wird clientseitig weder berechnet noch gerundet; Tastatur/Screenreader (AT-19, Teil).
- **Konkurrierend:** Angebot ändert sich zwischen Anzeige und Bestätigung durch parallelen Buchungsvorgang: es wird nur der angezeigte Stand oder gar nichts bestätigt; zwei Tabs bestätigen dasselbe Angebot: höchstens eine Buchung (Verweis WP-M2-42/AT-03).
- **Referenztests:** AT-19 (Teil).
- Testfälle: AT-19; web/passenger/src/booking/OfferList.test.tsx::angebot_zeigt_fusswegzeitfenster_dauer_preis; web/passenger/src/booking/OfferList.test.tsx::angezeigter_stand_gleich_bestaetigter_buchung; web/passenger/src/booking/OfferList.test.tsx::geaendertes_angebot_wird_nicht_still_bestaetigt; web/passenger/src/booking/OfferList.test.tsx::abgelaufenes_angebot_verlangt_neue_auswahl; web/passenger/src/booking/OfferList.test.tsx::requote_required_zeigt_neuen_stand; web/passenger/src/booking/OfferList.test.tsx::revisionswechsel_412_wird_nicht_still_bestaetigt; web/passenger/src/booking/OfferList.test.tsx::preis_wird_clientseitig_nicht_berechnet_oder_gerundet; web/passenger/src/booking/OfferList.test.tsx::angebotsliste_per_tastatur_und_screenreader_bedienbar; backend/app/src/test/kotlin/de/taktovia/app/booking/StandardfahrtHttpApiTest.kt::anzeige_entspricht_promise_snapshot_und_fare_quote; backend/app/src/test/kotlin/de/taktovia/app/booking/StandardfahrtHttpApiTest.kt::parallele_aenderung_bestaetigt_nur_angezeigten_stand_oder_nichts

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Geld).
