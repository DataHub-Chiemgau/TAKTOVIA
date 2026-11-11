# LH-I.7-CFG-007 – Wirkungsanalyse vor Publikation

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-23
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Vor dem Speichern wichtiger Konfigurationen werden Auswirkungen auf bestehende Fahrten und Schichten geprüft.

**Originalabnahme:** Eine Verkleinerung des Bediengebiets mit betroffenen Buchungen löst eine Konfliktprüfung aus.

## Vertrag

- **Tabellen / DDL:** neue Tabelle für Wirkungsberichte (`impact_report`, Name im Detailvertrag bestätigen; Pflichtenheft referenziert nur `impact_report_id`, OP-19) mit `(tenant_id,id)`, Revisionsbezug `config_revision_id`, Hash der geprüften Werte, Ergebnis (Objektverweise, Anzahl, Schweregrad), `created_by`, `created_at`; RLS mandantengetrennt; keine Personendaten.
- **API:** `/v1/config/revisions/{id}/impact` (Analyse auslösen/lesen; bei großem Umfang asynchron über `operation`); Bericht über seine ID abrufbar; `publishConfig` verlangt `impact_report_id` und weist fehlenden, fremden oder zu anderem Revisionsstand/Hash gehörenden Bericht ab. Auslösen nur mit Konfigurationsrecht.
- **Zustandsautomat:** Bericht ist an eine unveränderte Revision gebunden; Änderung der Revision invalidiert den Bericht.
- **JSON-Schema / Ereignisse:** Schema des Wirkungsberichts in `api/*.yaml`; Port `ImpactProvider` (Revision → betroffene Objekte, Schweregrad) je Fachmodul; M1 mit Test-Provider; Audit der Publikation referenziert den Bericht (WP-M1-46).
- **Parameter und Kennzahlen:** Kennzeichen „wichtig/impact-pflichtig" im Parameterschema (z. B. `pickup_window_min`); Bediengebietsverkleinerung als Auslöser.
- **Oberfläche:** UI-17 Basis: Vorschau „betroffene zukünftige Angebote", bestätigte Zusagen als „unverändert gebundene Version", Konfliktliste (Kap. 06 §6.8); Zustände leer, Fehler, fehlende Rechte.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-19 (Liste der impact-pflichtigen Konfigurationen; ob Bericht für jede Publikation Pflicht ist; Tabelle des Berichts); OP-01 (Originalabnahme mit echten Buchungen/Schichten erst mit WP-M2-04, WP-M2-23, WP-M4-17; M1 weist Port, Bericht und Publikationssperre mit Test-Provider nach); Regel, wie neue betroffene Objekte zwischen Bericht und Publikation erkannt werden (über Revisionsbindung), im Detailvertrag festzulegen. Kein Blocker für den M1-Bau.

## Tests

- **Positiv:** Revision mit Verkleinerung des Bediengebiets löst Konfliktprüfung aus, Bericht listet die vom Test-Provider gemeldeten Buchungen und Schichten; Bericht ohne Konflikte; Bericht nachträglich über ID abrufbar und im Audit der Publikation referenziert.
- **Negativ:** Publikation ohne Bericht, mit fremdem oder veraltetem Bericht (anderer Revisionsstand) abgewiesen; Bericht eines anderen Mandanten nicht lesbar; Analyse ohne Konfigurationsrecht verweigert; Bericht enthält keine Personendaten, nur Objektverweise und Anzahl.
- **Konkurrierend:** Revision wird nach Berichtserstellung geändert – Publikation scheitert; neue betroffene Objekte zwischen Bericht und Publikation werden über die Revisionsbindung erkannt.
- **Referenztests:** keine Katalog-ID im WP genannt; Nachweis über geplanten Test.

Testfälle: geplant:backend/config/src/test/kotlin/de/taktovia/config/ImpactAnalysisTest.kt::verkleinerung_bediengebiet_loest_konfliktpruefung_aus

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
