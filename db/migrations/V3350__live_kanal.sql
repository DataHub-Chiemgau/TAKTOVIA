-- WP-M3-35: Live-Aktualisierungskanal für Leitstelle und Fahrer (Detailvertrag m3-live-alarm.md).
-- `live_event` ist die adressierbare, referenzarme Projektion der Outbox-Ereignisse für den Push-Kanal (SSE/Abruf): je Outbox-Zeile höchstens eine Zeile, in DERSELBEN
-- Transaktion durch den Trigger `outbox_live`. Der Kanal liest nur diese Tabelle, nie `outbox.payload`: es gibt ausschließlich Referenzen (Aggregat, Revision, Dienst, Schicht)
-- und eine Positivliste skalarer Statusfelder (Codes, Zahlen, Kennungen), keine Freitexte, Namen, Adressen oder Koordinaten.
-- Cursor: (tx, seq). `seq` ist nicht in Commit-Reihenfolge; `tx` ist die Transaktionsnummer, und gelesen werden nur Zeilen unterhalb des Snapshot-Horizonts (`xmin`):
-- alle älteren Transaktionen sind dann beendet, ein später sichtbar werdender Eintrag kann also nie hinter einem bereits ausgelieferten Cursor liegen.
SET search_path = taktovia, public;

CREATE TABLE live_event (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  seq                bigint      GENERATED ALWAYS AS IDENTITY,
  tx                 bigint      NOT NULL DEFAULT (pg_current_xact_id()::text)::bigint,
  event_id           uuid        NOT NULL,
  event_type         text        NOT NULL CHECK (event_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
  topic              text        NOT NULL CHECK (topic ~ '^[a-z][a-z0-9_]*$'),
  aggregate_id       uuid        NOT NULL,
  aggregate_revision bigint      NOT NULL CHECK (aggregate_revision >= 1),
  service_id         uuid,
  shift_id           uuid,
  status             jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(status) = 'object'),
  occurred_at        timestamptz NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, seq),
  UNIQUE (tenant_id, event_id)
);
CREATE INDEX live_event_order ON live_event (tenant_id, tx, seq);
CREATE INDEX live_event_age ON live_event (created_at);

ALTER TABLE live_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE live_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON live_event USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON live_event FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
-- unveränderlich; Löschen nur für die Bereinigung alter Zeilen (Aufbewahrung des Kanalpuffers, Stunden)
CREATE TRIGGER live_event_immutable BEFORE UPDATE ON live_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Schreiben nur aus dem Outbox-Trigger (Triggertiefe >= 2): kein Fachcode und kein SQL der Laufzeitrolle kann Kanalereignisse erfinden. (Konvention: keine SECURITY-DEFINER-Funktionen,
-- test_06_konventionen; deshalb braucht die Laufzeitrolle INSERT, das dieser Wächter auf den einen Pfad begrenzt.)
CREATE FUNCTION live_event_insert_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF pg_trigger_depth() < 2 THEN RAISE EXCEPTION 'live_event is written by the outbox projection only' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER live_event_insert_guard BEFORE INSERT ON live_event FOR EACH ROW EXECUTE FUNCTION live_event_insert_guard();

-- Projektion der Outbox-Zeile in der Transaktion des Ereignisses; die Mandantenrichtlinie gilt (FORCE), der Mandant ist der der laufenden Transaktion.
CREATE FUNCTION outbox_live_projection() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  v_service uuid; v_shift uuid; v_status jsonb := '{}'::jsonb; k text; v jsonb;
BEGIN
  -- nur fachliche Statusereignisse ohne Personenbezug (Positivliste der Ereignisfamilien)
  IF NEW.event_type !~ '^(dispatch|alert|incident|fleet|vehicle|inspection|defect|repair)\.' THEN RETURN NEW; END IF;
  IF (NEW.payload ->> 'service_id') ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN v_service := (NEW.payload ->> 'service_id')::uuid; END IF;
  FOREACH k IN ARRAY ARRAY['state', 'status', 'priority', 'alert_kind', 'revision', 'previous_revision', 'vehicle_id', 'run_id', 'shift_id', 'cause', 'severity', 'alert_id', 'escalation_level'] LOOP
    v := NEW.payload -> k;
    IF v IS NOT NULL AND (jsonb_typeof(v) IN ('number', 'boolean') OR (jsonb_typeof(v) = 'string' AND (v #>> '{}') ~ '^[A-Za-z0-9_.:-]{1,64}$')) THEN
      v_status := v_status || jsonb_build_object(k, v);
    END IF;
  END LOOP;
  IF NEW.event_type = 'dispatch.run_revised' THEN
    SELECT r.shift_id INTO v_shift FROM vehicle_run r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.aggregate_id;
  END IF;
  INSERT INTO live_event (tenant_id, event_id, event_type, topic, aggregate_id, aggregate_revision, service_id, shift_id, status, occurred_at)
  VALUES (NEW.tenant_id, NEW.event_id, NEW.event_type, split_part(NEW.event_type, '.', 1), NEW.aggregate_id, NEW.aggregate_revision, v_service, v_shift, v_status, NEW.occurred_at);
  RETURN NEW;
END $$;
CREATE TRIGGER outbox_live AFTER INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION outbox_live_projection();
