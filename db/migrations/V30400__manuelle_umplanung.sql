-- WP-M3-40: Manuelle Umplanung mit Begründung und Plan-CAS.
-- 1. plan_change: Begründung, Akteur und der dokumentierte Ausnahmeweg (verletzte Regeln mit Begründung) der Planrevision; append-only wie die Tabelle selbst.
--    Revisionen aus Solver und Hold (WP-M2-17/-19) tragen keine Begründung (NULL); eine manuelle Revision (`created_via = 'MANUAL'`) braucht Begründung und Akteur.
-- 2. booking_change: Umbuchung einer Buchung auf einen anderen Umlauf (Quelle, Ziel); Grundlage der Prüfung von `max_reassignments`.
SET search_path = taktovia, public;

ALTER TABLE plan_change
  ADD COLUMN created_via              text NOT NULL DEFAULT 'SYSTEM' CHECK (created_via IN ('SYSTEM', 'MANUAL')),
  ADD COLUMN reason                   text CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
  ADD COLUMN created_by               text CHECK (created_by IS NULL OR created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN exception_rules          text[] CHECK (exception_rules IS NULL OR exception_rules <@ ARRAY['PROTECTED_STOP_HORIZON', 'PROMISE_WINDOW', 'MAX_REASSIGNMENTS']::text[]),
  ADD COLUMN exception_justification  text CHECK (exception_justification IS NULL OR length(btrim(exception_justification)) BETWEEN 10 AND 500);
ALTER TABLE plan_change ADD CONSTRAINT plan_change_manual_has_reason CHECK (created_via = 'SYSTEM' OR (reason IS NOT NULL AND created_by IS NOT NULL));
ALTER TABLE plan_change ADD CONSTRAINT plan_change_exception_documented CHECK ((exception_rules IS NULL) = (exception_justification IS NULL) AND (exception_rules IS NULL OR created_via = 'MANUAL'));

ALTER TABLE booking_change
  ADD COLUMN from_run_id uuid,
  ADD COLUMN to_run_id   uuid;
ALTER TABLE booking_change ADD CONSTRAINT booking_change_reassignment_pair CHECK ((from_run_id IS NULL) = (to_run_id IS NULL) AND (from_run_id IS NULL OR from_run_id <> to_run_id));
CREATE INDEX booking_change_reassigned ON booking_change (tenant_id, booking_id) WHERE to_run_id IS NOT NULL;
