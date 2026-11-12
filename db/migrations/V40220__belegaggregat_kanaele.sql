-- WP-M4-22 (LH-I.1-DOC-006): Belegaggregat mit einheitlicher Struktur für vier Eingangskanäle (Modul documents-finance).
-- Herkunftskanal, Belegart und Vorgangsbezug am Ticket UND am Beleg; die Eingangszeit ist `original_received_at` (V1, NOT NULL). Alle Kanäle laufen über upload_ticket und die Quarantäne.
-- expand: bestehende Zeilen (Altbestand, Fixtures) erhalten gültige Vorgabewerte; die Anwendung setzt Kanal und Belegart immer ausdrücklich.
SET search_path = taktovia, public;

ALTER TABLE upload_ticket
  ADD COLUMN source_channel  text    NOT NULL DEFAULT 'DISPATCH_WEB' CONSTRAINT upload_ticket_channel CHECK (source_channel IN ('DRIVER_APP', 'DISPATCH_WEB', 'EMAIL_INBOUND', 'INVOICE_IMPORT')),
  ADD COLUMN document_kind   text    NOT NULL DEFAULT 'RECEIPT' CONSTRAINT upload_ticket_kind CHECK (document_kind IN ('RECEIPT', 'FUEL_RECEIPT', 'INVOICE', 'OTHER')),
  ADD COLUMN case_ref_kind   text    CONSTRAINT upload_ticket_case_kind CHECK (case_ref_kind IN ('VEHICLE', 'SHIFT')),
  ADD COLUMN case_ref_id     uuid,
  ADD COLUMN source_ref      text    CONSTRAINT upload_ticket_source_ref CHECK (length(source_ref) BETWEEN 1 AND 300),   -- Nachrichtenkennung (E-Mail) bzw. Importkennung; Grundlage der Zustelldeduplikation
  ADD COLUMN sender_verified boolean,                                                                                  -- nur E-Mail-Eingang: Absenderdomäne verifiziert?
  ADD CONSTRAINT upload_ticket_case_ref_pair CHECK ((case_ref_kind IS NULL) = (case_ref_id IS NULL)),
  ADD CONSTRAINT upload_ticket_sender_only_email CHECK ((source_channel = 'EMAIL_INBOUND') = (sender_verified IS NOT NULL));
-- Dieselbe Quelle (Nachricht/Import) erzeugt höchstens ein Ticket; ein abgewiesenes Ticket gibt die Quelle wieder frei (defekte Datei, neu zustellbar).
CREATE UNIQUE INDEX upload_ticket_one_per_source ON upload_ticket (tenant_id, source_channel, source_ref) WHERE source_ref IS NOT NULL AND state <> 'REJECTED';

ALTER TABLE expense_document
  ADD COLUMN source_channel  text    NOT NULL DEFAULT 'DISPATCH_WEB' CONSTRAINT expense_document_channel CHECK (source_channel IN ('DRIVER_APP', 'DISPATCH_WEB', 'EMAIL_INBOUND', 'INVOICE_IMPORT')),
  ADD COLUMN document_kind   text    NOT NULL DEFAULT 'RECEIPT' CONSTRAINT expense_document_kind CHECK (document_kind IN ('RECEIPT', 'FUEL_RECEIPT', 'INVOICE', 'OTHER')),
  ADD COLUMN case_ref_kind   text    CONSTRAINT expense_document_case_kind CHECK (case_ref_kind IN ('VEHICLE', 'SHIFT')),
  ADD COLUMN case_ref_id     uuid,
  ADD COLUMN source_ref      text    CONSTRAINT expense_document_source_ref CHECK (length(source_ref) BETWEEN 1 AND 300),
  ADD COLUMN sender_verified boolean,
  ADD CONSTRAINT expense_document_case_ref_pair CHECK ((case_ref_kind IS NULL) = (case_ref_id IS NULL)),
  ADD CONSTRAINT expense_document_sender_only_email CHECK ((source_channel = 'EMAIL_INBOUND') = (sender_verified IS NOT NULL) OR upload_ticket_id IS NULL);
CREATE UNIQUE INDEX expense_document_one_per_source ON expense_document (tenant_id, source_channel, source_ref) WHERE source_ref IS NOT NULL;
CREATE INDEX expense_document_by_channel ON expense_document (tenant_id, source_channel, original_received_at);

-- Kanal, Belegart, Quelle und Absenderprüfung eines über ein Ticket erfassten Belegs sind unveränderlich (der Vorgangsbezug darf später zugeordnet werden, WP-M4-29).
CREATE FUNCTION expense_document_channel_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.upload_ticket_id IS NOT NULL AND (NEW.source_channel IS DISTINCT FROM OLD.source_channel OR NEW.document_kind IS DISTINCT FROM OLD.document_kind
     OR NEW.source_ref IS DISTINCT FROM OLD.source_ref OR NEW.sender_verified IS DISTINCT FROM OLD.sender_verified) THEN
    RAISE EXCEPTION 'expense_document: Herkunftskanal, Belegart und Quelle sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_channel_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_channel_guard();

-- Ticket: die neuen Sachfelder sind ebenfalls unveränderlich (der Wächter aus V40210 kennt sie noch nicht).
CREATE FUNCTION upload_ticket_channel_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.source_channel IS DISTINCT FROM OLD.source_channel OR NEW.document_kind IS DISTINCT FROM OLD.document_kind OR NEW.case_ref_kind IS DISTINCT FROM OLD.case_ref_kind
     OR NEW.case_ref_id IS DISTINCT FROM OLD.case_ref_id OR NEW.source_ref IS DISTINCT FROM OLD.source_ref OR NEW.sender_verified IS DISTINCT FROM OLD.sender_verified THEN
    RAISE EXCEPTION 'upload_ticket: Kanal, Belegart, Vorgangsbezug und Quelle sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER upload_ticket_channel_guard BEFORE UPDATE ON upload_ticket FOR EACH ROW EXECUTE FUNCTION upload_ticket_channel_guard();

-- Mandantenbezogene Beleg-Einstellungen. `receipt_ocr_enabled` und `invoice_email_inbound` folgen dem Katalog LH-J.5; solange der Parameterkatalog (policy_schema, Modul config) diese
-- Parameter nicht führt, liegen sie hier (Abweichung, siehe vertraege/m4-cluster-c10.md). Ohne Zeile gilt: OCR an, kein E-Mail-Eingang.
CREATE TABLE document_settings (
  tenant_id              uuid        NOT NULL REFERENCES tenant(id),
  receipt_ocr_enabled    boolean     NOT NULL DEFAULT true,
  invoice_email_inbound  text        CONSTRAINT document_settings_inbound_address CHECK (invoice_email_inbound ~ '^[a-z0-9._%+-]{1,64}@[a-z0-9.-]{1,200}\.[a-z]{2,}$'),
  inbound_legal_entity_id uuid,                              -- Rechtsträger, dem Belege aus dem E-Mail-Eingang zugeordnet werden
  verified_sender_domains text[]     NOT NULL DEFAULT '{}',   -- Absenderdomänen, deren Verifikation abgeschlossen ist (Verfahren: WP-M4 Offener Punkt 12)
  revision               bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_by             text        NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 200),
  PRIMARY KEY (tenant_id),
  FOREIGN KEY (tenant_id, inbound_legal_entity_id) REFERENCES legal_entity(tenant_id, id)
);
ALTER TABLE document_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_settings USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON document_settings FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
