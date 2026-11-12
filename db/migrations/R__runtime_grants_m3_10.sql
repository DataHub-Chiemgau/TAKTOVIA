-- WP-M3-10: Prüfelemente eines Entwurfs werden beim Bearbeiten ersetzt; das Löschen ist nur möglich, solange die Vorlagenrevision ein Entwurf ist
-- (Trigger checklist_step_guard). Der Dateiname sortiert hinter R__runtime_grants.sql.
GRANT DELETE ON taktovia.checklist_step TO taktovia_runtime;
