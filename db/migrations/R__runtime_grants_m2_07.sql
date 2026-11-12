-- WP-M2-07: Rechte für tariff_revision und tariff_rule. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Revision: anhängen, lesen und nur den Lebenslauf fortschreiben (Spaltenrecht, zusätzlich Trigger tariff_revision_guard); kein DELETE.
-- Regeln: anhängen, lesen, im Entwurf ersetzen (DELETE; der Trigger tariff_rule_guard verweigert jede Änderung an Regeln einer nicht mehr offenen Revision).
GRANT SELECT, INSERT ON taktovia.tariff_revision TO taktovia_runtime;
GRANT UPDATE (valid_from, valid_to, reason, state, reviewed_by, reviewed_at, published_by, published_at, row_version) ON taktovia.tariff_revision TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON taktovia.tariff_rule TO taktovia_runtime;
