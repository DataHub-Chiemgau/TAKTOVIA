-- WP-M3-34: Rohpositionen sind kurzlebige Daten (LK_STANDORT). Das Löschen nach der im Aufbewahrungsregister hinterlegten Frist braucht DELETE; Einfügen und Lesen bestehen seit
-- WP-M3-02, Ändern bleibt per Trigger `position_sample_immutable` verboten. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT DELETE ON taktovia.position_sample TO taktovia_runtime;
