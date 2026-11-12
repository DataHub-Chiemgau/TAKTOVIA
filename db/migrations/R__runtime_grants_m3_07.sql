-- WP-M3-07: die gesperrte Intervallprüfung der Schichtzuweisung (V3070) läuft im Trigger mit den Rechten des schreibenden Aufrufers; sie braucht EXECUTE auf ihre beiden
-- Hilfsfunktionen. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL). Das Modul staff-time schreibt staff_shift auch über seine
-- eigene Anmeldung (WP-M4-64, taktovia_mod_stafftime), die ebenfalls ausführen können muss.
GRANT EXECUTE ON FUNCTION taktovia.staff_shift_lock(uuid, uuid, uuid), taktovia.staff_shift_clash(uuid, uuid, uuid, uuid, timestamptz, timestamptz) TO taktovia_runtime;
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'taktovia_mod_stafftime') THEN
    GRANT EXECUTE ON FUNCTION taktovia.staff_shift_lock(uuid, uuid, uuid), taktovia.staff_shift_clash(uuid, uuid, uuid, uuid, timestamptz, timestamptz) TO taktovia_mod_stafftime;
  END IF;
END $$;
