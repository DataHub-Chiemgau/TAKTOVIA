# LH-I.7-CFG-002 – Parameterkatalog, `policy_schema` und Systemgrenzen

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-19
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Alle betriebsrelevanten Einstellwerte besitzen technische Namen, Datentyp, gültigen Bereich, Einheit und Beschreibung.

**Originalabnahme (unverändert):** Eine ungültige negative Wartezeit wird vor Speicherung abgewiesen.

## Vertrag

- **Tabellen / DDL:** `policy_schema` (Kap. 04 §4.4 CFG/SEC/INT) mit Schema-Version, Parametername, Typ, Einheit, Min/Max, Default, erlaubter Ebene, Sicherheitsklasse; `config_revision.schema_version` (V1, `NOT NULL`) referenziert sie; `values_json` wird vor dem Speichern validiert, bei Verstoß entsteht keine Zeile. Globale Grenzen im Plattformmandanten (OP-08). Parameterkatalog nur über Migration/Plattformfreigabe änderbar.
- **API:** Parameterkatalog lesen (Name, Typ, Einheit, Min/Max, Beschreibung, erlaubte Ebenen) als Teil von `/v1/config/effective` bzw. eigene Katalogressource (Detailvertrag, mit dem Bau in `api/openapi.yaml`); Entwurf mit Wert außerhalb des Schemas: 422 `application/problem+json` mit Feldverletzung; unbekannter Parametername oder falscher Typ: 422; für jeden Mandanten lesbar.
- **Zustandsautomat:** keine. **JSON-Schema:** je Parameter (Typ, Einheit, Min/Max, Default, Ebene, Sicherheitsklasse) unter `schemas/<parametername>.v1.schema.json`. Ereignisse: keine.
- **Parameter und Kennzahlen:** Startmenge (Vorschlag des Plans, OP-03), Namen exakt wie `kataloge.csv`: `max_wait_min` (0..240, Minuten, Dienst), `max_detour_pct` (0..200, Prozent, Dienst), `min_booking_lead_min` (0..43200, Minuten, Dienst), `max_booking_horizon_days` (0..365, Tage, Dienst), `pickup_window_min` (Katalogbereich „Positiv", unscharf, OP-03), `arrival_grace_min`, `no_show_grace_min`, `stop_walk_max_m`, `advance_arrival_buffer_min`, `pooling_allowed`, `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`, `transfer_min_time_min`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `vehicle_readiness_require_checks` (Bool, nicht abschaltbar), `customer_notify_before_pickup_min`, `reporting_timezone`, `notification_quiet_hours`. CI-Abgleich der Namen mit `kataloge.csv`.
- **Oberfläche:** keine eigene (Anzeige über UI-17 Basis, WP-M1-20).
- **Entscheidungen:** D-05 (Zielwerte/Pilotprofil) – blockiert nur die Freigabe von Produktionswerten, nicht den Bau.
- **Offene Punkte:** OP-03 (Auswahl der P1-Parameter, Zahlenwerte der Systemmaxima, unscharfe Bereiche wie `pickup_window_min` „Positiv"; Regel für solche Einträge ist vor Schema-Anlage zu entscheiden), OP-08 (Lesen globaler Grenzen unter RLS). Beides blockiert nur die jeweils betroffenen Parameter, nicht `max_wait_min` (Bereich 0..240 im Katalog).

## Tests

- **Positiv:** gültige Werte aller Startparameter an den Bereichsgrenzen; jeder Parameter mit Name, Datentyp, Bereich, Einheit, Beschreibung abrufbar; Namen stimmen mit `kataloge.csv` überein.
- **Negativ:** `max_wait_min` = −5 wird vor Speicherung mit 422 und Feldverletzung abgewiesen, keine Zeile in `config_revision` (Abnahme); Wert über Maximum (240); falscher Typ; falsche Einheit; unbekannter Name; `vehicle_readiness_require_checks` = false; Mandant verändert Schema/Systemgrenze (403).
- **Konkurrierend (Sicherheit):** Schemaversion wird parallel zu einem Entwurf angehoben – Entwurf wird gegen die bei Publikation gültige Version erneut validiert.
- **Referenztests:** E2E-097.
- Testfälle: E2E-097; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::werte_ausserhalb_bereich_typ_einheit_unbekannter_name_422; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::mandant_kann_schema_und_systemgrenze_nicht_aendern_403; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::vehicle_readiness_require_checks_false_abgewiesen; geplant:db/tests/test_policy_schema.py::test_ungueltiger_wert_erzeugt_keine_zeile_in_config_revision; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::negative_wartezeit_wird_vor_speicherung_abgewiesen; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::parameternamen_stimmen_mit_katalog_ueberein; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegistryTest.kt::schemaversion_parallel_zu_entwurf_wird_neu_validiert

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Sicherheit: Systemgrenzen).
