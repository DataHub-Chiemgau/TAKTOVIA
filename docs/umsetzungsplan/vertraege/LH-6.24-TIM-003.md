# LH-6.24-TIM-003 – Offline-Stempel mit einmaliger Übernahme

- **Verantwortlicher:** <Rolle/Person>
- **Arbeitspaket:** WP-M4-12
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Die Stempelfunktion darf bei gestörter Verbindung lokal Zeitereignisse puffern; Synchronisierung markiert Herkunft, Gerätezeit und möglichen Konflikt.

**Originalabnahme (unverändert):** Offline-Stempel wird nach Wiederverbindung mit eigener Ereignis-ID genau einmal übernommen.

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
