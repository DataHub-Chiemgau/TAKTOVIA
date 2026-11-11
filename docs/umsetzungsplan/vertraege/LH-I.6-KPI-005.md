# LH-I.6-KPI-005 – Nachfragetrichter und getrennte Zählung der Buchungsstufen

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-51
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Software unterscheidet Anfrage abgelehnt, Angebot angezeigt aber nicht gebucht, Zahlung fehlgeschlagen und Fahrgaststorno.

**Originalabnahme (unverändert):** Ein Funnel-Bericht zeigt alle vier Ereignisse getrennt.

## Vertrag

- **Tabellen / DDL:** Funnelfakten und `aggregate_snapshot` (Definition, Filter, Wert, Zähler, Nenner, Datenstand, Vollständigkeit; Detail KPI-010). Je Versuch genau ein Endzustand je Stufe; Fakten werden aus Ereignissen projiziert (Anfrage, Angebot angezeigt, Buchung bestätigt, Zahlung fehlgeschlagen, Fahrgaststorno, Fahrt abgeschlossen), Deduplikation je Ereignis und Konsument (Inbox). Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** `GET /v1/analytics/metrics` (Funnel und Zählwerte mit Definitionsversion, Filter, Datenstand, Vollständigkeit, Nenner; WP-M4-46); `api/analytics.yaml` existiert noch nicht und ist mit dem Bau anzulegen.
- **Zustandsautomat:** keiner eigener; liest Zustände von Angebot, Buchung, Zahlung aus M2.
- **JSON-Schema / Ereignisse:** Konsum bestehender Ereignisse aus `api/events/` (M2: Angebot, Buchung, Storno mit Grund, Zahlungsstatus; M3: Fahrtabschluss); Abgleichbericht gegen das Ereignisprotokoll.
- **Parameter und Kennzahlen:** Funnelstufen getrennt: `Anfrage abgelehnt` (Ablehnung mit Primärgrund, siehe KPI-002), `Angebot angezeigt aber nicht gebucht`, `Zahlung fehlgeschlagen`, `Fahrgaststorno`. Kennzahlen aus kataloge.csv: `Anfragen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber` (Betreiber- und Fahrgaststorno unterscheidbar). Die vier Zählgrößen werden nie zu einer Summe verschmolzen.
- **Oberfläche:** UI-19 (Kennzahlen) bzw. Zuordnung offen (WP-Dokument OP 23); Zustände: keine Daten, Wert nicht bewertbar (`null` mit Grund), vorläufig (`PROVISIONAL`), fehlende Rechte. Funnel-Ansicht mit vier getrennten Zählwerten.
- **Entscheidungen:** keine (D-05 nur für Zielwerte, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP 27: "Angebot angezeigt" braucht ein explizites Anzeigeereignis oder die Regel "Angebot abgelaufen ohne Hold"; "abgeschlossene Buchung" ist nicht gegen "durchgeführte Fahrt" abgegrenzt. Das blockiert den Bau der Stufen ohne Festlegung durch M2/Fachanalyse; Test nutzt bis dahin die im Fixture festgelegte Ereignisdefinition. OP 23: Oberfläche (UI-19) nicht einem P1-Meilenstein zugeordnet.

## Tests

- **Positiv:** Fixture mit bekanntem Ereignisprotokoll: Bericht zeigt Anfrage abgelehnt, Angebot angezeigt aber nicht gebucht, Zahlung fehlgeschlagen und Fahrgaststorno als vier getrennte Zahlen; Zahlen für Anfragen, Angebote, bestätigte und abgeschlossene Buchungen stimmen mit dem Ereignisprotokoll überein (Abgleichbericht); jeder Versuch hat genau einen Endzustand je Stufe; die vier Zählgrößen erscheinen nie als eine Summe; `Buchungsstornoquote Fahrgast` und `Buchungsstornoquote Betreiber` sind getrennt ausgewiesen; Nenner 0 ergibt `null` + `ZERO_DENOMINATOR`, nie 0 oder 100 %.
- **Negativ:** Storno nach Abschluss: Zuordnung nach der im Fixture festgelegten Ereignisdefinition (OP 27), der Versuch zählt in genau einer Stufe, nie doppelt; ein Betreiberstorno wird nicht als Fahrgaststorno gezählt (und umgekehrt); doppelte Ereigniszustellung verändert keine Zahl; Ereignis eines fremden Mandanten wird verworfen.
- **Konkurrierend:** Zwei Worker verarbeiten dasselbe Ereignis parallel: genau eine Wirkung; Abfrage während laufender Projektion nennt konsistenten Datenstand.
- **Referenztests:** Keine Katalog-IDs im WP-Dokument genannt; PH-AT-021 deckt Versuche und Ablehnungen mittelbar ab.
- Testfälle: geplant:db/tests/test_funnel_fakten.py::test_vier_stufen_getrennt_und_ereignisabgleich; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelProjectionTest.kt::doppelte_zustellung_aendert_keine_zahl

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
