-- WP-M3-55 / LH-I.8-AUT-002: Ablaufprotokoll automatisch ausgeführter Vorgänge (append-only), je Incident lückenlos und eindeutig geordnet.
-- Additiv zu V3020 (`workflow_run_log` besteht dort, unveränderlich per Trigger reject_mutation, Runtime nur SELECT/INSERT) und V30530:
-- 1. `seq`: laufende Ordnungsnummer je Incident, vergeben von der Datenbank unter der Zeilensperre des Incidents (`incident.log_seq`); parallele Schreiber ergeben lückenlose, verschiedene
--    Nummern, ein Rollback nimmt die Vergabe zurück.
-- 2. Auslöser (`event_type`), Entscheidung (`decision`), Ergebnisreferenz (`result_ref`, z. B. Operation), Grundcode (`reason_code`, Leitstellenentscheidung) und `correlation_id`
--    (Bezug zu `audit_event.correlation_id`) machen die Zeitleiste für sich allein lesbar. `created_by` ist der Akteur (`system:workflow` oder `user:<Kennung>`).
-- 3. Minimierung: Aktion, Ergebnis, Entscheidung, Grund und Referenz sind Codes (Zeichenklassen per CHECK), nie Freitext oder Rohpayload; Personendaten gelangen so nicht ins Protokoll.
SET search_path = taktovia, public;

ALTER TABLE incident ADD COLUMN log_seq bigint NOT NULL DEFAULT 0 CHECK (log_seq >= 0);

ALTER TABLE workflow_run_log
  ADD COLUMN seq            bigint CHECK (seq IS NULL OR seq >= 1),
  ADD COLUMN correlation_id uuid,
  ADD COLUMN event_type     text CHECK (event_type IS NULL OR event_type ~ '^[A-Za-z0-9_.:-]{1,64}$'),
  ADD COLUMN decision       text CHECK (decision IS NULL OR decision ~ '^[A-Z][A-Z0-9_:.-]{0,99}$'),
  ADD COLUMN result_ref     text CHECK (result_ref IS NULL OR result_ref ~ '^[A-Za-z0-9_.:-]{1,64}$'),
  ADD COLUMN reason_code    text CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  ADD CONSTRAINT workflow_run_log_action_code CHECK (action ~ '^[A-Z][A-Z0-9_]{1,99}$') NOT VALID,
  ADD CONSTRAINT workflow_run_log_outcome_code CHECK (outcome ~ '^[A-Za-z0-9_.:-]{1,100}$') NOT VALID,
  ADD CONSTRAINT workflow_run_log_seq_per_incident CHECK ((incident_id IS NULL) = (seq IS NULL)) NOT VALID;
-- NOT VALID: die Prüfungen gelten für alle neuen Zeilen; Zeilen aus der Zeit vor dieser Migration (Entwicklungsstände, Fixtures) werden nicht verändert (das Protokoll ist unveränderlich) und
-- tragen ggf. keine Ordnungsnummer; die Anzeige ordnet solche Zeilen vor den nummerierten nach Zeit und Kennung.

CREATE UNIQUE INDEX workflow_run_log_seq_unique ON workflow_run_log (tenant_id, incident_id, seq) WHERE incident_id IS NOT NULL;
CREATE INDEX workflow_run_log_by_event ON workflow_run_log (tenant_id, event_id);

-- Vergabe der Ordnungsnummer: ein bedingtes UPDATE auf der Incident-Zeile serialisiert parallele Schreiber desselben Incidents.
CREATE FUNCTION workflow_run_log_seq() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.incident_id IS NOT NULL THEN
    UPDATE incident SET log_seq = log_seq + 1 WHERE tenant_id = NEW.tenant_id AND id = NEW.incident_id RETURNING log_seq INTO NEW.seq;
    IF NEW.seq IS NULL THEN RAISE EXCEPTION 'workflow_run_log: unknown incident' USING ERRCODE = '23503'; END IF;
  ELSE
    NEW.seq := NULL;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER workflow_run_log_seq BEFORE INSERT ON workflow_run_log FOR EACH ROW EXECUTE FUNCTION workflow_run_log_seq();
