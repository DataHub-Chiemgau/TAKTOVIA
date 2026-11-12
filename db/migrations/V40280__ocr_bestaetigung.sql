-- WP-M4-28 (LH-I.1-DOC-003): OCR als unbestätigter Vorschlag mit Bestätigungspflicht (Modul documents-finance).
-- (1) extracted_field.flag_reason: Markierung eines OCR-Vorschlags (niedrige Konfidenz, Summenabweichung Netto + Steuer <> Brutto); nur am unveränderlichen Vorschlag, nie an menschlichen Entscheidungen.
-- (2) Feldart NET_AMOUNT (Nettobetrag) für die Summenprüfung.
-- (3) document_settings.receipt_mandatory_fields: Pflichtfelder je Mandant; die steuerlich notwendigen Felder sind geschützt und nie entfernbar.
SET search_path = taktovia, public;

ALTER TABLE extracted_field DROP CONSTRAINT extracted_field_field_kind_check;
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_field_kind_check CHECK (field_kind IN ('DOCUMENT_DATE', 'AMOUNT', 'CURRENCY', 'MERCHANT', 'DOCUMENT_NUMBER', 'VAT_AMOUNT', 'NET_AMOUNT'));

ALTER TABLE extracted_field ADD COLUMN flag_reason text CONSTRAINT extracted_field_flag_reason CHECK (flag_reason IN ('LOW_CONFIDENCE', 'SUM_MISMATCH'));
ALTER TABLE extracted_field ADD CONSTRAINT extracted_field_flag_only_on_proposal CHECK (flag_reason IS NULL OR origin = 'OCR');

ALTER TABLE document_settings ADD COLUMN receipt_mandatory_fields text[] NOT NULL DEFAULT ARRAY['amount_minor', 'currency', 'document_date', 'merchant']
  CONSTRAINT document_settings_mandatory_known CHECK (receipt_mandatory_fields <@ ARRAY['amount_minor', 'currency', 'document_date', 'merchant', 'document_number', 'vat_amounts', 'net_amount_minor']),
  ADD CONSTRAINT document_settings_mandatory_protected CHECK (receipt_mandatory_fields @> ARRAY['amount_minor', 'currency', 'document_date', 'merchant']);   -- steuerlich notwendige Felder (Betrag, Währung, Datum, Leistender)
