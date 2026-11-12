# LH-6.10-PAY-002 – Tarifengine: Festpreis, Zonenpreis, Grundpreis/Strecke

- **Verantwortlicher:** Rolle Fachmodul `fare-payment`
- **Arbeitspaket:** WP-M2-08
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Tarifengine unterstützt Festpreis, Zonenpreis und Kombination Grundpreis/Streckenanteil.

**Originalabnahme (unverändert):** Vordefinierte Testrelationen liefern die erwarteten Preise.

## Vertrag

- **Tabellen / DDL:** neue Tabelle `fare_component` (Komponente je Segment/Quote, Kap. 04 §4.4) mit `tenant_id`, RLS, Betrag `bigint` minor, Währung (`currency`, FK auf Tabelle `currency`), Bezug auf die Quote (`fare_quote`) bzw. das Segment (Spaltennamen im Bau; die Tabelle ist in V1 nicht vorhanden, Kap. 04 §4.4 nennt sie nur); Regelparameter in `tariff_rule` (Sätze als `numeric(20,8)`, siehe LH-6.10-PAY-003). Kein `float` im Codepfad (statische Prüfung).
- **API:** keine eigene; die reine Berechnungskomponente im Modul `fare-payment` (Port) wird von der Quote-Erzeugung (LH-6.10-PAY-001) genutzt. Eingang: wirksame `tariff_revision`, Tarifklasse, Personenzahl, Start-/Zielzone, tarifliche Strecke. Ausgang: disjunkte additive Komponenten (Netto, Steuer, Gebühren; Kap. 05 §5.2; Komponentencodes des bestehenden Schemas `FareComponent` in `api/federation.yaml`: `TRANSPORT`, `DISCOUNT`, `SURCHARGE`, `SUBSIDY`, `COMMISSION`, `TAX`; Abbildung „Gebühren“ im Detailvertrag, Rabatt/Zuschuss sind P2 und nicht im Umfang) in `bigint` minor mit dokumentierter Rundungsregel; Summe der Komponenten = Bruttobetrag, Steuer nicht doppelt. Regeltypen: Festpreis, Zonenpreis, Grundpreis plus Streckenanteil. Überlaufprüfung vor jeder Multiplikation (Kap. 04 §4.1).
- **Bauentscheidung (WP-M2-08, Testannahmen bis zur Freigabe des Pilottarifs, D-05/OP-12):** Engine `FareEngine` (Modul `fare-payment`, Paket `api`) als reine Funktion über die Regeln der wirksamen Revision. Einheiten: `PER_PERSON` = Personenzahl (1 bis 100), `PER_BOOKING` = 1. Preis je Einheit: Festpreis und Zonenpreis = `amount_minor` (Zonenpreis: gerichtetes Zonenpaar aus Start- und Zielzone, Rückrichtung ist ein eigenes Paar); Grundpreis/Strecke = `amount_minor + runden(rate_per_km * Meter / 1000)`, Strecke muss > 0 sein (Quelle der Strecke: Routingdistanz mit Version aus WP-M2-10, hier Eingangsparameter). Transport = Preis je Einheit * Einheiten; die Gebühr `booking_fee_minor` fällt einmal je Buchung an (`SURCHARGE`). Steuer einmal auf die Summe: `GROSS` (Beträge enthalten die Steuer): Netto = `runden(brutto / (1 + Satz))`, Steuer = brutto − Netto (`TAX`), Gebühr ebenso umgerechnet, Transport erhält den Rest; `NET`: Steuer = `runden(Summe * Satz)` obendrauf. Satz 0: keine `TAX`-Komponente. Rundung überall kaufmännisch (`HALF_UP`) auf ganze kleinste Einheiten, je Rechenschritt einmal. Überlaufprüfung vor jeder Multiplikation und nach jeder Addition gegen 2^53 − 1. Komponenten: `TRANSPORT`, `SURCHARGE`, `TAX` (Abbildung „Gebühren“ = `SURCHARGE`); `DISCOUNT`, `SUBSIDY`, `COMMISSION` sind P2. Testrelationen: `tests/fixtures/tarif_testrelationen.csv` (14 Relationen, von Hand und unabhängig von der Engine gerechnet; **vom Auftraggeber zu bestätigen**). `Tariffs.price(...)` verbindet Preisstichtag (WP-M2-07) und Engine: ohne wirksame Revision `NoTariff`. Tabelle `fare_component` (Migration V2080, unveränderlich); das Schreiben der Komponenten beim Erzeugen des `fare_quote` liefert WP-M2-09.
- **Zustandsautomat:** keiner (zustandslos).
- **JSON-Schema / Ereignisse:** Tabelle der Testrelationen als Fixture `tests/fixtures/tarif_testrelationen.csv` (Relation, Regeltyp, Personenzahl, erwarteter Preis in minor); vom Auftraggeber zu bestätigen.
- **Parameter und Kennzahlen:** keine im Katalog; Tarifparameter in `tariff_rule`.
- **Oberfläche:** keine eigene; Darstellung in UI-02/UI-03 (WP-M2-41, WP-M2-42).
- **Entscheidungen:** D-05 (Steuersätze und Preise bis zur Freigabe des Pilottarifs Testannahmen) blockiert nur die Produktivschaltung.
- **Offene Punkte:** OP-12 (Tarifklassen, Steuersätze und die Testrelationen mit erwarteten Preisen fehlen und müssen vom Auftraggeber geliefert werden), Formeln und Rundungsregel je Regeltyp sowie Mehrpersonenregel im Detailvertrag (WP-M2-01), Quelle der tariflichen Strecke (Routingdistanz mit Version aus WP-M2-10). Der Bau mit Testannahmen ist möglich; die Abnahme setzt die vom Auftraggeber bestätigten Testrelationen voraus (Originalabnahme „erwartete Preise“), was hier ausdrücklich festgehalten wird.

