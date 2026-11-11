# LH-I.7-CFG-004 – Parameterkatalog, `policy_schema` und Systemgrenzen

- **Verantwortlicher:** Rolle Plattform (Modul `config`)
- **Arbeitspaket:** WP-M1-19
- **Status:** Bereit
- **Phase / Priorität:** P1 / MUSS

## Originaltext (unverändert)

> Sicherheits- und Gesetzesgrenzen dürfen durch Mandantenkonfiguration nicht überschrieben werden.

**Originalabnahme:** Ein Parameter oberhalb des systemischen Sicherheitsmaximums wird abgelehnt.

## Vertrag

- **Tabellen / DDL:** neue Tabelle `policy_schema` (Kap. 04 §4.4 CFG/SEC/INT) mit Parametername, Schema-Version, JSON-Schema (Typ, Einheit, Min/Max, Default, erlaubte Ebene, Sicherheitsklasse) und Gültigkeit; Migration unter `db/migrations/` mit der nächsten freien Versionsnummer (V1 bis V3 sind vergeben, also ab V4). `config_revision.schema_version` (bereits in V1 vorhanden, NOT NULL) referenziert die Version. Globale Sicherheitsgrenzen liegen im Plattformmandanten (Kap. 02 §1, OP-08). RLS: für jeden Mandanten lesbar, schreibbar nur über Migration/Plattformfreigabe; die Runtime-Rolle hat keine Schreibrechte auf `policy_schema` (`R__runtime_grants.sql`).
- **API:** Parameterkatalog lesbar (Name, Typ, Einheit, Min/Max, Beschreibung, erlaubte Ebenen) als Teil von `/v1/config/effective` bzw. eigener Katalogressource (`api/*.yaml`, Detailvertrag beim Bau). Speichern/Entwurf eines Werts außerhalb Schema oder über Sicherheitsmaximum: 422 `application/problem+json` mit Feldverletzung; es entsteht keine Zeile in `config_revision`. Unbekannter Parametername oder falscher Typ: 422.
- **Zustandsautomat:** keiner eigener; Validierung greift vor dem Speichern des Entwurfs und erneut bei Publikation (Revision-Zustände siehe LH-6.1-SYS-002).
- **JSON-Schema / Ereignisse:** JSON-Schema je Parameter der Startmenge (Ablageort `schemas/<parameter>.v1.schema.json` ist Vorschlag des Autors, im Detailvertrag beim Bau zu bestätigen) der Startmenge; keine neuen Ereignisse. Der CI-Abgleich liest die Parameternamen aus `docs/pflichtenheft/kataloge.csv` (Spalte `name_original`).
- **Parameter und Kennzahlen:** Startmenge (Vorschlag des Plans, OP-03) aus `kataloge.csv`: `max_wait_min` (0..240), `max_detour_pct` (0..200), `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `arrival_grace_min`, `no_show_grace_min`, `stop_walk_max_m`, `advance_arrival_buffer_min`, `pooling_allowed`, `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`, `transfer_min_time_min`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `vehicle_readiness_require_checks` (Sicherheitsinvariante: false wird abgelehnt), `customer_notify_before_pickup_min`, `reporting_timezone`, `notification_quiet_hours`. Namen exakt wie `kataloge.csv` (CI-Abgleich).
- **Oberfläche:** UI-17 Basis zeigt Parametername, Einheit, Bereich und Fehlermeldung der Feldverletzung („Sicherheitsprüfungen dürfen nicht abgeschaltet werden"); Zustände Fehler und fehlende Rechte.
- **Entscheidungen:** D-05 (Zielwerte/Pilotprofil) blockiert nur die Freigabe von Produktionswerten, nicht den Bau.
- **Offene Punkte:** OP-03 (Zahlenwerte der systemischen Sicherheitsmaxima nicht beziffert; Katalogbereiche teils unscharf, z. B. `pickup_window_min` „Positiv"; Vorgehen: Katalogbereich aus `kataloge.csv` gilt als Maximum, Regel für unscharfe Bereiche im Detailvertrag beim Bau festzulegen, kein Blocker für die Prüfmechanik); OP-08 (Lesezugriff auf Plattformmandant unter RLS nicht spezifiziert, vor dem Bau der RLS-Policy zu klären); Schemas der übrigen Parameter erst mit M3, M4, M6, M8.

## Tests

- **Positiv:** gültige Werte aller Startparameter an Bereichsgrenzen werden akzeptiert; Katalog ist für jeden Mandanten abrufbar und der Name jedes Parameters stimmt mit `kataloge.csv` überein.
- **Negativ:** `max_wait_min` = 241 (über Maximum, auch durch Mandantenadministrator) und −5 abgelehnt; `vehicle_readiness_require_checks` = false abgelehnt; falscher Typ, falsche Einheit, unbekannter Name abgelehnt; Mandant versucht `policy_schema` zu ändern (API und SQL unter Runtime-Rolle) – verweigert; kein Schreiben in `config_revision` bei Ablehnung.
- **Konkurrierend:** Schema-Version wird parallel zu einem Entwurf angehoben – der Entwurf wird bei Publikation gegen die dann gültige Version erneut validiert.
- **Referenztests:** E2E-097.

Testfälle: E2E-097; geplant:backend/config/src/test/kotlin/de/taktovia/config/ParameterRegisterTest.kt::wert_ueber_sicherheitsmaximum_wird_abgelehnt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
