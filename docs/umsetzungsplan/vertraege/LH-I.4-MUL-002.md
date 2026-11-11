# LH-I.4-MUL-002 – Organisationsstruktur: Regionen, Betriebshöfe, Dienste, Kostenstellen

- **Verantwortlicher:** Rolle Plattform (Module `identity`/`service-geo`)
- **Arbeitspaket:** WP-M1-09
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Mandanten können mehrere Betriebshöfe, Regionen, Betriebsdienste und Kostenstellen verwalten.

**Originalabnahme (unverändert):** Ein Unternehmen betreibt zwei geografisch getrennte Gebiete mit eigenständigen Regeln innerhalb eines Logins.

## Vertrag

- **Tabellen / DDL:** vorhanden: `service` (`tenant_id,id,operator_id,name,timezone,config_revision_id`; `config_revision_id` Pflicht, FK auf `config_revision`) und `cost_object` (`kind='COST_CENTER'`, `UNIQUE(tenant_id,kind,business_ref)`, FK `legal_entity`). Neu per Flyway-Migration: Tabellen für Region und Betriebshof mit PK `(tenant_id,id)`, zusammengesetzten FKs, RLS (`tenant_isolation`), Revisionsspalte; Zuordnung Dienst → Region (Spalte oder Zuordnungstabelle, im Bau festzulegen); Betriebshof und Kostenstelle einem Gebiet/Dienst zuordenbar. Die Anlage eines Dienstes erzeugt seine erste Konfigurationsrevision über WP-M1-20 (OP-35). `service.timezone` ist eine gültige IANA-Zone.
- **API:** `/v1/services` (Kopfverwaltung); Verwaltungsendpunkte Region, Betriebshof, Kostenstelle als Detailvertrag (OpenAPI in `api/` im Bau zu ergänzen); `If-Match` für Änderungen (428/412), 404 für nicht sichtbare Objekte, 403 bei fehlendem Funktionsscope; Anlage/Änderung nur für berechtigte Rollen, auditiert.
- **Zustandsautomat / Schema / Ereignisse:** keine. **Parameter und Kennzahlen:** Konfigurationsparameter wie `max_wait_min` je Region/Dienst über Konfigurationsrevisionen (Auflösung in WP-M1-21, Kriterium 6, am selben Fixture).
- **Oberfläche:** UI-17 Basis – Strukturbaum Mandant → Region → Dienst; Kontextzeile „Mandant/Region/Dienst“ (Kap. 06 §6.1); Zustände leer, Fehler, fehlendes Recht.
- **Abgrenzung des Nachweises:** M1 weist zwei Gebiete als Stammobjekte Region und Dienst mit eigenen Konfigurationsrevisionen und einem Login nach; die geografische Abgrenzung über `service_area` und `stop_revision` wird mit WP-M2-04 und WP-M2-05 nachgewiesen (Vollabnahme). Dienstplan, Kostenrechnung (M4) und Betriebshof-Inventar (M7) nicht im Umfang.
- **Entscheidungen:** D-05 (Pilotbetreiber/Regionen für Fixtures) – blockiert den Bau nicht.
- **Offene Punkte:** OP-07 – Tabellen für Region und Betriebshof sind im Pflichtenheft nicht benannt (Entwurf im Bau, Review durch Architektur); Detailentscheidung, ob Kostenstellen in M1 nur als Stammobjekt `cost_object` oder mit Rechtsträgerlogik geführt werden (Finanzlogik ist M4; Vorgabe hier: nur Stammobjekt, `legal_entity_id` Pflicht wegen V1); OP-35 (Dienstanlage und Konfigurationsrevision); Region ↔ Dienst-Zuordnung (Zusammenspiel mit WP-M1-08).

## Tests

- **Positiv:** Mandant mit zwei Regionen mit je einem Dienst, je eigene Konfigurationsrevision mit unterschiedlichem `max_wait_min`; ein Nutzer mit Mitgliedschaft für beide Dienste arbeitet im selben Login in beiden; mehrere Betriebshöfe und Kostenstellen je Mandant anlegbar und einem Gebiet/Dienst zugeordnet.
- **Negativ:** Dienst einer Region eines fremden Mandanten zuordnen scheitert (FK/404); doppelte `business_ref` je `kind` wird abgewiesen; Anlage durch Rolle ohne Recht (403); ungültige IANA-Zone in `service.timezone` (422); Änderung ohne `If-Match` (428) bzw. veraltete Revision (412); gleichnamige Kostenstellen in Mandant A und B sind getrennte Objekte und aus dem jeweils anderen Mandanten nicht sichtbar.
- **Zusätzlich Datentrennung:** Region, Betriebshof und Dienst des Mandanten B sind aus A weder les- noch referenzierbar (404).
- **Konkurrierend:** paralleles Anlegen identischer Kostenstelle – genau ein Erfolg (`UNIQUE(tenant_id,kind,business_ref)`); zwei parallele Änderungen derselben Region-Revision – eine erhält 412.
- **Referenztests:** keine Katalog-ID im WP genannt; Fixture dient WP-M1-21 Kriterium 6.
- Testfälle: geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/OrganizationStructureTest.kt::zwei_regionen_eigene_regeln_ein_login; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/OrganizationStructureTest.kt::dienst_in_fremdem_mandanten_wird_abgewiesen; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostCenterAdminTest.kt::paralleles_anlegen_gleicher_kostenstelle_genau_ein_erfolg; geplant:db/tests/test_organisationsstruktur.py::test_region_betriebshof_tabellen_haben_tenant_pk_und_rls; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostCenterAdminTest.kt::mehrere_kostenstellen_je_mandant_zuordenbar; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostCenterAdminTest.kt::gleichnamige_kostenstellen_in_a_und_b_sind_getrennt; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/OrganizationStructureTest.kt::ungueltige_iana_zone_ergibt_422; geplant:backend/app/src/test/kotlin/de/taktovia/app/identity/OrganizationStructureApiTest.kt::anlage_ohne_recht_403_und_aenderung_ohne_if_match_428_veraltet_412; geplant:backend/modules/service-geo/src/test/kotlin/de/taktovia/servicegeo/OrganizationStructureTest.kt::zwei_parallele_aenderungen_derselben_region_eine_412; geplant:backend/modules/documents-finance/src/test/kotlin/de/taktovia/documentsfinance/CostCenterAdminTest.kt::doppelte_business_ref_je_kind_wird_abgewiesen_und_revisionen; geplant:web/operator/src/organisation/OrganisationsstrukturTest.test.tsx::strukturbaum_mandant_region_dienst_zeigt_leer_fehler_und_rechtezustand

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
