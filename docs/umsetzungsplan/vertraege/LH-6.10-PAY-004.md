# LH-6.10-PAY-004 – Zahlungsautomat unabhängig vom Buchungsstatus

- **Verantwortlicher:** <Rolle/Person>
- **Arbeitspaket:** WP-M2-30
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zahlstatus ist unabhängig vom Buchungsstatus gespeichert.

**Originalabnahme (unverändert):** Eine Zahlung kann fehlschlagen, ohne eine bereits vereinbarte Fahrt stillschweigend als abgeschlossen zu markieren.

## Vertrag

- **Tabellen / DDL:** <Tabellen, Migration, RLS, Invarianten aus Kap. 04 §4.5>
- **API:** <Pfade, Schemas, Statuscodes>
- **Zustandsautomat:** <Verweis auf vertraege/zustaende/…>
- **JSON-Schema / Ereignisse:** <Verweise>
- **Parameter und Kennzahlen:** <Namen aus kataloge.csv>
- **Oberfläche:** <UI-IDs, Zustände leer/Fehler/Rechte>
- **Entscheidungen:** <D-01 … D-12 oder „keine“>
- **Offene Punkte:** <konkret>

## Tests

- **Positiv:** …
- **Negativ:** …
- **Konkurrierend:** … (bei Geld, Kapazität, Sicherheit, Datentrennung Pflicht)
- **Referenztests:** AT-…, E2E-…, PH-AT-…

## Reviews

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (falls gefordert) | | | |
