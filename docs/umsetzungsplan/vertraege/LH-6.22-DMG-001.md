# LH-6.22-DMG-001 – Schadenakte: Meldung, Pflichtdaten, Fallnummer

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`, Aggregat DefectCase)
- **Arbeitspaket:** WP-M3-43
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer und berechtigte Mitarbeiter können Schäden unabhängig von Checklisten, Fahrten oder Schichten neu melden.

**Originalabnahme (unverändert):** Schaden kann ohne aktive Buchung mit eindeutiger Fallnummer erfasst werden.

## Vertrag

- **Tabellen / DDL:** `defect_case` mit `tenant_id`, `id`, Fallnummer (eindeutig je Mandant, UNIQUE (`tenant_id`, Fallnummer)), Fahrzeug (FK `vehicle(tenant_id,id)`), Meldender, Meldezeitpunkt, Ereigniszeit, Ereignisort, Kategorie, Beschreibung, Status „neu"; Index über Mandant, Fahrzeug und Meldezeitpunkt. Keine Pflichtspalte für Buchung, Schicht oder Prüflauf (nur optionale Herkunftsreferenzen). RLS über `tenant_id`. In V1–V3 noch nicht vorhanden; DDL aus WP-M3-02. Fallnummernvergabe je Mandant eindeutig (WP-Formulierung „lückenlos eindeutig“; ob Lückenfreiheit bei Rollback verlangt ist, siehe Offene Punkte), nicht aus Personendaten abgeleitet und mandantenübergreifend nicht erratbar zuordenbar.
- **API:** Pfade sind in Kap. 05 §5.7 genannt, in `api/*.yaml` noch nicht vorhanden. `POST /v1/defects` (idempotent, Idempotency-Key), `GET /v1/defects`, `GET /v1/defects/{id}`. 201 bei Anlage; Wiederholung mit gleichem Schlüssel liefert dieselbe Akte; 422 mit Feldverweis bei fehlenden Pflichtdaten (Fahrzeug, Kategorie, Beschreibung, Ereigniszeit) und Ereigniszeit in der Zukunft; 404 für Fahrzeug ohne Bezug des Fahrers bzw. Nutzer ohne Mandantenbezug. Mandant, Meldender, Meldezeitpunkt, Referenz und Anfangsstatus setzt der Server. Fahrer: nur Fahrzeuge mit Bezug (aktuelle oder laut Detailvertrag zulässige Schicht); Meldung verlangt weder Buchung noch laufende Prüfung.
- **Zustandsautomat:** DefectCase, Anfangsstatus „neu" (Weiteres WP-M3-45/-46).
- **JSON-Schema / Ereignisse:** Ereignis „Schaden gemeldet" (Outbox, ohne Freitext/Personendetails); `audit_event`.
- **Parameter und Kennzahlen:** keine; Kategorienkatalog als Detailvertrag vorab.
- **Oberfläche:** UI-11 – Erfassung durch Mitarbeiter, Aktenansicht; UI-10 – Einstieg „Mangel melden" ohne aktive Fahrt (mobile Maske: WP-M3-44). Zustände: Fehler 422 am Feld, Rechte (kein Bezug).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-22 (Form des Ereignisorts: Koordinate/Adresse/Freitext); Fallnummernformat und Kategorienkatalog sind Detailvertrag vorab und vor Codebeginn festzulegen; Bezugsregel „zulässige Schicht" für Fahrer genauer festzulegen. Spannung zum Originaltext „unabhängig von Schichten“: das WP bindet den Fahrer an einen Fahrzeugbezug; welche Fahrzeuge ein Fahrer ohne laufende Schicht melden darf, ist vor Codebeginn festzulegen. „Lückenlos eindeutig“ der Fallnummern: geklärt werden muss, ob nur Dublettenfreiheit oder Lückenfreiheit gemeint ist. Rollen „berechtigte Mitarbeiter“ nicht abgegrenzt (OP-34, D-11). Keine blockiert den Bau der Anlage ohne Buchung.

## Tests

- **Positiv:** Schaden ohne Buchung, Schicht und Prüfung wird erfasst und erhält eine eindeutige Fallnummer; berechtigter Mitarbeiter meldet über UI-11; Akte über Fahrzeug und Datum auffindbar.
- **Negativ:** Benutzer ohne Mandantenbezug darf nicht melden; Fahrer für Fahrzeug ohne Bezug (404); fehlende Pflichtfelder (422 mit Feldverweis); Ereigniszeit in der Zukunft; Clientangaben zu Mandant/Meldender/Status werden nicht übernommen; Fallnummer aus Fremdmandant nicht abrufbar (404, RLS); Fahrer ohne laufende Schicht und ohne Bezug erhält 404, mit zulässigem Bezug (Detailvertrag) kann er melden.
- **Konkurrierend:** parallele Meldungen erhalten unterschiedliche, eindeutige Fallnummern (UNIQUE (`tenant_id`, Fallnummer) hält unter Parallelität); wiederholte Übermittlung mit gleichem Idempotency-Key erzeugt keine zweite Akte.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::meldung_ohne_buchung_erhaelt_eindeutige_fallnummer; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::mitarbeiter_meldet_ueber_ui11_und_akte_ueber_fahrzeug_und_datum_auffindbar; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::nutzer_ohne_mandantenbezug_darf_nicht_melden; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::fahrer_fuer_fahrzeug_ohne_bezug_ergibt_404; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::fehlende_pflichtfelder_und_zukunftszeit_ergeben_422_mit_feldverweis; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::clientwerte_mandant_meldender_status_ignoriert; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::fallnummer_fremdmandant_nicht_abrufbar; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::parallele_meldungen_erhalten_eindeutige_fallnummern; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::gleicher_idempotency_key_erzeugt_keine_zweite_akte; geplant:db/tests/test_defect_case.py::test_fallnummer_je_mandant_eindeutig; geplant:db/tests/test_defect_case.py::test_defect_case_rls_mandantenisolation; geplant:mobile/driver/test/mangel_melden_ohne_fahrt_test.dart::einstieg_mangel_melden_ohne_aktive_fahrt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
