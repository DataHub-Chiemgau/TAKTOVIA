-- WP-M4-60: Live-Dashboard. Lesefunktion (laeuft als Aufrufer, RLS gilt); keine neue Tabelle. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT EXECUTE ON FUNCTION taktovia.live_dashboard(timestamptz, uuid[], integer, integer) TO taktovia_runtime;
