# LH-6.22-DMG-005 – Schadenklassifikation und Triage

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-45
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schäden werden mindestens in sicherheitskritisch, einsatzrelevant, kosmetisch und sonstig klassifiziert; Einstufung ist regelbasiert und korrigierbar.

**Originalabnahme (unverändert):** Ein sicherheitskritischer Schaden sperrt betroffene Einsatzressource unmittelbar.

## Vertrag

- **Tabellen / DDL:** `defect_case` erhält Klasse (genau eine der vier Werte: sicherheitskritisch, einsatzrelevant, kosmetisch, sonstig; CHECK), Regelversion, Zuständigen; Klassifikationshistorie append-only (Akteur, Zeit, Grund, alte/neue Klasse, Regelversion). Sperre über den Sperrkern aus WP-M3-08: `vehicle.active_block`, `vehicle.safety_revision`, Widerruf `resource_release.revoked_at`, alles im selben Commit wie die Einstufung; Eintrag in `audit_event`. Mehrere Sperrgründe sind in V1 nicht abbildbar (OP-01); die Schadenssperre wird als eigener Sperrgrund mit Quelle Schaden geführt, sobald OP-01 entschieden ist.
- **API:** `POST /v1/defects/{id}/triage` mit `If-Match` (Bestätigung oder Korrektur der Klasse mit Grund und Zuständigem). Fehler: 428 ohne `If-Match`, 412 bei veralteter Revision, 403 ohne Rolle, 422 ohne Grund bei Korrektur, 404 bei fremdem Mandanten/Dienst. Die Regelklassifikation läuft bei der Anlage (Kategorie/Bereich/Auswirkung → Klasse, Regelmatrix mit Regelversion).
- **Zustandsautomat:** Klasse ist unabhängig vom Akten-Status (DMG-007); Herabstufung hebt die Sperre nicht auf, Wiederfreigabe nur über WP-M3-51.
- **JSON-Schema / Ereignisse:** Ereignis „Fahrzeug gesperrt" (Quelle Schaden); Hochstufung löst Alarm nach wirksamer `alert_policy_revision` über WP-M3-52 aus (siehe DMG-006).
- **Parameter und Kennzahlen:** `workshop_auto_block_severity` (Ebene Mandant, Validierung „Kritischer Sicherheitsschaden immer sperrend": der Wert darf sicherheitskritische Schäden nie von der Sperre ausnehmen); Datengrundlage für `Ausfallminuten kritisch`.
- **Oberfläche:** UI-11 Triageansicht: vorgeschlagene Klasse, Korrektur mit Begründung, Zuständiger; nur Rollen mit Berechtigung sehen die Korrekturaktion.
- **Entscheidungen:** keine (Freigaberollen D-11 betreffen die Wiederfreigabe).
- **Offene Punkte:** OP-10 (Enum-Werte von `workshop_auto_block_severity` und Abbildung auf die vier Klassen sowie „S0" aus PH-AT-020 fehlen; blockiert die Festlegung des Parameterschemas, nicht die Sperrlogik für sicherheitskritisch); OP-01 (Sperrgründe im Schema); Regelmatrix und Rolle für Herabstufung als Detailvertrag vorab; OP-12 (Frist/Wiedervorlage aus AT-34 nicht P1, außerhalb des Umfangs); OP-34 (Rollenabgrenzung).

## Tests

- **Positiv:** je Klasse ein Fall (Regelklassifikation mit Regelversion); sicherheitskritisch sperrt das Fahrzeug im selben Commit; Korrektur mit Grund erhält die vorherige Einstufung in der Historie; Teil von AT-34 (klassifizieren, sperren, zuweisen, Statushistorie; Statushistorie zeigt Klassifikation, Sperre und Zuweisung mit Akteur und Zeit); Hochstufung auf sicherheitskritisch erzeugt den Alarm nach wirksamer `alert_policy_revision` (WP-M3-45 Krit. 7); jede Akte trägt genau eine der vier Klassen und die Regelversion (CHECK-Test).
- **Negativ:** Triage ohne Rolle, ohne Grund, mit veralteter Revision (412) und ohne `If-Match` (428); Parameter lässt sich nicht so setzen, dass sicherheitskritische Schäden nicht sperren; Herabstufung hebt Sperre nicht auf; Akte eines anderen Mandanten 404.
- **Konkurrierend:** Hochstufung parallel zu einer Fahrtzuweisung führt zu keiner Zusage (WP-M3-45: „Hochstufung parallel zu einer Zuweisung – keine Zusage“); Hochstufung parallel zur Zuweisung des Zuständigen geht nicht verloren (412 für den zweiten Schreiber); Hochstufung parallel zu einem Dispositions-Commit erzeugt keine Zusage auf das gesperrte Fahrzeug (Kern PH-AT-020 über WP-M3-08).
- **Referenztests:** AT-34 (P1-Anteil), PH-AT-020.
- Testfälle: AT-34; PH-AT-020; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::sicherheitskritisch_sperrt_im_selben_commit; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::korrektur_ohne_grund_wird_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::hochstufung_parallel_zu_zuweisung; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::hochstufung_loest_alarm_nach_alert_policy_revision_aus; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::parameter_darf_sicherheitskritisch_nicht_ausnehmen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::herabstufung_hebt_sperre_nicht_auf; db/tests/test_defect_case.py::test_klasse_check_und_klassifikationshistorie_append_only; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectTriageApiTest.kt::triage_klassifiziert_sperrt_und_zeigt_aktenhistorie; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectTriageApiTest.kt::fehlerfaelle_428_412_403_422_404; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectTriageApiTest.kt::herabstufung_nur_werkstatt_und_hebt_sperre_nicht_auf; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::regelklassifikation_je_klasse_mit_regelversion; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectTriageTest.kt::aktenhistorie_zeigt_klassifikation_sperre_und_zuweisung_mit_akteur_und_zeit

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
