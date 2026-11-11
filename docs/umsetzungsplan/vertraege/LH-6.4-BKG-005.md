# LH-6.4-BKG-005 – Storno mit Fristen und Gründen

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`, Storno)
- **Arbeitspaket:** WP-M2-26
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Buchungen können kontrolliert storniert werden, einschließlich konfigurierbarer Fristen und Gründen.

**Originalabnahme (unverändert):** Verfügbare Kapazität und Fahrerauftrag sind nach Storno konsistent aktualisiert.

## Vertrag

- **Tabellen / DDL:** `booking` (`state`, `revision`), `capacity_hold` (Freigabe, Zustand `RELEASED`), `vehicle_run`/`route_stop` (neue Planrevision ohne die Stopps der Buchung), Statushistorie (WP-M2-25, Tabelle offen OP-22). Storno, Kapazitätsfreigabe unter demselben Kapazitätslock (Run-Zeilen in stabiler Reihenfolge, Kap. 04 §4.5), Historieneintrag mit Grund, Planrevision und Outbox in einer Transaktion. RLS erzwungen.
- **API:** `POST /v1/bookings/{id}/cancel` (Pfadvorschlag, OP-21) mit `Idempotency-Key`, `If-Match`, Grundcode; Antwort mit Erstattungshinweis (aus WP-M2-31); Fehler: außerhalb Frist oder ohne zulässigen Grund → definierter Code (im Detailvertrag, `application/problem+json`); unzulässiger Zustand → 409; 412/428 bei ETag; fremde Buchung → 404; Wiederholung wirkungsgleich.
- **Zustandsautomat:** `booking.state` (OP-4, WP-M2-01): bestätigt → storniert; Hold `CONSUMED`/Kapazität wird freigegeben; unzulässige Übergänge mit Fehlercode.
- **JSON-Schema / Ereignisse:** Ereignisse „Buchung storniert (mit Grund)“, „Planrevision geändert“ (Katalog WP-M2-01); Grundkatalog im Detailvertrag.
- **Parameter und Kennzahlen:** Stornofristen und zulässige Gründe je Dienst, nicht in `kataloge.csv` (als neue Dienstparameter anzulegen, OP-23); Fristbezug im Pflichtenheft nicht festgelegt; Arbeitsannahme (OP-23): Frist = Mindestabstand zum Beginn des zugesagten Abholfensters (`promise_snapshot`), Auswertung mit Serverzeit; Kennzahl Datenbasis `Buchungsstornoquote Fahrgast`.
- **Oberfläche:** über UI-04 Selbststorno (LH-6.3-PAX-009, WP-M2-46); Betreiber-Storno ist nicht Teil von M2.
- **Entscheidungen:** D-05 (freigegebene Stornoregeln des Piloten; blockiert nur Produktivschaltung).
- **Offene Punkte:** OP-23 (Stornofristen/-gründe, Folge nach Fristablauf; Arbeitsannahme Ablehnung ohne Gebühr, PAY-006 ist P2); OP-24 (ein- oder zweistufig; Arbeitsannahme einstufig); OP-22 (Historientabelle); OP-21 (Pfad). Vollabnahme der Fahrerauftrags-Anzeige erst mit WP-M3-27 (kein Bauvorbedarf). Kein Punkt blockiert den Bau.

## Tests

- **Positiv:** Storno kurz vor Abholung (knapp innerhalb der Frist, steuerbare Uhr, Fristgrenze exakt getestet; Wortlaut AT-07 „Storno gebuchter Fahrt kurz vor Abholung“): Kapazität auf allen betroffenen Abschnitten und Dimensionen wieder verfügbar, eine neue Buchung auf denselben Platz gelingt; Fahrtenplan (Fahrerauftrag) trägt neue Revision ohne Stopps der stornierten Buchung, verbleibende Zusagen bleiben eingehalten; Historieneintrag mit Grund; Wiederholung mit gleichem `Idempotency-Key` liefert dasselbe Ergebnis; AT-07 (Routenteil).
- **Negativ:** außerhalb der Frist, unzulässiger Zustand (bereits storniert/abgeschlossen), fehlender oder unzulässiger Grund, fehlender `Idempotency-Key`, veralteter/fehlender `If-Match`, fremde Buchung → 404, jeweils mit definiertem Code und ohne Kapazitäts- oder Planänderung.
- **Konkurrierend:** Storno parallel zu Hold/Confirm auf denselben Abschnitt → nie Überbelegung; doppeltes Storno parallel → keine doppelte Kapazitätsfreigabe (Kapazität nie über physisches Maximum); Storno parallel zum Expiry-Job und zur Neubuchung desselben Platzes → genau ein konsistenter Ausgang; Abbruch mitten in der Transaktion hinterlässt Buchung, Kapazität und Plan unverändert.
- **Referenztests:** AT-07 (Routenteil).
- Testfälle: AT-07; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelBookingTest.kt::storno_gibt_kapazitaet_frei_und_neue_planrevision; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelBookingTest.kt::storno_ausserhalb_frist_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelConcurrencyTest.kt::storno_parallel_zu_confirm_nie_ueberbelegt; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelConcurrencyTest.kt::doppeltes_storno_eine_freigabe; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelBookingTest.kt::verbleibende_zusagen_bleiben_eingehalten; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelBookingTest.kt::unzulaessiger_zustand_grund_und_header_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelBookingTest.kt::fremde_buchung_404; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelConcurrencyTest.kt::storno_parallel_expiry_und_neubuchung_ein_ausgang; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/CancelConcurrencyTest.kt::abbruch_mitten_in_transaktion_unveraendert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
