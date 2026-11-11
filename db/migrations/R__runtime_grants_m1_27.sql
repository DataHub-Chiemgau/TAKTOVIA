-- WP-M1-27: Rechte der Zusage-Testtabellen (Dateiname sortiert hinter R__runtime_grants.sql, siehe R__runtime_grants_m1_17.sql).
GRANT SELECT, INSERT, UPDATE ON taktovia.promise_test_aggregate TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.promise_test_snapshot TO taktovia_runtime;
