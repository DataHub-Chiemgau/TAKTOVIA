# Konfiguration: Parameterkatalog, Systemgrenzen und Revisionen (WP-M1-19, WP-M1-20)

Bau-Detailvertrag zu `LH-I.7-CFG-002`, `LH-I.7-CFG-003`, `LH-I.7-CFG-004`, `LH-6.1-SYS-002`. Die Entscheidungen sind Festlegungen für den Bau; wo sie eine offene
Frage des Pflichtenhefts berühren, ist sie genannt. Code: Modul `config` (`backend/modules/config`), Migrationen `V1190`, `V1200`, API `api/registry.yaml`.

## Parameterkatalog und `policy_schema` (WP-M1-19)

- **Quelle:** je Parameter ein JSON-Schema (Draft 2020-12) unter [schemas/](schemas/) (`<parametername>.v1.schema.json`, die Dienstkonfiguration als `service-config.v1.schema.json`).
  Erweiterungsschlüssel: `x-unit` (Einheit), `x-levels` (zulässige Ebenen), `x-security-class` (`STANDARD` oder `SAFETY_INVARIANT`), `x-limit-source` (`KATALOG` oder
  `PLANUNGSANNAHME`), `x-catalog` (Quelle in `kataloge.csv`). Die Tabelle `policy_schema` enthält dieselben Schemas; ihre abfragbaren Spalten (Typ, Einheit, Min, Max, feste
  Werte, Ebenen, Sicherheitsklasse) sind aus dem JSON **erzeugt** (Generated Columns), es gibt keine zweite Quelle. Ein Test vergleicht Datenbank und Dateien.
- **Schema-Version:** `policy_schema_version` (Version, Gültigkeit ab). Gültig ist die höchste Version, deren Beginn erreicht ist (`taktovia.current_policy_version(at)`). Freigegebene
  Versionen sind unveränderlich (Trigger, auch für den Migrator); eine Änderung ist eine neue Version. `config_revision.schema_version` verweist darauf (Fremdschlüssel).
- **OP-08 (Entscheidung für den Bau):** `policy_schema` und `policy_schema_version` sind globale, nicht personenbezogene Plattformkataloge **ohne `tenant_id`** (wie `currency`, `state_transition_rule`).
  Jeder Mandant liest dieselben Zeilen, die Runtime-Rolle hat nur `SELECT`; geändert wird nur per Migration. Ein expliziter Plattformmandant ist dafür nicht nötig. Gilt nur für
  diese Kataloge; Mandantendaten bleiben über `tenant_id` und RLS getrennt.
- **OP-03 (Entscheidung für den Bau):** Der Katalogbereich aus `kataloge.csv` gilt als systemisches Maximum (`x-limit-source: KATALOG`: `max_wait_min` 0..240, `max_detour_pct` 0..200,
  `min_booking_lead_min` 0..43200, `max_booking_horizon_days` 0..365). Wo der Katalog keinen Bereich nennt („Positiv", „Vertragsabhängig" usw.), steht eine technische Planungsannahme
  (`PLANUNGSANNAHME`; z. B. `pickup_window_min` 1..240, `no_show_grace_min` 1..120, `stop_walk_max_m` 0..5000, `stop_dwell_sec` 1..3600). Diese Zahlen sind **nicht fachlich freigegeben**;
  die Freigabe der Werte ist D-05. Parameter ohne Zielwert haben keinen Vorgabewert (`default` fehlt, nichts erfunden). Die Ebenen (`x-levels`) sind eine Annahme, bis WP-M1-21 die
  Präzedenz festlegt (OP-22): Parameter der Ebene „Dienst" sind auf Mandant, Region und Dienst setzbar, „Region/Transfer" auf Mandant und Region, „Mandant" nur auf Mandant.
