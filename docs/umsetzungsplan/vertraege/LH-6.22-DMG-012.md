# LH-6.22-DMG-012 – Bekannte Altschäden anzeigen und quittieren

- **Verantwortlicher:** Rolle Fleet-Safety (Modul `fleet-safety`), Mobile Driver App
- **Arbeitspaket:** WP-M3-21
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein neuer Fahrer sieht die für die Nutzung relevanten offenen Altschäden des gewählten Fahrzeugs vor seiner Vor-Schicht-Prüfung.

**Originalabnahme (unverändert):** Fahrer kann vorhandenen Schaden quittieren, ohne den Vorfall als selbst verursacht zu markieren.

## Vertrag

- **Tabellen / DDL:** `defect_case` (lesend); Quittierung als Eintrag am `inspection_run` mit Verweis auf `defect_case` (Tabelle neu, Arbeitsname in WP-M3-01 festzulegen; `inspection_run` laut Kap. 04 §4.4 Teil der CHK-Tabellen; Mandantenschlüssel, zusammengesetzter FK, RLS `tenant_isolation`). Der Quittierungseintrag speichert Prüflauf, Schadenakte, Zeitpunkt, quittierenden Fahrer und den Stand der Schadenakte zum Quittierungszeitpunkt (Revision), aber keine Verursacher-/Schuldangabe (Kap. 04: keine automatische Schuldzuweisung); Eindeutigkeit je (Prüflauf, Schadenakte) für Idempotenz.
- **API:** Altschäden zum Prüflauf und Quittierung unterhalb `/v1/inspections/{id}` (Pfade im Detailvertrag, noch festzulegen, z. B. `GET …/known-defects`, `POST …/known-defects/{defect_id}/acknowledge`, idempotent per Idempotency-Key). Antwort ist eine minimierte Fahrerprojektion (Bereich, Art, Foto, Fallnummer; keine vertraulichen Dokumente, vgl. LH-6.22-DMG-011). Die Anfrage kennt kein Verursacherfeld; ein mitgesendetes Feld wird nicht übernommen. Statuscodes: 200/201; 404 für Fahrzeug ohne Zuweisung/fremden Mandanten (Kap. 05: Fahrer sehen nur zugeordnete Aufgaben); wiederholte Quittierung mit demselben Schlüssel liefert dasselbe Ergebnis, mit abweichendem Inhalt 409 `IDEMPOTENCY_CONFLICT`. Die Quittierung legt keine neue Schadenakte an. Pfade und Schema der Projektion in `api/driver.yaml` zu ergänzen.
- **Zustandsautomat:** keiner (Prüflauf-Zustände siehe CHK-Verträge; Prüfabschluss erst nach Quittierung der offenen Altschäden, WP-M3-21 Kriterium 1).
- **JSON-Schema / Ereignisse:** keine eigenen; Projektionsfelder im OpenAPI-Schema `api/driver.yaml`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 Driver Dienststart: „Vorhandene Schäden ansehen“ vor „Pflichtprüfung starten“; Quittierung „gesehen/bestehend“ ohne Verursacherangabe; Leerzustand (keine offenen Altschäden, ausdrückliche Anzeige, keine Quittierung verlangt); bei neuer Schadenerfassung zu einem Fahrzeug mit offenen Fällen werden diese vorher angezeigt, Neuanlage bleibt mit Bestätigung möglich (UI-10). Ein neuer Fahrer sieht die Altschäden nur des gewählten Fahrzeugs.
- **Entscheidungen:** keine.
- **Offene Punkte:** Regel „nutzungsrelevant“ und genaue Feldliste der Fahrerprojektion sind im Pflichtenheft nicht festgelegt (Detailvertrag vorab laut WP); Verhalten bei „als verändert gemeldet“ (Nachtrag über WP-M3-48); Ähnlichkeitsvorschlag/Dublettenerkennung kommt mit WP-M7c-39 (OP-12). Blockiert den Bau nicht; Arbeitsannahme bis zur Klärung: alle offenen (nicht abgeschlossenen) Fälle des Fahrzeugs.

## Tests

- **Positiv:** AT-29: neuer Fahrer sieht offene Altschäden des gewählten Fahrzeugs vor der Prüfung, quittiert; alter Fall bleibt referenziert, keine neue Schadenakte, keine Verursacherangabe gespeichert; Fahrzeug ohne offene Altschäden zeigt Leerzustand ohne Quittierungspflicht; Schadenerfassung an Fahrzeug mit offenen Fällen zeigt diese zuerst.
- **Negativ:** Prüfabschluss ohne Quittierung ist nicht möglich; Zugriff auf Altschäden eines nicht zugewiesenen Fahrzeugs bzw. fremden Mandanten → 404; Projektion enthält keine vertraulichen Dokumente und nur Altschäden des gewählten Fahrzeugs; Neuanlage eines identischen Schadens zeigt zuerst den bestehenden Fall; Quittierungsanfrage mit Verursacherfeld wird nicht übernommen.
- **Konkurrierend:** Doppelte Quittierung desselben Altschadens von zwei Geräten → genau ein Quittierungseintrag; Altschaden wird parallel zur Quittierung abgeschlossen – Prüfabschluss bleibt möglich und referenziert den Stand zum Quittierungszeitpunkt.
- **Referenztests:** AT-29
- Testfälle: AT-29; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::altschaeden_vor_abschluss_angezeigt_und_quittierbar; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::quittierung_speichert_keine_verursacherangabe; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::fahrer_sieht_nur_altschaeden_des_zugewiesenen_fahrzeugs_sonst_404; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::projektion_ohne_vertrauliche_dokumente_und_ohne_freitext; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::leerzustand_ohne_quittierungspflicht; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::neuanlage_bei_offenen_faellen_zeigt_bestehenden_fall_vorher; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::doppelte_quittierung_zwei_geraete_genau_ein_eintrag; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::altschaden_parallel_abgeschlossen_pruefabschluss_referenziert_stand_der_quittierung; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/KnownDamageAcknowledgementTest.kt::abschluss_ohne_quittierung_wird_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/fleet/KnownDefectApiTest.kt::altschaeden_anzeigen_quittieren_idempotent_ohne_verursacher; tests/acceptance/test_bekannter_schaden_m3.py::test_pruefabschluss_ohne_quittierung_abgewiesen; db/tests/test_damage_ack.py::test_quittierung_ohne_verursacher_oder_schuldspalte (Vertragskorrektur WP-M3-21: die zwei geplanten Klassen KnownDefectAckTest/KnownDamageAcknowledgementTest sind zu einer Testklasse zusammengelegt)

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
