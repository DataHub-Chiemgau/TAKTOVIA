-- WP-M3-15 / LH-6.21-CHK-014: Prüfentwurf und versionierte Korrektureinträge.
-- Antworten sind bis zum Abschluss änderbar (Wächter inspection_answer_guard, V3020); danach sind nur Nachträge möglich. inspection_amendment (V3020, append-only) bekommt:
-- Bezug auf die Originalantwort (answer_id), Version je Originalantwort (UNIQUE (Mandant, Originalantwort, Version): Doppelvergabe ist unmöglich), Art (Korrektur oder Ergänzung)
-- und die nachgetragenen Werte (Zahl, Notiz). Grund (reason) ist bereits Pflicht (NOT NULL, hier zusätzlich nicht leer). Die Originalzeile wird nie verändert.
-- Wächter: ein Nachtrag gehört nur zu einem abgeschlossenen Lauf und zu einer Antwort genau dieses Laufs und Schritts. Die Tabelle wurde bisher von keinem Code beschrieben.
SET search_path = taktovia, public;

ALTER TABLE inspection_amendment
  ADD COLUMN answer_id        uuid NOT NULL,
  ADD COLUMN version          int  NOT NULL CHECK (version >= 1),
  ADD COLUMN kind             text NOT NULL CHECK (kind IN ('CORRECTION', 'SUPPLEMENT')),
  ADD COLUMN new_number_value numeric,
  ADD COLUMN new_note         text CHECK (new_note IS NULL OR length(new_note) <= 500),
  ADD CONSTRAINT inspection_amendment_answer_fk FOREIGN KEY (tenant_id, answer_id) REFERENCES inspection_answer(tenant_id, id),
  ADD CONSTRAINT inspection_amendment_version_unique UNIQUE (tenant_id, answer_id, version),
  ADD CONSTRAINT inspection_amendment_reason_not_blank CHECK (length(btrim(reason)) >= 1);
CREATE INDEX inspection_amendment_by_inspection ON inspection_amendment (tenant_id, inspection_id, step_code, version);

CREATE FUNCTION inspection_amendment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE st text;
BEGIN
  SELECT r.status INTO st FROM inspection_run r WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_id;
  IF st IS DISTINCT FROM 'COMPLETED' THEN
    RAISE EXCEPTION 'an amendment belongs to a completed inspection run; before completion the draft is edited' USING ERRCODE = '23514';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM inspection_answer a WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.answer_id AND a.inspection_id = NEW.inspection_id AND a.step_code = NEW.step_code) THEN
    RAISE EXCEPTION 'an amendment must refer to the original answer of the same inspection run and step' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_amendment_guard BEFORE INSERT ON inspection_amendment FOR EACH ROW EXECUTE FUNCTION inspection_amendment_guard();
