# LH-6.22-DMG-011 – Vertraulichkeit der Schadenakte

- **Verantwortlicher:** Rolle Fleet-Safety (Modul `fleet-safety`), Datenschutz/Sicherheit
- **Arbeitspaket:** WP-M3-50
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Schadenereignisse werden von personenbezogenen Unfall-/Gesundheitsangaben logisch getrennt; sensible Inhalte sind nur berechtigten Rollen zugänglich.

**Originalabnahme (unverändert):** Fahrer anderer Fahrzeuge können keine vertraulichen Dokumente des Falls lesen.

## Vertrag

- **Tabellen / DDL:** Vertraulichkeitskennzeichen/-stufe an `defect_evidence` bzw. Revision der Schadenakte (additiv, expand/contract; `defect_evidence` nach Kap. 04 §4.4 Teil der DMG-Tabellen); `defect_case` enthält keine Felder für Gesundheitsangaben. Personenbezogene Unfallangaben liegen getrennt (in M3 abgegrenzter, standardmäßig leerer Bereich mit eigenem Zugriffsrecht; Tabellenname im Pflichtenheft nicht festgelegt, OP-23). Eigener Modulzugang bzw. Spalten-/Viewrechte nach Kap. 04 §4.6 (`db/role_matrix.csv`, `db/table_owners.csv`); `defect_evidence` unveränderlich (Trigger `reject_mutation()`); RLS `tenant_isolation`.
- **API:** `GET /v1/defects/{id}` mit Projektion je Rolle (Fahrer des Fahrzeugs, anderer Fahrer, Leitstelle, Werkstatt, Prüfer; Rollenmatrix ist Detailvertrag vorab laut WP); Dokument-/Medien-Download nur über kurzlebige signierte Links nach Prüfung von Rolle, Objekt (`canRead`) und Mandant. Nicht berechtigt: 404 (Kap. 05: nicht sichtbare Ressourcen konsistent 404, auch bei bekannter Dokument-ID), fehlender Funktionsscope 403; abgelaufener oder weitergegebener Link: abgewiesen (Statuscode im Pflichtenheft nicht festgelegt, Arbeitsannahme 403/410). Gilt für Detail-, Listen-, Such- und Medienendpunkte (Medienendpunkt: Uploadvertrag offen, OP-13). Fahrer sehen ausschließlich ihnen zugeordnete Aufgaben (Kap. 05 Minimierung).
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Sicherheitsereignis bei abgewiesenem Zugriff (ohne Fallinhalt) und Audit-Eintrag je Zugriff auf vertrauliche Dokumente (`audit_event`: `actor_ref`, `action`, `object_ref`, `correlation_id`, `redacted_changes`, WP-M1-12); Schema der Sicherheitsereignisse unter `api/events/` zu ergänzen.
- **Parameter und Kennzahlen:** keine (Gültigkeitsdauer der signierten Links ist ein zu benennender Konfigurationswert; im Pflichtenheft nicht festgelegt).
- **Oberfläche:** UI-10/UI-11 (Kap. 06 §6.9): vertrauliche Teile sind für Unberechtigte nicht vorhanden statt ausgegraut; berechtigte Rolle sieht sie.
- **Entscheidungen:** D-11 (Datenrechte, Supportzugriff) – blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-23 (Datenmodell der getrennten Unfallangaben; fachlicher Umfang zu bestätigen), Rollenmatrix der Schadenakte und Dauer der Downloadlinks sind festzulegen; OP-13 (Evidenz-Upload/Medienendpunkt). Unfallmeldeprozess mit Personenschaden ist nicht im P1-Umfang. Blockiert den Bau nicht (M3 baut nur Trennung und Zugriffsschutz).

## Tests

- **Positiv:** Berechtigte Rolle liest vertrauliches Dokument über signierten Link; Zugriff wird protokolliert (`audit_event`); Fahrer des Fahrzeugs sieht nur seine Projektion.
- **Negativ:** Fahrer eines anderen Fahrzeugs (Abnahme), anderer Schicht, anderer Mandant: weder lesen noch herunterladen, auch mit bekannter Dokument-ID; Listen-/Such-/Medienendpunkt mit fremder ID; abgelaufener oder weitergegebener Link; `defect_case` enthält keine Gesundheitsfelder (Schematest); abgewiesener Zugriff erzeugt Sicherheitsereignis ohne Fallinhalt; Datenbankrolle ohne Recht kann getrennten Bereich nicht lesen; vertrauliche Teile fehlen in der UI für Unberechtigte (nicht ausgegraut).
- **Konkurrierend:** Rechteentzug während eines offenen Downloadlinks: Download nach Commit des Entzugs abgewiesen; zwei Mandanten parallel über wiederverwendete Poolverbindungen sehen keine fremden Dokumente.
- **Referenztests:** Das WP nennt keine Katalog-ID für diese Anforderung; Testfälle aus dem WP: Positiv, Negativ je Zugriffsweg, Konkurrenz Rechteentzug.
- Testfälle: geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::fahrer_anderes_fahrzeug_kann_vertrauliches_dokument_nicht_lesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::fremde_id_in_liste_suche_medien_404; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::abgelaufener_oder_weitergegebener_link_abgewiesen; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::abgewiesener_zugriff_sicherheitsereignis_ohne_fallinhalt; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::berechtigter_zugriff_wird_auditiert; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::rechteentzug_waehrend_offenem_downloadlink; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/DefectConfidentialityTest.kt::mandanten_parallel_poolverbindung_isoliert; geplant:db/tests/test_defect_vertraulichkeit.py::test_defect_case_ohne_gesundheitsfelder_und_getrennter_bereich; geplant:db/tests/test_defect_vertraulichkeit.py::test_runtime_rolle_ohne_recht_liest_getrennten_bereich_nicht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
