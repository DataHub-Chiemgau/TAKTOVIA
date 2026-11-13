-- WP-M4-47 / LH-I.6-KPI-001: Versuchseintrag je Fahrtanfrage in der Faktenbasis. Je attempt_id genau ein Fakt (Primärschlüssel); ein technischer Retry trägt dieselbe attempt_id (höhere Revision) und zählt nicht neu.
-- is_test: Testanfragen sind aus den Kennzahlen ausgeschlossen (Abgrenzung mit M2 offen, Arbeitsannahme: das Ereignis trägt `is_test`). secondary_reasons: Nebengründe als Mehrfachlabels (WP-M4-48).
-- Reiner Zusatz an der abgeleiteten Tabelle: bestehende Fakten bleiben gültig (Defaults), der Wiederaufbau füllt die Spalten aus dem Eingang.
SET search_path = taktovia, public;

ALTER TABLE fact_attempt
  ADD COLUMN is_test           boolean NOT NULL DEFAULT false,
  ADD COLUMN secondary_reasons text[]  NOT NULL DEFAULT '{}';
CREATE INDEX fact_attempt_by_requested ON fact_attempt (tenant_id, requested_at);