## Tests

- **Positiv:** Tabelle der vordefinierten Testrelationen als parametrisierter Test für Festpreis, Zonenpreis und Grundpreis/Streckenanteil liefert exakt die erwarteten Preise; Mehrpersonenbuchung nach Regel bepreist; gleiche Eingaben ergeben immer dasselbe Ergebnis.
- **Negativ:** fehlende Zone; Strecke 0; Überlaufgrenze (Multiplikation überschreitet `bigint`) wird abgewiesen; unbekannter Regeltyp; Steuer wird nicht doppelt addiert (Komponente `TAX` nur einmal, keine Steuer auf bereits brutto ausgewiesene Komponenten); statischer Test: keine `float`-Arithmetik im Codepfad.
- **Konkurrierend:** entfällt für die zustandslose Berechnung (begründet im WP); als Geldpfad zusätzlich: parallele Berechnungen mit gleichen Eingaben liefern bitgleiche Ergebnisse (keine geteilten veränderlichen Zustände); Tarifwechsel parallel zur Preisbindung wird in LH-6.10-PAY-003 und LH-6.10-PAY-001 geprüft. Property-Test: Komponentensumme = Brutto für zufällige Eingaben.
- **Referenztests:** keine Katalog-ID im WP-Block genannt.
- Testfälle: backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::testrelationen_liefern_erwartete_preise; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::komponentensumme_gleich_brutto_property; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::ueberlauf_wird_abgewiesen; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::gleiche_eingaben_gleiches_ergebnis_auch_parallel; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::mehrpersonenbuchung_nach_regel; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::fehlende_zone_strecke_null_unbekannter_regeltyp_abgewiesen; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/FareEngineTest.kt::steuer_nicht_doppelt_und_keine_gleitkommaarithmetik; backend/modules/fare-payment/src/test/kotlin/de/taktovia/farepayment/TariffPricingTest.kt::preis_der_wirksamen_revision_nachfolgerregel_oder_kein_tarif; db/tests/test_tarif_komponenten.py::test_komponenten_summe_je_segment_und_regelbezug; db/tests/test_tarif_komponenten.py::test_unveraenderlich_und_mandantentrennung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
