-- WP-M2-31: Rechte für refund_case (hinter R__runtime_grants.sql sortiert). Anlegen, lesen, fortschreiben (Zustand, bestätigter Betrag); kein DELETE.
GRANT SELECT, INSERT, UPDATE ON taktovia.refund_case TO taktovia_runtime;
