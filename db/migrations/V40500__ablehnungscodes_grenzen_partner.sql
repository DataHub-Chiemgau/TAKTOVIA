-- WP-M4-50 / LH-I.6-KPI-004: die weiteren Ablehnungsgründe Bediengebiet (OUTSIDE_AREA), Betriebszeit (OUTSIDE_HOURS), Mindestvorlauf (LEAD_TIME), Maximalwartezeit (MAX_WAIT_EXCEEDED),
-- Umweggrenze (MAX_DETOUR_EXCEEDED), Partnerausfall (PARTNER_UNAVAILABLE) und technischer Fehler (TECHNICAL). Die Arbeitsnamen des Detailvertrags entsprechen den Codes des M2-Katalogs
-- (AREA_NOT_SERVED = OUTSIDE_AREA, OUTSIDE_OPERATING_HOURS = OUTSIDE_HOURS, MIN_LEAD_NOT_MET = LEAD_TIME, TECHNICAL_ERROR = TECHNICAL); neu sind die drei übrigen.
-- Kennzahl PARTNER (Technische Partnerablehnungen) nur als Definition: in P1 gibt es keine Partner (OP 26), der Code ist katalogisiert und wird nur per Testinjektion erzeugt.
SET search_path = taktovia, public;

ALTER TABLE rejection_reason DROP CONSTRAINT rejection_reason_metric_check;
ALTER TABLE rejection_reason ADD CONSTRAINT rejection_reason_metric_check CHECK (metric IN ('CAPACITY', 'PERSONNEL', 'EQUIPMENT', 'AREA', 'TIME_WINDOW', 'PARTNER'));

INSERT INTO rejection_reason (code, priority, counts_as_rejection, metric, label, next_action, reason_group, texts) VALUES
  ('MAX_WAIT_EXCEEDED', 35, true, 'TIME_WINDOW', 'Die Wartezeit bis zur Abholung würde die zulässige Maximalwartezeit überschreiten.', 'Wählen Sie eine spätere Abfahrtszeit.', 'TIME_DEADLINE',
   '{"en": "The wait until pickup would exceed the permitted maximum wait time."}'::jsonb),
  ('MAX_DETOUR_EXCEEDED', 115, true, NULL, 'Die Fahrt würde die zulässige Umweggrenze überschreiten.', 'Wählen Sie eine andere Zeit oder ein anderes Ziel.', 'LIMIT',
   '{"en": "The trip would exceed the permitted detour limit."}'::jsonb),
  ('PARTNER_UNAVAILABLE', 120, true, 'PARTNER', 'Der Partnerbetrieb für diese Verbindung ist nicht erreichbar.', 'Versuchen Sie es später erneut oder wählen Sie eine andere Verbindung.', 'PARTNER',
   '{"en": "The partner operator for this connection is unavailable."}'::jsonb);
