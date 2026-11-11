# LH-I.7-CFG-005 – Präzedenzkette und wirksame Konfiguration

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-21
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Die Konfiguration kann globale, mandantenbezogene, regionale, dienstbezogene und fahrzeugspezifische Vorgaben unterscheiden.

**Originalabnahme:** Bei Konflikt zeigt die Oberfläche den wirksamen Wert und die Vorrangregel.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle; Lesezugriff auf `config_revision` über Scope-Arten global, Mandant, Region, Dienst, Fahrzeug (`scope_kind`, `scope_id`); Index `(tenant_id, scope_kind, scope_id, effective_from)`. Fahrzeug-Scope in M1 an Fixture-Fahrzeug (Tabelle `vehicle`, WP-M0-16). RLS bleibt wirksam; globale Ebene aus dem Plattformmandanten (OP-08).
- **API:** `GET /v1/config/effective` (Parameter, Kontext aus `region_id`/`service_id`/`vehicle_id`, optional Zeitpunkt `at` (der eingefrorene Ausgangsstand heißt den Parameter `at`, nicht `as_of`) → Wert, Einheit, Herkunftsebene, Herkunftsrevision, angewandte Vorrangregel, Wirksamkeit); Fahrzeug-Override für nicht freigegebenen Parameter: 422; fremder Mandantenkontext und Dienst außerhalb `service_scope`: 403/404 gemäß Fehlerkonvention; unbekannter Parameter 404/422; Zeitpunkt ohne wirksame Revision: definierte Antwort ohne Wert.
- **Zustandsautomat:** keiner (lesend).
- **JSON-Schema / Ereignisse:** Antwortschema `EffectiveValue` in `api/*.yaml`; keine Ereignisse. Cache-Schlüssel enthält die Policyrevision.
- **Parameter und Kennzahlen:** alle Parameter des Katalogs gemäß Spalte `ebene_original` in `kataloge.csv` (z. B. `max_wait_min`, Ebene Dienst); Regel „nur dafür freigegebene Parameter sind auf Fahrzeug-/Profilebene überschreibbar"; engere fachliche Limits werden validiert, nicht blind überschrieben (Kap. 01 §1.6). Globale Sicherheitsgrenze wird nie durch niedrigere Ebenen überschrieben.
- **Oberfläche:** UI-17 Basis, Konfigurationseditor: Name/Einheit, aktueller und vorgeschlagener Wert, Herkunft der wirksamen Regel (Ebene, Revision), Vorrangregel, Wirksamkeitsdatum; Zustände leer, Fehler, fehlende Rechte (Kap. 06 §6.8).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-22 (Zuordnung der fünf Ebenen aus CFG-005 zu den sechs Stufen aus Kap. 01 §1.6; Produkt-Ebene existiert nicht, erst M2; abweichende Ebenenbezeichnungen in `kataloge.csv`); OP-08; Vollabnahme der Fahrzeugebene erst mit WP-M3-03 (gepflegter Fahrzeugstamm). Kein Blocker für den M1-Bau.

## Tests

- **Positiv:** Auflösung auf jeder Ebene; bei widersprüchlichen Werten für `max_wait_min` auf Mandanten-, Regions- und Dienstebene liefern API und Oberfläche den wirksamen Wert samt Vorrangregel und Herkunftsrevision; Fixture aus WP-M1-09 (zwei Regionen mit je einem Dienst) liefert unterschiedliche Werte, Wechsel im selben Login; Auflösung zum Zeitpunkt T nutzt nur Revisionen, deren Intervall T enthält.
- **Negativ:** Override für nicht freigegebenen Parameter am Fahrzeug abgewiesen; Kontext eines fremden Mandanten; unbekannter Parameter; Zeitpunkt ohne wirksame Revision; Dienst außerhalb des eigenen `service_scope`; Dienstwert, der die globale Sicherheitsgrenze verletzt, wird nie als wirksam geliefert.
- **Oberfläche (Test):** Konfigurationseditor zeigt bei widersprüchlichen Werten wirksamen Wert, Herkunftsebene, Herkunftsrevision und Vorrangregel; Zustände leer, Fehler, fehlende Rechte.
- **Konkurrierend:** Publikation während laufender Auflösungen – jede Antwort ist konsistent zu genau einer Revision; Cache liefert nach Publikation nicht den abgelösten Wert.
- **Referenztests:** E2E-096.

Testfälle: E2E-096; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::konflikt_liefert_wirksamen_wert_mit_vorrangregel; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::aufloesung_auf_jeder_ebene; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::fahrzeug_override_fuer_nicht_freigegebenen_parameter_wird_abgewiesen; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::dienstwert_der_die_globale_sicherheitsgrenze_verletzt_wird_nie_wirksam; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::aufloesung_zum_zeitpunkt_t_beruecksichtigt_nur_revisionen_deren_intervall_t_enthaelt; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::zwei_regionen_je_ein_dienst_unterschiedliche_werte_ein_login; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::kontext_eines_fremden_mandanten_und_unbekannter_parameter; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::publikation_waehrend_laufender_aufloesungen_jede_antwort_konsistent_zu_genau_einer_revision; geplant:backend/modules/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::cache_liefert_nach_publikation_nicht_den_abgeloesten_wert; geplant:backend/app/src/test/kotlin/de/taktovia/app/config/EffectiveConfigApiTest.kt::konflikt_ueber_die_api_liefert_wert_herkunft_und_vorrangregel; geplant:backend/app/src/test/kotlin/de/taktovia/app/config/EffectiveConfigApiTest.kt::fuenf_ebenen_global_mandant_region_dienst_fahrzeug_werden_unterschieden; geplant:backend/app/src/test/kotlin/de/taktovia/app/config/EffectiveConfigApiTest.kt::fahrzeug_override_fuer_nicht_freigegebenen_parameter_wird_abgewiesen_422; geplant:backend/app/src/test/kotlin/de/taktovia/app/config/EffectiveConfigApiTest.kt::zwei_gebiete_ein_login_wechsel_zeigt_jeweils_die_regeln_des_gewaehlten_gebiets; geplant:backend/app/src/test/kotlin/de/taktovia/app/config/EffectiveConfigApiTest.kt::fehlerfaelle_fremder_mandant_unbekannter_parameter_rechte_und_formatfehler; geplant:db/tests/test_praezedenz.py::test_nicht_freigegebener_parameter_auf_fahrzeugebene_abgewiesen; geplant:web/operator/src/config/ConfigEditor.test.tsx::ui17_zeigt_wirksamen_wert_herkunft_und_vorrangregel

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
