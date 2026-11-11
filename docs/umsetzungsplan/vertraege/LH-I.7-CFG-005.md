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
- **API:** `GET /v1/config/effective` (in `api/openapi.yaml` noch nicht vorhanden, mit diesem Vertrag zu ergänzen) (Parameter, Kontext, optional Zeitpunkt `as_of` → Wert, Einheit, Herkunftsebene, Herkunftsrevision, angewandte Vorrangregel, Wirksamkeit); Fahrzeug-Override für nicht freigegebenen Parameter: 422; fremder Mandantenkontext und Dienst außerhalb `service_scope`: 403/404 gemäß Fehlerkonvention; unbekannter Parameter 404/422; Zeitpunkt ohne wirksame Revision: definierte Antwort ohne Wert.
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

Testfälle: E2E-096; geplant:backend/config/src/test/kotlin/de/taktovia/config/EffectiveConfigResolverTest.kt::konflikt_liefert_wirksamen_wert_mit_vorrangregel; geplant:web/operator/src/config/ConfigEditor.test.tsx::ui17_zeigt_wirksamen_wert_herkunft_und_vorrangregel

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
