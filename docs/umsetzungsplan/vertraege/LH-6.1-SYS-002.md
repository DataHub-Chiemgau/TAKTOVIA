# LH-6.1-SYS-002 – Versionierte Dienstkonfiguration

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-20
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Jeder Dienst besitzt versionierte Konfigurationen für Bediengebiet, Verkehrstyp, Betreiber, Betriebszeiten und Tarifverknüpfung.

**Originalabnahme (unverändert):** Eine Konfigurationsänderung hat Ersteller, Zeitstempel und dokumentierte Wirksamkeit.

## Vertrag

- **Tabellen / DDL:** `config_revision` (`scope_kind` für Dienst (Wert im Bau festzulegen, OP-18), `scope_id` = `service.id`, `revision`, `schema_version`, `values_json`, `effective_from`, `effective_to`, `created_by`, `approved_by`, `published_at`; Kern-DDL hat kein `created_at`); `service.config_revision_id` verweist auf die gebundene Revision. Migration ergänzt `created_at` (der Zeitstempel der Änderung), Statusspalte, Änderungsgrund, Unveränderlichkeits-Trigger für publizierte Zeilen (analog `reject_mutation()`) und CHECK der `scope_kind`-Werte (WP-M1-20, OP-18). RLS/Mandantenlokalität wie WP-M1-01. Invarianten: `UNIQUE(tenant_id,scope_kind,scope_id,revision)`, `effective_to > effective_from` (bereits im Kern-DDL).
- **API:** `POST /v1/config/revisions` (Entwurf, `Idempotency-Key`), `GET /v1/config/revisions` (Liste je Scope), `GET /v1/config/revisions/{id}` mit ETag und As-of-Parameter; Änderung eines Entwurfs mit `If-Match` (veraltet 412 `REVISION_MISMATCH`, fehlend 428 `PRECONDITION_REQUIRED`). Diese Pfade stehen noch nicht in `api/config.yaml` (dort nur `/v1/config/revisions/{revision_id}/publish`) und sind im Bau zu ergänzen. Pflichtfelder bei Anlage: Ersteller (`created_by` aus Principal, nicht aus dem Request), `effective_from`, Grund. Ohne sie 422 `application/problem+json`. Fremder Scope/fremder Mandant: 404 `NOT_FOUND`; Rolle ohne Recht: 403. Anlegen/Ändern erzeugt Auditeintrag (WP-M1-46) und Outbox `config.revision_created` (Name Vorschlag, OP-33).
- **Zustandsautomat:** Revision: Entwurf → geprüft → publiziert (Publikation WP-M1-22), publizierte Revision unveränderlich.
- **JSON-Schema:** `schemas/service-config.v1.schema.json` mit den fünf Bestandteilen Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten, Tarifverknüpfung (Inhalt in WP-M1-20/-25); alle fünf als Bestandteile derselben Revision (`values_json`), Betreiber als Bezug auf `organization`. Änderungsprotokoll je Revision: Ersteller, Zeitpunkt, Altwert, Neuwert, Grund, Gültigkeitsbeginn (Diff gegen Vorgängerrevision).
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** UI-17 Basis, Revisionsliste je Scope mit Ersteller, Zeitpunkt, Wirksamkeit, Diff Alt/Neu, Grund; Zustände leer, Fehler (422 je Feld, 412-Konflikt), fehlende Rechte; jede Ansicht zeigt die verwendete Revision (PH-AT-018).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-18 (CHECK-Werte `scope_kind`, Wert für Dienst); OP-35 (`service.config_revision_id` Pflichtfeld: Entwurf oder publiziert, Fortschreibung bei Publikation; Reihenfolge Revision vor Dienst); Auflösung von Bediengebiets- und Tarifbezug erst mit M2 (`service_area`, `tariff_revision`); Nachweis in M1 am Testobjekt (WP-M1-20, AK 7).

## Tests

- **Positiv:** Änderung der Dienstkonfiguration erzeugt neue Revision mit `created_by`, Zeitstempel (`created_at`), `effective_from`/`effective_to`; die Revision enthält alle fünf Bestandteile (Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten, Tarifverknüpfung) und ist mit `service.config_revision_id` bindbar; Änderungsprotokoll zeigt Altwert/Neuwert/Grund; ältere Revision bleibt lesbar (As-of).
- **Negativ:** Änderung ohne Grund; ohne `effective_from`; `effective_to` vor `effective_from` (CHECK und API); im Request mitgelieferter fremder `created_by` wird ignoriert; Schema-Verstoß (fehlender oder unbekannter Bestandteil der Dienstkonfiguration); UPDATE/DELETE einer publizierten Revision per API und per SQL unter der Runtime-Rolle scheitert; Rolle ohne Recht (403); fremder Scope/Mandant (404); veralteter ETag (412), fehlender ETag (428).
- **Konkurrierend:** zwei parallele Entwürfe derselben Revisionsnummer → genau einer gelingt (`UNIQUE(tenant_id,scope_kind,scope_id,revision)`); gleichzeitige Änderung desselben Entwurfs mit gleichem ETag → genau eine gewinnt (412).
- **Referenztests:** E2E-096, E2E-098, PH-AT-018 (Konfigurationsteil).

Testfälle: E2E-096; E2E-098; PH-AT-018; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::aenderung_erzeugt_revision_mit_ersteller_zeitstempel_wirksamkeit; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::aenderung_ohne_grund_oder_wirksamkeit_wird_mit_422_abgewiesen; geplant:backend/config/src/test/kotlin/de/taktovia/config/ConfigRevisionTest.kt::parallele_entwuerfe_gleiche_revisionsnummer_genau_einer; geplant:db/tests/test_config_revision.py::test_publizierte_revision_unveraenderlich_unter_runtime_rolle

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
