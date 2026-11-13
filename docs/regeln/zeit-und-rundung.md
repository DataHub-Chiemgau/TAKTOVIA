# Regelübersicht Zeit und Rundung

- **Version:** 1.0.0 (WP-M5-07, LH-6.19-NFR-012)
- **Einzige Quelle im Code:** `backend/platform` (`platform.time`, `platform.money`). Fachmodule und Adapter rechnen nicht selbst.
- **Durchsetzung:** Architekturtest `backend/app/src/test/kotlin/de/taktovia/architecture/ZeitGeldRegelnTest.kt` (Regeln in `ArchitectureRules.timeAndMoneyRules()`), Datenbanktest `db/tests/test_geld_zeit_spalten.py`.
- **Referenztests:** `backend/platform/src/test/kotlin/de/taktovia/platform/time/ZeitUndRundungReferenzTest.kt` und `.../platform/money/ZeitUndRundungReferenzTest.kt`.
- **Änderungsregel:** Jede Änderung einer Regel erhöht die Version, ändert den Referenztestkatalog und nennt die Fundstelle. Abweichende Rundungsregeln für die Produktivfreigabe hängen an D-06 und D-07; die Rundung streckenabhängiger Fahrpreise, Rabatte, Stornoentgelte und Zeitrundung ist offen (OP-10).

## Zeit

| ID | Regel | Fundstelle | Code | Nutzende Module |
|---|---|---|---|---|
| Z-01 | Zeitpunkte werden als absoluter Zeitpunkt gespeichert: Datenbank `timestamptz` (intern UTC), im Code `Instant`. Zeitspalten ohne Zone sind verboten. | Kap. 05 §5.1, Kap. 04 §4.1 | `platform.time.ZonedMoment`, `db/tests/test_geld_zeit_spalten.py` | alle |
| Z-02 | Orte der Anwendung nutzen IANA-Zonennamen (`Europe/Berlin`); Abkürzungen, feste Offsets und `Etc/…` sind abgelehnt. | Kap. 05 §5.1 | `platform.time.IanaZones` | Tarif, Schichtdauer, KPI-Periode |
| Z-03 | Die API nutzt RFC 3339 mit Offset; Zeit ohne Offset wird abgelehnt (`OFFSET_REQUIRED`). | Kap. 05 §5.1 | `platform.time.Rfc3339` | alle mit Zeitfeldern |
| Z-04 | Eine Ortszeit in der Frühjahrslücke existiert nicht und wird abgelehnt (`LOCAL_TIME_NONEXISTENT`). | Kap. 05 §5.1 | `platform.time.ZonedMoments` | Tarif, Schichtdauer |
| Z-05 | Eine doppelte Ortszeit (Herbst) ist nur mit Offset eindeutig; ohne Offset abgelehnt (`LOCAL_TIME_AMBIGUOUS`), mit unpassendem Offset `ZONE_OFFSET_MISMATCH`. 25.10.2026 02:30 `Europe/Berlin` mit `+02:00` und `+01:00` sind zwei Zeitpunkte. | Kap. 05 §5.1 | `platform.time.ZonedMoments` | Tarif, Schichtdauer |
| Z-06 | Dauern entstehen aus absoluten Zeitpunkten, nie aus Ortszeit-Differenzen (Nachtschicht über die Herbstumstellung: 9 Stunden). | Kap. 05 §5.1 | `platform.time.Durations` | Schichtdauer, Storno, Tarif |
| Z-07 | Intervalle sind links geschlossen, rechts offen `[from, to)`. | Kap. 05 §5.1 | `platform.time.ValidityInterval` | Tarif, KPI-Periode |
| Z-08 | `reporting_timezone` (Katalog J.7) bestimmt Betriebstag und Periodenbasis. Betriebstage und Monate sind absolute Intervalle und können 23, 24 oder 25 Stunden (Tag) lang sein. | Kap. 05 §5.1, Katalog J.7 | `platform.time.ReportingPeriods`, `platform.time.OperatingDay` | KPI-Periode, Kostenumlage (Fiskalperiode) |
| Z-09 | `LocalDateTime` ohne Zone wird nicht gespeichert und nicht gerechnet. In Fachmodulen nur an der Darstellungsgrenze, begründete Ausnahmen in `ArchitectureRules.LOCAL_DATE_TIME_ALLOWLIST`. | Kap. 05 §5.1 | Architekturtest | alle |

## Geld und Rundung

| ID | Regel | Fundstelle | Code | Nutzende Module |
|---|---|---|---|---|
| G-01 | Geld ist `bigint` in der kleinsten Einheit, Währung ISO 4217 mit Exponent aus dem Währungskatalog; Bereich ±9.007.199.254.740.991. Kein Gleitkomma. | Kap. 03 §2.1, Kap. 05 §5.1 | `platform.money.Money`, `SignedMoney`, `MoneyValidator` | Tarif, Zahlung, Storno, Kostenumlage |
| G-02 | Mengen und Sätze sind `numeric(20,8)`, in der API Dezimalstrings. | Kap. 03 §2.1 | `platform.money.LargestRemainder.MAX_WEIGHT_SCALE` | Tarif, Kostenumlage |
| G-03 | Steuerrundung Standard `ROUND_HALF_UP` je Steuergruppe auf die kleinste Einheit. | Kap. 03 §2.1 | `platform.money.MoneyRounding.tax`, `netOfGross`, `halfUp` | Tarif, Zahlung, Storno |
| G-04 | Largest-Remainder-Umlage: abgerundete Quote, Reste an die größten Restanteile, Gleichstand nach kleinerer Kennung (Textordnung). 100,00 EUR auf drei gleiche Anteile = 33,34 / 33,33 / 33,33; 100,01 EUR = 33,34 / 33,34 / 33,33. | Kap. 03 §2.1, PH-AT-013 | `platform.money.LargestRemainder.distribute` | Kostenumlage, Belegaufteilung |
| G-05 | Negative Korrektur: zuerst den Absolutbetrag verteilen, dann das Vorzeichen anwenden. | Kap. 03 §2.1 | `LargestRemainder.distribute` | Kostenumlage, Storno |
| G-06 | Rücknahme spiegelt die gespeicherten Teilbeträge (100,00 EUR: -33,34 / -33,33 / -33,33); Teilrücknahme verteilt nach den gespeicherten Teilbeträgen. | Kap. 03 §2.1, PH-AT-018 | `LargestRemainder.reverse`, `reversePartial` | Storno, Kostenumlage |
| G-07 | Prozentpuffer (Sekunden, Mengen) werden aufgerundet (`CEILING`); Anzeigeprozente HALF_UP auf 8 Stellen, nie Grundlage von Beträgen. | Kap. 03 §2.1 | `MoneyRounding.ceilPercent`, `ratioHalfUp` | Prognose, Kostenumlage |
| G-08 | Fachmodule und Adapter verwenden weder `RoundingMode` noch `MathContext` und nie Gleitkomma für Geld; begründete Ausnahmen in `ArchitectureRules.ROUNDING_ALLOWLIST`. | Leitlinie 5 | Architekturtest | alle |
| G-09 | Ergebnisse sind reproduzierbar: gleiche Eingaben liefern in zwei Läufen, auf zwei Instanzen und nach Neustart (auch bei anderer Systemzone und Sprache) identische Ergebnisse; keine Uhr, kein Zufall. | LH-6.19-NFR-012 | `ReferenzLauf` in den Referenztests | alle |
