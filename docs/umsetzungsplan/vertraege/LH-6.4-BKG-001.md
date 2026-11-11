# LH-6.4-BKG-001 – Anfrageerfassung, `travel_attempt` und Zeitwunsch

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Anfrageannahme)
- **Arbeitspaket:** WP-M2-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Sofort- und Vorausbuchungen lassen sich mit Abfahrt- oder Ankunftswunsch anfragen.

**Originalabnahme (unverändert):** Entsprechende Anfrage ergibt ein korrektes Zeitfenster oder eine begründete Ablehnung.

## Vertrag

- **Tabellen / DDL:** `travel_attempt` (V1: `tenant_id`, `id`, `created_at`, `channel`, `primary_rejection`, `secondary_rejections text[]`, `coarse_origin`, `coarse_destination`; PK `(tenant_id,id)`); keine exakten Adressen oder Personenbezüge. RLS `tenant_isolation`. Regel „eine `attempt_id` je fachlich neuer Suche; technische Retries zählen nicht neu“ (Kap. 04 §4.3): Erkennung über `Idempotency-Key` (gleicher Schlüssel + gleicher Request-Fingerprint = Retry); eine bewusst neue Suche erzeugt einen neuen Versuch. Vergröberung der Orte (OP-13) ist offen; Arbeitsannahme: Zone/Bediengebietskennung, nie Koordinate oder Adresse.
- **API:** `POST /v1/ride-offers` (Endpunktkatalog Kap. 05 §5.7). Eingabe: Start, Ziel, Personenzahl/Unterstützungsbedarf, Zeitwunsch (`kind` Abfahrt|Ankunft, `desired_at`, `time_zone`), Sofort/Voraus (Abgrenzung ist im Pflichtenheft nicht festgelegt; Arbeitsannahme, im OpenAPI-Detailvertrag zu bestätigen: Sofort = Zeitwunsch „jetzt“ ohne Vorlauf, Voraus = `desired_at` in der Zukunft; der Mindestvorlauf `min_booking_lead_min` gilt nur für Voraus, damit eine Sofortanfrage nicht allein am Vorlauf scheitert). Antwort 200 mit `offers` (je Angebot `TimeWindow {earliest_at,latest_at}` für Abholung und Ankunft nach Kap. 05 §5.9) oder leere Liste plus normiertem Ablehnungsgrund (Grund „Zeitfenster/Frist“ bei Vorlauf/Horizont); 422 nur bei formal fehlerhafter Eingabe (z. B. Personenzahl 0, ungültige Zeitzone, `desired_at` und `time_zone` beschreiben nicht denselben lokalen Zeitpunkt, Kap. 05 §5.1); `Idempotency-Key` Pflicht; gleicher Schlüssel mit anderem Request-Fingerprint → `409 IDEMPOTENCY_CONFLICT` (Kap. 05 §5.1). DST nach Kap. 05 §5.1: nicht existente Ortszeit abgelehnt, doppelte Ortszeit ohne Offset (z. B. 25.10.2026 02:30 `Europe/Berlin`) zur Klärung zurückgewiesen.
- **Zustandsautomat:** keiner (Versuch ist ein Ereignisdatensatz; Zustandsautomat des Angebots siehe BKG-002).
- **JSON-Schema / Ereignisse:** OpenAPI-Schema der Anfrage in `api/` (WP-M2-01); Outbox-Ereignisse „Anfrageversuch erfasst“ und „Anfrageversuch abgelehnt“ mit `attempt_id`, Kanal, Primärgrund, ohne exakte Orte (Datenbasis M4).
- **Parameter und Kennzahlen:** `min_booking_lead_min` (Minuten, Dienst, 0..43200), `max_booking_horizon_days` (Tage, Dienst, 0..365), `pickup_window_min` (Minuten, Dienst, positiv), `arrival_grace_min` (Minuten, Dienst); effektive Werte über `/v1/config/effective`. Kennzahlen (Datenbasis, Berechnung M4): `Anfragen`, `Technische Anfragefehler`, `Zeitfensterablehnungen`.
- **Oberfläche:** Eingabe in UI-01 (WP-M2-37) mit Wahl Abfahrt/Ankunft und Zeit; Ergebnisdarstellung in UI-02 (WP-M2-41); Fehlerzustände Ablehnung mit Grundtext, 422 am Feld.
- **Entscheidungen:** D-11 (Aufbewahrung der Anfragedaten; blockiert nur Produktivschaltung).
- **Offene Punkte:** OP-13 (Auflösung `coarse_origin`/`coarse_destination`); OP-27 (Zahlungsplan bei Vorausbuchungen, betrifft AT-05 nur im Zahlungsteil); OP-14 (Katalog der Ablehnungsgründe, WP-M2-13: Code „Zeitfenster/Frist“ wird dort festgelegt); OP-21 (Unterpfade); OP-38 (synchron oder über RabbitMQ). Die Abgrenzung Sofort/Voraus gegenüber `min_booking_lead_min` ist als „Detailvertrag vorab“ des WP-Dokuments offen (ohne OP-Nummer, Arbeitsannahme siehe API). Keiner blockiert den Bau; Arbeitsannahmen siehe oben.

