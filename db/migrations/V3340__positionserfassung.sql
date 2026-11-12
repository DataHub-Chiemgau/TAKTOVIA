-- WP-M3-34: Positionserfassung und Standortalter. position_sample (V3020) speichert Messzeit (Gerät, `measured_at`) und Empfangszeit (Server, `received_at`) getrennt;
-- „letzte Position“ ist die jüngste Messzeit je Fahrzeug (Index position_sample_latest). Neu: eindeutige Messzeit je Schicht, damit eine erneute Sendung des Geräts
-- (Wiederverbindung, Wiederholung der Outbox) keine zweite Zeile erzeugt (INSERT ... ON CONFLICT DO NOTHING, auch bei paralleler Wiederholung genau eine Zeile).
-- Geschwindigkeit und Fahrtrichtung werden nicht gespeichert (Minimierung: kein Zweck in M3, Datenminimierung D-10). Die Löschung nach Ablauf der Frist des
-- Aufbewahrungsregisters (LK_STANDORT, WP-M1-33) erledigt der Löschlauf mit DELETE auf diese Tabelle (R__runtime_grants_m3_34.sql); UPDATE bleibt per Trigger gesperrt.
SET search_path = taktovia, public;

CREATE UNIQUE INDEX position_sample_dedupe ON position_sample (tenant_id, shift_id, measured_at);
