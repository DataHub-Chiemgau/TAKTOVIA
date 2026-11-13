# LH-I.6-KPI-006 – Getrennte Zeitpunkte: geplant, angekündigt, zugesagt, prognostiziert, tatsächlich

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-52
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Geplante, angekündigte, zugesagte, prognostizierte und tatsächliche Abhol-/Ankunftszeiten werden getrennt gespeichert.

**Originalabnahme (unverändert):** Ein Zeitvergleich nutzt für Verspätung die zum Zeitpunkt der Zusage gültige Zielzeit.

## Vertrag

- **Tabellen / DDL:** Zeitfaktentabelle je Fahrgastfahrt und Stopp mit Zeitart (`planned`, `announced`, `promised`, `forecast`, `actual`; Arbeitsnamen), Ereignis Abholung/Ankunft, Wert (timestamptz, absolute Zeitpunkte), Quelle, Version (Zusageversion, Planrevision, Prognoseversion). Append-only: neue Werte erzeugen neue Zeilen, bestehende werden nicht überschrieben. Bezug auf `booking.promise_snapshot` (V1, jsonb mit Zeitfenster) nur über Leseport. Offline-Ist-Zeiten speichern Geräte- und Serverzeit getrennt. Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** Abruf der fünf Zeitarten je Fahrt über `/v1/analytics/metrics` bzw. Detailabruf (Unterpfad im Bau festzulegen; `api/analytics.yaml` fehlt noch). Fehlende Ist-Zeit ergibt `null` mit Grund "nicht bewertbar", nie 0.
- **Zustandsautomat:** keiner; liest Planrevision und Zusageversion.
- **JSON-Schema / Ereignisse:** Konsum von Buchungs-, Planrevisions-, Benachrichtigungs- und Fahrerereignissen (M2/M3); `promise_snapshot` ist untypisiert, Schema der Zeitfenster ist festzulegen.
- **Parameter und Kennzahlen:** Verspätung = Ist-Zeit minus Zielzeit der gebundenen Zusageversion; spätere Umplanung oder Prognose ändert die Vergleichsbasis nicht; einvernehmliche Änderung der Zusage erzeugt eine neue Zusageversion. Konfiguration (`on_time_pickup_tolerance_min`, `pickup_window_min`) wird nur als zum Zusagezeitpunkt gebundene Version verwendet (PH-AT-018).
- **Oberfläche:** Anzeige der fünf Zeitarten je Fahrt (Betriebsdetail, UI-ID nicht festgelegt, OP 23); Zustände: Ist-Zeit fehlt (nicht bewertbar), Zusage nicht vorhanden.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP 28: Die "angekündigte" Zeit hat weder Feld im Kern-DDL noch Definition (vermutlich die dem Fahrgast per Benachrichtigung mitgeteilte Zeit, Quelle WP-M2-34). Ohne diese Festlegung ist die Zeitart `announced` nicht baubar; die vier übrigen Zeitarten sind unabhängig baubar. Der Vertrag hält den Vorschlag fest, Bestätigung durch Fachanalyse steht aus.

## Tests

- **Positiv:** Fahrt mit Umplanung nach der Zusage: alle fünf Zeitarten für Abholung und Ankunft getrennt gespeichert und abrufbar; Verspätung nutzt die Zusageversion, die Umplanung ändert sie nicht; Abruf je Fahrt liefert alle fünf Zeitarten; einvernehmliche Änderung der Zusage erzeugt eine neue Zusageversion, die alte bleibt lesbar; Zeitart `announced` bis zur Festlegung OP 28 nur mit Testwert.
- **Negativ:** Fehlende Ist-Zeit ergibt "nicht bewertbar" statt Nullwert; nachträgliche Änderung von Pünktlichkeitsschwelle oder Tarif ändert die gespeicherte Zusage nicht; UPDATE oder DELETE einer Zeitfaktenzeile wird unter der Runtime-Rolle abgewiesen; Offline-Ist-Zeit behält Geräte- und Serverzeit; DST-Doppelstunde (25.10.2026, 02:30) ergibt zwei eindeutige Zeitpunkte.
- **Konkurrierend:** Zwei parallele Umplanungen und eine Prognoseaktualisierung nach der Zusage: Zusageversion bleibt unverändert, jede Revision erscheint genau einmal; Fakten eines fremden Mandanten sind nicht sichtbar.
- **Referenztests:** PH-AT-018 (Teil Zusage und Schwelle, DST-Fixture 25.10.2026 `Europe/Berlin`); AT-47 (Schicht über DST-Umstellung, e2e.csv) nur mittelbar, deshalb nicht als Testfall-Token geführt.
- Testfälle: PH-AT-018; db/tests/test_zeitfakten.py::test_verspaetung_gegen_zusageversion_nach_umplanung; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/TimeFactsTest.kt::fehlende_ist_zeit_ist_nicht_bewertbar; db/tests/test_zeitfakten.py::test_zeile_ist_unveraenderlich_update_und_delete_abgewiesen; db/tests/test_zeitfakten.py::test_offline_ist_zeit_behaelt_geraete_und_serverzeit; db/tests/test_zeitfakten.py::test_dst_doppelstunde_ergibt_zwei_eindeutige_zeitpunkte; db/tests/test_zeitfakten.py::test_parallele_umplanungen_und_prognose_lassen_zusage_unveraendert_jede_revision_einmal; db/tests/test_zeitfakten.py::test_fakten_eines_fremden_mandanten_sind_nicht_sichtbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/TimeFactsTest.kt::fuenf_zeitarten_getrennt_verspaetung_nutzt_zusageversion_trotz_umplanung; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/TimeFactsTest.kt::einvernehmliche_aenderung_erzeugt_neue_zusageversion_die_alte_bleibt_lesbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/TimeFactsTest.kt::zwei_parallele_umplanungen_und_prognose_lassen_die_zusage_unveraendert_jede_revision_einmal

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

## Bau (WP-M4-52)

- Migration `V40520__zeitfakten.sql`: `time_fact` (append-only, RLS, Eindeutigkeit je Fahrt, Stopp, Zeitart, Versionsschlüssel) und Funktion `time_fact_delay(booking)`; Rechte `R__runtime_grants_m4_52.sql` (SELECT, INSERT, EXECUTE). Port `AnalyticsApi.timeFacts` (`TimeFacts`).
- Festlegungen: Zielzeit der Zusage = Ende des Zusagefensters (`PROMISED.value`, Beginn in `window_earliest`); gebundene Version = höchste `promise_version` des Stopps (eine einvernehmliche Änderung hängt eine neue Version an, Planrevision, Prognose und Ankündigung gehen nie in die Verspätung ein); Ist-Abholung = Einstieg (`CHECK_IN`), Ist-Ankunft = Ausstieg (`CHECK_OUT`); Serverzeit = Empfangszeit, Gerätezeit nur bei Offline-Erfassung (die wirksame Ereigniszeit); Planzeiten aus `dispatch.run_revised` (`booking_changes`), Zusage aus `booking.confirmed` (Version = Aggregatrevision).
- Noch nicht gebaut: HTTP-Abruf (`api/analytics.yaml` fehlt, Pfad im Bau mit WP-M4-46/-53 festzulegen), Verbraucher für `promise.changed` (neue Zusageversion braucht den Leseport der Buchung) und Prognoseversionen (Quelle WP-M4-62), Zeitart `announced` (OP 28; über `record` nur mit Testwert).
