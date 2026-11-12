-- WP-M1-33 / LH-6.18-SEC-007: Aufbewahrungs-, Archivierungs- und Löschregeln (Kap. 04 §4.4 CFG/SEC/INT, Kap. 01 §1.7).
-- Feldlisten sind im Pflichtenheft nicht festgelegt (OP-25) und werden hier mit dem Bau vorgeschlagen. Es werden keine Fristen festgelegt:
-- Fristen und Löschmatrix stehen unter D-11; Regeln entstehen als Entwurf und gelten erst nach Freigabe durch eine zweite Person.
SET search_path = taktovia, public;

-- Regel je Datenklasse (Löschklasse des Dateninventars, z. B. LK_PII). Bezugszeitpunkt der Frist ist das im Datensatz geführte Aufbewahrungsende
-- (Fahrgastkonto: `passenger_private.retention_until`); `retention_days` ist die Nachlauffrist danach. ARCHIVE führt in den Archivzustand für
-- `archive_days`, danach folgt die Anonymisierung.
CREATE TABLE retention_rule (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  data_class     text        NOT NULL CHECK (data_class ~ '^LK_[A-Z_]{2,40}$'),
  retention_days integer     NOT NULL CHECK (retention_days BETWEEN 1 AND 36500),
  action         text        NOT NULL CHECK (action IN ('DELETE', 'ANONYMIZE', 'ARCHIVE')),
  archive_days   integer     CHECK (archive_days BETWEEN 1 AND 36500),
  status         text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED')),
  revision       bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  changed_by     text        NOT NULL CHECK (changed_by ~ '^(user|service|system):[A-Za-z0-9._:-]{1,120}$'),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  approved_by    text        CHECK (approved_by ~ '^(user|service|system):[A-Za-z0-9._:-]{1,120}$'),
  approved_at    timestamptz,
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT retention_rule_archive_days CHECK ((action = 'ARCHIVE') = (archive_days IS NOT NULL)),
  CONSTRAINT retention_rule_approval_consistent CHECK ((status = 'APPROVED') = (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
  -- Vier-Augen: wer die Regel zuletzt geändert hat, gibt sie nicht frei.
  CONSTRAINT retention_rule_four_eyes CHECK (approved_by IS NULL OR approved_by <> changed_by)
);
CREATE UNIQUE INDEX retention_rule_one_approved ON retention_rule (tenant_id, data_class) WHERE status = 'APPROVED';
CREATE INDEX retention_rule_by_created ON retention_rule (tenant_id, created_at, id);

-- Löschlauf: fortsetzbarer Job. `resume_after` ist die Fortsetzungsmarke (zuletzt bearbeitetes Objekt), die Regelwerte sind beim Anlegen
-- festgehalten (eine spätere Regeländerung wirkt nicht mitten im Lauf). `operation_id` verweist auf die `operation` (WP-M1-15).
CREATE TABLE erasure_job (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  rule_id        uuid        NOT NULL,
  rule_revision  bigint      NOT NULL,
  data_class     text        NOT NULL,
  action         text        NOT NULL CHECK (action IN ('DELETE', 'ANONYMIZE', 'ARCHIVE')),
  retention_days integer     NOT NULL CHECK (retention_days >= 1),
  archive_days   integer     CHECK (archive_days >= 1),
  status         text        NOT NULL DEFAULT 'RESUMABLE' CHECK (status IN ('RUNNING', 'RESUMABLE', 'COMPLETED', 'ABORTED')),
  resume_after   uuid,
  processed_count integer    NOT NULL DEFAULT 0 CHECK (processed_count >= 0),
  skipped_count  integer     NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
  result_code    text        CHECK (result_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  operation_id   uuid,
  lease_until    timestamptz,
  created_by     text        NOT NULL CHECK (created_by ~ '^(user|service|system):[A-Za-z0-9._:-]{1,120}$'),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  finished_at    timestamptz,
  revision       bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, rule_id) REFERENCES retention_rule (tenant_id, id),
  FOREIGN KEY (tenant_id, operation_id) REFERENCES operation (tenant_id, id),
  CONSTRAINT erasure_job_final_consistent CHECK ((status IN ('COMPLETED', 'ABORTED')) = (finished_at IS NOT NULL)),
  CONSTRAINT erasure_job_archive_days CHECK ((action = 'ARCHIVE') = (archive_days IS NOT NULL))
);
CREATE INDEX erasure_job_by_created ON erasure_job (tenant_id, created_at, id);

-- Lösch-/Sperrjournal: jedes bearbeitete Objekt genau einmal je Aktion, ohne Personendaten (nur Objektart und Kennung). Es wird zusätzlich in eine
-- getrennte Berechtigungsdomäne ausgeleitet (infra/backup) und beim Restore vor Freigabe wieder eingespielt (Kap. 01 §1.7, WP-M1-39).
CREATE TABLE erasure_journal (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  seq           bigint      GENERATED ALWAYS AS IDENTITY,
  job_id        uuid        NOT NULL,
  data_class    text        NOT NULL,
  object_ref    text        NOT NULL CHECK (object_ref ~ '^[a-z][a-z0-9_]{1,40}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
  action        text        NOT NULL CHECK (action IN ('DELETE', 'ANONYMIZE', 'ARCHIVE')),
  archive_until timestamptz,
  applied_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, job_id) REFERENCES erasure_job (tenant_id, id),
  -- Doppelverarbeitung ausgeschlossen: ein Objekt wird je Aktion nur einmal protokolliert (und damit nur einmal bearbeitet).
  UNIQUE (tenant_id, object_ref, action)
);
CREATE INDEX erasure_journal_by_seq ON erasure_journal (tenant_id, seq);
CREATE TRIGGER erasure_journal_immutable BEFORE UPDATE OR DELETE ON erasure_journal FOR EACH ROW EXECUTE FUNCTION reject_mutation();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['retention_rule', 'erasure_job', 'erasure_journal'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

-- Regel: eine freigegebene Regel ist inhaltlich unveränderlich (Änderung = zurück auf DRAFT, dann neue Freigabe); Revision +1 je Änderung.
CREATE FUNCTION retention_rule_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.data_class IS DISTINCT FROM OLD.data_class OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'retention rule identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.status = 'APPROVED' AND NEW.status = 'APPROVED'
     AND (NEW.retention_days IS DISTINCT FROM OLD.retention_days OR NEW.action IS DISTINCT FROM OLD.action OR NEW.archive_days IS DISTINCT FROM OLD.archive_days) THEN
    RAISE EXCEPTION 'approved retention rule must be reset to draft before a change' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER retention_rule_guard BEFORE UPDATE ON retention_rule FOR EACH ROW EXECUTE FUNCTION retention_rule_guard();

-- Job: Zustände RUNNING, RESUMABLE, COMPLETED, ABORTED (vertraege/zustaende/erasure_job.md). Endzustände sind unveränderlich; Regelbezug und
-- Regelwerte sind fest.
CREATE FUNCTION erasure_job_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.status IN ('COMPLETED', 'ABORTED') THEN
    RAISE EXCEPTION 'erasure job is final' USING ERRCODE = '23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.rule_id IS DISTINCT FROM OLD.rule_id OR NEW.rule_revision IS DISTINCT FROM OLD.rule_revision
     OR NEW.data_class IS DISTINCT FROM OLD.data_class OR NEW.action IS DISTINCT FROM OLD.action OR NEW.retention_days IS DISTINCT FROM OLD.retention_days
     OR NEW.archive_days IS DISTINCT FROM OLD.archive_days OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
    RAISE EXCEPTION 'erasure job rule snapshot is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.processed_count < OLD.processed_count OR NEW.skipped_count < OLD.skipped_count THEN
    RAISE EXCEPTION 'erasure job counters must not decrease' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER erasure_job_guard BEFORE UPDATE ON erasure_job FOR EACH ROW EXECUTE FUNCTION erasure_job_guard();

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Fahrgastkonto: Archivzustand und Löschzustand (Tombstone) von passenger_private.
--  * archived_at/archive_until: der Datensatz ist für operative Rollen nicht mehr lesbar (restriktive RLS-Policy); lesbar bleibt er nur über den
--    Archivzugang (Sitzungseinstellung app.archive_access, die ausschließlich platform.retention setzt) bis `archive_until`.
--  * erased_at: Löschzustand. Beim Setzen entfernt der Trigger atomar alle personenbezogenen Spalten der Zeile (auch die Ciphertext-Spalten, für
--    die die Runtime-Rolle kein Recht hat: Trigger schreiben unabhängig von Spaltenrechten); danach darf keine Personenangabe zurückkehren
--    (verhindert wiederauflebende PII bei gleichzeitiger Profiländerung). Die Zeile bleibt als Tombstone (Schlüssel für aufbewahrungspflichtige
--    Bezüge wie Einwilligungsnachweis), ohne Personenbezug.
ALTER TABLE passenger_private
  ADD COLUMN archived_at   timestamptz,
  ADD COLUMN archive_until timestamptz,
  ADD COLUMN erased_at     timestamptz,
  ADD CONSTRAINT passenger_archive_consistent CHECK ((archived_at IS NULL) = (archive_until IS NULL));

CREATE FUNCTION passenger_private_erasure() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.erased_at IS NOT NULL THEN
    IF NEW.erased_at IS DISTINCT FROM OLD.erased_at THEN
      RAISE EXCEPTION 'erased passenger must not be restored' USING ERRCODE = '23514';
    END IF;
    IF NEW.contact_ciphertext IS NOT NULL OR NEW.support_ciphertext IS NOT NULL OR NEW.idp_subject IS NOT NULL OR NEW.idp_issuer IS NOT NULL
       OR NEW.subject_token IS DISTINCT FROM OLD.subject_token OR NEW.archived_at IS NOT NULL THEN
      RAISE EXCEPTION 'erased passenger must not carry personal data' USING ERRCODE = '23514';
    END IF;
  ELSIF NEW.erased_at IS NOT NULL THEN
    NEW.contact_ciphertext := NULL;
    NEW.support_ciphertext := NULL;
    NEW.idp_issuer := NULL;
    NEW.idp_subject := NULL;
    NEW.subject_token := 'erased:' || NEW.id::text;
    NEW.locale := 'de-DE';
    NEW.status := 'BLOCKED';
    NEW.archived_at := NULL;
    NEW.archive_until := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER passenger_private_erasure BEFORE UPDATE ON passenger_private FOR EACH ROW EXECUTE FUNCTION passenger_private_erasure();

CREATE POLICY archive_hidden ON passenger_private AS RESTRICTIVE
  USING (archived_at IS NULL OR current_setting('app.archive_access', true) = 'on');

-- ---------------------------------------------------------------------------------------------------------------------------------------
-- Restore (WP-M1-39): spielt ein außerhalb der Datenbank gesichertes Lösch-/Sperrjournal in eine wiederhergestellte Datenbank wieder ein, bevor sie
-- freigegeben wird. Nur für die Betriebsrolle (Superuser/Eigentümer): Ausführungsrecht ist PUBLIC und der Runtime-Rolle entzogen. Idempotent.
-- Eingabe: Array von {tenant_id, job_id, object_ref, data_class, action, archive_until, applied_at}. Rückgabe: Anzahl neu angewendeter Einträge.
CREATE FUNCTION replay_erasure_journal(p_entries jsonb) RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE e jsonb; n integer := 0; v_tenant uuid; v_id uuid; v_ref text; v_action text; v_job uuid;
BEGIN
  FOR e IN SELECT * FROM jsonb_array_elements(p_entries) LOOP
    v_tenant := (e->>'tenant_id')::uuid; v_ref := e->>'object_ref'; v_action := e->>'action';
    IF split_part(v_ref, ':', 1) <> 'passenger' THEN
      RAISE EXCEPTION 'unknown object type in erasure journal: %', split_part(v_ref, ':', 1);
    END IF;
    v_id := split_part(v_ref, ':', 2)::uuid;
    IF v_action IN ('DELETE', 'ANONYMIZE') THEN
      UPDATE passenger_private SET erased_at = COALESCE((e->>'applied_at')::timestamptz, clock_timestamp())
       WHERE tenant_id = v_tenant AND id = v_id AND erased_at IS NULL;
      IF FOUND THEN n := n + 1; END IF;
      DELETE FROM passenger_preference WHERE tenant_id = v_tenant AND passenger_id = v_id;
      DELETE FROM passenger_contact WHERE tenant_id = v_tenant AND passenger_id = v_id;
      DELETE FROM contact_verification WHERE tenant_id = v_tenant AND passenger_id = v_id;
    ELSIF v_action = 'ARCHIVE' THEN
      UPDATE passenger_private SET archived_at = COALESCE((e->>'applied_at')::timestamptz, clock_timestamp()),
                                   archive_until = (e->>'archive_until')::timestamptz
       WHERE tenant_id = v_tenant AND id = v_id AND erased_at IS NULL AND archived_at IS NULL;
      IF FOUND THEN n := n + 1; END IF;
    END IF;
    -- Das Journal selbst gehört wieder in die Datenbank (der Job, auf den es verweist, fehlt nach einem Restore auf früheren Stand gegebenenfalls).
    v_job := (e->>'job_id')::uuid;
    IF EXISTS (SELECT 1 FROM erasure_job WHERE tenant_id = v_tenant AND id = v_job) THEN
      INSERT INTO erasure_journal (tenant_id, id, job_id, data_class, object_ref, action, archive_until, applied_at)
      VALUES (v_tenant, gen_random_uuid(), v_job, e->>'data_class', v_ref, v_action, (e->>'archive_until')::timestamptz,
              COALESCE((e->>'applied_at')::timestamptz, clock_timestamp()))
      ON CONFLICT (tenant_id, object_ref, action) DO NOTHING;
    END IF;
  END LOOP;
  RETURN n;
END $$;
REVOKE ALL ON FUNCTION replay_erasure_journal(jsonb) FROM PUBLIC;

-- Kontaktkanäle und Verifikationsaufgaben sind weiterhin nicht löschbar, außer wenn der Fahrgast im Löschzustand ist (Löschlauf, WP-M1-33):
-- die Runtime-Rolle braucht dafür das DELETE-Recht; der Trigger beschränkt es auf Tombstones.
CREATE FUNCTION passenger_dependent_delete_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM passenger_private WHERE tenant_id = OLD.tenant_id AND id = OLD.passenger_id AND erased_at IS NOT NULL) THEN
    RAISE EXCEPTION '% rows are deleted only for erased passengers', TG_TABLE_NAME USING ERRCODE = '23514';
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER passenger_contact_delete_guard BEFORE DELETE ON passenger_contact FOR EACH ROW EXECUTE FUNCTION passenger_dependent_delete_guard();
CREATE TRIGGER contact_verification_delete_guard BEFORE DELETE ON contact_verification FOR EACH ROW EXECUTE FUNCTION passenger_dependent_delete_guard();

-- Der Archivzustand des Kontos gilt für alle Bestände, die am Konto hängen: sie sind nur lesbar, solange die Kontozeile für die Abfrage sichtbar ist
-- (also außerhalb des Archivs oder mit Archivzugang). Das EXISTS unterliegt selbst der RLS-Policy archive_hidden von passenger_private.
DO $$ DECLARE t text; c text; BEGIN
  FOREACH t IN ARRAY ARRAY['passenger_contact', 'contact_verification', 'passenger_preference', 'consent_revision'] LOOP
    -- nur Lesen, Ändern und Löschen: die Fremdschlüsselprüfung beim Anlegen (anderer Mandant) bleibt Sache der Fremdschlüssel
    FOREACH c IN ARRAY ARRAY['SELECT', 'UPDATE', 'DELETE'] LOOP
      EXECUTE format('CREATE POLICY archive_hidden_%s ON taktovia.%I AS RESTRICTIVE FOR %s USING (EXISTS (SELECT 1 FROM taktovia.passenger_private p WHERE p.tenant_id = %I.tenant_id AND p.id = %I.passenger_id))', lower(c), t, c, t, t);
    END LOOP;
  END LOOP;
END $$;
