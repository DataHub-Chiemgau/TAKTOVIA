-- WP-M4-16 / LH-6.23-SCH-009: Depotberechtigung im Dienstplan. Mandantentrennung bleibt ausnahmslos RLS; die Depotbindung ist die zweite Schicht in der Anwendung (Port canRead/canWrite je Depot).
-- `roster_depot_grant`: eine Vergabe gibt einer Person (IdP-Subject) das Recht für genau ein Depot (`DEPOT`) oder, als eigenes ausdrückliches Recht, für alle Depots des Mandanten (`ALL_DEPOTS`).
-- Ohne wirksame Vergabe ist der Depotscope leer: nichts lesbar, nichts änderbar (fail-closed, Planungsannahme analog OP-04). Rechteentzug (`revoked_at`) ist endgültig und der einzige zulässige
-- Änderungsschritt; die Anwendung liest die Vergabe in der Fachtransaktion mit FOR SHARE, ein Entzug wartet also auf laufende Schreibbefehle und wirkt ab seinem Commit.
-- Die Spalte `depot_id` an roster_revision (V40040) und staff_shift (V40030) ist der Depotbezug für die Objektprüfung.
SET search_path = taktovia, public;

CREATE TABLE roster_depot_grant (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  subject     text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 200 AND subject = lower(subject)),   -- IdP-Subject der berechtigten Person (kleingeschrieben)
  scope_kind  text        NOT NULL CHECK (scope_kind IN ('DEPOT', 'ALL_DEPOTS')),
  depot_id    uuid,
  granted_by  text        NOT NULL CHECK (granted_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  granted_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  revoked_at  timestamptz,
  revoked_by  text        CHECK (revoked_by IS NULL OR revoked_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, depot_id) REFERENCES depot (tenant_id, id),
  CONSTRAINT roster_depot_grant_kind CHECK ((scope_kind = 'DEPOT') = (depot_id IS NOT NULL)),
  CONSTRAINT roster_depot_grant_revoked CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
CREATE UNIQUE INDEX roster_depot_grant_one_depot ON roster_depot_grant (tenant_id, subject, depot_id) WHERE revoked_at IS NULL AND scope_kind = 'DEPOT';
CREATE UNIQUE INDEX roster_depot_grant_one_all   ON roster_depot_grant (tenant_id, subject) WHERE revoked_at IS NULL AND scope_kind = 'ALL_DEPOTS';
CREATE INDEX roster_depot_grant_by_subject ON roster_depot_grant (tenant_id, subject, revoked_at);

ALTER TABLE roster_depot_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE roster_depot_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roster_depot_grant USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON roster_depot_grant FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- einzige zulässige Änderung: Entzug (revoked_at und revoked_by setzen), endgültig
CREATE FUNCTION roster_depot_grant_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.scope_kind IS DISTINCT FROM OLD.scope_kind OR NEW.depot_id IS DISTINCT FROM OLD.depot_id
     OR NEW.granted_by IS DISTINCT FROM OLD.granted_by OR NEW.granted_at IS DISTINCT FROM OLD.granted_at THEN
    RAISE EXCEPTION 'roster_depot_grant: Vergabe ist unveraenderlich, nur der Entzug ist zulaessig' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'roster_depot_grant: Entzug ist endgueltig' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER roster_depot_grant_guard BEFORE UPDATE ON roster_depot_grant FOR EACH ROW EXECUTE FUNCTION roster_depot_grant_guard();
