-- WP-M3-22: Fahrzeugwechsel. Die Wechselhistorie wird nur angehängt und gelesen (Trigger gegen UPDATE/DELETE); die Qualifikationszuordnung je Fahrzeugtyp liest die Runtime nur
-- (Pflege durch den Betrieb bzw. die spätere Konfiguration, Anforderungsmatrix offen).
GRANT SELECT, INSERT ON taktovia.shift_vehicle_switch TO taktovia_runtime;
GRANT SELECT ON taktovia.vehicle_type_qualification TO taktovia_runtime;
