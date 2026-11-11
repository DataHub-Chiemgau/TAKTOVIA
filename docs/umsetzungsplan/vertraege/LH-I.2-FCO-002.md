# LH-I.2-FCO-002 – Strukturierte Fahrzeugkosten nach Kostenart und Monat

- **Verantwortlicher:** Rolle Finanzen/Kosten (Modul `documents-finance`)
- **Arbeitspaket:** WP-M4-40
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für jedes Fahrzeug werden Anschaffung/Leasing, Versicherungen, Kfz-Steuer, Energie, Wartung und Reparaturkosten strukturiert verwaltet.

**Originalabnahme (unverändert):** Kosten je Fahrzeug und Monat lassen sich ohne manuelle Tabellen nach Kostenart auswerten.

## Vertrag

- **Tabellen / DDL:** `cost_category` (Seed der sechs Gruppen: Anschaffung/Leasing, Versicherung, Kfz-Steuer, Energie, Wartung, Reparatur; Schlüssel mit dem Bau festzulegen), `economic_event` (`category`, `service_from`/`service_to`, `booked_at`, `source_system`/`source_ref` mit Unique `(tenant_id,legal_entity_id,source_system,source_ref,event_type,scenario)` als fachlicher Ursprungsschlüssel für „genau einmal“), `cost_object` (`kind='VEHICLE'`, `business_ref`). Anschaffung zählt in P1 nicht als Periodenaufwand und wird nicht abgeschrieben (Kap. 03 §1.1; die Gruppe Anschaffung/Leasing erscheint in der Monatsauswertung mit der erfassten Position, nicht verteilt). Die Kostenart-Schlüssel der sechs Gruppen werden mit dem Bau festgelegt; `economic_event.category` verweist auf `cost_category`. RLS wie alle Mandantentabellen.
- **API:** `POST /v1/cost-events` (manuelles Kostenereignis anlegen, freigabepflichtig; gleicher Freigabeautomat wie Belege, Vier-Augen-Schwelle und Verbot der Selbstfreigabe aus WP-M4-34), `GET /v1/cost-events` (Auswertung) und `GET /v1/vehicles/{id}/total-cost` (P1-Ausschnitt: Kosten je Monat und Kostenart; Verwendung des Pfads in P1 laut OP 20 offen). Monatsauswertung mit Monat, Kostenart, Betrag (`amount_minor`, Währung), Bewertungsart `ACTUAL`, Datenstand und ausgewiesener Zuordnungsregel (Leistungszeitraum `service_from/service_to` bzw. Buchungsperiode `booked_at`; die Regel steht im Ergebnis). Monat ohne Kosten liefert eine leere Auswertung (200), fremdes Fahrzeug 404, ohne Authentisierung 401, ohne Leserecht 403, unbekannte Kostenart beim Anlegen 422; die Monatsauswertung zählt nur gebuchte (`POSTED`) Ereignisse; ein Ereignis mit Leistungszeitraum über mehrere Monate wird nach der festgelegten Regel zugeordnet (Annahme: Leistungszeitraum, wenn vorhanden, sonst Buchungsperiode; keine anteilige Verteilung auf Monate, falls nicht anders festgelegt). Schemas werden mit dem Bau in `api/finance.yaml` ergänzt (Pfade dort noch nicht beschrieben).
- **Zustandsautomat:** manuelles Kostenereignis nutzt den Beleg-Automaten aus WP-M4-34 (`vertraege/zustaende/beleg.md`).
- **JSON-Schema / Ereignisse:** Schema der Monatsauswertung; Outbox-Ereignis beim Posting wie bei Belegen.
- **Parameter und Kennzahlen:** `approval_stage_thresholds` (Vier-Augen für manuelle Ereignisse, Testwerte bis D-06); keine neuen Katalogkennzahlen.
- **Oberfläche:** UI-15 (TCO/Deckungsbeitrag) mit Monatsmatrix Kostenart × Monat je Fahrzeug; Dialog „Kostenereignis erfassen“ (Fahrzeug, Kostenart, Betrag, Leistungszeitraum, Ursprungsschlüssel); Leerzustand (Monat ohne Kosten), Fehlerzustand, Rechtezustand (ohne Recht nur Lesen).
- **Entscheidungen:** D-07 (Abschreibung/Leasing, Kostenarten; blockiert nur die Produktivschaltung).
- **Offene Punkte:** OP 20 (Umfang in P1 gegenüber FCO-006 bis FCO-008, Pfad `/v1/vehicles/{id}/total-cost`, fehlendes Abschreibungsmodell: P1 führt Anschaffung/Leasing nur als erfasste Kostenposition ohne Abschreibung); Monatszuordnung (Leistungszeitraum vs. Buchungsperiode, Festlegung im Bau mit Ausweis im Ergebnis, Annahme: Leistungszeitraum, wenn vorhanden, sonst Buchungsperiode); Kostenartenkatalog und Schema der Monatsauswertung im Bau; OP 15. Nicht im Umfang: `asset_schedule` (FCO-006), `vehicle_lease` (FCO-007), Policen (FCO-008), `rate_revision` (ANA-006), vollständiges TCO. Blockiert den Bau nicht.

## Tests

- **Positiv:** Fixture mit allen sechs Kostengruppen über drei Monate für ein Fahrzeug, je Monat und Kostenart centgenau nachgerechnet (Summe je Monat und je Kostenart stimmt mit den erfassten Beträgen überein); Auswertung je Monat und Kostenart ohne manuelle Tabellen; jede Gruppe ist dem Fahrzeug strukturiert zuordenbar; Monatszuordnungsregel steht im Ergebnis.
- **Negativ:** Monat ohne Kosten liefert leere Auswertung statt Fehler; fremdes Fahrzeug bzw. fremder Mandant ergibt 404; manuelles Kostenereignis ohne Freigaberecht wird abgewiesen, Selbstfreigabe über der Schwelle ebenfalls; unbekannte Kostenart ergibt 422; manuelles Kostenereignis für ein Fahrzeug eines anderen Mandanten wird abgewiesen (404), ohne Authentisierung 401; Ereignis mit Leistungszeitraum in einem anderen Monat als die Buchung folgt der ausgewiesenen Zuordnungsregel; Anschaffung wird in P1 nicht als Periodenaufwand abgeschrieben.
- **Konkurrierend (Geld):** dasselbe manuelle Kostenereignis wird parallel zweimal angelegt bzw. freigegeben, genau ein `economic_event` (fachlicher Ursprungsschlüssel, `Idempotency-Key`).
- **Referenztests:** keine Katalog-ID im WP genannt; Abnahme über die geplanten Tests.
- Testfälle: geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::sechs_kostengruppen_je_fahrzeug_ueber_drei_monate_nach_kostenart_ausgewertet; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::manuelles_kostenereignis_zweimal_parallel_genau_ein_economic_event; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::manuelles_kostenereignis_ohne_freigaberecht_abgewiesen; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::monat_ohne_kosten_leer_200_fremdes_fahrzeug_404_unbekannte_kostenart_422; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::selbstfreigabe_ueber_schwelle_abgewiesen_und_anschaffung_nicht_abgeschrieben; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/VehicleCostCatalogTest.kt::monatszuordnung_leistungszeitraum_vor_buchungsperiode_im_ergebnis_ausgewiesen; geplant:web/operator/src/kosten/Fahrzeugkosten.test.tsx::monatsmatrix_kostenart_mal_monat_leerzustand_und_nur_lesen_ohne_recht; geplant:db/tests/test_cost_category.py::test_seed_sechs_gruppen_und_mandantentrennung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