## Tests

- **Positiv:** vier Kombinationen Sofort/Voraus × Abfahrt/Ankunft ergeben je ein Angebot mit nichtleerem Abhol- und Ankunftsfenster, das zum Wunsch passt (Fenster aus `pickup_window_min`/`arrival_grace_min`); doppelte Ortszeit 25.10.2026 02:30 `Europe/Berlin` einmal mit Offset +02:00 und einmal mit +01:00 ergibt zwei eindeutige Zeitpunkte; AT-05 (Vorausbuchung für den nächsten Betriebszeitraum, eindeutige Zeit-/Regelprüfung); gleicher Idempotenzschlüssel erzeugt keinen zweiten `travel_attempt`, bewusst neue Suche einen neuen.
- **Negativ:** Voraus-Anfrage unter `min_booking_lead_min` und Anfrage jenseits `max_booking_horizon_days` (je Grenzwert knapp innerhalb und knapp außerhalb) → leere Angebotsliste mit Grund „Zeitfenster/Frist“ (kein 422); DST-Lücke abgelehnt; DST-Doppelstunde ohne Offset zurückgewiesen; Personenzahl 0, ungültige Zeitzone und zu `time_zone` inkonsistentes `desired_at` → 422; gleicher `Idempotency-Key` mit anderem Fingerprint → 409 `IDEMPOTENCY_CONFLICT` ohne neuen `travel_attempt`; `travel_attempt` enthält keine exakte Adresse oder Personenbezug (Schemaprüfung); Ankunftswunsch mit nicht erreichbarem Fenster → begründete Ablehnung.
- **Konkurrierend:** Datentrennung: Versuche zweier Mandanten sind getrennt zählbar, paralleler Zugriff über denselben Connection-Pool liefert nie fremde Zeilen; 100 parallele identische Requests mit gleichem `Idempotency-Key` → genau ein `travel_attempt`.
- **Referenztests:** AT-05; Gate M2. Die DST-Fixture entspricht PH-AT-018 (nur Zeitteil, nicht Katalogzuordnung dieser Anforderung).
- Testfälle: AT-05; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferRequestTest.kt::vier_kombinationen_ergeben_zeitfenster; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferRequestTest.kt::vorlauf_horizont_dst_ablehnung; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/TravelAttemptTest.kt::retry_zaehlt_nicht_neu_mandanten_getrennt; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/TravelAttemptTest.kt::hundert_parallele_identische_requests_ein_versuch; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/TravelAttemptTest.kt::travel_attempt_ohne_adresse_und_personenbezug; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferRequestTest.kt::formal_fehlerhafte_eingabe_ergibt_422

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
