# LH-6.23-SCH-001 – UI-12: Planer-Dashboard Tag, Woche, Monat

- **Verantwortlicher:** Rolle Personalplanung (Modul `staff-time`, Oberfläche `web/operator`)
- **Arbeitspaket:** WP-M4-18
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ein webbasiertes Planer-Dashboard bietet Tages-, Wochen- und Monatsansichten mit Mitarbeitern als Zeilen und Zeiten bzw. Schichten als Einträgen.

**Originalabnahme (unverändert):** Planer kann verfügbare Einsätze eines Depots für zwei aufeinanderfolgende Wochen sehen.

## Vertrag

- **Tabellen / DDL:** keine eigenen; Lesemodell über `staff_shift` (V1) und `roster_revision` (WP-M4-04); RLS und Depotberechtigung (WP-M4-16) bleiben maßgeblich.
- **API:** `GET /v1/rosters` (Lesemodell je Depot und Zeitraum, Paging und Zeitraumgrenzen; Katalogpfad Kap. 05 §5.7, Unterpfad Vorschlag des Plans; in `api/rosters.yaml` bisher nur `/v1/rosters/{roster_id}/publish`). Der Entwurf ist nur mit Planerrecht lesbar, sonst 403/404.
- **Zustandsautomat / Schema / Ereignisse:** Zustände Entwurf/veröffentlicht aus WP-M4-04; Lesemodell-Schema ist ein Detailvertrag-Ergebnis von WP-M4-18.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-12 Dienstplan (Kap. 06): Raster Tag/Woche/Monat, Zeilen je Mitarbeiter, Fahrzeug je Schicht, Zeile(n) für offene Dienste mit Zähler, Konflikt-/Hinweismarken (Ruhezeit, Doppelbelegung, unbesetzter Dienst), Abwesenheit als „nicht verfügbar“, Entwurf und veröffentlichte Revision prominent getrennt, Datenstand; barrierefreie Tabellenalternative. Zustände leer (kein Dienstplan im Zeitraum), Fehler (Ladefehler mit Wiederholen), keine Rechte; Mandant, Depot, Revisionsstatus sind sichtbar; Status nie nur Farbe.
- **Entscheidungen:** keine.
- **Offene Punkte:** Wireframe UI-12 mit Zustandskatalog und Lesemodell-Schema entstehen in WP-M4-01; sie blockieren den Bau nicht, sind aber vor UI-Codebeginn zu liefern. Abhängigkeit von WP-M4-05/07/16.

## Tests

- **Positiv:** E2E „zwei Wochen eines Depots“: Planer sieht die verfügbaren Einsätze eines Depots für zwei aufeinanderfolgende Wochen; Tages-, Wochen- und Monatsansicht zeigen Mitarbeiter als Zeilen und Schichten als Einträge; offene Schichten hervorgehoben und gezählt (Status nie nur Farbe); Tabellenalternative per Tastatur und Screenreader nutzbar; Mandant, Depot, Revisionsstatus und Datenstand sichtbar.
- **Negativ:** fremdes Depot ist nicht auswählbar (API 404); Planer ohne Recht sieht keinen Entwurf (403/404); Zustände leer, Fehler und keine Rechte sind eigene getestete Zustände; axe-Prüfung als CI-Schritt.
- **Konkurrierend:** nicht gefordert (reine Lesesicht); Neuladen während parallel veröffentlichter Revision zeigt konsistent genau eine Revision mit Datenstand.
- **Referenztests:** Das WP-Dokument nennt für diese Anforderung keine Katalog-ID (AT-37 betrifft Entwurf/Publikation und ist an WP-M4-04/-14/-19 gebunden).
- Testfälle: web/operator/src/dienstplan/DienstplanRaster.test.tsx::zeigt_zwei_wochen_eines_depots_mit_mitarbeitern_als_zeilen; web/operator/src/dienstplan/DienstplanRaster.test.tsx::tages_wochen_monatsansicht_zeigen_schichten_als_eintraege; web/operator/src/dienstplan/DienstplanRaster.test.tsx::offene_schichten_hervorgehoben_und_gezaehlt; web/operator/src/dienstplan/DienstplanRaster.test.tsx::tabellenalternative_per_tastatur_nutzbar; web/operator/src/dienstplan/DienstplanRaster.test.tsx::zustaende_leer_fehler_keine_rechte; web/operator/src/dienstplan/DienstplanRaster.test.tsx::fremdes_depot_nicht_auswaehlbar; tests/acceptance/dienstplan/test_zwei_wochen_depot.py::test_planer_sieht_zwei_wochen_eines_depots

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
