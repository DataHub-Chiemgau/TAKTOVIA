-- WP-M1-31 / LH-6.18-SEC-005: Feldschutz für Mobilitäts- und Unterstützungsmerkmale (Kap. 04 §4.6).
-- Dateiname sortiert hinter R__runtime_grants.sql (siehe db/README.md): dort fehlt passenger_private absichtlich in der Standardvergabe.
--
-- 1. Allgemeine Runtime-Rolle: Spaltenrechte ohne die Ciphertext-Spalten. `SELECT *` und jeder Zugriff auf
--    contact_ciphertext/support_ciphertext scheitern mit "permission denied".
-- 2. Modulzugang `taktovia_fields` (eigene Anmeldung, nur der Feldschutzbaustein): liest und schreibt ausschließlich Schlüsselspalten
--    und die beiden Ciphertext-Spalten. Mandantentrennung bleibt RLS (FORCE) mit transaktionslokalem Tenantkontext.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'taktovia_fields') THEN
    RAISE EXCEPTION 'Rolle taktovia_fields fehlt: zuerst "db.py bootstrap" (WP-M1-31) ausführen';
  END IF;
END $$;

REVOKE ALL ON taktovia.passenger_private FROM taktovia_runtime, taktovia_fields;
GRANT SELECT (tenant_id, id, subject_token, retention_until) ON taktovia.passenger_private TO taktovia_runtime;
GRANT INSERT (tenant_id, id, subject_token, retention_until) ON taktovia.passenger_private TO taktovia_runtime;
GRANT UPDATE (subject_token, retention_until) ON taktovia.passenger_private TO taktovia_runtime;

GRANT USAGE ON SCHEMA taktovia TO taktovia_fields;
GRANT EXECUTE ON FUNCTION taktovia.current_tenant() TO taktovia_fields;
GRANT SELECT (tenant_id, id, contact_ciphertext, support_ciphertext) ON taktovia.passenger_private TO taktovia_fields;
GRANT UPDATE (contact_ciphertext, support_ciphertext) ON taktovia.passenger_private TO taktovia_fields;
-- Lesender Zugriff auf Mitgliedschaften und Audit gehört nicht zum Modulzugang: Prüfung und Audit laufen in der Transaktion der Runtime-Rolle.
