# LH-6.19-NFR-012 – Zentrale Zeitzonen- und Rundungsregeln mit Referenztests

- **Verantwortlicher:** Rolle Plattform (Modul `platform`, Geld- und Zeittypen)
- **Arbeitspaket:** WP-M5-07
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> System verwendet zentral definierte und reproduzierbare Zeitzonen-/Rundungsregeln für Zeit und Preise.

**Originalabnahme (unverändert):** Zeitumstellungs- und Rundungsreferenztests bestehen.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Prüfung (Datenbanktest), dass Geldspalten in `fare_quote`, `payment_intent`, `economic_event`, `allocation_line`, `journal_line` ganzzahlig (`bigint`, kleinste Einheit, Kap. 03 §2.1) und Zeitspalten `timestamptz` sind (db/migrations V1/V2).
- **API:** Zeit als RFC 3339 mit Offset, Zone als IANA-Name (Kap. 05 §5.1); Ortszeit in der DST-Lücke wird abgelehnt (Fehler nach `application/problem+json`), doppelte Ortszeit nur mit Offset; Zeit ohne Offset wird abgelehnt.
- **Zustandsautomat:** keine.
- **JSON-Schema / Ereignisse:** versionierte Regelübersicht „Zeit und Rundung“ (Fundstelle je Regel, nutzende Module: Tarif, Zahlung, Storno, Schichtdauer, Kostenumlage, KPI-Periode); Referenztestkatalog mit festen Eingaben und erwarteten Ergebnissen.
- **Regeln:** `backend/platform` als einzige Quelle für Geld-/Zeittypen; Steuerrundung Standard `ROUND_HALF_UP` je Steuergruppe; Largest-Remainder-Umlage mit stabilem Gleichstand (ID-Reihenfolge); Architekturtest verbietet eigene Rundung, Gleitkomma für Geld und `LocalDateTime` ohne Zone in Fachmodulen. Negative Korrekturen: zuerst absoluten Betrag verteilen, dann Vorzeichen anwenden; Korrektur wo möglich mit den gespeicherten ursprünglichen Teilbeträgen (Kap. 03 §2.1). Mengen/Sätze `numeric(20,8)`, API als Dezimalstrings. Beträge im Bereich +-9.007.199.254.740.991, Währung mit korrektem Exponenten.
- **Referenzfälle:** 25.10.2026 02:30 `Europe/Berlin` mit +02:00 und +01:00 sind zwei Zeitpunkte; Ortszeit in der Frühjahrslücke abgelehnt; Schicht-/Fahrtdauern über die Umstellung aus absoluten Zeitpunkten. 100,00 EUR auf drei gleiche Anteile (IDs A < B < C) = 33,34 / 33,33 / 33,33; 100,01 EUR = 33,34 / 33,34 / 33,33; Summen exakt; Rücknahme spiegelt gespeicherte Teilbeträge (100,00 EUR: -33,34 / -33,33 / -33,33). Gleiche Eingaben liefern in zwei Läufen, auf zwei Instanzen und nach Neustart identische Ergebnisse.
- **Parameter und Kennzahlen:** `reporting_timezone` (Betriebstag und Periodenbasis; kataloge.csv J.7).
- **Oberfläche:** keine.
- **Entscheidungen:** D-07 (Kostenbewertung) und D-06 (Steuer-/Kontenregeln) blockieren nur die Produktivfreigabe abweichender Rundungsregeln.
- **Offene Punkte:** OP-10 (Rundung streckenabhängiger Fahrpreise, Rabatte, Stornoentgelte und Zeitrundung im Pflichtenheft nicht festgelegt; Referenzbeispiele aus Kap. 03 §2.1, PH-AT-013, PH-AT-018; Preisrundungsregel der Tarifengine vor deren Bau zu klären).

## Tests

- **Positiv:** Referenzkatalog (Zeitumstellung und Rundung wie oben, inkl. 100,01 EUR und vollständiger Rücknahme, Schicht-/Fahrtdauer über die Umstellung) besteht; `reporting_timezone` bestimmt Betriebstag und Periodenbasis DST-sicher; zwei Läufe, zwei Instanzen und Neustart liefern identische Ergebnisse.
- **Negativ:** Betrag außerhalb +-9.007.199.254.740.991 abgelehnt; Währung mit falschem Exponenten abgelehnt; Zeit ohne Offset abgelehnt; Ortszeit in der Frühjahrslücke abgelehnt; Architekturtest schlägt bei Probeberechnung mit Gleitkomma oder modulinterner Rundung fehl; Datenbanktest schlägt bei nicht ganzzahliger Geldspalte oder Zeitspalte ohne Zone fehl.
- **Konkurrierend (Geld):** parallele Umlage desselben Kostenereignisses liefert identische Zeilen und genau eine wirksame Umlage.
- **Referenztests:** trägt bei zu PH-AT-013 und PH-AT-018 (dort im liefernden Meilenstein bestanden).
- Testfälle: PH-AT-013; PH-AT-018; backend/platform/src/test/kotlin/de/taktovia/platform/time/ZeitUndRundungReferenzTest.kt::dst_umstellung_25_10_2026_zwei_zeitpunkte; backend/platform/src/test/kotlin/de/taktovia/platform/time/ZeitUndRundungReferenzTest.kt::fruehjahrsluecke_und_zeit_ohne_offset_abgelehnt; backend/platform/src/test/kotlin/de/taktovia/platform/time/ZeitUndRundungReferenzTest.kt::schichtdauer_ueber_umstellung_aus_absoluten_zeitpunkten; backend/platform/src/test/kotlin/de/taktovia/platform/money/ZeitUndRundungReferenzTest.kt::largest_remainder_100_00_drei_anteile; backend/platform/src/test/kotlin/de/taktovia/platform/money/ZeitUndRundungReferenzTest.kt::largest_remainder_100_01_drei_anteile_und_ruecknahme; backend/platform/src/test/kotlin/de/taktovia/platform/money/ZeitUndRundungReferenzTest.kt::betrag_ausserhalb_bereich_und_falscher_waehrungsexponent_abgelehnt; backend/platform/src/test/kotlin/de/taktovia/platform/money/ZeitUndRundungReferenzTest.kt::zwei_laeufe_zwei_instanzen_neustart_identisch; backend/platform/src/test/kotlin/de/taktovia/platform/money/ZeitUndRundungReferenzTest.kt::parallele_umlage_genau_eine_wirksame; backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/AllocationServiceTest.kt::parallele_allokationsrevisionen_genau_eine_aktiv; backend/app/src/test/kotlin/de/taktovia/architecture/ZeitGeldRegelnTest.kt::keine_eigene_rundung_kein_gleitkomma_kein_localdatetime_in_fachmodulen; backend/app/src/test/kotlin/de/taktovia/architecture/ZeitGeldRegelnTest.kt::regeluebersicht_ist_versioniert_und_nennt_jede_regel_mit_fundstelle; db/tests/test_geld_zeit_spalten.py::test_geldspalten_bigint_zeitspalten_timestamptz; db/tests/test_geld_zeit_spalten.py::test_pruefung_schlaegt_bei_nicht_ganzzahliger_geldspalte_und_zeit_ohne_zone_an

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
