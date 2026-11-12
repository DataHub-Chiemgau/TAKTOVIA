# LH-6.10-PAY-001 – Preisangebot als unveränderlicher `fare_quote`

- **Verantwortlicher:** Rolle Fachmodul `fare-payment`
- **Arbeitspaket:** WP-M2-09
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Preisangebot wird vor verbindlicher Buchung mit Steuer-/Gebührenbestandteilen angezeigt.

**Originalabnahme (unverändert):** Bestätigter Betrag entspricht eingefrorenem Angebots-Preisstand.

## Vertrag

- **Tabellen / DDL:** `fare_quote` (V1): `tenant_id`, `id`, `attempt_id` (FK `travel_attempt`), `currency` (FK `currency`), `pay_at_platform_minor bigint >= 0`, `external_known_minor` (= 0) und `external_unknown_leg_ids` (leer) im Einbetreiberfall, `expires_at`, `tariff_revision_id uuid NOT NULL` (V1 ohne FK; neu: FK auf `tariff_revision`, WP-M2-07), `config_revision_id` (V1: FK `(tenant_id,config_revision_id)` auf `config_revision`), `snapshot jsonb`, `content_hash char(64)` (SHA-256 über kanonisierten Snapshot), Trigger `quote_immutable` (V1, `reject_mutation`): UPDATE und DELETE schlagen fehl (Invariante „Gegenereignis statt Änderung“, Kap. 04 §4.5). Je Quote `fare_component`-Zeilen (LH-6.10-PAY-002). RLS `tenant_isolation`.
- **API:** Preisblock in den Antworten von `/v1/ride-offers`: Money-Objekt `{amount_minor,currency,exponent}`, `fare_components` mit Netto, Steuer, Gebühren; jedes Angebot enthält den Endpreis vor Hold/Confirm. Wiederverwendung der vorhandenen Schemas `Money` und `FareComponent` aus `api/federation.yaml` (Codes `TRANSPORT`, `DISCOUNT`, `SURCHARGE`, `SUBSIDY`, `COMMISSION`, `TAX`; `amount` als `SignedMoney` aus `api/common.yaml`); die Abbildung „Gebühren“ auf einen dieser Codes ist nicht festgelegt und kommt in den Detailvertrag (WP-M2-01), `COMMISSION` ist keine Kundenkomponente (Kap. 05 §5.2). `/v1/ride-offers` und `/v1/bookings` stehen nur im Endpunktkatalog (Kap. 05 §5.7), nicht in `openapi.yaml`. Confirm in `/v1/bookings` mit akzeptiertem Betrag; weicht er von `fare_quote.pay_at_platform_minor` ab → `409 REQUOTE_REQUIRED`, ohne Buchung und ohne Zahlung. Abgelaufene Quote/Angebot: 409 mit Code aus Kap. 05 §5.4 (`OFFER_EXPIRED`/`HOLD_EXPIRED`), ebenfalls ohne Buchung und ohne Zahlung; Abgrenzung zur Quote-Gültigkeit in WP-M2-21 (OP-20). Zahlungsautorisierung exakt über `pay_at_platform_minor`.
- **Zustandsautomat:** keiner eigener; Quote ist unveränderlich, Ablauf über `expires_at`.
- **JSON-Schema / Ereignisse:** JSON-Schema `fare_quote.snapshot` unter `vertraege/schemas/fare_quote_snapshot.v1.schema.json`; Kanonisierung für `content_hash` (SHA-256) im Detailvertrag; OpenAPI-Schema Preisblock (`api/`, WP-M2-21/-41).
- **Parameter und Kennzahlen:** `config_revision_id` bindet die gültige Konfigurationsrevision (Tabelle `config_revision`, kein Katalogparameter); Hold-Dauer 180 s ist Planungsdefault und nicht Teil dieses Vertrags.
- **Oberfläche:** Preisaufschlüsselung in UI-02 und UI-03 (WP-M2-41, WP-M2-42), Fehlerzustand bei `REQUOTE_REQUIRED`.
- **Entscheidungen:** keine für den Bau.
- **Offene Punkte:** OP-3 (journey-Hülle: `fare_quote` ist nur über `journey.quote_id` mit der Reise verknüpft), OP-12 (Preisstichtag), OP-20 (Gültigkeitsdauer der Quote ist nirgends beziffert; bis dahin Testannahme, Parameter fehlt im Katalog). Der Bau ist damit nicht blockiert, die Quote-Gültigkeit läuft über Testannahme.

## Tests

- **Positiv:** Quote wird je Angebot mit Steuer- und Gebührenkomponenten erzeugt, `content_hash` reproduzierbar; bestätigter und autorisierter Betrag identisch mit `pay_at_platform_minor`.
- **Negativ:** UPDATE und DELETE auf `fare_quote` unter der Runtime-Rolle schlagen fehl (Trigger `quote_immutable`); Confirm mit abweichendem Betrag (höher wie niedriger) → `409 REQUOTE_REQUIRED`, keine Buchung, keine Zahlung; abgelaufene Quote → 409 (`OFFER_EXPIRED`/`HOLD_EXPIRED`), keine Buchung, keine Zahlung; Hold/Confirm ohne Quote nicht möglich; fremder Mandant → 404 (Quote unter RLS fremder Mandanten nicht lesbar); manipulierter `content_hash` bzw. Snapshot nicht nachträglich änderbar.
- **Konkurrierend (Geld):** Tarifrevision veröffentlicht parallel zum Confirm: Betrag bleibt der eingefrorene; zwei parallele Confirms mit derselben Quote ergeben höchstens eine Buchung (Ausführung mit WP-M2-23/-24, Idempotenz).
- **Referenztests:** E2E-096 (künftige Angebote nutzen den neuen Wert) und E2E-098 (Konfigurations-Rollback: Audit und Buchungszusage bleiben korrekt) im M2-Anteil, laut WP-M2-09 über die gebundene `config_revision_id`; Vollnachweis in den jeweiligen M1-/M6-Paketen.
- Testfälle: E2E-096; E2E-098; db/tests/test_fare_quote.py::test_quote_update_delete_abgelehnt; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::confirm_mit_abweichendem_betrag_ergibt_requote; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::tarifwechsel_parallel_zum_confirm_betrag_bleibt_eingefroren; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::quote_enthaelt_steuer_und_gebuehrenkomponenten_hash_reproduzierbar; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::abgelaufene_quote_ergibt_409_ohne_buchung_und_zahlung; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::hold_und_confirm_ohne_quote_nicht_moeglich; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareQuoteTest.kt::confirm_mit_quote_betrag_autorisiert_exakt_pay_at_platform_minor; db/tests/test_fare_quote.py::test_quote_fremder_mandant_unsichtbar; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/booking/RideOfferTest.kt::veraendertes_angebot_requote_oder_revision_mismatch; geplant:web/passenger/src/booking/PriceBreakdown.test.tsx::zeigt_netto_steuer_gebuehren_und_requote_zustand (WP-M2-41/-42, nicht in diesem Paket)

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
