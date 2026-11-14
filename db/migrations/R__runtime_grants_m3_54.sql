-- WP-M3-54: Störungsablauf. outage_booking: Zustandsführung durch das Modul booking-dispatch (kein DELETE, Trigger outage_booking_guard); outage_proposal: nur anhängen. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.outage_booking TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.outage_proposal TO taktovia_runtime;
