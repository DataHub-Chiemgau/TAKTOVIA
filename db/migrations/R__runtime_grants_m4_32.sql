-- WP-M4-32: Dublettentreffer lesen, anlegen und entscheiden (UPDATE nur offener Treffer, Trigger begrenzt; kein DELETE), Evidenzverknüpfungen nur lesen und anlegen (append-only).
-- Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.duplicate_candidate TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.evidence_link TO taktovia_runtime;
