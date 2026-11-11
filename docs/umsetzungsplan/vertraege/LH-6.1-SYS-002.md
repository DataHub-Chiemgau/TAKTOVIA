# LH-6.1-SYS-002 – Versionierte Dienstkonfiguration

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-20
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Jeder Dienst besitzt versionierte Konfigurationen für Bediengebiet, Verkehrstyp, Betreiber, Betriebszeiten und Tarifverknüpfung.

**Originalabnahme:** Eine Konfigurationsänderung hat Ersteller, Zeitstempel und dokumentierte Wirksamkeit.

## Vertrag

- **Tabellen / DDL:** `config_revision` (`scope_kind='SERVICE'`, `revision`, `values_json`, `effective_from`, `effective_to`, `created_by`, `created_at`); `service.config_revision_id` verweist auf die gebundene Revision. Migration ergänzt `created_at`, Statusspalte, Änderungsgrund und CHECK der `scope_kind`-Werte (WP-M1-20, OP-18). Invarianten: `UNIQUE(tenant_id,scope_kind,scope_id,revision)`, `effective_to > effective_from` (bereits im Kern-DDL).
- **API:** `POST /v1/config/revisions` (Entwurf, `Idempotency-Key`), `GET /v1/config/revisions/{id}` mit ETag; Änderung eines Entwurfs mit `If-Match`. Pflichtfelder bei Anlage: Ersteller (aus Principal), `effective_from`, Grund. Ohne sie 422 `application/problem+json`.
- **Zustandsautomat:** Revision: Entwurf → geprüft → publiziert (Publikation WP-M1-22), publizierte Revision unveränderlich.
- **JSON-Schema:** `schemas/service-config.v1.schema.json` mit den fünf Bestandteilen Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten, Tarifverknüpfung (Inhalt in WP-M1-20/-25).
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** UI-17 Basis, Revisionsliste je Scope mit Ersteller, Zeitpunkt, Wirksamkeit.
- **Entscheidungen:** keine.
- **Offene Punkte:** Auflösung von Bediengebiets- und Tarifbezug erst mit M2 (`service_area`, `tariff_revision`); Nachweis in M1 am Testobjekt (WP-M1-20, AK 7).

## Tests

- **Positiv:** Änderung der Dienstkonfiguration erzeugt neue Revision mit `created_by`, Zeitstempel, `effective_from`/`effective_to`.
- **Negativ:** Änderung ohne Grund; ohne `effective_from`; `effective_to` vor `effective_from`; Rolle ohne Recht; fremder Scope; veralteter ETag.
- **Konkurrierend:** zwei parallele Entwürfe derselben Revisionsnummer → genau einer gelingt.
- **Referenztests:** E2E-096, E2E-098, PH-AT-018 (Konfigurationsteil).

Testfälle: E2E-096; E2E-098; PH-AT-018; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::aenderung_erzeugt_revision_mit_ersteller_zeitstempel_wirksamkeit

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
