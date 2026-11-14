-- WP-M3-18 / LH-6.21-CHK-009: Technikbereitschaft vor Schichtbeginn und Technikereignis.
-- 1. Neuer Vorlagentyp TECHNICAL_READINESS mit genau vier Pflichtpunkten (Fahrer-App, Mobilfunkverbindung, Navigation, dienstliches Zubehör) und neue Lauf-Art TECH_READINESS
--    (Technikbereitschaft). Der Lauf erteilt keine Einsatzfreigabe und sperrt nie (Annahme laut Detailvertrag: Technikfehler erzeugt Ereignis und Hinweis, sperrt aber nicht).
-- 2. tech_event: je Mangel-Antwort eines Technikpunktes höchstens ein Technikereignis (UNIQUE über Prüflauf und Schritt, unveränderlich), mit Bezug auf Prüflauf, Fahrzeug und Schicht.
--    Der Wächter hält zusätzlich, dass das Ereignis zu einem Lauf der Art TECH_READINESS, zu Fahrzeug und Schicht dieses Laufs und zu einer vorhandenen Mangel-Antwort gehört.
--    Der Leitstellenhinweis ist der Alarm TECHNICAL_EVENT (Quellereignis = tech_event.id, Dedupe in business_alert); die Tabelle trägt keine Freitexte, keine Fahrgastdaten, keine Diagnosen.
SET search_path = taktovia, public;

ALTER TABLE checklist_template_revision DROP CONSTRAINT checklist_template_revision_template_type_check;
ALTER TABLE checklist_template_revision ADD CONSTRAINT checklist_template_revision_template_type_check
  CHECK (template_type IN ('GENERAL', 'VEHICLE_TECHNICAL', 'PASSENGER_COMPARTMENT', 'TECHNICAL_READINESS'));

CREATE OR REPLACE FUNCTION checklist_mandatory_keys(p_type text) RETURNS text[] LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT CASE p_type
    WHEN 'VEHICLE_TECHNICAL' THEN ARRAY['BELEUCHTUNG', 'REIFEN_SICHTPRUEFUNG', 'SCHEIBEN', 'SPIEGEL', 'TUEREN', 'SICHERHEITSGURTE', 'SICHTBARE_LECKAGEN', 'WARNANZEIGEN']
    WHEN 'PASSENGER_COMPARTMENT' THEN ARRAY['SAUBERKEIT', 'FAHRGASTRAUM', 'SITZPLAETZE', 'EINSTIEGSBEREICH', 'RUECKHALTESYSTEME', 'SICHERHEITSAUSRUESTUNG']
    WHEN 'TECHNICAL_READINESS' THEN ARRAY['FAHRER_APP', 'MOBILFUNK', 'NAVIGATION', 'DIENSTZUBEHOER']
    ELSE ARRAY[]::text[] END
$$;

ALTER TABLE inspection_run DROP CONSTRAINT inspection_run_kind_check;
ALTER TABLE inspection_run ADD CONSTRAINT inspection_run_kind_check CHECK (kind IN ('PRE_SHIFT', 'FOLLOW_UP', 'VEHICLE_CHANGE', 'PRE_BOARDING', 'TECH_READINESS'));

CREATE TABLE tech_event (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  inspection_id uuid        NOT NULL,
  step_code     text        NOT NULL CHECK (step_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  vehicle_id    uuid        NOT NULL,
  shift_id      uuid        NOT NULL,
  service_id    uuid,
  created_by    text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT tech_event_once_per_step UNIQUE (tenant_id, inspection_id, step_code),
  FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id) REFERENCES staff_shift(tenant_id, id)
);
CREATE INDEX tech_event_by_vehicle ON tech_event (tenant_id, vehicle_id, created_at);

ALTER TABLE tech_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE tech_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tech_event USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON tech_event FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
CREATE TRIGGER tech_event_immutable BEFORE UPDATE OR DELETE ON tech_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION tech_event_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE r_kind text; r_vehicle uuid; r_shift uuid;
BEGIN
  SELECT kind, vehicle_id, shift_id INTO r_kind, r_vehicle, r_shift FROM inspection_run WHERE tenant_id = NEW.tenant_id AND id = NEW.inspection_id;
  IF r_kind IS DISTINCT FROM 'TECH_READINESS' THEN
    RAISE EXCEPTION 'tech_event: nur fuer einen Pruefeinlauf der Art TECH_READINESS' USING ERRCODE = '23514';
  END IF;
  IF r_vehicle IS DISTINCT FROM NEW.vehicle_id OR r_shift IS DISTINCT FROM NEW.shift_id THEN
    RAISE EXCEPTION 'tech_event: Fahrzeug und Schicht muessen die des Pruefeinlaufs sein' USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM inspection_answer a WHERE a.tenant_id = NEW.tenant_id AND a.inspection_id = NEW.inspection_id AND a.step_code = NEW.step_code AND a.answer = 'DEFECT') THEN
    RAISE EXCEPTION 'tech_event: ohne Mangel-Antwort zum Technikpunkt kein Technikereignis' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tech_event_guard BEFORE INSERT ON tech_event FOR EACH ROW EXECUTE FUNCTION tech_event_guard();
