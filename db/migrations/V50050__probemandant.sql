-- WP-M5-05 / LH-6.19-NFR-001: Probemandant der synthetischen Verfügbarkeitsproben.
-- OP-03 (Ausschlussregel für Probebuchungen im Pflichtenheft nicht festgelegt) ist als Arbeitsregel so entschieden: Die Probenkennzeichnung ist ein Mandantenmerkmal
-- (`tenant.synthetic`), nicht eine Spalte an jeder Fachtabelle. Der Probemandant hat eigenen Dienst, eigenes Probefahrzeug und eigene Umläufe; durch Primärschlüssel (tenant_id, id),
-- Fremdschlüssel und RLS kann eine Probebuchung nie Kapazität eines Echtmandanten belegen (und umgekehrt). Zusätzlich gelangen Ereignisse und Fakten eines synthetischen Mandanten nie
-- in die Faktenbasis der Kennzahlen: Anfragen, Buchungskonversion und alle übrigen Kennzahlen sehen sie nicht (Trigger unten, auch als zweite Verteidigungslinie zu `fact_attempt.is_test`).
SET search_path = taktovia, public;

ALTER TABLE tenant ADD COLUMN synthetic boolean NOT NULL DEFAULT false;

-- Die Kennzeichnung ist endgültig: ein Echtmandant wird nie nachträglich zum Probemandanten (seine Daten wären aus den Kennzahlen verschwunden) und umgekehrt.
CREATE FUNCTION tenant_synthetic_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.synthetic IS DISTINCT FROM OLD.synthetic THEN
    RAISE EXCEPTION 'tenant.synthetic is immutable (probe tenant marking is final)' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tenant_synthetic_immutable BEFORE UPDATE ON tenant FOR EACH ROW EXECUTE FUNCTION tenant_synthetic_guard();

-- Zeilen eines synthetischen Mandanten werden in Analytics-Tabellen nicht gespeichert. Die Funktion läuft als Aufrufer (kein SECURITY DEFINER im Schema, Konvention): die Runtime-Rolle sieht
-- die Zeile des eigenen Mandanten (RLS own_tenant), die Modulrollen bekommen dafür nur das Spaltenrecht auf `tenant(id, synthetic)` (R__zz_modulzugaenge_m4_64.sql).
CREATE FUNCTION skip_synthetic_tenant_row() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM taktovia.tenant t WHERE t.id = NEW.tenant_id AND t.synthetic) THEN
    RETURN NULL;
  END IF;
  RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['analytics_event_inbox', 'fact_attempt', 'fact_offer', 'fact_booking', 'fact_stop_event', 'fact_vehicle_state'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE INSERT ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.skip_synthetic_tenant_row()', t || '_skip_synthetic', t);
  END LOOP;
END $$;
