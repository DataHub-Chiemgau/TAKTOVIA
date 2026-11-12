# LH-6.4-BKG-012 – Idempotenz der Buchungsbefehle

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`; Idempotenzregister aus `platform`)
- **Arbeitspaket:** WP-M2-24
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Buchungsbefehle sind durch Idempotenzschlüssel gegen Doppelbuchungen geschützt.

**Originalabnahme (unverändert):** Wiederholung desselben Requests erzeugt nicht mehrere aktive Buchungen.

## Vertrag

- **Tabellen / DDL:** `idempotency_record` (V1): Primärschlüssel `(tenant_id, principal_ref, command_scope, key)`, `request_sha256 char(64)`, `operation_id`, `response_json`, `expires_at`; `tenant_id` aus dem Mandantenkontext, nie aus dem Body. Zweite Sicherung durch fachliche Constraints (V1): `UNIQUE(tenant_id, hold_id)` und `UNIQUE(tenant_id, federation_ref)` auf `booking`, `UNIQUE(tenant_id, federation_ref)` auf `capacity_hold`. Diese bleiben auch nach Ablauf von `idempotency_record.expires_at` wirksam. Cachehaltung Entwurfswert 72 Stunden (Kap. 05 §5.1). Keine neue Migration erwartet.
- **API:** Pflichtheader `Idempotency-Key` (`api/common.yaml#/components/parameters/IdempotencyKey`) an `POST /v1/ride-offers/{id}/hold`, `POST /v1/bookings` und am Storno-Befehl (Pfade für Hold/Storno sind im Pflichtenheft nicht festgelegt, OP-21 des WP-Dokuments; Pfadvorschlag aus WP-M2-24). Fehlender Schlüssel: `4xx` laut einheitlichem Fehlermodell (WP-M1-13). `409 IDEMPOTENCY_CONFLICT` bei gleichem Schlüssel und abweichendem Fingerprint (ohne Ausführung); `409 OPERATION_IN_PROGRESS` bei gleichzeitig laufender identischer Ausführung. Reihenfolge nach Kap. 05 §5.1: Authentisierung, dann Prüfung des Idempotenzeintrags, dann erst ETag-/Angebotsablauftest; Authentisierung und aktuelle Leseberechtigung werden bei Wiederholung erneut geprüft. Wiederholung liefert dieselbe Buchungs-/Operations-ID und dasselbe HTTP-Ergebnis.
- **Fingerprint:** kanonisch (sortierte Schlüssel, normalisierte Zahlen/Zeiten, UTF-8, SHA-256) über Operationsname, Ressourcen-ID (Angebots-/Hold-/Buchungs-ID), fachlich relevante Header (`If-Match`) und Body inklusive Betrag. `command_scope`-Namen (Vorschlag, im Bau festzuschreiben): `booking.hold`, `booking.confirm`, `booking.cancel`.
- **Zustandsautomat:** keiner eigener; Zustände des Idempotenzeintrags: nicht vorhanden, in Ausführung, abgeschlossen (mit `response_json`), abgelaufen.
- **JSON-Schema / Ereignisse:** keine eigenen; die Wiederholung erzeugt kein weiteres Outbox-Ereignis (`UNIQUE(tenant_id, aggregate_id, aggregate_revision, event_type)`).
- **Parameter und Kennzahlen:** keine im Katalog; Cachehaltung 72 h ist Entwurfswert aus Kap. 05 §5.1.
- **Oberfläche:** keine eigene; UI-03 (Checkout) muss Doppelklick durch denselben Schlüssel je Bestätigungsversuch abbilden (Kap. 06).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-21 (Unterpfade Hold/Storno, synchron oder `operation` mit 202); Verhalten nach Cacheablauf: fachliche Constraints liefern dann Konflikt statt zweiter Buchung, die genaue Antwort (Rückgabe der bestehenden Buchung oder `409`) ist nicht festgelegt, Annahme: `409` mit Verweis auf die bestehende Buchung. Nicht blockierend für den Bau.

## Tests

- **Positiv:** identische Wiederholung desselben Confirm (gleicher Schlüssel, gleicher Body) liefert dieselbe Buchungs-/Operations-ID, dasselbe HTTP-Ergebnis und genau eine aktive Buchung, auch nach Ablauf des Angebots (Idempotenzprüfung vor ETag-/Ablauftest); gleiches für Hold und Storno; Wiederholung erzeugt kein weiteres Outbox-Ereignis.
- **Negativ:** gleicher Schlüssel mit anderem Angebot oder anderem Betrag: `409 IDEMPOTENCY_CONFLICT`, keine Ausführung, kein Seiteneffekt; fehlender `Idempotency-Key`: Ablehnung; gleicher Schlüssel in anderem Mandanten, von anderem Akteur oder für anderen Operationstyp kollidiert nicht (kein Cross-Tenant-Treffer, keine fremde Antwort); nach Cacheablauf entsteht keine zweite aktive Buchung (Constraint greift); bei Wiederholung wird Authentisierung und heutige Leseberechtigung erneut geprüft (entzogene Berechtigung: keine gecachte Antwort).
- **Konkurrierend (Kapazität/Geld):** 100 identische Requests parallel: genau eine Buchung, alle übrigen erhalten dasselbe Ergebnis oder `409 OPERATION_IN_PROGRESS`; zwei verschiedene Schlüssel parallel auf demselben Angebot/Hold: höchstens eine Buchung (ETag und `UNIQUE(tenant_id, hold_id)`).
- **Referenztests:** PH-AT-011 (Buchungsanteil: gleicher Schlüssel mit Angebot Q2 oder anderem Betrag ergibt Konfliktfehler, gleicher Schlüssel/gleicher Body denselben Operation-Identifier, keine Kollision zwischen Mandanten, Akteuren und Operationstypen). Die Kapazitätsrennen (letzter Platz unter Konkurrenz) gehören zu WP-M2-22/23 und sind hier nicht beansprucht.
- Testfälle: PH-AT-011; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::wiederholung_confirm_liefert_gleiche_buchung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::wiederholung_nach_angebotsablauf_liefert_gleiche_buchung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::hundert_parallele_identische_requests_eine_buchung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::zwei_schluessel_parallel_hoechstens_eine_buchung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::nach_cacheablauf_keine_zweite_aktive_buchung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingIdempotencyTest.kt::hold_und_confirm_mit_anderem_betrag_sind_keine_wiederholung; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::wiederholung_confirm_liefert_gleiche_buchung_auch_nach_angebotsablauf; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::wiederholung_hold_liefert_gleiches_ergebnis; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::fingerprint_abweichung_ergibt_409_ohne_ausfuehrung; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::fehlender_idempotency_key_wird_abgelehnt; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::schluessel_kollidiert_nicht_zwischen_mandant_akteur_operationstyp; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::wiederholung_prueft_authentisierung_und_leseberechtigung_erneut; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::nach_cacheablauf_keine_zweite_aktive_buchung; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::hundert_parallele_identische_requests_eine_buchung; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::zwei_schluessel_parallel_hoechstens_eine_buchung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
