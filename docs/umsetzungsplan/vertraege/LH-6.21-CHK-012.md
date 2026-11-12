# LH-6.21-CHK-012 – Bekannte Altschäden anzeigen und quittieren

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-21
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Bereits bekannte offene Schäden werden vor Abschluss angezeigt und können vom Fahrer als gesehen/bestehend markiert werden.

**Originalabnahme (unverändert):** Bekannter Schaden wird nicht ungeprüft als neues Schadensereignis angelegt.

## Vertrag

- **Tabellen / DDL:** `defect_case` (Lesequelle), Quittierung als Eintrag am `inspection_run` mit Verweis auf `defect_case` und dem Stand der Akte zum Quittierungszeitpunkt (Tabelle bzw. Spalten im DDL von WP-M3-02; V1–V3 enthalten sie noch nicht). Invariante: UNIQUE (Mandant, Prüflauf, Schadenakte) – genau ein Quittierungseintrag je Altschaden und Prüflauf. Keine Spalte für Verursacher/Schuld/Fahrer-Bewertung (DMG-012). RLS über `tenant_id`.
- **API:** Altschäden zum Prüflauf (GET) und Quittierung (POST) unterhalb `/v1/inspections/{id}` (Pfade werden im OpenAPI-Entwurf festgelegt); Fahrerprojektion minimiert: Bereich, Art, Foto, Fallnummer, keine vertraulichen Dokumente. 404 für nicht zugewiesenes Fahrzeug/fremden Prüflauf; Abschluss (`POST /v1/inspections/{id}/submit`, WP-M3-14) wird abgewiesen, solange offene Altschäden unquittiert sind. Quittierung erzeugt keinen `defect_case`. Die Altschadenliste für den Fahrer ist eine eigene minimierte Projektion; der Fahrer erhält über `GET /v1/defects` keine Akten anderer Fahrzeuge. Der Hinweis auf offene Fälle vor der Neuanlage (Originalabnahme) nutzt dieselbe Projektion je Fahrzeug. Pfade (api/fleet-safety.yaml, WP-M3-21): `GET /v1/inspections/{inspection_id}/known-defects` (`listKnownDefects`), `POST /v1/inspections/{inspection_id}/known-defects/{defect_id}/acknowledge` (`acknowledgeKnownDefect`, 201/200, leerer Body, ohne If-Match, da unveränderlicher Eintrag), `GET /v1/vehicles/{vehicle_id}/known-defects` (`listVehicleKnownDefects`); `createDefect` weist einen Fahrer bei offenen Fällen ohne `confirm_new_despite_known` mit 409 `KNOWN_OPEN_DEFECTS` ab. Der Abschluss wird in der Datenbank (Trigger `inspection_known_defects_completion_guard`, V30210) und über `KnownDefects.requireAllAcknowledged` für `submitInspection` (WP-M3-14) abgewiesen; „Altschaden“ = offene Akte des Fahrzeugs, die beim Start des Laufs bestand und nicht aus diesem Lauf stammt (Arbeitsannahme).
- **Zustandsautomat:** Prüflauf (WP-M3-14): Abschluss setzt vollständige Quittierung voraus; Altschaden-Status ändert die Quittierung nicht.
- **JSON-Schema / Ereignisse:** keine eigenen.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 – „Vorhandene Schäden ansehen" vor „Pflichtprüfung starten"; Quittierung „gesehen/bestehend" ohne Verursacherangabe; Leerzustand „keine offenen Altschäden" ohne Quittierungspflicht. Bei Schadenerfassung an Fahrzeug mit offenen Fällen zeigt die App diese vor dem Anlegen; Neuanlage bleibt mit Bestätigung möglich.
- **Entscheidungen:** keine.
- **Offene Punkte:** Regel „nutzungsrelevant" und exakte Feldliste der Fahrerprojektion sind im Pflichtenheft nicht festgelegt (Detailvertrag vorab laut WP-M3-21; vor Codebeginn zu entscheiden); automatische Dublettenerkennung über Ähnlichkeit ausgeschlossen (OP-12, WP-M7c-39); Verhalten bei „Altschaden verändert" über Nachtrag WP-M3-48. Keine blockiert den Bau des Kerns. Die Quittierungstabelle hat in OP-32 nur den Arbeitsnamen aus WP-M3-02.

## Tests

- **Positiv:** offener Altschaden wird vor Abschluss angezeigt, Fahrer quittiert „gesehen/bestehend", Abschluss möglich; keine neue Schadenakte, alter Fall referenziert (AT-29); Leerzustand ohne Quittierungspflicht.
- **Negativ:** Abschluss ohne Quittierung abgewiesen; Zugriff auf Altschäden eines nicht zugewiesenen Fahrzeugs (404); Altschäden anderer Fahrzeuge und anderer Mandanten nicht sichtbar (RLS); Neuanlage eines identischen Schadens zeigt zuvor den bestehenden Fall (Hinweis auf offene Fälle desselben Fahrzeugs; P1-Anteil des Dublettenhinweises); Neuanlage bei offenen Fällen ohne vorherige Anzeige des Hinweises wird nicht still durchgewunken; Quittierung eines Altschadens, der nicht zum Fahrzeug des Prüflaufs gehört (404); Quittierung speichert keine Verursacherangabe.
- **Konkurrierend:** doppelte Quittierung desselben Altschadens von zwei Geräten – genau ein Quittierungseintrag; Altschaden wird parallel zur Quittierung abgeschlossen – Prüfabschluss bleibt möglich und referenziert den Stand zum Quittierungszeitpunkt.
- **Referenztests:** AT-29 (AT-36 nur P1-Anteil, siehe OP-12).
- Testfälle: AT-29; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::altschaeden_vor_abschluss_angezeigt_und_quittierbar; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::abschluss_ohne_quittierung_wird_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::leerzustand_ohne_quittierungspflicht; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::fahrer_sieht_nur_altschaeden_des_zugewiesenen_fahrzeugs_sonst_404; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::quittierung_speichert_keine_verursacherangabe; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::neuanlage_bei_offenen_faellen_zeigt_bestehenden_fall_vorher; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::doppelte_quittierung_zwei_geraete_genau_ein_eintrag; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::altschaden_parallel_abgeschlossen_pruefabschluss_referenziert_stand_der_quittierung; db/tests/test_damage_ack.py::test_quittierung_erzeugt_keine_schadenakte; db/tests/test_damage_ack.py::test_quittierung_eindeutig_je_altschaden_und_pruefung; db/tests/test_damage_ack.py::test_quittierung_ohne_verursacher_oder_schuldspalte; mobile/driver/test/altschaeden_quittierung_test.dart::vorhandene_schaeden_ansehen_vor_pflichtpruefung_start; tests/acceptance/test_bekannter_schaden_m3.py::test_pruefabschluss_ohne_quittierung_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/fleet/KnownDefectApiTest.kt::neuanlage_eines_fahrers_bei_offenen_faellen_ohne_bestaetigung_409

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
