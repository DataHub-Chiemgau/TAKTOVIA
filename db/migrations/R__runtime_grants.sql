-- WP-M0-15: wiederholbare Grant-Migration. Rollenmatrix siehe db/role_matrix.csv.
-- Wird bei jeder Änderung dieser Datei erneut ausgeführt. Läuft als Migrator (Eigentümer).
REVOKE ALL ON SCHEMA taktovia FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA taktovia FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA taktovia FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA taktovia REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA taktovia REVOKE ALL ON FUNCTIONS FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

REVOKE ALL ON ALL TABLES IN SCHEMA taktovia FROM taktovia_runtime;
GRANT USAGE ON SCHEMA taktovia TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.current_tenant() TO taktovia_runtime;

-- Standard: SELECT, INSERT, UPDATE
GRANT SELECT, INSERT, UPDATE ON
  legal_entity, organization, membership, config_revision, service, service_area, stop_revision,
  cooperation_grant, passenger_private, travel_attempt, journey, journey_leg, operation, saga_step,
  vehicle, staff_shift, resource_release, vehicle_run, capacity_hold, booking, payment_intent,
  expense_document, cost_object, allocation_set, allocation_line, fiscal_period, journal, journal_line,
  settlement_line, idempotency_record, outbox, inbox
TO taktovia_runtime;
-- Unveränderlich (zusätzlich per Trigger): nur lesen und anhängen
GRANT SELECT, INSERT ON economic_event, audit_event, partner_contract_version, fare_quote TO taktovia_runtime;
-- Global bzw. Plattformverwaltung (Befund B-02): Mandantenstamm und Währungen ändert die Runtime nicht; nur lesen
GRANT SELECT ON currency, tenant TO taktovia_runtime;
-- Löschen nur, wo fachlich vorgesehen: Aufräumen und Entwürfe (gebuchte Journale sperrt der Trigger)
GRANT DELETE ON idempotency_record, inbox, outbox, journal, journal_line TO taktovia_runtime;
