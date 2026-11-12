# LH-6.23-SCH-002 – Schichtmodell mit Pausenplan, Qualifikation und Personalbedarf

- **Verantwortlicher:** Rolle Personalplanung (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-03
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schichten besitzen Datum, lokalen Beginn/Ende, Pausenplan, Betrieb, Depot, Arbeitsplatz/Funktion, erforderliche Qualifikation und Personalbedarf.

**Originalabnahme (unverändert):** Schicht über Mitternacht wird mit korrektem Kalendertag und Zeitdauer gespeichert.

## Vertrag

- **Tabellen / DDL:** M4-Migration an `staff_shift` (V1: `tenant_id`, `id`, `driver_ref`, `vehicle_id`, `starts_at`, `ends_at`, `timezone`, `roster_revision`, `state`, CHECK `ends_at>starts_at`): neu Depot-/Betriebsbezug, Funktion/Arbeitsplatz, Qualifikationsanforderung (Bezug `qualification_revision`, Katalog WP-M3-05), Personalbedarf, Pausenplan, Betriebstag; `driver_ref` und `vehicle_id` nullbar (offene Schichten, OP-08). Lokaler Beginn/Ende plus IANA-Zone (`timezone`) werden zu `timestamptz` abgeleitet. Zusammengesetzte FKs `(tenant_id,…)`, RLS, Audit. Bestehende M3-Zeilen (WP-M3-07) bleiben lesbar, neue Spalten für Altzeilen befüllt oder nullbar, `resource_release.shift_id` bleibt gültig.
- **API:** `/v1/rosters/shifts` und `/v1/rosters/shifts/{shift_id}` (Schichten anlegen, lesen, ändern; ETag/`If-Match`; `roster_id` als Gruppenschlüssel in Body/Abfrage bis zum Dienstplankopf WP-M4-04; Pfad ohne `{roster_id}`-Segment, weil der Linter für Sammelpfade ohne Revision kein `If-Match` verlangt); Pflichtmerkmale Datum, Beginn/Ende, Pausenplan, Betrieb, Depot, Funktion, Qualifikation, Personalbedarf sind über die API lesbar. Fremde Depot-/Betriebs-IDs: 404; Ende vor Beginn und DST-Lücke: 422; veralteter ETag: 412.
- **Zustandsautomat:** `vertraege/zustaende/dienstplan.md` (aus WP-M4-04).
- **JSON-Schema / Ereignisse:** Schema Pausenplan unter `vertraege/schemas/pausenplan.v1.schema.json` (entsteht mit WP-M4-01).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** keine eigene (Eingabe über UI-12, WP-M4-19).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-08 (WP-M4): Kalendertag einer Mitternachtsschicht ist im Pflichtenheft nicht definiert. Vorschlag dieses Plans: lokaler Tag des Beginns (22:00–06:00 gehört zum Tag des Beginns, 8 h Dauer); bestätigt in WP-M4-01, blockiert den Bau der Tagesableitung bis dahin. Doppelte lokale Zeit verlangt eindeutigen Offset (Kap. 05 §5.1). OP-38 (Überlappungsverbot M3 gegenüber Entwurf, WP-M4-65) berührt dieses WP nicht.

## Tests

- **Positiv:** Tagschicht; Mitternachtsschicht 22:00–06:00 wird dem Kalendertag des Beginns zugeordnet und mit 8 h Dauer gespeichert; alle Pflichtmerkmale (Datum, Beginn/Ende, Pausenplan, Betrieb, Depot, Funktion, Qualifikation, Personalbedarf) sind gespeichert und per API lesbar; Anlegen/Ändern steht mit Akteur, Zeit, Objekt im `audit_event`; in M3 angelegte `staff_shift`-Zeilen bleiben lesbar, `resource_release.shift_id` bleibt gültig.
- **Negativ:** Ende vor Beginn (CHECK/422); lokale Zeit in DST-Lücke abgelehnt, doppelte Ortszeit ohne Offset abgelehnt; fremdes Depot/Betrieb ergibt 404 (auch per DB-FK nicht referenzierbar); fehlender `If-Match` ergibt 428.
- **Konkurrierend:** paralleles Ändern derselben Schicht mit altem ETag ergibt 412 ohne stilles Überschreiben; parallele Anlage durch zwei Mandanten zeigt keine Vermischung.
- **Referenztests:** Das WP-Dokument nennt für diese Anforderung keine Katalog-ID (AT-47 gehört zu WP-M4-10, AT-37 zu WP-M4-04/-14/-19).
- Testfälle: backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::mitternachtsschicht_hat_kalendertag_des_beginns_und_8h; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::pflichtmerkmale_gespeichert_und_per_api_lesbar; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::ende_vor_beginn_ergibt_422; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::dst_luecke_wird_abgelehnt; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::doppelte_ortszeit_ohne_offset_wird_abgelehnt; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::anlegen_und_aendern_stehen_im_audit_event; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::paralleles_aendern_mit_altem_etag_ergibt_412; db/tests/test_staff_shift.py::test_fremdes_depot_nicht_referenzierbar; db/tests/test_staff_shift.py::test_m3_altzeilen_bleiben_lesbar_und_resource_release_gueltig; db/tests/test_staff_shift.py::test_mitternachtsschicht_betriebstag_ist_lokaler_tag_des_beginns_und_dauer_8h; backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftModelTest.kt::fremdes_depot_und_betrieb_ergeben_404; backend/app/src/test/kotlin/de/taktovia/app/staff/ShiftApiTest.kt::mitternachtsschicht_anlegen_lesen_und_etag; backend/app/src/test/kotlin/de/taktovia/app/staff/ShiftApiTest.kt::fehler_422_404_412_428_403

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
