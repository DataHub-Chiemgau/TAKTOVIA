-- WP-M1-46: Format der Pflichtangaben von audit_event, Abfrageindizes, kein TRUNCATE-Recht für Runtime.
-- Append-only bleibt wie in V1: Trigger audit_immutable (UPDATE/DELETE), Runtime-Rolle nur SELECT/INSERT, RLS wie alle Tabellen.
SET search_path = taktovia, public;
ALTER TABLE audit_event
  ADD CONSTRAINT audit_actor_ref_format  CHECK (actor_ref ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT audit_object_ref_format CHECK (object_ref ~ '^[a-z][a-z0-9_]{1,62}:[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT audit_action_format     CHECK (action ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){1,3}$'),
  ADD CONSTRAINT audit_changes_is_object CHECK (jsonb_typeof(redacted_changes) = 'object');
CREATE INDEX audit_event_by_time   ON audit_event (tenant_id, occurred_at DESC);
CREATE INDEX audit_event_by_object ON audit_event (tenant_id, object_ref, occurred_at DESC);
-- TRUNCATE: die Runtime-Rolle besitzt das Recht nicht (nur SELECT, INSERT; getestet in db/tests/test_46_audit_event.py).
