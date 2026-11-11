# LH-6.21-CHK-016 – Prüfstatus in Fahrer-App und Leitstelle

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`) mit Web/Operator und `mobile/driver`
- **Arbeitspaket:** WP-M3-23
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer-App und Operator zeigen einen eindeutigen Prüfstatus: ausstehend, in Bearbeitung, bestanden, eingeschränkt, gesperrt und technisch ungeklärt.

**Originalabnahme (unverändert):** Leitstelle sieht Statuswechsel ohne erneutes Laden im vorgesehenen Aktualisierungsintervall.

## Vertrag

- **Tabellen / DDL:** keine eigene Tabelle; Statusprojektion je (Fahrzeug, Schicht) abgeleitet aus `inspection_run`, Sperren (`vehicle.active_block`, `safety_revision`; OP-01) und `resource_release`. Ableitungstabelle Status <- Zustände als Detailvertrag vorab; die sechs Werte: ausstehend, in Bearbeitung, bestanden, eingeschränkt, gesperrt, technisch ungeklärt.
- **API:** Statusfeld in `/v1/operator/vehicles` und `/v1/driver/shifts/{id}/run`; Zustellung über den Kanal aus WP-M3-35. Fahrer eines anderen Fahrzeugs und Disponent eines nicht berechtigten Dienstes erhalten keinen Status (404). Die Statusableitung (Ableitungstabelle) ist vor Codebeginn als Detailvertrag festzulegen; `/v1/operator/vehicles` und `/v1/driver/shifts/{id}/run` sind in Kap. 05 §5.7 genannt, in `api/*.yaml` nicht vorhanden. Bei zwei Statuswechseln gilt die höhere Revision (kein Überschreiben durch ältere Nachricht).
- **Zustandsautomat:** Prüflauf/Freigabe, `vertraege/zustaende/pruefung.md` (Statuswerte als Projektion).
- **JSON-Schema / Ereignisse:** Nachrichtenhülle des Kanals mit Revision (WP-M3-35).
- **Parameter und Kennzahlen:** Aktualisierungsintervall – kein Parameter in `kataloge.csv` (OP-05).
- **Oberfläche:** UI-07 (Leitstelle) und UI-09 (Fahrer-App): Status mit Text und Symbol, nie nur Farbe; Live-Region für Änderungen; Datenstand; bei unterbrochenem Kanal Kennzeichnung „möglicherweise veraltet".
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-05 (Wert des Aktualisierungsintervalls und Technik Push/Polling nicht im Pflichtenheft festgelegt; muss vor Abnahme-Zeitmessung entschieden werden, Bau der Projektion nicht blockiert); OP-17 (Wirkung „eingeschränkt"); OP-16 (Wirkung „Nicht prüfbar" auf „technisch ungeklärt").

## Tests

- **Positiv:** Durchlauf aller sechs Zustände in beiden Oberflächen mit Zeitmessung bis zur Darstellung in der geöffneten Leitstellenansicht ohne Neuladen innerhalb des festgelegten Intervalls.
- **Negativ:** Status ist nur über Farbe nicht erkennbar (Text und Symbol je Wert in beiden Oberflächen); Kanalabbruch -> Datenstand und Veraltet-Kennzeichnung; Fahrer eines anderen Fahrzeugs erhält keinen Status; Disponent eines nicht berechtigten Dienstes erhält keinen Status; Status nie nur über Farbe erkennbar.
- **Konkurrierend:** zwei Statuswechsel kurz hintereinander (bestanden -> gesperrt) – beide Oberflächen enden auf dem letzten Serverstand, nie auf dem älteren.
- **Referenztests:** keine Katalog-ID im WP genannt.
- Testfälle: geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStatusProjectionTest.kt::sechs_statuswerte_eindeutig_aus_serverzustand; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStatusProjectionTest.kt::fahrer_anderes_fahrzeug_und_fremder_dienst_erhalten_404; geplant:backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionStatusProjectionTest.kt::zwei_wechsel_hintereinander_endet_auf_letztem_stand; geplant:web/operator/src/inspection/InspectionStatus.test.tsx::statuswechsel_ohne_neuladen_und_veraltet_kennzeichnung; geplant:web/operator/src/inspection/InspectionStatus.test.tsx::status_nie_nur_ueber_farbe_text_und_symbol; geplant:web/operator/src/inspection/InspectionStatus.test.tsx::aelteres_ereignis_ueberschreibt_hoehere_revision_nicht; geplant:mobile/driver/test/pruefstatus_anzeige_test.dart::sechs_werte_mit_text_symbol_und_datenstand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
