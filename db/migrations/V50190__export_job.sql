-- WP-M5-19 / AT-25, AT-53, AT-61: Exportauftrag (`export_job`, Kap. 04 §4.4; Spalten im Pflichtenheft nicht spezifiziert, Entwurf dieses WP). Ein Auftrag ist mandantengebunden (RLS), wird mit Idempotenzschlüssel angelegt
-- (derselbe Schlüssel mit anderem Umfang ist ein Konflikt), läuft als asynchroner Job (QUEUED -> RUNNING -> SUCCEEDED | FAILED) und hält den Datenstand (`as_of`, vom Server gesetzt) sowie die Nachweise des Ergebnisses
-- (SHA-256, Größe, Kategorien mit Status). Das Ergebnis (`result`) wird einmal geschrieben und ist danach unveränderlich; der Exportinhalt steht nie im Audit. `requester_scopes` und `requester_roles` halten die Funktionsrechte und Rollen des Auftraggebers
-- zum Auftragszeitpunkt (Empfängerrechte): der Export enthält nur, wozu der Auftraggeber berechtigt war.
SET search_path = taktovia, public;

CREATE TABLE export_job (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  kind             text        NOT NULL CHECK (kind IN ('PBEFG_DATA', 'INSPECTION_PROOF', 'DEFECT_CASE', 'TIME_PROOF', 'ROSTER_ARCHIVE')),
  scope            jsonb       NOT NULL CHECK (jsonb_typeof(scope) = 'object'),
  requested_by     text        NOT NULL CHECK (requested_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  requester_scopes text[]      NOT NULL,
  requester_roles  text[]      NOT NULL,
  idempotency_key  text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
  request_hash     char(64)    NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  state            text        NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  as_of            timestamptz NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  started_at       timestamptz,
  finished_at      timestamptz,
  result           jsonb,
  result_sha256    char(64)    CHECK (result_sha256 IS NULL OR result_sha256 ~ '^[0-9a-f]{64}$'),
  result_bytes     integer     CHECK (result_bytes IS NULL OR result_bytes >= 0),
  categories       jsonb,
  failure_code     text        CHECK (failure_code IS NULL OR length(failure_code) BETWEEN 1 AND 100),
  revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, requested_by, idempotency_key),
  CONSTRAINT export_job_result_consistent CHECK (
    (state = 'SUCCEEDED') = (result IS NOT NULL AND result_sha256 IS NOT NULL AND result_bytes IS NOT NULL AND categories IS NOT NULL AND finished_at IS NOT NULL)
    AND (state = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX export_job_by_state ON export_job (tenant_id, state, created_at);
CREATE INDEX export_job_by_requester ON export_job (tenant_id, requested_by, created_at);

ALTER TABLE export_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE export_job FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON export_job USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON export_job FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Lebenslauf und Unveränderlichkeit: Auftragsdaten (Art, Umfang, Auftraggeber, Rechte, Schlüssel, Hash, Datenstand) sind unveränderlich; Zustand nur QUEUED -> RUNNING -> SUCCEEDED | FAILED;
-- ein beendeter Auftrag ändert sich nicht mehr; das Ergebnis wird nur mit dem Übergang nach SUCCEEDED gesetzt.
CREATE FUNCTION export_job_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.scope IS DISTINCT FROM OLD.scope OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
     OR NEW.requester_scopes IS DISTINCT FROM OLD.requester_scopes OR NEW.requester_roles IS DISTINCT FROM OLD.requester_roles OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
     OR NEW.as_of IS DISTINCT FROM OLD.as_of OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'export_job: Auftragsdaten sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.state IN ('SUCCEEDED', 'FAILED') THEN
    RAISE EXCEPTION 'export_job: ein beendeter Auftrag aendert sich nicht mehr' USING ERRCODE = '23514', CONSTRAINT = 'export_job_state_transition';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT ((OLD.state = 'QUEUED' AND NEW.state IN ('RUNNING', 'FAILED')) OR (OLD.state = 'RUNNING' AND NEW.state IN ('SUCCEEDED', 'FAILED', 'QUEUED'))) THEN
    RAISE EXCEPTION 'export_job: Zustandsuebergang % -> % nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514', CONSTRAINT = 'export_job_state_transition';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER export_job_guard BEFORE UPDATE ON export_job FOR EACH ROW EXECUTE FUNCTION export_job_guard();
CREATE FUNCTION export_job_no_delete() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN RAISE EXCEPTION 'export_job: Auftraege werden nicht geloescht (Nachweis)' USING ERRCODE = '23514'; END $$;
CREATE TRIGGER export_job_no_delete BEFORE DELETE ON export_job FOR EACH ROW EXECUTE FUNCTION export_job_no_delete();
