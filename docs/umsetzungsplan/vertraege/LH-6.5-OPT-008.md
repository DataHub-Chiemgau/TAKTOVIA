# LH-6.5-OPT-008 – Mehrzielbewertung mit konfigurierbaren Gewichten

- **Verantwortlicher:** Rolle Disposition (Modul `booking-dispatch`, Bewertungsfunktion)
- **Arbeitspaket:** WP-M2-20
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Mehrzieloptimierung bewertet Wartezeit, Umwege, Leerstrecken und Betriebsqualität anhand konfigurierbarer Gewichte.

**Originalabnahme (unverändert):** Änderung eines Gewichts beeinflusst nachvollziehbar die Rangfolge von Varianten.

## Vertrag

- **Tabellen / DDL:** Je Variante gespeicherte Teilwerte (Wartezeit, Umweg, Leerstrecke, Betriebsqualität) und die verwendete Gewichtsrevision (Verweis auf `config_revision`). Keine Tabelle im V1/V2-Schema vorhanden; Ablage als Diagnosedaten am Angebot (WP-M2-21) bzw. in einer neuen Migration, im Bau festzulegen. Gewichte als neue Dienstparameter im Konfigurationsregister (Schema über WP-M1-19).
- **API:** Keine eigene Schnittstelle; Wirkung als Reihenfolge der Angebote in `POST /v1/ride-offers`. Diagnoseausgabe der Teilwerte und Gewichte je Variante nur für Tests.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Konfigurationsschema der vier Gewichte (`vertraege/schemas/`, im Bau mit WP-M1-19 anzulegen).
- **Parameter und Kennzahlen:** Gewichte für Wartezeit, Umwege, Leerstrecken, Betriebsqualität: im Parameterkatalog `kataloge.csv` nicht enthalten, als neue Dienstparameter anzulegen (OP-18 des WP-Dokuments). Bewertung nach Kap. 01 §1.4 Schritt 3: erst Unzulässigkeit = 0, dann weiche Ziele, danach Leerweg/Fahrzeit/Kosten.
- **Oberfläche:** Keine Fahrgastwirkung außer der Reihenfolge der Angebote.
- **Entscheidungen:** D-05 (Produktionsgewichte; blockiert nur die Produktivschaltung).
- **Offene Punkte:** OP-18 des WP-Dokuments: Formel, Normierung, Wertebereiche der Gewichte und Messdefinition Betriebsqualität sind im Pflichtenheft nicht festgelegt. Der Bau legt sie als vorläufige, im Vertrag nachzutragende Festlegung an (Gewichte nichtnegativ, Ausgabe Teilwerte). Gleichstand wird deterministisch aufgelöst (Regel im Bau festzulegen, z. B. Variante-ID). Blockiert den Bau nicht, die Fachfreigabe der Formel steht aus.

## Tests

- **Positiv:** Referenzszenario mit zwei zulässigen Varianten: Erhöhung eines Gewichts ändert die Rangfolge in die erwartete Richtung; Teilwerte und Gewichte je Variante sind ausgegeben; gleiche Eingaben und Gewichtsrevision ergeben dieselbe Rangfolge.
- **Negativ:** Gewicht außerhalb des Wertebereichs wird vom Konfigurationsregister abgelehnt; unzulässige Variante bleibt auch bei beliebig hohem gewichtetem Vorteil ausgeschlossen (Bewertung 0).
- **Konkurrierend:** Gewichtsänderung parallel zu einer laufenden Angebotsberechnung: das Angebot nennt die zu seinem Beginn wirksame Gewichtsrevision, die Rangfolge entspricht genau dieser Revision.
- **Referenztests:** Keine Katalog-ID im WP genannt.
- Testfälle: backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/MultiObjectiveScoringTest.kt::gewichtsaenderung_aendert_rangfolge_nachvollziehbar; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/MultiObjectiveScoringTest.kt::unzulaessige_variante_bleibt_ausgeschlossen; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/MultiObjectiveScoringTest.kt::gewicht_ausserhalb_des_wertebereichs_wird_abgelehnt; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/MultiObjectiveScoringTest.kt::gleiche_eingaben_und_revision_ergeben_dieselbe_rangfolge_und_gleichstand_ist_deterministisch; backend/modules/booking-dispatch/src/test/kotlin/de/taktovia/bookingdispatch/MultiObjectiveScoringTest.kt::gewichtsaenderung_parallel_zur_berechnung_wirkt_erst_auf_die_naechste; backend/modules/config/src/test/kotlin/de/taktovia/config/ScoringWeightParametersTest.kt::grenzwerte_gueltig_ausserhalb_und_falscher_typ_abgelehnt; backend/modules/config/src/test/kotlin/de/taktovia/config/ScoringWeightParametersTest.kt::gewichte_sind_im_register_mit_bereich_0_bis_100_und_standardwerten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
