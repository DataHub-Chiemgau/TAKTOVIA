-- WP-M2-44: ride_offer_walk (Fußweg-Snapshot je Angebot) wird angelegt und gelesen, nie geändert oder gelöscht. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT ON taktovia.ride_offer_walk TO taktovia_runtime;
