# LH-6.22-DMG-003 – Abweichung mit Foto und Schadenvorgang aus Prüfschritt oder Störung

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-20
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein Schaden kann aus einer negativen Prüffrage oder einer Betriebsstörung unter Übernahme der jeweiligen Referenzen erzeugt werden.

**Originalabnahme (unverändert):** Verknüpfung aus Checkliste zur Schadenakte und zurück funktioniert in beide Richtungen.

## Vertrag

- **Tabellen / DDL:** `defect_case` (Herkunftsreferenzen: Prüflauf, Prüfschritt, Fahrzeug, Schicht bzw. Incident; Spaltennamen Arbeitsnamen, z. B. `origin_type`, `origin_inspection_id`, `origin_step_ref`, `origin_incident_ref`), `inspection_answer` (Verweis auf `defect_case`), `defect_evidence`. Eindeutigkeit der Herkunft: höchstens eine Akte je (Mandant, Prüflauf, Prüfschritt), damit Wiederholung keine zweite Akte erzeugt. Alle Tabellen mit `tenant_id` und RLS wie V1. Die Incident-Referenz hat heute keine Tabelle (OP-02; kernschema kennt nur `business_alert`); bis zur Entscheidung wird die Referenz als Textreferenz geführt.
- **API:** `POST /v1/defects` mit Herkunftsangabe (Prüfschritt oder Störung); `GET /v1/inspections/{id}` liefert die Gegenreferenz zur Akte, `GET /v1/defects/{id}` die Referenz zum Prüfschritt bzw. Incident. Wiederholtes Auslösen für denselben Prüfschritt liefert dieselbe Akte (Idempotenz). Akte zu fremdem Prüflauf bzw. Gegenreferenz-Abruf ohne Fahrzeugbezug: 404; Pflichtfeld-Fehler 422.
- **Zustandsautomat:** keine eigene; Akte startet in `neu` (DMG-007).
- **JSON-Schema / Ereignisse:** Ereignis „Schaden gemeldet" (wie DMG-002) mit Herkunftsangabe.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 „Nein – Mangel melden" öffnet die Erfassung mit vorbelegten Referenzen (Foto mit sichtbarem Aufnahmedatum); UI-11 Sprung Prüfung ↔ Schadenakte; Sprung Incident → Schadenakte in UI-07. Zustände: Foto „in Prüfung" bis Medienprüfung bestanden, Fehler, fehlende Rechte.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-02 (Incident ohne Tabelle; Abfrage von Incident aus erreichbar bleibt bis dahin Referenz ohne Fremdschlüssel); Herkunftsmodell und Kategorienkatalog als Detailverträge vorab; Quelle des Aufnahmedatums (Gerätezeit als nicht vertrauenswürdig plus Serverempfangszeit, OP-31). Foto-Upload hängt an OP-13 (WP-M4-21); blockiert diese Verknüpfung nicht.

## Tests

- **Positiv:** negativer Prüfschritt mit zwei Fotos erzeugt Akte mit Fallnummer; von der Prüfung zur Akte und von der Akte zurück zum Prüfschritt navigierbar; aus Störung erzeugte Akte trägt Incident-Referenz und ist vom Incident erreichbar.
- **Negativ:** Abweichungserfassung ist an jedem negativ beantworteten Prüfschritt verfügbar, nicht nur an kritischen (WP-M3-20 Krit. 6); Akte zu fremdem Prüflauf abgewiesen; Foto ohne Aufnahmedatum abgewiesen; Gegenreferenz-Abruf durch Fahrer ohne Bezug zum Fahrzeug 404; Foto ohne bestandene Medienprüfung nicht sichtbar.
- **Konkurrierend:** parallele Auslösung für denselben Prüfschritt (zwei Transaktionen) trifft die Eindeutigkeit (Mandant, Prüflauf, Prüfschritt) und ergibt eine Akte; doppelte Übermittlung nach Timeout ergibt genau eine Akte.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectFromInspectionTest.kt::schadenakte_aus_pruefschritt_beidseitig_verknuepft; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectFromInspectionTest.kt::doppelte_uebermittlung_erzeugt_eine_akte; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectFromIncidentTest.kt::akte_aus_stoerung_traegt_incident_referenz; geplant:backend/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectFromInspectionTest.kt::fremder_pruflauf_und_fahrer_ohne_bezug_404; geplant:db/tests/test_defect_case.py::test_herkunft_eindeutig_je_pruefschritt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
