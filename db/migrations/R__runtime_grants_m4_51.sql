-- WP-M4-51: Nachfragetrichter. Lesefunktionen (laufen als Aufrufer, RLS gilt); keine neue Tabelle.
GRANT EXECUTE ON FUNCTION taktovia.funnel_counts(timestamptz, timestamptz, uuid, text) TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.funnel_reconciliation(timestamptz, timestamptz) TO taktovia_runtime;
