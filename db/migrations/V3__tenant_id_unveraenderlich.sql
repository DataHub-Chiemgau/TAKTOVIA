SET search_path = taktovia, public;
-- WP-M1-01 / Kap. 02 §4.2: tenant_id ist nach dem Anlegen unveränderlich. RLS (WITH CHECK) verhindert bereits das Umhängen
-- auf einen fremden Mandanten; der Trigger schützt zusätzlich Wartungs- und Administratorpfade.
CREATE FUNCTION reject_tenant_change() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
 IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
  RAISE EXCEPTION 'tenant_id is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
DO $$ DECLARE t record; BEGIN
 FOR t IN SELECT c.table_name FROM information_schema.columns c
          JOIN information_schema.tables x ON x.table_schema=c.table_schema AND x.table_name=c.table_name AND x.table_type='BASE TABLE'
          WHERE c.table_schema='taktovia' AND c.column_name='tenant_id' LOOP
  EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t.table_name);
 END LOOP;
END $$;
