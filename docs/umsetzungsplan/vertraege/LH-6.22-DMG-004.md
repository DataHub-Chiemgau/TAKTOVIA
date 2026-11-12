# LH-6.22-DMG-004 – Mobile Schadenerfassung mit Fotoevidenz

- **Verantwortlicher:** Rolle Fleet/Safety (Modul `fleet-safety`) mit Driver-App
- **Arbeitspaket:** WP-M3-44
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schadensmeldungen enthalten Art, Fahrzeugbereich, beobachtete Auswirkung und Fotos; freie Felder sind auf notwendige Angaben begrenzt.

**Originalabnahme (unverändert):** Vollständiger Musterfall lässt sich im Fahrergerät in wenigen Schritten erfassen.

## Vertrag

- **Tabellen / DDL:** `defect_evidence` (Objektschlüssel, `sha256`, MIME, Aufnahmedatum, Urheber, `tenant_id`, Bezug zu `defect_case`; unveränderlich, Trigger gegen UPDATE/DELETE gemäß DMG-008). `defect_case` erhält Auswahlfelder Art, Fahrzeugbereich, Auswirkung (Spaltennamen Arbeitsnamen: `kind`, `vehicle_area`, `observed_effect`) als Pflichtangaben aus Katalogen, dazu begrenzten Freitext. Objektspeicher unter Mandantenpräfix (WP-M1-11). Prüfstatus je Foto: `in Prüfung` bis Medienprüfung bestanden; erst danach sichtbar und herunterladbar.
- **API:** Evidenz-Upload unterhalb `/v1/defects/{id}` (Pfad mit Uploadticket: Größe, MIME, Hash; Quarantäne; Pfad im Detailvertrag festzulegen, OP-13). Signierter Download erst nach Objekt- und Zweckprüfung, Gültigkeit höchstens 60 s (Kap. 02 §4). Abweisung bei abweichendem Hash oder unzulässigem MIME (nicht an die Akte gehängt), 422 mit Feldverweis bei fehlender Pflichtangabe (Art, Fahrzeugbereich, Auswirkung, mindestens ein Foto), 404/403 bei fremdem Mandanten oder fehlender Berechtigung, abgelaufener oder mandantenfremder Link ungültig.
- **Zustandsautomat:** Medienprüfung (`hochgeladen` → `in Prüfung` → `freigegeben` | `abgewiesen`) als Arbeitsnamen, mit dem Uploadbaustein aus WP-M4-21 abzustimmen; Akte selbst siehe DMG-007.
- **JSON-Schema / Ereignisse:** Ereignis „Schaden gemeldet" mit Evidenzreferenzen; Integritätsprüfung Objekt ↔ gespeicherter Hash (periodischer Worker) meldet Abweichungen.
- **Parameter und Kennzahlen:** keine; Obergrenze der Schritte, Freitextlänge, Dateigröße und erlaubte MIME-Typen sind festzulegen (Detailvertrag vorab).
- **Oberfläche:** UI-10 Driver Fahrt/Schaden: geführte Erfassung mit Auswahlfeldern (Art, Fahrzeugbereich, Auswirkung), Kamera, begrenztem Freitext, lokaler Entwurf. Höchstzahl der Schritte für den Musterfall (Art, Bereich, Auswirkung, ein Foto) ist im Detailvertrag vorab festzulegen; Vorschlag: höchstens 5 Schritte (Wert nicht aus dem Pflichtenheft). Anzeigen „Entwurf", „synchronisiert", „angenommen" getrennt (Kap. 06 §6.7); Screenreader- und farbunabhängig bedienbar.
- **Entscheidungen:** D-11 (Aufbewahrung der Schadensbilder), blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-13 (Uploadvertrag, Größen-/Formatgrenzen, Zuständigkeit für Malware-Scan; echte Bauabhängigkeit auf WP-M4-21; ohne dieses ist der Teil Medienprüfung von AT-33 nicht abnehmbar, die Erfassung selbst aber baubar); OP-31 (Metadaten/EXIF, Aufnahmedatum); Kataloge Art/Fahrzeugbereich/Auswirkung nicht im Pflichtenheft festgelegt; OP-12 (Dublettenhinweis nicht im Umfang). Kein Bau-Blocker für den Vertrag.

## Tests

- **Positiv:** AT-33: Musterfall mit Art, Bereich, Auswirkung und einem Foto in höchstens der festgelegten Schrittzahl erfasst, Fallnummer vergeben, Foto nach Medienprüfung sichtbar.
- **Negativ:** falscher Hash, unzulässiger MIME, übergroße oder ausführbare Datei abgewiesen; fehlende Pflichtangabe (Art, Bereich, Auswirkung, Foto) 422 mit Feldverweis; Freitext über Grenze abgewiesen; Download ohne Berechtigung, mit Link eines anderen Mandanten oder abgelaufenem Link scheitert; nachträglich im Objektspeicher veränderte Datei wird von der Integritätsprüfung erkannt (AT-36, Teil Medienintegrität).
- **Barrierefreiheit/Anzeige:** Erfassung per Screenreader und ohne Farbunterscheidung bedienbar; Entwurf, „synchronisiert“ und „angenommen“ sind getrennte Anzeigen (WP-M3-44 Krit. 8); Fotos liegen unter dem Mandantenpräfix (Objektschlüssel-Test).
- **Konkurrierend:** doppelter Upload derselben Datei nach Timeout ergibt genau einen Evidenzeintrag.
- **Referenztests:** AT-33, AT-36 (Medienintegrität).
- Testfälle: AT-33; AT-36; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::foto_wird_erst_nach_bestandener_medienpruefung_sichtbar_und_ohne_exif_unter_mandantenpraefix_gespeichert; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::falscher_hash_wird_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::unzulaessiger_mime_uebergrosse_und_ausfuehrbare_datei_werden_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::doppelter_upload_ein_evidenzeintrag; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::fremder_mandant_und_abgelaufener_link_scheitern; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::pflichtangaben_fehlen_ergibt_422_mit_feldverweis; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectEvidenceUploadTest.kt::nachtraeglich_veraendertes_objekt_wird_von_der_integritaetspruefung_erkannt; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectEvidenceApiTest.kt::musterfall_mit_foto_fallnummer_medienpruefung_und_signiertem_abruf; backend/app/src/test/kotlin/de/taktovia/app/fleet/DefectEvidenceApiTest.kt::falscher_hash_unzulaessiger_typ_schadsoftware_und_fehlende_pflichtangaben; db/tests/test_defect_evidence.py::test_zustand_nur_vorwaerts_und_sachfelder_unveraenderlich; db/tests/test_defect_evidence.py::test_evidenz_unveraenderlich_und_je_akte_hoechstens_einmal; geplant:mobile/driver/test/schaden/defect_capture_test.dart::musterfall_in_hoechstzahl_schritten; geplant:mobile/driver/test/schaden/defect_capture_test.dart::status_anzeigen_getrennt_und_bedienbar_ohne_farbe

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
