# LH-6.4-BKG-002 – Angebotsobjekt mit Ablauf und Revisionsbindung

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Angebotsbildung) mit Rolle Tarif/Zahlung für den Preisstand
- **Arbeitspaket:** WP-M2-21
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Angebote besitzen IDs, Ablaufdatum, Preisstand, Abholpunkt und Dienstkennung.

**Originalabnahme (unverändert):** Abgelaufenes oder verändertes Angebot kann nicht blind bestätigt werden.

## Vertrag

- **Tabellen / DDL:** `fare_quote` (V1: `attempt_id`, `currency`, `pay_at_platform_minor`, `expires_at`, `tariff_revision_id`, `config_revision_id`, `snapshot`, `content_hash`; Trigger `quote_immutable`, WP-M2-09). Persistenz des Angebots selbst (Angebots-ID, Planrevision, Abhol-/Zielhaltepunkt als `stop_revision`, Zeitfenster, `service_id`, Revisionsbindung) ist im Kern-DDL nicht festgelegt (OP-19). Arbeitsannahme dieses Vertrags: eigene mandantengetrennte Tabelle `ride_offer` (PK `(tenant_id,id)`, FK `(tenant_id,quote_id)` auf `fare_quote`, `service_id`, `expires_at`, `plan_revision`, `revision`, RLS `tenant_isolation`), unveränderlich nach Erstellung außer Status; die Entscheidung trifft WP-M2-01 vor Codebeginn von WP-M2-21 (Alternative: ausschließlich `fare_quote.snapshot`).
- **API:** Antwort von `POST /v1/ride-offers` (Angebote je mit ID, `expires_at`, Preisblock aus `fare_quote`, Abholpunkt, Dienstkennung); `GET /v1/ride-offers/{id}` liefert genau den angebotenen Stand mit ETag, ohne Neuberechnung unter derselben ID. Folgebefehle auf einem Angebot (Hold/Confirm): abgelaufen (`now >= expires_at`, Serverzeit) → `409 OFFER_EXPIRED`; veraltete Angebotsrevision (`If-Match`) → `412 REVISION_MISMATCH`; ohne `If-Match` → `428 PRECONDITION_REQUIRED`; geänderter Preis/Bedingungen/Haltepunkt/Planrevision → `409 REQUOTE_REQUIRED`; fremdes Angebot → 404; Idempotenz nach Kap. 05 §5.1: ein bereits erfolgreich angenommener Hold mit gleichem `Idempotency-Key` erscheint bei Wiederholung nach `expires_at` nicht nachträglich als abgelaufen (Idempotenzprüfung vor erneutem Ablauf-/ETag-Test), gleicher Schlüssel mit anderem Fingerprint → `409 IDEMPOTENCY_CONFLICT`. Definition „verändert“ und Reaktion je Fall (neu planen oder 409) im Detailvertrag der OpenAPI (`RideOffer`).
- **Zustandsautomat:** Angebot: gültig → abgelaufen/verbraucht (Hold gesetzt); Dokument in WP-M2-01 (`vertraege/zustaende/`); Angebot verspricht keine Kapazität.
- **JSON-Schema / Ereignisse:** `RideOffer` (OpenAPI), `fare_quote.snapshot`; Ereignis „Angebot erstellt“ (mit `attempt_id`, Dienst, Ablauf; Datenbasis M4).
- **Parameter und Kennzahlen:** Angebotsgültigkeit ohne Katalogparameter (`partner_offer_ttl_sec` gilt nur für Partner; OP-20), Arbeitsannahme neuer Dienstparameter; Uhrenreserve für `expires_at` im Detailvertrag. Kennzahlen (Datenbasis): `Angebotsquote`, `Buchungskonversion`.
- **Oberfläche:** UI-02 Angebotsliste/-details mit Ablaufanzeige und Zustand „Angebot abgelaufen – neu suchen“ (WP-M2-41), Preisänderung → erneutes Bestätigen (WP-M2-42).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-19 (Persistenz des Angebots); OP-20 (Gültigkeitsdauer, Parameter); OP-21 (Pfade). Keiner blockiert den Bau über die genannte Arbeitsannahme; die Persistenzentscheidung ist vor WP-M2-21-Codebeginn in WP-M2-01 festzuhalten.

## Tests

- **Positiv:** jedes Angebot enthält ID, `expires_at`, Preisstand (`fare_quote`), Abholpunkt und Dienstkennung; Hold innerhalb der Frist möglich; `GET` desselben Angebots liefert unverändert denselben Inhalt und ETag.
- **Negativ:** abgelaufenes Angebot → 409 `OFFER_EXPIRED` ohne Buchung; Zeitgrenze exakt bei `expires_at` (steuerbare Uhr, `now >= expires_at` = abgelaufen); falscher ETag → 412; fehlender `If-Match` → 428; Preis-/Bedingungsänderung → 409 `REQUOTE_REQUIRED`; Tarifrevision nach Angebot ändert den gebundenen Preisstand nicht; Wiederholung eines erfolgreichen Holds nach `expires_at` liefert das ursprüngliche Ergebnis, nicht `OFFER_EXPIRED`; gleicher Schlüssel mit anderem Fingerprint → 409 `IDEMPOTENCY_CONFLICT`; fremdes Angebot (anderer Fahrgast/Mandant) → 404; nachträgliche Mutation des Angebots/`fare_quote` unter Runtime-Rolle schlägt fehl.
- **Konkurrierend:** zwei parallele Holds auf dasselbe Angebot → höchstens einer erfolgreich (Ausführung in WP-M2-22); Hold-Versuch parallel zum Ablauf des Angebots → genau ein Ausgang, nie „abgelaufen und gehalten“; Tarifveröffentlichung parallel zur Angebotsbildung → Angebot trägt vollständig die alte oder neue Revision.
- **Referenztests:** PH-AT-010 (Einbetreiberanteil: kein Bestätigen gegen abgelaufene Frist).
- Testfälle: PH-AT-010; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::abgelaufenes_angebot_ergibt_409_offer_expired; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::angebot_unveraendert_bei_get_und_etag; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::veraendertes_angebot_requote_oder_revision_mismatch; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::parallele_holds_hoechstens_einer; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::zeitgrenze_exakt_bei_expires_at; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::fremdes_angebot_404; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::fehlender_if_match_428_und_veralteter_412; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::fare_quote_mutation_unter_runtime_rolle_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::wiederholter_hold_nach_ablauf_idempotent

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
