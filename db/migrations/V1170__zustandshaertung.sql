-- WP-M1-17 / LH-6.1-SYS-007: Härtung der Zustandsspalten (Kap. 04 §4.1) als versionierte Übergangsregeln plus CHECK.
-- Je Automat stehen die erlaubten Übergänge in `state_transition_rule` (global, nur per Migration gepflegt, Runtime nur lesend).
-- `harden_state_column(tabelle, spalte, automat)` ergänzt einer Zustandsspalte (1) einen CHECK über die Wertemenge des Automaten und
-- (2) den Trigger `enforce_state_transition`, der jede Änderung des Wertes gegen die Regeln prüft (auch für Administration und direkte
-- SQL-Zugriffe). Gleicher Wert (kein Zustandswechsel) bleibt möglich. Der Server prüft zusätzlich selbst (platform.statemachine); ein Test
-- vergleicht beide Seiten. Die Funktion wird von M2/M3-Migrationen für booking.state, payment_intent.state, vehicle_run.state und
-- capacity_hold.state aufgerufen; Ausführung nur durch den Migrator (Eigentümer).
SET search_path = taktovia, public;

CREATE TABLE state_transition_rule (
  machine    text    NOT NULL CHECK (machine ~ '^[a-z][a-z0-9_]{1,40}$'),
  version    integer NOT NULL CHECK (version >= 1),
  from_state text    NOT NULL CHECK (from_state ~ '^[A-Z][A-Z0-9_]{1,40}$'),
  to_state   text    NOT NULL CHECK (to_state ~ '^[A-Z][A-Z0-9_]{1,40}$'),
  PRIMARY KEY (machine, from_state, to_state),
  CHECK (from_state <> to_state)
);

CREATE FUNCTION enforce_state_transition() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE o text := to_jsonb(OLD) ->> TG_ARGV[1]; n text := to_jsonb(NEW) ->> TG_ARGV[1];
BEGIN
  IF o IS DISTINCT FROM n AND NOT EXISTS (SELECT 1 FROM state_transition_rule WHERE machine = TG_ARGV[0] AND from_state = o AND to_state = n) THEN
    RAISE EXCEPTION 'state transition not allowed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE FUNCTION harden_state_column(tbl regclass, col name, machine text) RETURNS void LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE states text; rel text := (SELECT relname FROM pg_class WHERE oid = tbl);
BEGIN
  SELECT string_agg(quote_literal(s), ',' ORDER BY s) INTO states
    FROM (SELECT from_state AS s FROM state_transition_rule r WHERE r.machine = harden_state_column.machine
          UNION SELECT to_state FROM state_transition_rule r WHERE r.machine = harden_state_column.machine) x;
  IF states IS NULL THEN RAISE EXCEPTION 'unknown state machine %', machine USING ERRCODE = 'P0001'; END IF;
  EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I CHECK (%I IN (%s))', tbl, rel || '_' || col || '_state_valid', col, states);
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OF %I ON %s FOR EACH ROW WHEN (OLD.%I IS DISTINCT FROM NEW.%I) EXECUTE FUNCTION enforce_state_transition(%L, %L)',
                 rel || '_' || col || '_transition', col, tbl, col, col, machine, col);
END $$;

-- Operation (Kap. 02 §7.3): RECONCILING wird nie zu Erfolg oder Fehler verkürzt, sondern nur durch eindeutiges Ergebnis oder manuelle Prüfung verlassen.
INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('operation',1,'QUEUED','RUNNING'),
  ('operation',1,'RUNNING','SUCCEEDED'), ('operation',1,'RUNNING','FAILED'), ('operation',1,'RUNNING','RECONCILING'),
  ('operation',1,'RUNNING','MANUAL_REVIEW'), ('operation',1,'RUNNING','QUEUED'),
  ('operation',1,'RECONCILING','SUCCEEDED'), ('operation',1,'RECONCILING','FAILED'), ('operation',1,'RECONCILING','MANUAL_REVIEW'),
  ('operation',1,'MANUAL_REVIEW','SUCCEEDED'), ('operation',1,'MANUAL_REVIEW','FAILED'), ('operation',1,'MANUAL_REVIEW','QUEUED'),
  ('operation',1,'MANUAL_REVIEW','RECONCILING'),
