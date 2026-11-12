-- WP-M2-22 / LH-6.5-OPT-009: Zustandsautomat des Holds (`vertraege/zustaende/hold.md`) als Übergangsregeln plus CHECK, auch gegen direktes SQL:
-- HELD -> CONSUMED | RELEASED | EXPIRED; CONSUMED, RELEASED und EXPIRED sind endgültig. Außerdem sind Kapazität und Frist eines Holds nach dem Anlegen unveränderlich
-- (Vektor, Umlauf, Revision, Abschnitte, `expires_at`, Referenz): eine Hold-Dauer wird in P1 nicht verlängert (OP-47), ein Hold wird nie umgedeutet.
SET search_path = taktovia, public;

INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('capacity_hold',1,'HELD','CONSUMED'), ('capacity_hold',1,'HELD','RELEASED'), ('capacity_hold',1,'HELD','EXPIRED');
SELECT harden_state_column('taktovia.capacity_hold', 'state', 'capacity_hold');

CREATE FUNCTION capacity_hold_core_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF (NEW.run_id, NEW.run_revision, NEW.federation_ref, NEW.capacity_vector, NEW.interval_from, NEW.interval_to, NEW.expires_at)
     IS DISTINCT FROM (OLD.run_id, OLD.run_revision, OLD.federation_ref, OLD.capacity_vector, OLD.interval_from, OLD.interval_to, OLD.expires_at) THEN
    RAISE EXCEPTION 'capacity_hold capacity and expiry are immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER capacity_hold_core_immutable BEFORE UPDATE ON capacity_hold FOR EACH ROW EXECUTE FUNCTION capacity_hold_core_guard();
