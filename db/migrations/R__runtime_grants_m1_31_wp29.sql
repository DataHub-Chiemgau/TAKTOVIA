-- WP-M1-29: Rechte für Fahrgastpräferenzen (Dateiname sortiert hinter R__runtime_grants_m1_31.sql, siehe db/README.md). Kein UPDATE: die Menge wird ersetzt.
GRANT SELECT, INSERT, DELETE ON taktovia.passenger_preference TO taktovia_runtime;
