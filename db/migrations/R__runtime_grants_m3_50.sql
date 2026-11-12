-- WP-M3-50: getrennter Bereich der personenbezogenen Unfallangaben. Die Runtime-Rolle sieht nur die Schlüsselspalten (tenant_id, id: Mandantenprüfungen und Zählung), NICHT Akte, Art, Inhalt
-- und Urheber; lesen und anhängen darf alles nur der Modulzugang `taktovia_fields` (eigene Anmeldung, siehe db/README.md „Modulzugang für sensible Felder“, Muster wie `passenger_private`).
-- Mandantentrennung bleibt RLS (FORCE) mit transaktionslokalem Tenantkontext. Dateiname sortiert hinter R__runtime_grants.sql (REVOKE ALL dort).
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'taktovia_fields') THEN
    RAISE EXCEPTION 'Rolle taktovia_fields fehlt: zuerst "db.py bootstrap" (WP-M1-31) ausführen';
  END IF;
END $$;
REVOKE ALL ON taktovia.defect_personal_detail FROM taktovia_runtime, taktovia_fields;
GRANT SELECT (tenant_id, id) ON taktovia.defect_personal_detail TO taktovia_runtime;
GRANT SELECT (tenant_id, id, defect_id, detail_kind, content, recorded_by, revision, created_at) ON taktovia.defect_personal_detail TO taktovia_fields;
GRANT INSERT (tenant_id, id, defect_id, detail_kind, content, recorded_by) ON taktovia.defect_personal_detail TO taktovia_fields;
