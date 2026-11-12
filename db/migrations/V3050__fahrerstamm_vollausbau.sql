-- WP-M3-05: Fahrerstamm im Vollausbau (Verknüpfung zur Mitgliedschaft) und Qualifikationsrevisionen mit Freigabe und Gültigkeit.
-- Stammteil aus WP-M1-35 (V1350): driver und qualification_revision bestehen bereits; hier nur additiv (expand/contract). Altbestand bleibt gültig:
-- Qualifikationsrevisionen vor dieser Migration gelten als freigegeben (Default APPROVED), Fahrer ohne Mitgliedschaft bleiben bis zur Zuordnung bestehen.
SET search_path = taktovia, public;

ALTER TABLE driver
  ADD COLUMN membership_id     uuid,
  ADD COLUMN qualification_seq bigint      NOT NULL DEFAULT 0 CHECK (qualification_seq >= 0),
  ADD COLUMN created_by        text,
  ADD COLUMN updated_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT driver_created_by_format CHECK (created_by IS NULL OR created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  -- ein Fahrer gehört genau einer Mitgliedschaft des Mandanten (zusammengesetzter Schlüssel: keine Mitgliedschaft eines fremden Mandanten), eine Mitgliedschaft höchstens einem Fahrer
  ADD CONSTRAINT driver_membership_fk FOREIGN KEY (tenant_id, membership_id) REFERENCES membership(tenant_id, id),
  ADD CONSTRAINT driver_membership_unique UNIQUE (tenant_id, membership_id);

-- Die Zuordnung zur Mitgliedschaft ist nach dem Setzen endgültig; die fortlaufende Qualifikationsnummer des Fahrers (qualification_seq, Bezug von resource_release.qualification_revision) steigt nur.
CREATE FUNCTION driver_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.membership_id IS NOT NULL AND NEW.membership_id IS DISTINCT FROM OLD.membership_id THEN
    RAISE EXCEPTION 'driver membership link is final' USING ERRCODE = '23514';
  END IF;
  IF NEW.qualification_seq < OLD.qualification_seq THEN
    RAISE EXCEPTION 'qualification sequence may only increase' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER driver_guard BEFORE UPDATE ON driver FOR EACH ROW EXECUTE FUNCTION driver_guard();

-- Qualifikationsrevision: Freigabe (APPROVED), Entzug (REVOKED) oder wartend (PENDING). Wirksam ist je (Fahrer, Qualifikation) die höchste Revision, die nicht PENDING ist;
-- sie gilt nur, wenn sie APPROVED ist und das Gültigkeitsintervall den Zeitpunkt enthält. driver_seq ist die fortlaufende Nummer der Änderung je Fahrer.
ALTER TABLE qualification_revision
  ADD COLUMN approval_status text   NOT NULL DEFAULT 'APPROVED' CHECK (approval_status IN ('PENDING', 'APPROVED', 'REVOKED')),
  ADD COLUMN approved_by     text,
  ADD COLUMN approved_at     timestamptz,
  ADD COLUMN driver_seq      bigint CHECK (driver_seq IS NULL OR driver_seq >= 1),
  ADD COLUMN created_by      text,
  ADD CONSTRAINT qualification_pending_without_approver CHECK (approval_status <> 'PENDING' OR (approved_by IS NULL AND approved_at IS NULL)),
  ADD CONSTRAINT qualification_approver_format CHECK (approved_by IS NULL OR approved_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT qualification_created_by_format CHECK (created_by IS NULL OR created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT qualification_driver_seq_unique UNIQUE (tenant_id, driver_id, driver_seq);
CREATE INDEX qualification_by_code ON qualification_revision (tenant_id, driver_id, qualification_code, revision DESC);

-- Jede Änderung ist eine neue Revision; die alte bleibt unverändert lesbar (append-only, auch für die Administration).
CREATE TRIGGER qualification_revision_immutable BEFORE UPDATE OR DELETE ON qualification_revision FOR EACH ROW EXECUTE FUNCTION reject_mutation();
