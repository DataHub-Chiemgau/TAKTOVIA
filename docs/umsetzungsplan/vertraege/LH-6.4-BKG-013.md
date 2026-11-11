# LH-6.4-BKG-013 – Statushistorie der Buchung

- **Verantwortlicher:** Rolle Buchung/Disposition (Modul `booking-dispatch`; Datenbank-Rolle für append-only Tabelle)
- **Arbeitspaket:** WP-M2-25
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jede Buchung besitzt eine vollständige Statushistorie mit Änderungsgrund.

**Originalabnahme (unverändert):** Ein Prüfer kann den Ablauf von Anfrage bis Abschluss rekonstruieren.

## Vertrag

- **Tabellen / DDL:** neue Migration `V<n>__booking_status_history.sql` mit append-only Historientabelle; Name im Pflichtenheft nicht festgelegt (OP-22), Vorschlag `booking_status_history` (nicht `booking_change`, das Kap. 04 §4.4 für die Änderungsannahme BKG-006 reserviert). Spalten: `tenant_id`, `id`, `booking_id`, `from_state` (NULL beim ersten Eintrag), `to_state`, `reason_code` NOT NULL, `actor_ref`, `occurred_at`, `booking_revision` (Wert nach dem Übergang), `correlation_id`, Verknüpfungen `attempt_id` (`travel_attempt`), `hold_id`, `payment_intent_id` (nullable). `PRIMARY KEY(tenant_id, id)`, `UNIQUE(tenant_id, booking_id, booking_revision)`, FK `(tenant_id, booking_id)` auf `booking`. Trigger `BEFORE UPDATE OR DELETE ... EXECUTE FUNCTION reject_mutation()` analog `economic_immutable`; Runtime-Rolle ohne UPDATE/DELETE (`R__runtime_grants.sql`, Grants erweitern); RLS nach Mandant wie übrige Tabellen; zusätzlich `tenant_id_immutable`-Trigger (V3 deckt nur zum damaligen Zeitpunkt vorhandene Tabellen ab); Einträge in `db/table_owners.csv` und `db/role_matrix.csv`. Invariante: Anzahl Einträge je Buchung = `booking.revision`. Jeder Zustandsübergang von `booking` schreibt den Eintrag in derselben Transaktion.
- **API:** `GET /v1/bookings/{id}/history` (Pfadvorschlag, OP-21) für berechtigte Rollen (Prüfer, Betreiber mit Dienst-Scope); Fahrgast erhält eine reduzierte Sicht der eigenen Buchung (Zeitpunkt, Zustand, Grund in Fahrgastsprache, keine Akteur-IDs Dritter). Prüfersicht für den Fahrgast: `403`; fremder Mandant oder fremder Dienst: `404` (einheitlich, vgl. LH-6.2-IAM-005). Sichtbarkeitsregel Fahrgast/Betreiber ist im Pflichtenheft nicht festgelegt und wird mit dem Vertrag als Vorschlag festgelegt.
- **Zustandsautomat:** `booking.state` ist freier Text und ohne Automaten (OP-4); der Automat entsteht in WP-M2-01 (`vertraege/zustaende/booking.md`); die Historie verwendet dessen Zustände und lehnt unzulässige Übergänge ab.
- **JSON-Schema / Ereignisse:** keine eigenen; Änderungsgründe als Katalog, gemeinsam mit den Stornogründen aus LH-6.4-BKG-014 (Grundcodes u. a. Kundenstorno, Betreiberstorno, Fahrzeugausfall, Hold abgelaufen, Bestätigung, Check-in, Fahrtende, No-Show; Endfassung im Bau).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** Verlauf in UI-04 (Aktive/alte Reisen, WP-M2-43) und Listenansicht für Prüfer im Operator-Web; Zustände leer (nur Anfrageeintrag), Fehler, fehlende Rechte (403).
- **Entscheidungen:** D-11 (Aufbewahrungsfrist der Historie, blockiert nur die Produktivschaltung, nicht den Bau).
- **Offene Punkte:** OP-22 (Tabellenname), OP-4 (Zustandsautomat `booking.state`), OP-21 (Pfad). Abgrenzung: In M2 ist die Kette Anfrage, Angebot, Hold, Bestätigung, Storno an echten Übergängen nachgewiesen; der Zweig Abschluss (Check-in, Fahrtende, No-Show) nur mit Testereignissen, echte Nachweise mit WP-M3-29 und WP-M3-30. Nicht blockierend.

## Tests

- **Positiv:** vollständiger Lebenslauf einer Testbuchung: aus Historie und verknüpften Objekten (`travel_attempt`, Angebot, Hold, Zahlung) lässt sich Anfrage, Angebot, Hold, Bestätigung, Storno mit Zeitpunkten, Akteuren und Gründen lückenlos rekonstruieren; Anzahl Einträge entspricht `booking.revision`; Abschlusszweig mit Testereignissen nach Automat.
- **Negativ:** UPDATE/DELETE auf die Historientabelle unter Runtime-Rolle abgewiesen (Trigger und Grants); Übergang ohne Änderungsgrund abgewiesen; Grund nicht im Katalog abgewiesen; laut Automat unzulässiger Übergang abgewiesen und ohne Historieneintrag; Fahrgast ruft Prüfersicht ab: 403.
- **Konkurrierend:** zwei parallele Zustandsübergänge derselben Buchung: genau einer gewinnt, Historie und `booking.revision` bleiben deckungsgleich (keine Lücke, kein Duplikat durch `UNIQUE(tenant_id, booking_id, booking_revision)`).
- **Datentrennung:** Historie fremder Mandanten nicht lesbar (RLS, 404 in der API); fremder Dienst ohne Scope: 404.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:db/tests/test_booking_status_history.py::test_update_delete_abgewiesen; geplant:db/tests/test_booking_status_history.py::test_historieneintraege_gleich_booking_revision; geplant:db/tests/test_booking_status_history.py::test_uebergang_ohne_grund_abgewiesen; geplant:db/tests/test_booking_status_history.py::test_grund_nicht_im_katalog_abgewiesen; geplant:db/tests/test_booking_status_history.py::test_rls_fremder_mandant_nicht_lesbar; geplant:db/tests/test_booking_status_history.py::test_tenant_id_unveraenderlich; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::lebenslauf_anfrage_bis_storno_rekonstruierbar; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::abschlusszweig_mit_testereignissen_nach_automat; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::unzulaessiger_uebergang_ohne_historieneintrag; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::fahrgast_pruefersicht_ergibt_403; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::fahrgast_sicht_ohne_akteur_ids_dritter; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::parallele_uebergaenge_ein_gewinner_revision_deckungsgleich; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::fremder_mandant_historie_nicht_lesbar; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/BookingHistoryTest.kt::fremder_dienst_ohne_scope_ergibt_404

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
