-- WP-M4-58: Berichtsfilter. Lesefunktionen (laufen als Aufrufer, RLS gilt); keine neue Tabelle. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT EXECUTE ON FUNCTION taktovia.report_hour(timestamptz, text) TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.funnel_counts_filtered(timestamptz, timestamptz, uuid, text, uuid, uuid, uuid, integer, text, uuid[]) TO taktovia_runtime;
