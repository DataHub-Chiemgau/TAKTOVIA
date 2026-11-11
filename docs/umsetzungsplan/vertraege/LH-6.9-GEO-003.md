# LH-6.9-GEO-003 – Start-/Zielzulässigkeit nach Zonen und Zielpunkten

- **Verantwortlicher:** Rolle Fachmodul `service-geo`
- **Arbeitspaket:** WP-M2-06
- **Status:** Vertrag in Arbeit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Start- und Zielzulässigkeit kann abhängig von getrennten Zonen und Zielpunkten geprüft werden.

**Originalabnahme (unverändert):** Testrelation mit nicht erlaubtem Ziel wird abgelehnt.

## Vertrag

- **Tabellen / DDL:** neue Migration (im Pflichtenheft und Kern-DDL nicht als Tabelle benannt, OP-10): Zonenrolle (`START`, `ZIEL`, `BEIDES`) an `service_area` oder eigene Zonentabelle, und Menge erlaubter Zielpunkte mit Bezug auf `stop_revision.stop_id`; beide mit `tenant_id`, RLS und `valid_from`/`valid_to` (links geschlossen, rechts offen). Die Entscheidung Spalte vs. Tabelle trifft WP-M2-01; Bau erst danach.
- **API:** Erweiterung von `/v1/service-areas` um Zonenrolle und Relationsregeln (OpenAPI-Ergänzung); Fachoperation „Relation zulässig?“ mit Dienst, Startpunkt, Zielpunkt, Zeitpunkt; Ergebnis zulässig oder normierter Ablehnungsgrund aus dem Katalog von WP-M2-13 (Tabelle `rejection_reason`; Unterscheidung „außerhalb Gebiet“ gegenüber „Regelverbot“; die Codeliste selbst ist dort als Vorschlag in OP-14 offen, WP-M2-06 hängt dafür nicht formal von WP-M2-13 ab, die Zuordnung der Gründe zu den Fällen dieses Vertrags wird in WP-M2-01 festgelegt). Fehler/Revisionskonflikte wie GEO-001 (`application/problem+json`, 412 `REVISION_MISMATCH`, 428 `PRECONDITION_REQUIRED`); `/v1/service-areas` ist nur im Endpunktkatalog (Kap. 05 §5.7), nicht in `openapi.yaml`. Fremder Mandant → 404 `NOT_FOUND`, ohne Stammdatenrecht → 403, Änderungen auditiert (`audit_event`).
- **Zustandsautomat:** keiner. Wahrheitstabelle Start-/Zielzone/Zielpunkt → zulässig/Grund im Detailvertrag der Relationsregel (WP-M2-01), im Pflichtenheft nicht festgelegt; Arbeitsvorschlag, vom Detailvertrag zu bestätigen: Start in Startzone UND Ziel in Zielzone UND (Zielpunktmenge leer ODER Ziel in Menge) → zulässig; sonst Ablehnung mit Grund. Umgekehrte Richtung wird unabhängig nach eigenen Regeln bewertet.
- **JSON-Schema / Ereignisse:** Ereignis „Relationsregel wirksam“ (analog Gebietsrevision, `api/events/`).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-17: Zonenrolle und erlaubte Zielpunkte pflegen; Zustände leer/Fehler/konkurrierende Version; nur lesend ohne Stammdatenrecht.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-10 (Datenmodell für Zonen und Zielpunkte), Verhalten für Punkte exakt auf der Zonengrenze (Detailvertrag; Vorschlag wie GEO-001 `ST_Covers`), Semantik „Zielpunktmenge leer“ (Vorschlag: keine Einschränkung) und Behandlung eines Zielpunkts, der auf einen inaktiven Haltepunkt verweist (Vorschlag: nicht zulässig). Abgrenzung: Sperrbereiche (GEO-004) und Parallelfahrtenverbot (OPT-012) nicht im Umfang (OP-2). **Blockiert den Bau:** OP-10 muss in WP-M2-01 entschieden und DDL/OpenAPI hier nachgetragen werden; bis dahin bleibt der Vertrag in Arbeit.

## Tests

- **Positiv:** Start in Startzone und Ziel an erlaubtem Zielpunkt → zulässig; Zonenrolle `BEIDES` wirkt für Start und Ziel.
- **Negativ:** Testrelation mit nicht erlaubtem Ziel (Ziel außerhalb Zielzone bzw. nicht in der Zielpunktmenge) → abgelehnt mit Katalog-Grund; Start nicht erlaubt; Regel abgelaufen; Zielpunkt verweist auf inaktiven Haltepunkt; Regel eines fremden Mandanten nicht sichtbar (404 bzw. wirkungslos, in der Datenbank per RLS `tenant_isolation`); Änderung ohne Stammdatenrecht → 403; noch nicht gültige Regel (vor `valid_from`) wirkt nicht; die Ablehnung trägt genau einen Katalog-Grund (nicht „kein Gebiet“ und „Regelverbot“ vermischt); Umkehrrichtung wird nach eigenen Regeln bewertet (kann abgelehnt werden, obwohl Hinrichtung zulässig); Grenzfall Punkt auf Zonengrenze gemäß Detailvertrag.
- **Konkurrierend:** Regeländerung parallel zur Angebotsprüfung: die Prüfung verwendet vollständig die alte oder die neue wirksame Version.
- **Referenztests:** keine Katalog-ID im WP-Block genannt; AT-13 wird in M2 nur als Teilnachweis über diesen Plattformbaustein „Regelverbot“ geführt (WP-M2-48, OP-49).
- Testfälle: geplant:db/tests/test_geo_relationsregel.py::test_regel_fremder_mandant_unsichtbar_und_zielpunkt_referenz; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::nicht_erlaubtes_ziel_wird_abgelehnt; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::umkehrrichtung_wird_unabhaengig_bewertet; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::regelaenderung_parallel_zur_pruefung_alte_oder_neue_version; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::zulaessige_relation_wird_angenommen; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::start_ausserhalb_startzone_abgelehnt; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::abgelaufene_regel_wird_nicht_angewendet; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::zielpunkt_auf_inaktivem_haltepunkt_nicht_zulaessig; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleTest.kt::ablehnung_traegt_katalog_grund; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/RelationRuleApiTest.kt::fremder_mandant_404_ohne_recht_403_audit

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
