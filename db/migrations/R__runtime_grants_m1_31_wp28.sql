-- WP-M1-28: Rechte für Fahrgastregistrierung. Der Dateiname sortiert hinter R__runtime_grants_m1_31.sql (diese vergibt für passenger_private die
-- Spaltenrechte nach REVOKE ALL), siehe db/README.md. Die Ciphertext-Spalten bleiben dem Modulzugang taktovia_fields vorbehalten.
GRANT SELECT (idp_issuer, idp_subject, status, locale, revision, created_at, updated_at) ON taktovia.passenger_private TO taktovia_runtime;
GRANT INSERT (idp_issuer, idp_subject, status, locale) ON taktovia.passenger_private TO taktovia_runtime;
GRANT UPDATE (idp_issuer, idp_subject, status, locale, updated_at) ON taktovia.passenger_private TO taktovia_runtime;
-- Der Modulzugang liest Revision und Status nicht; er kennt nur Schlüssel- und Ciphertext-Spalten.
GRANT SELECT, INSERT, UPDATE ON taktovia.passenger_contact TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.contact_verification TO taktovia_runtime;
