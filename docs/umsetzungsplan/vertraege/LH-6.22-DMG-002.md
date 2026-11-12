# LH-6.22-DMG-002 – Schadenakte: Meldung, Pflichtdaten, Fallnummer

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`, Aggregat DefectCase)
- **Arbeitspaket:** WP-M3-43
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder Schaden hat Referenz, Mandant, Fahrzeug, Meldenden, Meldezeitpunkt, Ereigniszeit/-ort, Kategorie, Beschreibung und Status.

**Originalabnahme (unverändert):** Pflichtdaten werden validiert; Fall ist eindeutig über Fahrzeug und Datum auffindbar.

## Vertrag

- **Tabellen / DDL:** `defect_case` (Migration aus WP-M3-02; Kap. 04 §4.4 Zeile DMG nennt `defect_case`, `defect_evidence`; V1/V2 enthalten die Tabelle noch nicht). Spalten (Arbeitsnamen):
  - `tenant_id uuid NOT NULL REFERENCES tenant(id)`, `id uuid NOT NULL`, `PRIMARY KEY(tenant_id,id)` (Konvention V1).
  - `case_number text NOT NULL` (Referenz/Fallnummer), `UNIQUE(tenant_id, case_number)`; Vergabe je Mandant über eine Sequenz je Mandant, ohne Duplikate (WP-M3-43 Test: lückenlos eindeutig); Format nicht aus Personendaten abgeleitet und mandantenübergreifend nicht erratbar zuordenbar (WP-M3-43 Krit. 8).
  - `vehicle_id uuid NOT NULL`, `FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id)`.
  - `reported_by uuid NOT NULL` (Meldender, serverseitig aus Anmeldung), `reported_at timestamptz NOT NULL` (Server), `event_at timestamptz NOT NULL` (Ereigniszeit, vom Client, nicht in der Zukunft), `event_place` (Ereignisort; laut Originaltext Pflichtbestandteil der Akte, Spaltentyp und Pflichtigkeit hängen an OP-22), `category text NOT NULL` (Kategorienkatalog), `description text NOT NULL`, `state text NOT NULL DEFAULT 'neu'` (Wertebereich laut DMG-007).
  - Index `(tenant_id, vehicle_id, reported_at)` für die Auffindbarkeit über Fahrzeug und Datum.
  - Mandantenisolation per RLS wie V1; keine Felder für Gesundheits- oder Schuldangaben (Kap. 04 §4.4: keine automatische Schuldzuweisung).
  - Anlage erzeugt Eintrag in `audit_event` und `outbox` (Schaden gemeldet) im selben Commit.
- **API:** `POST /v1/defects` (idempotent über Idempotenzschlüssel, Tabelle `idempotency_record`; gleicher Schlüssel liefert dieselbe Akte, keine zweite), `GET /v1/defects` (Filter Fahrzeug und Zeitraum auf `reported_at`/`event_at`), `GET /v1/defects/{id}`. Statuscodes: 201 neu, 200 Wiederholung, 422 mit Feldverweis bei fehlenden/ungültigen Pflichtfeldern (Fahrzeug, Kategorie, Beschreibung, Ereigniszeit), 404 für fremden Mandanten, fremdes Fahrzeug oder nicht berechtigten Dienst, 403 ohne Berechtigung. Der Server setzt `tenant_id`, Meldenden, `reported_at`, Referenz und Anfangsstatus; entsprechende Client-Felder werden ignoriert und nicht übernommen. OpenAPI-Eintrag in `api/` ist mit dem Bau zu ergänzen (heute nicht vorhanden).
- **Zustandsautomat:** Anfangsstatus `neu`; Übergänge siehe LH-6.22-DMG-007.
- **JSON-Schema / Ereignisse:** Ereignis „Schaden gemeldet" (Outbox); Schema `api/events/defect.reported.v1.schema.json` (neu, Name Arbeitsname).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-11 (Erfassung durch Mitarbeiter, Aktenansicht), UI-10 (Einstieg „Mangel melden" ohne aktive Fahrt; Maske in LH-6.22-DMG-004). Zustände: Fehler mit Feldverweis je Pflichtfeld, Leerzustand der Aktenliste, fehlende Rechte.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-22 (Form des Ereignisorts: Koordinate, Adresse, Freitext; blockiert die Festlegung der Spalte `event_place`, nicht den Rest); Fallnummernformat und Kategorienkatalog sind im Pflichtenheft nicht festgelegt und mit WP-M3-43 vorab als Detailverträge zu beschließen. Kein Verweis auf eine Entscheidung D-xx.

## Tests

- **Positiv:** Meldung ohne Buchung, Schicht oder Prüfung mit allen Pflichtdaten ergibt 201, Fallnummer, Status `neu`; Akte wird über `GET /v1/defects?vehicle_id=…&from=…&to=…` genau gefunden; Wiederholung mit gleichem Idempotenzschlüssel liefert dieselbe Akte.
- **Negativ:** Fahrer ohne Bezug zum Fahrzeug (weder aktuelle noch laut Detailvertrag zulässige Schicht) wird abgewiesen, obwohl die Meldung selbst weder Buchung noch Prüfung verlangt (WP-M3-43 Krit. 7); Fallnummer lässt sich nicht aus Personendaten ableiten und nicht mandantenübergreifend zuordnen (Krit. 8); je fehlendes Pflichtfeld (Fahrzeug, Kategorie, Beschreibung, Ereigniszeit) 422 mit Feldverweis; Ereigniszeit in der Zukunft; fremdes Fahrzeug bzw. Fahrzeug anderen Mandanten 404; Client-Werte für `tenant_id`, Meldenden, `reported_at`, Referenz, Status werden nicht übernommen; Benutzer ohne Bezug zum Mandanten abgewiesen.
- **Konkurrierend:** (RLS-Isolation: Datenbanktest, ohne Tenantkontext null Zeilen auf `defect_case`) parallele Meldungen erhalten unterschiedliche, eindeutige Fallnummern je Mandant; parallele Wiederholung desselben Idempotenzschlüssels erzeugt genau eine Akte.
- **Referenztests:** keine Katalog-ID im WP für diese Anforderung genannt (AT-33 nennt WP-M3-44).
- Testfälle: db/tests/test_defect_case.py::test_fallnummer_eindeutig_je_mandant; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::fehlende_pflichtfelder_und_zukunftszeit_ergeben_422_mit_feldverweis; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::parallele_meldungen_erhalten_eindeutige_fallnummern; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::fahrer_fuer_fahrzeug_ohne_bezug_ergibt_404; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::clientwerte_mandant_meldender_status_werden_nicht_uebernommen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectCaseCreateTest.kt::mitarbeiter_meldet_ueber_ui11_und_akte_ueber_fahrzeug_und_datum_auffindbar; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectApiTest.kt::meldung_ohne_buchung_201_mit_fallnummer_etag_und_lesen; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectApiTest.kt::fehlende_pflichtfelder_zukunftszeit_und_clientwerte_ergeben_422_mit_feldverweis; db/tests/test_defect_case.py::test_rls_ohne_tenantkontext_keine_zeilen; web/operator/src/flotte/Schadenakte.test.tsx::zeigt den Leerzustand und Feldfehler der API (422) bei der Erfassung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
