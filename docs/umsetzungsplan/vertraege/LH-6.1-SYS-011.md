# LH-6.1-SYS-011 – Serverseitige Regelprüfung (Policy-Port)

- **Verantwortlicher:** Rolle Plattform (Modul `backend/platform`)
- **Arbeitspaket:** WP-M1-26
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Angebots- und Betriebsregeln werden vor Bestätigung einer Buchung serverseitig geprüft.

**Originalabnahme (unverändert):** Manipulierte Client-Parameter umgehen keine operative oder tarifliche Regel.

## Vertrag

- **Tabellen / DDL:** Keine neuen Tabellen. Die verwendete Regelrevision wird im bestätigten Objekt referenziert (z. B. `fare_quote.config_revision_id`); die wirksame Konfiguration stammt aus der Präzedenzauflösung (WP-M1-21, `/v1/config/effective`).
- **API:** Konvention und Lint für Request-Schemas: `additionalProperties: false`, keine clientseitig setzbaren Regel-, Preis- oder Statusfelder (Liste der regelrelevanten Felder, die in keinem Request-DTO vorkommen dürfen, im Bau als Detailvertrag); unbekannte Felder 422 `application/problem+json`. Regelverletzung mit stabilem Fehlercode (Codenamen im Bau festzulegen, OP-33). Port `PolicyCheck` ("prüfe Befehl X im Kontext Y gegen wirksame Regeln zum Zeitpunkt T", Name Vorschlag) mit Pflichtaufruf vor Commit für alle als "bestätigend" markierten Befehle (Architekturtest); Ergebnis enthält die Regelrevision. Ein im Request gelieferter `config_revision_id`/Regelwert ist kein Eingabefeld des Befehls; die Revision stammt ausschließlich aus der serverseitigen Auflösung. Fehler: Regelverletzung 409/422 mit stabilem `code` (Vorschlag, OP-33); Fehlerbild ohne fremde Objektbezeichnungen (Kap. 05 §5.1); fremder Mandant 404 `NOT_FOUND`.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Regelrevision im Ergebnis und im Audit-Ereignis (WP-M1-46) referenziert; Snapshot-Bezug zu SYS-005.
- **Parameter und Kennzahlen:** keine neuen; Fachregeln (Gebiet, Zeit, Kapazität, Tarif) sind M2.
- **Oberfläche:** keine.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-01/OP-38 – Nachweis an Buchungsbestätigung und Tarifprüfung ("operative oder tarifliche Regel") folgt mit M2 über denselben Port (Vollabnahme WP-M2-09, WP-M2-23); M1 weist den Port an einem Testbefehl nach. Blockiert den Bau nicht. OP-33: Port-/Klassen- und Fehlercodenamen sind Vorschläge.

## Tests

- **Positiv:** regelkonformer Testbefehl wird bestätigt; Ergebnis und Audit nennen die serverseitig aufgelöste Regelrevision.
- **Negativ:** zusätzliches Feld mit eigener Wartezeit/eigenem Preis (422 oder wirkungslos); überschriebene Revisions-ID; verändertes Zeitfenster außerhalb der Regel; manipulierte Regelwerte; veraltete Revisionsreferenz; Regelwechsel zwischen Angebot und Bestätigung; bestätigender Befehl ohne `PolicyCheck`-Aufruf lässt den Architekturtest scheitern; Request-Schema-Lint findet `additionalProperties` ungleich false (Lint über alle Request-Schemas unter `api/*.yaml`); clientseitig gesetztes Preis-/Status-/Regelfeld in einem Request-DTO lässt den Lint scheitern; Regel strenger veröffentlicht zwischen Angebot und Bestätigung wird nicht stillschweigend mit der alten Revision bestätigt; Fehlerverhalten (Muster: 409 `REQUOTE_REQUIRED`, Kap. 05) im Bau festzulegen (OP-33).
- **Konkurrierend (Geld/Kapazität):** Publikation einer strengeren Regel parallel zur Bestätigung – Entscheidung konsistent zu genau einer Revision.
- **Referenztests:** keine Katalog-ID im WP genannt.

Testfälle: geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/PolicyCheckTest.kt::manipulierte_client_parameter_bleiben_wirkungslos; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/PolicyCheckArchitectureTest.kt::bestaetigender_befehl_ohne_policycheck_scheitert; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/PolicyCheckTest.kt::regelkonformer_befehl_nennt_serverseitige_regelrevision_in_ergebnis_und_audit; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/PolicyCheckTest.kt::regelwechsel_zwischen_angebot_und_bestaetigung_wird_erkannt; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/RequestSchemaLintTest.kt::alle_request_schemas_haben_additional_properties_false_und_keine_regelfelder; geplant:backend/platform/src/test/kotlin/de/taktovia/platform/policy/PolicyCheckTest.kt::parallele_publikation_entscheidung_konsistent_zu_einer_revision

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (erforderlich: Sicherheit/Geld/Kapazität) | | | |
