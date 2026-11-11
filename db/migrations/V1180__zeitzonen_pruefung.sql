SET search_path = taktovia, public;
-- WP-M1-18 / LH-6.1-SYS-006: service.timezone und staff_shift.timezone sind IANA-Zonen.
-- Zulaessig sind `UTC` und `Gebiet/Ort` (kein `Etc/...`, keine Abkuerzungen wie CET, keine festen Offsets). Dass die Zone in der
-- Zeitzonen-Datenbank der Instanz existiert, prueft `AT TIME ZONE`: eine unbekannte Zone bricht mit SQLSTATE 22023 ab.
-- Keine eigene Funktion, damit das Schema taktovia keine zusaetzlichen Objekte erhaelt. Es gibt noch keine Zeilen; die Pruefung gilt ab jetzt.
ALTER TABLE service ADD CONSTRAINT service_timezone_iana
  CHECK (timezone ~ '^(UTC|[A-Z][A-Za-z_-]*(/[A-Za-z0-9_+-]+)+)$' AND timezone NOT LIKE 'Etc/%' AND (now() AT TIME ZONE timezone) IS NOT NULL);
ALTER TABLE staff_shift ADD CONSTRAINT staff_shift_timezone_iana
  CHECK (timezone ~ '^(UTC|[A-Z][A-Za-z_-]*(/[A-Za-z0-9_+-]+)+)$' AND timezone NOT LIKE 'Etc/%' AND (now() AT TIME ZONE timezone) IS NOT NULL);
