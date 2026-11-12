-- WP-M2-30: Rechte für payment_operation (hinter R__runtime_grants.sql sortiert). Anlegen und lesen; unveränderlich (kein UPDATE, kein DELETE).
-- `payment_intent` behält SELECT, INSERT, UPDATE (R__runtime_grants.sql); Geld und Identität schützt der Wächter `payment_intent_guard`.
GRANT SELECT, INSERT ON taktovia.payment_operation TO taktovia_runtime;
