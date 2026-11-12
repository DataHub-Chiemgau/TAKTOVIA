-- WP-M2-31: Erstattungsfall (`refund_case`, Kap. 04 §4.4) für Void und Erstattung nach Storno. Je Storno einer bezahlten Buchung höchstens ein Fall je Zahlung (`case_key` = Buchungs-ID; ein
-- wiederholtes Storno findet den vorhandenen Fall), mit eindeutiger PSP-Kommandoreferenz (`command_ref`, dieselbe wie in `payment_operation`), damit jeder Wiederholungsversuch denselben
-- Idempotenzschlüssel benutzt (nie eine zweite Erstattung). `payment_intent.refunded_minor` führt nur den vom PSP **bestätigten** Betrag; beauftragt ist, was in `REQUESTED` steht.
-- Zustände (Planungsannahme, D-05): `PENDING_CAPTURE` (Storno während eines laufenden Einzugs: Höhe steht erst nach dem Einzug fest), `REQUESTED` (beauftragt, noch nicht bestätigt: dem Fahrgast
-- „Erstattung beauftragt“, nie „erstattet“), `CONFIRMED` (PSP-bestätigt), `FAILED` (PSP lehnt ab: die Buchung bleibt storniert, der Betrag bleibt offen und sichtbar), `NOT_DUE` (nach der Regel
-- kein Anspruch, Betrag 0) und `NOT_REQUIRED` (es kam kein Einzug zustande, die Autorisierung wird aufgehoben).
SET search_path = taktovia, public;

INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('refund_case', 1, 'PENDING_CAPTURE', 'REQUESTED'), ('refund_case', 1, 'PENDING_CAPTURE', 'NOT_DUE'), ('refund_case', 1, 'PENDING_CAPTURE', 'NOT_REQUIRED'),
  ('refund_case', 1, 'REQUESTED', 'CONFIRMED'), ('refund_case', 1, 'REQUESTED', 'FAILED');

CREATE TABLE refund_case (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  payment_id       uuid        NOT NULL,
  case_key         uuid        NOT NULL,                                       -- fachlicher Schlüssel des Anlasses (Buchungs-ID beim Storno): ein Fall je Zahlung und Anlass
  seq              integer     NOT NULL CHECK (seq >= 1),                       -- Reihenfolge der Fälle einer Zahlung; je Zahlung läuft höchstens eine Erstattung beim PSP, die nächste erst nach der Bestätigung
  reason_code      text        NOT NULL CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  cause_class      text        NOT NULL CHECK (cause_class IN ('FAHRGAST', 'BETREIBER', 'FAHRZEUGAUSFALL')),
  state            text        NOT NULL,
  percent          integer     NOT NULL CHECK (percent BETWEEN 0 AND 100),     -- angewendete Regel (Prozent des erfassten Betrags)
  requested_minor  bigint      CHECK (requested_minor >= 0),                    -- beauftragt; erst bei Beauftragung bekannt
  refunded_minor   bigint      NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),  -- vom PSP bestätigt
  command_ref      text        CHECK (command_ref ~ '^[A-Za-z0-9._:-]{8,128}$'),
  failure_code     text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, payment_id) REFERENCES payment_intent (tenant_id, id),
  CONSTRAINT refund_case_per_key UNIQUE (tenant_id, payment_id, case_key),
  CONSTRAINT refund_case_seq UNIQUE (tenant_id, payment_id, seq),
  CONSTRAINT refund_case_command_ref UNIQUE (tenant_id, command_ref),
  CONSTRAINT refund_case_confirmed_le_requested CHECK (refunded_minor <= COALESCE(requested_minor, 0)),
  CONSTRAINT refund_case_amount_by_state CHECK (
    (state = 'PENDING_CAPTURE' AND requested_minor IS NULL AND command_ref IS NULL) OR
    (state IN ('REQUESTED', 'CONFIRMED', 'FAILED') AND requested_minor >= 1 AND command_ref IS NOT NULL) OR
    (state = 'NOT_DUE' AND requested_minor = 0) OR
    (state = 'NOT_REQUIRED')),
  CONSTRAINT refund_case_confirmed_amount CHECK ((state = 'CONFIRMED' AND refunded_minor >= 1) OR (state <> 'CONFIRMED' AND refunded_minor = 0))
);
ALTER TABLE refund_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE refund_case FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refund_case USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON refund_case FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX refund_case_by_payment ON refund_case (tenant_id, payment_id, created_at);

SELECT harden_state_column('taktovia.refund_case', 'state', 'refund_case');

-- Wächter: Identität unveränderlich, Summe der beauftragten Erstattungen einer Zahlung übersteigt nie den erfassten Betrag (unter der Zeilensperre der Zahlung, auch für parallele Aufträge und
-- die Administration); der Betrag eines Falls ändert sich nach der Beauftragung nicht.
CREATE FUNCTION refund_case_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE cap bigint; others bigint;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.payment_id IS DISTINCT FROM OLD.payment_id OR NEW.case_key IS DISTINCT FROM OLD.case_key OR NEW.seq IS DISTINCT FROM OLD.seq OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
       OR NEW.cause_class IS DISTINCT FROM OLD.cause_class OR NEW.percent IS DISTINCT FROM OLD.percent
       OR (OLD.command_ref IS NOT NULL AND NEW.command_ref IS DISTINCT FROM OLD.command_ref)
       OR (OLD.requested_minor IS NOT NULL AND NEW.requested_minor IS DISTINCT FROM OLD.requested_minor) THEN
      RAISE EXCEPTION 'refund_case identity and requested amount are immutable' USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.state IN ('REQUESTED', 'CONFIRMED') THEN
    -- Zeilensperre der Zahlung serialisiert gleichzeitige Beauftragungen; die Summe wird danach unter Sperre gebildet
    SELECT captured_minor INTO cap FROM payment_intent WHERE tenant_id = NEW.tenant_id AND id = NEW.payment_id FOR UPDATE;
    SELECT COALESCE(sum(requested_minor), 0) INTO others FROM refund_case
      WHERE tenant_id = NEW.tenant_id AND payment_id = NEW.payment_id AND id <> NEW.id AND state IN ('REQUESTED', 'CONFIRMED');
    IF others + NEW.requested_minor > cap THEN
      RAISE EXCEPTION 'refunds exceed the captured amount' USING ERRCODE = '23514';
    END IF;
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER refund_case_guard BEFORE INSERT OR UPDATE ON refund_case FOR EACH ROW EXECUTE FUNCTION refund_case_guard();
