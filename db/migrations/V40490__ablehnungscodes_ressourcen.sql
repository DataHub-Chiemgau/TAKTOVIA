-- WP-M4-49 / LH-I.6-KPI-003: die fünf ressourcenbezogenen Ablehnungsgründe mit unterschiedlichen Codes: keine Kapazität (NO_CAPACITY, bestand), kein Fahrzeug (NO_VEHICLE),
-- kein Fahrer (NO_STAFF, bestand), Schichtende (SHIFT_ENDED), falsche Ausstattung (EQUIPMENT_MISSING, bestand). Alle erscheinen in derselben Berichtsspalte „Ablehnungsgrund“.
-- Zuordnung zu den Katalogkennzahlen (Arbeitsannahme OP-25): keine Kapazität und kein Fahrzeug zu Kapazitätsablehnungen, kein Fahrer und Schichtende zu Personalmangelablehnungen, falsche Ausstattung zu Ausstattungsablehnungen.
-- Rangfolge bei mehreren zutreffenden Gründen = Priorität (kleinste Zahl gewinnt, wie im Katalog): kein Fahrzeug vor Ausstattung vor kein Fahrer vor Schichtende vor keine Kapazität.
SET search_path = taktovia, public;

INSERT INTO rejection_reason (code, priority, counts_as_rejection, metric, label, next_action, reason_group, texts) VALUES
  ('NO_VEHICLE', 85, true, 'CAPACITY', 'Zur gewünschten Zeit steht im Bediengebiet kein Fahrzeug zur Verfügung.', 'Wählen Sie eine andere Zeit.', 'CAPACITY',
   '{"en": "No vehicle is available in the service area at the requested time."}'::jsonb),
  ('SHIFT_ENDED', 105, true, 'PERSONNEL', 'Die Schicht des verfügbaren Fahrpersonals endet vor der gewünschten Fahrt.', 'Wählen Sie eine frühere Zeit.', 'PERSONNEL',
   '{"en": "The shift of the available driver ends before the requested trip."}'::jsonb);
