# LH-I.2-FCO-004 – Energiekosten je 100 km und je Betriebsstunde

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-43
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Das System berechnet Energiekosten pro 100 km beziehungsweise je Betriebsstunde aus plausibilisierten Vorgängen.

**Originalabnahme (unverändert):** Ein Testdatensatz liefert dieselbe Kennzahl wie die dokumentierte Referenzberechnung.

## Vertrag

- **Tabellen / DDL:** nur lesend (keine DML-Rechte des Moduls `analytics` auf Tabellen anderer Module): `economic_event` (V1: `category`, `net_minor`, `tax_minor`, `gross_minor`, `recoverable_tax_minor`, generiertes `cost_basis_minor`, `currency`, `scenario`, `occurred_at`, `document_id`, `reverses_id`), `fuel_transaction` (neu aus WP-M4-30: Fahrzeug, Kraftstoffart, Menge `numeric(20,8)` mit Einheit, Brutto `bigint`, Tankzeit, Belegbezug), Kilometerabschnitte `vehicle_km_segment` aus WP-M4-42, Betriebsstunden (kein Datenmodell vorhanden, Quelle und Definition offen, OP-22). Ergebnis in `aggregate_snapshot` (neu, `(tenant_id,id)`, RLS; Spalten laut WP-M4-46: Definition, Filter, Wert, Zähler, Nenner, Datenstand, Vollständigkeit) mit `definition_version` und `dataset_watermark`. Es gehen nur freigegebene, plausibilisierte Vorgänge ein (Szenario `ACTUAL`, nach WP-M4-31/-33); ausgeschlossene Vorgänge werden mit Grund benannt (Ausschlussliste im Snapshot). Stornierte Ereignisse (`reverses_id`) heben den ursprünglichen Vorgang auf und werden nicht doppelt gezählt. Beträge in Minor Units (`bigint`), keine Gleitkommarechnung für Geld; Ereignisse unterschiedlicher `currency` werden nicht addiert. Neue Migrationen nach V3 (nächste freie Versionsnummer).
- **API:** `GET /v1/analytics/metrics` mit den beiden neuen Kennzahlnamen (siehe unten): Wert, Einheit/Währung, `definition_version`, Nenner, Vollständigkeit, `as_of`. Nullnenner ergibt `null` + `ZERO_DENOMINATOR`; unvollständige Quellen ergeben Status `PROVISIONAL` + Abdeckungsgrad (Kap. 03 §7). 404 für fremde Mandanten/Fahrzeuge. Der Pfad steht in Kap. 05 §5.7, die Antwortschemas legt WP-M4-46 fest.
- **Zustandsautomat / Schema / Ereignisse:** keine.
- **Parameter und Kennzahlen:** neue, im Katalog K nicht enthaltene Definitionen „Energiekosten je 100 km“ (Zähler Energiekostenbasis `cost_basis_minor`, Nenner Kilometer aus WP-M4-42) und „Energiekosten je Betriebsstunde“ (Nenner Betriebsstunden), im Kennzahlregister zu ergänzen (OP-40). Einheiten strikt getrennt: Kraftstoff (Liter) und Strom (kWh) werden nicht gemischt, die Energieart bleibt in der Ausgabe getrennt. Datenbasis nur als Bezug: `Kraftstoff L/100 km`, `Strom kWh/100 km`, `Tankbeleg Vollständigkeit` (K.3, nicht Teil der P1-Liste aus WP-M4-46). P1 ist eine Erwerbsnäherung ohne Bestandsführung und Ladeverluste (Kap. 03 §1.1) und wird so gekennzeichnet.
- **Oberfläche:** Anzeige in UI-15 / Kennzahlenansicht (WP-M4-44): Wert mit Datenstand und Vollständigkeit; fehlende Energiedaten als „fehlt“ markiert, nie als 0 Kosten (COST-08); Nenner 0 als „nicht berechenbar“; Leerzustand „keine plausibilisierten Tankvorgänge“.
- **Entscheidungen:** D-07 (Energiebevorratung/Bewertung; blockiert nur die Produktivschaltung).
- **Offene Punkte:** OP-22 (Definition „Betriebsstunde“ und deren Datenquelle; „dokumentierte Referenzberechnung“ liegt nicht vor und wird in WP-M4-01 erstellt und freigegeben, der Abnahmelauf braucht sie); OP-40 (Name, Formel netto oder Kostenbasis inkl. nicht abzugsfähiger Steuer; Vorschlag: Kostenbasis; ob Kraftstoff- und Stromkosten zu einer Gesamtzahl addiert werden, ist nicht festgelegt, Vorschlag: nach Energieart getrennt); OP-16 (Vergleichsbasis der Tankplausibilität). Bau nicht blockiert.

## Tests

- **Positiv:** Referenzdatensatz liefert exakt dieselbe Kennzahl wie die dokumentierte Referenzberechnung, je 100 km und je Betriebsstunde (Rundung Minor Units dokumentiert); jede Antwort trägt `definition_version`, Datenstand und Vollständigkeit.
- **Negativ:** null Kilometer bzw. null Betriebsstunden ergibt `null` + `ZERO_DENOMINATOR`; Liter und kWh werden nie zu einer Mengenkennzahl gemischt; unfreigegebener oder nicht plausibilisierter Beleg geht nicht ein und erscheint mit Grund in der Ausschlussliste; fehlende Energiedaten ergeben `PROVISIONAL` mit Abdeckungsgrad und „fehlt“, nicht null Kosten (COST-08); Ereignisse mit unterschiedlicher `currency` werden nicht addiert; stornierter Tankvorgang (`reverses_id`) zählt nicht; Fahrzeug eines fremden Mandanten ergibt 404.
- **Konkurrierend (Geld):** Freigabe eines Tankbelegs parallel zur Berechnung: Ergebnis gehört genau einem Datenstand (`dataset_watermark`), Wiederholung auf demselben Stand ist identisch (reproduzierbar); Mandant A und B parallel ohne Vermischung (RLS auf `aggregate_snapshot`).
- **Referenztests:** keine Katalog-Testfälle im WP genannt (COST-08 als Kriterium).

Testfälle: geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::referenzdatensatz_ergibt_referenzkennzahl; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::null_kilometer_ergibt_zero_denominator; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::liter_und_kwh_werden_nicht_gemischt; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::unfreigegebener_beleg_wird_ausgeschlossen_und_benannt; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::fehlende_energiedaten_ergeben_provisional_nicht_null_kosten; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::unterschiedliche_waehrungen_werden_nicht_addiert; geplant:backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/EnergyCostMetricTest.kt::freigabe_parallel_zur_berechnung_bleibt_reproduzierbar; geplant:db/tests/test_aggregate_snapshot.py::test_rls_trennt_mandanten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Geld).