- **Sicherheitsinvariante:** `vehicle_readiness_require_checks` ist `const true` (`SAFETY_INVARIANT`); `false` ergibt `SAFETY_INVARIANT_VIOLATED` („Sicherheitsprüfungen dürfen nicht abgeschaltet werden").
  Hinweis: Planungsannahme PA-07 (Abschaltung nur im Mandantenrahmen mit Zweitfreigabe) ist damit nicht umgesetzt; WP-M1-19 AK 4 verlangt die Ablehnung. Eine Lockerung wäre eine neue Schema-Version.
- **Wertform:** ein Wert ist ein JSON-Skalar oder die Hülle `{"value": …, "unit": "<Einheit>"}`; die Einheit muss zum Parameter passen (`UNIT_MISMATCH`), gespeichert wird der Skalar.
- **Prüfung zweifach:** zuerst in der Anwendung (`ParameterValidator`: alle Verletzungen als 422 mit `pointer` und `code`, nie mit Eingabewerten), dann in der Datenbank
  (`config_value_violations`, Trigger an `config_revision`; Name, Ebene, Typ, Bereich, feste Werte, IANA-Zone, Pflicht-/Zusatzfelder der ersten Objektebene). Ein Test vergleicht beide Seiten.
  Tiefere Formate (Muster, verschachtelte Aufzählungen) prüft nur die Anwendung; die Vollständigkeit der Dienstkonfiguration (`service_config` je Dienstrevision) ebenfalls.
- **API:** `GET /v1/config/parameters` (Scope `config:read`), keine schreibende Route. Neue Codes: `ViolationCode.UNIT_MISMATCH`, `SAFETY_INVARIANT_VIOLATED`.

## Revisionen (WP-M1-20)

- **Scope-Arten (OP-18):** `TENANT`, `REGION`, `DEPOT`, `SERVICE` (CHECK; `VEHICLE` folgt mit WP-M1-21). Für `TENANT` muss `scope_id` der Mandant des Kontexts sein (sonst 404).
- **Zusatzspalten:** `state` (`DRAFT`, `VALIDATED`, `APPROVED`, `PUBLISHED`), `reason` (Pflicht, 3 bis 1000 Zeichen), `created_at`, `row_version` (ETag). `SUPERSEDED` aus dem API-Schema wird nicht gespeichert:
  die Nachfolge ergibt sich aus Beginn und Nummer; eine publizierte Zeile ändert sich nie.
- **Zustandsautomat:** `DRAFT → VALIDATED → APPROVED → PUBLISHED`, `VALIDATED/APPROVED → DRAFT`; Regeln in `state_transition_rule` (`config_revision`), erzwungen durch `harden_state_column`. Die Übergänge löst WP-M1-22 aus.
- **Unveränderlichkeit:** Trigger `config_revision_guard`: publizierte Zeilen weder ändern noch löschen (auch nicht für Administration); Kennung, Scope, Nummer, Ersteller und Anlagezeitpunkt sind fest; jede Änderung erhöht `row_version` um 1.
  Die Runtime-Rolle hat kein `DELETE`.
- **Anlegen:** `created_by` aus dem Principal (`user:<subject>`/`service:<subject>`), nie aus der Anfrage (`additionalProperties: false`). Pflicht: `effective_from`, `reason`; `effective_to` (optional) nach `effective_from`.
  Revisionsnummer = höchste im Scope + 1; gleichzeitige Anlage derselben Nummer: genau eine gewinnt (Unique-Index), die andere erhält 409 `CONFLICT`. Zeile, Audit `config.revision.created` und Outbox
  `config.revision_created` in einer Transaktion. `reason` steht im Schema nicht in `required` (Kompatibilität gegenüber dem Ausgangsstand), wird aber serverseitig als Pflicht geprüft.
- **Ändern:** `PATCH /v1/config/revisions/{id}` mit `If-Match` = `row_version` (428/412), nur `DRAFT` (publiziert: 409 `IMMUTABLE_RECORD`, sonst 409 `INVALID_STATE_TRANSITION`), Zeile gesperrt (`FOR UPDATE`), Audit `config.revision.changed`.
  Beim Ändern gilt die jetzt gültige Schema-Version.
- **Änderungsprotokoll (CFG-003):** `change_log` je Revision: Benutzer, Zeitpunkt (`created_at`), Grund, Gültigkeitsbeginn und der Diff Altwert/Neuwert gegen die Vorgängerrevision (höchste **publizierte**
  Revision desselben Scopes mit kleinerer Nummer; ohne sie ist alles „hinzugefügt"). Der Diff wird berechnet, nicht gespeichert; Objekte werden bis zu den Blättern aufgelöst (JSON-Pointer).
- **As-of:** `GET /v1/config/revisions?scope_kind=…&scope_id=…&as_of=<RFC 3339 mit Offset>` liefert die zum Zeitpunkt wirksame **publizierte** Revision: Beginn ≤ T und (kein Ende oder Ende > T), bei mehreren der späteste Beginn,
  dann die höchste Nummer. Ohne wirksame Revision ist die Liste leer.
- **Dienstkonfiguration (SYS-002):** Parameter `service_config` auf Ebene `SERVICE` mit den fünf Bestandteilen `service_area_id` (Bediengebiet), `traffic_type` (`AREA_ON_DEMAND`, `LINE_ON_DEMAND`, `STATION_FEEDER`, PA-01),
  `operator_id` (Betreiber, Bezug auf `organization`), `operating_hours` (Wochentage, Von/Bis in Ortszeit), `tariff_id` (Tarifverknüpfung). Jede Dienstrevision trägt die vollständige Konfiguration. Die Auflösung der Bezüge
  (`service_area`, `tariff_revision`, `organization`) folgt mit M2 bzw. WP-M1-09; `service.config_revision_id` bindet WP-M1-09 (OP-35).
- **Nicht in diesem Stand:** Idempotenz-Replay für `POST` (der Header `Idempotency-Key` ist Pflicht und wird geprüft; die Wiederholungssperre liefert der Baustein WP-M1-05), Prüfung/Freigabe/Publikation/Rollback (WP-M1-22),
  Wirkungsanalyse (WP-M1-23), Präzedenzauflösung (WP-M1-21), Dienstbegrenzung von Revisionen über `service_scope` (OP-04, braucht die Dienstobjekte aus WP-M1-09).
