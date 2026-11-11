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

Testfälle: backend/modules/config/src/test/kotlin/de/taktovia/config/ImpactAnalysisTest.kt::verkleinerung_bediengebiet_loest_konfliktpruefung_aus; backend/modules/config/src/test/kotlin/de/taktovia/config/ImpactAnalysisTest.kt::publikation_mit_veraltetem_bericht_wird_abgewiesen; backend/modules/config/src/test/kotlin/de/taktovia/config/ImpactAnalysisTest.kt::neue_betroffene_objekte_zwischen_bericht_und_publikation_werden_erkannt; backend/modules/config/src/test/kotlin/de/taktovia/config/ImpactAnalysisTest.kt::revision_wird_nach_berichtserstellung_geaendert_publikation_scheitert

## Umsetzung M1 (WP-M1-23)

- **Tabelle:** `impact_report` (V10230): `(tenant_id,id)`, `config_revision_id` (zusammengesetzter FK), `revision_row_version` (Stand bei der Analyse, informativ), `values_hash` (SHA-256 über kanonisches JSON aus Scope, Schema-Version, Werten und Gültigkeit; Grund und Zeilenversion zählen nicht, weil Prüfen und Freigeben die Zeilenversion erhöhen), `affected_digest`, `highest_severity` (`NONE|INFO|WARNING|BLOCKING`), `conflict_count`, `affected` (nur Art, Schweregrad, Anzahl, Verweise `{kind,id}`), `created_by`, `created_at`; unveränderlich (Trigger), RLS.
- **Bindungsregel:** Der Bericht gilt nur für die Revision mit identischem `values_hash`. `ImpactAnalysis.confirmForPublication` (von WP-M1-22 in der Publikationstransaktion aufzurufen) sperrt die Revisionszeile `FOR SHARE` und weist ab: fehlender Bericht 422 (`/impact_report_id` REQUIRED), unbekannter oder fremder Bericht 422 (INVALID_VALUE, nicht von „unbekannt“ unterscheidbar), Bericht einer anderen Revision oder geänderte Werte 409 `CONFLICT`.
- **Neue betroffene Objekte** (festgelegte Regel zur offenen Frage): Die Revisionsbindung erkennt Änderungen an der Revision. Änderungen am Bestand (neue Buchung nach der Analyse) erkennt `confirmForPublication`, indem es die Anbieter erneut befragt und das Ergebnis über `affected_digest`-Berechnung mit dem Bericht vergleicht; Abweichung 409, neue Analyse nötig.
- **Provider-Port:** `ImpactProvider.analyze(j, ctx, ImpactSubject)` je Fachmodul, in der Transaktion der Analyse mit Mandantenkontext; fällt ein Anbieter aus oder liefert er ungültige Befunde, entsteht kein Bericht (503). Bis M2/M4 gibt es nur Test-Anbieter.
- **API:** `POST /v1/config/revisions/{id}/impact` (`requestConfigImpact`, `config:write`, `If-Match` = Zeilenversion), neu `GET /v1/config/impact-reports/{id}` (`getConfigImpactReport`, `config:read`); `ConfigRevision.row_version` und `ConfigParameter.impact_required/impact_hint` ergänzt. M1 arbeitet synchron (die asynchrone Variante über `operation` `IMPACT_ANALYSIS` ist nicht gebaut, Umfang in M1 klein).
- **Impact-pflichtige Parameter** (vorläufig, OP-19): `pickup_window_min`, `max_wait_min`, `min_booking_lead_min`, `max_booking_horizon_days`, `protected_stop_horizon_min`, `min_capacity_reserve`, `service_config`; geführt in `ImpactRequiredParameters` (die Schema-Version 1 ist unveränderlich; die nächste Version führt `x-impact-required`).
- **Audit:** `config.impact.analyzed` (Analyse) und `config.impact.bound` (Bindung bei der Publikation, mit Bericht-ID und Hash).
- **Offen für Vollabnahme:** `publishConfig` selbst (WP-M1-22) ruft die Sperre auf; echte Anbieter für Bediengebiete/Buchungen (WP-M2-04, WP-M2-23) und Schichten (WP-M4-17).

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
