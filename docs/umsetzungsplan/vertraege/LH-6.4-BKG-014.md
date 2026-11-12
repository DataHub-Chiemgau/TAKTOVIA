# LH-6.4-BKG-014 – Stornoursache Fahrzeugausfall

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`; Datenbasis für Reports in `analytics`)
- **Arbeitspaket:** WP-M2-27
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Storno nach Fahrzeugausfall kann vom normalen Kundenstorno unterschieden werden.

**Originalabnahme (unverändert):** Reports und Abrechnungslogik verwenden den korrekten Grund.

## Vertrag

- **Tabellen / DDL:** Stornogrund mit Verursacherklasse als typisierte Felder: Historieneintrag (LH-6.4-BKG-013, `reason_code`) plus Verursacherklasse `FAHRGAST | BETREIBER | FAHRZEUGAUSFALL`; Ablage am Buchungsabschluss (Spaltennamen im Bau festzulegen, z. B. `booking.cancel_reason_code`, `booking.cancel_cause_class`, nach Setzen unveränderlich, gesichert per Trigger oder Check). Grund ist nachträglich nicht änderbar. Genau ein Storno je Buchung (Zustandsübergang über `booking.revision` CAS). Mandantenisolation per RLS (V1).
- **API:** Grundcode im Storno-Befehl (Pfad offen, OP-21) und in `GET /v1/bookings/{id}` (Felder Grundcode, Verursacherklasse); betreiberseitiger Aufruf über denselben Endpunkt mit Betreiberrolle und Dienst-Scope. Fahrgast darf nur den Grund Kundenstorno setzen, sonst `403`; Betriebspersonal ohne Dienst-Scope: `404`; nachträgliche Änderung des Grundes: abgewiesen (`409`). Pflichtheader `Idempotency-Key` (LH-6.4-BKG-012).
- **Zustandsautomat:** Übergang nach `STORNIERT` (Name vorläufig) laut `vertraege/zustaende/booking.md` (WP-M2-01); Auslöser und Berechtigung je Verursacherklasse als Tabelle im Automaten.
- **JSON-Schema / Ereignisse:** Outbox-Ereignis „Buchung storniert“ mit typisierten Feldern `reason_code` und `cause_class` (Schema unter `api/events/`, Name im Bau, z. B. `booking.cancelled.v1.schema.json`); Verteilung WP-M2-28.
- **Parameter und Kennzahlen:** Datenbasis für `Buchungsstornoquote Fahrgast` (LH-K.1-Zeile-11) und `Buchungsstornoquote Betreiber` (LH-K.1-Zeile-12, Ausschluss „Fahrzeugausfall getrennt“). Die Berechnung der Quoten ist nicht Teil dieses Vertrags (WP-M4-46).
- **Oberfläche:** Stornogrund im Verlauf UI-04 und Betreiberansicht (UI-07, Storno durch Betreiberrolle); Fahrgast sieht Grund Fahrzeugausfall nicht als wählbare Option.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-23 (Stornofristen und -gründe ohne Katalogparameter), OP-24 (ein- oder zweistufiger Storno), OP-21 (Pfade); Erstattungsregeln im Detail sind LH-6.10-PAY-006 (P2). Grundkatalog mit Verursacherklasse und Setz-Berechtigung wird mit diesem Vertrag als Vorschlag festgelegt. Abgrenzung: Auslösung aus dem Störungsablauf erst mit WP-M3-53/54, Berechnung der Quoten mit WP-M4-46. Nicht blockierend.

## Tests

- **Positiv:** zwei Testbuchungen, eine vom Fahrgast, eine wegen Fahrzeugausfall (über betreiberseitigen Storno-Befehl) storniert: Buchungsressource, Historie und Ereignis weisen unterschiedlichen Grund und Verursacherklasse aus; Abfragetest auf der Datenbasis: Fahrgaststorno zählt nur unter `Buchungsstornoquote Fahrgast`, Fahrzeugausfall getrennt; Erstattungslogik (WP-M2-31) wählt bei Fahrzeugausfall die betreiberverursachte Regel.
- **Negativ:** Fahrgast setzt Betreibergrund (Fahrzeugausfall): 403; Grund nachträglich ändern: abgewiesen; Betriebspersonal ohne Dienst-Scope storniert: 404; Storno einer fremden Mandantenbuchung: 404.
- **Konkurrierend (Geld):** Kundenstorno und Betreiberstorno derselben Buchung parallel: genau ein Storno mit genau einem Grund und genau einer Erstattungsentscheidung; Wiederholung mit gleichem `Idempotency-Key` erzeugt keinen zweiten Storno.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::fahrgast_und_fahrzeugausfall_storno_unterscheidbar; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::ereignis_traegt_reason_code_und_cause_class; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::fahrgast_setzt_betreibergrund_ergibt_403; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::grund_nachtraeglich_aendern_abgewiesen; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::betriebspersonal_ohne_dienst_scope_ergibt_404; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::fremde_mandantenbuchung_storno_ergibt_404; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::kunden_und_betreiberstorno_parallel_ein_storno_eine_erstattung; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/db/tests/test_cancel_reason_basis.py::test_stornoquote_datenbasis_trennt_fahrzeugausfall; db/tests/test_cancel_reason_basis.py::test_cancel_grund_unveraenderlich_nach_setzen; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingCancelReasonTest.kt::wiederholung_nach_storno_erzeugt_keinen_zweiten_storno; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingIdempotencyHttpTest.kt::wiederholung_storno_liefert_gleiches_ergebnis; backend/app/src/test/kotlin/de/taktovia/app/booking/BookingHttpApiTest.kt::storno_if_match_grund_frist_und_betreiberstorno

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
