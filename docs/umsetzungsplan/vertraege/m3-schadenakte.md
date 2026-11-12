# Detailvertrag Schadenakte (WP-M3-43, -44, -47, -48)

Ergänzt [m3-detailvertraege.md](m3-detailvertraege.md) und die Verträge `LH-6.22-DMG-001/-002/-004/-007/-008`. Alle Festlegungen, die das Pflichtenheft nicht trifft, sind **Annahmen dieses Plans**
(Vertragsprozess: [vertragsprozess.md](../vertragsprozess.md)); kein menschlicher Review ist erfolgt (D-12). Verantwortlicher: Rolle Flotte/Sicherheit, Modul `fleet-safety`.

## 1. Fallnummer (DMG-001/-002, OP-22 unberührt)

- Format `SCH-<Jahr>-<laufende Nummer, mindestens sechsstellig>`, z. B. `SCH-2026-000123` (passt zum Ereignisschema `defect.reported`).
- Vergabe je Mandant und Kalenderjahr (UTC) aus `defect_case_counter` durch `taktovia.next_defect_case_number()` **in der Transaktion der Anlage**: Zeilensperre des Zählers serialisiert
  parallele Meldungen desselben Mandanten, ein Rollback nimmt die Vergabe zurück. Ergebnis: je Mandant und Jahr **lückenlos und doppelfrei** (beantwortet die Frage „Dublettenfreiheit oder
  Lückenfreiheit“ mit dem strengeren Fall; die Gegenprobe sind `db/tests/test_defect_case.py::test_parallele_vergabe_lueckenlos_und_eindeutig` und `::test_rollback_hinterlaesst_keine_luecke`).
  Der Zähler steigt per Trigger nur um 1; `UNIQUE (tenant_id, case_number)` bleibt die zweite Schicht.
- Die Nummer enthält keine Personendaten. Gleichlautende Nummern verschiedener Mandanten verraten den Mandanten nicht: der Zugriff läuft über die mandantengebundene Akten-ID (fremd: 404).

## 2. Kataloge (Planungsannahmen, nicht freigegeben; Erweiterung additiv)

| Feld | Werte |
|---|---|
| `category_code` (Kategorie) | `BODY`, `GLASS`, `TIRES_WHEELS`, `LIGHTS`, `BRAKES`, `DRIVETRAIN`, `STEERING_SUSPENSION`, `ELECTRICAL_HV`, `INTERIOR`, `ACCESSIBILITY_EQUIPMENT`, `SAFETY_EQUIPMENT`, `CLEANLINESS`, `OTHER` |
| `kind` (Art) | `SCRATCH`, `DENT`, `CRACK_BREAK`, `MALFUNCTION`, `MISSING_PART`, `LEAK`, `WEAR`, `CONTAMINATION`, `OTHER` |
| `vehicle_area` (Fahrzeugbereich) | `FRONT`, `REAR`, `LEFT_SIDE`, `RIGHT_SIDE`, `ROOF`, `UNDERBODY`, `WHEELS_TIRES`, `INTERIOR_PASSENGER`, `INTERIOR_DRIVER`, `LUGGAGE`, `WHEELCHAIR_AREA`, `ENGINE_BATTERY`, `OTHER` |
| `observed_effect` (beobachtete Auswirkung) | `NONE_VISIBLE`, `COSMETIC_ONLY`, `FUNCTION_LIMITED`, `NOT_ROADWORTHY`, `FLUID_LEAK`, `WARNING_LIGHT`, `NOISE_VIBRATION`, `OTHER` |
| `location_kind` (Ereignisort, OP-22) | `COORDINATE`, `ADDRESS_TEXT`, `FREE_TEXT`, `DEPOT` mit `location_value` (höchstens 200 Zeichen); beide oder keines. Der Ort ist keine Pflichtangabe der Meldung (das WP nennt als Pflichtdaten Fahrzeug, Kategorie, Beschreibung, Ereigniszeit). |
| Freitext | `description` 1 bis 2000 Zeichen; keine Unfall-/Gesundheitsangaben (OP-23). Alle Auswahlfelder sind Codes. |

## 3. Meldung (WP-M3-43)

- `POST /v1/defects`: Pflicht `vehicle_id`, `occurred_at` (mit UTC-Offset, nicht später als jetzt + 60 s: Toleranz der Gerätezeit; Datenbanktrigger spiegelt die Regel), `category_code`, `description`.
  Mandant, Meldender, Meldezeitpunkt, Fallnummer und Status `NEW` setzt der Server; solche Felder im Body sind unbekannte Felder (422 `UNKNOWN_FIELD`), ein **fremder** `tenant_id` ist 404 mit Sicherheitsereignis (Plattformregel).
- Idempotenz: `Idempotency-Key` (Filter) und `device_event_id` (Modul, `UNIQUE (tenant_id, device_event_id)`): Wiederholung liefert dieselbe Akte (200), auch bei paralleler Wiederholung; dieselbe ID von anderem Meldenden/Fahrzeug: 409.
- Berechtigung: Rollen `driver`, `dispatcher`, `workshop` (Rechtematrix; zweite Schicht im Modul). **Fahrerbezug** (Kriterium 7, Annahme, OP-34): ein Konto, das nur als Fahrer auftritt, meldet für ein Fahrzeug, für das es eine
  Schicht hat, die läuft, in höchstens 2 h beginnt oder vor höchstens 24 h endete (`staff_shift.driver_ref` = IdP-Subject, nur lesend); sonst 404. Die Meldung verlangt weder Buchung noch Prüflauf. Ein Fahrer sieht nur eigene Akten (DMG-011).
- Ereignis `defect.reported` (Schema `api/events/defect.reported.v1.schema.json`), Audit `fleet.defect.reported` ohne Beschreibungstext.