-- Mandant
  ('tenant',1,'ACTIVE','SUSPENDED'), ('tenant',1,'SUSPENDED','ACTIVE'), ('tenant',1,'ACTIVE','CLOSED'), ('tenant',1,'SUSPENDED','CLOSED'),
-- Journey-Buchung (Kap. 02 §7.3); Anwendung auf booking.state durch M2
  ('booking',1,'HOLDING','HELD'), ('booking',1,'HOLDING','COMPENSATING'), ('booking',1,'HOLDING','FAILED'),
  ('booking',1,'HELD','ACTION_REQUIRED'), ('booking',1,'HELD','CONFIRMING'), ('booking',1,'HELD','COMPENSATING'), ('booking',1,'HELD','FAILED'),
  ('booking',1,'ACTION_REQUIRED','CONFIRMING'), ('booking',1,'ACTION_REQUIRED','COMPENSATING'), ('booking',1,'ACTION_REQUIRED','FAILED'),
  ('booking',1,'CONFIRMING','CONFIRMED'), ('booking',1,'CONFIRMING','PARTIAL'), ('booking',1,'CONFIRMING','RECONCILING'),
  ('booking',1,'CONFIRMING','COMPENSATING'), ('booking',1,'CONFIRMING','FAILED'),
  ('booking',1,'RECONCILING','CONFIRMED'), ('booking',1,'RECONCILING','PARTIAL'), ('booking',1,'RECONCILING','COMPENSATING'), ('booking',1,'RECONCILING','FAILED'),
  ('booking',1,'PARTIAL','COMPENSATING'), ('booking',1,'PARTIAL','CANCEL_PENDING'), ('booking',1,'PARTIAL','COMPLETED'),
  ('booking',1,'COMPENSATING','FAILED'), ('booking',1,'COMPENSATING','CANCELED'),
  ('booking',1,'CONFIRMED','CANCEL_PENDING'), ('booking',1,'CONFIRMED','COMPLETED'),
  ('booking',1,'CANCEL_PENDING','CANCELED'), ('booking',1,'CANCEL_PENDING','RECONCILING'),
-- Zahlung (Kap. 03 §4.2); DISPUTED ist ein eigener Streitstatus, nicht Teil dieses Automaten
  ('payment',1,'CREATED','REQUIRES_ACTION'), ('payment',1,'CREATED','AUTHORIZED'), ('payment',1,'CREATED','AUTHORIZATION_FAILED'),
  ('payment',1,'REQUIRES_ACTION','AUTHORIZED'), ('payment',1,'REQUIRES_ACTION','AUTHORIZATION_FAILED'),
  ('payment',1,'AUTHORIZED','CAPTURE_PENDING'), ('payment',1,'AUTHORIZED','VOID_PENDING'),
  ('payment',1,'CAPTURE_PENDING','CAPTURED'), ('payment',1,'CAPTURE_PENDING','CAPTURE_UNKNOWN'),
  ('payment',1,'CAPTURE_UNKNOWN','RECONCILING'),
  ('payment',1,'RECONCILING','CAPTURED'), ('payment',1,'RECONCILING','AUTHORIZED'), ('payment',1,'RECONCILING','VOID_PENDING'),
  ('payment',1,'VOID_PENDING','VOIDED'),
  ('payment',1,'CAPTURED','REFUND_PENDING'), ('payment',1,'REFUND_PENDING','PARTIALLY_REFUNDED'), ('payment',1,'REFUND_PENDING','REFUNDED'),
  ('payment',1,'PARTIALLY_REFUNDED','REFUND_PENDING');

-- Anwendung in M1: operation.status (bisher ohne CHECK) und tenant.status (CHECK bestand; jetzt zusätzlich die Übergangsregeln).
SELECT harden_state_column('taktovia.operation', 'status', 'operation');
SELECT harden_state_column('taktovia.tenant', 'status', 'tenant');
