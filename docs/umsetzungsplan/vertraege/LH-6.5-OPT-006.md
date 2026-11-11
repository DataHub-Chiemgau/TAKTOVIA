# LH-6.5-OPT-006 – Schutz zugesagter Zeitfenster

- **Verantwortlicher:** Rolle Disposition (Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-19
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zugesagte Abhol-/Ankunftsfenster werden bei Reoptimierung geschützt.

**Originalabnahme (unverändert):** Neue Buchung verletzt kein bereits zugesagtes hartes Zeitfenster.

## Vertrag

- **Tabellen / DDL:** `booking.promise_snapshot` (jsonb, NOT NULL, V1) mit Abhol-/Ankunftsfenster und Konfigurationsrevision je Buchung gespeichert; die Unveränderlichkeit sichert das Zusage-Änderungsverfahren (LH-6.1-SYS-006, `db/tests/test_zusage_snapshot.py`), V1–V3 enthalten dafür keinen eigenen Trigger; `capacity_hold` (`run_id`, `run_revision`, `state`) für gehaltene Buchungen; Planzeiten in `route_stop` (neue Tabelle, WP-M2-16; nicht in V1–V3 vorhanden). Geschützt sind Buchungen in Zustand bestätigt und aktiv gehalten (`capacity_hold.state = 'HELD'`). Gespeicherte Fenster werden durch spätere Änderung von `pickup_window_min` nicht verändert.
- **API:** Wirkung auf `POST /v1/ride-offers` (Einfügung, die nur durch Verletzung eines zugesagten Fensters möglich wäre, wird nicht angeboten) und auf den Hold-Befehl (OPT-009, Abweisung `409 CAPACITY_UNAVAILABLE`); erneute Prüfung beim Hold und beim Confirm gegen die dann aktuelle Planrevision.
- **Zustandsautomat:** keiner neu; nutzt `capacity_hold.state` (OPT-009) und die Buchungszustände (Zustandsautomat der lokalen Buchung ist offen, OP-04 des WP-Dokuments).
- **JSON-Schema / Ereignisse:** JSON-Schema `promise_snapshot` aus WP-M2-01 (Ablage unter `docs/umsetzungsplan/vertraege/schemas/`, Verzeichnis existiert noch nicht, wird mit WP-M2-01 angelegt).
- **Parameter und Kennzahlen:** `pickup_window_min` (Parameteränderung wirkt nicht auf gespeicherte Zusagefenster); Prüfer aus WP-M2-18 (OPT-005) deckt alle Planrevisionen des Testlaufs ab.
- **Oberfläche:** Keine Fahrgastoberfläche; Fahrgast sieht nur, dass das Angebot (nicht) zustande kommt.
- **Entscheidungen:** keine.
- **Offene Punkte:** Zustandsautomat von `booking.state`/`vehicle_run.state` nicht definiert (OP-04 des WP-Dokuments), daher die Schutzregel hier über bestätigt/`HELD` formuliert. Autorisierter Ausnahmeprozess der Leitstelle (OPT-015) nicht im Umfang. Blockiert den Bau nicht.

## Tests

- **Positiv:** Neue Buchung wird in einen Umlauf eingefügt; die Planzeiten aller zuvor bestätigten Buchungen desselben Umlaufs liegen nach der Einfügung weiterhin in ihren zugesagten Fenstern (`route_stop` gegen `promise_snapshot`).
- **Negativ:** Einfügung, die nur durch Verletzung eines bestehenden Fensters möglich wäre, wird nicht angeboten bzw. beim Hold abgewiesen; Änderung von `pickup_window_min` nach Bestätigung verändert kein gespeichertes `promise_snapshot`; Update eines `promise_snapshot` nach Erstellung wird abgewiesen (Nachweis im Test aus SYS-006); Hold/Confirm gegen eine Planrevision, in der die Einfügung inzwischen ein Fenster reißen würde, wird abgewiesen.
- **Konkurrierend:** Zwei parallele Einfügungen in denselben Umlauf, einzeln zulässig, zusammen fensterverletzend: höchstens eine wird gehalten, die andere erhält `409 CAPACITY_UNAVAILABLE` oder wird neu geplant; kein Fenster einer bestätigten Buchung wird verletzt.
- **Referenztests:** AT-02, Grundlage PH-AT-004.
- Testfälle: AT-02; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PromiseWindowProtectionTest.kt::einfuegung_haelt_bestehende_fenster_ein; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PromiseWindowProtectionTest.kt::fensterverletzende_einfuegung_wird_nicht_angeboten_und_beim_hold_und_confirm_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/PromiseWindowProtectionTest.kt::parallele_einfuegungen_hoechstens_eine_gehalten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Zusagen/Kapazität).
