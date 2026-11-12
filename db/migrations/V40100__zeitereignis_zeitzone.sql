-- WP-M4-10 / LH-6.24-TIM-005: Zeitereignis mit Zeitpunkt plus Zone. `occurred_at` bleibt der absolute Zeitpunkt (timestamptz); `timezone` ist die IANA-Zone, in der das Ereignis lokal gelesen wird
-- (Anzeige, Betriebstag). Keine lokale Zeit ohne Zone/Offset wird gespeichert. Altzeilen und Erfassungen ohne Angabe erhalten die Plattformzone Europe/Berlin, bis reporting_timezone je Mandant gelesen wird.
-- Prüfung wie bei service.timezone und staff_shift.timezone (V1180): Form `UTC` oder `Gebiet/Ort`, kein `Etc/…`; die Existenz der Zone prüft `AT TIME ZONE` (unbekannt: SQLSTATE 22023).
SET search_path = taktovia, public;

ALTER TABLE time_event ADD COLUMN timezone text NOT NULL DEFAULT 'Europe/Berlin';
ALTER TABLE time_event ADD CONSTRAINT time_event_timezone_iana
  CHECK (timezone ~ '^(UTC|[A-Z][A-Za-z_-]*(/[A-Za-z0-9_+-]+)+)$' AND timezone NOT LIKE 'Etc/%' AND (now() AT TIME ZONE timezone) IS NOT NULL);
