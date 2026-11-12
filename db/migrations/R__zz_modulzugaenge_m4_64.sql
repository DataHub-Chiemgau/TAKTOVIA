-- repeat: always
-- WP-M4-64: Modulzugänge der M4-Module (staff-time, documents-finance, analytics). Jede Anmeldung darf nur die Tabellen ihres Moduls
-- (taktovia_meta.table_owner, gespiegelt aus db/table_owners.d) mit denselben DML-Rechten wie die Runtime-Rolle benutzen und sonst nichts;
-- RLS (FORCE, Tenantkontext je Transaktion) gilt unverändert. Der Dateiname sortiert hinter allen R__runtime_grants*.sql; die Zeile oben lässt die
-- Datei bei jedem migrate erneut laufen, damit später hinzukommende Tabellen eines Moduls ohne Änderung dieser Datei berechtigt werden.
DO $$
DECLARE
  r record;
  p text;
  roles text[] := ARRAY['taktovia_mod_stafftime', 'taktovia_mod_docsfin', 'taktovia_mod_analytics'];
  ro text;
BEGIN
  FOREACH ro IN ARRAY roles LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ro) THEN
      RAISE EXCEPTION 'Rolle % fehlt: zuerst "db.py bootstrap" (WP-M4-64) ausführen', ro;
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA taktovia FROM %I', ro);
    EXECUTE format('GRANT USAGE ON SCHEMA taktovia TO %I', ro);
    EXECUTE format('GRANT EXECUTE ON FUNCTION taktovia.current_tenant() TO %I', ro);
  END LOOP;
  FOR r IN
    SELECT o.tabelle,
           CASE o.modul WHEN 'staff-time' THEN 'taktovia_mod_stafftime' WHEN 'documents-finance' THEN 'taktovia_mod_docsfin' WHEN 'analytics' THEN 'taktovia_mod_analytics' END AS rolle
      FROM taktovia_meta.table_owner o
     WHERE o.modul IN ('staff-time', 'documents-finance', 'analytics') AND to_regclass('taktovia.' || quote_ident(o.tabelle)) IS NOT NULL
  LOOP
    FOREACH p IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE'] LOOP
      IF has_table_privilege('taktovia_runtime', format('taktovia.%I', r.tabelle), p) THEN
        EXECUTE format('GRANT %s ON taktovia.%I TO %I', p, r.tabelle, r.rolle);
      END IF;
    END LOOP;
  END LOOP;
END $$;
