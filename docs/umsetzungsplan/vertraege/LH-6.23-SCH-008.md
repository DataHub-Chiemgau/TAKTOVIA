# LH-6.23-SCH-008 – Offene Schichten und unbesetzter Bedarf

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-05
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Offene Schichten und unbesetzter Bedarf werden sichtbar ausgewiesen, ohne einen Mitarbeiter oder Fahrzeug bereits verbindlich zuzuordnen.

**Originalabnahme (unverändert):** Unbesetzte Schicht wird im Dashboard hervorgehoben und gezählt.

## Vertrag

- **Tabellen / DDL:** `staff_shift` mit nullbarem `driver_ref` und `vehicle_id` (V1 hat beide `NOT NULL`; Änderung in der Migration aus WP-M4-03, OP-8); Index `(tenant_id,state,updated_at)`. Personalbedarf je Schicht (Anzahl, Funktion) aus WP-M4-03. RLS über `tenant_id`.
- **Definition (Vorschlag dieses Plans, in WP-M4-01 zu bestätigen):** „offen“ = Schicht ohne zugeordnete Person; eine offene Schicht enthält weder Mitarbeiter- noch verbindliche Fahrzeugzuordnung. „Unbesetzter Bedarf“ = Personalbedarf minus zugeordnete Personen, je Tag, Depot und Funktion.
- **API:** `/v1/rosters` Filter „offen“ und Zähler unbesetzter Schichten im Dienstplan-Lesemodell (Feldname im Detailvertrag WP-M4-01).
- **Zustandsautomat:** Zustand „offen“ der Schicht (`vertraege/zustaende/dienstplan.md`).
- **JSON-Schema / Ereignisse:** keine eigenen.
- **Parameter und Kennzahlen:** KPI `Schicht-Abdeckungsquote` (besetzte veröffentlichte Schichtstunden / geplante Schichtstunden; kataloge.csv LH-K.4-Zeile-41), hier nur Datenbasis (Berechnung der Kennzahl selbst nicht Teil dieses Vertrags).
- **Oberfläche:** UI-12 Planer-Dashboard: unbesetzte Schicht hervorgehoben und gezählt (Darstellung in WP-M4-18); leer: Zähler 0.
- **Entscheidungen:** keine.
- **Offene Punkte:** Anpassung des V1-DDL (OP-8); Angebot/Bewerbung/Tausch (SCH-021 bis 024) sind P2 und nicht Teil.

## Tests

- **Positiv:** Schicht ohne Person erscheint im Lesemodell als offen und geht in den Zähler ein; nach Zuweisung einer Person sinkt der Zähler um genau eins; Dashboard-Daten liefern Hervorhebung und Zähler.
- **Negativ:** offene Schicht enthält weder `driver_ref` noch verbindliche `vehicle_id`; eine offene Schicht lässt sich nicht als besetzt melden; Filter „offen“ liefert keine Schichten anderer Mandanten; Entwurfsschicht ohne Person wird nicht als verbindlich besetzt oder verbindlich offen an Fahrer-App/operative Sicht übertragen (SCH-005).
- **Konkurrierend:** zwei parallele Zuweisungen derselben offenen Schicht führen zu genau einer Besetzung (die zweite wird abgewiesen), Zähler sinkt nur um eins.
- **Referenztests:** keine Katalog-ID im WP-Dokument genannt.
- Testfälle: geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/OpenShiftTest.kt::offene_schicht_wird_gezaehlt_und_zaehler_sinkt_nach_zuweisung; geplant:backend/staff-time/src/test/kotlin/de/taktovia/stafftime/OpenShiftTest.kt::parallele_zuweisung_ergibt_genau_eine_besetzung; geplant:db/tests/test_staff_shift_offen.py::test_offene_schicht_ohne_driver_ref_und_vehicle_id_zulaessig

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
