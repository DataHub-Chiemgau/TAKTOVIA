# LH-6.1-SYS-003 – Dienstkonfiguration und Angebotsmodelle

- **Verantwortlicher:** Rolle Plattform (Modul `service-geo`)
- **Arbeitspaket:** WP-M1-25
- **Status:** Bereit (unter Planungsannahme PA-01, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Angebotsmodelle Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr und Bahnhofszubringer lassen sich konfigurieren.

**Originalabnahme (unverändert):** Für jedes Modell kann eine Testfahrt gemäß Modellregeln erfolgreich gebucht werden.

## Vertrag

- **Tabellen / DDL:** `service` (`config_revision_id` NOT NULL, `timezone`, `operator_id` per zusammengesetztem FK `(tenant_id,operator_id)` auf `organization`) und `config_revision` mit `scope_kind` für Dienst (Wert im Detailvertrag SYS-002 festgelegt). `service` hat kein Feld für das Angebotsmodell (OP-28): das Modell ist Bestandteil der Dienstrevision (`values_json`), keine neue Spalte. Reihenfolge Revision vor Dienst (OP-35). RLS und Mandantenlokalität wie WP-M1-01.
- **API:** `/v1/services` (Dienst mit Angebotsmodell und Konfigurationsrevision lesen/anlegen; `/v1/services` steht noch in keiner Datei unter `api/`; OpenAPI-Vertrag im Bau zu ergänzen). Anlage mit `Idempotency-Key`; Validierungsfehler 422 `application/problem+json` mit Feldverletzung (JSON-Pointer); Dienst mit Betreiber eines fremden Mandanten wie nicht vorhanden (404 `NOT_FOUND`, zusätzlich zusammengesetzter FK `(tenant_id,operator_id)`); fehlende Rolle 403. Wirksame Modellregeln über `/v1/config/effective` (Konfigurations-Port).
- **Zustandsautomat:** Revision Entwurf → geprüft → publiziert (WP-M1-22); kein eigener Automat.
- **JSON-Schema / Ereignisse:** `schemas/service-config.v1.schema.json` (Erweiterung aus SYS-002) mit Enum der drei Angebotsmodelle (Flächenbedarfsverkehr, linienbasierter Bedarfsverkehr, Bahnhofszubringer; technische Enum-Werte im Bau festzulegen) und Modellregel-Validator (je Modell Pflicht-/unzulässige Parameter); vorgesehen ist die Eintragung in `policy_schema` (Kap. 04 §4.1 nennt die Tabelle, `V1__kernschema.sql` enthält sie nicht: DDL im Bau, OP-33). Ereignis zur Publikation über WP-M1-22.
- **Parameter und Kennzahlen:** `pooling_allowed`, `max_detour_pct`, `stop_walk_max_m`, `transfer_min_time_min`, `advance_arrival_buffer_min` als modellabhängige Werte (Katalog J.1: Ebene Dienst bzw. Dienst/Profil, Region/Transfer, Dienst/Transfer).
- **Oberfläche:** UI-17 Basis – Dienst anlegen, Angebotsmodell wählen, modellabhängige Felder; Zustände leer/Fehler (Validierungsfehler je Feld)/fehlende Rechte.
- **Entscheidungen:** D-05 (Pilotdienste und Genehmigungsmodell) – blockiert nur die Produktivkonfiguration.
- **Offene Punkte:** OP-28 – Das Pflichtenheft beschreibt die Modellregeln der drei Angebotsmodelle nicht (unterscheidende Regeln, Pflichtparameter, unzulässige Parameter). Ohne diese Festlegung kann der Modellregel-Validator inhaltlich nicht gebaut und die Originalabnahme ("gemäß Modellregeln") nicht geprüft werden: **blockiert den Bau des Validators** bis der Auftraggeber die Modellregeln festlegt; Bausteinrahmen (Enum, Schema, API) kann vorher entstehen. OP-01/OP-38: Testfahrt je Modell erst mit M2 (Vollabnahme WP-M2-04, -05, -07, -21, -23); M1 liefert je Modell eine publizierte Fixture-Konfiguration. Auflösung von `service_area`, `stop_revision`, Tarifverknüpfung erst M2.

- **Festlegung im Bau (WP-M1-25):** Modellregeln (Pflicht/unzulässig je Modell), Prüfung und Bezüge: [angebotsmodelle.md](angebotsmodelle.md). API zusätzlich `GET /v1/services/{service_id}/configuration` (`getServiceConfiguration`) und `GET /v1/service-offer-models` (`listServiceOfferModels`); Modell und Parameter werden über Konfigurationsrevisionen geschrieben (Dienst anlegen erzeugt den Entwurf, WP-M1-09).
- **Planungsannahme:** PA-01 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

## Tests

- **Positiv:** drei Dienste, je ein Modell, konfigurieren, validieren, publizieren; wirksame Konfiguration liefert Modell und Modellregeln; je Modell eine publizierte Fixture-Konfiguration für die M2-Testfahrt.
- **Negativ:** unbekanntes Modell; Pflichtangabe des Modells fehlt; modellfremder Parameter gesetzt (modellwidrige Parameterkombination); Dienst mit Betreiber eines fremden Mandanten; Rolle ohne Recht.
- **Konkurrierend (Datentrennung):** zwei parallele Publikationen der Dienstkonfiguration desselben Dienstes – genau eine wirksam (Mechanismus aus WP-M1-22); gleichzeitige Dienstanlage in Mandant A und B ohne gegenseitige Wirkung.
- **Referenztests:** keine Katalog-ID im WP genannt; Testfahrt je Modell mit M2 (Vollabnahme WP-M2-04, -05, -07, -21, -23). Die Originalabnahme ("Testfahrt gemäß Modellregeln") ist in M1 nur als publizierte Fixture-Konfiguration je Modell prüfbar.

Testfälle: geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/ServiceConfigOfferModelTest.kt::drei_angebotsmodelle_konfigurieren_validieren_publizieren; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/ServiceConfigOfferModelTest.kt::modellwidrige_parameterkombination_wird_abgewiesen; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/ServiceConfigOfferModelTest.kt::parallele_publikation_genau_eine_wirksam; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/ServiceConfigOfferModelTest.kt::dienst_mit_betreiber_fremder_mandant_wie_nicht_vorhanden; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/ServiceConfigOfferModelTest.kt::gleichzeitige_dienstanlage_mandant_a_und_b_ohne_wirkung; geplant:backend/app/src/test/kotlin/de/taktovia/app/structure/ServiceConfigurationApiTest.kt::drei_dienste_je_ein_modell_wirksame_modellregeln_ueber_die_api; geplant:web/operator/src/config/DienstKonfiguration.test.tsx::ui17_modellabhaengige_felder_pflicht_optional_und_unzulaessig_entfaellt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Datentrennung: Betreiber/Mandantengrenze).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
| Zweitprüfung (erforderlich: Datentrennung) | | | |
