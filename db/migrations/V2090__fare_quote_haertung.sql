-- WP-M2-09 / LH-6.10-PAY-001: Preisangebot als unveränderlicher `fare_quote`. Das Kernschema (V1) legt `fare_quote` mit Trigger `quote_immutable` an, V2070 den Fremdschlüssel auf
-- die Tarifrevision, V2080 die Komponenten. Hier die Härtung für die Quote-Bildung:
--  * `content_hash` ist ein SHA-256 in Kleinbuchstaben-Hex (Kanonisierung siehe `farepayment.api.QuoteHashing`), `snapshot` ein JSON-Objekt;
--  * Zugriff je Versuch (`travel_attempt`) ohne Tabellendurchlauf.
-- Rechte unverändert: Runtime SELECT, INSERT (`R__runtime_grants.sql`); UPDATE und DELETE schlagen für jede Rolle am Trigger fehl.
SET search_path = taktovia, public;

ALTER TABLE fare_quote ADD CONSTRAINT fare_quote_hash_format CHECK (content_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE fare_quote ADD CONSTRAINT fare_quote_snapshot_object CHECK (jsonb_typeof(snapshot) = 'object');
CREATE INDEX fare_quote_by_attempt ON fare_quote (tenant_id, attempt_id);
